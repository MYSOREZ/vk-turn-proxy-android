package aiobfs

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"
)

const (
	headerLen = 12 // matches an RTP v2 fixed header with no extensions

	markerData  byte = 0x01
	markerDecoy byte = 0x00
	markerProbe byte = 0x02 // "please pong this ID back" — see autonomous.go
	markerPong  byte = 0x03 // "here is your ID back" — see autonomous.go

	autoStaleProbe = 3 * time.Second // how long an un-ponged probe counts as still-pending before drainAutoStats treats it as lost

	defaultMinDwell     = 4 * time.Second
	defaultLearningRate = 0.05
	defaultExploreRate  = 0.15
	defaultReplayWindow = 4096
)

// Config configures a Shaper.
type Config struct {
	// Key is used to derive a 32-byte AES-256-GCM key via SHA-256, so any
	// length passphrase or pre-shared key works. Both ends of a tunnel must
	// supply the same Key.
	Key []byte

	// Profiles overrides the built-in disguise set. Leave nil to use
	// StandardProfiles(). Both ends must use profile lists of the same
	// length in the same order, since arm index is the only thing that
	// travels implicitly (via which profile the wire shape matches) — the
	// index itself is never sent on the wire.
	Profiles []Profile

	// ExploreRate is EXP3's gamma (0,1]. Zero uses a sane default (0.15).
	ExploreRate float64

	// LearningRate is the policy network's step size. Zero uses a sane
	// default (0.05).
	LearningRate float64

	// MinDwell is the minimum time the shaper commits to a disguise before
	// it is allowed to switch again, even if the learners would prefer to
	// switch sooner. This bounds how "twitchy" the visible traffic shape
	// is; it does not limit how often profiles get re-evaluated (every
	// Observe call trains the learners regardless of dwell).
	MinDwell time.Duration

	// TargetThroughputBps is the throughput the caller considers "as fast
	// as this link can go" — used only to normalize the throughput term of
	// the reward signal passed to Observe. If zero, throughput is ignored
	// in the reward (RTT and loss still count).
	TargetThroughputBps float64

	// InitialProfile pins the starting disguise by name (matching a
	// Profile.Name from Profiles/StandardProfiles) instead of letting the
	// bandit pick uniformly at random. Mainly useful for tests and demos
	// that want a deterministic starting point; production use should
	// normally leave this empty so a fresh session doesn't always launch
	// with the same, therefore more fingerprintable, initial shape.
	InitialProfile string
}

// Shaper disguises tunnel packets as one of several real-time-media traffic
// profiles and continually re-weighs which disguise to use based on
// measured path performance, via the EXP3 bandit (bandit.go) biased by a
// small online-trained policy network (policy.go). See the package doc
// comment in profile.go for the overall design rationale.
type Shaper struct {
	aead cipher.AEAD

	profiles []Profile
	bandit   *exp3
	policy   *policyNet
	minDwell time.Duration
	target   float64

	currentIdx int32 // atomic index into profiles

	writeMu    sync.Mutex
	seq        uint16
	tsMicros   uint32
	ssrc       uint32
	packetSent uint64
	activePT   uint8
	// padDrift is a normalized (0..1) position in an autocorrelated
	// mean-reverting random walk, reinterpreted against whichever
	// profile's PaddingMax is currently active (see wrapInternal). Kept as
	// a single continuous process rather than reset per profile so the
	// underlying drift trajectory itself carries no discontinuity a
	// classifier could key on beyond what a real profile switch (PT/size
	// regime change) would already show.
	padDrift float64

	learnMu         sync.Mutex
	lastSwitch      time.Time
	haveRound       bool
	pendingArm      int
	pendingProb     float64
	pendingFeatures []float64
	pendingHidden   []float64
	pendingProbs    []float64

	replay *replayGuard
	rng    *pseudoRand

	// Autonomous self-monitoring state (see autonomous.go): SendProbe/
	// Unwrap/RunAutonomous let a Shaper measure its own RTT and loss via
	// self-generated probe/pong packets instead of requiring the host to
	// implement its own ping loop and call Observe() manually.
	autoMu       sync.Mutex
	nextProbeID  uint32
	pendingProbe map[uint32]time.Time
	autoRTTEWMA  float64
	autoHaveRTT  bool
	autoSent     int
	autoPonged   int
	pongQueue    chan []byte
}

// New builds a Shaper from cfg.
func New(cfg Config) (*Shaper, error) {
	if len(cfg.Key) == 0 {
		return nil, errors.New("aiobfs: Key is required")
	}
	profiles := cfg.Profiles
	if len(profiles) == 0 {
		profiles = StandardProfiles()
	}
	if len(profiles) < 1 {
		return nil, errors.New("aiobfs: at least one profile is required")
	}

	key := sha256.Sum256(cfg.Key)
	block, err := aes.NewCipher(key[:])
	if err != nil {
		return nil, fmt.Errorf("aiobfs: cipher init: %w", err)
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("aiobfs: GCM init: %w", err)
	}

	minDwell := cfg.MinDwell
	if minDwell <= 0 {
		minDwell = defaultMinDwell
	}
	learningRate := cfg.LearningRate
	if learningRate <= 0 {
		learningRate = defaultLearningRate
	}

	rng := newPseudoRand()
	var ssrcBuf [4]byte
	if _, err := rand.Read(ssrcBuf[:]); err != nil {
		return nil, fmt.Errorf("aiobfs: ssrc random: %w", err)
	}

	s := &Shaper{
		aead:       aead,
		profiles:   profiles,
		bandit:     newExp3(len(profiles), cfg.ExploreRate),
		policy:     newPolicyNet(len(profiles), learningRate),
		minDwell:   minDwell,
		target:     cfg.TargetThroughputBps,
		seq:        uint16(rng.intn(1 << 16)),
		tsMicros:   uint32(rng.intn(1 << 30)),
		ssrc:       binary.BigEndian.Uint32(ssrcBuf[:]),
		lastSwitch: time.Now(),
		replay:     newReplayGuard(defaultReplayWindow),
		rng:        rng,
		padDrift:   0.5,

		pendingProbe: make(map[uint32]time.Time),
		pongQueue:    make(chan []byte, 8),
	}
	arm, _ := s.bandit.selectArm()
	if cfg.InitialProfile != "" {
		for i, p := range profiles {
			if p.Name == cfg.InitialProfile {
				arm = i
				break
			}
		}
	}
	atomic.StoreInt32(&s.currentIdx, int32(arm))
	s.activePT = s.pickPayloadType(profiles[arm])
	return s, nil
}

// pickPayloadType picks one payload type at random from the profile's
// candidate list, for the caller to hold fixed for the duration of one
// profile activation (see the PayloadTypes doc comment on Profile).
func (s *Shaper) pickPayloadType(p Profile) uint8 {
	if len(p.PayloadTypes) == 0 {
		return 96 // reasonable generic dynamic-PT fallback
	}
	return p.PayloadTypes[s.rng.intn(len(p.PayloadTypes))] & 0x7F
}

// CurrentProfile returns the disguise currently in use.
func (s *Shaper) CurrentProfile() Profile {
	return s.profiles[atomic.LoadInt32(&s.currentIdx)]
}

// Wrap disguises payload as one packet of the current profile: an RTP-like
// header, AEAD-sealed payload, and profile-shaped random padding.
func (s *Shaper) Wrap(payload []byte) ([]byte, error) {
	return s.wrapInternal(markerData, payload)
}

// MaybeDecoy probabilistically produces a decoy packet (no tunnel payload,
// shaped like a keepalive/comfort-noise frame for the current profile) —
// callers should send this on the wire exactly like a Wrap()ed packet when
// ok is true. Mixing in decoys is what makes idle periods and low-bitrate
// spans look like a real media session instead of a tunnel that "goes
// quiet" the instant there's nothing to send. Call this on your own timer;
// it does not block or sleep.
func (s *Shaper) MaybeDecoy() (wire []byte, ok bool, err error) {
	profile := s.CurrentProfile()
	if profile.DecoyProbability <= 0 || s.rng.float64() >= profile.DecoyProbability {
		return nil, false, nil
	}
	size := s.rng.normal(float64(profile.DecoyBytesMean), float64(profile.DecoyBytesMean)/4, 1)
	filler := make([]byte, size)
	if _, err := rand.Read(filler); err != nil {
		return nil, false, fmt.Errorf("aiobfs: decoy filler: %w", err)
	}
	wire, err = s.wrapInternal(markerDecoy, filler)
	if err != nil {
		return nil, false, err
	}
	return wire, true, nil
}

func (s *Shaper) wrapInternal(marker byte, payload []byte) ([]byte, error) {
	profile := s.CurrentProfile()

	s.writeMu.Lock()
	seq := s.seq
	s.seq++

	// Pacing: bootstrap-sample from a real capture when the caller has
	// calibrated one (EmpiricalIntervals), otherwise fall back to the
	// synthetic mean+jitter formula. Either way this advances tsMicros so
	// the RTP-like timestamp's growth rate matches the actual packet rate,
	// the way a real codec's RTP timestamp would.
	var intervalUs int64
	if n := len(profile.EmpiricalIntervals); n > 0 {
		intervalUs = profile.EmpiricalIntervals[s.rng.intn(n)].Microseconds()
	} else {
		nominal := profile.SendInterval.Microseconds()
		jitter := int64(float64(nominal) * profile.IntervalJitter * (s.rng.float64()*2 - 1))
		intervalUs = nominal + jitter
	}
	s.tsMicros += uint32(intervalUs)
	ts := s.tsMicros
	ssrc := s.ssrc
	pt := s.activePT
	s.packetSent++

	// Padding target follows an autocorrelated mean-reverting random walk
	// (a discrete Ornstein-Uhlenbeck process) bounded to [0,1], reinterpreted
	// against this profile's PaddingMax — NOT an independent draw per
	// packet. Real codec frame sizes are autocorrelated (rate control and
	// motion compensation carry state from frame to frame); sampling
	// padding i.i.d. per packet would itself be an unnatural — and
	// therefore learnable — statistical signature that a classifier
	// trained specifically on this tool's traffic could pick up on, even
	// though the size still "varies" packet to packet either way.
	const reversionRate = 0.2
	const noiseStddev = 0.15
	s.padDrift += reversionRate*(0.5-s.padDrift) + noiseStddev*s.rng.gaussian()
	if s.padDrift < 0 {
		s.padDrift = 0
	} else if s.padDrift > 1 {
		s.padDrift = 1
	}
	padNeeded := 0
	if profile.PaddingMax > 0 {
		padNeeded = int(s.padDrift * float64(profile.PaddingMax))
	}
	s.writeMu.Unlock()

	var header [headerLen]byte
	header[0] = 0x80 | 0x20 // V=2, P=1 (padding present), X=0, CC=0
	header[1] = pt & 0x7F
	binary.BigEndian.PutUint16(header[2:4], seq)
	binary.BigEndian.PutUint32(header[4:8], ts)
	binary.BigEndian.PutUint32(header[8:12], ssrc)

	plaintext := make([]byte, 0, 1+len(payload))
	plaintext = append(plaintext, marker)
	plaintext = append(plaintext, payload...)

	sealed := s.aead.Seal(nil, header[:], plaintext, header[:])

	// padNeeded is computed independently of the ciphertext length: aiming
	// for an exact target wire size around profile.PacketBytesMean works
	// fine while the real ciphertext is smaller than that target, but the
	// moment the caller's payload makes the ciphertext *exceed* it, "how
	// much padding to hit the target" clamps to the same floor value on
	// every single packet — collapsing the very padding meant to hide the
	// true size into a constant, which is a worse fingerprint than no
	// padding at all.
	padTotal := padNeeded + 1 // +1 for the length byte itself, RFC3550-style

	out := make([]byte, headerLen+len(sealed)+padTotal)
	copy(out, header[:])
	copy(out[headerLen:], sealed)
	if padNeeded > 0 {
		if _, err := rand.Read(out[headerLen+len(sealed) : headerLen+len(sealed)+padNeeded]); err != nil {
			return nil, fmt.Errorf("aiobfs: padding random: %w", err)
		}
	}
	out[len(out)-1] = byte(padTotal)
	return out, nil
}

// Unwrap authenticates and decrypts a wire packet produced by Wrap,
// MaybeDecoy, or SendProbe on the peer side. isDecoy is true for anything
// that isn't real tunnel data — a decoy, or one of the self-monitoring
// probe/pong packets used by the autonomous mode (see autonomous.go) — the
// caller should drop it rather than forwarding it into the tunneled
// connection, but must still have called Unwrap so the AEAD/replay checks
// ran (dropping unauthenticated bytes instead would let an attacker
// distinguish non-data packets from data by whether the recipient bothers
// to check them at all). Unwrap also transparently drives the autonomous
// probe/pong protocol as a side effect: a received probe queues a pong for
// PendingPong to hand back to the caller to send, and a received pong
// updates this Shaper's own internal RTT/loss tracking used by
// RunAutonomous — none of that requires any extra action from the caller
// beyond the normal receive loop.
func (s *Shaper) Unwrap(wire []byte) (payload []byte, isDecoy bool, err error) {
	header, ciphertext, err := parseWireFrame(wire, s.aead.Overhead())
	if err != nil {
		return nil, false, err
	}

	if !s.replay.accept(header) {
		return nil, false, errors.New("aiobfs: replayed or duplicate packet")
	}

	marker, payload, err := openFrame(s.aead, header, ciphertext)
	if err != nil {
		return nil, false, err
	}

	switch marker {
	case markerProbe:
		s.handleIncomingProbe(payload)
	case markerPong:
		s.handleIncomingPong(payload)
	}

	return payload, marker != markerData, nil
}

// parseWireFrame validates the RTP-shaped header and strips padding,
// returning the header (also the AEAD nonce/AAD) and the still-encrypted
// ciphertext+tag. Shared between Unwrap (which then applies replay
// checking and opens with a fixed key) and TryUnwrap (which tries opening
// with several candidate keys and has no replay state of its own).
func parseWireFrame(wire []byte, aeadOverhead int) (header, ciphertext []byte, err error) {
	if len(wire) < headerLen+1 {
		return nil, nil, errors.New("aiobfs: packet too short")
	}
	if wire[0]>>6 != 2 {
		return nil, nil, errors.New("aiobfs: not an RTP-shaped packet (bad version)")
	}
	header = wire[:headerLen]

	payloadEnd := len(wire)
	if wire[0]&0x20 != 0 { // padding flag
		padLen := int(wire[len(wire)-1])
		if padLen == 0 || padLen > payloadEnd-headerLen {
			return nil, nil, fmt.Errorf("aiobfs: invalid padding length %d", padLen)
		}
		payloadEnd -= padLen
	}

	ciphertext = wire[headerLen:payloadEnd]
	if len(ciphertext) <= aeadOverhead {
		return nil, nil, errors.New("aiobfs: no payload after stripping header/padding")
	}
	return header, ciphertext, nil
}

// openFrame authenticates and decrypts a parsed frame, splitting the
// leading marker byte (see markerData/markerDecoy/markerProbe/markerPong)
// from the actual payload.
func openFrame(aead cipher.AEAD, header, ciphertext []byte) (marker byte, payload []byte, err error) {
	plain, err := aead.Open(nil, header, ciphertext, header)
	if err != nil {
		return 0, nil, fmt.Errorf("aiobfs: authentication failed: %w", err)
	}
	if len(plain) == 0 {
		return 0, nil, errors.New("aiobfs: empty plaintext")
	}
	return plain[0], plain[1:], nil
}

// Observe feeds back path measurements (recent average round-trip time,
// packet loss rate in [0,1], and achieved throughput in bits/sec) taken
// since the previous Observe call. It trains both the bandit and the
// policy network on the reward earned by whichever profile was active
// during that interval, then — no more often than once per MinDwell —
// may switch to a different profile for the next interval. Call this on a
// steady cadence (every 2-10s is reasonable); calling it more often makes
// the learners adapt faster at the cost of noisier reward estimates.
//
// It returns true if the active disguise profile changed as a result.
func (s *Shaper) Observe(rttMs, lossRate, throughputBps float64) bool {
	rttNorm := clamp01(rttMs / 300.0)
	lossNorm := clamp01(lossRate)
	throughputNorm := 0.5
	if s.target > 0 {
		throughputNorm = clamp01(throughputBps / s.target)
	}

	s.learnMu.Lock()
	defer s.learnMu.Unlock()

	now := time.Now()
	dwellNorm := clamp01(now.Sub(s.lastSwitch).Seconds() / s.minDwell.Seconds())
	features := []float64{rttNorm, lossNorm, throughputNorm, dwellNorm, 1.0}

	reward := clamp01(0.4*(1-rttNorm) + 0.4*(1-lossNorm) + 0.2*throughputNorm)

	if s.haveRound {
		s.bandit.update(s.pendingArm, s.pendingProb, reward)
		s.policy.train(s.pendingFeatures, s.pendingHidden, s.pendingProbs, s.pendingArm, reward)
	}

	hidden, logits, probs := s.policy.forward(features)
	bias := centeredBias(logits)
	candidateArm, candidateProb, dist := s.bandit.selectArmBiased(bias)

	current := int(atomic.LoadInt32(&s.currentIdx))
	useArm, useProb := current, dist[current]
	switched := false
	if now.Sub(s.lastSwitch) >= s.minDwell && candidateArm != current {
		useArm, useProb = candidateArm, candidateProb
		atomic.StoreInt32(&s.currentIdx, int32(candidateArm))
		s.lastSwitch = now
		switched = true

		s.writeMu.Lock()
		s.activePT = s.pickPayloadType(s.profiles[candidateArm])
		s.writeMu.Unlock()
	}

	s.pendingArm = useArm
	s.pendingProb = useProb
	s.pendingFeatures = features
	s.pendingHidden = hidden
	s.pendingProbs = probs
	s.haveRound = true

	return switched
}

// Stats reports current learner state, useful for logging/telemetry.
type Stats struct {
	ProfileName   string
	ProfileIndex  int
	BanditWeights []float64
	DwellSeconds  float64
}

func (s *Shaper) Stats() Stats {
	idx := int(atomic.LoadInt32(&s.currentIdx))
	s.learnMu.Lock()
	dwell := time.Since(s.lastSwitch).Seconds()
	s.learnMu.Unlock()
	return Stats{
		ProfileName:   s.profiles[idx].Name,
		ProfileIndex:  idx,
		BanditWeights: s.bandit.snapshot(),
		DwellSeconds:  dwell,
	}
}

// centeredBias converts raw policy-network logits into a bandit selection
// bias centered on zero, so that a network that is currently indifferent
// (uniform logits) contributes no net bias, and only genuine preferences
// push probability mass toward or away from an arm.
func centeredBias(logits []float64) []float64 {
	mean := 0.0
	for _, v := range logits {
		mean += v
	}
	mean /= float64(len(logits))
	out := make([]float64, len(logits))
	for i, v := range logits {
		out[i] = v - mean
	}
	return out
}

func clamp01(x float64) float64 {
	if x < 0 {
		return 0
	}
	if x > 1 {
		return 1
	}
	return x
}

// replayGuard rejects duplicate packets by their 12-byte header
// (seq+timestamp+ssrc), bounded to a fixed number of most-recent entries so
// memory use can't grow without bound over a long-running session. This is
// a simpler (FIFO-eviction) mechanism than a timestamp-windowed replay
// filter, trading a little precision for a trivially-correct, allocation-
// light implementation appropriate for a library layer; a production tunnel
// core with its own sequence-numbering may prefer a tighter window.
type replayGuard struct {
	mu    sync.Mutex
	seen  map[[headerLen]byte]struct{}
	order [][headerLen]byte
	max   int
}

func newReplayGuard(max int) *replayGuard {
	if max <= 0 {
		max = defaultReplayWindow
	}
	return &replayGuard{seen: make(map[[headerLen]byte]struct{}, max), max: max}
}

func (g *replayGuard) accept(header []byte) bool {
	var key [headerLen]byte
	copy(key[:], header)

	g.mu.Lock()
	defer g.mu.Unlock()
	if _, dup := g.seen[key]; dup {
		return false
	}
	if len(g.order) >= g.max {
		oldest := g.order[0]
		g.order = g.order[1:]
		delete(g.seen, oldest)
	}
	g.seen[key] = struct{}{}
	g.order = append(g.order, key)
	return true
}

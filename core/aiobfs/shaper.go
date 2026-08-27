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
	return s, nil
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
	// tsMicros advances by roughly one profile send-interval per packet,
	// with the same jitter model used for pacing (see profile.go) so the
	// timestamp field's growth rate matches the packet-rate an observer
	// sees, the way a real codec's RTP timestamp would.
	intervalUs := profile.SendInterval.Microseconds()
	jitterUs := int64(float64(intervalUs) * profile.IntervalJitter * (s.rng.float64()*2 - 1))
	s.tsMicros += uint32(intervalUs + jitterUs)
	ts := s.tsMicros
	ssrc := s.ssrc
	s.packetSent++
	s.writeMu.Unlock()

	var header [headerLen]byte
	header[0] = 0x80 | 0x20 // V=2, P=1 (padding present), X=0, CC=0
	header[1] = profile.PayloadType & 0x7F
	binary.BigEndian.PutUint16(header[2:4], seq)
	binary.BigEndian.PutUint32(header[4:8], ts)
	binary.BigEndian.PutUint32(header[8:12], ssrc)

	plaintext := make([]byte, 0, 1+len(payload))
	plaintext = append(plaintext, marker)
	plaintext = append(plaintext, payload...)

	sealed := s.aead.Seal(nil, header[:], plaintext, header[:])

	// Padding is sampled independently of the ciphertext length: aiming for
	// an exact target wire size (mean +/- stddev around
	// profile.PacketBytesMean) works fine while the real ciphertext is
	// smaller than that target, but the moment the caller's payload makes
	// the ciphertext *exceed* it, "how much padding to hit the target"
	// clamps to the same floor value on every single packet — collapsing
	// the very padding meant to hide the true size into a constant, which
	// is a worse fingerprint than no padding at all. Sampling padding
	// uniformly from [0, PaddingMax] regardless of ciphertext length keeps
	// every packet's wire size varying no matter how the payload compares
	// to the profile's nominal size.
	padNeeded := 0
	if profile.PaddingMax > 0 {
		padNeeded = s.rng.intn(profile.PaddingMax + 1)
	}
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

// Unwrap authenticates and decrypts a wire packet produced by Wrap or
// MaybeDecoy on the peer side. isDecoy is true for a decoy packet — the
// caller should drop it rather than forwarding it into the tunneled
// connection, but must still have called Unwrap so the AEAD/replay checks
// ran (dropping unauthenticated bytes instead would let an attacker
// distinguish decoys from data by whether the recipient bothers to check
// them at all).
func (s *Shaper) Unwrap(wire []byte) (payload []byte, isDecoy bool, err error) {
	if len(wire) < headerLen+1 {
		return nil, false, errors.New("aiobfs: packet too short")
	}
	if wire[0]>>6 != 2 {
		return nil, false, errors.New("aiobfs: not an RTP-shaped packet (bad version)")
	}
	header := wire[:headerLen]

	payloadEnd := len(wire)
	if wire[0]&0x20 != 0 { // padding flag
		padLen := int(wire[len(wire)-1])
		if padLen == 0 || padLen > payloadEnd-headerLen {
			return nil, false, fmt.Errorf("aiobfs: invalid padding length %d", padLen)
		}
		payloadEnd -= padLen
	}

	ciphertext := wire[headerLen:payloadEnd]
	if len(ciphertext) <= s.aead.Overhead() {
		return nil, false, errors.New("aiobfs: no payload after stripping header/padding")
	}

	if !s.replay.accept(header) {
		return nil, false, errors.New("aiobfs: replayed or duplicate packet")
	}

	plain, err := s.aead.Open(nil, header, ciphertext, header)
	if err != nil {
		return nil, false, fmt.Errorf("aiobfs: authentication failed: %w", err)
	}
	if len(plain) == 0 {
		return nil, false, errors.New("aiobfs: empty plaintext")
	}

	marker := plain[0]
	return plain[1:], marker == markerDecoy, nil
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

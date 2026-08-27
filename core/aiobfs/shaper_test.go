package aiobfs

import (
	"bytes"
	"math"
	"testing"
	"time"
)

func newTestShaper(t *testing.T) *Shaper {
	t.Helper()
	s, err := New(Config{
		Key:      []byte("test-key-do-not-use-in-prod"),
		MinDwell: 10 * time.Millisecond,
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	return s
}

func TestWrapUnwrapRoundTrip(t *testing.T) {
	s := newTestShaper(t)
	msg := []byte("hello over the wire, this is tunnel payload")

	wire, err := s.Wrap(msg)
	if err != nil {
		t.Fatalf("Wrap: %v", err)
	}
	if len(wire) <= len(msg) {
		t.Fatalf("expected wrapped packet to carry header+tag+padding overhead, got %d bytes for %d byte payload", len(wire), len(msg))
	}

	got, isDecoy, err := s.Unwrap(wire)
	if err != nil {
		t.Fatalf("Unwrap: %v", err)
	}
	if isDecoy {
		t.Fatalf("real payload misidentified as decoy")
	}
	if !bytes.Equal(got, msg) {
		t.Fatalf("round trip mismatch: got %q want %q", got, msg)
	}
}

func TestWrapProducesRTPLikeHeader(t *testing.T) {
	s := newTestShaper(t)
	wire, err := s.Wrap([]byte("x"))
	if err != nil {
		t.Fatalf("Wrap: %v", err)
	}
	if len(wire) < headerLen {
		t.Fatalf("packet shorter than header: %d", len(wire))
	}
	if version := wire[0] >> 6; version != 2 {
		t.Fatalf("expected RTP version 2 in top bits, got %d", version)
	}
	profile := s.CurrentProfile()
	pt := wire[1] & 0x7F
	found := false
	for _, candidate := range profile.PayloadTypes {
		if pt == candidate {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("payload type %d not among profile %q's candidates %v", pt, profile.Name, profile.PayloadTypes)
	}
}

func TestDecoyRoundTripIsFlagged(t *testing.T) {
	// Force high decoy probability so the test is deterministic rather than
	// relying on a standard profile's default (low) chance, by supplying a
	// single custom profile at construction time.
	s, err := New(Config{
		Key: []byte("test-key-do-not-use-in-prod"),
		Profiles: []Profile{{
			Name: "always-decoy", PayloadTypes: []uint8{111},
			PacketBytesMean: 64, PacketBytesStd: 8, PaddingMax: 16,
			SendInterval: 20 * time.Millisecond, IntervalJitter: 0.1,
			DecoyProbability: 1.0, DecoyBytesMean: 24,
		}},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	wire, ok, err := s.MaybeDecoy()
	if err != nil {
		t.Fatalf("MaybeDecoy: %v", err)
	}
	if !ok {
		t.Fatalf("expected a decoy with DecoyProbability=1.0")
	}
	payload, isDecoy, err := s.Unwrap(wire)
	if err != nil {
		t.Fatalf("Unwrap decoy: %v", err)
	}
	if !isDecoy {
		t.Fatalf("decoy packet not flagged as decoy")
	}
	_ = payload
}

func TestUnwrapRejectsTamperedPacket(t *testing.T) {
	s := newTestShaper(t)
	wire, err := s.Wrap([]byte("integrity matters"))
	if err != nil {
		t.Fatalf("Wrap: %v", err)
	}
	tampered := append([]byte(nil), wire...)
	tampered[headerLen] ^= 0xFF // flip a bit inside the ciphertext

	if _, _, err := s.Unwrap(tampered); err == nil {
		t.Fatalf("expected authentication failure on tampered packet")
	}
}

func TestUnwrapRejectsReplay(t *testing.T) {
	s := newTestShaper(t)
	wire, err := s.Wrap([]byte("only once"))
	if err != nil {
		t.Fatalf("Wrap: %v", err)
	}
	if _, _, err := s.Unwrap(wire); err != nil {
		t.Fatalf("first Unwrap should succeed: %v", err)
	}
	if _, _, err := s.Unwrap(wire); err == nil {
		t.Fatalf("expected replay of an identical packet to be rejected")
	}
}

func TestUnwrapRejectsCrossKeyPacket(t *testing.T) {
	a, err := New(Config{Key: []byte("key-a")})
	if err != nil {
		t.Fatalf("New a: %v", err)
	}
	b, err := New(Config{Key: []byte("key-b")})
	if err != nil {
		t.Fatalf("New b: %v", err)
	}
	wire, err := a.Wrap([]byte("secret"))
	if err != nil {
		t.Fatalf("Wrap: %v", err)
	}
	if _, _, err := b.Unwrap(wire); err == nil {
		t.Fatalf("expected packet wrapped under a different key to fail authentication")
	}
}

// TestShapeVariesAcrossPackets checks that consecutive wrapped packets
// (same plaintext, same profile) are not all the same wire size — the
// whole point of per-packet padding sampling is that the shape isn't a
// fixed, fingerprintable constant.
func TestShapeVariesAcrossPackets(t *testing.T) {
	s := newTestShaper(t)
	sizes := map[int]bool{}
	for i := 0; i < 40; i++ {
		wire, err := s.Wrap([]byte("same plaintext every time"))
		if err != nil {
			t.Fatalf("Wrap: %v", err)
		}
		sizes[len(wire)] = true
	}
	if len(sizes) < 2 {
		t.Fatalf("expected varying wire sizes across packets for shape-hiding, got only %d distinct size(s)", len(sizes))
	}
}

// TestObserveAdaptsAwayFromPenalizedProfile simulates a path where one
// profile is consistently penalized (as if a censor were throttling that
// disguise) and checks the shaper trends toward using it less over time.
func TestObserveAdaptsAwayFromPenalizedProfile(t *testing.T) {
	s := newTestShaper(t)
	penalized := int(s.currentIdx)

	usedPenalized := 0
	usedOther := 0
	const rounds = 400
	for i := 0; i < rounds; i++ {
		cur := int(s.currentIdx)
		if cur == penalized {
			usedPenalized++
			s.Observe(280, 0.9, 1000) // bad RTT+loss on the penalized profile
		} else {
			usedOther++
			s.Observe(20, 0.0, 1_000_000) // great RTT+loss+throughput elsewhere
		}
		time.Sleep(time.Millisecond) // let MinDwell (10ms) actually elapse sometimes
	}

	if usedOther == 0 {
		t.Fatalf("shaper never moved away from the penalized profile in %d rounds", rounds)
	}
	// Later rounds should favor the non-penalized profile more than a
	// uniform coin flip would, i.e. learning happened rather than the
	// switch being pure exploration noise.
	if usedOther < rounds/4 {
		t.Fatalf("expected meaningful adaptation away from the penalized profile, got other=%d penalized=%d over %d rounds", usedOther, usedPenalized, rounds)
	}
}

// TestPayloadTypeStaysFixedWithinOneActivation checks that PayloadTypes is
// sampled once per profile activation, not re-rolled on every packet — real
// SDP negotiates a payload type once per call, so a PT that flips mid-flow
// would itself be an unnatural tell.
func TestPayloadTypeStaysFixedWithinOneActivation(t *testing.T) {
	s, err := New(Config{
		Key: []byte("test-key-do-not-use-in-prod"),
		Profiles: []Profile{{
			Name: "multi-pt", PayloadTypes: []uint8{96, 100, 110, 120},
			PacketBytesMean: 100, PacketBytesStd: 20, PaddingMax: 32,
			SendInterval: 20 * time.Millisecond, IntervalJitter: 0.1,
		}},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	wire, err := s.Wrap([]byte("first"))
	if err != nil {
		t.Fatalf("Wrap: %v", err)
	}
	want := wire[1] & 0x7F
	for i := 0; i < 50; i++ {
		wire, err := s.Wrap([]byte("payload"))
		if err != nil {
			t.Fatalf("Wrap: %v", err)
		}
		if got := wire[1] & 0x7F; got != want {
			t.Fatalf("payload type changed mid-activation on packet %d: got %d want %d", i, got, want)
		}
	}
}

// TestPayloadTypeChangesOnProfileSwitch checks that switching the active
// profile (via Observe) also re-picks the payload type from the new
// profile's candidates, using two single-candidate profiles so the
// expected value is deterministic.
func TestPayloadTypeChangesOnProfileSwitch(t *testing.T) {
	s, err := New(Config{
		Key: []byte("test-key-do-not-use-in-prod"),
		Profiles: []Profile{
			{Name: "A", PayloadTypes: []uint8{50}, PacketBytesMean: 100, PacketBytesStd: 20, PaddingMax: 32, SendInterval: 20 * time.Millisecond, IntervalJitter: 0.1},
			{Name: "B", PayloadTypes: []uint8{90}, PacketBytesMean: 100, PacketBytesStd: 20, PaddingMax: 32, SendInterval: 20 * time.Millisecond, IntervalJitter: 0.1},
		},
		InitialProfile: "A",
		MinDwell:       5 * time.Millisecond,
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	wire, err := s.Wrap([]byte("x"))
	if err != nil {
		t.Fatalf("Wrap: %v", err)
	}
	if got := wire[1] & 0x7F; got != 50 {
		t.Fatalf("expected initial PT 50 (profile A), got %d", got)
	}

	switched := false
	for i := 0; i < 500 && !switched; i++ {
		if s.Stats().ProfileName == "A" {
			switched = s.Observe(280, 0.9, 0) // penalize A
		} else {
			switched = s.Observe(20, 0.0, 0) // reward B
		}
		time.Sleep(time.Millisecond)
	}
	if s.Stats().ProfileName != "B" {
		t.Fatalf("expected shaper to have switched to profile B, still on %s", s.Stats().ProfileName)
	}

	wire, err = s.Wrap([]byte("y"))
	if err != nil {
		t.Fatalf("Wrap: %v", err)
	}
	if got := wire[1] & 0x7F; got != 90 {
		t.Fatalf("expected PT 90 (profile B) after switching, got %d", got)
	}
}

// TestPaddingIsAutocorrelatedNotIID checks that the padding-driven wire
// size follows a smooth, mean-reverting random walk rather than
// independent draws: real codec frame sizes carry state from frame to
// frame (rate control, motion compensation), so i.i.d. padding would
// itself be an unnatural, learnable signature. We check this by measuring
// the lag-1 sample autocorrelation of consecutive wire sizes (holding the
// plaintext, and therefore the ciphertext length, fixed) — i.i.d. noise
// has an expected lag-1 correlation of 0, while the OU-style drift used
// here has a strong positive one.
func TestPaddingIsAutocorrelatedNotIID(t *testing.T) {
	s, err := New(Config{
		Key: []byte("test-key-do-not-use-in-prod"),
		Profiles: []Profile{{
			Name: "wide-pad", PayloadTypes: []uint8{96},
			PacketBytesMean: 200, PacketBytesStd: 50, PaddingMax: 200,
			SendInterval: 20 * time.Millisecond, IntervalJitter: 0.1,
		}},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	const n = 300
	sizes := make([]float64, n)
	for i := 0; i < n; i++ {
		wire, err := s.Wrap([]byte("constant plaintext for every packet"))
		if err != nil {
			t.Fatalf("Wrap: %v", err)
		}
		sizes[i] = float64(len(wire))
	}

	r := lag1Autocorrelation(sizes)
	if r < 0.3 {
		t.Fatalf("expected strong lag-1 autocorrelation from mean-reverting drift, got r=%.3f (i.i.d. noise would be ~0)", r)
	}
}

func lag1Autocorrelation(xs []float64) float64 {
	n := len(xs) - 1
	a, b := xs[:n], xs[1:]
	meanA, meanB := mean(a), mean(b)

	var num, denomA, denomB float64
	for i := 0; i < n; i++ {
		da, db := a[i]-meanA, b[i]-meanB
		num += da * db
		denomA += da * da
		denomB += db * db
	}
	if denomA == 0 || denomB == 0 {
		return 0
	}
	return num / math.Sqrt(denomA*denomB)
}

func mean(xs []float64) float64 {
	sum := 0.0
	for _, v := range xs {
		sum += v
	}
	return sum / float64(len(xs))
}

// TestEmpiricalIntervalsOverridePacing checks that when a profile carries
// EmpiricalIntervals, packet pacing is bootstrap-sampled from that slice
// (values only ever seen in the list) instead of the synthetic
// SendInterval+jitter formula.
func TestEmpiricalIntervalsOverridePacing(t *testing.T) {
	empirical := []time.Duration{5 * time.Millisecond, 50 * time.Millisecond}
	s, err := New(Config{
		Key: []byte("test-key-do-not-use-in-prod"),
		Profiles: []Profile{{
			Name: "calibrated", PayloadTypes: []uint8{96},
			PacketBytesMean: 100, PacketBytesStd: 20, PaddingMax: 16,
			// Deliberately different from the empirical samples, so the
			// test would fail if EmpiricalIntervals weren't actually
			// taking priority.
			SendInterval:       500 * time.Millisecond,
			IntervalJitter:     0,
			EmpiricalIntervals: empirical,
		}},
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	seenSmall, seenLarge := false, false
	var prevTs uint32
	for i := 0; i < 60; i++ {
		wire, err := s.Wrap([]byte("x"))
		if err != nil {
			t.Fatalf("Wrap: %v", err)
		}
		ts := beUint32(wire[4:8])
		if i > 0 {
			delta := time.Duration(ts-prevTs) * time.Microsecond
			switch delta {
			case 5 * time.Millisecond:
				seenSmall = true
			case 50 * time.Millisecond:
				seenLarge = true
			default:
				t.Fatalf("timestamp delta %v not one of the empirical samples", delta)
			}
		}
		prevTs = ts
	}
	if !seenSmall || !seenLarge {
		t.Fatalf("expected to see both empirical samples over 60 packets, seenSmall=%v seenLarge=%v", seenSmall, seenLarge)
	}
}

func beUint32(b []byte) uint32 {
	return uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3])
}

func TestStatsReportsCurrentProfile(t *testing.T) {
	s := newTestShaper(t)
	st := s.Stats()
	if st.ProfileName == "" {
		t.Fatalf("expected non-empty profile name")
	}
	if len(st.BanditWeights) != len(s.profiles) {
		t.Fatalf("expected %d bandit weights, got %d", len(s.profiles), len(st.BanditWeights))
	}
}

package aiobfs

import (
	"bytes"
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
	if pt := wire[1] & 0x7F; pt != profile.PayloadType {
		t.Fatalf("payload type mismatch: got %d want %d", pt, profile.PayloadType)
	}
}

func TestDecoyRoundTripIsFlagged(t *testing.T) {
	// Force high decoy probability so the test is deterministic rather than
	// relying on a standard profile's default (low) chance, by supplying a
	// single custom profile at construction time.
	s, err := New(Config{
		Key: []byte("test-key-do-not-use-in-prod"),
		Profiles: []Profile{{
			Name: "always-decoy", PayloadType: 111,
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

package aiobfs

import (
	"context"
	"sync/atomic"
	"testing"
	"time"
)

func TestProbePongRoundTripMeasuresRTT(t *testing.T) {
	a, err := New(Config{Key: []byte("shared-key")})
	if err != nil {
		t.Fatalf("New a: %v", err)
	}
	b, err := New(Config{Key: []byte("shared-key")})
	if err != nil {
		t.Fatalf("New b: %v", err)
	}

	probe, err := a.SendProbe()
	if err != nil {
		t.Fatalf("SendProbe: %v", err)
	}

	payload, isDecoy, err := b.Unwrap(probe)
	if err != nil {
		t.Fatalf("b.Unwrap(probe): %v", err)
	}
	if !isDecoy {
		t.Fatalf("a probe must be classified as non-data (isDecoy=true) so naive callers never forward it")
	}
	_ = payload

	pong, ok := b.PendingPong()
	if !ok {
		t.Fatalf("expected b to have queued a pong after receiving a probe")
	}

	time.Sleep(2 * time.Millisecond) // make the RTT measurably nonzero
	_, isDecoy, err = a.Unwrap(pong)
	if err != nil {
		t.Fatalf("a.Unwrap(pong): %v", err)
	}
	if !isDecoy {
		t.Fatalf("pong must also be classified as non-data")
	}

	rttMs, lossRate := a.drainAutoStats()
	if lossRate != 0 {
		t.Fatalf("expected zero loss for a probe that was answered before draining, got %f", lossRate)
	}
	if rttMs < 1 {
		t.Fatalf("expected a measurable RTT (>= ~2ms sleep) after a real probe/pong round trip, got %f", rttMs)
	}
}

func TestUnansweredProbeCountsAsLossInWindow(t *testing.T) {
	a, err := New(Config{Key: []byte("shared-key")})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if _, err := a.SendProbe(); err != nil {
		t.Fatalf("SendProbe: %v", err)
	}
	rttMs, lossRate := a.drainAutoStats()
	if lossRate != 1 {
		t.Fatalf("expected a never-answered probe to read as 100%% loss in its window, got %f", lossRate)
	}
	if rttMs != 300 {
		t.Fatalf("expected the no-samples-yet worst-case RTT default (300ms), got %f", rttMs)
	}
}

func TestPendingPongIsEmptyWhenNothingToSend(t *testing.T) {
	a, err := New(Config{Key: []byte("shared-key")})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if _, ok := a.PendingPong(); ok {
		t.Fatalf("expected no pending pong on a shaper that hasn't received a probe")
	}
}

// TestRunAutonomousAdaptsWithoutManualObserve wires two Shapers together
// through an in-memory "network" (a send function that immediately calls
// Unwrap on the peer and bounces any resulting pong back), starts blocking
// whichever payload type profile "A" happens to be using partway through,
// and checks that RunAutonomous alone — with no test code ever calling
// Observe directly — notices and switches to profile "B". This is the
// end-to-end proof that the autonomous path is genuinely self-sufficient.
func TestRunAutonomousAdaptsWithoutManualObserve(t *testing.T) {
	a, err := New(Config{
		Key: []byte("shared-key"),
		Profiles: []Profile{
			{Name: "A", PayloadTypes: []uint8{50}, PacketBytesMean: 100, PacketBytesStd: 20, PaddingMax: 16, SendInterval: 5 * time.Millisecond, IntervalJitter: 0.1},
			{Name: "B", PayloadTypes: []uint8{90}, PacketBytesMean: 100, PacketBytesStd: 20, PaddingMax: 16, SendInterval: 5 * time.Millisecond, IntervalJitter: 0.1},
		},
		InitialProfile: "A",
		MinDwell:       20 * time.Millisecond,
	})
	if err != nil {
		t.Fatalf("New a: %v", err)
	}
	b, err := New(Config{Key: []byte("shared-key")})
	if err != nil {
		t.Fatalf("New b: %v", err)
	}

	var sendCount int32
	var blocked int32
	send := func(wire []byte) error {
		if atomic.AddInt32(&sendCount, 1) == 5 {
			atomic.StoreInt32(&blocked, 1) // start censoring partway through
		}
		if atomic.LoadInt32(&blocked) == 1 && wire[1]&0x7F == 50 {
			return nil // silently drop anything shaped like profile A
		}
		if _, _, err := b.Unwrap(wire); err != nil {
			return nil
		}
		if pong, ok := b.PendingPong(); ok {
			_, _, _ = a.Unwrap(pong)
		}
		return nil
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stop := a.RunAutonomous(ctx, send, 30*time.Millisecond)
	defer stop()

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) && a.Stats().ProfileName != "B" {
		time.Sleep(10 * time.Millisecond)
	}
	if got := a.Stats().ProfileName; got != "B" {
		t.Fatalf("expected RunAutonomous to adapt to profile B on its own within 3s, still on %s", got)
	}
}

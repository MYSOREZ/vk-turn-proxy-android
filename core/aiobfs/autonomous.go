package aiobfs

import (
	"context"
	"encoding/binary"
	"time"
)

// This file implements the "autonomous" integration path: a Shaper that
// measures its own RTT and loss via self-generated probe/pong packets and
// drives its own Observe() calls, instead of requiring the host application
// to implement a ping loop and feed measurements in manually (the "paternal"
// path in the design discussion this addresses — the adaptive layer looks
// after its own health rather than depending on an outside agent to hand it
// data every few seconds). Observe() itself, and everything in shaper.go,
// still works exactly as before for callers who already have their own
// RTT/loss telemetry (e.g. WireGuard handshake timing, an existing ping
// loop) and would rather feed that in directly instead of paying for a
// second, separate probe stream on the wire.
//
// The mechanism: SendProbe produces a decoy-shaped packet carrying a random
// ID. When the peer's Unwrap sees it, it automatically queues a pong
// carrying the same ID (drained via PendingPong) — no action required from
// the probing side's application logic beyond sending what SendProbe
// handed it. When the original Shaper's own Unwrap later sees that pong, it
// measures the round trip and folds it into an internal EWMA. RunAutonomous
// ties this into a periodic loop that calls Observe() using only what the
// Shaper measured about itself.

// SendProbe produces a wire packet — shaped exactly like a decoy, so it's
// indistinguishable on the wire from one — that asks the peer to echo an ID
// back. Send it exactly like a Wrap()ed packet. Pair with RunAutonomous, or
// call it directly and read PendingPong()/feed Observe() yourself if you
// want the probing but your own scheduling.
func (s *Shaper) SendProbe() ([]byte, error) {
	s.autoMu.Lock()
	s.nextProbeID++
	id := s.nextProbeID
	s.pendingProbe[id] = time.Now()
	s.autoSent++
	s.autoMu.Unlock()

	var idBytes [4]byte
	binary.BigEndian.PutUint32(idBytes[:], id)
	return s.wrapInternal(markerProbe, idBytes[:])
}

// PendingPong returns, if Unwrap has just processed an incoming probe from
// the peer, the wire-ready pong packet to send back — call this once after
// every Unwrap in your receive loop; ok is false on most calls (only a
// received probe produces a pong to send). Sending it is the only action
// needed to keep the peer's own autonomous RTT measurement working.
func (s *Shaper) PendingPong() ([]byte, bool) {
	select {
	case p := <-s.pongQueue:
		return p, true
	default:
		return nil, false
	}
}

// handleIncomingProbe is called from Unwrap when a probe arrives from the
// peer: it builds the matching pong and queues it for PendingPong to hand
// back to the caller. Building the pong here (rather than in PendingPong)
// means it carries a fresh sequence/timestamp/padding-drift sample from the
// moment it was actually requested, consistent with every other packet.
func (s *Shaper) handleIncomingProbe(idPayload []byte) {
	pong, err := s.wrapInternal(markerPong, idPayload)
	if err != nil {
		return // best-effort: a dropped probe just costs one lost RTT sample
	}
	select {
	case s.pongQueue <- pong:
	default:
		// Queue full (host isn't draining PendingPong): drop rather than
		// block Unwrap. The peer just sees one extra "lost" probe.
	}
}

// handleIncomingPong is called from Unwrap when a pong arrives, matching it
// against a still-pending probe (if any — a very late or duplicate pong is
// silently ignored) and folding the measured RTT into an EWMA.
func (s *Shaper) handleIncomingPong(idPayload []byte) {
	if len(idPayload) < 4 {
		return
	}
	id := binary.BigEndian.Uint32(idPayload)

	s.autoMu.Lock()
	defer s.autoMu.Unlock()
	sentAt, ok := s.pendingProbe[id]
	if !ok {
		return
	}
	delete(s.pendingProbe, id)

	rttMs := float64(time.Since(sentAt).Microseconds()) / 1000.0
	if !s.autoHaveRTT {
		s.autoRTTEWMA = rttMs
		s.autoHaveRTT = true
	} else {
		const alpha = 0.3 // weight on the newest sample
		s.autoRTTEWMA = (1-alpha)*s.autoRTTEWMA + alpha*rttMs
	}
	s.autoPonged++
}

// drainAutoStats reads and resets the current window's self-measured
// RTT/loss, pruning any probe old enough (autoStaleProbe) to count as lost
// rather than merely slow, so a probe that will never be answered doesn't
// sit in pendingProbe forever inflating memory use without ever being
// counted against the loss rate.
func (s *Shaper) drainAutoStats() (rttMs, lossRate float64) {
	s.autoMu.Lock()
	defer s.autoMu.Unlock()

	cutoff := time.Now().Add(-autoStaleProbe)
	for id, sentAt := range s.pendingProbe {
		if sentAt.Before(cutoff) {
			delete(s.pendingProbe, id)
		}
	}

	sent, ponged := s.autoSent, s.autoPonged
	s.autoSent, s.autoPonged = 0, 0

	if sent > 0 {
		lossRate = 1 - float64(ponged)/float64(sent)
		if lossRate < 0 {
			lossRate = 0
		}
	}
	if s.autoHaveRTT {
		rttMs = s.autoRTTEWMA
	} else {
		rttMs = 300 // no samples this window at all: assume the worst rather than the best
	}
	return rttMs, lossRate
}

// RunAutonomous starts a background goroutine that periodically sends a
// self-probe (via send, which should transmit the given wire bytes to the
// peer exactly like any other wrapped packet — e.g. conn.Write) and, every
// interval, folds the resulting self-measured RTT/loss into Observe(). It
// returns a stop function; call it (or cancel ctx) to end the loop.
//
// This is the fully autonomous integration path: once running, the Shaper
// adapts on its own from what it measures about itself. It still needs the
// host to actually move bytes over the network (send) and to keep calling
// Unwrap on everything received (so pongs — and the peer's own probes,
// answered via PendingPong — get processed); it does not open sockets or
// know anything about the transport itself.
func (s *Shaper) RunAutonomous(ctx context.Context, send func(wire []byte) error, interval time.Duration) (stop func()) {
	if interval <= 0 {
		interval = 2 * time.Second
	}
	probeInterval := interval / 4
	if probeInterval <= 0 {
		probeInterval = interval
	}

	runCtx, cancel := context.WithCancel(ctx)
	go func() {
		probeTicker := time.NewTicker(probeInterval)
		evalTicker := time.NewTicker(interval)
		defer probeTicker.Stop()
		defer evalTicker.Stop()
		for {
			select {
			case <-runCtx.Done():
				return
			case <-probeTicker.C:
				if wire, err := s.SendProbe(); err == nil {
					_ = send(wire)
				}
			case <-evalTicker.C:
				rttMs, lossRate := s.drainAutoStats()
				s.Observe(rttMs, lossRate, 0)
			}
		}
	}()
	return cancel
}

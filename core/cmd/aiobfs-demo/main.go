// Command aiobfs-demo runs a tiny end-to-end simulation of the aiobfs
// adaptive traffic-masking layer: a client and a server talk over real
// loopback UDP sockets, through an in-between "censor" process that starts
// throttling specific disguise fingerprints partway through the run — the
// same way a real DPI middlebox might learn to recognize and penalize one
// traffic shape. Watch the printed status lines: the active profile and
// the bandit's weights visibly shift away from whatever the censor just
// started blocking, without either side renegotiating anything explicit —
// the shape change is entirely a side effect of the client noticing worse
// RTT/loss and the bandit+policy learners reacting to it.
//
// Run it with: go run ./cmd/aiobfs-demo
package main

import (
	"context"
	"encoding/binary"
	"flag"
	"fmt"
	"log"
	"math/rand/v2"
	"net"
	"os"
	"os/signal"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/mysorez/vk-turn-proxy-android/core/aiobfs"
)

func main() {
	key := flag.String("key", "demo-shared-secret-change-me", "shared key for both sides of the demo tunnel")
	duration := flag.Duration("duration", 24*time.Second, "how long to run the demo")
	flag.Parse()

	ctx, cancel := context.WithTimeout(context.Background(), *duration)
	defer cancel()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigCh
		cancel()
	}()

	serverAddr, err := runServer(ctx, *key)
	if err != nil {
		log.Fatalf("server: %v", err)
	}

	censorAddr, err := runCensor(ctx, serverAddr)
	if err != nil {
		log.Fatalf("censor: %v", err)
	}

	if err := runClient(ctx, *key, censorAddr); err != nil {
		log.Fatalf("client: %v", err)
	}
}

// ---------------------------------------------------------------- server --

// runServer starts a UDP echo server that authenticates/unwraps every
// packet with its own aiobfs.Shaper and, for any packet that isn't a
// decoy, wraps the same payload again and echoes it straight back. Real
// tunnel cores would instead forward the payload into WireGuard/whatever
// is behind them; echoing is enough for the client to measure RTT and loss.
func runServer(ctx context.Context, key string) (*net.UDPAddr, error) {
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		return nil, err
	}
	shaper, err := aiobfs.New(aiobfs.Config{Key: []byte(key)})
	if err != nil {
		return nil, err
	}

	go func() {
		<-ctx.Done()
		_ = conn.Close()
	}()

	go func() {
		buf := make([]byte, 2048)
		for {
			n, addr, err := conn.ReadFromUDP(buf)
			if err != nil {
				return // socket closed on ctx cancel
			}
			payload, isDecoy, err := shaper.Unwrap(buf[:n])
			if err != nil {
				continue // drop anything that doesn't authenticate
			}
			if isDecoy {
				continue // real tunnel cores never forward decoy traffic
			}
			echo, err := shaper.Wrap(payload)
			if err != nil {
				continue
			}
			_, _ = conn.WriteToUDP(echo, addr)
		}
	}()

	return conn.LocalAddr().(*net.UDPAddr), nil
}

// ---------------------------------------------------------------- censor --

// censor sits between client and server like a DPI middlebox: it forwards
// everything by default, but once armed against a payload-type fingerprint
// it mostly drops (and otherwise heavily delays) packets carrying it —
// exactly the visible byte an on-path observer *can* see, since our own
// disguise deliberately leaves the payload-type field in cleartext to look
// like ordinary WebRTC signaling.
type censor struct {
	conn       *net.UDPConn
	serverAddr *net.UDPAddr
	clientAddr atomic.Pointer[net.UDPAddr]

	blockedTypes sync.Map // uint8 -> struct{}
}

func runCensor(ctx context.Context, serverAddr *net.UDPAddr) (*net.UDPAddr, error) {
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		return nil, err
	}
	c := &censor{conn: conn, serverAddr: serverAddr}

	go func() {
		<-ctx.Done()
		_ = conn.Close()
	}()
	go c.loop()
	go c.schedule(ctx)

	return conn.LocalAddr().(*net.UDPAddr), nil
}

// schedule escalates censorship over the demo's runtime: first the default
// audio disguise gets fingerprinted and blocked, then — forcing a second,
// different adaptation — the video disguise gets blocked too.
func (c *censor) schedule(ctx context.Context) {
	select {
	case <-time.After(7 * time.Second):
		fmt.Println("\n>>> [censor] fingerprinted PT=111 (audio_opus-shaped traffic) — blocking it now")
		c.blockedTypes.Store(uint8(111), struct{}{})
	case <-ctx.Done():
		return
	}
	select {
	case <-time.After(8 * time.Second):
		fmt.Println("\n>>> [censor] fingerprinted PT=96 (video-shaped traffic) too — blocking that as well")
		c.blockedTypes.Store(uint8(96), struct{}{})
	case <-ctx.Done():
		return
	}
}

func (c *censor) loop() {
	buf := make([]byte, 2048)
	for {
		n, addr, err := c.conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if addr.String() == c.serverAddr.String() {
			// Server -> client direction: never throttled in this demo,
			// mirroring how censors typically target the outbound/uplink
			// fingerprint rather than every packet in both directions.
			if client := c.clientAddr.Load(); client != nil {
				_, _ = c.conn.WriteToUDP(buf[:n], client)
			}
			continue
		}

		c.clientAddr.Store(addr)
		if drop, delay := c.decide(buf[:n]); drop {
			continue
		} else if delay > 0 {
			pkt := append([]byte(nil), buf[:n]...)
			time.AfterFunc(delay, func() { _, _ = c.conn.WriteToUDP(pkt, c.serverAddr) })
			continue
		}
		_, _ = c.conn.WriteToUDP(buf[:n], c.serverAddr)
	}
}

func (c *censor) decide(pkt []byte) (drop bool, delay time.Duration) {
	if len(pkt) < 2 {
		return false, 0
	}
	pt := pkt[1] & 0x7F
	if _, blocked := c.blockedTypes.Load(pt); !blocked {
		return false, 0
	}
	if rand.Float64() < 0.85 {
		return true, 0
	}
	return false, 300 * time.Millisecond
}

// ---------------------------------------------------------------- client --

type pending struct {
	sentAt time.Time
}

func runClient(ctx context.Context, key string, peer *net.UDPAddr) error {
	conn, err := net.DialUDP("udp", nil, peer)
	if err != nil {
		return err
	}
	defer conn.Close()

	shaper, err := aiobfs.New(aiobfs.Config{
		Key: []byte(key),
		// Pinned so the demo's first ~7s (before the censor blocks
		// anything) has an obvious, deterministic starting shape to
		// contrast with what it adapts to afterward.
		InitialProfile: "audio_opus",
		// Longer than the pre-block window so the demo doesn't drift off
		// audio_opus by chance before there's anything to react to, but
		// still short enough to visibly react within the run's duration.
		MinDwell: 8 * time.Second,
	})
	if err != nil {
		return err
	}

	var mu sync.Mutex
	inFlight := map[uint32]pending{}
	var nextSeq uint32
	var sentInWindow, ackedInWindow int
	var rttSumMs float64

	go func() {
		<-ctx.Done()
		_ = conn.Close()
	}()

	// Reader: unwrap echoes, match them to the send map, record RTT.
	go func() {
		buf := make([]byte, 2048)
		for {
			n, err := conn.Read(buf)
			if err != nil {
				return
			}
			payload, isDecoy, err := shaper.Unwrap(buf[:n])
			if err != nil || isDecoy || len(payload) < 4 {
				continue
			}
			seq := binary.BigEndian.Uint32(payload[:4])
			mu.Lock()
			if p, ok := inFlight[seq]; ok {
				rttSumMs += float64(time.Since(p.sentAt).Microseconds()) / 1000.0
				ackedInWindow++
				delete(inFlight, seq)
			}
			mu.Unlock()
		}
	}()

	sendTicker := time.NewTicker(20 * time.Millisecond)
	defer sendTicker.Stop()
	observeTicker := time.NewTicker(2 * time.Second)
	defer observeTicker.Stop()
	sweepTicker := time.NewTicker(1 * time.Second)
	defer sweepTicker.Stop()

	fmt.Println("aiobfs demo: client -> censor -> server over real loopback UDP")
	fmt.Println("watch the [status] lines: profile + weights should shift when the censor escalates blocking")
	fmt.Println()

	for {
		select {
		case <-ctx.Done():
			fmt.Println("\ndemo finished")
			return nil

		case <-sendTicker.C:
			seq := atomic.AddUint32(&nextSeq, 1)
			payload := make([]byte, 4, 4+64)
			binary.BigEndian.PutUint32(payload[:4], seq)
			payload = append(payload, []byte("aiobfs demo tunnel payload......")...)

			wire, err := shaper.Wrap(payload)
			if err == nil {
				if _, err := conn.Write(wire); err == nil {
					mu.Lock()
					inFlight[seq] = pending{sentAt: time.Now()}
					sentInWindow++
					mu.Unlock()
				}
			}
			if decoy, ok, _ := shaper.MaybeDecoy(); ok {
				_, _ = conn.Write(decoy)
			}

		case <-sweepTicker.C:
			// Anything still unacked after ~1s counts as lost for the
			// purposes of this demo's loss-rate estimate.
			cutoff := time.Now().Add(-time.Second)
			mu.Lock()
			for seq, p := range inFlight {
				if p.sentAt.Before(cutoff) {
					delete(inFlight, seq)
				}
			}
			mu.Unlock()

		case <-observeTicker.C:
			mu.Lock()
			sent, acked, rttSum := sentInWindow, ackedInWindow, rttSumMs
			sentInWindow, ackedInWindow, rttSumMs = 0, 0, 0
			mu.Unlock()

			lossRate := 0.0
			avgRTT := 0.0
			if sent > 0 {
				lossRate = 1 - float64(acked)/float64(sent)
			}
			if lossRate < 0 {
				// An ack can land in the window after the one it was sent
				// in (censor-induced delay); clamp rather than show a
				// nonsensical negative loss rate.
				lossRate = 0
			}
			if acked > 0 {
				avgRTT = rttSum / float64(acked)
			} else {
				avgRTT = 300 // no acks at all this window: treat as worst-case RTT
			}

			// Measured under whichever profile was actually active during
			// the interval we just aggregated — capture that name *before*
			// Observe() potentially switches it, so the printed line
			// correctly reads "here's how profile X performed, and here's
			// what we're switching to as a result" rather than attributing
			// this window's numbers to the new profile.
			measuredUnder := shaper.Stats().ProfileName
			switched := shaper.Observe(avgRTT, lossRate, 0)
			st := shaper.Stats()
			line := fmt.Sprintf("[status] measured_under=%-18s rtt=%6.1fms loss=%4.0f%% weights=%s",
				measuredUnder, avgRTT, lossRate*100, formatWeights(st.BanditWeights))
			if switched {
				line += fmt.Sprintf("  <-- switching to %s", st.ProfileName)
			}
			fmt.Println(line)
		}
	}
}

func formatWeights(w []float64) string {
	profiles := aiobfsStandardProfileNames()
	s := "["
	for i, v := range w {
		if i > 0 {
			s += " "
		}
		name := fmt.Sprintf("p%d", i)
		if i < len(profiles) {
			name = profiles[i]
		}
		s += fmt.Sprintf("%s=%.2f", name, v)
	}
	return s + "]"
}

func aiobfsStandardProfileNames() []string {
	profiles := aiobfs.StandardProfiles()
	names := make([]string, len(profiles))
	for i, p := range profiles {
		names[i] = p.Name
	}
	return names
}

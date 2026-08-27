// Package aiobfs implements an adaptive traffic-masking layer for tunnel
// cores such as vk-turn-proxy. It disguises tunnel packets as one of several
// realistic real-time-media traffic patterns (RTP-like headers, per-profile
// packet-size/timing/padding statistics, occasional decoy packets) and uses
// a small online-learning controller to keep switching between disguises —
// biased toward whichever one currently measures faster and less lossy on
// the path — instead of committing to a single static fingerprint for the
// life of the connection.
//
// This is deliberately NOT a large language model in the packet path: an
// LLM call per packet would add tens to hundreds of milliseconds of latency,
// which defeats both the "hide" and the "speed up" goals. What actually
// works for this problem (and what real DPI-evasion research such as
// obfs4/Snowflake/format-transforming-encryption relies on) is a lightweight
// statistical/ML model that runs in microseconds. See core/README.md for the
// full rationale and how to wire this into a real TURN client/server.
package aiobfs

import "time"

// Profile describes the statistical shape of one disguise: how packets of
// that "kind" of traffic tend to look on the wire. Wrap() samples from these
// distributions instead of using fixed sizes/timings, so consecutive packets
// within a single profile still vary — and switching profiles (driven by
// Shaper, see shaper.go) changes the shape altogether.
type Profile struct {
	// Name identifies the profile (used for logging/metrics only).
	Name string

	// PayloadType is placed in the RTP-like header, mirroring real dynamic
	// payload type numbers WebRTC clients negotiate for these media kinds.
	PayloadType uint8

	// PacketBytesMean/StdDev model the ciphertext+header size a real codec
	// operating in this mode tends to produce for one frame/packet. Wrap()
	// uses this only to pick a padding target — it never truncates the
	// caller's actual payload.
	PacketBytesMean int
	PacketBytesStd  int

	// SendInterval is the nominal spacing between packets (e.g. 20ms frames
	// for Opus audio); IntervalJitter adds proportional random jitter, since
	// real media pacing is never perfectly periodic.
	SendInterval   time.Duration
	IntervalJitter float64 // fraction of SendInterval, e.g. 0.15 = ±15%

	// PaddingMax bounds the random padding (in bytes) appended after the
	// AEAD ciphertext so the wire size approximates PacketBytesMean instead
	// of leaking the true plaintext length.
	PaddingMax int

	// DecoyProbability is the chance, per send, of also emitting a small
	// keepalive-shaped decoy packet (silence/comfort-noise frames, RTCP
	// receiver reports) that carries no tunnel payload — real WebRTC
	// sessions produce exactly this kind of "empty" traffic.
	DecoyProbability float64
	DecoyBytesMean   int
}

// StandardProfiles returns the built-in disguise set. Order is fixed and
// significant: Shaper indexes profiles positionally (bandit arm i <->
// StandardProfiles()[i]), so callers must not reorder this slice across a
// running session — persisted bandit/policy state assumes stable indices.
func StandardProfiles() []Profile {
	return []Profile{
		{
			Name:             "audio_opus",
			PayloadType:      111,
			PacketBytesMean:  110,
			PacketBytesStd:   40,
			SendInterval:     20 * time.Millisecond,
			IntervalJitter:   0.10,
			PaddingMax:       48,
			DecoyProbability: 0.05,
			DecoyBytesMean:   24,
		},
		{
			Name:             "video_low_motion",
			PayloadType:      96,
			PacketBytesMean:  350,
			PacketBytesStd:   150,
			SendInterval:     33 * time.Millisecond,
			IntervalJitter:   0.20,
			PaddingMax:       120,
			DecoyProbability: 0.03,
			DecoyBytesMean:   40,
		},
		{
			Name:             "video_high_motion",
			PayloadType:      96,
			PacketBytesMean:  900,
			PacketBytesStd:   400,
			SendInterval:     16 * time.Millisecond,
			IntervalJitter:   0.25,
			PaddingMax:       200,
			DecoyProbability: 0.02,
			DecoyBytesMean:   40,
		},
		{
			Name:             "screen_share",
			PayloadType:      100,
			PacketBytesMean:  1100,
			PacketBytesStd:   500,
			SendInterval:     66 * time.Millisecond,
			IntervalJitter:   0.35,
			PaddingMax:       220,
			DecoyProbability: 0.02,
			DecoyBytesMean:   32,
		},
		{
			Name:             "idle_keepalive",
			PayloadType:      13, // RFC 3551 CN (comfort noise) PT, used by real clients during silence
			PacketBytesMean:  32,
			PacketBytesStd:   10,
			SendInterval:     200 * time.Millisecond,
			IntervalJitter:   0.5,
			PaddingMax:       24,
			DecoyProbability: 0.15,
			DecoyBytesMean:   20,
		},
	}
}

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
// full rationale — including the honest limits of this approach against a
// classifier that was itself trained on captured traffic from this exact
// scheme, and how EmpiricalIntervals lets you close that gap with real data.
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

	// PayloadTypes lists the RTP-like payload-type numbers plausible for
	// this media kind (real dynamic payload types WebRTC clients negotiate
	// span roughly 96-127, plus a few well-known static ones like 13 for
	// comfort noise). Shaper picks ONE of these at random each time this
	// profile is (re)activated and keeps it fixed until the next
	// activation — real SDP negotiates a payload type once per call and
	// does not change it mid-session, so a PT that flips on every packet
	// would itself be a tell. Having more than one candidate means a
	// classifier can't key on "this tool always uses PT 111."
	PayloadTypes []uint8

	// PacketBytesMean/StdDev model the ciphertext+header size a real codec
	// operating in this mode tends to produce for one frame/packet. Wrap()
	// uses this only as a fallback padding target when EmpiricalIntervals
	// isn't calibrated with real size data — it never truncates the
	// caller's actual payload.
	PacketBytesMean int
	PacketBytesStd  int

	// SendInterval is the nominal spacing between packets (e.g. 20ms frames
	// for Opus audio); IntervalJitter adds proportional random jitter, since
	// real media pacing is never perfectly periodic. Used only when
	// EmpiricalIntervals is empty.
	SendInterval   time.Duration
	IntervalJitter float64 // fraction of SendInterval, e.g. 0.15 = ±15%

	// EmpiricalIntervals, when non-empty, overrides SendInterval/
	// IntervalJitter: each packet's pacing is bootstrap-sampled (drawn
	// uniformly at random, with replacement) from this slice instead of
	// computed from the synthetic formula. This is the calibration path —
	// a synthetic Gaussian-ish jitter model is a reasonable default, but a
	// classifier trained specifically against this tool can learn its
	// exact shape; feeding it inter-packet-arrival times measured from a
	// real capture of the traffic you're disguising as removes that
	// specific weakness. See LoadDurationsFile for one way to build this
	// slice from a capture, and core/README.md for the full workflow.
	EmpiricalIntervals []time.Duration

	// PaddingMax bounds the random padding (in bytes) appended after the
	// AEAD ciphertext so the wire size approximates PacketBytesMean instead
	// of leaking the true plaintext length. The padding amount itself
	// follows an autocorrelated random walk (see driftState in shaper.go),
	// not an independent draw per packet — real codec frame sizes are
	// autocorrelated (rate control, motion compensation carry over from
	// frame to frame), so independent-per-packet padding would itself be
	// an unnatural, and therefore learnable, statistical signature.
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
			PayloadTypes:     []uint8{96, 101, 105, 109, 111, 113, 120},
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
			PayloadTypes:     []uint8{96, 98, 100, 102, 104, 106, 108},
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
			PayloadTypes:     []uint8{96, 98, 100, 102, 104, 106, 108},
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
			PayloadTypes:     []uint8{97, 99, 103, 107, 122, 126},
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
			PayloadTypes:     []uint8{13}, // RFC 3551 CN (comfort noise) — a real, well-known static PT
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

package aiobfs

import (
	"crypto/rand"
	"encoding/binary"
	"math"
	mrand "math/rand/v2"
	"sync"
)

// cryptoSeededSource seeds math/rand/v2's PCG generator from crypto/rand so
// sequences derived from it (bandit arm draws, packet-shape sampling) are
// not predictable to an outside observer fingerprinting the traffic.
func cryptoSeededSource() *mrand.PCG {
	var seed [16]byte
	if _, err := rand.Read(seed[:]); err != nil {
		// crypto/rand failing is effectively unheard of on any real target
		// platform; fall back to a fixed seed rather than panicking a
		// packet-path constructor.
		binary.BigEndian.PutUint64(seed[:8], 0x9E3779B97F4A7C15)
		binary.BigEndian.PutUint64(seed[8:], 0xC2B2AE3D27D4EB4F)
	}
	return mrand.NewPCG(binary.BigEndian.Uint64(seed[:8]), binary.BigEndian.Uint64(seed[8:]))
}

// pseudoRand is a small helper around math/rand/v2 for the non-cryptographic
// randomness used to sample packet shapes (sizes, jitter, decoys) and to
// initialize policyNet weights. It is seeded unpredictably (see above) but
// is not itself relied on for any cryptographic property — actual
// encryption uses crypto/aes + crypto/cipher.
//
// math/rand/v2's Rand is explicitly documented as unsafe for concurrent
// use, but Shaper's public API (Wrap/MaybeDecoy from a send loop, Observe
// from a separate periodic-measurement goroutine) is a perfectly reasonable
// way to call it that would otherwise race on this generator. A single
// mutex here is cheap enough not to matter next to AEAD sealing and RTP
// framing, and it removes that whole footgun for callers.
type pseudoRand struct {
	mu sync.Mutex
	r  *mrand.Rand
}

func newPseudoRand() *pseudoRand {
	return &pseudoRand{r: mrand.New(cryptoSeededSource())}
}

// smallWeight returns a small value in [-0.5, 0.5), used to break symmetry
// when initializing policyNet weights.
func (p *pseudoRand) smallWeight() float64 {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.r.Float64() - 0.5
}

// float64 returns a value in [0, 1).
func (p *pseudoRand) float64() float64 {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.r.Float64()
}

// normal returns a sample from N(mean, stddev) via the Box-Muller
// transform, clamped to be >= min so callers get usable byte counts instead
// of occasional negative sizes from the tail of the distribution.
func (p *pseudoRand) normal(mean, stddev float64, min int) int {
	if stddev <= 0 {
		return int(mean)
	}
	p.mu.Lock()
	u1 := p.r.Float64()
	if u1 <= 1e-12 {
		u1 = 1e-12
	}
	u2 := p.r.Float64()
	p.mu.Unlock()

	z := math.Sqrt(-2*math.Log(u1)) * math.Cos(2*math.Pi*u2)
	v := int(mean + z*stddev)
	if v < min {
		v = min
	}
	return v
}

// gaussian returns one standard-normal (mean 0, stddev 1) sample.
func (p *pseudoRand) gaussian() float64 {
	p.mu.Lock()
	u1 := p.r.Float64()
	if u1 <= 1e-12 {
		u1 = 1e-12
	}
	u2 := p.r.Float64()
	p.mu.Unlock()
	return math.Sqrt(-2*math.Log(u1)) * math.Cos(2*math.Pi*u2)
}

// intn returns a value in [0, n).
func (p *pseudoRand) intn(n int) int {
	if n <= 0 {
		return 0
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	return int(p.r.Int64N(int64(n)))
}

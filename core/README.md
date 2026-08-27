# core/aiobfs — adaptive AI traffic masking

This directory adds an **adaptive, learning-based traffic-disguise layer**
for a tunnel core such as the one this Android app is designed to drive.
It is a standalone Go module (`core/go.mod`) with no external dependencies —
only the Go standard library — so it builds and cross-compiles as easily as
any other part of this project.

```
core/
  aiobfs/            the library: profiles, bandit, policy net, wrap/unwrap
  cmd/aiobfs-demo/    a runnable end-to-end demo over real loopback UDP
```

## What problem this solves

The rest of this repository (see the root [README](../README.md)) is an
Android UI that launches an **externally supplied** tunnel binary — the
user imports a "kernel" (`libcustom_kernel.so`) built from a project like
[vk-turn-proxy](https://github.com/cacggghp/vk-turn-proxy), which handles
the actual VK-call credential negotiation, STUN/TURN relay, and DTLS
tunnel. That engine already disguises tunnel packets as WebRTC media (an
RTP-like header, audio/video payload types) so casual inspection sees "a
video call," not "a VPN." That disguise is **static** for the life of a
connection: pick "audio" or "video" once, and every packet looks like that
one thing until the process restarts.

The ask this module answers is: make that disguise **keep changing shape**,
automatically, based on how well the current shape is actually working —
so a network that has learned to fingerprint one specific traffic pattern
loses that advantage, and so the tunnel can steer itself toward whichever
disguise currently measures fastest instead of committing to a guess.

## Why this is a small neural net + bandit, not a large language model

The original ask mentioned embedding "a big AI" in the chain. A literal LLM
call per packet was deliberately **not** what got built, for a concrete
reason: an LLM inference call costs tens to hundreds of milliseconds. Doing
that per packet (or even per burst) would make the tunnel *slower*, which
directly contradicts "speed up the internet" — one of the two goals in the
original request. It would also add a large, complex, hard-to-audit
dependency to a security-sensitive packet path.

What actually solves "traffic should keep changing shape, favor whichever
shape is fastest right now, and adapt when a censor starts targeting a
shape" is the family of techniques real circumvention tools use (obfs4,
Snowflake, format-transforming encryption, traffic morphing research): a
lightweight **statistical/ML model that runs in microseconds**. That's
what `aiobfs` is:

- **`profile.go`** — five disguise profiles (`audio_opus`, `video_low_motion`,
  `video_high_motion`, `screen_share`, `idle_keepalive`), each with
  realistic packet-size, timing, padding, and decoy-traffic statistics for
  that kind of real-time media.
- **`bandit.go`** — a multi-armed bandit (a baseline-subtracted variant of
  EXP3, chosen because it's designed for *adversarial*, not just random,
  reward — exactly the situation of a censor actively trying to penalize
  whichever disguise it has detected) that tracks which profile has
  actually been paying off recently, in the "did this get through fast and
  unthrottled" sense.
- **`policy.go`** — a tiny (5 input → 8 hidden → N output) feed-forward
  network, trained online with REINFORCE policy-gradient updates, that
  biases profile selection based on *current* measured conditions (RTT,
  loss, throughput, time-since-last-switch) rather than only historical
  averages.
- **`shaper.go`** — ties it together: `Wrap`/`Unwrap` do the actual
  RTP-shaped, AES-256-GCM-sealed, randomly-padded framing (with a
  fixed-size FIFO replay guard), `MaybeDecoy` emits keepalive-shaped decoy
  traffic so idle periods don't visibly "go quiet," and `Observe` feeds
  back path measurements to train both learners and, at most once per
  `MinDwell`, switch the active disguise.

A forward pass through the whole thing (bandit distribution + NN
inference) is a few dozen floating-point multiply-adds — real work, real
learning, but not a latency source.

## Security properties

- **AEAD**: every packet (including decoys) is sealed with AES-256-GCM;
  the 12-byte RTP-like header doubles as both the AEAD nonce and its
  associated data, so header tampering is caught by authentication, not
  just payload tampering.
- **Replay protection**: a bounded FIFO window rejects any exact header
  seen before.
- **Decoys are truly indistinguishable pre-decryption**: a decoy is a
  normal AEAD-sealed packet with a marker byte *inside* the ciphertext; an
  observer (or even the receiving code, before it authenticates and opens
  the packet) cannot tell a decoy from real tunnel data by looking at the
  wire bytes.
- **Key derivation**: `Config.Key` is hashed with SHA-256 before use, so
  callers can pass a passphrase of any length (it should still be a real
  high-entropy secret shared out-of-band — this is not a KDF meant to
  stretch a weak password).

See `aiobfs/shaper_test.go` for round-trip, tamper-rejection, replay, and
cross-key tests, and `aiobfs/bandit_test.go` / `aiobfs/policy_test.go` for
the learning components.

## Try it

```sh
cd core
go test ./...              # unit tests (round-trip crypto, bandit/NN learning behavior)
go run ./cmd/aiobfs-demo    # live demo: client+server+"censor" over real loopback UDP
```

The demo starts a client and server talking through a simulated DPI
middlebox that begins blocking the `audio_opus` disguise's fingerprint
partway through the run, then blocks the video fingerprint too. Watch the
`[status]` lines: loss/RTT spikes the moment a disguise gets blocked, the
bandit's weight for that profile visibly drops, and the shaper switches —
without any explicit renegotiation between client and server, since both
sides derive their decisions independently from what they each observe.

## Integrating this into an actual TURN/DTLS tunnel core

This module intentionally does **not** reimplement VK-call credential
scraping, STUN/TURN relaying, or DTLS — that's a large, already-solved,
security-sensitive piece of engineering that exists in mature form in
community forks of vk-turn-proxy, and duplicating it here would add risk
(subtly wrong VK API handling, captcha bypass logic, etc.) without adding
value. Instead, `aiobfs.Shaper` is written to be a **drop-in replacement
for a static per-packet obfuscation layer** such an engine already has —
typically a file named something like `obfs.go` with
`obfsWrapPacket`/`obfsUnwrapPacket` functions. Wiring it in looks like:

```go
shaper, err := aiobfs.New(aiobfs.Config{
    Key: wrapKey, // the same pre-shared/derived key both ends already use
})

// Wherever the engine currently calls its static wrap function before
// writing to the TURN/UDP socket:
wire, err := shaper.Wrap(plaintextPacket)

// Wherever it currently calls its static unwrap function after reading:
plaintext, isDecoy, err := shaper.Unwrap(wireBytes)
if isDecoy {
    continue // never forward decoy traffic into WireGuard/the tunnel
}

// Wherever the engine already measures RTT/loss (a ping loop, ACK
// tracking, WorkerGroup stats) — feed it in periodically, e.g. every 2-10s:
switched := shaper.Observe(rttMs, lossRate, throughputBps)

// And periodically (e.g. once per send tick), optionally mix in decoy
// traffic so idle spans don't look like a tunnel that "goes quiet":
if decoy, ok, _ := shaper.MaybeDecoy(); ok {
    sendOnWire(decoy)
}
```

Both ends of a connection need the same `Key` and the same `Profiles`
list (in the same order — profile identity is implicit in which shape a
packet matches, never sent as an explicit index on the wire).

## Honest limitations

- This is a shape-and-timing obfuscation layer, not a full protocol
  implementation. It does not do NAT traversal, VK credential negotiation,
  or DTLS — it assumes something else already got two endpoints a shared
  key and a way to exchange UDP packets (which is exactly what the
  existing TURN/DTLS engine this app supports already provides).
- The bandit's baseline-subtracted update (see the comment in `bandit.go`)
  is a deliberate deviation from textbook EXP3 for faster real-world
  adaptation; it no longer carries EXP3's formal worst-case regret bound.
  That trade was made after observing (in this module's own demo) that
  vanilla EXP3's "weight never decreases" property made recovery from a
  freshly-throttled profile unacceptably slow and luck-dependent.
- Five built-in profiles are provided; real deployments would benefit from
  measuring actual WebRTC traffic on the target network to calibrate
  `Profile` parameters more precisely, and from adding more profiles.
- This targets the same use case the rest of this repository already
  targets: helping a client reach a server across a network that
  restricts or throttles VPN-shaped traffic. It is not intended, and
  should not be used, to hide traffic from a network's own legitimate
  security monitoring on infrastructure you don't control or aren't
  authorized to circumvent controls on.

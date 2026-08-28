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
  `video_high_motion`, `screen_share`, `idle_keepalive`), each with a
  candidate list of realistic payload-type numbers, plus packet-size,
  timing, padding, and decoy-traffic statistics for that kind of real-time
  media.
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
  RTP-shaped, AES-256-GCM-sealed framing (with a fixed-size FIFO replay
  guard), padded by an autocorrelated random-walk drift rather than
  independent per-packet noise (see "Against ML-based classifiers" below),
  `MaybeDecoy` emits keepalive-shaped decoy traffic so idle periods don't
  visibly "go quiet," and `Observe` feeds back path measurements to train
  both learners and, at most once per `MinDwell`, switch the active
  disguise (which also re-picks that profile's payload type).
- **`autonomous.go`** — an optional zero-configuration mode: `SendProbe`/
  `PendingPong`/`RunAutonomous` let the Shaper measure its own RTT and loss
  via self-generated probe/pong packets and drive `Observe` itself, so a
  host application doesn't have to implement a ping loop at all. See
  "Autonomous self-monitoring" below.
- **`trace.go`** — `LoadDurationsFile` loads real captured inter-packet
  timing into a `Profile.EmpiricalIntervals` for calibration against actual
  traffic instead of the synthetic timing model.
- **`trial.go`** — `TryUnwrap(keys, wire)` is a stateless multi-key
  bootstrap helper for a server that supports several active
  passwords/keys at once: try each candidate until one authenticates a new
  connection's first packet, then build a persistent per-connection
  `Shaper` with the winning key for everything after. Mirrors the
  multi-password support most tunnel cores already have for their static
  obfuscation layer.

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

## Against ML-based classifiers: what this addresses, what it doesn't

A fair objection to any traffic-disguise scheme: the network doing the
blocking may itself be running an ML classifier, not simple rules — so
"disguise the traffic" isn't automatically a win. Three concrete design
choices in this module answer that, and one gap is left honestly open:

- **Reacting to outcomes, not out-thinking the classifier.** The EXP3
  bandit's regret bound holds against an *arbitrarily intelligent*
  adversary by construction — it never assumes anything about how the
  other side decides what to flag, only that flagging shows up as worse
  throughput/loss. That's why this is a bandit and not a hand-tuned rule
  list: it doesn't need to know the classifier is ML-based to route around
  it, it just needs the classifier's actions to eventually cost it
  something measurable.
- **No single learnable payload-type constant.** Early versions of this
  module hardcoded one payload type per profile — an obvious feature for a
  classifier to key on. `Profile.PayloadTypes` is now a candidate list;
  Shaper picks one per profile *activation* (matching how real SDP
  negotiates a PT once per call) instead of per packet, so there's no
  single number that "is" the audio disguise.
- **Autocorrelated padding, not i.i.d. padding.** The first implementation
  drew each packet's padding independently — but real codec frame sizes
  are autocorrelated (rate control and motion compensation carry state
  frame to frame), so per-packet-independent padding is itself an
  unnatural, and therefore learnable, statistical signature. Padding now
  follows a bounded mean-reverting random walk (`shaper.go`'s `padDrift`),
  which produces the same "still varies every packet" property with a
  realistic autocorrelation structure instead (see
  `TestPaddingIsAutocorrelatedNotIID`).
- **The open gap: synthetic ≠ real.** `Profile`'s default packet-size/
  timing parameters are hand-picked Gaussians, not measurements. A
  classifier trained specifically on *this tool's* traffic (rather than on
  WebRTC in general) could in principle still learn the difference between
  a real call and this synthetic approximation of one — that's a known,
  general limitation of any hand-designed traffic-morphing scheme, not
  something bandit adaptation fixes. `Profile.EmpiricalIntervals` (loaded
  via `LoadDurationsFile`) exists specifically to close this gap with real
  data: capture actual traffic of the kind you're imitating —
  `tshark -r capture.pcapng -Y "udp.port==<port>" -T fields -e frame.time_delta_displayed > gaps.txt`
  — and assign the result to a profile instead of relying on the synthetic
  formula. Packet-size calibration from real captures is not implemented
  yet (contributions welcome); timing was prioritized because it's the
  harder property to fake after the fact (padding can adjust size at wrap
  time, but genuine send timing is driven by the application above it).
- Neither of these makes the disguise unbeatable against a sufficiently
  resourced adversary — active probing (connecting to your server to check
  it behaves like a real one) and TLS/DTLS handshake fingerprinting are
  both still open attack surfaces this module doesn't address. The
  realistic goal is raising the cost and false-positive rate of
  classification, not guaranteeing undetectability.

## Autonomous self-monitoring (no manual Observe() needed)

The API described above expects the host application to measure RTT/loss
itself and call `Observe()`. For a host that would rather not implement a
ping loop, `Shaper` can measure its own path health via self-generated
probe/pong packets and drive `Observe` internally:

```go
shaper, _ := aiobfs.New(aiobfs.Config{Key: wrapKey})

// send moves wire bytes to the peer exactly like Wrap()ed traffic (e.g. a
// UDP conn.Write). RunAutonomous calls it on its own schedule.
stop := shaper.RunAutonomous(ctx, send, 2*time.Second)
defer stop()

// Receive loop: unchanged except PendingPong. Unwrap already treats
// probes/pongs as non-data (isDecoy=true), so old code that skips decoys
// keeps working without modification.
for {
    wire := receiveFromPeer()
    payload, isDecoy, err := shaper.Unwrap(wire)
    if err != nil {
        continue
    }
    if pong, ok := shaper.PendingPong(); ok {
        send(pong) // answer the peer's own probe — one extra line
    }
    if isDecoy {
        continue
    }
    forwardIntoTunnel(payload)
}
```

This is entirely additive: a caller with its own RTT/loss telemetry (a
WireGuard handshake timer, an existing ping loop) can keep calling
`Observe()` directly and ignore `RunAutonomous`/`SendProbe`/`PendingPong`
altogether.

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
  adding more, and from calibrating timing via `EmpiricalIntervals`/
  `LoadDurationsFile` (see "Against ML-based classifiers" above).
  Packet-size calibration from real captures isn't implemented yet — only
  timing is.
- This targets the same use case the rest of this repository already
  targets: helping a client reach a server across a network that
  restricts or throttles VPN-shaped traffic. It is not intended, and
  should not be used, to hide traffic from a network's own legitimate
  security monitoring on infrastructure you don't control or aren't
  authorized to circumvent controls on.

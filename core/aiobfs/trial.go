package aiobfs

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"errors"
	"fmt"
)

// TryUnwrap attempts to authenticate and decrypt wire against each
// candidate key in turn (each treated exactly like Config.Key — any-length
// passphrase or pre-shared material, SHA-256-hashed internally), returning
// the first one that verifies.
//
// This is a stateless, replay-unaware primitive meant only for a server's
// connection-bootstrap step — "which of my N active passwords does this
// new, not-yet-associated peer belong to" — mirroring the multi-password
// support many tunnel cores already have (try each active key until one
// authenticates the first packet from a new remote address). Once
// TryUnwrap identifies the key, construct a persistent *Shaper via New
// with that same key for the rest of that connection's traffic; that
// Shaper carries full replay protection, this function does not (calling
// it repeatedly for an ongoing connection provides no replay protection by
// itself, and is also needlessly slow — O(number of keys) per packet
// instead of the O(1) a per-connection Shaper gives you).
func TryUnwrap(keys [][]byte, wire []byte) (matchedKey, payload []byte, isDecoy bool, err error) {
	if len(keys) == 0 {
		return nil, nil, false, errors.New("aiobfs: no candidate keys")
	}

	// Parsing the frame (header/padding validation) doesn't depend on the
	// key, so do it once rather than once per candidate.
	header, ciphertext, err := parseWireFrame(wire, minAEADOverhead)
	if err != nil {
		return nil, nil, false, err
	}

	var lastErr error
	for _, k := range keys {
		aead, aeadErr := trialAEAD(k)
		if aeadErr != nil {
			lastErr = aeadErr
			continue
		}
		if len(ciphertext) <= aead.Overhead() {
			lastErr = errors.New("aiobfs: no payload after stripping header/padding")
			continue
		}
		marker, p, uErr := openFrame(aead, header, ciphertext)
		if uErr != nil {
			lastErr = uErr
			continue
		}
		return append([]byte(nil), k...), p, marker != markerData, nil
	}
	if lastErr == nil {
		lastErr = errors.New("aiobfs: authentication failed for all candidate keys")
	}
	return nil, nil, false, fmt.Errorf("aiobfs: no candidate key matched: %w", lastErr)
}

// minAEADOverhead is used for the key-independent framing check in
// TryUnwrap — both AES-256-GCM (what this package actually uses) and any
// other 128-bit-tag AEAD have a 16-byte overhead, so this is a
// conservative lower bound that only rejects frames too short to possibly
// contain a valid ciphertext+tag under any candidate key.
const minAEADOverhead = 16

func trialAEAD(key []byte) (cipher.AEAD, error) {
	sum := sha256.Sum256(key)
	block, err := aes.NewCipher(sum[:])
	if err != nil {
		return nil, fmt.Errorf("aiobfs: cipher init: %w", err)
	}
	return cipher.NewGCM(block)
}

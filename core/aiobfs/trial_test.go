package aiobfs

import (
	"bytes"
	"testing"
)

func TestTryUnwrapFindsMatchingKey(t *testing.T) {
	keyA := []byte("password-A")
	keyB := []byte("password-B")
	keyC := []byte("password-C")

	sender, err := New(Config{Key: keyB})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	wire, err := sender.Wrap([]byte("hello server"))
	if err != nil {
		t.Fatalf("Wrap: %v", err)
	}

	matched, payload, isDecoy, err := TryUnwrap([][]byte{keyA, keyB, keyC}, wire)
	if err != nil {
		t.Fatalf("TryUnwrap: %v", err)
	}
	if isDecoy {
		t.Fatalf("real data packet misidentified as decoy")
	}
	if !bytes.Equal(matched, keyB) {
		t.Fatalf("matched key = %q, want %q", matched, keyB)
	}
	if string(payload) != "hello server" {
		t.Fatalf("payload = %q, want %q", payload, "hello server")
	}
}

func TestTryUnwrapNoKeyMatches(t *testing.T) {
	sender, err := New(Config{Key: []byte("real-password")})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	wire, err := sender.Wrap([]byte("x"))
	if err != nil {
		t.Fatalf("Wrap: %v", err)
	}

	_, _, _, err = TryUnwrap([][]byte{[]byte("wrong-1"), []byte("wrong-2")}, wire)
	if err == nil {
		t.Fatalf("expected an error when no candidate key matches")
	}
}

func TestTryUnwrapEmptyKeyList(t *testing.T) {
	if _, _, _, err := TryUnwrap(nil, make([]byte, 40)); err == nil {
		t.Fatalf("expected an error for an empty key list")
	}
}

// TestTryUnwrapThenPersistentShaperMatches checks the intended real usage
// pattern: TryUnwrap identifies the key for a connection's first packet,
// then a persistent Shaper built with that key correctly continues the
// exchange (including packets after the first, which TryUnwrap never
// sees).
func TestTryUnwrapThenPersistentShaperMatches(t *testing.T) {
	password := []byte("shared-password")
	client, err := New(Config{Key: password})
	if err != nil {
		t.Fatalf("New client: %v", err)
	}

	first, err := client.Wrap([]byte("first packet"))
	if err != nil {
		t.Fatalf("Wrap: %v", err)
	}
	matchedKey, payload, _, err := TryUnwrap([][]byte{[]byte("decoy-password"), password}, first)
	if err != nil {
		t.Fatalf("TryUnwrap: %v", err)
	}
	if string(payload) != "first packet" {
		t.Fatalf("first packet payload = %q", payload)
	}

	server, err := New(Config{Key: matchedKey})
	if err != nil {
		t.Fatalf("New server: %v", err)
	}

	second, err := client.Wrap([]byte("second packet"))
	if err != nil {
		t.Fatalf("Wrap second: %v", err)
	}
	payload2, _, err := server.Unwrap(second)
	if err != nil {
		t.Fatalf("server.Unwrap(second): %v", err)
	}
	if string(payload2) != "second packet" {
		t.Fatalf("second packet payload = %q", payload2)
	}
}

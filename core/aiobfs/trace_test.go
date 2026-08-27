package aiobfs

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestLoadDurationsFileParsesSecondsIgnoringCommentsAndBlanks(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "gaps.txt")
	content := "# captured inter-packet gaps, seconds\n0.020\n\n0.0215\n# a comment in the middle\n0.019\n"
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("writeFile: %v", err)
	}

	got, err := LoadDurationsFile(path)
	if err != nil {
		t.Fatalf("LoadDurationsFile: %v", err)
	}
	want := []time.Duration{20 * time.Millisecond, 21500 * time.Microsecond, 19 * time.Millisecond}
	if len(got) != len(want) {
		t.Fatalf("got %d durations, want %d: %v", len(got), len(want), got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("duration %d: got %v want %v", i, got[i], want[i])
		}
	}
}

func TestLoadDurationsFileRejectsGarbageLine(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "gaps.txt")
	if err := os.WriteFile(path, []byte("0.02\nnot-a-number\n"), 0o600); err != nil {
		t.Fatalf("writeFile: %v", err)
	}
	if _, err := LoadDurationsFile(path); err == nil {
		t.Fatalf("expected an error for a non-numeric line")
	}
}

func TestLoadDurationsFileRejectsEmptyResult(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "gaps.txt")
	if err := os.WriteFile(path, []byte("# only comments\n\n"), 0o600); err != nil {
		t.Fatalf("writeFile: %v", err)
	}
	if _, err := LoadDurationsFile(path); err == nil {
		t.Fatalf("expected an error when the file has no samples")
	}
}

func TestLoadDurationsFileMissingFile(t *testing.T) {
	if _, err := LoadDurationsFile(filepath.Join(t.TempDir(), "does-not-exist.txt")); err == nil {
		t.Fatalf("expected an error for a missing file")
	}
}

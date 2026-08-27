package aiobfs

import (
	"bufio"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// LoadDurationsFile reads a text file with one number of seconds (may be
// fractional) per line — the format produced by, for example:
//
//	tshark -r capture.pcapng -Y "udp.port==<the media port>" \
//	    -T fields -e frame.time_delta_displayed > gaps.txt
//
// run against a real capture of the traffic a Profile is meant to imitate
// (an actual VK/WebRTC call, captured on a machine you control). Blank
// lines and lines starting with '#' are ignored. The result is meant to be
// assigned directly to Profile.EmpiricalIntervals.
//
// This is the calibration path described in core/README.md: a Profile's
// SendInterval/IntervalJitter fields are a reasonable synthetic default
// (independent per-packet jitter around a fixed mean), but that is an
// exact, simple shape — a classifier trained specifically against this
// tool's traffic, rather than against real WebRTC in general, could learn
// to tell the two apart. Feeding it real inter-packet gaps captured from
// genuine traffic of the kind being imitated removes that specific
// weakness, because the timing is then not a synthetic approximation at
// all — it's a resampling of real observations.
func LoadDurationsFile(path string) ([]time.Duration, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("aiobfs: open %s: %w", path, err)
	}
	defer f.Close()

	var out []time.Duration
	scanner := bufio.NewScanner(f)
	lineNo := 0
	for scanner.Scan() {
		lineNo++
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		seconds, err := strconv.ParseFloat(line, 64)
		if err != nil {
			return nil, fmt.Errorf("aiobfs: %s:%d: invalid duration %q: %w", path, lineNo, line, err)
		}
		if seconds < 0 {
			return nil, fmt.Errorf("aiobfs: %s:%d: negative duration %q", path, lineNo, line)
		}
		out = append(out, time.Duration(seconds*float64(time.Second)))
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("aiobfs: read %s: %w", path, err)
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("aiobfs: %s contains no duration samples", path)
	}
	return out, nil
}

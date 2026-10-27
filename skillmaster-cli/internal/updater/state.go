package updater

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

// Throttle is how often a machine checks at most.
//
// **A day is long enough that nobody notices the request and short enough that nobody waits a week
// for a fix.** The state file is what makes it possible; there is no timer anywhere, so a machine
// that is not used simply does not check — which is the correct behaviour for a one-shot CLI.
const Throttle = 24 * time.Hour

// State is what this machine remembers between runs.
//
// **Written in place, never renamed and never deleted** (ADR 0101). It is small, and every rewrite
// is a write a sandbox may ask about: normally once a day, twice more around a successful install,
// once an hour while a download keeps failing, and once per command while a staged helper keeps
// failing (bounded by maxPendingSpawns).
type State struct {
	// CheckedAt is the last attempt, successful or not. **Failures are recorded too**: a flaky
	// network that reset this to "never checked" would make every command in the window try again.
	CheckedAt time.Time `json:"checked_at"`
	// Base is the channel the last check used. A different base means a different channel (or a
	// test server), and the old answer says nothing about the new one.
	Base string `json:"base,omitempty"`
	// BinarySize and BinaryMtime fingerprint the executable that was checked. Reinstalling the CLI
	// by hand changes them, and the next run must not be told "checked recently" about a binary it
	// has never seen.
	BinarySize  int64     `json:"binary_size,omitempty"`
	BinaryMtime time.Time `json:"binary_mtime,omitempty"`
	// Pending is a verified download that has not replaced the binary yet. It survives a crash, a
	// closed terminal, and a Windows helper that was not allowed to rename.
	Pending *Pending `json:"pending,omitempty"`
	// Notified is the release a failure line was last shown for — "this machine cannot install it"
	// or "the download did not check out" — so either appears once per version rather than on every
	// command.
	Notified string `json:"notified,omitempty"`
}

// Pending is a staged download waiting for its swap.
type Pending struct {
	Path     string `json:"path"`
	Revision string `json:"revision,omitempty"`
	Version  string `json:"version,omitempty"`
	// Spawns counts the helpers started for this record. A swap that never completes must not
	// swallow every later check, so the record is dropped after a few attempts.
	Spawns int `json:"spawns,omitempty"`
}

// maxPendingSpawns is how many helpers one staged file may start before the record gives up.
const maxPendingSpawns = 3

// StatePath is where the state lives: the CLI's config directory.
func StatePath(dir string) string { return filepath.Join(dir, "update.json") }

// LoadState reads the state. A missing or unreadable file is the zero state, not an error: the
// worst it can cause is one extra check.
func LoadState(dir string) State {
	blob, err := os.ReadFile(StatePath(dir))
	if err != nil {
		return State{}
	}
	var state State
	if err := json.Unmarshal(blob, &state); err != nil {
		return State{}
	}
	return state
}

// SaveState replaces the file in place.
//
// **An error here is not fatal and callers may ignore it.** A read-only config directory means the
// throttle cannot work; it must not mean the command fails or the update is abandoned.
func SaveState(dir string, state State) error {
	blob, err := json.Marshal(state)
	if err != nil {
		return fmt.Errorf("编码 %s：%w", StatePath(dir), err)
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("创建 %s：%w", dir, err)
	}
	file, err := os.OpenFile(StatePath(dir), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return fmt.Errorf("写 %s：%w", StatePath(dir), err)
	}
	if _, err := file.Write(blob); err != nil {
		file.Close()
		return fmt.Errorf("写 %s：%w", StatePath(dir), err)
	}
	if err := file.Close(); err != nil {
		return fmt.Errorf("关闭 %s：%w", StatePath(dir), err)
	}
	return nil
}

// ShouldCheck answers the one question the throttle exists for.
func ShouldCheck(state State, now time.Time, base string, size int64, mtime time.Time) bool {
	if state.CheckedAt.IsZero() {
		return true
	}
	if now.Sub(state.CheckedAt) >= Throttle {
		return true
	}
	if state.Base != base {
		return true
	}
	return state.BinarySize != size || !state.BinaryMtime.Equal(mtime)
}

// statTarget fingerprints the running executable, or returns zeros when it cannot be read — a
// zero fingerprint simply means "check", which is the safe direction.
func statTarget(path string) (int64, time.Time) {
	info, err := os.Stat(path)
	if err != nil {
		return 0, time.Time{}
	}
	return info.Size(), info.ModTime()
}

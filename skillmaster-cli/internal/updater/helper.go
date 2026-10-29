package updater

import (
	"os"
	"path/filepath"
	"strings"
	"time"
)

// TargetEnv is how a staged binary knows it was started to replace the one at the other end.
//
// **An environment variable rather than a command.** The helper takes no arguments and prints
// nothing, so it must not appear in the command table, in help, or in the parser — and a hidden
// subcommand would have to do all three. The staged file is the new binary itself (it has just been
// checksum-verified), so there is no second executable to build, ship or trust.
const TargetEnv = "SKILLMASTER_UPDATE_TARGET"

// HelperBudget is how long the helper keeps trying.
//
// A command that has already exited needs no time at all; this is for the case where something else
// still holds the target open — a second skmr invocation, a scanner, a slow filesystem. Giving
// up leaves the staged file in place, and the next run retries through the `pending` record.
const HelperBudget = 60 * time.Second

// HelperInterval is the retry spacing.
const HelperInterval = 50 * time.Millisecond

// ApplyResult says what Apply did.
type ApplyResult struct {
	// Scheduled means the swap was handed to a helper and happens after this process exits, so the
	// caller cannot report it as done — only that it was arranged.
	Scheduled bool
}

// IsHelper reports whether this process was started to swap a staged binary into place.
//
// **Both halves are required.** An environment variable alone would let a stray export turn any
// invocation — including `--version` — into a process that renames itself onto somebody else's
// path; the staged file name is the other half, and only the updater ever produces it.
func IsHelper() bool {
	if os.Getenv(TargetEnv) == "" {
		return false
	}
	self, err := os.Executable()
	if err != nil {
		return false
	}
	return strings.Contains(filepath.Base(self), ".new-")
}

// RunHelper is what a staged binary does when it is started as the helper.
//
// **It renames itself into place.** On Windows a running image cannot be replaced, but it can be
// renamed — the mapping follows the file — so the new binary, already running, moves its own name
// onto the target. Retrying is the whole protocol: the first attempts fail while the parent is still
// alive, and the first one after it exits succeeds.
func RunHelper(target string) error {
	self, err := os.Executable()
	if err != nil {
		return err
	}
	return runHelperFrom(self, target, HelperBudget, HelperInterval)
}

// runHelperFrom is RunHelper without the assumption that the source is this process, so tests can
// watch it work on files they own.
func runHelperFrom(source, target string, budget, interval time.Duration) error {
	deadline := time.Now().Add(budget)
	var last error
	for {
		if err := os.Rename(source, target); err == nil {
			return nil
		} else {
			last = err
		}
		if !time.Now().Before(deadline) {
			return last
		}
		time.Sleep(interval)
	}
}

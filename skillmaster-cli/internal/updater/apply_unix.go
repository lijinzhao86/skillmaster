//go:build !windows

package updater

import "os"

// Apply replaces the running binary by renaming the staged file over it.
//
// **No unlink anywhere** (ADR 0101). `rename` on a busy executable is allowed on every unix: the
// running process keeps its own inode, the name points at the new file, and the old one goes away
// when the last process holding it exits — the kernel's business, not ours.
func Apply(staged, target string) (ApplyResult, error) {
	if err := os.Rename(staged, target); err != nil {
		return ApplyResult{}, err
	}
	return ApplyResult{}, nil
}

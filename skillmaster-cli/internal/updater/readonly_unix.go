//go:build !windows

package updater

import (
	"errors"
	"syscall"
)

// isReadOnlyFilesystem reports a write refused because the filesystem is mounted read-only.
//
// **A different errno from "permission denied", and a real one here**: sandboxes, Nix stores and
// containers mount the install prefix read-only while leaving it owned by the user, so EACCES never
// appears and the "rerun the installer" line would never be printed (the audit's finding 11).
func isReadOnlyFilesystem(err error) bool {
	return errors.Is(err, syscall.EROFS)
}

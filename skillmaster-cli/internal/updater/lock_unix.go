//go:build !windows

package updater

import (
	"os"
	"path/filepath"
	"syscall"
)

// acquireLock takes the machine-wide update lock without waiting.
//
// **An advisory lock on a file that is never deleted** (ADR 0101): the lock is the kernel's, so a
// process that is killed releases it, and the file's continued existence says nothing about anyone
// holding it. `ok == false` means another skillmaster is already updating; that is not an error.
func acquireLock(dir string) (func(), bool, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, false, err
	}
	file, err := os.OpenFile(filepath.Join(dir, "update.lock"), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, false, err
	}
	if err := syscall.Flock(int(file.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		file.Close()
		return nil, false, nil
	}
	return func() {
		_ = syscall.Flock(int(file.Fd()), syscall.LOCK_UN)
		file.Close()
	}, true, nil
}

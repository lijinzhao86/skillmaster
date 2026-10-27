//go:build windows

package updater

import (
	"os"
	"path/filepath"

	"golang.org/x/sys/windows"
)

// acquireLock is the unix version with a different primitive: Windows has no flock, but it has an
// exclusive byte-range lock, and `LOCKFILE_FAIL_IMMEDIATELY` makes it the same non-waiting try.
func acquireLock(dir string) (func(), bool, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, false, err
	}
	file, err := os.OpenFile(filepath.Join(dir, "update.lock"), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, false, err
	}
	handle := windows.Handle(file.Fd())
	overlapped := &windows.Overlapped{}
	err = windows.LockFileEx(handle,
		windows.LOCKFILE_EXCLUSIVE_LOCK|windows.LOCKFILE_FAIL_IMMEDIATELY, 0, 1, 0, overlapped)
	if err != nil {
		file.Close()
		return nil, false, nil
	}
	return func() {
		_ = windows.UnlockFileEx(handle, 0, 1, 0, overlapped)
		file.Close()
	}, true, nil
}

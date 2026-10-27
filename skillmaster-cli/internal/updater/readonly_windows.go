//go:build windows

package updater

// isReadOnlyFilesystem is always false on Windows: there is no read-only mount to report, and a
// refused write there is an access-denied error the permission check already covers.
func isReadOnlyFilesystem(error) bool { return false }

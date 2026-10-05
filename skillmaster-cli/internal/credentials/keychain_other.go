//go:build !linux

package credentials

// keychainUsable is always true off Linux, and that is a statement about the platforms rather
// than optimism: macOS has the Keychain and Windows has the Credential Manager, and neither
// involves a session bus that may or may not be running. There is nothing to probe.
//
// What macOS has *instead* is a trap this file cannot close — the permission is attached to
// `/usr/bin/security`, not to our binary, so an unsigned build re-prompts on every upgrade. See
// the CLI's README; it is a signing problem, not a detection problem.
func keychainUsable() bool {
	return true
}

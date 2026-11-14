//go:build !unix

package feedback

// sessionImageDirs is empty where the host's temp layout is not the Unix one. The clipboard
// reader is the other path, and the drag-or-give-a-path fallback covers the rest.
func sessionImageDirs() []string {
	return nil
}

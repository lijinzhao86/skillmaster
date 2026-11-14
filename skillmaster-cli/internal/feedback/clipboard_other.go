//go:build !darwin

package feedback

// clipboardMedia is macOS-only in v65. The other paths — a message path, the session cache or a
// dragged file — still work; the command layer prints ErrClipboardUnsupported's sentence rather
// than pretending there was nothing on the clipboard.
func clipboardMedia() (string, string, error) {
	return "", "", ErrClipboardUnsupported
}

//go:build darwin

package feedback

import (
	"encoding/base64"
	"os"
	"path/filepath"
	"testing"
)

// TestClipboardMediaWritesTheFirstClassItCanRead exercises the Go side of the macOS reader without
// the real pasteboard: the fake `osascript` receives the output path as its last argument and writes
// a PNG there, which is exactly the contract the real script has. The real AppleScript itself is
// covered by the manual T3 run.
func TestClipboardMediaWritesTheFirstClassItCanRead(t *testing.T) {
	fakeDir := t.TempDir()
	script := "#!/bin/sh\nfor last; do :; done\n" +
		"python3 -c 'import base64,sys;open(sys.argv[1],\"wb\").write(base64.b64decode(sys.argv[2]))' " +
		"\"$last\" '" + base64.StdEncoding.EncodeToString(pngBytes) + "'\n"
	if err := os.WriteFile(filepath.Join(fakeDir, "osascript"), []byte(script), 0o700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", fakeDir+string(os.PathListSeparator)+os.Getenv("PATH"))

	path, name, err := clipboardMedia()
	if err != nil {
		t.Fatalf("clipboardMedia: %v", err)
	}
	defer func() { _ = os.Remove(path) }()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != string(pngBytes) {
		t.Error("the clipboard bytes are not the PNG the fake wrote")
	}
	if name != "clipboard.png" {
		t.Errorf("name = %q", name)
	}
}

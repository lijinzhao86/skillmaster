//go:build darwin

package feedback

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// clipboardMedia writes the clipboard's image (or the file it holds) to a temp file this package
// owns. The caller copies it into the stable directory and deletes the temp file.
//
// **osascript rather than cgo.** The CLI cross-compiles; a CGO pasteboard binding would take that
// away. AppleScript is present on every macOS install. The image classes are tried in order
// because a screenshot, a copied photo and a copied file arrive as different pasteboard types; a
// TIFF (common for screenshots) is converted with the system's `sips` before it is handed back.
func clipboardMedia() (string, string, error) {
	dir, err := os.MkdirTemp("", "skmr-clip-")
	if err != nil {
		return "", "", fmt.Errorf("创建剪贴板临时目录：%w", err)
	}
	cleanup := func() {
		// ADR 0101: no deletes. The temp files are emptied and the directory stays as a shell.
		truncateDir(dir)
	}

	for _, candidate := range []struct {
		class string
		ext   string
	}{
		{"PNGf", "png"},
		{"JPEG", "jpg"},
		{"GIFf", "gif"},
	} {
		path := filepath.Join(dir, "clipboard."+candidate.ext)
		if _, err := runAppleScript(writeClipboardScript(candidate.class), path); err != nil {
			continue
		}
		if info, err := os.Stat(path); err == nil && info.Mode().IsRegular() && info.Size() > 0 {
			return path, "clipboard." + candidate.ext, nil
		}
	}

	tiff := filepath.Join(dir, "clipboard.tiff")
	if _, err := runAppleScript(writeClipboardScript("TIFF"), tiff); err == nil {
		png := filepath.Join(dir, "clipboard.png")
		if err := exec.Command("sips", "-s", "format", "png", tiff, "--out", png).Run(); err == nil {
			truncateFile(tiff)
			return png, "clipboard.png", nil
		}
		// The conversion failed; the original TIFF still holds the picture's bytes.
		truncateFile(tiff)
	}

	if path, err := clipboardFile(); err == nil {
		destination := filepath.Join(dir, "clipboard"+filepath.Ext(path))
		if _, err := copyFile(path, destination); err == nil {
			return destination, filepath.Base(path), nil
		}
	}

	cleanup()
	return "", "", ErrNoPaste
}

// clipboardFile is the path a Finder copy puts on the pasteboard. A multiple selection is not one
// alias; the AppleScript fails and the caller gets ErrNoPaste, whose sentence says "one file at a
// time or drag it in".
func clipboardFile() (string, error) {
	const script = `on run
set f to the clipboard as «class furl»
return POSIX path of f
end run`
	out, err := exec.Command("osascript", "-e", script).Output()
	if err != nil {
		return "", err
	}
	path := strings.TrimSpace(string(out))
	if path == "" {
		return "", errors.New("剪贴板里没有文件")
	}
	return path, nil
}

func runAppleScript(script string, argument string) (string, error) {
	cmd := exec.Command("osascript", "-e", script, "--", argument)
	out, err := cmd.Output()
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(out)), nil
}

func writeClipboardScript(class string) string {
	return `on run argv
set outPath to POSIX file (item 1 of argv)
set d to the clipboard as «class ` + class + `»
set f to open for access outPath with write permission
set eof f to 0
write d to f
close access f
return "ok"
end run`
}

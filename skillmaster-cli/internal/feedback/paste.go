package feedback

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
	"unicode"
)

// PastedMedia is one attachment after it has been copied into the CLI's stable directory.
//
// Path is what the agent passes to `send --attach`; it lives until the send succeeds (or until the
// next attach run sweeps files older than a day). Source is one of message_path, session_cache or
// clipboard, and is printed so the person can see where the file came from.
type PastedMedia struct {
	Path         string
	Source       string
	Media        Media
	Size         int64
	SHA256       string
	OriginalName string
	ModTime      time.Time
}

// AttachRequest is one `skmr feedback attach` invocation. Exactly one of CopyPath, LastPaste and
// Clipboard is set by the command layer.
type AttachRequest struct {
	CopyPath  string
	LastPaste bool
	Clipboard bool
	MaxAge    time.Duration
	StableDir string
}

var (
	// ErrNoPaste means no source produced a file. The command layer turns it into the sentence
	// that tells the user to drag the file in or give a path.
	ErrNoPaste = errors.New("没有找到最近粘贴的图片或视频")
	// ErrClipboardUnsupported means this operating system has no clipboard reader in v65.
	ErrClipboardUnsupported = errors.New("当前平台不支持读取剪贴板")
)

const staleAfter = 24 * time.Hour

// Attach resolves one source, copies it into the stable directory, and returns its metadata.
//
// **The file is copied even when the source already is a file.** The message path, a session
// cache entry and a clipboard temp file all have different lifetimes; the preview and the send are
// two processes, so the path has to outlive both. A copy under a directory this CLI owns is the
// only path that makes that true.
func Attach(req AttachRequest) (PastedMedia, error) {
	dir := req.StableDir
	if dir == "" {
		dir = filepath.Join(os.TempDir(), "skmr-feedback")
	}
	if err := prepareDir(dir); err != nil {
		return PastedMedia{}, err
	}
	cleanupStale(dir)

	switch {
	case req.CopyPath != "":
		return copyInto(req.CopyPath, "message_path", dir, "")
	case req.Clipboard:
		return fromClipboard(dir)
	case req.LastPaste:
		if path, ok := newestSessionPaste(req.MaxAge); ok {
			return copyInto(path, "session_cache", dir, "")
		}
		return fromClipboard(dir)
	default:
		return PastedMedia{}, errors.New("没有选择附件来源")
	}
}

func fromClipboard(dir string) (PastedMedia, error) {
	path, original, err := clipboardMedia()
	if err != nil {
		return PastedMedia{}, err
	}
	defer truncateFile(path)
	return copyInto(path, "clipboard", dir, original)
}

// copyInto copies one regular file into the stable directory and computes what the preview shows.
// originalName is the clipboard's own name when it has one; otherwise the source basename is used.
func copyInto(source, label, dir, originalName string) (PastedMedia, error) {
	info, err := os.Stat(source)
	if err != nil {
		return PastedMedia{}, fmt.Errorf("读附件：%w", err)
	}
	if !info.Mode().IsRegular() {
		return PastedMedia{}, fmt.Errorf("附件「%s」不是普通文件", SanitizeFilename(source))
	}
	// **Refused before the copy.** No allowed type may exceed the video ceiling (the largest of
	// them), so a multi-gigabyte file is rejected here instead of being copied into $TMPDIR first.
	if info.Size() > MaxVideoBytes {
		return PastedMedia{}, fmt.Errorf("附件「%s」超过单文件上限 %s",
			SanitizeFilename(source), HumanBytes(MaxVideoBytes))
	}
	media, err := DetectFile(source)
	if err != nil {
		return PastedMedia{}, err
	}
	name := SanitizeFilename(filepath.Base(source))
	if originalName != "" {
		name = SanitizeFilename(originalName)
	}
	destination, err := stablePath(dir, media.Ext, name)
	if err != nil {
		return PastedMedia{}, err
	}
	size, err := copyFile(source, destination)
	if err != nil {
		truncateFile(destination)
		return PastedMedia{}, err
	}
	digest, err := SHA256File(destination)
	if err != nil {
		truncateFile(destination)
		return PastedMedia{}, err
	}
	return PastedMedia{
		Path:         destination,
		Source:       label,
		Media:        media,
		Size:         size,
		SHA256:       digest,
		OriginalName: name,
		ModTime:      info.ModTime(),
	}, nil
}

// ManagedDir is where `attach` puts its stable copies and where `send` looks before it clears a
// file after a successful submission. A path outside it belongs to the person and is never touched.
func ManagedDir() string {
	return filepath.Join(os.TempDir(), "skmr-feedback")
}

// ClearIfManaged removes the bytes of one attachment copied by this CLI, but not the file itself.
//
// **Truncate, not unlink.** ADR 0101 forbids the CLI from deleting files: a delete is what a
// sandbox stops and asks a person to approve. The credential file already uses this same shape —
// an empty file is "nothing here". The name stays as a visible shell; the content does not.
func ClearIfManaged(path string) {
	absolute, err := filepath.Abs(path)
	if err != nil {
		return
	}
	// **A symlink is not one of ours.** `os.Truncate` follows it, so a link planted in the managed
	// directory would empty its target outside it.
	info, err := os.Lstat(absolute)
	if err != nil || !info.Mode().IsRegular() {
		return
	}
	if !isManagedPath(absolute) {
		return
	}
	truncateFile(absolute)
}

// managedName is the shape stablePath writes: the original's stem, a hyphen, 32 hex characters
// and the detected extension.
var managedName = regexp.MustCompile(`^.+?-[0-9a-f]{32}\.(png|jpg|gif|webp|mp4|mov|webm)$`)

func isManagedPath(absolute string) bool {
	managed := ManagedDir()
	// **A symlinked managed directory is not ours.** Truncating through it would empty a path
	// outside the directory the name promises.
	if info, err := os.Lstat(managed); err == nil {
		if info.Mode()&os.ModeSymlink != 0 {
			return false
		}
		relative, err := filepath.Rel(managed, absolute)
		if err == nil && relative != ".." &&
			!strings.HasPrefix(relative, ".."+string(os.PathSeparator)) {
			return true
		}
	}
	// A managed directory that does not exist in this process is exactly the $TMPDIR case below.
	// **$TMPDIR can differ between the attach and the send process.** Accept the same shape only
	// under a directory that really looks like a temp root, so `~/work/skmr-feedback/…` stays the
	// person's own file.
	parent := filepath.Dir(absolute)
	if filepath.Base(parent) != "skmr-feedback" || !looksLikeTempRoot(filepath.Dir(parent)) {
		return false
	}
	if info, err := os.Lstat(parent); err != nil || info.Mode()&os.ModeSymlink != 0 {
		return false
	}
	return managedName.MatchString(filepath.Base(absolute))
}

// looksLikeTempRoot reports whether a directory is a system temp root, not a project folder that
// happens to be named `skmr-feedback`. /tmp and macOS's per-user `T` are the two this CLI sees.
func looksLikeTempRoot(root string) bool {
	cleaned := filepath.Clean(root)
	switch strings.ToLower(filepath.Base(cleaned)) {
	case "tmp", "temp", "t":
		return true
	}
	return strings.HasPrefix(cleaned, "/var/folders/")
}

// truncateFile keeps the inode and empties it. It is the only cleanup this package does, for the
// reason above; a failure is ignored because the bytes are in the bucket and the shell is not
// sensitive.
func truncateFile(path string) {
	_ = os.Truncate(path, 0)
}

// DisplayName is what `send --attach` records for one attachment: the original stem plus the
// detected extension, with the random segment removed. A path this package did not write is
// returned as the person named it.
func DisplayName(path string) string {
	absolute, err := filepath.Abs(path)
	if err != nil || !isManagedPath(absolute) {
		return SanitizeFilename(filepath.Base(path))
	}
	base := filepath.Base(absolute)
	match := managedName.FindStringSubmatch(base)
	if match == nil {
		return SanitizeFilename(base)
	}
	extension := match[1]
	stem := strings.TrimSuffix(base, "."+extension)
	if index := strings.LastIndex(stem, "-"); index > 0 {
		stem = stem[:index]
	}
	return SanitizeFilename(stem + "." + extension)
}

func truncateDir(dir string) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	for _, entry := range entries {
		if info, err := entry.Info(); err == nil && info.Mode().IsRegular() {
			truncateFile(filepath.Join(dir, entry.Name()))
		}
	}
}

func prepareDir(dir string) error {
	if info, err := os.Lstat(dir); err == nil && info.Mode()&os.ModeSymlink != 0 {
		return fmt.Errorf("拒绝把符号链接作为附件目录：%s", dir)
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("创建附件目录：%w", err)
	}
	return os.Chmod(dir, 0o700)
}

func cleanupStale(dir string) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	cutoff := time.Now().Add(-staleAfter)
	for _, entry := range entries {
		info, err := entry.Info()
		if err != nil || !info.Mode().IsRegular() || info.ModTime().After(cutoff) {
			continue
		}
		truncateFile(filepath.Join(dir, entry.Name()))
	}
}

// stablePath names a stable copy after the original, so `send --attach` can still tell which
// picture or clip it carries: `<stem>-<32 hex>.<ext>`. The random suffix keeps two attachments of
// the same name apart; the stem is display-only and never trusted as a path.
func stablePath(dir, extension, original string) (string, error) {
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		return "", fmt.Errorf("生成附件文件名：%w", err)
	}
	return filepath.Join(dir, managedStem(original)+"-"+hex.EncodeToString(raw)+"."+extension), nil
}

func managedStem(original string) string {
	stem := strings.TrimSuffix(original, filepath.Ext(original))
	stem = strings.Map(func(r rune) rune {
		switch {
		case r < 0x20 || r == 0x7f:
			return -1
		case unicode.IsSpace(r), strings.ContainsRune(`/\:*?"<>|`, r):
			return '-'
		}
		return r
	}, stem)
	stem = strings.Trim(stem, " .-_")
	if runes := []rune(stem); len(runes) > 40 {
		stem = strings.Trim(string(runes[:40]), " .-_")
	}
	if stem == "" {
		return "attach"
	}
	return stem
}

func copyFile(source, destination string) (int64, error) {
	in, err := os.Open(source)
	if err != nil {
		return 0, fmt.Errorf("读附件：%w", err)
	}
	defer in.Close()
	out, err := os.OpenFile(destination, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return 0, fmt.Errorf("写附件：%w", err)
	}
	written, copyErr := io.Copy(out, in)
	closeErr := out.Close()
	if copyErr != nil {
		return 0, fmt.Errorf("写附件：%w", copyErr)
	}
	if closeErr != nil {
		return 0, fmt.Errorf("写附件：%w", closeErr)
	}
	return written, nil
}

// newestSessionPaste is the current session's newest allowed media file within maxAge. The
// directory list is platform-specific; when it is empty the caller goes to the clipboard.
func newestSessionPaste(maxAge time.Duration) (string, bool) {
	if maxAge <= 0 {
		maxAge = 30 * time.Minute
	}
	cutoff := time.Now().Add(-maxAge)
	var best string
	var bestTime time.Time
	for _, dir := range sessionImageDirs() {
		entries, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, entry := range entries {
			info, err := entry.Info()
			if err != nil || !info.Mode().IsRegular() || info.ModTime().Before(cutoff) {
				continue
			}
			if _, err := DetectFile(filepath.Join(dir, entry.Name())); err != nil {
				continue
			}
			if best == "" || info.ModTime().After(bestTime) {
				best = filepath.Join(dir, entry.Name())
				bestTime = info.ModTime()
			}
		}
	}
	return best, best != ""
}

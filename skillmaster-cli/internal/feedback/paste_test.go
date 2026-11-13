//go:build unix

package feedback

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestAttachCopiesAMessagePathIntoAStableFile(t *testing.T) {
	sourceDir := t.TempDir()
	source := filepath.Join(sourceDir, "shot.png")
	if err := os.WriteFile(source, pngBytes, 0o600); err != nil {
		t.Fatal(err)
	}
	stable := t.TempDir()

	media, err := Attach(AttachRequest{CopyPath: source, StableDir: stable})
	if err != nil {
		t.Fatalf("Attach: %v", err)
	}
	if media.Source != "message_path" {
		t.Errorf("source = %q, want message_path", media.Source)
	}
	if media.Media.Type != "image/png" || media.Size != int64(len(pngBytes)) {
		t.Errorf("media = %+v", media.Media)
	}
	if media.OriginalName != "shot.png" {
		t.Errorf("original name = %q", media.OriginalName)
	}
	if !strings.HasPrefix(media.Path, stable+string(os.PathSeparator)) {
		t.Errorf("path %q is not under the stable dir %q", media.Path, stable)
	}
	copied, err := os.ReadFile(media.Path)
	if err != nil {
		t.Fatal(err)
	}
	if string(copied) != string(pngBytes) {
		t.Error("the stable copy is not the source bytes")
	}
	info, err := os.Stat(media.Path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Errorf("mode = %o, want 600", info.Mode().Perm())
	}
	directory, err := os.Stat(stable)
	if err != nil {
		t.Fatal(err)
	}
	if directory.Mode().Perm() != 0o700 {
		t.Errorf("stable directory mode = %o, want 700", directory.Mode().Perm())
	}
	if _, err := os.Stat(source); err != nil {
		t.Errorf("the source was removed: %v", err)
	}
	digest, err := SHA256File(source)
	if err != nil || digest != media.SHA256 {
		t.Errorf("sha = %q, want %q (err %v)", media.SHA256, digest, err)
	}
}

func TestClearIfManagedEmptiesOnlyARegularFileItWrote(t *testing.T) {
	root := t.TempDir()
	t.Setenv("TMPDIR", root)
	dir := filepath.Join(root, "skmr-feedback")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	ours := filepath.Join(dir, "shot-0123456789abcdef0123456789abcdef.png")
	if err := os.WriteFile(ours, pngBytes, 0o600); err != nil {
		t.Fatal(err)
	}
	ClearIfManaged(ours)
	if info, err := os.Stat(ours); err != nil || info.Size() != 0 {
		t.Errorf("a managed copy was not emptied: size %v, err %v", info, err)
	}

	// A path outside the managed directory belongs to the person.
	user := filepath.Join(t.TempDir(), "notes.png")
	if err := os.WriteFile(user, pngBytes, 0o600); err != nil {
		t.Fatal(err)
	}
	ClearIfManaged(user)
	if info, _ := os.Stat(user); info.Size() == 0 {
		t.Error("a file outside the managed directory was emptied")
	}

	// os.Truncate follows a symlink; a link inside the directory must not empty its target.
	outside := filepath.Join(t.TempDir(), "secret")
	if err := os.WriteFile(outside, []byte("keep me"), 0o600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "link-0123456789abcdef0123456789abcdef.png")
	if err := os.Symlink(outside, link); err != nil {
		t.Fatal(err)
	}
	ClearIfManaged(link)
	if data, err := os.ReadFile(outside); err != nil || string(data) != "keep me" {
		t.Errorf("a symlink target outside the directory was touched: %q, err %v", data, err)
	}
}

func TestClearIfManagedRecognisesItsNameUnderAnotherTempRoot(t *testing.T) {
	root := filepath.Join(t.TempDir(), "tmp")
	t.Setenv("TMPDIR", t.TempDir())
	dir := filepath.Join(root, "skmr-feedback")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	copyPath := filepath.Join(dir, "clip-0123456789abcdef0123456789abcdef.mp4")
	if err := os.WriteFile(copyPath, []byte("bytes"), 0o600); err != nil {
		t.Fatal(err)
	}
	ClearIfManaged(copyPath)
	if info, err := os.Stat(copyPath); err != nil || info.Size() != 0 {
		t.Errorf("a copy from another temp root was not emptied: size %v, err %v", info, err)
	}
}

func TestClearIfManagedRefusesASymlinkedManagedDirectory(t *testing.T) {
	real := filepath.Join(t.TempDir(), "real")
	if err := os.MkdirAll(real, 0o700); err != nil {
		t.Fatal(err)
	}
	outside := filepath.Join(real, "shot-0123456789abcdef0123456789abcdef.png")
	if err := os.WriteFile(outside, pngBytes, 0o600); err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	if err := os.Symlink(real, filepath.Join(root, "skmr-feedback")); err != nil {
		t.Fatal(err)
	}
	t.Setenv("TMPDIR", root)

	ClearIfManaged(outside)
	if info, err := os.Stat(outside); err != nil || info.Size() == 0 {
		t.Errorf("a file reached through a symlinked managed directory was emptied: size %v, err %v",
			info, err)
	}
}

func TestDisplayNameStripsOnlyTheManagedSuffix(t *testing.T) {
	root := t.TempDir()
	t.Setenv("TMPDIR", root)
	// ManagedDir is root/skmr-feedback, so containment makes the file recognisably ours.
	dir := filepath.Join(root, "skmr-feedback")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	managed := filepath.Join(dir, "shot-0123456789abcdef0123456789abcdef.png")
	if got := DisplayName(managed); got != "shot.png" {
		t.Errorf("DisplayName(%q) = %q, want shot.png", managed, got)
	}
	plain := filepath.Join(t.TempDir(), "shot-final.png")
	if got := DisplayName(plain); got != "shot-final.png" {
		t.Errorf("DisplayName(%q) = %q, want it unchanged", plain, got)
	}
}

func TestAttachPicksTheNewestSessionPaste(t *testing.T) {
	base := t.TempDir()
	t.Setenv("CLAUDE_CODE_TMPDIR", base)
	t.Setenv("CLAUDE_CODE_SESSION_ID", "session-1")
	cwd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	slug := strings.ReplaceAll(cwd, string(os.PathSeparator), "-")
	images := filepath.Join(base, "claude-"+strconv.Itoa(os.Getuid()), slug, "session-1", "images")
	if err := os.MkdirAll(images, 0o700); err != nil {
		t.Fatal(err)
	}
	older := filepath.Join(images, "1.png")
	newer := filepath.Join(images, "2.png")
	if err := os.WriteFile(older, pngBytes, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(newer, pngBytes, 0o600); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	if err := os.Chtimes(older, now.Add(-10*time.Minute), now.Add(-10*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(newer, now.Add(-1*time.Minute), now.Add(-1*time.Minute)); err != nil {
		t.Fatal(err)
	}

	media, err := Attach(AttachRequest{
		LastPaste: true,
		MaxAge:    30 * time.Minute,
		StableDir: t.TempDir(),
	})
	if err != nil {
		t.Fatalf("Attach: %v", err)
	}
	if media.Source != "session_cache" || media.OriginalName != "2.png" {
		t.Errorf("source = %q, name = %q; want session_cache / 2.png",
			media.Source, media.OriginalName)
	}
}

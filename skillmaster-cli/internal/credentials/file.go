package credentials

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// fileStore is the default store since ADR 0185: one 0600 file, readable only by its owner.
//
// **`path` is where it is written; `older` is where a copy from another state directory may be
// read.** The two are separate because the directory this machine normally keeps its credential in
// can be readable and not writable at the same time (an agent sandbox mounts it read-only), and a
// store that could only look in the writable place would report "not signed in" while the credential
// sits one directory away. Reads prefer `path` — the copy this build last wrote — and fall back to
// `older`; `Clear` truncates both. Writes always go to `path`.
type fileStore struct {
	path  string
	older string
}

func (s *fileStore) Source() string { return s.Where() }

func (s *fileStore) Where() string {
	// The copy that exists, when one does: "where is my credential" is a question about the file
	// this machine will actually read, which may be the older one in a directory it cannot write.
	for _, path := range []string{s.path, s.older} {
		if path == "" {
			continue
		}
		if _, err := os.Stat(path); err == nil {
			return path
		}
	}
	return s.path
}

func (s *fileStore) Load() (Credentials, bool, error) {
	blob, err := s.read()
	if errors.Is(err, fs.ErrNotExist) {
		return Credentials{}, false, nil
	}
	if err != nil {
		return Credentials{}, false, err
	}
	// **An empty file is not an empty credential.** `Clear` makes a logged-out machine's file zero
	// bytes instead of deleting it (ADR 0101), and an empty document is how "nothing stored" reads
	// (`ParseBlob`). Returning before parsing keeps `found` false, which is what the keychain wrapper
	// compares against (`newerCredential`) and what tells every caller the machine is signed out.
	if len(blob) == 0 {
		return Credentials{}, false, nil
	}
	parsed, err := ParseBlob(blob)
	if err != nil {
		// A file that exists and does not parse is reported rather than treated as absent: the
		// alternative is silently signing the person out and letting them wonder why, while the
		// broken file stays where it is.
		return Credentials{}, false, fmt.Errorf("parsing the credential: %w", err)
	}
	return parsed, true, nil
}

// read returns the bytes of the first copy that exists, and the path it came from.
func (s *fileStore) read() ([]byte, error) {
	paths := []string{s.path}
	if s.older != "" && s.older != s.path {
		paths = append(paths, s.older)
	}
	var firstErr error
	for _, path := range paths {
		blob, err := os.ReadFile(path)
		if err == nil {
			return blob, nil
		}
		if !errors.Is(err, fs.ErrNotExist) && firstErr == nil {
			firstErr = fmt.Errorf("reading %s: %w", path, err)
		}
	}
	if firstErr != nil {
		return nil, firstErr
	}
	return nil, fs.ErrNotExist
}

func (s *fileStore) Save(c Credentials) error {
	if s.path == "" {
		return errors.New("没有可写的凭据路径：状态目录定位失败")
	}
	blob, err := c.Blob()
	if err != nil {
		return err
	}
	dir := filepath.Dir(s.path)
	// 0700 on the directory as well as 0600 on the file. A world-readable directory does not
	// expose the contents, but it does let anybody see that this account has a credential and
	// replace the file if they can write to the directory.
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("creating %s: %w", dir, err)
	}

	// **Written in place, not to a neighbour and renamed.** A sandbox can allow writing a file and
	// still refuse `rename` over it: replacing a name is a directory operation, and the allowlist
	// this CLI writes deliberately does not ask for deletion (ADR 0186). WorkBuddy 5.8.6 measured
	// on 2026-11-11 wrote the neighbour and then got `operation not permitted` on the rename, so
	// the login could not be saved at all — in the very environment this version exists for.
	//
	// The cost is atomicity: an interrupted write leaves a truncated credential and the person logs
	// in again (`previousCredential` tolerates an unreadable copy and does not block the next login).
	// That is the right trade here: a login that cannot save is a broken product, while a credential
	// that has to be re-created is an inconvenience with a named way out.
	return writeInPlace(s.path, blob)
}

// writeInPlace writes the credential directly to its final path, following a symlink if there is
// one (the link stays a link).
func writeInPlace(path string, blob []byte) error {
	// **Open without O_TRUNC, and truncate only after the permission change succeeded.** O_TRUNC
	// takes effect at open: a chmod that the filesystem or sandbox refuses would then return an
	// error with the old credential already gone, and the person would be signed out without
	// being told. In this order a failed chmod leaves the previous file intact, so the only state
	// that loses content is the one already being replaced.
	_, statErr := os.Stat(path)
	created := os.IsNotExist(statErr)
	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return fmt.Errorf("writing %s: %w", path, err)
	}
	// **A failed write to a file this run created leaves an empty file, and an empty file reads as
	// "signed out"** — so `older` is not consulted and a usable copy one directory away becomes
	// invisible. That is the cost of never deleting (ADR 0101); the message must say it, because
	// "the old credential is still there" would be false.
	kept := "the old credential is still there"
	if created {
		kept = "this login did not save; run `skmr login` again"
	}
	if err := file.Chmod(0o600); err != nil {
		file.Close()
		return fmt.Errorf("setting permissions on %s (%s): %w", path, kept, err)
	}
	if err := file.Truncate(0); err != nil {
		file.Close()
		return fmt.Errorf("writing %s (%s): %w", path, kept, err)
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		file.Close()
		return fmt.Errorf("writing %s (it may now be half-written; run `skmr login` again): %w", path, err)
	}
	if _, err := file.Write(blob); err != nil {
		file.Close()
		return fmt.Errorf("writing %s (it may now be half-written; run `skmr login` again): %w", path, err)
	}
	if err := file.Close(); err != nil {
		return fmt.Errorf("closing %s (run `skmr login` again): %w", path, err)
	}
	return nil
}

// Clear makes the stored credential stop existing, without deleting the file (ADR 0101).
//
// **A zero-byte file is the tombstone.** `Load` reads it as "nothing stored" (§查 `Load`), so the
// meaning is exactly the deleted file's, and the machine is signed out. Truncating also keeps the
// file's 0600 mode and its name, which is what makes the two paths a sandbox approves stable.
//
// **Both copies are cleared.** `Older` is a place this store reads, so a logout that only truncates
// `path` would report success while the next command reads the credential from `Older` and stays
// signed in. When `Older` exists and cannot be truncated, that is reported rather than forgiven: the
// credential is still there.
//
// A file that is not there is already cleared: `logout` twice is not a failure.
func (s *fileStore) Clear() error {
	paths := []string{s.path}
	if s.older != "" && s.older != s.path {
		paths = append(paths, s.older)
	}
	var errs []error
	for _, path := range paths {
		if err := truncate(path); err != nil {
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
}

// ClearPath makes the write path read as "nothing stored" without touching `older`. **A write that
// succeeds at the keychain uses this, not `Clear`**: the older copy may be read-only by design
// (that is what `older` is for), and failing the login because it cannot be truncated would report
// a failure for a credential that was stored.
//
// **It creates the tombstone when the file is not there.** Simply truncating nothing is not enough:
// the default store reads `path` first and `older` second, so with `path` absent a stale copy in
// `older` would still be served and the credential just written to the keychain would never be
// used. A zero-byte path is the fact that makes `read` stop there.
func (s *fileStore) ClearPath() error {
	if s.path == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return fmt.Errorf("preparing %s: %w", s.path, err)
	}
	file, err := os.OpenFile(s.path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return fmt.Errorf("clearing %s: %w", s.path, err)
	}
	if err := file.Close(); err != nil {
		return fmt.Errorf("clearing %s: %w", s.path, err)
	}
	return nil
}

func truncate(path string) error {
	if path == "" {
		return nil
	}
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_TRUNC, 0o600)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("clearing %s: %w", path, err)
	}
	if err := file.Close(); err != nil {
		return fmt.Errorf("clearing %s: %w", path, err)
	}
	return nil
}

func fallbackNotice(path string) string {
	return fmt.Sprintf(
		"没有可用的钥匙串，凭据写在 %s（明文文件，仅本人可读）。"+
			"这台机器上任何能以你的身份读文件的进程都能读到它。", path)
}

// FileDefaultNotice is what ADR 0185 owes the person on every login: the default is a plaintext
// file now, and the way back to the keychain is one flag.
func FileDefaultNotice(path string) string {
	return fmt.Sprintf(
		"凭据默认写在 %s（明文文件，仅本人可读）。要用系统钥匙串登录：skmr login --keyring。", path)
}

// KeyringFallbackNotice is the sentence for `login --keyring` when the keychain refused and the
// file took the credential. Reusing FileDefaultNotice there would say the default is a file and
// recommend `--keyring` — the flag that just failed.
func KeyringFallbackNotice(path, reason string) string {
	next := "在沙箱外再跑一次 skmr login --keyring"
	if strings.Contains(reason, "超时") {
		next = "解锁钥匙串（或在机器前批准）后再跑一次 skmr login --keyring"
	}
	return fmt.Sprintf(
		"系统钥匙串没写进去，这次凭据存到了 %s（明文文件，仅本人可读）。要放进钥匙串：%s。", path, next)
}

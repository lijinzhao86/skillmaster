package credentials

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
)

// fileStore is the fallback: one file, readable only by its owner.
type fileStore struct {
	path string
}

func (s *fileStore) Where() string {
	return s.path
}

func (s *fileStore) Load() (Credentials, bool, error) {
	blob, err := os.ReadFile(s.path)
	if errors.Is(err, fs.ErrNotExist) {
		return Credentials{}, false, nil
	}
	if err != nil {
		return Credentials{}, false, fmt.Errorf("reading %s: %w", s.path, err)
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
		return Credentials{}, false, fmt.Errorf("parsing %s: %w", s.path, err)
	}
	return parsed, true, nil
}

func (s *fileStore) Save(c Credentials) error {
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

	// Written to a neighbour and renamed, so an interrupted write cannot leave a half-file where a
	// credential used to be.
	//
	// **The neighbour has a fixed name, and it is never removed** (ADR 0101). The CLI does not delete,
	// so a failed write leaves the `.new` sibling behind — and a successful one consumes it with the
	// rename. A
	// fixed name rather than `CreateTemp`'s random one for a second reason: a sandbox that approves
	// paths sees the same two paths every time instead of a new one per attempt. When a write does
	// fail, the error names the leftover, because it is a 0600 file the person has to look at.
	tmpName := s.path + ".new"
	tmp, err := os.OpenFile(tmpName, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return fmt.Errorf("writing %s: %w", tmpName, err)
	}

	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return fmt.Errorf("setting permissions on %s: %w", tmpName, err)
	}
	if _, err := tmp.Write(blob); err != nil {
		tmp.Close()
		return fmt.Errorf("writing %s (this half-written file stays behind; remove it when you have confirmed): %w", tmpName, err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("closing %s (this half-written file stays behind; remove it when you have confirmed): %w", tmpName, err)
	}
	if err := os.Rename(tmpName, s.path); err != nil {
		return fmt.Errorf("replacing %s (%s stays behind; remove it when you have confirmed): %w", s.path, tmpName, err)
	}
	return nil
}

// Clear makes the stored credential stop existing, without deleting the file (ADR 0101).
//
// **A zero-byte file is the tombstone.** `Load` reads it as "nothing stored" (§查 `Load`), so the
// meaning is exactly the deleted file's, and the machine is signed out. Truncating also keeps the
// file's 0600 mode and its name, which is what makes the two paths a sandbox approves stable.
//
// A file that is not there is already cleared: `logout` twice is not a failure.
func (s *fileStore) Clear() error {
	file, err := os.OpenFile(s.path, os.O_WRONLY|os.O_TRUNC, 0o600)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("clearing %s: %w", s.path, err)
	}
	if err := file.Close(); err != nil {
		return fmt.Errorf("clearing %s: %w", s.path, err)
	}
	return nil
}

func fallbackNotice(path string) string {
	return fmt.Sprintf(
		"没有可用的钥匙串，凭据写在 %s（仅本人可读）。"+
			"这台机器上任何能以你的身份读文件的进程都能读到它。", path)
}

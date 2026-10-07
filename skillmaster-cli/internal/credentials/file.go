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
	// credential used to be. `os.CreateTemp` makes it 0600 already; the explicit chmod keeps that
	// true if the process umask ever says otherwise.
	tmp, err := os.CreateTemp(dir, ".credentials-*")
	if err != nil {
		return fmt.Errorf("writing a temporary file in %s: %w", dir, err)
	}
	tmpName := tmp.Name()
	defer func() {
		// Only reached when something above returned early; after a successful rename this is a
		// no-op because the name no longer exists.
		_ = os.Remove(tmpName)
	}()

	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return fmt.Errorf("setting permissions on %s: %w", tmpName, err)
	}
	if _, err := tmp.Write(blob); err != nil {
		tmp.Close()
		return fmt.Errorf("writing %s: %w", tmpName, err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("closing %s: %w", tmpName, err)
	}
	if err := os.Rename(tmpName, s.path); err != nil {
		return fmt.Errorf("replacing %s: %w", s.path, err)
	}
	return nil
}

func (s *fileStore) Delete() error {
	err := os.Remove(s.path)
	if err == nil || errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	return fmt.Errorf("removing %s: %w", s.path, err)
}

func fallbackNotice(path string) string {
	return fmt.Sprintf(
		"没有可用的钥匙串，凭据写在 %s（仅本人可读）。"+
			"这台机器上任何能以你的身份读文件的进程都能读到它。", path)
}

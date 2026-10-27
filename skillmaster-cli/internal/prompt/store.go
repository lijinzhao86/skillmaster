package prompt

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
)

// Record is what this machine remembers about the block it wrote.
//
// **A note, not the truth.** The truth is the file: a person can edit, move or delete it, and the
// record has no way to notice until somebody asks. That is why every command that acts on the file
// reads the file, and the record is used for exactly two things the file cannot answer: where it is,
// and whether the one-line notice has already been shown.
type Record struct {
	// File is the instruction file `prompt install --file` was pointed at.
	File string `json:"file"`
	// Digest is the block that was written, as `sha256:<hex>` of the normalised text.
	Digest string `json:"digest"`
	// Version is the CLI version that wrote it, for the message and for bug reports. Empty on a
	// record written by a build that did not fill it in.
	Version string `json:"version,omitempty"`
	// Notified is the digest the one-line notice was last shown for. A person who ignores the
	// notice should not be told again on every command; running `prompt status` still answers.
	Notified string `json:"notified,omitempty"`
}

// Dir is where the record lives: the CLI's config directory, beside the credential and the pins.
func path(dir string) string { return filepath.Join(dir, "prompt.json") }

// Load reads the record. A missing file is "no record", not an error.
func Load(dir string) (Record, bool, error) {
	blob, err := os.ReadFile(path(dir))
	if errors.Is(err, fs.ErrNotExist) {
		return Record{}, false, nil
	}
	if err != nil {
		return Record{}, false, fmt.Errorf("读取 %s：%w", path(dir), err)
	}
	var record Record
	if err := json.Unmarshal(blob, &record); err != nil {
		// **A record that does not parse is not fatal.** It can only tell us where the file is and
		// what was notified; the file itself is still the truth. Reporting it as an error would make
		// `prompt status` fail on a machine whose blocks are perfectly fine.
		return Record{}, false, nil
	}
	if record.File == "" {
		return Record{}, false, nil
	}
	return record, true, nil
}

// Save replaces the record in place (ADR 0101: no temporary file, nothing to clean up).
func Save(dir string, record Record) error {
	blob, err := json.Marshal(record)
	if err != nil {
		return fmt.Errorf("编码 %s：%w", path(dir), err)
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("创建 %s：%w", dir, err)
	}
	file, err := os.OpenFile(path(dir), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return fmt.Errorf("写 %s：%w", path(dir), err)
	}
	if _, err := file.Write(blob); err != nil {
		file.Close()
		return fmt.Errorf("写 %s：%w", path(dir), err)
	}
	if err := file.Close(); err != nil {
		return fmt.Errorf("关闭 %s：%w", path(dir), err)
	}
	return nil
}

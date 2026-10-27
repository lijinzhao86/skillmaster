package prompt

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
)

// Record is what this machine remembers about one instruction file.
//
// **A note, not the truth.** The truth is the file: a person can edit, move or delete it, and the
// record has no way to notice until somebody asks. That is why every command that acts on the file
// reads the file, and the record is used for exactly three things the file cannot answer: where it
// is, which agent label the person gave it, and whether the one-line notice has already been shown.
//
// **Agent is a label, not a lookup key.** It is never compared with a list of known agents and never
// decides whether a path may be written; `prompt install --agent <随意名字>` is legal.
type Record struct {
	// File is the instruction file `prompt install --file` was pointed at.
	File string `json:"path"`
	// Agent is the optional label the person gave this file (`--agent codex`). Empty means unknown.
	Agent string `json:"agent,omitempty"`
	// Digest is the block that was written, as `sha256:<hex>` of the normalised text.
	Digest string `json:"digest"`
	// Version is the CLI version that wrote it, for the message and for bug reports. Empty on a
	// record written by a build that did not fill it in.
	Version string `json:"version,omitempty"`
	// Notified is the digest the one-line notice was last shown for. A person who ignores the
	// notice should not be told again on every command; running `prompt status` still answers.
	Notified string `json:"notified,omitempty"`
}

// Store is the whole `prompt.json`: one record per instruction file.
//
// **Schema 2 is a set, not a map.** Order is the order files were first written, and the file keeps
// the person's labels verbatim. A map would sort paths and lose which one came first.
type Store struct {
	Schema int      `json:"schema"`
	Files  []Record `json:"files"`
}

// legacyRecord is the v25 shape: one file, top-level fields. It is read only, never written.
type legacyRecord struct {
	File     string `json:"file"`
	Digest   string `json:"digest"`
	Version  string `json:"version"`
	Notified string `json:"notified"`
}

// Dir is where the record lives: the CLI's config directory, beside the credential and the pins.
func path(dir string) string { return filepath.Join(dir, "prompt.json") }

// LoadStore reads every record. A missing file is "no records", not an error.
//
// **A record that does not parse is not fatal** (v25): it can only tell us where files are and what
// was notified; the files themselves are still the truth. Reporting it as an error would make
// `prompt status` fail on a machine whose blocks are perfectly fine.
func LoadStore(dir string) (Store, bool, error) {
	blob, err := os.ReadFile(path(dir))
	if errors.Is(err, fs.ErrNotExist) {
		return Store{}, false, nil
	}
	if err != nil {
		return Store{}, false, fmt.Errorf("读取 %s：%w", path(dir), err)
	}
	var store Store
	if err := json.Unmarshal(blob, &store); err == nil && (store.Schema == 2 || store.Files != nil) {
		return store, true, nil
	}
	var legacy legacyRecord
	if err := json.Unmarshal(blob, &legacy); err == nil && legacy.File != "" {
		return Store{Schema: 2, Files: []Record{{
			File:     legacy.File,
			Digest:   legacy.Digest,
			Version:  legacy.Version,
			Notified: legacy.Notified,
		}}}, true, nil
	}
	return Store{}, false, nil
}

// SaveStore replaces the set in place (ADR 0101: no temporary file, nothing to clean up).
func SaveStore(dir string, store Store) error {
	store.Schema = 2
	if store.Files == nil {
		store.Files = []Record{}
	}
	blob, err := json.Marshal(store)
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

// Load is the v25 single-record view. It returns the first record, or no record.
//
// Kept as a compatibility wrapper while the callers move to LoadStore; new code must not use it.
func Load(dir string) (Record, bool, error) {
	store, has, err := LoadStore(dir)
	if err != nil || !has || len(store.Files) == 0 {
		return Record{}, false, err
	}
	return store.Files[0], true, nil
}

// Save replaces the set with this one record. It is the v25 single-record writer kept for tests and
// small callers; new code writes through SaveStore after it has the whole set. (Upsert is the
// multi-file operation; Save is deliberately the old "this is the record" shape.)
func Save(dir string, record Record) error {
	if record.File == "" {
		return nil
	}
	return SaveStore(dir, Store{Schema: 2, Files: []Record{record}})
}

// Upsert replaces the record for record.File, or appends it. A path is the identity: the same path
// with a new agent label updates that label instead of adding a second row.
func Upsert(records []Record, record Record) []Record {
	if record.File == "" {
		return records
	}
	out := make([]Record, len(records))
	copy(out, records)
	for i := range out {
		if out[i].File == record.File {
			out[i] = record
			return out
		}
	}
	return append(out, record)
}

// RemoveRecord deletes the record for path. It never deletes the file (ADR 0101).
func RemoveRecord(records []Record, file string) []Record {
	out := records[:0]
	for _, record := range records {
		if record.File != file {
			out = append(out, record)
		}
	}
	return out
}

// Find returns the record for path.
func Find(records []Record, file string) (Record, bool) {
	for _, record := range records {
		if record.File == file {
			return record, true
		}
	}
	return Record{}, false
}

package prompt

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
)

// Record is what this machine once remembered about one instruction file.
//
// **History, not state** (ADR 0110, v27). Nothing writes new records any more: the CLI stopped
// managing the copy, so `prompt.json` is frozen at whatever the last writing version left there. Its
// one reader is `startup`'s record line, which answers 「我换了一个 Agent，还要再写吗」 with the agent
// labels found here. Whether a file still carries the block is a question no command asks any more —
// the file is the truth, and nobody is looking at it.
//
// **Paths and labels are kept verbatim.** They were typed by a person (`--agent codex`), never
// validated against a list of known agents.
type Record struct {
	// File is the instruction file `prompt install --file` was pointed at.
	File string `json:"path"`
	// Agent is the optional label the person gave this file. Empty means unknown.
	Agent string `json:"agent,omitempty"`
	// Digest is the block that was written, as `sha256:<hex>` of the normalised text. Nothing compares
	// it any more; it is kept so a record reads back exactly as it was written.
	Digest string `json:"digest"`
	// Version is the CLI version that wrote it, for the message and for bug reports. Empty on a
	// record written by a build that did not fill it in.
	Version string `json:"version,omitempty"`
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
//
// `notified` is still in old files and is deliberately not modelled: it belonged to the one-line
// 「规则段是旧版」 notice, which retired with the markers (ADR 0110). An unknown field is ignored.
type legacyRecord struct {
	File    string `json:"file"`
	Digest  string `json:"digest"`
	Version string `json:"version"`
}

// path is where the record lives: the CLI's config directory, beside the credential and the pins.
func path(dir string) string { return filepath.Join(dir, "prompt.json") }

// LoadStore reads every record. A missing file is "no records", not an error.
//
// **A record that does not parse is not fatal** (v25): it can only say which agents were configured,
// and a machine whose record was mangled should still see its journey. Reporting it as an error would
// turn a stale note into a broken command.
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
			File:    legacy.File,
			Digest:  legacy.Digest,
			Version: legacy.Version,
		}}}, true, nil
	}
	return Store{}, false, nil
}

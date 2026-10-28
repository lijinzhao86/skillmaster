package prompt

import (
	"os"
	"path/filepath"
	"testing"
)

// **The store is read-only now** (ADR 0110). These tests write the file the way the last writing
// version of the CLI would have, and assert that what comes back is what the record line needs: one
// label per file, verbatim, and nothing else.

func writePromptJSON(t *testing.T, dir, body string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, "prompt.json"), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestAnEmptyDirectoryHasNoRecords(t *testing.T) {
	store, found, err := LoadStore(t.TempDir())
	if err != nil || found {
		t.Fatalf("found=%v err=%v, want no records and no error", found, err)
	}
	if len(store.Files) != 0 {
		t.Errorf("store = %+v, want no files", store)
	}
}

func TestACorruptRecordIsNoRecord(t *testing.T) {
	dir := t.TempDir()
	writePromptJSON(t, dir, "{not json")
	if _, found, err := LoadStore(dir); err != nil || found {
		t.Fatalf("a corrupt record was reported as found=%v err=%v", found, err)
	}
}

func TestAV25RecordIsReadAsOneFile(t *testing.T) {
	dir := t.TempDir()
	writePromptJSON(t, dir, `{"file":"/home/me/.claude/CLAUDE.md","digest":"sha256:abc",`+
		`"version":"1.1.0-beta (abc1234)","notified":"sha256:old"}`)
	store, found, err := LoadStore(dir)
	if err != nil || !found {
		t.Fatalf("LoadStore: found=%v err=%v", found, err)
	}
	if store.Schema != 2 || len(store.Files) != 1 {
		t.Fatalf("store = %+v, want schema 2 with one file", store)
	}
	// `notified` retired with the stale-block notice; it reads as absent, not as an error.
	want := Record{File: "/home/me/.claude/CLAUDE.md", Digest: "sha256:abc", Version: "1.1.0-beta (abc1234)"}
	if store.Files[0] != want {
		t.Errorf("record = %+v, want %+v", store.Files[0], want)
	}
}

func TestEveryFileKeepsItsLabelAndItsOrder(t *testing.T) {
	dir := t.TempDir()
	writePromptJSON(t, dir, `{"schema":2,"files":[`+
		`{"path":"/a/CLAUDE.md","agent":"claude","digest":"sha256:1"},`+
		`{"path":"/b/AGENTS.md","digest":"sha256:2"}]}`)
	store, found, err := LoadStore(dir)
	if err != nil || !found {
		t.Fatalf("LoadStore: found=%v err=%v", found, err)
	}
	if len(store.Files) != 2 {
		t.Fatalf("store = %+v, want two files", store)
	}
	if store.Files[0].Agent != "claude" || store.Files[1].Agent != "" {
		t.Errorf("labels changed on the way in: %+v", store.Files)
	}
	if store.Files[0].File != "/a/CLAUDE.md" || store.Files[1].File != "/b/AGENTS.md" {
		t.Errorf("order changed on the way in: %+v", store.Files)
	}
}

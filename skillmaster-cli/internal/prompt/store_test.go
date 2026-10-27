package prompt

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestARecordSurvivesARoundTrip(t *testing.T) {
	dir := t.TempDir()
	if _, found, err := Load(dir); err != nil || found {
		t.Fatalf("an empty directory has a record: found=%v err=%v", found, err)
	}
	want := Record{File: "/tmp/CLAUDE.md", Digest: Digest(), Version: "1.1.0-beta (abc1234)"}
	if err := Save(dir, want); err != nil {
		t.Fatalf("Save: %v", err)
	}
	got, found, err := Load(dir)
	if err != nil || !found {
		t.Fatalf("Load: found=%v err=%v", found, err)
	}
	if got != want {
		t.Errorf("record = %+v, want %+v", got, want)
	}
	info, err := os.Stat(filepath.Join(dir, "prompt.json"))
	if err != nil {
		t.Fatal(err)
	}
	// Windows reports a writable file as 0666 whatever we asked for; the mode is a POSIX promise.
	if runtime.GOOS != "windows" && info.Mode().Perm() != 0o600 {
		t.Errorf("mode = %v, want 0600", info.Mode().Perm())
	}
}

func TestACorruptRecordIsNoRecord(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "prompt.json"), []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, found, err := Load(dir); err != nil || found {
		t.Fatalf("a corrupt record was reported as found=%v err=%v", found, err)
	}
}

func TestSaveRewritesInsteadOfReplacing(t *testing.T) {
	// ADR 0101: the file is truncated in place, never renamed, never deleted. A second save must
	// leave one file with the new content and nothing else in the directory.
	dir := t.TempDir()
	if err := Save(dir, Record{File: "/a", Digest: "sha256:1"}); err != nil {
		t.Fatal(err)
	}
	if err := Save(dir, Record{File: "/b", Digest: "sha256:2"}); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != "prompt.json" {
		t.Fatalf("directory holds %v, want just prompt.json", entries)
	}
	got, _, _ := Load(dir)
	if got.File != "/b" {
		t.Errorf("record = %+v, want the second one", got)
	}
}

func TestAV25RecordIsReadAsOneFile(t *testing.T) {
	dir := t.TempDir()
	body := `{"file":"/home/me/.claude/CLAUDE.md","digest":"sha256:abc","version":"1.1.0-beta (abc1234)","notified":"sha256:old"}`
	if err := os.WriteFile(filepath.Join(dir, "prompt.json"), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	store, found, err := LoadStore(dir)
	if err != nil || !found {
		t.Fatalf("LoadStore: found=%v err=%v", found, err)
	}
	if store.Schema != 2 || len(store.Files) != 1 {
		t.Fatalf("store = %+v, want schema 2 with one file", store)
	}
	want := Record{
		File:     "/home/me/.claude/CLAUDE.md",
		Digest:   "sha256:abc",
		Version:  "1.1.0-beta (abc1234)",
		Notified: "sha256:old",
	}
	if store.Files[0] != want {
		t.Errorf("record = %+v, want %+v", store.Files[0], want)
	}
}

func TestOneRecordPerPath(t *testing.T) {
	records := []Record{{File: "/a", Agent: "codex", Digest: "sha256:1"}}
	records = Upsert(records, Record{File: "/b", Agent: "claude", Digest: "sha256:2"})
	records = Upsert(records, Record{File: "/a", Agent: "codex-renamed", Digest: "sha256:3"})
	if len(records) != 2 {
		t.Fatalf("records = %+v, want two", records)
	}
	if records[0].Agent != "codex-renamed" || records[0].Digest != "sha256:3" {
		t.Errorf("the same path did not replace its record: %+v", records[0])
	}
	if records[1].File != "/b" {
		t.Errorf("order changed or the second file was lost: %+v", records)
	}
	records = RemoveRecord(records, "/a")
	if len(records) != 1 || records[0].File != "/b" {
		t.Errorf("RemoveRecord left %+v", records)
	}
}

func TestTheStoreRoundTripsEveryFile(t *testing.T) {
	dir := t.TempDir()
	want := Store{Schema: 2, Files: []Record{
		{File: "/a", Agent: "codex", Digest: "sha256:1"},
		{File: "/b", Digest: "sha256:2", Notified: "sha256:0"},
	}}
	if err := SaveStore(dir, want); err != nil {
		t.Fatal(err)
	}
	got, found, err := LoadStore(dir)
	if err != nil || !found {
		t.Fatalf("LoadStore: found=%v err=%v", found, err)
	}
	if len(got.Files) != 2 || got.Files[0] != want.Files[0] || got.Files[1] != want.Files[1] {
		t.Errorf("store = %+v, want %+v", got, want)
	}
}

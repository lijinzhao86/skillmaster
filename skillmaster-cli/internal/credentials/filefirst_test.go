package credentials

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestFileFirstReadsTheFileBeforeTheFallback(t *testing.T) {
	dir := t.TempDir()
	file := &fileStore{path: filepath.Join(dir, "cred")}
	keychain := &stubStore{creds: Credentials{AccessToken: "from-keychain"}, found: true}
	store := newFileFirst(file, keychain)

	if err := store.Save(Credentials{AccessToken: "from-file"}); err != nil {
		t.Fatalf("save: %v", err)
	}
	creds, found, err := store.Load()
	if err != nil || !found || creds.AccessToken != "from-file" {
		t.Fatalf("load = %+v, %v, %v", creds, found, err)
	}
	if !strings.Contains(store.Source(), "cred") {
		t.Fatalf("source = %q, want the file path", store.Source())
	}
}

func TestFileFirstFallsBackToTheKeychainWhenTheFileIsEmpty(t *testing.T) {
	dir := t.TempDir()
	file := &fileStore{path: filepath.Join(dir, "cred")}
	keychain := &stubStore{creds: Credentials{AccessToken: "from-keychain"}, found: true}
	store := newFileFirst(file, keychain)

	creds, found, err := store.Load()
	if err != nil || !found || creds.AccessToken != "from-keychain" {
		t.Fatalf("load = %+v, %v, %v", creds, found, err)
	}
	if !strings.Contains(store.Source(), "钥匙串") {
		t.Fatalf("source = %q, want the keychain", store.Source())
	}
}

// `Open` is the default wiring itself: a file that exists is served before the keychain is asked.
func TestOpenIsFileFirst(t *testing.T) {
	dir := t.TempDir()
	loc := Location{Path: filepath.Join(dir, "cred")}
	if err := (&fileStore{path: loc.Path}).Save(Credentials{AccessToken: "from-file"}); err != nil {
		t.Fatal(err)
	}
	store, _ := Open(loc, "https://example.test")
	creds, found, err := store.Load()
	if err != nil || !found || creds.AccessToken != "from-file" {
		t.Fatalf("load = %+v, %v, %v", creds, found, err)
	}
}

// **No neighbour, no rename.** A sandbox that allows writing the file but denies renaming over it
// (WorkBuddy 5.8.6, measured 2026-11-11) must still get a stored credential, so Save writes in
// place and leaves nothing beside the file.
func TestSaveWritesInPlaceWithoutANeighbour(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "cred")
	store := &fileStore{path: path}

	if err := store.Save(Credentials{AccessToken: "the-new-token"}); err != nil {
		t.Fatalf("Save: %v", err)
	}
	creds, found, err := store.Load()
	if err != nil || !found || creds.AccessToken != "the-new-token" {
		t.Fatalf("load = %+v, %v, %v", creds, found, err)
	}
	if _, err := os.Stat(path + ".new"); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("a neighbour was created: %v", err)
	}
}

// The default store prefers the file even when the keychain copy was written later (ADR 0185);
// "newer wins" belongs to the `--keyring` store only.
func TestFileFirstPrefersTheFileWhenTheKeychainIsNewer(t *testing.T) {
	dir := t.TempDir()
	file := &fileStore{path: filepath.Join(dir, "cred")}
	if err := file.Save(Credentials{AccessToken: "from-file", SavedAt: time.Now().Add(-time.Hour)}); err != nil {
		t.Fatal(err)
	}
	keychain := &stubStore{creds: Credentials{AccessToken: "from-keychain", SavedAt: time.Now()}, found: true}
	store := newFileFirst(file, keychain)

	creds, found, err := store.Load()
	if err != nil || !found || creds.AccessToken != "from-file" {
		t.Fatalf("load = %+v, %v, %v", creds, found, err)
	}
}

// A broken file is not a reason to refuse to be signed in when the keychain has a credential.
func TestFileFirstUsesTheKeychainWhenTheFileIsBroken(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "cred")
	if err := os.WriteFile(path, []byte("not a credential"), 0o600); err != nil {
		t.Fatal(err)
	}
	keychain := &stubStore{creds: Credentials{AccessToken: "from-keychain"}, found: true}
	store := newFileFirst(&fileStore{path: path}, keychain)

	creds, found, err := store.Load()
	if err != nil || !found || creds.AccessToken != "from-keychain" {
		t.Fatalf("load = %+v, %v, %v", creds, found, err)
	}
}

// `Older` is a place this store reads, so clearing only the write path would report a logout that
// the next command does not see.
func TestFileStoreClearClearsTheOlderCopy(t *testing.T) {
	dir := t.TempDir()
	newPath := filepath.Join(dir, "new", "cred")
	olderPath := filepath.Join(dir, "older", "cred")
	if err := (&fileStore{path: olderPath}).Save(Credentials{AccessToken: "old"}); err != nil {
		t.Fatal(err)
	}
	store := &fileStore{path: newPath, older: olderPath}
	if _, found, _ := store.Load(); !found {
		t.Fatalf("the older copy was not read")
	}
	if err := store.Clear(); err != nil {
		t.Fatalf("clear: %v", err)
	}
	if _, found, _ := store.Load(); found {
		t.Fatalf("still signed in from the older copy")
	}
}

func TestFileFirstClearClearsBoth(t *testing.T) {
	dir := t.TempDir()
	file := &fileStore{path: filepath.Join(dir, "cred")}
	keychain := &stubStore{creds: Credentials{AccessToken: "from-keychain"}, found: true}
	store := newFileFirst(file, keychain)
	if err := store.Save(Credentials{AccessToken: "from-file"}); err != nil {
		t.Fatal(err)
	}
	if err := store.Clear(); err != nil {
		t.Fatalf("clear: %v", err)
	}
	if _, found, _ := file.Load(); found {
		t.Fatalf("the file still has a credential")
	}
	if keychain.deletes != 1 {
		t.Fatalf("keychain deletes = %d, want 1", keychain.deletes)
	}
}

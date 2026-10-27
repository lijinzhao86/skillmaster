package credentials

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func sample() Credentials {
	return Credentials{
		ClientID:     "skillmaster-cli",
		AccessToken:  "access-one",
		RefreshToken: "refresh-one",
		ExpiresAt:    time.Date(2026, 10, 3, 12, 0, 0, 0, time.UTC),
	}
}

func TestFileStoreRoundTrips(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nested", "credentials")
	store := &fileStore{path: path}

	if _, found, err := store.Load(); err != nil || found {
		t.Fatalf("a store with nothing in it: found=%v err=%v", found, err)
	}

	if err := store.Save(sample()); err != nil {
		t.Fatalf("saving: %v", err)
	}
	got, found, err := store.Load()
	if err != nil || !found {
		t.Fatalf("loading what was just saved: found=%v err=%v", found, err)
	}
	want := sample()
	if got.SavedAt.IsZero() {
		t.Fatal("the stored form was not stamped with when it was written")
	}
	got.SavedAt = time.Time{}
	if got != want {
		t.Fatalf("round trip changed the credential:\n got %+v\nwant %+v", got, want)
	}
}

// **The stamp is taken when the blob is produced, and it is not taken on the value in memory.**
//
// Both halves matter to `newerCredential`: a stamp taken anywhere else (at construction, at parse
// time) would order copies by something other than which was written last, and a `Blob` that stamped
// its receiver would change a credential the caller still holds.
func TestTheStampIsTakenWhenTheBlobIsProduced(t *testing.T) {
	before := time.Now()
	creds := sample()

	blob, err := creds.Blob()
	if err != nil {
		t.Fatalf("Blob: %v", err)
	}
	if !creds.SavedAt.IsZero() {
		t.Fatalf("Blob stamped the value it was called on: %v", creds.SavedAt)
	}

	parsed, err := ParseBlob(blob)
	if err != nil {
		t.Fatalf("ParseBlob: %v", err)
	}
	if parsed.SavedAt.Before(before) {
		t.Fatalf("the stored form was stamped %v, which is before the write began (%v)",
			parsed.SavedAt, before)
	}
}

func TestFileStoreKeepsTheCredentialToItsOwner(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "nested", "credentials")
	store := &fileStore{path: path}
	if err := store.Save(sample()); err != nil {
		t.Fatalf("saving: %v", err)
	}

	file, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if perm := file.Mode().Perm(); perm != 0o600 {
		t.Errorf("credential file is %04o, want 0600", perm)
	}
	// The directory too: 0755 would let anybody see that this account holds a credential, and
	// replace the file if the directory is writable by them.
	parent, err := os.Stat(filepath.Dir(path))
	if err != nil {
		t.Fatalf("stat parent: %v", err)
	}
	if perm := parent.Mode().Perm(); perm != 0o700 {
		t.Errorf("credential directory is %04o, want 0700", perm)
	}
}

func TestFileStoreClearIsIdempotent(t *testing.T) {
	store := &fileStore{path: filepath.Join(t.TempDir(), "credentials")}
	if err := store.Clear(); err != nil {
		t.Fatalf("deleting what is not there: %v", err)
	}
	if err := store.Save(sample()); err != nil {
		t.Fatalf("saving: %v", err)
	}
	if err := store.Clear(); err != nil {
		t.Fatalf("deleting: %v", err)
	}
	if err := store.Clear(); err != nil {
		t.Fatalf("deleting twice: %v", err)
	}
	if _, found, err := store.Load(); err != nil || found {
		t.Fatalf("after clearing: found=%v err=%v", found, err)
	}
}

// **Clearing leaves the file where it is, empty** (ADR 0101). The CLI does not delete, and a
// zero-byte file means the same thing a missing one does — which is what makes `logout` work without
// an unlink.
func TestClearingTruncatesAndKeepsTheFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "credentials")
	store := &fileStore{path: path}
	if err := store.Save(sample()); err != nil {
		t.Fatalf("saving: %v", err)
	}
	if err := store.Clear(); err != nil {
		t.Fatalf("clearing: %v", err)
	}

	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("the cleared file is gone: %v", err)
	}
	if info.Size() != 0 {
		t.Fatalf("the cleared file is %d bytes", info.Size())
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Errorf("the cleared file is %04o, want 0600", perm)
	}
}

// Saving goes through the `.new` sibling and consumes it with the rename: no temporary name is left
// behind on the ordinary path, and the CLI never deletes one.
func TestSavingConsumesItsSibling(t *testing.T) {
	path := filepath.Join(t.TempDir(), "credentials")
	store := &fileStore{path: path}
	if err := store.Save(sample()); err != nil {
		t.Fatalf("saving: %v", err)
	}
	if _, err := os.Stat(path + ".new"); !os.IsNotExist(err) {
		t.Fatalf("the .new sibling survived a successful save: %v", err)
	}
}

// A file the world can read is still a credential file: the empty case is the one that must read as
// "nothing stored", and an empty document is not an empty credential.
func TestAnEmptyFileIsNotASignedInMachine(t *testing.T) {
	path := filepath.Join(t.TempDir(), "credentials")
	if err := os.WriteFile(path, nil, 0o600); err != nil {
		t.Fatalf("writing an empty file: %v", err)
	}
	if _, found, err := (&fileStore{path: path}).Load(); err != nil || found {
		t.Fatalf("an empty file read as found=%v err=%v", found, err)
	}
}

func TestFileStoreReportsAFileItCannotParse(t *testing.T) {
	path := filepath.Join(t.TempDir(), "credentials")
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatalf("writing a broken file: %v", err)
	}

	// Reported, not treated as absent. Treating it as absent would sign the person out silently
	// and leave the broken file in place for the next run to trip over again.
	if _, _, err := (&fileStore{path: path}).Load(); err == nil {
		t.Fatal("a credential file that is not JSON was accepted")
	}
}

func TestOpenPrefersTheKeychainAndSaysNothing(t *testing.T) {
	store, notice := openWith(true, "/tmp/credentials", "https://example.test")

	// A deadline-wrapped keychain, not the bare store: "there is a keychain" is answered from the
	// environment, and "it will answer" can only be answered by calling it — see responsiveKeychain.
	responsive, ok := store.(*responsiveKeychain)
	if !ok {
		t.Fatalf("with a keychain available, the store is %T", store)
	}
	if _, ok := responsive.keychain.(*keychainStore); !ok {
		t.Fatalf("the wrapped store is %T", responsive.keychain)
	}
	// Silent, because there is nothing to warn about: ADR 0025 only requires the notice when the
	// credential is somewhere weaker than the person would assume. A keychain that later goes silent
	// announces itself then, through Where — that is the only moment it is a fact.
	if notice != "" {
		t.Errorf("a keychain store came with a notice: %q", notice)
	}
}

func TestOpenFallsBackToAFileAndSaysSo(t *testing.T) {
	store, notice := openWith(false, "/tmp/skillmaster/credentials", "https://example.test")

	if _, ok := store.(*fileStore); !ok {
		t.Fatalf("with no keychain, the store is %T", store)
	}
	if !strings.Contains(notice, "/tmp/skillmaster/credentials") {
		t.Errorf("the notice does not name the file: %q", notice)
	}
	// The notice has to say what the person is actually accepting, or it is a warning nobody can
	// act on.
	if !strings.Contains(notice, "仅本人可读") {
		t.Errorf("the notice does not say the file is owner-only: %q", notice)
	}
}

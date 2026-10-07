package pins

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

// The ordinary round trip, and the reason the file exists: an answer written by one process has to be
// there for the next one, which has no memory of it.
func TestAPinWrittenByOneRunIsThereForTheNext(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pins")
	pin := Pin{Name: "1.2.3", Digest: "sha256:abc"}

	if _, had, err := Open(path).Remember("demo/hello", pin); err != nil || had {
		t.Fatalf("first Remember: pin=%v had=%v err=%v", pin, had, err)
	}

	// A second Store over the same path, which is what the next invocation of the CLI is.
	got, found, err := Open(path).Lookup("demo/hello")
	if err != nil {
		t.Fatalf("Lookup: %v", err)
	}
	if !found || got != pin {
		t.Errorf("Lookup returned %+v, found=%v", got, found)
	}
}

// The displaced value comes back, and it is the whole reason Remember returns one.
//
// `invoke` re-resolves on every call, so the move it has to report is "what was remembered, versus
// what this call resolved" — and asking for the old value separately would be a second read of a file
// this call is already writing.
func TestRememberReturnsWhatItDisplaced(t *testing.T) {
	store := Open(filepath.Join(t.TempDir(), "pins"))
	first := Pin{Name: "1.2.3", Digest: "sha256:abc"}
	second := Pin{Name: "1.3.0", Digest: "sha256:def"}

	if _, _, err := store.Remember("demo/hello", first); err != nil {
		t.Fatalf("first: %v", err)
	}
	previous, had, err := store.Remember("demo/hello", second)
	if err != nil {
		t.Fatalf("second: %v", err)
	}
	if !had || previous != first {
		t.Errorf("second Remember reported previous=%+v had=%v", previous, had)
	}
	if now, _, _ := store.Lookup("demo/hello"); now != second {
		t.Errorf("after the second write the store holds %+v", now)
	}
}

// One file holds many addresses, and they do not disturb each other: a task that invokes two skills
// must not have the second one's pin replace the first one's.
func TestPinsForDifferentAddressesCoexist(t *testing.T) {
	store := Open(filepath.Join(t.TempDir(), "pins"))
	a := Pin{Name: "1.0.0", Digest: "sha256:a"}
	b := Pin{Digest: "sha256:b"}

	if _, _, err := store.Remember("demo/one", a); err != nil {
		t.Fatalf("one: %v", err)
	}
	if _, _, err := store.Remember("demo/two", b); err != nil {
		t.Fatalf("two: %v", err)
	}

	for address, want := range map[string]Pin{"demo/one": a, "demo/two": b} {
		got, found, err := store.Lookup(address)
		if err != nil || !found || got != want {
			t.Errorf("%s: got %+v found=%v err=%v", address, got, found, err)
		}
	}
}

// A nameless version's digest survives, and it is the case the `name` field cannot express: an empty
// name is how "the author declared none" is stored, so a store keeping only the wire spelling would be
// keeping a value it cannot tell from a name (ADR 0033).
func TestANamelessVersionKeepsItsDigestAndItsEmptyName(t *testing.T) {
	store := Open(filepath.Join(t.TempDir(), "pins"))
	nameless := Pin{Digest: "sha256:" + "0123456789abcdef"}

	if _, _, err := store.Remember("demo/hello", nameless); err != nil {
		t.Fatalf("Remember: %v", err)
	}
	got, found, err := store.Lookup("demo/hello")
	if err != nil || !found {
		t.Fatalf("Lookup: found=%v err=%v", found, err)
	}
	if got != nameless || got.Name != "" {
		t.Errorf("a nameless version came back as %+v", got)
	}
}

// Nothing remembered is not a failure. Every first read takes this path, and turning it into an error
// would make the ordinary case look like a broken one.
func TestNoFileMeansNothingRemembered(t *testing.T) {
	got, found, err := Open(filepath.Join(t.TempDir(), "absent")).Lookup("demo/hello")
	if err != nil {
		t.Fatalf("an absent file was an error: %v", err)
	}
	if found {
		t.Errorf("an absent file reported %+v", got)
	}
}

// **A file that exists and does not parse is an error, not an empty store.**
//
// The distinction is the point: treating it as absent would send the next read to whatever is current
// — a different version — and leave the broken file where it is, to do it again next time.
//
// **And it is a *typed* error, because a caller has to tell it from the store's other failure mode.**
// Only this one means "the file is the problem, delete it"; an I/O failure leaves a file that is
// perfectly good, and one message for both would send an operator to repair the wrong thing.
func TestACorruptFileIsAnErrorRatherThanAnEmptyStore(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pins")
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}

	for name, err := range map[string]error{
		"Lookup":   lookupErr(t, path),
		"Remember": rememberErr(t, path),
	} {
		if err == nil {
			t.Fatalf("%s read a corrupt file as an empty store", name)
		}
		var corrupt *CorruptError
		if !errors.As(err, &corrupt) {
			t.Fatalf("%s reported %T rather than *CorruptError: %v", name, err, err)
		}
		if corrupt.Path != path {
			t.Errorf("%s named %q rather than the file it could not read", name, corrupt.Path)
		}
	}
}

// **`null` is valid JSON and it is not an empty store.** Unmarshalling it into a map leaves the map
// nil without an error, so it would pass any "did unmarshalling fail" check — and then the next read
// would answer about whatever is current (the silent substitution this store exists to prevent) while
// the next write would assign into a nil map and panic. Both halves are asserted here, because the
// panic is the half that cannot be discovered by reading the message.
func TestANullDocumentIsCorruptRatherThanEmpty(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pins")
	if err := os.WriteFile(path, []byte("null"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}

	for name, err := range map[string]error{
		"Lookup":   lookupErr(t, path),
		"Remember": rememberErr(t, path),
	} {
		var corrupt *CorruptError
		if err == nil {
			t.Fatalf("%s read a null document as an empty store", name)
		}
		if !errors.As(err, &corrupt) {
			t.Fatalf("%s reported %T rather than *CorruptError: %v", name, err, err)
		}
	}
}

// The other failure mode: the file is fine and reading it failed anyway. Nothing here is corrupt, so
// nothing here may be reported as corrupt — that type is what a caller keys "delete it" off.
func TestAnIOFailureIsNotACorruptFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "pins")
	if err := os.WriteFile(path, []byte("{}"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	// A directory where a file was expected: readable enough to exist, unreadable as a file.
	if err := os.Remove(path); err != nil {
		t.Fatalf("remove: %v", err)
	}
	if err := os.Mkdir(path, 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}

	err := lookupErr(t, path)
	if err == nil {
		t.Fatal("a directory was read as an empty store")
	}
	var corrupt *CorruptError
	if errors.As(err, &corrupt) {
		t.Errorf("an I/O failure was reported as a corrupt file, which invites deleting it: %v", err)
	}
}

func lookupErr(t *testing.T, path string) error {
	t.Helper()
	_, _, err := Open(path).Lookup("demo/hello")
	return err
}

func rememberErr(t *testing.T, path string) error {
	t.Helper()
	_, _, err := Open(path).Remember("demo/hello", Pin{Name: "1"})
	return err
}

// The file is written owner-only, in a directory that is too — the same rule the credential fallback
// follows, and for a weaker reason that still holds: nothing in this directory is anybody else's
// business.
func TestTheFileIsWrittenOwnerOnly(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sub", "pins")
	if _, _, err := Open(path).Remember("demo/hello", Pin{Name: "1"}); err != nil {
		t.Fatalf("Remember: %v", err)
	}

	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Errorf("the file is %o", perm)
	}
	dir, err := os.Stat(filepath.Dir(path))
	if err != nil {
		t.Fatalf("stat dir: %v", err)
	}
	if perm := dir.Mode().Perm(); perm != 0o700 {
		t.Errorf("the directory is %o", perm)
	}
}

package pins

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// openAt builds a store over a fresh directory, with a legacy table beside it that does not exist.
func openAt(t *testing.T) (*Store, string, string) {
	t.Helper()
	root := t.TempDir()
	dir := filepath.Join(root, "pins")
	legacy := filepath.Join(root, "pins-legacy")
	return Open(dir, legacy), dir, legacy
}

// The ordinary round trip, and the reason the file exists: an answer written by one process has to be
// there for the next one, which has no memory of it.
func TestAPinWrittenByOneRunIsThereForTheNext(t *testing.T) {
	store, dir, legacy := openAt(t)
	pin := Pin{Name: "1.2.3", Digest: "sha256:abc"}

	if _, had, err := store.Remember("demo/hello", pin); err != nil || had {
		t.Fatalf("first Remember: pin=%v had=%v err=%v", pin, had, err)
	}

	// A second Store over the same directory, which is what the next invocation of the CLI is.
	got, found, err := Open(dir, legacy).Lookup("demo/hello")
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
	store, _, _ := openAt(t)
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

// One record per address: a task that invokes two skills must not have the second one's pin replace
// the first one's. Under the old whole-table store this was a load–modify–write race; now the two
// addresses do not share a file at all.
func TestPinsForDifferentAddressesCoexist(t *testing.T) {
	store, dir, _ := openAt(t)
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
	if one, two := store.path("demo/one"), store.path("demo/two"); one == two {
		t.Errorf("two addresses share one record: %s", one)
	} else if filepath.Dir(one) != dir {
		t.Errorf("a record was written outside the store's directory: %s", one)
	}
}

// A nameless version's digest survives, and it is the case the `name` field cannot express: an empty
// name is how "the author declared none" is stored, so a store keeping only the wire spelling would be
// keeping a value it cannot tell from a name (ADR 0033).
func TestANamelessVersionKeepsItsDigestAndItsEmptyName(t *testing.T) {
	store, _, _ := openAt(t)
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
	got, found, err := Open(filepath.Join(t.TempDir(), "absent"), "").Lookup("demo/hello")
	if err != nil {
		t.Fatalf("an absent file was an error: %v", err)
	}
	if found {
		t.Errorf("an absent file reported %+v", got)
	}
}

// **A record that exists and does not parse is an error on read, not an empty store.**
//
// The distinction is the point: treating it as absent would send the next read to whatever is current
// — a different version — and leave the broken file where it is, to do it again next time.
//
// **And it is a *typed* error, because a caller has to tell it from the store's other failure mode.**
// Only this one means "the file is the problem, delete it"; an I/O failure leaves a file that is
// perfectly good, and one message for both would send an operator to repair the wrong thing.
func TestACorruptRecordIsAnErrorOnRead(t *testing.T) {
	store, _, _ := openAt(t)
	path := store.path("demo/hello")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}

	err := lookupErr(t, store, "demo/hello")
	if err == nil {
		t.Fatal("Lookup read a corrupt record as an empty store")
	}
	var corrupt *CorruptError
	if !errors.As(err, &corrupt) {
		t.Fatalf("Lookup reported %T rather than *CorruptError: %v", err, err)
	}
	if corrupt.Path != path {
		t.Errorf("Lookup named %q rather than the file it could not read", corrupt.Path)
	}
}

// **A write repairs a record that no longer parses** (ADR 0101's in-place write). The version just
// resolved is the answer, not a guess, so overwriting cannot substitute anything; and without this the
// address would need a manual delete to recover — the one repair this design avoids.
func TestRememberOverwritesACorruptRecord(t *testing.T) {
	store, _, _ := openAt(t)
	path := store.path("demo/hello")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}

	pin := Pin{Name: "2.0.0", Digest: "sha256:def"}
	previous, had, err := store.Remember("demo/hello", pin)
	if err != nil {
		t.Fatalf("Remember over a corrupt record: %v", err)
	}
	if had {
		t.Errorf("a corrupt record was reported as a previous pin: %+v", previous)
	}
	if got, found, err := store.Lookup("demo/hello"); err != nil || !found || got != pin {
		t.Errorf("after the repair Lookup returned %+v found=%v err=%v", got, found, err)
	}
}

// **A record whose file says it belongs to another address is refused on both paths.**
//
// Serving it would answer about a version this address was never at; overwriting it would erase the
// other address's pin. The address inside the file is what turns a name collision (or a hand copy)
// into a refusal instead of a wrong answer.
func TestARecordForAnotherAddressIsRefused(t *testing.T) {
	store, _, _ := openAt(t)
	path := store.path("demo/hello")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	blob, _ := json.Marshal(record{Address: "demo/other", Pin: Pin{Name: "9.9.9", Digest: "sha256:x"}})
	if err := os.WriteFile(path, blob, 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}

	for name, err := range map[string]error{
		"Lookup":   lookupErr(t, store, "demo/hello"),
		"Remember": rememberErr(t, store, "demo/hello"),
	} {
		var mismatch *MismatchError
		if err == nil {
			t.Fatalf("%s served a record that belongs to another address", name)
		}
		if !errors.As(err, &mismatch) {
			t.Fatalf("%s reported %T rather than *MismatchError: %v", name, err, err)
		}
		if mismatch.Address != "demo/other" || mismatch.Wanted != "demo/hello" {
			t.Errorf("%s named %+v", name, mismatch)
		}
	}
}

// **Writing the same version again touches nothing.** This is what keeps a repeated `invoke` out of a
// sandbox's write approval (v24 PRD 做 #4). The assertion is the observable the PRD names — the
// content and the modification time do not move — and the record is made read-only as a second,
// stronger signal: under a non-root user a write would fail outright. (The chmod alone is not enough
// evidence: as root, or on a filesystem where mode bits are advisory, it would not stop anything.)
func TestRememberingTheSameVersionDoesNotWrite(t *testing.T) {
	store, _, _ := openAt(t)
	pin := Pin{Name: "1.2.3", Digest: "sha256:abc"}
	if _, _, err := store.Remember("demo/hello", pin); err != nil {
		t.Fatalf("first: %v", err)
	}
	path := store.path("demo/hello")
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	stamp := time.Unix(1_600_000_000, 0)
	if err := os.Chtimes(path, stamp, stamp); err != nil {
		t.Fatalf("chtimes: %v", err)
	}
	if err := os.Chmod(path, 0o400); err != nil {
		t.Fatalf("chmod: %v", err)
	}
	t.Cleanup(func() { _ = os.Chmod(path, 0o600) })

	previous, had, err := store.Remember("demo/hello", pin)
	if err != nil {
		t.Fatalf("a repeated Remember tried to write: %v", err)
	}
	if !had || previous != pin {
		t.Errorf("a repeated Remember reported previous=%+v had=%v", previous, had)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if !info.ModTime().Equal(stamp) {
		t.Errorf("a repeated Remember rewrote the record (mtime %v, want %v)", info.ModTime(), stamp)
	}
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read after: %v", err)
	}
	if string(after) != string(before) {
		t.Errorf("a repeated Remember changed the record:\nbefore %s\nafter  %s", before, after)
	}
}

// **A legacy pin is migrated even when the version did not move.** "The same version does not write"
// is about a record that already holds the version — not about the old table. Without the migration a
// machine whose skills all sit on their pinned version would keep reading the whole-table file
// forever, and the upgrade would never happen.
func TestRememberMigratesAnUnchangedLegacyPin(t *testing.T) {
	store, _, legacy := openAt(t)
	pin := Pin{Kind: "name", Name: "1.2.0", Digest: "sha256:abc"}
	blob, _ := json.Marshal(map[string]Pin{"demo/hello": pin})
	if err := os.WriteFile(legacy, blob, 0o600); err != nil {
		t.Fatalf("write legacy: %v", err)
	}

	previous, had, err := store.Remember("demo/hello", pin)
	if err != nil {
		t.Fatalf("Remember: %v", err)
	}
	if !had || previous != pin {
		t.Errorf("Remember reported previous=%+v had=%v", previous, had)
	}
	if _, err := os.Stat(store.path("demo/hello")); err != nil {
		t.Fatalf("the unchanged legacy pin was not migrated: %v", err)
	}
	// The second time, the record itself holds the version, so nothing is written.
	path := store.path("demo/hello")
	stamp := time.Unix(1_600_000_000, 0)
	if err := os.Chtimes(path, stamp, stamp); err != nil {
		t.Fatalf("chtimes: %v", err)
	}
	if _, _, err := store.Remember("demo/hello", pin); err != nil {
		t.Fatalf("second Remember: %v", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if !info.ModTime().Equal(stamp) {
		t.Errorf("a migrated record was rewritten on the next call")
	}
}

// **A document that parses but names no address is corrupt, not somebody else's record.** `null` and
// `{}` unmarshal into a zero value without an error; as a mismatch they could never be repaired,
// because `Remember` refuses to overwrite a record that belongs to another address.
func TestARecordWithNoAddressIsCorrupt(t *testing.T) {
	for name, document := range map[string]string{"null": "null", "empty object": "{}"} {
		t.Run(name, func(t *testing.T) {
			store, _, _ := openAt(t)
			path := store.path("demo/hello")
			if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
				t.Fatalf("mkdir: %v", err)
			}
			if err := os.WriteFile(path, []byte(document), 0o600); err != nil {
				t.Fatalf("write: %v", err)
			}
			var corrupt *CorruptError
			if err := lookupErr(t, store, "demo/hello"); !errors.As(err, &corrupt) {
				t.Fatalf("Lookup reported %T rather than *CorruptError: %v", err, err)
			}
			// And the write repairs it, which a mismatch would not.
			if _, _, err := store.Remember("demo/hello", Pin{Name: "1.0.0"}); err != nil {
				t.Fatalf("Remember: %v", err)
			}
		})
	}
}

// **A record with an address but no version is corrupt too.** An empty `Suffix()` sends an empty
// version header, which is "whatever is current" — the substitution this store exists to prevent.
func TestARecordWithNoVersionIsCorrupt(t *testing.T) {
	store, _, _ := openAt(t)
	path := store.path("demo/hello")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	blob, _ := json.Marshal(record{Address: "demo/hello"})
	if err := os.WriteFile(path, blob, 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	var corrupt *CorruptError
	if err := lookupErr(t, store, "demo/hello"); !errors.As(err, &corrupt) {
		t.Fatalf("Lookup reported %T rather than *CorruptError: %v", err, err)
	}
}

// The same rule for the legacy table: a row with no version must not become an empty header either.
func TestALegacyRowWithNoVersionIsCorrupt(t *testing.T) {
	store, _, legacy := openAt(t)
	blob, _ := json.Marshal(map[string]Pin{"demo/hello": {}})
	if err := os.WriteFile(legacy, blob, 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	var corrupt *CorruptError
	if err := lookupErr(t, store, "demo/hello"); !errors.As(err, &corrupt) {
		t.Fatalf("Lookup reported %T rather than *CorruptError: %v", err, err)
	}
}

// The pre-v24 table is read when the address has no record of its own, and it is never written.
//
// This is the upgrade path: a machine that pinned a skill before v24 must not start reading the
// current version instead (ADR 0035's silent substitution), and the old file must come out of the
// upgrade byte for byte.
func TestTheLegacyTableIsReadAndNeverWritten(t *testing.T) {
	store, _, legacy := openAt(t)
	old := map[string]Pin{"demo/hello": {Name: "1.0.0", Digest: "sha256:old"}}
	blob, _ := json.Marshal(old)
	if err := os.WriteFile(legacy, blob, 0o600); err != nil {
		t.Fatalf("write legacy: %v", err)
	}

	got, found, err := store.Lookup("demo/hello")
	if err != nil || !found || got != old["demo/hello"] {
		t.Fatalf("Lookup through the legacy table: got=%+v found=%v err=%v", got, found, err)
	}

	next := Pin{Name: "2.0.0", Digest: "sha256:new"}
	previous, had, err := store.Remember("demo/hello", next)
	if err != nil {
		t.Fatalf("Remember: %v", err)
	}
	if !had || previous != old["demo/hello"] {
		t.Errorf("the legacy pin was not returned as previous: %+v had=%v", previous, had)
	}

	after, err := os.ReadFile(legacy)
	if err != nil {
		t.Fatalf("read legacy after: %v", err)
	}
	if string(after) != string(blob) {
		t.Errorf("the legacy table was modified: %s", after)
	}
	if now, _, _ := store.Lookup("demo/hello"); now != next {
		t.Errorf("after Remember the new record is %+v", now)
	}
}

// A corrupt legacy table is reported when it is the only source, and stops mattering once the address
// has a record of its own.
func TestACorruptLegacyTableIsAnErrorOnlyWhenConsulted(t *testing.T) {
	store, _, legacy := openAt(t)
	if err := os.WriteFile(legacy, []byte("null"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}

	var corrupt *CorruptError
	if err := lookupErr(t, store, "demo/hello"); !errors.As(err, &corrupt) {
		t.Fatalf("a null legacy table was read as %T: %v", err, err)
	}

	if _, _, err := store.Remember("demo/hello", Pin{Name: "3.0.0"}); err != nil {
		t.Fatalf("Remember: %v", err)
	}
	if _, found, err := store.Lookup("demo/hello"); err != nil || !found {
		t.Errorf("after a new record the corrupt legacy table still matters: found=%v err=%v", found, err)
	}
}

// The other failure mode: the record is fine and reading it failed anyway. Nothing here is corrupt, so
// nothing here may be reported as corrupt — that type is what a caller keys "delete it" off.
func TestAnIOFailureIsNotACorruptRecord(t *testing.T) {
	store, dir, _ := openAt(t)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	path := store.path("demo/hello")
	if err := os.Mkdir(path, 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}

	err := lookupErr(t, store, "demo/hello")
	if err == nil {
		t.Fatal("a directory was read as an empty store")
	}
	var corrupt *CorruptError
	if errors.As(err, &corrupt) {
		t.Errorf("an I/O failure was reported as a corrupt file, which invites deleting it: %v", err)
	}
}

// The record is written owner-only, in a directory that is too — the same rule the credential
// fallback follows, and for a weaker reason that still holds: nothing in this directory is anybody
// else's business.
func TestTheRecordIsWrittenOwnerOnly(t *testing.T) {
	store, dir, _ := openAt(t)
	if _, _, err := store.Remember("demo/hello", Pin{Name: "1"}); err != nil {
		t.Fatalf("Remember: %v", err)
	}

	info, err := os.Stat(store.path("demo/hello"))
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Errorf("the file is %o", perm)
	}
	dinfo, err := os.Stat(dir)
	if err != nil {
		t.Fatalf("stat dir: %v", err)
	}
	if perm := dinfo.Mode().Perm(); perm != 0o700 {
		t.Errorf("the directory is %o", perm)
	}
}

// The file is a record, not just a pin: the address is inside it, and that is what makes a mismatch
// detectable rather than a wrong answer.
func TestTheRecordCarriesItsAddress(t *testing.T) {
	store, _, _ := openAt(t)
	if _, _, err := store.Remember("demo/hello", Pin{Name: "1"}); err != nil {
		t.Fatalf("Remember: %v", err)
	}
	blob, err := os.ReadFile(store.path("demo/hello"))
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	var flat map[string]any
	if err := json.Unmarshal(blob, &flat); err != nil {
		t.Fatalf("the record is not one flat object: %v", err)
	}
	if flat["address"] != "demo/hello" {
		t.Errorf("the record says address=%v", flat["address"])
	}
}

func lookupErr(t *testing.T, store *Store, address string) error {
	t.Helper()
	_, _, err := store.Lookup(address)
	return err
}

func rememberErr(t *testing.T, store *Store, address string) error {
	t.Helper()
	_, _, err := store.Remember(address, Pin{Name: "1"})
	return err
}

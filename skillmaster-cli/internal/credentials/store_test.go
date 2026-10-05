package credentials

import (
	"errors"
	"io/fs"
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
	if got != sample() {
		t.Fatalf("round trip changed the credential:\n got %+v\nwant %+v", got, sample())
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

func TestFileStoreDeleteIsIdempotent(t *testing.T) {
	store := &fileStore{path: filepath.Join(t.TempDir(), "credentials")}
	if err := store.Delete(); err != nil {
		t.Fatalf("deleting what is not there: %v", err)
	}
	if err := store.Save(sample()); err != nil {
		t.Fatalf("saving: %v", err)
	}
	if err := store.Delete(); err != nil {
		t.Fatalf("deleting: %v", err)
	}
	if err := store.Delete(); err != nil {
		t.Fatalf("deleting twice: %v", err)
	}
	if _, found, err := store.Load(); err != nil || found {
		t.Fatalf("after deleting: found=%v err=%v", found, err)
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

// A lock whose holder is gone is taken rather than waited on, because nothing else ever clears it.
//
// The directory outlives the process that made it, so before this one crash mid-refresh left a lock
// that was permanent in fact rather than in intent: every later refresh waited out the full timeout
// and printed the "somebody else holds it" warning, for good. The comment on Acquire anticipated a
// lock "left behind by a process that died" as a passing thing; it is not passing unless something
// ends it.
func TestAStaleLockIsReclaimed(t *testing.T) {
	dir := t.TempDir()
	lock := NewLock(dir, "https://example.test")

	// Planted rather than acquired: this is what a killed process leaves, and no `release` is coming.
	if err := os.Mkdir(lock.path, 0o700); err != nil {
		t.Fatalf("planting the abandoned lock: %v", err)
	}
	old := time.Now().Add(-staleLockAfter - time.Minute)
	if err := os.Chtimes(lock.path, old, old); err != nil {
		t.Fatalf("ageing the abandoned lock: %v", err)
	}

	release, err := lock.Acquire(time.Second)
	if err != nil {
		t.Fatalf("taking a lock nobody holds: %v", err)
	}
	defer release()
}

// And the threshold is an age, not the waiter's patience.
//
// Comparing against the timeout makes it however long the *next* caller was willing to wait, so a
// second holder whose patience happens to equal the age it is looking at takes the lock from a live
// holder at exactly the moment it should have given up — which is what a first version of this did,
// and what TestLockIsHeldUntilItIsReleased caught.
func TestAFreshLockIsNotReclaimedHoweverLongTheWaiterWaits(t *testing.T) {
	lock := NewLock(t.TempDir(), "https://example.test")

	release, err := lock.Acquire(time.Second)
	if err != nil {
		t.Fatalf("taking a free lock: %v", err)
	}
	defer release()

	// The wait is longer than the age at the moment it starts, which is the shape that used to steal.
	if _, err := lock.Acquire(120 * time.Millisecond); err == nil {
		t.Fatal("a lock held by somebody who is still there was taken from them")
	}
}

// Something that is not a lock directory is not a stale lock, so it is never swept aside: a file in
// the way is reported as unusable rather than removed.
func TestALockPathThatIsNotADirectoryIsNeverReclaimed(t *testing.T) {
	dir := t.TempDir()
	lock := NewLock(dir, "https://example.test")
	if err := os.WriteFile(lock.path, []byte("in the way"), 0o600); err != nil {
		t.Fatalf("writing the blocker: %v", err)
	}

	if _, err := lock.age(); err == nil {
		t.Fatal("age() accepted something that is not a lock directory")
	}
}

func TestTheLockCreatesItsDirectoryWhenTheCredentialIsInTheKeychain(t *testing.T) {
	// The state every keychain user is in: the credential is in the keychain, so nobody ever created
	// the directory the lock lives in. Before this, `Acquire` failed with ENOENT on *every* refresh —
	// reported as the same warning contention gets, and silently locking nothing.
	dir := filepath.Join(t.TempDir(), "never-created")
	lock := NewLock(dir, "https://example.test")

	release, err := lock.Acquire(time.Second)
	if err != nil {
		t.Fatalf("taking a lock in a directory that does not exist: %v", err)
	}
	defer release()

	// And it is a real lock, not merely an accepted call: a second holder has to wait.
	if _, err := lock.Acquire(50 * time.Millisecond); err == nil {
		t.Fatal("the lock was not actually taken")
	}
	if _, err := os.Stat(dir); err != nil {
		t.Fatalf("the directory was not created: %v", err)
	}
}

func TestLockIsHeldUntilItIsReleased(t *testing.T) {
	lock := NewLock(t.TempDir(), "https://example.test")

	release, err := lock.Acquire(time.Second)
	if err != nil {
		t.Fatalf("taking a free lock: %v", err)
	}

	// A second holder must not get it while the first has it. The timeout is short on purpose:
	// this asserts on the waiting path, which is the whole point of the type.
	if _, err := lock.Acquire(50 * time.Millisecond); err == nil {
		t.Fatal("a second Acquire succeeded while the lock was held")
	}

	release()

	second, err := lock.Acquire(time.Second)
	if err != nil {
		t.Fatalf("taking a released lock: %v", err)
	}
	second()
}

func TestLockReleaseIsSafeToCallTwice(t *testing.T) {
	lock := NewLock(t.TempDir(), "https://example.test")
	release, err := lock.Acquire(time.Second)
	if err != nil {
		t.Fatalf("taking the lock: %v", err)
	}
	release()
	// `defer release()` next to an explicit call is the ordinary way this happens, and a panic
	// there would be in the middle of a credential write.
	release()
}

func TestTwoServersDoNotShareALock(t *testing.T) {
	dir := t.TempDir()
	first := NewLock(dir, "https://one.example.test")
	second := NewLock(dir, "https://two.example.test")

	release, err := first.Acquire(time.Second)
	if err != nil {
		t.Fatalf("taking the first lock: %v", err)
	}
	defer release()

	// Refreshing against two servers has no interaction at all, and serialising it would make one
	// server's slowness into the other's.
	other, err := second.Acquire(50 * time.Millisecond)
	if err != nil {
		t.Fatalf("the second server's lock was blocked by the first: %v", err)
	}
	other()
}

func TestLockReportsAPathItCannotUse(t *testing.T) {
	dir := t.TempDir()
	// A file where the lock directory should go: contention retries will never clear this, so it
	// has to come back as an error rather than as a wait that ends in a timeout.
	blocker := NewLock(dir, "https://example.test")
	if err := os.WriteFile(blocker.path, []byte("in the way"), 0o600); err != nil {
		t.Fatalf("writing the blocker: %v", err)
	}

	_, err := blocker.Acquire(50 * time.Millisecond)
	if err == nil {
		t.Fatal("Acquire succeeded with a file in the lock's place")
	}
	if !errors.Is(err, fs.ErrExist) && !strings.Contains(err.Error(), "was held for") {
		t.Fatalf("the error does not explain itself: %v", err)
	}
}

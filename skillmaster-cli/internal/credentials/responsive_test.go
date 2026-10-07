package credentials

import (
	"errors"
	"path/filepath"
	"testing"
	"time"
)

// deadlineUnderTest is short enough that the tests are instant and long enough that a call which
// answers immediately is never mistaken for one that did not. The production value is 5s.
const deadlineUnderTest = 20 * time.Millisecond

// stubKeychain is a keychain whose behaviour each test chooses. `block` makes every call wait on a
// channel nobody sends to — which is what a locked keychain does, except that this one can be
// released so the test does not leak a goroutine.
type stubKeychain struct {
	creds   Credentials
	found   bool
	block   chan struct{}
	calls   int
	deleted int
	saved   int
}

func (s *stubKeychain) Load() (Credentials, bool, error) {
	s.calls++
	if s.block != nil {
		<-s.block
	}
	return s.creds, s.found, nil
}

func (s *stubKeychain) Save(c Credentials) error {
	s.calls++
	s.saved++
	if s.block != nil {
		<-s.block
	}
	s.creds, s.found = c, true
	return nil
}

func (s *stubKeychain) Delete() error {
	s.calls++
	s.deleted++
	if s.block != nil {
		<-s.block
	}
	s.found = false
	return nil
}

func (s *stubKeychain) Where() string { return "the stub keychain" }

func responsiveFor(t *testing.T, keychain *stubKeychain) (*responsiveKeychain, *fileStore) {
	t.Helper()
	fallback := &fileStore{path: filepath.Join(t.TempDir(), "credentials")}
	store := newResponsiveKeychain(keychain, fallback)
	store.deadline = deadlineUnderTest
	return store, fallback
}

func TestAKeychainThatAnswersIsTheOnlyThingUsed(t *testing.T) {
	keychain := &stubKeychain{creds: Credentials{AccessToken: "from-the-keychain"}, found: true}
	store, fallback := responsiveFor(t, keychain)

	got, found, err := store.Load()
	if err != nil || !found {
		t.Fatalf("Load: %v, found=%v", err, found)
	}
	if got.AccessToken != "from-the-keychain" {
		t.Fatalf("access token = %q", got.AccessToken)
	}
	if _, found := loadFallback(t, fallback); found {
		t.Fatal("a working keychain still caused a file to be written")
	}
	// "the stub keychain" and not the file: the ordinary case must not report the fallback at all.
	if store.Where() != "the stub keychain" {
		t.Fatalf("Where = %q", store.Where())
	}
}

func TestASilentKeychainOnSaveMovesThisRunToTheFile(t *testing.T) {
	keychain := &stubKeychain{block: make(chan struct{})}
	store, fallback := responsiveFor(t, keychain)
	defer close(keychain.block)

	// This is the observed failure: the keychain never answers, so `login` has to be able to finish
	// somewhere. Before the deadline existed this call never returned at all.
	if err := store.Save(Credentials{AccessToken: "saved-the-hard-way"}); err != nil {
		t.Fatalf("Save: %v", err)
	}

	creds, found, err := fallback.Load()
	if err != nil || !found {
		t.Fatalf("the fallback file: %v, found=%v", err, found)
	}
	if creds.AccessToken != "saved-the-hard-way" {
		t.Fatalf("what landed in the file: %q", creds.AccessToken)
	}
	// And the person is told where it went and why — ADR 0025's announcement, on a trigger the
	// environment cannot predict. The alternative is a credential in a plain file, unremarked.
	if store.Where() == fallback.Where() {
		t.Fatal("Where says the file without saying that the keychain went silent")
	}
}

func TestAfterATimeoutTheKeychainIsNotTriedAgainInThisRun(t *testing.T) {
	keychain := &stubKeychain{block: make(chan struct{})}
	store, _ := responsiveFor(t, keychain)
	defer close(keychain.block)

	if err := store.Save(Credentials{AccessToken: "first"}); err != nil {
		t.Fatalf("Save: %v", err)
	}
	callsAfterFirst := keychain.calls

	// Waiting again would add the deadline to every remaining call in a command that makes several,
	// turning one five-second stall into several.
	if _, _, err := store.Load(); err != nil {
		t.Fatalf("Load after the switch: %v", err)
	}
	if err := store.Delete(); err != nil {
		t.Fatalf("Delete after the switch: %v", err)
	}
	if keychain.calls != callsAfterFirst {
		t.Fatalf("the keychain was called %d more times after it had already been given up on",
			keychain.calls-callsAfterFirst)
	}
}

func TestASilentKeychainOnLoadFallsBackToTheFileWhenTheFileHasSomething(t *testing.T) {
	keychain := &stubKeychain{block: make(chan struct{})}
	store, fallback := responsiveFor(t, keychain)
	defer close(keychain.block)

	// An earlier timed-out run put it here, so this is a real credential and the person is signed in.
	if err := fallback.Save(Credentials{AccessToken: "from-an-earlier-run"}); err != nil {
		t.Fatalf("seeding the fallback: %v", err)
	}

	got, found, err := store.Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if !found || got.AccessToken != "from-an-earlier-run" {
		t.Fatalf("got %+v, found=%v", got, found)
	}
}

func TestASilentKeychainOnLoadDoesNotClaimNobodyIsSignedIn(t *testing.T) {
	keychain := &stubKeychain{block: make(chan struct{})}
	store, _ := responsiveFor(t, keychain)
	defer close(keychain.block)

	// "Not signed in" would be a claim about a keychain that never answered — and the credential is
	// probably in it. The error says so and says what to do, which is the whole difference.
	_, _, err := store.Load()
	if !errors.Is(err, errKeychainSilent) {
		t.Fatalf("err = %v, want errKeychainSilent", err)
	}
}

func TestASilentKeychainOnDeleteStillReportsSuccess(t *testing.T) {
	keychain := &stubKeychain{block: make(chan struct{}), found: true}
	store, fallback := responsiveFor(t, keychain)
	defer close(keychain.block)

	if err := fallback.Save(Credentials{AccessToken: "left-over"}); err != nil {
		t.Fatalf("seeding the fallback: %v", err)
	}

	// Nil, not an error: what `logout` exists for — the server-side revocation — has already
	// happened by this point, and what may survive is a dead token in the keychain (see
	// responsiveKeychain). Failing here would tell the person the logout did not work.
	if err := store.Delete(); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	if _, found := loadFallback(t, fallback); found {
		t.Fatal("the fallback file survived a delete")
	}
}

func TestAKeychainCopyWithNothingInItStillFindsTheFileCopy(t *testing.T) {
	// The state a timed-out `login` leaves behind: the credential in the file, and a keychain whose
	// read of a *nonexistent* item comes back instantly (a read needs no unlock, a write does). A
	// version of this that only consulted the file on a *timeout* said "not signed in" here while
	// the credential sat on disk — found by running it.
	keychain := &stubKeychain{}
	store, fallback := responsiveFor(t, keychain)
	if err := fallback.Save(Credentials{AccessToken: "written-when-locked"}); err != nil {
		t.Fatalf("seeding the fallback: %v", err)
	}

	got, found, err := store.Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if !found || got.AccessToken != "written-when-locked" {
		t.Fatalf("got %+v, found=%v", got, found)
	}
}

func TestTheLaterOfTwoCopiesIsTheOneServed(t *testing.T) {
	// Two copies exist whenever the keychain was unreachable for a write and reachable afterwards.
	// The earlier one holds a refresh token the server has already rotated, and presenting a spent
	// refresh token is a replay — ADR 0024 answers that by revoking the whole chain. So this is the
	// difference between "signed in" and "signed out for no visible reason".
	older := time.Now().Add(-time.Hour)
	newer := time.Now()

	keychain := &stubKeychain{
		creds: Credentials{AccessToken: "the-keychain-copy", ExpiresAt: older},
		found: true,
	}
	store, fallback := responsiveFor(t, keychain)
	if err := fallback.Save(Credentials{AccessToken: "the-newer-file-copy", ExpiresAt: newer}); err != nil {
		t.Fatalf("seeding the fallback: %v", err)
	}

	got, found, err := store.Load()
	if err != nil || !found {
		t.Fatalf("Load: %v, found=%v", err, found)
	}
	if got.AccessToken != "the-newer-file-copy" {
		t.Fatalf("served %q, want the later write", got.AccessToken)
	}

	// And the other way round: a keychain copy that is the later one wins, which is the ordinary
	// case after any successful write.
	keychain.creds = Credentials{AccessToken: "the-keychain-copy", ExpiresAt: newer.Add(time.Hour)}
	got, _, err = store.Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if got.AccessToken != "the-keychain-copy" {
		t.Fatalf("served %q, want the keychain's later copy", got.AccessToken)
	}
}

func TestASuccessfulKeychainWriteRemovesTheFileCopy(t *testing.T) {
	keychain := &stubKeychain{}
	store, fallback := responsiveFor(t, keychain)
	if err := fallback.Save(Credentials{AccessToken: "the-old-file-copy"}); err != nil {
		t.Fatalf("seeding the fallback: %v", err)
	}

	if err := store.Save(Credentials{AccessToken: "the-new-keychain-copy"}); err != nil {
		t.Fatalf("Save: %v", err)
	}

	// Left behind, the file copy is what keeps the two-copies problem alive past the run that
	// created it.
	if _, found := loadFallback(t, fallback); found {
		t.Fatal("the older file copy survived a successful keychain write")
	}
}

func TestLogoutClearsBothCopies(t *testing.T) {
	keychain := &stubKeychain{creds: Credentials{AccessToken: "in-the-keychain"}, found: true}
	store, fallback := responsiveFor(t, keychain)
	if err := fallback.Save(Credentials{AccessToken: "in-the-file"}); err != nil {
		t.Fatalf("seeding the fallback: %v", err)
	}

	if err := store.Delete(); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	// A logout that cleared one and left the other is the exact thing this command exists to avoid.
	if keychain.deleted != 1 {
		t.Fatalf("the keychain copy was deleted %d times", keychain.deleted)
	}
	if _, found := loadFallback(t, fallback); found {
		t.Fatal("the file copy survived a logout")
	}
}

// loadFallback reads the file store directly, so a test can ask what actually landed on disk rather
// than what the decorator said it did.
func loadFallback(t *testing.T, store *fileStore) (Credentials, bool) {
	t.Helper()
	creds, found, err := store.Load()
	if err != nil {
		t.Fatalf("reading %s: %v", store.path, err)
	}
	return creds, found
}

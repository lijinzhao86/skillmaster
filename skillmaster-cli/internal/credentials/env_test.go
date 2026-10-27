package credentials

import (
	"errors"
	"testing"
)

// stubStore stands in for the keychain or the fallback file: what matters here is *which* store a
// write reached, and the environment wrapper's whole job is to answer that differently from the read.
type stubStore struct {
	creds   Credentials
	found   bool
	loadErr error

	saves   []Credentials
	deletes int
}

func (s *stubStore) Load() (Credentials, bool, error) {
	return s.creds, s.found, s.loadErr
}

func (s *stubStore) Save(c Credentials) error {
	s.saves = append(s.saves, c)
	s.creds, s.found = c, true
	return nil
}

func (s *stubStore) Clear() error {
	s.deletes++
	s.creds, s.found = Credentials{}, false
	return nil
}

func (s *stubStore) Where() string { return "钥匙串" }

func wrapped(value string) (EnvStore, *stubStore) {
	fallback := &stubStore{creds: Credentials{
		ClientID: "skillmaster-cli", AccessToken: "stored-token", RefreshToken: "stored-refresh",
	}, found: true}
	return EnvStore{Var: "SKILLMASTER_API_KEY", Value: value, Fallback: fallback}, fallback
}

// The environment wins, and the stored credential is untouched rather than replaced.
//
// This is the shape a container has: nothing on disk, one exported variable. `Load` returning the
// environment's key has to leave the fallback alone — reading it there would be a second credential
// in play, and the one that is used would depend on which store answered first.
func TestTheEnvironmentIsWhatGetsPresented(t *testing.T) {
	store, fallback := wrapped("skm_from-env")

	creds, found, err := store.Load()
	if err != nil || !found {
		t.Fatalf("Load = (%v, %v), want a credential", found, err)
	}
	if creds.AccessToken != "skm_from-env" {
		t.Fatalf("presented %q, want the environment's key", creds.AccessToken)
	}
	// Nothing beside the token: no refresh token, because nothing renews a key; no client id, because
	// a client id is what a refresh goes out as; and no expiry, because the server is the authority
	// on whether a key still works and a date kept here could only disagree with it.
	if creds.RefreshToken != "" || creds.ClientID != "" || !creds.ExpiresAt.IsZero() {
		t.Fatalf("the environment's credential carries more than a token: %+v", creds)
	}
	if fallback.deletes != 0 || len(fallback.saves) != 0 {
		t.Fatal("reading the environment's key wrote to this machine's store")
	}
}

func TestWithNoVariableTheStoredCredentialIsUsed(t *testing.T) {
	store, _ := wrapped("")

	creds, found, err := store.Load()
	if err != nil || !found {
		t.Fatalf("Load = (%v, %v), want the stored credential", found, err)
	}
	if creds.AccessToken != "stored-token" {
		t.Fatalf("presented %q, want the stored token", creds.AccessToken)
	}
	if store.FromEnv() {
		t.Fatal("an unset variable was reported as the source")
	}
}

// The whitespace every way of setting a variable can add, taken off — and a value that is *only*
// whitespace read as "nothing was exported" rather than as a credential of spaces.
//
// A trailing newline is what `export K=$(cat file)`, a Docker secret file and a Kubernetes Secret
// all produce. Left on, it goes into the `Authorization` header, where the person's report is either
// a transport error that says nothing about the credential or a 401 that reads as「这把 key 不对」.
func TestTheVariablesValueIsTrimmedTheWayAPasteIs(t *testing.T) {
	store, _ := wrapped("  skm_from-env\n")
	creds, found, err := store.Load()
	if err != nil || !found {
		t.Fatalf("Load = (%v, %v), want a credential", found, err)
	}
	if creds.AccessToken != "skm_from-env" {
		t.Fatalf("presented %q, want the value with its whitespace removed", creds.AccessToken)
	}
	if !store.FromEnv() || store.Source() != "环境变量 SKILLMASTER_API_KEY" {
		t.Fatalf("FromEnv=%v Source=%q, want the variable to be the source", store.FromEnv(), store.Source())
	}

	// Only whitespace: nothing was exported in any sense this program can use, so the machine's own
	// credential is what gets presented — reporting an empty token as a credential would turn a
	// formatting accident into a 401 that blames the key.
	blank, _ := wrapped("  \n")
	creds, found, err = blank.Load()
	if err != nil || !found {
		t.Fatalf("Load = (%v, %v), want the stored credential", found, err)
	}
	if creds.AccessToken != "stored-token" {
		t.Fatalf("presented %q, want the stored credential", creds.AccessToken)
	}
	if blank.FromEnv() {
		t.Fatal("a blank variable was reported as the source of the credential")
	}
}

// A write cannot reach the environment, so it goes to the machine — and the login that just happened
// is therefore not a no-op while a variable is set. The caller says the variable still wins; this
// only has to make sure the write happened somewhere real.
func TestWritesGoToThisMachineEvenWhileTheVariableIsSet(t *testing.T) {
	store, fallback := wrapped("skm_from-env")

	if err := store.Save(Credentials{AccessToken: "skm_new"}); err != nil {
		t.Fatalf("Save: %v", err)
	}
	if len(fallback.saves) != 1 || fallback.saves[0].AccessToken != "skm_new" {
		t.Fatalf("the write did not reach the fallback store: %+v", fallback.saves)
	}

	if err := store.Clear(); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	if fallback.deletes != 1 {
		t.Fatalf("Delete reached the fallback %d times, want 1", fallback.deletes)
	}
}

// Where and Source are two questions with two answers while the variable is set, and conflating them
// is how a message ends up saying a key was stored in the environment.
func TestWhereAndSourceAnswerDifferentQuestions(t *testing.T) {
	store, _ := wrapped("skm_from-env")

	if got := store.Where(); got != "钥匙串" {
		t.Fatalf("Where = %q, want where a credential is written and deleted", got)
	}
	if got := store.Source(); got != "环境变量 SKILLMASTER_API_KEY" {
		t.Fatalf("Source = %q, want the variable it will actually present", got)
	}

	unset, _ := wrapped("")
	if got := unset.Source(); got != "钥匙串" {
		t.Fatalf("Source = %q, want the store when nothing is exported", got)
	}
}

// A read that fails is the fallback's failure and stays one: the wrapper is a rule about precedence,
// not a place that swallows the reason a credential could not be read.
func TestAFailedReadIsPassedThrough(t *testing.T) {
	store, fallback := wrapped("")
	fallback.loadErr = errors.New("钥匙串超时")

	if _, _, err := store.Load(); err == nil {
		t.Fatal("an unreadable store reported a successful read")
	}
}

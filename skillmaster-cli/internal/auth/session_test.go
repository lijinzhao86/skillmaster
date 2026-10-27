package auth

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// memoryStore is the credential store without the disk. Counting writes is how the tests ask "was
// anything written" rather than only what came back.
type memoryStore struct {
	creds   credentials.Credentials
	found   bool
	saves   int
	deletes int
}

func (s *memoryStore) Load() (credentials.Credentials, bool, error) {
	return s.creds, s.found, nil
}

func (s *memoryStore) Save(c credentials.Credentials) error {
	s.creds, s.found, s.saves = c, true, s.saves+1
	return nil
}

func (s *memoryStore) Clear() error {
	s.deletes++
	s.found = false
	return nil
}

func (s *memoryStore) Where() string { return "a test" }

// aLogin is what this version's `login` writes: the token, its date, its scopes, and a stamp — no
// refresh token, and no client id, because nothing renews it.
func aLogin(expiresAt time.Time) credentials.Credentials {
	return credentials.Credentials{
		AccessToken: "a-login-token",
		ExpiresAt:   expiresAt,
		Scope:       "skills:read skills:write",
		SavedAt:     time.Now(),
	}
}

// TestAKeyIsHandedBackUntouched is the rule that has no exception: the server decides whether a key
// still works, and anything this machine concluded from the date stored beside it would be a guess
// whose cost is a wrong message.
func TestAKeyIsHandedBackUntouched(t *testing.T) {
	store := &memoryStore{found: true, creds: credentials.Credentials{
		AccessToken: "skm_" + "abcdefghijklmnopqrstuvwxyz0123456789-_ABC",
		// The same date `login` stores for display, and a date that has gone by: a key is not judged
		// by it either way.
		ExpiresAt: time.Now().Add(-time.Hour),
	}}

	got, err := AccessToken(store)
	if err != nil {
		t.Fatalf("AccessToken: %v", err)
	}
	if got.AccessToken != store.creds.AccessToken {
		t.Fatalf("access token = %q", got.AccessToken)
	}
	if store.saves != 0 {
		t.Fatalf("a key was rewritten %d times", store.saves)
	}
}

// The credential this version writes is handed over whatever its stored date says.
//
// **This is the version's whole change to this function.** `expires_at` from the exchange is the
// *earliest* moment the credential could end — the server slides it on every use — so a machine whose
// copy of that date has gone by is holding a credential that is very likely still working, and a date
// used as a liveness judgement here would report a healthy login as dead. What it is, is the server's
// answer to give (and the server names which of the two rulers ran out).
func TestALoginCredentialIsPresentedEvenWhenTheStoredDateHasGoneBy(t *testing.T) {
	store := &memoryStore{found: true, creds: aLogin(time.Now().Add(-time.Hour))}

	got, err := AccessToken(store)
	if err != nil {
		t.Fatalf("AccessToken: %v", err)
	}
	if got.AccessToken != "a-login-token" {
		t.Fatalf("access token = %q", got.AccessToken)
	}
	if store.deletes != 0 || store.saves != 0 {
		t.Fatal("a credential that does not need renewing was written or deleted")
	}
}

// A spent token from the old browser login gets its own sentence, and is **not** thrown away.
//
// The sentence has to say what the thing is, why it stopped working and what to do (v4 Q8(i)), and
// none of those is 「the authorization was revoked」 -- nothing asked a server about it. It is kept
// because the refresh token inside it is the only thing that can end that authorization, and the
// thing that presents it is the next `login`.
func TestAnOldBrowserLoginSaysWhatItIs(t *testing.T) {
	store := &memoryStore{found: true, creds: credentials.Credentials{
		ClientID: "skillmaster-cli", AccessToken: "an-oauth-token", RefreshToken: "refresh-one",
		ExpiresAt: time.Now().Add(-time.Hour), SavedAt: time.Now(),
	}}

	_, err := AccessToken(store)
	if !errors.Is(err, ErrOldBrowserLogin) {
		t.Fatalf("err = %v, want ErrOldBrowserLogin", err)
	}
	for _, wanted := range []string{"旧版的浏览器登录", "不再续期", "skillmaster login", "结束掉"} {
		if !strings.Contains(err.Error(), wanted) {
			t.Errorf("message = %q, want it to mention %q", err, wanted)
		}
	}
	// The three sentences it must not be: a translation of a server code, a claim that something
	// revoked it, and the unattended leftover's reason.
	for _, unwanted := range []string{"invalid_grant", "已撤销", "无人值守"} {
		if strings.Contains(err.Error(), unwanted) {
			t.Errorf("message = %q must not say %q", err, unwanted)
		}
	}
	if store.deletes != 0 || !store.found {
		t.Fatal("the credential was thrown away, taking the only means of ending it with it")
	}
}

// The unattended login's leftover says the three things the design asks for, and never says the two
// it must not.
//
// **Its shape is the stamp's absence**, which is what makes it identifiable at all: every blob this
// CLI's stores write carries `SavedAt`, so a token with none was written before the stamp existed,
// and the only such shape without a refresh token is that one.
func TestTheUnattendedLeftoverSaysWhyItCannotBeEnded(t *testing.T) {
	store := &memoryStore{found: true, creds: credentials.Credentials{
		ClientID: "dev-ci", AccessToken: "an-oauth-token",
		ExpiresAt: time.Now().Add(-time.Hour),
	}}

	_, err := AccessToken(store)
	if !errors.Is(err, ErrNotRenewable) {
		t.Fatalf("err = %v, want ErrNotRenewable", err)
	}
	for _, wanted := range []string{"无人值守登录", "撤不掉", "自己失效", "skillmaster login"} {
		if !strings.Contains(err.Error(), wanted) {
			t.Errorf("message = %q, want it to mention %q", err, wanted)
		}
	}
	// **「上一份已撤销」 is a sentence about something that never happened**, which is the one thing the
	// design names explicitly here. And nothing was written or deleted: the credential is spent, not
	// revoked, and there is nothing this machine can do about it.
	if strings.Contains(err.Error(), "已撤销") {
		t.Errorf("message = %q claims the leftover was revoked", err)
	}
	if store.deletes != 0 || store.saves != 0 {
		t.Fatal("a leftover nothing can renew was rewritten or deleted")
	}
}

// A leftover whose token has not run out yet is still a credential this version will not present, and
// the test says so rather than leaving it to the reader.
//
// **The cost is deliberate and is one login long**: the old shape's access token may have some of its
// hour left, and what the person gets instead is one sentence that says what the thing is and one
// command that fixes it — where presenting it would work for a few minutes and then hand them a
// refusal whose reason belongs to a shape they are not holding. v4 Q8 asks for the leftover's own
// sentence at the command, which this is.
func TestALeftoverIsNotPresentedEvenWhileItsTokenMightStillWork(t *testing.T) {
	fresh := time.Now().Add(time.Hour)
	for _, c := range []credentials.Credentials{
		{ClientID: "skillmaster-cli", AccessToken: "an-oauth-token", RefreshToken: "refresh-one",
			ExpiresAt: fresh, SavedAt: time.Now()},
		{ClientID: "dev-ci", AccessToken: "an-oauth-token", ExpiresAt: fresh},
	} {
		if _, err := AccessToken(&memoryStore{found: true, creds: c}); err == nil {
			t.Fatalf("a leftover %+v was presented as a usable credential", c)
		}
	}
}

func TestAccessTokenSaysSoWhenNobodyIsSignedIn(t *testing.T) {
	_, err := AccessToken(&memoryStore{})

	if !errors.Is(err, ErrNotSignedIn) {
		t.Fatalf("err = %v, want ErrNotSignedIn", err)
	}
}

// The one caller left is `login` replacing an authorization, so these go at RevokeAuthorization
// directly rather than through a command: what is worth testing is what gets presented — the refresh
// token, and no client authentication of any kind.
func TestRevokingPrefersTheRefreshToken(t *testing.T) {
	fake := newFakeServer(t)

	err := RevokeAuthorization(context.Background(), RevokeOptions{
		Server: fake.URL, Credential: credentials.Credentials{
			ClientID: "skillmaster-cli", AccessToken: "access-one", RefreshToken: "refresh-one",
		},
	})
	if err != nil {
		t.Fatalf("RevokeAuthorization: %v", err)
	}

	if len(fake.revocations) != 1 {
		t.Fatalf("the revocation endpoint was called %d times", len(fake.revocations))
	}
	// The refresh token, not the access token: it is the long-lived half, and the server takes the
	// whole authorization down with it — including the access token, which would otherwise keep
	// working for up to an hour.
	if got := fake.revocations[0].Get("token"); got != "refresh-one" {
		t.Errorf("revoked %q, want the refresh token", got)
	}
}

func TestRevokingAnEmptyCredentialDoesNothing(t *testing.T) {
	fake := newFakeServer(t)

	// What `login` calls when there was nothing stored before, and what v4's own credential would
	// reach if it were ever handed here: no refresh token, so nothing to present and nothing to ask
	// the server. Reported as success because that is the state the caller wants.
	for _, c := range []credentials.Credentials{
		{ClientID: "skillmaster-cli"},
		aLogin(time.Now().Add(time.Hour)),
	} {
		err := RevokeAuthorization(context.Background(), RevokeOptions{Server: fake.URL, Credential: c})
		if err != nil {
			t.Fatalf("RevokeAuthorization(%+v): %v", c, err)
		}
	}
	if len(fake.revocations) != 0 {
		t.Fatalf("the revocation endpoint was called %d times with nothing to revoke",
			len(fake.revocations))
	}
}

func TestRevokingABrowserCredentialSendsNoClientAuthentication(t *testing.T) {
	fake := newFakeServer(t)

	// **A public client has nothing to authenticate with, and the wire shape has to stay that way.**
	// This is what the confidential half used to break: a secret in either place made the server read
	// the request as a confidential client's and refuse it with `invalid_client`, which reads like a
	// wrong secret rather than a wrong kind of client. The half that could send one went with
	// `--client-credentials` in v3, and this pins the shape so it cannot come back by accident.
	err := RevokeAuthorization(context.Background(), RevokeOptions{
		Server: fake.URL, Credential: credentials.Credentials{
			ClientID: "skillmaster-cli", AccessToken: "access-one", RefreshToken: "refresh-one",
		},
	})
	if err != nil {
		t.Fatalf("RevokeAuthorization: %v", err)
	}
	if got := fake.revocations[0].Get("client_secret"); got != "" {
		t.Errorf("a public client's revocation carried client_secret = %q", got)
	}
	if got := fake.revocationAuth[0]; got != "" {
		t.Errorf("a public client's revocation carried Authorization: %q", got)
	}
	if got := fake.revocations[0].Get("token"); got != "refresh-one" {
		t.Errorf("revoked %q, want the refresh token", got)
	}
}

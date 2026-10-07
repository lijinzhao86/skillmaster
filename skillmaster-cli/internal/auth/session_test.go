package auth

import (
	"context"
	"encoding/base64"
	"errors"
	"net/http"
	"net/url"
	"testing"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// memoryStore is the credential store without the disk. Counting saves and deletes is how the
// tests ask "was it written" and "was it cleaned up" rather than only what came back.
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

func (s *memoryStore) Delete() error {
	s.deletes++
	s.found = false
	return nil
}

func (s *memoryStore) Where() string { return "a test" }

func sessionFor(fake *fakeServer, store credentials.Store) SessionOptions {
	return SessionOptions{
		Server: fake.URL, Dir: "", Store: store,
	}
}

func TestAFreshAccessTokenIsUsedWithoutAskingTheServer(t *testing.T) {
	fake := newFakeServer(t)
	store := &memoryStore{found: true, creds: credentials.Credentials{
		ClientID:     "skillmaster-cli",
		AccessToken:  "still-good",
		RefreshToken: "refresh-one",
		ExpiresAt:    time.Now().Add(30 * time.Minute),
	}}

	got, err := AccessToken(context.Background(), sessionFor(fake, store))
	if err != nil {
		t.Fatalf("AccessToken: %v", err)
	}
	if got.AccessToken != "still-good" {
		t.Fatalf("access token = %q", got.AccessToken)
	}
	// The whole reason the access token is stored: refreshing rotates the refresh token, so a CLI
	// that refreshed on every command would rotate on every command an agent runs.
	if len(fake.tokenRequests) != 0 {
		t.Fatalf("the token endpoint was called %d times for a token that is still valid",
			len(fake.tokenRequests))
	}
	if store.saves != 0 {
		t.Fatalf("the credential was rewritten %d times without changing", store.saves)
	}
}

func TestASpentAccessTokenIsRefreshedAndTheRotationIsStored(t *testing.T) {
	fake := newFakeServer(t)
	fake.tokenResponse = func(w http.ResponseWriter, form url.Values) {
		if got := form.Get("grant_type"); got != "refresh_token" {
			t.Errorf("grant_type = %q, want refresh_token", got)
		}
		if got := form.Get("refresh_token"); got != "refresh-one" {
			t.Errorf("refresh_token = %q", got)
		}
		writeJSON(t, w, map[string]any{
			"access_token": "access-two", "refresh_token": "refresh-two", "expires_in": 3600,
		})
	}
	store := &memoryStore{found: true, creds: credentials.Credentials{
		ClientID:     "skillmaster-cli",
		AccessToken:  "expired",
		RefreshToken: "refresh-one",
		ExpiresAt:    time.Now().Add(-time.Minute),
	}}

	got, err := AccessToken(context.Background(), sessionFor(fake, store))
	if err != nil {
		t.Fatalf("AccessToken: %v", err)
	}
	if got.AccessToken != "access-two" {
		t.Fatalf("access token = %q", got.AccessToken)
	}
	// Storing the rotated refresh token is the mistake with the delayed symptom: keeping the old
	// one means the next refresh presents a spent token, which the server reads as a replay and
	// answers by revoking the whole chain (ADR 0024).
	if store.creds.RefreshToken != "refresh-two" {
		t.Fatalf("stored refresh token = %q, want the rotated one", store.creds.RefreshToken)
	}
	if store.saves != 1 {
		t.Fatalf("the credential was saved %d times", store.saves)
	}
}

func TestATokenExpiringWithinTheSkewIsRefreshed(t *testing.T) {
	fake := newFakeServer(t)
	store := &memoryStore{found: true, creds: credentials.Credentials{
		ClientID: "skillmaster-cli", AccessToken: "about-to-die", RefreshToken: "refresh-one",
		// Valid for another thirty seconds: valid now, and not when the request it is for arrives.
		ExpiresAt: time.Now().Add(30 * time.Second),
	}}

	if _, err := AccessToken(context.Background(), sessionFor(fake, store)); err != nil {
		t.Fatalf("AccessToken: %v", err)
	}
	if len(fake.tokenRequests) != 1 {
		t.Fatalf("a token inside the expiry skew was used as-is; it would have expired in flight")
	}
}

func TestARefreshIsPresentedAsTheClientTheCredentialWasIssuedTo(t *testing.T) {
	fake := newFakeServer(t)
	fake.tokenResponse = func(w http.ResponseWriter, form url.Values) {
		if got := form.Get("client_id"); got != "dev-ci" {
			t.Errorf("client_id = %q, want the credential's own (dev-ci)", got)
		}
		writeJSON(t, w, map[string]any{
			"access_token": "access-two", "refresh_token": "refresh-two", "expires_in": 3600,
		})
	}
	// An unattended credential, minted for a client that is not the browser login's. Sending this
	// CLI's own compiled-in id instead is `invalid_client` from the server — which is what the option
	// this test can no longer pass used to invite.
	store := &memoryStore{found: true, creds: credentials.Credentials{
		ClientID: "dev-ci", AccessToken: "expired", RefreshToken: "refresh-one",
		ExpiresAt: time.Now().Add(-time.Minute),
	}}

	if _, err := AccessToken(context.Background(), sessionFor(fake, store)); err != nil {
		t.Fatalf("AccessToken: %v", err)
	}
}

func TestASpentCredentialWithNoRefreshTokenSaysItCannotBeRenewed(t *testing.T) {
	fake := newFakeServer(t)
	store := &memoryStore{found: true, creds: credentials.Credentials{
		ClientID: "dev-ci", AccessToken: "expired",
		// No refresh token: `client_credentials` issues none (ADR 0022).
		ExpiresAt: time.Now().Add(-time.Minute),
	}}

	_, err := AccessToken(context.Background(), sessionFor(fake, store))
	if !errors.Is(err, ErrNotRenewable) {
		t.Fatalf("err = %v, want ErrNotRenewable", err)
	}
	if len(fake.tokenRequests) != 0 {
		t.Fatalf("the token endpoint was called %d times with nothing to present", len(fake.tokenRequests))
	}
	// Kept, not deleted: the credential is spent, not revoked, and the secret that mints a new one
	// lives in the environment rather than here.
	if store.deletes != 0 || !store.found {
		t.Fatal("a spent-but-renewable-by-hand credential was thrown away")
	}
}

func TestRenewRefreshesATokenTheStoredExpiryStillBelievesIn(t *testing.T) {
	fake := newFakeServer(t)
	store := &memoryStore{found: true, creds: credentials.Credentials{
		ClientID: "skillmaster-cli", AccessToken: "refused-by-the-server", RefreshToken: "refresh-one",
		// An hour left on the clock, which is exactly the situation this exists for: the server says
		// no and this machine's arithmetic says there is nothing wrong.
		ExpiresAt: time.Now().Add(time.Hour),
	}}

	got, err := Renew(context.Background(), sessionFor(fake, store), "refused-by-the-server")
	if err != nil {
		t.Fatalf("Renew: %v", err)
	}
	if got.AccessToken == "refused-by-the-server" {
		t.Fatal("Renew handed back the very token the server had refused")
	}
	// The plain path must keep the old behaviour, or every command would rotate the chain.
	if len(fake.tokenRequests) != 1 {
		t.Fatalf("the token endpoint was called %d times", len(fake.tokenRequests))
	}
	if _, err := AccessToken(context.Background(), sessionFor(fake, store)); err != nil {
		t.Fatalf("AccessToken: %v", err)
	}
	if len(fake.tokenRequests) != 1 {
		t.Fatalf("AccessToken refreshed a token with an hour left on it (%d calls)",
			len(fake.tokenRequests))
	}
}

func TestRenewLeavesAnAlreadyReplacedCredentialAlone(t *testing.T) {
	fake := newFakeServer(t)
	store := &memoryStore{found: true, creds: credentials.Credentials{
		ClientID: "skillmaster-cli", AccessToken: "the-one-the-server-refused",
		RefreshToken: "refresh-one", ExpiresAt: time.Now().Add(time.Hour),
	}}

	// What the window between the 401 and this call looks like when another process got there first:
	// the store holds something else now, so there is nothing here to distrust. Rotating again would
	// spend a rotation to learn what the store already says.
	first, err := Renew(context.Background(), sessionFor(fake, store), "the-one-the-server-refused")
	if err != nil {
		t.Fatalf("Renew: %v", err)
	}
	before := len(fake.tokenRequests)

	// Still reporting the *same* refused token, which is what a second command doing its own retry
	// would do — the store has moved on, and that is the whole answer.
	second, err := Renew(context.Background(), sessionFor(fake, store), "the-one-the-server-refused")
	if err != nil {
		t.Fatalf("Renew: %v", err)
	}
	if len(fake.tokenRequests) != before {
		t.Fatalf("a second Renew refreshed again (%d calls, want %d)", len(fake.tokenRequests), before)
	}
	if second.AccessToken != first.AccessToken {
		t.Fatalf("second Renew returned %q, want the credential already in the store", second.AccessToken)
	}
}

func TestARefreshTheServerRefusesEndsTheLocalCredential(t *testing.T) {
	fake := newFakeServer(t)
	fake.tokenResponse = func(w http.ResponseWriter, _ url.Values) {
		w.WriteHeader(http.StatusBadRequest)
		writeJSON(t, w, map[string]string{
			"error": "invalid_grant", "error_description": "the authorization was revoked",
		})
	}
	store := &memoryStore{found: true, creds: credentials.Credentials{
		ClientID: "skillmaster-cli", AccessToken: "expired", RefreshToken: "revoked-refresh",
		ExpiresAt: time.Now().Add(-time.Minute),
	}}

	_, err := AccessToken(context.Background(), sessionFor(fake, store))
	if !errors.Is(err, ErrAuthorizationRevoked) {
		t.Fatalf("err = %v, want ErrAuthorizationRevoked", err)
	}
	// Deleted, not merely reported: a file that keeps producing the same refusal is worse than no
	// file, because the person has nothing to act on but an error code.
	if store.deletes != 1 {
		t.Fatalf("the credential was deleted %d times, want 1", store.deletes)
	}
}

func TestAccessTokenSaysSoWhenNobodyIsSignedIn(t *testing.T) {
	fake := newFakeServer(t)
	_, err := AccessToken(context.Background(), sessionFor(fake, &memoryStore{}))

	if !errors.Is(err, ErrNotSignedIn) {
		t.Fatalf("err = %v, want ErrNotSignedIn", err)
	}
	if len(fake.tokenRequests) != 0 {
		t.Fatal("the server was called with nothing to present")
	}
}

func TestLogoutRevokesTheRefreshTokenAndThenDeletes(t *testing.T) {
	fake := newFakeServer(t)
	store := &memoryStore{found: true, creds: credentials.Credentials{
		ClientID: "skillmaster-cli", AccessToken: "access-one", RefreshToken: "refresh-one",
		ExpiresAt: time.Now().Add(time.Hour),
	}}

	if err := Logout(context.Background(), LogoutOptions{Server: fake.URL, Store: store}); err != nil {
		t.Fatalf("Logout: %v", err)
	}

	if len(fake.revocations) != 1 {
		t.Fatalf("the revocation endpoint was called %d times", len(fake.revocations))
	}
	// The refresh token, not the access token: it is the long-lived half, and the server takes the
	// whole authorization down with it — including the access token, which the person cannot see
	// and which would otherwise keep working for up to an hour.
	if got := fake.revocations[0].Get("token"); got != "refresh-one" {
		t.Errorf("revoked %q, want the refresh token", got)
	}
	if store.deletes != 1 {
		t.Fatalf("the credential was deleted %d times, want 1", store.deletes)
	}
}

func TestLogoutRevokesTheAccessTokenWithTheSecretWhenThereIsNoRefreshToken(t *testing.T) {
	fake := newFakeServer(t)
	// The unattended shape: an access token and nothing else.
	store := &memoryStore{found: true, creds: credentials.Credentials{
		ClientID: "dev-ci", AccessToken: "access-one", ExpiresAt: time.Now().Add(time.Hour),
	}}

	err := Logout(context.Background(), LogoutOptions{
		Server: fake.URL, Store: store, Secret: "the-secret",
	})
	if err != nil {
		t.Fatalf("Logout: %v", err)
	}

	// Without this the command reports a successful logout while the token keeps working for up to
	// an hour — and this is the credential a machine holds with nobody watching it.
	if len(fake.revocations) != 1 {
		t.Fatalf("the revocation endpoint was called %d times, want 1", len(fake.revocations))
	}
	if got := fake.revocations[0].Get("token"); got != "access-one" {
		t.Errorf("revoked %q, want the access token", got)
	}
	// The secret, in the header rather than the form: `client_credentials` requires a confidential
	// client, and the server registers `client_secret_basic`. Found by running it — a bare client_id
	// and a form-encoded secret were each refused with `invalid_client` before this.
	want := "Basic " + base64.StdEncoding.EncodeToString([]byte("dev-ci:the-secret"))
	if got := fake.revocationAuth[0]; got != want {
		t.Errorf("Authorization = %q, want %q", got, want)
	}
	if got := fake.revocations[0].Get("client_secret"); got != "" {
		t.Errorf("the secret was also put in the form (%q), which is the other registered method", got)
	}
	if store.deletes != 1 {
		t.Fatalf("the credential was deleted %d times, want 1", store.deletes)
	}
}

func TestRevokingAnEmptyCredentialDoesNothing(t *testing.T) {
	fake := newFakeServer(t)

	// What `login` calls when there was nothing stored before: no halves, so nothing to present and
	// nothing to ask the server. Reported as success because that is the state the caller wants.
	err := RevokeAuthorization(context.Background(), RevokeOptions{
		Server: fake.URL, Credential: credentials.Credentials{ClientID: "skillmaster-cli"},
	})
	if err != nil {
		t.Fatalf("RevokeAuthorization: %v", err)
	}
	if len(fake.revocations) != 0 {
		t.Fatalf("the revocation endpoint was called %d times with nothing to revoke",
			len(fake.revocations))
	}
}

func TestLogoutRefusesToPretendWhenAnUnattendedCredentialHasNoSecret(t *testing.T) {
	fake := newFakeServer(t)
	store := &memoryStore{found: true, creds: credentials.Credentials{
		ClientID: "dev-ci", AccessToken: "access-one", ExpiresAt: time.Now().Add(time.Hour),
	}}

	err := Logout(context.Background(), LogoutOptions{Server: fake.URL, Store: store})
	if !errors.Is(err, ErrRevocationNeedsSecret) {
		t.Fatalf("err = %v, want ErrRevocationNeedsSecret", err)
	}
	// Kept, and nothing was asked of the server: a credential deleted next to a token nobody revoked
	// is the logout that only looks like one, and there would be nothing left to retry with.
	if store.deletes != 0 || !store.found {
		t.Fatal("the credential was removed without being revoked")
	}
	if len(fake.revocations) != 0 {
		t.Fatalf("the revocation endpoint was called %d times with nothing to authenticate with",
			len(fake.revocations))
	}
}

func TestLogoutDoesNotSendASecretForABrowserCredential(t *testing.T) {
	fake := newFakeServer(t)
	store := &memoryStore{found: true, creds: credentials.Credentials{
		ClientID: "skillmaster-cli", AccessToken: "access-one", RefreshToken: "refresh-one",
		ExpiresAt: time.Now().Add(time.Hour),
	}}

	// A secret is present in the environment, which is the real situation for somebody who has used
	// both login shapes on one machine. Sending it would make the server read this as a confidential
	// client's request and refuse it, breaking a logout that worked before the secret was set.
	err := Logout(context.Background(), LogoutOptions{
		Server: fake.URL, Store: store, Secret: "the-secret",
	})
	if err != nil {
		t.Fatalf("Logout: %v", err)
	}
	if got := fake.revocations[0].Get("client_secret"); got != "" {
		t.Errorf("a public client's logout carried client_secret = %q", got)
	}
	if got := fake.revocationAuth[0]; got != "" {
		t.Errorf("a public client's logout carried Authorization: %q", got)
	}
	if got := fake.revocations[0].Get("token"); got != "refresh-one" {
		t.Errorf("revoked %q, want the refresh token", got)
	}
}

func TestLogoutKeepsTheCredentialWhenTheServerRefuses(t *testing.T) {
	fake := newFakeServer(t)
	fake.revokeStatus = http.StatusInternalServerError
	store := &memoryStore{found: true, creds: credentials.Credentials{
		ClientID: "skillmaster-cli", RefreshToken: "refresh-one",
	}}

	err := Logout(context.Background(), LogoutOptions{Server: fake.URL, Store: store})
	if err == nil {
		t.Fatal("a failed revocation was reported as a successful logout")
	}
	// **Kept.** Deleting here would leave the server's copy live with nothing left to present for
	// revoking it — a logout that looks like it worked and is not true. Keeping it is what makes a
	// retry possible.
	if store.deletes != 0 {
		t.Fatalf("the credential was deleted anyway (%d times)", store.deletes)
	}
	if !store.found {
		t.Fatal("the credential is gone after a failed revocation")
	}
}

func TestLogoutWithNothingStoredIsNotAnError(t *testing.T) {
	fake := newFakeServer(t)
	store := &memoryStore{}

	if err := Logout(context.Background(), LogoutOptions{Server: fake.URL, Store: store}); err != nil {
		t.Fatalf("Logout: %v", err)
	}
	if len(fake.revocations) != 0 {
		t.Fatal("the server was called with nothing to revoke")
	}
}

package auth

import (
	"context"
	"encoding/base64"
	"net/http"
	"net/url"
	"testing"
)

// The secret's *placement* is the thing worth a test, because getting it wrong produces a message
// that blames the secret rather than its position.
func TestClientCredentialsPutsTheSecretInBasicAuthAndNotInTheForm(t *testing.T) {
	fake := newFakeServer(t)
	var authorization string
	fake.tokenResponse = nil

	// The fake records the header through a wrapper around its own handler.
	original := fake.Config.Handler
	fake.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/oauth/token" {
			authorization = r.Header.Get("Authorization")
		}
		original.ServeHTTP(w, r)
	})

	tokens, err := ClientCredentials(context.Background(), fake.Client(), fake.URL+"/oauth/token",
		"skillmaster-ci", "a-pipeline-secret", []string{"skills:read"})
	if err != nil {
		t.Fatalf("ClientCredentials: %v", err)
	}
	if tokens.AccessToken == "" {
		t.Fatal("no access token came back")
	}

	want := "Basic " + base64.StdEncoding.EncodeToString([]byte("skillmaster-ci:a-pipeline-secret"))
	if authorization != want {
		t.Errorf("Authorization = %q, want %q", authorization, want)
	}

	if len(fake.tokenRequests) != 1 {
		t.Fatalf("the token endpoint was called %d times", len(fake.tokenRequests))
	}
	form := fake.tokenRequests[0]
	if form.Get("client_secret") != "" {
		t.Error("the secret was sent as a form field; the server registers client_secret_basic")
	}
	if form.Get("client_id") != "" {
		t.Error("the client id was sent as a form field; it belongs in the Basic credentials")
	}
	if got := form.Get("grant_type"); got != "client_credentials" {
		t.Errorf("grant_type = %q", got)
	}
	if got := form.Get("scope"); got != "skills:read" {
		t.Errorf("scope = %q", got)
	}
}

func TestClientCredentialsSendsNoScopeParameterWhenNoneAreAskedFor(t *testing.T) {
	fake := newFakeServer(t)

	if _, err := ClientCredentials(context.Background(), fake.Client(), fake.URL+"/oauth/token",
		"skillmaster-ci", "s", nil); err != nil {
		t.Fatalf("ClientCredentials: %v", err)
	}
	// An empty `scope=` is not the same request as no scope: the server would read it as a request
	// for zero scopes and mint a token that can do nothing.
	if _, present := fake.tokenRequests[0]["scope"]; present {
		t.Errorf("a scope parameter was sent: %v", url.Values(fake.tokenRequests[0]))
	}
}

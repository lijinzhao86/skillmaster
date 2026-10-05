package auth

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

// fakeServer plays the two endpoints `login` uses, and records what it was asked.
//
// Recording rather than only answering: half of what these tests are about is what the CLI *sent* —
// the proof key, the state, the redirect URI — and a fake that only returned a token would pass
// with any of them wrong.
type fakeServer struct {
	*httptest.Server
	tokenRequests []url.Values
	// tokenResponse lets a test make the exchange fail.
	tokenResponse func(w http.ResponseWriter, form url.Values)

	revocations []url.Values
	// revocationAuth is the `Authorization` header of each revocation, in the same order. Kept
	// apart from the form because *where* a client secret goes is the thing that has been got wrong
	// here: the form and the header are two different registered methods.
	revocationAuth []string
	// revokeStatus lets a test make the revocation fail; 0 means 200.
	revokeStatus int
}

func newFakeServer(t *testing.T) *fakeServer {
	t.Helper()
	fake := &fakeServer{}

	mux := http.NewServeMux()
	mux.HandleFunc(DiscoveryPath, func(w http.ResponseWriter, r *http.Request) {
		writeJSON(t, w, map[string]string{
			"issuer":                 fake.URL,
			"authorization_endpoint": fake.URL + "/oauth/authorize",
			"token_endpoint":         fake.URL + "/oauth/token",
			"revocation_endpoint":    fake.URL + "/oauth/revoke",
		})
	})
	mux.HandleFunc("/oauth/token", func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		fake.tokenRequests = append(fake.tokenRequests, r.PostForm)
		if fake.tokenResponse != nil {
			fake.tokenResponse(w, r.PostForm)
			return
		}
		writeJSON(t, w, map[string]any{
			"access_token":  "access-one",
			"refresh_token": "refresh-one",
			"token_type":    "Bearer",
			"expires_in":    3600,
		})
	})

	mux.HandleFunc("/oauth/revoke", func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		fake.revocations = append(fake.revocations, r.PostForm)
		fake.revocationAuth = append(fake.revocationAuth, r.Header.Get("Authorization"))
		if fake.revokeStatus != 0 {
			w.WriteHeader(fake.revokeStatus)
			writeJSON(t, w, map[string]string{"error": "server_error"})
			return
		}
		w.WriteHeader(http.StatusOK)
	})

	fake.Server = httptest.NewServer(mux)
	t.Cleanup(fake.Close)
	return fake
}

// freePort asks the OS for a port and gives it back. There is a race between giving it back and
// binding it again; on a test machine with nothing else starting listeners it does not come up, and
// the alternative — a fixed port — collides with the real CLI when both run.
func freePort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("finding a free port: %v", err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	return port
}

func writeJSON(t *testing.T, w http.ResponseWriter, body any) {
	t.Helper()
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(body); err != nil {
		t.Errorf("writing a response: %v", err)
	}
}

// runLogin drives a whole login with the test standing in for the browser: it receives the
// authorization URL, reads the state out of it, and sends the callback a browser would have sent.
//
// It returns the authorization query as well as the result, because half of what these tests are
// about is what the CLI *asked for* — the challenge, the state, the redirect URI — and a test that
// only looked at the token would pass with any of them wrong.
//
// `tamper` may replace the callback's parameters; nil sends the honest one.
func runLogin(t *testing.T, fake *fakeServer, tamper func(state string) url.Values) (Tokens, error, url.Values) {
	t.Helper()
	port := freePort(t)
	var asked url.Values

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	tokens, err := Login(ctx, LoginOptions{
		Server:   fake.URL,
		ClientID: "skillmaster-cli",
		Port:     port,
		OnURL: func(raw string) {
			parsed, parseErr := url.Parse(raw)
			if parseErr != nil {
				t.Errorf("the authorization URL does not parse: %v", parseErr)
				return
			}
			asked = parsed.Query()

			callback := url.Values{"code": {"the-code"}, "state": {asked.Get("state")}}
			if tamper != nil {
				callback = tamper(asked.Get("state"))
			}
			resp, getErr := http.Get(fmt.Sprintf("http://127.0.0.1:%d/callback?%s",
				port, callback.Encode()))
			if getErr != nil {
				t.Errorf("calling back: %v", getErr)
				return
			}
			resp.Body.Close()
		},
		OpenURL: func(string) error { return nil },
	})
	return tokens, err, asked
}

func TestLoginAsksForTheRightThingsAndStoresWhatComesBack(t *testing.T) {
	fake := newFakeServer(t)

	tokens, err, asked := runLogin(t, fake, nil)
	if err != nil {
		t.Fatalf("login: %v", err)
	}
	if tokens.AccessToken != "access-one" || tokens.RefreshToken != "refresh-one" {
		t.Fatalf("login returned %+v", tokens)
	}

	if got := asked.Get("response_type"); got != "code" {
		t.Errorf("response_type = %q, want code", got)
	}
	if got := asked.Get("client_id"); got != "skillmaster-cli" {
		t.Errorf("client_id = %q", got)
	}
	if got := asked.Get("code_challenge_method"); got != "S256" {
		t.Errorf("code_challenge_method = %q, want S256 — plain would be no protection", got)
	}
	if got := asked.Get("code_challenge"); got == "" {
		t.Error("no code_challenge was sent")
	}
	if got := asked.Get("state"); got == "" {
		t.Error("no state was sent")
	}
	if got := asked.Get("redirect_uri"); !strings.HasPrefix(got, "http://127.0.0.1:") {
		t.Errorf("redirect_uri = %q, want a loopback URI", got)
	}
	// Both scopes, in one request: a second consent screen for the other half would be a second
	// interruption for one decision.
	if got := asked.Get("scope"); got != "skills:read skills:write" {
		t.Errorf("scope = %q", got)
	}

	// The exchange, where the proof key has to be right: the server compares the verifier against
	// the challenge, so sending the wrong one is `invalid_grant` and no token at all.
	if len(fake.tokenRequests) != 1 {
		t.Fatalf("the token endpoint was called %d times", len(fake.tokenRequests))
	}
	exchange := fake.tokenRequests[0]
	if got := exchange.Get("grant_type"); got != "authorization_code" {
		t.Errorf("grant_type = %q", got)
	}
	if got := exchange.Get("code"); got != "the-code" {
		t.Errorf("code = %q", got)
	}
	if got := exchange.Get("code_verifier"); got == "" {
		t.Error("no code_verifier was sent; the challenge proves nothing without it")
	}
	if got := exchange.Get("client_secret"); got != "" {
		t.Errorf("a client_secret was sent (%q) — this is a public client", got)
	}
}

func TestLoginRefusesACallbackWhoseStateIsNotOurs(t *testing.T) {
	fake := newFakeServer(t)

	// Somebody else's authorization request: the code is theirs, the state is theirs. Accepting it
	// would store a credential for an account the person running the command does not own.
	_, err, _ := runLogin(t, fake, func(string) url.Values {
		return url.Values{"code": {"someone-elses-code"}, "state": {"not-our-state"}}
	})

	if err == nil {
		t.Fatal("a callback with the wrong state was accepted")
	}
	if !strings.Contains(err.Error(), "state") {
		t.Errorf("the error does not mention the state: %v", err)
	}
	// And nothing was exchanged: refusing after redeeming the code would be refusing too late.
	if len(fake.tokenRequests) != 0 {
		t.Errorf("the code was redeemed anyway: %v", fake.tokenRequests)
	}
}

func TestLoginSurfacesARefusedAuthorization(t *testing.T) {
	fake := newFakeServer(t)

	_, err, _ := runLogin(t, fake, func(state string) url.Values {
		return url.Values{
			"error":             {"access_denied"},
			"error_description": {"the user refused"},
			"state":             {state},
		}
	})

	if err == nil {
		t.Fatal("a refused authorization was reported as a success")
	}
	if !strings.Contains(err.Error(), "access_denied") {
		t.Errorf("the error does not name the refusal: %v", err)
	}
	if len(fake.tokenRequests) != 0 {
		t.Errorf("a refused authorization still redeemed something: %v", fake.tokenRequests)
	}
}

func TestLoginRefusesATokenResponseWithNoLifetime(t *testing.T) {
	fake := newFakeServer(t)
	fake.tokenResponse = func(w http.ResponseWriter, _ url.Values) {
		writeJSON(t, w, map[string]any{"access_token": "access-one", "token_type": "Bearer"})
	}

	// Defaulted would be guessed, and the guess that fails keeps a dead token in the store until
	// some later command reports a 401 with no explanation.
	if _, err, _ := runLogin(t, fake, nil); err == nil {
		t.Fatal("a token with no expires_in was accepted")
	}
}

func TestListenBindsOnlyTheLoopbackAddress(t *testing.T) {
	port := freePort(t)
	callback, err := Listen(port, "a-state")
	if err != nil {
		t.Fatalf("listening: %v", err)
	}
	defer callback.Close()

	// `0.0.0.0` would let anybody on the same network reach a listener that is about to receive an
	// authorization code (RFC 8252 §7.3). Asserted on the listener itself, since the difference is
	// invisible from the outside on a machine with no other interface.
	address := callback.listener.Addr().(*net.TCPAddr)
	if !address.IP.IsLoopback() {
		t.Fatalf("the listener is bound to %s, which is not loopback", address.IP)
	}
}

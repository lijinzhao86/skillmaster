package auth

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"
)

// fakeServer plays the endpoints `login` uses, and records what it was asked.
//
// Recording rather than only answering: half of what these tests are about is what the CLI *sent* —
// the proof key, the state, the scopes — and a fake that only returned a credential would pass with
// any of them wrong.
type fakeServer struct {
	*httptest.Server
	// signInURL is the address the browser was sent to, whole. Kept because the base it is built on
	// is a part of it that its query parameters cannot show.
	signInURL string
	// issuer is what the discovery document answers with, or empty for this server's own URL.
	issuer string
	// exchangeRequests is the body of each exchange, decoded: the two fields are the code and the
	// verifier, and both are things a person cannot see but a server depends on.
	exchangeRequests []url.Values
	// exchangeResponse lets a test make the exchange fail, or answer something unusable.
	exchangeResponse func(w http.ResponseWriter)

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
		// `issuer` is a field so that a test can hand back the spelling a deployment might use —
		// with a trailing slash — rather than only the one this fake would write.
		issuer := fake.issuer
		if issuer == "" {
			issuer = fake.URL
		}
		writeJSON(t, w, map[string]string{
			"issuer":              issuer,
			"revocation_endpoint": fake.URL + "/oauth/revoke",
		})
	})
	mux.HandleFunc(ExchangePath, func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<16))
		var sent map[string]string
		if err := json.Unmarshal(body, &sent); err != nil {
			t.Errorf("the exchange body is not JSON: %s", body)
		}
		fake.exchangeRequests = append(fake.exchangeRequests, url.Values{
			"code":          {sent["code"]},
			"code_verifier": {sent["code_verifier"]},
		})
		if fake.exchangeResponse != nil {
			fake.exchangeResponse(w)
			return
		}
		writeJSON(t, w, map[string]any{
			"access_token": "access-one",
			"expires_at":   "2026-10-23T10:00:00Z",
			"scope":        "skills:read skills:write",
			"name":         "我的 MacBook",
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

// runLogin drives a whole login with the test standing in for the browser: it receives the sign-in
// URL, reads the state out of it, and sends the callback a browser would have sent.
//
// It returns the sign-in query as well as the result, because half of what these tests are about is
// what the CLI *asked for* — the challenge, the state, the scopes — and a test that only looked at
// the credential would pass with any of them wrong.
//
// `tamper` may replace the callback's parameters; nil sends the honest one.
func runLogin(t *testing.T, fake *fakeServer, tamper func(state string) url.Values) (Credential, error, url.Values) {
	t.Helper()
	port := freePort(t)
	var asked url.Values

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	credential, err := Login(ctx, LoginOptions{
		Server:   fake.URL,
		ClientID: "skillmaster-cli",
		Port:     port,
		// **Named rather than looked up**, because the lookup's answer is a property of the computer
		// running the tests — and the value here is deliberately not the one the fake server returns
		// as the credential's name, so that the address and the exchange are asserting different
		// things. It is the value `scutil` measures on this Mac, from the design's table.
		MachineName: "lijinzhao的MacBook Pro",
		OnURL: func(raw string) {
			fake.signInURL = raw
			parsed, parseErr := url.Parse(raw)
			if parseErr != nil {
				t.Errorf("the sign-in URL does not parse: %v", parseErr)
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
	return credential, err, asked
}

// The address the browser is given is the sign-in page, and its five parameters are the whole of what
// that page reads (v4 §接口 ①).
//
// **Two absences are asserted as deliberately as the five presences.** A `redirect_uri` in the
// address would let the request decide where a code is delivered, and the server reads it from the
// client's own row instead (ADR 0028's fixed port is where that lands); a `code_challenge_method`
// would be a choice on a path whose only method is S256.
func TestLoginSendsThePageWhatItReadsAndNothingElse(t *testing.T) {
	fake := newFakeServer(t)

	credential, err, asked := runLogin(t, fake, nil)
	if err != nil {
		t.Fatalf("login: %v", err)
	}
	if credential.AccessToken != "access-one" {
		t.Fatalf("login returned %+v", credential)
	}
	// The scopes come from the exchange rather than from what was asked for, which is the whole
	// reason the answer carries them: the server intersects the two.
	if strings.Join(credential.Scopes, " ") != "skills:read skills:write" {
		t.Fatalf("scopes = %v", credential.Scopes)
	}
	if credential.ExpiresAt.IsZero() {
		t.Fatal("the credential came back with no expiry")
	}
	// The name is the one of 验收 #1's three facts this process cannot work out for itself: the
	// person typed it on a page this process never saw, so the exchange is the only place it can
	// arrive — and it is what the terminal prints first.
	if credential.Name != "我的 MacBook" {
		t.Fatalf("name = %q, want what the person called this machine", credential.Name)
	}

	if got := asked.Get("client_id"); got != "skillmaster-cli" {
		t.Errorf("client_id = %q", got)
	}
	if got := asked.Get("code_challenge"); got == "" {
		t.Error("no code_challenge was sent; the page stores it into the code and the exchange needs it")
	}
	if got := asked.Get("state"); got == "" {
		t.Error("no state was sent")
	}
	// Both scopes, in one request: a second consent screen for the other half would be a second
	// interruption for one decision.
	if got := asked.Get("scope"); got != "skills:read skills:write" {
		t.Errorf("scope = %q", got)
	}
	// **The machine's name, which is the one parameter the page could not have been given otherwise.**
	// The browser does not hand out hostnames and the server only sees an IP, so this process is the
	// only party in the flow that knows what the machine is called — and the name is the whole of how
	// the row it creates gets recognized later.
	if got := asked.Get("name"); got != "lijinzhao的MacBook Pro" {
		t.Errorf("name = %q, want the name this machine is called by", got)
	}
	for _, absent := range []string{"redirect_uri", "response_type", "code_challenge_method"} {
		if got := asked.Get(absent); got != "" {
			t.Errorf("%s = %q was sent, and this path does not take one", absent, got)
		}
	}

	// The exchange, where the proof key has to be right: the server compares the verifier against the
	// challenge the code was minted with, and a wrong one is the same answer as a code that never
	// existed.
	if len(fake.exchangeRequests) != 1 {
		t.Fatalf("the exchange was called %d times", len(fake.exchangeRequests))
	}
	exchange := fake.exchangeRequests[0]
	if got := exchange.Get("code"); got != "the-code" {
		t.Errorf("code = %q", got)
	}
	if got := exchange.Get("code_verifier"); got == "" {
		t.Error("no code_verifier was sent; the challenge proves nothing without it")
	}
}

// The address's base is the discovery document's issuer, because that is the field the server already
// sets to where it serves its own pages.
//
// A trailing slash is the case worth a test of its own: an issuer is a base URL and a deployment may
// spell it either way, and `//cli-sign-in` is a different address on the wire — a 404 that reads as a
// server problem the server does not have.
func TestTheSignInAddressIsTheIssuerAndThePath(t *testing.T) {
	for _, spelling := range []string{"", "/"} {
		t.Run("issuer spelled "+strconv.Quote("…"+spelling), func(t *testing.T) {
			fake := newFakeServer(t)
			fake.issuer = fake.URL + spelling

			_, err, _ := runLogin(t, fake, nil)
			if err != nil {
				t.Fatalf("login: %v", err)
			}
			if !strings.HasPrefix(fake.signInURL, fake.URL+SignInPath+"?") {
				t.Fatalf("the sign-in address is %q, want it under %s%s",
					fake.signInURL, fake.URL, SignInPath)
			}
		})
	}
}

func TestLoginRefusesACallbackWhoseStateIsNotOurs(t *testing.T) {
	fake := newFakeServer(t)

	// Somebody else's sign-in: the code is theirs, the state is theirs. Accepting it would store a
	// credential for an account the person running the command does not own.
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
	if len(fake.exchangeRequests) != 0 {
		t.Errorf("the code was redeemed anyway: %v", fake.exchangeRequests)
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
		t.Fatal("a refused login was reported as a success")
	}
	if !strings.Contains(err.Error(), "access_denied") {
		t.Errorf("the error does not name the refusal: %v", err)
	}
	if len(fake.exchangeRequests) != 0 {
		t.Errorf("a refused login still redeemed something: %v", fake.exchangeRequests)
	}
}

// The three ways the exchange itself can fail, and each one says something different to a person.
//
// **The 404 is one answer for four causes** — no such code, an expired one, a spent one, and a
// verifier that did not match — because telling them apart would tell somebody holding a random code
// whether it ever existed. So the CLI's sentence names all four rather than guessing, and that is
// what this pins. The 429 is the rate limit this one public write carries, and it must not read as a
// credential problem: nothing about the person's machine is wrong.
func TestWhatTheExchangeCanAnswer(t *testing.T) {
	cases := []struct {
		name     string
		answer   func(w http.ResponseWriter)
		wanted   []string
		unwanted []string
	}{
		{
			name: "a code the server will not redeem",
			answer: func(w http.ResponseWriter) {
				w.WriteHeader(http.StatusNotFound)
				writeJSON(t, w, map[string]any{"error": map[string]string{"code": "code_not_found"}})
			},
			wanted: []string{"登录码不能用", "过期", "skillmaster login"},
		},
		{
			name: "the address is rate limited",
			answer: func(w http.ResponseWriter) {
				w.Header().Set("Retry-After", "30")
				w.WriteHeader(http.StatusTooManyRequests)
			},
			wanted: []string{"限流", "30 秒"},
			// **Not the code's failure.** A person told their code was bad would go and click again,
			// which is exactly what the limit is refusing to do yet.
			unwanted: []string{"登录码不能用"},
		},
		{
			name: "a credential with no name for the machine",
			answer: func(w http.ResponseWriter) {
				writeJSON(t, w, map[string]any{
					"access_token": "access-one", "expires_at": "2026-10-23T10:00:00Z",
					"scope": "skills:read",
				})
			},
			// **Refused rather than defaulted, and it is the one field with a third option**: a name
			// this CLI invented would be printed as the machine's own (验收 #1), and an invented fact
			// on the console's row is worse than a failed login.
			wanted: []string{"without a name"},
		},
		{
			name: "a credential with no end",
			answer: func(w http.ResponseWriter) {
				writeJSON(t, w, map[string]any{"access_token": "access-one", "name": "我的 MacBook"})
			},
			wanted: []string{"did not say when"},
		},
		{
			name: "an end this CLI cannot read",
			answer: func(w http.ResponseWriter) {
				writeJSON(t, w, map[string]any{
					"access_token": "access-one", "name": "我的 MacBook", "expires_at": "someday",
				})
			},
			wanted: []string{"cannot read"},
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			fake := newFakeServer(t)
			fake.exchangeResponse = c.answer

			_, err, _ := runLogin(t, fake, nil)
			if err == nil {
				t.Fatal("an unusable answer was accepted")
			}
			for _, wanted := range c.wanted {
				if !strings.Contains(err.Error(), wanted) {
					t.Errorf("message = %q, want it to mention %q", err, wanted)
				}
			}
			for _, unwanted := range c.unwanted {
				if strings.Contains(err.Error(), unwanted) {
					t.Errorf("message = %q, must not say %q", err, unwanted)
				}
			}
		})
	}
}

func TestListenBindsOnlyTheLoopbackAddress(t *testing.T) {
	port := freePort(t)
	callback, err := Listen(port, "a-state")
	if err != nil {
		t.Fatalf("listening: %v", err)
	}
	defer callback.Close()

	// `0.0.0.0` would let anybody on the same network reach a listener that is about to receive a
	// one-time code (RFC 8252 §7.3). Asserted on the listener itself, since the difference is
	// invisible from the outside on a machine with no other interface.
	address := callback.listener.Addr().(*net.TCPAddr)
	if !address.IP.IsLoopback() {
		t.Fatalf("the listener is bound to %s, which is not loopback", address.IP)
	}
}

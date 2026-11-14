package auth

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/pkce"
)

// fakeServer plays the three endpoints `login` uses, and records what it was asked.
//
// Recording rather than only answering: half of what these tests are about is what the CLI *sent* —
// the challenge, the name, the scopes, the device secret — and a fake that only returned a
// credential would pass with any of them wrong.
type fakeServer struct {
	*httptest.Server
	// signInURL is the address the person is told to open, whole.
	signInURL string
	// issuer is what the discovery document answers with, or empty for this server's own URL.
	issuer string
	// starts is the body of each creation request, decoded.
	starts []url.Values
	// polls is the body of each poll, decoded.
	polls []url.Values
	// cancels is the body of each cancellation, decoded.
	cancels []url.Values
	// startResponse lets a test make the creation fail, or answer something unusable.
	startResponse func(w http.ResponseWriter, call int)
	// pollResponse lets a test script the sequence: pending first, approved later, refused, slow…
	pollResponse func(w http.ResponseWriter, r *http.Request, call int)

	revocations []url.Values
	// revocationAuth is the `Authorization` header of each revocation, in the same order.
	revocationAuth []string
	// revokeStatus lets a test make the revocation fail; 0 means 200.
	revokeStatus int
}

func newFakeServer(t *testing.T) *fakeServer {
	t.Helper()
	fake := &fakeServer{}

	mux := http.NewServeMux()
	mux.HandleFunc(DiscoveryPath, func(w http.ResponseWriter, r *http.Request) {
		// `issuer` is a field so a test can hand back the spelling a deployment might use — with a
		// trailing slash — rather than only the one this fake would write.
		issuer := fake.issuer
		if issuer == "" {
			issuer = fake.URL
		}
		writeJSON(t, w, map[string]string{
			"issuer":              issuer,
			"revocation_endpoint": fake.URL + "/oauth/revoke",
		})
	})

	mux.HandleFunc(DeviceSignInsPath, func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<16))
		var sent map[string]string
		if err := json.Unmarshal(body, &sent); err != nil {
			t.Errorf("the creation body is not JSON: %s", body)
		}
		fake.starts = append(fake.starts, url.Values{
			"client_id":      {sent["client_id"]},
			"name":           {sent["name"]},
			"scope":          {sent["scope"]},
			"code_challenge": {sent["code_challenge"]},
		})
		if fake.startResponse != nil {
			fake.startResponse(w, len(fake.starts))
			return
		}
		w.WriteHeader(http.StatusCreated)
		writeJSON(t, w, map[string]any{
			"ticket":        "ticket-one",
			"device_secret": "secret-one",
			"expires_in":    600,
			"interval":      1,
		})
	})

	mux.HandleFunc(DeviceSignInsPath+"/ticket-one/token", func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<16))
		var sent map[string]string
		if err := json.Unmarshal(body, &sent); err != nil {
			t.Errorf("the poll body is not JSON: %s", body)
		}
		fake.polls = append(fake.polls, url.Values{
			"device_secret": {sent["device_secret"]},
			"code_verifier": {sent["code_verifier"]},
		})
		if fake.pollResponse != nil {
			fake.pollResponse(w, r, len(fake.polls))
			return
		}
		approved(t, w)
	})

	mux.HandleFunc(DeviceSignInsPath+"/ticket-one/cancel", func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<16))
		var sent map[string]string
		if err := json.Unmarshal(body, &sent); err != nil {
			t.Errorf("the cancel body is not JSON: %s", body)
		}
		fake.cancels = append(fake.cancels, url.Values{"device_secret": {sent["device_secret"]}})
		w.WriteHeader(http.StatusNoContent)
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

// approved is the credential answer, written out in one place so tests only script the differences.
func approved(t *testing.T, w http.ResponseWriter) {
	writeJSON(t, w, map[string]any{
		"status":       "approved",
		"access_token": "access-one",
		"expires_at":   "2026-10-23T10:00:00Z",
		"scope":        "skills:read skills:write",
		"name":         "我的 MacBook",
		"username":     "alice",
	})
}

func writeJSON(t *testing.T, w http.ResponseWriter, body any) {
	t.Helper()
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(body); err != nil {
		t.Errorf("writing a response: %v", err)
	}
}

// runLogin drives a whole login at the fake.
//
// It returns the result, and the fake keeps what was sent — the assertions about *what* the CLI asked
// for read it from there.
func runLogin(t *testing.T, fake *fakeServer, adjust ...func(*LoginOptions)) (Credential, error) {
	t.Helper()

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	options := LoginOptions{
		Server:   fake.URL,
		ClientID: "skillmaster-cli",
		// **Named rather than looked up**, because the lookup's answer is a property of the computer
		// running the tests — and the value here is deliberately not the one the fake server returns
		// as the credential's name, so that the request and the answer assert different things.
		MachineName: "lijinzhao的MacBook Pro",
		// The server's own interval is one second; a test does not need to spend it waiting.
		PollInterval: 5 * time.Millisecond,
		OnURL: func(raw string) {
			fake.signInURL = raw
		},
		OpenURL: func(string) error { return nil },
	}
	for _, change := range adjust {
		change(&options)
	}

	return Login(ctx, options)
}

// The creation request carries what the page needs to draw the question and what the poll needs to
// prove it is the same process.
//
// **The link carries one thing only — the ticket.** The device secret comes back in the answer and
// never appears in a URL; the name, the scopes and the challenge travel in the body. A link that
// carried the secret would put half of the proof into browser history and chat logs.
func TestLoginAsksWithTheNameScopesAndChallenge(t *testing.T) {
	fake := newFakeServer(t)

	credential, err := runLogin(t, fake)
	if err != nil {
		t.Fatalf("login: %v", err)
	}
	if credential.AccessToken != "access-one" {
		t.Fatalf("login returned %+v", credential)
	}
	// The scopes come from the answer rather than from what was asked for, which is the whole reason
	// the credential carries them: the server intersects the two.
	if strings.Join(credential.Scopes, " ") != "skills:read skills:write" {
		t.Fatalf("scopes = %v", credential.Scopes)
	}
	if credential.ExpiresAt.IsZero() {
		t.Fatal("the credential came back with no expiry")
	}
	// The name is the one of 验收 #1's three facts this process cannot work out for itself: the person
	// typed it on a page this process never saw, so the answer is the only place it can arrive.
	if credential.Name != "我的 MacBook" {
		t.Fatalf("name = %q, want what the person called this machine", credential.Name)
	}
	if credential.Username != "alice" {
		t.Fatalf("username = %q, want the account the server answered", credential.Username)
	}

	if len(fake.starts) != 1 {
		t.Fatalf("the creation endpoint was called %d times", len(fake.starts))
	}
	start := fake.starts[0]
	if got := start.Get("client_id"); got != "skillmaster-cli" {
		t.Errorf("client_id = %q", got)
	}
	// Both scopes, in one request: a second consent screen for the other half would be a second
	// interruption for one decision.
	if got := start.Get("scope"); got != "skills:read skills:write" {
		t.Errorf("scope = %q", got)
	}
	if got := start.Get("name"); got != "lijinzhao的MacBook Pro" {
		t.Errorf("name = %q, want the name this machine is called by", got)
	}
	if got := start.Get("code_challenge"); got == "" {
		t.Error("no code_challenge was sent; the poll needs it to recognise the verifier")
	}

	// The link, which is the page and its ticket and nothing else.
	parsed, err := url.Parse(fake.signInURL)
	if err != nil {
		t.Fatalf("the sign-in URL does not parse: %v", err)
	}
	if parsed.Path != SignInPath {
		t.Errorf("path = %q, want %q", parsed.Path, SignInPath)
	}
	if got := parsed.Query().Get("ticket"); got != "ticket-one" {
		t.Errorf("ticket = %q, want the one the server answered", got)
	}
	if got := parsed.Query().Get("device_secret"); got != "" {
		t.Errorf("the device secret is in the link (%q) — it must never leave the process", got)
	}
	if len(parsed.Query()) != 1 {
		t.Errorf("the link carries %v, want only the ticket", parsed.Query())
	}
}

// The address's base is the discovery document's issuer, because that is the field the server already
// sets to where it serves its own pages.
//
// A trailing slash is the case worth its own test: an issuer is a base URL and a deployment may spell
// it either way, and `//cli-sign-in` is a different address on the wire — a 404 that reads as a server
// problem the server does not have.
func TestTheSignInAddressIsTheIssuerAndThePath(t *testing.T) {
	for _, spelling := range []string{"", "/"} {
		t.Run("issuer spelled "+strconv.Quote("…"+spelling), func(t *testing.T) {
			fake := newFakeServer(t)
			fake.issuer = fake.URL + spelling

			if _, err := runLogin(t, fake); err != nil {
				t.Fatalf("login: %v", err)
			}
			if !strings.HasPrefix(fake.signInURL, fake.URL+SignInPath+"?") {
				t.Fatalf("the sign-in address is %q, want it under %s%s",
					fake.signInURL, fake.URL, SignInPath)
			}
		})
	}
}

// **Pending is not an answer, and the loop keeps asking.** The first poll is answered 202 and the
// second with the credential; both carry the same secret, and the verifier on each hashes to the
// challenge the question was created with — which is what makes this process the one that asked.
func TestLoginKeepsAskingUntilTheAnswerArrives(t *testing.T) {
	fake := newFakeServer(t)
	fake.pollResponse = func(w http.ResponseWriter, r *http.Request, call int) {
		if call < 3 {
			w.WriteHeader(http.StatusAccepted)
			writeJSON(t, w, map[string]string{"status": "pending"})
			return
		}
		approved(t, w)
	}

	credential, err := runLogin(t, fake)
	if err != nil {
		t.Fatalf("login: %v", err)
	}
	if credential.AccessToken != "access-one" {
		t.Fatalf("token = %q", credential.AccessToken)
	}
	if len(fake.polls) != 3 {
		t.Fatalf("the poll was called %d times, want 3", len(fake.polls))
	}

	challenge := fake.starts[0].Get("code_challenge")
	for _, poll := range fake.polls {
		if got := poll.Get("device_secret"); got != "secret-one" {
			t.Errorf("device_secret = %q, want the one the creation answered", got)
		}
		verifier := poll.Get("code_verifier")
		if verifier == "" {
			t.Fatal("no code_verifier was sent; the challenge proves nothing without it")
		}
		if pkce.Challenge(verifier) != challenge {
			t.Errorf("the verifier does not hash to the challenge the question was created with")
		}
	}
}

// A 429 is the server saying "not this often", not a failure: the loop waits as told and asks again.
// The credential still arrives, and the command does not tell the person anything about it.
func TestASlowDownIsWaitedOutRatherThanFatal(t *testing.T) {
	fake := newFakeServer(t)
	fake.pollResponse = func(w http.ResponseWriter, r *http.Request, call int) {
		if call == 1 {
			w.Header().Set("Retry-After", "0")
			w.WriteHeader(http.StatusTooManyRequests)
			writeJSON(t, w, map[string]string{"status": "slow_down"})
			return
		}
		approved(t, w)
	}

	if _, err := runLogin(t, fake); err != nil {
		t.Fatalf("a slow-down failed the login: %v", err)
	}
	if len(fake.polls) != 2 {
		t.Fatalf("the poll was called %d times, want 2", len(fake.polls))
	}
}

// The three ends a question can have, and one sentence for all of them: from this side they are the
// same fact — the link is not a question any more — and the way forward is a new one.
func TestAQuestionThatIsOverSaysToStartAgain(t *testing.T) {
	for _, status := range []string{"denied", "expired", "used"} {
		t.Run(status, func(t *testing.T) {
			fake := newFakeServer(t)
			fake.pollResponse = func(w http.ResponseWriter, r *http.Request, call int) {
				w.WriteHeader(http.StatusGone)
				writeJSON(t, w, map[string]string{"status": status})
			}

			_, err := runLogin(t, fake)
			if err == nil {
				t.Fatal("an ended question was reported as a success")
			}
			for _, wanted := range []string{"没有完成", "skmr login"} {
				if !strings.Contains(err.Error(), wanted) {
					t.Errorf("message = %q, want it to mention %q", err, wanted)
				}
			}
			// The credential is never collected from a dead ticket, so the poll ran exactly once.
			if len(fake.polls) != 1 {
				t.Errorf("the poll was called %d times after the question ended", len(fake.polls))
			}
		})
	}
}

// **404 is one answer for every way the poll can fail to match** — no such ticket, a secret that is
// not the one it was issued with, a verifier that does not hash to the challenge. The sentence names
// the way forward, not the cause.
func TestAPollThatDoesNotMatchSaysToStartAgain(t *testing.T) {
	fake := newFakeServer(t)
	fake.pollResponse = func(w http.ResponseWriter, r *http.Request, call int) {
		w.WriteHeader(http.StatusNotFound)
		writeJSON(t, w, map[string]string{"error": "session_not_found"})
	}

	_, err := runLogin(t, fake)
	if err == nil {
		t.Fatal("a poll the server refused was reported as a success")
	}
	for _, wanted := range []string{"失效", "skmr login"} {
		if !strings.Contains(err.Error(), wanted) {
			t.Errorf("message = %q, want it to mention %q", err, wanted)
		}
	}
}

// A question that is never answered ends at the lifetime the server gave, and says so rather than
// hanging. The lifetime is one second here; a real answer is ten minutes.
func TestAQuestionNobodyAnswersEndsAtItsLifetime(t *testing.T) {
	fake := newFakeServer(t)
	fake.startResponse = func(w http.ResponseWriter, call int) {
		w.WriteHeader(http.StatusCreated)
		writeJSON(t, w, map[string]any{
			"ticket": "ticket-one", "device_secret": "secret-one",
			"expires_in": 1, "interval": 1,
		})
	}
	fake.pollResponse = func(w http.ResponseWriter, r *http.Request, call int) {
		w.WriteHeader(http.StatusAccepted)
		writeJSON(t, w, map[string]string{"status": "pending"})
	}

	started := time.Now()
	_, err := runLogin(t, fake)
	if err == nil {
		t.Fatal("an unanswered question was reported as a success")
	}
	if !strings.Contains(err.Error(), "没有被批准") {
		t.Errorf("message = %q, want it to say the link was not approved", err)
	}
	if elapsed := time.Since(started); elapsed > 5*time.Second {
		t.Errorf("the wait took %s; it should end at the lifetime the server gave", elapsed)
	}
}

// Ctrl-C is a supported way out, and it is answered as itself: not a failed poll, not a server
// problem. Nothing is sent to the server — the ticket expires on its own (v68 Q7).
func TestCtrlCStopsTheWait(t *testing.T) {
	fake := newFakeServer(t)
	fake.pollResponse = func(w http.ResponseWriter, r *http.Request, call int) {
		w.WriteHeader(http.StatusAccepted)
		writeJSON(t, w, map[string]string{"status": "pending"})
	}

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		// Long enough for the creation and the first poll, short enough not to slow the suite.
		time.Sleep(30 * time.Millisecond)
		cancel()
	}()

	_, err := Login(ctx, LoginOptions{
		Server: fake.URL, ClientID: "skillmaster-cli", MachineName: "测试机",
		PollInterval: 50 * time.Millisecond, OnURL: func(string) {},
	})
	if err == nil {
		t.Fatal("a cancelled login was reported as a success")
	}
	if !strings.Contains(err.Error(), "context canceled") {
		t.Errorf("message = %q, want the cancellation itself", err)
	}
	// **The question is ended, not only the wait.** Otherwise whoever opens the link after the person
	// gave up could still approve it. The same secret the poll carries is what proves this is the
	// process that asked.
	if len(fake.cancels) != 1 {
		t.Fatalf("the question was cancelled %d times, want 1", len(fake.cancels))
	}
	if got := fake.cancels[0].Get("device_secret"); got != "secret-one" {
		t.Errorf("cancel device_secret = %q, want the one the creation answered", got)
	}
}

// **Ctrl-C can land while a poll is in flight**, and that error arrives from `client.Do` rather than
// from `sleep`. The question still has to be ended — the same acceptance applies whichever line the
// cancellation surfaces on.
func TestCtrlCDuringAPollStillCancelsTheQuestion(t *testing.T) {
	fake := newFakeServer(t)
	fake.pollResponse = func(w http.ResponseWriter, r *http.Request, call int) {
		// Long enough that the cancellation below lands while this request is open.
		time.Sleep(300 * time.Millisecond)
		approved(t, w)
	}

	ctx, cancel := context.WithCancel(context.Background())
	_, err := Login(ctx, LoginOptions{
		Server: fake.URL, ClientID: "skillmaster-cli", MachineName: "测试机",
		PollInterval: time.Second,
		OnURL: func(string) {
			go func() {
				time.Sleep(80 * time.Millisecond)
				cancel()
			}()
		},
	})
	if err == nil {
		t.Fatal("a cancelled login was reported as a success")
	}
	if len(fake.cancels) != 1 {
		t.Fatalf("the question was cancelled %d times, want 1", len(fake.cancels))
	}
}

// **A server that says "wait 60 seconds" must not make the CLI give up on a link that still lives
// for another 30.** The deadline is when asking stops; the last poll always happens.
func TestASlowDownLongerThanTheQuestionStillGetsItsLastPoll(t *testing.T) {
	fake := newFakeServer(t)
	fake.startResponse = func(w http.ResponseWriter, call int) {
		w.WriteHeader(http.StatusCreated)
		writeJSON(t, w, map[string]any{
			"ticket": "ticket-one", "device_secret": "secret-one",
			"expires_in": 2, "interval": 1,
		})
	}
	fake.pollResponse = func(w http.ResponseWriter, r *http.Request, call int) {
		w.Header().Set("Retry-After", "60")
		w.WriteHeader(http.StatusTooManyRequests)
		writeJSON(t, w, map[string]string{"status": "slow_down"})
	}

	started := time.Now()
	_, err := runLogin(t, fake)
	if err == nil {
		t.Fatal("an unanswered question was reported as a success")
	}
	if !strings.Contains(err.Error(), "没有被批准") {
		t.Errorf("message = %q, want the deadline sentence", err)
	}
	// It waited the question out rather than aborting on the first 429, and asked more than once.
	if elapsed := time.Since(started); elapsed < 1500*time.Millisecond {
		t.Errorf("gave up after %s, before the question's own clock", elapsed)
	}
	if len(fake.polls) < 2 {
		t.Errorf("polled %d times, want at least 2", len(fake.polls))
	}
}

// The creation can fail on its own, and each failure says something a person can act on.
func TestWhatTheCreationCanAnswer(t *testing.T) {
	cases := []struct {
		name   string
		answer func(w http.ResponseWriter)
		wanted []string
	}{
		{
			name: "the address is rate limited",
			answer: func(w http.ResponseWriter) {
				w.Header().Set("Retry-After", "30")
				w.WriteHeader(http.StatusTooManyRequests)
			},
			wanted: []string{"限流", "30 秒"},
		},
		{
			name: "the server is broken",
			answer: func(w http.ResponseWriter) {
				w.WriteHeader(http.StatusInternalServerError)
				writeJSON(t, w, map[string]string{"error": "server_error"})
			},
			wanted: []string{"HTTP 500"},
		},
		{
			name: "a ticket with no secret",
			answer: func(w http.ResponseWriter) {
				w.WriteHeader(http.StatusCreated)
				writeJSON(t, w, map[string]any{"ticket": "ticket-one", "expires_in": 600})
			},
			// **Refused rather than defaulted**: a ticket with no secret is a question this process
			// could never collect, and the failure would arrive ten minutes later as a timeout.
			wanted: []string{"device_secret"},
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			fake := newFakeServer(t)
			fake.startResponse = func(w http.ResponseWriter, call int) { c.answer(w) }

			_, err := runLogin(t, fake)
			if err == nil {
				t.Fatal("a creation that failed was reported as a success")
			}
			for _, wanted := range c.wanted {
				if !strings.Contains(err.Error(), wanted) {
					t.Errorf("message = %q, want it to mention %q", err, wanted)
				}
			}
		})
	}
}

// An answer that says "approved" but is not a credential is refused rather than filled in: a guessed
// date is a store holding a dead credential with a number beside it that is not true.
func TestWhatACredentialAnswerMustCarry(t *testing.T) {
	cases := []struct {
		name     string
		answer   map[string]any
		wanted   []string
		unwanted []string
	}{
		{
			name:   "no token",
			answer: map[string]any{"status": "approved", "expires_at": "2026-10-23T10:00:00Z", "name": "我的 MacBook"},
			wanted: []string{"却没有给凭据"},
		},
		{
			name:   "no name for the machine",
			answer: map[string]any{"status": "approved", "access_token": "access-one", "expires_at": "2026-10-23T10:00:00Z"},
			wanted: []string{"机器"},
		},
		{
			name:   "no end",
			answer: map[string]any{"status": "approved", "access_token": "access-one", "name": "我的 MacBook"},
			wanted: []string{"什么时候到期"},
		},
		{
			name: "an end this CLI cannot read",
			answer: map[string]any{"status": "approved", "access_token": "access-one",
				"name": "我的 MacBook", "expires_at": "someday"},
			wanted: []string{"读不懂"},
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			fake := newFakeServer(t)
			fake.pollResponse = func(w http.ResponseWriter, r *http.Request, call int) {
				writeJSON(t, w, c.answer)
			}

			_, err := runLogin(t, fake)
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

// **A server older than the field is still a server.** It does not send `username`, and that must
// cost the person a sentence, not the login (v46 验收 A6).
func TestAnAnswerWithoutAnAccountStillLogsIn(t *testing.T) {
	fake := newFakeServer(t)
	fake.pollResponse = func(w http.ResponseWriter, r *http.Request, call int) {
		writeJSON(t, w, map[string]any{
			"status": "approved", "access_token": "access-one",
			"expires_at": "2026-10-23T10:00:00Z", "scope": "skills:read skills:write",
			"name": "我的 MacBook",
		})
	}

	credential, err := runLogin(t, fake)
	if err != nil {
		t.Fatalf("an older server's answer was refused: %v", err)
	}
	if credential.Username != "" {
		t.Fatalf("username = %q, want empty when the server did not send one", credential.Username)
	}
}

// A poll can also be the first thing that fails, and the message names the command rather than a
// status code: the person's next step is the same whichever way the connection broke.
func TestAPollThatCannotReachTheServerIsNamedWithTheCommand(t *testing.T) {
	fake := newFakeServer(t)
	// The creation is answered, then the connection carrying the poll is dropped. Hijacked rather
	// than `fake.Close()`: closing the server from inside its own handler waits for that handler,
	// which is the deadlock this test would otherwise be.
	fake.pollResponse = func(w http.ResponseWriter, r *http.Request, call int) {
		if hijacker, ok := w.(http.Hijacker); ok {
			connection, _, err := hijacker.Hijack()
			if err == nil {
				connection.Close()
			}
		}
	}

	_, err := runLogin(t, fake)
	if err == nil {
		t.Fatal("a broken connection was reported as a success")
	}
	if !strings.Contains(err.Error(), "calling") {
		t.Errorf("message = %q, want the failed call named", err)
	}
}

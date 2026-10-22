package main

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/auth"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// The CLI's half of v2: a key pasted in, a key refused, and a logout that only touches this machine.
//
// These are about the four places the design is easy to get wrong and expensive to get wrong — a
// credential stored before it was checked, a key renewed as if it were a token, a variable that wins
// silently, and a `logout` that claims more than it did. Every one of them has the same failure shape:
// the command says something happened and something else did.

// stubStore is this machine's store: it records what was written rather than writing anywhere.
type stubStore struct {
	creds   credentials.Credentials
	found   bool
	saves   []credentials.Credentials
	deletes int
}

func (s *stubStore) Load() (credentials.Credentials, bool, error) { return s.creds, s.found, nil }

func (s *stubStore) Save(c credentials.Credentials) error {
	s.saves = append(s.saves, c)
	s.creds, s.found = c, true
	return nil
}

func (s *stubStore) Delete() error {
	s.deletes++
	s.creds, s.found = credentials.Credentials{}, false
	return nil
}

func (s *stubStore) Where() string { return "测试钥匙串" }

// aKey is a string with the shape the console issues: the prefix and 43 base64url characters.
func aKey(secret string) string {
	return "skm_" + secret + strings.Repeat("a", 43-len(secret))
}

// serveMe answers `/me` with a key of the given name and scope, and counts what was asked of it.
func serveMe(t *testing.T, status int, body string) (*httptest.Server, *[]string) {
	t.Helper()
	asked := &[]string{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		*asked = append(*asked, r.Method+" "+r.URL.Path)
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(server.Close)
	return server, asked
}

func keyBody(name, scope, expires string) string {
	expiry := "null"
	if expires != "" {
		expiry = `"` + expires + `"`
	}
	return `{"user":{"user_id":"u1","username":"owner","namespace":"owner"},
		"key":{"name":"` + name + `","scope":"` + scope + `","expires_at":` + expiry +
		`,"last_used_at":null}}`
}

// ---------------------------------------------------------------------------
// a refused key is never renewed
// ---------------------------------------------------------------------------

// A 401 on a key is reported, not retried — and the refusal is turned into what to do about it.
//
// Renewing is not a retry that might work: a key has no refresh chain, so the only thing a second
// attempt could do is present the same refused string again. The count of requests is what proves
// nothing tried: the token endpoint is on the same server, so an attempt would show up here.
func TestAKeyThatIsRefusedIsNotRetried(t *testing.T) {
	cases := []struct {
		name   string
		code   string
		wanted string
	}{
		{"an expired key", "token_expired", "已经过期"},
		{"a revoked key", "token_revoked", "已被撤销"},
		{"a suspended account", "account_suspended", "账号已被停用"},
		// The server's answer when it cannot place the token at all: mistyped, or revoked by a path
		// this build does not name. It is a named refusal, so it gets its own sentence.
		{"a token it cannot place", "unauthenticated", "服务端不认"},
		// A reason this version does not know keeps the sentence it always had, plus where to look:
		// reporting it as a plain failure would leave somebody with no next step at all.
		{"a reason this version does not know", "token_mystery", "本机不能续期"},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			body := `{"error":{"code":"` + c.code + `","message":"refused"}}`
			server, asked := serveMe(t, http.StatusUnauthorized, body)
			t.Setenv(config.ServerEnv, server.URL)
			t.Setenv(config.WebURLEnv, "https://web.example")
			t.Setenv(config.ApiKeyEnv, aKey("from-env"))
			t.Setenv("HOME", t.TempDir())

			_, err := authenticated(context.Background(), func(client api.Client) (api.Me, error) {
				return client.Me(context.Background())
			})
			if err == nil {
				t.Fatal("a refused key reported success")
			}
			if !strings.Contains(err.Error(), c.wanted) {
				t.Fatalf("message = %q, want it to contain %q", err, c.wanted)
			}
			if c.wanted != "账号已被停用" && !strings.Contains(err.Error(), "https://web.example/account/keys") {
				t.Fatalf("message = %q, want the console address", err)
			}
			if len(*asked) != 1 {
				t.Fatalf("the credential was presented %d times (%v), want once", len(*asked), *asked)
			}
			// **And which credential it was**, because two can be in play at once: the environment's
			// wins over the local store here (this case exports one), and 「这把 API key」 alone leaves
			// somebody with both renewing the one they meant and watching the command fail the same
			// way. The source is the half the CLI knows and the server cannot.
			if !strings.Contains(err.Error(), config.ApiKeyEnv) {
				t.Fatalf("message = %q, want it to name where the refused credential came from", err)
			}
		})
	}
}

// The negative case v4 验收 #7(a) asks for: on a machine with no browser `login` must fail, and the
// failure must point at the one road that works there.
//
// **"Only that one" is half the criterion.** The message used to name the paste command v2 had, and
// that command does not exist any more: a person sent to a misspelling of a command that is gone has
// been told something worse than nothing. So the two things it may name are asserted, and so is the
// absence of the removed shape.
//
// It also fails **before** it prints a link and starts listening, which is the part worth testing: the
// other order produces a command that waits for a callback that cannot come, and the person has no way
// to tell that from a slow login. Nothing is listening at the address below, so a path that reached
// the network at all would answer a connection error instead of this sentence.
func TestLoginWithoutABrowserPointsAtTheKeyPath(t *testing.T) {
	original := browserAvailable
	browserAvailable = func() bool { return false }
	t.Cleanup(func() { browserAvailable = original })

	t.Setenv(config.ServerEnv, "http://127.0.0.1:9")
	t.Setenv(config.WebURLEnv, "https://web.example")
	t.Setenv("HOME", t.TempDir())

	var err error
	_ = captureStdout(t, func() { err = login(context.Background(), nil) })
	if err == nil {
		t.Fatal("a browser login was attempted on a machine with no browser")
	}
	for _, want := range []string{config.ApiKeyEnv, "没有可以打开链接的浏览器", "https://web.example/account/keys"} {
		if !strings.Contains(err.Error(), want) {
			t.Fatalf("message = %q, want it to contain %q", err, want)
		}
	}
	for _, unwanted := range []string{"--with-key", "pbpaste", "--client-credentials"} {
		if strings.Contains(err.Error(), unwanted) {
			t.Fatalf("message = %q, still sends the person to a login this version does not have", err)
		}
	}
}

// What `login` does with the credential it replaced, one shape at a time.
//
// **Three of the four have nothing for this CLI to call, and each says something different about why**
// (v4 Q7, Q8), which is the whole reason this is a table: a key is revoked in the console, this
// version's own credential was already ended by the server in the browser action that created the new
// one, and the unattended leftover cannot be ended from anywhere. Only the old browser login's has a
// refresh token to present — and posting a key to `/oauth/revoke` would be refused by the server with
// no client id, which this command would then report as an authorization that "stayed live".
func TestReplacingEachShapeOfCredential(t *testing.T) {
	login := credentials.Credentials{
		Name: "我的 MacBook", AccessToken: "a-login-token",
		ExpiresAt: time.Now().Add(time.Hour), Scope: "skills:read skills:write", SavedAt: time.Now(),
	}
	cases := []struct {
		name        string
		previous    credentials.Credentials
		wanted      []string
		unwanted    []string
		aRevocation bool
	}{
		{
			name:     "a key",
			previous: credentials.Credentials{AccessToken: aKey("stored")},
			wanted:   []string{"/account/keys"},
		},
		{
			name: "the old browser login's credential",
			previous: credentials.Credentials{
				ClientID: config.ClientID, AccessToken: "an-oauth-token", RefreshToken: "refresh-one",
			},
			wanted:      []string{"已撤销"},
			aRevocation: true,
		},
		{
			name:     "the unattended login's leftover",
			previous: credentials.Credentials{ClientID: "dev-ci", AccessToken: "an-oauth-token"},
			wanted:   []string{"无人值守登录", "无法从这台机器撤销", "过期"},
			// **The sentence this test exists to forbid**: nothing revoked it, and the version before
			// this one said so.
			unwanted: []string{"已撤销"},
		},
		{
			name:     "this version's own credential",
			previous: login,
			// The ending is the server's, in the browser action that created the new credential, and
			// what the CLI can honestly add is where to look. **It must not claim the call it did not
			// make** — this shape has nothing to present even if it wanted to.
			wanted:   []string{"本机存的登录凭据", "服务端", "/account/sessions"},
			unwanted: []string{"已撤销", "无法从这台机器撤销"},
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			server, revocations := serveRevocations(t)
			t.Setenv(config.WebURLEnv, "https://web.example")

			out := captureStdout(t, func() {
				revokeReplaced(context.Background(), server.URL, c.previous, true)
			})
			for _, wanted := range c.wanted {
				if !strings.Contains(out, wanted) {
					t.Errorf("output = %q, want it to mention %q", out, wanted)
				}
			}
			for _, unwanted := range c.unwanted {
				if strings.Contains(out, unwanted) {
					t.Errorf("output = %q must not say %q", out, unwanted)
				}
			}
			if !c.aRevocation && *revocations != 0 {
				t.Fatalf("the revocation endpoint was called %d times, want never", *revocations)
			}
			if c.aRevocation && *revocations == 0 {
				t.Fatal("an authorization with a refresh token to present was not revoked at all")
			}
		})
	}
}

// serveRevocations answers the discovery document and the revocation endpoint, and counts the
// revocations: "it called and it reported success" and "it never called" are two different facts, and
// the second one is the one three of the four shapes above are about.
func serveRevocations(t *testing.T) (*httptest.Server, *int) {
	t.Helper()
	calls := new(int)
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/oauth/revoke" {
			*calls++
			w.WriteHeader(http.StatusOK)
			return
		}
		// The issuer is required by `Discover` and unused by the revocation, so its value is this
		// server's own address — the only one that is certainly a URL.
		_, _ = w.Write([]byte(`{"issuer":"` + server.URL + `","revocation_endpoint":"` +
			server.URL + `/oauth/revoke"}`))
	}))
	t.Cleanup(server.Close)
	return server, calls
}

// ---------------------------------------------------------------------------
// logout, and what it does not do
// ---------------------------------------------------------------------------

// **`login`'s own dispatcher, which is where this command actually breaks.**
//
// The shapes had tests; the switch that chooses between them did not, and a catch-all for
// `-`-prefixed arguments placed above the real ones swallowed the flags this command took — while
// `go test ./...` stayed green, because every test called the shape's function directly. So these go
// through `login`: what is being pinned is that an argument reaches the right answer.
//
// **The answer for every argument now is a refusal**, which is what one shape means, and the case
// worth pinning most is the flag v2 added for pasting a key: v4 removed it, so its spelling has to be
// refused as an unknown option — named, so somebody can see the mistake, and without repeating what
// they handed over (v4 验收 #6, the same shape as v3's 验收 #10). **Its name survives in this test
// and nowhere a person can read it**: that is the acceptance criterion, not an oversight.
func TestLoginRefusesWhatItDoesNotTake(t *testing.T) {
	t.Setenv(config.ServerEnv, "http://127.0.0.1:9")
	t.Setenv(config.WebURLEnv, "https://web.example")
	t.Setenv("HOME", t.TempDir())

	// No browser either, so a mistake that fell through to the login would answer with the sentence
	// about API keys rather than with a refusal — a different failure, and one this catches.
	original := browserAvailable
	browserAvailable = func() bool { return false }
	t.Cleanup(func() { browserAvailable = original })

	// **Quoted, because the usage text is in the same message and a bare substring proves nothing.**
	// The usage names the command itself, so `Contains(err, "--with-key")` would be satisfied by the
	// paragraph rather than by the refusal.
	for _, args := range [][]string{{"--with-ke", aKey("secret")}, {"-x"}} {
		var err error
		_ = captureStdout(t, func() { err = login(context.Background(), args) })
		if err == nil {
			t.Fatalf("login%v was accepted", args)
		}
		if !strings.Contains(err.Error(), "用法：skillmaster login") || !strings.Contains(err.Error(), strconv.Quote(args[0])) {
			t.Fatalf("login%v said %q, want the usage text and the flag it did not know", args, err)
		}
		// The value came as a separate word, which is the spelling a person types by hand — and the
		// one no `=`-shaped canary below covers.
		if len(args) > 1 && strings.Contains(err.Error(), args[1]) {
			t.Fatalf("login%v repeated the value it was given: %q", args, err)
		}
	}

	// **The refusal does not quote the argument back, and that is the half this table is really
	// about.** `--with-key=skm_…` is the spelling somebody reaches for when they know the flag and
	// not the version, and `%q` on the whole argument would put the secret in the terminal (and in
	// whatever collects a CI job's output) — one more place the credential lives, in the message that
	// was supposed to protect it. Each row asserts the flag it names as well, so a message that
	// drifted to another branch fails here rather than passing on the leak check alone.
	for _, c := range []struct {
		args []string
		want string
	}{
		{[]string{"--with-key=" + aKey("canary")}, `未知的选项 "--with-key"`},
		{[]string{"--client-credentials=" + aKey("canary")}, `未知的选项 "--client-credentials"`},
		{[]string{"--api-key=" + aKey("canary")}, `未知的选项 "--api-key"`},
		{[]string{"--with-key"}, `未知的选项 "--with-key"`},
		// A flag with a value after it: the value is not read, so a key aimed at something else is
		// never treated as a credential — and the refusal still does not repeat it.
		{[]string{"--with-key", aKey("canary")}, `未知的选项 "--with-key"`},
	} {
		var leaked error
		_ = captureStdout(t, func() { leaked = login(context.Background(), c.args) })
		if leaked == nil {
			t.Fatalf("login%v was accepted", c.args)
		}
		if strings.Contains(leaked.Error(), aKey("canary")) {
			t.Fatalf("the refusal of %v repeated the secret: %q", c.args, leaked)
		}
		if !strings.Contains(leaked.Error(), c.want) {
			t.Fatalf("the refusal of %v said %q, want %q", c.args, leaked, c.want)
		}
	}

	// **A positional argument is the same mistake as an unknown flag**, and it used to be the
	// quietest one: `login skm_…` — the paste without its flag — fell through to the browser shape,
	// which opened a consent page and never looked at what it had been handed. The refusal names
	// where the credential goes instead, and still does not repeat it.
	for _, arg := range []string{aKey("canary"), ""} {
		var quiet error
		_ = captureStdout(t, func() { quiet = login(context.Background(), []string{arg}) })
		if quiet == nil {
			t.Fatalf("login with the positional argument %q was accepted", arg)
		}
		if strings.Contains(quiet.Error(), aKey("canary")) {
			t.Fatalf("the refusal repeated the argument: %q", quiet)
		}
		if !strings.Contains(quiet.Error(), "未知的选项") {
			t.Fatalf("login with %q said %q, want a refusal rather than a login", arg, quiet)
		}
	}

	// And asking for help is not a mistake: the usage goes to stderr (this CLI's convention, so that
	// a script's stdout stays parseable) and the command succeeds.
	for _, args := range [][]string{{"help"}, {"--help"}} {
		var err error
		usage := captureStderr(t, func() { err = login(context.Background(), args) })
		if err != nil {
			t.Fatalf("login%v: %v", args, err)
		}
		if !strings.Contains(usage, "用法：skillmaster login") {
			t.Fatalf("login%v does not print this command's own usage:\n%s", args, usage)
		}
	}
}

// `auth help` is help rather than an unknown subcommand, the way `skill help` and `login help` are.
func TestAuthHelpIsHelp(t *testing.T) {
	var err error
	usage := captureStderr(t, func() { err = authCmd(context.Background(), []string{"help"}) })
	if err != nil {
		t.Fatalf("auth help: %v", err)
	}
	if !strings.Contains(usage, "status") {
		t.Fatalf("auth help does not list its subcommand:\n%s", usage)
	}

	var unknown error
	_ = captureStdout(t, func() { unknown = authCmd(context.Background(), []string{"stat"}) })
	if unknown == nil || !strings.Contains(unknown.Error(), "status") {
		t.Fatalf("auth stat said %v, want the usage", unknown)
	}
}

// A logout that could not clear the store must not say it cleared it.
//
// **The two ways it lies have the same shape and only one of them was closed.** A store that cannot be
// *read* was fixed in the previous round (see `logoutWith`); a store that cannot be *deleted* went on
// reporting success, because the responsive keychain treats its own timeout as success — and in v2
// what it leaves behind is a live key, not the dead token v1 left (`logout` no longer revokes
// anything, ADR 0038 §决定 7). The person is told the machine is clean while every later command keeps
// authenticating as them.
func TestALogoutThatCouldNotClearTheStoreSaysSo(t *testing.T) {
	var err error
	out := captureStdout(t, func() { err = logoutWith(credentials.EnvStore{Fallback: undeletable{}}) })
	if err == nil {
		t.Fatal("a logout that removed nothing reported success")
	}
	if !strings.Contains(err.Error(), errStickyStore.Error()) {
		t.Fatalf("err = %v, want the store's own reason", err)
	}
	// And it does not congratulate itself on the way out: the success sentence is the thing being
	// withheld, so a version of this that printed it and returned an error too is still a lie.
	for _, claim := range []string{"已清除本地凭据", "没什么可清"} {
		if strings.Contains(out, claim) {
			t.Fatalf("logout said %q while the credential was still there:\n%s", claim, out)
		}
	}
}

// `logout` clears this machine and says what it did not reach — the server's copy, and a variable.
func TestLogoutTouchesOnlyThisMachine(t *testing.T) {
	server, asked := serveMe(t, http.StatusOK, "{}")
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.WebURLEnv, "https://web.example")

	store := &stubStore{found: true, creds: credentials.Credentials{AccessToken: aKey("stored")}}
	out := captureStdout(t, func() {
		if err := logoutWith(credentials.EnvStore{Fallback: store}); err != nil {
			t.Fatalf("logout: %v", err)
		}
	})
	if !strings.Contains(out, "已清除本地凭据") {
		t.Fatalf("logout does not say what it cleared:\n%s", out)
	}
	if !strings.Contains(out, "https://web.example/account/keys") {
		t.Fatalf("logout does not say where the key is revoked:\n%s", out)
	}
	if !strings.Contains(out, "浏览器里的登录状态不受影响") {
		t.Fatalf("logout does not say what it is not:\n%s", out)
	}
	if store.deletes != 1 {
		t.Fatalf("the local credential was deleted %d times, want 1", store.deletes)
	}
	if len(*asked) != 0 {
		t.Fatalf("logout asked the server %v, want nothing", *asked)
	}
}

// A store that cannot be read is not a machine with nothing on it.
//
// **This is a regression the audit caught in the fix itself.** Folding the read failure into "there
// was nothing here" printed「本机没有凭据，没什么可清的」and exited 0 on a machine whose keychain was
// merely locked — while the credential stayed there and every later command went on authenticating
// with it. The delete cannot be believed either: the responsive keychain returns success on its own
// timeout by design, so nothing downstream would report what this command concealed.
func TestALockedStoreIsNotAMachineWithNothingOnIt(t *testing.T) {
	t.Setenv(config.WebURLEnv, "https://web.example")

	var err error
	out := captureStdout(t, func() {
		err = logoutWith(credentials.EnvStore{Fallback: unreadable{}})
	})
	if err == nil {
		t.Fatal("a store that could not be read was reported as a clean machine")
	}
	if !strings.Contains(err.Error(), "读不到本机凭据") {
		t.Fatalf("message = %q, want it to say the credential could not be read", err)
	}
	for _, unwanted := range []string{"没有凭据", "已清除"} {
		if strings.Contains(out, unwanted) {
			t.Fatalf("output claims %q:\n%s", unwanted, out)
		}
	}
}

// The last sentence is about the credential that was just deleted, and the one in use when they
// differ — two credentials, two places to end them.
func TestLogoutSpeaksAboutBothCredentialsWhenTheyDiffer(t *testing.T) {
	t.Setenv(config.WebURLEnv, "https://web.example")
	store := credentials.EnvStore{
		Var:   config.ApiKeyEnv,
		Value: aKey("from-env"),
		Fallback: &stubStore{found: true, creds: credentials.Credentials{
			AccessToken: "an-oauth-token", RefreshToken: "refresh-one",
		}},
	}

	out := captureStdout(t, func() {
		if err := logoutWith(store); err != nil {
			t.Fatalf("logout: %v", err)
		}
	})
	// The deleted token's story — that the console has no entry for it — used to be lost whenever a
	// variable was set, because the sentence was built from the credential in use instead.
	if !strings.Contains(out, "控制台没有它的入口") {
		t.Fatalf("the deleted authorization went unmentioned:\n%s", out)
	}
	if !strings.Contains(out, "/account/keys") {
		t.Fatalf("the variable's key was not pointed at the console:\n%s", out)
	}
}

// The other two endings, both of which have something to *not* say.
//
// **A token has no console entry.** The authorization an old browser login left behind cannot be
// revoked from `/account/keys`, and printing that address for it would send somebody to look for a
// row that is not on the list (ADR 0040 §后果). And a machine that held nothing at all must not be
// told its credential was cleared — the sentence a person checks to decide whether the machine is
// clean.
func TestLogoutOnlySendsYouToTheConsoleForAKey(t *testing.T) {
	t.Setenv(config.WebURLEnv, "https://web.example")
	cases := []struct {
		name     string
		store    *stubStore
		wanted   []string
		unwanted []string
	}{
		{
			name:     "a credential from the old browser login",
			store:    &stubStore{found: true, creds: credentials.Credentials{AccessToken: "an-oauth-token"}},
			wanted:   []string{"已清除本地凭据", "控制台没有它的入口"},
			unwanted: []string{"/account/keys"},
		},
		{
			name:     "a machine that had nothing",
			store:    &stubStore{},
			wanted:   []string{"本机没有凭据，没什么可清的"},
			unwanted: []string{"已清除本地凭据", "/account/keys"},
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			out := captureStdout(t, func() {
				if err := logoutWith(credentials.EnvStore{Fallback: c.store}); err != nil {
					t.Fatalf("logout: %v", err)
				}
			})
			for _, want := range c.wanted {
				if !strings.Contains(out, want) {
					t.Fatalf("output does not contain %q:\n%s", want, out)
				}
			}
			for _, unwanted := range c.unwanted {
				if strings.Contains(out, unwanted) {
					t.Fatalf("output claims %q:\n%s", unwanted, out)
				}
			}
		})
	}
}

// With the variable set there is a credential this command cannot clear, and it says so rather than
// reporting a logout that leaves the next command working.
func TestLogoutSaysWhenTheVariableStillHoldsACredential(t *testing.T) {
	t.Setenv(config.ApiKeyEnv, aKey("from-env"))
	t.Setenv("HOME", t.TempDir())
	t.Setenv(config.WebURLEnv, "https://web.example")

	out := captureStdout(t, func() {
		if err := logoutWith(credentials.EnvStore{
			Var: config.ApiKeyEnv, Value: aKey("from-env"), Fallback: &stubStore{},
		}); err != nil {
			t.Fatalf("logout: %v", err)
		}
	})
	if !strings.Contains(out, config.ApiKeyEnv) || !strings.Contains(out, "仍然会用它") {
		t.Fatalf("logout does not mention the variable it cannot clear:\n%s", out)
	}
}

// ---------------------------------------------------------------------------
// auth status
// ---------------------------------------------------------------------------

// Both halves of "what is in use here", each read from the place that knows it.
func TestAuthStatusAnswersBothHalves(t *testing.T) {
	server, asked := serveMe(t, http.StatusOK, keyBody("笔记本", "skills:read", "2027-01-13T15:59:59Z"))
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.ApiKeyEnv, aKey("from-env"))
	t.Setenv("HOME", t.TempDir())

	var err error
	out := captureStdout(t, func() { err = authStatus(context.Background(), nil) })
	if err != nil {
		t.Fatalf("authStatus: %v", err)
	}
	for _, want := range []string{
		"本机：凭据来自 环境变量 " + config.ApiKeyEnv,
		config.ApiKeyEnv + " 优先于本机存储",
		"服务端：账号 owner",
		"笔记本", "skills:read", "2027-01-13T15:59:59Z 到期", "从未使用",
	} {
		if !strings.Contains(out, want) {
			t.Fatalf("auth status does not mention %q:\n%s", want, out)
		}
	}
	if len(*asked) != 1 {
		t.Fatalf("asked %v, want one request", *asked)
	}
}

// A credential that is not a key is said to be one rather than described as if it were.
//
// The stored credential is the way that happens in practice, and **v4 added a second shape to this
// answer**: what `login` writes now is a login, and the console has a screen that lists it and ends
// it — where the sentence this replaced said, of every non-key, that the console had no entry at all.
// That was true of the leftovers when it was written and is still true of them, which is why the two
// halves are asserted separately here and neither is allowed to say the other's line.
func TestAuthStatusDescribesANonKeyByWhatItIs(t *testing.T) {
	server, _ := serveMe(t, http.StatusOK,
		`{"user":{"user_id":"u1","username":"owner","namespace":"owner"},"key":null}`)
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.ApiKeyEnv, "")
	t.Setenv(config.WebURLEnv, "https://web.example")
	t.Setenv("HOME", t.TempDir())

	cases := []struct {
		name     string
		stored   credentials.Credentials
		wanted   []string
		unwanted []string
	}{
		{
			name: "this version's login credential",
			stored: credentials.Credentials{
				Name: "我的 MacBook", AccessToken: "a-login-token",
				ExpiresAt: time.Now().Add(time.Hour),
				Scope:     "skills:read skills:write", SavedAt: time.Now(),
			},
			// The name is here for the same reason the scopes are: the exchange is the only answer
			// that carries either, so a machine that did not write them down could not say which of
			// its logins it is holding.
			wanted: []string{"不是 API key", "CLI 登录", "我的 MacBook", "skills:read skills:write",
				"https://web.example/account/sessions"},
			// The sentence that is no longer true of it, and the reason this case exists.
			unwanted: []string{"控制台没有它的入口"},
		},
		{
			name: "the old browser login's leftover",
			stored: credentials.Credentials{
				AccessToken: "an-oauth-token", RefreshToken: "refresh-one",
			},
			wanted:   []string{"控制台没有它的入口"},
			unwanted: []string{"/account/sessions"},
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			store := credentials.EnvStore{Fallback: &stubStore{found: true, creds: c.stored}}
			var err error
			out := captureStdout(t, func() {
				err = authStatusWith(context.Background(), server.URL, store, "")
			})
			if err != nil {
				t.Fatalf("authStatus: %v", err)
			}
			for _, wanted := range c.wanted {
				if !strings.Contains(out, wanted) {
					t.Errorf("output = %q, want it to mention %q", out, wanted)
				}
			}
			for _, unwanted := range c.unwanted {
				if strings.Contains(out, unwanted) {
					t.Errorf("output = %q must not say %q", out, unwanted)
				}
			}
			// And it is never described as a key, whichever shape it is.
			if strings.Contains(out, "这把 key 叫") {
				t.Errorf("a credential that is not a key was described as one:\n%s", out)
			}
		})
	}
}

// An exported variable that is not a key is named as such, instead of surfacing as the unattended
// shape's own sentence three layers down.
//
// Without this, `SKILLMASTER_API_KEY=sk_…` (a typo, or a token pasted from the wrong place) reached
// every command as「这份凭据没有刷新令牌（无人值守登录就是这样）…请重新运行 skillmaster login
// --client-credentials」: a message about a shape nobody used, for a variable that is simply wrong.
func TestAnExportedVariableThatIsNotAKeyIsNamedAsSuch(t *testing.T) {
	server, asked := serveMe(t, http.StatusOK, keyBody("k", "skills:read", ""))
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.ApiKeyEnv, "sk_typo-not-a-key")
	t.Setenv("HOME", t.TempDir())

	for _, run := range []struct {
		name string
		call func(context.Context) error
	}{
		{"auth status", func(ctx context.Context) error { return authStatus(ctx, nil) }},
		{"a command that needs a token", func(ctx context.Context) error {
			_, err := authenticated(ctx, func(client api.Client) (api.Me, error) {
				return client.Me(ctx)
			})
			return err
		}},
	} {
		t.Run(run.name, func(t *testing.T) {
			var err error
			_ = captureStdout(t, func() { err = run.call(context.Background()) })
			if err == nil {
				t.Fatal("a variable that is not a key was used as one")
			}
			if !strings.Contains(err.Error(), config.ApiKeyEnv) || !strings.Contains(err.Error(), "不是一把 API key") {
				t.Fatalf("message = %q, want it to name the variable and what is wrong with it", err)
			}
			if !strings.Contains(err.Error(), "login --client-credentials") {
				// The old sentence must not be what the person reads: this is the assertion that the
				// dead end is gone rather than merely preceded by a better one.
				return
			}
			t.Fatalf("message = %q, still the unattended shape's sentence", err)
		})
	}
	if len(*asked) != 0 {
		t.Fatalf("the server was asked %v about a credential that cannot be one", *asked)
	}
}

// With a refused credential there is no server half to report, and the local half is still worth
// printing: it is what tells somebody which machine they are standing on.
func TestAuthStatusReportsARefusalWithTheReason(t *testing.T) {
	server, _ := serveMe(t, http.StatusUnauthorized,
		`{"error":{"code":"token_expired","message":"This API key has expired.","details":[]}}`)
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.ApiKeyEnv, aKey("from-env"))
	t.Setenv(config.WebURLEnv, "https://web.example")
	t.Setenv("HOME", t.TempDir())

	var err error
	out := captureStdout(t, func() { err = authStatus(context.Background(), nil) })
	if err == nil {
		t.Fatal("a refused credential was reported as a working one")
	}
	if !strings.Contains(err.Error(), "服务端：") || !strings.Contains(err.Error(), "已经过期") {
		t.Fatalf("message = %q, want the server's reason", err)
	}
	if !strings.Contains(out, "本机：凭据来自") {
		t.Fatalf("the local half was dropped when the server half failed:\n%s", out)
	}
}

// T17 of v3 · a machine still holding an unattended-era credential is not left in a dead end.
//
// **The shape is real and v3 created it**: `--client-credentials` wrote a credential with no refresh
// token, and removing the flag did not reach into anyone's keychain. So a machine that used that
// login still holds one, and everything it reads from here on is a sentence somebody wrote for a
// shape that no longer exists — which is exactly what T14 cannot see, because T14 greps for the
// *flag name* and these are messages.
//
// The two sentences are the whole feature: one tells a person with a spent leftover how to get out,
// the other tells somebody replacing one that the old credential cannot be cleaned up from here.

// A spent leftover, run through a command that needs a credential.
//
// The point of driving a command rather than `auth.AccessToken` — which has its own case for this —
// is the sentence the person actually reads, and that the way out it names is one this version has.
func TestASpentLeftoverNamesTheTwoWaysOutThatStillExist(t *testing.T) {
	server, asked := serveMe(t, http.StatusOK, keyBody("k", "skills:read", ""))
	store := &stubStore{found: true, creds: credentials.Credentials{
		ClientID: "dev-ci", AccessToken: "an-oauth-token",
		// No refresh token, and spent: what the unattended login left behind. Nothing this version
		// stores lacks one — a key is never renewed, and a browser login always has a chain.
		ExpiresAt: time.Now().Add(-time.Minute),
	}}
	pointTheMachineAt(t, store)
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.WebURLEnv, "https://web.example")
	t.Setenv("HOME", t.TempDir())

	var err error
	_ = captureStdout(t, func() {
		_, err = authenticated(context.Background(), func(client api.Client) (api.Me, error) {
			return client.Me(context.Background())
		})
	})
	if err == nil {
		t.Fatal("a spent credential was used as a working one")
	}

	said := err.Error()
	// The three things the design asks this sentence for (v4 Q8(ii)): what it is, why it cannot be
	// ended from this machine, and that it expires on its own — plus the way out that works here.
	for _, wanted := range []string{"无人值守登录", "撤不掉", "没有刷新令牌", "自己失效", "skillmaster login"} {
		if !strings.Contains(said, wanted) {
			t.Errorf("message = %q, want it to mention %q", said, wanted)
		}
	}
	// **The regression this case exists for**: the way out must not be a login this version does not
	// have. Two of them have been removed, one per version, and this sentence has been wrong about
	// which one before — it went on naming the unattended login for a version after that was gone.
	for _, unwanted := range []string{"--client-credentials", "--with-key"} {
		if strings.Contains(said, unwanted) {
			t.Fatalf("message = %q, still sends the person to a login this version does not have", said)
		}
	}
	// And it does not claim the credential was revoked: nothing did.
	if strings.Contains(said, "已撤销") {
		t.Fatalf("message = %q claims the leftover was revoked", said)
	}
	// And nothing was asked of the server: there is no request to make with a credential that cannot
	// be renewed, and a 401 would replace this sentence with the server's own.
	if len(*asked) != 0 {
		t.Fatalf("the server was asked %v about a credential that cannot be renewed", *asked)
	}
}

// Replacing a leftover says what it cannot do, rather than passing over it.
//
// Silence here reads as "the previous one was dealt with", and the version before this one revoked
// the authorization it had just replaced — so the person has been told the opposite of the truth
// once already. What is left is to say which credential this is and what will happen to it.
func TestReplacingALeftoverSaysItStaysUntilItExpires(t *testing.T) {
	server, asked := serveMe(t, http.StatusOK, "{}")

	previous := credentials.Credentials{ClientID: "dev-ci", AccessToken: "an-oauth-token"}
	out := captureStdout(t, func() {
		revokeReplaced(context.Background(), server.URL, previous, true)
	})

	for _, wanted := range []string{"无人值守登录", "无法从这台机器撤销", "过期"} {
		if !strings.Contains(out, wanted) {
			t.Errorf("output = %q, want it to mention %q", out, wanted)
		}
	}
	// The two sentences it must not be: that it was dealt with, and that something went wrong. The
	// login itself succeeded, so an error here would be a lie about the state the person is in.
	if strings.Contains(out, "已撤销") {
		t.Fatalf("output = %q, claims the leftover was revoked", out)
	}
	if len(*asked) != 0 {
		t.Fatalf("the server was asked %v to revoke a credential this CLI cannot present", *asked)
	}
}

// ---------------------------------------------------------------------------
// the two 403s, and the login a command starts for itself
// ---------------------------------------------------------------------------

// **"Another credential would work" and "nothing you hold will help" must not be said in the same
// words.** That is v4 验收 #8, and it is why the CLI tells the two 403s apart rather than printing one
// sentence about a status code: the difference is exactly the action a person can take.
//
// The two cases are the design's own (prd §一次操作有哪几种结果 2 and 5), one per credential, and each
// asserts what the other one says as well as what this one says — a message that drifted into the
// wrong branch is a different failure from one that is merely unhelpful.
func TestTheTwoRefusalsThatBothLookLike403(t *testing.T) {
	challenge := "Bearer, error=\"insufficient_scope\", scope=\"skills:write\"," +
		" resource_metadata=\"https://web.example/.well-known/oauth-protected-resource\""

	cases := []struct {
		name       string
		credential credentials.Credentials
		fromEnv    bool
		header     string
		body       string
		wanted     []string
		unwanted   []string
	}{
		{
			name:    "a key whose scopes are too narrow",
			fromEnv: true,
			header:  challenge,
			body:    `{"error":{"code":"insufficient_scope","message":"This request requires the 'skills:write' scope."}}`,
			wanted:  []string{"这把 API key 没有 `skills:write`", "https://web.example/account/keys", config.ApiKeyEnv},
			// **登录 is what it must not say, and that is the whole distinction**: another credential
			// would work here, and this CLI's login would hand over the same two scopes it always asks
			// for, so a person sent that way comes back to this message.
			unwanted: []string{"登录", "重新授权"},
		},
		{
			name: "a login credential whose scopes are too narrow",
			credential: credentials.Credentials{
				Name: "我的 MacBook", AccessToken: "a-login-token",
				ExpiresAt: time.Now().Add(time.Hour), Scope: "skills:read", SavedAt: time.Now(),
			},
			header: challenge,
			body:   `{"error":{"code":"insufficient_scope","message":"This request requires the 'skills:write' scope."}}`,
			wanted: []string{"这份凭据没有 `skills:write`", "https://web.example/account/keys",
				// Said rather than left for the person to discover by trying: another login asks for
				// the same two scopes, so it would come back with exactly what this one carries.
				"重新登录也一样"},
			// **Not 「重新授权」**: that is the loop this distinction exists to keep out.
			unwanted: []string{"重新授权"},
		},
		{
			name: "a login credential and a skill the account may not administer",
			credential: credentials.Credentials{
				Name: "我的 MacBook", AccessToken: "a-login-token",
				ExpiresAt: time.Now().Add(time.Hour), Scope: "skills:read skills:write",
				SavedAt: time.Now(),
			},
			// No challenge, which is the server's deliberate half: this header tells a client to go
			// and obtain a token with more scope, and no token would help here.
			body:     `{"error":{"code":"forbidden","message":"you need the editor role"}}`,
			wanted:   []string{"你对这个 skill 没有这个权限"},
			unwanted: []string{"重新授权", "重新登录", "到控制台签"},
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				if c.header != "" {
					w.Header().Set("WWW-Authenticate", c.header)
				}
				w.WriteHeader(http.StatusForbidden)
				_, _ = w.Write([]byte(c.body))
			}))
			t.Cleanup(server.Close)
			t.Setenv(config.ServerEnv, server.URL)
			t.Setenv(config.WebURLEnv, "https://web.example")
			t.Setenv("HOME", t.TempDir())
			if c.fromEnv {
				t.Setenv(config.ApiKeyEnv, aKey("narrow"))
			} else {
				pointTheMachineAt(t, &stubStore{found: true, creds: c.credential})
			}

			var err error
			_ = captureStdout(t, func() {
				_, err = authenticated(context.Background(), func(client api.Client) (api.SearchPage, error) {
					return client.Search(context.Background(), "anything")
				})
			})
			if err == nil {
				t.Fatal("a 403 was reported as success")
			}
			for _, wanted := range c.wanted {
				if !strings.Contains(err.Error(), wanted) {
					t.Errorf("message = %q, want it to mention %q", err, wanted)
				}
			}
			for _, unwanted := range c.unwanted {
				if strings.Contains(err.Error(), unwanted) {
					t.Errorf("message = %q must not say %q", err, unwanted)
				}
			}
		})
	}
}

// **A command with nothing to present starts the login itself and then finishes** (v4 做 #8, 验收 #9).
//
// The half worth testing is "and then finishes", which is the whole difference from the error message
// that used to be the answer: a person on a fresh machine typed `skills list`, and what they get is
// `skills list`. The login is stubbed — a browser cannot be driven from a test, and what it does is
// tested where it lives — and it writes a credential the way a real one does, so what is under test
// here is the wiring on both sides of it.
func TestACommandWithNoCredentialStartsTheLoginAndCarriesOn(t *testing.T) {
	server, asked := serveMe(t, http.StatusOK, keyBody("k", "skills:read", ""))
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv("HOME", t.TempDir())

	store := &stubStore{}
	pointTheMachineAt(t, store)
	driveTheLogin(t, true, func(store credentials.EnvStore) error {
		return store.Save(credentials.Credentials{
			Name: "我的 MacBook", AccessToken: "a-login-token",
			ExpiresAt: time.Now().Add(time.Hour), Scope: "skills:read skills:write",
			SavedAt: time.Now(),
		})
	})

	var err error
	// The announcement is a notice, so it goes to stderr: stdout is the command's answer, and in
	// `--json` mode it must be exactly one object.
	out := captureStderr(t, func() {
		_, err = authenticated(context.Background(), func(client api.Client) (api.Me, error) {
			return client.Me(context.Background())
		})
	})
	if err != nil {
		t.Fatalf("a command that logged in and then ran failed: %v", err)
	}
	// Said before anything else, so the browser that is about to appear is not a surprise.
	if !strings.Contains(out, "本机没有凭据，先登录") {
		t.Fatalf("the command did not say what it was doing:\n%s", out)
	}
	// **And the command it was asked to run is the one that ran** — the request is the proof, because
	// a login that stored a credential and then reported success would look the same on screen.
	if len(*asked) != 1 {
		t.Fatalf("the server was asked %v, want the command's own request", *asked)
	}
}

// And the boundary v4 验收 #9 names: a machine that cannot open a browser is not asked to (the
// existing `BrowserAvailable` is what decides), and the failure is the one it always was.
func TestACommandWithNoCredentialAndNoBrowserDoesNotStartOne(t *testing.T) {
	// Nothing is listening here, and that is the point: this path must not reach the network at all.
	t.Setenv(config.ServerEnv, "http://127.0.0.1:9")
	t.Setenv("HOME", t.TempDir())

	started := false
	driveTheLogin(t, false, func(credentials.EnvStore) error {
		started = true
		return nil
	})

	var err error
	_ = captureStdout(t, func() {
		_, err = authenticated(context.Background(), func(client api.Client) (api.Me, error) {
			return client.Me(context.Background())
		})
	})
	if err == nil {
		t.Fatal("a command with no credential and no browser reported success")
	}
	if started {
		t.Fatal("a login was started on a machine that cannot open a browser")
	}
	if !strings.Contains(err.Error(), "本机没有可用的凭据") {
		t.Fatalf("message = %q, want the one it always gave", err)
	}
}

// driveTheLogin stands in for the browser: what is being pinned is that a command that needs a
// credential runs the login and carries on, not what opening a browser does.
func driveTheLogin(t *testing.T, available bool, start func(store credentials.EnvStore) error) {
	t.Helper()
	wasBrowser, wasLogin := browserAvailable, startLogin
	browserAvailable = func() bool { return available }
	startLogin = func(_ context.Context, _ string, store credentials.EnvStore, _ string) error {
		return start(store)
	}
	t.Cleanup(func() { browserAvailable, startLogin = wasBrowser, wasLogin })
}

// 验收 #1's sentence: right after a login, the terminal says what this machine is called, what the
// credential may do, and when it could end.
//
// **Those three facts exist in one place and can only come from one answer**: the name was typed on a
// page this process never saw, and the scopes are what the server granted rather than what was asked
// for. So the sentence is pinned where it is composed, not only at the ends that feed it — and what is
// stored is pinned with it, because a blob that dropped either would leave this machine unable to
// describe the login it is holding.
func TestTheLoginReportSaysNameScopeAndDate(t *testing.T) {
	store := &stubStore{}
	var err error
	out := captureStdout(t, func() {
		err = saveAndReport(os.Stdout, credentials.EnvStore{Fallback: store}, "", "https://api.example",
			auth.Credential{
				Name:        "我的 MacBook",
				AccessToken: "a-login-token",
				ExpiresAt:   time.Date(2026, 10, 23, 10, 0, 0, 0, time.UTC),
				Scopes:      []string{"skills:read", "skills:write"},
			})
	})
	if err != nil {
		t.Fatalf("saveAndReport: %v", err)
	}
	for _, want := range []string{"已登录 https://api.example", "我的 MacBook",
		"skills:read skills:write", "2026-10-23T10:00:00Z"} {
		if !strings.Contains(out, want) {
			t.Errorf("the report does not mention %q:\n%s", want, out)
		}
	}
	// **The date is the earliest end rather than a fixed one, and the sentence has to say so**: every
	// use slides it forward, so a person who read the stamp as an end would conclude that a working
	// login had died.
	if !strings.Contains(out, "至少有效到") {
		t.Errorf("the date is presented as a fixed end:\n%s", out)
	}

	if len(store.saves) != 1 {
		t.Fatalf("the credential was written %d times, want 1", len(store.saves))
	}
	stored := store.saves[0]
	if stored.Name != "我的 MacBook" || stored.Scope != "skills:read skills:write" {
		t.Fatalf("stored %+v, want the name and the scopes the exchange answered", stored)
	}
	// And the absences are the shape: nothing renews this credential, so a client id and a refresh
	// token are what mark the leftovers of the logins that are gone.
	if stored.ClientID != "" || stored.RefreshToken != "" {
		t.Fatalf("a login was stored with a refresh shape: %+v", stored)
	}
}

// ---------------------------------------------------------------------------
// auth status <地址> — 做 #9 的第二条查询
// ---------------------------------------------------------------------------

// What **this** credential can do with one skill, in the shapes the answer actually has.
//
// **The two middle cases are the ones worth having**, and they differ only in what the credential
// carries: a missing scope is something the person fixes by issuing another credential, while the
// account's own standing on the skill is something no credential can fix. A command that said
// 「没有权限」 for both would send somebody to re-issue a key that cannot help them, which is why the
// second of them also asserts that the first one's remedy is *absent*.
//
// The last two are about not saying more than the server does: a 404 covers "no such skill" and "not
// visible to you" alike, and collapsing them is the point rather than a shortcut.
func TestAuthStatusAboutOneSkill(t *testing.T) {
	const address = "alice/pdf-tools"

	cases := []struct {
		name     string
		scope    string
		status   int
		body     string
		wanted   []string
		unwanted []string
	}{
		{
			name:   "readable and writable",
			scope:  "skills:read skills:write",
			status: http.StatusOK,
			body:   `{"can_write":true}`,
			wanted: []string{address + "：能读，能写。"},
		},
		{
			name:   "readable, not writable, and the credential is what is short",
			scope:  "skills:read",
			status: http.StatusOK,
			body:   `{"can_write":false}`,
			wanted: []string{
				address + "：能读，不能写。",
				"这份凭据只带了 skills:read。",
				"换一份带 skills:write 的凭据就行。",
			},
		},
		{
			name:   "readable, not writable, and no credential would change it",
			scope:  "skills:read skills:write",
			status: http.StatusOK,
			body:   `{"can_write":false}`,
			wanted: []string{
				address + "：能读，不能写。",
				"你对这个 skill 没有这个权限",
				"换一份凭据没有用。",
			},
			unwanted: []string{"换一份带 skills:write 的凭据就行"},
		},
		{
			name:   "not readable, and the credential is why",
			scope:  "skills:write",
			status: http.StatusForbidden,
			body:   `{"error":{"code":"insufficient_scope","message":"nope","details":[]}}`,
			wanted: []string{address + "：不能读", "这份凭据没有 skills:read", "换一份"},
		},
		{
			name:   "no such skill, which is also what one they may not see answers",
			scope:  "skills:read",
			status: http.StatusNotFound,
			body:   `{"error":{"code":"skill_not_found","message":"no","details":[]}}`,
			wanted: []string{address + "：读不到", "它不存在，或者这份凭据看不到它"},
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				if strings.HasSuffix(r.URL.Path, "/me") {
					_, _ = w.Write([]byte(keyBody("笔记本", c.scope, "")))
					return
				}
				w.WriteHeader(c.status)
				_, _ = w.Write([]byte(c.body))
			}))
			t.Cleanup(server.Close)

			t.Setenv(config.ServerEnv, server.URL)
			t.Setenv(config.ApiKeyEnv, aKey("about-one-skill"))
			t.Setenv("HOME", t.TempDir())
			store := credentials.EnvStore{
				Fallback: &stubStore{found: true, creds: credentials.Credentials{
					AccessToken: aKey("about-one-skill"),
				}},
			}

			var err error
			out := captureStdout(t, func() {
				err = authStatusWith(context.Background(), server.URL, store, address)
			})
			if err != nil {
				t.Fatalf("authStatus %s: %v", address, err)
			}
			// The credential's own facts are still printed — this narrows the question rather than
			// replacing it.
			if !strings.Contains(out, "服务端：账号 owner") {
				t.Errorf("output = %q, want the credential's facts above the answer", out)
			}
			for _, wanted := range c.wanted {
				if !strings.Contains(out, wanted) {
					t.Errorf("output = %q, want it to mention %q", out, wanted)
				}
			}
			for _, unwanted := range c.unwanted {
				if strings.Contains(out, unwanted) {
					t.Errorf("output = %q must not say %q", out, unwanted)
				}
			}
		})
	}
}

// Without an address, nothing is asked of any skill — the command is the one it was before.
//
// Asserted by counting, because "it did not print" and "it did not ask" are different failures: a
// request that went out and was ignored would still be a request made with somebody's credential.
func TestAuthStatusWithoutAnAddressAsksNothingElse(t *testing.T) {
	server, asked := serveMe(t, http.StatusOK, keyBody("笔记本", "skills:read", ""))
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.ApiKeyEnv, aKey("no-address"))
	t.Setenv("HOME", t.TempDir())

	var err error
	_ = captureStdout(t, func() { err = authStatus(context.Background(), nil) })
	if err != nil {
		t.Fatalf("authStatus: %v", err)
	}
	if len(*asked) != 1 || !strings.HasSuffix((*asked)[0], "/me") {
		t.Fatalf("asked %v, want `/me` alone", *asked)
	}
}

// A second argument is refused rather than ignored.
//
// Asserted through `authCmd`, because that is where the refusal lives: dropping the extra argument
// further in would silently answer about the first address, which is the kind of guess a person
// cannot see they are being given.
func TestAuthStatusRefusesASecondAddress(t *testing.T) {
	err := authCmd(context.Background(), []string{"status", "alice/pdf-tools", "bob/other"})
	if err == nil || !strings.Contains(err.Error(), "最多接一个") {
		t.Fatalf("err = %v", err)
	}
}

// The login's narration goes wherever its caller says. `login` answers on stdout; a command that
// signed in for itself passes stderr, because its stdout is the `--json` object and nothing else.
func TestTheLoginReportWritesToTheCallersWriter(t *testing.T) {
	var buf bytes.Buffer
	store := &stubStore{}
	err := saveAndReport(&buf, credentials.EnvStore{Fallback: store}, "", "https://api.example",
		auth.Credential{
			Name:        "我的 MacBook",
			AccessToken: "a-login-token",
			ExpiresAt:   time.Date(2026, 10, 23, 10, 0, 0, 0, time.UTC),
			Scopes:      []string{"skills:read"},
		})
	if err != nil {
		t.Fatalf("saveAndReport: %v", err)
	}
	if !strings.Contains(buf.String(), "已登录 https://api.example") {
		t.Errorf("the report did not reach the writer:\n%s", buf.String())
	}
}

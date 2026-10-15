package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
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

// withStdin hands the command something to read, which is the only way `login --with-key` accepts a
// credential: an argument would be in the shell's history and in the process table.
func withStdin(t *testing.T, content string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "paste")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("writing the paste: %v", err)
	}
	file, err := os.Open(path)
	if err != nil {
		t.Fatalf("opening the paste: %v", err)
	}
	t.Cleanup(func() { _ = file.Close() })
	original := os.Stdin
	os.Stdin = file
	t.Cleanup(func() { os.Stdin = original })
}

// ---------------------------------------------------------------------------
// login --with-key
// ---------------------------------------------------------------------------

// The paste is checked with the server **before** anything is written.
//
// Storing first would move the failure to the next command, where the person is no longer looking at
// what they pasted — and the message they would get there is about a request, not about the string
// they can still fix.
func TestAKeyIsVerifiedBeforeItIsStored(t *testing.T) {
	server, asked := serveMe(t, http.StatusOK, keyBody("笔记本", "skills:read", "2027-01-13T15:59:59Z"))
	store := &stubStore{}
	t.Setenv(config.WebURLEnv, "https://web.example")
	withStdin(t, aKey("paste")+"\n") // a trailing newline, as a pipe or a redirect gives it

	var err error
	_ = captureStdout(t, func() {
		err = loginWithKey(context.Background(), server.URL, "",
			credentials.EnvStore{Fallback: store}, credentials.Credentials{}, false)
	})
	if err != nil {
		t.Fatalf("loginWithKey: %v", err)
	}

	if len(store.saves) != 1 {
		t.Fatalf("the credential was written %d times, want 1", len(store.saves))
	}
	if got := store.saves[0].AccessToken; got != aKey("paste") {
		t.Fatalf("stored %q, want the key that was pasted", got)
	}
	// No refresh chain and no client id: nothing renews a key, so there is nothing to store for it.
	if store.saves[0].RefreshToken != "" || store.saves[0].ClientID != "" {
		t.Fatalf("a key was stored with a refresh chain: %+v", store.saves[0])
	}
	if len(*asked) != 1 {
		t.Fatalf("asked %v, want exactly one request", *asked)
	}
	// The trailing newline a pipe adds is not part of the credential.
	if got := store.saves[0].AccessToken; strings.HasSuffix(got, "\n") {
		t.Fatalf("the stored key kept its newline: %q", got)
	}
}

// The expiry the server reports is kept for the answer `auth status` gives, and it never becomes a
// local judgement — the stored date is not what decides whether the key still works.
func TestTheKeysOwnNameAndScopeAreReportedBack(t *testing.T) {
	server, _ := serveMe(t, http.StatusOK, keyBody("CI 流水线", "skills:read skills:write", ""))
	store := &stubStore{}
	withStdin(t, aKey("paste"))

	var runErr error
	out := captureStdout(t, func() {
		runErr = loginWithKey(context.Background(), server.URL, "", credentials.EnvStore{Fallback: store},
			credentials.Credentials{}, false)
	})
	if runErr != nil {
		t.Fatalf("loginWithKey: %v", runErr)
	}

	for _, want := range []string{"owner", "CI 流水线", "skills:read skills:write", "不过期"} {
		if !strings.Contains(out, want) {
			t.Fatalf("the report does not mention %q:\n%s", want, out)
		}
	}
	if !store.saves[0].ExpiresAt.IsZero() {
		t.Fatalf("a key the server says never expires was stored with %v", store.saves[0].ExpiresAt)
	}
}

// A terminal is neither of the two shapes `--with-key` accepts, and it is refused before the read.
func TestATerminalIsNotOneOfTheTwoShapes(t *testing.T) {
	server, asked := serveMe(t, http.StatusOK, keyBody("k", "skills:read", ""))
	store := &stubStore{}
	t.Setenv(config.WebURLEnv, "https://web.example")
	withStdin(t, aKey("pasted"))
	was := stdinIsATerminal
	stdinIsATerminal = func() bool { return true }
	t.Cleanup(func() { stdinIsATerminal = was })

	var err error
	captureStdout(t, func() {
		err = loginWithKey(context.Background(), server.URL, "", credentials.EnvStore{Fallback: store},
			credentials.Credentials{}, false)
	})
	if err == nil {
		t.Fatal("a terminal was treated as a pipe")
	}
	for _, want := range []string{"不收终端里直接粘贴", "pbpaste | skillmaster login --with-key", "SKILLMASTER_API_KEY"} {
		if !strings.Contains(err.Error(), want) {
			t.Fatalf("message = %q, want it to mention %q", err, want)
		}
	}
	// Refused before anything else happens: no request, and nothing written.
	if len(*asked) != 0 || len(store.saves) != 0 {
		t.Fatalf("the terminal case reached the server (%d) or the store (%d)", len(*asked), len(store.saves))
	}
}

// `--with-key` says where the credential went, including the fallback it did not choose.
//
// ADR 0025 §决定 requires a login to say whether the credential landed in the keychain or in a
// plaintext file, and this shape used to print the location and drop the warning — on exactly the
// machine where it fires (a container with no keychain).
func TestTheFallbackNoticeIsSaidOnThisShapeToo(t *testing.T) {
	server, _ := serveMe(t, http.StatusOK, keyBody("k", "skills:read", ""))
	t.Setenv(config.WebURLEnv, "https://web.example")
	withStdin(t, aKey("paste")+"\n")

	stderr := captureStderr(t, func() {
		captureStdout(t, func() {
			if err := loginWithKey(context.Background(), server.URL, "这台机器上没有可用的钥匙串，凭据写到了明文文件",
				credentials.EnvStore{Fallback: &stubStore{}}, credentials.Credentials{}, false); err != nil {
				t.Fatalf("loginWithKey: %v", err)
			}
		})
	})
	if !strings.Contains(stderr, "明文") {
		t.Fatalf("stderr = %q, want the store's own notice", stderr)
	}
}

// A paste that cannot be a key is refused without a request, and the sentence is about the paste.
//
// Two cases, and the empty one is not a formality: a reader with nothing in it is not a paste, and
// the sentence says so.
//
// **What a test cannot reach is the terminal**, and that is the case that bites: `io.ReadAll` returns
// at end-of-file, a pasted line does not end the file, so the command sits there having printed
// nothing. This fixture hands it a *file* — which always ends — so it proves the empty-input message
// and nothing about a TTY; `TestATerminalIsNotOneOfTheTwoShapes` is where that is pinned.
func TestAPasteThatCannotBeAKeyNeverReachesTheServer(t *testing.T) {
	cases := []struct {
		name   string
		paste  string
		wanted string
	}{
		{"nothing on standard input", "  \n", "标准输入里什么都没有"},
		{"a truncated key", "skm_" + strings.Repeat("a", 20), "共 47 个字符"},
		{"an OAuth token", "eyJhbGciOiJIUzI1NiJ9.payload.signature", "这不像一把 API key"},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			server, asked := serveMe(t, http.StatusOK, keyBody("k", "skills:read", ""))
			store := &stubStore{}
			t.Setenv(config.WebURLEnv, "https://web.example")
			withStdin(t, c.paste)

			var err error
			captureStdout(t, func() {
				err = loginWithKey(context.Background(), server.URL, "", credentials.EnvStore{Fallback: store},
					credentials.Credentials{}, false)
			})
			if err == nil {
				t.Fatal("a paste that cannot be a key was accepted")
			}
			if !strings.Contains(err.Error(), c.wanted) {
				t.Fatalf("message = %q, want it to contain %q", err, c.wanted)
			}
			if len(*asked) != 0 {
				t.Fatalf("the server was asked %v about a string that cannot be a key", *asked)
			}
			if len(store.saves) != 0 {
				t.Fatal("a refused paste was written to the store")
			}
		})
	}
}

// A key the server refuses is reported with the server's reason and stored nowhere.
//
// The reason is the whole point of the server naming it: revoked and expired read the same to a
// person unless the CLI says which one happened, and they lead to different buttons in the console.
func TestARefusedKeyIsReportedAndNotStored(t *testing.T) {
	server, _ := serveMe(t, http.StatusUnauthorized,
		`{"error":{"code":"token_revoked","message":"This API key has been revoked.","details":[]}}`)
	store := &stubStore{}
	t.Setenv(config.WebURLEnv, "https://web.example")
	withStdin(t, aKey("paste"))

	var err error
	captureStdout(t, func() {
		err = loginWithKey(context.Background(), server.URL, "", credentials.EnvStore{Fallback: store},
			credentials.Credentials{}, false)
	})
	if err == nil {
		t.Fatal("a revoked key was accepted")
	}
	if !strings.Contains(err.Error(), "已被撤销") || !strings.Contains(err.Error(), "https://web.example/account/keys") {
		t.Fatalf("message = %q, want the reason and where to go", err)
	}
	if !strings.Contains(err.Error(), "没有写入任何东西") {
		t.Fatalf("message = %q, want it to say nothing was stored", err)
	}
	if len(store.saves) != 0 {
		t.Fatal("a refused key was written to the store")
	}
}

// A variable that wins over what was just stored is said out loud, at the moment it starts to matter.
//
// Otherwise the login reads as one that did not take: the command reports success, and the next
// command uses a different key.
func TestAKeyStoredWhileTheVariableIsSetSaysTheVariableStillWins(t *testing.T) {
	server, _ := serveMe(t, http.StatusOK, keyBody("CI", "skills:read", ""))
	store := &stubStore{}
	withStdin(t, aKey("paste"))

	var err error
	out := captureStdout(t, func() {
		err = loginWithKey(context.Background(), server.URL, "",
			credentials.EnvStore{Var: config.ApiKeyEnv, Value: aKey("from-env"), Fallback: store},
			credentials.Credentials{}, false)
	})
	if err != nil {
		t.Fatalf("loginWithKey: %v", err)
	}
	// The write still happened — a process cannot set its parent's variables, so pretending it went to
	// the environment would be a login that reports success and changes nothing.
	if len(store.saves) != 1 {
		t.Fatalf("the machine's store was written %d times, want 1", len(store.saves))
	}
	if !strings.Contains(out, config.ApiKeyEnv) {
		t.Fatalf("the report does not say which variable wins:\n%s", out)
	}
}

// Replacing a browser-login credential warns about the one thing this path cannot clean up.
//
// A key is not an authorization, so there is no refresh chain to end and no row this machine created
// — but the OAuth authorization an earlier browser login made stays live, and the console has no
// entry for it (ADR 0040 §后果). Saying so is the difference between a documented gap and a surprise.
func TestReplacingABrowserCredentialSaysWhatCannotBeRevoked(t *testing.T) {
	server, _ := serveMe(t, http.StatusOK, keyBody("CI", "skills:read", ""))
	store := &stubStore{}
	withStdin(t, aKey("paste"))

	previous := credentials.Credentials{AccessToken: "an-oauth-token", RefreshToken: "refresh-one"}
	var err error
	out := captureStdout(t, func() {
		err = loginWithKey(context.Background(), server.URL, "", credentials.EnvStore{Fallback: store},
			previous, true)
	})
	if err != nil {
		t.Fatalf("loginWithKey: %v", err)
	}
	if !strings.Contains(out, "控制台无法单独撤销") {
		t.Fatalf("the report does not mention the authorization left behind:\n%s", out)
	}
}

// And nothing is said when the thing being replaced was itself a key: a key leaves nothing behind, so
// a warning here would be about a problem that does not exist on this path.
func TestReplacingAKeySaysNothingAboutAuthorizations(t *testing.T) {
	server, _ := serveMe(t, http.StatusOK, keyBody("CI", "skills:read", ""))
	store := &stubStore{}
	withStdin(t, aKey("paste"))

	var err error
	out := captureStdout(t, func() {
		err = loginWithKey(context.Background(), server.URL, "", credentials.EnvStore{Fallback: store},
			credentials.Credentials{AccessToken: aKey("old")}, true)
	})
	if err != nil {
		t.Fatalf("loginWithKey: %v", err)
	}
	if strings.Contains(out, "控制台无法单独撤销") {
		t.Fatalf("a replaced key was described as an authorization:\n%s", out)
	}
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
		// A reason this version does not know keeps the sentence it always had, plus where to look:
		// reporting it as a plain failure would leave somebody with no next step at all.
		{"a reason this version does not know", "unauthenticated", "本机不能续期"},
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

// The negative case T6 asks for: on a machine with no browser the ordinary `login` must fail, and the
// failure must name what does work there.
//
// It fails **before** it prints an authorization URL and starts listening, which is the part worth
// testing: the other order produces a command that waits for a callback that cannot come, and the
// person has no way to tell that from a slow login.
func TestLoginWithoutABrowserPointsAtTheKeyPath(t *testing.T) {
	original := browserAvailable
	browserAvailable = func() bool { return false }
	t.Cleanup(func() { browserAvailable = original })

	// Nothing is listening, and that is fine: this path must not reach the network at all. It is also
	// the address the credential is looked up under, which is why it is a port nobody serves.
	t.Setenv(config.ServerEnv, "http://127.0.0.1:9")
	t.Setenv("HOME", t.TempDir())

	var err error
	_ = captureStdout(t, func() { err = login(context.Background(), nil) })
	if err == nil {
		t.Fatal("a browser login was attempted on a machine with no browser")
	}
	for _, want := range []string{"--with-key", config.ApiKeyEnv, "没有可以打开链接的浏览器"} {
		if !strings.Contains(err.Error(), want) {
			t.Fatalf("message = %q, want it to contain %q", err, want)
		}
	}
}

// A replaced credential is handed to the revocation endpoint only when there is an authorization to
// end, and the difference is visible: an API key posted to `/oauth/revoke` is refused with no client
// id, and this command would report that as an authorization that "stayed live".
func TestReplacingAKeyDoesNotCallTheRevocationEndpoint(t *testing.T) {
	server, asked := serveMe(t, http.StatusOK, "{}")
	t.Setenv(config.WebURLEnv, "https://web.example")

	out := captureStdout(t, func() {
		revokeReplaced(context.Background(), server.URL,
			credentials.Credentials{AccessToken: aKey("stored")}, true)
	})
	if len(*asked) != 0 {
		t.Fatalf("a key was presented for revocation: %v", *asked)
	}
	if !strings.Contains(out, "/account/keys") {
		t.Fatalf("the message does not say where a key is revoked:\n%s", out)
	}

	// And the other kind is still revoked: an OAuth authorization has a refresh token to present, and
	// skipping that would leave it live until it idled out.
	*asked = (*asked)[:0]
	captureStdout(t, func() {
		revokeReplaced(context.Background(), server.URL,
			credentials.Credentials{ClientID: config.ClientID, RefreshToken: "refresh-one"}, true)
	})
	if len(*asked) == 0 {
		t.Fatal("an OAuth authorization was not revoked at all")
	}
}

// ---------------------------------------------------------------------------
// logout, and what it does not do
// ---------------------------------------------------------------------------

// **`login`'s own dispatcher, which is where this command actually breaks.**
//
// The three shapes had tests; the switch that chooses between them did not, and a catch-all for
// `-`-prefixed arguments placed above the real flags swallowed `--with-key` and
// `--client-credentials` — the only ways this version hands the CLI a credential — while
// `go test ./...` stayed green, because every test called `loginWithKey`/`loginWithBrowser` directly.
// These go through `login` for that reason: what is being pinned is that the flag reaches its shape.
func TestLoginSendsEachFlagToItsOwnShape(t *testing.T) {
	server, _ := serveMe(t, http.StatusOK, keyBody("笔记本", "skills:read", ""))
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.WebURLEnv, "https://web.example")
	t.Setenv("HOME", t.TempDir())

	// `--with-key`: the paste is read and checked with the server.
	withStdin(t, aKey("paste"))
	var err error
	out := captureStdout(t, func() { err = login(context.Background(), []string{"--with-key"}) })
	if err != nil {
		t.Fatalf("login --with-key: %v", err)
	}
	if !strings.Contains(out, "笔记本") {
		t.Fatalf("login --with-key reported %q, want the key it verified:\n%s", err, out)
	}

	// `--client-credentials`: it refuses for the reason it always did — the two variables — rather
	// than for an unknown option.
	t.Setenv(config.ClientCredentialsIDEnv, "")
	t.Setenv(config.ClientCredentialsSecretEnv, "")
	var second error
	_ = captureStdout(t, func() { second = login(context.Background(), []string{"--client-credentials"}) })
	if second == nil || !strings.Contains(second.Error(), config.ClientCredentialsIDEnv) {
		t.Fatalf("login --client-credentials said %v, want the missing variables", second)
	}
	if strings.Contains(second.Error(), "未知的选项") {
		t.Fatalf("a flag this command has was refused as unknown: %v", second)
	}
}

// A flag this command does not know is refused rather than answered with a browser window, and the
// refusal does not repeat what it was given.
//
// `login --help` used to fall through to the browser shape: somebody asking what the options are got
// a consent page, or — on a machine with no browser — a paragraph about API keys.
func TestLoginRefusesFlagsItDoesNotKnow(t *testing.T) {
	t.Setenv(config.ServerEnv, "http://127.0.0.1:9")
	t.Setenv("HOME", t.TempDir())

	original := browserAvailable
	browserAvailable = func() bool { return false }
	t.Cleanup(func() { browserAvailable = original })

	// **Quoted, because the usage text is in the same message and a bare substring proves nothing.**
	// `--with-ke` is a prefix of the `--with-key` the usage lists, so `Contains(err, args[0])` was
	// satisfied by the paragraph rather than by the refusal — it would have passed with the flag name
	// stripped out of the message entirely.
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

	// **And the refusal does not quote the argument back.** `--with-key=skm_…` is exactly the spelling
	// this guard exists to refuse, and `%q` on the whole argument put the secret in the terminal (and
	// in whatever collects a CI job's output) — one more place the credential now lives, in the message
	// that was supposed to protect it.
	//
	// Three spellings reach three branches and each refuses in its own words, so each gets its own
	// phrase: a message that drifted into the wrong branch is a different failure from one that leaked,
	// and the two have to be able to fail separately.
	for _, c := range []struct {
		arg  string
		want string // a phrase only this branch produces
	}{
		{"--with-key=" + aKey("canary"), "login --with-key 不接受参数"},
		{"--client-credentials=" + aKey("canary"), "login --client-credentials 不接受参数"},
		{"--api-key=" + aKey("canary"), `未知的选项 "--api-key"`},
	} {
		var leaked error
		_ = captureStdout(t, func() { leaked = login(context.Background(), []string{c.arg}) })
		if leaked == nil {
			t.Fatalf("%s was accepted", c.arg)
		}
		if strings.Contains(leaked.Error(), aKey("canary")) {
			t.Fatalf("the refusal of %s repeated the secret: %q", c.arg, leaked)
		}
		if !strings.Contains(leaked.Error(), c.want) {
			t.Fatalf("the refusal of %s said %q, want the branch that says %q", c.arg, leaked, c.want)
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
			t.Fatalf("login with %q said %q, want a refusal rather than a browser login", arg, quiet)
		}
	}

	// And asking for help is not a mistake: the usage goes to stderr (this CLI's convention, so that a
	// script's stdout stays parseable) and the command succeeds.
	for _, args := range [][]string{{"help"}, {"--help"}} {
		var err error
		usage := captureStderr(t, func() { err = login(context.Background(), args) })
		if err != nil {
			t.Fatalf("login%v: %v", args, err)
		}
		if !strings.Contains(usage, "--with-key") {
			t.Fatalf("login%v does not mention the shape v2 added:\n%s", args, usage)
		}
	}
}

// The two real shapes take no parameters, and saying so is a different sentence from "don't put a
// credential on the command line".
//
// `login --with-key --client-credentials` reached the old message and was told about a credential
// sitting in the shell's history and the process table — something that had not happened, which sends
// the person looking for a mistake they did not make. `login --client-credentials extra` was worse
// than a wrong sentence: it read the environment and said nothing at all about the word it dropped.
func TestTheTwoShapesRefuseParameters(t *testing.T) {
	t.Setenv(config.ServerEnv, "http://127.0.0.1:9")
	t.Setenv("HOME", t.TempDir())

	for _, c := range []struct {
		args []string
		want string
	}{
		{[]string{"--with-key", "--client-credentials"}, "login --with-key 不接受参数"},
		{[]string{"--with-key", aKey("canary")}, "login --with-key 不接受参数"},
		{[]string{"--client-credentials", "extra"}, "login --client-credentials 不接受参数"},
		{[]string{"--client-credentials=" + aKey("canary")}, "login --client-credentials 不接受参数"},
	} {
		var err error
		_ = captureStdout(t, func() { err = login(context.Background(), c.args) })
		if err == nil {
			t.Fatalf("login%v was accepted", c.args)
		}
		if !strings.Contains(err.Error(), c.want) {
			t.Fatalf("login%v said %q, want %q", c.args, err, c.want)
		}
		if strings.Contains(err.Error(), aKey("canary")) {
			t.Fatalf("the refusal of %v repeated the argument: %q", c.args, err)
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
	out := captureStdout(t, func() { err = authStatus(context.Background()) })
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
// The stored credential is the way that happens in practice — the authorization an old browser login
// left behind — while a *variable* holding one is refused before this point (see the test below): the
// variable is documented as holding a key, so a token in it is a mistake worth naming as itself.
func TestAuthStatusSaysWhenTheStoredCredentialIsNotAKey(t *testing.T) {
	server, _ := serveMe(t, http.StatusOK,
		`{"user":{"user_id":"u1","username":"owner","namespace":"owner"},"key":null}`)
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.ApiKeyEnv, "")
	t.Setenv("HOME", t.TempDir())

	store := credentials.EnvStore{Fallback: &stubStore{found: true, creds: credentials.Credentials{
		AccessToken: "an-oauth-token", RefreshToken: "refresh-one",
	}}}
	var err error
	out := captureStdout(t, func() { err = authStatusWith(context.Background(), server.URL, store) })
	if err != nil {
		t.Fatalf("authStatus: %v", err)
	}
	if !strings.Contains(out, "不是 API key") {
		t.Fatalf("auth status described a token as a key:\n%s", out)
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
		{"auth status", func(ctx context.Context) error { return authStatus(ctx) }},
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
	out := captureStdout(t, func() { err = authStatus(context.Background()) })
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

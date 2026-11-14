// The device login (v68), as a command sees it: a link on stdout, a credential collected, and the
// refusals that used to mean "log in first" meaning something else now.
//
// **Its own file rather than more of `keys_test.go`.** That file is over the repository's file-size
// ceiling (`scripts/check-file-size.mjs`, ADR 0162), and a baseline file may only shrink; these
// tests are about the login, not about keys, so they belong beside the command they pin.

package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/auth"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// writeTestJSON is the two lines every fake server in this package would otherwise repeat.
func writeTestJSON(t *testing.T, w http.ResponseWriter, body any) {
	t.Helper()
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(body); err != nil {
		t.Errorf("writing a response: %v", err)
	}
}

// **A revoked login does not quietly start another one.** The person ended that login; a command
// that meets the 401 says so and names the command that starts a new one. Starting one by itself
// would block for ten minutes on a URL nobody is looking at, on a machine whose person was just told
// they were signed out.
func TestARevokedLoginSaysSoAndStartsNoNewLogin(t *testing.T) {
	server, _ := serveMe(t, http.StatusUnauthorized,
		`{"error":{"code":"token_revoked","message":"refused"}}`)
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.WebURLEnv, "https://web.example")
	t.Setenv(config.ApiKeyEnv, "")
	t.Setenv("HOME", t.TempDir())

	pointTheMachineAt(t, &stubStore{found: true, creds: credentials.Credentials{
		AccessToken: "a-login-token", Name: "我的 MacBook", SavedAt: time.Now(),
	}})
	wasLogin := startLogin
	started := false
	startLogin = func(context.Context, string, credentials.EnvStore, string) error {
		started = true
		return nil
	}
	t.Cleanup(func() { startLogin = wasLogin })

	_, err := authenticated(context.Background(), func(client api.Client) (api.Me, error) {
		return client.Me(context.Background())
	})
	if err == nil {
		t.Fatal("a revoked login reported success")
	}
	if started {
		t.Fatal("a revoked login started a new one by itself")
	}
	if !strings.Contains(err.Error(), "skmr login") {
		t.Fatalf("message = %q, want the command that starts a new login", err)
	}
}

// **A machine with no browser can log in now, and this is the criterion that changed in v68.**
//
// v4 验收 #7(a) used to ask for a refusal here, with the API key as the only road. The device login
// deletes the reason: the link is opened somewhere else, so a browser on *this* machine is not a
// precondition any more. What used to be the refusal is now the whole login, so the test drives it
// against a fake server and pins the two facts that matter — the link is printed, and nothing about
// the removed shapes (the paste command, `--with-key`, client credentials) comes back.
func TestLoginWithoutABrowserPrintsTheLinkAndCollectsTheCredential(t *testing.T) {
	// The sandbox markers are cleared for the reason `driveTheLogin` clears them: these tests run
	// under `go test`, including inside an agent sandbox, and the marker would change the path.
	for _, name := range []string{"CODEX_SANDBOX", "CURSOR_SANDBOX", "GEMINI_SANDBOX", "SANDBOX_RUNTIME"} {
		t.Setenv(name, "")
	}
	originalBrowser, originalOpen := browserAvailable, openBrowser
	browserAvailable = func() bool { return false }
	var opened string
	openBrowser = func(target string) error {
		opened = target
		return nil
	}
	t.Cleanup(func() { browserAvailable, openBrowser = originalBrowser, originalOpen })

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == auth.DiscoveryPath:
			writeTestJSON(t, w, map[string]string{"issuer": "https://" + r.Host})
		case r.URL.Path == auth.DeviceSignInsPath && r.Method == http.MethodPost:
			w.WriteHeader(http.StatusCreated)
			writeTestJSON(t, w, map[string]any{
				"ticket": "ticket-one", "device_secret": "secret-one",
				"expires_in": 600, "interval": 1,
			})
		case strings.HasSuffix(r.URL.Path, "/token"):
			writeTestJSON(t, w, map[string]any{
				"status": "approved", "access_token": "a-login-token",
				"expires_at": time.Now().Add(time.Hour).Format(time.RFC3339),
				"scope":      "skills:read skills:write", "name": "我的 MacBook", "username": "alice",
			})
		default:
			t.Errorf("unexpected request %s %s", r.Method, r.URL.Path)
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	t.Cleanup(server.Close)

	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv("HOME", t.TempDir())

	var err error
	out := captureStdout(t, func() { err = login(context.Background(), nil) })
	if err != nil {
		t.Fatalf("login without a browser: %v", err)
	}
	if !strings.Contains(out, "/cli-sign-in?ticket=ticket-one") {
		t.Errorf("stdout does not carry the link:\n%s", out)
	}
	if !strings.Contains(out, "不要转发") {
		t.Errorf("stdout does not warn about forwarding the link:\n%s", out)
	}
	for _, unwanted := range []string{"--with-key", "pbpaste", "--client-credentials",
		"没有可以打开链接的浏览器"} {
		if strings.Contains(out, unwanted) {
			t.Errorf("the login still says %q", unwanted)
		}
	}
	// **No launch is attempted here** (PRD 验收 #1): the machine has no browser, so the command does
	// not call one and print its failure over the instructions the person is reading. The link is the
	// whole mechanism, and it is on stdout.
	if opened != "" {
		t.Errorf("tried to open %q on a machine with no browser", opened)
	}
}

// **A login that ran out is not a connection problem.** The two codes ask for different next steps,
// so a script reading only the code must not be told to retry a link that is gone.
func TestALoginThatRanOutIsItsOwnFailureCode(t *testing.T) {
	failure := classifyFailure(&auth.LoginFailedError{Err: errors.New("这次登录没有完成")})
	if failure.Code != "login_failed" {
		t.Fatalf("code = %q, want login_failed", failure.Code)
	}
	// The transport failure keeps the code it always had: the two must not collapse into one.
	transport := classifyFailure(errors.New("dial tcp 127.0.0.1:9: connect: connection refused"))
	if transport.Code != "network" {
		t.Fatalf("code = %q, want network", transport.Code)
	}
}

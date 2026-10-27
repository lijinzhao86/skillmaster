package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

// A machine with no credential and no browser gets a refusal, not half a journey.
//
// v17 acceptance 1 says it in one line: `startup` must not pretend. It also has to say **both** ways
// in — the browser, or a key somebody already has — because the two audiences are a new user at a
// terminal and an agent on a box nobody is sitting at.
func TestStartupRefusesWithoutACredentialAndNamesBothWays(t *testing.T) {
	t.Setenv(config.ApiKeyEnv, "")
	original := browserAvailable
	browserAvailable = func() bool { return false }
	t.Cleanup(func() { browserAvailable = original })

	err := startup(context.Background(), nil)
	if err == nil {
		t.Fatal("startup succeeded with no credential")
	}
	if got := reportError(err); got != 4 {
		t.Errorf("exit code = %d, want 4 (the credential class)", got)
	}
	for _, want := range []string{"skillmaster login", config.ApiKeyEnv} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("the refusal does not name %q:\n%s", want, err)
		}
	}
}

// The journey is a skill on the platform (ADR 0096): `startup` loads `skillmaster/startup` and
// prints its body verbatim — the text is the answer, not a summary of it.
func TestStartupPrintsTheSkillBody(t *testing.T) {
	const body = "# SkillMaster 入门\n\n一条链，不是菜单。\n"
	server := startupServer(t, body)

	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.ApiKeyEnv, "skm_livekey123456")

	out := captureStdout(t, func() {
		if err := startup(context.Background(), nil); err != nil {
			t.Fatalf("startup: %v", err)
		}
	})
	if !strings.HasSuffix(out, body) {
		t.Errorf("startup printed %q, want it to end with the body verbatim %q", out, body)
	}
}

// A journey that is not there has to be a sentence — naming where it lives and what to check — and
// never a page printed as if it were instructions. **No fallback text**: a copy in this binary is
// the second home ADR 0096 exists to prevent.
func TestStartupSaysWhereTheJourneyLivesWhenItIsMissing(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/v1/skills/") {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"code":"not_found","message":"not here"}`))
			return
		}
		http.NotFound(w, r)
	}))
	t.Cleanup(server.Close)

	t.Setenv("HOME", t.TempDir())
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.ApiKeyEnv, "skm_livekey123456")

	err := startup(context.Background(), nil)
	if err == nil {
		t.Fatal("startup succeeded with no journey to load")
	}
	for _, want := range []string{startupSkill, "控制台"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("the refusal does not name %q:\n%s", want, err)
		}
	}
	if strings.Contains(err.Error(), "# SkillMaster") {
		t.Errorf("startup invented a journey instead of reporting the failure:\n%s", err)
	}
}

// startupServer serves the two calls `startup` makes: the version the address resolves to, then the
// bytes at that version.
func startupServer(t *testing.T, body string) *httptest.Server {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/skills/skillmaster/startup/metadata":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"name":"startup","namespace":{"slug":"skillmaster"},` +
				`"version":{"name":"1.1.0","digest":"sha256:abc"}}`))
		case "/api/v1/skills/skillmaster/startup/body":
			w.Header().Set("Content-Type", "text/markdown; charset=utf-8")
			_, _ = w.Write([]byte(body))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(server.Close)
	t.Setenv("HOME", t.TempDir())
	return server
}

// **A state directory that cannot be written does not withhold the journey** (v24 acceptance 10).
//
// The pin is this machine's note of which version it read; the body is already in hand. Failing the
// command over the note would turn a read-only `$HOME` into "startup does not work" — the exact
// sentence v22 removed. The warning names the record's directory, and nothing is deleted on the way.
func TestStartupWarnsAndCarriesOnWhenTheStateDirectoryIsReadOnly(t *testing.T) {
	if runtime.GOOS == "windows" || os.Geteuid() == 0 {
		t.Skip("a 0500 directory does not stop this user")
	}
	const body = "# SkillMaster 入门\n\n一条链，不是菜单。\n"
	server := startupServer(t, body)
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.ApiKeyEnv, "skm_livekey123456")

	dir, err := config.Dir()
	if err != nil {
		t.Fatalf("Dir: %v", err)
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.Chmod(dir, 0o500); err != nil {
		t.Fatalf("chmod: %v", err)
	}
	t.Cleanup(func() { _ = os.Chmod(dir, 0o700) })

	stderr := captureStderr(t, func() {
		if err := startup(context.Background(), nil); err != nil {
			t.Fatalf("startup failed over an unwritable state directory: %v", err)
		}
	})
	for _, want := range []string{"没能记下这一版", filepath.Join(dir, "pins")} {
		if !strings.Contains(stderr, want) {
			t.Errorf("the warning does not name %q:\n%s", want, stderr)
		}
	}
}

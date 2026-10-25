package main

import (
	"context"
	"net/http"
	"net/http/httptest"
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

// With a credential the body is printed verbatim — the document is the answer, not a summary of it.
func TestStartupPrintsTheBodyItFetched(t *testing.T) {
	const body = "# SkillMaster 入门旅程\n\n服务地址：`https://skills.example.test`\n"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/gateway/STARTUP.md" {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(server.Close)

	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.ApiKeyEnv, "skm_livekey123456")

	out := captureStdout(t, func() {
		if err := startup(context.Background(), nil); err != nil {
			t.Fatalf("startup: %v", err)
		}
	})
	if out != body {
		t.Errorf("startup printed %q, want the body verbatim %q", out, body)
	}
}

// A reverse proxy that answers unknown paths with the SPA's index pages is not a journey, and the
// failure has to be a sentence rather than a page printed as if it were instructions.
func TestStartupRefusesAWebPage(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		_, _ = w.Write([]byte("<!doctype html>\n<html><body>SkillMaster</body></html>\n"))
	}))
	t.Cleanup(server.Close)

	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.ApiKeyEnv, "skm_livekey123456")

	err := startup(context.Background(), nil)
	if err == nil {
		t.Fatal("startup printed a web page as the journey")
	}
	if !strings.Contains(err.Error(), "网页") {
		t.Errorf("the refusal does not say what arrived:\n%s", err)
	}
}

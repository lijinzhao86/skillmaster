package main

import (
	"os"
	"strings"
	"testing"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/auth"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

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
				Username:    "alice",
				AccessToken: "a-login-token",
				ExpiresAt:   time.Date(2026, 10, 23, 10, 0, 0, 0, time.UTC),
				Scopes:      []string{"skills:read", "skills:write"},
			})
	})
	if err != nil {
		t.Fatalf("saveAndReport: %v", err)
	}
	for _, want := range []string{"已登录 https://api.example", "已登录为 alice", "我的 MacBook",
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

// The login report's account line (v46). It lives beside the keys tests' `stubStore` rather than
// inside them: the file-size guard refuses a shared test file that only grows.

// **An older server does not send the account, and the report says less rather than guessing**
// (v46 验收 A6). The rest of the login is unchanged, including the fact that it succeeded.
func TestTheLoginReportOmitsAnAccountTheServerDidNotSend(t *testing.T) {
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
	if strings.Contains(out, "已登录为") {
		t.Fatalf("the report invented an account the server did not send:\n%s", out)
	}
	if !strings.Contains(out, "已登录 https://api.example") {
		t.Fatalf("a login without an account name stopped being reported:\n%s", out)
	}
}

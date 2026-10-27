package main

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// The process status is the public contract (ADR 0087), so it is asserted where it is decided —
// `reportError` — rather than inferred from a handler's return value. Each row is one of the
// classes the contract names, including the near-misses that must stay 1 (test-plan T12 / T13).
func TestReportErrorStatus(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want int
	}{
		{"usage", usagef("要接一个地址。%s", usageAt("skill show")), 2},
		{"credential", &credentialError{message: "本机没有凭据"}, 4},
		{"refusal", &refusalError{message: "这个动作不在 CLI 做"}, 1},
		{"an ordinary failure", errors.New("something broke"), 1},

		// The four 401s mean "replace the credential".
		{"unauthenticated", &api.Refusal{Code: "unauthenticated"}, 4},
		{"token_idle", &api.Refusal{Code: "token_idle"}, 4},
		{"token_expired", &api.Refusal{Code: "token_expired"}, 4},
		{"token_revoked", &api.Refusal{Code: "token_revoked"}, 4},

		// And these look similar but are not that: a new credential would not help, or we cannot
		// tell what the server meant.
		{"account_suspended", &api.Refusal{Code: "account_suspended"}, 1},
		{"401 with no code", &api.Refusal{}, 1},
		{"401 with an unknown code", &api.Refusal{Code: "something_new"}, 1},

		// The version group flattens every error into a cliFailure, which has no Unwrap: the status
		// has to survive that flattening. The credential case is the one that used to be lost there
		// — a 19-command family answered 1 where the rest answered 4.
		{"flattened credential", versionErr(false, &credentialError{message: "not a key"}, ""), 4},
		{"flattened 401", versionErr(false, &api.Refusal{Code: "token_expired"}, ""), 4},
		{"flattened usage", versionErr(false, usagef("坏参数"), ""), 2},
		{"flattened ordinary", versionErr(false, errors.New("something broke"), ""), 1},
	}
	for _, c := range cases {
		var status int
		stderr := captureStderr(t, func() { status = reportError(c.err) })
		if status != c.want {
			t.Errorf("%s: status %d, want %d (stderr %q)", c.name, status, c.want, stderr)
		}
	}
}

// `auth status` keeps the server's own word for why the credential was refused: "token_expired"
// says renewal, "account_suspended" says nobody can fix it from here (验收 #11). The code is in
// the sentence *and* in the exit status — the sentence is for the person, the status for a script.
func TestAuthStatusKeepsTheServersCode(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":{"code":"token_expired","message":"This credential has expired."}}`))
	}))
	t.Cleanup(server.Close)

	store := credentials.EnvStore{Fallback: &stubStore{found: true, creds: credentials.Credentials{
		AccessToken: "skm_live_abcdef123456",
	}}}
	var err error
	var status int
	stderr := captureStderr(t, func() {
		err = authStatusWith(context.Background(), server.URL, store, "")
		if err == nil {
			t.Fatal("a 401 was reported as success")
		}
		status = reportError(err)
	})
	if status != 4 {
		t.Errorf("status = %d, want 4", status)
	}
	if !strings.Contains(stderr, "token_expired") {
		t.Errorf("stderr %q does not name the code", stderr)
	}
}

// A store that cannot be read is not an empty store (验收 #11). The credential may still be there,
// so the answer is "fix this machine" and exit 1 — not "sign in", and never a clean bill of health.
type unreadableStore struct{}

func (unreadableStore) Load() (credentials.Credentials, bool, error) {
	return credentials.Credentials{}, false, errors.New("keychain is locked")
}

func (unreadableStore) Save(credentials.Credentials) error { return nil }
func (unreadableStore) Clear() error                       { return nil }
func (unreadableStore) Where() string                      { return "测试钥匙串" }

func TestAuthStatusCannotReadThisMachinesCredential(t *testing.T) {
	t.Setenv(config.ApiKeyEnv, "")
	t.Setenv("HOME", t.TempDir())

	var err error
	captureStderr(t, func() {
		err = authStatusWith(context.Background(), "http://127.0.0.1:1",
			credentials.EnvStore{Fallback: unreadableStore{}}, "")
	})
	if err == nil {
		t.Fatal("an unreadable store was reported as an empty one")
	}
	var status int
	stderr := captureStderr(t, func() { status = reportError(err) })
	if status != 1 {
		t.Errorf("status %d, want 1 (stderr %q)", status, stderr)
	}
	if !strings.Contains(stderr, "读取本机凭据") {
		t.Errorf("the sentence does not say what failed: %q", stderr)
	}
}

// A refusal with no code still prints the generic sentence rather than an empty one.
func TestReportErrorWithoutACodeStillSaysSomething(t *testing.T) {
	stderr := captureStderr(t, func() { reportError(&api.Refusal{Body: "unauthorized"}) })
	if strings.TrimSpace(stderr) == "" {
		t.Fatal("a codeless 401 printed nothing")
	}
}

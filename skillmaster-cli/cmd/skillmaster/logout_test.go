// `logout`'s server half (v46, ADR 0142): what it ends, what it does not, and what it says when the
// server cannot be reached.

package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// aLoginCredential is the shape `skmr login` writes: a name, a date, scopes, and the saved-at stamp
// that tells it apart from the leftovers of older logins (`auth.LeftoverOf`).
func aLoginCredential() *stubStore {
	return &stubStore{found: true, creds: credentials.Credentials{
		Name:        "我的 MacBook",
		AccessToken: "a-login-token",
		ExpiresAt:   time.Now().Add(time.Hour),
		Scope:       "skills:read skills:write",
		SavedAt:     time.Now(),
	}}
}

// endingServer stands in for `POST /api/v1/logout` and records what was asked of it.
type endingServer struct {
	*httptest.Server
	method string
	path   string
	auth   string
}

func newEndingServer(t *testing.T, answer func(http.ResponseWriter)) *endingServer {
	t.Helper()
	ending := &endingServer{}
	ending.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ending.method, ending.path, ending.auth = r.Method, r.URL.Path, r.Header.Get("Authorization")
		_, _ = io.Copy(io.Discard, r.Body)
		answer(w)
	}))
	t.Cleanup(ending.Close)
	return ending
}

// A CLI login is ended on the server too, and the console pointer disappears with it.
func TestLogoutEndsTheServerLoginItHolds(t *testing.T) {
	t.Setenv(config.WebURLEnv, "https://web.example")
	ending := newEndingServer(t, func(w http.ResponseWriter) { w.WriteHeader(http.StatusNoContent) })
	store := aLoginCredential()

	out := captureStdout(t, func() {
		if err := logoutWithServer(credentials.EnvStore{Fallback: store}, ending.URL, nil); err != nil {
			t.Fatalf("logout: %v", err)
		}
	})

	if ending.method != http.MethodPost || ending.path != "/api/v1/logout" {
		t.Fatalf("asked %s %s, want POST /api/v1/logout", ending.method, ending.path)
	}
	if ending.auth != "Bearer a-login-token" {
		t.Fatalf("Authorization = %q, want the credential this machine held", ending.auth)
	}
	if !strings.Contains(out, "已清除本地凭据") {
		t.Fatalf("logout does not say what it cleared:\n%s", out)
	}
	if !strings.Contains(out, "服务端那份也已结束") {
		t.Fatalf("logout does not say what it ended on the server:\n%s", out)
	}
	// The console sentence is the fallback for a failed ending, not a second telling of the same fact.
	if strings.Contains(out, "/account/sessions") {
		t.Fatalf("a successful ending still points at the console:\n%s", out)
	}
	if store.deletes != 1 {
		t.Fatalf("the local credential was deleted %d times, want 1", store.deletes)
	}
}

// **A key is not sent to the login route.** It has no login to end, and the console is where it is
// revoked (ADR 0038 §决定 7, 验收 A12).
func TestLogoutDoesNotSendAKeyToTheLoginRoute(t *testing.T) {
	t.Setenv(config.WebURLEnv, "https://web.example")
	ending := newEndingServer(t, func(w http.ResponseWriter) { w.WriteHeader(http.StatusNoContent) })
	// **The stamp is the point.** A key saved by an older `login --with-key` carries `SavedAt`, so
	// the leftover shape alone would call it a CLI login. Only the key's prefix tells them apart.
	store := &stubStore{found: true, creds: credentials.Credentials{
		AccessToken: aKey("stored"),
		SavedAt:     time.Now(),
	}}

	out := captureStdout(t, func() {
		if err := logoutWithServer(credentials.EnvStore{Fallback: store}, ending.URL, nil); err != nil {
			t.Fatalf("logout: %v", err)
		}
	})

	if ending.path != "" {
		t.Fatalf("a key was sent to %s, want no request at all", ending.path)
	}
	if !strings.Contains(out, "https://web.example/account/keys") {
		t.Fatalf("the key's console address went missing:\n%s", out)
	}
	if store.deletes != 1 {
		t.Fatalf("the local key was deleted %d times, want 1", store.deletes)
	}
}

// **The local clear is the contract; the server is the report.** A refusal leaves the machine clean
// and says plainly that the server's copy is still there (验收 A13).
func TestLogoutStillClearsLocallyWhenTheServerRefuses(t *testing.T) {
	t.Setenv(config.WebURLEnv, "https://web.example")
	ending := newEndingServer(t, func(w http.ResponseWriter) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"error":{"code":"internal_error","message":"boom"}}`))
	})
	store := aLoginCredential()

	var err error
	out := captureStdout(t, func() {
		err = logoutWithServer(credentials.EnvStore{Fallback: store}, ending.URL, nil)
	})
	if err != nil {
		t.Fatalf("a failed server call failed the command: %v", err)
	}
	if store.deletes != 1 {
		t.Fatalf("the local credential was deleted %d times, want 1", store.deletes)
	}
	if !strings.Contains(out, "服务端那份还在") {
		t.Fatalf("logout does not say the server's copy is still there:\n%s", out)
	}
	if !strings.Contains(out, "https://web.example/account/sessions") {
		t.Fatalf("a failed ending does not say where to end it:\n%s", out)
	}
	if strings.Contains(out, "已结束") {
		t.Fatalf("logout claimed an ending that did not happen:\n%s", out)
	}
}

// The login was already over when the command ran — an ending, not an error (Q3).
//
// **Three names, one answer.** Revoked, expired and idle all mean the server has no usable login to
// end, and the console's list filters by the credential's own expiry — so the console pointer would
// lead to a row that is not there.
func TestLogoutTreatsAnAlreadyEndedLoginAsEnded(t *testing.T) {
	t.Setenv(config.WebURLEnv, "https://web.example")

	for _, code := range []string{"token_revoked", "token_expired", "token_idle"} {
		t.Run(code, func(t *testing.T) {
			ending := newEndingServer(t, func(w http.ResponseWriter) {
				w.WriteHeader(http.StatusUnauthorized)
				_, _ = w.Write([]byte(`{"error":{"code":"` + code + `","message":"over"}}`))
			})
			store := aLoginCredential()

			out := captureStdout(t, func() {
				if err := logoutWithServer(credentials.EnvStore{Fallback: store}, ending.URL, nil); err != nil {
					t.Fatalf("logout: %v", err)
				}
			})

			if !strings.Contains(out, "服务端那份也已结束") {
				t.Fatalf("an already-ended login was not reported as ended:\n%s", out)
			}
			if strings.Contains(out, "/account/sessions") {
				t.Fatalf("an ended login still points at the console:\n%s", out)
			}
			if store.deletes != 1 {
				t.Fatalf("the local credential was deleted %d times, want 1", store.deletes)
			}
		})
	}
}

// **A server that cannot be reached at all is the same report as one that refuses.** The local
// clear is the contract; the sentence says the server's copy is still there and where to end it.
func TestLogoutStillClearsLocallyWhenTheServerIsUnreachable(t *testing.T) {
	t.Setenv(config.WebURLEnv, "https://web.example")
	store := aLoginCredential()

	// A closed listener: the request fails before any HTTP answer exists.
	ending := newEndingServer(t, func(http.ResponseWriter) {})
	ending.Close()

	out := captureStdout(t, func() {
		if err := logoutWithServer(credentials.EnvStore{Fallback: store}, ending.URL, nil); err != nil {
			t.Fatalf("an unreachable server failed the command: %v", err)
		}
	})

	if store.deletes != 1 {
		t.Fatalf("the local credential was deleted %d times, want 1", store.deletes)
	}
	if !strings.Contains(out, "服务端那份还在") {
		t.Fatalf("logout does not say the server's copy is still there:\n%s", out)
	}
	if !strings.Contains(out, "https://web.example/account/sessions") {
		t.Fatalf("an unreachable server does not say where to end the login:\n%s", out)
	}
}

// **The environment's key does not save the stored login from being ended** (Q4): what this command
// removes is the stored credential, and the key it cannot remove is named separately.
func TestLogoutEndsTheStoredLoginEvenWhenAKeyIsInTheEnvironment(t *testing.T) {
	t.Setenv(config.WebURLEnv, "https://web.example")
	ending := newEndingServer(t, func(w http.ResponseWriter) { w.WriteHeader(http.StatusNoContent) })
	stored := aLoginCredential()

	out := captureStdout(t, func() {
		if err := logoutWithServer(credentials.EnvStore{
			Var: config.ApiKeyEnv, Value: aKey("from-env"), Fallback: stored,
		}, ending.URL, nil); err != nil {
			t.Fatalf("logout: %v", err)
		}
	})

	if ending.auth != "Bearer a-login-token" {
		t.Fatalf("Authorization = %q, want the stored login's token", ending.auth)
	}
	if !strings.Contains(out, "服务端那份也已结束") {
		t.Fatalf("the stored login's ending went unmentioned:\n%s", out)
	}
	if !strings.Contains(out, "https://web.example/account/keys") {
		t.Fatalf("the environment's key was not pointed at the console:\n%s", out)
	}
	if stored.deletes != 1 {
		t.Fatalf("the stored login was deleted %d times, want 1", stored.deletes)
	}
}

package api

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

// serve answers every request with one body, and counts what was asked of it.
func serve(t *testing.T, status int, body string) (*httptest.Server, *[]string) {
	t.Helper()
	asked := &[]string{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		*asked = append(*asked, r.URL.Path)
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(server.Close)
	return server, asked
}

// The two answers `/me` can give, and the null that is one of them.
//
// `key` being null is what says "this credential is not an API key", and the CLI reports that rather
// than inventing a name — so which of the two shapes comes back has to be readable from the parsed
// value alone, with no side channel saying which endpoint was called.
func TestMeReadsBothShapesOfAnswer(t *testing.T) {
	withKey := `{"user":{"user_id":"u1","username":"owner","namespace":"owner"},
		"key":{"name":"笔记本","scope":"skills:read skills:write",
		"expires_at":"2027-01-13T15:59:59Z","last_used_at":null}}`
	server, asked := serve(t, http.StatusOK, withKey)

	me, err := Client{BaseURL: server.URL, Token: "skm_x"}.Me(context.Background())
	if err != nil {
		t.Fatalf("Me: %v", err)
	}
	if got := me.User.Username; got != "owner" {
		t.Fatalf("username = %q", got)
	}
	if me.Key == nil {
		t.Fatal("a key's answer came back with no key")
	}
	if me.Key.Name != "笔记本" || me.Key.Scope != "skills:read skills:write" {
		t.Fatalf("key = %+v", *me.Key)
	}
	// Null and absent are the same answer here, and both mean "never used" — the CLI turns it into a
	// sentence rather than printing an empty string where a time should be.
	if me.Key.LastUsedAt != "" {
		t.Fatalf("last_used_at = %q, want empty", me.Key.LastUsedAt)
	}
	if len(*asked) != 1 || (*asked)[0] != "/api/v1/me" {
		t.Fatalf("asked %v, want one request to /api/v1/me", *asked)
	}

	oauth := `{"user":{"user_id":"u1","username":"owner","namespace":"owner"},"key":null}`
	server2, _ := serve(t, http.StatusOK, oauth)
	me2, err := Client{BaseURL: server2.URL, Token: "a-token"}.Me(context.Background())
	if err != nil {
		t.Fatalf("Me: %v", err)
	}
	if me2.Key != nil {
		t.Fatal("a null key was parsed as a key")
	}
}

// A 401 that carries a reason arrives as a `Refusal` **and** as ErrUnauthorized.
//
// Both, because the two kinds of caller exist: the layer holding the store only knows "the credential
// was refused" and has to keep working, while the command that talks to a person branches on which
// kind of no it was (ADR 0039 §决定 3). The reason is only reachable through the type; the ordinary
// check is only reachable through the sentinel.
func TestA401KeepsItsReasonAndItsSentinel(t *testing.T) {
	server, _ := serve(t, http.StatusUnauthorized,
		`{"error":{"code":"token_revoked","message":"This API key has been revoked.","details":[]}}`)

	_, err := Client{BaseURL: server.URL, Token: "skm_x"}.Me(context.Background())
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("err = %v, want ErrUnauthorized", err)
	}
	var refusal *Refusal
	if !errors.As(err, &refusal) {
		t.Fatalf("err = %v, want a *Refusal", err)
	}
	if refusal.Code != "token_revoked" {
		t.Fatalf("Code = %q, want the server's own reason", refusal.Code)
	}
}

// A 401 from something in front of the application has no envelope, and that is not a failure: the
// status is the fact, and the code is simply absent. Reporting an unreadable body as a code would
// make a proxy's HTML error page look like a reason the server chose.
func TestA401WithoutAnEnvelopeHasNoCode(t *testing.T) {
	server, _ := serve(t, http.StatusUnauthorized, "<html>401</html>")

	_, err := Client{BaseURL: server.URL, Token: "skm_x"}.Me(context.Background())
	var refusal *Refusal
	if !errors.As(err, &refusal) {
		t.Fatalf("err = %v, want a *Refusal", err)
	}
	if refusal.Code != "" {
		t.Fatalf("Code = %q, want empty", refusal.Code)
	}
	// And the message is still the one a 401 produced before reasons existed, with the body kept —
	// an operator greps a log for what the server sent, and dropping it loses that.
	if refusal.Body != "<html>401</html>" {
		t.Fatalf("Body = %q, want the body as it arrived", refusal.Body)
	}
}

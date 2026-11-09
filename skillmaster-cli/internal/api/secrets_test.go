package api

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// **The lease is the only response that carries a value**, so it is the one that has to be read
// exactly: one wrong key here and a skill receives nothing with no error anywhere.
func TestLeaseCredentialsReadsValuesAndTheirNames(t *testing.T) {
	var asked string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		asked = r.Method + " " + r.URL.Path
		_, _ = w.Write([]byte(`{"values":[{"env":"ZHIHU_COOKIE","value":"a=b;c"},` +
			`{"env":"KEY_2","value":"pem\nline"}],"expires_at":"2026-11-08T00:05:00Z"}`))
	}))
	t.Cleanup(server.Close)

	client := Client{BaseURL: server.URL, Token: "skm_test"}
	lease, err := client.LeaseCredentials(context.Background(), "dev/zhihu")
	if err != nil {
		t.Fatalf("LeaseCredentials: %v", err)
	}
	if want := "POST /api/v1/skills/dev/zhihu/credentials/lease"; asked != want {
		t.Errorf("asked %q, want %q", asked, want)
	}
	if len(lease.Values) != 2 || lease.Values[0].Value != "a=b;c" || lease.Values[1].Env != "KEY_2" {
		t.Fatalf("lease = %+v", lease)
	}
	pairs := lease.EnvPairs()
	if len(pairs) != 2 || pairs[0] != "ZHIHU_COOKIE=a=b;c" || pairs[1] != "KEY_2=pem\nline" {
		t.Errorf("pairs = %q", pairs)
	}
}

// A name the child would ignore is refused here rather than silently dropped: "the key did not
// work" is the failure nobody can see.
func TestLeaseCredentialsRefusesAnImpossibleVariableName(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"values":[{"env":"2BAD-NAME","value":"v"}]}`))
	}))
	t.Cleanup(server.Close)

	client := Client{BaseURL: server.URL, Token: "skm_test"}
	_, err := client.LeaseCredentials(context.Background(), "dev/zhihu")
	if err == nil || !strings.Contains(err.Error(), "2BAD-NAME") {
		t.Fatalf("err = %v", err)
	}
}

func TestValidEnvName(t *testing.T) {
	for _, name := range []string{"KEY", "_K", "A1_b"} {
		if !validEnvName(name) {
			t.Errorf("%q was refused", name)
		}
	}
	for _, name := range []string{"", "1K", "K-EY", "K EY", "键"} {
		if validEnvName(name) {
			t.Errorf("%q was accepted", name)
		}
	}
}

package sandbox

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func findCheck(t *testing.T, report Report, name string) Check {
	t.Helper()
	for _, check := range report.Checks {
		if check.Name == name {
			return check
		}
	}
	t.Fatalf("report has no %q check: %+v", name, report.Checks)
	return Check{}
}

func TestProbeReportsAReachablePlatform(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer server.Close()

	report := Probe(context.Background(), Options{
		BaseURL:        server.URL,
		StateDir:       t.TempDir(),
		CredentialPath: filepath.Join(t.TempDir(), "credentials"),
		Server:         "https://example.test",
		Timeout:        time.Second,
	})
	check := findCheck(t, report, "platform")
	if !check.OK {
		t.Fatalf("platform check failed: %+v", check)
	}
	if !strings.Contains(check.Detail, "401") {
		t.Errorf("detail = %q, want the HTTP status", check.Detail)
	}
}

func TestProbeReportsAnUnreachablePlatform(t *testing.T) {
	report := Probe(context.Background(), Options{
		BaseURL:        "http://127.0.0.1:1",
		StateDir:       t.TempDir(),
		CredentialPath: filepath.Join(t.TempDir(), "credentials"),
		Server:         "https://example.test",
		Timeout:        time.Second,
	})
	check := findCheck(t, report, "platform")
	if check.OK {
		t.Fatalf("platform check passed against a closed port: %+v", check)
	}
	if check.Hint == "" {
		t.Errorf("a refused connection got no hint: %+v", check)
	}
}

func TestProbeLoopbackWorksHere(t *testing.T) {
	if Detect().Sandboxed {
		t.Skip("this test is running inside a sandbox, which is what the probe is for")
	}
	check := findCheck(t, Probe(context.Background(), Options{
		StateDir:       t.TempDir(),
		CredentialPath: filepath.Join(t.TempDir(), "credentials"),
		Server:         "https://example.test",
	}), "loopback")
	if !check.OK {
		t.Fatalf("loopback check failed: %+v", check)
	}
}

func TestProbeStateExplainsAnUnwritableDirectory(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "not-a-directory")
	if err := os.WriteFile(file, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	check := findCheck(t, Probe(context.Background(), Options{
		StateDir:       file,
		CredentialPath: filepath.Join(t.TempDir(), "credentials"),
		Server:         "https://example.test",
	}), "state")
	if check.OK {
		t.Fatalf("state check passed against a file: %+v", check)
	}
	if check.Hint == "" || !strings.Contains(check.Hint, "可写") {
		t.Errorf("state failure has no actionable hint: %+v", check)
	}
}

func TestProbeWritesAStateMarker(t *testing.T) {
	dir := t.TempDir()
	check := findCheck(t, Probe(context.Background(), Options{
		StateDir:       dir,
		CredentialPath: filepath.Join(t.TempDir(), "credentials"),
		Server:         "https://example.test",
	}), "state")
	if !check.OK {
		t.Fatalf("state check failed: %+v", check)
	}
	if _, err := os.Stat(filepath.Join(dir, ".sandbox-probe")); err != nil {
		t.Errorf("the probe did not leave its marker: %v", err)
	}
}

func TestProbeReportsWhereCredentialsLive(t *testing.T) {
	check := findCheck(t, Probe(context.Background(), Options{
		StateDir:       t.TempDir(),
		CredentialPath: filepath.Join(t.TempDir(), "credentials"),
		Server:         "https://example.test",
	}), "credentials")
	if !check.OK {
		t.Fatalf("credentials check failed: %+v", check)
	}
	if check.Detail == "" {
		t.Error("credentials check has no location")
	}
}

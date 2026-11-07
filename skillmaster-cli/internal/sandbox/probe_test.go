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

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
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

// With no explicit path the location comes from config, and it carries both halves: the write path
// and the older copy a sandboxed read may fall back to. Dropping either is the regression this
// covers — the probe used to take a bare string, before the store's Location existed.
func TestCredentialLocationResolvesBothPaths(t *testing.T) {
	home := t.TempDir()
	t.Setenv("SKILLMASTER_HOME", home)
	const server = "https://example.test"

	ordinary := t.TempDir()
	t.Setenv("HOME", ordinary)
	// Linux reads XDG_CONFIG_HOME ahead of HOME; without this the test would write into the
	// developer's real config directory.
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(ordinary, "config"))
	t.Setenv("SKILLMASTER_HOME", "")
	ordinaryWrite, _, err := config.CredentialPaths(server)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Dir(ordinaryWrite), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(ordinaryWrite, []byte("{}"), 0o600); err != nil {
		t.Fatal(err)
	}

	t.Setenv("SKILLMASTER_HOME", home)
	location, err := credentialLocation("", server)
	if err != nil {
		t.Fatal(err)
	}
	if location.Path == "" || location.Path == ordinaryWrite {
		t.Errorf("write path = %q, want the state directory from SKILLMASTER_HOME", location.Path)
	}
	if location.Older != ordinaryWrite {
		t.Errorf("older copy = %q, want the one that exists (%q)", location.Older, ordinaryWrite)
	}
}

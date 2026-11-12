package main

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/agentconfig"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// The `--keyring` half of the seam main_test.go sets up. Without it, a future test that drives
// `login --keyring` reaches this machine's real keychain (and on macOS, a dialog).
func init() {
	machineKeyringStore = func(credentials.Location, string) (credentials.Store, string) {
		return &stubStore{}, ""
	}
}

func TestParseDoctorArgs(t *testing.T) {
	cases := []struct {
		args    []string
		fix     bool
		undo    bool
		dryRun  bool
		wantErr bool
	}{
		{nil, false, false, false, false},
		{[]string{"--fix"}, true, false, false, false},
		{[]string{"--fix", "--undo"}, true, true, false, false},
		{[]string{"--fix", "--dry-run"}, true, false, true, false},
		{[]string{"--undo"}, false, false, false, true},
		{[]string{"--dry-run"}, false, false, false, true},
		{[]string{"everything"}, false, false, false, true},
	}
	for _, c := range cases {
		fix, undo, dryRun, err := parseDoctorArgs(c.args)
		if (err != nil) != c.wantErr {
			t.Errorf("parseDoctorArgs(%v) error = %v, wantErr %v", c.args, err, c.wantErr)
			continue
		}
		if fix != c.fix || undo != c.undo || dryRun != c.dryRun {
			t.Errorf("parseDoctorArgs(%v) = (%v,%v,%v)", c.args, fix, undo, dryRun)
		}
	}
}

// A write failure is a failure with a sentence, not a line that starts with a colon and exits 0.
func TestDescribeChangeFailure(t *testing.T) {
	got := describeChange(agentconfig.Change{
		Client: "Claude Code",
		Err:    errors.New("permission denied"),
	}, false, false)
	if !strings.HasPrefix(got, "没配上：") || !strings.Contains(got, "permission denied") {
		t.Fatalf("describeChange = %q", got)
	}
}

// PRD #10's guidance exists even when no client was written.
func TestNoConfigSurfaceLinesNameTheClients(t *testing.T) {
	text := strings.Join(noConfigSurfaceLines(), "\n")
	for _, name := range []string{"Cline", "Roo Code", "Aider", "Amp", "旧 Windsurf", "OpenHands", "DSH", "GrokBot"} {
		if !strings.Contains(text, name) {
			t.Fatalf("guidance does not name %s:\n%s", name, text)
		}
	}
}

// A write failure is counted; a deliberate skip is not.
func TestFailedChanges(t *testing.T) {
	changes := []agentconfig.Change{
		{Client: "a"},
		{Client: "b", Skip: "没有安装"},
		{Client: "c", Err: errors.New("permission denied")},
	}
	if got := failedChanges(changes); got != 1 {
		t.Fatalf("failedChanges = %d, want 1", got)
	}
}

// "Some clients failed" maps to a non-zero status; none failed maps to success.
func TestFixFailure(t *testing.T) {
	if fixFailure(0) != nil {
		t.Fatalf("fixFailure(0) returned an error")
	}
	if fixFailure(2) == nil {
		t.Fatalf("fixFailure(2) returned nil")
	}
}

// The clients whose grant is a total network switch are named, so the cost can be printed — both
// the ones this run switched on and the ones that were already on.
func TestNetworkSwitchClients(t *testing.T) {
	switched, already := networkSwitchClients([]agentconfig.Change{
		{Client: "Codex", Processed: true, Toggled: []string{"network_access", "sandbox_mode"}},
		{Client: "Gemini", Processed: true, Toggled: []string{"networkAccess"}},
		{Client: "Claude Code", Processed: true, Toggled: []string{"allowLocalBinding"}},
		{Client: "Codex", Toggled: []string{"network_access"}}, // not processed: not a finding
	})
	if strings.Join(switched, ",") != "Codex,Gemini" || len(already) != 0 {
		t.Fatalf("networkSwitchClients = %v, %v", switched, already)
	}

	_, already = networkSwitchClients([]agentconfig.Change{{Client: "Gemini", Processed: true}})
	if strings.Join(already, ",") != "Gemini" {
		t.Fatalf("already = %v", already)
	}
}

// A zero-byte file is the logout tombstone, not a credential.
func TestCredentialFilePresent(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "cred")
	if err := os.WriteFile(path, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if !credentialFilePresent(path) {
		t.Fatalf("a non-empty file reads as absent")
	}
	if err := os.WriteFile(path, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if credentialFilePresent(path) {
		t.Fatalf("the tombstone reads as a credential")
	}
	if credentialFilePresent(filepath.Join(dir, "missing")) {
		t.Fatalf("a missing file reads as present")
	}
}

func TestParseLoginArgs(t *testing.T) {
	if keyring, err := parseLoginArgs(nil); err != nil || keyring {
		t.Errorf("plain login = %v, %v", keyring, err)
	}
	if keyring, err := parseLoginArgs([]string{"--keyring"}); err != nil || !keyring {
		t.Errorf("login --keyring = %v, %v", keyring, err)
	}
	for _, arg := range []string{"--nope", "a-key"} {
		if _, err := parseLoginArgs([]string{arg}); err == nil {
			t.Errorf("parseLoginArgs(%q) accepted it", arg)
		}
	}
}

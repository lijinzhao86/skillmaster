package main

import (
	"context"
	"net"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// **验收 #21 on the real store, not on a stub.** `TestHelpHasNoSideEffects` proves the decision in
// `logout` — it is handed a store and does not call it. This one proves the wiring: with the real
// `credentials.Open` installed, a credential that is really on this machine is still there after
// `logout --help`.
//
// **It seeds the file store, which is the one every platform has.** A keychain write asks for
// authorization, and a test binary cannot keep that approval (a fresh unsigned binary every run), so
// seeding the keychain would put a dialog on somebody's screen for a test about help — the thing
// `TestMain` exists to prevent. The file is the fallback the real wiring reads whenever the keychain
// has nothing (see `responsiveKeychain.Load`), so this exercises the same path `logout` would take.
// The keychain half runs only when asked for, with the variable named below.
//
// The server address is unique per run and the home directory is a temporary one, so nothing on this
// machine can be affected: the entry is one this test wrote, under a name nothing else uses.
func TestLogoutHelpLeavesTheRealCredentialAlone(t *testing.T) {
	server := unusedServerAddress(t)
	t.Setenv(config.ServerEnv, server)
	t.Setenv("HOME", t.TempDir())
	t.Setenv(config.ApiKeyEnv, "")

	path, err := config.CredentialPath(server)
	if err != nil {
		t.Fatalf("credential path: %v", err)
	}
	credential := credentials.Credentials{
		Name:        "测试设备",
		AccessToken: "skm_side_effect_probe",
		SavedAt:     time.Now(),
	}
	seedFile(t, path, credential)
	t.Cleanup(func() { _ = os.Remove(path) })

	// The real wiring, for the length of this test. Without it the test would prove something about
	// the stub, which is what `TestMain` installs for every other test in the package.
	previous := machineStore
	machineStore = credentials.Open
	t.Cleanup(func() { machineStore = previous })
	if reflect.ValueOf(machineStore).Pointer() != reflect.ValueOf(credentials.Open).Pointer() {
		t.Fatal("the real store is not installed: this test would be about the stub")
	}

	var runErr error
	stdout := captureStdout(t, func() { runErr = run(context.Background(), []string{"logout", "--help"}) })
	if runErr != nil {
		t.Fatalf("logout --help: %v", runErr)
	}
	if !strings.Contains(stdout, "用法：skillmaster logout") {
		t.Fatalf("logout --help did not print its help:\n%s", stdout)
	}

	// Read it back the way the next command would: through the real wiring.
	store, _ := credentials.Open(path, server)
	got, found, err := store.Load()
	if err != nil {
		t.Fatalf("reading the credential back: %v", err)
	}
	if !found {
		t.Fatal("logout --help made the credential unreadable")
	}
	if got.AccessToken != credential.AccessToken {
		t.Fatalf("the credential changed: %q", got.AccessToken)
	}
	if _, err := os.Stat(path); err != nil {
		t.Errorf("the credential file is gone: %v", err)
	}

	// The keychain half, in its own subtest so the file branch above keeps its pass: on macOS this
	// raises an authorization dialog, and CI has no keychain at all.
	// `SKILLMASTER_REAL_KEYCHAIN_TEST=1 go test ./cmd/skillmaster -run TestLogoutHelpLeavesTheRealCredentialAlone`
	t.Run("keychain", func(t *testing.T) {
		if os.Getenv("SKILLMASTER_REAL_KEYCHAIN_TEST") != "1" {
			t.Skip("the keychain branch asks for authorization; set SKILLMASTER_REAL_KEYCHAIN_TEST=1 to run it")
		}
		keychain, _ := credentials.Open(path, server)
		if !strings.Contains(keychain.Where(), "钥匙串") {
			t.Skipf("this machine has no usable keychain (%s)", keychain.Where())
		}
		keychainCredential := credentials.Credentials{AccessToken: "skm_side_effect_probe_keychain", SavedAt: time.Now()}
		if err := keychain.Save(keychainCredential); err != nil {
			t.Skipf("the keychain would not accept a write (%v)", err)
		}
		t.Cleanup(func() { _ = keychain.Delete() })

		var keychainErr error
		captureStdout(t, func() { keychainErr = run(context.Background(), []string{"logout", "--help"}) })
		if keychainErr != nil {
			t.Fatalf("logout --help: %v", keychainErr)
		}
		got, found, err := keychain.Load()
		if err != nil || !found {
			t.Fatalf("the keychain credential is gone after `logout --help` (found=%v, err=%v)", found, err)
		}
		if got.AccessToken != keychainCredential.AccessToken {
			t.Fatalf("the keychain credential changed: %q", got.AccessToken)
		}
	})
}

// seedFile writes the stored form the file store reads. The blob is the same JSON both stores keep
// (see `Credentials.Blob`), so this is the file store's own shape rather than a shortcut past it.
func seedFile(t *testing.T, path string, credential credentials.Credentials) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatalf("create the config directory: %v", err)
	}
	blob, err := credential.Blob()
	if err != nil {
		t.Fatalf("serialise the credential: %v", err)
	}
	if err := os.WriteFile(path, blob, 0o600); err != nil {
		t.Fatalf("write the credential file: %v", err)
	}
}

// unusedServerAddress is an address nothing listens on, unique to this run: the credential stores
// are keyed by the server, so a shared constant would let two runs see each other's entries.
func unusedServerAddress(t *testing.T) string {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("reserve a port: %v", err)
	}
	address := listener.Addr().String()
	_ = listener.Close()
	return "http://" + address
}

package main

import (
	"context"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// driveTheLogin stands in for the browser: what is being pinned is that a command that needs a
// credential runs the login and carries on, not what opening a browser does.
func driveTheLogin(t *testing.T, available bool, start func(store credentials.EnvStore) error) {
	t.Helper()
	// **Clear the sandbox markers.** `clientFor` reads the real environment, and these tests run
	// under `go test` — including from inside Claude Code's own sandbox, where `SANDBOX_RUNTIME=1`
	// is inherited. Without this the gate skips `startLogin` and the login tests fail for a reason
	// that has nothing to do with what they pin.
	for _, name := range []string{"CODEX_SANDBOX", "CURSOR_SANDBOX", "GEMINI_SANDBOX", "SANDBOX_RUNTIME"} {
		t.Setenv(name, "")
	}
	wasBrowser, wasLogin := browserAvailable, startLogin
	browserAvailable = func() bool { return available }
	startLogin = func(_ context.Context, _ string, store credentials.EnvStore, _ string) error {
		return start(store)
	}
	t.Cleanup(func() { browserAvailable, startLogin = wasBrowser, wasLogin })
}

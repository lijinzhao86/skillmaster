// Ctrl-C, at the one place every command sees it (v68).

package main

import (
	"os"
	"runtime"
	"testing"
	"time"
)

// **Ctrl-C has to reach the context, or `login`'s cancellation is dead code.** The signal is sent to
// this very process; `rootContext` installed the handler, so it is delivered to the context instead
// of ending the test run. Windows has no Interrupt signal to send, and the CLI's promise there is
// the same handler — this only pins the wiring, which is shared code.
//
// Its own file rather than a line in `main_test.go`: that file is over the repository's file-size
// ceiling (ADR 0162) and a baseline file may only shrink.
func TestRootContextIsCancelledByAnInterrupt(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("os.Interrupt cannot be sent on Windows")
	}
	ctx, stop := rootContext()
	defer stop()

	process, err := os.FindProcess(os.Getpid())
	if err != nil {
		t.Fatalf("finding this process: %v", err)
	}
	if err := process.Signal(os.Interrupt); err != nil {
		t.Fatalf("sending SIGINT to self: %v", err)
	}

	select {
	case <-ctx.Done():
	case <-time.After(2 * time.Second):
		t.Fatal("SIGINT did not cancel the root context")
	}
}

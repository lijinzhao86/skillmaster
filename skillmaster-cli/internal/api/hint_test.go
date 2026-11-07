package api

import (
	"errors"
	"strings"
	"testing"
)

func TestWithHintAddsTheSandboxSentence(t *testing.T) {
	sentinel := errors.New(`Get "https://console.skillmasterai.com/api/v1/me": ` +
		`tls: failed to verify certificate: x509: OSStatus -26276`)
	wrapped := withHint(sentinel)
	if !errors.Is(wrapped, sentinel) {
		t.Fatal("the original error was not wrapped; callers' errors.Is checks would break")
	}
	if !strings.Contains(wrapped.Error(), "trustd") {
		t.Errorf("no sandbox hint:\n%s", wrapped)
	}
}

func TestWithHintLeavesOtherErrorsAlone(t *testing.T) {
	sentinel := errors.New("something the sandbox package has never seen")
	if got := withHint(sentinel); got != sentinel {
		t.Errorf("withHint rewrote an unrelated error: %v", got)
	}
}

package api

import (
	"errors"
	"strings"
	"testing"
)

func TestWithHintAddsTheSandboxSentence(t *testing.T) {
	sentinel := errors.New(`Get "https://console.skillmasterai.com/api/v1/me": ` +
		`tls: failed to verify certificate: x509: OSStatus -26276`)
	client := Client{BaseURL: "https://console.skillmasterai.com"}
	wrapped := client.withHint(sentinel)
	if !errors.Is(wrapped, sentinel) {
		t.Fatal("the original error was not wrapped; callers' errors.Is checks would break")
	}
	if !strings.Contains(wrapped.Error(), "trustd") {
		t.Errorf("no sandbox hint:\n%s", wrapped)
	}
}

// The sentence names the host this client talks to, not the one configured for the CLI: a test
// server, a mirror or a self-hosted deployment is exactly the case where the two differ.
func TestWithHintNamesTheClientsOwnHost(t *testing.T) {
	t.Setenv("SANDBOX_RUNTIME", "1")
	t.Setenv("SKILLMASTER_SERVER", "https://console.skillmasterai.com")
	client := Client{BaseURL: "https://mirror.example"}
	err := client.withHint(errors.New("Get https://mirror.example/api/v1/me: " +
		"dial tcp: lookup mirror.example: no such host"))
	if !strings.Contains(err.Error(), "到 mirror.example 的连接失败") {
		t.Errorf("the hint does not name the client's host:\n%s", err)
	}
	if strings.Contains(err.Error(), "console.skillmasterai.com") {
		t.Errorf("the hint names the configured server instead:\n%s", err)
	}
}

func TestWithHintLeavesOtherErrorsAlone(t *testing.T) {
	sentinel := errors.New("something the sandbox package has never seen")
	client := Client{BaseURL: "https://console.skillmasterai.com"}
	if got := client.withHint(sentinel); got != sentinel {
		t.Errorf("withHint rewrote an unrelated error: %v", got)
	}
}

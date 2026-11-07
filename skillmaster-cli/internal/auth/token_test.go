package auth

import (
	"context"
	"net"
	"net/http"
	"os"
	"strings"
	"testing"
)

// refusingTransport fails every request the way a sandbox refusing an outbound connection does.
type refusingTransport struct{}

func (refusingTransport) RoundTrip(*http.Request) (*http.Response, error) {
	return nil, &net.OpError{Op: "dial", Net: "tcp", Err: os.ErrPermission}
}

// A revocation endpoint comes from discovery and may be another origin than the platform
// (RFC 8414). The hint has to name the address that failed, or it sends a person to allowlist the
// wrong host.
func TestRevokeNamesTheEndpointThatFailed(t *testing.T) {
	t.Setenv("SANDBOX_RUNTIME", "1")
	t.Setenv("CLAUDECODE", "1")
	t.Setenv("SKILLMASTER_SERVER", "https://console.skillmasterai.com")

	client := &http.Client{Transport: refusingTransport{}}
	err := Revoke(context.Background(), client, "https://idp.example.com/revoke", "skillmaster-cli", "token")
	if err == nil {
		t.Fatal("a refused request returned no error")
	}
	// **The hint sentence, not the host.** The original error already contains the endpoint, so
	// asserting only that would pass with no hint at all — the one regression worth guarding
	// against is a return to a plain wrap.
	if !strings.Contains(err.Error(), "到 idp.example.com 的连接失败") {
		t.Errorf("the failure carries no hint for the endpoint's host:\n%s", err)
	}
	if strings.Contains(err.Error(), "console.skillmasterai.com") {
		t.Errorf("the failure names the platform for a request to another origin:\n%s", err)
	}
}

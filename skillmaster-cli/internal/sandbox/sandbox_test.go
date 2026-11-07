package sandbox

import (
	"errors"
	"net"
	"os"
	"runtime"
	"strings"
	"testing"
)

func clearSandboxMarkers(t *testing.T) {
	t.Helper()
	for _, key := range []string{"CODEX_SANDBOX", "CURSOR_SANDBOX", "GEMINI_SANDBOX", "SANDBOX_RUNTIME", "CLAUDECODE"} {
		t.Setenv(key, "")
	}
}

func TestDetectNamesKnownClients(t *testing.T) {
	cases := []struct {
		name      string
		key       string
		value     string
		want      Client
		sandboxed bool
	}{
		{"codex", "CODEX_SANDBOX", "seatbelt", Codex, true},
		{"cursor", "CURSOR_SANDBOX", "1", Cursor, true},
		{"gemini", "GEMINI_SANDBOX", "docker", Gemini, true},
		{"claude", "CLAUDECODE", "1", ClaudeCode, false},
		{"sandbox-only", "SANDBOX_RUNTIME", "1", Unknown, true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			clearSandboxMarkers(t)
			t.Setenv(c.key, c.value)
			env := Detect()
			if env.Client != c.want {
				t.Errorf("Detect().Client = %q, want %q", env.Client, c.want)
			}
			if env.Sandboxed != c.sandboxed {
				t.Errorf("Detect().Sandboxed = %v, want %v", env.Sandboxed, c.sandboxed)
			}
		})
	}
}

func TestDetectLeavesUnknownAlone(t *testing.T) {
	clearSandboxMarkers(t)
	env := Detect()
	if env.Client != Unknown || env.Sandboxed {
		t.Errorf("Detect() = %+v, want Unknown and not sandboxed", env)
	}
}

func TestHintForTLSTrust(t *testing.T) {
	clearSandboxMarkers(t)
	t.Setenv("CLAUDECODE", "1")
	hint := Hint(errors.New(`Get "https://console.skillmasterai.com/api/v1/me": ` +
		`tls: failed to verify certificate: x509: OSStatus -26276`))
	if !strings.Contains(hint, "trustd") {
		t.Errorf("hint does not name the trustd cause:\n%s", hint)
	}
	if !strings.Contains(hint, "内置公共根证书") {
		t.Errorf("hint does not say the binary carries its own roots:\n%s", hint)
	}
	if !strings.Contains(hint, CAVariable) {
		t.Errorf("hint does not name the private-CA escape hatch:\n%s", hint)
	}
	if strings.Contains(hint, "升级") {
		t.Errorf("hint promises an upgrade, which this beta has no public release for:\n%s", hint)
	}
	if strings.Contains(hint, "关闭客户端沙箱") {
		t.Errorf("hint offers to weaken the sandbox:\n%s", hint)
	}
}

func TestHintForUntrustedCertificateDoesNotBlameTheSandbox(t *testing.T) {
	hint := Hint(errors.New(`Get "https://localhost:8443/": ` +
		`tls: failed to verify certificate: x509: certificate signed by unknown authority`))
	if runtime.GOOS == "darwin" {
		if !strings.Contains(hint, CAVariable) {
			t.Errorf("hint does not name the CA bundle:\n%s", hint)
		}
	} else if !strings.Contains(hint, "系统信任库") {
		t.Errorf("hint does not send this platform to its system trust store:\n%s", hint)
	}
	if strings.Contains(hint, "沙箱") {
		t.Errorf("hint blames the sandbox for a generic certificate failure:\n%s", hint)
	}
	if strings.Contains(hint, "enableWeakerNetworkIsolation") {
		t.Errorf("hint asks for a sandbox setting that no longer helps:\n%s", hint)
	}
}

func TestCertificateHintNamesTheStoreThatActuallyApplies(t *testing.T) {
	if hint := certificateHintOn("darwin"); !strings.Contains(hint, CAVariable) {
		t.Errorf("macOS hint does not name the escape hatch it honours:\n%s", hint)
	} else if strings.Contains(hint, "update-ca-certificates") {
		t.Errorf("macOS hint sends the person to the Linux store:\n%s", hint)
	}
	for _, goos := range []string{"linux", "windows"} {
		hint := certificateHintOn(goos)
		if strings.Contains(hint, CAVariable) {
			t.Errorf("%s hint names a variable that is ignored there:\n%s", goos, hint)
		}
		if !strings.Contains(hint, "系统信任库") {
			t.Errorf("%s hint does not send the CA to the system store:\n%s", goos, hint)
		}
		if strings.Contains(hint, "重跑安装说明") {
			t.Errorf("%s hint offers to reinstall the binary, which does not touch its trust store:\n%s", goos, hint)
		}
	}
}

func TestHintForTheNewerTrustdSpelling(t *testing.T) {
	clearSandboxMarkers(t)
	hint := Hint(errors.New(`Get "https://console.skillmasterai.com/api/v1/me": ` +
		`tls: failed to verify certificate: x509: SecTrustEvaluateWithError error: -26276`))
	if !strings.Contains(hint, "trustd") {
		t.Errorf("the newer trustd wording did not reach the trustd sentence:\n%s", hint)
	}
}

func TestHintIgnoresAHostnameMismatch(t *testing.T) {
	hint := Hint(errors.New(`Get "https://localhost:8443/": tls: failed to verify certificate: ` +
		`x509: certificate is valid for console.skillmasterai.com, not localhost`))
	if hint != "" {
		t.Errorf("a wrong address got the certificate hint:\n%s", hint)
	}
}

func TestHintForNetworkWithoutSandbox(t *testing.T) {
	clearSandboxMarkers(t)
	t.Setenv("SKILLMASTER_SERVER", "https://skills.corp.example")
	hint := Hint(errors.New("Get https://skills.corp.example/: " +
		"dial tcp: lookup skills.corp.example: no such host"))
	if !strings.Contains(hint, "skills.corp.example") {
		t.Errorf("hint does not name the configured host:\n%s", hint)
	}
	if strings.Contains(hint, "allowedDomains") {
		t.Errorf("hint gave sandbox settings outside a sandbox:\n%s", hint)
	}
}

func TestHintForNetworkInSandboxUsesTheConfiguredHost(t *testing.T) {
	clearSandboxMarkers(t)
	t.Setenv("CLAUDECODE", "1")
	t.Setenv("SANDBOX_RUNTIME", "1")
	t.Setenv("SKILLMASTER_SERVER", "https://skills.corp.example")
	hint := Hint(errors.New("Get https://skills.corp.example/: " +
		"dial tcp: lookup skills.corp.example: no such host"))
	if !strings.Contains(hint, "skills.corp.example") {
		t.Errorf("hint does not name the configured host:\n%s", hint)
	}
	if !strings.Contains(hint, "allowedDomains") {
		t.Errorf("hint does not give the sandbox setting inside a sandbox:\n%s", hint)
	}
}

func TestHintForNamesTheGivenHostNotThePlatform(t *testing.T) {
	clearSandboxMarkers(t)
	t.Setenv("CLAUDECODE", "1")
	t.Setenv("SANDBOX_RUNTIME", "1")
	t.Setenv("SKILLMASTER_SERVER", "https://console.skillmasterai.com")
	hint := HintFor("dl.skillmasterai.com", errors.New(
		"Get https://dl.skillmasterai.com/version.txt: dial tcp: lookup dl.skillmasterai.com: no such host"))
	if !strings.Contains(hint, "dl.skillmasterai.com") {
		t.Errorf("hint does not name the host the request went to:\n%s", hint)
	}
	if strings.Contains(hint, "console.skillmasterai.com") {
		t.Errorf("hint names the platform for a request to another host:\n%s", hint)
	}
}

func TestHintForAPermissionDeniedDial(t *testing.T) {
	clearSandboxMarkers(t)
	hint := Hint(&net.OpError{Op: "dial", Net: "tcp", Err: os.ErrPermission})
	if hint == "" {
		t.Fatal("a permission-denied dial got no hint")
	}
	if !strings.Contains(hint, "连接失败") {
		t.Errorf("hint does not describe a network failure:\n%s", hint)
	}
}

func TestHintIgnoresUnrelatedErrors(t *testing.T) {
	if hint := Hint(errors.New("something the package has never seen")); hint != "" {
		t.Errorf("Hint invented a sentence: %q", hint)
	}
}

// The update path downloads from another host. A snippet that allows only the platform leaves
// `skmr update` blocked — and that is also the recovery route when the embedded roots are stale.
func TestSnippetsAlsoAllowTheDownloadHost(t *testing.T) {
	t.Setenv("SKILLMASTER_SERVER", "https://console.skillmasterai.com")
	t.Setenv("SKILLMASTER_UPDATE_BASE", "https://dl.skillmasterai.com/beta")
	snippet := Snippets(ClaudeCode)
	if !strings.Contains(snippet, "console.skillmasterai.com") {
		t.Errorf("snippet lost the platform host:\n%s", snippet)
	}
	if !strings.Contains(snippet, "dl.skillmasterai.com") {
		t.Errorf("snippet does not allow the download host:\n%s", snippet)
	}
}

// An allowlist entry is a host, not an endpoint. A self-hosted server on a port must not put
// "host:port" into a domain rule.
func TestSnippetsNameTheHostWithoutThePort(t *testing.T) {
	t.Setenv("SKILLMASTER_SERVER", "https://skills.corp.example:8443")
	t.Setenv("SKILLMASTER_UPDATE_BASE", "")
	snippet := Snippets(ClaudeCode)
	if !strings.Contains(snippet, "skills.corp.example") {
		t.Errorf("snippet lost the configured host:\n%s", snippet)
	}
	if strings.Contains(snippet, ":8443") {
		t.Errorf("snippet put a port into a domain rule:\n%s", snippet)
	}
	// The sentence that reports the failure keeps the port: it is how the person finds what was
	// being contacted.
	clearSandboxMarkers(t)
	t.Setenv("SANDBOX_RUNTIME", "1")
	if hint := networkHint("skills.corp.example:8443"); !strings.Contains(hint, "skills.corp.example:8443") {
		t.Errorf("the failure sentence dropped the port:\n%s", hint)
	}
}

func TestHostOnlyLeavesABareAddressAlone(t *testing.T) {
	for _, host := range []string{"::1", "[::1]", "[::1]:8443", "127.0.0.1:8080", "example.com", ""} {
		want := map[string]string{
			"::1": "::1", "[::1]": "::1", "[::1]:8443": "::1",
			"127.0.0.1:8080": "127.0.0.1", "example.com": "example.com", "": "",
		}[host]
		if got := hostOnly(host); got != want {
			t.Errorf("hostOnly(%q) = %q, want %q", host, got, want)
		}
	}
}

func TestSnippetsUseTheConfiguredHost(t *testing.T) {
	t.Setenv("SKILLMASTER_SERVER", "https://console.skillmasterai.com")
	for _, client := range []Client{ClaudeCode, Codex, Cursor, Gemini, Unknown} {
		if snippet := Snippets(client); !strings.Contains(snippet, "console.skillmasterai.com") {
			t.Errorf("Snippets(%q) does not name the configured host:\n%s", client, snippet)
		}
	}
}

func TestLoopbackHintIsClientSpecific(t *testing.T) {
	t.Setenv("SKILLMASTER_SERVER", "https://console.skillmasterai.com")
	if hint := LoopbackHint(ClaudeCode); !strings.Contains(hint, "allowLocalBinding") {
		t.Errorf("Claude hint lacks allowLocalBinding:\n%s", hint)
	}
	if hint := LoopbackHint(Codex); !strings.Contains(hint, "allow_local_binding") {
		t.Errorf("Codex hint lacks allow_local_binding:\n%s", hint)
	}
	if hint := LoopbackHint(Unknown); !strings.Contains(hint, "127.0.0.1") {
		t.Errorf("generic hint lacks the loopback address:\n%s", hint)
	}
}

func TestStateHintDoesNotPromiseAnUnreadVariable(t *testing.T) {
	hint := StateHint()
	if strings.Contains(hint, "SKILLMASTER_HOME") && !strings.Contains(hint, "下一版") {
		t.Errorf("state hint promises an unread variable without a version:\n%s", hint)
	}
}

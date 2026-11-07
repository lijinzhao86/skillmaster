package sandbox

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// Options lets a caller (and a test) point the probe somewhere other than this machine's real
// directories. Empty fields fall back to the CLI's own answers.
type Options struct {
	BaseURL        string
	StateDir       string
	CredentialPath string
	Server         string
	Timeout        time.Duration
}

// Check is one probed capability. Detail is what happened; Hint is what to do about it, and is empty
// when the capability works or when this package has nothing useful to say.
type Check struct {
	Name   string
	OK     bool
	Detail string
	Hint   string
}

// Report is what a doctor command prints. The checks are ordered the way a failure cascades: if the
// platform is unreachable, nothing below it can be exercised.
type Report struct {
	Environment Environment
	Checks      []Check
}

// Probe runs the checks this package knows how to run. It never reads a credential: opening the
// store is enough to say where one would live, and reading a keychain can raise a permission prompt
// in front of an agent — the exact hang the credential package's deadline exists to avoid.
func Probe(ctx context.Context, opts Options) Report {
	timeout := opts.Timeout
	if timeout <= 0 {
		timeout = 3 * time.Second
	}
	report := Report{Environment: Detect()}
	report.Checks = append(report.Checks,
		probePlatform(ctx, opts.BaseURL, timeout),
		probeLoopback(timeout),
		probeState(opts.StateDir),
		probeCredentials(opts.CredentialPath, opts.Server),
	)
	return report
}

func probePlatform(ctx context.Context, base string, timeout time.Duration) Check {
	if base == "" {
		base = config.Server()
	}
	client := http.Client{Timeout: timeout}
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, base, nil)
	if err != nil {
		return Check{Name: "platform", Detail: fmt.Sprintf("%s：%v", base, err), Hint: HintFor(HostOf(base), err)}
	}
	resp, err := client.Do(req)
	if err != nil {
		return Check{Name: "platform", Detail: fmt.Sprintf("%s：%v", base, err), Hint: HintFor(HostOf(base), err)}
	}
	defer resp.Body.Close()
	// Any answer means the network and the certificate chain were good enough to get one. A 401 or a
	// 405 is a reachable server; only a transport error is a failed check.
	return Check{Name: "platform", OK: true, Detail: fmt.Sprintf("%s -> HTTP %d", base, resp.StatusCode)}
}

// probeLoopback tests the shape the CLI needs for its own listener: bind a port and connect to it
// from this process. The child-process shape is the one `skmr run` will need; it is not tested here
// because starting a process to test a socket would make `doctor` depend on the thing it is
// diagnosing.
func probeLoopback(timeout time.Duration) Check {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return Check{Name: "loopback", Detail: err.Error(), Hint: LoopbackHint(Detect().Client)}
	}
	defer listener.Close()

	addr := listener.Addr().String()
	conn, err := net.DialTimeout("tcp", addr, timeout)
	if err != nil {
		return Check{Name: "loopback", Detail: fmt.Sprintf("listen %s ok; connect failed: %v", addr, err), Hint: LoopbackHint(Detect().Client)}
	}
	conn.Close()
	return Check{Name: "loopback", OK: true, Detail: fmt.Sprintf("listen + connect %s", addr)}
}

// probeState writes a zero-byte marker rather than a temporary file it deletes: the CLI never
// deletes files (ADR 0101), and the marker is the write test itself. It stays behind; the ADR records
// that side effect.
func probeState(dir string) Check {
	if dir == "" {
		resolved, err := config.Dir()
		if err != nil {
			return Check{Name: "state", Detail: err.Error(), Hint: StateHint()}
		}
		dir = resolved
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return Check{Name: "state", Detail: fmt.Sprintf("%s：%v", dir, err), Hint: StateHint()}
	}
	marker := filepath.Join(dir, ".sandbox-probe")
	if err := os.WriteFile(marker, nil, 0o600); err != nil {
		return Check{Name: "state", Detail: fmt.Sprintf("%s：%v", marker, err), Hint: StateHint()}
	}
	return Check{Name: "state", OK: true, Detail: dir}
}

// probeCredentials reports which store the CLI would use. It opens, it does not load: see Probe.
func probeCredentials(path, server string) Check {
	if server == "" {
		server = config.Server()
	}
	if path == "" {
		resolved, err := config.CredentialPath(server)
		if err != nil {
			return Check{Name: "credentials", Detail: err.Error(), Hint: Hint(err)}
		}
		path = resolved
	}
	store, notice := credentials.Open(path, server)
	detail := "凭据位置：" + store.Where()
	if notice != "" {
		detail += "（" + notice + "）"
	}
	// OK means "we know where it would live", not "it is readable": reading a keychain can raise a
	// prompt in front of an agent. The ADR records that this is a location check.
	return Check{Name: "credentials", OK: true, Detail: detail}
}

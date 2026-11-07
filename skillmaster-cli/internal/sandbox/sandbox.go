// Package sandbox answers two questions about the process it runs in: what can this machine
// actually do from inside an agent's sandbox, and what does the person do about the parts it cannot.
//
// **It exists because there is no one sandbox.** Claude Code, Codex, Cursor and the rest each block
// a different subset of TLS, network, loopback, the filesystem and the keychain. The CLI does not
// try to recognise every client and carry a branch for each; it probes the capabilities and uses the
// same degradation for all of them. A client's name is used for exactly one thing — the
// configuration snippet a person has to paste — and a client we do not know still gets a useful
// answer. That is the whole policy (ADR 0167).
package sandbox

import (
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"runtime"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/version"
)

// CAVariable names the optional PEM bundle of private roots. The binary reads it once, at startup.
const CAVariable = "SKILLMASTER_CA_BUNDLE"

// Client is the agent whose sandbox this process is (probably) running in. It names a snippet, not
// a code path: nothing in this package branches on it except the text a person is told to paste.
type Client string

const (
	ClaudeCode Client = "claude-code"
	Codex      Client = "codex"
	Cursor     Client = "cursor"
	Gemini     Client = "gemini"
	Unknown    Client = "unknown"
)

// Environment is what the process can learn without touching the network or the filesystem.
type Environment struct {
	OS     string
	Client Client
	// Sandboxed says a sandbox marker was present. `CLAUDECODE` alone selects a client but does not
	// mean a sandbox is on: the agent sets it in ordinary sessions too.
	Sandboxed bool
	// Markers are the variables that led to Client, kept so a doctor report can show why the CLI
	// thought what it thought. A wrong guess should be visible, not silent.
	Markers map[string]string
}

// Detect names the client from its documented sandbox markers.
//
// **Only the sandbox markers decide `Sandboxed`.** Claude Code and Codex both export model/provider
// variables in ordinary sessions, so treating those as a sandbox signal would tell every user they
// are in one. `CODEX_SANDBOX`, `CURSOR_SANDBOX`, `GEMINI_SANDBOX` and `SANDBOX_RUNTIME` are the
// markers the clients set around a sandboxed command. `CLAUDECODE` is accepted as a client name
// only; WorkBuddy 5.8.3 set no marker in our test, so it is reported as Unknown rather than guessed
// from a path.
func Detect() Environment {
	env := Environment{OS: runtime.GOOS, Client: Unknown, Markers: map[string]string{}}
	for _, key := range []string{"CODEX_SANDBOX", "CURSOR_SANDBOX", "GEMINI_SANDBOX", "SANDBOX_RUNTIME"} {
		if value := os.Getenv(key); value != "" {
			env.Markers[key] = value
			env.Sandboxed = true
		}
	}
	if value := os.Getenv("CLAUDECODE"); value != "" {
		env.Markers["CLAUDECODE"] = value
	}
	switch {
	case env.Markers["CODEX_SANDBOX"] != "":
		env.Client = Codex
	case env.Markers["CURSOR_SANDBOX"] != "":
		env.Client = Cursor
	case env.Markers["GEMINI_SANDBOX"] != "":
		env.Client = Gemini
	case env.Markers["CLAUDECODE"] != "":
		env.Client = ClaudeCode
	}
	return env
}

// Snippets is the network/loopback configuration a person has to paste for this client. It is the
// only client-specific text in the package.
//
// **Both names, not just the platform's.** `skmr update` downloads from the update channel, which is
// a different host (ADR 0103); a list that allows only the platform leaves the update path blocked,
// and the update path is also the recovery route when the embedded roots are out of date (ADR 0167).
func Snippets(client Client) string {
	return snippetsFor(networkHosts(serverHost()), client)
}

// networkHosts is what a sandbox has to allow for this CLI: the host being contacted, plus the
// download host when it is a different name.
func networkHosts(primary string) []string {
	hosts := []string{hostOnly(primary)}
	if download := hostOnly(HostOf(version.Channel())); download != "" && download != hosts[0] {
		hosts = append(hosts, download)
	}
	return hosts
}

// hostOnly drops a port: an allowlist entry names a host, not an endpoint. The sentence that
// reports the failure keeps the port, because that is how the person finds what was being contacted.
func hostOnly(host string) string {
	// A bare address is not a host:port — url.Parse would read "::1" as host ":" and port "1".
	if ip := strings.Trim(host, "[]"); net.ParseIP(ip) != nil {
		return ip
	}
	if parsed, err := url.Parse("//" + host); err == nil && parsed.Hostname() != "" {
		return parsed.Hostname()
	}
	return host
}

func snippetsFor(hosts []string, client Client) string {
	host := strings.Join(hosts, `", "`)
	switch client {
	case ClaudeCode:
		return fmt.Sprintf(`Claude Code（~/.claude/settings.json）：
  "sandbox": {
    "network": {
      "allowedDomains": ["%s"],
      "allowLocalBinding": true
    }
  }
allowLocalBinding 只影响本机回环。`, host)
	case Codex:
		return fmt.Sprintf(`Codex（config.toml；键名以你安装的版本为准，本轮未实测）：
  允许网络：sandbox_workspace_write.network_access = true
  允许回环：allow_local_binding = true
  放行域名：在 profiles.*.network.domains 里加 "%s"`, host)
	case Cursor:
		return fmt.Sprintf(`Cursor（~/.cursor/sandbox.json；本轮未实测）：
  "networkPolicy": {
    "allow": ["%s", "127.0.0.1/32", "::1/128"]
  }
（私有地址默认被 SSRF 规则挡住；能否用 allow 覆盖尚未实测。）`, host)
	case Gemini:
		return fmt.Sprintf(`Gemini CLI：让沙箱 profile 允许网络（例如 SEATBELT_PROFILE=permissive-open），
或在容器沙箱里放行 %s。（本轮未实测 Gemini。）`, strings.Join(hosts, "、"))
	default:
		return fmt.Sprintf(`在客户端的沙箱/网络设置里放行 %s；需要本地回环时再放行 127.0.0.1。
WorkBuddy 5.8.3 的默认沙箱实测已放行 TLS、回环与网络，无需配置。`, strings.Join(hosts, "、"))
	}
}

// Hint turns a transport failure into the sentence a person can act on. An error the package does
// not recognise gets no sentence: inventing one would be worse than the raw error.
//
// **The catalog is limited to what internal/api actually sees.** It recognises the macOS trustd
// signature, an untrusted certificate chain, and network failures. It does not translate arbitrary
// `operation not permitted` errors: the skill-runtime version's PRD (不做 8) is right that errno text is not a stable way
// to identify a sandbox, and the probe below knows which capability it was checking without guessing
// from a string.
func Hint(err error) string {
	return HintFor(serverHost(), err)
}

// HintFor is Hint for a failure against host.
//
// **The host is a parameter because not every request goes to the platform.** The updater talks to
// the download host, which is a different name; a sentence that names the platform sends a person to
// whitelist a domain that was never blocked, and the retry fails the same way.
func HintFor(host string, err error) string {
	if err == nil {
		return ""
	}
	if host == "" {
		host = serverHost()
	}
	text := err.Error()
	switch {
	case strings.Contains(text, "x509:") && strings.Contains(text, "-26276"):
		// Two spellings in the wild: `x509: OSStatus -26276` from older chains and
		// `x509: SecTrustEvaluateWithError error: -26276` from newer Go. Both are trustd being
		// unreachable, and both must reach the same sentence.
		return tlsHint()
	case isHostnameMismatch(text):
		// The certificate verified; it just names another host. That is a wrong address, and the
		// certificate hint would send somebody to install a CA that is not missing.
		return ""
	case isCertificateFailure(text):
		return certificateHint()
	case permissionDenied(err) && dials(err):
		return networkHint(host)
	case isNetworkFailure(text):
		return networkHint(host)
	default:
		return ""
	}
}

// Wrap appends Hint to an error, keeping the original wrapped so errors.Is/As still work.
func Wrap(err error) error {
	return WrapFor(serverHost(), err)
}

// WrapFor is Wrap for a failure against host; see HintFor for why the host is a parameter.
func WrapFor(host string, err error) error {
	if hint := HintFor(host, err); hint != "" {
		return fmt.Errorf("%w\n\n%s", err, hint)
	}
	return err
}

// LoopbackHint is for the login listener and the probe, both of which know which capability they
// were exercising and therefore do not have to infer it from an errno string.
func LoopbackHint(client Client) string {
	return "本地回环被沙箱挡住了。如果只是登录，在自己的终端里跑 `skmr login`；\n" +
		"如果要用本地功能，按这个客户端的设置放行回环：\n" + Snippets(client)
}

// StateHint is for the probe's state-directory check. `SKILLMASTER_HOME` is named as the next
// version's mechanism; this branch does not read it yet, so the first sentence is the one that
// works today.
func StateHint() string {
	return "当前状态目录不可写。把配置目录加入客户端的可写白名单；下一版起还可用 SKILLMASTER_HOME。"
}

func isCertificateFailure(text string) bool {
	return strings.Contains(text, "certificate signed by unknown authority") ||
		strings.Contains(text, "tls: failed to verify certificate")
}

// isHostnameMismatch is matched before isCertificateFailure: those failures also carry the
// "failed to verify certificate" prefix, and they are about the address rather than the chain.
func isHostnameMismatch(text string) bool {
	for _, fragment := range []string{
		"certificate is valid for", "cannot validate certificate for",
		"doesn't contain any IP SANs", "certificate relies on legacy Common Name field",
	} {
		if strings.Contains(text, fragment) {
			return true
		}
	}
	return false
}

func permissionDenied(err error) bool {
	return errors.Is(err, os.ErrPermission) || strings.Contains(err.Error(), "operation not permitted")
}

func dials(err error) bool {
	var opErr *net.OpError
	return errors.As(err, &opErr) && opErr.Op == "dial"
}

func isNetworkFailure(text string) bool {
	for _, fragment := range []string{
		"connection refused", "no such host", "i/o timeout", "network is unreachable",
		"proxyconnect tcp", "TLS handshake timeout", "connection reset by peer",
	} {
		if strings.Contains(text, fragment) {
			return true
		}
	}
	return false
}

func tlsHint() string {
	return "这通常是 macOS 沙箱挡住了 Go 的证书验证（trustd 的 Mach IPC，OSStatus -26276）。\n" +
		"当前版本强制使用内置公共根证书，不再向系统验证器要结果；如果仍然出现：\n" +
		"1. 企业自建 CA：把 CA 的 PEM 路径放进 " + CAVariable + "，然后重跑。\n" +
		"2. 仍然失败：把这条错误原文发给支持。"
}

func certificateHint() string {
	return certificateHintOn(runtime.GOOS)
}

// certificateHintOn says where a private CA goes on this platform.
//
// **The escape hatch is not the same one everywhere.** macOS is the platform where the CLI's own
// trust store is the one in use (trustroots_darwin.go); Linux and Windows normally use the system
// store, so a private CA there goes into that store. SKILLMASTER_CA_BUNDLE still applies where the
// system store cannot be loaded, but a hint cannot count on that — sending somebody on a managed
// laptop to a variable that is usually ignored is worse than pointing at the store they already own.
func certificateHintOn(goos string) string {
	const clock = "证书链不被信任。公共站点请检查系统时间；企业自建 CA 请"
	if goos == "darwin" {
		// The installer only replaces the binary, which on macOS *is* the trust store.
		return clock + "把 PEM 路径放进 " + CAVariable + "。" +
			"公共站点上仍然失败：重跑安装说明里那条命令。"
	}
	// Elsewhere the store is the system's, and reinstalling the CLI does not touch it — so the
	// advice is about the store, not about the binary.
	return clock + "装进系统信任库（Debian/Ubuntu：放到 /usr/local/share/ca-certificates 后跑" +
		" update-ca-certificates；RHEL 系：放到 /etc/pki/ca-trust/source/anchors 后跑" +
		" update-ca-trust；Windows：证书管理器）。公共站点的根过期：更新系统的 CA。"
}

func networkHint(host string) string {
	env := Detect()
	if env.Sandboxed {
		return fmt.Sprintf("到 %s 的连接失败。客户端沙箱的域名白名单默认通常是空的，放行它：\n%s",
			host, snippetsFor(networkHosts(host), env.Client))
	}
	return fmt.Sprintf("到 %s 的连接失败。检查服务是否启动、地址是否正确、网络是否可达。", host)
}

// HostOf is the host (and port, when present) of a URL, or "" when there is none to find.
func HostOf(raw string) string {
	if parsed, err := url.Parse(raw); err == nil {
		return parsed.Host
	}
	return ""
}

// serverHost is the host (and port, when present) actually configured, not the production name.
// A self-hosted or local server must be told to the person as itself; the default is only a default.
func serverHost() string {
	server := config.Server()
	if host := HostOf(server); host != "" {
		return host
	}
	return server
}

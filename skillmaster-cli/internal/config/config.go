// Package config is what this CLI needs to know before it can talk to anything: which server,
// where the credential lives, and which loopback port it answers on.
package config

import (
	"crypto/sha256"
	"fmt"
	"os"
	"path/filepath"
)

// ServerEnv names the environment variable that overrides the server.
//
// An environment variable rather than a config file, because the two things that need to change it
// are CI and a developer pointing at their own stack — both of which have somewhere to put one and
// neither of which wants a file to manage.
const ServerEnv = "SKILLMASTER_SERVER"

// SkillsDirEnv overrides where this machine's skills live. A machine running an agent this version
// does not know about is then a configuration line rather than an unsupported platform.
//
// **One fact, one reader today.** `submit` resolves a bare skill name here. `startup`'s state line
// deliberately does not: it scans the candidate table (internal/local) because a person who has not
// set this variable yet is exactly the person the journey is written for.
const SkillsDirEnv = "SKILLMASTER_SKILLS_DIR"

// ApiKeyEnv names a key directly, and it wins over anything this machine has stored (ADR 0038
// §决定 5). It is the only credential source a container can be given without a person.
const ApiKeyEnv = "SKILLMASTER_API_KEY"

// WebURLEnv overrides where the browser is sent to look at a skill.
//
// Only the deep link `submit` opens after a successful upload — nothing in this CLI's own requests
// uses it, and the API base is `Server`. It exists because the two are the same origin in a
// deployment (ADR 0015's reverse proxy serves the pages and the API together) and are not in
// development, where the pages are Vite's on 5173 and the API is on 8080. A deep link built from
// `Server` would then open a 404 that looks like the submission failed.
const WebURLEnv = "SKILLMASTER_WEB_URL"

// ClientID is this CLI's registered identity.
//
// Compiled in rather than discovered, because the CLI is a first-party client and `client_id` is
// published with it (ADR 0011: v1 registers clients ahead of time). It has to match the seeded
// `oauth_client.client_id` **and** the redirect URI has to match that row's, so this constant and
// `CallbackPort` and the deployment's seed row are one fact in three places — which is why ADR 0028
// says changing the port means changing the seed.
const ClientID = "skillmaster-cli"

// DefaultServer is where a login goes when nothing says otherwise.
//
// **This is a source default, and releases do not keep it.** A plain `go build` talks to the local
// stack — the person running it is far more likely to be working on their own checkout than to be a
// user of the deployment — and pointing an unconfigured binary at production would mean one slipped
// command hands a credential to the wrong server.
//
// **A variable, because a release build overwrites it.** The pipeline passes
//
//	-ldflags "-X …/internal/config.DefaultServer=$SKILLMASTER_PUBLIC_BASE_URL"
//
// where the value is the repository variable of that name — the same one the deployment renders
// into the server's own environment. The CLI's address and the `issuer` the server publishes are
// then one value rather than two that have to be kept in step.
//
// On a working copy, `SKILLMASTER_SERVER` exported by the shell overrides both. The repository's
// `.env.example` documents it; nothing here reads a file.
var DefaultServer = "http://localhost:8080"

// CallbackPort is the loopback port the redirect comes back on.
//
// **Fixed, not ephemeral**, and it has to match the client's registered redirect URI character for
// character: the server reads that URI from the client's own row rather than letting a request name
// one (v4 §接口), and nothing knows about RFC 8252 §8.4's exception for the port. So this number and
// the deployed `oauth_client.redirect_uris` entry are one fact in two places — see ADR 0028.
const CallbackPort = 51004

// CallbackPath is the path half of that registered redirect URI, and the one address the loopback
// listener answers.
const CallbackPath = "/callback"

// Server is the base URL to talk to, without a trailing slash.
func Server() string {
	if fromEnv := os.Getenv(ServerEnv); fromEnv != "" {
		return trimTrailingSlash(fromEnv)
	}
	// Trimmed as well, because this one is injected at build time from a variable somebody types
	// into: a trailing slash would make every request path start `//`, which no proxy rule matches.
	return trimTrailingSlash(DefaultServer)
}

// Dir is where this CLI keeps its state: the credential, and the pin file beside it.
//
// `os.UserConfigDir` rather than a dotfile in the home directory: it is the platform's answer to
// this question, and those answers differ — `$HOME/Library/Application Support` on macOS, which is
// not `~/.config` (checked rather than assumed: the function was run on this machine and returned
// exactly that), `$XDG_CONFIG_HOME` or `~/.config` on Linux, `%AppData%` on Windows. On macOS it is
// also the one place a file is not silently synced to iCloud.
func Dir() (string, error) {
	base, err := os.UserConfigDir()
	if err != nil {
		return "", fmt.Errorf("finding the user config directory: %w", err)
	}
	return filepath.Join(base, "skillmaster"), nil
}

// CredentialPath is the fallback file's location for one server (ADR 0025).
//
// **Per server, because the keychain is and the two have to agree.** The keychain entry is keyed by
// the server URL, so a machine signed in to two of them — somebody's local stack and the real one —
// keeps two entries, which is the whole point of that key. A single shared file breaks it in the
// direction that costs something: the load path would serve the *other* server's credential, so its
// bearer token goes to an origin that never issued it, and the 401 that comes back is about a
// credential that origin has never heard of. Using one server would destroy the other's sign-in.
//
// The server is hashed rather than written into the name for the reason `PinPath` below shares: a base
// URL has slashes and a colon, and a path that needs escaping is a path that will one day be a
// directory somebody did not expect.
func CredentialPath(server string) (string, error) {
	return perServerPath("credentials", server)
}

// PinPath is where this machine remembers which version of which skill it invoked (ADR 0035).
//
// **Per server, for a weaker reason than the credential's and the same shape anyway.** Nothing secret
// is in here — a version name and a digest — so the worst a shared file could do is hand one server's
// pin to another, where it resolves to nothing or, if the names collide, to some other skill's
// version. That is still a wrong answer arriving without a complaint, which is the failure this
// project spends the most effort on, and one hash is cheaper than deciding when it matters.
func PinPath(server string) (string, error) {
	return perServerPath("pins", server)
}

// perServerPath is the naming rule both of the above share: a fixed prefix and the first six bytes of
// the server's hash.
//
// The server is hashed rather than written into the name because a base URL has slashes and a colon,
// and a path that needs escaping is a path that will one day be a directory somebody did not expect.
func perServerPath(prefix, server string) (string, error) {
	dir, err := Dir()
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256([]byte(server))
	return filepath.Join(dir, fmt.Sprintf("%s-%x", prefix, sum[:6])), nil
}

// SkillsDir is where this machine's skills live.
//
// Claude Code's location, which is the one agent this version knows. The environment variable comes
// first so that a machine with a different agent is a configuration line rather than an unsupported
// platform.
func SkillsDir() (string, error) {
	if fromEnv := os.Getenv(SkillsDirEnv); fromEnv != "" {
		return fromEnv, nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("finding the home directory: %w", err)
	}
	return filepath.Join(home, ".claude", "skills"), nil
}

// WebURL is where the browser looks at a skill, without a trailing slash.
//
// Defaults to the server, because in a deployment they are one origin. The two differ only while
// developing, where this is a line in the environment rather than a wrong link.
func WebURL() string {
	if fromEnv := os.Getenv(WebURLEnv); fromEnv != "" {
		return trimTrailingSlash(fromEnv)
	}
	return Server()
}

func trimTrailingSlash(url string) string {
	for len(url) > 0 && url[len(url)-1] == '/' {
		url = url[:len(url)-1]
	}
	return url
}

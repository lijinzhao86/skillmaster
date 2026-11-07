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

// HomeEnv names the directory this CLI keeps its state in, ahead of the usual one. It is the
// escape hatch for a machine whose ordinary location is not writable — an agent sandbox, a
// container, a CI job — and the reason `Dir` probes instead of assuming.
const HomeEnv = "SKILLMASTER_HOME"

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

// Name is what this CLI is called on the command line (ADR 0113).
const Name = "skmr"

// PackageName is the prefix of the release artifacts: `skillmaster_<os>_<arch>.tar.gz`.
//
// **It is not the command's name** — that is `Name`, and the file inside the archive is named after
// it. The artifacts keep this spelling on purpose (2026-10-29): the address users copy from the
// install page should not move because the command was renamed.
const PackageName = "skillmaster"

// LegacyName is what this CLI was called before v29. **One line of compatibility, kept in code
// and out of the documents**: a few places still spell the command this way (an agent memory file
// written by an older setup, a published skill's body). `skmr` is the name everywhere; this only
// keeps a second entry point working.
const LegacyName = "skillmaster"

// stateDir is the name of the directory under the OS config directory.
//
// **A location, not the command, and not the compatibility entry either.** Every machine that ever
// signed in has its credential under `…/skillmaster`; it happens to be spelled like the old name,
// and it must outlive both — changing it would sign everybody out and lose their pins.
const stateDir = "skillmaster"

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
	resolved, err := resolveDir()
	return resolved.dir, err
}

// DirNote explains a state directory that is not the first candidate, or returns "".
//
// **A fallback the person cannot see is worse than a failure.** An agent's sandbox refuses writes
// outside its workspace, so the ordinary location is unreachable there and the CLI has to use
// another one; saying so is the difference between "it works" and "why is my credential here".
// The callers that show it are the ones whose output a person reads (doctor, login).
//
// **The directory named is the one that refused**, not a fixed guess. When `SKILLMASTER_HOME` is
// the unwritable candidate and the ordinary directory is fine, a note that blames the ordinary one
// says "`<dir>` is not writable, using `<dir>`" — true of nothing and useful to nobody.
func DirNote() string {
	resolved, err := resolveDir()
	if err != nil || resolved.refused == "" {
		return ""
	}
	return fmt.Sprintf("%s 写不进去（沙箱或权限），状态改放 %s。", resolved.refused, resolved.dir)
}

// StatePaths is where one state file is written, and where an older copy may be read from.
//
// **Reading and writing are two different questions, and one path cannot answer both.** A sandbox
// makes the ordinary directory read-only, so a write-probe says "not here" — but the credential and
// the pins this machine has are *in* that directory and are perfectly readable. Reading has to find
// them; writing has to land somewhere writable. Returning one path for both is how a read-only
// credential file becomes a failed `login` (ADR 0164 §2).
//
// The write path is `Dir()`'s: the first candidate a probe could write to. `older` is the first
// other candidate that already holds this file, or "" when there is none. Callers that only read
// use both; a caller that writes uses the first and leaves the second alone.
func StatePaths(rel string) (write, older string, err error) {
	dir, err := Dir()
	if err != nil {
		return "", "", err
	}
	write = filepath.Join(dir, rel)
	for _, candidate := range stateCandidates() {
		path := filepath.Join(candidate, rel)
		if path == write {
			continue
		}
		if _, statErr := os.Stat(path); statErr == nil {
			return write, path, nil
		}
	}
	return write, "", nil
}

type resolution struct {
	dir string
	// refused is the first candidate that would not take a write, "" when the first one did.
	refused string
}

// resolveDir walks the candidates and takes the first one it can actually write to.
//
// **Trying, not detecting.** Only two of the four agent clients announce a sandbox in the
// environment, and none of them announce it in a way the others share; a write probe is the one
// test every client answers the same way. It runs every time rather than being memoized because a
// CLI invokes this a handful of times per command, and a memo would make the answer depend on which
// command ran first in the process.
func resolveDir() (resolution, error) {
	var firstErr error
	refused := ""
	for _, candidate := range stateCandidates() {
		if probeErr := writable(candidate); probeErr != nil {
			if firstErr == nil {
				firstErr = probeErr
				refused = candidate
			}
			continue
		}
		return resolution{candidate, refused}, nil
	}
	if firstErr == nil {
		firstErr = fmt.Errorf("no writable state directory among %v", stateCandidates())
	}
	return resolution{}, firstErr
}

func stateCandidates() []string {
	var out []string
	if fromEnv := os.Getenv(HomeEnv); fromEnv != "" {
		out = append(out, fromEnv)
	}
	if ordinary := ordinaryStateDir(); ordinary != "" {
		out = append(out, ordinary)
	}
	// `$TMPDIR` is the one place every agent sandbox lets a command write and delete; the
	// subdirectory keeps it recognisable in a directory full of other programs' leftovers.
	out = append(out, filepath.Join(os.TempDir(), stateDir, "state"))
	return out
}

func ordinaryStateDir() string {
	base, err := os.UserConfigDir()
	if err != nil {
		return ""
	}
	// **The directory name is `stateDir`, and it is a location.** Every installed machine already has
	// this directory; it does not follow the command's name, so it is spelled out separately from
	// both `Name` and `LegacyName` — the three constants answer three different questions and only
	// two of them are ever allowed to move together.
	return filepath.Join(base, stateDir)
}

// writable makes the directory and proves it, with one fixed-name file that is overwritten rather
// than removed (ADR 0101). MkdirAll succeeding is not proof: an existing directory can be read-only,
// which is exactly the state a sandbox puts `$HOME` in.
func writable(dir string) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	probe, err := os.OpenFile(filepath.Join(dir, ".write-probe"),
		os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if err := probe.Close(); err != nil {
		return err
	}
	// **Truncating a file this CLI created proves the file is writable, not the directory.** Once
	// `.write-probe` exists, `O_CREATE|O_TRUNC` succeeds on a directory whose write bit is gone
	// (a `chmod 0500` mount, a sandbox that leaves the file readable), and every later write in
	// this command would fail. The directory's own bits are the cheap second half of the answer.
	if info, statErr := os.Stat(dir); statErr == nil && info.Mode().Perm()&0o200 == 0 {
		return fmt.Errorf("%s 没有写权限位", dir)
	}
	return nil
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

// CredentialPaths is CredentialPath plus the older copy a read may fall back to.
func CredentialPaths(server string) (write, older string, err error) {
	sum := sha256.Sum256([]byte(server))
	return StatePaths(fmt.Sprintf("credentials-%x", sum[:6]))
}

// PinPath is the **pre-v24** home of this machine's pins: one JSON table per server.
//
// **It is read-only now.** v24 moved the records to one file per skill (`PinDir`); this path is still
// consulted when the new file for an address does not exist, so an upgrade does not lose a pin. It is
// never written and never deleted (ADR 0101).
//
// **Per server, for a weaker reason than the credential's and the same shape anyway.** Nothing secret
// is in here — a version name and a digest — so the worst a shared file could do is hand one server's
// pin to another, where it resolves to nothing or, if the names collide, to some other skill's
// version. That is still a wrong answer arriving without a complaint, which is the failure this
// project spends the most effort on, and one hash is cheaper than deciding when it matters.
func PinPath(server string) (string, error) {
	return perServerPath("pins", server)
}

// PinDir is where this machine keeps one pin record per skill (ADR 0101, v24).
//
// **A directory per server, and a file per address inside it.** The old shape rewrote one table for
// every invoke, which meant two agents invoking *different* skills could lose each other's record —
// and every read command showed up in a sandbox as a write. Each record is now read and written on
// its own, in place.
//
// **Same hash as `perServerPath`.** The two shapes have to agree on which server they belong to, and
// the directory name is what keeps one server's pins from being read as another's. The paths do not
// collide with `PinPath`'s result: that one is a file named `pins-<hash>`, this one is a directory
// named `pins`.
func PinDir(server string) (string, error) {
	return perServerDir("pins", server)
}

// PinDirs is PinDir plus the older directory a read may fall back to.
func PinDirs(server string) (write, older string, err error) {
	return StatePaths(filepath.Join("pins", serverHash(server)))
}

func perServerDir(prefix, server string) (string, error) {
	dir, err := Dir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, prefix, serverHash(server)), nil
}

func serverHash(server string) string {
	sum := sha256.Sum256([]byte(server))
	return fmt.Sprintf("%x", sum[:6])
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
	return filepath.Join(dir, fmt.Sprintf("%s-%s", prefix, serverHash(server))), nil
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

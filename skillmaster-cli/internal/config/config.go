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

// ClientCredentialsIDEnv and ClientCredentialsSecretEnv are what `login --client-credentials`
// reads. A pipeline has somewhere to keep a secret and no way to answer a browser prompt, so the
// unattended path takes them from the environment rather than from a file this CLI manages.
const (
	ClientCredentialsIDEnv     = "SKILLMASTER_CLIENT_ID"
	ClientCredentialsSecretEnv = "SKILLMASTER_CLIENT_SECRET"
)

// SkillsDirEnv overrides where this machine's skills live. A machine running an agent this version
// does not know about is then a configuration line rather than an unsupported platform.
//
// **One fact, two readers.** `setup` installs the gateway here, and `submit` resolves a bare skill
// name here. They were separate until submit needed the same answer, and two answers to "where are
// the skills" is a `setup` that installs one place and a `submit` that looks in another.
const SkillsDirEnv = "SKILLMASTER_SKILLS_DIR"

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
// The local stack, not a hosted address. A default that pointed at production would mean a typo in
// one command could hand a credential to the wrong server, and a person running `login` on a
// machine that has never been told anything is far more likely to be working on their own project
// than to be a user of a deployment that does not exist yet.
const DefaultServer = "http://localhost:8080"

// CallbackPort is the loopback port the redirect comes back on.
//
// **Fixed, not ephemeral**, and it has to match the client's registered redirect URI character for
// character: the authorization server compares the requested URI against the registered set with a
// plain membership test and knows nothing about RFC 8252 §8.4's exception for the port. So this
// number and the deployed `oauth_client.redirect_uris` entry are one fact in two places — see
// ADR 0028.
const CallbackPort = 51004

// CallbackPath is the path half of that URI.
const CallbackPath = "/callback"

// Server is the base URL to talk to, without a trailing slash.
func Server() string {
	if fromEnv := os.Getenv(ServerEnv); fromEnv != "" {
		return trimTrailingSlash(fromEnv)
	}
	return DefaultServer
}

// Dir is where this CLI keeps its state: the credential, and the refresh lock beside it.
//
// `os.UserConfigDir` rather than a dotfile in the home directory: it is the platform's answer to
// this question (`~/.config` on Linux and macOS, `%AppData%` on Windows), and on macOS it is the
// one place a file is not silently synced to iCloud.
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
// bearer token goes to an origin that never issued it, and the 401 that follows presents its refresh
// token there, reads the `invalid_grant`, and deletes it. Using one server would destroy the other's
// sign-in.
//
// The server is hashed rather than written into the name for the same reason the refresh lock hashes
// it: a base URL has slashes and a colon, and a path that needs escaping is a path that will one day
// be a directory somebody did not expect.
func CredentialPath(server string) (string, error) {
	dir, err := Dir()
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256([]byte(server))
	return filepath.Join(dir, fmt.Sprintf("credentials-%x", sum[:6])), nil
}

// SkillsDir is where this machine's skills live.
//
// Claude Code's location, which is the one agent this version knows — the same single-entry table
// `setup` documents. The environment variable comes first so that a machine with a different agent
// is a configuration line rather than an unsupported platform.
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

// RedirectURI is the loopback URI this run will ask for.
func RedirectURI() string {
	return fmt.Sprintf("http://127.0.0.1:%d%s", CallbackPort, CallbackPath)
}

func trimTrailingSlash(url string) string {
	for len(url) > 0 && url[len(url)-1] == '/' {
		url = url[:len(url)-1]
	}
	return url
}

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

// SkillsDirEnv overrides where `setup` installs the gateway skill. A machine running an agent this
// version does not know about is then a configuration line rather than an unsupported platform.
const SkillsDirEnv = "SKILLMASTER_SKILLS_DIR"

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

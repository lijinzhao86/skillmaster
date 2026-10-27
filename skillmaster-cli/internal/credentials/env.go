package credentials

import "strings"

// EnvStore is the credential that comes from the environment, beside whatever this machine has
// stored (ADR 0038 §决定 6).
//
// **The environment wins, and it wins without being written anywhere.** That is the whole point of
// this shape: a container, a CI job, an agent sandbox has no keychain, no home directory worth
// writing to and nobody to answer a prompt — and an exported variable is the only one of the three
// that a deployment can set without a person. `SKILLMASTER_API_KEY` therefore decides what every
// command uses, and nothing about it reaches the disk.
//
// **It is a wrapper rather than a branch at each call site.** Every command already asks a Store for
// a credential, so putting the environment in the Store is one place instead of one per command —
// and it keeps the second half of the promise honest: `Save` and `Clear` still touch the real
// store, so a `login` while the variable is set is not silently ignored, and `Where` is what says
// which of the two a message is about.
type EnvStore struct {
	// Var is the name of the variable, carried rather than hardcoded so that a message can name the
	// thing the person has to change — and so this package does not have to know the CLI's config.
	Var string
	// Value is the key as the environment presented it. Empty means "not set", which is the case
	// this wrapper exists to leave alone.
	Value string
	// Fallback is where a credential lives when the environment does not name one.
	Fallback Store
}

// Load returns the environment's credential when there is one, and this machine's otherwise.
//
// The value is returned as an access token with nothing beside it, and both absences are deliberate:
// a key has no refresh token (nothing renews it — a new one is issued in the console), and no client
// id either, because a client id is what a *refresh* goes out as and there is no refresh. The expiry
// is left at zero for the same reason it is never consulted for a key: the server is the authority
// on whether a key still works, and a date kept here could only disagree with it.
func (s EnvStore) Load() (Credentials, bool, error) {
	presented := s.presented()
	if presented == "" {
		return s.Fallback.Load()
	}
	return Credentials{AccessToken: presented}, true, nil
}

// presented is the value with the whitespace the world adds to environment variables taken off.
//
// **Trimmed, because every way a variable gets set can add a newline**: `export K=$(cat file)`, a
// Docker secret file, a Kubernetes Secret, a line appended to a `.env`. A trailing newline goes into
// the `Authorization` header, where it is either an invalid header — a transport error that says
// nothing about the credential — or a token that hashes to nothing, which the person reads as「这把
// key 不对」and has no way to explain. The paste path trims for the same reason: two ways in, one
// rule.
func (s EnvStore) presented() string {
	return strings.TrimSpace(s.Value)
}

// Stored answers the one question the environment cannot: what this machine itself is holding.
//
// **Not `Load`, and the two callers both need the difference.** `login` is about to replace what is
// *stored* — a variable is not something a login can replace — and `logout` clears what is stored,
// so it has to know whether there was anything there. Read through `Load` and both sentences become
// about the variable: `login` would hand a key from the environment to the OAuth revocation path
// (see `revokeReplaced`), and `logout` would report clearing a copy it never touched.
func (s EnvStore) Stored() (Credentials, bool, error) {
	return s.Fallback.Load()
}

// Save writes to the machine's store, not to the environment — a process cannot set its parent's
// variables, and pretending otherwise would be a login that reports success and changes nothing.
func (s EnvStore) Save(c Credentials) error {
	return s.Fallback.Save(c)
}

// Clear removes the machine's copy and leaves the environment alone, which is the honest reading of
// `logout` while a variable is set: what can be cleared is cleared, and the caller says the rest.
func (s EnvStore) Clear() error {
	return s.Fallback.Clear()
}

// Where answers the storage question — where a credential would be written and cleared — and the
// environment never changes that. A login while the variable is set really does write to the
// keychain, so a message built from this is true either way.
func (s EnvStore) Where() string {
	return s.Fallback.Where()
}

// Source answers the other question: what the next command will actually present.
//
// Two questions rather than one because they have different answers while the variable is set, and
// conflating them is how a message ends up saying a credential was stored in the environment.
func (s EnvStore) Source() string {
	if s.presented() == "" {
		return s.Fallback.Where()
	}
	return "环境变量 " + s.Var
}

// FromEnv says whether this credential came from the environment, for the commands that have to
// explain what they did not do — `logout` above all: there is nothing on this machine to sign out of.
func (s EnvStore) FromEnv() bool {
	return s.presented() != ""
}

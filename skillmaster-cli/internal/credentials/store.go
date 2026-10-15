// Package credentials is where the CLI keeps what a login left behind.
//
// ADR 0025 decides the shape: the OS keychain first, a file with 0600 permissions as the
// fallback, and the person told when the fallback is what happened. This package is that
// decision, and the two places it is easy to get wrong are both in the platform files next to
// this one — see keychain.go.
package credentials

import (
	"encoding/json"
	"time"
)

// Credentials is one authorization's worth of material.
//
// The **access token is stored too**, and that is not an optimisation: refreshing rotates the
// refresh token (ADR 0024), so a CLI that refreshed on every invocation would rotate on every
// invocation — burning through the chain, spending a round trip, and making the "keep me signed
// in" clock move for somebody who never opened the tool. Reusing the short-lived token until it
// is nearly expired is what makes the long-lived one long-lived.
type Credentials struct {
	ClientID     string    `json:"client_id"`
	AccessToken  string    `json:"access_token"`
	RefreshToken string    `json:"refresh_token"`
	ExpiresAt    time.Time `json:"expires_at"`
	// SavedAt is when this copy was written down, and it is how two copies of the same credential are
	// ordered — see `newerCredential` in responsive.go for what "the newer one" decides and why the
	// expiry date cannot answer it. Zero on a blob written before this field existed, which is what
	// keeps that function correct for them.
	SavedAt time.Time `json:"saved_at,omitempty"`
}

// Blob is the stored form. Marshalled to JSON for both stores, so the two agree byte for byte and
// a credential can be moved between them by hand when somebody has to.
//
// **It stamps itself**, rather than each store stamping what it writes: the moment a credential was
// written down is a property of the stored form, and there are two stores — a rule applied at both
// of them is a rule one of them will forget. `SavedAt` therefore reads "when this blob was last
// produced", which is the only sense in which one copy is newer than another.
func (c Credentials) Blob() ([]byte, error) {
	c.SavedAt = time.Now()
	return json.Marshal(c)
}

// ParseBlob is the inverse. An empty document is not an error — it is how "nothing stored" reads.
func ParseBlob(blob []byte) (Credentials, error) {
	var c Credentials
	if len(blob) == 0 {
		return c, nil
	}
	err := json.Unmarshal(blob, &c)
	return c, err
}

// Store is one place a credential can live.
type Store interface {
	// Load returns the credential, and whether there was one. Not finding one is not an error:
	// "not signed in" is the ordinary state of a machine that has never run `login`.
	Load() (Credentials, bool, error)
	Save(Credentials) error
	// Delete removes it. Absent already is success — `logout` twice is not a failure. **A store that
	// could not be asked is not an absent credential**, and says so: the responsive keychain reports
	// its own timeout, because a credential it did not remove is still a credential.
	Delete() error
	// Where says where this store keeps things, so a message can name the place rather than
	// saying "somewhere".
	Where() string
}

// Open picks the store for this machine.
//
// The second return value is a sentence to show the person, and it is empty in the ordinary case.
// It exists because ADR 0025 requires the fallback to be **announced**: a credential in a plain
// file is a different promise from one in a keychain, and the person is the only one who can
// decide whether that is acceptable on this machine.
//
// The choice is made from the environment, never by trying the keychain and seeing. Trying is the
// expensive mistake in both directions: on a headless Linux box it blocks for about three seconds
// waiting for a session bus that will never answer — on *every* invocation — and on macOS it
// raises a permission prompt, which in front of an agent is a hung task.
//
// **There is a second decision, and it cannot be made here.** Whether there *is* a keychain is a
// question about the machine; whether it will *answer* is a question about the moment, and the only
// way to ask it is to call it and time the call — a locked keychain waits for a person forever. So
// the keychain store is wrapped in a deadline (see responsiveKeychain) and may move to the file
// later in the run. That is why the notice cannot be exhaustive: it says what happened at the
// start, and `Store.Where` is what says where the credential ended up.
func Open(path, server string) (Store, string) {
	return openWith(keychainUsable(), path, server)
}

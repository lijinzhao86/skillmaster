// Package pkce implements the proof key the CLI proves itself with.
//
// The CLI is a public client: it is installed on somebody's machine, so it has nowhere to
// keep a secret (RFC 8252 §8.5). PKCE is therefore not an extra layer on top of a client
// secret — it is the only thing standing between an authorization code and whoever gets hold
// of it. The code comes back through a loopback redirect, and anything that can watch that
// redirect would otherwise be able to redeem it.
package pkce

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"strings"
)

// VerifierLength is 32 random bytes, which is 43 base64url characters.
//
// RFC 7636 §4.1 allows 43 to 128. The floor is the shortest value the spec considers to carry
// enough entropy, and every byte past it buys a longer URL for nothing.
const VerifierLength = 32

// ErrMalformedVerifier is returned by Verify for a verifier outside RFC 7636's shape.
var ErrMalformedVerifier = errors.New("code verifier is not 43-128 characters of [A-Za-z0-9-._~]")

// NewVerifier returns a fresh code verifier.
//
// crypto/rand, never math/rand: the value is a credential for the length of one exchange, and a
// predictable one lets somebody who can see the challenge compute the verifier.
func NewVerifier() (string, error) {
	buf := make([]byte, VerifierLength)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(buf), nil
}

// Challenge is the S256 transformation of a verifier: base64url(sha256(verifier)), unpadded.
//
// S256 and nothing else. RFC 7636 also defines a "plain" method whose challenge is the verifier
// itself, which offers no protection at all against the interception this exists to stop; the
// server accepts only S256 (auth_code.method says so), so a client that offered plain would be
// refused rather than quietly downgraded.
func Challenge(verifier string) string {
	sum := sha256.Sum256([]byte(verifier))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

// Verify reports whether a verifier has the shape RFC 7636 §4.1 requires.
//
// Checked by the client before it starts, not only by the server after. A short verifier makes
// S256's strength nominal rather than real, and the failure would otherwise arrive as a rejected
// token request — long after the browser has been opened and the person has consented.
func Verify(verifier string) error {
	if len(verifier) < 43 || len(verifier) > 128 {
		return ErrMalformedVerifier
	}
	for _, r := range verifier {
		if !strings.ContainsRune(
			"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~", r) {
			return ErrMalformedVerifier
		}
	}
	return nil
}

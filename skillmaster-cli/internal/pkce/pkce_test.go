package pkce

import (
	"strings"
	"testing"
)

func TestNewVerifierHasTheShapeTheSpecRequires(t *testing.T) {
	verifier, err := NewVerifier()
	if err != nil {
		t.Fatalf("generating a verifier: %v", err)
	}
	if err := Verify(verifier); err != nil {
		t.Fatalf("a freshly generated verifier was refused: %v", err)
	}
	// 32 bytes of base64url, unpadded. Asserted as a length rather than as "long enough" so that a
	// change to VerifierLength has to be deliberate.
	if len(verifier) != 43 {
		t.Fatalf("verifier is %d characters, want 43: %q", len(verifier), verifier)
	}
}

func TestNewVerifierDoesNotRepeat(t *testing.T) {
	// Two calls must not agree. A constant would pass every other test here and be the whole
	// vulnerability, since the challenge would then be a constant too.
	first, err := NewVerifier()
	if err != nil {
		t.Fatalf("generating a verifier: %v", err)
	}
	second, err := NewVerifier()
	if err != nil {
		t.Fatalf("generating a verifier: %v", err)
	}
	if first == second {
		t.Fatal("two verifiers were identical")
	}
}

func TestChallengeIsTheKnownS256OfTheRFCsExample(t *testing.T) {
	// RFC 7636 Appendix B. The expected value is the spec's, so this fails if the transformation
	// stops being the one servers implement.
	const verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
	const want = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

	if got := Challenge(verifier); got != want {
		t.Fatalf("challenge = %q, want %q", got, want)
	}
}

func TestVerifyAcceptsAndRefusesAtTheSpecsBoundaries(t *testing.T) {
	const fortyThree = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG" // 26 + 10 + 7
	const fortyTwo = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEF"    // one short of the floor

	cases := []struct {
		name     string
		verifier string
		wantErr  bool
	}{
		{"the shortest legal verifier", fortyThree, false},
		{"one character short", fortyTwo, true},
		{"the longest legal verifier", strings.Repeat("a", 128), false},
		{"one character too long", strings.Repeat("a", 129), true},
		{"empty", "", true},
		// Each of these is legal in base64url's *padded* alphabet or in a URL, and none is legal in
		// a verifier. A client that emitted one would produce a challenge the server refuses, and
		// the person would have consented by then.
		{"a space", fortyThree[:42] + " ", true},
		{"a percent", fortyThree[:42] + "%", true},
		{"a plus", fortyThree[:42] + "+", true},
		{"a slash", fortyThree[:42] + "/", true},
		{"padding", fortyThree[:42] + "=", true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			err := Verify(c.verifier)
			if c.wantErr && err == nil {
				t.Fatalf("accepted %q", c.verifier)
			}
			if !c.wantErr && err != nil {
				t.Fatalf("refused a legal verifier: %v", err)
			}
		})
	}
}

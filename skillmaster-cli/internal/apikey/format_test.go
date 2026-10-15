package apikey

import (
	"strings"
	"testing"
)

// What a paste is checked against, and what is deliberately *not* checked here.
//
// The two questions are different and the difference is the point: `HasShape` runs before a round
// trip and may only refuse what could not possibly be a key, while `IsKey` routes a credential the
// server has already refused and must agree with the server's own routing — prefix, and nothing more.
// A string that satisfies neither is an OAuth token, and every one of these cases is really asking
// "which of the two rules does this line sit on".
func TestIsKeyRoutesOnThePrefixAlone(t *testing.T) {
	cases := []struct {
		name  string
		token string
		want  bool
	}{
		{"a whole key", Prefix + strings.Repeat("a", secretLength), true},
		// Short is still *routed* as a key: the server decides by hashing, and this function is not a
		// second opinion about what a key is. It only has to keep a token from being treated as one.
		{"a truncated key", Prefix + "abc", true},
		{"an OAuth access token", "eyJhbGciOiJIUzI1NiJ9.payload.signature", false},
		// **The bare prefix is a key.** The server's own routing test pins the same string to the same
		// answer (`ApiKeyFormatTest.routesToApiKeys`), and this side has to agree with it: routing is
		// prefix-and-nothing-else on both, and the hash lookup is what decides afterwards. A length
		// floor here would send `skm_` down the OAuth renewal path — reporting「请重新运行 login
		// --client-credentials」for a credential that was simply pasted short.
		{"the prefix without a secret", Prefix, true},
		{"empty", "", false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := IsKey(c.token); got != c.want {
				t.Fatalf("IsKey(%q) = %v, want %v", c.token, got, c.want)
			}
		})
	}
}

func TestHasShapeIsThePasteCheck(t *testing.T) {
	cases := []struct {
		name  string
		token string
		want  bool
	}{
		{"a whole key", Prefix + strings.Repeat("a", secretLength), true},
		{"the base64url alphabet, both ends of it", Prefix + "-_" + strings.Repeat("a", secretLength-2), true},
		// The likeliest way a paste goes wrong: something was cut off on the way through a terminal,
		// a chat window, or a click that selected one line of two.
		{"truncated", Prefix + strings.Repeat("a", secretLength-1), false},
		{"something appended", Prefix + strings.Repeat("a", secretLength+1), false},
		// Padding and `+` are in the standard alphabet and not in the URL-safe one, so a key that
		// acquired either is not a key this service could have issued.
		{"base64 padding", Prefix + strings.Repeat("a", secretLength-1) + "=", false},
		{"the standard alphabet", Prefix + "+" + strings.Repeat("a", secretLength-1), false},
		{"the prefix alone", Prefix, false},
		{"an OAuth token that happens to be long", strings.Repeat("a", TotalLength), false},
		{"empty", "", false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := HasShape(c.token); got != c.want {
				t.Fatalf("HasShape(%q) = %v, want %v", c.token, got, c.want)
			}
		})
	}
}

// The length the refusal quotes back to somebody whose paste was cut off, which is only useful if it
// is the server's own number: 32 bytes, base64url, no padding.
func TestTotalLengthMatchesWhatTheServerIssues(t *testing.T) {
	if TotalLength != 47 {
		t.Fatalf("TotalLength = %d, want 47 (2 for the prefix, 43 for 32 base64url bytes)", TotalLength)
	}
}

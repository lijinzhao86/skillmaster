package config

import (
	"os"
	"path/filepath"
	"testing"
)

// The fallback credential file is named per server, because the keychain is.
//
// One shared path broke the invariant the keychain's own key exists for, and broke it in the
// direction that costs something: a machine signed in to two servers — somebody's local stack and
// the real one, which is the case that comment names — would load the *other* one's credential, so
// its bearer token went to an origin that never issued it, and the 401 that followed presented its
// refresh token there, read `invalid_grant`, and deleted it. Using one server signed the other out.
func TestTheFallbackCredentialIsPerServer(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(home, ".config"))

	one, err := CredentialPath("https://one.example.test")
	if err != nil {
		t.Fatalf("CredentialPath: %v", err)
	}
	two, err := CredentialPath("https://two.example.test")
	if err != nil {
		t.Fatalf("CredentialPath: %v", err)
	}
	again, err := CredentialPath("https://one.example.test")
	if err != nil {
		t.Fatalf("CredentialPath: %v", err)
	}

	if one == two {
		t.Fatalf("two servers share one credential file: %s", one)
	}
	if one != again {
		t.Fatalf("the same server gave two paths: %s and %s", one, again)
	}
	if filepath.Dir(one) != filepath.Dir(two) {
		t.Fatalf("the two files are not beside each other: %s / %s", one, filepath.Dir(two))
	}
	dir, err := Dir()
	if err != nil {
		t.Fatalf("Dir: %v", err)
	}
	if filepath.Dir(one) != dir {
		t.Fatalf("the credential is not inside Dir(): %s", one)
	}
	// Naming a path is all this does: the two stores create their own files.
	if _, err := os.Stat(one); err == nil {
		t.Fatal("CredentialPath created something")
	}
}

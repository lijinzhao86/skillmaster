package config

import (
	"os"
	"path/filepath"
	"strings"
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

// The pin file follows the same rule for a weaker reason, and the rule is what is being tested: one
// server's pins must not be readable as another's, or a version resolved against one server would be
// sent to the other — where it resolves to nothing, or, if the names collide, to some other skill's
// version. A wrong answer arriving without a complaint is the failure worth one hash to avoid.
func TestThePinFileIsPerServerAndBesideTheCredential(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(home, ".config"))

	one, err := PinPath("https://one.example.test")
	if err != nil {
		t.Fatalf("PinPath: %v", err)
	}
	two, err := PinPath("https://two.example.test")
	if err != nil {
		t.Fatalf("PinPath: %v", err)
	}
	credential, err := CredentialPath("https://one.example.test")
	if err != nil {
		t.Fatalf("CredentialPath: %v", err)
	}

	if one == two {
		t.Fatalf("two servers share one pin file: %s", one)
	}
	if filepath.Dir(one) != filepath.Dir(credential) {
		t.Fatalf("the pins are not beside the credential: %s / %s", filepath.Dir(one),
			filepath.Dir(credential))
	}
	// A different name from the credential's, so that neither can ever be read as the other.
	if filepath.Base(one) == filepath.Base(credential) {
		t.Fatalf("the pin file is named like the credential: %s", filepath.Base(one))
	}
	if _, err := os.Stat(one); err == nil {
		t.Fatal("PinPath created something")
	}
}

// The pin records live in a directory per server, and it is the same hash the old table used.
//
// **The two paths must not collide.** The pre-v24 table is a *file* named `pins-<hash>`; the records
// live in a *directory* named `pins/<hash>`. If they ever met at one name, an upgrade would have to
// delete one of them — and the CLI does not delete (ADR 0101).
func TestThePinDirectoryIsPerServerAndDoesNotCollideWithTheLegacyTable(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(home, ".config"))

	one, err := PinDir("https://one.example.test")
	if err != nil {
		t.Fatalf("PinDir: %v", err)
	}
	two, err := PinDir("https://two.example.test")
	if err != nil {
		t.Fatalf("PinDir: %v", err)
	}
	legacy, err := PinPath("https://one.example.test")
	if err != nil {
		t.Fatalf("PinPath: %v", err)
	}

	if one == two {
		t.Fatalf("two servers share one pin directory: %s", one)
	}
	if filepath.Base(filepath.Dir(one)) != "pins" {
		t.Errorf("the records are not under a `pins` directory: %s", one)
	}
	if one == legacy || filepath.Dir(one) == legacy {
		t.Errorf("the new store and the legacy table share a path: %s / %s", one, legacy)
	}
	dir, err := Dir()
	if err != nil {
		t.Fatalf("Dir: %v", err)
	}
	if filepath.Dir(filepath.Dir(one)) != dir {
		t.Errorf("the pin directory is not inside Dir(): %s", one)
	}
	// Naming a path is all this does: the store creates its own directory.
	if _, err := os.Stat(one); err == nil {
		t.Fatal("PinDir created something")
	}
}

// **The state directory is probed, not assumed** (v51). An agent's sandbox refuses writes outside
// its workspace, which makes the ordinary location unreachable; the CLI then has to write its
// credential and pins somewhere else rather than fail. The override is tried first, the ordinary
// location second, `$TMPDIR` last — and a fallback is reported rather than applied silently.
func TestTheStateDirectoryHonoursItsOverride(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	want := filepath.Join(home, "state")
	t.Setenv(HomeEnv, want)

	dir, err := Dir()
	if err != nil {
		t.Fatalf("Dir: %v", err)
	}
	if dir != want {
		t.Errorf("Dir = %s, want the override %s", dir, want)
	}
	if note := DirNote(); note != "" {
		t.Errorf("a working override was reported as a fallback: %s", note)
	}
}

func TestTheStateDirectoryFallsBackWhenTheOverrideIsNotWritable(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("TMPDIR", filepath.Join(home, "tmp"))

	readOnly := filepath.Join(home, "ro")
	if err := os.MkdirAll(readOnly, 0o500); err != nil {
		t.Fatalf("making the read-only directory: %v", err)
	}
	t.Cleanup(func() { _ = os.Chmod(readOnly, 0o700) })
	t.Setenv(HomeEnv, readOnly)

	dir, err := Dir()
	if err != nil {
		t.Fatalf("Dir: %v", err)
	}
	if dir == readOnly {
		t.Fatalf("Dir returned the directory it just proved it cannot write")
	}
	if err := os.WriteFile(filepath.Join(dir, "probe"), nil, 0o600); err != nil {
		t.Errorf("the chosen directory is not writable: %v", err)
	}
	note := DirNote()
	if note == "" {
		t.Fatal("no note for a fallback: a person cannot tell where the credential went")
	}
	// **The note has to name the directory that refused, not the ordinary one by assumption.** When
	// the override is the unwritable candidate, blaming the ordinary directory produces "`<dir>` is
	// not writable, using `<dir>`" — one sentence that contradicts itself and describes nothing.
	if !strings.Contains(note, readOnly) {
		t.Errorf("the note does not name the directory that refused: %q", note)
	}
	if !strings.Contains(note, dir) {
		t.Errorf("the note does not name the directory in use: %q", note)
	}
}

// **Reading and writing are two different places, and the write must not follow the read.**
//
// A sandbox mounts the ordinary state directory read-only. A file that is there can still be read —
// that is what keeps a signed-in machine signed in — but writing to that path fails, so the write
// path has to be the one the probe found. One path doing both jobs produced a `login` that could not
// save and a credential that could not be found (ADR 0164 §2).
func TestAStateFileIsWrittenWhereItCanBeAndReadWhereItIs(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(home, "config"))
	t.Setenv("TMPDIR", filepath.Join(home, "tmp"))

	rel := filepath.Join("pins", "deadbeef")
	ordinary, err := Dir()
	if err != nil {
		t.Fatalf("Dir: %v", err)
	}
	existing := filepath.Join(ordinary, rel)
	if err := os.MkdirAll(filepath.Dir(existing), 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.WriteFile(existing, []byte("{}"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	if err := os.Chmod(ordinary, 0o500); err != nil {
		t.Fatalf("chmod: %v", err)
	}
	t.Cleanup(func() { _ = os.Chmod(ordinary, 0o700) })

	write, older, err := StatePaths(rel)
	if err != nil {
		t.Fatalf("StatePaths: %v", err)
	}
	if write == existing {
		t.Fatalf("the write path is the read-only file %q", existing)
	}
	if older != existing {
		t.Errorf("older = %q, want the copy that is already there (%q)", older, existing)
	}
	fallback, err := Dir()
	if err != nil {
		t.Fatalf("Dir after the fallback: %v", err)
	}
	if !strings.HasPrefix(write, fallback+string(filepath.Separator)) {
		t.Errorf("the write path %q is not under the writable state directory %q", write, fallback)
	}
}

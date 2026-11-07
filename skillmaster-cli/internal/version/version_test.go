package version

import "testing"

// What `--version` prints for the release half. Two shapes, and the difference is the decision
// (ADR 0099): a beta's version string does not move between builds, so the build has to be there;
// a formal version already names one commit, so it must not grow a second identity.
func TestString(t *testing.T) {
	for _, tc := range []struct{ version, revision, want string }{
		{"1.0.2", "", "1.0.2"},
		{"1.1.0-beta", "a1b2c3d", "1.1.0-beta (a1b2c3d)"},
		{"dev", "", "dev"},
	} {
		Version, Revision = tc.version, tc.revision
		if got := String(); got != tc.want {
			t.Errorf("String() = %q, want %q", got, tc.want)
		}
	}
	Version, Revision = "dev", ""
}

// Channel is the download base, and the override is the only way in. The trailing slash is the one
// thing callers must not have to think about: both forms end up meaning the same directory.
func TestChannel(t *testing.T) {
	original := UpdateBase
	defer func() { UpdateBase = original }()

	UpdateBase = "https://dl.example/"
	t.Setenv(UpdateBaseEnv, "")
	if got := Channel(); got != "https://dl.example" {
		t.Errorf("Channel() = %q, want the compiled base without the slash", got)
	}

	t.Setenv(UpdateBaseEnv, " https://mirror.example/base/ ")
	if got := Channel(); got != "https://mirror.example/base" {
		t.Errorf("Channel() = %q, want the override, trimmed", got)
	}
}

package auth

import "testing"

// The half of "can this machine open a URL" that `LookPath` cannot answer.
//
// The case is an ordinary CI image: `xdg-utils` is installed because something else wanted it, and
// there is no `$DISPLAY` and no session bus. `LookPath` says the launcher is there, so the login
// prints an authorization URL and listens on the loopback port until the five-minute timeout — the
// "command that looks like it is working" this check exists to prevent.
func TestALauncherWithNoScreenIsNotABrowser(t *testing.T) {
	cases := []struct {
		name    string
		goos    string
		display string
		wayland string
		want    bool
	}{
		{"a linux desktop", "linux", ":0", "", true},
		{"a wayland session", "linux", "", "wayland-0", true},
		{"a headless container", "linux", "", "", false},
		{"macOS, which has no such variable", "darwin", "", "", true},
		{"windows, likewise", "windows", "", "", true},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			t.Setenv("DISPLAY", c.display)
			t.Setenv("WAYLAND_DISPLAY", c.wayland)

			if got := hasSomewhereToDraw(c.goos); got != c.want {
				t.Fatalf("hasSomewhereToDraw(%q) = %v, want %v", c.goos, got, c.want)
			}
		})
	}
}

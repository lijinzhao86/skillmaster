package auth

import (
	"fmt"
	"os"
	"os/exec"
	"runtime"
)

// OpenBrowser asks the operating system to open a URL.
//
// Best effort, and the caller treats it that way: the URL is printed either way, because there are
// machines with no browser to open it in and a person in front of them who can paste it somewhere.
func OpenBrowser(url string) error {
	command, args, err := browserCommand(url)
	if err != nil {
		return err
	}
	// Not `Run` with output captured: this process outlives nothing, and a browser that prints to
	// stderr on the way up is not an error worth failing a login over.
	return exec.Command(command, args...).Start()
}

// BrowserAvailable answers "is there anything on this machine that opens a URL".
//
// **Asked by commands that would start a login on their own, which is a different moment from
// `login` itself.** Since v68 the login no longer needs a browser on *this* machine: the link can be
// opened anywhere, so an explicit `skmr login` prints it and waits regardless. What still depends on
// the answer is a command that logs in for itself, because there nobody asked for a ten-minute wait,
// and a machine with nothing to open the link in would sit through one for a step nobody can finish.
// The answer comes from the same platform switch the opening uses, rather than from a second opinion
// about what a browser is.
func BrowserAvailable() bool {
	if _, _, err := browserCommand(""); err != nil {
		return false
	}
	return hasSomewhereToDraw(runtime.GOOS)
}

// hasSomewhereToDraw is the other half on the platforms that have such a thing.
//
// **`xdg-open` installed is not a browser**, and the image where that matters is the ordinary one: a
// CI base with `xdg-utils` pulled in by something else, no `$DISPLAY`, no session bus. There,
// `LookPath` answers yes, and a command that logs in for itself would block on a wait whose only
// possible end is ten minutes of silence — the "command that looks like it is working" this check
// exists to prevent, with the person on the other side of it. The variable is the platform's own
// statement that there is a screen to open something on.
//
// Deliberately not a probe of the launcher (running `xdg-open` to see what happens would open a
// window on the machines where it works), and deliberately nothing on macOS or Windows, which have
// no such variable and where the launcher is part of the system.
func hasSomewhereToDraw(goos string) bool {
	if goos != "linux" {
		return true
	}
	return os.Getenv("DISPLAY") != "" || os.Getenv("WAYLAND_DISPLAY") != ""
}

// browserCommand is the platform switch both of the above read, so the two can never disagree.
func browserCommand(url string) (string, []string, error) {
	var command string
	var args []string

	switch runtime.GOOS {
	case "darwin":
		command, args = "open", []string{url}
	case "windows":
		// `rundll32` rather than `cmd /c start`: `start` is a shell builtin whose argument parsing
		// treats `&` in a URL as a command separator, and an authorization URL has several.
		command, args = "rundll32", []string{"url.dll,FileProtocolHandler", url}
	default:
		command, args = "xdg-open", []string{url}
	}

	if _, err := exec.LookPath(command); err != nil {
		return "", nil, fmt.Errorf("%s is not installed", command)
	}
	return command, args, nil
}

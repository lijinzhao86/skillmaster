package auth

import (
	"fmt"
	"os/exec"
	"runtime"
)

// OpenBrowser asks the operating system to open a URL.
//
// Best effort, and the caller treats it that way: the URL is printed either way, because there are
// machines with no browser to open it in and a person in front of them who can paste it somewhere.
func OpenBrowser(url string) error {
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
		return fmt.Errorf("%s is not installed", command)
	}
	// Not `Run` with output captured: this process outlives nothing, and a browser that prints to
	// stderr on the way up is not an error worth failing a login over.
	return exec.Command(command, args...).Start()
}

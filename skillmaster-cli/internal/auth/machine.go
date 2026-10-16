package auth

import (
	"context"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"time"
)

// nameTimeout bounds each system lookup.
//
// **A lookup that hangs must not become a login that hangs.** These are short subprocesses, and the
// reason for a bound is not the ones that work — it is the platform where the command is present but
// the service behind it is not (a container with `hostnamectl` installed and no systemd, whose dbus
// call runs out its own timeout of tens of seconds). Two seconds is several times what an answer
// takes, and the cost of giving up is that the page opens with an empty box — a state it draws
// anyway, and one keystroke from where the person was going.
const nameTimeout = 2 * time.Second

// MachineName is the name a person gave this machine, for the sign-in page's 名称 field.
//
// **The page cannot know it and this process can**, which is the whole reason the value travels on
// the address (`name=`, v4 §接口 ①). The browser does not hand out hostnames, and the server sees an
// IP and a User-Agent — so of the three parties in this flow, the CLI is the only one running on the
// machine being named. Without it the field starts empty and every login costs a person a name they
// already gave their computer (`design/README.md` §怎么打开「授权请求」).
//
// **The system's own answer beats `os.Hostname()`, and the difference is visible in the result.** The
// two disagree on this Mac: `os.Hostname()` answers `lijinzhaodeMBP.lan` — lowercase, no spaces, and a
// resolver suffix — while `scutil --get ComputerName` answers `lijinzhao的MacBook Pro`, which is what
// the person typed in System Settings and therefore what they will recognize in 「登录设备」 a week
// later. Recognizing the row is the only thing that column is for, so the recognizable answer is the
// one to send.
//
// **The value is not policed here.** It goes on the address as the system gave it; the page's field
// caps typing at 60 characters, and a name longer than that is a machine nobody can mistake for
// another — which is the property the name is there to have.
func MachineName() string {
	if name := platformName(runtime.GOOS); name != "" {
		return name
	}
	return hostname()
}

// platformName asks the operating system for the name a person set, on the platforms that keep one.
//
// Takes the platform as an argument rather than reading `runtime.GOOS` inside, the way
// `hasSomewhereToDraw` does: it is what lets the switch itself be tested on the machine the tests run
// on.
//
// **Nothing here is a requirement.** Every branch may answer nothing — a stripped image with no
// `scutil`, a Windows box with an unset environment — and the caller falls through to
// `os.Hostname()`, which is present wherever Go runs.
func platformName(goos string) string {
	switch goos {
	case "darwin":
		// The name from System Settings → General → Sharing, which is the one a person chose.
		return commandOutput("scutil", "--get", "ComputerName")
	case "windows":
		// Windows keeps it in the environment, so no subprocess is needed.
		return strings.TrimSpace(os.Getenv("COMPUTERNAME"))
	case "linux":
		// `--pretty` is the human-readable half of `hostnamectl`: the same command without it prints
		// `Static hostname: …` and a table, which is a machine name with the machine's own output
		// wrapped around it.
		if name := commandOutput("hostnamectl", "--pretty"); name != "" {
			return name
		}
		return fileFirstLine("/etc/hostname")
	}
	return ""
}

// commandOutput runs a system command and reads its first line, or answers nothing.
//
// Silence on failure is the point rather than an oversight: this is a suggestion for a text field,
// and a machine whose name cannot be read is not a machine whose login should fail. The caller
// distinguishes "no name" from "the name is empty" by the former being what it falls through on.
func commandOutput(command string, args ...string) string {
	ctx, cancel := context.WithTimeout(context.Background(), nameTimeout)
	defer cancel()
	output, err := exec.CommandContext(ctx, command, args...).Output()
	if err != nil {
		return ""
	}
	return firstLine(string(output))
}

// fileFirstLine is Linux's second answer, for a machine with no `hostnamectl` to ask.
func fileFirstLine(path string) string {
	contents, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return firstLine(string(contents))
}

// hostname is the answer of last resort, with the resolver's suffix taken off.
//
// **`os.Hostname()` is a machine name, not the name somebody gave the machine**, and the difference
// shows up exactly where this value is read: as a row on 「登录设备」. On this Mac it answers
// `lijinzhaodeMBP.lan`, and `.lan` is not part of what the person calls the laptop — it is what the
// router called it. The suffix list is the set of names a hostname gets for being reachable on a
// local network rather than the set of names a person types, and it is deliberately short: a
// corporate `host.corp.example.com` keeps its domain, because there the domain is how you tell two
// machines of the same name apart.
func hostname() string {
	name, err := os.Hostname()
	if err != nil {
		return ""
	}
	return withoutLocalSuffix(strings.TrimSpace(name))
}

// withoutLocalSuffix takes one local-network suffix off the end of a hostname.
func withoutLocalSuffix(name string) string {
	for _, suffix := range []string{".lan", ".local", ".internal"} {
		// Not `strings.TrimSuffix`, which is exact: the suffix is whatever the resolver produced,
		// and `Foo.LAN` and `foo.lan` are one suffix. Comparing without case but cutting the
		// original keeps the rest of the name spelled the way it arrived — `lijinzhaodeMBP` is how
		// that Mac reads, and lowercasing it would be this function inventing a spelling.
		//
		// The length check keeps a name that *is* a suffix — `.lan`, which no real machine is called
		// — from being trimmed down to nothing.
		if len(name) > len(suffix) && strings.EqualFold(name[len(name)-len(suffix):], suffix) {
			return name[:len(name)-len(suffix)]
		}
	}
	return name
}

// firstLine is the first line of some output, trimmed.
//
// Every source above may answer with more than a name: these commands print a trailing newline, and
// a file read may be a file with several lines. The field this value lands in is a single-line
// input, so anything past the first line would be text the person cannot see they are carrying.
func firstLine(output string) string {
	line, _, _ := strings.Cut(output, "\n")
	return strings.TrimSpace(line)
}

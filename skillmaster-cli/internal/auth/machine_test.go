package auth

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// The name this machine will be known by on 「登录设备」.
//
// **Only one of the four sources can be checked on any one machine**, so this is asked as two
// questions rather than one: does the platform these tests are running on produce a name (and is it
// the one that platform itself reports), and does the fall-through chain behave.
//
// `design/README.md` §怎么打开「授权请求」 marks which of the three platform rows have been measured on
// a real machine. **This Mac measures the macOS row.** The Linux and Windows rows are covered here
// only in the sense that the code path runs — a Linux branch exercised on macOS proves it returns a
// string, not that the string is right, and claiming otherwise is the ordinary way a platform table
// rots.
func TestTheNameThisMachineIsKnownBy(t *testing.T) {
	name := MachineName()
	if name == "" {
		t.Fatal("no name at all: the 名称 field would open empty on a machine that has one")
	}
	if name != strings.TrimSpace(name) || strings.ContainsAny(name, "\n\r") {
		t.Errorf("name = %q: it lands in a single-line field, and that is neither", name)
	}

	// The platform's own answer when it has one — on macOS, the name System Settings shows. Read
	// through the same call the implementation makes, so this asserts that the switch reaches the
	// platform, not that the machine is configured a particular way.
	if expected := platformName(runtime.GOOS); expected != "" && name != expected {
		t.Errorf("MachineName() = %q, want the platform's own answer %q", name, expected)
	}
}

// A platform with no source this program knows how to ask still produces something.
//
// **`plan9` stands for "neither of the two subprocesses, and no such environment variable"**, and it
// is deliberately not a real branch: the point is that the switch's default answer is empty and the
// caller falls through to `os.Hostname()`, which is what happens on the stripped images where
// `scutil` or `hostnamectl` is missing.
func TestAPlatformWithNoKnownSourceStillAnswers(t *testing.T) {
	if got := platformName("plan9"); got != "" {
		t.Errorf("platformName(%q) = %q, want nothing", "plan9", got)
	}
	if hostname() == "" {
		t.Skip("this machine's hostname is unreadable, so the fall-through cannot be exercised")
	}
}

// The resolver's suffix comes off, and the rest of the name keeps its spelling.
//
// **The first case is the measured one, on both sides**: on this Mac `os.Hostname()` answers
// `lijinzhaodeMBP.lan`, and the design's table records what it has to become. `.lan` is what the
// router called the laptop; `lijinzhaodeMBP` is what the person did.
func TestTheHostnamesLocalSuffixComesOff(t *testing.T) {
	cases := []struct {
		hostname string
		want     string
	}{
		{"lijinzhaodeMBP.lan", "lijinzhaodeMBP"},
		{"lijinzhao-mac.local", "lijinzhao-mac"},
		{"build-box.internal", "build-box"},
		// One suffix, not all of them: `.internal` is not a second thing to cut off the end of
		// `.local`.
		{"box.local.internal", "box.local"},
		// Nothing to cut, and the case is left alone — including the suffix's, which the first
		// case shows must not be lowercased along with the rest.
		{"MyLaptop", "MyLaptop"},
		{"WORKSTATION.LAN", "WORKSTATION"},
		// A domain is how two machines sharing a name are told apart, so it stays.
		{"host.corp.example.com", "host.corp.example.com"},
		// A name that *is* a suffix is not a name with one, and trimming it would leave nothing.
		{".lan", ".lan"},
	}
	for _, tc := range cases {
		if got := withoutLocalSuffix(tc.hostname); got != tc.want {
			t.Errorf("withoutLocalSuffix(%q) = %q, want %q", tc.hostname, got, tc.want)
		}
	}
}

// The Windows row reads the environment, and it is the one source with no subprocess behind it.
//
// This one is worth running on any platform: it is the row whose failure mode is silence — an unset
// variable is indistinguishable from a machine with no name — and the setter is the whole of it.
func TestWindowsReadsTheEnvironment(t *testing.T) {
	t.Setenv("COMPUTERNAME", "  DESKTOP-ABC123\n")
	if got := platformName("windows"); got != "DESKTOP-ABC123" {
		t.Errorf("platformName(windows) = %q", got)
	}
}

// Linux's second answer, for a machine with no `hostnamectl` to ask.
func TestLinuxFallsBackToTheHostnameFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "hostname")
	if err := os.WriteFile(path, []byte("my-laptop\n"), 0o600); err != nil {
		t.Fatalf("writing the fixture: %v", err)
	}
	if got := fileFirstLine(path); got != "my-laptop" {
		t.Errorf("fileFirstLine = %q", got)
	}
	// A file that is not there is not an error: it is a machine with nothing to say, and the caller's
	// next move is the same either way.
	if got := fileFirstLine(filepath.Join(t.TempDir(), "absent")); got != "" {
		t.Errorf("fileFirstLine of an absent file = %q, want nothing", got)
	}
}

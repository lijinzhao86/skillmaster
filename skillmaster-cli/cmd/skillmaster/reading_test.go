package main

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/pins"
)

// withTempHome points this machine's config directory at a temporary one, so a test exercises the pin
// file without touching the real one.
//
// Both variables, because `os.UserConfigDir` reads a different one per platform: `$HOME` on macOS and
// `$XDG_CONFIG_HOME` on Linux when it is set.
func withTempHome(t *testing.T) {
	t.Helper()
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(home, "config"))
	t.Setenv(config.ServerEnv, "http://pins.test")
}

// The round trip that makes `read` possible without a version in the command: `invoke` writes what it
// resolved, and a later process reads it back.
func TestPinForReadsWhatInvokeRemembered(t *testing.T) {
	withTempHome(t)

	if _, _, err := rememberPin("demo/hello", "1.2.3", "sha256:abc"); err != nil {
		t.Fatalf("rememberPin: %v", err)
	}

	pin, note, err := pinFor("demo/hello")
	if err != nil {
		t.Fatalf("pinFor: %v", err)
	}
	if pin != "1.2.3" {
		t.Errorf("pin = %q — the name is the spelling the address grammar uses", pin)
	}
	if note != "" {
		t.Errorf("a pinned read said something extra: %q", note)
	}
}

// A version whose author declared no name is remembered with an empty name and sent by its digest —
// the only thing such a version can be addressed by (ADR 0033).
func TestPinForSendsTheDigestWhenTheVersionHasNoName(t *testing.T) {
	withTempHome(t)

	if _, _, err := rememberPin("demo/hello", "", "sha256:abc"); err != nil {
		t.Fatalf("rememberPin: %v", err)
	}

	pin, _, err := pinFor("demo/hello")
	if err != nil {
		t.Fatalf("pinFor: %v", err)
	}
	if pin != "sha256:abc" {
		t.Errorf("pin = %q", pin)
	}
}

// Nothing remembered is said out loud rather than passed over.
//
// The design rests on `invoke` having run; a read that quietly used whatever is current would be the
// drift the pin replaced, with nothing on screen to show that it had happened. The message also says
// what to do, because a warning with no next step is just noise.
func TestPinForSaysWhenNothingWasInvoked(t *testing.T) {
	withTempHome(t)

	pin, note, err := pinFor("demo/hello")
	if err != nil {
		t.Fatalf("pinFor: %v", err)
	}
	if pin != "" {
		t.Errorf("an uninvoked address produced the pin %q — there is nothing to send", pin)
	}
	for _, want := range []string{"demo/hello", "还没 invoke", "skillmaster skill invoke"} {
		if !strings.Contains(note, want) {
			t.Errorf("the note does not say %q: %q", want, note)
		}
	}
}

// A version written into the address wins, and the store is not consulted at all.
//
// The corrupt file is the assertion: if `pinFor` so much as read the store on this path it would fail,
// and a caller who named a version explicitly would be blocked by a broken file that has nothing to do
// with what they asked for.
func TestAVersionInTheAddressWinsOutright(t *testing.T) {
	withTempHome(t)

	path, err := config.PinPath("http://pins.test")
	if err != nil {
		t.Fatalf("PinPath: %v", err)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}

	pin, note, err := pinFor("demo/hello@2.0.0")
	if err != nil {
		t.Fatalf("an explicit version was blocked by the store: %v", err)
	}
	if pin != "2.0.0" || note != "" {
		t.Errorf("pin = %q, note = %q", pin, note)
	}
}

// A 404 that happened while this machine's own pin was in force gets its own message.
//
// The server's 404 is one answer by design, so the CLI cannot tell from it which thing is missing —
// but it *does* know it pinned this request itself, and saying which pin failed is the difference
// between an answer and a puzzle. **It must not fall back to the current version**: that is the silent
// substitution the whole design exists to prevent.
func TestPinFailureNamesTheVersionItCouldNotResolve(t *testing.T) {
	cause := api.ErrNotFound
	err := pinFailure(cause, "sha256:abc", "demo/hello")

	if !errors.Is(err, api.ErrNotFound) {
		t.Error("the original answer was swallowed instead of wrapped")
	}
	for _, want := range []string{"sha256:abc", "skillmaster skill invoke demo/hello"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("the message does not say %q:\n%v", want, err)
		}
	}
	if strings.Contains(err.Error(), "当前") {
		t.Errorf("the message suggests falling back to the current version:\n%v", err)
	}
}

// **A binary file is still a file read at some version, and the line saying which one is the only
// thing that makes the local pin visible.** It was missing here once — the text path ended with the
// version and this one ended with the path — which made reading a binary the one operation whose
// version you could not see. The path stays on a line of its own, because a caller has to reproduce
// it exactly.
func TestABinaryReadStillSaysWhichVersionItWas(t *testing.T) {
	out := captureStdout(t, func() {
		if err := printBinary([]byte{0x00, 0x01, 0x02}, "assets/logo.png", 1,
			"（按 demo/hello@1.2.3 取。）"); err != nil {
			t.Fatalf("printBinary: %v", err)
		}
	})

	lines := strings.Split(strings.TrimRight(out, "\n"), "\n")
	if len(lines) != 3 {
		t.Fatalf("printed %d lines, want 3:\n%s", len(lines), out)
	}
	if !strings.HasPrefix(lines[0], "/") {
		t.Errorf("the first line is not a path on its own: %q", lines[0])
	}
	for _, want := range []string{"assets/logo.png", "二进制文件", "3 字节"} {
		if !strings.Contains(lines[1], want) {
			t.Errorf("the second line does not mention %q: %q", want, lines[1])
		}
	}
	if lines[2] != "（按 demo/hello@1.2.3 取。）" {
		t.Errorf("the last line does not name the version: %q", lines[2])
	}
	// And the bytes really are on disk at that path, so the line is usable rather than decorative.
	body, err := os.ReadFile(lines[0])
	if err != nil {
		t.Fatalf("reading the printed path: %v", err)
	}
	if !bytes.Equal(body, []byte{0x00, 0x01, 0x02}) {
		t.Errorf("the bytes on disk are %v", body)
	}
}

// **The manifest as the caller will use it**: every relpath alone on its line — that line is what goes
// into `read` — with a note under the two entries that need one, and the version named at the end.
//
// **No `uri` and no digest anywhere in it**, and that is the assertion worth keeping: both are long
// arbitrary strings (79 and 71 characters, measured) that this listing would otherwise be asking a
// caller to reproduce, and `read` needs neither — the pin already says which version.
func TestFilesListsRelpathsAloneAndSaysWhichVersionItListed(t *testing.T) {
	// The manifest order is the server's; this command does not reorder it, because a second opinion
	// about what a version contains is exactly what the manifest is for.
	files := []api.File{
		{Relpath: "SKILL.md", URI: "/api/v1/skills/demo/hello@1.2.3/files/SKILL.md",
			SHA256: "sha256:aaaa", Size: 15251},
		{Relpath: "assets/logo.png", URI: "/api/v1/skills/demo/hello@1.2.3/files/assets/logo.png",
			SHA256: "sha256:bbbb", Size: 2048, IsBinary: true},
		{Relpath: "references/notes.md", URI: "/api/v1/skills/demo/hello@1.2.3/files/references/notes.md",
			SHA256: "sha256:cccc", Size: 4096},
	}

	out := captureStdout(t, func() {
		printFiles(files, "demo/hello", "（按 demo/hello@1.2.3 列的。）")
	})

	want := "SKILL.md\n" +
		"    （正文，由 skillmaster skill invoke 加载）\n" +
		"assets/logo.png\n" +
		"    （二进制，read 只给路径不打字节）\n" +
		"references/notes.md\n" +
		"（共 3 个文件。取其中一个：skillmaster skill read demo/hello <相对路径>。）\n" +
		"（按 demo/hello@1.2.3 列的。）\n"
	if out != want {
		t.Errorf("printed\n%q\nwant\n%q", out, want)
	}
	for _, unwanted := range []string{"sha256:", "/api/v1/"} {
		if strings.Contains(out, unwanted) {
			t.Errorf("the listing carries %q:\n%s", unwanted, out)
		}
	}
}

// The command in the footer is one the caller can run, so it carries the address exactly as they wrote
// it: a listing of `demo/hello@1.0.0` must not hand back a command that reads whatever is current.
func TestFilesEchoesTheAddressTheCallerUsed(t *testing.T) {
	files := []api.File{{Relpath: "references/notes.md"}}

	out := captureStdout(t, func() {
		printFiles(files, "demo/hello@1.0.0", "（按 demo/hello@1.0.0 列的。）")
	})

	if !strings.Contains(out, "取其中一个：skillmaster skill read demo/hello@1.0.0 <相对路径>") {
		t.Errorf("the footer does not carry the address as given:\n%s", out)
	}
}

// A `read` of a path this version does not have names the way out of the dead end.
//
// The lookup is an exact match and nothing can repair a near miss, so a message that only says "there
// is no such file" leaves a caller guessing names — which is the shape enumeration exists to end.
func TestFileMissingPointsAtTheListing(t *testing.T) {
	err := fileMissing("notes.md", "demo/hello")

	for _, want := range []string{"notes.md", "skillmaster skill files demo/hello"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("the message does not say %q:\n%v", want, err)
		}
	}
}

// **A 404 means two different things and gets two different messages**, and the branch that decides
// which is the thing being tested here rather than the two sentences in isolation: a caller whose
// explicit version 404'd must not be told about a record on this machine, and must not be told to
// re-invoke a *bare* address — that resolves to whatever is current, so the advice would resolve their
// problem by handing them a different version, silently.
func TestA404IsExplainedByWhereTheVersionCameFrom(t *testing.T) {
	fromAddress := notFoundFor(api.ErrNotFound, "demo/hello@1.0.0", "1.0.0", "demo/hello")
	for _, want := range []string{"1.0.0", "skillmaster skill versions demo/hello", "skillmaster skill list"} {
		if !strings.Contains(fromAddress.Error(), want) {
			t.Errorf("the address case does not say %q:\n%v", want, fromAddress)
		}
	}
	for _, unwanted := range []string{"本地", "skill invoke"} {
		if strings.Contains(fromAddress.Error(), unwanted) {
			t.Errorf("the address case claims something about this machine (%q):\n%v",
				unwanted, fromAddress)
		}
	}

	// The same call with a bare address is the record case, and it still speaks about the record.
	fromRecord := notFoundFor(api.ErrNotFound, "demo/hello", "1.0.0", "demo/hello")
	if !strings.Contains(fromRecord.Error(), "本地记住的") {
		t.Errorf("the record case no longer says where the version came from:\n%v", fromRecord)
	}
	if !errors.Is(fromRecord, api.ErrNotFound) {
		t.Error("the original answer was swallowed instead of wrapped")
	}
}

// The split itself: which half of an address is the version, and whether there was one — including the
// bare `@`, which is neither a version nor the absence of one.
//
// This is the rule that decides which of the two messages above a failure gets, and it is also what
// `invoke` refuses a malformed address with, so it is asserted on its own.
func TestVersionInAddress(t *testing.T) {
	for _, c := range []struct {
		address  string
		want     string
		named    bool
		wantFail bool
	}{
		{address: "demo/hello"},
		{address: "demo/hello@1.2.3", want: "1.2.3", named: true},
		{address: "demo/hello@sha256:abc", want: "sha256:abc", named: true},
		{address: "我的命名空间/技能"},
		{address: "demo/hello@", wantFail: true},
	} {
		got, named, err := versionInAddress(c.address)
		if c.wantFail {
			if err == nil {
				t.Errorf("versionInAddress(%q) accepted a bare @", c.address)
			}
			continue
		}
		if err != nil {
			t.Errorf("versionInAddress(%q): %v", c.address, err)
		}
		if got != c.want || named != c.named {
			t.Errorf("versionInAddress(%q) = %q, %v; want %q, %v", c.address, got, named, c.want, c.named)
		}
	}
}

// **A bare `@` is refused, not read as "no version named".** Read quietly it would send an address the
// caller cannot have meant *and* skip the line saying which version the read is for — the one thing
// that must never be missing. The corrupt store is the second half of the assertion: it proves the
// refusal happens before anything on this machine is touched, so a broken file cannot be blamed for it.
func TestABareAtIsRefusedRatherThanIgnored(t *testing.T) {
	withTempHome(t)

	path, err := config.PinPath("http://pins.test")
	if err != nil {
		t.Fatalf("PinPath: %v", err)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}

	if _, _, err := pinFor("demo/hello@"); err == nil {
		t.Fatal("a bare @ was accepted")
	} else if !strings.Contains(err.Error(), "@") {
		t.Errorf("the refusal does not name the @ it refused: %v", err)
	}
}

// **Only a file that is actually the problem gets told to delete it.** The store fails two ways, and
// they want opposite advice: a corrupt file is a cache of version names and digests that the next
// `invoke` rebuilds, while an I/O failure — a directory that belongs to somebody else, a full disk —
// leaves a file that is perfectly good. Advice attached to both sends the operator to repair the wrong
// thing, and on a read-only directory it sends them somewhere they cannot even go.
func TestOnlyACorruptPinFileIsCalledDeletable(t *testing.T) {
	corrupt := pinsUnreadable(&pins.CorruptError{Path: "/somewhere/pins-abc", Err: errors.New("bad json")})
	for _, want := range []string{"删掉它是安全的", "invoke 会重建", "不会替你猜一个版本"} {
		if !strings.Contains(corrupt.Error(), want) {
			t.Errorf("the corrupt case does not say %q:\n%v", want, corrupt)
		}
	}

	denied := errors.New("创建 /somewhere：permission denied")
	io := pinsUnreadable(denied)
	if !errors.Is(io, denied) {
		t.Error("an I/O failure was not passed through as it came")
	}
	if strings.Contains(io.Error(), "删掉它是安全的") {
		t.Errorf("an I/O failure was told to delete a file that is fine:\n%v", io)
	}
}

// **The whole chain from an unreadable file to a message with a next step**, asserted on real files
// rather than on a hand-built error: the store decides the file is not a set of records, the command
// layer turns that into advice, and the `errors.As` in between is the part that could quietly stop
// matching — at which point the advice would vanish and nobody would notice until an operator was
// staring at a path with nothing to do about it.
//
// Two shapes, because they reach the same place by different routes: text that is not JSON at all, and
// `null`, which *is* JSON and unmarshals into a nil map without an error.
func TestAnUnreadablePinFileSurfacesWithItsAdvice(t *testing.T) {
	for _, c := range []struct{ name, content string }{
		{name: "text that is not JSON", content: "{not json"},
		{name: "a null document", content: "null"},
	} {
		t.Run(c.name, func(t *testing.T) {
			withTempHome(t)

			path, err := config.PinPath("http://pins.test")
			if err != nil {
				t.Fatalf("PinPath: %v", err)
			}
			if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
				t.Fatalf("mkdir: %v", err)
			}
			if err := os.WriteFile(path, []byte(c.content), 0o600); err != nil {
				t.Fatalf("write: %v", err)
			}

			_, _, err = rememberedPin("demo/hello")
			if err == nil {
				t.Fatal("an unreadable file was read as \"no record\"")
			}
			if !strings.Contains(err.Error(), "删掉它是安全的") {
				t.Errorf("the advice did not survive the trip out:\n%v", err)
			}
		})
	}
}

// The store's key is the address without its version, which is what lets a lookup work whichever
// spelling the version was invoked at.
func TestBareAddressDropsTheVersionAndNothingElse(t *testing.T) {
	for _, c := range []struct{ address, want string }{
		{"demo/hello", "demo/hello"},
		{"demo/hello@1.2.3", "demo/hello"},
		{"demo/hello@sha256:abc", "demo/hello"},
		{"我的命名空间/技能", "我的命名空间/技能"},
	} {
		if got := bareAddress(c.address); got != c.want {
			t.Errorf("bareAddress(%q) = %q, want %q", c.address, got, c.want)
		}
	}
}

// What sits under a version in the list: whether a bare address resolves to it, and when it first went
// live. The date is the date alone — somebody choosing a version is answering "which one", and the
// clock time has never decided that.
func TestVersionLine(t *testing.T) {
	for _, c := range []struct {
		name    string
		version api.VersionInfo
		want    string
	}{
		{
			name:    "the current one, with its date",
			version: api.VersionInfo{PublishedAt: "2026-10-06T10:00:00Z", IsCurrent: true},
			want:    "当前 · 2026-10-06",
		},
		{
			name:    "a superseded one is its date alone",
			version: api.VersionInfo{PublishedAt: "2026-10-01T10:00:00Z"},
			want:    "2026-10-01",
		},
		{
			name:    "current without a date still says which one it is",
			version: api.VersionInfo{IsCurrent: true},
			want:    "当前",
		},
		{
			name:    "and nothing at all prints nothing",
			version: api.VersionInfo{},
			want:    "",
		},
	} {
		t.Run(c.name, func(t *testing.T) {
			if got := versionLine(c.version); got != c.want {
				t.Errorf("versionLine = %q, want %q", got, c.want)
			}
		})
	}
}

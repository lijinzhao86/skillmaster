package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// resumeInto builds the same kind of continuation string the commands do, so a test can paste the
// offset it just read back into the next call — which is the property being checked, not the wording.
func resumeInto(address string) func(int) string {
	return func(next int) string {
		return fmt.Sprintf("skillmaster skill read %s notes.md --offset %d", address, next)
	}
}

// bodyOf is the content region of a command's output: everything above the first remark.
//
// Not a convenience — it is the contract these commands document, that a caller can treat everything
// above the first full-width bracket as the file. Tests exercising the content use it for the same
// reason a caller would.
func bodyOf(out string) string {
	if at := strings.Index(out, "（"); at >= 0 {
		return out[:at]
	}
	return out
}

// A body that fits comes out as exactly its own bytes, with nothing appended.
//
// This is `invoke`'s rendering, and **the byte-exactness is the point of it**: the bytes served are the
// bytes whose digest the manifest advertises, so this is the one output a caller may want to compare
// against the digest it was told. Nothing is added — no newline, no normalised line endings, no prefix.
func TestAShortBodyIsItsOwnBytesAndNothingElse(t *testing.T) {
	for _, c := range []struct {
		name    string
		content string
	}{
		{name: "ordinary text", content: "# notes\n\n- one\n- two\n"},
		{name: "no trailing newline", content: "no newline at the end"},
		{name: "CRLF", content: "# notes\r\n\r\n- one\r\n"},
		{name: "empty", content: ""},
		{name: "a single blank line", content: "\n"},
	} {
		t.Run(c.name, func(t *testing.T) {
			got := captureStdout(t, func() {
				printContent([]byte(c.content), 1, withoutLineNumbers, resumeInto("demo/hello"))
			})
			if got != c.content {
				t.Errorf("printed %q, want %q", got, c.content)
			}
		})
	}
}

// A file longer than the line budget is cut at 2000 lines, and the footer says so and says how to get
// the rest.
//
// **The count is the load-bearing half.** A listing that stops without saying so reads as "that was
// all of it" — for a skill's file that means a caller proceeding on half a document and never knowing.
func TestALongFileIsCutAndTheFooterSaysHowToContinue(t *testing.T) {
	content := bytes.Repeat([]byte("line\n"), maxContentLines+300)

	out := captureStdout(t, func() {
		printContent(content, 1, withoutLineNumbers, resumeInto("demo/hello"))
	})

	wantHead := string(bytes.Repeat([]byte("line\n"), maxContentLines))
	if !strings.HasPrefix(out, wantHead) {
		t.Fatal("the first 2000 lines did not come through unaltered")
	}
	for _, want := range []string{
		fmt.Sprintf("共 %d 行", maxContentLines+300),
		fmt.Sprintf("已显示到第 %d 行", maxContentLines),
		fmt.Sprintf("--offset %d", maxContentLines+1),
	} {
		if !strings.Contains(out, want) {
			t.Errorf("the footer does not say %q:\n%s", want, out[len(wantHead):])
		}
	}
}

// **The offset the footer prints is one that works.** The two halves are joined here rather than
// asserted separately, because a footer naming an offset the command would reject — or one off by one
// — is a bug that both halves' tests would pass.
func TestTheOffsetInTheFooterResumesExactlyWhereItStopped(t *testing.T) {
	content := bytes.Repeat([]byte("line\n"), maxContentLines+300)

	first := captureStdout(t, func() {
		printContent(content, 1, withoutLineNumbers, resumeInto("demo/hello"))
	})
	// Exactly what a caller would do: take the number out of the footer and pass it back.
	var next int
	if _, err := fmt.Sscanf(
		first[strings.Index(first, "--offset "):], "--offset %d", &next); err != nil {
		t.Fatalf("no usable offset in the footer: %v", err)
	}

	second := captureStdout(t, func() {
		printContent(content, next, withoutLineNumbers, resumeInto("demo/hello"))
	})

	if got := bodyOf(first) + bodyOf(second); got != string(content) {
		t.Errorf("the two halves together are %d bytes, the file is %d — they do not join",
			len(got), len(content))
	}
	// The last chunk is the end of the file, so it must not promise more.
	if strings.Contains(second, "继续取") {
		t.Errorf("the final chunk claims there is more:\n%s", second)
	}
	if strings.Contains(second, "超过") {
		t.Errorf("the final chunk claims a line was cut:\n%s", second)
	}
}

// An offset past the end is said out loud rather than silently printing nothing, which would be
// indistinguishable from an empty file.
func TestAnOffsetPastTheEndSaysSo(t *testing.T) {
	out := captureStdout(t, func() {
		printContent([]byte("one\ntwo\n"), 9, withoutLineNumbers, resumeInto("demo/hello"))
	})
	if !strings.Contains(out, "只有 2 行") {
		t.Errorf("printed %q", out)
	}
}

// **One line longer than the whole byte budget** — a minified script, or a single long value. No line
// count stops it, so the byte cap does, and the footer neither pretends the line came through whole
// nor offers a continuation that does not exist.
func TestASingleOversizedLineIsCutAndSaidToBeCut(t *testing.T) {
	content := bytes.Repeat([]byte("y"), maxContentBytes+(64<<10))

	out := captureStdout(t, func() {
		printContent(content, 1, withoutLineNumbers, resumeInto("demo/hello"))
	})

	wantHead := string(content[:maxContentBytes])
	if !strings.HasPrefix(out, wantHead) {
		t.Fatal("the bytes that fit did not come through")
	}
	if !strings.Contains(out, "超过 128 KiB") || !strings.Contains(out, "只印了它的开头") {
		t.Errorf("the footer does not say the line itself was cut:\n%s", out[len(wantHead):])
	}
	// The rest of that line is not reachable: an offset counts lines, so offering one would be a lie.
	if strings.Contains(out, "--offset") {
		t.Errorf("a continuation was offered for a mid-line cut:\n%s", out[len(wantHead):])
	}
}

// The remarks follow the content and never precede it, so everything above the first bracket is the
// file.
//
// That ordering is what lets a caller treat the output as the document — and the document is not only
// data here: a SKILL.md is instructions, and one interleaved with this CLI's notes is a document nobody
// can follow.
func TestTheRemarksComeAfterTheContent(t *testing.T) {
	content := bytes.Repeat([]byte("line\n"), maxContentLines+1)

	out := captureStdout(t, func() {
		printContent(content, 1, withoutLineNumbers, resumeInto("demo/hello"))
	})

	bracket := strings.Index(out, "（")
	if bracket < 0 {
		t.Fatal("no remark was printed at all")
	}
	if strings.Contains(out[:bracket], "（") {
		t.Error("a remark appears inside the content")
	}

	// And when they follow content whose last line has no newline — the oversized-line case, where the
	// cut is the end of the file — they start on their own line rather than being glued to it.
	tail := captureStdout(t, func() {
		printContent(bytes.Repeat([]byte("y"), maxContentBytes+(1<<10)), 1, withoutLineNumbers, resumeInto("demo/hello"))
	})
	if tail[maxContentBytes-1] != 'y' {
		t.Fatal("the cut did not land where the byte cap says it should")
	}
	if tail[maxContentBytes] != '\n' {
		t.Errorf("a remark was glued to the last line of a file that does not end with one: %q",
			tail[maxContentBytes-20:maxContentBytes+20])
	}
}

// **`read`'s rendering, and the whole of its difference from `invoke`'s**: every line carries its
// number in the file, then a tab — the shape Claude Code's own `Read` emits, unpadded.
//
// The reason is one this command already leans on: `--offset` counts lines, and the footer hands a
// number back, so printing the numbers is what lets a caller check that value rather than trust it.
func TestAReadNumbersEveryLineAndABodyDoesNot(t *testing.T) {
	content := []byte("one\ntwo\nthree\n")

	numbered := captureStdout(t, func() {
		printContent(content, 1, withLineNumbers, resumeInto("demo/hello"))
	})
	if numbered != "1\tone\n2\ttwo\n3\tthree\n" {
		t.Errorf("numbered output:\n%q", numbered)
	}

	plain := captureStdout(t, func() {
		printContent(content, 1, withoutLineNumbers, resumeInto("demo/hello"))
	})
	if plain != string(content) {
		t.Errorf("unnumbered output:\n%q", plain)
	}
}

// **The numbers are the file's own, not the chunk's.** Asking for line 3 must print `3` on the first
// line shown — otherwise the one thing the numbers are for, checking the offset you were handed, is
// exactly what they would get wrong.
func TestTheNumbersAreTheFilesAndNotTheChunks(t *testing.T) {
	out := captureStdout(t, func() {
		printContent([]byte("a\nb\nc\n"), 3, withLineNumbers, resumeInto("demo/hello"))
	})
	if out != "3\tc\n" {
		t.Errorf("printed %q, want the third line numbered 3", out)
	}
}

// A blank line is a line and is numbered like any other — and the remarks still start on a line of
// their own rather than being glued to a last line that has no newline.
func TestBlankLinesAreNumberedToo(t *testing.T) {
	out := captureStdout(t, func() {
		printContent([]byte("a\n\nb"), 1, withLineNumbers, resumeInto("demo/hello"))
	})
	if out != "1\ta\n2\t\n3\tb" {
		t.Errorf("printed %q", out)
	}
}

// **The numbers count against the byte cap.** What that cap bounds is what reaches the caller's
// context, and a prefix is part of what reaches it — so a numbered read of an oversized single line
// must still come out inside the budget rather than seven bytes a line over it.
func TestTheLineNumbersCountAgainstTheByteCap(t *testing.T) {
	content := bytes.Repeat([]byte("y"), maxContentBytes+(64<<10))

	out := captureStdout(t, func() {
		printContent(content, 1, withLineNumbers, resumeInto("demo/hello"))
	})

	// The region the file produced: everything before the remarks, less the newline this CLI inserts
	// so that its own remark does not sit glued to a line with no terminator. That newline is the
	// CLI's, not the file's, so it is not what the cap is about.
	region := strings.TrimSuffix(bodyOf(out), "\n")
	if got := len(region); got != maxContentBytes {
		t.Errorf("the file region is %d bytes, want exactly the %d cap", got, maxContentBytes)
	}
	if !strings.Contains(out, "超过 128 KiB") {
		t.Errorf("a cut line was not said to be cut:\n%s", out[len(out)-120:])
	}
}

// The offset parser: the only switch either read command has, and one a caller will paste a number
// into.
func TestParseOffset(t *testing.T) {
	for _, c := range []struct {
		name    string
		args    []string
		want    int
		wantArg []string
		wantErr bool
	}{
		{name: "absent means the beginning", args: []string{"demo/hello", "notes.md"}, want: 1,
			wantArg: []string{"demo/hello", "notes.md"}},
		{name: "a line number", args: []string{"demo/hello", "notes.md", "--offset", "2001"}, want: 2001,
			wantArg: []string{"demo/hello", "notes.md"}},
		{name: "anywhere in the line", args: []string{"--offset", "3", "demo/hello", "notes.md"}, want: 3,
			wantArg: []string{"demo/hello", "notes.md"}},
		{name: "zero is not a line number", args: []string{"--offset", "0"}, wantErr: true},
		{name: "nor is a word", args: []string{"--offset", "later"}, wantErr: true},
		{name: "and it needs its number", args: []string{"--offset"}, wantErr: true},
	} {
		t.Run(c.name, func(t *testing.T) {
			got, rest, err := parseOffset(c.args)
			if c.wantErr {
				if err == nil {
					t.Fatalf("accepted %v", c.args)
				}
				return
			}
			if err != nil {
				t.Fatalf("parseOffset: %v", err)
			}
			if got != c.want {
				t.Errorf("offset = %d, want %d", got, c.want)
			}
			if strings.Join(rest, " ") != strings.Join(c.wantArg, " ") {
				t.Errorf("the remaining arguments are %v, want %v", rest, c.wantArg)
			}
		})
	}
}

// The four content commands answer bytes, lines and counts, so `--json` is refused rather than
// silently ignored (PRD 验收 #21). No server is involved: the refusal arrives before a request.
func TestContentCommandsRefuseJSON(t *testing.T) {
	for _, verb := range []string{"ls", "cat", "wc", "grep"} {
		err := skillVerb(context.Background(), []string{verb, "dev/pdf-tools", "--json"})
		if err == nil || !strings.Contains(err.Error(), "--json") {
			t.Errorf("%s accepted --json: %v", verb, err)
		}
	}
}

// `-c` answers from the manifest, so a path the manifest does not have is a failed argument — not a
// zero-byte file. The other path through `wc` (the count endpoint) already answers 404 for it.
func TestWcBytesRefusesAPathTheManifestDoesNotHave(t *testing.T) {
	withTempHome(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/files") {
			_, _ = w.Write([]byte(`{"files":[
				{"relpath":"SKILL.md","size":3,"is_binary":false}]}`))
			return
		}
		t.Errorf("unexpected path %s", r.URL.Path)
	})

	var err error
	out := captureStdout(t, func() {
		err = run(context.Background(), []string{"skill", "wc",
			"dev/pdf-tools@01JKMNPQ", "nope.md", "-c"})
	})
	var exited *exitError
	if !errors.As(err, &exited) || exited.code != 1 {
		t.Fatalf("wc error = %v, want exit 1", err)
	}
	if !strings.Contains(out, "3 total") && !strings.Contains(out, "0 total") {
		t.Errorf("stdout = %q", out)
	}
}

// A row-id spelling reaches the author plane only when it is one: a bare 8/26-character code, or the
// legacy `draft:` in front of a 26-character ULID. `draft:<short>` is refused by the server, so the
// CLI must not strip the prefix and succeed where the address grammar says 404 (PRD #23).
func TestRowIDOfPinKeepsTheAddressGrammarInOnePlace(t *testing.T) {
	for _, accepted := range []struct{ pin, id string }{
		{"K3M9QX2P", "K3M9QX2P"},
		{"01JKMNPQRSTVWXYZ0123456789", "01JKMNPQRSTVWXYZ0123456789"},
		{"draft:01JKMNPQRSTVWXYZ0123456789", "01JKMNPQRSTVWXYZ0123456789"},
	} {
		id, ok := rowIDOfPin(accepted.pin)
		if !ok || id != accepted.id {
			t.Errorf("rowIDOfPin(%q) = %q, %v", accepted.pin, id, ok)
		}
	}
	for _, refused := range []string{"draft:K3M9QX2P", "1.2.3", "sha256:abc"} {
		if id, ok := rowIDOfPin(refused); ok {
			t.Errorf("rowIDOfPin(%q) = %q, accepted", refused, id)
		}
	}
}

// A suffix the consumption plane has no name for is an address the author plane answers: `@latest`
// sent as a version header would come back a 400 about the header, while the address grammar's answer
// is the author plane's 404 (PRD #23). The two spellings the consumer plane knows stay on it.
func TestANonConsumerSuffixIsResolvedOnTheAuthorPlane(t *testing.T) {
	for _, pin := range []string{"latest", "foo", "3", "abcdefabcdefabcdefabcdefab"} {
		target, err := resolve(context.Background(), api.Client{}, "dev/pdf-tools@"+pin, false)
		if err != nil {
			t.Fatalf("resolve @%s: %v", pin, err)
		}
		if !target.rowID || target.pin != pin {
			t.Errorf("@%s resolved to %+v, want the author plane", pin, target)
		}
	}
	for _, pin := range []string{"1.2.3", "1.2.3+build.1", "sha256:" + strings.Repeat("a", 64)} {
		target, err := resolve(context.Background(), api.Client{}, "dev/pdf-tools@"+pin, false)
		if err != nil {
			t.Fatalf("resolve @%s: %v", pin, err)
		}
		if target.rowID {
			t.Errorf("@%s resolved to %+v, want the consumer plane", pin, target)
		}
	}
}

// The consumer plane's 410 is about the version it serves, not about the caller's rights: a
// maintainer can still read that file on the author plane, and `cat` must ask before giving up.
func TestCatFallsBackToTheAuthorPlaneForARevokedVersion(t *testing.T) {
	withTempHome(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/skills/dev/pdf-tools/files/a.md":
			w.WriteHeader(http.StatusGone)
			_, _ = w.Write([]byte(`{"error":{"code":"version_revoked","message":"gone"}}`))
		case "/api/v1/author/skills/dev/pdf-tools/versions/1.2.3/files/a.md":
			_, _ = w.Write([]byte("from the author plane"))
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
		}
	})

	out := captureStdout(t, func() {
		if err := run(context.Background(), []string{"skill", "cat",
			"dev/pdf-tools@1.2.3", "a.md"}); err != nil {
			t.Fatalf("cat: %v", err)
		}
	})
	if out != "from the author plane" {
		t.Errorf("output = %q", out)
	}
}

// With nothing live, a `write`+ caller is handed the newest draft rather than a 404 (PRD #27).
// The probe distinguishes them from `browse`/`invoke`: the draft list is open to `write`+ only.
func TestCatDefaultPinFallsBackToTheNewestDraft(t *testing.T) {
	withTempHome(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/v1/skills/dev/pdf-tools/metadata":
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":{"code":"skill_not_found","message":"none"}}`))
		case r.URL.Path == "/api/v1/skills/dev/pdf-tools":
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":{"code":"skill_not_found","message":"none"}}`))
		case r.URL.Path == "/api/v1/author/skills/dev/pdf-tools/versions":
			_, _ = w.Write([]byte(`{"skill":{"namespace":"dev","name":"pdf-tools",
				"address":"dev/pdf-tools"},"versions":[{"address":"dev/pdf-tools",
				"pin":"K3M9QX2P","code":"K3M9QX2P","name":"草稿","version":null,
				"status":"draft","digest":"sha256:abc","files":1,"bytes":3}],
				"counts":{"all":1,"live":0,"draft":1,"history":0,"revoked":0,"discarded":0},
				"next":null}`))
		case r.URL.Path == "/api/v1/author/skills/dev/pdf-tools/versions/K3M9QX2P/files/a.md":
			_, _ = w.Write([]byte("draft body"))
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
		}
	})

	out := captureStdout(t, func() {
		if err := run(context.Background(), []string{"skill", "cat", "dev/pdf-tools", "a.md"}); err != nil {
			t.Fatalf("cat: %v", err)
		}
	})
	if out != "draft body" {
		t.Errorf("output = %q", out)
	}
}

// While a skill is offline the consumer plane answers 503, but the author plane still serves it to
// `browse` and above (PRD #17). The default pin must make that second call; `invoke` cannot, and
// keeps the 503 rather than being told a version it may not read.
func TestCatDefaultPinFallsBackToTheAuthorPlaneWhileTheSkillIsOffline(t *testing.T) {
	withTempHome(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/v1/skills/dev/pdf-tools/metadata":
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"error":{"code":"skill_offline","message":"offline"}}`))
		case r.URL.Path == "/api/v1/author/skills/dev/pdf-tools/versions":
			_, _ = w.Write([]byte(`{"skill":{"namespace":"dev","name":"pdf-tools",
				"address":"dev/pdf-tools"},"versions":[{"address":"dev/pdf-tools",
				"pin":"1.2.3","code":"K3M9QX2P","name":"线上","version":"1.2.3",
				"status":"live","digest":"sha256:abc","files":1,"bytes":3}],
				"counts":{"all":1,"live":1,"draft":0,"history":0,"revoked":0,"discarded":0},
				"next":null}`))
		case r.URL.Path == "/api/v1/author/skills/dev/pdf-tools/versions/1.2.3/files/a.md":
			_, _ = w.Write([]byte("served by the author plane"))
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
		}
	})

	out := captureStdout(t, func() {
		if err := run(context.Background(), []string{"skill", "cat", "dev/pdf-tools", "a.md"}); err != nil {
			t.Fatalf("cat: %v", err)
		}
	})
	if out != "served by the author plane" {
		t.Errorf("output = %q", out)
	}
}

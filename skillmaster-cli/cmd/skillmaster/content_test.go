package main

import (
	"bytes"
	"fmt"
	"strings"
	"testing"
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

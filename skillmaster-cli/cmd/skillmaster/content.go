package main

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"strconv"
)

// How much of one file this command will put in front of a caller at once.
//
// Two caps, because they stop two different things. The line count stops a long document, and it is
// the host's own number — Claude Code's Read reads up to 2000 lines by default — so a caller that has
// learned what that means from `Read` is not being taught a second convention for the same idea. The
// byte cap stops one *line*, which no line count can: a minified script, a single long JSON value or a
// base64 payload is one line and any size at all.
const (
	maxContentLines = 2000
	maxContentBytes = 128 << 10
)

// Whether a rendering numbers its lines. Named because a bare `true` at a call site says nothing about
// which of the two halves of this CLI is being printed, and that difference is the whole point:
//
//   - **`read` numbers**, the way the host's `Read` does — the line's number in the file, a tab, then
//     the line, counted from the file's own first line. Those are the numbers this command asks the
//     caller to hand back (`--offset` counts lines), so printing them is what lets the caller check the
//     value it was given instead of trusting it.
//   - **`invoke` does not**, mirroring the host's other half: `Skill` loads instructions with no
//     prefix on any line, and a prefixed instruction document is a different document. It is also the
//     rendering whose bytes a caller may want to hold against the digest it was told.
const (
	withoutLineNumbers = false
	withLineNumbers    = true
)

// printContent writes a file's bytes to stdout, capped, followed by what this CLI has to say about it.
//
// **The content goes out first, and the CLI's own remarks come after it in full-width brackets.** That
// ordering is what lets a caller treat everything above the first bracket as the file — and the file is
// not merely data here: a SKILL.md is instructions, so one merged with a note about offsets is a
// document nobody can follow.
//
// The remarks go to stdout rather than stderr, which the credential notice deliberately does not. The
// difference is what happens if a line is dropped: a lost notice about where a credential went over is
// an inconvenience, while a lost "there is more" reads as "that was the whole file" — the failure
// these caps exist to prevent. The marker has to travel the way the content does.
//
// @param fromLine 1-based line to start at; 1 prints from the beginning
// @param numbered whether each line is prefixed with its number; see the constants above
// @param resume   builds the exact command that fetches the next chunk, given the line to resume at
func printContent(content []byte, fromLine int, numbered bool, resume func(nextLine int) string) {
	if len(content) == 0 {
		// An empty file prints nothing, which is the honest rendering of it — and the alternative, a
		// note saying there are no lines, would be this CLI talking about a file that is simply empty.
		return
	}
	lines := splitLines(content)
	if fromLine > len(lines) {
		fmt.Printf("（这个文件只有 %d 行，没有第 %d 行。）\n", len(lines), fromLine)
		return
	}

	var shown bytes.Buffer
	last := fromLine - 1
	cutLine := false
	remaining := maxContentBytes
	for i := fromLine - 1; i < len(lines) && i-fromLine+1 < maxContentLines; i++ {
		prefix := linePrefix(i+1, numbered)
		if len(prefix)+len(lines[i]) > remaining {
			// One line longer than what is left of the byte budget. Printed up to it and said so,
			// rather than skipped: silently returning nothing for a file that exists is the worst of
			// the available answers, and pretending the line fits is worse still. **The number counts
			// against the budget like everything else** — what the cap bounds is what reaches the
			// caller's context, not what the file happens to weigh.
			shown.WriteString(prefix)
			if room := remaining - len(prefix); room > 0 {
				shown.Write(lines[i][:room])
			}
			last = i + 1
			cutLine = true
			break
		}
		shown.WriteString(prefix)
		shown.Write(lines[i])
		remaining -= len(prefix) + len(lines[i])
		last = i + 1
	}

	out := shown.Bytes()
	os.Stdout.Write(out)

	hasMore := last < len(lines)
	if !hasMore && !cutLine {
		return
	}
	if len(out) > 0 && !bytes.HasSuffix(out, []byte("\n")) {
		// The remarks must not be glued to the last line of a file that ends without a newline.
		// Nothing is added when they do not follow content, so a whole unnumbered file still comes out
		// exactly as it was stored.
		fmt.Println()
	}
	if cutLine {
		fmt.Printf("（第 %d 行本身超过 %d KiB，只印了它的开头。）\n", last, maxContentBytes>>10)
	}
	if hasMore {
		fmt.Printf("（共 %d 行，已显示到第 %d 行。继续取：%s）\n", len(lines), last, resume(last+1))
	}
}

// linePrefix is a line's number in the file, or nothing when this rendering does not number lines.
//
// **The number, a tab, then the line — unpadded**, which is what the host's `Read` actually emits
// (`1\t`, `10\t`; its own description calls the shape `cat -n`, but it does not do `cat -n`'s
// alignment). Verified against the installed CLI rather than assumed, because a caller that has seen
// this shape a few thousand times is the entire reason to have it: a padded column would be a different
// shape, and a different shape is a thing to learn. The tab is what makes the number separable from the
// content rather than merely adjacent to it, so a line copied back out has something exact to cut on.
//
// No padding also means the prefix is at most a few bytes, which it is charged for: see the byte cap.
func linePrefix(line int, numbered bool) string {
	if !numbered {
		return ""
	}
	return fmt.Sprintf("%d\t", line)
}

// splitLines cuts content into lines, each keeping its own terminator.
//
// Keeping the terminator is what makes the printed region a genuine prefix of the file rather than a
// re-rendering of it: a file with CRLF endings, or one whose last line has no newline, comes out as it
// was stored. The whole read path is built on that — the bytes served are the bytes whose digest the
// manifest advertises, and a CLI that normalised anything would be the one place it stopped being true.
func splitLines(content []byte) [][]byte {
	if len(content) == 0 {
		return nil
	}
	lines := bytes.SplitAfter(content, []byte("\n"))
	if last := lines[len(lines)-1]; len(last) == 0 {
		// A trailing newline terminates the line before it; it does not begin an empty one. Without
		// this, "共 N 行" would be one more than any reader would count.
		lines = lines[:len(lines)-1]
	}
	return lines
}

// parseOffset pulls `--offset <行号>` out of an argument list and returns the rest.
//
// The unit is lines, and it is the host's unit on purpose: Claude Code's Read takes its offset and
// limit in lines, so this parameter is one the caller has already met. It is also the unit this
// command's own footer speaks in, which is what makes the value printed there something to paste back
// rather than something to compute.
func parseOffset(args []string) (int, []string, error) {
	offset := 1
	rest := make([]string, 0, len(args))
	for i := 0; i < len(args); i++ {
		if args[i] != "--offset" {
			rest = append(rest, args[i])
			continue
		}
		if i+1 >= len(args) {
			return 0, nil, errors.New("--offset 后面要跟一个行号")
		}
		n, err := strconv.Atoi(args[i+1])
		if err != nil || n < 1 {
			return 0, nil, fmt.Errorf("--offset 要是一个从 1 开始的行号，收到 %q", args[i+1])
		}
		offset = n
		i++
	}
	return offset, rest, nil
}

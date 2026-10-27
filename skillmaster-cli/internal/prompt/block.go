// Package prompt is the rule block that gets pasted into an agent's instruction file.
//
// **It lives here because it describes this CLI** (ADR 0102). Until v25 it lived in the platform
// skill `skillmaster/startup`, where a change reached everybody at once — and could name a command
// the person's binary did not have. Shipping it with the binary makes the block and the command
// surface one artefact, and it turns "is the pasted copy current?" into arithmetic on this machine
// instead of a request that needs a credential.
//
// **The file is the authority, byte for byte.** `block.md` is embedded with `go:embed` and printed
// verbatim by `skillmaster prompt print`; the text has not changed since v20 approved it, and this
// version moves it without rewording it.
package prompt

import (
	"crypto/sha256"
	_ "embed"
	"encoding/hex"
	"errors"
	"fmt"
	"sort"
	"strings"
)

// Begin and End delimit the block inside the user's instruction file.
//
// **Whole lines, not substrings.** The markers are HTML comments, so they can appear inside a
// sentence about the markers themselves; matching the trimmed line is what keeps `prompt print`'s
// own output from being mistaken for one.
const (
	Begin = "<!-- skillmaster:begin -->"
	End   = "<!-- skillmaster:end -->"
)

//go:embed block.md
var embedded []byte

// Block is the block this binary carries, markers included and ending in a newline.
//
// A copy: callers compare it with, write it to, and print it from their own buffers, and a caller
// that mutated the embedded bytes would change what every later command in the process says.
func Block() []byte {
	out := make([]byte, len(embedded))
	copy(out, embedded)
	return out
}

// Digest names the block's content, and is what the record stores.
//
// A content digest rather than the CLI's version: the block can stay the same across ten releases,
// and a version number would then report ten updates.
func Digest() string { return DigestOf(embedded) }

// DigestOf is Digest for arbitrary bytes — the file's copy of the block, for instance.
func DigestOf(block []byte) string {
	sum := sha256.Sum256(block)
	return "sha256:" + hex.EncodeToString(sum[:])
}

// ErrMissing is "this file has no marked block".
//
// Its own value because the two callers answer it differently: `install` appends one, `update`
// refuses and points at `install`.
var ErrMissing = errors.New("文件里没有 " + Begin + " 标记段")

// AmbiguousError is more than one marked block in one file.
//
// **A refusal rather than a guess.** Picking one would silently leave the other stale, and the next
// update would report a difference nobody could act on. Naming the lines is what lets a person
// delete the extra copy — which is the only repair, and it is theirs to make.
type AmbiguousError struct {
	Path  string
	Lines []int
}

func (e *AmbiguousError) Error() string {
	return fmt.Sprintf("%s 里有 %d 个标记段（第 %s 行）；只保留一个再试",
		e.Path, len(e.Lines), joinInts(e.Lines))
}

func joinInts(values []int) string {
	parts := make([]string, len(values))
	for i, v := range values {
		parts[i] = fmt.Sprint(v)
	}
	return strings.Join(parts, "、")
}

// Region is where one file's block sits: the byte range from the start of the `begin` line through
// the newline that ends the `end` line.
type Region struct {
	Start int
	End   int
	// CRLF says this file uses \r\n. The block is written back with the same ending, so an update
	// does not turn one paragraph of a Windows file into mixed line endings.
	CRLF bool
}

// Locate finds the block in file. The error is ErrMissing, or *AmbiguousError.
func Locate(path string, file []byte) (Region, error) {
	var begins, ends []int
	for offset := 0; offset < len(file); {
		lineEnd := offset
		for lineEnd < len(file) && file[lineEnd] != '\n' {
			lineEnd++
		}
		next := lineEnd
		if next < len(file) {
			next++ // include the newline in the *next* line's start
		}
		line := strings.TrimRight(string(file[offset:lineEnd]), "\r")
		switch strings.TrimSpace(line) {
		case Begin:
			begins = append(begins, offset)
		case End:
			ends = append(ends, next)
		}
		offset = next
	}
	if len(begins) == 0 && len(ends) == 0 {
		return Region{}, ErrMissing
	}
	if len(begins) != 1 || len(ends) != 1 || ends[0] < begins[0] {
		lines := make([]int, 0, len(begins)+len(ends))
		for _, offset := range begins {
			lines = append(lines, 1+strings.Count(string(file[:offset]), "\n"))
		}
		for _, offset := range ends {
			// `ends` holds the offset *after* the newline that closes the `end` line, so the
			// newline count up to there is that line's number.
			lines = append(lines, strings.Count(string(file[:offset]), "\n"))
		}
		sort.Ints(lines)
		return Region{}, &AmbiguousError{Path: path, Lines: lines}
	}
	// **The whole file decides the line ending, not the block.** A Windows file that is CRLF
	// throughout can hold a block that was pasted with LF (that is exactly what `prompt print`
	// emits on every platform), and reading the ending off the block would then write LF back into
	// a CRLF file — mixed endings, which is what this field exists to prevent.
	return Region{
		Start: begins[0],
		End:   ends[0],
		CRLF:  strings.Contains(string(file), "\r\n"),
	}, nil
}

// BlockIn returns the file's copy of the block, as it would be compared with Block().
//
// **Normalised to \n before comparison.** A Windows editor may have rewritten the line endings of a
// file that was pasted with \n; that is not a different rule block, and reporting it as one would
// make `prompt status` cry wolf on every Windows machine.
func BlockIn(path string, file []byte) ([]byte, Region, error) {
	region, err := Locate(path, file)
	if err != nil {
		return nil, Region{}, err
	}
	return Normalise(file[region.Start:region.End]), region, nil
}

// Normalise removes \r before \n.
func Normalise(data []byte) []byte {
	return []byte(strings.ReplaceAll(string(data), "\r\n", "\n"))
}

// WithLineEnding renders block with \r\n when the target file uses it.
func WithLineEnding(block []byte, crlf bool) []byte {
	if !crlf {
		return block
	}
	return []byte(strings.ReplaceAll(string(Normalise(block)), "\n", "\r\n"))
}

// Replace writes block into file's region, keeping the rest of the file byte for byte.
func Replace(file []byte, region Region, block []byte) []byte {
	replacement := WithLineEnding(block, region.CRLF)
	out := make([]byte, 0, len(file)-(region.End-region.Start)+len(replacement))
	out = append(out, file[:region.Start]...)
	out = append(out, replacement...)
	out = append(out, file[region.End:]...)
	return out
}

// Append puts the block at the end of a file that has no marked block, separated by one blank line.
//
// **The blank line is the whole formatting rule**: the block is a paragraph, and gluing it to
// whatever the person wrote last reads as part of that section.
func Append(file []byte, block []byte, crlf bool) []byte {
	out := make([]byte, 0, len(file)+len(block)+2)
	out = append(out, file...)
	ending := "\n"
	if crlf {
		ending = "\r\n"
	}
	if len(file) > 0 && file[len(file)-1] != '\n' {
		out = append(out, ending...)
	}
	if len(file) > 0 {
		out = append(out, ending...)
	}
	out = append(out, WithLineEnding(block, crlf)...)
	return out
}

// UsesCRLF guesses a file's line ending from its content. A file with no newline at all is \n.
func UsesCRLF(file []byte) bool {
	return strings.Contains(string(file), "\r\n")
}

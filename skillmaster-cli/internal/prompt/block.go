// Package prompt is the rule block that gets pasted into an agent's instruction file.
//
// **It lives here because it describes this CLI** (ADR 0102). Shipping it with the binary makes the
// block and the command surface one artefact.
//
// **The CLI prints this text and nothing else** (ADR 0110, 2026-10-28). Older versions wrapped it in
// `<!-- skillmaster:begin -->` / `<!-- skillmaster:end -->`, then located it again to report, update
// or remove it, and carried `Append` / `WithLineEnding` / `UsesCRLF` for the write itself. All of
// that went with the markers: the paragraph belongs to the person's file, the platform skill writes
// it, and no CLI code path reads or writes a user file any more. What is left is the text, its
// digest, and the line-ending normalisation the embedded copy needs on Windows.
package prompt

import (
	"crypto/sha256"
	_ "embed"
	"encoding/hex"
	"strings"
)

//go:embed block.md
var embeddedRaw []byte

// **Normalised on load, not trusted to be committed with LF.** Windows developers (and the Windows
// CI runner) check out with `core.autocrlf=true`, which rewrites the file's line endings; without
// this the same source would produce two different blocks — a different digest, and a printed copy
// that differs from the one every other platform prints. `.gitattributes` pins the file, and this
// makes the promise hold even for a working copy that predates that line.
var embedded = Normalise(embeddedRaw)

// Block is the block this binary carries, ending in a newline.
//
// A copy: callers print it from their own buffers, and a caller that mutated the embedded bytes
// would change what every later command in the process says.
func Block() []byte {
	out := make([]byte, len(embedded))
	copy(out, embedded)
	return out
}

// Digest names the block's content, and is the shape `prompt.json` records store.
//
// A content digest rather than the CLI's version: the block can stay the same across ten releases,
// and a version number would then report ten updates.
func Digest() string { return DigestOf(embedded) }

// DigestOf is Digest for arbitrary bytes.
func DigestOf(block []byte) string {
	sum := sha256.Sum256(block)
	return "sha256:" + hex.EncodeToString(sum[:])
}

// Normalise removes \r before \n.
func Normalise(data []byte) []byte {
	return []byte(strings.ReplaceAll(string(data), "\r\n", "\n"))
}

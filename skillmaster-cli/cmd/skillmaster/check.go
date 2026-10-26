package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"path"
	"regexp"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// skill check: does every relative reference in one version point at a file that version has?
//
// **Only the references.** Whether the root `SKILL.md` exists and whether its frontmatter is valid
// are decided when the version is written — the server parses them with a real YAML reader — and this
// CLI deliberately does not parse frontmatter at all (`internal/api/client.go` says why). The
// **content** of `SKILL.md` is scanned like every other text file; what is out of scope is the
// structure check the write path already made (v22 S9).
//
// **Two requests, one snapshot.** The file list carries the manifest digest; the archive is asked
// for that digest, not for "latest", so the bytes and the list cannot come from different edits.
// When the digest no longer resolves — somebody wrote the draft in between — the honest answer is to
// retry, not to pair one version's list with another version's bytes.

var (
	// `path` — an inline code span. A command written in one is parsed as a command; anything else
	// that looks like a path is a reference (see looksLikePath).
	checkCodeSpan = regexp.MustCompile("`([^`\n]+)`")
	// `skill read <地址> <路径>…` at the start of a span or a line. **Both ends have to come from
	// structure, not from guessing where the prose stops**: a span ends at its closing backtick, and
	// a line of a code block is the command. Matching "the rest of the line" instead swallowed the
	// next command and glued Chinese prose onto the path (v22 audit round 2).
	checkReadCommand = regexp.MustCompile(`^(?:skillmaster )?skill (?:read|cat)\s+(\S+)(.*)$`)
)

type brokenReference struct {
	relpath string
	line    int
	target  string
}

func checkVersion(ctx context.Context, args []string) error {
	if len(args) != 1 || strings.HasPrefix(args[0], "-") {
		return usagef("要接一个地址，不收 flag。%s", usageAt("skill check"))
	}
	// The address is checked before a credential is looked for: a typo must be a usage error even on
	// a machine that would otherwise open a browser first (ADR 0087 §决定 4).
	if err := checkAddressSyntax("skill check", args[0]); err != nil {
		return err
	}
	broken, err := authenticated(ctx, func(client api.Client) ([]brokenReference, error) {
		return brokenReferences(ctx, client, args[0])
	})
	if err != nil {
		return err
	}
	if len(broken) == 0 {
		return nil
	}
	for _, ref := range broken {
		fmt.Printf("%s:%d: %s 不在这一版里\n", ref.relpath, ref.line, ref.target)
	}
	// The findings are already on stdout, one line each; the exit status is the answer. An
	// `exitError` with no message is how every multi-file command says "look above".
	return &exitError{code: 1}
}

// brokenReferences is one version's scan: resolve the target, take the list and its digest, fetch
// the archive pinned to that digest, and look at every text file the list named.
func brokenReferences(ctx context.Context, client api.Client, address string) ([]brokenReference, error) {
	target, err := resolve(ctx, client, address, true)
	if err != nil {
		return nil, err
	}
	// Which version was checked, on stderr, like `cat` and `wc`: a bare address follows this
	// machine's pin, and a run that prints nothing on success would otherwise look like it checked
	// whatever is live now.
	printNote(target)

	listed, err := client.AuthorFileList(ctx, target.address, target.pin)
	if err != nil {
		return nil, checkRefusal(err)
	}
	pin := digestPinOf(listed.ETag)
	if pin == "" {
		return nil, errors.New("服务端没有给出这一版的摘要，无法保证两次请求读的是同一版；重试，或升级 skillmaster。")
	}
	content, err := client.ReadArchiveInMemory(ctx, archivePath(target.address, pin, true), maxArchiveBytes)
	if err != nil {
		// The list named a digest a moment ago; if the archive cannot find it, the version moved
		// under the check. Pairing the old list with whatever is current now would report links that
		// do exist and miss ones that do not, so the answer is to look again.
		var server *api.ServerError
		if errors.As(err, &server) && server.Status == http.StatusNotFound {
			return nil, errors.New("检查期间版本变过，请重试。")
		}
		return nil, checkRefusal(err)
	}

	index := newFileIndex(listed.Files)
	var broken []brokenReference
	for _, file := range listed.Files {
		if file.IsBinary {
			continue
		}
		data, ok := content[file.Relpath]
		if !ok {
			continue
		}
		broken = append(broken, brokenReferencesIn(file.Relpath, string(data), index)...)
	}
	return broken, nil
}

// checkRefusal says, for the two states check meets, what happened — the same words download uses,
// because it is the same rule refusing the same thing (a discarded version is not exportable).
func checkRefusal(err error) error {
	var server *api.ServerError
	if errors.As(err, &server) {
		if message, known := versionMessages[server.Code]; known {
			return errors.New(message)
		}
	}
	return err
}

// digestPinOf turns the list response's ETag into the pin the archive understands.
//
// The ETag is a quoted entity tag — `"sha256:<hex>"` — while the author plane's pin grammar wants
// the suffix form `sha256:<hex>`: quotes make it neither a version name nor a digest and the archive
// answers 404. A weak tag would carry a `W/` prefix in front of the quotes.
func digestPinOf(etag string) string {
	tag := strings.TrimSpace(etag)
	tag = strings.TrimPrefix(tag, "W/")
	tag = strings.Trim(tag, `"`)
	if !strings.HasPrefix(tag, "sha256:") {
		return ""
	}
	hex := strings.TrimPrefix(tag, "sha256:")
	if len(hex) != 64 || strings.ToLower(hex) != hex {
		return ""
	}
	return tag
}

// fileIndex answers "does this version have that path", including directories: a link to
// `references/` is satisfied by any file under it, and a directory is not a manifest entry.
type fileIndex struct {
	files map[string]bool
	dirs  map[string]bool
}

func newFileIndex(files []api.File) fileIndex {
	index := fileIndex{files: make(map[string]bool, len(files)), dirs: map[string]bool{".": true}}
	for _, file := range files {
		index.files[file.Relpath] = true
		for dir := path.Dir(file.Relpath); dir != "." && dir != "/"; dir = path.Dir(dir) {
			index.dirs[dir] = true
		}
	}
	return index
}

func (index fileIndex) has(target string) bool {
	cleaned := path.Clean(target)
	return index.files[cleaned] || index.dirs[cleaned]
}

// anchors says whether a bare code span is a reference to this version at all: its first segment
// must be something the version has. Prose is full of path-shaped spans that are about somewhere
// else — `AGENTS.override.md` in an agent's config directory, `.bak` as a suffix, a path on the
// user's own disk, `skillmaster/startup` as an address. Verified against the real `skillmaster/startup`
// 1.2.0 skill, where all five reported spans were about something other than the version's files.
// A markdown link and a `skill read/cat` argument are different: both say "this is a file".
func (index fileIndex) anchors(target string) bool {
	first, _, _ := strings.Cut(path.Clean(target), "/")
	return index.files[first] || index.dirs[first]
}

// brokenReferencesIn reports the relative references in one file that no file in this version
// satisfies. Line numbers are 1-based, like every other line number this CLI prints.
//
// **Where a reference is anchored differs by form.** A markdown link is relative to the file it is
// written in (CommonMark); a code span or a `skill read` argument is a path in the skill, which
// people write from the root. The two command forms are accepted from either place rather than
// guessing one: a checker that reports a real file as missing teaches people to ignore it.
func brokenReferencesIn(relpath, text string, index fileIndex) []brokenReference {
	var broken []brokenReference
	seen := make(map[string]bool)

	note := func(offset int, target string) {
		key := fmt.Sprintf("%d\x00%s", offset, target)
		if seen[key] {
			return
		}
		seen[key] = true
		broken = append(broken, brokenReference{relpath: relpath, line: lineAt(text, offset), target: target})
	}
	fileDir := path.Dir(relpath)
	// A command's paths are written from the skill root; a markdown link is relative to the file it
	// is in. The command forms are also accepted from the file's directory, because a checker that
	// calls a real file missing teaches people to ignore it.
	missingFromBoth := func(target string) bool {
		return !index.has(target) && !index.has(path.Join(fileDir, target))
	}

	spans := scanCodeSpans(text)
	fences := fenceRanges(text)

	// A link written inside a code span or a fenced block is an example, not a reference.
	for _, link := range scanMarkdownLinks(text) {
		if withinAnyRange(spanRanges(spans), link.offset) || withinAnyRange(fences, link.offset) {
			continue
		}
		target := referenceTarget(link.text)
		if target != "" && !index.has(path.Join(fileDir, target)) {
			note(link.offset, target)
		}
	}
	for _, span := range spans {
		if withinAnyRange(fences, span.offset) {
			continue // the backticks are literal inside a fence
		}
		if _, paths, ok := readCommandPaths(span.text); ok {
			for _, candidate := range paths {
				if target := referenceTarget(candidate); target != "" && missingFromBoth(target) {
					note(span.offset, target)
				}
			}
			continue
		}
		inside := trimReference(span.text)
		if !looksLikePath(inside) {
			continue
		}
		if target := referenceTarget(inside); target != "" && index.anchors(target) && missingFromBoth(target) {
			note(span.offset, target)
		}
	}
	// A line that begins with the command — inside a fence or not — is a command: every token after
	// the address is a path until a flag or shell syntax starts. Prose that merely mentions the
	// command is not one of these; it is handled by the code-span pass above, or not at all.
	lineStart := 0
	for _, line := range strings.Split(text, "\n") {
		lineText, prompted := stripLineMarkers(line)
		// **A command is only a command where the document says so**: inside a fenced block, or after
		// a shell prompt. A bare line that merely begins with the command is prose often enough
		// (`skill cat a.md b.md — 首尾相接`) that reading its words as paths reported sentences as
		// broken links. A command quoted inline is a code span, and the pass above handled it.
		if !prompted && !withinAnyRange(fences, lineStart) {
			lineStart += len(line) + 1
			continue
		}
		if _, tokens, ok := readCommandPaths(lineText); ok {
			for _, token := range tokens {
				if target := referenceTarget(token); target != "" && missingFromBoth(target) {
					note(lineStart, target)
				}
			}
		}
		lineStart += len(line) + 1
	}
	return broken
}

// offsetRange is where a code span or a fenced block sits in the file.
type offsetRange struct{ start, end int }

func withinAnyRange(ranges []offsetRange, at int) bool {
	for _, r := range ranges {
		if at >= r.start && at < r.end {
			return true
		}
	}
	return false
}

// fenceRanges finds ``` and ~~~ blocks: text inside them is code, so a `[x](y)` there is an example.
//
// **A fence closes on its own marker, and only on one at least as long as it opened.** Flipping on
// any fence line let a four-backtick block — the ordinary way to show a three-backtick one — end at
// its own example, and the example was then read as content.
func fenceRanges(text string) []offsetRange {
	var ranges []offsetRange
	type fence struct {
		char   byte
		length int
		start  int
	}
	var open *fence
	offset := 0
	for _, line := range strings.Split(text, "\n") {
		char, length := fenceMarker(strings.TrimLeft(line, " \t"))
		if length > 0 {
			switch {
			case open == nil:
				open = &fence{char: char, length: length, start: offset}
			case char == open.char && length >= open.length:
				ranges = append(ranges, offsetRange{open.start, offset + len(line)})
				open = nil
			}
		}
		offset += len(line) + 1
	}
	if open != nil {
		ranges = append(ranges, offsetRange{open.start, len(text)})
	}
	return ranges
}

// fenceMarker reports the fence character and how many of it the line opens with, or (0, 0).
func fenceMarker(line string) (byte, int) {
	if len(line) == 0 || (line[0] != '`' && line[0] != '~') {
		return 0, 0
	}
	char := line[0]
	count := 0
	for count < len(line) && line[count] == char {
		count++
	}
	if count < 3 {
		return 0, 0
	}
	return char, count
}

// sourced is a candidate reference and where it starts, so a finding can name its line.
type sourced struct {
	offset int
	text   string
}

// scanMarkdownLinks finds `](target)` destinations the way CommonMark does, instead of with a
// regular expression: a destination may be `<a path with spaces>` or contain balanced parentheses
// (`a_(b).md`), and `[^)\s]+` truncated both — reporting files that exist as missing.
func scanMarkdownLinks(text string) []sourced {
	var links []sourced
	for i := 0; i+1 < len(text); i++ {
		if text[i] != ']' || text[i+1] != '(' {
			continue
		}
		j := i + 2
		for j < len(text) && (text[j] == ' ' || text[j] == '\t') {
			j++
		}
		start := j
		if j < len(text) && text[j] == '<' {
			start = j + 1
			j = start
			for j < len(text) && text[j] != '>' && text[j] != '\n' {
				j++
			}
			if j < len(text) && text[j] == '>' {
				links = append(links, sourced{offset: start, text: text[start:j]})
			}
			i = j
			continue
		}
		depth := 0
		for j < len(text) && text[j] != '\n' {
			switch text[j] {
			case '\\':
				if j+1 < len(text) && text[j+1] != '\n' {
					j += 2
				} else {
					j++
				}
				continue
			case '(':
				depth++
			case ')':
				if depth == 0 {
					goto done
				}
				depth--
			case ' ', '\t':
				if depth == 0 {
					goto done // a title follows the destination
				}
			}
			j++
		}
	done:
		if j > start {
			links = append(links, sourced{offset: start, text: text[start:j]})
		}
		i = j
	}
	return links
}

// scanCodeSpans finds inline code spans and where they start.
func scanCodeSpans(text string) []sourced {
	var spans []sourced
	for _, match := range checkCodeSpan.FindAllStringSubmatchIndex(text, -1) {
		spans = append(spans, sourced{offset: match[2], text: text[match[2]:match[3]]})
	}
	return spans
}

// readCommandPaths parses one `skill read` / `skill cat` line or span. It reports the address and
// every path after it up to the first flag. Paths are not filtered by extension: `LICENSE` and
// `Makefile` are files too, and a command's arguments are paths by definition.
func readCommandPaths(text string) (address string, paths []string, ok bool) {
	match := checkReadCommand.FindStringSubmatch(strings.TrimSpace(text))
	if match == nil {
		return "", nil, false
	}
	for _, token := range strings.Fields(match[2]) {
		token = trimReference(token)
		// The command ends at a flag or at shell syntax. `cat a.md | grep TODO` is the command this
		// CLI itself recommends for a published version; reading `|`, `grep` and `TODO` as paths
		// would report a clean version as broken.
		if strings.HasPrefix(token, "-") || strings.ContainsAny(token, "|&;<>#`") ||
			token == "&&" || token == "||" {
			break
		}
		if token != "" {
			paths = append(paths, token)
		}
	}
	return match[1], paths, true
}

// stripLineMarkers removes what a code block puts in front of a command: a shell prompt, a block
// quote marker, or a list bullet. Without it, `$ skill read …` and `- skill read …` — both ordinary
// ways to write a command down — were not recognised at all.
func stripLineMarkers(line string) (text string, prompted bool) {
	trimmed := strings.TrimLeft(line, " \t")
	// A shell prompt is the strongest signal there is: what follows is a command, not prose.
	if strings.HasPrefix(trimmed, "$ ") {
		return strings.TrimLeft(trimmed[2:], " \t"), true
	}
	for _, marker := range []string{"> ", "- ", "* "} {
		if strings.HasPrefix(trimmed, marker) {
			return strings.TrimLeft(trimmed[len(marker):], " \t"), false
		}
	}
	// `1. ` and `12. `
	digits := 0
	for digits < len(trimmed) && trimmed[digits] >= '0' && trimmed[digits] <= '9' {
		digits++
	}
	if digits > 0 && digits+1 < len(trimmed) && trimmed[digits] == '.' && trimmed[digits+1] == ' ' {
		return strings.TrimLeft(trimmed[digits+1:], " \t"), false
	}
	return trimmed, false
}

// trimReference drops the punctuation a path picks up from the prose around it — the closing
// backtick of a code span, a comma, a full-width bracket. Without this, “ `skill read ns/s
// SKILL.md` “ reports the path as “SKILL.md` “ and every clean version fails.
func trimReference(candidate string) string {
	return strings.TrimRight(candidate, "`\"'.,;:!?，。；：！？、）)]}》」』】>")
}

// referenceTarget normalises one candidate, or returns "" when it is not a path this command can
// judge: an anchor-only link, an absolute URL, a site-absolute path, or a placeholder with obvious
// holes in it. The anchor is dropped from the rest — `..#section` points at a file that must exist.
func referenceTarget(candidate string) string {
	target := strings.TrimSpace(candidate)
	if hash := strings.IndexByte(target, '#'); hash >= 0 {
		target = target[:hash]
	}
	target = strings.TrimRight(target, "/")
	switch {
	case target == "":
		return ""
	case strings.Contains(target, "://"), strings.HasPrefix(target, "mailto:"):
		return ""
	case strings.HasPrefix(target, "/"), strings.HasPrefix(target, "~"):
		return ""
	case strings.ContainsAny(target, "<>{}*[]"):
		// A bracket means markdown syntax, not a file: a span that shows `[说明](x)` is an example
		// of how to write a link, and a real relpath does not contain `[` or `]`.
		return ""
	}
	return target
}

// looksLikePath keeps ordinary prose code spans out: `skillmaster login` is a command, not a file.
func looksLikePath(span string) bool {
	if strings.ContainsAny(span, " \t") {
		return false
	}
	if strings.Contains(span, "/") || strings.HasPrefix(span, ".") {
		return true
	}
	switch path.Ext(span) {
	case ".md", ".txt", ".json", ".yaml", ".yml", ".sh", ".py", ".js", ".ts", ".csv", ".toml":
		return true
	}
	return false
}

func lineAt(text string, offset int) int {
	if offset > len(text) {
		offset = len(text)
	}
	return strings.Count(text[:offset], "\n") + 1
}

// spanRanges turns code spans into the ranges the code-context check compares against.
func spanRanges(spans []sourced) []offsetRange {
	ranges := make([]offsetRange, 0, len(spans))
	for _, span := range spans {
		ranges = append(ranges, offsetRange{span.offset, span.offset + len(span.text)})
	}
	return ranges
}

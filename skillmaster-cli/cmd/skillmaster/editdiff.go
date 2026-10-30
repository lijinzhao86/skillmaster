package main

// The half of `skill edit` that never touches the network: how to show what a write changed, and
// what to say when the text was not found.
//
// **Why this exists at all.** Claude Code's `Edit` returns one sentence and the caller can run
// `git diff`; it also has a host that watches the file and hands back any change made behind its
// back. This CLI has neither — its `read` is a numbered rendering, so re-reading to check a write is
// both expensive and the exact path that produced a file full of `12\t` prefixes. So the command
// carries the verification itself: the applied hunks on success, and a diagnosis of *why* the text
// was not found on failure.

import (
	"bytes"
	"errors"
	"fmt"
	"regexp"
	"strings"
)

// maxEditEchoLines bounds what one successful edit prints. A change of one paragraph lands far
// under it; a whole-file rewrite exceeds it, and `version diff` is the authority for that case.
const maxEditEchoLines = 60

// editContext is how many unchanged lines a hunk shows on either side — git's own default, and the
// number the help page promises.
const editContext = 3

// readNumberedLine is `read`'s own rendering: the number, a tab, then the line. The shape is
// checked rather than assumed, because the diagnosis must not fire on content that happens to
// contain a number and a tab.
var readNumberedLine = regexp.MustCompile(`^([0-9]+)\t(.*)$`)

// readFooters are the notes `read` appends after the content: the version it resolved, the
// truncation notice, and the not-invoked line. Any of them at the end of a copied rendering has to
// be dropped before the text can be compared with the file.
var readFooters = []*regexp.Regexp{
	regexp.MustCompile(`^（按 .+ 取。）$`),
	regexp.MustCompile(`^（共 [0-9]+ 行，已显示到第 [0-9]+ 行。继续取：.*）$`),
	regexp.MustCompile(`^（.+ 还没 invoke 过，按当前线上版本取。.*）$`),
}

// lineEndingView is the text matching runs on: every terminator projected to LF.
//
// **Why project instead of comparing raw bytes.** Local `Edit` matches across line endings: an LF
// `old_string` finds a CRLF file (research/local-edit-mechanics.md, measured). Without the
// projection the tolerance would have to live in the caller, and the files that still hold CRLF are
// exactly the ones a caller cannot retype: `.bat` / `.cmd`, which v33 deliberately leaves alone, and
// drafts made before v33.
func lineEndingView(content []byte) string {
	view := make([]byte, 0, len(content))
	for i := 0; i < len(content); i++ {
		if content[i] == '\r' {
			if i+1 < len(content) && content[i+1] == '\n' {
				i++
			}
			view = append(view, '\n')
			continue
		}
		view = append(view, content[i])
	}
	return string(view)
}

// originalCursor walks the original bytes alongside the view. Matches arrive in order, so one cursor
// for the whole replacement keeps the mapping linear — the alternative, an offset per projected
// byte, costs eight bytes per byte of a file that may be 16 MB (the archive limit).
type originalCursor struct {
	content []byte
	viewPos int
	origPos int
}

// at returns the original offset of the byte the view shows at index target. It only moves forward.
func (c *originalCursor) at(target int) int {
	for c.viewPos < target {
		if c.content[c.origPos] == '\r' {
			c.origPos++
			if c.origPos < len(c.content) && c.content[c.origPos] == '\n' {
				c.origPos++
			}
		} else {
			c.origPos++
		}
		c.viewPos++
	}
	return c.origPos
}

// normalizeLineEndings is lineEndingView without the mapping: what `old` is compared as.
func normalizeLineEndings(text string) string {
	return strings.ReplaceAll(strings.ReplaceAll(text, "\r\n", "\n"), "\r", "\n")
}

// dominantTerminator is the file's own line-ending style: CRLF when it has more CRLF endings than LF
// ones, otherwise LF. A tie goes to LF — the same rule local `Edit` applies when it re-encodes a
// whole file.
func dominantTerminator(content []byte) string {
	crlf := bytes.Count(content, []byte("\r\n"))
	if lf := bytes.Count(content, []byte("\n")) - crlf; crlf > lf {
		return "\r\n"
	}
	return "\n"
}

// encodeReplacement writes the replacement in the file's own style. The replacement is projected to
// LF first, so typing CRLF into an LF file (or the reverse) cannot leave one file holding two kinds
// of ending.
func encodeReplacement(replacement, terminator string) string {
	normalised := normalizeLineEndings(replacement)
	if terminator == "\n" {
		return normalised
	}
	return strings.ReplaceAll(normalised, "\n", terminator)
}

// spliceReplacement replaces each match of wanted at its original bytes. **Everything outside the
// matches is copied verbatim**: v33 already canonicalises text files to LF on the server, and the
// files exempt from that (`.bat` / `.cmd`) are exactly the ones whose other bytes must not move.
func spliceReplacement(content []byte, view, wanted, replacement string, all bool) []byte {
	encoded := encodeReplacement(replacement, dominantTerminator(content))
	out := make([]byte, 0, len(content)+len(encoded))
	cursor := &originalCursor{content: content}
	copied, from := 0, 0
	for {
		at := strings.Index(view[from:], wanted)
		if at < 0 {
			break
		}
		start := from + at
		end := start + len(wanted)
		out = append(out, content[copied:cursor.at(start)]...)
		out = append(out, encoded...)
		copied = cursor.at(end)
		from = end
		if !all {
			break
		}
	}
	return append(out, content[copied:]...)
}

// editContentLines is the file as display lines: terminators removed, one entry per line.
func editContentLines(content []byte) []string {
	lines := splitLines(content)
	out := make([]string, len(lines))
	for i, line := range lines {
		out[i] = string(bytes.TrimRight(line, "\r\n"))
	}
	return out
}

// editLineStarts returns the byte offset at which each line of content begins.
func editLineStarts(content []byte) []int {
	lines := splitLines(content)
	starts := make([]int, 0, len(lines))
	offset := 0
	for _, line := range lines {
		starts = append(starts, offset)
		offset += len(line)
	}
	return starts
}

// editLineOf maps a byte offset to a 1-based line number.
func editLineOf(starts []int, offset int) int {
	if len(starts) == 0 {
		return 1
	}
	lo, hi := 0, len(starts)-1
	for lo < hi {
		mid := (lo + hi + 1) / 2
		if starts[mid] <= offset {
			lo = mid
		} else {
			hi = mid - 1
		}
	}
	return lo + 1
}

// lineOp is one line of a unified diff: kept, removed or added, with the line number it had on each
// side at that point.
type lineOp struct {
	kind  byte // ' ', '-' or '+'
	text  string
	oldNo int
	newNo int
}

// renderHunks draws the change, in git's own diff shape: three lines of context, `-` for what was
// removed, `+` for what was added, and a `@@` header whose numbers are real line numbers on both
// sides.
//
// **It diffs the two contents rather than replaying the replacement.** The first draft rebuilt the
// hunk from the occurrence list, and that is where three defects came from: an `--old` ending in a
// newline indexed past the line it belonged to (a panic on the single most common file shape), a
// second occurrence whose line was already inside the previous hunk was rendered as *unchanged*
// context, and deleting a whole line produced a hunk that claimed one line fewer than the file
// really lost. A diff of the before and after bytes has none of those failure modes, because it
// never has to reason about where the text came from.
//
// The middle of the file — between the first and last changed line — is the only part that needs an
// LCS; long middle regions fall back to "all removed, then all added", which is a valid edit script
// even when it is not the minimal one.
func renderHunks(before, after []byte) (rendered []string, truncated bool) {
	oldLines := editContentLines(before)
	newLines := editContentLines(after)

	prefix := 0
	for prefix < len(oldLines) && prefix < len(newLines) && oldLines[prefix] == newLines[prefix] {
		prefix++
	}
	suffix := 0
	for suffix < len(oldLines)-prefix && suffix < len(newLines)-prefix &&
		oldLines[len(oldLines)-1-suffix] == newLines[len(newLines)-1-suffix] {
		suffix++
	}
	if prefix == len(oldLines) && prefix == len(newLines) {
		return nil, false
	}

	kinds := make([]lineOp, 0, 16)
	for i := 0; i < prefix; i++ {
		kinds = append(kinds, lineOp{kind: ' ', text: oldLines[i]})
	}
	kinds = append(kinds, diffMiddle(oldLines[prefix:len(oldLines)-suffix], newLines[prefix:len(newLines)-suffix])...)
	for i := len(oldLines) - suffix; i < len(oldLines); i++ {
		kinds = append(kinds, lineOp{kind: ' ', text: oldLines[i]})
	}

	// Number the ops on both sides in one pass: a kept line advances both, a removal only the old
	// side, an addition only the new side.
	oldNo, newNo := 1, 1
	for i := range kinds {
		kinds[i].oldNo, kinds[i].newNo = oldNo, newNo
		switch kinds[i].kind {
		case ' ':
			oldNo++
			newNo++
		case '-':
			oldNo++
		case '+':
			newNo++
		}
	}

	// Group the changes: two changes belong to one hunk when no more than 2*context unchanged lines
	// separate them, which is what makes a run of nearby edits read as one change.
	var groups [][2]int // [first, last) op indices
	for i := 0; i < len(kinds); i++ {
		if kinds[i].kind == ' ' {
			continue
		}
		first := i
		last := i
		for j := i + 1; j < len(kinds); j++ {
			if kinds[j].kind != ' ' {
				last = j
				continue
			}
			kept := 0
			for k := j; k < len(kinds) && kinds[k].kind == ' '; k++ {
				kept++
			}
			if kept > 2*editContext {
				break
			}
			j += kept - 1
		}
		groups = append(groups, [2]int{first, last})
		i = last
	}

	for index, group := range groups {
		hunk := renderHunk(kinds, group)
		// **Whole hunks only.** Cutting inside one is how a header ends up promising lines that
		// are not there, and how a change that did make the cut disappears without a word.
		if len(rendered) > 0 && len(rendered)+len(hunk) > maxEditEchoLines {
			truncated = true
			break
		}
		if len(rendered) == 0 && len(hunk) > maxEditEchoLines {
			hunk = hunk[:maxEditEchoLines]
			for len(hunk) > 0 && strings.HasPrefix(hunk[len(hunk)-1], "@@") {
				hunk = hunk[:len(hunk)-1]
			}
			truncated = true
		}
		rendered = append(rendered, hunk...)
		if len(rendered) >= maxEditEchoLines && index < len(groups)-1 {
			truncated = true
			break
		}
	}
	return rendered, truncated
}

// renderHunk is one group of changes with its context, header first.
func renderHunk(kinds []lineOp, group [2]int) []string {
	from := group[0] - editContext
	if from < 0 {
		from = 0
	}
	to := group[1] + editContext + 1
	if to > len(kinds) {
		to = len(kinds)
	}
	oldStart, newStart := kinds[from].oldNo, kinds[from].newNo
	oldCount, newCount := 0, 0
	for _, op := range kinds[from:to] {
		if op.kind != '+' {
			oldCount++
		}
		if op.kind != '-' {
			newCount++
		}
	}
	hunk := make([]string, 0, to-from+1)
	hunk = append(hunk, fmt.Sprintf("@@ -%s +%s @@",
		editSpan(oldStart, oldCount), editSpan(newStart, newCount)))
	for _, op := range kinds[from:to] {
		hunk = append(hunk, string(op.kind)+op.text)
	}
	return hunk
}

// diffMiddle is the line diff of the changed region. It prefers a real LCS so that unchanged lines
// between two edits stay context; a region too large for the matrix falls back to removing every old
// line and adding every new one, which is coarser but never wrong.
func diffMiddle(oldLines, newLines []string) []lineOp {
	const maxCells = 4_000_000
	const maxLines = 4000
	if len(oldLines) > maxLines || len(newLines) > maxLines ||
		len(oldLines)*len(newLines) > maxCells {
		ops := make([]lineOp, 0, len(oldLines)+len(newLines))
		for _, line := range oldLines {
			ops = append(ops, lineOp{kind: '-', text: line})
		}
		for _, line := range newLines {
			ops = append(ops, lineOp{kind: '+', text: line})
		}
		return ops
	}

	table := make([][]int, len(oldLines)+1)
	for i := range table {
		table[i] = make([]int, len(newLines)+1)
	}
	for i := len(oldLines) - 1; i >= 0; i-- {
		for j := len(newLines) - 1; j >= 0; j-- {
			if oldLines[i] == newLines[j] {
				table[i][j] = table[i+1][j+1] + 1
			} else if table[i+1][j] >= table[i][j+1] {
				table[i][j] = table[i+1][j]
			} else {
				table[i][j] = table[i][j+1]
			}
		}
	}

	ops := make([]lineOp, 0, len(oldLines)+len(newLines))
	i, j := 0, 0
	for i < len(oldLines) && j < len(newLines) {
		switch {
		case oldLines[i] == newLines[j]:
			ops = append(ops, lineOp{kind: ' ', text: oldLines[i]})
			i++
			j++
		case table[i+1][j] >= table[i][j+1]:
			ops = append(ops, lineOp{kind: '-', text: oldLines[i]})
			i++
		default:
			ops = append(ops, lineOp{kind: '+', text: newLines[j]})
			j++
		}
	}
	for ; i < len(oldLines); i++ {
		ops = append(ops, lineOp{kind: '-', text: oldLines[i]})
	}
	for ; j < len(newLines); j++ {
		ops = append(ops, lineOp{kind: '+', text: newLines[j]})
	}
	return ops
}

// editSpan renders the `start,count` half of a hunk header, omitting a count of one the way git
// does.
func editSpan(start, count int) string {
	if count == 1 {
		return fmt.Sprintf("%d", start)
	}
	return fmt.Sprintf("%d,%d", start, count)
}

// diagnoseMiss is what a zero-match edit says instead of "not found": one of three things the
// caller can act on, tried in the order that names the mistake most precisely.
func diagnoseMiss(content []byte, old, address, relpath string) error {
	cat := fmt.Sprintf("skmr skill cat %s %s", address, relpath)

	if stripped, numbers, hadFooter, ok := stripReadLineNumbers(old); ok && stripped != "" {
		if looksLikeReadRendering(content, stripped, numbers, hadFooter) {
			return fmt.Errorf("你给的原文带着 read 的行号（`12\\t…` 这种）。去掉行号再试；"+
				"取原文：%s。文件没有改动。", cat)
		}
	}

	if start, end, actual, ok := whitespaceMatch(content, old); ok {
		var b strings.Builder
		if start == end {
			fmt.Fprintf(&b, "只差空白：第 %d 行的实际写法是：", start)
		} else {
			fmt.Fprintf(&b, "只差空白：第 %d–%d 行的实际写法是：", start, end)
		}
		for i, line := range actual {
			fmt.Fprintf(&b, "\n  %d  %s", start+i, line)
		}
		b.WriteString("\n文件没有改动。")
		return errors.New(b.String())
	}

	if line, text, ok := closestLine(content, old); ok {
		return fmt.Errorf("没找到。最接近的是第 %d 行：`%s`。先 %s 看一眼原文。文件没有改动。",
			line, text, cat)
	}

	return fmt.Errorf("这一版里没有找到要替换的文本；文件没有改动。先 %s 看一眼原文。", cat)
}

// looksLikeReadRendering decides whether a numbered text really came from a `read` rendering.
//
// **Two conditions keep it from firing on content that merely looks numbered.** The numbers must be
// consecutive, and the text without them must either appear as a whole line (one line) or be more
// than one line — a single `3\tfoo` that is nowhere in the file is far more likely to be a typo in
// real content than a copy of a three-line window beginning on line 3.
func looksLikeReadRendering(content []byte, stripped string, numbers []int, hadFooter bool) bool {
	for i := 1; i < len(numbers); i++ {
		if numbers[i] != numbers[i-1]+1 {
			return false
		}
	}
	// A trailing note (`（按 … 取。）` and friends) is the CLI's own sentence, so the text is a
	// rendering whatever its line count.
	if hadFooter {
		return true
	}
	// Two numbered lines are a copied window. One is only a copy when its text is a whole line:
	// a single `2\tnpm instxl` saved by an editor carries a trailing newline too, and counting
	// that as "more than one line" would blame a plain typo on `read`.
	if len(numbers) > 1 {
		return true
	}
	for _, line := range editContentLines(content) {
		if line == stripped {
			return true
		}
	}
	return false
}

// stripReadLineNumbers turns a copied `read` rendering back into the file's text. ok is false unless
// every non-empty line carried a number — a file may legitimately contain `3\tfoo`, and treating
// that as a rendering would rewrite real content.
func stripReadLineNumbers(text string) (stripped string, numbers []int, hadFooter bool, ok bool) {
	lines := strings.Split(text, "\n")
	// The CLI's own notes travel after the content; drop them, up to two, before comparing.
	// **The last non-empty line, not the last line**: a rendering copied out of a terminal ends
	// with a newline, so `lines[len-1]` is always empty and would never match a note.
	for dropped := 0; dropped < 2; dropped++ {
		last := -1
		for i := len(lines) - 1; i >= 0; i-- {
			if strings.TrimSpace(lines[i]) != "" {
				last = i
				break
			}
		}
		if last < 0 {
			break
		}
		matched := false
		for _, footer := range readFooters {
			if footer.MatchString(strings.TrimSpace(lines[last])) {
				matched = true
				break
			}
		}
		if !matched {
			break
		}
		lines = append(lines[:last], lines[last+1:]...)
		hadFooter = true
	}
	for i, line := range lines {
		if line == "" {
			continue
		}
		match := readNumberedLine.FindStringSubmatch(line)
		if match == nil {
			return text, nil, hadFooter, false
		}
		lines[i] = match[2]
		numbers = append(numbers, atoiDigits(match[1]))
	}
	if len(numbers) == 0 {
		return text, nil, hadFooter, false
	}
	return strings.Join(lines, "\n"), numbers, hadFooter, true
}

// looksLikeReadOutput reports whether the whole text is a copied `read` rendering, which is not the
// file's text: every non-empty line carries `N\t`, the numbers run consecutively, and the last note
// is one of `read`'s own footers.
//
// **All three, because two are not enough.** A file may legitimately contain `3\tfoo` (so the shape
// alone must not fire), and a caller may copy the body without the footer (so a footer alone must
// not fire either). What must never be written is the *whole* rendering — the numbers would become
// the content, which is the incident v32 exists for. Stripping the numbers instead of refusing is
// the wrong repair: a rendering is capped by lines and bytes, so it can be a truncated file wearing
// the original's clothes.
func looksLikeReadOutput(content []byte) bool {
	stripped, numbers, hadFooter, ok := stripReadLineNumbers(string(content))
	if !ok || !hadFooter || stripped == "" {
		return false
	}
	for i := 1; i < len(numbers); i++ {
		if numbers[i] != numbers[i-1]+1 {
			return false
		}
	}
	return true
}

// atoiDigits reads a run of ASCII digits; the regex has already established the shape.
func atoiDigits(digits string) int {
	value := 0
	for _, digit := range digits {
		value = value*10 + int(digit-'0')
	}
	return value
}

// whitespaceMatch reports where the text is once trailing spaces and the block's outer blank lines
// are ignored. It is a diagnosis, not a fallback: the edit still refuses, but the caller is told the
// one thing that differs.
func whitespaceMatch(content []byte, old string) (start, end int, actual []string, ok bool) {
	lines := editContentLines(content)
	normalised := make([]string, len(lines))
	for i, line := range lines {
		normalised[i] = strings.TrimRight(line, " \t")
	}
	wanted := normaliseOldText(old)
	if wanted == "" {
		return 0, 0, nil, false
	}
	haystack := strings.Join(normalised, "\n")
	index, unique := onlyOccurrence(haystack, wanted)
	if !unique {
		return 0, 0, nil, false
	}
	start = strings.Count(haystack[:index], "\n") + 1
	end = start + strings.Count(wanted, "\n")
	if end > len(lines) {
		return 0, 0, nil, false
	}
	for line := start; line <= end && len(actual) < 6; line++ {
		actual = append(actual, lines[line-1])
	}
	return start, end, actual, true
}

// onlyOccurrence picks the line-aligned occurrence of wanted in haystack. **A plain "appears once"
// test is too weak**: `npm install` also appears inside `npm install2` on the next line, and that
// would suppress the whitespace diagnosis exactly when it is true. So a single substring hit wins
// outright, and when there are several, the one that starts and ends a whole line is the answer.
func onlyOccurrence(haystack, wanted string) (int, bool) {
	first, alignedFirst := -1, -1
	count, alignedCount := 0, 0
	for from := 0; from <= len(haystack); {
		found := strings.Index(haystack[from:], wanted)
		if found < 0 {
			break
		}
		at := from + found
		count++
		if first < 0 {
			first = at
		}
		before := at == 0 || haystack[at-1] == '\n'
		end := at + len(wanted)
		after := end == len(haystack) || haystack[end] == '\n'
		if before && after {
			alignedCount++
			if alignedFirst < 0 {
				alignedFirst = at
			}
		}
		from = at + len(wanted)
	}
	// The whole string is walked rather than capped: an early stop could call a later aligned
	// occurrence invisible and turn "ambiguous" into "unique".
	if count == 1 {
		return first, true
	}
	if alignedCount == 1 {
		return alignedFirst, true
	}
	return 0, false
}

// normaliseOldText trims the same whitespace off the text being searched for, and drops blank lines
// at either end — the two differences a person makes when re-typing a fragment by hand. Carriage
// returns count as line ends here, so a CRLF file compared with an LF fragment still matches.
func normaliseOldText(old string) string {
	lines := strings.Split(old, "\n")
	for i, line := range lines {
		lines[i] = strings.TrimRight(line, " \t\r")
	}
	for len(lines) > 0 && strings.TrimSpace(lines[0]) == "" {
		lines = lines[1:]
	}
	for len(lines) > 0 && strings.TrimSpace(lines[len(lines)-1]) == "" {
		lines = lines[:len(lines)-1]
	}
	return strings.Join(lines, "\n")
}

// closestLine finds the line most like the first line of what was asked for. It is a hint for a
// person, so it stays conservative: below 0.6 similarity it returns nothing rather than pointing at
// an unrelated line.
//
// **The scan has a budget.** Two similar-length lines cost |wanted|×|candidate| comparisons, and a
// multi-megabyte file would spend minutes there for a hint nobody reads. The budget is measured in
// cells; a candidate too expensive for it is skipped rather than ending the scan, so one long line
// near the top cannot hide a near match below it.
func closestLine(content []byte, old string) (line int, text string, ok bool) {
	wanted := firstNonEmptyLine(old)
	if wanted == "" {
		return 0, "", false
	}
	lines := editContentLines(content)
	wantedRunes := []rune(wanted)
	best := 0.0
	bestLine := 0
	budget := 20_000_000
	for i, candidate := range lines {
		if strings.TrimSpace(candidate) == "" {
			continue
		}
		candidateRunes := []rune(candidate)
		longest := maxInt(len(wantedRunes), len(candidateRunes))
		if longest == 0 {
			continue
		}
		// A length difference alone bounds the similarity: at a difference of d over the longer
		// string, the best possible ratio is 1-d/longest. Skip what cannot reach 0.6.
		if float64(absInt(len(candidateRunes)-len(wantedRunes))) > 0.4*float64(longest) {
			continue
		}
		cells := len(wantedRunes) * len(candidateRunes)
		if cells > budget {
			// Skipped, not fatal: one long line near the top must not hide a near match below it.
			continue
		}
		budget -= cells
		ratio := 1 - float64(editDistance(wantedRunes, candidateRunes))/float64(longest)
		if ratio > best {
			best = ratio
			bestLine = i + 1
		}
	}
	if bestLine == 0 || best < 0.6 {
		return 0, "", false
	}
	text = lines[bestLine-1]
	if runes := []rune(text); len(runes) > 120 {
		text = string(runes[:120]) + "…"
	}
	return bestLine, text, true
}

// firstNonEmptyLine is what a multi-line fragment is compared by: the first line with something on
// it, since leading blank lines carry no signal.
func firstNonEmptyLine(text string) string {
	for _, line := range strings.Split(text, "\n") {
		if strings.TrimSpace(line) != "" {
			return line
		}
	}
	return ""
}

// editDistance is the Levenshtein distance between two rune slices.
func editDistance(a, b []rune) int {
	previous := make([]int, len(b)+1)
	for j := range previous {
		previous[j] = j
	}
	for i := 1; i <= len(a); i++ {
		current := make([]int, len(b)+1)
		current[0] = i
		for j := 1; j <= len(b); j++ {
			cost := 1
			if a[i-1] == b[j-1] {
				cost = 0
			}
			current[j] = minInt(current[j-1]+1, previous[j]+1, previous[j-1]+cost)
		}
		previous = current
	}
	return previous[len(b)]
}

func minInt(values ...int) int {
	best := values[0]
	for _, value := range values[1:] {
		if value < best {
			best = value
		}
	}
	return best
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func absInt(value int) int {
	if value < 0 {
		return -value
	}
	return value
}

// multipleMatchesError lists where the text appears, so `--replace-all` and "add more context" are
// both decisions a caller can make from the message alone.
//
// **Two numbers, because they answer two questions**: how many times the text occurs (what
// `--replace-all` would do), and on how many lines it sits (where to look). They differ as soon as
// one line holds two matches.
func multipleMatchesError(content []byte, old string, count int) error {
	text := string(content)
	var offsets []int
	for from := 0; from <= len(text); {
		found := strings.Index(text[from:], old)
		if found < 0 {
			break
		}
		at := from + found
		offsets = append(offsets, at)
		from = at + len(old)
	}
	return multipleMatchesErrorAt(text, offsets, count)
}

// multipleMatchesErrorAt is the same sentence for a caller that already knows where the matches are
// — `edit --regex` (v34): a pattern is not a substring, so searching for it in the content found
// nothing and printed an empty line list.
func multipleMatchesErrorAt(content string, offsets []int, count int) error {
	starts := editLineStarts([]byte(content))
	var lines []int
	seen := map[int]bool{}
	for _, at := range offsets {
		line := editLineOf(starts, at)
		if !seen[line] {
			seen[line] = true
			lines = append(lines, line)
		}
	}
	shown := lines
	suffix := ""
	if len(shown) > 6 {
		shown = shown[:6]
		suffix = fmt.Sprintf(" 等 %d 行", len(lines))
	}
	parts := make([]string, len(shown))
	for i, line := range shown {
		parts[i] = fmt.Sprintf("%d", line)
	}
	return fmt.Errorf("要替换的文本出现了 %d 次，在第 %s 行%s。加 --replace-all 全部替换，"+
		"或带上更多上下文只改一处。文件没有改动。",
		count, strings.Join(parts, "、"), suffix)
}

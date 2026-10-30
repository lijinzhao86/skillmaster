package main

import (
	"errors"
	"fmt"
	"io/fs"
	"path/filepath"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// v33's name refusals, said the way the version's PRD says them (§措辞).
//
// **Why the client does the talking.** The server answers with a path and a reason code; the
// sentence, the character it names and the suggestion are all facts the client can get right and
// the server should not spend its error body on. One vocabulary, two surfaces — the console's
// import sheet reads the same codes (v33 TD §接口).
const (
	issueInvalidChar      = "windows_invalid_char"
	issueReservedName     = "windows_reserved_name"
	issueTrailingSpace    = "trailing_space_or_dot"
	issuePathTooLong      = "path_too_long"
	issueCaseCollision    = "case_collision"
	issueUnicodeCollision = "unicode_collision"
	issueZipName          = "invalid_zip_name"

	// maxNameLines is how many problems a person is shown at once. More than this is not a list
	// anybody fixes in one pass, and the rest are found on the next attempt.
	maxNameLines = 10
)

// nameProblem is one line of the refusal: one issue, and the one or two paths it is about.
type nameProblem struct {
	paths []string
	issue string
}

func isNameIssue(issue string) bool {
	switch issue {
	case issueInvalidChar, issueReservedName, issueTrailingSpace, issuePathTooLong,
		issueCaseCollision, issueUnicodeCollision, issueZipName:
		return true
	}
	return false
}

// nameProblems groups the details into lines. A collision arrives as two adjacent details, one per
// path; anything else is one detail per line.
func nameProblems(details []api.IssueDetail) []nameProblem {
	problems := make([]nameProblem, 0, len(details))
	for index := 0; index < len(details); index++ {
		detail := details[index]
		if !isNameIssue(detail.Issue) {
			continue
		}
		if isCollision(detail.Issue) {
			if index+1 < len(details) && details[index+1].Issue == detail.Issue {
				problems = append(problems, nameProblem{
					paths: []string{detail.Field, details[index+1].Field},
					issue: detail.Issue,
				})
				index++
				continue
			}
		}
		problems = append(problems, nameProblem{paths: []string{detail.Field}, issue: detail.Issue})
	}
	return problems
}

func isCollision(issue string) bool {
	return issue == issueCaseCollision || issue == issueUnicodeCollision
}

// sentence is the shared reason, the same words the editor shows inline and the import sheet shows
// on its own line.
func (problem nameProblem) sentence() string {
	path := first(problem.paths)
	switch problem.issue {
	case issueInvalidChar:
		return charLabel(path) + "不能作为文件名（Windows）。"
	case issueReservedName:
		return reservedStem(path) + " 是 Windows 保留的设备名。"
	case issueTrailingSpace:
		return "结尾有一个空格（或点），Windows 会去掉它。"
	case issuePathTooLong:
		return "路径太长（单段超过 255 字节，或整个路径超过 1024 字节）。"
	case issueCaseCollision:
		return "两个名字在 macOS 和 Windows 上会被当成同一个文件。"
	case issueUnicodeCollision:
		return "两个名字只有编码写法不同，macOS 会把它们当成同一个文件。"
	case issueZipName:
		return "有一个文件名解不出来（压缩包不是 UTF-8 编码）。"
	}
	return "这个名字在 macOS 或 Windows 上不可用。"
}

// fix is what to do about it, when the client can say something definite. A suggestion is only
// made when it is certain to be free: `local` is the caller's own tree, and nil means "I do not
// know what else is in this version" — then no suggestion is offered rather than a wrong one.
func (problem nameProblem) fix(local map[string]bool) string {
	switch problem.issue {
	case issueInvalidChar:
		suggested, ok := suggestion(first(problem.paths), local)
		if !ok {
			return ""
		}
		return "改成「" + suggested + "」"
	case issueReservedName:
		suggested, ok := reservedSuggestion(first(problem.paths), local)
		if !ok {
			return "换一个名字"
		}
		return "改成「" + suggested + "」"
	case issueTrailingSpace:
		return "去掉它"
	case issuePathTooLong:
		return "缩短文件名或目录名"
	case issueCaseCollision, issueUnicodeCollision:
		return "改掉其中一个"
	case issueZipName:
		return "用 UTF-8 重新打包"
	}
	return ""
}

// line is one problem as the CLI prints it: path(s) → reason → what to do.
func (problem nameProblem) line(local map[string]bool) string {
	label := strings.Join(problem.paths, " 与 ")
	line := problem.sentence()
	// `invalid_zip_name` has no path to show — the name was lost in decoding — so the line is the
	// sentence alone rather than an empty label followed by a colon.
	if label != "" {
		line = label + "：" + line
	}
	if fix := problem.fix(local); fix != "" {
		line += fix
	}
	return line
}

// submitNameRefusal is `skill submit`'s answer to a package whose names cannot be restored on
// every system. It is deliberately not the generic `服务端答 400` line: the author is the only one
// who can fix this, and the fix is different per file.
func submitNameRefusal(server *api.ServerError, dir string) (string, bool) {
	problems := nameProblems(server.DetailList)
	if len(problems) == 0 {
		return "", false
	}
	local := localNames(dir)

	// **The header counts files, not lines.** A collision is one line naming two files; saying
	// "3 个文件名" about four names was the first version of this and it was wrong.
	var refused strings.Builder
	fmt.Fprintf(&refused, "错误：%d 个文件名在 macOS 或 Windows 上不可用；这次提交没有落库。\n",
		namedFiles(problems))
	shown := problems
	if len(shown) > maxNameLines {
		shown = shown[:maxNameLines]
	}
	for _, problem := range shown {
		refused.WriteString("  " + problem.line(local) + "\n")
	}
	if rest := namedFiles(problems) - namedFiles(shown); rest > 0 {
		fmt.Fprintf(&refused, "  还有 %d 个。\n", rest)
	}
	fmt.Fprintf(&refused, "改完再提交：skmr skill submit %s", dir)
	return refused.String(), true
}

// namedFiles counts the files a set of problems is about — two for a collision line, one otherwise
// (and one for a name that could not be decoded, which has no path to show).
func namedFiles(problems []nameProblem) int {
	count := 0
	for _, problem := range problems {
		if len(problem.paths) == 0 {
			count++
			continue
		}
		count += len(problem.paths)
	}
	return count
}

// writeNameRefusal is the same thing for one file. There is one problem and one next step, so it
// is a sentence rather than a list — and no suggestion, because this command cannot see the rest
// of the version the name has to be unique within.
func writeNameRefusal(server *api.ServerError) (string, bool) {
	problems := nameProblems(server.DetailList)
	if len(problems) == 0 {
		return "", false
	}
	// The first is the target — the server writes the path the request named first, and the one it
	// collided with second (v33 TD §接口).
	return "错误：这个文件名在 macOS 或 Windows 上不可用；草稿没有改动。\n  " +
		problems[0].line(nil) + "\n改完再写一次。", true
}

// submitDirSpelling is the directory as the caller wrote it: the next-step line is a command they
// are meant to paste, and pasting an absolute path they never typed reads as somebody else's.
func submitDirSpelling(dirArgs []string) string {
	if len(dirArgs) > 0 && dirArgs[0] != "" {
		return dirArgs[0]
	}
	return "."
}

// droppedLine is the one line a successful submission adds when the door discarded something.
//
// It says "不是 skill 内容的文件" rather than naming the artefacts as junk: the author may have
// packed a `.DS_Store` on purpose without knowing what it was, and the sentence that helps is the
// one that says what happened to it, not the one that judges it.
func droppedLine(dropped []string) string {
	if len(dropped) == 0 {
		return ""
	}
	shown := dropped
	if len(shown) > maxNameLines {
		shown = shown[:maxNameLines]
	}
	line := fmt.Sprintf("  忽略了 %d 个不是 skill 内容的文件：%s", len(dropped),
		strings.Join(shown, "、"))
	if rest := len(dropped) - len(shown); rest > 0 {
		line += fmt.Sprintf("，还有 %d 个", rest)
	}
	return line
}

// uploadRefusal routes a refused submission to the name renderer, or says it is not one.
func uploadRefusal(err error, dir string) (string, bool) {
	var server *api.ServerError
	if !errors.As(err, &server) || server.Code != "invalid_upload" {
		return "", false
	}
	return submitNameRefusal(server, dir)
}

// notANameRefusal keeps every other `invalid_upload` answering the way it did before v33: the
// envelope's own text, which is where the other per-file reasons (a missing SKILL.md, an oversized
// package) have always been said. The alternative — the server's one-line English message — would
// be a quieter answer than the one authors get today.
func notANameRefusal(err error) error {
	var server *api.ServerError
	if !errors.As(err, &server) || server.Code != "invalid_upload" {
		return nil
	}
	return fmt.Errorf("服务端答 %d：%s", server.Status, server.Body)
}

// localNames is every file under dir, lowercased and slash-separated — what a suggestion has to be
// different from. Case matters because macOS and Windows compare names that way, which is the whole
// point of the rule being applied.
func localNames(dir string) map[string]bool {
	names := map[string]bool{}
	_ = filepath.WalkDir(dir, func(path string, entry fs.DirEntry, err error) error {
		if err != nil || entry.IsDir() {
			return nil
		}
		relative, err := filepath.Rel(dir, path)
		if err != nil {
			return nil
		}
		names[strings.ToLower(filepath.ToSlash(relative))] = true
		return nil
	})
	return names
}

// suggestion replaces the characters Windows refuses with '-', and returns it only when it differs
// and is free in the caller's own tree.
func suggestion(path string, local map[string]bool) (string, bool) {
	if local == nil {
		return "", false
	}
	var replaced strings.Builder
	changed := false
	for _, char := range path {
		if isWindowsInvalid(char) {
			replaced.WriteByte('-')
			changed = true
			continue
		}
		replaced.WriteRune(char)
	}
	if !changed {
		return "", false
	}
	suggested := replaced.String()
	if local[strings.ToLower(suggested)] {
		return "", false
	}
	return suggested, true
}

// charLabel names the character the path has trouble with, in the interface's words rather than as
// a code point — "冒号不能作为文件名" is something a person can act on.
func charLabel(path string) string {
	for _, char := range path {
		switch {
		case char < 0x20 || char == 0x7F:
			return "控制字符"
		case char == '<' || char == '>':
			return "尖括号"
		case char == ':':
			return "冒号"
		case char == '"':
			return "引号"
		case char == '|':
			return "竖线"
		case char == '?':
			return "问号"
		case char == '*':
			return "星号"
		}
	}
	return "这个字符"
}

func isWindowsInvalid(char rune) bool {
	if char < 0x20 || char == 0x7F {
		return true
	}
	switch char {
	case '<', '>', ':', '"', '|', '?', '*':
		return true
	}
	return false
}

// reservedDeviceNames is what Windows treats as a device. Kept in one place so the sentence and the
// suggestion cannot disagree about which names are in it.
var reservedDeviceNames = func() []string {
	names := []string{"CON", "PRN", "AUX", "NUL"}
	for number := 1; number <= 9; number++ {
		names = append(names, fmt.Sprintf("COM%d", number), fmt.Sprintf("LPT%d", number))
	}
	return names
}()

// reservedStem is the segment Windows reads as a device — the part before its first dot, which is
// what makes `CON.md` as unusable as `CON`.
func reservedStem(path string) string {
	for _, segment := range strings.Split(path, "/") {
		if stem := stemOf(segment); isReservedName(stem) {
			return stem
		}
	}
	return "这个文件名"
}

// reservedSuggestion prefixes the offending segment with '_': the smallest change that keeps the
// name recognisable, which is why the PRD offers it. Only when the result is free in the caller's
// own tree — a suggestion that collides is worse than none.
func reservedSuggestion(path string, local map[string]bool) (string, bool) {
	if local == nil {
		return "", false
	}
	segments := strings.Split(path, "/")
	changed := false
	for index, segment := range segments {
		if isReservedName(stemOf(segment)) {
			segments[index] = "_" + segment
			changed = true
		}
	}
	if !changed {
		return "", false
	}
	suggested := strings.Join(segments, "/")
	if local[strings.ToLower(suggested)] {
		return "", false
	}
	return suggested, true
}

func stemOf(segment string) string {
	if dot := strings.Index(segment, "."); dot >= 0 {
		return strings.ToUpper(segment[:dot])
	}
	return strings.ToUpper(segment)
}

func isReservedName(stem string) bool {
	upper := strings.ToUpper(stem)
	for _, name := range reservedDeviceNames {
		if upper == name {
			return true
		}
	}
	return false
}

func first(values []string) string {
	if len(values) == 0 {
		return ""
	}
	return values[0]
}

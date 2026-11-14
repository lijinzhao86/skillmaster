package main

// The two-layer contract: `--help` is the command reference, the Guide is the manual. These tests
// read the Guide source the way an Agent would, and fail when it sends a reader to a command that
// does not exist or to a retired one. **A moved, unfixed Guide is expected to fail here**; the
// failures are recorded in testdata/help-guide-known-issues.json and may only shrink (ADR 0173).

import (
	"encoding/json"
	"flag"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
	"testing"
	"unicode/utf8"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

func helpJSONUnmarshal(data []byte, v any) error { return json.Unmarshal(data, v) }

func helpJSON(t *testing.T, v any) []byte {
	t.Helper()
	data, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		t.Fatalf("encode JSON: %v", err)
	}
	return append(data, '\n')
}

var updateGuideBaseline = flag.Bool("update-guide-baseline", false,
	"rewrite testdata/help-guide-known-issues.json from the current Guide violations")

// retiredWords are the spellings ADR 0136 removed. They are not commands any more; a Guide that
// still teaches them gets an entry in the baseline, never a free pass.
var retiredWords = []string{"@new", "skill put", "skill download", "skill files", "`put`", "`download`"}

// retiredHeads are the words that follow a group when the retired spelling was a command. The
// unknown-command check skips them so one retired line produces one baseline entry, not two.
var retiredHeads = map[string]bool{"put": true, "download": true, "files": true}

// retiredTopLevel is the same set for a bare invocation (`skmr put ...`): it is a retired spelling,
// not merely an unknown command, so the baseline names the real problem.
var retiredTopLevel = map[string]bool{"put": true, "download": true}

// requiredCapabilities are the entries a reader of the Guide index must be able to find. Missing
// ones are baseline entries today; the content round removes them. `draft` needs `draft create`:
// the bare word already appears in the concept link and in `--status draft`, which is not an entry
// for creating a draft.
var requiredCapabilities = []struct {
	Name   string
	Needle string
}{
	{"draft", "draft create"},
	{"secret", "skmr secret"},
	{"run", "skmr run"},
	{"notifications", "notifications"},
}

type guideIssue struct {
	Check string `json:"check"`
	File  string `json:"file"`
	Match string `json:"match"`
	Note  string `json:"note,omitempty"`
}

type guideBaseline struct {
	Entries []guideIssue `json:"entries"`
}

func (i guideIssue) key() string { return i.Check + "\x00" + i.File + "\x00" + i.Match }

// repoRoot walks up from this test file to the directory that holds both skills/ and the CLI
// module. runtime.Caller is used instead of the working directory so the test works from any
// package directory CI chooses.
func helpRepoRoot(t *testing.T) string {
	t.Helper()
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	dir := filepath.Dir(file)
	for {
		if _, err := os.Stat(filepath.Join(dir, "skills")); err == nil {
			if _, err := os.Stat(filepath.Join(dir, "skillmaster-cli", "go.mod")); err == nil {
				return dir
			}
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatalf("repo root not found from %s", file)
		}
		dir = parent
	}
}

type guideCommand struct {
	spec  *help.Spec
	flags map[string]bool
}

func helpCommandTable() map[string]*guideCommand {
	table := make(map[string]*guideCommand)
	for _, spec := range commandHelp.Specs() {
		flags := map[string]bool{"--help": true, "-h": true}
		for _, f := range spec.Flags {
			flags[f.Name] = true
			for _, alias := range f.Aliases {
				flags[alias] = true
			}
		}
		for _, f := range help.Universal(commandHelp, spec.Path) {
			flags[f.Name] = true
			for _, alias := range f.Aliases {
				flags[alias] = true
			}
		}
		table[spec.Path] = &guideCommand{spec: spec, flags: flags}
	}
	return table
}

// guideFiles is every Markdown file the checks read: the moved Guide, plus the entry skill that
// routes an Agent to it.
func guideFiles(t *testing.T, root string) []string {
	t.Helper()
	var files []string
	guideDir := filepath.Join(root, "skills", "skillmaster-guide")
	err := filepath.WalkDir(guideDir, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !d.IsDir() && strings.HasSuffix(path, ".md") {
			files = append(files, path)
		}
		return nil
	})
	if err != nil {
		t.Fatalf("walk the Guide source: %v", err)
	}
	entry := filepath.Join(root, "skills", "skillmaster", "SKILL.md")
	if _, err := os.Stat(entry); err != nil {
		t.Fatalf("the entry skill is missing: %v", err)
	}
	return append(files, entry)
}

// skmrInvocations returns every `skmr` invocation on a Guide line. A line can name more than one
// command ("`skmr skill submit` 或 `skmr skill write` 报错"); checking only the first leaves the
// rest unvalidated. A quoted value stays one token and scanning continues after it, so a flag
// written after `--name "..."` is still checked.
func skmrInvocations(line string) [][]string {
	var all [][]string
	for pos := 0; pos < len(line); {
		at := nextSkmr(line, pos)
		if at < 0 {
			return all
		}
		fields, consumed := splitShellFields(line[at+len("skmr"):])
		if len(fields) > 0 {
			all = append(all, fields)
		}
		pos = at + len("skmr") + consumed
	}
	return all
}

func nextSkmr(line string, from int) int {
	for start := from; start < len(line); {
		at := strings.Index(line[start:], "skmr")
		if at < 0 {
			return -1
		}
		at += start
		end := at + len("skmr")
		beforeOK := at == 0 || !isWordByte(line[at-1])
		afterOK := end == len(line) || line[end] == ' ' || line[end] == '\t'
		if beforeOK && afterOK {
			return at
		}
		start = end
	}
	return -1
}

func isWordByte(b byte) bool {
	return b == '_' || b == '-' || (b >= '0' && b <= '9') || (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
}

// splitShellFields splits one invocation into arguments and returns how many bytes of s it read. A
// following `skmr` at a token boundary ends the invocation. Backticks, pipes and `#` end the
// command the way the surrounding Markdown does.
func splitShellFields(s string) ([]string, int) {
	var fields []string
	var cur strings.Builder
	inSingle, inDouble := false, false
	flush := func() {
		if cur.Len() > 0 {
			fields = append(fields, cur.String())
			cur.Reset()
		}
	}
	for i, r := range s {
		if !inSingle && !inDouble && cur.Len() == 0 && strings.HasPrefix(s[i:], "skmr") {
			end := i + len("skmr")
			if end == len(s) || s[end] == ' ' || s[end] == '\t' {
				flush()
				return fields, i
			}
		}
		switch {
		case inSingle:
			if r == '\'' {
				inSingle = false
			} else {
				cur.WriteRune(r)
			}
		case inDouble:
			if r == '"' {
				inDouble = false
			} else {
				cur.WriteRune(r)
			}
		case r == '\'':
			inSingle = true
		case r == '"':
			inDouble = true
		case r == '`' || r == '|' || r == '#':
			flush()
			return fields, i
		case r == ' ' || r == '\t':
			flush()
		default:
			cur.WriteRune(r)
		}
	}
	flush()
	return fields, len(s)
}

// commandHead keeps prose out of the command parser: a name is ASCII lowercase, a digit, or a
// dash. Chinese prose after `skmr` (frontmatter descriptions, headings) is not an invocation.
var commandHead = regexp.MustCompile(`^[a-z][a-z0-9-]*$`)

func trimToken(token string) string {
	return strings.TrimRight(token, "，。；、,.)")
}

// guideViolations reads every Guide line and returns the three machine-checkable kinds of drift:
// retired spellings, command paths that do not exist, and flags the command does not declare.
func guideViolations(t *testing.T, root string) []guideIssue {
	t.Helper()
	table := helpCommandTable()
	var issues []guideIssue
	for _, path := range guideFiles(t, root) {
		rel, err := filepath.Rel(root, path)
		if err != nil {
			t.Fatalf("relative path: %v", err)
		}
		rel = filepath.ToSlash(rel)
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("read %s: %v", rel, err)
		}
		for _, line := range strings.Split(string(data), "\n") {
			for _, word := range retiredWords {
				if strings.Contains(line, word) {
					issues = append(issues, guideIssue{
						Check: "retired_word", File: rel,
						Match: strings.TrimSpace(line) + " :: " + word,
					})
				}
			}
			for _, args := range skmrInvocations(line) {
				issues = append(issues, invocationIssues(rel, line, args, table)...)
			}
		}
	}
	sortIssues(issues)
	return dedupeIssues(issues)
}

// dedupeIssues collapses identical (check, file, match) keys. A line that repeats the same mistake
// twice is one issue with one fix; two identical baseline entries would make the ratchet depend on
// the duplicate being preserved.
func dedupeIssues(issues []guideIssue) []guideIssue {
	out := issues[:0]
	for i, issue := range issues {
		if i > 0 && issue.key() == issues[i-1].key() {
			continue
		}
		out = append(out, issue)
	}
	return out
}

// invocationIssues checks one `skmr …` on a line: whether the longest command path exists, whether
// the word after a pure group is one of its children, and whether each flag is declared.
func invocationIssues(rel, line string, args []string, table map[string]*guideCommand) []guideIssue {
	var issues []guideIssue
	tokens := make([]string, len(args))
	for i := range args {
		tokens[i] = trimToken(args[i])
	}
	if len(tokens) == 0 || tokens[0] == "help" || strings.HasPrefix(tokens[0], "-") ||
		!commandHead.MatchString(tokens[0]) {
		return nil
	}
	if strings.Contains(strings.Join(tokens, " "), "@new") {
		// The retired draft-creation form; the retired-word check owns it.
		return nil
	}
	if retiredTopLevel[tokens[0]] {
		return []guideIssue{{
			Check: "retired_word", File: rel,
			Match: strings.TrimSpace(line) + " :: " + tokens[0],
		}}
	}
	command, rest, ok := longestCommand(table, tokens)
	if !ok {
		return []guideIssue{{
			Check: "unknown_command", File: rel,
			Match: strings.TrimSpace(line) + " :: " + strings.Join(tokens[:helpMin(3, len(tokens))], " "),
		}}
	}
	info := table[command]
	if info.spec.PureGroup && len(rest) > 0 {
		next := rest[0]
		if retiredHeads[next] {
			// A retired command spelled after its group; the retired-word check owns it,
			// and its flags are not this group's flags.
			return nil
		}
		if isLiteral(next) && !helpContains(info.spec.Children, next) {
			issues = append(issues, guideIssue{
				Check: "unknown_command", File: rel,
				Match: strings.TrimSpace(line) + " :: " + command + " " + next,
			})
		}
	}
	for _, bad := range undeclaredFlags(command, rest, info) {
		issues = append(issues, guideIssue{
			Check: "unknown_flag", File: rel,
			Match: strings.TrimSpace(line) + " :: " + bad,
		})
	}
	return issues
}

// TestGuideParserSeesFlagsAfterQuotedValues pins the parser's blind spot: a flag that follows a
// quoted value (`--old "…" --new "…"`) is still an argument of the command, not prose.
func TestGuideParserSeesFlagsAfterQuotedValues(t *testing.T) {
	line := "skmr skill edit <地址>@<版本码> SKILL.md --old \"旧文本\" --new \"新文本\""
	got := skmrInvocations(line)
	want := [][]string{{"skill", "edit", "<地址>@<版本码>", "SKILL.md", "--old", "旧文本", "--new", "新文本"}}
	if len(got) != len(want) || strings.Join(got[0], "\x00") != strings.Join(want[0], "\x00") {
		t.Errorf("skmrInvocations(%q) = %q, want %q", line, got, want)
	}
}

// TestGuideParserSeesEveryInvocationOnALine covers the second command on a line: the Guide writes
// "`skmr skill submit` 或 `skmr skill write` 报错", and only checking the first left the rest free.
func TestGuideParserSeesEveryInvocationOnALine(t *testing.T) {
	line := "`skmr skill submit`、`skmr skill frobnicate --bogus` 或 `skmr skill write` 报错"
	got := skmrInvocations(line)
	if len(got) != 3 {
		t.Fatalf("skmrInvocations(%q) found %d invocations, want 3: %q", line, len(got), got)
	}
	if got[1][0] != "skill" || got[1][1] != "frobnicate" || got[1][2] != "--bogus" {
		t.Errorf("the second invocation parsed as %q", got[1])
	}
}

func longestCommand(table map[string]*guideCommand, tokens []string) (string, []string, bool) {
	limit := len(tokens)
	if limit > 4 {
		limit = 4
	}
	for n := limit; n >= 1; n-- {
		path := strings.Join(tokens[:n], " ")
		if _, ok := table[path]; ok {
			return path, tokens[n:], true
		}
	}
	return "", tokens, false
}

func isLiteral(token string) bool {
	if token == "" || strings.HasPrefix(token, "-") {
		return false
	}
	for _, prefix := range []string{"<", "[", "./", "~", "\"", "'", "$", "@"} {
		if strings.HasPrefix(token, prefix) {
			return false
		}
	}
	return true
}

func undeclaredFlags(command string, rest []string, info *guideCommand) []string {
	var bad []string
	for _, token := range rest {
		if token == "--" {
			break
		}
		if !strings.HasPrefix(token, "-") || token == "-" {
			continue
		}
		name := token
		if cut := strings.Index(name, "="); cut >= 0 {
			name = name[:cut]
		}
		if info.flags[name] {
			continue
		}
		// A few switches are accepted but deliberately absent from help; the existing flag
		// attribution test is their home. Reuse it rather than declaring them twice.
		if _, ok := unadvertisedPerCommand[command][name]; ok {
			continue
		}
		bad = append(bad, name)
	}
	return bad
}

func missingCapabilities(t *testing.T, root string) []guideIssue {
	t.Helper()
	index := filepath.Join(root, "skills", "skillmaster-guide", "SKILL.md")
	data, err := os.ReadFile(index)
	if err != nil {
		t.Fatalf("read the Guide index: %v", err)
	}
	text := string(data)
	var issues []guideIssue
	for _, capability := range requiredCapabilities {
		if !strings.Contains(text, capability.Needle) {
			issues = append(issues, guideIssue{
				Check: "missing_capability", File: "skills/skillmaster-guide/SKILL.md",
				Match: capability.Name,
			})
		}
	}
	return issues
}

func readGuideBaseline(t *testing.T, path string) []guideIssue {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read the Guide baseline: %v", err)
	}
	var file guideBaseline
	if err := helpJSONUnmarshal(data, &file); err != nil {
		t.Fatalf("parse the Guide baseline: %v", err)
	}
	sortIssues(file.Entries)
	return dedupeIssues(file.Entries)
}

func sortIssues(issues []guideIssue) {
	sort.Slice(issues, func(i, j int) bool { return issues[i].key() < issues[j].key() })
}

func issueSet(issues []guideIssue) map[string]guideIssue {
	set := make(map[string]guideIssue, len(issues))
	for _, issue := range issues {
		set[issue.key()] = issue
	}
	return set
}

func helpContains(list []string, want string) bool {
	for _, item := range list {
		if item == want {
			return true
		}
	}
	return false
}

func helpMin(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func guideBaselinePath(root string) string {
	return filepath.Join(root, "skillmaster-cli", "testdata", "help-guide-known-issues.json")
}

// TestGuideKnownIssues is the ratchet: the current violations must equal the baseline exactly.
// A new violation fails; removing a violation without deleting its baseline entry fails too.
func TestGuideKnownIssues(t *testing.T) {
	root := helpRepoRoot(t)
	path := guideBaselinePath(root)
	current := append(guideViolations(t, root), missingCapabilities(t, root)...)
	sortIssues(current)

	if *updateGuideBaseline {
		if err := os.WriteFile(path, helpJSON(t, guideBaseline{Entries: current}), 0o644); err != nil {
			t.Fatalf("write the Guide baseline: %v", err)
		}
		t.Logf("wrote %d known issues to %s", len(current), path)
		return
	}

	baseline := readGuideBaseline(t, path)
	if len(baseline) != len(current) {
		t.Errorf("the Guide has %d known issues, the baseline has %d; the diff follows; run -update-guide-baseline only after reviewing it", len(current), len(baseline))
	}
	want := issueSet(baseline)
	have := issueSet(current)
	// Iterate the sorted slices, not the maps: a map's order is random, and a failure report that
	// changes between runs is hard to compare with the baseline.
	for _, issue := range current {
		if _, ok := want[issue.key()]; !ok {
			t.Errorf("new Guide violation: %s %s %q", issue.Check, issue.File, issue.Match)
		}
	}
	for _, issue := range baseline {
		if _, ok := have[issue.key()]; !ok {
			t.Errorf("baseline entry no longer occurs; delete it from the baseline: %s %s %q", issue.Check, issue.File, issue.Match)
		}
	}
}

func TestGuideRetiredWords(t *testing.T) {
	root := helpRepoRoot(t)
	baseline := issueSet(readGuideBaseline(t, guideBaselinePath(root)))
	for _, issue := range guideViolations(t, root) {
		if issue.Check == "retired_word" {
			if _, ok := baseline[issue.key()]; !ok {
				t.Errorf("retired spelling outside the baseline: %s %q", issue.File, issue.Match)
			}
		}
	}
}

func TestGuideCommandsAreDeclared(t *testing.T) {
	root := helpRepoRoot(t)
	baseline := issueSet(readGuideBaseline(t, guideBaselinePath(root)))
	for _, issue := range guideViolations(t, root) {
		if issue.Check == "unknown_command" || issue.Check == "unknown_flag" {
			if _, ok := baseline[issue.key()]; !ok {
				t.Errorf("command or flag outside the baseline: %s %s %q", issue.Check, issue.File, issue.Match)
			}
		}
	}
}

func TestGuideIndexMentionsCapabilities(t *testing.T) {
	root := helpRepoRoot(t)
	baseline := issueSet(readGuideBaseline(t, guideBaselinePath(root)))
	for _, issue := range missingCapabilities(t, root) {
		if _, ok := baseline[issue.key()]; !ok {
			t.Errorf("missing capability outside the baseline: %q", issue.Match)
		}
	}
}

// TestTopLevelColumnsAlign is the rendered-page check the declaration tests cannot see: every
// command's summary must start in the same column, including the 13-character `notifications`.
func TestTopLevelColumnsAlign(t *testing.T) {
	text := help.RenderTop(commandHelp)
	lines := commandBlockLines(t, text, "命令：")
	if len(lines) == 0 {
		t.Fatal("the top-level page has no 命令 block")
	}
	column := -1
	for _, line := range lines {
		got := summaryColumn(t, line)
		if column < 0 {
			column = got
			continue
		}
		if got != column {
			t.Errorf("summary starts at column %d, want %d: %q", got, column, line)
		}
	}
}

func TestGroupColumnsAlign(t *testing.T) {
	for _, spec := range commandHelp.Specs() {
		if len(spec.Children) == 0 || spec.Path == "" {
			continue
		}
		t.Run(spec.Path, func(t *testing.T) {
			text := help.Render(commandHelp, spec.Path)
			lines := commandBlockLines(t, text, "子命令：")
			if len(lines) == 0 {
				t.Fatalf("%s renders no 子命令 block", spec.Path)
			}
			column := -1
			for _, line := range lines {
				got := summaryColumn(t, line)
				if column < 0 {
					column = got
					continue
				}
				if got != column {
					t.Errorf("summary starts at column %d, want %d: %q", got, column, line)
				}
			}
		})
	}
}

func commandBlockLines(t *testing.T, text, heading string) []string {
	t.Helper()
	_, after, found := strings.Cut(text, heading+"\n")
	if !found {
		return nil
	}
	var lines []string
	for _, line := range strings.Split(after, "\n") {
		if strings.TrimSpace(line) == "" {
			break
		}
		if strings.HasPrefix(line, "  ") {
			lines = append(lines, line)
		}
	}
	return lines
}

func summaryColumn(t *testing.T, line string) int {
	t.Helper()
	trimmed := strings.TrimLeft(line, " ")
	name := strings.Fields(trimmed)
	if len(name) == 0 {
		t.Fatalf("a command block line has no name: %q", line)
	}
	at := strings.Index(trimmed, name[0]) + len(name[0])
	rest := strings.TrimLeft(trimmed[at:], " ")
	start := at + (len(trimmed[at:]) - len(rest))
	// The renderer pads by runes, so the column is counted in runes too.
	return utf8.RuneCountInString(trimmed[:start])
}

// TestNoDuplicateSemantics catches the two facts that were written twice on one page: the value
// ceiling in `secret set`, and the two spellings of the credential failure.
func TestNoDuplicateSemantics(t *testing.T) {
	for _, spec := range commandHelp.Specs() {
		text := help.Render(commandHelp, spec.Path)
		if strings.Count(text, "32 KiB") > 1 {
			t.Errorf("%s says 32 KiB more than once", spec.Path)
		}
		if strings.Count(text, "凭据不被接受") != 0 {
			t.Errorf("%s uses the retired credential sentence", spec.Path)
		}
		if strings.Count(text, "服务端不认这份凭据") != 0 {
			t.Errorf("%s has its own spelling of the credential failure", spec.Path)
		}
		if strings.Count(text, "凭据不可用") > 1 {
			t.Errorf("%s says 凭据不可用 more than once", spec.Path)
		}
	}
}

// TestReadExamplesPinAVersion keeps the four read examples paste-ready: a bare address reading
// SKILL.md is refused by the "read = load" rule, so every example names a version.
func TestReadExamplesPinAVersion(t *testing.T) {
	for _, path := range []string{"skill read", "skill cat", "skill head", "skill tail"} {
		spec, ok := help.Lookup(commandHelp, path)
		if !ok {
			t.Fatalf("%q has no declaration", path)
		}
		for _, example := range spec.Examples {
			// The address is the first argument after the command name that is not a flag;
			// counting fields breaks the moment a flag gains a value or moves.
			fields := strings.Fields(example)
			address := ""
			for _, field := range fields[helpMin(3, len(fields)):] {
				if !strings.HasPrefix(field, "-") {
					address = field
					break
				}
			}
			at := strings.LastIndex(address, "@")
			if at < 1 || at == len(address)-1 {
				t.Errorf("%s example does not pin a version: %q", path, example)
			}
		}
	}
}

// TestEvalTableTargetsLiveCommands keeps the ruler honest: every expected path exists, no task
// points at a retired spelling, and the new capabilities are represented.
func TestEvalTableTargetsLiveCommands(t *testing.T) {
	root := helpRepoRoot(t)
	data, err := os.ReadFile(filepath.Join(root, "skillmaster-cli", "testdata", "help-eval.json"))
	if err != nil {
		t.Fatalf("read the eval table: %v", err)
	}
	var table struct {
		Tasks []struct {
			ID       string `json:"id"`
			Prompt   string `json:"prompt"`
			Expected string `json:"expected"`
		} `json:"tasks"`
	}
	if err := helpJSONUnmarshal(data, &table); err != nil {
		t.Fatalf("parse the eval table: %v", err)
	}
	if len(table.Tasks) != 20 {
		t.Fatalf("the ruler is 20 tasks, found %d", len(table.Tasks))
	}
	commands := helpCommandTable()
	seen := map[string]bool{}
	ids := map[string]bool{}
	expected := map[string]string{}
	for _, task := range table.Tasks {
		if ids[task.ID] {
			t.Errorf("the eval table repeats the id %q", task.ID)
		}
		ids[task.ID] = true
		if other, ok := expected[task.Expected]; ok {
			t.Errorf("%s and %s expect the same command %q", other, task.ID, task.Expected)
		}
		expected[task.Expected] = task.ID
		for _, word := range retiredWords {
			if strings.Contains(task.Expected, word) {
				t.Errorf("%s still expects a retired spelling: %q", task.ID, task.Expected)
			}
		}
		if promptNamesCommand(task.Prompt, task.Expected) {
			t.Errorf("%s leaks its expected command in the prompt: %q", task.ID, task.Expected)
		}
		info, ok := commands[task.Expected]
		if !ok {
			t.Errorf("%s expects a command that does not exist: %q", task.ID, task.Expected)
			continue
		}
		if info.spec.PureGroup {
			t.Errorf("%s expects a group, not a leaf: %q", task.ID, task.Expected)
			continue
		}
		seen[task.Expected] = true
	}
	for _, required := range []string{"draft create", "secret set", "run", "notifications accept"} {
		if !seen[required] {
			t.Errorf("the eval table has no task for %q", required)
		}
	}
}

// TestHelpTopicsAreCurrent keeps `help workflow` from drifting back to the retired spellings, and
// keeps the three capabilities an Agent is most likely to miss named there (A5 第一处).
func TestHelpTopicsAreCurrent(t *testing.T) {
	for _, topic := range commandHelp.Topics() {
		for _, word := range retiredWords {
			if strings.Contains(topic.Body, word) {
				t.Errorf("help %s still teaches %q", topic.Name, word)
			}
		}
	}
	topic, ok := commandHelp.Topic("workflow")
	if !ok {
		t.Fatal("the workflow topic is missing")
	}
	for _, needle := range []string{"draft create", "secret set", "notifications", "run"} {
		if !strings.Contains(topic.Body, needle) {
			t.Errorf("help workflow does not mention %q", needle)
		}
	}
}

// TestHelpTopicsUseDeclaredCommands runs the command tree over help workflow/auth/env. Those pages
// name commands without the `skmr` prefix, so the Guide parser never sees them; a typo here reaches
// an Agent on its first command. Punctuation is split out so a command can start after `：` or sit
// inside parentheses.
func TestHelpTopicsUseDeclaredCommands(t *testing.T) {
	table := helpCommandTable()
	split := strings.NewReplacer(
		"：", " ： ", "→", " → ", "（", " （ ", "）", " ） ",
		"，", " ， ", "；", " ； ", "。", " 。 ",
	)
	for _, topic := range commandHelp.Topics() {
		for _, raw := range strings.Split(topic.Body, "\n") {
			tokens := strings.Fields(split.Replace(raw))
			for i := 0; i < len(tokens); i++ {
				head := trimToken(tokens[i])
				if head == "" {
					continue
				}
				if _, ok := table[head]; !ok {
					continue
				}
				line := make([]string, 0, len(tokens)-i)
				line = append(line, head)
				for _, token := range tokens[i+1:] {
					line = append(line, trimToken(token))
				}
				command, rest, ok := longestCommand(table, line)
				if !ok || command == "" {
					continue
				}
				info := table[command]
				if info.spec.PureGroup && len(rest) > 0 {
					next := rest[0]
					if isLiteral(next) && !helpContains(info.spec.Children, next) {
						t.Errorf("help %s names %q, which is not a child of %q", topic.Name, command+" "+next, command)
					}
				}
				for _, bad := range undeclaredFlags(command, rest, info) {
					t.Errorf("help %s: %s does not declare %s", topic.Name, command, bad)
				}
				i += len(strings.Fields(command)) - 1
			}
		}
	}
}

// TestWorkflowTopicNamesTheMainPath pins the phrases the one-screen journey is made of. The
// command-tree check above cannot see a misspelled top-level name (`runn`) because it starts from
// known heads; requiring each phrase catches a rename on either side.
func TestWorkflowTopicNamesTheMainPath(t *testing.T) {
	topic, ok := commandHelp.Topic("workflow")
	if !ok {
		t.Fatal("the workflow topic is missing")
	}
	for _, phrase := range []string{
		"skills search", "skill invoke", "skill ls", "run <地址>",
		"skill create", "draft create", "skill read", "skill write / edit",
		"version diff", "skill check", "version open", "skill submit",
		"skills pending", "skill share", "notifications list",
		"notifications accept / decline", "notifications approve / deny",
		"skill request", "secret set",
	} {
		if !strings.Contains(topic.Body, phrase) {
			t.Errorf("help workflow no longer names %q", phrase)
		}
	}
}

// TestUnauthenticatedFailureHasOneSentence is the declaration half of A5 第五处: every page that
// can report a credential failure names the same reason and the same next step.
func TestUnauthenticatedFailureHasOneSentence(t *testing.T) {
	for _, spec := range commandHelp.Specs() {
		for _, failure := range spec.Failures {
			switch {
			case failure.Code == "unauthenticated":
				if failure.Summary != help.UnauthenticatedSummary {
					t.Errorf("%s: unauthenticated summary %q, want %q", spec.Path, failure.Summary, help.UnauthenticatedSummary)
				}
				if failure.Next != help.UnauthenticatedNext {
					t.Errorf("%s: unauthenticated next %q, want %q", spec.Path, failure.Next, help.UnauthenticatedNext)
				}
			case strings.HasPrefix(failure.Code, "token_"):
				if failure.Next != help.UnauthenticatedNext {
					t.Errorf("%s: %s next %q, want %q", spec.Path, failure.Code, failure.Next, help.UnauthenticatedNext)
				}
			}
		}
	}
}

// promptNamesCommand reports whether the prompt names the expected path as whole ASCII words:
// "runtime" does not count as "run", while Chinese prose touching the command still does.
func promptNamesCommand(prompt, command string) bool {
	for from := 0; from+len(command) <= len(prompt); {
		at := strings.Index(prompt[from:], command)
		if at < 0 {
			return false
		}
		at += from
		end := at + len(command)
		beforeOK := at == 0 || !isWordByte(prompt[at-1])
		afterOK := end == len(prompt) || !isWordByte(prompt[end])
		if beforeOK && afterOK {
			return true
		}
		from = at + 1
	}
	return false
}

// TestHelpBudgets keeps the entry cost from creeping back. The numbers are the PRD's A9.
func TestHelpBudgets(t *testing.T) {
	top := help.RenderTop(commandHelp)
	if len(top) > 2048 {
		t.Errorf("top-level help is %d bytes, over 2048", len(top))
	}
	for _, spec := range commandHelp.Specs() {
		if spec.Path == "" {
			continue
		}
		if text := help.Render(commandHelp, spec.Path); len(text) > 2048 {
			t.Errorf("%s help is %d bytes, over 2048", spec.Path, len(text))
		}
	}
	topic, ok := commandHelp.Topic("workflow")
	if !ok {
		t.Fatal("the workflow topic is missing")
	}
	if len(topic.Body) > 1024 {
		t.Errorf("help workflow is %d bytes, over 1024", len(topic.Body))
	}
	root := helpRepoRoot(t)
	index, err := os.ReadFile(filepath.Join(root, "skills", "skillmaster-guide", "SKILL.md"))
	if err != nil {
		t.Fatalf("read the Guide index: %v", err)
	}
	indexRunes := utf8.RuneCountInString(string(index))
	if indexRunes > 3000 {
		t.Errorf("the Guide index is %d characters, over 3000", indexRunes)
	}
	if lines := len(strings.Split(strings.TrimSuffix(string(index), "\n"), "\n")); lines > 150 {
		t.Errorf("the Guide index is %d lines, over 150", lines)
	}
	entry := utf8.RuneCountInString(top) + utf8.RuneCountInString(topic.Body) + indexRunes
	if entry > 5000 {
		t.Errorf("the entry cost is %d characters, over 5000", entry)
	}
}

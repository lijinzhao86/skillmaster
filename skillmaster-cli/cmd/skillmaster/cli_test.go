package main

import (
	"net/http"
	"strings"
	"testing"
	"unicode/utf8"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// The contract is a process contract, so most of it is asserted the way a caller meets it: through
// `run`, with the real streams and the real status (test-plan T06–T18, T20). The unit tests over
// `internal/help` prove the rendering; these prove what the binary does with it.

func commandAddr(args []string) string {
	return "skmr " + strings.Join(args, " ") + " --help"
}

// pathOf is the command path an argv names: the longest declared prefix.
func pathOf(args []string) []string {
	for length := len(args); length > 0; length-- {
		if _, ok := help.Lookup(commandHelp, strings.Join(args[:length], " ")); ok {
			return args[:length]
		}
	}
	return args[:1]
}

func tail(s string) string {
	lines := strings.Split(strings.TrimSpace(s), "\n")
	return lines[len(lines)-1]
}

// T06: every node with children lists exactly those children, from the word `子命令：` to the next
// blank line, and says where its own parent lives.
func TestGroupHelpListsItsChildren(t *testing.T) {
	for _, path := range []string{"auth", "skills", "skill", "version", "skill tag", "skill category", "skills my-tags"} {
		spec, ok := help.Lookup(commandHelp, path)
		if !ok {
			t.Fatalf("%q has no declaration", path)
		}
		words := strings.Split(path, " ")
		for _, spelling := range []string{"help", "-h", "--help"} {
			args := append(append([]string{}, words...), spelling)
			stdout, stderr, status := runCaptured(t, args...)
			if status != 0 {
				t.Errorf("%v: status %d, stderr %q", args, status, stderr)
				continue
			}
			listed := blockChildren(stdout)
			if got, want := sorted(listed), sorted(spec.Children); !equal(got, want) {
				t.Errorf("%v lists %v, want %v:\n%s", args, got, want, stdout)
				continue
			}
			for _, child := range listed {
				childSpec, _ := help.Lookup(commandHelp, path+" "+child)
				if childSpec == nil {
					continue
				}
				if n := utf8.RuneCountInString(childSpec.Summary); n == 0 || n > 12 {
					t.Errorf("%v: the line for %q has a %d-rune summary", args, child, n)
				}
			}
			// `skills my-tags delete` is a child that exists only to say where the action lives; the
			// sentence is the whole reason the line is there (ADR 0079).
			if path == "skills my-tags" && !strings.Contains(stdout, "只在控制台") {
				t.Errorf("%v: the delete line does not say where deleting happens:\n%s", args, stdout)
			}
			if !strings.Contains(stdout, parentLineFor(path)) {
				t.Errorf("%v does not name its parent as %q:\n%s", args, parentLineFor(path), stdout)
			}
		}
	}
}

// parentLineFor is where a node's parent lives: its own group's help, or the top level for a group
// that sits directly under the root.
func parentLineFor(path string) string {
	words := strings.Split(path, " ")
	if len(words) == 1 {
		return "上级：skmr help"
	}
	return "上级：skmr " + strings.Join(words[:len(words)-1], " ") + " --help"
}

// blockChildren parses the 子命令 block: from its heading to the first empty line.
func blockChildren(out string) []string {
	_, after, found := strings.Cut(out, "子命令：\n")
	if !found {
		return nil
	}
	var children []string
	for _, line := range strings.Split(after, "\n") {
		if strings.TrimSpace(line) == "" {
			break
		}
		fields := strings.Fields(line)
		if len(fields) > 0 {
			children = append(children, fields[0])
		}
	}
	return children
}

// T07 / T16: the three spellings are the same page, it fits the budget, every top-level name is
// found with its own address, the four exit classes are there, and the last line is the documents.
func TestTopLevelHelpShape(t *testing.T) {
	var pages []string
	for _, args := range [][]string{{"help"}, {"--help"}, {"-h"}} {
		stdout, stderr, status := runCaptured(t, args...)
		if status != 0 || stderr != "" {
			t.Fatalf("%v: status %d, stderr %q", args, status, stderr)
		}
		pages = append(pages, stdout)
	}
	for i := 1; i < len(pages); i++ {
		if pages[i] != pages[0] {
			t.Errorf("%v differs from `skmr help`", [][]string{{"help"}, {"--help"}, {"-h"}}[i])
		}
	}
	page := pages[0]
	if len(page) > 2048 {
		t.Errorf("top-level help is %d bytes, over 2048", len(page))
	}
	root, _ := help.Lookup(commandHelp, "")
	for _, child := range root.Children {
		if strings.Count(page, child) == 0 || !strings.Contains(page, child+" --help") {
			t.Errorf("top-level help does not give %q its own address:\n%s", child, page)
		}
	}
	for _, class := range help.ExitClasses() {
		if !strings.Contains(page, "\n  "+itoa(class.Code)+" "+class.Text) {
			t.Errorf("top-level help is missing the exit class %d:\n%s", class.Code, page)
		}
	}
	lines := strings.Split(strings.TrimRight(page, "\n"), "\n")
	// **No documents line any more.** It used to end with an unfilled 「文档：（待定）」 placeholder;
	// v17 gave the rules a home of their own and the placeholder was removed with it (ADR 0089),
	// so the exit classes are the last thing on the page.
	if strings.HasPrefix(lines[len(lines)-1], "文档：") {
		t.Errorf("the placeholder documents line is back: %q", lines[len(lines)-1])
	}
	// **The rules are not a help topic any more** (ADR 0096): they live in the platform's
	// `skillmaster/startup` skill, and help stays a command reference. A topic line here would be
	// the second home that ADR exists to prevent.
	if strings.Contains(page, "help usage") {
		t.Errorf("top-level help still points at `help usage`:\n%s", page)
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var digits []byte
	for n > 0 {
		digits = append([]byte{byte('0' + n%10)}, digits...)
		n /= 10
	}
	return string(digits)
}

// T08 / T09: a name that was not understood gets one line — what was not understood, the closest
// name when exactly one is close, and where the list is. Never the whole help.
func TestUnknownNamesAreOneLine(t *testing.T) {
	cases := []struct {
		args     []string
		want     string
		wantHint bool
	}{
		{[]string{"skill", "invok"}, "skmr skill --help", true},
		{[]string{"foo"}, "skmr help", false},
		{[]string{"skil"}, "skmr help", true},
	}
	for _, c := range cases {
		stdout, stderr, status := runCaptured(t, c.args...)
		if status != 2 {
			t.Errorf("%v: status %d, want 2", c.args, status)
		}
		if stdout != "" {
			t.Errorf("%v wrote to stdout: %q", c.args, stdout)
		}
		if strings.Count(strings.TrimRight(stderr, "\n"), "\n") != 0 {
			t.Errorf("%v printed more than one line:\n%s", c.args, stderr)
		}
		if len(stderr) > 200 {
			t.Errorf("%v printed %d bytes, over 200", c.args, len(stderr))
		}
		if !strings.Contains(stderr, c.want) {
			t.Errorf("%v does not point at %q:\n%s", c.args, c.want, stderr)
		}
		if gotHint := strings.Contains(stderr, "你是不是想用"); gotHint != c.wantHint {
			t.Errorf("%v suggestion = %v, want %v:\n%s", c.args, gotHint, c.wantHint, stderr)
		}
	}
}

// T10: every usage error ends at the command's own help, on one line, within the budget.
func TestUsageErrorsEndAtTheHelpAddress(t *testing.T) {
	cases := [][]string{
		{"version", "list", "--jsno"},
		{"skill", "invoke", "bad"},
		{"skill", "edit", "a/b"},
		{"skill", "grep", "a/b", "pat", "--path"},
		{"skill", "cat", "a/b@", "x"},
		{"skill", "files", "a/b@"},
		{"skill", "wc", "a/b@"},
		{"skill", "grep", "a/b@", "p"},
		// The ones that used to print a usage paragraph and exit 1.
		{"skill", "share"},
		{"skills", "search"},
		{"skills", "list", "--show", "nope"},
		{"skill", "write", "a/b"},
		{"version", "rename", "a/b"},
		{"skill", "download", "a/b@"},
		{"skill", "share", "a/b@", "--to", "alice"},
		{"skill", "submit", "--to", "a/b@", "."},
		{"version", "note", "a/b"},
		{"version", "list", "a/b@"},
		// **The classes the first list missed** (audit round 3): a flag whose value is missing or
		// out of range, two flags that cannot be given together, an address that does not parse, and
		// a version pin on a command that acts on the whole skill. Each used to be a plain failure
		// (exit 1) with no address — indistinguishable from a network error to a caller reading the
		// status, and a dead end for a person reading the sentence.
		{"skill", "read", "a/b", "c", "--offset"},
		{"skill", "read", "a/b", "--offset", "0"},
		{"skill", "invoke", "a/b", "--offset", "x"},
		{"skill", "write", "@new", "p", "--name"},
		{"skill", "share", "a/b", "--to", "alice", "--role", "nope"},
		{"skill", "request", "a/b", "--status", "--cancel"},
		{"skill", "share", "bad", "--to", "alice"},
		{"skill", "submit", "--to", "bad", "."},
		{"skill", "tag", "add", "a/b@", "x"},
		{"skill", "join", "a/b@"},
		{"skill", "restore", "a/b@"},
		{"skill", "category", "set", "a/b@", "x"},
		// **The path advice is a usage error like any other**, and it earns its 200 bytes by name
		// (v1.3.1's audit: the first draft of the directory sentence was 232).
		{"skill", "invoke", "a/b", "code-audit"},
		{"skill", "invoke", "a/b/code-audit"},
		{"skill", "invoke", "a/b", "--json"},
		{"skill", "show", "bad"},
		{"skill", "unshare", "bad", "--user", "alice"},
		{"skill", "request", "bad", "--status"},
	}
	t.Setenv(config.ServerEnv, "http://127.0.0.1:1")
	t.Setenv("HOME", t.TempDir())
	t.Setenv("SKILLMASTER_API_KEY", "")
	for _, args := range cases {
		stdout, stderr, status := runCaptured(t, args...)
		if status != 2 {
			t.Errorf("%v: status %d, want 2 (stderr %q)", args, status, stderr)
		}
		if stdout != "" {
			t.Errorf("%v wrote to stdout: %q", args, stdout)
		}
		if len(stderr) > 200 {
			t.Errorf("%v printed %d bytes, over 200", args, len(stderr))
		}
		// **The address has to be this command's**, not the group's: a typo in `version note` that
		// sends the reader to a page listing fourteen verbs is one more step, not an answer.
		want := "用法见：skmr " + strings.Join(pathOf(args), " ") + " --help"
		if !strings.HasSuffix(strings.TrimSpace(stderr), want) {
			t.Errorf("%v ends with %q, want %q", args, tail(stderr), want)
		}
	}
}

// **The invariant behind the table above: every command, not a chosen sample.** Each executable
// command is handed one flag it cannot know; the answer has to be a usage error (2) that ends at
// *that* command's help. The table covers the classes by hand — a value that is missing, a value
// out of range, two flags that conflict, an address that does not parse, a pin where none belongs —
// and this covers the commands, so neither list can fall behind the other.
//
// The page-only verbs are the one documented exception: their whole answer is the sentence saying
// where the action lives, they parse nothing a flag could belong to, and the declaration marks them
// with a `refused` failure. They are asserted to give that answer, not skipped.
func TestEveryCommandRefusesAnUnknownFlagAtItsOwnHelp(t *testing.T) {
	t.Setenv(config.ServerEnv, "http://127.0.0.1:1")
	t.Setenv("HOME", t.TempDir())
	t.Setenv("SKILLMASTER_API_KEY", "")

	for _, spec := range commandHelp.Specs() {
		var words []string
		if spec.Path != "" {
			words = strings.Split(spec.Path, " ")
		}
		args := append(append([]string{}, words...), "--not-a-flag")

		pageOnly := false
		for _, failure := range spec.Failures {
			if failure.Code == "refused" {
				pageOnly = true
			}
		}

		stdout, stderr, status := runCaptured(t, args...)
		if stdout != "" {
			t.Errorf("%v wrote to stdout: %q", args, stdout)
		}
		if pageOnly {
			if status != 1 || strings.TrimSpace(stderr) == "" {
				t.Errorf("%v: status %d, stderr %q — a page-only verb answers with its sentence", args, status, stderr)
			}
			// The sentence has to say **where** the action lives, or it is only a refusal.
			if !strings.Contains(stderr, "控制台") && !strings.Contains(stderr, "网页") {
				t.Errorf("%v does not say where the action lives: %q", args, stderr)
			}
			continue
		}
		if status != 2 {
			t.Errorf("%v: status %d, want 2 (stderr %q)", args, status, stderr)
		}
		want := "用法见：" + helpAddressFor(spec.Path)
		if !strings.HasSuffix(strings.TrimSpace(stderr), want) {
			t.Errorf("%v ends with %q, want %q", args, tail(stderr), want)
		}
	}
}

// helpAddressFor is the root's own address (`skmr help`) or the command's `--help`.
func helpAddressFor(path string) string {
	if path == "" {
		return "skmr help"
	}
	return "skmr " + path + " --help"
}

// T14's other half: `help` in a value position is an argument, not a request. The resolution is
// checked directly rather than by running the command — three of these would read a directory or
// change state before any help question could be answered, and the question here is only "does the
// word get read as help".
func TestABareHelpWordInAValuePositionIsData(t *testing.T) {
	cases := [][]string{
		{"skill", "grep", "a/b", "help"},
		{"skill", "read", "a/b", "help"},
		{"skill", "download", "a/b", "--into", "help"},
		{"skills", "search", "help"},
		{"skills", "my-tags", "add", "help"},
		{"skill", "show", "a/b", "help"},
	}
	for _, args := range cases {
		if got := help.Requested(commandHelp, args); got.Kind != help.KindNone {
			t.Errorf("%v resolved to %+v; a value-position `help` is data", args, got)
		}
	}

	// **Then the same five, executed.** The resolution says the word is not a help request; this
	// says what happens instead: the command goes on to its own work, so nothing is printed to
	// stdout and the answer is about this machine's credential, not about help. `skill show` is the
	// one that refuses the extra word — it takes a single address — and that is a usage error.
	//
	// `skill download a/b --into help` is left at the resolution level: running it would create a
	// directory named `help` in whatever directory the test happens to be in.
	t.Setenv(config.ServerEnv, "http://127.0.0.1:1")
	t.Setenv("HOME", t.TempDir())
	t.Setenv(config.ApiKeyEnv, "")
	original := browserAvailable
	browserAvailable = func() bool { return false }
	t.Cleanup(func() { browserAvailable = original })

	executed := map[string]int{
		"skill grep a/b help":     1,
		"skill read a/b help":     1,
		"skills search help":      1,
		"skills my-tags add help": 1,
		"skill show a/b help":     2,
	}
	for line, want := range executed {
		stdout, stderr, status := runCaptured(t, strings.Fields(line)...)
		if status != want {
			t.Errorf("%q: status %d, want %d (stderr %q)", line, status, want, stderr)
		}
		if stdout != "" {
			t.Errorf("%q wrote to stdout: %q", line, stdout)
		}
		// Not a help page: no parent line, and no usage paragraph except in the one refusal that is
		// itself a usage error.
		if strings.Contains(stderr, "上级：") || (want != 2 && strings.Contains(stderr, "用法：")) {
			t.Errorf("%q printed help instead of doing its work: %q", line, stderr)
		}
	}
}

// **A usage error has to say what was wrong, not only where the answer is.** For the commands that
// refuse a wrong number of arguments the sentence used to be nothing but the address — the reader
// learned that something was wrong with the line they typed and had to open a page to find out what.
// The command's own usage line fills that half now; the tail still ends the sentence.
func TestAUsageErrorSaysWhatWasWrong(t *testing.T) {
	cases := [][]string{
		{"skills", "my-tags", "add"},
		{"skill", "tag", "add", "a/b"},
		{"skill", "category", "set", "a/b"},
		{"skill", "versions"},
		{"skill", "show", "a/b", "c"},
		{"version", "note", "a/b", "--bogus"},
	}
	t.Setenv(config.ServerEnv, "http://127.0.0.1:1")
	t.Setenv("HOME", t.TempDir())
	t.Setenv("SKILLMASTER_API_KEY", "")
	for _, args := range cases {
		_, stderr, status := runCaptured(t, args...)
		if status != 2 {
			t.Errorf("%v: status %d, want 2", args, status)
		}
		said := strings.TrimPrefix(strings.TrimSpace(stderr), "错误：")
		if strings.HasPrefix(said, "用法见：") {
			t.Errorf("%v says only where the help is, not what was wrong: %q", args, stderr)
		}
		// The two shapes that say something: a sentence, or the command's own usage line (which is
		// what a tail-only refusal is replaced with).
		if !strings.Contains(said, "用法：") && !strings.Contains(said, "不认识的参数") {
			t.Errorf("%v does not say what was wrong: %q", args, stderr)
		}
		if strings.Contains(stderr, "。。") {
			t.Errorf("%v has a doubled full stop: %q", args, stderr)
		}
	}
}

// T11: each exit class has a command that really produces it. The 0 and the 1 that need a server use
// the fake; the rest need nothing.
func TestExitCodeScenarios(t *testing.T) {
	t.Setenv(config.ServerEnv, "http://127.0.0.1:1")
	t.Setenv("HOME", t.TempDir())
	t.Setenv("SKILLMASTER_API_KEY", "")

	if _, _, status := runCaptured(t, "skills", "list", "--no-login"); status != 4 {
		t.Errorf("skills list --no-login without a credential: status %d, want 4", status)
	}
	if _, _, status := runCaptured(t, "version", "publish", "dev/pdf-tools@1"); status != 1 {
		t.Errorf("a page-only verb: status %d, want 1", status)
	}
	if _, _, status := runCaptured(t, "skills", "my-tags", "delete", "x"); status != 1 {
		t.Errorf("skills my-tags delete: status %d, want 1", status)
	}
	if _, _, status := runCaptured(t, "skill", "invok"); status != 2 {
		t.Errorf("an unknown verb: status %d, want 2", status)
	}
	if _, _, status := runCaptured(t, "skills", "list"); status != 1 {
		t.Errorf("a command that starts a login that fails: status %d, want 1", status)
	}

	// And 0: the library answers with nothing.
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[],"next_cursor":null}`
	})
	if _, stderr, status := runCaptured(t, "skills", "list"); status != 0 {
		t.Errorf("an empty library: status %d, want 0 (stderr %q)", status, stderr)
	}
}

// T14: `--help` and `-h` mean the same thing wherever they sit after the command words — including
// where a value is expected, which is the price 做 #1 accepts.
func TestHelpIsRecognisedAtEveryPosition(t *testing.T) {
	t.Setenv(config.ServerEnv, "http://127.0.0.1:1")
	t.Setenv("HOME", t.TempDir())
	t.Setenv("SKILLMASTER_API_KEY", "")

	// Every example the help offers: the arguments a person would actually paste.
	var cases []struct {
		words []string
		args  []string
	}
	for _, spec := range commandHelp.Specs() {
		for _, example := range spec.Examples {
			path, args, ok := splitExample(example)
			if !ok {
				continue
			}
			cases = append(cases, struct {
				words []string
				args  []string
			}{strings.Split(path, " "), args})
		}
	}
	if len(cases) < 40 {
		t.Fatalf("only %d examples to walk; the help should carry far more", len(cases))
	}
	for _, c := range cases {
		want, _, _ := runCaptured(t, append(append(append([]string{}, c.words...), c.args...), "--help")...)
		if strings.TrimSpace(want) == "" {
			t.Fatalf("%v --help printed nothing", c.words)
		}
		for _, flag := range []string{"--help", "-h"} {
			for at := 0; at <= len(c.args); at++ {
				args := append([]string{}, c.words...)
				args = append(args, c.args[:at]...)
				args = append(args, flag)
				args = append(args, c.args[at:]...)
				stdout, stderr, status := runCaptured(t, args...)
				if status != 0 {
					t.Errorf("%v: status %d (stderr %q)", args, status, stderr)
					continue
				}
				if stdout != want {
					t.Errorf("%v printed something else than the command's help", args)
				}
			}
		}
	}
}

// T15: a bare group does nothing, so it is a usage error with the table on stderr.
func TestBareGroupsPrintTheTableToStderr(t *testing.T) {
	t.Setenv(config.ServerEnv, "http://127.0.0.1:1")
	t.Setenv("HOME", t.TempDir())
	t.Setenv("SKILLMASTER_API_KEY", "")

	for _, words := range [][]string{{}, {"skill"}, {"skills"}, {"version"}, {"auth"}, {"skill", "tag"}, {"skill", "category"}} {
		stdout, stderr, status := runCaptured(t, words...)
		if status != 2 {
			t.Errorf("%v: status %d, want 2", words, status)
		}
		if stdout != "" {
			t.Errorf("%v wrote to stdout: %q", words, stdout)
		}
		// The root's list is headed 命令：; every group's is 子命令：. Both are the table — what the
		// test refuses is a bare word answered with nothing or with a page of prose.
		heading := "子命令："
		if len(words) == 0 {
			heading = "命令："
		}
		if !strings.Contains(stderr, heading) {
			t.Errorf("%v printed no command table:\n%s", words, stderr)
		}
	}
}

// T18: the environment page is one page, small, and the top level points at it instead of listing
// the variables.
func TestHelpEnvPage(t *testing.T) {
	stdout, stderr, status := runCaptured(t, "help", "env")
	if status != 0 || stderr != "" {
		t.Fatalf("help env: status %d, stderr %q", status, stderr)
	}
	if len(stdout) > 512 {
		t.Errorf("help env is %d bytes, over 512", len(stdout))
	}
	for _, name := range []string{"SKILLMASTER_SERVER", "SKILLMASTER_API_KEY", "SKILLMASTER_SKILLS_DIR", "SKILLMASTER_WEB_URL", "SKILLMASTER_AUTO_UPDATE"} {
		if !strings.Contains(stdout, name) {
			t.Errorf("help env does not name %s", name)
		}
	}

	top, _, _ := runCaptured(t, "help")
	if !strings.Contains(top, "help env") {
		t.Errorf("top-level help does not point at the topic:\n%s", top)
	}
	for _, name := range []string{"SKILLMASTER_SERVER", "SKILLMASTER_API_KEY", "SKILLMASTER_SKILLS_DIR", "SKILLMASTER_WEB_URL"} {
		if strings.Contains(top, name) {
			t.Errorf("top-level help still lists the variable %s", name)
		}
	}
}

// T20: no help text describes the product's state at the moment it was written.
func TestNoRelativeTimeWordsAnywhere(t *testing.T) {
	pages := []string{}
	if top, _, status := runCaptured(t, "help"); status == 0 {
		pages = append(pages, top)
	}
	if topic, _, status := runCaptured(t, "help", "env"); status == 0 {
		pages = append(pages, topic)
	}
	for _, spec := range commandHelp.Specs() {
		pages = append(pages, help.Render(commandHelp, spec.Path))
	}
	for _, page := range pages {
		for _, word := range relativeTimeWords {
			if strings.Contains(page, word) {
				t.Errorf("a help page still says %q", word)
			}
		}
	}
}

// T24: the ratio the plan asks the command-tree test to print.
func TestHelpCoverage(t *testing.T) {
	declared := 0
	withHelp := 0
	for _, spec := range commandPaths(commandHelp) {
		declared++
		if strings.TrimSpace(help.Render(commandHelp, spec.Path)) != "" {
			withHelp++
		}
	}
	t.Logf("coverage: %d/%d command paths have a declaration and a help page", withHelp, declared)
	if withHelp != declared {
		t.Errorf("%d of %d declared command paths render no help", declared-withHelp, declared)
	}
}

// T04: the limits on what a sentence may say, counted in runes.
func TestHelpSentencesFitTheirLimits(t *testing.T) {
	for _, spec := range commandHelp.Specs() {
		if spec.PureGroup || spec.Path == "" {
			continue
		}
		if n := utf8.RuneCountInString(spec.Summary); n > 12 {
			t.Errorf("%q summary is %d runes, over 12", spec.Path, n)
		}
		for _, flag := range spec.Flags {
			if n := utf8.RuneCountInString(flag.Summary); n > 25 {
				t.Errorf("%q: the flag %s has a %d-rune summary, over 25", spec.Path, flag.Name, n)
			}
		}
		for _, failure := range append(append([]help.Failure{}, spec.Failures...), help.UniversalFailures(commandHelp, spec.Path)...) {
			if n := utf8.RuneCountInString(failure.Summary); n == 0 || n > 35 {
				t.Errorf("%q: failure %q has a %d-rune summary", spec.Path, failure.Code, n)
			}
			if n := utf8.RuneCountInString(failure.Next); n == 0 || n > 35 {
				t.Errorf("%q: failure %q has a %d-rune next step", spec.Path, failure.Code, n)
			}
		}
	}
}

// T12 / T13: the credential classes, driven end to end through the fake server rather than only
// through `reportError`. Each row is a different way to arrive at the same question — is this
// credential usable? — and the answer decides 4 or 1.
func TestCredentialFailuresByScenario(t *testing.T) {
	cases := []struct {
		name   string
		status int
		body   string
		args   []string
		want   int
		hints  []string
	}{
		{"unauthenticated", 401, `{"error":{"code":"unauthenticated","message":"who"}}`,
			[]string{"skills", "list"}, 4, []string{"控制台"}},
		{"token_idle", 401, `{"error":{"code":"token_idle","message":"idle"}}`,
			[]string{"skills", "list"}, 4, []string{"控制台"}},
		{"token_expired", 401, `{"error":{"code":"token_expired","message":"expired"}}`,
			[]string{"skills", "list"}, 4, []string{"续期"}},
		{"token_revoked", 401, `{"error":{"code":"token_revoked","message":"revoked"}}`,
			[]string{"skills", "list"}, 4, []string{"新签"}},
		{"expired through the JSON path", 401, `{"error":{"code":"token_expired","message":"expired"}}`,
			[]string{"version", "list", "dev/x", "--json"}, 4, []string{"token_expired"}},
		{"suspended", 401, `{"error":{"code":"account_suspended","message":"suspended"}}`,
			[]string{"skills", "list"}, 1, []string{"账号已被停用"}},
		{"401 without a code", 401, `{"error":{"message":"no"}}`,
			[]string{"skills", "list"}, 1, nil},
		{"401 with an unknown code", 401, `{"error":{"code":"something_new","message":"?"}}`,
			[]string{"skills", "list"}, 1, nil},
		{"skill grep against an expired credential", 401, `{"error":{"code":"token_expired","message":"expired"}}`,
			[]string{"skill", "grep", "dev/x@1.2.3", "TODO", "--path", "src"}, 4, []string{"续期"}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			status, body := c.status, c.body
			pointAtServer(t, func(w http.ResponseWriter, _ *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(status)
				_, _ = w.Write([]byte(body))
			})
			stdout, stderr, got := runCaptured(t, c.args...)
			if got != c.want {
				t.Fatalf("%v: status %d, want %d (stderr %q)", c.args, got, c.want, stderr)
			}
			if stdout != "" {
				t.Errorf("%v wrote to stdout: %q", c.args, stdout)
			}
			for _, hint := range c.hints {
				if !strings.Contains(stderr, hint) {
					t.Errorf("%v: %q does not mention %q", c.args, stderr, hint)
				}
			}
		})
	}

	// A command with no credential and no browser is an ordinary failure: it can be retried as it is.
	t.Setenv(config.ServerEnv, "http://127.0.0.1:1")
	t.Setenv("HOME", t.TempDir())
	t.Setenv("SKILLMASTER_API_KEY", "")
	original := browserAvailable
	browserAvailable = func() bool { return false }
	t.Cleanup(func() { browserAvailable = original })
	if _, _, status := runCaptured(t, "skills", "list"); status != 1 {
		t.Errorf("no credential, no browser: status %d, want 1", status)
	}
	if _, _, status := runCaptured(t, "skills", "list", "--no-login"); status != 4 {
		t.Errorf("--no-login with no credential: status %d, want 4", status)
	}
	// **`skill grep` has no `--no-login`**: with nothing stored it tries to sign in, and a sign-in
	// that cannot start is an ordinary failure (1), not "replace your credential" (验收 #10).
	if _, _, status := runCaptured(t, "skill", "grep", "dev/x", "TODO"); status != 1 {
		t.Errorf("skill grep with no credential and no browser: status %d, want 1", status)
	}

	// **A variable that is not a key.** The answer is about the variable rather than about the
	// credential it is not, and it is the same for a command that would have asked the server
	// (`验收 #10`).
	t.Setenv(config.ApiKeyEnv, "not-a-key")
	for _, args := range [][]string{{"skills", "list"}, {"skill", "grep", "dev/x", "TODO"}, {"auth", "status"}} {
		_, stderr, status := runCaptured(t, args...)
		if status != 4 {
			t.Errorf("%v with a variable that is not a key: status %d, want 4 (stderr %q)", args, status, stderr)
		}
		if !strings.Contains(stderr, config.ApiKeyEnv) {
			t.Errorf("%v does not name the variable to fix: %q", args, stderr)
		}
	}
	t.Setenv(config.ApiKeyEnv, "skm_livekey123456")

	// **`auth status` against a suspended account**: the server's own sentence, exit 1, and nothing
	// about signing in — a new credential would not help (`验收 #11`).
	pointAtServer(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":{"code":"account_suspended","message":"This account is suspended."}}`))
	})
	_, stderr, status := runCaptured(t, "auth", "status")
	if status != 1 {
		t.Errorf("auth status on a suspended account: status %d, want 1 (stderr %q)", status, stderr)
	}
	if !strings.Contains(stderr, "账号已被停用") {
		t.Errorf("auth status does not say the account is suspended: %q", stderr)
	}
	if strings.Contains(stderr, "登录") {
		t.Errorf("auth status suggests signing in again, which cannot help: %q", stderr)
	}
}

// T11's `skill grep` rows: a 403 is an ordinary failure (1), and no match is 1 with a sentence. The
// old implementation reported every grep failure as 2, which made "you cannot do that" look like
// "you typed it wrong".
func TestGrepFailureClasses(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusForbidden, `{"error":{"code":"insufficient_scope","message":"no"}}`
	})
	if _, stderr, status := runCaptured(t, "skill", "grep", "dev/x@1.2.3", "TODO", "--path", "src"); status != 1 {
		t.Errorf("a 403: status %d, want 1 (stderr %q)", status, stderr)
	}

	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"matches":[],"truncated":false}`
	})
	_, stderr, status := runCaptured(t, "skill", "grep", "dev/x@1.2.3", "TODO", "--path", "src")
	if status != 1 {
		t.Errorf("no match: status %d, want 1", status)
	}
	if !strings.Contains(stderr, "没有命中") {
		t.Errorf("no match said %q, want the sentence about no hits", stderr)
	}
}

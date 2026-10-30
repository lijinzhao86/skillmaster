package main

import (
	"regexp"
	"strings"
	"testing"
	"unicode/utf8"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// The command set these tests measure against is the PRD's count of executable paths, which already
// includes the bare `skills my-tags`; the pure groups and the top level are nodes, not paths.
// v25 added `update` and the `prompt` group's four verbs, v26 added `prompt uninstall`, v26's 0005
// retired all four of that group except `print` (ADR 0110), and v34 retired `files` while adding
// `ls` / `find` / `stat` / `head` / `tail` — 65 − 4 + 4 = 65.
const wantCommandPaths = 65

// relativeTimeWords are the phrases a help text must not use: they describe the product's state at
// the moment somebody wrote it, and they go stale without anyone editing the text.
var relativeTimeWords = []string{"目前", "即将", "暂时", "以后", "未来"}

func commandPaths(cat help.Catalog) []*help.Spec {
	paths := make([]*help.Spec, 0, len(cat.Specs()))
	for _, spec := range cat.Specs() {
		if spec.Path != "" && !spec.PureGroup {
			paths = append(paths, spec)
		}
	}
	return paths
}

func TestDeclarationsMatchTheCommandTree(t *testing.T) {
	if err := help.Validate(commandHelp); err != nil {
		t.Fatalf("declarations do not validate: %v", err)
	}
	paths := commandPaths(commandHelp)
	if len(paths) != wantCommandPaths {
		t.Fatalf("declared %d command paths, want %d", len(paths), wantCommandPaths)
	}
	for _, spec := range paths {
		if spec.Summary == "" {
			t.Errorf("%q has no summary", spec.Path)
		}
		if n := utf8.RuneCountInString(spec.Summary); n > 12 {
			t.Errorf("%q summary is %d runes, want at most 12: %q", spec.Path, n, spec.Summary)
		}
		if spec.Usage == "" {
			t.Errorf("%q has no usage line", spec.Path)
		}
		if len(spec.Exits) == 0 {
			t.Errorf("%q declares no exit codes", spec.Path)
		}
		if len(spec.Failures) == 0 && len(spec.Children) == 0 {
			t.Errorf("%q declares no failures", spec.Path)
		}
	}
}

func TestEveryHelpFitsTheBudget(t *testing.T) {
	ansi := regexp.MustCompile("\x1b\\[[0-9;]*m")
	for _, spec := range commandHelp.Specs() {
		text := help.Render(commandHelp, spec.Path)
		if text == "" {
			t.Errorf("%q renders nothing", spec.Path)
			continue
		}
		if len(text) > 2048 {
			t.Errorf("%q help is %d bytes, want at most 2048", spec.Path, len(text))
		}
		if ansi.MatchString(text) {
			t.Errorf("%q help has ANSI colour", spec.Path)
		}
		for _, word := range relativeTimeWords {
			if strings.Contains(text, word) {
				t.Errorf("%q help uses the relative-time word %q", spec.Path, word)
			}
		}
	}
}

func TestTopLevelHelpListsEverything(t *testing.T) {
	text := help.Render(commandHelp, "")
	for _, name := range []string{"skill", "skills", "version", "auth", "login", "logout", "startup"} {
		if !strings.Contains(text, name) {
			t.Errorf("top-level help does not mention %q", name)
		}
	}
}

// The unknown-topic line names the topics the catalog carries, so adding one is a map entry rather
// than a hunt for the places that say "env". This test adds a second topic to the real catalog,
// asks for a third, and reads the answer.
func TestAnUnknownTopicNamesTheRealSet(t *testing.T) {
	original := commandHelp
	t.Cleanup(func() { commandHelp = original })
	commandHelp = declarationsCatalog{
		specs: original.specs,
		topics: map[string]help.Topic{
			"env":  original.topics["env"],
			"auth": {Name: "auth", Label: "登录", Body: "登录主题\n"},
		},
	}

	handled, err := helpRequest([]string{"help", "nosuch"})
	if !handled || err == nil {
		t.Fatalf("an unknown topic was not refused: handled=%v err=%v", handled, err)
	}
	for _, want := range []string{"env", "auth"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("the refusal %q does not name the topic %q", err, want)
		}
	}

	top := help.RenderTop(commandHelp)
	if !strings.Contains(top, "登录：skmr help auth") {
		t.Errorf("the top-level help does not list the second topic:\n%s", top)
	}
}

// **Completeness, not just presence.** A command that can return 4 has to say so in its own help:
// the two shared failures are rendered by the kernel, so this test fails if either the gate or the
// table goes missing — not if a declaration forgot to repeat them.
func TestEveryCommandThatCanExitFourSaysWhatToDo(t *testing.T) {
	for _, spec := range commandHelp.Specs() {
		if spec.PureGroup || spec.Path == "" {
			continue
		}
		text := help.Render(commandHelp, spec.Path)
		canFour := false
		for _, code := range spec.Exits {
			if code == 4 {
				canFour = true
			}
		}
		if !canFour {
			if strings.Contains(text, "运行 skmr login 或换一把 API key") {
				t.Errorf("%q never leaves this machine but advertises a credential failure:\n%s", spec.Path, text)
			}
			continue
		}
		// `skmr login` is the next step an agent needs; a command may say it in its own
		// words (auth status does) or get it from the shared line.
		for _, want := range []string{"skmr login", "连不上服务端"} {
			if !strings.Contains(text, want) {
				t.Errorf("%q can exit 4 but its help does not mention %q:\n%s", spec.Path, want, text)
			}
		}
	}
}

// v22 S3 / Q4: the next step a 401 declares and the sentence the same 401 produces must agree about
// both ways in. The declaration is what an agent reads before it ever sees a 401; a declaration that
// says only 「重新登录」 sends a key holder to a command the environment key overrides, where it
// cannot help.
func TestTheUnauthenticatedNextStepAgreesWithTheRuntimeSentence(t *testing.T) {
	// **All six**, not one: the shared row only applies where a command does not declare its own
	// `unauthenticated` (internal/help/help.go's dedup), so a command that kept the old sentence
	// would silently stop naming the key path.
	for _, path := range []string{"auth status", "skills list", "skills my-tags", "skill invoke",
		"skill grep", "skill show"} {
		spec, ok := help.Lookup(commandHelp, path)
		if !ok {
			t.Fatalf("%q has no declaration", path)
		}
		var declared string
		for _, failure := range spec.Failures {
			if failure.Code == "unauthenticated" {
				declared = failure.Next
			}
		}
		if declared == "" {
			t.Errorf("%q declares no unauthenticated next step", path)
			continue
		}
		// The declaration serves both shapes, so it names both: the login and where a key is
		// replaced.
		for _, want := range []string{"skmr login", "API key"} {
			if !strings.Contains(declared, want) {
				t.Errorf("%q's declared next step omits %q: %s", path, want, declared)
			}
		}
		if !strings.Contains(declared, "控制台") {
			t.Errorf("%q's declared next step names no place to replace a key: %s", path, declared)
		}
	}
	if loginSentence, _ := refusalMessage(&api.Refusal{Code: "unauthenticated"}, false); !strings.Contains(loginSentence, "skmr login") {
		t.Errorf("the login-credential sentence omits the login command: %s", loginSentence)
	}
	if keySentence, _ := refusalMessage(&api.Refusal{Code: "unauthenticated"}, true); !strings.Contains(keySentence, "/account/keys") {
		t.Errorf("the key sentence omits the console address: %s", keySentence)
	}
}

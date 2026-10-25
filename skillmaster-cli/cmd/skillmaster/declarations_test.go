package main

import (
	"regexp"
	"strings"
	"testing"
	"unicode/utf8"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// The command set these tests measure against is the PRD's: 58 executable paths, which already
// includes the bare `skills my-tags`; six pure groups and the top level are nodes, not paths.
const wantCommandPaths = 58

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
	for _, name := range []string{"skill", "skills", "version", "auth", "login", "logout", "setup"} {
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
	if !strings.Contains(top, "登录：skillmaster help auth") {
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
			if strings.Contains(text, "运行 skillmaster login 或换一把 API key") {
				t.Errorf("%q never leaves this machine but advertises a credential failure:\n%s", spec.Path, text)
			}
			continue
		}
		// `skillmaster login` is the next step an agent needs; a command may say it in its own
		// words (auth status does) or get it from the shared line.
		for _, want := range []string{"skillmaster login", "连不上服务端"} {
			if !strings.Contains(text, want) {
				t.Errorf("%q can exit 4 but its help does not mention %q:\n%s", spec.Path, want, text)
			}
		}
	}
}

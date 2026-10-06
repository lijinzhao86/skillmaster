package main

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// The three outcomes of a submission, which the server reports as two fields and the person reads as
// one sentence.
//
// The middle row is the reason this is a table: a changed skill submitted again answers with
// `created: true`, because *this call created a version*. Reading that as "the skill was created"
// is what printed "已创建" at somebody who had just updated a skill that already existed — a mistake
// no single-case test would have caught.
//
// None of the three says anything about publishing: a submission lands a draft and the consumption
// plane does not change, so there is no outcome here that could claim otherwise.
func TestSubmitVerbNamesWhatHappened(t *testing.T) {
	cases := []struct {
		name    string
		created bool
		version int
		want    string
	}{
		{"a skill submitted for the first time", true, 1, "已创建"},
		{"a new version of a skill that already existed", true, 2, "已提交"},
		{"identical content, which writes nothing (ADR 0005)", false, 2, "内容未变"},
	}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			var result api.SubmitResult
			result.Created = test.created
			result.Version.Number = test.version

			if got := submitVerb(result); got != test.want {
				t.Fatalf("submitVerb = %q, want %q", got, test.want)
			}
		})
	}
}

// What the line under a submission says, which is not always "you have a draft waiting".
//
// `created` cannot answer this. A replay answers with whatever row already holds that digest, and the
// unique constraint is on the content rather than on the state — so that row may be a draft, may
// already be published, or may have been discarded. Two of those three must not be told to go and
// publish: one has nothing left to approve, and the other is refused by the server outright.
func TestTheStateLineSaysWhatTheVersionActuallyIs(t *testing.T) {
	cases := []struct {
		name  string
		state string
		want  string
	}{
		{"a fresh draft", api.StateDraft, "这一版还是草稿，线上没有任何变化。要生效得去网页上点「上线」。"},
		{"content that is already live", api.StatePublished, "这一版已经在线上，没有需要审批的东西。"},
		{"content that was thrown away", api.StateDiscarded, "这一版曾被丢弃，不会被上线；提交相同内容也不会把它变回草稿。"},
		// A state from a newer server than this build. Saying nothing about publishing is the honest
		// answer, and it must not fall through to the draft line.
		{"a state this build does not know", "archived", "这一版已提交。"},
	}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			if got := stateLine(test.state); got != test.want {
				t.Fatalf("stateLine(%q) = %q, want %q", test.state, got, test.want)
			}
		})
	}

	// The instruction belongs to the draft case and to no other. Asserted separately from the exact
	// strings, because the point is what the line tells somebody to *do*.
	if !strings.Contains(stateLine(api.StateDraft), "去网页上点") {
		t.Fatal("the draft line has to say where publishing happens")
	}
	for _, state := range []string{api.StatePublished, api.StateDiscarded, "archived"} {
		if got := stateLine(state); strings.Contains(got, "去网页上点") {
			t.Fatalf("stateLine(%q) = %q sends the reader to publish something that cannot be published",
				state, got)
		}
	}
}

// A skill whose `SKILL.md` is a symbolic link is not offered as a candidate.
//
// `os.Stat` follows the link and the archiver refuses one, so listing it would name a skill that
// cannot be submitted — and the refusal that followed reads as a broken skill rather than as a name
// this command should never have suggested.
func TestASymlinkedSkillFileIsNotOffered(t *testing.T) {
	skillsDir := t.TempDir()
	real := filepath.Join(skillsDir, "real-tools")
	if err := os.MkdirAll(real, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(real, "SKILL.md"), []byte("---\nname: real-tools\n---\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	linked := filepath.Join(skillsDir, "linked-tools")
	if err := os.MkdirAll(linked, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(real, "SKILL.md"), filepath.Join(linked, "SKILL.md")); err != nil {
		t.Skipf("symbolic links are not available here: %v", err)
	}

	names, err := skillNamesIn(skillsDir)
	if err != nil {
		t.Fatalf("skillNamesIn: %v", err)
	}
	if len(names) != 1 || names[0] != "real-tools" {
		t.Fatalf("skillNamesIn = %v, want [real-tools]", names)
	}
}

// `~` is expanded and `~user` is not.
//
// Stripping the tilde from `~bob/skills/x` would resolve it to `$HOME/bob/skills/x` — a path nobody
// named, at a directory that may well exist and is the wrong one. Left alone it is a path that does
// not exist, so it fails where it is used and says so.
func TestExpandHomeHandlesOnlyABareTilde(t *testing.T) {
	home, err := os.UserHomeDir()
	if err != nil {
		t.Skipf("no home directory to expand against: %v", err)
	}

	cases := []struct{ in, want string }{
		{"~/my-skills/pdf-tools", filepath.Join(home, "my-skills", "pdf-tools")},
		{"~", home},
		{"~bob/skills/x", "~bob/skills/x"},
		{"/somewhere/else", "/somewhere/else"},
		{"./relative", "./relative"},
	}

	for _, test := range cases {
		got, err := expandHome(test.in)
		if err != nil {
			t.Fatalf("expandHome(%q): %v", test.in, err)
		}
		if got != test.want {
			t.Fatalf("expandHome(%q) = %q, want %q", test.in, got, test.want)
		}
	}
}

// What a gateway fetch has to be, and what it must not be mistaken for.
//
// The negative case is a real one: the dev proxy has no `/gateway` route, so Vite answered with its
// SPA index — 200, `text/html`, and a perfectly installable-looking body. `setup` wrote it into
// `SKILL.md` and reported success (2026-10-05), and the agent loaded a web page as a skill. A
// reverse proxy with a `try_files … /index.html` fallback does the same thing in production.
func TestOnlyASkillIsInstalledAsTheGateway(t *testing.T) {
	cases := []struct {
		name    string
		content string
		want    bool
	}{
		{
			name:    "the gateway skill as the server serves it",
			content: "---\nname: skillmaster\ndescription: 按需取用已托管的 skill。\nmetadata:\n  platform_api_version: \"1\"\n---\n\n# 我的 skill 库\n",
			want:    true,
		},
		{
			name:    "a web page, which is what a fallback answers with",
			content: "<!doctype html>\n<html lang=\"zh-CN\">\n  <head>\n    <title>SkillMaster</title>\n",
			want:    false,
		},
		{
			name:    "an HTML page that happens to mention name:",
			content: "<!doctype html>\n<html><body>name: skillmaster</body></html>\n",
			want:    false,
		},
		{
			name:    "frontmatter with no name in it",
			content: "---\ndescription: anonymous\n---\n# x\n",
			want:    false,
		},
		{
			name:    "frontmatter that is never closed",
			content: "---\nname: skillmaster\n# no closing fence\n",
			want:    false,
		},
		{name: "an empty body", content: "", want: false},
	}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			if got := isSkillMarkdown([]byte(test.content)); got != test.want {
				t.Fatalf("isSkillMarkdown = %v, want %v", got, test.want)
			}
		})
	}
}

// The gateway goes into a directory named after the skill's own `name`.
//
// §1.1 makes that equality a MUST, and this repository obeys it everywhere it controls a layout —
// which is why the source lives at `gateway/skillmaster/`. The install was the one place it could be
// broken, and it was: `setup` wrote to `skillmaster-gateway`, a name it invented, while the file
// inside declared `name: skillmaster`. That failure is silent where it costs most — an agent that
// checks the rule ignores the skill, so the service is never discovered while `setup` prints
// 「已安装」 and exits 0.
func TestSetupInstallsUnderTheNameTheSkillDeclares(t *testing.T) {
	server, skillsDir := serveGateway(t,
		"---\nname: skillmaster\ndescription: x\nmetadata:\n  platform_api_version: \"1\"\n---\n# body\n")

	if err := setup(context.Background(), nil); err != nil {
		t.Fatalf("setup: %v", err)
	}

	if _, err := os.Stat(filepath.Join(skillsDir, "skillmaster", "SKILL.md")); err != nil {
		t.Fatalf("the skill is not where its own name says it goes: %v", err)
	}
	if _, err := os.Stat(filepath.Join(skillsDir, "skillmaster-gateway")); err == nil {
		t.Fatal("a directory named after nothing in the skill was created")
	}
	_ = server
}

// The name decides a path, and it arrives over the network from a server this CLI does not control,
// so it is checked rather than trusted: `..` would put the file outside the skills directory, and a
// separator would put it wherever the server liked. Nothing is written when it is refused.
func TestSetupRefusesANameThatIsNotAPathComponent(t *testing.T) {
	for _, name := range []string{"../../evil", "a/b", "..", ".", "UPPER", "with space", "-lead"} {
		t.Run(name, func(t *testing.T) {
			serveGateway(t, "---\nname: "+name+"\ndescription: x\n---\n# body\n")
			skillsDir := os.Getenv(config.SkillsDirEnv)

			if err := setup(context.Background(), nil); err == nil {
				t.Fatal("setup accepted a name that is not a path component")
			}
			if entries, err := os.ReadDir(skillsDir); err != nil || len(entries) != 0 {
				t.Fatalf("something was written anyway: %v %v", entries, err)
			}
		})
	}
}

// serveGateway points the CLI at a server that answers `/gateway/SKILL.md` with this content, and
// returns the skills directory setup will install into.
func serveGateway(t *testing.T, content string) (*httptest.Server, string) {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/gateway/SKILL.md" {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write([]byte(content))
	}))
	t.Cleanup(server.Close)

	skillsDir := t.TempDir()
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.SkillsDirEnv, skillsDir)
	return server, skillsDir
}

// The two keys this file reads are plain scalars on their own line, and a key that is absent or
// malformed has to read as absent rather than as an empty value that then becomes a path.
func TestFrontmatterScalarReadsOnlyTheTopLevel(t *testing.T) {
	const content = "---\nname: skillmaster\ndescription: 按需取用\nmetadata:\n  platform_api_version: \"1\"\n---\n# body\n"

	if got, ok := frontmatterScalar([]byte(content), "name"); !ok || got != "skillmaster" {
		t.Fatalf("name = %q, %v", got, ok)
	}
	if got, ok := frontmatterScalar([]byte(content), "platform_api_version"); !ok || got != "1" {
		t.Fatalf("platform_api_version = %q, %v", got, ok)
	}
	if got, ok := frontmatterScalar([]byte(content), "description"); !ok || got != "按需取用" {
		t.Fatalf("description = %q, %v", got, ok)
	}
	if _, ok := frontmatterScalar([]byte(content), "absent"); ok {
		t.Fatal("an absent key reported a value")
	}
	if _, ok := frontmatterScalar([]byte("no frontmatter here"), "name"); ok {
		t.Fatal("a body with no frontmatter reported a name")
	}
	// Quoted, because a real frontmatter writes the version that way, and the quotes are not part
	// of the value.
	if got, _ := frontmatterScalar([]byte("---\nname: \"quoted\"\n---\n"), "name"); got != "quoted" {
		t.Fatalf("a quoted value kept its quotes: %q", got)
	}
}

// A credential that cannot be read does not stop the command that replaces it.
//
// The read is only there to learn what to revoke *afterwards*, and this is the one credential on a
// machine that may be unreadable — a keychain that timed out, a file somebody edited by hand. Making
// it fatal left `login`, the command that fixes exactly that, as the one command a broken credential
// stops, with a message pointing at the read instead of at what to delete.
func TestAnUnreadableCredentialDoesNotStopLogin(t *testing.T) {
	previous, hadPrevious := previousCredential(unreadable{})

	if hadPrevious {
		t.Fatal("an unreadable credential was reported as one to revoke")
	}
	if previous != (credentials.Credentials{}) {
		t.Fatalf("something was carried forward anyway: %+v", previous)
	}
}

// unreadable is a store whose every operation fails, which is what a locked keychain and a corrupt
// file have in common from here.
type unreadable struct{}

func (unreadable) Load() (credentials.Credentials, bool, error) {
	return credentials.Credentials{}, false, errors.New("the keychain did not answer")
}
func (unreadable) Save(credentials.Credentials) error { return errors.New("no") }
func (unreadable) Delete() error                      { return errors.New("no") }
func (unreadable) Where() string                      { return "nowhere" }

func TestIsSkillNameRejectsWhatCannotBeAPath(t *testing.T) {
	good := []string{"skillmaster", "a", "a-b-c", "x1"}
	bad := []string{"", ".", "..", "a/b", "../x", "A", "a b", "-a", "a-", "a--b", "a_b", "ü"}
	for _, name := range good {
		if !isSkillName(name) {
			t.Errorf("%q should be a usable name", name)
		}
	}
	for _, name := range bad {
		if isSkillName(name) {
			t.Errorf("%q should not be a usable name", name)
		}
	}
}

// Which of the three shapes an argument is: a bare name, a path, or nothing at all.
//
// The classification reads the argument's own characters and never the filesystem, which is what
// makes it deterministic — `submit pdf-tools` means the same thing whether or not a directory called
// `pdf-tools` happens to be in the current one. A skill's name is a path component (§1.1), so any of
// these characters in one is already a name no skill can have, and the two readings cannot collide.
func TestIsPathLikeReadsTheArgumentRatherThanTheDisk(t *testing.T) {
	paths := []string{"pdf-tools/", "./pdf-tools", "../skills/pdf-tools", "~/skills/pdf-tools",
		"/opt/skills/pdf-tools", "a/b/c", ".hidden"}
	names := []string{"pdf-tools", "a", "x1", "a-b-c"}

	for _, arg := range paths {
		if !isPathLike(arg) {
			t.Errorf("%q should be read as a path", arg)
		}
	}
	for _, arg := range names {
		if isPathLike(arg) {
			t.Errorf("%q should be read as a name", arg)
		}
	}
}

// A bare name goes under the skills directory, which is the same one `setup` installs into — one
// answer to "where are the skills", so installing one and submitting one cannot disagree.
func TestBareNameResolvesUnderTheSkillsDirectory(t *testing.T) {
	skillsDir := t.TempDir()
	t.Setenv(config.SkillsDirEnv, skillsDir)

	got, err := resolveSkill([]string{"pdf-tools"})
	if err != nil {
		t.Fatalf("resolveSkill: %v", err)
	}
	if want := filepath.Join(skillsDir, "pdf-tools"); got != want {
		t.Fatalf("resolveSkill = %q, want %q", got, want)
	}
}

// A path is taken as given, including one that starts at the home directory — which a shell usually
// expands before this sees it, and does not when the value was quoted.
func TestAPathIsTakenAsGiven(t *testing.T) {
	home, err := os.UserHomeDir()
	if err != nil {
		t.Skipf("no home directory to expand against: %v", err)
	}

	got, err := resolveSkill([]string{"~/skills/pdf-tools"})
	if err != nil {
		t.Fatalf("resolveSkill: %v", err)
	}
	if want := filepath.Join(home, "skills", "pdf-tools"); got != want {
		t.Fatalf("resolveSkill = %q, want %q", got, want)
	}

	if got, err := resolveSkill([]string{"./here"}); err != nil || got != "./here" {
		t.Fatalf("a relative path was rewritten: %q, %v", got, err)
	}
}

// No argument lists what there is instead of guessing at one, which is the whole value of the empty
// form: the person who does not remember the directory name is who it is for.
//
// A directory without a SKILL.md in it is not a skill, which is the same check the server's
// validator starts with — so listing it would offer somebody something that cannot be submitted.
func TestNoArgumentListsTheSkillsThatAreThere(t *testing.T) {
	skillsDir := t.TempDir()
	for _, name := range []string{"pdf-tools", "image-tools"} {
		dir := filepath.Join(skillsDir, name)
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, "SKILL.md"), []byte("---\nname: x\n---\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	// Not a skill: no SKILL.md. And a plain file, which is not a directory at all.
	if err := os.MkdirAll(filepath.Join(skillsDir, "not-a-skill"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(skillsDir, "stray.md"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Setenv(config.SkillsDirEnv, skillsDir)

	_, err := resolveSkill(nil)
	if err == nil {
		t.Fatal("no argument was accepted")
	}
	if !strings.Contains(err.Error(), "image-tools、pdf-tools") {
		t.Fatalf("the candidates are not listed: %v", err)
	}
	if strings.Contains(err.Error(), "not-a-skill") || strings.Contains(err.Error(), "stray.md") {
		t.Fatalf("something that is not a skill was offered: %v", err)
	}
}

// An empty argument is refused rather than read as a name.
//
// `skillmaster submit "$SKILL"` with the variable unset is an empty argument, and `filepath.Join(dir,
// "")` is `dir` — so the bare-name branch would archive the whole skills directory and send it as
// one skill. The server would refuse it, but only after the upload, and its answer would be about a
// skill's contents rather than about the argument.
func TestAnEmptyArgumentIsRefused(t *testing.T) {
	t.Setenv(config.SkillsDirEnv, t.TempDir())

	if _, err := resolveSkill([]string{""}); err == nil {
		t.Fatal("an empty argument was accepted as a skill name")
	}
}

func TestTooManyArgumentsIsARefusal(t *testing.T) {
	if _, err := resolveSkill([]string{"a", "b"}); err == nil {
		t.Fatal("two arguments were accepted")
	}
}

// The deep link, which is the CLI's whole half of the handover to the browser (ADR 0031).
//
// Built from the web base rather than from the API base: the two are one origin in a deployment and
// are not in development, and a link built from the server would open a 404 that reads as a failed
// submission. Both segments are escaped, because a name or a namespace may be non-ASCII.
func TestSkillPageURLPointsAtTheWebBaseAndEscapesItsSegments(t *testing.T) {
	t.Setenv(config.WebURLEnv, "http://localhost:5173/")

	var result api.SubmitResult
	result.Namespace = "demo user"
	result.Name = "pdf-tools"

	if got, want := skillPageURL(result), "http://localhost:5173/skills/demo%20user/pdf-tools"; got != want {
		t.Fatalf("skillPageURL = %q, want %q", got, want)
	}
}

// With nothing configured the two bases are the same, which is what a deployment looks like: one
// reverse proxy serving the pages and the API together.
func TestTheWebBaseDefaultsToTheServer(t *testing.T) {
	t.Setenv(config.ServerEnv, "https://skills.example.com/")
	t.Setenv(config.WebURLEnv, "")

	if got, want := config.WebURL(), "https://skills.example.com"; got != want {
		t.Fatalf("WebURL = %q, want %q", got, want)
	}
}

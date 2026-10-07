package main

import (
	"context"
	"errors"
	"io"
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
// no single-case test would have caught. `skill_created` is the field that carries the distinction
// the version number used to carry (ADR 0033), so the two fields have to be combined here exactly as
// the server sets them.
//
// None of the three says anything about publishing: a submission lands a draft and the consumption
// plane does not change, so there is no outcome here that could claim otherwise.
func TestSubmitVerbNamesWhatHappened(t *testing.T) {
	cases := []struct {
		name         string
		created      bool
		skillCreated bool
		want         string
	}{
		{"a skill submitted for the first time", true, true, "已创建"},
		{"a new version of a skill that already existed", true, false, "已提交"},
		{"identical content, which writes nothing (ADR 0005)", false, false, "内容未变"},
	}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			var result api.SubmitResult
			result.Created = test.created
			result.SkillCreated = test.skillCreated

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
// `skillmaster skill submit "$SKILL"` with the variable unset is an empty argument, and
// `filepath.Join(dir,
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

// `share` refuses everything it can refuse before a request is made, and the refusals are asserted
// to be usage messages rather than merely errors: a request would also fail here (no server, no
// credential), and that failure would be about the machine rather than about what the person typed.
//
// The `--to`-less case is the one that is not cosmetic. There is no such thing as sharing with
// somebody in general, so a default target would mean the command could hand access to a person
// nobody named — which is why it is refused rather than answered with anything.
func TestShareRefusesWhatItCannotCarryOut(t *testing.T) {
	cases := map[string][]string{
		"no arguments":        {},
		"address only":        {"demo/hello"},
		"--to with no handle": {"demo/hello", "--to"},
		"a role that is none": {"demo/hello", "--to", "bob", "--role", "owner"},
		"an unknown flag":     {"demo/hello", "--to", "bob", "--force"},
		"a malformed address": {"demo", "--to", "bob"},
		"a pinned address":    {"demo/hello@1.2.3", "--to", "bob"},
		"a pinned sha256":     {"demo/hello@sha256:abc", "--to", "bob"},
	}
	for name, args := range cases {
		t.Run(name, func(t *testing.T) {
			if _, _, _, err := parseShare(args); err == nil {
				t.Fatalf("%v was accepted", args)
			}
		})
	}
}

// The two things a valid `share` answers with, both of which have a default that must be the safe
// one: the role defaults to the read-only one, and the handle comes back exactly as typed so that
// what was resolved can be printed beside what was asked for.
func TestShareDefaultsToViewerAndKeepsTheHandle(t *testing.T) {
	address, handle, role, err := parseShare([]string{"demo/hello", "--to", "bob"})
	if err != nil {
		t.Fatalf("a valid share was refused: %v", err)
	}
	if address != "demo/hello" || handle != "bob" {
		t.Fatalf("address = %q, handle = %q", address, handle)
	}
	if role != api.RoleViewer {
		t.Fatalf("role = %q, want %q", role, api.RoleViewer)
	}

	// Flags in either order, because a person typing one does not know which the parser wants.
	if _, _, role, err := parseShare([]string{"demo/hello", "--role", "editor", "--to", "bob"}); err != nil || role != api.RoleEditor {
		t.Fatalf("--role before --to: role = %q, err = %v", role, err)
	}
}

// `submit --to` separates the target from the argument that names what to upload, and the separation
// has to be exact: `resolveSkill` reads its one argument by the argument's own characters, so a
// `--to` left in the list would be read as a skill called `--to`.
func TestSubmitToIsPulledOutOfTheArguments(t *testing.T) {
	dirArgs, target, err := splitSubmitArgs([]string{"pdf-tools", "--to", "lark/pdf-tools"})
	if err != nil {
		t.Fatalf("a valid --to was refused: %v", err)
	}
	if target != "lark/pdf-tools" {
		t.Fatalf("target = %q", target)
	}
	if len(dirArgs) != 1 || dirArgs[0] != "pdf-tools" {
		t.Fatalf("dirArgs = %v", dirArgs)
	}

	// And the flag is optional: without it the arguments pass through untouched and the target is
	// empty, which is what selects the create route rather than the add-a-version one.
	dirArgs, target, err = splitSubmitArgs([]string{"pdf-tools"})
	if err != nil || target != "" || len(dirArgs) != 1 {
		t.Fatalf("without --to: dirArgs = %v, target = %q, err = %v", dirArgs, target, err)
	}

	// A version pin is refused rather than dropped: the version is what this call produces, so a
	// target naming one names something the call does not use — and silently submitting to more than
	// was asked for is the direction that must not fail open.
	for _, args := range [][]string{
		{"pdf-tools", "--to"},
		{"pdf-tools", "--to", "lark"},
		{"pdf-tools", "--to", "lark/pdf-tools@1.2.3"},
		{"pdf-tools", "--to", "a/b", "--to", "c/d"},
	} {
		if _, _, err := splitSubmitArgs(args); err == nil {
			t.Fatalf("%v was accepted", args)
		}
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

// `list` takes a switch and nothing else.
//
// There is no paging parameter, and the absence is the design: what a cursor would carry is a
// 191-character arbitrary string, and a caller that has to reproduce it is being asked for something
// a model has no exact-copy path for. A filter flag is absent for a different reason — what a
// namespace filter should mean for somebody with several namespaces is not settled, and an
// unsettled flag is one that will be used wrongly.
func TestListAcceptsOnlyAllAndRepeatedNamespaces(t *testing.T) {
	all, namespaces, err := parseListArgs(nil)
	if err != nil || all || len(namespaces) != 0 {
		t.Fatalf("no arguments: all = %v, namespaces = %v, err = %v", all, namespaces, err)
	}

	all, namespaces, err = parseListArgs([]string{"--all"})
	if err != nil || !all || len(namespaces) != 0 {
		t.Fatalf("--all: all = %v, namespaces = %v, err = %v", all, namespaces, err)
	}

	// Repeated, and in the order given: the endpoint reads them as a set, but the CLI must not drop
	// one or reorder them on the way out.
	all, namespaces, err = parseListArgs([]string{"--namespace", "mine", "--namespace", "lark", "--all"})
	if err != nil || !all {
		t.Fatalf("both: all = %v, err = %v", all, err)
	}
	if len(namespaces) != 2 || namespaces[0] != "mine" || namespaces[1] != "lark" {
		t.Fatalf("namespaces = %v", namespaces)
	}

	// Every one of these used to be either a working invocation or a typo away from one: the cursor
	// form was a real feature until it was removed, so a command line still carrying it has to be
	// refused rather than silently ignored.
	for _, args := range [][]string{
		{"lark"},
		{"2"},
		{"--all", "lark"},
		{"--namespace"},
		{"--cursor", "eyJjdXJzb3IiOiJ4In0"},
		{"--namespace=lark"},
	} {
		if _, _, err := parseListArgs(args); err == nil {
			t.Errorf("%v was accepted", args)
		}
	}
}

// **The filter goes on every page of a walk.** The server's cursor carries the ordering and not the
// predicate, so a page fetched without the filter is accepted and answers a different question —
// silently, which is the failure the whole cursor design exists to prevent. This is that failure
// arriving from the client side, so it is asserted on every request rather than on the first.
func TestEveryPageOfAWalkCarriesTheFilter(t *testing.T) {
	var asked []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		asked = append(asked, r.URL.RawQuery)
		if strings.Contains(r.URL.RawQuery, "cursor=") {
			_, _ = w.Write([]byte(`{"skills":[],"next_cursor":null}`))
			return
		}
		_, _ = w.Write([]byte(`{"skills":[],"next_cursor":"c1"}`))
	}))
	defer server.Close()

	if _, err := collect(context.Background(), api.Client{BaseURL: server.URL}, true, []string{"mine", "lark"}); err != nil {
		t.Fatalf("collect: %v", err)
	}
	if len(asked) != 2 {
		t.Fatalf("asked %v", asked)
	}
	for _, query := range asked {
		if !strings.Contains(query, "namespace=mine") || !strings.Contains(query, "namespace=lark") {
			t.Errorf("a page went out without the filter: %q", query)
		}
	}
}

// The walk: one page unless `--all`, and every page when it is asked for.
//
// The default is what the caller sees, and `--all` is the loop the caller does not have to drive —
// which is the whole point of the change, because the alternative was handing it a 191-character
// string to carry between calls. So what is pinned here is which of the two happens, and that the
// cursor travels between pages rather than page one being fetched three times.
func TestListWalksEveryPageOnlyWhenAsked(t *testing.T) {
	card := func(name string) string {
		return `{"name":"` + name + `","title":"T","namespace":"n",` +
			`"version":{"name":"1.0.0","digest":"sha256:x"}}`
	}
	// Keyed by the raw query, which `url.Values.Encode()` sorts: `cursor` before `limit`.
	body := map[string]string{
		"limit=100":           `{"skills":[` + card("a") + `],"next_cursor":"c1"}`,
		"cursor=c1&limit=100": `{"skills":[` + card("b") + `],"next_cursor":"c2"}`,
		"cursor=c2&limit=100": `{"skills":[` + card("c") + `],"next_cursor":null}`,
	}

	var asked []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		asked = append(asked, r.URL.RawQuery)
		answer, ok := body[r.URL.RawQuery]
		if !ok {
			t.Errorf("asked for %q, which is not a page", r.URL.RawQuery)
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		_, _ = w.Write([]byte(answer))
	}))
	defer server.Close()

	client := api.Client{BaseURL: server.URL}

	one, err := collect(context.Background(), client, false, nil)
	if err != nil {
		t.Fatalf("one page: %v", err)
	}
	if len(one.cards) != 1 || !one.more {
		t.Fatalf("one page: %d cards, more = %v — the caller has to be told there is more", len(one.cards), one.more)
	}
	if len(asked) != 1 {
		t.Fatalf("one page took %d requests: %v", len(asked), asked)
	}

	asked = nil
	every, err := collect(context.Background(), client, true, nil)
	if err != nil {
		t.Fatalf("--all: %v", err)
	}
	if len(every.cards) != 3 || every.more {
		t.Fatalf("--all: %d cards, more = %v", len(every.cards), every.more)
	}
	// The cursor travelled: three distinct pages, in order, rather than page one three times.
	if len(asked) != 3 || asked[0] != "limit=100" || asked[1] != "cursor=c1&limit=100" ||
		asked[2] != "cursor=c2&limit=100" {
		t.Fatalf("--all asked for %v", asked)
	}
}

// A server that keeps handing back the cursor it was just given is not paging, and a loop on it would
// hang with no output — which reads as a slow network rather than as the bug it is.
func TestListStopsWhenTheServerDoesNotAdvance(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"skills":[],"next_cursor":"same"}`))
	}))
	defer server.Close()

	if _, err := collect(context.Background(), api.Client{BaseURL: server.URL}, true, nil); err == nil {
		t.Fatal("a server that reissued its own cursor was followed for ever")
	}
}

// **The exact bytes of a row**, which is the thing this command exists to produce.
//
// Everything above is about parsing arguments and walking pages; this is about what a caller
// actually reads, so it is asserted against the output rather than against a struct — a formatting
// change that nobody meant is exactly the kind that passes every other test here.
//
// **No version is anywhere in the expected output, and that is the contract now** (ADR 0035). The
// address used to carry the resolved version so that copying the line whole kept a caller on the
// version the listing was about; `invoke` does that job now, so a listing that started printing a pin
// again would be handing back the long arbitrary string this change took away.
func TestARowIsTheAddressAloneAndItsReadingUnderneath(t *testing.T) {
	for _, c := range []struct {
		name  string
		cards []api.Card
		want  string
	}{
		{
			name:  "an address on its own line, and the reading indented under it",
			cards: []api.Card{{Namespace: "demo", Name: "pdf-tools", Description: "d", WhenToUse: "w"}},
			want:  "demo/pdf-tools\n    d - w\n",
		},
		{
			name:  "a skill with neither description nor when_to_use is one line",
			cards: []api.Card{{Namespace: "lark", Name: "sheets"}},
			want:  "lark/sheets\n",
		},
		{
			name: "several rows, each complete on its own",
			cards: []api.Card{
				{Namespace: "demo", Name: "pdf-tools", Description: "拆开与合并。"},
				{Namespace: "lark", Name: "sheets"},
			},
			want: "demo/pdf-tools\n    拆开与合并。\nlark/sheets\n",
		},
	} {
		t.Run(c.name, func(t *testing.T) {
			if got := captureStdout(t, func() { printCards(c.cards) }); got != c.want {
				t.Errorf("printed\n%q\nwant\n%q", got, c.want)
			}
		})
	}
}

// captureStdout runs one function with stdout redirected, and returns what it printed.
//
// The technique every output assertion here needs, and the reason it is a helper now rather than four
// lines at each call site: the read commands print content, and content is the one thing in this CLI
// whose exact bytes are a contract rather than a courtesy.
//
// **The read runs concurrently, and that is not tidiness.** A pipe holds 64 KiB; the content caps here
// are twice that, so a writer that filled the buffer before anybody drained it would block for ever —
// a test that hangs rather than fails, which is the worst way to learn this.
func captureStdout(t *testing.T, print func()) string {
	t.Helper()
	read, write, err := os.Pipe()
	if err != nil {
		t.Fatalf("pipe: %v", err)
	}
	original := os.Stdout
	os.Stdout = write

	drained := make(chan string, 1)
	go func() {
		out, _ := io.ReadAll(read)
		drained <- string(out)
	}()

	print()
	_ = write.Close()
	os.Stdout = original

	out := <-drained
	_ = read.Close()
	return out
}

// The `skill` group, and the fact that its verbs are no longer top-level.
//
// **Both halves matter and for different reasons.** The group has to name its own verbs when somebody
// gets one wrong — a person who typed `skill lst` wants that group's commands, not the whole CLI. And
// the flat names have to be *refused* rather than quietly kept working as aliases: they were a real
// interface until this change, so a command line still carrying one is a caller running yesterday's
// protocol, and two spellings of one command is the thing the rename exists to end.
func TestTheSkillGroupOwnsItsVerbsAndTheFlatNamesAreGone(t *testing.T) {
	verbs := []string{"list", "search", "invoke", "files", "read", "versions", "submit", "share"}

	for _, args := range [][]string{nil, {"lst"}, {"Get"}, {"list", "extra"}} {
		err := skillVerb(context.Background(), args)
		if len(args) == 2 && args[0] == "list" {
			// `list extra` is a *usage* refusal from the verb itself, not from the group — it still
			// has to be an error, and it still names the verb's own syntax.
			if err == nil {
				t.Errorf("skill %v was accepted", args)
			}
			continue
		}
		if err == nil {
			t.Fatalf("skill %v was accepted", args)
		}
		for _, verb := range verbs {
			if !strings.Contains(err.Error(), verb) {
				t.Errorf("skill %v does not name %q:\n%v", args, verb, err)
			}
		}
	}

	// `help` under the group is the one that succeeds, and it prints rather than failing: asking a
	// group what it holds is not a mistake.
	if err := skillVerb(context.Background(), []string{"help"}); err != nil {
		t.Errorf("skill help: %v", err)
	}

	// And through the real dispatch: the old names reach nothing.
	for _, verb := range verbs {
		if err := run(context.Background(), []string{verb}); err == nil {
			t.Errorf("%q still works at the top level", verb)
		}
	}
	if err := run(context.Background(), []string{"skill"}); err == nil {
		t.Error("`skill` with no verb was accepted")
	}
}

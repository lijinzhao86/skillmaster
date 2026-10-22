package main

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"regexp"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// TestMain points `openStore` at a store that is not this machine's, for every test in the package.
//
// **A test that runs the real `login` reaches the real login keychain**, and on macOS that means a
// keychain authorization dialog on whoever's screen is in front of the machine: the approval is
// attached to the binary, `go test` builds a fresh unsigned one every run, so it can never stick.
// Seen on 2026-10-15 — running this package repeatedly raised that dialog repeatedly, from tests
// whose subject was which flag reaches which shape.
//
// Set here rather than in each test, because the cost of one test forgetting is not a red test: it
// is a dialog nobody asked for, on a machine that may not even be the author's. Tests that need a
// particular store still inject one (`authStatusWith`, `logoutWith`), and a test that wants to see
// what the real wiring does can hand `machineStore` its own value and restore it.
func TestMain(m *testing.M) {
	machineStore = func(string, string) (credentials.Store, string) { return &stubStore{}, "" }
	// **And for the same reason, no test opens a browser.** A command with nothing to present now
	// starts a login for itself (v4 做 #8), so a test that reaches one is a test that would, left to
	// the real thing: print a link, bind the loopback port and call `open` — a window on whoever's
	// screen is in front of the machine, and five minutes of waiting for a callback nobody will make.
	// What it stands in for here refuses, so a test that did not mean to log in fails where it is
	// standing instead. The tests that *are* about the login replace this again (`driveTheLogin`).
	startLogin = func(context.Context, string, credentials.EnvStore, string) error {
		return errors.New("a test reached the login: this run does not open anything")
	}
	os.Exit(m.Run())
}

// pointTheMachineAt hands `openStore` a store of the test's choosing, for one test.
//
// TestMain's seam hands out an empty store, which is what most cases want. A case about what is
// *already on this machine* needs one with something in it — and the credential has to arrive through
// the wiring a person's would (`openStore`), not as a parameter, or the case would prove the sentence
// rather than the path that reaches it.
func pointTheMachineAt(t *testing.T, store credentials.Store) {
	t.Helper()
	previous := machineStore
	machineStore = func(string, string) (credentials.Store, string) { return store, "" }
	t.Cleanup(func() { machineStore = previous })
}

// **The seam's failure is invisible, so it gets its own assertion.** Delete TestMain's first line and
// every other test in this package still passes — on a machine whose keychain answers without asking,
// the difference is a five-second deadline and a dialog somewhere else. Comparing the two functions
// is the only way to state "this package does not call the real one" from inside the package.
//
// **The browser seam is asserted beside it for the same reason and a worse consequence**: a test that
// reached the real login would open a window and then wait five minutes for a callback that is never
// coming, which reads as a hanging suite rather than as a red test.
func TestTheSuiteIsNotPointedAtThisMachinesKeychain(t *testing.T) {
	if reflect.ValueOf(machineStore).Pointer() == reflect.ValueOf(credentials.Open).Pointer() {
		t.Fatal("machineStore is credentials.Open: this package's tests will read the real keychain")
	}
	store, _ := machineStore("/nowhere", "http://localhost:8080")
	if store.Where() != "测试钥匙串" {
		t.Fatalf("the seam hands out %q, want the test store", store.Where())
	}
	if reflect.ValueOf(startLogin).Pointer() == reflect.ValueOf(loginForACommand).Pointer() {
		t.Fatal("startLogin is the real login: a test with nothing to present would open a browser")
	}
}

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
	// Wrapped the way `login` wraps the real one: what `previousCredential` reads is this machine's
	// own copy, and the environment is not it.
	previous, hadPrevious := previousCredential(credentials.EnvStore{Fallback: unreadable{}})

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

// undeletable reads fine and cannot delete: the state a keychain that stopped answering leaves
// `logout` in, and the one where a wrong answer sounds exactly like a right one.
type undeletable struct{}

var errStickyStore = errors.New("系统钥匙串没有回应：它可能锁着")

func (undeletable) Load() (credentials.Credentials, bool, error) {
	return credentials.Credentials{AccessToken: "an-oauth-token"}, true, nil
}
func (undeletable) Save(credentials.Credentials) error { return errStickyStore }
func (undeletable) Delete() error                      { return errStickyStore }
func (undeletable) Where() string                      { return "测试钥匙串" }

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
// one: the role defaults to the lowest rung, and the handle comes back exactly as typed so that what
// was resolved can be printed beside what was asked for.
func TestShareDefaultsToInvokeAndKeepsTheHandle(t *testing.T) {
	address, handle, role, err := parseShare([]string{"demo/hello", "--to", "bob"})
	if err != nil {
		t.Fatalf("a valid share was refused: %v", err)
	}
	if address != "demo/hello" || handle != "bob" {
		t.Fatalf("address = %q, handle = %q", address, handle)
	}
	if role != api.RoleInvoke {
		t.Fatalf("role = %q, want %q", role, api.RoleInvoke)
	}

	// Flags in either order, because a person typing one does not know which the parser wants.
	if _, _, role, err := parseShare([]string{"demo/hello", "--role", "write", "--to", "bob"}); err != nil || role != api.RoleWrite {
		t.Fatalf("--role before --to: role = %q, err = %v", role, err)
	}
}

// **The retired words are refused, not translated** (technical-design §一处要连带改的契约). A request
// that answered "已授权" while granting something other than what was typed would be worse than an
// error: the person would never find out until somebody could do something they did not intend.
func TestShareRefusesTheRetiredRoleNames(t *testing.T) {
	for _, retired := range []string{"viewer", "editor"} {
		_, _, _, err := parseShare([]string{"demo/hello", "--to", "bob", "--role", retired})
		if err == nil {
			t.Fatalf("%q was accepted", retired)
		}
		if !strings.Contains(err.Error(), "已经不再接受") {
			t.Errorf("%q: the refusal does not say the name is retired: %v", retired, err)
		}
	}
	// And a word outside the five is refused by name, with the five listed.
	if _, _, _, err := parseShare([]string{"demo/hello", "--to", "bob", "--role", "owner"}); err == nil {
		t.Fatal("owner was accepted as a grantable role")
	}
}

// `submit --to` separates the target from the argument that names what to upload, and the separation
// has to be exact: `resolveSkill` reads its one argument by the argument's own characters, so a
// `--to` left in the list would be read as a skill called `--to`.
func TestSubmitToIsPulledOutOfTheArguments(t *testing.T) {
	dirArgs, target, name, note, err := splitSubmitArgs([]string{"pdf-tools", "--name", "compress 补文档",
		"--note", "一句说明", "--to", "lark/pdf-tools"})
	if err != nil {
		t.Fatalf("a valid --to was refused: %v", err)
	}
	if target != "lark/pdf-tools" {
		t.Fatalf("target = %q", target)
	}
	if name != "compress 补文档" || note != "一句说明" {
		t.Fatalf("name = %q, note = %q", name, note)
	}
	if len(dirArgs) != 1 || dirArgs[0] != "pdf-tools" {
		t.Fatalf("dirArgs = %v", dirArgs)
	}

	// And the target is optional: without it the arguments pass through untouched and the target is
	// empty, which is what selects the create route rather than the add-a-version one. `--note` is
	// optional too; `--name` is not.
	dirArgs, target, name, note, err = splitSubmitArgs([]string{"pdf-tools", "--name", "名称"})
	if err != nil || target != "" || len(dirArgs) != 1 || name != "名称" || note != "" {
		t.Fatalf("without --to: dirArgs = %v, target = %q, name = %q, note = %q, err = %v",
			dirArgs, target, name, note, err)
	}

	// **A missing name is legal since v9** (ADR 0069): a downloaded package carries the version's
	// name, and only the server can read it — so the CLI sends no part and lets the package answer.
	// A version pin on `--to` is refused rather than dropped: the version is what this call produces.
	dirArgs, target, name, note, err = splitSubmitArgs([]string{"pdf-tools"})
	if err != nil || target != "" || len(dirArgs) != 1 || name != "" || note != "" {
		t.Fatalf("without --name: dirArgs = %v, target = %q, name = %q, note = %q, err = %v",
			dirArgs, target, name, note, err)
	}
	dirArgs, target, name, note, err = splitSubmitArgs([]string{"pdf-tools", "--name", ""})
	if err != nil || target != "" || len(dirArgs) != 1 || name != "" || note != "" {
		t.Fatalf("with an empty --name: dirArgs = %v, target = %q, name = %q, note = %q, err = %v",
			dirArgs, target, name, note, err)
	}

	for _, args := range [][]string{
		{"pdf-tools", "--name"},
		{"pdf-tools", "--name", "名称", "--to"},
		{"pdf-tools", "--name", "名称", "--to", "lark"},
		{"pdf-tools", "--name", "名称", "--to", "lark/pdf-tools@1.2.3"},
		{"pdf-tools", "--name", "名称", "--to", "a/b", "--to", "c/d"},
		{"pdf-tools", "--name", "a", "--name", "b"},
	} {
		if _, _, _, _, err := splitSubmitArgs(args); err == nil {
			t.Fatalf("%v was accepted", args)
		}
	}
}

// A submission's printed address follows the identity the row has: the bare row id while it is a
// draft, and the number-or-digest spelling once the row already exists as a published version.
func TestSubmitPinUsesTheDraftAddress(t *testing.T) {
	draftID := "01J0DRAFT"
	var draft api.SubmitResult
	draft.Version.DraftID = &draftID
	draft.Version.Digest = "sha256:abc"
	if got := submitPin(draft); got != "01J0DRAFT" {
		t.Errorf("a draft was addressed as %q", got)
	}

	var live api.SubmitResult
	live.Version.Name = "1.2.3"
	live.Version.Digest = "sha256:abc"
	if got := submitPin(live); got != "1.2.3" {
		t.Errorf("a published version was addressed as %q", got)
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
// captureStderr is `captureStdout` for the other stream, which is where this CLI prints help: the
// convention is that usage goes to stderr (so a script's stdout stays parseable) and that asking for
// it is not an error.
func captureStderr(t *testing.T, print func()) string {
	t.Helper()
	read, write, err := os.Pipe()
	if err != nil {
		t.Fatalf("pipe: %v", err)
	}
	original := os.Stderr
	os.Stderr = write
	// **And restored on the way out however `print` ends.** A `t.Fatalf` or a panic inside it leaves
	// through a path that skips the straight-line restore below, and the stream would stay on a closed
	// pipe for the rest of the package — swallowing the output of every later test, at exactly the
	// moment somebody is reading that output to find out what failed.
	defer func() {
		_ = write.Close()
		os.Stderr = original
	}()

	drained := make(chan string, 1)
	go func() {
		out, _ := io.ReadAll(read)
		drained <- string(out)
	}()

	print()
	_ = write.Close()
	os.Stderr = original

	out := <-drained
	_ = read.Close()
	return out
}

func captureStdout(t *testing.T, print func()) string {
	t.Helper()
	read, write, err := os.Pipe()
	if err != nil {
		t.Fatalf("pipe: %v", err)
	}
	original := os.Stdout
	os.Stdout = write
	defer func() { // restored however `print` ends — see captureStderr
		_ = write.Close()
		os.Stdout = original
	}()

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

// The `skill` and `skills` groups, and the singular/plural rule between them.
//
// **Both halves matter and for different reasons.** Each group has to name its own verbs when
// somebody gets one wrong. The old collection verbs are not aliases: the product has not shipped,
// so `skill list` / `skill search` / `skill ls` are unknown commands rather than a compatibility
// layer with a stderr hint (ADR 0077).
func TestTheSkillAndSkillsGroupsOwnTheirVerbs(t *testing.T) {
	single := []string{"invoke", "files", "read", "versions", "submit", "share"}
	for _, verb := range single {
		if !strings.Contains(skillUsage, "\n  "+verb+" ") {
			t.Errorf("skill help does not list %s", verb)
		}
	}
	for _, verb := range []string{"list", "search"} {
		if strings.Contains(skillUsage, "\n  "+verb+" ") {
			t.Errorf("skill help still advertises the collection verb %s", verb)
		}
		if !strings.Contains(skillsUsage, "\n  "+verb+" ") {
			t.Errorf("skills help does not list %s", verb)
		}
	}

	for _, args := range [][]string{nil, {"lst"}, {"Get"}} {
		err := skillVerb(context.Background(), args)
		if err == nil {
			t.Fatalf("skill %v was accepted", args)
		}
		for _, verb := range single {
			if !strings.Contains(err.Error(), verb) {
				t.Errorf("skill %v does not name %q:\n%v", args, verb, err)
			}
		}
		if strings.Contains(err.Error(), "\n  list ") {
			t.Errorf("skill %v still advertises list:\n%v", args, err)
		}
	}
	for _, args := range [][]string{nil, {"lst"}} {
		err := skillsVerb(context.Background(), args)
		if err == nil {
			t.Fatalf("skills %v was accepted", args)
		}
		for _, verb := range []string{"list", "search"} {
			if !strings.Contains(err.Error(), verb) {
				t.Errorf("skills %v does not name %q:\n%v", args, verb, err)
			}
		}
	}

	// `help` under either group succeeds and prints rather than failing: asking a group what it
	// holds is not a mistake.
	for _, group := range []func(context.Context, []string) error{skillVerb, skillsVerb} {
		if err := group(context.Background(), []string{"help"}); err != nil {
			t.Errorf("help: %v", err)
		}
	}

	// The old collection verbs are not aliases: each is an unknown command under `skill`, and
	// nothing reaches the new group. Asserted through `run` and `reportError`, because the refusal
	// is a process result — stdout empty, a sentence on stderr, a non-zero status — not just an
	// error value returned to the caller.
	for _, verb := range []string{"list", "search", "ls"} {
		var err error
		var status int
		var stderr string
		stdout := captureStdout(t, func() {
			stderr = captureStderr(t, func() {
				err = run(context.Background(), []string{"skill", verb, "dev/pdf-tools"})
				if err != nil {
					status = reportError(err)
				}
			})
		})
		if err == nil || status == 0 {
			t.Errorf("skill %s was not refused: err=%v status=%d", verb, err, status)
		}
		if stdout != "" {
			t.Errorf("skill %s wrote to stdout:\n%s", verb, stdout)
		}
		if !strings.Contains(stderr, "未知的 skill 子命令") {
			t.Errorf("skill %s did not say it was unknown:\n%s", verb, stderr)
		}
	}

	for _, verb := range []string{"list", "search", "invoke"} {
		if err := run(context.Background(), []string{verb}); err == nil {
			t.Errorf("%q still works at the top level", verb)
		}
	}
	if err := run(context.Background(), []string{"skill"}); err == nil {
		t.Error("`skill` with no verb was accepted")
	}
	if err := run(context.Background(), []string{"skills"}); err == nil {
		t.Error("`skills` with no verb was accepted")
	}
}

// A refusal names an argument without repeating it, when repeating it would repeat a credential.
//
// **`skillmaster --with-key=skm_…` is the spelling somebody reaches when they know the flag and not
// the level it lives at**, and it is one of four places that quoted the whole argument back: the
// top-level default, the `skill` group's, `auth`'s, and any flag parser's. The secret then sat in the
// terminal and in whatever collected the output — the same `%q` the `login` guard was fixed for, one
// level up. A bare key gets a pointer to where it belongs instead, and an ordinary typo is still
// quoted, because that word is what the person has to change.
func TestARefusalDoesNotRepeatAKey(t *testing.T) {
	key := aKey("top-level")
	// **A credential has to be present for the address rows to mean anything.** Without one they stop
	// at 「本机没有凭据」 — `session` refuses before the command ever parses its argument — and an
	// assertion about the message passes without the address grammar being reached at all. That is how
	// the first version of this guard looked complete while `invoke`, `read`, `files` and `versions`
	// still quoted the key back: only `share` and `submit --to` had been given the check.
	t.Setenv(config.ApiKeyEnv, key)
	// Nothing here may reach a real server: a row that stops refusing this early must fail immediately
	// and locally rather than making a request to whatever listens on the default address.
	t.Setenv(config.ServerEnv, "http://127.0.0.1:1")

	// `byAddress` is what says *why* a row must fail: 「failed, and did not quote the key」 is also true
	// of a command that never got as far as parsing its argument.
	cases := []struct {
		args      []string
		byAddress bool
	}{
		{[]string{"--with-key=" + key}, false},
		{[]string{key}, false},
		{[]string{"skill", key}, false},
		{[]string{"auth", key}, false},
		{[]string{"skills", "list", "--api-key=" + key}, false},
		// **Where an address goes is the likeliest place for this paste**: `skill invoke` is the
		// command a person reaches for with the credential in hand.
		{[]string{"skill", "invoke", key}, true},
		{[]string{"skill", "read", key, "SKILL.md"}, true},
		{[]string{"skill", "files", key}, true},
		{[]string{"skill", "versions", key}, true},
		{[]string{"skill", "share", key, "--to", "somebody"}, true},
		{[]string{"skill", "submit", "--to", key}, true},
	}
	for _, c := range cases {
		var err error
		_ = captureStdout(t, func() { err = run(context.Background(), c.args) })
		if err == nil {
			t.Fatalf("run%v was accepted", c.args)
		}
		if strings.Contains(err.Error(), key) {
			t.Fatalf("run%v repeated what it was given: %q", c.args, err)
		}
		if c.byAddress && !strings.Contains(err.Error(), "地址要写成") {
			t.Fatalf("run%v did not fail on the address: %q", c.args, err)
		}
	}

	// The typo still gets named: this is the half that would break if the guard were "quote nothing".
	var typo error
	_ = captureStdout(t, func() { typo = run(context.Background(), []string{"lst"}) })
	if typo == nil || !strings.Contains(typo.Error(), `"lst"`) {
		t.Fatalf("run lst said %v, want the word it did not know", typo)
	}
}

// **`invoke` and `read` must work at the 「调用」 rung, so neither may read the manifest**
// (technical-design §P2). The manifest is a `browse` question; these two resolve the version from
// `/metadata` and then ask for the bytes at it. This pins the path itself rather than the client
// methods one by one — a command that went back to `Detail` would fail here, not merely elsewhere.
func TestInvokeAndReadNeverFetchTheManifest(t *testing.T) {
	var paths []string
	var pins []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.Path)
		pins = append(pins, r.Header.Get(api.VersionHeader))
		switch {
		case strings.HasSuffix(r.URL.Path, "/metadata"):
			_, _ = w.Write([]byte(`{"name":"pdf-tools","title":"PDF Tools","namespace":{"slug":"demo"},
				"version":{"name":"1.2.3","digest":"sha256:abc"}}`))
		case strings.HasSuffix(r.URL.Path, "/body"):
			_, _ = w.Write([]byte("the body"))
		case strings.HasSuffix(r.URL.Path, "/files/references/x.md"):
			_, _ = w.Write([]byte("the file"))
		default:
			t.Errorf("a manifest-shaped request went out: %s", r.URL.Path)
			w.WriteHeader(http.StatusForbidden)
		}
	}))
	defer server.Close()

	client := api.Client{BaseURL: server.URL}
	body, err := fetchBody(context.Background(), client, "demo/pdf-tools", false)
	if err != nil {
		t.Fatalf("fetchBody: %v", err)
	}
	if string(body.content) != "the body" {
		t.Errorf("body = %q", body.content)
	}
	file, err := fetchFile(context.Background(), client, "demo/pdf-tools", "references/x.md", "")
	if err != nil {
		t.Fatalf("fetchFile: %v", err)
	}
	if string(file.content) != "the file" {
		t.Errorf("file = %q", file.content)
	}

	want := []string{
		"/api/v1/skills/demo/pdf-tools/metadata",
		"/api/v1/skills/demo/pdf-tools/body",
		"/api/v1/skills/demo/pdf-tools/metadata",
		"/api/v1/skills/demo/pdf-tools/files/references/x.md",
	}
	if !reflect.DeepEqual(paths, want) {
		t.Fatalf("paths = %v, want %v", paths, want)
	}
	// The first request resolves `latest`; the second is pinned to what it resolved.
	if pins[0] != "" || pins[1] != "1.2.3" || pins[2] != "" || pins[3] != "1.2.3" {
		t.Errorf("version headers = %v", pins)
	}
}

// helpVerb is a line in a group's help table: two spaces, then one or two lower-case verb names.
// Continuation lines are indented further and descriptions are not ASCII verbs, so neither parses.
var helpVerb = regexp.MustCompile(`^[a-z]+(?:\|[a-z]+)?$`)

// groupHelpVerbs is the set of verbs a group's help table advertises, `help` included.
func groupHelpVerbs(t *testing.T, text string) map[string]bool {
	t.Helper()
	verbs := map[string]bool{}
	for _, line := range strings.Split(text, "\n") {
		if len(line) < 3 || line[0] != ' ' || line[1] != ' ' || line[2] == ' ' {
			continue
		}
		token := strings.TrimSuffix(strings.Fields(strings.TrimSpace(line))[0], ",")
		if !helpVerb.MatchString(token) {
			continue
		}
		for _, verb := range strings.Split(token, "|") {
			verbs[verb] = true
		}
	}
	return verbs
}

// topLevelHelpCommands is the set of `skillmaster <name>` entries in the top-level help.
func topLevelHelpCommands(text string) map[string]bool {
	commands := map[string]bool{}
	for _, line := range strings.Split(text, "\n") {
		if !strings.HasPrefix(line, "  skillmaster ") {
			continue
		}
		fields := strings.Fields(strings.TrimSpace(line))
		if len(fields) >= 2 {
			commands[fields[1]] = true
		}
	}
	return commands
}

func TestTopLevelHelpListsEightCommands(t *testing.T) {
	want := map[string]bool{
		"login": true, "logout": true, "auth": true, "setup": true,
		"skills": true, "skill": true, "version": true, "help": true,
	}

	var err error
	var out string
	stdout := captureStdout(t, func() {
		out = captureStderr(t, func() {
			err = run(context.Background(), []string{"help"})
		})
	})
	if err != nil {
		t.Fatalf("help: %v", err)
	}
	if stdout != "" {
		t.Errorf("help wrote to stdout:\n%s", stdout)
	}
	if got := topLevelHelpCommands(out); !reflect.DeepEqual(got, want) {
		t.Errorf("top-level commands = %v, want exactly %v:\n%s", got, want, out)
	}
	for _, old := range []string{"skill list", "skill search", "skill ls"} {
		if strings.Contains(out, old) {
			t.Errorf("top-level help still teaches %q:\n%s", old, out)
		}
	}
}

func TestSkillHelpHasTheTwentySingleSkillVerbs(t *testing.T) {
	want := map[string]bool{}
	for _, verb := range []string{"invoke", "files", "read", "cat", "wc", "grep", "write", "edit",
		"rm", "mv", "cp", "download", "versions", "submit", "share", "shares", "unshare", "request",
		"create", "open"} {
		want[verb] = true
	}
	want["help"] = true

	if got := groupHelpVerbs(t, skillUsage); !reflect.DeepEqual(got, want) {
		t.Errorf("skill verbs = %v, want exactly %v:\n%s", got, want, skillUsage)
	}
	for _, notAVerb := range []string{"list", "search", "ls"} {
		if strings.Contains(skillUsage, "\n  "+notAVerb+" ") {
			t.Errorf("skill help still lists %q", notAVerb)
		}
	}
	if !strings.Contains(skillUsage, "skillmaster version list") {
		t.Error("skill help does not point at the author-plane version list")
	}

	if got, want := groupHelpVerbs(t, skillsUsage),
		map[string]bool{"list": true, "search": true, "help": true}; !reflect.DeepEqual(got, want) {
		t.Errorf("skills verbs = %v, want exactly %v:\n%s", got, want, skillsUsage)
	}
}

// The body `setup` writes is the repository's gateway file, byte for byte, with only the deployment
// address filled in. Serving the file itself is the point: a fixture would keep passing after the
// real gateway drifted, which is the failure this test exists to catch (PRD 验收 #9).
func TestSetupInstallsTheGatewayFileTheRepositoryShips(t *testing.T) {
	source, err := os.ReadFile(filepath.Join("..", "..", "..", "gateway", "skillmaster", "SKILL.md"))
	if err != nil {
		t.Fatalf("read the gateway source: %v", err)
	}
	if !strings.Contains(string(source), `platform_api_version: "9"`) {
		t.Fatalf("the gateway source does not declare platform_api_version 9:\n%s", source)
	}
	if !strings.Contains(string(source), "https://<host>") {
		t.Fatal("the gateway source has no deployment-address placeholder")
	}
	served := strings.ReplaceAll(string(source), "https://<host>", "https://skills.example.test")

	_, skillsDir := serveGateway(t, served)
	if err := setup(context.Background(), nil); err != nil {
		t.Fatalf("setup: %v", err)
	}
	installed, err := os.ReadFile(filepath.Join(skillsDir, "skillmaster", "SKILL.md"))
	if err != nil {
		t.Fatalf("read the installed gateway: %v", err)
	}
	if string(installed) != served {
		t.Errorf("installed body differs from the served gateway file:\n--- got ---\n%s\n--- want ---\n%s",
			installed, served)
	}
}

func TestSetupReportsAPlatformVersionChange(t *testing.T) {
	const eight = "---\nname: skillmaster\ndescription: x\nmetadata:\n  platform_api_version: \"8\"\n---\n# old\n"
	const nine = "---\nname: skillmaster\ndescription: x\nmetadata:\n  platform_api_version: \"9\"\n---\n# new\n"
	_, skillsDir := serveGateway(t, nine)
	dir := filepath.Join(skillsDir, "skillmaster")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "SKILL.md"), []byte(eight), 0o644); err != nil {
		t.Fatal(err)
	}

	out := captureStdout(t, func() {
		if err := setup(context.Background(), nil); err != nil {
			t.Fatalf("setup: %v", err)
		}
	})
	if !strings.Contains(out, "8") || !strings.Contains(out, "9") {
		t.Errorf("setup did not report the 8→9 change:\n%s", out)
	}
	installed, err := os.ReadFile(filepath.Join(dir, "SKILL.md"))
	if err != nil {
		t.Fatal(err)
	}
	if string(installed) != nine {
		t.Errorf("installed body = %q, want the 9 body", installed)
	}
}

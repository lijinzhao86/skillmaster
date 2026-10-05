package main

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// The three outcomes of a publish, which the server reports as two fields and the person reads as
// one sentence.
//
// The middle row is the reason this is a table: a changed skill re-published answers with
// `created: true`, because *this call created a version*. Reading that as "the skill was created"
// is what printed "已创建" at somebody who had just updated a skill that already existed — a mistake
// no single-case test would have caught.
func TestPublishVerbNamesWhatHappened(t *testing.T) {
	cases := []struct {
		name    string
		created bool
		version int
		want    string
	}{
		{"a skill published for the first time", true, 1, "已创建"},
		{"a new version of a skill that already existed", true, 2, "已更新"},
		{"identical content, which writes nothing (ADR 0005)", false, 2, "内容未变"},
	}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			var result api.PublishResult
			result.Created = test.created
			result.Version.Number = test.version

			if got := publishVerb(result); got != test.want {
				t.Fatalf("publishVerb = %q, want %q", got, test.want)
			}
		})
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

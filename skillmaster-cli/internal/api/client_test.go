package api

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSkillPathEscapesBothSegmentsAndKeepsThePin(t *testing.T) {
	cases := []struct {
		address string
		want    string
		wantErr bool
	}{
		// `@` and `:` are legal in a path segment, so the pin travels inside the name exactly as the
		// server's own advertised addresses do.
		{"demo/hello", "/api/v1/skills/demo/hello", false},
		{"demo/hello@1.2.3", "/api/v1/skills/demo/hello@1.2.3", false},
		{"demo/hello@sha256:abc123", "/api/v1/skills/demo/hello@sha256:abc123", false},
		// A space would make the URL invalid; a non-ASCII name is permitted by M5.
		{"demo a/hello b", "/api/v1/skills/demo%20a/hello%20b", false},
		{"演示/技能", "/api/v1/skills/%E6%BC%94%E7%A4%BA/%E6%8A%80%E8%83%BD", false},
		{"no-slash", "", true},
		{"/hello", "", true},
		{"demo/", "", true},
		{"demo/a/b", "", true},
	}
	for _, c := range cases {
		got, err := SkillPath(c.address)
		if c.wantErr {
			if err == nil {
				t.Errorf("%q was accepted as %q", c.address, got)
			}
			continue
		}
		if err != nil {
			t.Errorf("%q: %v", c.address, err)
			continue
		}
		if got != c.want {
			t.Errorf("%q -> %q, want %q", c.address, got, c.want)
		}
	}
}

func TestSearchReadsTheFieldTheServerActuallySends(t *testing.T) {
	var askedPath, askedAuth string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		askedPath, askedAuth = r.URL.RequestURI(), r.Header.Get("Authorization")
		// The server's field is `skills`. A client that read `items` would print "没有匹配的 skill"
		// for every query and look like a search that found nothing.
		_, _ = w.Write([]byte(`{"skills":[{"id":"1","name":"hello","title":"Hello",
			"description":"a skill","namespace":"demo","visibility":"public",
			"version":{"name":"1.2.3","digest":"sha256:abc"},
			"updated_at":"2026-01-01T00:00:00Z"}],"next_cursor":"more"}`))
	}))
	defer server.Close()

	page, err := Client{BaseURL: server.URL, Token: "a-token"}.Search(context.Background(), "hello world")
	if err != nil {
		t.Fatalf("Search: %v", err)
	}

	if askedPath != "/api/v1/skills?q=hello+world" {
		t.Errorf("the request was %q", askedPath)
	}
	if askedAuth != "Bearer a-token" {
		t.Errorf("Authorization = %q", askedAuth)
	}
	if len(page.Skills) != 1 {
		t.Fatalf("decoded %d cards from one", len(page.Skills))
	}
	// **A bare address, version and all — and the fixture above still sends one on purpose.** A card
	// used to carry the version so that copying the printed address was what kept a caller on the
	// version the listing was about; since ADR 0035 `invoke` resolves and remembers that, so the
	// address is the name alone. Leaving the field in this server's answer is the point: a client that
	// started appending it again would still pass a test written against a server that had stopped.
	if got := page.Skills[0].Address(); got != "demo/hello" {
		t.Errorf("address = %q", got)
	}
	if page.NextCursor != "more" {
		t.Errorf("next_cursor = %q — the CLI has to know there is another page", page.NextCursor)
	}
}

// The listing asks for the server's own maximum, passes the cursor back exactly as it came, and
// sends the namespace filter as **repeated parameters** — the shape the endpoint reads. Joining them
// with a comma on this side would be a second grammar for one thing.
//
// 100 rather than something smaller because it is the bound the server enforces (`MAX_LIMIT`): a
// smaller page would mean more requests for the same listing and nothing else, and a larger one
// would be silently clamped.
func TestListAsksForOneFullPageAndCarriesTheCursor(t *testing.T) {
	var asked []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		asked = append(asked, r.URL.RequestURI())
		_, _ = w.Write([]byte(`{"skills":[],"next_cursor":"b2Zmc2V0"}`))
	}))
	defer server.Close()

	client := Client{BaseURL: server.URL, Token: "a-token"}

	if _, err := client.List(context.Background(), nil, ""); err != nil {
		t.Fatalf("List: %v", err)
	}
	// No namespace filter: the listing's scope is the endpoint's own rule — what the caller owns plus
	// what has been shared with them (ADR 0034) — and there is no parameter here that could widen it.
	// Nothing but the limit is sent, so `Search`'s `q` cannot leak in either.
	if want := "/api/v1/skills?limit=100"; asked[0] != want {
		t.Errorf("first request was %q, want %q", asked[0], want)
	}

	if _, err := client.List(context.Background(), nil, "b2Zmc2V0"); err != nil {
		t.Fatalf("List with a cursor: %v", err)
	}
	if want := "/api/v1/skills?cursor=b2Zmc2V0&limit=100"; asked[1] != want {
		t.Errorf("second request was %q, want %q", asked[1], want)
	}

	// Two namespaces, in the order given, as two parameters. `url.Values.Encode` sorts them for the
	// wire, which is why this reads alphabetically rather than in the order they were passed.
	if _, err := client.List(context.Background(), []string{"mine", "lark"}, ""); err != nil {
		t.Fatalf("List with two namespaces: %v", err)
	}
	if want := "/api/v1/skills?limit=100&namespace=mine&namespace=lark"; asked[2] != want {
		t.Errorf("third request was %q, want %q", asked[2], want)
	}
}

// Share sends the handle and the role as a JSON body to the skill's grants route, and reads back the
// handle the server resolved.
func TestSharePostsTheGrantAndReturnsTheResolvedHandle(t *testing.T) {
	var body map[string]string
	var path, method string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path, method = r.URL.Path, r.Method
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Errorf("the body was not JSON: %v", err)
		}
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"handle":"bob","role":"viewer","removed":false}`))
	}))
	defer server.Close()

	result, err := Client{BaseURL: server.URL}.Share(context.Background(), "demo/hello", "bob", RoleViewer)
	if err != nil {
		t.Fatalf("Share: %v", err)
	}
	if method != http.MethodPost || path != "/api/v1/skills/demo/hello/grants" {
		t.Errorf("%s %s", method, path)
	}
	if body["handle"] != "bob" || body["role"] != RoleViewer {
		t.Errorf("body = %v", body)
	}
	if result.Handle != "bob" || result.Role != RoleViewer {
		t.Errorf("result = %+v", result)
	}
}

// The 403 is the system's only one, and it means something narrower than the word usually does: the
// caller can read this skill and may not administer it. The server's sentence is the one that names
// which level they are missing, so it has to survive into the CLI's message rather than being
// replaced by a status code.
func TestAShareTheCallerMayNotMakeKeepsTheServersSentence(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte(`{"error":{"code":"forbidden",
			"message":"sharing 'hello' is its owner's to do"}}`))
	}))
	defer server.Close()

	_, err := Client{BaseURL: server.URL}.Share(context.Background(), "demo/hello", "bob", RoleViewer)
	if err == nil {
		t.Fatal("a 403 was accepted")
	}
	if !strings.Contains(err.Error(), "sharing 'hello' is its owner's to do") {
		t.Errorf("the server's sentence is missing from %q", err)
	}
	// Not an authentication problem: signing in again yields the same scopes, so a caller sent round
	// that loop would never come out of it. See ErrUnauthorized.
	if errors.Is(err, ErrUnauthorized) {
		t.Errorf("a 403 was reported as a credential problem: %v", err)
	}
}

func TestDetailDecodesTheManifestAndItsPinnedURIs(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/skills/demo/hello" {
			t.Errorf("the request path was %q", r.URL.Path)
		}
		_, _ = w.Write([]byte(`{
			"id":"1","name":"hello","title":"Hello","description":"d",
			"namespace":{"slug":"demo","title":"Demo"},"visibility":"public",
			"version":{"name":"1.2.3","digest":"sha256:abc","published_at":"2026-01-01T00:00:00Z",
			           "file_count":2,"total_bytes":100,"is_latest":true},
			"files":[{"relpath":"references/a.md",
			          "uri":"/api/v1/skills/demo/hello@1.2.3/files/references/a.md",
			          "sha256":"x","size":10,"is_binary":false}],
			"resources":{"body":"/api/v1/skills/demo/hello@1.2.3/body"}}`))
	}))
	defer server.Close()

	detail, err := Client{BaseURL: server.URL, Token: "t"}.Detail(context.Background(), "demo/hello", "")
	if err != nil {
		t.Fatalf("Detail: %v", err)
	}

	if detail.Version.Name != "1.2.3" || !detail.Version.IsLatest || detail.Version.FileCount != 2 {
		t.Fatalf("version decoded as %+v", detail.Version)
	}
	if detail.Address() != "demo/hello@1.2.3" {
		t.Errorf("address = %q", detail.Address())
	}
	// The URIs carry the version, which is the whole pinning mechanism: following one cannot land
	// on a different version than the manifest describes.
	if !strings.Contains(detail.Resources.Body, "@1.2.3") {
		t.Errorf("the body URI is not pinned: %q", detail.Resources.Body)
	}
	if got := detail.Files[0].URI; !strings.Contains(got, "@1.2.3/files/") {
		t.Errorf("the file URI is not pinned: %q", got)
	}
}

// **The pin travels as a header, and this is the assertion the whole design rests on** (ADR 0035).
//
// `read` gets the version from a file on this machine and has to put it on the request without putting
// it in the command, so the header is the only place the two commands' agreement becomes real. A test
// that only checked the output would pass with the header missing entirely — and the read would then
// quietly answer about whatever is current, which is the drift the pin exists to prevent.
func TestDetailPutsTheVersionOnTheRequestAndNotInTheAddress(t *testing.T) {
	for _, c := range []struct {
		name     string
		pin      string
		wantPath string
		wantHead string
	}{
		{
			name:     "a pinned read sends the header and leaves the path bare",
			pin:      "1.2.3",
			wantPath: "/api/v1/skills/demo/hello",
			wantHead: "1.2.3",
		},
		{
			name:     "an unpinned read sends no header at all rather than an empty one",
			pin:      "",
			wantPath: "/api/v1/skills/demo/hello",
			wantHead: "",
		},
		{
			name:     "a nameless version's digest is a pin like any other",
			pin:      "sha256:abc",
			wantPath: "/api/v1/skills/demo/hello",
			wantHead: "sha256:abc",
		},
	} {
		t.Run(c.name, func(t *testing.T) {
			var askedPath, askedHeader string
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				askedPath, askedHeader = r.URL.Path, r.Header.Get(VersionHeader)
				_, _ = w.Write([]byte(`{"name":"hello","version":{"name":"1.2.3"}}`))
			}))
			defer server.Close()

			client := Client{BaseURL: server.URL, Token: "t"}
			if _, err := client.Detail(context.Background(), "demo/hello", c.pin); err != nil {
				t.Fatalf("Detail: %v", err)
			}
			if askedPath != c.wantPath {
				t.Errorf("the path was %q — the version does not belong in the address", askedPath)
			}
			if askedHeader != c.wantHead {
				t.Errorf("%s = %q, want %q", VersionHeader, askedHeader, c.wantHead)
			}
		})
	}
}

// The version list, which is the one command that can print a pin.
func TestReadVersionsAsksTheVersionsRouteAndDecodesIt(t *testing.T) {
	var askedPath, askedHeader string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		askedPath, askedHeader = r.URL.Path, r.Header.Get(VersionHeader)
		_, _ = w.Write([]byte(`{"versions":[
			{"name":"2.0.0","digest":"sha256:def","published_at":"2026-10-06T10:00:00Z","is_current":true},
			{"name":null,"digest":"sha256:abc","published_at":"2026-10-01T10:00:00Z","is_current":false}]}`))
	}))
	defer server.Close()

	listed, err := Client{BaseURL: server.URL, Token: "t"}.ReadVersions(context.Background(), "demo/hello")
	if err != nil {
		t.Fatalf("ReadVersions: %v", err)
	}

	if askedPath != "/api/v1/skills/demo/hello/versions" {
		t.Errorf("the request was %q", askedPath)
	}
	// No pin: no version is being selected, and the server refuses the header here rather than
	// ignoring it (§4.2) — so sending one would turn a listing into a 400.
	if askedHeader != "" {
		t.Errorf("a pin was sent to the version list: %q", askedHeader)
	}
	if len(listed) != 2 {
		t.Fatalf("decoded %d versions from two", len(listed))
	}
	// Order is the server's (newest submission first) and is not re-sorted here: what an author just
	// did is the question, and it is not the same as what the version names sort to.
	if listed[0].Name != "2.0.0" || !listed[0].IsCurrent || listed[0].PublishedAt == "" {
		t.Errorf("the first version decoded as %+v", listed[0])
	}
	if listed[1].Name != "" || listed[1].Digest != "sha256:abc" || listed[1].IsCurrent {
		t.Errorf("the nameless superseded version decoded as %+v", listed[1])
	}
}

// An address is named by the author's semver when they declared one, and by the digest when they did
// not (ADR 0033).
//
// Both halves matter: a version with no name is a legal submission and not a broken one, and it is
// still addressable — by the digest, which is the identity the name was only ever an alias for.
func TestAnAddressNamesAVersionByItsNameOrElseItsDigest(t *testing.T) {
	named := Detail{Name: "hello"}
	named.Namespace.Slug = "demo"
	named.Version.Name, named.Version.Digest = "1.2.3", "sha256:abc"

	nameless := Detail{Name: "hello"}
	nameless.Namespace.Slug = "demo"
	nameless.Version.Digest = "sha256:abc"

	if got := named.Address(); got != "demo/hello@1.2.3" {
		t.Errorf("a named version was addressed as %q", got)
	}
	// The digest is already prefixed on the wire, so the suffix is it verbatim — no second `sha256:`.
	if got := nameless.Address(); got != "demo/hello@sha256:abc" {
		t.Errorf("a nameless version was addressed as %q", got)
	}
}

func TestFetchUsesTheURIItWasGivenVerbatim(t *testing.T) {
	var asked string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		asked = r.URL.RequestURI()
		_, _ = w.Write([]byte("# the body\n"))
	}))
	defer server.Close()

	body, err := Client{BaseURL: server.URL, Token: "t"}.Fetch(context.Background(),
		"/api/v1/skills/demo/hello@1.2.3/body?x=1")
	if err != nil {
		t.Fatalf("Fetch: %v", err)
	}
	if asked != "/api/v1/skills/demo/hello@1.2.3/body?x=1" {
		t.Errorf("the URI was rewritten to %q; a client must follow what it was handed", asked)
	}
	if string(body) != "# the body\n" {
		t.Errorf("body = %q", body)
	}
}

func TestANotFoundIsReportedWithoutInventingWhichKindItWas(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer server.Close()

	_, err := Client{BaseURL: server.URL}.Detail(context.Background(), "demo/hello", "")
	if err == nil {
		t.Fatal("a 404 was reported as a success")
	}
	// The server answers 404 alike for "no such skill", "not yours" and "no such version" (§4.2),
	// and a client that named one of them would be undoing that on purpose.
	if strings.Contains(err.Error(), "不存在") && !strings.Contains(err.Error(), "不区分") {
		t.Errorf("the error claims to know which 404 it was: %v", err)
	}
	// The message stays the server's one answer; what is added is the ability to tell it apart. A
	// caller that pinned a version from its own records needs that and cannot get it from the text —
	// see `read`, which names the pin it failed on rather than leaving a puzzle.
	if !errors.Is(err, ErrNotFound) {
		t.Errorf("a 404 is not identifiable as one: %v", err)
	}
}

func TestArchiveCarriesTheTreeWithForwardSlashes(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "SKILL.md"), "# hello\n")
	write(t, filepath.Join(dir, "references", "a.md"), "a\n")
	write(t, filepath.Join(dir, "references", "b.md"), "b\n")

	archive, err := Archive(dir)
	if err != nil {
		t.Fatalf("Archive: %v", err)
	}

	names := entryNames(t, archive)
	want := []string{"SKILL.md", "references/a.md", "references/b.md"}
	if len(names) != len(want) {
		t.Fatalf("archive holds %v, want %v", names, want)
	}
	for i, name := range want {
		if names[i] != name {
			t.Errorf("entry %d is %q, want %q", i, names[i], name)
		}
	}

	// Same tree, same bytes — not required for correctness (the server's digest is over the file
	// set), but it makes two archives comparable when something does look wrong.
	again, err := Archive(dir)
	if err != nil {
		t.Fatalf("Archive again: %v", err)
	}
	if !bytes.Equal(archive, again) {
		t.Error("archiving the same tree twice produced different bytes")
	}
}

func TestArchiveRefusesWhatTheFormatCannotCarry(t *testing.T) {
	t.Run("no SKILL.md", func(t *testing.T) {
		dir := t.TempDir()
		write(t, filepath.Join(dir, "notes.md"), "x")
		// Courtesy only — the server refuses this too and is the authority (ADR 0011). This exists
		// so the answer arrives before a possibly large upload.
		if _, err := Archive(dir); err == nil {
			t.Fatal("a directory with no SKILL.md was archived")
		}
	})

	t.Run("a symlink", func(t *testing.T) {
		dir := t.TempDir()
		write(t, filepath.Join(dir, "SKILL.md"), "# hello\n")
		if err := os.Symlink("/etc/hosts", filepath.Join(dir, "escape")); err != nil {
			t.Skipf("cannot create a symlink here: %v", err)
		}
		if _, err := Archive(dir); err == nil {
			t.Fatal("a symlink was archived; it would upload as its target's contents")
		}
	})

	t.Run("a file where a directory was expected", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "SKILL.md")
		write(t, path, "x")
		if _, err := Archive(path); err == nil {
			t.Fatal("a file was archived as if it were a directory")
		}
	})

	t.Run("a directory reached through a symlink", func(t *testing.T) {
		// The root escaped the check above, because `WalkDir` Lstats its own root and does not descend
		// into a link — so the callback returned early and the archive came out **valid and empty**.
		// The server then answered that the upload contained no files, which blames the skill for a
		// link. `ln -s ~/dev/my-skill ~/.claude/skills/my-skill` is an ordinary way to work, so this
		// is a name a person types.
		dir := t.TempDir()
		real := filepath.Join(dir, "real")
		if err := os.MkdirAll(real, 0o755); err != nil {
			t.Fatal(err)
		}
		write(t, filepath.Join(real, "SKILL.md"), "# hello\n")
		link := filepath.Join(dir, "linked")
		if err := os.Symlink(real, link); err != nil {
			t.Skipf("cannot create a symlink here: %v", err)
		}

		archive, err := Archive(link)
		if err == nil {
			t.Fatalf("a symlinked directory was archived as %d bytes rather than refused", len(archive))
		}
	})

	t.Run("a directory reached through a symlink, spelled with a trailing separator", func(t *testing.T) {
		// `Lstat("link/")` resolves the final component — a trailing separator asks the kernel what
		// the *path* denotes rather than lstat-ing the entry itself — so the refusal above did not
		// fire for `submit link/` while it did for `submit link`. `Clean` makes the two spellings
		// take the same path. The per-file rule inside the walk has no such hole: it judges entries.
		dir := t.TempDir()
		real := filepath.Join(dir, "real")
		if err := os.MkdirAll(real, 0o755); err != nil {
			t.Fatal(err)
		}
		write(t, filepath.Join(real, "SKILL.md"), "# hello\n")
		link := filepath.Join(dir, "linked")
		if err := os.Symlink(real, link); err != nil {
			t.Skipf("cannot create a symlink here: %v", err)
		}

		archive, err := Archive(link + string(filepath.Separator))
		if err == nil {
			t.Fatalf("a symlinked directory with a trailing separator was archived as %d bytes", len(archive))
		}
	})
}

func TestSubmitSendsOneMultipartPartNamedFile(t *testing.T) {
	var (
		partName string
		payload  []byte
		auth     string
		path     string
	)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path, auth = r.URL.Path, r.Header.Get("Authorization")
		_, params, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
		if err != nil {
			t.Errorf("Content-Type: %v", err)
			return
		}
		reader := multipart.NewReader(r.Body, params["boundary"])
		part, err := reader.NextPart()
		if err != nil {
			t.Errorf("reading the part: %v", err)
			return
		}
		partName = part.FormName()
		payload, _ = io.ReadAll(part)
		w.WriteHeader(http.StatusCreated)
		// Every field the real answer carries, `state` included: it is what `stateLine` branches on,
		// and a mistyped tag would leave it as the empty string and quietly take the default branch —
		// which no other test here would notice.
		_, _ = w.Write([]byte(`{"id":"1","name":"hello","namespace":"demo","created":true,
			"skill_created":true,
			"version":{"name":"1.0.0","digest":"sha256:abc","file_count":1,"total_bytes":3,
			           "submitted_at":"2026-01-01T00:00:00Z","state":"draft"}}`))
	}))
	defer server.Close()

	result, err := Client{BaseURL: server.URL, Token: "t"}.Submit(context.Background(), []byte("zip-bytes"))
	if err != nil {
		t.Fatalf("Submit: %v", err)
	}

	if path != "/api/v1/skills" || auth != "Bearer t" {
		t.Errorf("posted to %q with %q", path, auth)
	}

	if partName != "file" {
		t.Errorf("the part is named %q; the endpoint reads one called `file`", partName)
	}
	if string(payload) != "zip-bytes" {
		t.Errorf("the part carried %q", payload)
	}
	// `created` and `skill_created` are separate fields and both must arrive: the second is what
	// separates a new skill from a new version of an old one, and nothing else can (ADR 0033).
	if !result.Created || !result.SkillCreated || result.Version.Name != "1.0.0" {
		t.Errorf("result = %+v", result)
	}
	if result.Version.State != StateDraft {
		t.Errorf("state = %q, want %q — the tag and the server's field must agree", result.Version.State, StateDraft)
	}
}

// `--to` is a different route, not a different parameter on the same one, and getting it wrong is
// silent in the worst way: posting to the create route with someone else's skill's content would
// create a skill of your own with that name rather than adding a version to theirs — a success that
// did something else.
func TestSubmitVersionPostsToTheVersionsRouteOfTheNamedSkill(t *testing.T) {
	var path string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// The escaped form, because that is what leaves this process: `URL.Path` is what the server
		// decoded it back to, and asserting on that would not notice an unescaped name.
		path = r.URL.EscapedPath()
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"id":"1","name":"hello","namespace":"other","created":true,
			"skill_created":false,
			"version":{"name":"1.0.0","digest":"sha256:abc","file_count":1,"total_bytes":3,
			           "submitted_at":"2026-01-01T00:00:00Z","state":"draft"}}`))
	}))
	defer server.Close()

	// A non-ASCII name, because M5 permits one and this path goes through the same `SkillPath` every
	// other address in this client does.
	_, err := Client{BaseURL: server.URL, Token: "t"}.
		SubmitVersion(context.Background(), "other/技能", []byte("zip"))
	if err != nil {
		t.Fatalf("SubmitVersion: %v", err)
	}
	if want := "/api/v1/skills/other/%E6%8A%80%E8%83%BD/versions"; path != want {
		t.Errorf("posted to %q, want %q", path, want)
	}
}

// A viewer's grant lets them read and not add, so this route is the one that answers 403 for a
// write. The message has to be the server's, because it is the server that knows which level they
// would need.
func TestVersionsRouteRefusesAReaderWithTheServersSentence(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte(`{"error":{"code":"permitted","message":"you need the editor role"}}`))
	}))
	defer server.Close()

	_, err := Client{BaseURL: server.URL}.SubmitVersion(context.Background(), "other/hello", []byte("z"))
	if err == nil {
		t.Fatal("a 403 was accepted")
	}
	if !strings.Contains(err.Error(), "you need the editor role") {
		t.Errorf("the server's sentence is missing from %q", err)
	}
}

// --- helpers ---

func write(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}

func entryNames(t *testing.T, archive []byte) []string {
	t.Helper()
	reader, err := zip.NewReader(bytes.NewReader(archive), int64(len(archive)))
	if err != nil {
		t.Fatalf("reading the archive: %v", err)
	}
	names := make([]string, 0, len(reader.File))
	for _, file := range reader.File {
		names = append(names, file.Name)
	}
	return names
}

// What the server says when it will not accept the token at all, and — just as important — when it
// accepts the token and refuses something else.
//
// The distinction is the whole reason ErrUnauthorized exists: a 401 is about the *credential* and the
// layer holding the store can act on it, while a 403 `insufficient_scope` is about the grant. Sending
// somebody to sign in again for the second one would be a loop that cannot help, because signing in
// produces the same scopes — consent is remembered per (client, account).
func TestOnlyARejectedTokenIsReportedAsSuch(t *testing.T) {
	cases := []struct {
		name   string
		status int
		body   string
		want   bool
	}{
		{"a token the server does not accept", http.StatusUnauthorized,
			`{"error":{"code":"unauthenticated","message":"The presented token was not accepted.","details":[]}}`, true},
		{"a token that is fine but too narrow", http.StatusForbidden,
			`{"error":{"code":"insufficient_scope","message":"The token lacks skills:write.","details":[]}}`, false},
		{"a server error", http.StatusInternalServerError, `{"error":{"code":"internal"}}`, false},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(c.status)
				_, _ = w.Write([]byte(c.body))
			}))
			defer server.Close()

			_, err := Client{BaseURL: server.URL, Token: "t"}.Search(context.Background(), "q")
			if err == nil {
				t.Fatal("a refused request reported success")
			}
			if got := errors.Is(err, ErrUnauthorized); got != c.want {
				t.Fatalf("errors.Is(err, ErrUnauthorized) = %v, want %v (err = %v)", got, c.want, err)
			}
			// Whatever the server said is kept: the code is what an operator greps for, and dropping
			// it would leave a message that cannot be matched against a log.
			if !strings.Contains(err.Error(), c.body) {
				t.Fatalf("the server's own words are missing from %q", err)
			}
		})
	}
}

func TestAnUploadRefusedForItsTokenIsReportedTheSameWay(t *testing.T) {
	// `submit` builds its own request, so it needs its own branch — and it is the one call where a
	// retry has to be known safe, which it is: a 401 is refused before anything is written.
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":{"code":"unauthenticated"}}`))
	}))
	defer server.Close()

	_, err := Client{BaseURL: server.URL, Token: "t"}.Submit(context.Background(), []byte("zip"))
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("err = %v, want ErrUnauthorized", err)
	}
}

// The one refusal whose answer is not "try again" and not "sign in again", but "edit your SKILL.md".
//
// A version name is immutable (ADR 0033), so submitting it a second time with different content is a
// mistake the author fixes at the source — and it is the mistake most easily taken for success,
// because from the command line everything looked like it worked. Saying only `服务端答 400` would
// send them hunting for a network problem.
func TestARefusedVersionNameSaysWhatToChange(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":{"code":"version_already_exists",` +
			`"message":"'pdf-tools' already has a version named 1.0.0 with different content",` +
			`"details":[]}}`))
	}))
	defer server.Close()

	_, err := Client{BaseURL: server.URL, Token: "t"}.Submit(context.Background(), []byte("zip"))
	if err == nil {
		t.Fatal("a refused submission reported success")
	}
	if !strings.Contains(err.Error(), "SKILL.md") {
		t.Errorf("the fix is not named in %q", err)
	}
	// The server's sentence is quoted rather than replaced: it names the version, and inventing a
	// second wording for somebody else's decision is how the two drift apart.
	if !strings.Contains(err.Error(), "already has a version named 1.0.0") {
		t.Errorf("the server's own words are missing from %q", err)
	}
}

// What a row says below its address: the description, and the author's `when_to_use` joined to it.
//
// The join is the L1's reading rule rather than a printer's detail — the same two fields make the
// same sentence wherever a listing is rendered — and Claude Code's own listing joins exactly these
// two. The two halves are independent, so the three cases where one of them is absent are the whole
// of what this can get wrong: an author may write neither, one, or both.
func TestASummaryJoinsTheDescriptionAndTheAuthorsWhenToUse(t *testing.T) {
	cases := []struct {
		description string
		whenToUse   string
		want        string
	}{
		{"拆开再拼起来。", "要动 PDF 的时候用。", "拆开再拼起来。 - 要动 PDF 的时候用。"},
		{"拆开再拼起来。", "", "拆开再拼起来。"},
		{"", "要动 PDF 的时候用。", "要动 PDF 的时候用。"},
		{"", "", ""},
	}
	for _, c := range cases {
		var card Card
		card.Description, card.WhenToUse = c.description, c.whenToUse
		if got := card.Summary(); got != c.want {
			t.Errorf("description %q + when_to_use %q → %q, want %q", c.description, c.whenToUse, got, c.want)
		}
	}
	// No dangling separator when the author declared no when_to_use, and no doubled one either.
	if got := (Card{Description: "只有描述"}).Summary(); got != "只有描述" {
		t.Errorf("a skill with no when_to_use read as %q", got)
	}
}

// A card no longer carries a title, and the absence is asserted rather than left to the JSON decoder:
// the field is gone from the server's record too, so a `title` in a response means somebody put it
// back — and the listing would print a skill's name twice for every author who declared none.
func TestACardHasNoTitle(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"skills":[{"id":"1","name":"hello","description":"d",
			"when_to_use":"w","namespace":"demo","version":{"name":"1.0.0","digest":"sha256:a"}}],
			"next_cursor":null}`))
	}))
	defer server.Close()

	page, err := Client{BaseURL: server.URL}.List(context.Background(), nil, "")
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if got := page.Skills[0].Summary(); got != "d - w" {
		t.Errorf("summary = %q", got)
	}
}

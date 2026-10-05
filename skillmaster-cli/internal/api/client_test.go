package api

import (
	"archive/zip"
	"bytes"
	"context"
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
		{"demo/hello@3", "/api/v1/skills/demo/hello@3", false},
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
	if got := page.Skills[0].Address(); got != "demo/hello" {
		t.Errorf("address = %q", got)
	}
	if page.NextCursor != "more" {
		t.Errorf("next_cursor = %q — the CLI has to know there is another page", page.NextCursor)
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
			"version":{"number":3,"digest":"sha256:abc","published_at":"2026-01-01T00:00:00Z",
			           "file_count":2,"total_bytes":100,"is_latest":true},
			"files":[{"relpath":"references/a.md",
			          "uri":"/api/v1/skills/demo/hello@3/files/references/a.md",
			          "sha256":"x","size":10,"is_binary":false}],
			"resources":{"body":"/api/v1/skills/demo/hello@3/body"}}`))
	}))
	defer server.Close()

	detail, err := Client{BaseURL: server.URL, Token: "t"}.Detail(context.Background(), "demo/hello")
	if err != nil {
		t.Fatalf("Detail: %v", err)
	}

	if detail.Version.Number != 3 || !detail.Version.IsLatest || detail.Version.FileCount != 2 {
		t.Fatalf("version decoded as %+v", detail.Version)
	}
	if detail.Address() != "demo/hello@3" {
		t.Errorf("address = %q", detail.Address())
	}
	// The URIs carry the version, which is the whole pinning mechanism: following one cannot land
	// on a different version than the manifest describes.
	if !strings.Contains(detail.Resources.Body, "@3") {
		t.Errorf("the body URI is not pinned: %q", detail.Resources.Body)
	}
	if got := detail.Files[0].URI; !strings.Contains(got, "@3/files/") {
		t.Errorf("the file URI is not pinned: %q", got)
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
		"/api/v1/skills/demo/hello@3/body?x=1")
	if err != nil {
		t.Fatalf("Fetch: %v", err)
	}
	if asked != "/api/v1/skills/demo/hello@3/body?x=1" {
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

	_, err := Client{BaseURL: server.URL}.Detail(context.Background(), "demo/hello")
	if err == nil {
		t.Fatal("a 404 was reported as a success")
	}
	// The server answers 404 alike for "no such skill", "not yours" and "no such version" (§4.2),
	// and a client that named one of them would be undoing that on purpose.
	if strings.Contains(err.Error(), "不存在") && !strings.Contains(err.Error(), "不区分") {
		t.Errorf("the error claims to know which 404 it was: %v", err)
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
}

func TestPublishSendsOneMultipartPartNamedFile(t *testing.T) {
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
		_, _ = w.Write([]byte(`{"id":"1","name":"hello","namespace":"demo","created":true,
			"version":{"number":1,"digest":"sha256:abc","file_count":1,"total_bytes":3,
			           "published_at":"2026-01-01T00:00:00Z"}}`))
	}))
	defer server.Close()

	result, err := Client{BaseURL: server.URL, Token: "t"}.Publish(context.Background(), []byte("zip-bytes"))
	if err != nil {
		t.Fatalf("Publish: %v", err)
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
	if !result.Created || result.Version.Number != 1 {
		t.Errorf("result = %+v", result)
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
	// `publish` builds its own request, so it needs its own branch — and it is the one call where a
	// retry has to be known safe, which it is: a 401 is refused before anything is written.
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":{"code":"unauthenticated"}}`))
	}))
	defer server.Close()

	_, err := Client{BaseURL: server.URL, Token: "t"}.Publish(context.Background(), []byte("zip"))
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("err = %v, want ErrUnauthorized", err)
	}
}

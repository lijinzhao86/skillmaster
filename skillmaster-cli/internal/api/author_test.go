package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// The author plane is a different path and its filters are the screen's: repeated `status`, `q`,
// the limit and the opaque cursor. This pins all four on one request.
func TestAuthorVersionsAsksTheAuthorPlaneWithEveryFilter(t *testing.T) {
	var askedPath, askedQuery, askedAuth string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		askedPath, askedQuery, askedAuth = r.URL.Path, r.URL.RawQuery, r.Header.Get("Authorization")
		_, _ = w.Write([]byte(`{
			"skill":{"namespace":"dev","name":"pdf-tools","address":"dev/pdf-tools"},
			"versions":[{"address":"dev/pdf-tools","pin":"draft:01J","name":"compress 补文档",
				"version":null,"status":"draft","note":"把 compress 的用法补进正文",
				"submitted_by":"yuhan","submitted_at":"2026-10-16T10:12:00Z",
				"first_published_at":null,"state_at":null,"revoked_reason":null,
				"digest":"sha256:abc","files":6,"bytes":18342}],
			"counts":{"all":2,"live":0,"draft":1,"history":0,"revoked":1,"discarded":0},
			"next":"c2"}`))
	}))
	defer server.Close()

	listed, err := Client{BaseURL: server.URL, Token: "t"}.AuthorVersions(context.Background(),
		"dev/pdf-tools", AuthorVersionQuery{
			Statuses: []string{"draft", "revoked"},
			Query:    "compress",
			Limit:    7,
			Cursor:   "c1",
		})
	if err != nil {
		t.Fatalf("AuthorVersions: %v", err)
	}

	if askedPath != "/api/v1/author/skills/dev/pdf-tools/versions" {
		t.Errorf("path = %q", askedPath)
	}
	if askedAuth != "Bearer t" {
		t.Errorf("Authorization = %q", askedAuth)
	}
	for _, want := range []string{"status=draft", "status=revoked", "q=compress", "limit=7", "cursor=c1"} {
		if !strings.Contains(askedQuery, want) {
			t.Errorf("query %q is missing %q", askedQuery, want)
		}
	}
	if len(listed.Versions) != 1 || listed.Versions[0].Pin != "draft:01J" {
		t.Fatalf("versions = %+v", listed.Versions)
	}
	if listed.Versions[0].Version != nil {
		t.Errorf("a draft decoded with a version number: %v", *listed.Versions[0].Version)
	}
	if listed.Counts.All != 2 || listed.Counts.Revoked != 1 {
		t.Errorf("counts = %+v", listed.Counts)
	}
	if listed.Next == nil || *listed.Next != "c2" {
		t.Errorf("next = %v", listed.Next)
	}
}

// A draft pin is a path segment, and the two pins this build sends — `draft:<id>` and `sha256:…` —
// both have to arrive intact.
func TestReadAuthorVersionUsesThePinAsAPathSegment(t *testing.T) {
	cases := []struct {
		pin      string
		wantPath string
	}{
		{"draft:01J0DRAFT", "/api/v1/author/skills/dev/pdf-tools/versions/draft:01J0DRAFT"},
		{"sha256:abc", "/api/v1/author/skills/dev/pdf-tools/versions/sha256:abc"},
		{"1.2.3", "/api/v1/author/skills/dev/pdf-tools/versions/1.2.3"},
	}
	for _, c := range cases {
		t.Run(c.pin, func(t *testing.T) {
			var asked string
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				asked = r.URL.Path
				_, _ = w.Write([]byte(`{"skill":{"namespace":"dev","name":"pdf-tools","address":"dev/pdf-tools"},
					"version":{"address":"dev/pdf-tools","pin":"` + c.pin + `","status":"draft"}}`))
			}))
			defer server.Close()

			if _, err := (Client{BaseURL: server.URL}).ReadAuthorVersion(context.Background(),
				"dev/pdf-tools", c.pin); err != nil {
				t.Fatalf("ReadAuthorVersion: %v", err)
			}
			if asked != c.wantPath {
				t.Errorf("path = %q, want %q", asked, c.wantPath)
			}
		})
	}
}

func TestAuthorRenameAndNotePatchTheVersionSubresource(t *testing.T) {
	cases := []struct {
		name    string
		field   string
		payload map[string]string
		call    func(Client) (AuthorWriteResult, error)
	}{
		{
			name:    "rename",
			field:   "name",
			payload: map[string]string{"name": "compress 用法补全"},
			call: func(client Client) (AuthorWriteResult, error) {
				return client.AuthorRename(context.Background(), "dev/pdf-tools", "draft:01J",
					"compress 用法补全")
			},
		},
		{
			name:    "note",
			field:   "note",
			payload: map[string]string{"note": "补了按页与按分隔符"},
			call: func(client Client) (AuthorWriteResult, error) {
				return client.AuthorNote(context.Background(), "dev/pdf-tools", "draft:01J",
					"补了按页与按分隔符")
			},
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			var method, path string
			var body map[string]string
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				method, path = r.Method, r.URL.Path
				_ = json.NewDecoder(r.Body).Decode(&body)
				_, _ = w.Write([]byte(`{"ok":true,"version":{"pin":"draft:01J","status":"draft"}}`))
			}))
			defer server.Close()

			result, err := c.call(Client{BaseURL: server.URL})
			if err != nil {
				t.Fatalf("%s: %v", c.name, err)
			}
			if method != http.MethodPatch {
				t.Errorf("%s used %s", c.name, method)
			}
			want := "/api/v1/author/skills/dev/pdf-tools/versions/draft:01J/" + c.field
			if path != want {
				t.Errorf("%s path = %q, want %q", c.name, path, want)
			}
			for key, value := range c.payload {
				if body[key] != value {
					t.Errorf("%s body = %v", c.name, body)
				}
			}
			if !result.Ok {
				t.Errorf("%s result = %+v", c.name, result)
			}
		})
	}
}

// A POST action puts the version pin in the body, the same convention publish and discard use.
func TestAuthorDiscardPostsThePinInTheBody(t *testing.T) {
	var method, path string
	var body map[string]string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		method, path = r.Method, r.URL.Path
		_ = json.NewDecoder(r.Body).Decode(&body)
		_, _ = w.Write([]byte(`{"ok":true,"version":{"pin":"draft:01J","status":"discarded"}}`))
	}))
	defer server.Close()

	result, err := Client{BaseURL: server.URL}.AuthorDiscard(context.Background(),
		"dev/pdf-tools", "draft:01J")
	if err != nil {
		t.Fatalf("AuthorDiscard: %v", err)
	}
	if method != http.MethodPost || path != "/api/v1/author/skills/dev/pdf-tools/versions/discard" {
		t.Errorf("%s %s", method, path)
	}
	if body["version"] != "draft:01J" {
		t.Errorf("body = %v", body)
	}
	if result.Version.Status != "discarded" {
		t.Errorf("result = %+v", result)
	}
}

// **`--patch` is one request for one file.** The `file=` parameter is what keeps it from becoming
// the first batch of a paginated walk, and the test server records the query so the assertion is
// about what actually left the process.
func TestAuthorDiffSendsFileAndDoesNotFollowNext(t *testing.T) {
	var askedPath, askedQuery string
	requests := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		askedPath, askedQuery = r.URL.Path, r.URL.RawQuery
		_, _ = w.Write([]byte(`{
			"from":"1.2.2","to":"1.2.3","truncated":false,"next":"should-not-be-followed",
			"files":[{"relpath":"SKILL.md","status":"modified","binary":false,"size":10,
				"added":2,"removed":1,
				"hunks":[{"header":"@@ -1,2 +1,3 @@","lines":[" a","-b","+c"]}]}]}`))
	}))
	defer server.Close()

	diff, err := Client{BaseURL: server.URL}.AuthorDiff(context.Background(),
		"dev/pdf-tools", "1.2.3", "1.2.2", "SKILL.md", "")
	if err != nil {
		t.Fatalf("AuthorDiff: %v", err)
	}
	if requests != 1 {
		t.Fatalf("the diff took %d requests; a single-file patch must take one", requests)
	}
	if askedPath != "/api/v1/author/skills/dev/pdf-tools/versions/1.2.3/diff" {
		t.Errorf("path = %q", askedPath)
	}
	for _, want := range []string{"file=SKILL.md", "from=1.2.2"} {
		if !strings.Contains(askedQuery, want) {
			t.Errorf("query %q is missing %q", askedQuery, want)
		}
	}
	if len(diff.Files) != 1 || diff.Files[0].Added == nil || *diff.Files[0].Added != 2 {
		t.Fatalf("files = %+v", diff.Files)
	}
	if diff.Next == nil || *diff.Next != "should-not-be-followed" {
		t.Errorf("next = %v — the server's answer is echoed, not followed", diff.Next)
	}
}

// The two refusal shapes the `--json` contract reads: a 403 stays a `Denial` (so the CLI can call it
// `insufficient_permission` and keep `server_code`), and every other status keeps its envelope.
func TestAuthorErrorsKeepTheServersCode(t *testing.T) {
	cases := []struct {
		name    string
		status  int
		header  string
		body    string
		check   func(error) bool
		message string
	}{
		{
			name:    "a rung refusal",
			status:  http.StatusForbidden,
			body:    `{"error":{"code":"forbidden","message":"only the submitter may rename this draft"}}`,
			message: "only the submitter may rename this draft",
			check: func(err error) bool {
				var denial *Denial
				return errors.As(err, &denial) && denial.Code == Forbidden
			},
		},
		{
			name:    "a frozen draft field",
			status:  http.StatusBadRequest,
			body:    `{"error":{"code":"draft_name_is_frozen","message":"this version is no longer a draft"}}`,
			message: "no longer a draft",
			check: func(err error) bool {
				var server *ServerError
				return errors.As(err, &server) && server.Code == "draft_name_is_frozen"
			},
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				if c.header != "" {
					w.Header().Set("WWW-Authenticate", c.header)
				}
				w.WriteHeader(c.status)
				_, _ = io.WriteString(w, c.body)
			}))
			defer server.Close()

			_, err := (Client{BaseURL: server.URL}).AuthorRename(context.Background(),
				"dev/pdf-tools", "draft:01J", "x")
			if err == nil {
				t.Fatal("a refusal reported success")
			}
			if !c.check(err) {
				t.Fatalf("err = %v (%T)", err, err)
			}
			if !strings.Contains(err.Error(), c.message) {
				t.Errorf("the server's sentence is missing from %q", err)
			}
		})
	}
}

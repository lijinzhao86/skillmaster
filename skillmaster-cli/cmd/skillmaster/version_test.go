package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

// A credential in the environment is what keeps these tests off the keychain and off a login: the
// commands under test only need a token to put on the request.
func pointAtServer(t *testing.T, handler http.HandlerFunc) *httptest.Server {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.ApiKeyEnv, "skm_test")
	return server
}

func TestVersionTargetRequiresAPinAndWritesRefuseADigest(t *testing.T) {
	for _, arg := range []string{"dev/pdf-tools", "dev/pdf-tools@", "no-slash@1.2.3"} {
		if _, err := parseVersionTarget(arg); err == nil {
			t.Errorf("%q was accepted as a version target", arg)
		}
	}
	target, err := parseVersionTarget("dev/pdf-tools@draft:01J")
	if err != nil || target.skill != "dev/pdf-tools" || target.pin != "draft:01J" {
		t.Fatalf("draft target = %+v, err = %v", target, err)
	}
	target, err = parseVersionTarget("dev/pdf-tools@1.2.3")
	if err != nil || target.pin != "1.2.3" {
		t.Fatalf("named target = %+v, err = %v", target, err)
	}

	// The draft's identity is the row id; a digest is a different pin and would be answered about a
	// different thing. A number is sent to the server, which owns the frozen/state answer.
	if _, err := writablePin("dev/pdf-tools@sha256:abc"); err == nil {
		t.Fatal("a digest pin was accepted for a draft write")
	} else if !strings.Contains(err.Error(), "draft:<id>") {
		t.Errorf("the refusal does not name the right pin: %v", err)
	}
	if _, err := writablePin("dev/pdf-tools@draft:01J"); err != nil {
		t.Errorf("a draft pin was refused: %v", err)
	}
	if _, err := writablePin("dev/pdf-tools@1.2.3"); err != nil {
		t.Errorf("a published number was refused locally: %v", err)
	}
}

func TestVersionOpenPrintsTheSingleVersionPageWithoutAServer(t *testing.T) {
	t.Setenv(config.WebURLEnv, "http://localhost:5173")

	called := false
	previous := openBrowser
	openBrowser = func(string) error { called = true; return nil }
	t.Cleanup(func() { openBrowser = previous })

	var err error
	out := captureStdout(t, func() {
		err = versionVerb(context.Background(),
			[]string{"open", "dev/pdf-tools@draft:01J", "--print"})
	})
	if err != nil {
		t.Fatalf("version open: %v", err)
	}
	want := "http://localhost:5173/skills/dev/pdf-tools?tab=versions&version=draft:01J"
	if !strings.Contains(out, want) {
		t.Fatalf("output = %q, want it to contain %q", out, want)
	}
	if called {
		t.Fatal("--print opened a browser; it is the flag that says not to")
	}

	// `--json` is the machine contract: one object, with whether a browser was actually opened.
	out = captureStdout(t, func() {
		err = versionVerb(context.Background(),
			[]string{"open", "dev/pdf-tools@1.2.3", "--json"})
	})
	if err != nil {
		t.Fatalf("version open --json: %v", err)
	}
	var payload struct {
		URL    string `json:"url"`
		Opened bool   `json:"opened"`
	}
	if err := json.Unmarshal([]byte(out), &payload); err != nil {
		t.Fatalf("JSON output %q: %v", out, err)
	}
	if payload.URL != "http://localhost:5173/skills/dev/pdf-tools?tab=versions&version=1.2.3" {
		t.Errorf("url = %q", payload.URL)
	}
	if !payload.Opened {
		t.Error("opened = false after a successful browser call")
	}
}

// The page-only verbs are not in help, and pressing one names the page command instead of doing
// anything. The address is carried through so the refusal is copyable.
func TestPageOnlyVerbsRefuseAndNameTheOpenCommand(t *testing.T) {
	for _, verb := range []string{"publish", "rollback", "revoke", "restore", "offline", "online", "delete"} {
		err := versionVerb(context.Background(),
			[]string{verb, "dev/pdf-tools@draft:01J"})
		if err == nil {
			t.Fatalf("%s was accepted", verb)
		}
		for _, want := range []string{verb, "网页", "skillmaster version open dev/pdf-tools@draft:01J"} {
			if !strings.Contains(err.Error(), want) {
				t.Errorf("%s refusal is missing %q:\n%v", verb, want, err)
			}
		}
	}
	for _, verb := range []string{"publish", "rollback", "revoke", "restore", "offline", "online", "delete"} {
		// `revoked` is a legitimate status word; what must not appear is the verb as a command of
		// its own, which in this listing would start a line at the command column.
		if strings.Contains(versionUsage, "\n  "+verb) || strings.Contains(versionUsage, "\n    "+verb) {
			t.Errorf("help lists the page-only verb %q as a command", verb)
		}
	}
}

func TestVersionDiscardWithoutYesPrintsTheCommandAndSendsNothing(t *testing.T) {
	requests := 0
	pointAtServer(t, func(http.ResponseWriter, *http.Request) {
		requests++
	})

	var err error
	out := captureStdout(t, func() {
		err = versionVerb(context.Background(),
			[]string{"discard", "dev/pdf-tools@draft:01J"})
	})
	if err == nil {
		t.Fatal("discard without --yes reported success")
	}
	if requests != 0 {
		t.Fatalf("discard without --yes made %d requests", requests)
	}
	for _, want := range []string{"无法找回",
		"skillmaster version discard dev/pdf-tools@draft:01J --yes"} {
		if !strings.Contains(out, want) {
			t.Errorf("output is missing %q:\n%s", want, out)
		}
	}
}

// The second discard is not a failure: it exits 0 and says the draft was already discarded. The
// server answers idempotently, and a `write` submitter cannot see the discarded row to ask first, so
// the CLI reads the pre-state (visible while it is a draft) and treats the later 404 as "already".
func TestASecondDiscardSaysItWasAlreadyDiscarded(t *testing.T) {
	discarded := false
	posts := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet:
			if discarded {
				w.WriteHeader(http.StatusNotFound)
				_, _ = w.Write([]byte(`{"error":{"code":"skill_not_found","message":"not visible"}}`))
				return
			}
			_, _ = w.Write([]byte(`{"skill":{"address":"dev/pdf-tools"},
				"version":{"pin":"draft:01J","status":"draft"}}`))
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/versions/discard"):
			posts++
			discarded = true
			_, _ = w.Write([]byte(`{"ok":true,"version":{"pin":"draft:01J","status":"discarded"}}`))
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
		}
	})

	var first, second error
	out := captureStdout(t, func() {
		first = versionVerb(context.Background(),
			[]string{"discard", "dev/pdf-tools@draft:01J", "--yes"})
	})
	if first != nil {
		t.Fatalf("first discard: %v", first)
	}
	if !strings.Contains(out, "已丢弃") {
		t.Errorf("first discard said %q", out)
	}

	out = captureStdout(t, func() {
		second = versionVerb(context.Background(),
			[]string{"discard", "dev/pdf-tools@draft:01J", "--yes"})
	})
	if second != nil {
		t.Fatalf("second discard: %v", second)
	}
	if !strings.Contains(out, "已经是丢弃状态") {
		t.Errorf("second discard said %q", out)
	}
	if posts != 2 {
		t.Errorf("the server saw %d discard requests, want 2", posts)
	}
}

func TestVersionListSendsTheFiltersAndPrintsTheAuthorsRows(t *testing.T) {
	var path, query string
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		path, query = r.URL.Path, r.URL.RawQuery
		_, _ = w.Write([]byte(`{
			"skill":{"namespace":"dev","name":"pdf-tools","address":"dev/pdf-tools"},
			"versions":[
				{"address":"dev/pdf-tools","pin":"1.2.3","name":"搜索重构","version":"1.2.3",
				 "status":"live","note":"把 split 的范围语法写成文档","submitted_by":"yuhan",
				 "submitted_at":"2026-10-16T10:12:00Z","first_published_at":"2026-10-18T08:23:32Z",
				 "state_at":"2026-10-18T08:23:32Z","revoked_reason":null,
				 "digest":"sha256:abc","files":6,"bytes":18342},
				{"address":"dev/pdf-tools","pin":"draft:01J","name":"compress 补文档","version":null,
				 "status":"draft","note":null,"submitted_by":"yuhan",
				 "submitted_at":"2026-10-19T10:12:00Z","first_published_at":null,
				 "state_at":null,"revoked_reason":null,"digest":"sha256:def","files":2,"bytes":10}],
			"counts":{"all":2,"live":1,"draft":1,"history":0,"revoked":0,"discarded":0},
			"next":null}`))
	})

	var err error
	out := captureStdout(t, func() {
		err = versionVerb(context.Background(), []string{"list", "dev/pdf-tools",
			"--status", "draft", "--status", "revoked", "--query", "compress", "--limit", "2"})
	})
	if err != nil {
		t.Fatalf("version list: %v", err)
	}
	if path != "/api/v1/author/skills/dev/pdf-tools/versions" {
		t.Errorf("path = %q", path)
	}
	for _, want := range []string{"status=draft", "status=revoked", "q=compress", "limit=2"} {
		if !strings.Contains(query, want) {
			t.Errorf("query %q is missing %q", query, want)
		}
	}
	for _, want := range []string{"dev/pdf-tools@1.2.3", "dev/pdf-tools@draft:01J",
		"线上 · 搜索重构", "草稿 · compress 补文档", "把 split 的范围语法写成文档", "全部 2"} {
		if !strings.Contains(out, want) {
			t.Errorf("output is missing %q:\n%s", want, out)
		}
	}

	// `--json` is the list object, verbatim: the same fields the server sent, nulls included.
	out = captureStdout(t, func() {
		err = versionVerb(context.Background(), []string{"list", "dev/pdf-tools", "--json"})
	})
	if err != nil {
		t.Fatalf("version list --json: %v", err)
	}
	var payload map[string]any
	if err := json.Unmarshal([]byte(out), &payload); err != nil {
		t.Fatalf("JSON output %q: %v", out, err)
	}
	for _, key := range []string{"skill", "versions", "counts", "next"} {
		if _, ok := payload[key]; !ok {
			t.Errorf("JSON output is missing %q: %s", key, out)
		}
	}
	versions, _ := payload["versions"].([]any)
	first, _ := versions[0].(map[string]any)
	for _, key := range []string{"address", "pin", "name", "version", "status", "note",
		"submitted_by", "submitted_at", "first_published_at", "state_at", "revoked_reason",
		"digest", "files", "bytes"} {
		if _, ok := first[key]; !ok {
			t.Errorf("a version object is missing %q: %s", key, out)
		}
	}
}

// `skill versions` stays the consumer question, and it now prints the note v6 added there.
func TestSkillVersionsPrintsTheConsumerNote(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/skills/dev/pdf-tools/versions" {
			t.Errorf("path = %q", r.URL.Path)
		}
		if r.Header.Get("X-Skill-Version") != "" {
			t.Errorf("a pin was sent to the version list: %q", r.Header.Get("X-Skill-Version"))
		}
		_, _ = w.Write([]byte(`{"versions":[
			{"name":"1.2.3","digest":"sha256:abc","published_at":"2026-10-18T08:23:32Z",
			 "is_current":true,"note":"把 split 的范围语法写成文档","state":"published","status":"live"}]}`))
	})

	var err error
	out := captureStdout(t, func() {
		err = run(context.Background(), []string{"skill", "versions", "dev/pdf-tools"})
	})
	if err != nil {
		t.Fatalf("skill versions: %v", err)
	}
	for _, want := range []string{"dev/pdf-tools@1.2.3", "当前", "把 split 的范围语法写成文档"} {
		if !strings.Contains(out, want) {
			t.Errorf("output is missing %q:\n%s", want, out)
		}
	}
}

func TestVersionListRefusesAnUnknownStatus(t *testing.T) {
	err := versionVerb(context.Background(), []string{"list", "dev/pdf-tools", "--status", "gone"})
	if err == nil {
		t.Fatal("an unknown status was accepted")
	}
	if !strings.Contains(err.Error(), "live") || !strings.Contains(err.Error(), "discarded") {
		t.Errorf("the refusal does not name the five statuses: %v", err)
	}
}

func TestVersionDiffPatchSendsFileAndTruncates(t *testing.T) {
	var query string
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		query = r.URL.RawQuery
		_, _ = w.Write([]byte(`{
			"from":"1.2.2","to":"1.2.3","truncated":false,"next":null,
			"files":[{"relpath":"SKILL.md","status":"modified","binary":false,"size":10,
				"added":2,"removed":1,
				"hunks":[{"header":"@@ -1,2 +1,3 @@","lines":[" a","-b","+c"]}]}]}`))
	})

	var err error
	out := captureStdout(t, func() {
		err = versionVerb(context.Background(), []string{"diff", "dev/pdf-tools@1.2.3",
			"--patch", "--max-lines", "3", "--json"})
	})
	if err != nil {
		t.Fatalf("version diff --patch: %v", err)
	}
	// **No `file=` without `--file`.** The CLI does not invent a path: a file that did not change is
	// not part of the comparison, and defaulting to SKILL.md made a patch of a draft that touched
	// another file an error. The run is still one request — no `cursor` is sent, and the returned
	// `next` is only echoed.
	if strings.Contains(query, "file=") {
		t.Errorf("query = %q, want no file= parameter without --file", query)
	}
	if strings.Contains(query, "cursor=") {
		t.Errorf("query = %q, a --patch run must not page", query)
	}
	var payload struct {
		From      string `json:"from"`
		To        string `json:"to"`
		Added     int    `json:"added"`
		Removed   int    `json:"removed"`
		Truncated bool   `json:"truncated"`
		Next      any    `json:"next"`
		Patch     string `json:"patch"`
		Files     []struct {
			Path    string `json:"path"`
			Status  string `json:"status"`
			Added   *int   `json:"added"`
			Removed *int   `json:"removed"`
		} `json:"files"`
	}
	if err := json.Unmarshal([]byte(out), &payload); err != nil {
		t.Fatalf("JSON output %q: %v", out, err)
	}
	if payload.From != "1.2.2" || payload.To != "1.2.3" || payload.Added != 2 || payload.Removed != 1 {
		t.Errorf("payload = %+v", payload)
	}
	if !payload.Truncated {
		t.Error("truncated = false although --max-lines cut the patch")
	}
	if len(payload.Files) != 1 || payload.Files[0].Path != "SKILL.md" ||
		payload.Files[0].Added == nil || *payload.Files[0].Added != 2 {
		t.Errorf("files = %+v", payload.Files)
	}
	for _, want := range []string{"--- a/SKILL.md", "+++ b/SKILL.md", "@@ -1,2 +1,3 @@"} {
		if !strings.Contains(payload.Patch, want) {
			t.Errorf("patch is missing %q:\n%s", want, payload.Patch)
		}
	}
}

// `--patch --file` is one request carrying `file=`, never a walk through the batches: the point of
// the parameter is to open the hundredth changed file without fetching the first nineteen batches.
func TestVersionDiffPatchWithFileAsksForThatOneFile(t *testing.T) {
	var query string
	requests := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		requests++
		query = r.URL.RawQuery
		_, _ = w.Write([]byte(`{
			"from":"1.2.2","to":"1.2.3","truncated":false,"next":null,
			"files":[{"relpath":"compress.md","status":"modified","binary":false,"size":10,
				"added":1,"removed":1,"hunks":[{"header":"@@ -1 +1 @@","lines":["-a","+b"]}]}]}`))
	})

	var err error
	captureStdout(t, func() {
		err = versionVerb(context.Background(), []string{"diff", "dev/pdf-tools@1.2.3",
			"--patch", "--file", "compress.md", "--json"})
	})
	if err != nil {
		t.Fatalf("version diff --patch --file: %v", err)
	}
	if !strings.Contains(query, "file=compress.md") {
		t.Errorf("query = %q, want the named file", query)
	}
	if strings.Contains(query, "cursor=") {
		t.Errorf("query = %q, a single-file read must not page", query)
	}
	if requests != 1 {
		t.Errorf("requests = %d, want 1", requests)
	}
}

// A validation refusal says what is wrong, not what a permission problem would mean: the write's
// hint belongs to a 403 and used to follow every failure from the same command.
func TestVersionNoteTooLongReadsAsItsOwnRefusal(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":{"code":"note_too_long",` +
			`"message":"a version's note can be at most 200 characters"}}`))
	})

	var err error
	captureStdout(t, func() {
		err = versionVerb(context.Background(),
			[]string{"note", "dev/pdf-tools@draft:01J", "--note", "x", "--json"})
	})
	var failure *cliFailure
	if !errors.As(err, &failure) {
		t.Fatalf("err = %v, want a cliFailure", err)
	}
	if failure.Code != "note_too_long" || failure.ServerCode != "note_too_long" {
		t.Fatalf("failure = %+v", failure)
	}
	if !strings.Contains(failure.Message, "200") {
		t.Errorf("message = %q, want the interface's own sentence", failure.Message)
	}
	if failure.Hint != "" {
		t.Errorf("hint = %q, a validation refusal is not a permission refusal", failure.Hint)
	}
}

// A 404 on the author plane is the browser plane's sentence, not the server's operator-facing one.
// The server collapses "no such version", "no such skill" and "not yours" into one answer, so the
// message must not claim to know which it was.
func TestAnUnknownPinReadsAsTheInterfacesNotFound(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"error":{"code":"skill_not_found",` +
			`"message":"no skill at that address"}}`))
	})

	var err error
	captureStdout(t, func() {
		err = versionVerb(context.Background(),
			[]string{"show", "dev/pdf-tools@9.9.9", "--json"})
	})
	var failure *cliFailure
	if !errors.As(err, &failure) {
		t.Fatalf("err = %v, want a cliFailure", err)
	}
	if failure.Code != "skill_not_found" || failure.ServerCode != "skill_not_found" {
		t.Fatalf("failure = %+v", failure)
	}
	if !strings.Contains(failure.Message, "找不到") {
		t.Errorf("message = %q, want the interface's own sentence", failure.Message)
	}
	if strings.Contains(failure.Message, "no skill at that address") {
		t.Errorf("message = %q, the server's sentence leaked", failure.Message)
	}
}

// A refusal in `--json` mode is one object on stderr, and nothing on stdout: a script that starts
// parsing half a successful answer must not be able to.
func TestVersionErrorsBecomeTheJSONObject(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte(`{"error":{"code":"forbidden",` +
			`"message":"only the submitter may rename this draft"}}`))
	})

	var err error
	stdout := captureStdout(t, func() {
		err = versionVerb(context.Background(),
			[]string{"show", "dev/pdf-tools@draft:01J", "--json"})
	})
	if err == nil {
		t.Fatal("a 403 reported success")
	}
	var failure *cliFailure
	if !errors.As(err, &failure) {
		t.Fatalf("err = %v, want a cliFailure", err)
	}
	if failure.Code != "insufficient_permission" || failure.ServerCode != "forbidden" || !failure.JSON {
		t.Fatalf("failure = %+v", failure)
	}

	stderr := captureStderr(t, func() {
		if status := reportError(err); status == 0 {
			t.Errorf("reportError returned success")
		}
	})
	if stdout != "" {
		t.Errorf("stdout is not empty: %q", stdout)
	}
	var payload struct {
		Ok    bool `json:"ok"`
		Error struct {
			Code       string `json:"code"`
			Message    string `json:"message"`
			Hint       string `json:"hint"`
			ServerCode string `json:"server_code"`
		} `json:"error"`
	}
	if unmarshalErr := json.Unmarshal([]byte(stderr), &payload); unmarshalErr != nil {
		t.Fatalf("stderr %q is not one JSON object: %v", stderr, unmarshalErr)
	}
	if payload.Ok || payload.Error.Code != "insufficient_permission" ||
		payload.Error.ServerCode != "forbidden" || payload.Error.Hint == "" {
		t.Errorf("payload = %+v", payload)
	}
}

func TestInvokeRefusesADraftAndPointsAtTheVersionCommands(t *testing.T) {
	err := run(context.Background(), []string{"skill", "invoke", "dev/pdf-tools@draft:01J"})
	if err == nil {
		t.Fatal("invoke accepted a draft pin")
	}
	for _, want := range []string{"version show", "version diff"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("the refusal is missing %q: %v", want, err)
		}
	}
}

func TestFilesRoutesADraftToTheAuthorPlane(t *testing.T) {
	consumer := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/v1/author/skills/dev/pdf-tools/versions/draft:01J/files":
			_, _ = w.Write([]byte(`{"files":[{"relpath":"SKILL.md","uri":"","sha256":"x",
				"size":3,"is_binary":false}]}`))
		case strings.HasPrefix(r.URL.Path, "/api/v1/skills/"):
			consumer++
			t.Errorf("a draft went to the consumer plane: %s", r.URL.Path)
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
		}
	})

	var err error
	out := captureStdout(t, func() {
		err = run(context.Background(), []string{"skill", "files", "dev/pdf-tools@draft:01J"})
	})
	if err != nil {
		t.Fatalf("skill files: %v", err)
	}
	if consumer != 0 {
		t.Fatalf("%d consumer requests were made", consumer)
	}
	if !strings.Contains(out, "SKILL.md") || !strings.Contains(out, "共 1 个文件") {
		t.Errorf("output = %q", out)
	}
}

func TestReadRoutesADraftToTheAuthorPlane(t *testing.T) {
	var seen []string
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, r.URL.Path)
		// The manifest first: the bytes alone cannot say whether they are binary, and the author
		// plane's own file list is where the consumption path's `is_binary` also comes from.
		switch r.URL.Path {
		case "/api/v1/author/skills/dev/pdf-tools/versions/draft:01J/files":
			_, _ = w.Write([]byte(`{"files":[{"relpath":"SKILL.md","uri":"","sha256":"x",
				"size":5,"is_binary":false}]}`))
		case "/api/v1/author/skills/dev/pdf-tools/versions/draft:01J/files/SKILL.md":
			_, _ = w.Write([]byte("# hi\n"))
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
		}
	})

	var err error
	out := captureStdout(t, func() {
		err = run(context.Background(),
			[]string{"skill", "read", "dev/pdf-tools@draft:01J", "SKILL.md"})
	})
	if err != nil {
		t.Fatalf("skill read: %v", err)
	}
	if !strings.Contains(out, "# hi") || !strings.Contains(out, "draft:01J") {
		t.Errorf("output = %q", out)
	}
	// The order is the point of the comment above, so it is asserted rather than assumed: two
	// requests in the other order printed a binary file as text whenever the second call failed.
	if len(seen) != 2 || !strings.HasSuffix(seen[0], "/files") {
		t.Errorf("requests = %v, want the manifest then the bytes", seen)
	}
}

func TestFilesFallsBackToTheAuthorPlaneForARevokedPin(t *testing.T) {
	author := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasPrefix(r.URL.Path, "/api/v1/skills/"):
			w.WriteHeader(http.StatusGone)
			_, _ = w.Write([]byte(`{"error":{"code":"version_revoked",` +
				`"message":"this version has been revoked"}}`))
		case r.URL.Path == "/api/v1/author/skills/dev/pdf-tools/versions/1.2.1/files":
			author++
			_, _ = w.Write([]byte(`{"files":[{"relpath":"SKILL.md","is_binary":false}]}`))
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
		}
	})

	var err error
	out := captureStdout(t, func() {
		err = run(context.Background(), []string{"skill", "files", "dev/pdf-tools@1.2.1"})
	})
	if err != nil {
		t.Fatalf("skill files: %v", err)
	}
	if author != 1 {
		t.Fatalf("the author plane was asked %d times", author)
	}
	if !strings.Contains(out, "SKILL.md") {
		t.Errorf("output = %q", out)
	}
}

// A low rung gets the author plane's 404 rather than the consumer plane's 410: the design says the
// version is invisible to them, not that they can see it was taken down.
func TestARevokedPinReportsTheAuthorPlanesNotFoundToALowRung(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasPrefix(r.URL.Path, "/api/v1/skills/"):
			w.WriteHeader(http.StatusGone)
			_, _ = w.Write([]byte(`{"error":{"code":"version_revoked","message":"revoked"}}`))
		case strings.HasPrefix(r.URL.Path, "/api/v1/author/"):
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":{"code":"skill_not_found","message":"not visible to you"}}`))
		}
	})

	err := run(context.Background(), []string{"skill", "files", "dev/pdf-tools@1.2.1"})
	if err == nil {
		t.Fatal("a low rung read a revoked version")
	}
	if strings.Contains(err.Error(), "已下架") {
		t.Errorf("the low rung got the consumer plane's 410: %v", err)
	}
	if !strings.Contains(err.Error(), "not visible to you") {
		t.Errorf("the author plane's answer is missing: %v", err)
	}
}

func TestVersionShowPrintsTheAuthorFacts(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{
			"skill":{"namespace":"dev","name":"pdf-tools","address":"dev/pdf-tools"},
			"version":{"address":"dev/pdf-tools","pin":"draft:01J","name":"compress 补文档",
				"version":null,"status":"draft","note":"把 compress 的用法补进正文",
				"submitted_by":"yuhan","submitted_at":"2026-10-16T10:12:00Z",
				"first_published_at":null,"state_at":null,"revoked_reason":null,
				"digest":"sha256:abc","files":6,"bytes":18342}}`))
	})

	var err error
	out := captureStdout(t, func() {
		err = versionVerb(context.Background(), []string{"show", "dev/pdf-tools@draft:01J"})
	})
	if err != nil {
		t.Fatalf("version show: %v", err)
	}
	for _, want := range []string{"dev/pdf-tools@draft:01J", "compress 补文档", "草稿",
		"把 compress 的用法补进正文", "yuhan", "6 个 · 18342 字节", "sha256:abc"} {
		if !strings.Contains(out, want) {
			t.Errorf("output is missing %q:\n%s", want, out)
		}
	}
}

func TestVersionRenameJSONIsTheServersWriteShape(t *testing.T) {
	var method, path string
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		method, path = r.Method, r.URL.Path
		_, _ = w.Write([]byte(`{"ok":true,"version":{"pin":"draft:01J","name":"compress 用法补全",
			"status":"draft"}}`))
	})

	var err error
	out := captureStdout(t, func() {
		err = versionVerb(context.Background(), []string{"rename", "dev/pdf-tools@draft:01J",
			"--name", "compress 用法补全", "--json"})
	})
	if err != nil {
		t.Fatalf("version rename: %v", err)
	}
	if method != http.MethodPatch ||
		path != "/api/v1/author/skills/dev/pdf-tools/versions/draft:01J/name" {
		t.Errorf("%s %s", method, path)
	}
	var payload struct {
		Ok      bool `json:"ok"`
		Version struct {
			Pin  string `json:"pin"`
			Name string `json:"name"`
		} `json:"version"`
	}
	if err := json.Unmarshal([]byte(out), &payload); err != nil {
		t.Fatalf("JSON output %q: %v", out, err)
	}
	if !payload.Ok || payload.Version.Pin != "draft:01J" || payload.Version.Name != "compress 用法补全" {
		t.Errorf("payload = %+v", payload)
	}
}

func TestVersionNoteRequiresExactlyOneSource(t *testing.T) {
	for _, args := range [][]string{
		{"dev/pdf-tools@draft:01J"},
		{"dev/pdf-tools@draft:01J", "--note", "x", "--clear"},
		{"dev/pdf-tools@draft:01J", "--note-file", "/does/not/exist"},
	} {
		if _, _, err := parseNoteArgs(args); err == nil {
			t.Errorf("%v was accepted", args)
		}
	}
	target, note, err := parseNoteArgs([]string{"dev/pdf-tools@draft:01J", "--clear"})
	if err != nil || target.pin != "draft:01J" || note != "" {
		t.Fatalf("--clear: target = %+v, note = %q, err = %v", target, note, err)
	}
}

// A credential refusal is the same answer here as in every other command, and `--json` still names
// the server's own reason. Before this, the version commands called `session` directly, so a 401
// printed the error envelope as raw JSON and a script got no code at all.
func TestARefusedCredentialReadsAsItsOwnReason(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":{"code":"token_expired","message":"This credential has expired."}}`))
	})

	var err error
	captureStdout(t, func() {
		err = versionVerb(context.Background(), []string{"list", "dev/pdf-tools", "--json"})
	})
	var failure *cliFailure
	if !errors.As(err, &failure) {
		t.Fatalf("err = %v, want a cliFailure", err)
	}
	if failure.Code != "token_expired" || failure.ServerCode != "token_expired" {
		t.Fatalf("failure = %+v", failure)
	}
	if !strings.Contains(failure.Message, "过期") {
		t.Errorf("message = %q, want the interface's sentence", failure.Message)
	}
	if strings.Contains(failure.Message, "{") {
		t.Errorf("message = %q, the wire body leaked", failure.Message)
	}
	// A credential is not a rung: the permission paragraph would describe a different problem.
	if failure.Hint != "" {
		t.Errorf("hint = %q", failure.Hint)
	}
}

// A 404 on `--file` means "that file did not change" only when the server says so. `skill_not_found`
// is the same status and the opposite answer: a nonexistent skill, version or baseline must not be
// reported as "no differences".
func TestDiffOnAMissingVersionIsNotReportedAsNoChange(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"error":{"code":"skill_not_found","message":"no skill at that address"}}`))
	})

	err := versionVerb(context.Background(),
		[]string{"diff", "dev/pdf-tools@9.9.9", "--file", "SKILL.md"})
	if err == nil {
		t.Fatal("a nonexistent version was reported as having no differences")
	}
	var failure *cliFailure
	if !errors.As(err, &failure) || failure.Code != "skill_not_found" {
		t.Fatalf("err = %v", err)
	}
}

// And the other 404 — the named file is not part of the comparison — is still the answer rather than
// a failure, in both output modes.
func TestDiffOnAFileThatDidNotChangeIsAnAnswer(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"error":{"code":"file_not_found","message":"not in this version"}}`))
	})

	var err error
	out := captureStdout(t, func() {
		err = versionVerb(context.Background(),
			[]string{"diff", "dev/pdf-tools@draft:01J", "--file", "SKILL.md"})
	})
	if err != nil {
		t.Fatalf("a file that did not change failed the command: %v", err)
	}
	if !strings.Contains(out, "没有差异") {
		t.Errorf("output = %q", out)
	}
}

// Without `--yes`, the machine answer still carries the consequence and the command it would run:
// that is the whole content of the refusal, and a hint would be dropped for a usage failure.
func TestDiscardWithoutYesKeepsItsConsequenceInTheJSONObject(t *testing.T) {
	err := versionVerb(context.Background(),
		[]string{"discard", "dev/pdf-tools@draft:01J", "--json"})
	var failure *cliFailure
	if !errors.As(err, &failure) {
		t.Fatalf("err = %v, want a cliFailure", err)
	}
	if failure.Code != "usage" {
		t.Fatalf("code = %q", failure.Code)
	}
	if !strings.Contains(failure.Message, "无法找回") {
		t.Errorf("message = %q, want the consequence", failure.Message)
	}
	if !strings.Contains(failure.Message, "--yes") {
		t.Errorf("message = %q, want the command to run", failure.Message)
	}
}

// `forbidden` is the rung's answer, so it brings the rung paragraph. `insufficient_scope` is the
// credential's, and its own sentence already names the missing scope.
func TestTheRungHintIsOnlyForForbidden(t *testing.T) {
	for _, testCase := range []struct {
		code string
		hint bool
	}{
		{"forbidden", true},
		{"insufficient_scope", false},
	} {
		pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusForbidden)
			_, _ = w.Write([]byte(`{"error":{"code":"` + testCase.code + `","message":"no"}}`))
		})
		err := versionVerb(context.Background(), []string{"show", "dev/pdf-tools@draft:01J"})
		var failure *cliFailure
		if !errors.As(err, &failure) {
			t.Fatalf("%s: err = %v", testCase.code, err)
		}
		if got := failure.Hint != ""; got != testCase.hint {
			t.Errorf("%s: hint = %q, want attached = %v", testCase.code, failure.Hint, testCase.hint)
		}
	}
}

// A free-text query has to come back quoted, and the hint names exactly one cursor — the next one,
// not also the one that produced this page.
func TestTheNextPageHintQuotesTheQueryAndNamesOneCursor(t *testing.T) {
	got := listContinuation(listOptions{query: "compress 补文档", limit: 50})
	if got != "--query 'compress 补文档' --limit 50" {
		t.Errorf("continuation = %q", got)
	}
	withCursor := listContinuation(listOptions{query: "x", limit: 50, cursor: "0"})
	if strings.Contains(withCursor, "--cursor") {
		t.Errorf("continuation = %q, want the caller's cursor left out", withCursor)
	}
}

// `submit`'s field refusals are v6 codes, and they read in the same words as everywhere else.
func TestSubmitFieldRefusalsUseTheVersionSentences(t *testing.T) {
	failure := classifyFailure(&api.ServerError{
		Status:  http.StatusBadRequest,
		Code:    "draft_name_too_long",
		Message: "a version's name can be at most 40 code points",
	})
	if failure.Code != "draft_name_too_long" {
		t.Fatalf("code = %q", failure.Code)
	}
	if failure.Message != versionMessages["draft_name_too_long"] {
		t.Errorf("message = %q", failure.Message)
	}
}

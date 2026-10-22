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

	// A write names a row, and only a row id does that (ADR 0063). A digest names content and a
	// number names a published version; neither is something a draft write can address, so both are
	// refused here rather than sent to the server to be refused about state.
	for _, refused := range []string{"dev/pdf-tools@sha256:abc", "dev/pdf-tools@1.2.3"} {
		if _, err := writablePin(refused); err == nil {
			t.Fatalf("%s was accepted for a draft write", refused)
		} else if !strings.Contains(err.Error(), "@<版本码>") {
			t.Errorf("the refusal does not name the right pin: %v", err)
		}
	}
	for _, accepted := range []string{"dev/pdf-tools@draft:01J", "dev/pdf-tools@01JKMNPQ"} {
		if _, err := writablePin(accepted); err != nil {
			t.Errorf("%s was refused, want accepted: %v", accepted, err)
		}
	}
}

// The page's address grammar has no `draft:` prefix. `version open` normalises a legacy ULID to the
// bare id; a published row id is left as written (it is not a web address, and opening another
// version silently would be worse than the page's 「没有这一版」).
func TestVersionOpenNormalisesALegacyDraftPrefix(t *testing.T) {
	t.Setenv(config.WebURLEnv, "http://localhost:5173")
	var out string
	out = captureStdout(t, func() {
		if err := versionVerb(context.Background(), []string{"open",
			"dev/pdf-tools@draft:01JKMNPQRSTVWXYZ0123456789", "--print"}); err != nil {
			t.Fatalf("version open: %v", err)
		}
	})
	if !strings.Contains(out, "version=01JKMNPQRSTVWXYZ0123456789") {
		t.Errorf("output = %q", out)
	}
	out = captureStdout(t, func() {
		if err := versionVerb(context.Background(), []string{"open",
			"dev/pdf-tools@K3M9QX2P", "--print"}); err != nil {
			t.Fatalf("version open: %v", err)
		}
	})
	if !strings.Contains(out, "version=K3M9QX2P") {
		t.Errorf("output = %q", out)
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
//
// **Two destinations, because the actions live on two pages** (ADR 0072): publish / rollback /
// revoke / restore are the version's own page; offline / online / delete are the skill's settings.
func TestPageOnlyVerbsRefuseAndNameTheOpenCommand(t *testing.T) {
	for _, verb := range []string{"publish", "rollback", "revoke", "restore"} {
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
	for _, verb := range []string{"offline", "online", "delete"} {
		err := versionVerb(context.Background(),
			[]string{verb, "dev/pdf-tools@draft:01J"})
		if err == nil {
			t.Fatalf("%s was accepted", verb)
		}
		for _, want := range []string{verb, "网页",
			"skillmaster skill open dev/pdf-tools --tab settings"} {
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
				{"address":"dev/pdf-tools","pin":"01JKMNPQ","name":"compress 补文档","version":null,
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
	for _, want := range []string{"dev/pdf-tools@1.2.3", "dev/pdf-tools@01JKMNPQ",
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
	for _, key := range []string{"skill", "versions", "counts"} {
		if _, ok := payload[key]; !ok {
			t.Errorf("JSON output is missing %q: %s", key, out)
		}
	}
	if _, ok := payload["next"]; ok {
		t.Errorf("JSON output still carries a server cursor: %s", out)
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

// A row-id pin is the author plane, and it is the spelling that reaches a draft (ADR 0063, v7 #25).
func TestInvokeRowIdLoadsFromTheAuthorPlane(t *testing.T) {
	withTempHome(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/author/skills/dev/pdf-tools/versions/01JKMNPQ":
			_, _ = w.Write([]byte(`{"skill":{"namespace":"dev","name":"pdf-tools",
				"address":"dev/pdf-tools"},"version":{"address":"dev/pdf-tools","pin":"01JKMNPQ",
				"code":"01JKMNPQ","name":"draft","version":null,"status":"draft","digest":
				"sha256:abc","files":1,"bytes":3}}`))
		case "/api/v1/author/skills/dev/pdf-tools/versions/01JKMNPQ/files/SKILL.md":
			w.Header().Set("ETag", `"sha256:abc"`)
			_, _ = w.Write([]byte("body"))
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
		}
	})

	out := captureStdout(t, func() {
		if err := run(context.Background(), []string{"skill", "invoke",
			"dev/pdf-tools@01JKMNPQ"}); err != nil {
			t.Fatalf("invoke: %v", err)
		}
	})
	if !strings.Contains(out, "body") || !strings.Contains(out, "@01JKMNPQ") {
		t.Errorf("output = %q", out)
	}
}

// A row id is not a consumer address, so a 403 from the author plane must not fall back: the
// fallback would answer "there is no such version" to a caller who was simply not allowed in.
func TestInvokeWithARowIdSaysTheRungIsMissingAndDoesNotFallBack(t *testing.T) {
	withTempHome(t)
	consumer := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/v1/skills/") {
			consumer++
		}
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte(`{"error":{"code":"forbidden","message":"needs browse",
			"details":[]}}`))
	})

	var err error
	captureStdout(t, func() {
		err = run(context.Background(), []string{"skill", "invoke", "dev/pdf-tools@01JKMNPQ"})
	})
	if err == nil || !strings.Contains(err.Error(), "调用档没有作者面入口") {
		t.Fatalf("invoke error = %v", err)
	}
	if consumer != 0 {
		t.Errorf("a row id was sent to the consumer plane")
	}
}

func TestFilesRoutesADraftToTheAuthorPlane(t *testing.T) {
	consumer := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/v1/author/skills/dev/pdf-tools/versions/01JKMNPQRSTVWXYZ0123456789/files":
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
		err = run(context.Background(), []string{"skill", "files",
			"dev/pdf-tools@draft:01JKMNPQRSTVWXYZ0123456789"})
	})
	if err != nil {
		t.Fatalf("skill files: %v", err)
	}
	if consumer != 0 {
		t.Fatalf("%d consumer requests were made", consumer)
	}
	if !strings.Contains(out, "SKILL.md\n") || strings.Contains(out, "SKILL.md\t3\ttext") {
		t.Errorf("output = %q, want the relpath line format", out)
	}
	if !strings.Contains(out, "skillmaster skill read") {
		t.Errorf("output does not give the read command:\n%s", out)
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
			"version":{"address":"dev/pdf-tools","pin":"01JKMNPQ","code":"01JKMNPQ",
				"name":"compress 补文档",
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
	for _, want := range []string{"dev/pdf-tools@01JKMNPQ", "版本码：01JKMNPQ", "compress 补文档", "草稿",
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

// A page with more rows says so without handing the caller a cursor to carry. The caller's switch
// is `--all`; the opaque server token stays inside the CLI.
func TestVersionListSaysThereIsMoreWithoutACursor(t *testing.T) {
	out := captureStdout(t, func() {
		printAuthorVersions("dev/pdf-tools", api.AuthorVersionList{}, true)
	})
	if !strings.Contains(out, "还有更多") || !strings.Contains(out, "--all") {
		t.Errorf("output does not say how to get the rest:\n%s", out)
	}
	for _, leaked := range []string{"--cursor", "next", "eyJ"} {
		if strings.Contains(out, leaked) {
			t.Errorf("output leaks %q:\n%s", leaked, out)
		}
	}
}

// A deleted name is a 409 carrying its own code (ADR 0071), and `submit` reads it through the same
// classifier — so the person is told to restore the skill rather than given an operator's line.
func TestADeletedNameIsTranslatedOnSubmit(t *testing.T) {
	failure := classifyFailure(&api.ServerError{
		Status:  http.StatusConflict,
		Code:    "skill_deleted",
		Message: "that name belongs to a deleted skill",
	})
	if failure.Code != "skill_deleted" || failure.ServerCode != "skill_deleted" {
		t.Fatalf("failure = %+v", failure)
	}
	if failure.Message != versionMessages["skill_deleted"] {
		t.Errorf("message = %q", failure.Message)
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

// `version open` can name the pane and the publish choice. Both are appended only when asked for,
// and `record` is the pane the address already means — writing it would be a second spelling.
func TestVersionOpenWritesThePaneAndTheBumpIntoTheAddress(t *testing.T) {
	t.Setenv(config.WebURLEnv, "http://localhost:5173")

	cases := []struct {
		flags  []string
		want   string
		forbid string
	}{
		{[]string{"--sub", "actions"}, "?tab=versions&version=d1&sub=actions", ""},
		{[]string{"--sub", "diff"}, "?tab=versions&version=d1&sub=diff", ""},
		// `record` is the default pane: writing it would be a second spelling, so the address must
		// not merely "contain" the old shape — it must not contain `sub=` at all.
		{[]string{"--sub", "record"}, "?tab=versions&version=d1", "sub="},
		{[]string{"--bump", "minor"}, "?tab=versions&version=d1&bump=minor", ""},
		{[]string{"--sub", "actions", "--bump", "major"},
			"?tab=versions&version=d1&sub=actions&bump=major", ""},
		// Flags may come before the address; the address is whatever is left.
		{[]string{"--bump", "patch"}, "?tab=versions&version=d1&bump=patch", ""},
	}
	for _, c := range cases {
		args := append([]string{"open"}, c.flags...)
		args = append(args, "dev/pdf-tools@d1", "--print")
		out := captureStdout(t, func() {
			if err := versionVerb(context.Background(), args); err != nil {
				t.Errorf("version open %v: %v", c.flags, err)
			}
		})
		line := "http://localhost:5173/skills/dev/pdf-tools" + c.want
		if !strings.Contains(out, line) {
			t.Errorf("version open %v output = %q, want a line %q", c.flags, out, line)
		}
		if c.forbid != "" && strings.Contains(out, c.forbid) {
			t.Errorf("version open %v output = %q, must not contain %q", c.flags, out, c.forbid)
		}
	}
}

// The machine contract keeps its shape; the address simply carries the two new parameters.
func TestVersionOpenJSONCarriesTheNewParameters(t *testing.T) {
	t.Setenv(config.WebURLEnv, "http://localhost:5173")

	previous := openBrowser
	openBrowser = func(string) error { return nil }
	t.Cleanup(func() { openBrowser = previous })

	out := captureStdout(t, func() {
		if err := versionVerb(context.Background(),
			[]string{"open", "dev/pdf-tools@d1", "--sub", "actions", "--bump", "minor", "--json"}); err != nil {
			t.Errorf("version open --json: %v", err)
		}
	})
	var fields map[string]any
	if err := json.Unmarshal([]byte(out), &fields); err != nil {
		t.Fatalf("JSON output %q: %v", out, err)
	}
	// The shape is part of the machine contract: exactly two fields, no more.
	if len(fields) != 2 {
		t.Errorf("JSON output has %d fields, want 2: %q", len(fields), out)
	}
	for _, key := range []string{"url", "opened"} {
		if _, ok := fields[key]; !ok {
			t.Errorf("JSON output %q is missing %q", out, key)
		}
	}
	var payload struct {
		URL    string `json:"url"`
		Opened bool   `json:"opened"`
	}
	_ = json.Unmarshal([]byte(out), &payload)
	want := "http://localhost:5173/skills/dev/pdf-tools?tab=versions&version=d1&sub=actions&bump=minor"
	if payload.URL != want {
		t.Errorf("url = %q, want %q", payload.URL, want)
	}
	if !payload.Opened {
		t.Error("opened = false after a successful browser call")
	}
}

// Values outside the closed sets are refused locally, in the shape `--json` promises. None of them
// builds an address, so a typo cannot open a page with a silently dropped pre-selection.
func TestVersionOpenRefusesUnknownPanesBumpsAndFlags(t *testing.T) {
	pointAtServer(t, func(http.ResponseWriter, *http.Request) {
		t.Error("version open sent a request")
	})

	for _, c := range []struct {
		args   []string
		wantJS bool
	}{
		{[]string{"open", "dev/pdf-tools@d1", "--sub", "general", "--json"}, true},
		{[]string{"open", "dev/pdf-tools@d1", "--bump", "foo", "--json"}, true},
		{[]string{"open", "dev/pdf-tools@d1", "--what", "--json"}, true},
		{[]string{"open", "dev/pdf-tools@d1", "dev/pdf-tools@d2", "--json"}, true},
		// A missing value swallows the next token even when it spells `--json`: the flag was
		// written where a value belongs, so this answers in text rather than in JSON.
		{[]string{"open", "dev/pdf-tools@d1", "--sub", "--json"}, false},
		{[]string{"open", "dev/pdf-tools@d1", "--bump", "--json"}, false},
		// A duplicate value flag is the same silent-drop shape the version exists to remove.
		{[]string{"open", "dev/pdf-tools@d1", "--sub", "diff", "--sub", "actions", "--json"}, true},
		{[]string{"open", "dev/pdf-tools@d1", "--bump", "minor", "--bump", "major", "--json"}, true},
	} {
		err := versionVerb(context.Background(), c.args)
		var failure *cliFailure
		if !errors.As(err, &failure) || failure.Code != "usage" || failure.JSON != c.wantJS {
			t.Errorf("%v → %#v, want a usage failure with JSON=%v", c.args, err, c.wantJS)
		}
	}
}

// Every refusal ends with exactly one copyable command, and the command names the page that owns
// the action. `publish` relays the bump; the other version-level verbs do not accept one; the three
// skill-level verbs keep pointing at the settings page (ADR 0072) and drop the version pin.
func TestPageOnlyRefusalsEndWithTheExactHandoff(t *testing.T) {
	requests := 0
	pointAtServer(t, func(http.ResponseWriter, *http.Request) { requests++ })

	const versionPage = "到那一版自己的页面上去做：skillmaster version open dev/pdf-tools@d1 --sub actions"
	const settingsPage = "到这个 skill 的设置页去做：skillmaster skill open dev/pdf-tools --tab settings"

	cases := []struct {
		args []string
		want string
	}{
		{[]string{"publish", "dev/pdf-tools@d1"}, versionPage},
		{[]string{"publish", "dev/pdf-tools@d1", "--bump", "patch"}, versionPage + " --bump patch"},
		{[]string{"publish", "dev/pdf-tools@d1", "--bump", "major"}, versionPage + " --bump major"},
		{[]string{"rollback", "dev/pdf-tools@d1"}, versionPage},
		{[]string{"revoke", "dev/pdf-tools@d1"}, versionPage},
		{[]string{"restore", "dev/pdf-tools@d1"}, versionPage},
		{[]string{"offline", "dev/pdf-tools@d1"}, settingsPage},
		{[]string{"online", "dev/pdf-tools@d1"}, settingsPage},
		{[]string{"delete", "dev/pdf-tools@d1"}, settingsPage},
	}
	for _, c := range cases {
		err := versionVerb(context.Background(), c.args)
		if err == nil {
			t.Fatalf("%v was accepted", c.args)
		}
		lines := strings.Split(err.Error(), "\n")
		if got := lines[len(lines)-1]; got != c.want {
			t.Errorf("%v handoff = %q, want %q", c.args, got, c.want)
		}
	}
	if requests != 0 {
		t.Fatalf("page-only verbs made %d requests", requests)
	}
}

// The refusal now takes arguments apart instead of silently dropping them: `--bump` belongs to
// publish alone, duplicates are refused, and a second address is a typo, not a hint. The usage error
// still names the page that owns the action — including the settings page for skill-level verbs.
func TestPageOnlyVerbsRefuseFlagsTheyDoNotTake(t *testing.T) {
	requests := 0
	pointAtServer(t, func(http.ResponseWriter, *http.Request) { requests++ })

	cases := []struct {
		args    []string
		wantMsg string
	}{
		{[]string{"rollback", "dev/pdf-tools@d1", "--bump", "minor", "--json"}, "只有 publish 选版本号"},
		{[]string{"offline", "dev/pdf-tools@d1", "--bump", "minor", "--json"}, "只有 publish 选版本号"},
		{[]string{"publish", "dev/pdf-tools@d1", "--bump", "foo", "--json"}, "--bump 只接受"},
		{[]string{"publish", "dev/pdf-tools@d1", "--bump", "minor", "--bump", "major", "--json"}, "只能给一次"},
		{[]string{"publish", "dev/pdf-tools@d1", "--what", "--json"}, "不认识的参数"},
		{[]string{"publish", "dev/pdf-tools@d1", "dev/pdf-tools@d2", "--json"}, "只接一个地址"},
		// An unknown flag on a skill-level verb must still point at that skill's settings page: the
		// handoff is where the action lives, not where the verb's name sounds like it lives.
		{[]string{"offline", "dev/pdf-tools@d1", "--what", "--json"},
			"skillmaster skill open dev/pdf-tools --tab settings"},
	}
	for _, c := range cases {
		err := versionVerb(context.Background(), c.args)
		var failure *cliFailure
		if !errors.As(err, &failure) || !failure.JSON || failure.Code != "usage" {
			t.Errorf("%v → %#v, want a JSON usage failure", c.args, err)
			continue
		}
		if !strings.Contains(failure.Message, c.wantMsg) {
			t.Errorf("%v message = %q, want it to contain %q", c.args, failure.Message, c.wantMsg)
		}
	}
	if requests != 0 {
		t.Fatalf("page-only verbs made %d requests", requests)
	}
}

// The help has to teach the new flags; the refused verbs stay out of the command column.
func TestVersionUsageListsTheNewOpenFlags(t *testing.T) {
	out := captureStderr(t, func() {
		if err := versionVerb(context.Background(), []string{"help"}); err != nil {
			t.Errorf("version help: %v", err)
		}
	})
	for _, want := range []string{
		"[--sub record|diff|actions]",
		"[--bump patch|minor|major]",
		"record 是缺省",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("version help is missing %q", want)
		}
	}
}

// A usage error must not repeat a credential it was handed. The address position is the easy one to
// get wrong: a key pasted one token too far once landed in stderr, and stderr is what CI keeps.
func TestUsageErrorsDoNotEchoACredential(t *testing.T) {
	pointAtServer(t, func(http.ResponseWriter, *http.Request) { t.Error("a request was sent") })

	const key = "skm_live_abcdef123456"
	cases := [][]string{
		{"publish", "dev/pdf-tools@d1", key},
		{"publish", "dev/pdf-tools@d1", "--bump", key},
		{"open", "dev/pdf-tools@d1", key, "--print"},
		{"open", "dev/pdf-tools@d1", "--sub", key},
		{"open", "dev/pdf-tools@d1", "--bump", key},
		{"publish", key},
		{"offline", key},
	}
	for _, args := range cases {
		err := versionVerb(context.Background(), args)
		if err == nil {
			t.Fatalf("%v was accepted", args)
		}
		if strings.Contains(err.Error(), key) {
			t.Errorf("%v echoed the credential:\n%v", args, err)
		}
	}

	// A namespace that merely starts with `skm_` is an address, not a credential: the handoff keeps it.
	err := versionVerb(context.Background(), []string{"publish", "skm_team/pdf-tools@1.0.0"})
	if err == nil || !strings.Contains(err.Error(), "skillmaster version open skm_team/pdf-tools@1.0.0 --sub actions") {
		t.Errorf("a skm_ namespace was mistaken for a key: %v", err)
	}

	// And a plain token pasted twice is named, so the reader can see what to remove.
	err = versionVerb(context.Background(), []string{"open", "dev/pdf-tools@d1", "dev/pdf-tools@d2", "--json"})
	var failure *cliFailure
	if !errors.As(err, &failure) || !strings.Contains(failure.Message, "dev/pdf-tools@d2") {
		t.Errorf("the second address was not named: %#v", err)
	}
}

func authorPage(pin, next string) string {
	return `{"skill":{"namespace":"dev","name":"pdf-tools","address":"dev/pdf-tools"},"versions":[{"address":"dev/pdf-tools","pin":"` + pin + `","name":"v` + pin + `","version":"` + pin + `","status":"live","note":null,"submitted_by":"yuhan","submitted_at":"2026-10-18T08:23:32Z","first_published_at":null,"state_at":null,"revoked_reason":null,"digest":"sha256:a","files":1,"bytes":1}],"counts":{"all":2,"live":2,"draft":0,"history":0,"revoked":0,"discarded":0},"next":` + next + `}`
}

// `--all` walks the author list, keeps `--status` / `--query` on every page, and never hands the
// caller the server's cursor. The JSON form has no `next` field at all.
func TestVersionListAllWalksPagesAndKeepsTheFilters(t *testing.T) {
	var asked []string
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		asked = append(asked, r.URL.RawQuery)
		if strings.Contains(r.URL.RawQuery, "cursor=c2") {
			_, _ = w.Write([]byte(authorPage("2.0.0", "null")))
			return
		}
		_, _ = w.Write([]byte(authorPage("1.0.0", `"c2"`)))
	})

	var err error
	out := captureStdout(t, func() {
		err = versionVerb(context.Background(), []string{"list", "dev/pdf-tools",
			"--status", "draft", "--query", "compress", "--limit", "2", "--all"})
	})
	if err != nil {
		t.Fatalf("version list --all: %v", err)
	}
	if len(asked) != 2 {
		t.Fatalf("requests = %d, want 2: %v", len(asked), asked)
	}
	for _, query := range asked {
		for _, want := range []string{"status=draft", "q=compress", "limit=2"} {
			if !strings.Contains(query, want) {
				t.Errorf("query %q is missing %q", query, want)
			}
		}
	}
	if !strings.Contains(asked[1], "cursor=c2") {
		t.Errorf("second query did not carry the next cursor: %q", asked[1])
	}
	for _, want := range []string{"dev/pdf-tools@1.0.0", "dev/pdf-tools@2.0.0"} {
		if !strings.Contains(out, want) {
			t.Errorf("output is missing %q:\n%s", want, out)
		}
	}
	if strings.Contains(out, "--cursor") || strings.Contains(out, "还有更多") {
		t.Errorf("a complete walk still advertises a cursor:\n%s", out)
	}

	out = captureStdout(t, func() {
		err = versionVerb(context.Background(), []string{"list", "dev/pdf-tools", "--all", "--json"})
	})
	if err != nil {
		t.Fatalf("version list --all --json: %v", err)
	}
	var payload map[string]any
	if err := json.Unmarshal([]byte(out), &payload); err != nil {
		t.Fatalf("JSON output %q: %v", out, err)
	}
	if versions, _ := payload["versions"].([]any); len(versions) != 2 {
		t.Errorf("JSON versions = %d, want 2: %s", len(versions), out)
	}
	if _, ok := payload["next"]; ok {
		t.Errorf("JSON output carries next: %s", out)
	}
}

func TestVersionListAllStopsOnARepeatedCursor(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(authorPage("1.0.0", `"same"`)))
	})
	err := versionVerb(context.Background(), []string{"list", "dev/pdf-tools", "--all"})
	if err == nil || !strings.Contains(err.Error(), "游标") {
		t.Fatalf("repeated cursor = %v, want an error naming the cursor", err)
	}
}

// A cursor that alternates A → B → A never repeats the one just sent, so a "same as last time"
// check would walk forever. Any cursor already seen stops the walk (PRD §4).
func TestVersionListAllStopsOnAnAlternatingCursor(t *testing.T) {
	requests := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		requests++
		switch requests {
		case 1:
			_, _ = w.Write([]byte(authorPage("1.0.0", `"a"`)))
		case 2:
			_, _ = w.Write([]byte(authorPage("2.0.0", `"b"`)))
		default:
			_, _ = w.Write([]byte(authorPage("3.0.0", `"a"`)))
		}
	})
	err := versionVerb(context.Background(), []string{"list", "dev/pdf-tools", "--all"})
	if err == nil || !strings.Contains(err.Error(), "游标") {
		t.Fatalf("alternating cursor = %v, want an error naming the cursor", err)
	}
	if requests != 3 {
		t.Errorf("requests = %d, want 3 before stopping", requests)
	}
}

// A truncated JSON list must say it is truncated. `counts` cannot stand in for that: under
// `--status` it still counts every status (PRD §4).
func TestVersionListJSONSaysWhenThereIsMore(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.RawQuery, "cursor=c2") {
			_, _ = w.Write([]byte(authorPage("2.0.0", "null")))
			return
		}
		_, _ = w.Write([]byte(authorPage("1.0.0", `"c2"`)))
	})

	var err error
	out := captureStdout(t, func() {
		err = versionVerb(context.Background(), []string{"list", "dev/pdf-tools", "--json"})
	})
	if err != nil {
		t.Fatalf("version list --json: %v", err)
	}
	var payload map[string]any
	if err := json.Unmarshal([]byte(out), &payload); err != nil {
		t.Fatalf("JSON output %q: %v", out, err)
	}
	if more, ok := payload["has_more"].(bool); !ok || !more {
		t.Errorf("a truncated page did not say has_more: %s", out)
	}

	out = captureStdout(t, func() {
		err = versionVerb(context.Background(), []string{"list", "dev/pdf-tools", "--all", "--json"})
	})
	if err != nil {
		t.Fatalf("version list --all --json: %v", err)
	}
	payload = nil
	if err := json.Unmarshal([]byte(out), &payload); err != nil {
		t.Fatalf("JSON output %q: %v", out, err)
	}
	if more, ok := payload["has_more"].(bool); !ok || more {
		t.Errorf("a complete walk said has_more: %s", out)
	}
}

// A bare address is resolved once and that pin is what the footer keeps: the `read` command must
// name the version that was listed, not whatever is current by the time somebody copies it.
func TestFilesOnABareAddressKeepsTheResolvedPinInItsFooter(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "/versions"):
			_, _ = w.Write([]byte(authorPage("1.2.3", "null")))
		case strings.HasSuffix(r.URL.Path, "/files"):
			_, _ = w.Write([]byte(`{"files":[{"relpath":"SKILL.md","uri":"","sha256":"x",
				"size":3,"is_binary":false}]}`))
		default:
			t.Errorf("unexpected path %s", r.URL.Path)
		}
	})

	var err error
	out := captureStdout(t, func() {
		err = run(context.Background(), []string{"skill", "files", "dev/pdf-tools"})
	})
	if err != nil {
		t.Fatalf("skill files: %v", err)
	}
	if !strings.Contains(out, "skillmaster skill read dev/pdf-tools@1.2.3") {
		t.Errorf("the read command does not carry the resolved pin:\n%s", out)
	}
	lines := strings.Split(strings.TrimRight(out, "\n"), "\n")
	last := lines[len(lines)-1]
	if !strings.Contains(last, "dev/pdf-tools@1.2.3") {
		t.Errorf("the version line is not last or does not name the pin: %q\n%s", last, out)
	}
}

func TestVersionListAllDoesNotPrintAPartialList(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.RawQuery, "cursor=c2") {
			http.Error(w, `{"error":{"code":"internal","message":"boom"}}`, http.StatusInternalServerError)
			return
		}
		_, _ = w.Write([]byte(authorPage("1.0.0", `"c2"`)))
	})
	var err error
	out := captureStdout(t, func() {
		err = versionVerb(context.Background(), []string{"list", "dev/pdf-tools", "--all"})
	})
	if err == nil {
		t.Fatal("a failed second page was reported as success")
	}
	if out != "" {
		t.Errorf("a failed walk printed a partial list:\n%s", out)
	}
}

func TestVersionListRefusesCursor(t *testing.T) {
	err := versionVerb(context.Background(), []string{"list", "dev/pdf-tools", "--cursor", "c2"})
	if err == nil || !strings.Contains(err.Error(), "--cursor") {
		t.Fatalf("--cursor = %v, want an unknown-argument refusal", err)
	}
}

func TestFilesWithoutAnAddressPrintsItsOwnUsage(t *testing.T) {
	err := skillVerb(context.Background(), []string{"files"})
	if err == nil || !strings.Contains(err.Error(), "skillmaster skill files") {
		t.Fatalf("files with no address = %v, want the files usage", err)
	}
}

func TestVersionHelpAdvertisesAllAndPointsAtTheConsumerList(t *testing.T) {
	out := captureStderr(t, func() {
		_ = versionVerb(context.Background(), []string{"help"})
	})
	for _, want := range []string{"--all", "--limit", "作者视角", "线上", "历史版本",
		"skillmaster skill versions"} {
		if !strings.Contains(out, want) {
			t.Errorf("version help is missing %q:\n%s", want, out)
		}
	}
	if strings.Contains(out, "--cursor") {
		t.Errorf("version help still teaches --cursor:\n%s", out)
	}
}

func TestVersionHelpHidesPageOnlyVerbs(t *testing.T) {
	out := captureStderr(t, func() {
		_ = versionVerb(context.Background(), []string{"help"})
	})
	for _, verb := range []string{"publish", "rollback", "revoke", "restore", "offline", "online", "delete"} {
		if strings.Contains(out, "\n  "+verb+" ") {
			t.Errorf("version help lists the page-only verb %s:\n%s", verb, out)
		}
	}
}

func TestVersionCreateIsUnknown(t *testing.T) {
	err := versionVerb(context.Background(), []string{"create", "dev/pdf-tools"})
	if err == nil || !strings.Contains(err.Error(), "未知的 version 子命令") {
		t.Fatalf("version create = %v, want an unknown-subcommand refusal", err)
	}
}

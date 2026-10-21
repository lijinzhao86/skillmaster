package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

// The five v10 verbs: create, shares/unshare, request, open, and the @new form of the write
// commands. These tests are about the requests the CLI builds and the sentences it prints; the
// rules behind them are the server's and are covered by its own integration tests.

func TestCreateBuildsTheRequestAndPrintsTheAddress(t *testing.T) {
	var seen map[string]string
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/v1/author/skills" {
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
		}
		body, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(body, &seen)
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"namespace":"me","name":"hello",` +
			`"version_code":"K3M9QX2P","version_name":"第一版","state":"draft"}`))
	})

	out := captureStdout(t, func() {
		if err := createSkill(context.Background(),
			[]string{"hello", "--description", "第一个 skill"}); err != nil {
			t.Fatalf("create: %v", err)
		}
	})
	if seen["name"] != "hello" || seen["description"] != "第一个 skill" ||
		seen["version_name"] != "第一版" {
		t.Fatalf("request body = %#v", seen)
	}
	if !strings.Contains(out, "me/hello@K3M9QX2P") || !strings.Contains(out, "第一版") {
		t.Errorf("output = %q", out)
	}

	jsonOut := captureStdout(t, func() {
		if err := createSkill(context.Background(),
			[]string{"hello", "--description", "第一个 skill", "--json"}); err != nil {
			t.Fatalf("create --json: %v", err)
		}
	})
	var created map[string]any
	if err := json.Unmarshal([]byte(jsonOut), &created); err != nil {
		t.Fatalf("--json output = %q (%v)", jsonOut, err)
	}
	for _, field := range []string{"namespace", "name", "version_code", "version_name", "state"} {
		if _, ok := created[field]; !ok {
			t.Errorf("--json is missing %q: %#v", field, created)
		}
	}
	if created["state"] != "draft" {
		t.Errorf("state = %v", created["state"])
	}
}

func TestCreateRefusesBadUsageBeforeARequest(t *testing.T) {
	requests := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		requests++
		http.NotFound(w, r)
	})
	for _, args := range [][]string{
		{"hello"},                        // no description
		{"he/llo", "--description", "d"}, // a name is one segment
		{"he@llo", "--description", "d"}, // @ separates a version
	} {
		if err := createSkill(context.Background(), args); err == nil {
			t.Errorf("%v was accepted", args)
		}
	}
	if requests != 0 {
		t.Fatalf("%d requests were sent before the refusal", requests)
	}
}

func TestTheFirstWriteReadsTheBaseAndCreatesTheDraftWithItsChange(t *testing.T) {
	var draftBody map[string]any
	paths := []string{}
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.Method+" "+r.URL.Path)
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/draft-base"):
			_, _ = w.Write([]byte(`{"base":{"pin":"1.0.0","digest":"sha256:abc"}}`))
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/versions"):
			body, _ := io.ReadAll(r.Body)
			_ = json.Unmarshal(body, &draftBody)
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"version":{"code":"K3M9QX2P","draft_name":"第二版",` +
				`"state":"draft","digest":"sha256:def"}}`))
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	})

	oldStdin := stdin
	stdin = strings.NewReader("new text")
	t.Cleanup(func() { stdin = oldStdin })

	if err := write(context.Background(), []string{
		"me/hello@new", "--name", "第二版", "a.md"}); err != nil {
		t.Fatalf("write @new: %v", err)
	}

	if len(paths) != 2 || !strings.Contains(paths[0], "GET") ||
		!strings.Contains(paths[1], "POST") {
		t.Fatalf("requests = %v", paths)
	}
	if draftBody["name"] != "第二版" || draftBody["from"] != "1.0.0" ||
		draftBody["from_digest"] != "sha256:abc" {
		t.Fatalf("create body = %#v", draftBody)
	}
	edit, _ := draftBody["edit"].(map[string]any)
	if edit["op"] != "write" || edit["path"] != "a.md" || edit["text"] != "new text" {
		t.Fatalf("edit = %#v", edit)
	}
}

func TestTheFirstWriteNeedsANameAndCpIsRefused(t *testing.T) {
	requests := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		requests++
		http.NotFound(w, r)
	})
	if err := write(context.Background(), []string{"me/hello@new", "a.md"}); err == nil {
		t.Fatal("a first write without --name was accepted")
	}
	if err := moveOrCopy(context.Background(),
		[]string{"me/hello@new", "--name", "x", "a.md", "b.md"}, "cp"); err == nil {
		t.Fatal("cp @new was accepted")
	}
	if requests != 0 {
		t.Fatalf("%d requests were sent before the refusal", requests)
	}
}

func TestSharesPrintsTheRowsAndUnshareDeletesOne(t *testing.T) {
	var deleted string
	revokes := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			if r.URL.Query().Get("q") != "ka" || r.URL.Query().Get("p") != "2" {
				t.Errorf("query = %s", r.URL.RawQuery)
			}
			_, _ = w.Write([]byte(`{"grants":[{"handle":"kai","role":"write",` +
				`"created_at":"2026-10-21T10:00:00Z"}],"total":11,"page":2,"page_size":10}`))
		case http.MethodDelete:
			deleted = r.URL.Path
			revokes++
			w.WriteHeader(http.StatusNoContent)
		}
	})

	out := captureStdout(t, func() {
		if err := shares(context.Background(),
			[]string{"me/hello", "--query", "ka", "--page", "2"}); err != nil {
			t.Fatalf("shares: %v", err)
		}
	})
	if !strings.Contains(out, "kai") || !strings.Contains(out, "写入") ||
		!strings.Contains(out, "2026-10-21T10:00:00Z") ||
		!strings.Contains(out, "第 2 页，共 11 人") {
		t.Errorf("output = %q", out)
	}
	for i := 0; i < 2; i++ {
		if err := unshare(context.Background(),
			[]string{"me/hello", "--user", "kai"}); err != nil {
			t.Fatalf("unshare #%d: %v", i+1, err)
		}
	}
	if !strings.HasSuffix(deleted, "/grants/kai") {
		t.Errorf("delete path = %q", deleted)
	}
	if revokes != 2 {
		t.Errorf("DELETE calls = %d, want 2 (withdrawing twice is idempotent)", revokes)
	}
}

func TestRequestHasThreeForms(t *testing.T) {
	var posted string
	var pending string = "write"
	calls := []string{}
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		calls = append(calls, r.Method)
		switch r.Method {
		case http.MethodPost:
			body, _ := io.ReadAll(r.Body)
			posted = string(body)
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"rung":"write"}`))
		case http.MethodGet:
			if pending == "" {
				_, _ = w.Write([]byte(`{"pending_rung":null}`))
			} else {
				_, _ = w.Write([]byte(`{"pending_rung":"` + pending + `"}`))
			}
		case http.MethodDelete:
			w.WriteHeader(http.StatusNoContent)
		}
	})

	out := captureStdout(t, func() {
		if err := requestAccess(context.Background(),
			[]string{"me/hello", "--role", "write"}); err != nil {
			t.Fatalf("request: %v", err)
		}
	})
	if !strings.Contains(posted, `"rung":"write"`) || !strings.Contains(out, "等待审批") {
		t.Fatalf("posted = %q, output = %q", posted, out)
	}
	// A second ask is sent rather than blocked: the server keeps one row and moves its target.
	if err := requestAccess(context.Background(),
		[]string{"me/hello", "--role", "maintain"}); err != nil {
		t.Fatalf("second request: %v", err)
	}
	if !strings.Contains(posted, `"rung":"maintain"`) {
		t.Fatalf("second posted = %q", posted)
	}

	out = captureStdout(t, func() {
		if err := requestAccess(context.Background(), []string{"me/hello", "--status"}); err != nil {
			t.Fatalf("status: %v", err)
		}
	})
	if !strings.Contains(out, "正在等待「写入」") {
		t.Errorf("status output = %q", out)
	}

	pending = ""
	out = captureStdout(t, func() {
		if err := requestAccess(context.Background(), []string{"me/hello", "--status"}); err != nil {
			t.Fatalf("status: %v", err)
		}
	})
	if !strings.Contains(out, "没有在等的申请") {
		t.Errorf("status output = %q", out)
	}

	if err := requestAccess(context.Background(), []string{"me/hello", "--cancel"}); err != nil {
		t.Fatalf("cancel: %v", err)
	}
	if !strings.Contains(strings.Join(calls, ","), "POST,POST,GET,GET,DELETE") {
		t.Errorf("calls = %v", calls)
	}
}

func TestSkillOpenBuildsThePageAddress(t *testing.T) {
	t.Setenv(config.WebURLEnv, "http://localhost:5173")
	previous := openBrowser
	openBrowser = func(string) error { return nil }
	t.Cleanup(func() { openBrowser = previous })

	out := captureStdout(t, func() {
		if err := openSkill(context.Background(),
			[]string{"me/hello", "--tab", "settings", "--sub", "collaborators",
				"--print"}); err != nil {
			t.Fatalf("open: %v", err)
		}
	})
	if !strings.Contains(out, "http://localhost:5173/skills/me/hello?") ||
		!strings.Contains(out, "tab=settings") ||
		!strings.Contains(out, "sub=collaborators") {
		t.Errorf("output = %q", out)
	}
	simple := captureStdout(t, func() {
		if err := openSkill(context.Background(),
			[]string{"me/hello", "--print"}); err != nil {
			t.Fatalf("open default: %v", err)
		}
	})
	if !strings.Contains(simple, "http://localhost:5173/skills/me/hello\n") {
		t.Errorf("default address = %q", simple)
	}
	tabOnly := captureStdout(t, func() {
		if err := openSkill(context.Background(),
			[]string{"me/hello", "--tab", "files", "--print"}); err != nil {
			t.Fatalf("open --tab: %v", err)
		}
	})
	if !strings.Contains(tabOnly, "tab=files") || strings.Contains(tabOnly, "sub=") {
		t.Errorf("--tab address = %q", tabOnly)
	}
	jsonOut := captureStdout(t, func() {
		if err := openSkill(context.Background(),
			[]string{"me/hello", "--print", "--json"}); err != nil {
			t.Fatalf("open --json: %v", err)
		}
	})
	var opened struct {
		URL    string `json:"url"`
		Opened bool   `json:"opened"`
	}
	if err := json.Unmarshal([]byte(jsonOut), &opened); err != nil {
		t.Fatalf("--json output = %q (%v)", jsonOut, err)
	}
	if opened.URL != "http://localhost:5173/skills/me/hello" || opened.Opened {
		t.Errorf("--json = %#v", opened)
	}
	if err := openSkill(context.Background(),
		[]string{"me/hello", "--sub", "general"}); err == nil {
		t.Error("--sub without --tab settings was accepted")
	}
}

// ADR 0072: the last line of a page-only refusal names the page that owns the action.
func TestPageOnlyRefusalsPointAtThePageThatOwnsTheAction(t *testing.T) {
	versionLevel := map[string]bool{
		"publish": true, "rollback": true, "revoke": true, "restore": true,
	}
	skillLevel := map[string]bool{"offline": true, "online": true, "delete": true}
	for verb := range versionLevel {
		err := versionVerb(context.Background(), []string{verb, "me/hello@1.0.0"})
		if err == nil || !strings.Contains(err.Error(), "skillmaster version open me/hello@1.0.0") {
			t.Errorf("%s refusal = %v", verb, err)
		}
	}
	for verb := range skillLevel {
		err := versionVerb(context.Background(), []string{verb, "me/hello@1.0.0"})
		if err == nil ||
			!strings.Contains(err.Error(), "skillmaster skill open me/hello --tab settings") {
			t.Errorf("%s refusal = %v", verb, err)
		}
	}
}

// The first write is one read of the base and one write; `edit @new` must not read the base a
// second time, or a base that moved in between produces a draft whose content came from the old
// one while its digest claims the new one.
func TestEditOnNewDraftResolvesTheBaseOnce(t *testing.T) {
	bases := 0
	var posted map[string]any
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/draft-base"):
			bases++
			_, _ = w.Write([]byte(`{"base":{"pin":"1.0.0","digest":"sha256:abc"}}`))
		case r.Method == http.MethodGet && strings.Contains(r.URL.Path, "/files/"):
			_, _ = w.Write([]byte("one"))
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/versions"):
			body, _ := io.ReadAll(r.Body)
			_ = json.Unmarshal(body, &posted)
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"version":{"code":"K3M9QX2P","state":"draft"}}`))
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	})

	if err := edit(context.Background(), []string{
		"me/hello@new", "--name", "第二版", "SKILL.md", "--old", "one", "--new", "two"}); err != nil {
		t.Fatalf("edit @new: %v", err)
	}
	if bases != 1 {
		t.Fatalf("the base was resolved %d times, want 1", bases)
	}
	if posted["from_digest"] != "sha256:abc" {
		t.Fatalf("from_digest = %v", posted["from_digest"])
	}
	editBody, _ := posted["edit"].(map[string]any)
	if editBody["text"] != "two" {
		t.Fatalf("edit = %#v", editBody)
	}
}

func TestEditOnNewDraftRefusesWithoutANameBeforeAnyRequest(t *testing.T) {
	requests := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		requests++
		http.NotFound(w, r)
	})
	if err := edit(context.Background(), []string{
		"me/hello@new", "SKILL.md", "--old", "one", "--new", "two"}); err == nil {
		t.Fatal("edit @new without --name was accepted")
	}
	if requests != 0 {
		t.Fatalf("%d requests were sent before the refusal", requests)
	}
}

// An empty file is legal content; the request has to carry the empty string rather than omit it.
func TestTheFirstWriteSendsEmptyTextAsContent(t *testing.T) {
	var posted map[string]any
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/draft-base") {
			_, _ = w.Write([]byte(`{"base":null}`))
			return
		}
		body, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(body, &posted)
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"version":{"code":"K3M9QX2P","state":"draft"}}`))
	})
	oldStdin := stdin
	stdin = strings.NewReader("")
	t.Cleanup(func() { stdin = oldStdin })

	if err := write(context.Background(), []string{
		"me/hello@new", "--name", "空白", "a.md"}); err != nil {
		t.Fatalf("write @new: %v", err)
	}
	editBody, _ := posted["edit"].(map[string]any)
	text, present := editBody["text"]
	if !present || text != "" {
		t.Fatalf("edit = %#v (text must be present and empty)", editBody)
	}
	if _, sent := posted["from"]; sent {
		t.Fatalf("a blank base must send no from: %#v", posted)
	}
}

func TestTheFirstWriteRefusesBinary(t *testing.T) {
	requests := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		requests++
		w.Write([]byte(`{"base":null}`))
	})
	oldStdin := stdin
	stdin = strings.NewReader(string([]byte{0xff, 0xfe, 0x00}))
	t.Cleanup(func() { stdin = oldStdin })
	if err := write(context.Background(), []string{
		"me/hello@new", "--name", "x", "a.bin"}); err == nil {
		t.Fatal("binary content was accepted on the text-only @new channel")
	}
	if requests != 0 {
		t.Fatalf("%d requests were sent before the refusal", requests)
	}
}

func TestNewCommandsTranslateFailuresAndCarryTheCode(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(`{"error":{"code":"skill_deleted","message":"restore it first",` +
			`"details":[]}}`))
	})
	err := createSkill(context.Background(),
		[]string{"hello", "--description", "d", "--json"})
	failure, ok := err.(*cliFailure)
	if !ok {
		t.Fatalf("error = %T %v", err, err)
	}
	if !failure.JSON || failure.Code != "skill_deleted" {
		t.Fatalf("failure = %#v", failure)
	}
	if !strings.Contains(failure.Message, "到网页") {
		t.Errorf("message = %q", failure.Message)
	}
}

func TestUnshareTranslatesAnUnknownUser(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":{"code":"invalid_request","message":"bad",` +
			`"details":[{"field":"handle","issue":"no_such_user"}]}}`))
	})
	err := unshare(context.Background(), []string{"me/hello", "--user", "ghost"})
	failure, ok := err.(*cliFailure)
	if !ok {
		t.Fatalf("error = %T %v", err, err)
	}
	if failure.Message != "没有这个用户。" {
		t.Fatalf("message = %q", failure.Message)
	}
}

func TestSharesDoesNotCallAnEmptyPageEmpty(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"grants":[],"total":11,"page":3,"page_size":10}`))
	})
	out := captureStdout(t, func() {
		if err := shares(context.Background(), []string{"me/hello", "--page", "3"}); err != nil {
			t.Fatalf("shares: %v", err)
		}
	})
	if strings.Contains(out, "还没有共享") {
		t.Fatalf("an empty page was reported as no collaborators: %q", out)
	}
	if !strings.Contains(out, "这一页没有人") || !strings.Contains(out, "第 3 页，共 11 人") {
		t.Fatalf("output = %q", out)
	}
}

func TestPrintDoesNotOpenTheBrowser(t *testing.T) {
	t.Setenv(config.WebURLEnv, "http://localhost:5173")
	calls := 0
	previous := openBrowser
	openBrowser = func(string) error { calls++; return nil }
	t.Cleanup(func() { openBrowser = previous })
	if err := openSkill(context.Background(), []string{"me/hello", "--print"}); err != nil {
		t.Fatalf("open: %v", err)
	}
	if calls != 0 {
		t.Fatalf("--print opened the browser %d times", calls)
	}
}

func TestAnExplicitBaseReadsTheVersionsDigest(t *testing.T) {
	var posted map[string]any
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && strings.Contains(r.URL.Path, "/versions/"):
			_, _ = w.Write([]byte(`{"version":{"digest":"sha256:xyz","state":"published"}}`))
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/versions"):
			body, _ := io.ReadAll(r.Body)
			_ = json.Unmarshal(body, &posted)
			w.WriteHeader(http.StatusConflict)
			_, _ = w.Write([]byte(`{"error":{"code":"digest_mismatch","message":"stale",` +
				`"details":[]}}`))
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
		}
	})
	oldStdin := stdin
	stdin = strings.NewReader("x")
	t.Cleanup(func() { stdin = oldStdin })

	err := write(context.Background(),
		[]string{"me/hello@new", "--name", "第二版", "--from", "1.0.0", "a.md"})
	if posted["from_digest"] != "sha256:xyz" || posted["from"] != "1.0.0" {
		t.Fatalf("body = %#v", posted)
	}
	if err == nil || !strings.Contains(err.Error(), "重新读一遍") {
		t.Fatalf("error = %v", err)
	}
}

func TestTheNewCommandsHonourJSON(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/grants"):
			_, _ = w.Write([]byte(`{"grants":[],"total":0,"page":1,"page_size":10}`))
		case r.Method == http.MethodDelete && strings.HasSuffix(r.URL.Path, "/grants/kai"):
			w.WriteHeader(http.StatusNoContent)
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/access-requests/me"):
			_, _ = w.Write([]byte(`{"pending_rung":"write"}`))
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/access-requests"):
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"rung":"write"}`))
		case r.Method == http.MethodDelete && strings.HasSuffix(r.URL.Path, "/access-requests/me"):
			w.WriteHeader(http.StatusNoContent)
		}
	})
	out := captureStdout(t, func() {
		if err := shares(context.Background(), []string{"me/hello", "--json"}); err != nil {
			t.Fatalf("shares --json: %v", err)
		}
	})
	var page map[string]any
	if err := json.Unmarshal([]byte(out), &page); err != nil {
		t.Fatalf("shares --json output = %q", out)
	}
	if page["total"] != float64(0) {
		t.Errorf("page = %#v", page)
	}

	out = captureStdout(t, func() {
		if err := requestAccess(context.Background(),
			[]string{"me/hello", "--status", "--json"}); err != nil {
			t.Fatalf("status --json: %v", err)
		}
	})
	var status map[string]any
	if err := json.Unmarshal([]byte(out), &status); err != nil {
		t.Fatalf("status --json output = %q", out)
	}
	if status["pending_rung"] != "write" {
		t.Errorf("status = %#v", status)
	}

	for _, form := range []struct {
		name string
		args []string
		run  func(context.Context, []string) error
	}{
		{"unshare", []string{"me/hello", "--user", "kai", "--json"}, unshare},
		{"request", []string{"me/hello", "--role", "write", "--json"}, requestAccess},
		{"cancel", []string{"me/hello", "--cancel", "--json"}, requestAccess},
	} {
		out := captureStdout(t, func() {
			if err := form.run(context.Background(), form.args); err != nil {
				t.Fatalf("%s --json: %v", form.name, err)
			}
		})
		var decoded map[string]any
		if err := json.Unmarshal([]byte(out), &decoded); err != nil {
			t.Errorf("%s --json output = %q (%v)", form.name, out, err)
		}
	}
}

func TestTheHelpListsTheNewVerbs(t *testing.T) {
	for _, verb := range []string{"create", "shares", "unshare", "request", "open"} {
		if !strings.Contains(skillUsage, "\n  "+verb+" ") {
			t.Errorf("skill help does not list %s", verb)
		}
	}
}

// v10 PRD #19: the failure object carries the server's `details[].issue`, and a local usage error is
// a failure object too when `--json` is on the line — not a stray Chinese sentence.
func TestJSONFailuresCarryTheIssueAndUsageErrorsAreJSON(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":{"code":"invalid_upload","message":"nope",` +
			`"details":[{"field":"name","issue":"name_too_long"}]}}`))
	})

	serverErr := createSkill(context.Background(),
		[]string{"hello", "--description", "d", "--json"})
	var failure *cliFailure
	if !errors.As(serverErr, &failure) || !failure.JSON {
		t.Fatalf("server failure = %#v", serverErr)
	}
	if failure.Issue != "name_too_long" || failure.Code != "invalid_upload" {
		t.Errorf("failure = %#v", failure)
	}
	if failure.Message != "名字最多 64 个码点。" {
		t.Errorf("message = %q", failure.Message)
	}

	usageErr := createSkill(context.Background(), []string{"hello", "--json"})
	if !errors.As(usageErr, &failure) || !failure.JSON || failure.Code != "usage" {
		t.Errorf("usage failure = %#v", usageErr)
	}

	// `--status` is a boolean here and a value flag for `version list`; the parser knows which, so a
	// missing address after it must still produce the JSON object.
	missing := requestAccess(context.Background(), []string{"--status", "--json"})
	if !errors.As(missing, &failure) || !failure.JSON || failure.Code != "usage" {
		t.Errorf("request usage failure = %#v", missing)
	}

	// The help lists `--json` last, so the flag often comes after the value that is wrong.
	early := shares(context.Background(), []string{"me/hello", "--page", "0", "--json"})
	if !errors.As(early, &failure) || !failure.JSON || failure.Code != "usage" {
		t.Errorf("shares usage failure = %#v", early)
	}

	// The same slot holds a value on other codes; that is not an issue name and must not be filed
	// as one.
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":{"code":"version_is_not_draft","message":"nope",` +
			`"details":[{"field":"state","issue":"published"}]}}`))
	})
	stateErr := createSkill(context.Background(),
		[]string{"hello", "--description", "d", "--json"})
	if !errors.As(stateErr, &failure) || failure.Issue != "" {
		t.Errorf("state value was filed as an issue: %#v", failure)
	}
}

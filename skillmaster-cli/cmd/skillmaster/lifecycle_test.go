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

// v44: creating a draft is its own action. `draft create` copies the base untouched — no file
// changes — and the name is the one thing the caller must supply.
func TestDraftCreateCopiesTheBaseAndNamesTheDraft(t *testing.T) {
	var draftBody map[string]any
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/me":
			_, _ = w.Write([]byte(`{"user":{"username":"me","namespace":"me"}}`))
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/versions"):
			_, _ = w.Write([]byte(`{"versions":[]}`))
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

	_, stderr, status := runCaptured(t, "draft", "create", "me/hello", "--name", "第二版")
	if status != 0 {
		t.Fatalf("status = %d, stderr = %s", status, stderr)
	}
	if draftBody["name"] != "第二版" {
		t.Fatalf("create body = %#v", draftBody)
	}
	// **A copy sends no edit.** Sending one would make this the write path again, with a caller
	// who never named a file to change.
	if _, edited := draftBody["edit"]; edited {
		t.Fatalf("draft create sent an edit: %#v", draftBody)
	}
	if _, from := draftBody["from"]; from {
		t.Fatalf("a draft with no --from let the server pick the base, but the body named one: %#v",
			draftBody)
	}
	if !strings.Contains(stderr, "已建出草稿 me/hello@K3M9QX2P") {
		t.Fatalf("stderr = %q", stderr)
	}
}

func TestDraftCreateNeedsANameAndTheFileVerbsRefuseNew(t *testing.T) {
	requests := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		requests++
		http.NotFound(w, r)
	})
	if err := draftCreate(context.Background(), []string{"me/hello"}); err == nil {
		t.Fatal("draft create without --name was accepted")
	}
	if err := write(context.Background(), []string{"me/hello@new", "SKILL.md"}); err == nil {
		t.Fatal("write @new was accepted")
	}
	if err := rm(context.Background(), []string{"me/hello@new", "a.md"}); err == nil {
		t.Fatal("rm @new was accepted")
	}
	if err := moveOrCopy(context.Background(), []string{"me/hello@new", "a.md", "b.md"}, "mv"); err == nil {
		t.Fatal("mv @new was accepted")
	}
	if err := copyFiles(context.Background(),
		[]string{"me/hello@new", "a.md", "b.md"}); err == nil {
		t.Fatal("cp @new was accepted")
	}
	if err := copyFiles(context.Background(),
		[]string{"me/hello@a1b2c3", "--name", "x", "a.md", "b.md"}); err == nil {
		t.Fatal("cp accepted a new-draft flag it does not have")
	}
	if requests != 0 {
		t.Fatalf("%d requests were sent before the refusal", requests)
	}
}

func TestSharesPrintsTheRowsAndUnshareDeletesOne(t *testing.T) {
	var deleted string
	revokes := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/invitations"):
			_, _ = w.Write([]byte(`{"items":[]}`))
		case r.Method == http.MethodGet:
			if r.URL.Query().Get("q") == "ka" && r.URL.Query().Get("p") != "2" {
				t.Errorf("query = %s", r.URL.RawQuery)
			}
			_, _ = w.Write([]byte(`{"grants":[{"handle":"kai","role":"write",` +
				`"created_at":"2026-10-21T10:00:00Z"}],"total":11,"page":2,"page_size":10}`))
		case r.Method == http.MethodDelete:
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
	// One unshare now does two deletes — the waiting invitation and the grant — so two runs are
	// four calls. Both are idempotent, which is why the second run is still a success.
	if revokes != 4 {
		t.Errorf("DELETE calls = %d, want 4 (two per idempotent unshare)", revokes)
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
func TestSkillPageActionsOpenTheSettingsPage(t *testing.T) {
	var opened []string
	previous := openBrowser
	openBrowser = func(target string) error { opened = append(opened, target); return nil }
	t.Cleanup(func() { openBrowser = previous })
	pointAtServer(t, func(http.ResponseWriter, *http.Request) { t.Error("a request was sent") })
	t.Setenv(config.ApiKeyEnv, "")

	// `delete` without --yes is a confirmation, not a page; the other three open the settings page.
	for _, verb := range []string{"offline", "online", "visibility"} {
		var err error
		out := captureStdout(t, func() {
			err = skillVerb(context.Background(), []string{verb, "me/hello"})
		})
		if err != nil {
			t.Fatalf("%s: %v", verb, err)
		}
		if !strings.Contains(out, "还没执行：") || !strings.Contains(out, "tab=settings") {
			t.Errorf("%s output = %q", verb, out)
		}
	}
	var deleteErr error
	deleteOut := captureStdout(t, func() {
		deleteErr = skillVerb(context.Background(), []string{"delete", "me/hello"})
	})
	if deleteErr == nil || !strings.Contains(deleteErr.Error(), "没能确认") {
		t.Errorf("skill delete without --yes = %v, output %q", deleteErr, deleteOut)
	}
	// **No confirmation command without knowing the delete is allowed** (2026-11-01).
	if strings.Contains(deleteErr.Error(), "--yes") || strings.Contains(deleteOut, "--yes") {
		t.Errorf("a command was offered for an unconfirmed delete: %v / %q", deleteErr, deleteOut)
	}
	if !strings.Contains(deleteErr.Error(), "tab=settings") {
		t.Errorf("the refusal does not say where a person confirms: %v", deleteErr)
	}
	if len(opened) != 3 {
		t.Fatalf("opened %d pages, want 3: %v", len(opened), opened)
	}
	// The three names the version group used to carry are gone, and say so as unknown verbs.
	for _, retired := range []string{"delete", "offline", "online"} {
		err := versionVerb(context.Background(), []string{retired, "me/hello@1.0.0"})
		if err == nil || !strings.Contains(err.Error(), "不认识的子命令") {
			t.Errorf("version %s = %v, want an unknown verb", retired, err)
		}
	}
}

// Every file verb that used to accept `@new` now refuses it before touching the network, and says
// which command creates the draft instead (v44 / ADR 0136).
func TestTheFileVerbsRefuseANewDraftBeforeAnyRequest(t *testing.T) {
	requests := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		requests++
		http.NotFound(w, r)
	})
	cases := map[string]error{
		"write": write(context.Background(), []string{"me/hello@new", "SKILL.md"}),
		"edit": edit(context.Background(), []string{
			"me/hello@new", "SKILL.md", "--old", "one", "--new", "two"}),
		"rm": rm(context.Background(), []string{"me/hello@new", "a.md"}),
		"mv": moveOrCopy(context.Background(), []string{"me/hello@new", "a.md", "b.md"}, "mv"),
		"cp": copyFiles(context.Background(), []string{"me/hello@new", "a.md", "b.md"}),
	}
	for verb, err := range cases {
		if err == nil {
			t.Errorf("%s @new was accepted", verb)
			continue
		}
		if !strings.Contains(err.Error(), "不再用 @new 建草稿") &&
			!strings.Contains(err.Error(), "第一次改动不能是复制") {
			t.Errorf("%s @new error = %v, want the draft-create pointer", verb, err)
		}
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
		if strings.HasSuffix(r.URL.Path, "/invitations") {
			_, _ = w.Write([]byte(`{"items":[]}`))
			return
		}
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

// `--from` names the version the copy starts from, and the server reads it itself: a copy changes
// no bytes, so there is no digest for this side to compare them against.
func TestDraftCreateWithFromPassesTheVersionThrough(t *testing.T) {
	var posted map[string]any
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/me":
			_, _ = w.Write([]byte(`{"user":{"username":"me","namespace":"me"}}`))
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/versions"):
			_, _ = w.Write([]byte(`{"versions":[]}`))
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/versions"):
			body, _ := io.ReadAll(r.Body)
			_ = json.Unmarshal(body, &posted)
			w.WriteHeader(http.StatusConflict)
			_, _ = w.Write([]byte(`{"error":{"code":"content_already_exists","message":"same",` +
				`"details":[{"field":"state","issue":"published"},` +
				`{"field":"pin","issue":"1.0.0"}]}}`))
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
		}
	})

	err := draftCreate(context.Background(),
		[]string{"me/hello", "--name", "第二版", "--from", "1.0.0"})
	if posted["from"] != "1.0.0" {
		t.Fatalf("body = %#v", posted)
	}
	if _, digest := posted["from_digest"]; digest {
		t.Fatalf("a copy has no digest of its own to send: %#v", posted)
	}
	if err == nil || !strings.Contains(err.Error(), "这段内容已经存在") {
		t.Fatalf("error = %v", err)
	}
}

func TestTheNewCommandsHonourJSON(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/grants"):
			_, _ = w.Write([]byte(`{"grants":[],"total":0,"page":1,"page_size":10}`))
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/invitations"):
			_, _ = w.Write([]byte(`{"items":[]}`))
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
	text := groupHelpText(t, "skill")
	for _, verb := range []string{"create", "shares", "unshare", "request", "open"} {
		if !strings.Contains(text, "\n  "+verb+" ") {
			t.Errorf("skill help does not list %s:\n%s", verb, text)
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

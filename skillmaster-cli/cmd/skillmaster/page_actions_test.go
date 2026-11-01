package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

// The three answers of `skill delete` (v37, ADR 0127), end to end at the command boundary.

func factsPage(everPublished bool) string {
	return `{"skill":{"namespace":"dev","name":"x","address":"dev/x","access":"admin",` +
		`"owned":true,"offline":false,"ever_published":` + map[bool]string{true: "true", false: "false"}[everPublished] +
		`},"versions":[],"counts":{},"next":null}`
}

// **Every command that hands the action to a page says so in its help** — in the list line and in
// the body. `skill delete` is the one whose web path is conditional (a published skill), so its
// sentence is the conditional one.
func TestPageActionHelpSaysTheActionHappensInTheBrowser(t *testing.T) {
	pointAtServer(t, func(http.ResponseWriter, *http.Request) { t.Error("help sent a request") })

	for _, path := range []string{
		"version publish", "version rollback", "version revoke", "version restore",
		"skill offline", "skill online", "skill visibility", "skills publish", "skill delete",
	} {
		argv := append(strings.Split(path, " "), "--help")
		stdout, stderr, status := runCaptured(t, argv...)
		if status != 0 {
			t.Fatalf("%s --help exited %d: %s", path, status, stderr)
		}
		firstLine, _, _ := strings.Cut(stdout, "\n")
		if !strings.Contains(firstLine, "网页") && !strings.Contains(firstLine, "打开") {
			t.Errorf("%s help's first line does not mark the page: %q", path, firstLine)
		}
		if !strings.Contains(stdout, "网页") {
			t.Errorf("%s help does not mention the web:\n%s", path, stdout)
		}
		want := "命令不执行"
		if path == "skill delete" {
			want = "由人在网页上确认删除"
		}
		if !strings.Contains(stdout, want) {
			t.Errorf("%s help does not say %q:\n%s", path, want, stdout)
		}
		if path != "skill delete" && !strings.Contains(stdout, "确认在网页上由人完成") {
			t.Errorf("%s help does not say who confirms:\n%s", path, stdout)
		}
	}
}

// **The allowed branch's JSON**: the code says "confirm", and the message carries the exact command.
// Nothing is written until that command is run.
func TestDeleteWithoutYesJSONCarriesTheConfirmationCommand(t *testing.T) {
	stub := stubPlanes(t, 0,
		`{"skill":{"namespace":"dev","name":"x","address":"dev/x","access":"admin",`+
			`"owned":true,"offline":false,"ever_published":false},"versions":[],"counts":{},"next":null}`, 0, "")

	err := skillVerb(context.Background(), []string{"delete", "dev/x", "--json"})
	var failure *cliFailure
	if !errors.As(err, &failure) || failure.Code != "confirmation_required" || !failure.JSON {
		t.Fatalf("allowed delete --json = %#v", err)
	}
	if !strings.Contains(failure.Message, "skmr skill delete dev/x --yes") {
		t.Errorf("the refusal does not carry the command: %q", failure.Message)
	}
	if len(stub.calls) != 1 || !strings.HasPrefix(stub.calls[0], "GET ") {
		t.Errorf("calls = %v, want one read and no write", stub.calls)
	}
}

// **A caller who can see the skill but does not own it** is refused before any command is printed.
func TestDeleteWithoutYesRefusesANonOwner(t *testing.T) {
	stubPlanes(t, 0,
		`{"skill":{"namespace":"dev","name":"x","address":"dev/x","access":"browse",`+
			`"owned":false,"offline":false,"ever_published":false},"versions":[],"counts":{},"next":null}`, 0, "")

	err := skillVerb(context.Background(), []string{"delete", "dev/x", "--json"})
	var failure *cliFailure
	if !errors.As(err, &failure) || failure.Code != "insufficient_permission" {
		t.Fatalf("delete by a non-owner = %#v", err)
	}
	if strings.Contains(failure.Message, "--yes") {
		t.Errorf("a non-owner was handed a command: %q", failure.Message)
	}
}

// **The consumer plane can rule somebody out** even when the author plane refuses: a rung below
// `admin` cannot be the owner.
func TestDeleteWithoutYesRefusesBelowAdminFromTheConsumerPlane(t *testing.T) {
	stub := stubPlanes(t, http.StatusForbidden, forbiddenBody, 0,
		`{"namespace":{"slug":"dev"},"name":"x","access":"write","version":{}}`)

	err := skillVerb(context.Background(), []string{"delete", "dev/x", "--json"})
	var failure *cliFailure
	if !errors.As(err, &failure) || failure.Code != "insufficient_permission" {
		t.Fatalf("delete by a write caller = %#v", err)
	}
	// **The fallback is the mechanism under test**: the author plane refused, so the consumer plane
	// had to answer, and its rung is the one the message names.
	if len(stub.calls) != 2 || !strings.HasSuffix(stub.calls[1], "/metadata") {
		t.Errorf("calls = %v, want an author read then the consumer read", stub.calls)
	}
	if !strings.Contains(failure.Message, "写入") {
		t.Errorf("the refusal does not name the consumer rung: %q", failure.Message)
	}
}

// **When the precheck cannot answer, the code says so** — not "add the switch" (ADR 0128).
func TestDeleteWithoutYesSaysThePrecheckWasUnavailable(t *testing.T) {
	stubPlanes(t, http.StatusInternalServerError, `{"error":{"code":"internal_error","message":"no"}}`,
		http.StatusInternalServerError, `{"error":{"code":"internal_error","message":"no"}}`)

	err := skillVerb(context.Background(), []string{"delete", "dev/x", "--json"})
	var failure *cliFailure
	if !errors.As(err, &failure) || failure.Code != "precheck_unavailable" {
		t.Fatalf("unreadable delete --json = %#v", err)
	}
	if strings.Contains(failure.Message, "--yes") {
		t.Errorf("the unknown branch handed out a command: %q", failure.Message)
	}
}

// **The help offers the safe command, not the one-step one** (ADR 0128; 0056 is the browser-side
// idea behind it). The confirmation
// switch is printed by the command itself, after it has said what the delete would do — help that
// listed it would make the one-step delete the first thing somebody copies.
func TestConfirmationHelpDoesNotMentionTheSwitch(t *testing.T) {
	pointAtServer(t, func(http.ResponseWriter, *http.Request) { t.Error("help sent a request") })

	for _, c := range []struct {
		argv []string
		safe string
	}{
		{[]string{"skill", "delete", "--help"}, "skmr skill delete dev/typo-skill"},
		{[]string{"version", "discard", "--help"}, "skmr version discard dev/pdf-tools@a1b2c3"},
	} {
		stdout, stderr, status := runCaptured(t, c.argv...)
		if status != 0 {
			t.Fatalf("%v exited %d: %s", c.argv, status, stderr)
		}
		if strings.Contains(stdout, "--yes") || strings.Contains(stderr, "--yes") {
			t.Errorf("%v advertises the confirmation switch:\n%s%s", c.argv, stdout, stderr)
		}
		if !strings.Contains(stdout, c.safe) {
			t.Errorf("%v does not show the safe command:\n%s", c.argv, stdout)
		}
	}
}

func TestDeleteWithoutYesExplainsAndSendsNoWrite(t *testing.T) {
	wrote := false
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			wrote = true
		}
		_, _ = w.Write([]byte(factsPage(false)))
	})
	t.Setenv(config.ApiKeyEnv, "skm_test")

	var err error
	out := captureStdout(t, func() {
		err = skillVerb(context.Background(), []string{"delete", "dev/x"})
	})
	if err == nil {
		t.Fatal("delete without --yes reported success")
	}
	if wrote {
		t.Fatal("delete without --yes sent a write")
	}
	for _, want := range []string{"skmr skill delete dev/x --yes", "名字不会释放"} {
		if !strings.Contains(out, want) {
			t.Errorf("output is missing %q:\n%s", want, out)
		}
	}
}

func TestDeleteOfAPublishedSkillPointsAtThePage(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(factsPage(true)))
	})
	t.Setenv(config.ApiKeyEnv, "skm_test")

	var err error
	out := captureStdout(t, func() {
		err = skillVerb(context.Background(), []string{"delete", "dev/x"})
	})
	if err == nil || !strings.Contains(err.Error(), "网页") {
		t.Fatalf("delete of a published skill = %v", err)
	}
	if !strings.Contains(err.Error(), "/skills/dev/x?tab=settings") {
		t.Errorf("the page address is missing: %v", err)
	}
	if strings.Contains(out, "--yes") || strings.Contains(err.Error(), "--yes") {
		t.Errorf("a published skill must not suggest --yes: %v / %s", err, out)
	}
}

func TestDeleteWithYesDeletesANeverPublishedSkill(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/delete") {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		_, _ = w.Write([]byte(factsPage(false)))
	})
	t.Setenv(config.ApiKeyEnv, "skm_test")

	var err error
	out := captureStdout(t, func() {
		err = skillVerb(context.Background(), []string{"delete", "dev/x", "--yes"})
	})
	if err != nil {
		t.Fatalf("delete --yes: %v", err)
	}
	if !strings.Contains(out, "已删除 dev/x") {
		t.Errorf("output = %q", out)
	}
}

func TestDeleteWithYesReportsTheServersRefusals(t *testing.T) {
	cases := []struct {
		status     int
		code       string
		want       string
		serverCode string
	}{
		{http.StatusConflict, "skill_published", "上线过", "skill_published"},
		{http.StatusForbidden, "forbidden", "权限", "forbidden"},
		{http.StatusNotFound, "skill_not_found", "找不到", "skill_not_found"},
	}
	for _, c := range cases {
		pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
			if r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/delete") {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(c.status)
				_, _ = w.Write([]byte(`{"error":{"code":"` + c.code + `","message":"no"}}`))
				return
			}
			_, _ = w.Write([]byte(factsPage(false)))
		})
		t.Setenv(config.ApiKeyEnv, "skm_test")

		err := skillVerb(context.Background(), []string{"delete", "dev/x", "--yes", "--json"})
		if err == nil {
			t.Fatalf("%d reported success", c.status)
		}
		if !strings.Contains(err.Error(), c.want) {
			t.Errorf("%d: %v, want it to contain %q", c.status, err, c.want)
		}
		var failure *cliFailure
		if !errors.As(err, &failure) || failure.ServerCode != c.serverCode {
			t.Errorf("%d: server_code = %#v, want %q", c.status, failure, c.serverCode)
		}
	}
}

// The JSON half of a page action: one object, and `performed` is false.
func TestAPageActionSaysItDidNotPerformInJSON(t *testing.T) {
	previous := openBrowser
	openBrowser = func(string) error { return nil }
	t.Cleanup(func() { openBrowser = previous })
	pointAtServer(t, func(http.ResponseWriter, *http.Request) { t.Error("a request was sent") })
	t.Setenv(config.ApiKeyEnv, "")

	var err error
	out := captureStdout(t, func() {
		err = versionVerb(context.Background(),
			[]string{"publish", "dev/pdf-tools@d1", "--json"})
	})
	if err != nil {
		t.Fatalf("publish --json: %v", err)
	}
	var payload struct {
		Action    string `json:"action"`
		Performed bool   `json:"performed"`
		URL       string `json:"url"`
		Opened    bool   `json:"opened"`
	}
	if err := json.Unmarshal([]byte(out), &payload); err != nil {
		t.Fatalf("output %q: %v", out, err)
	}
	if payload.Action != "publish" || payload.Performed || !payload.Opened {
		t.Errorf("payload = %+v", payload)
	}
	if !strings.Contains(payload.URL, "sub=actions") {
		t.Errorf("url = %q", payload.URL)
	}
}

// **The no-credential path**: with nothing to precheck against, every page action opens its page and
// says the action has not happened (ADR 0126). The address is printed so it can be copied.
//
// **Two destinations, because the actions live on two pages** (ADR 0072): publish / rollback /
// revoke / restore are the version's own page; offline / online / delete are the skill's settings.
// (The delete case is in `skill_delete.go` and has its own tests — it is not a page hand-off.)
func TestPageActionsOpenThePageAndSayNothingHappened(t *testing.T) {
	var opened []string
	previous := openBrowser
	openBrowser = func(target string) error { opened = append(opened, target); return nil }
	t.Cleanup(func() { openBrowser = previous })
	pointAtServer(t, func(http.ResponseWriter, *http.Request) { t.Error("a page action made a request") })
	t.Setenv(config.ApiKeyEnv, "")

	for _, verb := range []string{"publish", "rollback", "revoke", "restore"} {
		var err error
		out := captureStdout(t, func() {
			err = versionVerb(context.Background(), []string{verb, "dev/pdf-tools@draft:01J"})
		})
		if err != nil {
			t.Fatalf("%s: %v", verb, err)
		}
		if !strings.Contains(out, "还没执行：") {
			t.Errorf("%s output does not open with the sentence: %q", verb, out)
		}
		if !strings.Contains(out, "tab=versions") || !strings.Contains(out, "sub=actions") {
			t.Errorf("%s opened %q", verb, out)
		}
	}
	if len(opened) != 4 {
		t.Fatalf("opened %d pages, want 4: %v", len(opened), opened)
	}
}

// Every refusal ends with exactly one copyable command, and the command names the page that owns
// the action. `publish` relays the bump; the other version-level verbs do not accept one; the three
// skill-level verbs keep pointing at the settings page (ADR 0072) and drop the version pin.
func TestPageActionsRefuseWhenTheRungIsTooLow(t *testing.T) {
	opened := 0
	previous := openBrowser
	openBrowser = func(string) error { opened++; return nil }
	t.Cleanup(func() { openBrowser = previous })
	pointAtServer(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"skill":{"namespace":"dev","name":"x","address":"dev/x",` +
			`"access":"write","owned":false},"version":{"pin":"d1","status":"draft"}}`))
	})
	t.Setenv(config.ApiKeyEnv, "skm_test")

	err := versionVerb(context.Background(), []string{"publish", "dev/x@d1", "--json"})
	var failure *cliFailure
	if !errors.As(err, &failure) || failure.Code != "insufficient_permission" {
		t.Fatalf("publish by a write caller = %#v, want insufficient_permission", err)
	}
	if !strings.Contains(failure.Message, "维护") {
		t.Errorf("the refusal does not name the rung: %q", failure.Message)
	}
	if opened != 0 {
		t.Fatal("a page was opened for a caller who cannot act on it")
	}
}

func TestPageActionsRefuseWhenTheStateIsWrong(t *testing.T) {
	opened := 0
	previous := openBrowser
	openBrowser = func(string) error { opened++; return nil }
	t.Cleanup(func() { openBrowser = previous })
	pointAtServer(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"skill":{"namespace":"dev","name":"x","address":"dev/x",` +
			`"access":"maintain","owned":false},"version":{"pin":"1.0.0","status":"live"}}`))
	})
	t.Setenv(config.ApiKeyEnv, "skm_test")

	err := versionVerb(context.Background(), []string{"revoke", "dev/x@1.0.0", "--json"})
	var failure *cliFailure
	if !errors.As(err, &failure) || failure.Code != "wrong_version_state" {
		t.Fatalf("revoking a live version = %#v, want wrong_version_state", err)
	}
	if opened != 0 {
		t.Fatal("a page was opened for an action the page does not offer")
	}
}

// The refusal now takes arguments apart instead of silently dropping them: `--bump` belongs to
// publish alone, duplicates are refused, and a second address is a typo, not a hint. The usage error
// still names the page that owns the action — including the settings page for skill-level verbs.
func TestPageActionsRefuseFlagsTheyDoNotTake(t *testing.T) {
	cases := [][]string{
		{"rollback", "dev/pdf-tools@d1", "--bump", "minor"},
		{"publish", "dev/pdf-tools@d1", "--bump", "foo"},
		{"publish", "dev/pdf-tools@d1", "--bump", "minor", "--bump", "major"},
		{"publish", "dev/pdf-tools@d1", "--what"},
		{"publish", "dev/pdf-tools@d1", "dev/pdf-tools@d2"},
	}
	for _, args := range cases {
		err := versionVerb(context.Background(), args)
		var failure *cliFailure
		if !errors.As(err, &failure) || failure.Code != "usage" {
			t.Errorf("%v = %#v, want a usage failure", args, err)
		}
	}
}

// **A usage error still gets the shape the caller asked for.** `--json` is read before the parse,
// so a script that asked for one object receives one even when the command line is wrong.
func TestPageActionUsageErrorsKeepTheJSONShape(t *testing.T) {
	err := versionVerb(context.Background(), []string{"publish", "dev/x@d1", "--what", "--json"})
	var failure *cliFailure
	if !errors.As(err, &failure) || !failure.JSON {
		t.Fatalf("a usage error after --json = %#v, want a JSON failure", err)
	}
}

// The pin is written into `?version=` in the console's own grammar: a legacy `draft:` prefix is
// stripped (ADR 0063), or the page selects no row and says 「没有这一版」.
func TestAPageActionOpensTheBareRowIDForALegacyDraftPin(t *testing.T) {
	var opened string
	previous := openBrowser
	openBrowser = func(target string) error { opened = target; return nil }
	t.Cleanup(func() { openBrowser = previous })
	pointAtServer(t, func(http.ResponseWriter, *http.Request) { t.Error("a request was sent") })
	t.Setenv(config.ApiKeyEnv, "")

	var err error
	captureStdout(t, func() {
		err = versionVerb(context.Background(),
			[]string{"publish", "dev/x@draft:01JKMNPQRSTVWXYZ0123456789", "--bump", "minor"})
	})
	if err != nil {
		t.Fatalf("publish: %v", err)
	}
	if !strings.Contains(opened, "version=01JKMNPQRSTVWXYZ0123456789&") {
		t.Errorf("the page address is not the bare row id: %q", opened)
	}
	if strings.Contains(opened, "draft") {
		t.Errorf("the legacy prefix reached the page address: %q", opened)
	}
	if !strings.Contains(opened, "bump=minor") {
		t.Errorf("the bump choice was dropped: %q", opened)
	}
}

// **Precheck below `browse`.** The author plane answers 403 there; the consumer plane still says
// which rung the caller holds, and that answer is enough to refuse a page action they cannot take.
// planeStub is what a precheck did to the server: every call, method included. **A page action
// writes nothing**, so a non-GET is a test failure rather than a count.
type planeStub struct {
	reads int
	calls []string
}

func stubPlanes(t *testing.T, authorStatus int, authorBody string, consumerStatus int, consumerBody string) *planeStub {
	t.Helper()
	stub := &planeStub{}
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		stub.calls = append(stub.calls, r.Method+" "+r.URL.Path)
		if r.Method == http.MethodGet {
			stub.reads++
		} else {
			t.Errorf("a page action sent %s %s; it must only read", r.Method, r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/metadata"):
			w.WriteHeader(statusOr(consumerStatus))
			_, _ = w.Write([]byte(consumerBody))
		default:
			w.WriteHeader(statusOr(authorStatus))
			_, _ = w.Write([]byte(authorBody))
		}
	})
	return stub
}

func statusOr(status int) int {
	if status == 0 {
		return http.StatusOK
	}
	return status
}

const forbiddenBody = `{"error":{"code":"forbidden","message":"needs browse"}}`

func TestAPageActionRefusesBelowBrowse(t *testing.T) {
	opened := 0
	previous := openBrowser
	openBrowser = func(string) error { opened++; return nil }
	t.Cleanup(func() { openBrowser = previous })
	stubPlanes(t, http.StatusForbidden, forbiddenBody, 0,
		`{"namespace":{"slug":"dev"},"name":"x","access":"invoke","version":{}}`)

	_, stderr, status := runCaptured(t, "version", "publish", "dev/x@d1")
	if status != 1 {
		t.Fatalf("publish by an invoke caller exited %d", status)
	}
	if opened != 0 {
		t.Fatal("a page was opened for a caller who cannot act on it")
	}
	for _, want := range []string{"维护", "调用"} {
		if !strings.Contains(stderr, want) {
			t.Errorf("the refusal does not name %q:\n%s", want, stderr)
		}
	}
}

// An owner-only action is refused when the fallback rung already rules the caller out, and opened
// when it does not: `admin` cannot tell an owner from an admin grant, so the page decides.
func TestAnOwnerActionUsesTheFallbackOnlyWhenItCanTell(t *testing.T) {
	opened := 0
	previous := openBrowser
	openBrowser = func(string) error { opened++; return nil }
	t.Cleanup(func() { openBrowser = previous })

	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/metadata") {
			_, _ = w.Write([]byte(`{"namespace":{"slug":"dev"},"name":"x","access":"browse","version":{}}`))
			return
		}
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte(forbiddenBody))
	})
	_, stderr, status := runCaptured(t, "skill", "offline", "dev/x")
	if status != 1 || !strings.Contains(stderr, "所有者") {
		t.Fatalf("offline by a browse caller = %d %q", status, stderr)
	}
	if opened != 0 {
		t.Fatal("a page was opened for a caller who cannot act on it")
	}
	// **Ownership is not a rung** (ADR 0047): it must not be called a 「档」.
	if strings.Contains(stderr, "档") {
		t.Errorf("the refusal calls ownership a rung: %q", stderr)
	}

	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/metadata") {
			_, _ = w.Write([]byte(`{"namespace":{"slug":"dev"},"name":"x","access":"admin","version":{}}`))
			return
		}
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte(forbiddenBody))
	})
	_, stderr, status = runCaptured(t, "skill", "offline", "dev/x")
	if status != 0 || opened != 1 {
		t.Fatalf("offline by an admin caller = %d, opened %d: %s", status, opened, stderr)
	}
	if !strings.Contains(stderr, "没能预检") {
		t.Errorf("the incomplete precheck was not said out loud: %q", stderr)
	}
}

// **Refusing an open on a version the caller cannot see.** The author plane answers 404 for a row
// its visibility filter removed; the consumer plane proves the skill itself is readable, so the 404
// is about this version and the page would say 「没有这一版」.
func TestVersionOpenRefusesAPinTheCallerCannotSee(t *testing.T) {
	opened := 0
	previous := openBrowser
	openBrowser = func(string) error { opened++; return nil }
	t.Cleanup(func() { openBrowser = previous })
	stubPlanes(t, http.StatusNotFound, `{"error":{"code":"not_found","message":"no"}}`, 0,
		`{"namespace":{"slug":"dev"},"name":"x","access":"browse","version":{}}`)

	_, stderr, status := runCaptured(t, "version", "open", "dev/x@d1")
	if status != 1 || opened != 0 {
		t.Fatalf("version open on an invisible pin = %d, opened %d", status, opened)
	}
	for _, want := range []string{"看不到这一版", "草稿", "写入"} {
		if !strings.Contains(stderr, want) {
			t.Errorf("the refusal does not explain %q:\n%s", want, stderr)
		}
	}
}

// `skill open` is gated per section (ADR 0051): 信息 and 版本 exist at every rung, 文件 needs
// `browse`, 设置 needs `admin`.
func TestSkillOpenUsesTheSectionsOwnRung(t *testing.T) {
	previous := openBrowser
	opened := 0
	openBrowser = func(string) error { opened++; return nil }
	t.Cleanup(func() { openBrowser = previous })
	stubPlanes(t, http.StatusForbidden, forbiddenBody, 0,
		`{"namespace":{"slug":"dev"},"name":"x","access":"browse","version":{}}`)

	_, stderr, status := runCaptured(t, "skill", "open", "dev/x", "--tab", "settings")
	if status != 1 || opened != 0 {
		t.Fatalf("settings at browse = %d, opened %d", status, opened)
	}
	for _, want := range []string{"设置", "管理"} {
		if !strings.Contains(stderr, want) {
			t.Errorf("the refusal does not name %q:\n%s", want, stderr)
		}
	}

	_, _, status = runCaptured(t, "skill", "open", "dev/x", "--tab", "info")
	if status != 0 || opened != 1 {
		t.Fatalf("info at browse = %d, opened %d", status, opened)
	}
}

// **Both reads can fail without the page being lost.** A 500 on each plane is "could not precheck":
// the page still opens, and the caller is told.
func TestAPageActionOpensWhenBothReadsFail(t *testing.T) {
	opened := 0
	previous := openBrowser
	openBrowser = func(string) error { opened++; return nil }
	t.Cleanup(func() { openBrowser = previous })
	stub := stubPlanes(t, http.StatusInternalServerError, `{"error":{"code":"internal","message":"no"}}`,
		http.StatusInternalServerError, `{"error":{"code":"internal","message":"no"}}`)

	_, stderr, status := runCaptured(t, "version", "publish", "dev/x@d1")
	if status != 0 || opened != 1 {
		t.Fatalf("publish with both reads failing = %d, opened %d", status, opened)
	}
	if !strings.Contains(stderr, "没能预检") {
		t.Errorf("the incomplete precheck was not said out loud: %q", stderr)
	}
	if len(stub.calls) != 2 {
		t.Errorf("calls = %v, want the author read then the consumer read", stub.calls)
	}
}

// **`version open` on a pin neither plane can answer for** opens and says so — the 404 is not
// enough to mean "that version is invisible" when the skill itself could not be checked either.
func TestVersionOpenOpensWhenBothPlanesAnswer404(t *testing.T) {
	opened := 0
	previous := openBrowser
	openBrowser = func(string) error { opened++; return nil }
	t.Cleanup(func() { openBrowser = previous })
	notFound := `{"error":{"code":"not_found","message":"no"}}`
	stubPlanes(t, http.StatusNotFound, notFound, http.StatusNotFound, notFound)

	_, stderr, status := runCaptured(t, "version", "open", "dev/x@d1")
	if status != 0 || opened != 1 {
		t.Fatalf("version open with both planes 404 = %d, opened %d", status, opened)
	}
	if !strings.Contains(stderr, "没能预检") {
		t.Errorf("the incomplete precheck was not said out loud: %q", stderr)
	}
}

// **A skill both planes answer 404 for is still opened** (ADR 0127 §决定 5): the answer is not a
// rung, and the browser's own session is what the page will judge the person by. The command says
// that the precheck did not complete.
func TestOpenStillOpensWhenNeitherPlaneCanSeeTheSkill(t *testing.T) {
	opened := 0
	previous := openBrowser
	openBrowser = func(string) error { opened++; return nil }
	t.Cleanup(func() { openBrowser = previous })
	pointAtServer(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"error":{"code":"skill_not_found","message":"no"}}`))
	})

	_, stderr, status := runCaptured(t, "skill", "open", "dev/x")
	if status != 0 || opened != 1 {
		t.Fatalf("open of an unseen skill = %d, opened %d", status, opened)
	}
	if !strings.Contains(stderr, "没能预检") {
		t.Errorf("the incomplete precheck was not said out loud: %q", stderr)
	}
}

// **The version group's "allowed" path**: the author plane says maintain, the draft is publishable,
// so the page opens — and the first line still says nothing happened.
func TestAPageActionOpensWhenTheAuthorPlaneAllowsIt(t *testing.T) {
	opened := ""
	previous := openBrowser
	openBrowser = func(target string) error { opened = target; return nil }
	t.Cleanup(func() { openBrowser = previous })
	stub := stubPlanes(t, 0,
		`{"skill":{"namespace":"dev","name":"x","address":"dev/x","access":"maintain",`+
			`"owned":false,"offline":false,"ever_published":false},`+
			`"version":{"pin":"d1","status":"draft"}}`, 0, "")

	stdout, stderr, status := runCaptured(t, "version", "publish", "dev/x@d1")
	if status != 0 || opened == "" {
		t.Fatalf("publish by a maintain caller = %d, opened %q", status, opened)
	}
	if !strings.Contains(stdout, "还没执行：") {
		t.Errorf("the first line does not say nothing happened:\n%s", stdout)
	}
	// **The author plane answered**, so there was no fallback and nothing to warn about. Without
	// this, a malformed fixture would silently exercise the "could not precheck" branch instead.
	if stub.reads != 1 || len(stub.calls) != 1 {
		t.Errorf("calls = %v, want one author read", stub.calls)
	}
	if strings.Contains(stderr, "没能预检") {
		t.Errorf("the precheck did not complete: %q", stderr)
	}
}

// **One read per page action when the author plane answers**, and no writes.
func TestAPageActionReadsOnceAndWritesNothing(t *testing.T) {
	previous := openBrowser
	openBrowser = func(string) error { return nil }
	t.Cleanup(func() { openBrowser = previous })
	reads := stubPlanes(t, 0, factsPage(false), 0, "")

	_, _, status := runCaptured(t, "skill", "offline", "dev/x")
	if status != 0 {
		t.Fatalf("offline exited %d", status)
	}
	if reads.reads != 1 {
		t.Fatalf("the page action made %d reads, want 1", reads.reads)
	}
}

// **The batch action has no address and no precheck**: it opens the queue page, sends nothing, and
// says nothing happened.
func TestSkillsPublishOpensTheQueueWithoutReading(t *testing.T) {
	opened := ""
	previous := openBrowser
	openBrowser = func(target string) error { opened = target; return nil }
	t.Cleanup(func() { openBrowser = previous })
	pointAtServer(t, func(http.ResponseWriter, *http.Request) { t.Error("a request was sent") })

	stdout, _, status := runCaptured(t, "skills", "publish")
	if status != 0 {
		t.Fatalf("skills publish exited %d", status)
	}
	if !strings.HasSuffix(opened, "/publish") {
		t.Errorf("opened %q", opened)
	}
	if !strings.Contains(stdout, "还没执行：") {
		t.Errorf("the first line does not say nothing happened:\n%s", stdout)
	}
}

func TestSkillsPublishJSONIsTheMachineContract(t *testing.T) {
	previous := openBrowser
	openBrowser = func(string) error { return nil }
	t.Cleanup(func() { openBrowser = previous })
	pointAtServer(t, func(http.ResponseWriter, *http.Request) { t.Error("a request was sent") })

	stdout, _, status := runCaptured(t, "skills", "publish", "--json")
	if status != 0 {
		t.Fatalf("skills publish --json exited %d", status)
	}
	var payload struct {
		Action    string `json:"action"`
		Performed bool   `json:"performed"`
		Opened    bool   `json:"opened"`
	}
	if err := json.Unmarshal([]byte(stdout), &payload); err != nil {
		t.Fatalf("output %q: %v", stdout, err)
	}
	if payload.Action != "publish-batch" || payload.Performed || !payload.Opened {
		t.Errorf("payload = %+v", payload)
	}
}

// **Every page action refuses below the rung it needs**, including the ones the other tests do not
// drive: the fallback read answers `invoke`, and that is enough to say which rung is missing.
func TestEveryPageActionRefusesBelowBrowse(t *testing.T) {
	opened := 0
	previous := openBrowser
	openBrowser = func(string) error { opened++; return nil }
	t.Cleanup(func() { openBrowser = previous })
	stubPlanes(t, http.StatusForbidden, forbiddenBody, 0,
		`{"namespace":{"slug":"dev"},"name":"x","access":"invoke","version":{}}`)

	for _, argv := range [][]string{
		{"version", "rollback", "dev/x@1.0.0"},
		{"version", "revoke", "dev/x@1.0.0"},
		{"version", "restore", "dev/x@1.0.0"},
		{"skill", "online", "dev/x"},
		{"skill", "visibility", "dev/x"},
	} {
		_, stderr, status := runCaptured(t, argv...)
		if status != 1 {
			t.Errorf("%v exited %d", argv, status)
		}
		if opened != 0 {
			t.Fatalf("%v opened a page it cannot act on", argv)
		}
		if !strings.Contains(stderr, "调用") && !strings.Contains(stderr, "所有者") {
			t.Errorf("%v does not name the rung: %q", argv, stderr)
		}
	}
}

// **One read, one open**: the seven addressed page actions each read the author plane exactly once
// when it answers, and none of them writes.
func TestEveryPageActionReadsOnceWhenTheAuthorPlaneAnswers(t *testing.T) {
	previous := openBrowser
	openBrowser = func(string) error { return nil }
	t.Cleanup(func() { openBrowser = previous })

	const skill = `"namespace":"dev","name":"x","address":"dev/x"`
	cases := []struct {
		argv []string
		body string
	}{
		{[]string{"version", "publish", "dev/x@d1"}, `{"skill":{` + skill + `,"access":"maintain","owned":true,"offline":false,"ever_published":false},"version":{"pin":"d1","status":"draft"}}`},
		{[]string{"version", "rollback", "dev/x@1.0.0"}, `{"skill":{` + skill + `,"access":"maintain","owned":true,"offline":false,"ever_published":true},"version":{"pin":"1.0.0","status":"live"}}`},
		{[]string{"version", "revoke", "dev/x@1.0.0"}, `{"skill":{` + skill + `,"access":"maintain","owned":true,"offline":false,"ever_published":true},"version":{"pin":"1.0.0","status":"history"}}`},
		{[]string{"version", "restore", "dev/x@1.0.0"}, `{"skill":{` + skill + `,"access":"maintain","owned":true,"offline":false,"ever_published":true},"version":{"pin":"1.0.0","status":"revoked"}}`},
		{[]string{"skill", "offline", "dev/x"}, `{"skill":{` + skill + `,"access":"admin","owned":true,"offline":false,"ever_published":true},"versions":[],"counts":{},"next":null}`},
		{[]string{"skill", "online", "dev/x"}, `{"skill":{` + skill + `,"access":"admin","owned":true,"offline":true,"ever_published":true},"versions":[],"counts":{},"next":null}`},
		{[]string{"skill", "visibility", "dev/x"}, `{"skill":{` + skill + `,"access":"admin","owned":false,"offline":false,"ever_published":false},"versions":[],"counts":{},"next":null}`},
	}
	for _, c := range cases {
		stub := stubPlanes(t, 0, c.body, 0, "")
		_, stderr, status := runCaptured(t, c.argv...)
		if status != 0 {
			t.Errorf("%v exited %d: %s", c.argv, status, stderr)
		}
		// **The author plane, once** — a consumer-only read would also be one GET, and would mean
		// the precheck never saw the rung it is supposed to judge.
		if len(stub.calls) != 1 || !strings.HasPrefix(stub.calls[0], "GET /api/v1/author/skills/dev/x/versions") {
			t.Errorf("%v calls = %v, want one author-plane read", c.argv, stub.calls)
		}
	}
}

func TestSkillDeleteSaysWhenItCouldNotTellWhetherTheSkillWasPublished(t *testing.T) {
	pointAtServer(t, func(http.ResponseWriter, *http.Request) { t.Error("a request was sent") })
	t.Setenv(config.ApiKeyEnv, "")

	stdout, stderr, status := runCaptured(t, "skill", "delete", "dev/x")
	if status != 1 {
		t.Fatalf("delete without --yes exited %d", status)
	}
	// The refusal is an error, so it lands on stderr like every other one.
	if !strings.Contains(stderr, "没能确认") {
		t.Errorf("the unknown fact was not said to be unknown:\n%s", stderr)
	}
	if strings.Contains(stderr, "它从未上线") {
		t.Errorf("an unverified fact was asserted:\n%s", stderr)
	}
	if strings.Contains(stdout+stderr, "--yes") || strings.Contains(stdout+stderr, "确认后执行") {
		t.Errorf("a command was offered without knowing the delete is allowed:\n%s%s", stdout, stderr)
	}
	if !strings.Contains(stderr, "/skills/dev/x?tab=settings") {
		t.Errorf("the web confirmation is not offered:\n%s", stderr)
	}
}

func TestDeleteWithYesPrintsTheJSONContract(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/delete") {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		_, _ = w.Write([]byte(factsPage(false)))
	})

	stdout, _, status := runCaptured(t, "skill", "delete", "dev/x", "--yes", "--json")
	if status != 0 {
		t.Fatalf("delete --yes --json exited %d", status)
	}
	var payload struct {
		Action    string `json:"action"`
		Performed bool   `json:"performed"`
		Address   string `json:"address"`
		Deleted   bool   `json:"deleted"`
	}
	if err := json.Unmarshal([]byte(stdout), &payload); err != nil {
		t.Fatalf("output %q: %v", stdout, err)
	}
	if payload.Action != "delete" || !payload.Performed || !payload.Deleted || payload.Address != "dev/x" {
		t.Errorf("payload = %+v", payload)
	}
}

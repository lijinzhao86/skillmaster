package main

// v14's CLI contract (PRD #27–#30, #39–#53). The tests run through `run`, so what they assert is the
// process result — stdout, stderr, exit status, and the requests that were (or were not) sent — rather
// than a helper's return value.

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

// recordedRequest is one request the fake server saw.
type recordedRequest struct {
	Method string
	Path   string
	Query  url.Values
	Body   string
}

// fakeServer is a stand-in for the v14 endpoints, recording everything so a test can assert both the
// answer and the request that produced it.
type fakeServer struct {
	requests []recordedRequest
	answer   func(recordedRequest) (int, string)
}

// newFakeServer starts the server and points the CLI at it, with an API key in the environment so no
// test reaches a login.
func newFakeServer(t *testing.T, answer func(recordedRequest) (int, string)) *fakeServer {
	t.Helper()
	withTempHome(t)
	fake := &fakeServer{answer: answer}
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		request := recordedRequest{
			Method: r.Method,
			Path:   r.URL.Path,
			Query:  r.URL.Query(),
			Body:   string(body),
		}
		fake.requests = append(fake.requests, request)
		status, payload := fake.answer(request)
		if status == 0 {
			status = http.StatusOK
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		if payload != "" {
			_, _ = w.Write([]byte(payload))
		}
	})
	return fake
}

// runCaptured is the process contract: stdout, stderr and the status `main` would exit with.
func runCaptured(t *testing.T, args ...string) (stdout, stderr string, status int) {
	t.Helper()
	var err error
	stdout = captureStdout(t, func() {
		stderr = captureStderr(t, func() {
			err = run(context.Background(), args)
			if err != nil {
				status = reportError(err)
			}
		})
	})
	return stdout, stderr, status
}

func mustRun(t *testing.T, args ...string) string {
	t.Helper()
	stdout, stderr, status := runCaptured(t, args...)
	if status != 0 {
		t.Fatalf("%v failed (%d):\nstdout:\n%s\nstderr:\n%s", args, status, stdout, stderr)
	}
	return stdout
}

func wantsRequests(t *testing.T, fake *fakeServer, method, path string) []recordedRequest {
	t.Helper()
	var found []recordedRequest
	for _, request := range fake.requests {
		if request.Method == method && request.Path == path {
			found = append(found, request)
		}
	}
	return found
}

// ---------------------------------------------------------------------------
// #27 skills list
// ---------------------------------------------------------------------------

const listCards = `{"skills":[
 {"id":"1","name":"pdf-tools","namespace":"dev","description":"把 PDF 拆页、合并与压缩。",
  "when_to_use":"需要拆分、合并或压缩 PDF 时。","source":"joined","state":"live","version":"1.2.3",
  "updated_at":"2026-10-20T09:12:00Z",
  "category":{"key":"doc","name":"文档与演示"},
  "public_tags":[{"key":"pdf","name":"PDF"},{"key":"batch","name":"批量处理"}],
  "my_tags":[{"id":"01T","name":"客户项目"}]},
 {"id":"2","name":"bare","namespace":"dev"}
],"next_cursor":"next"}`

func TestListDefaultsToTheConsumableLibraryAndSaysThereIsMore(t *testing.T) {
	fake := newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, listCards
	})

	out := mustRun(t, "skills", "list")

	if len(fake.requests) != 1 || fake.requests[0].Path != "/api/v1/skills" {
		t.Fatalf("requests = %+v", fake.requests)
	}
	query := fake.requests[0].Query
	if query.Get("scope") != "library" || query.Get("show") != "live" ||
		query.Get("sort") != "recent" || query.Get("limit") != "50" {
		t.Errorf("default query = %v", query)
	}

	want := "dev/pdf-tools\n" +
		"    把 PDF 拆页、合并与压缩。 - 需要拆分、合并或压缩 PDF 时。\n" +
		"dev/bare\n"
	if !strings.HasPrefix(out, want) {
		t.Errorf("cards = %q, want prefix %q", out, want)
	}
	if !strings.Contains(out, "还有更多") || !strings.Contains(out, "skills list --all") {
		t.Errorf("a truncated page must say so and offer --all:\n%s", out)
	}
	for _, forbidden := range []string{"客户项目", "来源：", "状态：", "公开标签："} {
		if strings.Contains(out, forbidden) {
			t.Errorf("the default card printed %q:\n%s", forbidden, out)
		}
	}
}

func TestListVerboseAddsOneLineAndKeepsTheCard(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[{"name":"pdf-tools","namespace":"dev",
			"description":"把 PDF 拆页。","when_to_use":"需要拆分时。","source":"joined","state":"live",
			"version":"1.2.3","updated_at":"2026-10-20T09:12:00Z",
			"category":{"key":"doc","name":"文档与演示"},
			"public_tags":[{"key":"pdf","name":"PDF"}],
			"my_tags":[{"id":"01T","name":"客户项目"}]}],"next_cursor":null}`
	})
	out := mustRun(t, "skills", "list", "--verbose")
	want := "dev/pdf-tools\n" +
		"    把 PDF 拆页。 - 需要拆分时。\n" +
		"    来源：我加入的 · 状态：在用 · 版本：1.2.3 · 更新于：2026-10-20 · 分类：文档与演示 · 公开标签：PDF\n"
	if !strings.Contains(out, want) {
		t.Errorf("verbose card:\n%s\nwant it to contain:\n%s", out, want)
	}
	if strings.Contains(out, "客户项目") {
		t.Errorf("--verbose printed my tags, which only the console and skill tags read:\n%s", out)
	}
}

func TestListDeletedSaysWhenAndNeverPrintsTags(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[{"name":"gone","namespace":"dev","state":"deleted",
			"deleted_at":"2026-09-30T08:00:00Z","updated_at":"2026-08-01T00:00:00Z"}],
			"next_cursor":null}`
	})

	out := mustRun(t, "skills", "list", "--show", "deleted", "--verbose")
	if !strings.Contains(out, "删除于：2026-09-30") {
		t.Errorf("the deleted view does not say when it was deleted:\n%s", out)
	}
	if strings.Contains(out, "更新于") {
		t.Errorf("the deleted view reported an update date:\n%s", out)
	}
}

func TestListSendsFilterNamesOnEveryPage(t *testing.T) {
	fake := newFakeServer(t, func(r recordedRequest) (int, string) {
		if r.Query.Get("cursor") == "" {
			return http.StatusOK, `{"skills":[],"next_cursor":"c1"}`
		}
		return http.StatusOK, `{"skills":[],"next_cursor":null}`
	})

	mustRun(t, "skills", "list", "压缩", "--all",
		"--category", "文档与演示", "--my-tag", "客户项目", "--public-tag", "pdf",
		"--source", "joined", "--source", "created", "--sort", "name", "--show", "all")

	pages := wantsRequests(t, fake, http.MethodGet, "/api/v1/skills")
	if len(pages) != 2 {
		t.Fatalf("--all read %d pages", len(pages))
	}
	for _, page := range pages {
		query := page.Query
		// **Names, not keys or ids.** The API plane resolves them (TD §接口); sending a key here
		// would be a filter that selects nothing.
		if query.Get("q") != "压缩" || query.Get("category") != "文档与演示" ||
			query.Get("my_tag") != "客户项目" || query.Get("public_tag") != "pdf" ||
			query.Get("sort") != "name" || query.Get("show") != "all" ||
			query.Get("limit") != "50" {
			t.Errorf("page query = %v", query)
		}
		if got := query["source"]; len(got) != 2 || got[0] != "joined" || got[1] != "created" {
			t.Errorf("sources = %v", got)
		}
	}
	if pages[0].Query.Get("cursor") != "" || pages[1].Query.Get("cursor") != "c1" {
		t.Errorf("the cursor did not travel: %v", pages[1].Query)
	}
}

// An unknown name is an empty page on the server (a filter narrows, it does not fail), so the
// answer adds the pointer to the vocabulary that names the words (PRD #27).
func TestListUnknownFilterNamesAnswerWithTheVocabulary(t *testing.T) {
	for _, test := range []struct {
		name  string
		args  []string
		wants string
	}{
		{name: "未知分类", args: []string{"skills", "list", "--category", "不存在的分类"}, wants: "skills categories"},
		{name: "未知我的标签", args: []string{"skills", "list", "--my-tag", "不存在的标签"}, wants: "skills my-tags"},
		{name: "未知公开标签", args: []string{"skills", "list", "--public-tag", "不存在的标签"}, wants: "skills public-tags"},
	} {
		t.Run(test.name, func(t *testing.T) {
			fake := newFakeServer(t, func(recordedRequest) (int, string) {
				return http.StatusOK, `{"skills":[],"next_cursor":null}`
			})
			out := mustRun(t, test.args...)
			if !strings.Contains(out, "没有匹配的 skill") || !strings.Contains(out, test.wants) {
				t.Errorf("output does not answer with the empty page and %q:\n%s", test.wants, out)
			}
			if len(wantsRequests(t, fake, http.MethodGet, "/api/v1/skills")) != 1 {
				t.Errorf("requests = %+v", fake.requests)
			}
		})
	}
}

func TestListDeletedRefusalsAndTheImpossibleSource(t *testing.T) {
	fake := newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[],"next_cursor":null}`
	})

	stdout, stderr, status := runCaptured(t, "skills", "list", "--show", "deleted", "--category", "文档与演示")
	if status == 0 || stdout != "" || !strings.Contains(stderr, "不看标签") {
		t.Errorf("status=%d stdout=%q stderr=%q", status, stdout, stderr)
	}

	fake.requests = nil
	out, _, status := runCaptured(t, "skills", "list", "--show", "deleted", "--source", "joined")
	if status != 0 {
		t.Fatalf("an impossible source is an empty answer, not a failure: %d", status)
	}
	if !strings.Contains(out, "只接受 created") {
		t.Errorf("output = %q", out)
	}
	if len(fake.requests) != 0 {
		t.Errorf("an answer that needs no server sent %d requests", len(fake.requests))
	}
}

// ---------------------------------------------------------------------------
// #28 skills search
// ---------------------------------------------------------------------------

func TestSearchDefaultsToTheLibrary(t *testing.T) {
	fake := newFakeServer(t, func(r recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[
		 {"name":"slides","namespace":"ann","description":"做 PPT。","source":"shared",
		  "state":"live","version":"2.0.0","updated_at":"2026-10-01T00:00:00Z",
		  "public_tags":[{"key":"ppt","name":"PPT"}]},
		 {"name":"elsewhere","namespace":"bob","description":"别人的。"}],
		 "next_cursor":"more"}`
	})

	out := mustRun(t, "skills", "search", "做PPT", "--category", "文档与演示", "--public-tag", "PPT", "--verbose")

	pages := wantsRequests(t, fake, http.MethodGet, "/api/v1/skills")
	if len(pages) != 1 {
		t.Fatalf("search read %d pages; it is one page by contract", len(pages))
	}
	query := pages[0].Query
	// v19 (PRD #13): a bare search asks the caller's own library, ten rows, semantically — the
	// question people actually type is about their own tools, not about the public catalogue.
	if query.Get("scope") != "library" || query.Get("q") != "做PPT" ||
		query.Get("category") != "文档与演示" || query.Get("public_tag") != "PPT" ||
		query.Get("limit") != "10" || query.Get("semantic") != "true" {
		t.Errorf("search query = %v", query)
	}
	for _, forbidden := range []string{"show", "sort", "source", "my_tag"} {
		if query.Has(forbidden) {
			t.Errorf("search sent %s", forbidden)
		}
	}
	if !strings.Contains(out, "还有更多") {
		t.Errorf("a truncated search must say so:\n%s", out)
	}
	if !strings.Contains(out, "来源：共享给我的") {
		t.Errorf("an in-library result does not show its source:\n%s", out)
	}
	if strings.Count(out, "来源：") != 1 {
		t.Errorf("a result outside the library showed a source:\n%s", out)
	}
	if strings.Contains(out, "我的标签") {
		t.Errorf("search printed my tags:\n%s", out)
	}
}

func TestSearchRefusesTheLibraryOnlyFlagsBeforeAnyRequest(t *testing.T) {
	for _, args := range [][]string{
		{"skills", "search", "--scope", "public", "--my-tag", "客户项目"},
		{"skills", "search", "--all"},
		{"skills", "search", "--sort", "name"},
		{"skills", "search", "--show", "live"},
		{"skills", "search", "--source", "created"},
	} {
		fake := newFakeServer(t, func(recordedRequest) (int, string) {
			return http.StatusOK, `{"skills":[],"next_cursor":null}`
		})
		stdout, stderr, status := runCaptured(t, args...)
		if status == 0 || stdout != "" || stderr == "" {
			t.Errorf("%v was accepted (status=%d stdout=%q stderr=%q)", args, status, stdout, stderr)
		}
		if len(fake.requests) != 0 {
			t.Errorf("%v sent %d requests", args, len(fake.requests))
		}
	}
}

func TestSearchEmptyStillPointsAtTheVocabulary(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[],"next_cursor":null}`
	})
	out := mustRun(t, "skills", "search", "--public-tag", "没有这个词")
	if !strings.Contains(out, "没有匹配的 skill") || !strings.Contains(out, "skills public-tags") {
		t.Errorf("an empty search does not name the vocabulary:\n%s", out)
	}
}

func TestSearchNeedsOneOfItsThreeInputs(t *testing.T) {
	stdout, stderr, status := runCaptured(t, "skills", "search")
	if status == 0 || stdout != "" || !strings.Contains(stderr, "至少一个") {
		t.Errorf("status=%d stdout=%q stderr=%q", status, stdout, stderr)
	}
}

// ---------------------------------------------------------------------------
// #29 / #39 the vocabularies
// ---------------------------------------------------------------------------

func TestCategoriesPrintsTheVocabularyInOrderAndMarksStopped(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"categories":[
		 {"key":"doc","name":"文档与演示","state":"enabled","public_total":12},
		 {"key":"ops","name":"运维与云","state":"stopped","public_total":0}]}`
	})

	out := mustRun(t, "skills", "categories", "--no-login")
	want := "| 分类 | 公域 |\n|---|---|\n| 文档与演示 | 12 |\n| 运维与云（停用） | 0 |\n"
	if out != want {
		t.Errorf("categories =\n%q\nwant\n%q", out, want)
	}

	if _, _, status := runCaptured(t, "skills", "categories", "文档"); status == 0 {
		t.Error("skills categories accepted a query")
	}
}

func TestPublicTagsPagesSaysThereIsMoreAndAllTakesEverything(t *testing.T) {
	pages := map[string]string{
		"":   `{"tags":[{"key":"batch","name":"批量处理","public_total":4},{"key":"pdf","name":"PDF","public_total":7}],"next_cursor":"c1"}`,
		"c1": `{"tags":[{"key":"n8n","name":"n8n","public_total":1}],"next_cursor":null}`,
	}
	fake := newFakeServer(t, func(r recordedRequest) (int, string) {
		return http.StatusOK, pages[r.Query.Get("cursor")]
	})

	out := mustRun(t, "skills", "public-tags")
	want := "| 公开标签 | 公域 |\n|---|---|\n| PDF | 7 |\n| 批量处理 | 4 |\n"
	if !strings.HasPrefix(out, want) {
		t.Errorf("page one =\n%s\nwant prefix\n%s", out, want)
	}
	if !strings.Contains(out, "还有更多") || !strings.Contains(out, "public-tags --all") {
		t.Errorf("a truncated vocabulary must offer --all:\n%s", out)
	}

	fake.requests = nil
	out = mustRun(t, "skills", "public-tags", "--all")
	for _, name := range []string{"| PDF | 7 |", "| 批量处理 | 4 |", "| n8n | 1 |"} {
		if !strings.Contains(out, name) {
			t.Errorf("--all is missing %q:\n%s", name, out)
		}
	}
	if strings.Contains(out, "还有更多") {
		t.Errorf("--all still claims there is more:\n%s", out)
	}
	if len(fake.requests) != 2 || fake.requests[1].Query.Get("cursor") != "c1" ||
		fake.requests[0].Query.Get("limit") != "50" {
		t.Errorf("requests = %+v", fake.requests)
	}
}

func TestPublicTagsEmptyStillDrawsTheHeaderAndASentence(t *testing.T) {
	fake := newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"tags":[],"next_cursor":null}`
	})
	out := mustRun(t, "skills", "public-tags", "没有这个词")
	if got := fake.requests[0].Query.Get("q"); got != "没有这个词" {
		t.Errorf("the search term did not reach the server: %q", got)
	}
	want := "| 公开标签 | 公域 |\n|---|---|\n"
	if !strings.HasPrefix(out, want) || !strings.Contains(out, "没有匹配的公开标签") {
		t.Errorf("empty vocabulary =\n%s", out)
	}
}

func TestMyTagsPrintsUsageCountsAndDescriptions(t *testing.T) {
	fake := newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"tags":[
		 {"id":"2","name":"n8n","description":null,"used_in":1},
		 {"id":"1","name":"客户项目","description":"交付期内客户随时要用的 skill","used_in":6}]}`
	})

	out := mustRun(t, "skills", "my-tags", "交付")
	want := "| 标签 | 用在 | 说明 |\n|---|---|---|\n" +
		"| 客户项目 | 6 个 skill 上 | 交付期内客户随时要用的 skill |\n" +
		"| n8n | 1 个 skill 上 |  |\n"
	if out != want {
		t.Errorf("my tags =\n%q\nwant\n%q", out, want)
	}
	if got := wantsRequests(t, fake, http.MethodGet, "/api/v1/me/tags"); len(got) != 1 ||
		got[0].Query.Get("q") != "交付" {
		t.Errorf("the search term did not reach the server: %+v", got)
	}
}

// ---------------------------------------------------------------------------
// #46 / #48 the definition writes
// ---------------------------------------------------------------------------

func TestMyTagsAddCreatesADefinitionAndSaysWhatItDid(t *testing.T) {
	for _, test := range []struct {
		name string
		args []string
		body map[string]any
		want string
	}{
		{
			name: "没有说明",
			args: []string{"skills", "my-tags", "add", "客户项目"},
			body: map[string]any{"name": "客户项目"},
			want: "已新建标签「客户项目」。\n",
		},
		{
			name: "带说明",
			args: []string{"skills", "my-tags", "add", "客户项目", "--describe", "交付期内用"},
			body: map[string]any{"name": "客户项目", "description": "交付期内用"},
			want: "已新建标签「客户项目」，并写了说明。\n",
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			fake := newFakeServer(t, func(r recordedRequest) (int, string) {
				return http.StatusCreated, `{"id":"01T","name":"客户项目","used_in":0}`
			})
			out := mustRun(t, test.args...)
			if out != test.want {
				t.Errorf("output = %q, want %q", out, test.want)
			}
			posts := wantsRequests(t, fake, http.MethodPost, "/api/v1/me/tags")
			if len(posts) != 1 {
				t.Fatalf("posts = %+v", posts)
			}
			var body map[string]any
			if err := json.Unmarshal([]byte(posts[0].Body), &body); err != nil {
				t.Fatalf("body: %v", err)
			}
			if len(body) != len(test.body) {
				t.Errorf("body = %v, want %v", body, test.body)
			}
			for key, value := range test.body {
				if body[key] != value {
					t.Errorf("body[%q] = %v, want %v", key, body[key], value)
				}
			}
		})
	}
}

func TestMyTagsRenameResolvesTheOldNameToItsID(t *testing.T) {
	fake := newFakeServer(t, func(r recordedRequest) (int, string) {
		if r.Path == "/api/v1/me/tags" {
			return http.StatusOK, `{"tags":[{"id":"01TAG","name":"客户项目","used_in":3}]}`
		}
		return http.StatusOK, `{"changed":true}`
	})

	out := mustRun(t, "skills", "my-tags", "rename", "客户项目", "--name", "重点项目")
	if out != "已改名：「客户项目」→「重点项目」。\n" {
		t.Errorf("output = %q", out)
	}
	patches := wantsRequests(t, fake, http.MethodPatch, "/api/v1/me/tags/01TAG")
	if len(patches) != 1 {
		t.Fatalf("patches = %+v", patches)
	}
	if patches[0].Body != `{"name":"重点项目"}` {
		t.Errorf("body = %s", patches[0].Body)
	}
}

func TestMyTagsDescribeWritesAndClears(t *testing.T) {
	for _, test := range []struct {
		name string
		args []string
		body string
		want string
	}{
		{
			name: "写说明",
			args: []string{"skills", "my-tags", "describe", "客户项目", "--text", "交付期内用"},
			body: `{"description":"交付期内用"}`,
			want: "已更新「客户项目」的说明。\n",
		},
		{
			name: "清空",
			args: []string{"skills", "my-tags", "describe", "客户项目", "--text", ""},
			body: `{"description":""}`,
			want: "已清空「客户项目」的说明。\n",
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			fake := newFakeServer(t, func(r recordedRequest) (int, string) {
				if r.Path == "/api/v1/me/tags" {
					return http.StatusOK, `{"tags":[{"id":"01TAG","name":"客户项目","used_in":3}]}`
				}
				return http.StatusOK, `{"changed":true}`
			})
			out := mustRun(t, test.args...)
			if out != test.want {
				t.Errorf("output = %q, want %q", out, test.want)
			}
			patches := wantsRequests(t, fake, http.MethodPatch, "/api/v1/me/tags/01TAG")
			if len(patches) != 1 || patches[0].Body != test.body {
				t.Errorf("patches = %+v, want body %s", patches, test.body)
			}
		})
	}
}

func TestMyTagsDoesNotGuessAMissingDefinition(t *testing.T) {
	for _, args := range [][]string{
		{"skills", "my-tags", "rename", "没有这个", "--name", "新名"},
		{"skills", "my-tags", "describe", "没有这个", "--text", "说明"},
	} {
		fake := newFakeServer(t, func(r recordedRequest) (int, string) {
			return http.StatusOK, `{"tags":[]}`
		})
		stdout, stderr, status := runCaptured(t, args...)
		if status == 0 || stdout != "" {
			t.Errorf("%v: status=%d stdout=%q", args, status, stdout)
		}
		if !strings.Contains(stderr, "skills my-tags") {
			t.Errorf("%v does not point at the definitions:\n%s", args, stderr)
		}
		for _, request := range fake.requests {
			if request.Method == http.MethodPatch {
				t.Errorf("%v wrote to a definition it could not find", args)
			}
		}
	}
}

func TestMyTagsNameConflictIsReadAsTheServersIssue(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusBadRequest, `{"error":{"code":"invalid_request","message":"taken",
			"details":[{"field":"name","issue":"name_taken"}]}}`
	})
	_, stderr, status := runCaptured(t, "skills", "my-tags", "add", "客户项目")
	if status == 0 || !strings.Contains(stderr, "同一个名字已经有了") ||
		!strings.Contains(stderr, "skill tag add") {
		t.Errorf("status=%d stderr=%q", status, stderr)
	}
}

func TestTagDefinitionDeletionStaysInTheConsole(t *testing.T) {
	for _, args := range [][]string{
		{"skills", "my-tags", "delete", "客户项目"},
		{"skill", "tag", "rename", "dev/pdf-tools", "旧名"},
		{"skill", "tag", "describe", "dev/pdf-tools", "名字"},
		{"skills", "discover"},
		{"skills", "add", "dev/pdf-tools"},
	} {
		fake := newFakeServer(t, func(recordedRequest) (int, string) {
			return http.StatusOK, `{"skills":[],"next_cursor":null}`
		})
		stdout, stderr, status := runCaptured(t, args...)
		if status == 0 || stdout != "" || stderr == "" {
			t.Errorf("%v was accepted (status=%d stdout=%q stderr=%q)", args, status, stdout, stderr)
		}
		if len(fake.requests) != 0 {
			t.Errorf("%v sent %d requests", args, len(fake.requests))
		}
	}
}

// ---------------------------------------------------------------------------
// #40 skill tags
// ---------------------------------------------------------------------------

const threeGroups = `{
 "category":{"key":"doc","name":"文档与演示"},
 "public_tags":[{"key":"batch","name":"批量处理"},{"key":"pdf","name":"PDF"}],
 "my_tags":{"readable":true,"reason":null,"tags":[{"id":"01T","name":"客户项目"}]}}`

func TestSkillTagsPrintsThreeGroupsInOrder(t *testing.T) {
	fake := newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, threeGroups
	})
	out := mustRun(t, "skill", "tags", "dev/pdf-tools")
	want := "| 组 | 名字 |\n|---|---|\n" +
		"| 分类 | 文档与演示 |\n" +
		"| 公开标签 | PDF |\n" +
		"| 公开标签 | 批量处理 |\n" +
		"| 我的标签 | 客户项目 |\n"
	if out != want {
		t.Errorf("skill tags =\n%q\nwant\n%q", out, want)
	}
	if got := wantsRequests(t, fake, http.MethodGet, "/api/v1/skills/dev/pdf-tools/tags"); len(got) != 1 {
		t.Errorf("requests = %+v", fake.requests)
	}
}

func TestSkillTagsExplainsAnUnreadablePrivateGroup(t *testing.T) {
	for _, test := range []struct {
		name   string
		reason string
		want   string
	}{
		{name: "不在库", reason: "not_in_library", want: "不在你的库里"},
		{name: "休眠", reason: "dormant", want: "不生效"},
	} {
		t.Run(test.name, func(t *testing.T) {
			newFakeServer(t, func(recordedRequest) (int, string) {
				return http.StatusOK, `{"category":null,"public_tags":[],
					"my_tags":{"readable":false,"reason":"` + test.reason + `","tags":[]}}`
			})
			out := mustRun(t, "skill", "tags", "dev/pdf-tools")
			if !strings.HasPrefix(out, "| 组 | 名字 |\n|---|---|\n") {
				t.Errorf("the table header is missing:\n%s", out)
			}
			if !strings.Contains(out, test.want) {
				t.Errorf("the reason is missing %q:\n%s", test.want, out)
			}
			if strings.Contains(out, "还没有分类或标签") {
				t.Errorf("an unreadable group used the empty sentence:\n%s", out)
			}
		})
	}
}

func TestSkillTagsEmptyAndEscaped(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"category":null,"public_tags":[],
			"my_tags":{"readable":true,"reason":null,"tags":[]}}`
	})
	out := mustRun(t, "skill", "tags", "dev/pdf-tools")
	if out != "| 组 | 名字 |\n|---|---|\n这个 skill 还没有分类或标签。\n" {
		t.Errorf("empty skill tags = %q", out)
	}

	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"category":null,
			"public_tags":[{"key":"a|b","name":"a|b"}],
			"my_tags":{"readable":false,"reason":"not_in_library","tags":[]}}`
	})
	out = mustRun(t, "skill", "tags", "dev/pdf-tools")
	if !strings.Contains(out, `| 公开标签 | a\|b |`) {
		t.Errorf("a pipe in a name broke the table:\n%s", out)
	}
}

// ---------------------------------------------------------------------------
// #41 skill tag add / remove
// ---------------------------------------------------------------------------

func TestTagAddAndRemoveAddressTheRightLayer(t *testing.T) {
	for _, test := range []struct {
		name   string
		args   []string
		method string
		path   string
		body   string
		want   string
	}{
		{
			name:   "贴我的标签",
			args:   []string{"skill", "tag", "add", "dev/pdf-tools", "客户项目"},
			method: http.MethodPost,
			path:   "/api/v1/skills/dev/pdf-tools/tags/my",
			body:   `{"name":"客户项目"}`,
			want:   "已把「客户项目」贴到 dev/pdf-tools。\n",
		},
		{
			name:   "贴公开标签",
			args:   []string{"skill", "tag", "add", "dev/pdf-tools", "PDF", "--public"},
			method: http.MethodPost,
			path:   "/api/v1/skills/dev/pdf-tools/tags/public",
			body:   `{"name":"PDF"}`,
			want:   "已把公开标签「PDF」贴到 dev/pdf-tools。\n",
		},
		{
			name:   "摘我的标签",
			args:   []string{"skill", "tag", "remove", "dev/pdf-tools", "客户项目"},
			method: http.MethodDelete,
			path:   "/api/v1/skills/dev/pdf-tools/tags/my",
			want:   "已从 dev/pdf-tools 摘掉「客户项目」。\n",
		},
		{
			name:   "摘公开标签",
			args:   []string{"skill", "tag", "remove", "dev/pdf-tools", "PDF", "--public"},
			method: http.MethodDelete,
			path:   "/api/v1/skills/dev/pdf-tools/tags/public",
			want:   "已从 dev/pdf-tools 摘掉公开标签「PDF」。\n",
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			fake := newFakeServer(t, func(recordedRequest) (int, string) {
				return http.StatusNoContent, ""
			})
			out := mustRun(t, test.args...)
			if out != test.want {
				t.Errorf("output = %q, want %q", out, test.want)
			}
			got := wantsRequests(t, fake, test.method, test.path)
			if len(got) != 1 {
				t.Fatalf("requests = %+v", fake.requests)
			}
			if test.body != "" && got[0].Body != test.body {
				t.Errorf("body = %s, want %s", got[0].Body, test.body)
			}
			if test.method == http.MethodDelete && got[0].Query.Get("name") == "" {
				t.Errorf("the tag name did not travel: %v", got[0].Query)
			}
		})
	}
}

func TestTagAddSaysWhenItWasAlreadyThere(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"changed":false}`
	})
	out := mustRun(t, "skill", "tag", "add", "dev/pdf-tools", "客户项目")
	if !strings.Contains(out, "已经贴过") {
		t.Errorf("a no-op did not say so: %q", out)
	}
}

// A 403 that means "add it to your library first" must not be answered with the v4 permission
// sentence — another credential would not help, and the server already said what would (PRD #12).
func TestPrivateTagOutsideTheLibrarySaysToJoinFirst(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusForbidden, `{"error":{"code":"forbidden",
			"message":"先把 dev/pdf-tools 加入你的库：skmr skill join dev/pdf-tools",
			"details":[{"field":"reason","issue":"not_in_library"}]}}`
	})
	stdout, stderr, status := runCaptured(t, "skill", "tag", "add", "dev/pdf-tools", "客户项目")
	if status == 0 || stdout != "" {
		t.Fatalf("status=%d stdout=%q", status, stdout)
	}
	if !strings.Contains(stderr, "skill join dev/pdf-tools") {
		t.Errorf("the next step was replaced by a permission sentence:\n%s", stderr)
	}
}

func TestTagRemoveWithNothingAttachedIsAReadable404(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusNotFound, `{"error":{"code":"tag_not_attached","message":"not attached"}}`
	})
	stdout, stderr, status := runCaptured(t, "skill", "tag", "remove", "dev/pdf-tools", "客户项目")
	if status == 0 || stdout != "" || !strings.Contains(stderr, "没有这个标签") {
		t.Errorf("status=%d stdout=%q stderr=%q", status, stdout, stderr)
	}
}

// A dormant entry is a 409 rather than a 403: the skill is in the library, the entry just does not
// work right now (PRD §错误形状).
func TestPrivateTagOnADormantEntrySaysWhy(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusConflict, `{"error":{"code":"library_entry_dormant","message":"dormant"}}`
	})
	stdout, stderr, status := runCaptured(t, "skill", "tag", "add", "dev/pdf-tools", "客户项目")
	if status == 0 || stdout != "" || !strings.Contains(stderr, "不生效") {
		t.Errorf("status=%d stdout=%q stderr=%q", status, stdout, stderr)
	}
}

// ---------------------------------------------------------------------------
// #42 skill category set / clear
// ---------------------------------------------------------------------------

func TestCategorySetResolvesTheWordToItsKeyAndReplaces(t *testing.T) {
	fake := newFakeServer(t, func(r recordedRequest) (int, string) {
		if r.Path == "/api/v1/categories" {
			return http.StatusOK, `{"categories":[
			 {"key":"doc","name":"文档与演示","state":"enabled","public_total":3},
			 {"key":"ops","name":"运维与云","state":"stopped","public_total":0}]}`
		}
		return http.StatusOK, `{"changed":true}`
	})

	out := mustRun(t, "skill", "category", "set", "dev/pdf-tools", "文档与演示")
	if out != "已把 dev/pdf-tools 放到「文档与演示」。\n" {
		t.Errorf("output = %q", out)
	}
	puts := wantsRequests(t, fake, http.MethodPut, "/api/v1/skills/dev/pdf-tools/category")
	if len(puts) != 1 || puts[0].Body != `{"key":"doc"}` {
		t.Errorf("puts = %+v", puts)
	}
}

func TestCategorySetRefusesAWordOutsideTheVocabulary(t *testing.T) {
	fake := newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"categories":[{"key":"doc","name":"文档与演示","state":"enabled","public_total":3}]}`
	})
	stdout, stderr, status := runCaptured(t, "skill", "category", "set", "dev/pdf-tools", "不存在的分类")
	if status == 0 || stdout != "" || !strings.Contains(stderr, "skills categories") {
		t.Errorf("status=%d stdout=%q stderr=%q", status, stdout, stderr)
	}
	if len(wantsRequests(t, fake, http.MethodPut, "/api/v1/skills/dev/pdf-tools/category")) != 0 {
		t.Error("a key was invented for a word that is not in the vocabulary")
	}
}

func TestCategorySetSurfacesTheVocabularysRefusals(t *testing.T) {
	for issue, want := range map[string]string{
		"category_stopped": "已停用",
		"category_retired": "已经退役",
	} {
		newFakeServer(t, func(r recordedRequest) (int, string) {
			if r.Path == "/api/v1/categories" {
				return http.StatusOK, `{"categories":[{"key":"ops","name":"运维与云","state":"stopped","public_total":0}]}`
			}
			return http.StatusBadRequest, `{"error":{"code":"invalid_request","message":"refused",
				"details":[{"field":"key","issue":"` + issue + `"}]}}`
		})
		stdout, stderr, status := runCaptured(t, "skill", "category", "set", "dev/pdf-tools", "运维与云")
		if status == 0 || stdout != "" || !strings.Contains(stderr, want) {
			t.Errorf("%s: status=%d stdout=%q stderr=%q", issue, status, stdout, stderr)
		}
	}
}

func TestCategoryClearAndItsNoOp(t *testing.T) {
	for _, test := range []struct {
		name    string
		changed string
		want    string
	}{
		{name: "清除了", changed: "true", want: "已清除 dev/pdf-tools 的分类。\n"},
		{name: "本来就没有", changed: "false", want: "本来就没有分类，没有需要再做的：dev/pdf-tools\n"},
	} {
		t.Run(test.name, func(t *testing.T) {
			newFakeServer(t, func(recordedRequest) (int, string) {
				return http.StatusOK, `{"changed":` + test.changed + `}`
			})
			if out := mustRun(t, "skill", "category", "clear", "dev/pdf-tools"); out != test.want {
				t.Errorf("output = %q, want %q", out, test.want)
			}
		})
	}
}

// ---------------------------------------------------------------------------
// #44 skill join / leave
// ---------------------------------------------------------------------------

func TestJoinAndLeaveRefuseWithoutYesAndSendNothing(t *testing.T) {
	for _, test := range []struct {
		verb        string
		consequence string
	}{
		{verb: "join", consequence: "不复制内容"},
		{verb: "leave", consequence: "我的标签会休眠"},
	} {
		t.Run(test.verb, func(t *testing.T) {
			fake := newFakeServer(t, func(recordedRequest) (int, string) {
				return http.StatusNoContent, ""
			})
			stdout, stderr, status := runCaptured(t, "skill", test.verb, "dev/pdf-tools")
			if status == 0 {
				t.Fatal("a write without --yes succeeded")
			}
			if !strings.Contains(stdout, test.consequence) {
				t.Errorf("the consequence is missing:\n%s", stdout)
			}
			want := "skmr skill " + test.verb + " dev/pdf-tools --yes"
			if !strings.Contains(stdout, want) {
				t.Errorf("the full command is missing %q:\n%s", want, stdout)
			}
			if !strings.Contains(stderr, "--yes") {
				t.Errorf("the refusal does not name --yes:\n%s", stderr)
			}
			if len(fake.requests) != 0 {
				t.Errorf("a refused write sent %d requests", len(fake.requests))
			}
		})
	}
}

func TestJoinAndLeaveSendTheReference(t *testing.T) {
	for _, test := range []struct {
		verb   string
		method string
		body   string
		want   string
	}{
		{verb: "join", method: http.MethodPost, body: "", want: "已把 dev/pdf-tools 收进你的库。\n"},
		{verb: "leave", method: http.MethodDelete, want: "已把 dev/pdf-tools 移出你的库。\n"},
	} {
		t.Run(test.verb, func(t *testing.T) {
			fake := newFakeServer(t, func(recordedRequest) (int, string) {
				return http.StatusNoContent, test.body
			})
			out := mustRun(t, "skill", test.verb, "dev/pdf-tools", "--yes")
			if out != test.want {
				t.Errorf("output = %q, want %q", out, test.want)
			}
			if got := wantsRequests(t, fake, test.method, "/api/v1/skills/dev/pdf-tools/library"); len(got) != 1 {
				t.Errorf("requests = %+v", fake.requests)
			}
		})
	}
}

func TestJoinAlreadyInTheLibrarySaysSo(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"changed":false}`
	})
	if out := mustRun(t, "skill", "join", "dev/pdf-tools", "--yes"); out != "已经在你的库里。\n" {
		t.Errorf("output = %q", out)
	}
}

func TestJoinAndLeaveRefusalsAreNamedByCode(t *testing.T) {
	for _, test := range []struct {
		name   string
		verb   string
		status int
		code   string
		want   string
	}{
		{name: "已在库里", verb: "join", status: http.StatusConflict, code: "skill_not_joinable", want: "加入不了"},
		{name: "没有引用", verb: "leave", status: http.StatusConflict, code: "library_reference_missing", want: "没有可移除的加入引用"},
		{name: "看不见", verb: "join", status: http.StatusNotFound, code: "skill_not_found", want: "找不到这个 skill"},
	} {
		t.Run(test.name, func(t *testing.T) {
			newFakeServer(t, func(recordedRequest) (int, string) {
				return test.status, `{"error":{"code":"` + test.code + `","message":"refused"}}`
			})
			stdout, stderr, status := runCaptured(t, "skill", test.verb, "dev/pdf-tools", "--yes")
			if status == 0 || stdout != "" || !strings.Contains(stderr, test.want) {
				t.Errorf("status=%d stdout=%q stderr=%q", status, stdout, stderr)
			}
		})
	}
}

// ---------------------------------------------------------------------------
// #53 skill show
// ---------------------------------------------------------------------------

func TestSkillShowPrintsTheFieldsInOrderAndOmitsTheEmptyOnes(t *testing.T) {
	fake := newFakeServer(t, func(r recordedRequest) (int, string) {
		switch r.Path {
		case "/api/v1/skills/dev/pdf-tools/metadata":
			return http.StatusOK, `{"namespace":{"slug":"dev"},"name":"pdf-tools",
				"description":"把 PDF 拆页、合并与压缩。",
				"when_to_use":"需要拆分、合并或压缩 PDF 时。",
				"compatibility":"需要 Python 3.10+ 与 poppler。",
				"version":{"name":"1.2.3","digest":"sha256:abc"}}`
		case "/api/v1/skills/dev/pdf-tools/tags":
			return http.StatusOK, threeGroups
		}
		return http.StatusNotFound, `{}`
	})

	out := mustRun(t, "skill", "show", "dev/pdf-tools")
	want := "dev/pdf-tools\n" +
		"  把 PDF 拆页、合并与压缩。\n" +
		"  适用场景：需要拆分、合并或压缩 PDF 时。\n" +
		"  运行环境：需要 Python 3.10+ 与 poppler。\n" +
		"  版本：1.2.3\n" +
		"  分类：文档与演示\n" +
		"  公开标签：PDF、批量处理\n" +
		"  我的标签：客户项目\n"
	if out != want {
		t.Errorf("skill show =\n%q\nwant\n%q", out, want)
	}
	if len(fake.requests) != 2 {
		t.Errorf("skill show is two reads, got %d", len(fake.requests))
	}

	newFakeServer(t, func(r recordedRequest) (int, string) {
		if r.Path == "/api/v1/skills/dev/pdf-tools/metadata" {
			// No version object at all: every field this command could print is empty.
			return http.StatusOK, `{"namespace":{"slug":"dev"},"name":"pdf-tools"}`
		}
		return http.StatusOK, `{"category":null,"public_tags":[],
			"my_tags":{"readable":true,"reason":null,"tags":[]}}`
	})
	if out := mustRun(t, "skill", "show", "dev/pdf-tools"); out != "dev/pdf-tools\n" {
		t.Errorf("an all-empty skill printed more than its address: %q", out)
	}
}

func TestSkillShowExplainsAnUnreadablePrivateGroup(t *testing.T) {
	newFakeServer(t, func(r recordedRequest) (int, string) {
		if r.Path == "/api/v1/skills/dev/pdf-tools/metadata" {
			return http.StatusOK, `{"namespace":{"slug":"dev"},"name":"pdf-tools"}`
		}
		return http.StatusOK, `{"category":{"key":"doc","name":"文档与演示"},"public_tags":[],
			"my_tags":{"readable":false,"reason":"dormant","tags":[]}}`
	})
	out := mustRun(t, "skill", "show", "dev/pdf-tools")
	if !strings.Contains(out, "  我的标签：") || !strings.Contains(out, "不生效") {
		t.Errorf("the reason is missing:\n%s", out)
	}
}

// ---------------------------------------------------------------------------
// #52 skill restore
// ---------------------------------------------------------------------------

func TestRestorePostsAndRefusesAPin(t *testing.T) {
	fake := newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusNoContent, ""
	})
	if out := mustRun(t, "skill", "restore", "dev/pdf-tools"); out != "已恢复 dev/pdf-tools。\n" {
		t.Errorf("output = %q", out)
	}
	if got := wantsRequests(t, fake, http.MethodPost, "/api/v1/skills/dev/pdf-tools/restore"); len(got) != 1 {
		t.Errorf("requests = %+v", fake.requests)
	}

	stdout, stderr, status := runCaptured(t, "skill", "restore", "dev/pdf-tools@1.2.3")
	if status == 0 || stdout != "" || !strings.Contains(stderr, "@版本") {
		t.Errorf("a pin was accepted: status=%d stdout=%q stderr=%q", status, stdout, stderr)
	}
}

// ---------------------------------------------------------------------------
// #6 / #43 --no-login, --json and help
// ---------------------------------------------------------------------------

// The seven read commands accept `--no-login`, and with no credential they fail **before any
// request** with the one next step that works.
func TestTheSevenReadCommandsFailFastWithoutCredentials(t *testing.T) {
	withTempHome(t)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Errorf("an unauthenticated --no-login command sent %s %s", r.Method, r.URL.Path)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.ApiKeyEnv, "")

	for _, args := range [][]string{
		{"skills", "list", "--no-login"},
		{"skills", "search", "--category", "文档与演示", "--no-login"},
		{"skills", "categories", "--no-login"},
		{"skills", "public-tags", "--no-login"},
		{"skills", "my-tags", "--no-login"},
		{"skill", "tags", "dev/pdf-tools", "--no-login"},
		{"skill", "show", "dev/pdf-tools", "--no-login"},
	} {
		stdout, stderr, status := runCaptured(t, args...)
		if status == 0 || stdout != "" {
			t.Errorf("%v: status=%d stdout=%q", args, status, stdout)
		}
		if !strings.Contains(stderr, "skmr login") || !strings.Contains(stderr, "--no-login") {
			t.Errorf("%v does not point at the login:\n%s", args, stderr)
		}
	}
}

func TestEveryNewCommandRefusesJSON(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[],"next_cursor":null}`
	})
	for _, args := range [][]string{
		{"skills", "list", "--json"},
		{"skills", "search", "x", "--json"},
		{"skills", "categories", "--json"},
		{"skills", "public-tags", "--json"},
		{"skills", "my-tags", "--json"},
		{"skills", "my-tags", "add", "x", "--json"},
		{"skills", "my-tags", "rename", "x", "--name", "y", "--json"},
		{"skills", "my-tags", "describe", "x", "--text", "", "--json"},
		{"skill", "tags", "dev/pdf-tools", "--json"},
		{"skill", "tag", "add", "dev/pdf-tools", "x", "--json"},
		{"skill", "tag", "remove", "dev/pdf-tools", "x", "--json"},
		{"skill", "category", "set", "dev/pdf-tools", "文档与演示", "--json"},
		{"skill", "category", "clear", "dev/pdf-tools", "--json"},
		{"skill", "join", "dev/pdf-tools", "--json"},
		{"skill", "leave", "dev/pdf-tools", "--json"},
		{"skill", "show", "dev/pdf-tools", "--json"},
		{"skill", "restore", "dev/pdf-tools", "--json"},
	} {
		stdout, stderr, status := runCaptured(t, args...)
		if status == 0 || stdout != "" || !strings.Contains(stderr, "--json") {
			t.Errorf("%v: status=%d stdout=%q stderr=%q", args, status, stdout, stderr)
		}
	}
}

func TestEveryNewCommandHasHelp(t *testing.T) {
	for _, args := range [][]string{
		{"skills", "list", "--help"},
		{"skills", "search", "--help"},
		{"skills", "categories", "--help"},
		{"skills", "public-tags", "--help"},
		{"skills", "my-tags", "--help"},
		{"skills", "my-tags", "add", "--help"},
		{"skills", "my-tags", "rename", "--help"},
		{"skills", "my-tags", "describe", "--help"},
		{"skill", "tags", "--help"},
		{"skill", "tag", "--help"},
		{"skill", "tag", "add", "--help"},
		{"skill", "category", "--help"},
		{"skill", "join", "--help"},
		{"skill", "leave", "--help"},
		{"skill", "show", "--help"},
		{"skill", "restore", "--help"},
	} {
		stdout, stderr, status := runCaptured(t, args...)
		if status != 0 || stderr != "" || !strings.Contains(stdout, "用法：") {
			t.Errorf("%v: status=%d stdout=%q stderr=%q", args, status, stdout, stderr)
		}
	}
}

// A flag never takes the next flag as its value.
//
// The typo is ordinary — `--name --text` while editing a definition — and before this check the
// parser wrote "--text" as the new name: a wrong write, not a refused one. The same shape applies
// to every v14 flag whose value is free text.
func TestAFlagNeverSwallowsTheNextFlagAsItsValue(t *testing.T) {
	fake := newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"changed":true}`
	})

	for _, args := range [][]string{
		{"skills", "my-tags", "rename", "客户项目", "--name", "--text"},
		{"skills", "my-tags", "add", "客户项目", "--describe", "--public"},
		{"skills", "my-tags", "describe", "客户项目", "--text", "--public"},
		{"skills", "list", "--category", "--my-tag", "foo"},
		{"skills", "search", "--public-tag", "--category", "文档与演示"},
	} {
		stdout, stderr, status := runCaptured(t, args...)
		if status == 0 {
			t.Errorf("%v: status=0, want a refusal; stdout=%q", args, stdout)
		}
		if !strings.Contains(stderr, "看起来是旗标") {
			t.Errorf("%v: stderr=%q, want the flag-value refusal", args, stderr)
		}
		if stdout != "" {
			t.Errorf("%v: stdout=%q, want nothing", args, stdout)
		}
	}
	if len(fake.requests) != 0 {
		t.Fatalf("refused commands sent %d request(s): %+v", len(fake.requests), fake.requests)
	}
}

// The definition is resolved by the server's normalization, not by comparing display names.
func TestMyTagsResolvesANameThroughTheServer(t *testing.T) {
	fake := newFakeServer(t, func(r recordedRequest) (int, string) {
		if r.Path == "/api/v1/me/tags" {
			return http.StatusOK, `{"tags":[{"id":"01TAG","name":"PDF","used_in":3}]}`
		}
		return http.StatusOK, `{"changed":true}`
	})

	mustRun(t, "skills", "my-tags", "describe", "pdf", "--text", "交付期内用")

	var lookups []recordedRequest
	for _, request := range fake.requests {
		if request.Method == http.MethodGet && request.Path == "/api/v1/me/tags" {
			lookups = append(lookups, request)
		}
	}
	if len(lookups) != 1 {
		t.Fatalf("lookups = %+v", lookups)
	}
	if got := lookups[0].Query.Get("name"); got != "pdf" {
		t.Errorf("name query = %q, want the word as typed (the server normalizes)", got)
	}
	if wantsRequests(t, fake, http.MethodPatch, "/api/v1/me/tags/01TAG") == nil {
		t.Fatal("the definition was not patched")
	}
}

// A value beginning with one dash is free text (a sentence may start with a bullet); only a long
// flag is refused as a missing value.
func TestAFreeTextValueMayBeginWithASingleDash(t *testing.T) {
	fake := newFakeServer(t, func(r recordedRequest) (int, string) {
		if r.Path == "/api/v1/me/tags" {
			return http.StatusOK, `{"tags":[{"id":"01TAG","name":"客户项目","used_in":1}]}`
		}
		return http.StatusOK, `{"changed":true}`
	})

	if _, stderr, status := runCaptured(t, "skills", "my-tags", "describe", "客户项目",
		"--text", "- 交付期内"); status != 0 {
		t.Fatalf("status=%d stderr=%q", status, stderr)
	}
	patches := wantsRequests(t, fake, http.MethodPatch, "/api/v1/me/tags/01TAG")
	if len(patches) != 1 || !strings.Contains(patches[0].Body, "- 交付期内") {
		t.Fatalf("patches = %+v", patches)
	}
}

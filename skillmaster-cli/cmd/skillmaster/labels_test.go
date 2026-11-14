package main

import (
	"net/http"
	"strings"
	"testing"
)

// v65's half of the CLI (test-plan T8, T9, T10): the attribute filter, the one-line attributes under
// a search result, and the vocabulary command.

func TestSearchSendsEveryLabelAndAcceptsAFilterWithoutAKeyword(t *testing.T) {
	fake := newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[],"next_cursor":null,"note":null}`
	})

	// No positional at all: `--label` alone is a question (v65 PRD #7).
	mustRun(t, "skills", "search", "--label", "runtime:python", "--label", "pricing_type:paid")

	pages := wantsRequests(t, fake, http.MethodGet, "/api/v1/skills")
	if len(pages) != 1 {
		t.Fatalf("label-only search made %d requests", len(pages))
	}
	got := pages[0].Query["label"]
	if len(got) != 2 || got[0] != "runtime:python" || got[1] != "pricing_type:paid" {
		t.Errorf("label = %v, want both in the order typed", got)
	}
	if q := pages[0].Query.Get("q"); q != "" {
		t.Errorf("a label-only search sent q=%q", q)
	}
}

func TestSearchRefusesALabelWithoutItsDimension(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[],"next_cursor":null,"note":null}`
	})

	_, stderr, status := runCaptured(t, "skills", "search", "--label", "python")
	if status != 2 || !strings.Contains(stderr, "<维度>:<值>") {
		t.Errorf("status=%d stderr=%q", status, stderr)
	}
}

func TestSearchPrintsAtMostThreeAttributesAndCountsTheRest(t *testing.T) {
	reply := `{"skills":[{"name":"pdf-tools","namespace":"dev","description":"拆页。",
		"labels":[
		  {"key":"runtime","value":"python","label":"Python","full":"需要 Python","group":"language"},
		  {"key":"runtime","value":"macos","label":"macOS","full":"仅 macOS","group":"platform"},
		  {"key":"runtime","value":"api-key","label":"API key","full":"需要 API key","group":"credential"},
		  {"key":"runtime","value":"docker","label":"Docker","full":"需要 Docker","group":"environment"}]}],
		"next_cursor":null,"note":null}`

	newFakeServer(t, func(recordedRequest) (int, string) { return http.StatusOK, reply })
	out := mustRun(t, "skills", "search", "拆页")
	if !strings.Contains(out, "    属性：Python、macOS、API key、+1\n") {
		t.Errorf("the attribute line is not three names plus +N:\n%s", out)
	}
}

func TestSearchPrintsEveryAttributeWhenThereAreThree(t *testing.T) {
	reply := `{"skills":[{"name":"pdf-tools","namespace":"dev","description":"拆页。",
		"labels":[
		  {"key":"runtime","value":"python","label":"Python","full":"需要 Python","group":"language"},
		  {"key":"runtime","value":"macos","label":"macOS","full":"仅 macOS","group":"platform"},
		  {"key":"pricing_type","value":"paid","label":"付费","full":"需要付费","group":"price"}]}],
		"next_cursor":null,"note":null}`

	newFakeServer(t, func(recordedRequest) (int, string) { return http.StatusOK, reply })
	out := mustRun(t, "skills", "search", "拆页")
	if !strings.Contains(out, "    属性：Python、macOS、付费\n") || strings.Contains(out, "+0") {
		t.Errorf("three names must be printed whole, with no +N:\n%s", out)
	}
}

func TestSearchWithNoAttributesAddsNoLine(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[{"name":"pdf-tools","namespace":"dev","description":"拆页。"}],
			"next_cursor":null,"note":null}`
	})
	out := mustRun(t, "skills", "search", "拆页")
	if strings.Contains(out, "属性：") {
		t.Errorf("a skill with no attributes printed an empty line:\n%s", out)
	}
}

func TestAnUnknownLabelIsAFieldNamedByTheServer(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusBadRequest,
			`{"error":{"code":"invalid_request","message":"unknown label 'runtime:nope'"}}`
	})

	_, stderr, status := runCaptured(t, "skills", "search", "--label", "runtime:nope")
	if status == 0 || !strings.Contains(stderr, "runtime:nope") {
		t.Errorf("status=%d stderr=%q: the refusal must name the word typed", status, stderr)
	}
}

func TestLabelsPrintsTheVocabularyAndNoPublicCount(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `[
		  {"key":"runtime:python","dimension_key":"runtime","dimension_name":"运行环境",
		   "value":"python","short":"Python","full":"需要 Python","group":"language",
		   "filterable":true,"state":"enabled","sort_order":1},
		  {"key":"pricing_type:paid","dimension_key":"pricing_type","dimension_name":"付费类型",
		   "value":"paid","short":"付费","full":"需要付费","group":"price",
		   "filterable":true,"state":"enabled","sort_order":1}]`
	})

	out := mustRun(t, "skills", "labels", "--no-login")
	want := "| 维度 | 键 | 短名 | 完整语义 |\n|---|---|---|---|\n" +
		"| 运行环境 | runtime:python | Python | 需要 Python |\n" +
		"| 付费类型 | pricing_type:paid | 付费 | 需要付费 |\n"
	if out != want {
		t.Errorf("labels =\n%q\nwant\n%q", out, want)
	}
	if strings.Contains(out, "公域") {
		t.Errorf("the vocabulary exposed a public count:\n%s", out)
	}

	if _, _, status := runCaptured(t, "skills", "labels", "python"); status == 0 {
		t.Error("skills labels accepted a query")
	}
}

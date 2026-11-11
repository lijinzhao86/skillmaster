package main

import (
	"net/http"
	"strings"
	"testing"
)

// Split out of v14_test.go: `skill show`'s field order test grew when v59 added the personal
// sentence, and that file is on the size baseline (may only shrink).

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
		case "/api/v1/skills/dev/pdf-tools/my-when-to-use":
			return http.StatusOK, `{"when_to_use":"当我说循环审计时用它"}`
		}
		return http.StatusNotFound, `{}`
	})

	out := mustRun(t, "skill", "show", "dev/pdf-tools")
	want := "dev/pdf-tools\n" +
		"  把 PDF 拆页、合并与压缩。\n" +
		"  适用场景：需要拆分、合并或压缩 PDF 时。\n" +
		"  我的适用场景：当我说循环审计时用它\n" +
		"  运行环境：需要 Python 3.10+ 与 poppler。\n" +
		"  版本：1.2.3\n" +
		"  分类：文档与演示\n" +
		"  公开标签：PDF、批量处理\n" +
		"  我的标签：客户项目\n"
	if out != want {
		t.Errorf("skill show =\n%q\nwant\n%q", out, want)
	}
	if len(fake.requests) != 3 {
		t.Errorf("skill show is three reads (metadata, tags, my when-to-use), got %d",
			len(fake.requests))
	}

	newFakeServer(t, func(r recordedRequest) (int, string) {
		if r.Path == "/api/v1/skills/dev/pdf-tools/metadata" {
			// No version object at all: every field this command could print is empty.
			return http.StatusOK, `{"namespace":{"slug":"dev"},"name":"pdf-tools"}`
		}
		if r.Path == "/api/v1/skills/dev/pdf-tools/my-when-to-use" {
			return http.StatusOK, `{"when_to_use":null}`
		}
		return http.StatusOK, `{"category":null,"public_tags":[],
			"my_tags":{"readable":true,"reason":null,"tags":[]}}`
	})
	if out := mustRun(t, "skill", "show", "dev/pdf-tools"); out != "dev/pdf-tools\n" {
		t.Errorf("an all-empty skill printed more than its address: %q", out)
	}
}

// v61 (ADR 0184): the ramp is a state with a date, and `skill show` is where a reader looks for it.
func TestSkillShowNamesTheRampAndItsDate(t *testing.T) {
	newFakeServer(t, func(r recordedRequest) (int, string) {
		switch r.Path {
		case "/api/v1/skills/dev/pdf-tools/metadata":
			return http.StatusOK, `{"namespace":{"slug":"dev"},"name":"pdf-tools",
				"offline_pending_until":"2026-12-11T00:00:00Z"}`
		case "/api/v1/skills/dev/pdf-tools/tags":
			return http.StatusOK, threeGroups
		case "/api/v1/skills/dev/pdf-tools/my-when-to-use":
			return http.StatusOK, `{"when_to_use":null}`
		}
		return http.StatusNotFound, `{}`
	})

	out := mustRun(t, "skill", "show", "dev/pdf-tools")
	if !strings.Contains(out, "  状态：下线中 · 2026-12-11\n") {
		t.Errorf("skill show does not name the ramp and its date:\n%s", out)
	}
}

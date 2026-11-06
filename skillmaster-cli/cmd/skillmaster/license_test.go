package main

import (
	"net/http"
	"strings"
	"testing"
)

func TestSkillLicensePrintsTheBlockFromMetadata(t *testing.T) {
	fake := newFakeServer(t, func(r recordedRequest) (int, string) {
		if r.Path == "/api/v1/skills/dev/pdf-tools/metadata" {
			return http.StatusOK, `{"namespace":{"slug":"dev"},"name":"pdf-tools",
				"license":{"spdx":"MIT","status":"ok","copyright":"© 2025 Corey Haines",
				"url":"https://example.test/LICENSE","allows":["commercial","modify"],
				"requires":["notice"]}}`
		}
		return http.StatusNotFound, `{}`
	})

	out := mustRun(t, "skill", "license", "dev/pdf-tools")
	for _, want := range []string{
		"dev/pdf-tools",
		"许可证：MIT",
		"状态：已识别",
		"版权：© 2025 Corey Haines",
		"可做：可商用 · 可修改",
		"要履行：保留版权与许可声明",
		"全文：https://example.test/LICENSE",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("skill license lacks %q:\n%s", want, out)
		}
	}
	if len(fake.requests) != 1 {
		t.Errorf("skill license is one read, got %d", len(fake.requests))
	}
}

func TestSkillLicenseSaysWhenTheServerHasNoBlock(t *testing.T) {
	newFakeServer(t, func(r recordedRequest) (int, string) {
		if r.Path == "/api/v1/skills/dev/pdf-tools/metadata" {
			return http.StatusOK, `{"namespace":{"slug":"dev"},"name":"pdf-tools"}`
		}
		return http.StatusNotFound, `{}`
	})

	out := mustRun(t, "skill", "license", "dev/pdf-tools")
	if !strings.Contains(out, "许可证：—") || !strings.Contains(out, "没有返回许可信息") {
		t.Errorf("an older server must be named, not guessed at:\n%s", out)
	}
}

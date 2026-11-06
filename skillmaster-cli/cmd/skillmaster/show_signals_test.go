package main

import (
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

func TestShowPrintsAnOfficialSourceWithoutARevision(t *testing.T) {
	out := captureStdout(t, func() {
		printOrigin(api.Metadata{Origin: &api.Origin{
			Kind: "official", Label: "官方", Locator: "skillmaster/startup",
		}})
	})
	if strings.Contains(out, "对应版本") {
		t.Errorf("an official source has no upstream revision:\n%s", out)
	}
	if !strings.Contains(out, "来源：官方") {
		t.Errorf("official source missing:\n%s", out)
	}
	if strings.Contains(out, "skillmaster/startup") {
		t.Errorf("the locator is not a platform address to print for an official source:\n%s", out)
	}
	if strings.Contains(out, "最近校验") {
		t.Errorf("an official source has no upstream check to report:\n%s", out)
	}
}

func TestShowPrintsAGitHubSourceAndItsSignals(t *testing.T) {
	out := captureStdout(t, func() {
		printOrigin(api.Metadata{Origin: &api.Origin{
			Kind:     "github",
			Label:    "GitHub",
			Locator:  "coreyhaines31/marketingskills",
			Revision:  "8f3c2a1d4e5f60718293a4b5c6d7e8f901234567",
			CheckedAt: "2026-11-05T00:00:00Z",
			Status:    "ok",
			Signals: []api.Signal{
				{Label: "Stars", Display: "1.2k"},
				{Label: "最近提交", Display: "2026-10-30"},
			},
		}})
	})
	for _, want := range []string{
		"来源：GitHub · coreyhaines31/marketingskills",
		"对应版本：commit 8f3c2a1",
		"指标：Stars 1.2k · 最近提交 2026-10-30",
		"最近校验：2026-11-05",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("source block lacks %q:\n%s", want, out)
		}
	}
}

func TestShowPrintsOnlyTheLicenseNameAndStatus(t *testing.T) {
	ok := captureStdout(t, func() {
		printLicenseLine(&api.License{
			Spdx: "MIT", Status: "ok", URL: "https://example.test/LICENSE",
			Copyright: "© 2025 Corey Haines",
			Allows:    []string{"commercial", "modify", "redistribute"},
			Requires:  []string{"notice"},
		})
	})
	if ok != "  许可证：MIT\n" {
		t.Errorf("skill show must leave the details to `skill license`, got %q", ok)
	}

	declared := captureStdout(t, func() {
		printLicenseLine(&api.License{Spdx: "Apache-2.0", Status: "declared-only"})
	})
	if !strings.Contains(declared, "许可证：Apache-2.0（仅声明，未附全文）") {
		t.Errorf("declared-only license misphrased:\n%s", declared)
	}
}

func TestLicenseBlockPhrasesEveryStatus(t *testing.T) {
	ok := captureStdout(t, func() {
		printLicenseBlock("dev/pdf-tools", &api.License{
			Spdx: "MIT", Status: "ok", URL: "https://example.test/LICENSE",
			Copyright: "© 2025 Corey Haines",
			Allows:    []string{"commercial", "modify", "redistribute"},
			Requires:  []string{"notice"},
		})
	})
	for _, want := range []string{
		"dev/pdf-tools",
		"许可证：MIT",
		"状态：已识别",
		"可做：可商用 · 可修改 · 可再分发",
		"要履行：保留版权与许可声明",
		"版权：© 2025 Corey Haines",
		"全文：https://example.test/LICENSE",
	} {
		if !strings.Contains(ok, want) {
			t.Errorf("ok license lacks %q:\n%s", want, ok)
		}
	}

	declared := captureStdout(t, func() {
		printLicenseBlock("dev/pdf-tools", &api.License{Spdx: "Apache-2.0", Status: "declared-only"})
	})
	if !strings.Contains(declared, "许可证：Apache-2.0（仅声明，未附全文）") ||
		!strings.Contains(declared, "状态：仅声明") || strings.Contains(declared, "可做：") {
		t.Errorf("declared-only license must not claim permissions:\n%s", declared)
	}

	missing := captureStdout(t, func() {
		printLicenseBlock("dev/pdf-tools", &api.License{Status: "missing"})
	})
	if !strings.Contains(missing, "许可证：未声明") || !strings.Contains(missing, "不要复制到平台外") {
		t.Errorf("missing license must not default to MIT:\n%s", missing)
	}

	showMissing := captureStdout(t, func() {
		printLicenseLine(&api.License{Status: "missing", Spdx: "NOASSERTION"})
	})
	if !strings.Contains(showMissing, "许可证：未声明") || strings.Contains(showMissing, "NOASSERTION") {
		t.Errorf("skill show must not print the wire sentinel:\n%s", showMissing)
	}
	showOther := captureStdout(t, func() {
		printLicenseLine(&api.License{Status: "other", Spdx: "NOASSERTION"})
	})
	if !strings.Contains(showOther, "许可证：其他许可") || strings.Contains(showOther, "NOASSERTION") {
		t.Errorf("skill show must name the other state:\n%s", showOther)
	}

	other := captureStdout(t, func() {
		printLicenseBlock("dev/pdf-tools",
			&api.License{Status: "other", URL: "https://example.test/COPYING"})
	})
	if !strings.Contains(other, "许可证：其他许可") ||
		!strings.Contains(other, "全文：https://example.test/COPYING") {
		t.Errorf("other license must point at its text:\n%s", other)
	}
}

func TestShowPrintsTheUsageBucketAndJoins(t *testing.T) {
	out := captureStdout(t, func() {
		printUsage(&api.Usage{Bucket: "100+", CallsBucket: "1k+",
			LastUsed: "2026-11-05T10:00:00Z", Joins: 45})
	})
	for _, want := range []string{"1k+ 次调用", "100+ 个账号用过", "最近使用 2026-11-05", "入库 45"} {
		if !strings.Contains(out, want) {
			t.Errorf("usage line lacks %q:\n%s", want, out)
		}
	}

	if out := captureStdout(t, func() { printUsage(nil) }); out != "" {
		t.Errorf("an older server's absent block prints nothing, got %q", out)
	}

	empty := captureStdout(t, func() {
		printUsage(&api.Usage{Bucket: "少于 10 个账号"})
	})
	if !strings.Contains(empty, "使用：还没有人用过") {
		t.Errorf("a zero aggregate should say so:\n%s", empty)
	}
}

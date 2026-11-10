package main

import (
	"context"
	"net/http"
	"strings"
	"testing"
	"time"
)

// v60: the window is local calendar days, converted in the CLI; the server only sees instants.

func fixedNow() time.Time {
	return time.Date(2026, 11, 11, 10, 30, 0, 0, time.FixedZone("CST", 8*3600))
}

func TestUsageWindowDefaultsToThirtyDays(t *testing.T) {
	window, err := usageWindowOf("", "", "", "", fixedNow())
	if err != nil {
		t.Fatalf("default window: %v", err)
	}
	if window.Label != "近 30 天（2026-10-13 至 2026-11-11）" {
		t.Errorf("label = %q", window.Label)
	}
	if window.Since != "2026-10-12T16:00:00Z" || window.Until != "2026-11-11T16:00:00Z" {
		t.Errorf("instants = %s .. %s", window.Since, window.Until)
	}
}

// The oldest selectable day is today-89; today-90 is the first refusal. Both sides of that edge.
func TestUsageWindowAcceptsOnlyTheNinetyDayWindow(t *testing.T) {
	today := fixedNow()
	oldest := today.AddDate(0, 0, -89)
	tooOld := today.AddDate(0, 0, -90)

	if _, err := usageWindowOf("", dayText(oldest), dayText(today), "", fixedNow()); err != nil {
		t.Fatalf("today-89 should be allowed: %v", err)
	}
	if _, err := usageWindowOf("", dayText(tooOld), dayText(today), "", fixedNow()); err == nil {
		t.Fatalf("today-90 should be refused")
	} else if !strings.Contains(err.Error(), "不能早于") {
		t.Fatalf("error = %q", err)
	}
}

func TestUsageWindowOfDays(t *testing.T) {
	window, err := usageWindowOf("7", "", "", "pdf", fixedNow())
	if err != nil {
		t.Fatalf("--days 7: %v", err)
	}
	if window.Label != "近 7 天（2026-11-05 至 2026-11-11）" {
		t.Errorf("label = %q", window.Label)
	}
	if window.Skill != "pdf" {
		t.Errorf("skill = %q", window.Skill)
	}
}

func TestUsageWindowOfCustomDaysIncludesBothEnds(t *testing.T) {
	window, err := usageWindowOf("", "2026-11-01", "2026-11-10", "", fixedNow())
	if err != nil {
		t.Fatalf("custom window: %v", err)
	}
	if window.Label != "2026-11-01 至 2026-11-10" {
		t.Errorf("label = %q", window.Label)
	}
	if window.Since != "2026-10-31T16:00:00Z" || window.Until != "2026-11-10T16:00:00Z" {
		t.Errorf("instants = %s .. %s", window.Since, window.Until)
	}
}

func TestUsageWindowRefusesWhatItCannotAnswer(t *testing.T) {
	cases := []struct {
		name                      string
		days, since, until, skill string
		want                      string
	}{
		{"zero days", "0", "", "", "", "1 到 90"},
		{"over ninety", "91", "", "", "", "1 到 90"},
		{"both forms", "7", "2026-11-01", "2026-11-10", "", "只能给一种"},
		{"half a range", "", "2026-11-01", "", "", "成对"},
		{"backwards", "", "2026-11-10", "2026-11-01", "", "晚于结束"},
		{"too old", "", "2026-08-01", "2026-08-10", "", "不能早于"},
		{"future", "", "2026-11-01", "2026-11-20", "", "不能晚于今天"},
		{"bad date", "", "11-01", "2026-11-10", "", "YYYY-MM-DD"},
	}
	for _, row := range cases {
		t.Run(row.name, func(t *testing.T) {
			if _, err := usageWindowOf(row.days, row.since, row.until, row.skill, fixedNow()); err == nil {
				t.Fatalf("accepted %+v", row)
			} else if !strings.Contains(err.Error(), row.want) {
				t.Fatalf("error = %q, want it to mention %q", err, row.want)
			}
		})
	}
}

func TestUsageSendsTheWindowAndKeyword(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/usage/interactions" {
			t.Errorf("unexpected path %s", r.URL.Path)
		}
		for _, key := range []string{"since", "until", "skill"} {
			if r.URL.Query().Get(key) == "" {
				t.Errorf("request is missing %s: %s", key, r.URL.RawQuery)
			}
		}
		_, _ = w.Write([]byte(`{"items":[],"total":0,"page":1,"page_size":50,"window_days":7,"has_any":true}`))
	})

	out := captureStdout(t, func() {
		if err := usageVerb(context.Background(), []string{"--days", "7", "--skill", "pdf"}); err != nil {
			t.Fatalf("usage --days 7 --skill pdf: %v", err)
		}
	})
	if !strings.Contains(out, "没有匹配的使用记录") {
		t.Errorf("filtered empty state missing:\n%s", out)
	}
}

func TestUsageStatsCarriesTheWindowOnItsFirstLine(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/usage/stats" {
			t.Errorf("unexpected path %s", r.URL.Path)
		}
		_, _ = w.Write([]byte(`{"window_days":7,"calls":3,"searches":2,"converted":1,"missed":1,
			"skills":1,"latest":"2026-11-10T01:41:00Z",
			"top":[{"skill":"dev/pdf-tools","calls":3,"last":"2026-11-10T01:41:00Z"}]}`))
	})

	out := captureStdout(t, func() {
		if err := usageVerb(context.Background(), []string{"--stats", "--days", "7", "--skill", "pdf"}); err != nil {
			t.Fatalf("usage --stats: %v", err)
		}
	})
	if !strings.Contains(out, "近 7 天（") || !strings.Contains(out, "skill 地址含 “pdf”") {
		t.Errorf("first line does not carry the window and keyword:\n%s", out)
	}
	if !strings.Contains(out, "搜索结果里出现了这条 skill") {
		t.Errorf("filtered search-outcome wording missing:\n%s", out)
	}
}

func TestUsageRefusesSkillWithAnAddress(t *testing.T) {
	_, _, status := runCaptured(t, "usage", "dev/pdf-tools", "--skill", "pdf")
	if status != 2 {
		t.Fatalf("exited %d, want 2", status)
	}
}

func TestUsageSkillParamIsEncoded(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if got := r.URL.Query().Get("skill"); got != "a b&c" {
			t.Errorf("skill = %q", got)
		}
		_, _ = w.Write([]byte(`{"items":[],"total":0,"page":1,"page_size":50,"window_days":30}`))
	})
	_ = captureStdout(t, func() {
		if err := usageVerb(context.Background(), []string{"--skill", "a b&c"}); err != nil {
			t.Fatalf("usage --skill: %v", err)
		}
	})
}

package main

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"testing"
)

// v58: the CLI is the first frontend, so every usage shape the console shows is reachable here:
// the interaction log (with paging and expansion), the stats, the owner's aggregate and users,
// and one person's use of one skill.

func TestUsageListPrintsEachInteractionOneLine(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/usage/interactions" || r.URL.Query().Get("limit") != "50" {
			t.Errorf("unexpected %s %s?%s", r.Method, r.URL.Path, r.URL.RawQuery)
		}
		_, _ = w.Write([]byte(`{"items":[
		 {"kind":"search","at":"2026-11-10T01:38:00Z","query":"pdf 合并","total":12,"returned":2,
		  "results":[{"skill":"yuhan/pdf-splitter","rank":1},{"skill":"dev/pdf-tools","rank":2}],
		  "invocations":[{"skill":"dev/pdf-tools","version":"1.2.3","at":"2026-11-10T01:41:00Z","rank":2}]},
		 {"kind":"direct","at":"2026-11-10T00:55:00Z","skill":"lijinzhao/code-dev-sop","version":"1.1.2"}
		],"total":2,"page":1,"page_size":50,"window_days":90}`))
	})

	out := captureStdout(t, func() {
		if err := usageVerb(context.Background(), nil); err != nil {
			t.Fatalf("usage: %v", err)
		}
	})
	for _, want := range []string{
		"“pdf 合并”", "12 个结果", "dev/pdf-tools@1.2.3", "第 2 个结果",
		"lijinzhao/code-dev-sop@1.1.2", "直接调用", "共 2 条",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("output does not carry %q:\n%s", want, out)
		}
	}
}

func TestUsageListsEveryInvocationOfOneSearch(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"items":[
		 {"kind":"search","at":"2026-11-10T01:38:00Z","query":"slides","total":3,"returned":3,
		  "results":[{"skill":"hexu/slide-maker","rank":1}],
		  "invocations":[
		   {"skill":"hexu/slide-maker","version":"0.8.0","at":"2026-11-10T01:41:00Z","rank":1},
		   {"skill":"qian/deck-theme","version":"1.4.0","at":"2026-11-10T01:45:00Z","rank":2},
		   {"skill":"linzhi/markdown-to-ppt","version":"0.6.2","at":"2026-11-10T01:49:00Z","rank":3}]}
		],"total":1,"page":1,"page_size":50,"window_days":90}`))
	})

	out := captureStdout(t, func() {
		if err := usageVerb(context.Background(), nil); err != nil {
			t.Fatalf("usage: %v", err)
		}
	})
	for _, want := range []string{
		"调用了 3 个", "#1 hexu/slide-maker@0.8.0", "#2 qian/deck-theme@1.4.0",
		"#3 linzhi/markdown-to-ppt@0.6.2",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("output does not carry %q:\n%s", want, out)
		}
	}
}

func TestUsageExpandShowsResultsAndSaysWhenItWasTruncated(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"items":[
		 {"kind":"search","at":"2026-11-10T01:38:00Z","query":"pdf","total":60,"returned":2,
		  "results":[{"skill":"a/one","rank":1},{"skill":"b/two","rank":2}],
		  "invocations":[{"skill":"b/two","version":"1.0.0","at":"2026-11-10T01:41:00Z","rank":2}]}
		],"total":1,"page":1,"page_size":50,"window_days":90}`))
	})

	out := captureStdout(t, func() {
		if err := usageVerb(context.Background(), []string{"--expand"}); err != nil {
			t.Fatalf("usage --expand: %v", err)
		}
	})
	for _, want := range []string{
		"这里只记录前 2 个", "#1 a/one", "#2 b/two", "← 已调用",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("output does not carry %q:\n%s", want, out)
		}
	}
}

func TestUsageStatsPrintsTheSameFourNumbers(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/usage/stats" {
			t.Errorf("unexpected %s", r.URL.Path)
		}
		_, _ = w.Write([]byte(`{"window_days":90,"calls":12,"searches":8,"converted":6,"missed":2,
			"skills":7,"latest":"2026-11-10T01:41:00Z",
			"top":[{"skill":"dev/pdf-tools","calls":3,"last":"2026-11-10T01:41:00Z"}]}`))
	})

	out := captureStdout(t, func() {
		if err := usageVerb(context.Background(), []string{"--stats"}); err != nil {
			t.Fatalf("usage --stats: %v", err)
		}
	})
	for _, want := range []string{"调用次数      12", "搜索次数      8", "使用过的 skill 7",
		"8 次搜索中，6 次最终调用了结果，2 次未调用", "dev/pdf-tools"} {
		if !strings.Contains(out, want) {
			t.Errorf("output does not carry %q:\n%s", want, out)
		}
	}
}

func TestUsageOfAnAddressShowsTheOwnerTable(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/usage/skills/dev/pdf-tools" {
			t.Errorf("unexpected %s", r.URL.Path)
		}
		_, _ = w.Write([]byte(`{"skill":"dev/pdf-tools","window_days":90,"public":false,
			"totals":{"calls":"3","accounts":"2","joins":45,"last_used":"2026-11-10T01:41:00Z"},
			"users":[{"who":"dev","owner":true,"role":"所有者","invoke":3,"submitted":12,"last":"2026-11-10T01:41:00Z"},
			         {"who":"yuhan","owner":false,"role":"维护","invoke":42,"submitted":5,"last":"2026-11-09T00:00:00Z"}]}`))
	})

	out := captureStdout(t, func() {
		if err := usageVerb(context.Background(), []string{"dev/pdf-tools"}); err != nil {
			t.Fatalf("usage <address>: %v", err)
		}
	})
	for _, want := range []string{"dev/pdf-tools", "调用次数 3", "入库人数 45", "所有者",
		"yuhan", "维护"} {
		if !strings.Contains(out, want) {
			t.Errorf("output does not carry %q:\n%s", want, out)
		}
	}
}

func TestUsageUsersPrintsTheTableAlone(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/usage/skills/dev/pdf-tools/users" {
			t.Errorf("unexpected %s", r.URL.Path)
		}
		_, _ = w.Write([]byte(`{"window_days":90,"users":[
			{"who":"dev","owner":true,"role":"所有者","invoke":3,"submitted":12,"last":"2026-11-10T01:41:00Z"},
			{"who":"yuhan","owner":false,"role":"maintain","invoke":42,"submitted":5,"last":"2026-11-09T00:00:00Z"}]}`))
	})

	out := captureStdout(t, func() {
		if err := usageVerb(context.Background(), []string{"dev/pdf-tools", "--users"}); err != nil {
			t.Fatalf("usage <address> --users: %v", err)
		}
	})
	for _, want := range []string{"使用者", "档位", "dev", "所有者", "yuhan", "维护"} {
		if !strings.Contains(out, want) {
			t.Errorf("output does not carry %q:\n%s", want, out)
		}
	}
}

func TestUsageUsersForSomeoneElseExitsNonZero(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"error":{"code":"skill_not_found","message":"no such skill"}}`))
	})

	_, stderr, status := runCaptured(t, "usage", "dev/nope", "--users")
	if status != 1 {
		t.Fatalf("usage <address> --users exited %d, want 1 like every other 404", status)
	}
	if !strings.Contains(stderr, "没有这个 skill") {
		t.Errorf("stderr does not explain the 404:\n%s", stderr)
	}
}

func TestUsageMineShowsVersionsAndTheirState(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/usage/skills/dev/pdf-tools/mine" {
			t.Errorf("unexpected %s", r.URL.Path)
		}
		_, _ = w.Write([]byte(`{"skill":"dev/pdf-tools","window_days":90,"calls":3,"versions":2,
			"latest":"2026-11-10T01:41:00Z",
			"by_version":[{"version":"1.2.3","state":"live","calls":2,"last":"2026-11-10T01:41:00Z"},
			              {"version":"1.2.2","state":"history","calls":1,"last":"2026-11-08T10:02:00Z"}]}`))
	})

	out := captureStdout(t, func() {
		if err := usageVerb(context.Background(), []string{"dev/pdf-tools", "--mine"}); err != nil {
			t.Fatalf("usage <address> --mine: %v", err)
		}
	})
	for _, want := range []string{"@1.2.3", "线上", "历史版本", "调用次数 3"} {
		if !strings.Contains(out, want) {
			t.Errorf("output does not carry %q:\n%s", want, out)
		}
	}
}

func TestUsageRefusesCombinationsThatWouldReadDifferentQuestions(t *testing.T) {
	for _, args := range [][]string{
		{"--stats", "dev/pdf-tools"},
		{"--users"},
		{"--mine"},
		{"--users", "--mine", "dev/pdf-tools"},
		{"--stats", "--expand"},
		{"dev/pdf-tools", "yuhan/other"},
	} {
		_, _, status := runCaptured(t, append([]string{"usage"}, args...)...)
		if status == 0 {
			t.Errorf("usage %v exited 0", args)
		}
	}
}

func TestUsageJSONPrintsTheWireShapeUnchanged(t *testing.T) {
	const wire = `{"items":[{"kind":"search","at":"2026-11-10T01:38:00Z","query":"nomatch","returned":0,"results":[],"invocations":[]}],"total":1,"page":1,"page_size":50,"window_days":90}`
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(wire))
	})

	out := captureStdout(t, func() {
		if err := usageVerb(context.Background(), []string{"--json"}); err != nil {
			t.Fatalf("usage --json: %v", err)
		}
	})
	// The bytes, not a re-encoding: a 0-result search keeps `returned: 0` and its empty arrays.
	if strings.TrimSpace(out) != wire {
		t.Errorf("--json changed the shape:\n got: %s\nwant: %s", strings.TrimSpace(out), wire)
	}
}

func TestUsageJSONReportsANotFoundByItsCode(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"error":{"code":"skill_not_found","message":"no such skill"}}`))
	})

	err := usageVerb(context.Background(), []string{"dev/nope", "--json"})
	var failure *cliFailure
	if !errors.As(err, &failure) {
		t.Fatalf("err = %v, want a cliFailure", err)
	}
	if failure.Code != "not_found" {
		t.Errorf("code = %q, want not_found (the 404 must not classify as network)", failure.Code)
	}
}

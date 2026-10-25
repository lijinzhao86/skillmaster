package main

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
)

// `skills pending` — the console's 待上线 list, in the terminal (v18).
//
// What is asserted is the answer a person reads: one skill line, then one line per draft with the
// three facts that decide whether it can be published — which draft, what kind it declares, and when
// it was submitted. The list itself is the server's to order; the command prints it as it arrives.

const pendingQueue = `{
  "skills": [
    {"namespace": "dev", "name": "log-triage",
     "current": {"version": "0.9.0", "submitted_at": "2026-08-30T09:20:00Z"},
     "drafts": [
       {"code": "7K3M9QX2", "name": "重写分类", "release_type": "major", "submitted_at": "2026-10-25T09:12:00Z"},
       {"code": "4P8N2C7V", "name": null, "release_type": null, "submitted_at": "2026-10-24T20:40:00Z"}]},
    {"namespace": "dev", "name": "perf-budget", "current": null,
     "drafts": [{"code": "9F2T4HXK", "name": "初版", "release_type": null, "submitted_at": "2026-10-25T09:40:00Z"}]}
  ],
  "truncated": false
}`

func TestPendingPrintsEachDraftWithItsKindAndSaysWhatIsLive(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/author/publish-queue" {
			t.Errorf("asked %s; the queue has its own route", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(pendingQueue))
	})

	_, stderr, status := runCaptured(t, "skills", "pending")
	if status != 0 {
		t.Fatalf("skills pending: status %d, stderr %q", status, stderr)
	}

	stdout, _, _ := runCaptured(t, "skills", "pending")
	for _, want := range []string{
		"dev/log-triage",
		"已上线 @0.9.0 · 2 份草稿",
		"7K3M9QX2  「重写分类」 · 主版本",
		// A draft with no name and no kind is both facts said out loud, not blanks: the first is a
		// row from before drafts had names, the second is what somebody has to choose before this
		// one can be published at all.
		"4P8N2C7V  「未命名」 · 未指定版本类型",
		// A skill that has never been published says so — which is not the same as a live version
		// that carries no number.
		"dev/perf-budget",
		"从未上线 · 1 份草稿",
		"共 2 个 skill、3 份草稿",
	} {
		if !strings.Contains(stdout, want) {
			t.Errorf("output does not contain %q:\n%s", want, stdout)
		}
	}
	// **The pin is the code, bare** — no `draft:` prefix (ADR 0063), because that is what a publish
	// request takes.
	if strings.Contains(stdout, "draft:") {
		t.Errorf("the pin was spelled with a prefix:\n%s", stdout)
	}
}

func TestPendingSaysWhenTheListWasCutAndWhenItIsEmpty(t *testing.T) {
	truncated := strings.Replace(pendingQueue, `"truncated": false`, `"truncated": true`, 1)
	pointAtServer(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(truncated))
	})
	stdout, _, _ := runCaptured(t, "skills", "pending")
	if !strings.Contains(stdout, "只列出了最近 500 个 skill") {
		t.Errorf("a cut list was not said out loud:\n%s", stdout)
	}

	pointAtServer(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"skills": [], "truncated": false}`))
	})
	stdout, _, _ = runCaptured(t, "skills", "pending")
	if !strings.Contains(stdout, "没有待上线的草稿") {
		t.Errorf("an empty queue does not say so:\n%s", stdout)
	}
}

func TestPendingJSONIsTheServerAnswer(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(pendingQueue))
	})

	stdout, _, status := runCaptured(t, "skills", "pending", "--json")
	if status != 0 {
		t.Fatalf("status %d", status)
	}
	var decoded struct {
		Skills []struct {
			Name   string `json:"name"`
			Drafts []struct {
				ReleaseType *string `json:"release_type"`
			} `json:"drafts"`
		} `json:"skills"`
		Truncated bool `json:"truncated"`
	}
	if err := json.Unmarshal([]byte(stdout), &decoded); err != nil {
		t.Fatalf("--json did not print one object: %v\n%s", err, stdout)
	}
	if len(decoded.Skills) != 2 || len(decoded.Skills[0].Drafts) != 2 {
		t.Fatalf("the object lost rows: %s", stdout)
	}
	if decoded.Skills[0].Drafts[0].ReleaseType == nil ||
		*decoded.Skills[0].Drafts[0].ReleaseType != "major" {
		t.Errorf("the kind was dropped from the machine-readable answer: %s", stdout)
	}
}

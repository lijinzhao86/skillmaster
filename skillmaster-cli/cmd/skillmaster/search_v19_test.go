package main

import (
	"net/http"
	"strings"
	"testing"
)

// v19's half of `skills search`: the scope, the semantic switch, the size and the server's note.

func TestSearchSendsTheScopeTheFlagNames(t *testing.T) {
	fake := newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[{"name":"slides","namespace":"ann","description":"做 PPT。"}],"next_cursor":null,"note":null}`
	})

	mustRun(t, "skills", "search", "做PPT", "--scope", "public", "--limit", "3")

	pages := wantsRequests(t, fake, http.MethodGet, "/api/v1/skills")
	query := pages[0].Query
	if query.Get("scope") != "public" || query.Get("limit") != "3" {
		t.Errorf("scope/limit = %v", query)
	}
}

func TestSearchAsksForSemanticUnlessToldOtherwise(t *testing.T) {
	reply := `{"skills":[],"next_cursor":null,"note":null}`

	// The product defaults to semantic (PRD #13) and says so on the wire: the server treats the
	// parameter as opt-in, because the same route serves listings that predate v19.
	def := newFakeServer(t, func(recordedRequest) (int, string) { return http.StatusOK, reply })
	mustRun(t, "skills", "search", "做PPT")
	if got := wantsRequests(t, def, http.MethodGet, "/api/v1/skills")[0].Query.Get("semantic"); got != "true" {
		t.Errorf("a default search sent semantic=%q, want true", got)
	}

	off := newFakeServer(t, func(recordedRequest) (int, string) { return http.StatusOK, reply })
	mustRun(t, "skills", "search", "做PPT", "--semantic=false")
	if got := wantsRequests(t, off, http.MethodGet, "/api/v1/skills")[0].Query.Get("semantic"); got != "false" {
		t.Errorf("semantic = %q, want false", got)
	}
}

func TestSearchPrintsTheServersNote(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[{"name":"slides","namespace":"ann","description":"做 PPT。"}],
			"next_cursor":null,"note":"语义检索暂不可用，这是按字面找到的"}`
	})

	out := mustRun(t, "skills", "search", "做PPT")
	if !strings.Contains(out, "语义检索暂不可用") {
		t.Errorf("a fallback answer that does not say so reads as a semantic one:\n%s", out)
	}
}

func TestSearchSaysWhenItClampedTheLimit(t *testing.T) {
	fake := newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[],"next_cursor":null,"note":null}`
	})

	out := mustRun(t, "skills", "search", "做PPT", "--limit", "100")
	if got := wantsRequests(t, fake, http.MethodGet, "/api/v1/skills")[0].Query.Get("limit"); got != "50" {
		t.Errorf("limit = %q, want 50", got)
	}
	if !strings.Contains(out, "最多 50") {
		t.Errorf("a clamped request must say so:\n%s", out)
	}
}

func TestSearchNeverPrintsTheInternalSimilarity(t *testing.T) {
	reply := `{"skills":[{"name":"slides","namespace":"ann","description":"做 PPT。","score":0.87}],
		"next_cursor":null,"note":null}`

	newFakeServer(t, func(recordedRequest) (int, string) { return http.StatusOK, reply })
	if out := mustRun(t, "skills", "search", "做PPT"); strings.Contains(out, "相似度") {
		t.Errorf("the default card carries no score:\n%s", out)
	}

	newFakeServer(t, func(recordedRequest) (int, string) { return http.StatusOK, reply })
	if out := mustRun(t, "skills", "search", "做PPT", "--verbose"); strings.Contains(out, "相似度") {
		t.Errorf("verbose must not expose the internal score either:\n%s", out)
	}
}

func TestSearchVerboseShowsTheCompactPublicSignals(t *testing.T) {
	reply := `{"skills":[{"name":"slides","namespace":"ann","description":"做 PPT。",
		"origin_label":"GitHub","license_spdx":"MIT","calls_bucket":"100+"}],
		"next_cursor":null,"note":null}`

	newFakeServer(t, func(recordedRequest) (int, string) { return http.StatusOK, reply })
	out := mustRun(t, "skills", "search", "做PPT", "--verbose")
	for _, want := range []string{"公域来源：GitHub · MIT · 100+ 次调用"} {
		if !strings.Contains(out, want) {
			t.Errorf("verbose output lacks %q:\n%s", want, out)
		}
	}
}

func TestSearchVerboseNeverPrintsTheLicenseSentinel(t *testing.T) {
	cases := []struct {
		name, licenseJSON, want string
	}{
		{"missing", `"license_status":"missing"`, "公域来源：官方 · 未声明 · 10+ 次调用"},
		{"other", `"license_status":"other"`, "公域来源：官方 · 其他许可 · 10+ 次调用"},
		{"declared-only", `"license_status":"declared-only","license_spdx":"MIT"`,
			"公域来源：官方 · MIT（仅声明，未附全文） · 10+ 次调用"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			reply := `{"skills":[{"name":"slides","namespace":"ann","description":"做 PPT。",
				"origin_label":"官方","license_spdx":"NOASSERTION",` + tc.licenseJSON + `,
				"calls_bucket":"10+"}],"next_cursor":null,"note":null}`
			newFakeServer(t, func(recordedRequest) (int, string) { return http.StatusOK, reply })
			out := mustRun(t, "skills", "search", "做PPT", "--verbose")
			if !strings.Contains(out, tc.want) || strings.Contains(out, "NOASSERTION") {
				t.Errorf("verbose output lacks %q or leaks the sentinel:\n%s", tc.want, out)
			}
		})
	}
}

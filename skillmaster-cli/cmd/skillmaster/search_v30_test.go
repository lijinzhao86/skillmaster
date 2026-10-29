package main

import (
	"fmt"
	"net/http"
	"strings"
	"testing"
)

// v30's half of `skills search`: the exclusion list, its shape, and the hint that goes first.

func TestSearchSendsEveryExcludedAddress(t *testing.T) {
	fake := newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK,
			`{"skills":[{"name":"slides","namespace":"ann","description":"做 PPT。"}],"next_cursor":null,"note":null}`
	})

	// Both spellings at once, with a repeat: the flag is a list however it was typed, and the
	// repeat must not travel twice.
	mustRun(t, "skills", "search", "做PPT", "--exclude", "ann/slides,bob/deck",
		"--exclude", "ann/slides")

	query := wantsRequests(t, fake, http.MethodGet, "/api/v1/skills")[0].Query
	got := query["exclude"]
	if len(got) != 2 || got[0] != "ann/slides" || got[1] != "bob/deck" {
		t.Errorf("exclude params = %v, want [ann/slides bob/deck]", got)
	}
}

func TestSearchRefusesAMalformedExclude(t *testing.T) {
	withTempHome(t)

	for _, bad := range []string{"ann", "ann/slides/SKILL.md", "ann/slides@1.0.0", "ann/"} {
		_, stderr, status := runCaptured(t, "skills", "search", "做PPT", "--exclude", bad)
		if status != 2 {
			t.Errorf("--exclude %q exited %d, want 2", bad, status)
			continue
		}
		if !strings.Contains(stderr, "exclude 要写") {
			t.Errorf("--exclude %q did not name the shape:\n%s", bad, stderr)
		}
	}
}

func TestSearchCapsTheExcludesAtFifty(t *testing.T) {
	withTempHome(t)

	addresses := make([]string, maxExcludeAddresses+1)
	for i := range addresses {
		addresses[i] = fmt.Sprintf("ns/skill-%d", i)
	}
	// Distinct addresses, so the ceiling — not deduplication — is what refuses this.
	_, stderr, status := runCaptured(t, "skills", "search", "做PPT",
		"--exclude", strings.Join(addresses, ","))
	if status != 2 {
		t.Fatalf("51 excludes exited %d, want 2", status)
	}
	if !strings.Contains(stderr, "最多") {
		t.Errorf("the refusal does not name the ceiling:\n%s", stderr)
	}
}

func TestListRefusesTheExcludeFlag(t *testing.T) {
	withTempHome(t)

	_, stderr, status := runCaptured(t, "skills", "list", "--exclude", "ann/slides")
	if status != 2 {
		t.Fatalf("skills list --exclude exited %d, want 2", status)
	}
	if !strings.Contains(stderr, "不接受 --exclude") {
		t.Errorf("skills list did not refuse --exclude by name:\n%s", stderr)
	}
}

func TestSearchPutsTheExcludeHintFirst(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK,
			`{"skills":[{"name":"slides","namespace":"ann","description":"做 PPT。"}],"next_cursor":null,"note":null}`
	})

	out := mustRun(t, "skills", "search", "做PPT")
	first := strings.SplitN(out, "\n", 2)[0]
	want := "（要换说法再查：加 --exclude 带上这次给过的地址，重复的不再占上下文。）"
	if first != want {
		t.Errorf("first line = %q, want %q\nfull output:\n%s", first, want, out)
	}
}

// The hint is the first line even when `--limit` was clamped: the addresses then start after both
// the hint and the clamp note, which is why "addresses start on line two" is not the contract.
func TestSearchKeepsTheHintAheadOfTheClampNote(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK,
			`{"skills":[{"name":"slides","namespace":"ann","description":"做 PPT。"}],"next_cursor":null,"note":null}`
	})

	out := mustRun(t, "skills", "search", "做PPT", "--limit", "100")
	lines := strings.Split(out, "\n")
	if len(lines) < 3 {
		t.Fatalf("too few lines:\n%s", out)
	}
	if !strings.HasPrefix(lines[0], "（要换说法再查") {
		t.Errorf("line 1 = %q, want the exclude hint", lines[0])
	}
	if !strings.HasPrefix(lines[1], "（--limit 最多") {
		t.Errorf("line 2 = %q, want the clamp note", lines[1])
	}
	if !strings.HasPrefix(lines[2], "ann/slides") {
		t.Errorf("line 3 = %q, want the first address", lines[2])
	}
}

// An empty semantic result always carries a note, so the exclusion line must be offered beside it —
// gating on an absent note would silence it in the ordinary case, which is the one A8 is about.
func TestSearchExplainsTheOrdinarySemanticEmptyWithExcludes(t *testing.T) {
	const note = "没有足够相关的 skill。"
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[],"next_cursor":null,"note":"` + note + `"}`
	})

	out := mustRun(t, "skills", "search", "做PPT", "--exclude", "ann/slides")
	if !strings.Contains(out, note) {
		t.Errorf("the server's sentence was lost:\n%s", out)
	}
	if !strings.Contains(out, "也可能是 --exclude 排掉的") {
		t.Errorf("an empty excluded semantic search did not offer the exclusion check:\n%s", out)
	}
}

// When the cause is a vocabulary name, the sentence still appears — as a possibility, not a blame —
// and the server's own explanation is not displaced.
func TestSearchKeepsTheServerExplanationWhenItOffersTheExcludeCheck(t *testing.T) {
	const note = "分类或标签的名字不在词表里，没有可检索的范围。"
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[],"next_cursor":null,"note":"` + note + `"}`
	})

	out := mustRun(t, "skills", "search", "做PPT", "--exclude", "ann/slides")
	if !strings.Contains(out, note) {
		t.Errorf("the server's explanation was lost:\n%s", out)
	}
	if !strings.Contains(out, "也可能是 --exclude 排掉的") {
		t.Errorf("the exclusion check is missing:\n%s", out)
	}
	if strings.Contains(out, "没有匹配的 skill") {
		t.Errorf("the unqualified sentence claimed nothing matched:\n%s", out)
	}
}

func TestSearchOmitsTheHintWhenExcluding(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK,
			`{"skills":[{"name":"slides","namespace":"ann","description":"做 PPT。"}],"next_cursor":null,"note":null}`
	})

	out := mustRun(t, "skills", "search", "做PPT", "--exclude", "ann/other")
	if strings.Contains(out, "要换说法再查") {
		t.Errorf("the hint was printed even though --exclude was used:\n%s", out)
	}
}

func TestSearchExplainsAnEmptyResultWithExcludes(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[],"next_cursor":null,"note":null}`
	})

	out := mustRun(t, "skills", "search", "做PPT", "--exclude", "ann/slides")
	if !strings.Contains(out, "也可能是 --exclude 排掉的") {
		t.Errorf("an empty excluded search did not explain itself:\n%s", out)
	}
	// The old sentence claims nothing matched. With exclusions that is a claim the CLI cannot make.
	if strings.Contains(out, "没有匹配的 skill") {
		t.Errorf("the excluded empty result reused the unqualified sentence:\n%s", out)
	}
}

func TestSearchKeepsTheOldEmptySentenceWithoutExcludes(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[],"next_cursor":null,"note":null}`
	})

	out := mustRun(t, "skills", "search", "做PPT")
	if !strings.Contains(out, "没有匹配的 skill") {
		t.Errorf("an ordinary empty search lost its sentence:\n%s", out)
	}
	if strings.Contains(out, "没有新的结果") {
		t.Errorf("the exclusion sentence appeared without --exclude:\n%s", out)
	}
}

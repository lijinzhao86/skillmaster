package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// names turns a list into the set shape the line is computed from.
func names(list ...string) map[string]struct{} {
	set := make(map[string]struct{}, len(list))
	for _, name := range list {
		set[name] = struct{}{}
	}
	return set
}

// The line names the next step, and **「搬没搬过」 is decided by names, not counts**.
//
// Importing is optional since ADR 0108, so the `local and live share no name` case no longer points
// at the import step. It is still the KI-1 regression gate for the count: the line must say
// 「其中 0 个已上线」 instead of implying the local skills were moved. Drafts, when present, come
// first; otherwise the prompt is the next required step.
func TestWhereYouAreNamesTheNextStep(t *testing.T) {
	cases := []struct {
		name                           string
		local, live, pending           []string
		drafts                         int
		draftsTruncated, liveTruncated bool
		want                           []string
		not                            string
	}{
		{
			name: "nothing anywhere",
			want: []string{"下一步：看看还差几步。"},
		},
		{
			name:  "local copies the platform has never seen",
			local: []string{"a", "b"},
			want:  []string{"下一步：看看还差几步。", "其中 0 个已上线"},
			not:   "搬运",
		},
		{
			// KI-1: the live skills belong to somebody else's history, not to these directories. The
			// import is optional now, so the line may point at the prompt — but it must still show 0
			// local copies live.
			name:  "local and live share no name",
			local: []string{"lark-a", "lark-b"},
			live:  []string{"meeting-notes"},
			want:  []string{"下一步：看看还差几步。", "其中 0 个已上线", "我的库里有 1 条线上"},
			not:   "搬运",
		},
		{
			name:  "every local copy is live",
			local: []string{"a", "b"},
			live:  []string{"a", "b"},
			want:  []string{"下一步：看看还差几步。", "其中 2 个已上线"},
		},
		{
			name:  "only some local copies are live",
			local: []string{"a", "b", "c"},
			live:  []string{"a"},
			want:  []string{"下一步：看看还差几步。", "其中 1 个已上线"},
			not:   "搬运",
		},
		{
			name:    "every local copy is a draft",
			local:   []string{"a"},
			pending: []string{"a"},
			drafts:  1,
			want:    []string{"下一步：上线。", "1 份草稿在等上线"},
			not:     "搬运",
		},
		{
			name:    "a waiting draft comes before the optional import",
			local:   []string{"a", "b"},
			live:    []string{"a"},
			pending: []string{"a"},
			drafts:  1,
			want:    []string{"下一步：上线。", "其中 1 个已上线"},
			not:     "搬运",
		},
		{
			name: "done: nothing local, the library is live",
			live: []string{"a", "b"},
			want: []string{"已完成：平台可用，本机没有副本。"},
		},
		{
			name:    "done is not said while a draft waits",
			live:    []string{"a"},
			pending: []string{"a"},
			drafts:  1,
			want:    []string{"下一步：上线。"},
			not:     "已完成",
		},
		{
			// A page that stopped early must not be read as the whole library.
			name:          "a cut listing keeps its wording",
			live:          []string{"x", "y"},
			liveTruncated: true,
			want:          []string{"50 条以上", "已完成"},
		},
		{
			// A name that is not on the first page cannot be proven moved. The count stays a lower
			// bound; the next step is still the prompt, because importing is optional.
			name:          "a cut listing still shows how many local copies are live",
			local:         []string{"a", "b"},
			live:          []string{"a"},
			liveTruncated: true,
			want:          []string{"下一步：看看还差几步。", "50 条以上", "其中至少 1 个已上线"},
			not:           "搬运",
		},
		{
			// …but when every local name was on the page, truncation changes nothing.
			name:          "a cut listing still moves on when every local name was found",
			local:         []string{"a"},
			live:          []string{"a"},
			liveTruncated: true,
			want:          []string{"下一步：看看还差几步。"},
		},
		{
			name:            "a cut queue keeps its wording",
			pending:         []string{"a"},
			drafts:          500,
			draftsTruncated: true,
			want:            []string{"500 份以上草稿在等上线（更早的没有列进来）"},
		},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			got := whereYouAreLine(whereabouts{
				local:           names(test.local...),
				live:            names(test.live...),
				pending:         names(test.pending...),
				drafts:          test.drafts,
				liveTruncated:   test.liveTruncated,
				draftsTruncated: test.draftsTruncated,
			})
			for _, want := range test.want {
				if !strings.Contains(got, want) {
					t.Fatalf("whereYouAreLine = %q, want it to contain %q", got, want)
				}
			}
			if test.not != "" && strings.Contains(got, test.not) {
				t.Fatalf("whereYouAreLine = %q, want it not to contain %q", got, test.not)
			}
			// Phases, never step numbers: the journey's own numbering is the only one.
			if strings.Contains(got, "第") {
				t.Fatalf("whereYouAreLine = %q, want no step numbers", got)
			}
		})
	}
}

// fakeAPI is the three remote answers the state line needs, plus what each request asked for.
type fakeAPI struct {
	listing string // the last skills query string
	live    string
	queue   string
	me      string
	fail    map[string]bool
}

func newFakeAPI() *fakeAPI {
	return &fakeAPI{
		live:  `{"skills":[],"next_cursor":""}`,
		queue: `{"skills":[],"truncated":false}`,
		me:    `{"user":{"user_id":"u1","username":"dev","namespace":"dev"}}`,
		fail:  map[string]bool{},
	}
}

func (f *fakeAPI) serve(t *testing.T) api.Client {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if f.fail[r.URL.Path] {
			http.Error(w, "boom", http.StatusInternalServerError)
			return
		}
		switch r.URL.Path {
		case "/api/v1/me":
			_, _ = w.Write([]byte(f.me))
		case "/api/v1/skills":
			f.listing = r.URL.RawQuery
			_, _ = w.Write([]byte(f.live))
		case "/api/v1/author/publish-queue":
			_, _ = w.Write([]byte(f.queue))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(server.Close)
	return api.Client{BaseURL: server.URL, Token: "skm_test"}
}

// localSkill gives the scan one directory to find under the test's own HOME.
func localSkill(t *testing.T, home, name string) {
	t.Helper()
	dir := filepath.Join(home, ".claude", "skills", name)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.WriteFile(filepath.Join(dir, "SKILL.md"), []byte("# "+name+"\n"), 0o644); err != nil {
		t.Fatalf("write SKILL.md: %v", err)
	}
}

// **Only the caller's own skills are asked for.** The listing's default scope is the library —
// created, shared and joined at once (SearchScope) — so a teammate's skill that happens to share a
// directory name would make a local copy look moved: KI-1 through another door. The filtering is
// the server's; this asserts the client asks for it, which is the client's whole side of that
// contract.
func TestWhereYouAreAsksOnlyForTheCallersOwnSkills(t *testing.T) {
	t.Setenv("HOME", t.TempDir())

	fake := newFakeAPI()
	fake.live = `{"skills":[{"name":"a"}],"next_cursor":""}`

	line, ok := whereYouAre(context.Background(), fake.serve(t))
	if !ok {
		t.Fatal("whereYouAre gave up on a server that answered everything")
	}
	if !strings.Contains(line, "已完成") {
		t.Fatalf("line = %q, want the empty-library answer", line)
	}
	for _, want := range []string{"show=live", "source=created"} {
		if !strings.Contains(fake.listing, want) {
			t.Fatalf("the skills listing asked %q, want it to carry %q", fake.listing, want)
		}
	}
}

// **The queue is a permission surface, not an ownership one**: it lists what the caller may
// publish, and maintain on somebody else's skill puts their drafts on it. A local directory that
// shares a name with one of those must not be read as moved — and their drafts are not this
// person's 「N 份草稿」 either.
func TestWhereYouAreCountsOnlyTheCallersOwnDrafts(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	localSkill(t, home, "theirs")

	fake := newFakeAPI()
	fake.queue = `{"skills":[
		{"namespace":"other","name":"theirs","drafts":[{"code":"K1"}]},
		{"namespace":"dev","name":"mine","drafts":[{"code":"K2"}]}
	],"truncated":false}`

	line, ok := whereYouAre(context.Background(), fake.serve(t))
	if !ok {
		t.Fatal("whereYouAre gave up on a server that answered everything")
	}
	if !strings.Contains(line, "下一步：上线。") {
		t.Fatalf("line = %q, want the publish step: the caller's own draft is waiting", line)
	}
	if !strings.Contains(line, "1 份草稿在等上线") {
		t.Fatalf("line = %q, want only the caller's own draft counted", line)
	}
}

// A source that fails removes the whole line; a zero filled in for it would say "your library is
// empty" to somebody whose library is not (v17 acceptance 29). Each remote answer is a source, and
// each must be able to take the line down on its own.
func TestWhereYouArePrintsNothingWhenASourceFails(t *testing.T) {
	for _, path := range []string{
		"/api/v1/me",
		"/api/v1/author/publish-queue",
		"/api/v1/skills",
	} {
		t.Run(path, func(t *testing.T) {
			t.Setenv("HOME", t.TempDir())
			fake := newFakeAPI()
			fake.fail[path] = true

			if line, ok := whereYouAre(context.Background(), fake.serve(t)); ok || line != "" {
				t.Fatalf("whereYouAre = %q, %v; want no line at all", line, ok)
			}
		})
	}
}

// The local scan is the fourth source, and it fails the same way — a candidate path that cannot be
// read is not "no skills on this machine".
//
// **A regular file standing where a directory belongs, not a chmod 000.** The permission trick
// only works for a non-root test process; a container running as uid 0 reads straight through it.
// `os.ReadDir` on a file raises ENOTDIR — not "does not exist" — for every uid.
func TestWhereYouArePrintsNothingWhenTheLocalScanFails(t *testing.T) {
	home := t.TempDir()
	blocked := filepath.Join(home, ".claude", "skills")
	if err := os.MkdirAll(filepath.Dir(blocked), 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	if err := os.WriteFile(blocked, []byte("not a directory"), 0o644); err != nil {
		t.Fatalf("write: %v", err)
	}
	t.Setenv("HOME", home)

	if line, ok := whereYouAre(context.Background(), newFakeAPI().serve(t)); ok || line != "" {
		t.Fatalf("whereYouAre = %q, %v; want no line at all", line, ok)
	}
}

// **An identity answer without a namespace is not the answer "my namespace is empty".** Filtering
// on "" would drop every queue row and print a confident but wrong line, which is exactly what this
// line is not allowed to do (v17 acceptance 29).
func TestWhereYouArePrintsNothingWhenTheIdentityHasNoNamespace(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	fake := newFakeAPI()
	fake.me = `{"user":{"user_id":"u1","username":"dev"}}`

	if line, ok := whereYouAre(context.Background(), fake.serve(t)); ok || line != "" {
		t.Fatalf("whereYouAre = %q, %v; want no line at all", line, ok)
	}
}

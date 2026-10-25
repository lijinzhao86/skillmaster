package main

import (
	"strings"
	"testing"
)

// The line has to name the next step, and it must not claim completion before the journey is done.
func TestWhereYouAreNamesTheNextStep(t *testing.T) {
	cases := []struct {
		name          string
		local, drafts int
		live          int
		truncated     bool
		want          string
	}{
		{"nothing anywhere", 0, 0, 0, false, "下一步：搬运"},
		{"local only", 33, 0, 0, false, "下一步：搬运"},
		{"drafts waiting", 33, 33, 0, false, "下一步：上线"},
		{"published, copies still here", 33, 0, 33, false, "下一步：写提示词、验证，然后下线"},
		{"done", 0, 0, 33, false, "已完成"},
		{"more than a page", 0, 0, 50, true, "50 条以上"},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			got := whereYouAreLine(test.local, test.drafts, test.live, test.truncated)
			if !strings.Contains(got, test.want) {
				t.Fatalf("whereYouAreLine = %q, want it to contain %q", got, test.want)
			}
		})
	}
}

// "Done" is only true when nothing local is left; drafts and local copies both block it.
func TestWhereYouAreNeverSaysDoneWithWorkLeft(t *testing.T) {
	if got := whereYouAreLine(1, 0, 33, false); strings.Contains(got, "已完成") {
		t.Fatalf("a local copy is still there but the line says done: %q", got)
	}
	if got := whereYouAreLine(0, 1, 33, false); strings.Contains(got, "已完成") {
		t.Fatalf("a draft is still waiting but the line says done: %q", got)
	}
}

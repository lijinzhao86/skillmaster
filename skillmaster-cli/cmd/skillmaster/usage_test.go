package main

import (
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// `help usage` is the one home of the three rules, and its last block is what people paste into a
// system prompt. Both halves of that sentence are asserted here: the block is last, and it says what
// it has to without naming a single parameter (ADR 0089 — a rule that names a flag becomes a second
// description that goes stale).
func TestUsageEndsWithAPasteableSnapshotAndNamesNoFlags(t *testing.T) {
	topic, ok := commandHelp.Topic("usage")
	if !ok {
		t.Fatal("there is no `help usage` topic")
	}

	const begin = "<!-- skillmaster:begin -->"
	const end = "<!-- skillmaster:end -->"
	start := strings.Index(topic.Body, begin)
	if start < 0 {
		t.Fatalf("the topic has no begin marker:\n%s", topic.Body)
	}
	finish := strings.Index(topic.Body, end)
	if finish < start {
		t.Fatalf("the topic has no end marker after the begin one:\n%s", topic.Body)
	}

	// **The block is the last thing on the page**, so "the end of `help usage`" and "the snapshot"
	// are the same bytes — which is what the journey tells an agent to copy.
	if strings.TrimSpace(topic.Body[finish+len(end):]) != "" {
		t.Errorf("something follows the snapshot; the pasted block must be last:\n%s",
			topic.Body[finish+len(end):])
	}

	snapshot := topic.Body[start+len(begin) : finish]
	for _, want := range []string{"skills list", "skills search", "skill invoke", "不要逐个试"} {
		if !strings.Contains(snapshot, want) {
			t.Errorf("the snapshot does not carry %q:\n%s", want, snapshot)
		}
	}
	// **One flag is allowed, and only one** (ADR 0094): `--no-login` is what keeps the rule from
	// opening a browser in a session nobody is watching. Everything else is still forbidden —
	// a rule that teaches flags is a second command reference that goes stale.
	if !strings.Contains(snapshot, "skills list --no-login") {
		t.Errorf("the snapshot does not carry the session-start read:\n%s", snapshot)
	}
	if strings.Contains(strings.ReplaceAll(snapshot, "--no-login", ""), "-") {
		t.Errorf("the snapshot names a parameter other than --no-login:\n%s", snapshot)
	}

	// The top level points at it, which is how a person who lost their prompt gets back.
	if page := help.RenderTop(commandHelp); !strings.Contains(page, "流程：skillmaster help usage") {
		t.Errorf("the top-level help does not point at `help usage`:\n%s", page)
	}
}

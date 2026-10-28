package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/prompt"
)

// writePromptStore plants a `prompt.json` the way the last writing version of the CLI would have.
// Nothing writes one any more (ADR 0110), so the fixture writes the file itself.
func writePromptStore(t *testing.T, dir string, store prompt.Store) {
	t.Helper()
	blob, err := json.Marshal(store)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "prompt.json"), blob, 0o600); err != nil {
		t.Fatal(err)
	}
}

// **The record line outlived the plan it used to be printed by** (v27). `startup --plan` is gone
// (ADR 0111); `startup` itself still names the AIs this machine has already been configured for,
// because that is the local answer to 「我换了一个 AI，还要再写吗」 (ADR 0104).
func TestStartupPrintsTheRecordedAIsOnStderr(t *testing.T) {
	const body = "# SkillMaster 入门\n\n一条链，不是菜单。\n"
	server := startupServer(t, body)
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.ApiKeyEnv, "skm_livekey123456")
	dir, err := config.Dir()
	if err != nil {
		t.Fatal(err)
	}
	writePromptStore(t, dir, prompt.Store{Schema: 2, Files: []prompt.Record{{
		File: filepath.Join(os.Getenv("HOME"), "CLAUDE.md"), Agent: "claude", Digest: prompt.Digest(),
	}}})

	stdout, stderr, status := runCaptured(t, "startup")
	if status != 0 {
		t.Fatalf("status = %d, stderr=%s", status, stderr)
	}
	if !strings.HasSuffix(stdout, body) {
		t.Errorf("stdout does not end with the journey body:\n%s", stdout)
	}
	if !strings.Contains(stderr, "这台电脑上写过这段说明的 Agent：claude") {
		t.Errorf("stderr does not name the recorded AI:\n%s", stderr)
	}
}

func TestPromptRecordLineNamesEachConfiguredAI(t *testing.T) {
	dir := isolateConfig(t)
	writePromptStore(t, dir, prompt.Store{Schema: 2, Files: []prompt.Record{
		{File: "/a/CLAUDE.md", Agent: "claude", Digest: "sha256:1"},
		{File: "/b/AGENTS.md", Agent: "codex", Digest: "sha256:2"},
		{File: "/c/AGENTS.md", Agent: "claude", Digest: "sha256:3"},
		{File: "/d/AGENTS.md", Digest: "sha256:4"},
	}})
	line, ok := promptRecordLine()
	if !ok || !strings.Contains(line, "claude、codex") {
		t.Fatalf("line = %q, ok=%v", line, ok)
	}
	if strings.Count(line, "claude") != 1 {
		t.Errorf("a repeated label was not deduplicated: %q", line)
	}
	if !strings.Contains(line, "未命名") {
		t.Errorf("an unlabelled v25 record is not called 未命名: %q", line)
	}
	if strings.Contains(line, "/d/AGENTS.md") {
		t.Errorf("the line shows a path: %q", line)
	}
}

// **`startup` takes no arguments any more** (ADR 0111). Each of the three flags it used to accept
// is now an unknown argument, and none of them may be quietly ignored: a person who typed
// `--plan` is asking a question that no longer has an answer, and silence would answer it wrongly.
func TestStartupTakesNoArguments(t *testing.T) {
	isolateConfig(t)
	for _, args := range [][]string{
		{"startup", "--plan"},
		{"startup", "--agent", "codex"},
		{"startup", "--file", filepath.Join(os.Getenv("HOME"), "AGENTS.md")},
	} {
		stdout, stderr, status := runCaptured(t, args...)
		if status != 2 {
			t.Errorf("%v status = %d, want 2 (usage)", args, status)
		}
		if !strings.Contains(stdout+stderr, args[1]) {
			t.Errorf("%v does not name the argument it refused:\n%s%s", args, stdout, stderr)
		}
	}
}

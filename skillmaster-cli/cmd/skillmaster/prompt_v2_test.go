package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/prompt"
)

// **The four commands that wrote, located, updated or removed the copy retired with the markers**
// (ADR 0110). They still answer — a person who typed one deserves to know why it stopped — and
// they write nothing.
func TestTheMarkerCommandsAreRetired(t *testing.T) {
	dir := isolateConfig(t)
	file := filepath.Join(os.Getenv("HOME"), "CLAUDE.md")
	if err := os.WriteFile(file, []byte("前文\n"+string(prompt.Block())+"后文\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	before, _ := os.ReadFile(file)

	cases := map[string][]string{
		"install":   {"prompt", "install", "--file", file, "--yes"},
		"status":    {"prompt", "status"},
		"update":    {"prompt", "update", "--yes"},
		"uninstall": {"prompt", "uninstall", "--file", file, "--yes"},
	}
	for name, args := range cases {
		stdout, stderr, status := runCaptured(t, args...)
		if status != 2 {
			t.Errorf("prompt %s status = %d, want 2 (retired)", name, status)
		}
		// **The refusal is on stderr, and stdout stays empty**: stdout is where a result goes, and
		// this group's only result is the block from `prompt print`.
		if stdout != "" {
			t.Errorf("prompt %s wrote to stdout: %q", name, stdout)
		}
		for _, want := range []string{"已经退役", "skillmaster prompt print", "skillmaster startup"} {
			if !strings.Contains(stderr, want) {
				t.Errorf("prompt %s does not mention %q:\n%s", name, want, stderr)
			}
		}
	}

	after, _ := os.ReadFile(file)
	if string(before) != string(after) {
		t.Error("a retired command changed the file")
	}
	if _, err := os.Stat(filepath.Join(os.Getenv("HOME"), "AGENTS.md")); !os.IsNotExist(err) {
		t.Error("a retired install created a file")
	}
	// **Nothing new appears anywhere.** The verbs used to write `prompt.json`; a retired one that
	// quietly started a record file again would be the same regression in a quieter place.
	if _, err := os.Stat(filepath.Join(dir, "prompt.json")); !os.IsNotExist(err) {
		t.Error("a retired command wrote prompt.json")
	}
}

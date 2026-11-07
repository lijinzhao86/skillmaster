package main

import (
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/sandbox"
)

func joined(lines []string) string { return strings.Join(lines, "\n") }

// A recognised client gets its own snippet and nothing else's: printing four blocks to somebody who
// told us which client they use is noise, and printing another client's keys is worse.
func TestSandboxConfigPrintsOneSnippetForAKnownClient(t *testing.T) {
	body := joined(sandboxConfigLines(sandbox.Codex, "/state", "/cache"))
	if !strings.Contains(body, "Codex（config.toml") {
		t.Errorf("no Codex snippet:\n%s", body)
	}
	for _, other := range []string{"Claude Code（~/.claude", "Cursor（~/.cursor", "Gemini CLI"} {
		if strings.Contains(body, other) {
			t.Errorf("a known client also got %q:\n%s", other, body)
		}
	}
	if strings.Contains(body, "excludedCommands") {
		t.Errorf("Codex got Claude Code's escape hatch:\n%s", body)
	}
}

// An unrecognised client gets every snippet plus the generic one — the person may be asking before
// they open an agent, and WorkBuddy sets no marker at all.
func TestSandboxConfigPrintsEverySnippetForAnUnknownClient(t *testing.T) {
	body := joined(sandboxConfigLines(sandbox.Unknown, "/state", "/cache"))
	for _, want := range []string{
		"Claude Code（~/.claude", "Codex（config.toml", "Cursor（~/.cursor",
		"Gemini CLI", "WorkBuddy 5.8.3", "excludedCommands",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("unknown client is missing %q:\n%s", want, body)
		}
	}
}

// The write paths are this command's own half and must be there whatever the client is.
func TestSandboxConfigAlwaysNamesTheWritePaths(t *testing.T) {
	for _, client := range []sandbox.Client{sandbox.ClaudeCode, sandbox.Codex, sandbox.Cursor, sandbox.Gemini, sandbox.Unknown} {
		body := joined(sandboxConfigLines(client, "/state", "/cache"))
		for _, want := range []string{
			`filesystem.allowWrite = ["/state", "/cache"]`,
			`writable_roots = ["/state", "/cache"]`,
			`additionalReadwritePaths = ["/state", "/cache"]`,
			`extraAllowWrite = ["/state", "/cache"]`,
		} {
			if !strings.Contains(body, want) {
				t.Errorf("%s: missing %q:\n%s", client, want, body)
			}
		}
	}
}

// Claude Code is the one client with the escape hatch, and it is printed for it alone — plus the
// unknown case, where it is the only client we can guess the person might be in.
func TestSandboxConfigOffersExcludedCommandsOnlyToClaude(t *testing.T) {
	for _, tc := range []struct {
		client sandbox.Client
		want   bool
	}{
		{sandbox.ClaudeCode, true},
		{sandbox.Unknown, true},
		{sandbox.Codex, false},
		{sandbox.Cursor, false},
		{sandbox.Gemini, false},
	} {
		if got := strings.Contains(joined(sandboxConfigLines(tc.client, "/s", "/c")), "excludedCommands"); got != tc.want {
			t.Errorf("%s: excludedCommands present = %v, want %v", tc.client, got, tc.want)
		}
	}
}

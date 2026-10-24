package main

import (
	"context"
	"os"
	"sort"
	"strconv"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// Exit codes are a public contract (ADR 0087) with three projections: the declarations, the
// top-level help, and the CLI README. This file compares all three against the registry, so a
// change to any one of them alone fails here (v16 验收 #19).

// genericCodes are the failures the classifier produces on paths no declaration enumerates. They
// are allowed to be referenced by nobody.
var genericCodes = map[string]bool{
	"usage":                   true,
	"network":                 true,
	"server_error":            true,
	"insufficient_permission": true,
	"not_found":               true,
}

// exitSet is the distinct statuses a projection carries.
func exitSet(codes []int) []int {
	seen := map[int]bool{}
	for _, code := range codes {
		seen[code] = true
	}
	set := make([]int, 0, len(seen))
	for code := range seen {
		set = append(set, code)
	}
	sort.Ints(set)
	return set
}

func TestDeclarationsProjectEveryExitClass(t *testing.T) {
	var declared []int
	for _, spec := range commandHelp.Specs() {
		declared = append(declared, spec.Exits...)
	}
	got := exitSet(declared)
	want := []int{0, 1, 2, 4}
	if len(got) != len(want) {
		t.Fatalf("declared exit codes are %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("declared exit codes are %v, want %v", got, want)
		}
	}
}

// TestTopLevelHelpCarriesEveryExitClass reads the four lines out of the rendered help: a declared
// class the top level does not print is one a caller cannot find.
func TestTopLevelHelpCarriesEveryExitClass(t *testing.T) {
	classes := help.ExitClasses()
	if len(classes) != 4 {
		t.Fatalf("the registry has %d exit classes, want 4", len(classes))
	}
	text := help.RenderTop(commandHelp)
	_, after, found := strings.Cut(text, "退出码：\n")
	if !found {
		t.Fatalf("top-level help has no exit-code block:\n%s", text)
	}
	lines := strings.Split(strings.TrimSpace(after), "\n")
	byCode := map[int]string{}
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || !isDigit(trimmed[0]) {
			break
		}
		code, rest, ok := strings.Cut(trimmed, " ")
		if !ok {
			continue
		}
		number, err := strconv.Atoi(code)
		if err != nil {
			continue
		}
		byCode[number] = strings.TrimSpace(rest)
	}
	compareClasses(t, byCode, classes)
}

// TestCLIReadmeCarriesEveryExitClass reads the four-row table out of `skillmaster-cli/README.md`.
// The table is a projection of the registry, so its text is the registry's text.
func TestCLIReadmeCarriesEveryExitClass(t *testing.T) {
	data, err := os.ReadFile("../../README.md")
	if err != nil {
		t.Fatalf("read the CLI README: %v", err)
	}
	byCode := map[int]string{}
	for _, line := range strings.Split(string(data), "\n") {
		cells := strings.Split(strings.Trim(line, "| "), "|")
		if len(cells) != 2 {
			continue
		}
		code := strings.TrimSpace(cells[0])
		code = strings.Trim(code, "`")
		number, err := strconv.Atoi(code)
		if err != nil {
			continue
		}
		byCode[number] = strings.TrimSpace(cells[1])
	}
	if len(byCode) == 0 {
		t.Fatal("the CLI README has no exit-code table")
	}
	compareClasses(t, byCode, help.ExitClasses())
}

func compareClasses(t *testing.T, got map[int]string, want []help.ExitClass) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("carries %d exit classes, want %d: %v", len(got), len(want), got)
	}
	for _, class := range want {
		text, ok := got[class.Code]
		if !ok {
			t.Fatalf("exit code %d is missing", class.Code)
		}
		if text != class.Text {
			t.Fatalf("exit code %d reads %q, want %q", class.Code, text, class.Text)
		}
	}
}

// TestDeclaredFailuresFitTheCommandsExitCodes keeps a declaration from promising a status it does
// not list: a command that names `token_expired` exits 4, so 4 has to be in its Exits.
func TestDeclaredFailuresFitTheCommandsExitCodes(t *testing.T) {
	exits := map[string]int{}
	for _, code := range help.Codes() {
		exits[code.Name] = code.Exit
	}
	for _, spec := range commandHelp.Specs() {
		allowed := map[int]bool{}
		for _, code := range spec.Exits {
			allowed[code] = true
		}
		for _, failure := range spec.Failures {
			status, ok := exits[failure.Code]
			if !ok {
				t.Errorf("%q declares the unregistered failure %q", spec.Path, failure.Code)
				continue
			}
			if !allowed[status] {
				t.Errorf("%q declares failure %q, which exits %d, but its Exits are %v",
					spec.Path, failure.Code, status, spec.Exits)
			}
		}
	}
}

// TestEveryRegisteredFailureIsDeclaredSomewhere keeps the registry from growing a code no command
// can produce: a caller reading it in `help` would be reading about nothing.
func TestEveryRegisteredFailureIsDeclaredSomewhere(t *testing.T) {
	used := map[string]bool{}
	for _, spec := range commandHelp.Specs() {
		for _, failure := range spec.Failures {
			used[failure.Code] = true
		}
	}
	for _, code := range help.Codes() {
		if genericCodes[code.Name] {
			continue
		}
		if !used[code.Name] {
			t.Errorf("failure %q is registered but no command declares it", code.Name)
		}
	}
}

func isDigit(b byte) bool { return b >= '0' && b <= '9' }

// TestHelpHasNoSideEffects is 验收 #16 / T23: `logout --help` used to clear this machine's
// credential, because the handler ran before anyone looked at the arguments. Help is now answered
// before the handler, so the store is never touched.
func TestHelpHasNoSideEffects(t *testing.T) {
	store := &stubStore{creds: credentials.Credentials{AccessToken: "token"}, found: true}
	pointTheMachineAt(t, store)

	var err error
	out := captureStdout(t, func() { err = run(context.Background(), []string{"logout", "--help"}) })
	if err != nil {
		t.Fatalf("logout --help: %v", err)
	}
	if !strings.Contains(out, "用法：skillmaster logout") {
		t.Fatalf("logout --help did not print its help:\n%s", out)
	}
	if store.deletes != 0 {
		t.Fatalf("logout --help deleted the credential %d time(s)", store.deletes)
	}
	if !store.found {
		t.Fatal("logout --help left the store empty")
	}
}

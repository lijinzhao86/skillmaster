package main

import (
	"reflect"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// The declaration is a promise about the command, so the tests read it the way a caller would:
// feed what help says to the pure parser and see whether it is accepted (test-plan T03 / T22).

// TestEveryDeclaredFlagIsAccepted walks each command's own flags and offers each one to the parser.
// A flag help advertises but the parser refuses is a command that cannot be used as documented.
func TestEveryDeclaredFlagIsAccepted(t *testing.T) {
	for _, spec := range commandHelp.Specs() {
		if spec.PureGroup || spec.Path == "" {
			continue
		}
		for _, flag := range spec.Flags {
			args := []string{}
			for _, arg := range spec.Args {
				if arg.Required {
					args = append(args, "value")
				}
			}
			args = append(args, flag.Name)
			if flag.Value != "" {
				args = append(args, "1")
			}
			if _, err := parseOnly(spec.Path, args); err != nil {
				t.Errorf("%q refuses its own --%s: %v", spec.Path, strings.TrimLeft(flag.Name, "-"), err)
			}
		}
	}
}

// TestEveryDeclaredPositionalBinds walks **every** positional, optional ones included, in
// declaration order, and checks each arrives under its own key (T03). Optional ones used to be
// skipped here, so `skill submit [名字|目录]` and `skill wc [相对路径...]` had no evidence at all.
//
// It also gives the last positional one more token than the declaration names, because that is the
// half of the rule the parser owns: a `...` argument keeps the list and a phrase argument keeps the
// words joined (`skills search`) — the two shapes that read more than one token.
func TestEveryDeclaredPositionalBinds(t *testing.T) {
	for _, spec := range commandHelp.Specs() {
		if spec.PureGroup || spec.Path == "" || len(spec.Args) == 0 {
			continue
		}
		var args []string
		want := map[string]any{}
		for i, arg := range spec.Args {
			value := "value" + string(rune('0'+i))
			args = append(args, value)
			want[arg.Key] = value
		}
		last := spec.Args[len(spec.Args)-1]
		args = append(args, "extra")
		if strings.HasSuffix(last.Name, "...") {
			want[last.Key] = []string{want[last.Key].(string), "extra"}
		} else {
			want[last.Key] = want[last.Key].(string) + " extra"
		}

		bound, err := parseOnly(spec.Path, args)
		if err != nil {
			t.Errorf("%q refuses its own positionals %v: %v", spec.Path, args, err)
			continue
		}
		for key, value := range want {
			got, ok := bound[key]
			if !ok {
				t.Errorf("%q bound no %q for %v", spec.Path, key, args)
				continue
			}
			if !reflect.DeepEqual(got, value) {
				t.Errorf("%q bound %q to %#v, want %#v", spec.Path, key, got, value)
			}
		}
	}
}

// TestEveryExampleParses is 验收 #20: every example in help is an invocation the command accepts.
// Only the parsing is run — no request, no state.
func TestEveryExampleParses(t *testing.T) {
	for _, spec := range commandHelp.Specs() {
		for _, example := range spec.Examples {
			path, args, ok := splitExample(example)
			if !ok {
				t.Errorf("%q has an example that does not start with `skillmaster %s`: %q", spec.Path, spec.Path, example)
				continue
			}
			if path != spec.Path {
				t.Errorf("%q has an example for another command: %q", spec.Path, example)
				continue
			}
			if _, err := parseOnly(path, args); err != nil {
				t.Errorf("%q rejects its own example %q: %v", spec.Path, example, err)
			}
		}
	}
}

// TestExamplesCoverTheCommandsThatNeedThem is 验收 #20's lower bound: two or more positionals, or
// two or more behavioural flags, means at least one example. The three main-chain commands need one
// whatever their shape.
func TestExamplesCoverTheCommandsThatNeedThem(t *testing.T) {
	always := map[string]bool{"skills search": true, "skill invoke": true, "skill read": true}
	for _, spec := range commandHelp.Specs() {
		if spec.PureGroup || spec.Path == "" {
			continue
		}
		needs := always[spec.Path] || len(spec.Args) >= 2 || len(spec.Flags) >= 2
		if needs && len(spec.Examples) == 0 {
			t.Errorf("%q needs at least one example and has none", spec.Path)
		}
		if len(spec.Examples) > 2 {
			t.Errorf("%q has %d examples; at most two", spec.Path, len(spec.Examples))
		}
	}
}

// TestNoDeclarationBindsOneKeyTwice: two arguments under one key would silently overwrite.
func TestNoDeclarationBindsOneKeyTwice(t *testing.T) {
	for _, spec := range commandHelp.Specs() {
		seen := map[string]bool{}
		for _, arg := range spec.Args {
			if seen[arg.Key] {
				t.Errorf("%q binds %q twice", spec.Path, arg.Key)
			}
			seen[arg.Key] = true
		}
	}
}

// TestMainChainNextSteps is 验收 #20's second half: the four handoffs have to be there.
func TestMainChainNextSteps(t *testing.T) {
	want := map[string]string{
		"skills search":   "skill invoke",
		"skill invoke":    "skill read",
		"skill submit":    "浏览器",
		// v22: a refusal's next step is the handoff that actually does the thing. Listing versions
		// after `version publish` left the reader where they started.
		"version publish": "version open",
	}
	for path, contains := range want {
		spec, ok := help.Lookup(commandHelp, path)
		if !ok {
			t.Errorf("%q has no declaration", path)
			continue
		}
		if !strings.Contains(spec.Next, contains) {
			t.Errorf("%q's next step is %q, want it to mention %q", path, spec.Next, contains)
		}
	}
}

// splitExample turns `skillmaster skill read a/b SKILL.md` into the path and the arguments. The path
// is the longest declared prefix, so a group's example does not swallow its child's name. A leading
// pipeline is kept as an example's idiom — `echo hi | skillmaster skill write …` is pasteable — so
// the invocation starts at the word `skillmaster`.
func splitExample(example string) (string, []string, bool) {
	words := strings.Fields(example)
	at := -1
	for i, word := range words {
		if word == "skillmaster" {
			at = i
			break
		}
	}
	if at < 0 || len(words) <= at+1 {
		return "", nil, false
	}
	rest := words[at+1:]
	for length := len(rest); length > 0; length-- {
		candidate := strings.Join(rest[:length], " ")
		if _, ok := help.Lookup(commandHelp, candidate); ok {
			return candidate, rest[length:], true
		}
	}
	return "", nil, false
}

package main

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"regexp"
	"strconv"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// A flag the code accepts but no declaration names is help that leaves something out; a flag the
// declaration names but the code never mentions is help that promises something that does not work.
// The handler side is read from the source, because the parsers are spread over the commands and
// helpers that consume them (test-plan T02).
//
// This scan is package-wide rather than per command: a literal has to be declared **somewhere**.
// Proving it is declared on the command that uses it would take a call graph, and the per-command
// direction is what `TestEveryDeclaredFlagIsAccepted` already checks from the other side.

// notFlags are single-dash words that are data rather than flags: `-` is stdin.
var notFlags = map[string]bool{"-": true}

// refusedFlags are named in the parser only to be turned away, so no command's help advertises
// them: `--cursor` belongs to the consumer's `skill versions`, and `version list` refuses it by
// name (v6) rather than letting it look like a flag this command has.
var refusedFlags = map[string]bool{"--cursor": true}

// Uppercase short flags are real here (-F, -i, -n, -l on `skill grep`), so the class is not [a-z].
var flagLike = regexp.MustCompile(`^-{1,2}[A-Za-z][A-Za-z0-9-]*$`)

func TestEveryFlagLiteralIsDeclared(t *testing.T) {
	declared := map[string]bool{"--help": true, "-h": true, "--json": true}
	for _, spec := range commandHelp.Specs() {
		for _, flag := range spec.Flags {
			declared[flag.Name] = true
			for _, alias := range flag.Aliases {
				declared[alias] = true
			}
		}
		for _, name := range spec.SharedFlags {
			declared[name] = true
		}
	}

	fset := token.NewFileSet()
	packages, err := parser.ParseDir(fset, ".", func(info os.FileInfo) bool {
		return !strings.HasSuffix(info.Name(), "_test.go")
	}, 0)
	if err != nil {
		t.Fatalf("parse the package: %v", err)
	}
	for _, pkg := range packages {
		for _, file := range pkg.Files {
			ast.Inspect(file, func(node ast.Node) bool {
				literal, ok := node.(*ast.BasicLit)
				if !ok || literal.Kind != token.STRING {
					return true
				}
				value, err := strconv.Unquote(literal.Value)
				if err != nil || notFlags[value] || !flagLike.MatchString(value) {
					return true
				}
				if !declared[value] && !refusedFlags[value] {
					t.Errorf("%s: the flag %q is used but no declaration names it",
						fset.Position(literal.Pos()), value)
				}
				return true
			})
		}
	}
}

// TestUniversalFlagsAreOnEveryCommand is the shared registry's other half: `--help` and `-h` are
// every command's, `--json` only where the declaration says so.
func TestUniversalFlagsAreOnEveryCommand(t *testing.T) {
	for _, spec := range commandHelp.Specs() {
		names := map[string]bool{}
		for _, flag := range help.Universal(commandHelp, spec.Path) {
			names[flag.Name] = true
			for _, alias := range flag.Aliases {
				names[alias] = true
			}
		}
		if !names["--help"] || !names["-h"] {
			t.Errorf("%q does not get --help/-h from the shared registry", spec.Path)
		}
		wantJSON := len(spec.JSONFields) > 0
		if names["--json"] != wantJSON {
			t.Errorf("%q has --json = %v, want %v (JSONFields set = %v)",
				spec.Path, names["--json"], wantJSON, wantJSON)
		}
	}
}

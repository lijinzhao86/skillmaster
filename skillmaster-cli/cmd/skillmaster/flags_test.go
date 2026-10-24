package main

import (
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// **The per-command direction lives in `flagattribution_test.go`**: a literal has to be declared on
// the command that reaches it, not merely somewhere in the package. What is left here is the other
// half of the shared registry — which universal flags each command gets.

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

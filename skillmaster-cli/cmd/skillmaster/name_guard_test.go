package main

import (
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

// TestTheOldCommandNameIsWrittenOnce is 验收 3 与验收 6 的守卫 (ADR 0113).
//
// **Go only.** The install scripts, the site page and the server strings are covered by the
// repo-wide grep in the test plan (T8); this test never looks at them.
//
// **The old spelling lives in three declarations, all in `config`** — the artifact prefix
// (`PackageName`), the compatibility entry (`LegacyName`) and the state directory (`stateDir`) —
// and nothing outside that file may spell it. A literal anywhere else is how a rename half-lands: one hint keeps telling people to run a
// command that no longer exists, and nothing fails until somebody reads that exact line.
//
// What it counts, in non-test sources only: **any literal that contains the word at all.** The
// first version of this guard matched only `skillmaster`, `skillmaster.exe`, `skillmaster …` and
// `skillmaster_…`, and two user-visible strings got through — `升级 skillmaster。` has no space
// after the name, and an artifact name built with a format string is not the bare word either.
// A missed hint is the exact failure this test exists for, so the rule is the word, not its shape.
//
// What it deliberately does not count — each one a different fact that happens to be spelled with
// the same letters (see `isOldCommandName`): the OAuth client id `skillmaster-cli`, the domains, a skill
// address under the publish namespace (`skillmaster/startup`), and the sidecar directory
// `.skillmaster/`. Test files are out of scope on purpose: fixtures say all sorts of things, and the
// product's words are what this guards.
func TestTheOldCommandNameIsWrittenOnce(t *testing.T) {
	root := filepath.Join("..", "..")
	declaration := filepath.Join("internal", "config", "config.go")
	seen := 0

	err := filepath.WalkDir(root, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return nil
		}
		file, err := parser.ParseFile(token.NewFileSet(), path, nil, 0)
		if err != nil {
			return err
		}
		relative, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		ast.Inspect(file, func(node ast.Node) bool {
			literal, ok := node.(*ast.BasicLit)
			if !ok || literal.Kind != token.STRING {
				return true
			}
			value, err := strconv.Unquote(literal.Value)
			if err != nil || !isOldCommandName(value) {
				return true
			}
			if filepath.ToSlash(relative) == filepath.ToSlash(declaration) {
				seen++
				return true
			}
			t.Errorf("%s: the old command name is spelled out as a literal; compose it from config.LegacyName (ADR 0113)", relative)
			return true
		})
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if seen != 3 {
		t.Errorf("%s spells the old name %d times, want exactly three (PackageName, LegacyName, stateDir)", declaration, seen)
	}
	// The count alone cannot say *which* three: drop one and add a user-visible string, and it is
	// still three. Pin the two exported ones by value. `stateDir` is unexported and only reachable
	// from here through the count, which is why the count is kept as well.
	if config.PackageName != "skillmaster" {
		t.Errorf("PackageName = %q, want the artifact prefix to stay \"skillmaster\"", config.PackageName)
	}
	if config.LegacyName != "skillmaster" {
		t.Errorf("LegacyName = %q, want the one compatibility name to stay \"skillmaster\"", config.LegacyName)
	}
}

func isOldCommandName(value string) bool {
	// **Strip the other facts first, then look.** Testing "does the literal contain one of them"
	// would exempt the whole literal: `用 skillmaster-cli 这个 client，然后运行 skillmaster login`
	// is one string holding both a client id and a command, and a substring test says "fine".
	trimmed := value
	for _, otherFact := range []string{
		config.LegacyName + "-cli", // the registered OAuth client id and the keychain service,
		// and the module path — `github.com/…/skillmaster/skillmaster-cli/…` contains both this and
		// the slash form below, which is why the list is applied in order
		config.LegacyName + "ai.", // the domains, as in `skillmasterai.com`
		config.LegacyName + ".ai", // and the dotted spelling, if anyone ever writes `skillmaster.ai`
		config.LegacyName + "/",   // a skill address under the publish namespace, and the module path
		"." + config.LegacyName,   // the sidecar directory inside a skill version
	} {
		trimmed = strings.ReplaceAll(trimmed, otherFact, "")
	}
	return strings.Contains(trimmed, config.LegacyName)
}

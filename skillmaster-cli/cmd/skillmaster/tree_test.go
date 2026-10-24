package main

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"sort"
	"strconv"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// The command tree has two authorities that must agree: the declarations (what help says exists)
// and the dispatch switches (what the binary will actually run). A command added to one and not the
// other is the failure this file exists to catch — help for something that does not run, or a
// command nobody can find in help (v16 验收 #2 的前置, test-plan T05).
var dispatchFunctions = map[string]string{
	"run":          "",
	"skillVerb":    "skill",
	"skillsVerb":   "skills",
	"versionVerb":  "version",
	"authCmd":      "auth",
	"myTagsCmd":    "skills my-tags",
	"tagVerb":      "skill tag",
	"categoryVerb": "skill category",
}

func TestTheCommandTreeMatchesTheSource(t *testing.T) {
	dispatched := map[string][]string{}
	for _, fn := range dispatchSwitches(t) {
		dispatched[fn.name] = fn.cases
	}

	seen := map[string]bool{}
	for name, cases := range dispatched {
		path, ok := dispatchFunctions[name]
		if !ok {
			t.Errorf("%s dispatches on args[0] but has no entry: a command reachable here would have no declaration", name)
			continue
		}
		seen[name] = true
		spec, ok := help.Lookup(commandHelp, path)
		if !ok {
			t.Errorf("%s is registered as the node %q, which has no declaration", name, path)
			continue
		}
		if got, want := sorted(cases), sorted(spec.Children); !equal(got, want) {
			t.Errorf("the node %q declares children %v but %s dispatches %v", path, want, name, got)
		}
	}
	for name, path := range dispatchFunctions {
		if !seen[name] {
			t.Errorf("%s is registered for %q but no longer dispatches on args[0]", name, path)
		}
	}
}

// TestEveryDispatchIsRegistered is T05 ④ on its own: a new group function that nobody registered
// fails even when its node happens not to clash with anything.
func TestEveryDispatchIsRegistered(t *testing.T) {
	for _, fn := range dispatchSwitches(t) {
		if _, ok := dispatchFunctions[fn.name]; !ok {
			t.Errorf("%s dispatches on args[0] and must be registered with the node it owns", fn.name)
		}
	}
}

type dispatch struct {
	name  string
	cases []string
}

// dispatchSwitches finds every switch whose tag is `args[0]` and returns its string cases. A switch
// on a single argument (`arg`) is a flag switch, not a command one, and is left out.
func dispatchSwitches(t *testing.T) []dispatch {
	t.Helper()
	fset := token.NewFileSet()
	packages, err := parser.ParseDir(fset, ".", func(info os.FileInfo) bool {
		return !strings.HasSuffix(info.Name(), "_test.go")
	}, 0)
	if err != nil {
		t.Fatalf("parse the package: %v", err)
	}
	var found []dispatch
	for _, pkg := range packages {
		for _, file := range pkg.Files {
			for _, decl := range file.Decls {
				fn, ok := decl.(*ast.FuncDecl)
				if !ok || fn.Body == nil {
					continue
				}
				ast.Inspect(fn.Body, func(node ast.Node) bool {
					sw, ok := node.(*ast.SwitchStmt)
					if !ok || sw.Tag == nil || exprName(sw.Tag) != "args[0]" {
						return true
					}
					entry := dispatch{name: fn.Name.Name}
					for _, statement := range sw.Body.List {
						clause := statement.(*ast.CaseClause)
						for _, expr := range clause.List {
							literal, ok := expr.(*ast.BasicLit)
							if !ok || literal.Kind != token.STRING {
								continue
							}
							value, err := strconv.Unquote(literal.Value)
							if err != nil {
								continue
							}
							entry.cases = append(entry.cases, value)
						}
					}
					found = append(found, entry)
					return true
				})
			}
		}
	}
	return found
}

// exprName renders the small expressions a switch tag can be here: an identifier or an index.
func exprName(expr ast.Expr) string {
	switch x := expr.(type) {
	case *ast.Ident:
		return x.Name
	case *ast.IndexExpr:
		return exprName(x.X) + "[" + exprName(x.Index) + "]"
	case *ast.BasicLit:
		return x.Value
	}
	return ""
}

func sorted(values []string) []string {
	copied := append([]string(nil), values...)
	sort.Strings(copied)
	return copied
}

func equal(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

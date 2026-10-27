package nodelete

import (
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"testing"
)

// **The CLI never deletes files** ([ADR 0101](../../../docs/decisions/0101-the-cli-never-deletes-files.md)).
//
// A sandbox approves every file operation an agent's command performs, and a delete is the one that
// reads as "it is taking something away" — the approval that stops a task and waits for a person. The
// command surface never needed one: every delete this package once had was cleanup of its own
// temporary files. This test is what keeps that true after the cleanup is gone.
//
// It is a source-level check, not a proof: a call made through a variable, reflection, or a helper in
// another module would not be seen. That is why ADR 0101 also records the rule — the test makes the
// ordinary way of adding one fail, and the ADR makes the deliberate way require a decision.
func TestProductCodeNeverDeletesFiles(t *testing.T) {
	root := moduleRoot(t)
	var violations []string

	err := filepath.WalkDir(root, func(current string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() {
			if current == root {
				return nil
			}
			name := entry.Name()
			// Test fixtures are data, not product code, and `vendor` would be somebody else's.
			if strings.HasPrefix(name, ".") || name == "testdata" || name == "vendor" {
				return filepath.SkipDir
			}
			return nil
		}
		if !strings.HasSuffix(current, ".go") || strings.HasSuffix(current, "_test.go") {
			return nil
		}
		fset := token.NewFileSet()
		parsed, err := parser.ParseFile(fset, current, nil, 0)
		if err != nil {
			return err
		}
		relative, err := filepath.Rel(root, current)
		if err != nil {
			relative = current
		}
		violations = append(violations, scanForDeletes(fset, relative, parsed)...)
		return nil
	})
	if err != nil {
		t.Fatalf("walking the module: %v", err)
	}

	if len(violations) > 0 {
		t.Errorf("the CLI never deletes files (ADR 0101); found %d call(s):\n%s",
			len(violations), strings.Join(violations, "\n"))
	}
}

// TestTheScannerSeesADelete is the check's own check.
//
// A scanner that silently returns nothing looks exactly like a clean tree, so the one failure mode
// that would make the test above meaningless is asserted here: it must see a delete, including one
// written through an import alias.
func TestTheScannerSeesADelete(t *testing.T) {
	const source = `package sample

import (
	"os"
	quiet "os"
	"syscall"
	"golang.org/x/sys/unix"
)

func f() {
	_ = os.Remove("a")
	_ = quiet.RemoveAll("b")
	_ = unix.Rmdir("c")
	_ = syscall.DeleteFile("d")
	_ = os.Getenv("not a delete")
}
`
	fset := token.NewFileSet()
	file, err := parser.ParseFile(fset, "sample.go", source, 0)
	if err != nil {
		t.Fatalf("parsing the sample: %v", err)
	}
	found := scanForDeletes(fset, "sample.go", file)
	if len(found) != 4 {
		t.Fatalf("the scanner found %d delete(s), want 4:\n%s", len(found), strings.Join(found, "\n"))
	}
	for _, want := range []string{"os.Remove", "RemoveAll", "unix.Rmdir", "syscall.DeleteFile"} {
		joined := strings.Join(found, "\n")
		if !strings.Contains(joined, want) {
			t.Errorf("the scanner missed %s:\n%s", want, joined)
		}
	}
}

// deletePackages are the imports whose functions remove things from a filesystem.
//
// `os.Rename` and `os.Truncate` are deliberately absent: a rename is not an unlink, and truncating is
// how `logout` clears a credential without one (ADR 0101).
var deletePackages = map[string]bool{
	"os":                    true,
	"syscall":               true,
	"golang.org/x/sys/unix": true,
}

// deleteFuncs are the functions in those packages that delete.
var deleteFuncs = map[string]bool{
	"Remove":          true,
	"RemoveAll":       true,
	"Unlink":          true,
	"Unlinkat":        true,
	"Rmdir":           true,
	"Rmdirat":         true,
	"DeleteFile":      true, // Windows: syscall.DeleteFile / windows.DeleteFile
	"RemoveDirectory": true, // Windows
}

// scanForDeletes reports every `pkg.Func(...)` in file where pkg is one of deletePackages and Func is
// one of deleteFuncs. Import aliases are resolved, so `o "os"` is seen as `os`.
func scanForDeletes(fset *token.FileSet, name string, file *ast.File) []string {
	imports := map[string]string{}
	for _, spec := range file.Imports {
		importPath, err := strconv.Unquote(spec.Path.Value)
		if err != nil {
			continue
		}
		alias := path.Base(importPath)
		if spec.Name != nil {
			alias = spec.Name.Name
		}
		imports[alias] = importPath
	}

	var violations []string
	ast.Inspect(file, func(node ast.Node) bool {
		call, ok := node.(*ast.CallExpr)
		if !ok {
			return true
		}
		selector, ok := call.Fun.(*ast.SelectorExpr)
		if !ok {
			return true
		}
		qualifier, ok := selector.X.(*ast.Ident)
		if !ok {
			return true
		}
		if !deletePackages[imports[qualifier.Name]] || !deleteFuncs[selector.Sel.Name] {
			return true
		}
		violations = append(violations, name+":"+strconv.Itoa(fset.Position(call.Pos()).Line)+
			": "+qualifier.Name+"."+selector.Sel.Name)
		return true
	})
	return violations
}

// moduleRoot is the directory `go test ./...` runs this module from, found from this file's own path
// so the test does not depend on the working directory.
func moduleRoot(t *testing.T) string {
	t.Helper()
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller could not find this test's file")
	}
	// .../skillmaster-cli/internal/nodelete/nodelete_test.go
	root := filepath.Dir(filepath.Dir(filepath.Dir(file)))
	// **A wrong root would scan nothing and pass**, which is the one failure this check cannot
	// afford. `go.mod` is what makes the directory the module.
	if _, err := os.Stat(filepath.Join(root, "go.mod")); err != nil {
		t.Fatalf("the module root %s has no go.mod: %v", root, err)
	}
	return root
}

package main

import (
	"encoding/json"
	"go/ast"
	"go/parser"
	"go/token"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// The registry is the public list of failures (ADR 0087). A code nobody can produce is a promise
// with nothing behind it, and a code invented at a call site is a failure no help describes — so
// both directions are tested here (test-plan T17, 验收 #15).

type errorFixture struct {
	Code   string   `json:"code"`
	Argv   []string `json:"argv"`
	Status int      `json:"status"`
	Body   string   `json:"body"`
	Dead   bool     `json:"dead"`
}

// defensiveCodes are registered names that no command can actually return today, with the reason.
// Everything else has to be produced by a fixture.
var defensiveCodes = map[string]string{
	"login_failed": "登录失败没有 --json，也没有稳定的服务端码：声明用这个名字是为了让帮助说出下一步，" +
		"分类器仍按通用失败（1）处理",
}

func registryExit(t *testing.T, code string) int {
	t.Helper()
	for _, entry := range help.Codes() {
		if entry.Name == code {
			return entry.Exit
		}
	}
	t.Fatalf("%q is not a registry code", code)
	return 0
}

func TestEveryRegisteredCodeIsReallyTriggered(t *testing.T) {
	data, err := os.ReadFile("testdata/error-fixtures.json")
	if err != nil {
		t.Fatalf("read the fixtures: %v", err)
	}
	var file struct {
		Fixtures []errorFixture `json:"fixtures"`
	}
	if err := json.Unmarshal(data, &file); err != nil {
		t.Fatalf("parse the fixtures: %v", err)
	}

	covered := map[string]bool{}
	for _, fixture := range file.Fixtures {
		t.Run(fixture.Code, func(t *testing.T) {
			covered[fixture.Code] = true
			want := registryExit(t, fixture.Code)

			if fixture.Dead {
				t.Setenv(config.ServerEnv, "http://127.0.0.1:1")
				t.Setenv(config.ApiKeyEnv, "skm_test")
			} else {
				status, body := fixture.Status, fixture.Body
				pointAtServer(t, func(w http.ResponseWriter, _ *http.Request) {
					if status != 0 {
						w.Header().Set("Content-Type", "application/json")
						w.WriteHeader(status)
					}
					_, _ = w.Write([]byte(body))
				})
			}

			_, stderr, got := runCaptured(t, fixture.Argv...)
			if got != want {
				t.Fatalf("%v: status %d, want %d (stderr %q)", fixture.Argv, got, want, stderr)
			}
			if strings.TrimSpace(stderr) == "" {
				t.Fatalf("%v: a failure printed nothing", fixture.Argv)
			}
			if hasJSON(fixture.Argv) {
				if !strings.Contains(stderr, `"code":"`+fixture.Code+`"`) {
					t.Fatalf("%v: the error object does not carry %q:\n%s", fixture.Argv, fixture.Code, stderr)
				}
			}
		})
	}

	var missing []string
	for _, entry := range help.Codes() {
		if covered[entry.Name] {
			continue
		}
		if reason, ok := defensiveCodes[entry.Name]; ok {
			if strings.TrimSpace(reason) == "" {
				t.Errorf("%q is exempt with no reason", entry.Name)
			}
			continue
		}
		missing = append(missing, entry.Name)
	}
	sort.Strings(missing)
	if len(missing) > 0 {
		t.Errorf("registered codes with no fixture and no reason: %v", missing)
	}
}

func hasJSON(argv []string) bool {
	for _, arg := range argv {
		if arg == "--json" {
			return true
		}
	}
	return false
}

// The other direction: a handler must not invent a code. Every `Code: "…"` literal in the package
// has to be a registry name.
func TestHandlersOnlyUseRegistryCodes(t *testing.T) {
	registered := map[string]bool{}
	for _, entry := range help.Codes() {
		registered[entry.Name] = true
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
			check := func(field string, value ast.Expr) {
				literal, ok := value.(*ast.BasicLit)
				if !ok || literal.Kind != token.STRING {
					return
				}
				name, err := strconv.Unquote(literal.Value)
				if err != nil || name == "" {
					return
				}
				if !registered[name] {
					t.Errorf("%s: %s = %q is not a registry code", fset.Position(literal.Pos()), field, name)
				}
			}
			ast.Inspect(file, func(node ast.Node) bool {
				switch node := node.(type) {
				case *ast.KeyValueExpr:
					key, ok := node.Key.(*ast.Ident)
					if ok && (key.Name == "Code" || key.Name == "ServerCode") {
						check(key.Name, node.Value)
					}
				case *ast.AssignStmt:
					// **The classifier writes codes by assignment**, not only in a composite literal
					// (`failure.Code = "usage"`), and a scan that only read literals would have
					// declared the whole of `classifyFailure` clean without looking at it.
					for i, left := range node.Lhs {
						selector, ok := left.(*ast.SelectorExpr)
						if !ok || i >= len(node.Rhs) {
							continue
						}
						if selector.Sel.Name == "Code" || selector.Sel.Name == "ServerCode" {
							check(selector.Sel.Name, node.Rhs[i])
						}
					}
				}
				return true
			})
		}
	}
}

package main

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// **T02's reverse direction, attributed to the command that reads the flag.** The earlier scan was
// package-wide: a literal had to be declared *somewhere*, so a handler that read a flag declared
// only on another command passed. This one asks the question help actually promises — "the flags
// this command reads are the flags its help lists" — by walking from the command's own entry
// function through the package's call graph.
//
// The graph is derived, not written down: each dispatcher's `switch args[0]` names the function a
// `case` reaches, and `run` → `skillVerb` → `tagVerb` gives the full paths. A shared parser
// (`splitNewDraftFlags`, `parseOffset`) is therefore attributed to **every** command that reaches
// it, which is the point: those were the places a declaration could quietly fall behind.

// flagLike catches both long flags and the uppercase short ones (`-F`, `-i`, `-n`, `-l`).
var flagLike = regexp.MustCompile(`^-{1,2}[A-Za-z][A-Za-z0-9-]*$`)

// notFlags are single-dash words that are data rather than flags: `-` is stdin.
var notFlags = map[string]bool{"-": true}

// refusedFlags are named in the parser only to be turned away, so no command's help advertises
// them: `--cursor` belongs to the consumer's `skill versions`, and `version list` refuses it by
// name (v6) rather than letting it look like a flag this command has.
var refusedFlags = map[string]bool{"--cursor": true}

// refusedPerCommand is the same idea where the refusal is about **which command**: `skills search`
// shares one parser with `skills list` and answers these six by name — "公域检索只有相关度" — because
// the generic "不认识的参数" would leave a reader wondering whether they mistyped a flag that works
// elsewhere. They are refused, so the help must not list them, and the reason they appear in the
// source at all is that sentence.
var refusedPerCommand = map[string]map[string]string{
	"skills search": {
		"--all":       "检索只取一页",
		"--namespace": "用 --scope 选范围",
		"--source":    "检索不看库来源",
		"--sort":      "检索只有相关度",
		"--show":      "语义检索只看能看到的",
	},
	// The same one parser reads the three v19 flags, and a listing refuses each of them by name for
	// the same reason: the generic "unknown flag" would leave a reader wondering whether it works.
	"skills list": {
		"--scope":    "列表就是你的库",
		"--limit":    "列表按一页 50 条读",
		"--semantic": "列表不是检索",
		"--exclude":  "排除只用于检索",
	},
	// One parser serves the seven page-only verbs; six of them name `--bump` to refuse it, because
	// only 上线 picks a version number and a generic "不认识的参数" would not say why.
	"version rollback": {"--bump": "只有 publish 选版本号"},
	"version revoke":   {"--bump": "只有 publish 选版本号"},
	"version restore":  {"--bump": "只有 publish 选版本号"},
	"version offline":  {"--bump": "只有 publish 选版本号"},
	"version online":   {"--bump": "只有 publish 选版本号"},
	"version delete":   {"--bump": "只有 publish 选版本号"},
}

// dispatchers are the functions that turn a word into a command, with the path prefix each of them
// adds. They are the only entry points this test needs to know: every other name comes from the
// source.
var dispatchers = map[string]string{
	"run":          "",
	"skillVerb":    "skill ",
	"skillsVerb":   "skills ",
	"authCmd":      "auth ",
	"myTagsCmd":    "skills my-tags ",
	"tagVerb":      "skill tag ",
	"categoryVerb": "skill category ",
	"versionVerb":  "version ",
	"promptVerb":   "prompt ",
}

// packageGraph is what one pass over the package's non-test source yields.
type packageGraph struct {
	// calls is caller → the package functions it names in a call expression.
	calls map[string][]string
	// flags is function → the flag literals it contains, with where they are.
	flags map[string][]flagLiteral
	// entries is a full command path → the function its dispatch reaches ("" when the case answers
	// without leaving the dispatcher, as the page-only verbs do).
	entries map[string]string
	// resolved records the paths whose `case` was found, so "" can mean "found, inline" rather
	// than "missing".
	resolved map[string]bool
}

type flagLiteral struct {
	name string
	pos  token.Position
}

func readPackageGraph(t *testing.T) packageGraph {
	t.Helper()
	fset := token.NewFileSet()
	packages, err := parser.ParseDir(fset, ".", func(info os.FileInfo) bool {
		return !strings.HasSuffix(info.Name(), "_test.go")
	}, 0)
	if err != nil {
		t.Fatalf("parse the package: %v", err)
	}

	g := packageGraph{
		calls:    map[string][]string{},
		flags:    map[string][]flagLiteral{},
		entries:  map[string]string{},
		resolved: map[string]bool{},
	}
	for _, pkg := range packages {
		for _, file := range pkg.Files {
			for _, decl := range file.Decls {
				fn, ok := decl.(*ast.FuncDecl)
				if !ok || fn.Recv != nil || fn.Body == nil {
					continue
				}
				name := fn.Name.Name
				record := func(literal *ast.BasicLit) {
					if literal.Kind != token.STRING {
						return
					}
					value, err := strconv.Unquote(literal.Value)
					if err != nil || notFlags[value] || !flagLike.MatchString(value) {
						return
					}
					g.flags[name] = append(g.flags[name], flagLiteral{name: value, pos: fset.Position(literal.Pos())})
				}
				ast.Inspect(fn.Body, func(node ast.Node) bool {
					switch node := node.(type) {
					case *ast.CallExpr:
						if callee, ok := node.Fun.(*ast.Ident); ok {
							g.calls[name] = append(g.calls[name], callee.Name)
						}
					case *ast.CaseClause:
						// `case "--from", "--name":` — the switch's own arms.
						for _, expr := range node.List {
							if literal, ok := expr.(*ast.BasicLit); ok {
								record(literal)
							}
						}
					case *ast.BinaryExpr:
						// `if arg == "--offset"` / `args[i] != "--clear"`.
						if node.Op != token.EQL && node.Op != token.NEQ {
							break
						}
						if literal, ok := node.X.(*ast.BasicLit); ok {
							record(literal)
						}
						if literal, ok := node.Y.(*ast.BasicLit); ok {
							record(literal)
						}
					}
					return true
				})
			}
		}
	}

	for _, pkg := range packages {
		for _, file := range pkg.Files {
			for _, decl := range file.Decls {
				fn, ok := decl.(*ast.FuncDecl)
				if !ok || fn.Recv != nil || fn.Body == nil {
					continue
				}
				prefix, isDispatcher := dispatchers[fn.Name.Name]
				if !isDispatcher {
					continue
				}
				readDispatch(t, fn, prefix, &g)
			}
		}
	}
	return g
}

// readDispatch records, for every `case` of a `switch args[0]`, which function that case reaches.
func readDispatch(t *testing.T, fn *ast.FuncDecl, prefix string, g *packageGraph) {
	t.Helper()
	found := false
	ast.Inspect(fn.Body, func(node ast.Node) bool {
		sw, ok := node.(*ast.SwitchStmt)
		if !ok {
			return true
		}
		index, ok := sw.Tag.(*ast.IndexExpr)
		if !ok {
			return true
		}
		if base, ok := index.X.(*ast.Ident); !ok || base.Name != "args" {
			return true
		}
		found = true
		for _, stmt := range sw.Body.List {
			clause, ok := stmt.(*ast.CaseClause)
			if !ok {
				continue
			}
			var words []string
			for _, expr := range clause.List {
				literal, ok := expr.(*ast.BasicLit)
				if !ok || literal.Kind != token.STRING {
					continue
				}
				if word, err := strconv.Unquote(literal.Value); err == nil {
					words = append(words, word)
				}
			}
			if len(words) == 0 {
				continue // the default: no command path of its own
			}
			callee := returnedCallee(clause.Body)
			for _, word := range words {
				g.entries[prefix+word] = callee
				g.resolved[prefix+word] = true
			}
		}
		return false
	})
	if !found {
		t.Fatalf("%s has no `switch args[0]`: the dispatcher list and the source disagree", fn.Name.Name)
	}
}

// returnedCallee is the package function a case hands its work to. It looks at what the case
// *returns*, not at the first call it makes: several cases reject an argument first
// (`rejectJSON`), and that call is not the command.
func returnedCallee(body []ast.Stmt) string {
	for _, stmt := range body {
		ret, ok := stmt.(*ast.ReturnStmt)
		if !ok || len(ret.Results) == 0 {
			continue
		}
		call, ok := ret.Results[0].(*ast.CallExpr)
		if !ok {
			continue
		}
		if callee, ok := call.Fun.(*ast.Ident); ok {
			return callee.Name
		}
	}
	return ""
}

// reachable is the transitive closure of the call graph from one function, itself included.
func (g packageGraph) reachable(from string) map[string]bool {
	seen := map[string]bool{}
	if from == "" {
		return seen
	}
	queue := []string{from}
	for len(queue) > 0 {
		name := queue[0]
		queue = queue[1:]
		if seen[name] {
			continue
		}
		seen[name] = true
		queue = append(queue, g.calls[name]...)
	}
	return seen
}

// declaredFlags is what this command's help promises: its own flags, their aliases, and the
// universal ones.
func declaredFlags(path string) map[string]bool {
	declared := map[string]bool{}
	for _, flag := range help.Universal(commandHelp, path) {
		declared[flag.Name] = true
		for _, alias := range flag.Aliases {
			declared[alias] = true
		}
	}
	spec, _ := help.Lookup(commandHelp, path)
	for _, flag := range spec.Flags {
		declared[flag.Name] = true
		for _, alias := range flag.Aliases {
			declared[alias] = true
		}
	}
	return declared
}

func TestEveryCommandOnlyReadsTheFlagsItsHelpLists(t *testing.T) {
	graph := readPackageGraph(t)

	for _, spec := range commandHelp.Specs() {
		// Groups dispatch; their own flags are checked where the help is rendered. The root has no
		// `case` of its own.
		if spec.Path == "" || len(spec.Children) > 0 || spec.PureGroup {
			continue
		}
		if !graph.resolved[spec.Path] {
			t.Errorf("%q has no dispatch case: the help promises a command nothing reaches", spec.Path)
			continue
		}
		entry := graph.entries[spec.Path]
		declared := declaredFlags(spec.Path)
		refusedHere := refusedPerCommand[spec.Path]

		var used []flagLiteral
		for name := range graph.reachable(entry) {
			used = append(used, graph.flags[name]...)
		}
		sort.Slice(used, func(i, j int) bool { return used[i].pos.String() < used[j].pos.String() })
		seen := map[string]bool{}
		for _, literal := range used {
			if declared[literal.name] || refusedFlags[literal.name] || seen[literal.name] {
				continue
			}
			if _, refused := refusedHere[literal.name]; refused {
				continue
			}
			seen[literal.name] = true
			t.Errorf("%q reads %q (reached from %s) but its help does not list it — %s",
				spec.Path, literal.name, entry, literal.pos)
		}
	}
}

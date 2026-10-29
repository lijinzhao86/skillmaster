package help

import (
	"fmt"
)

// Lookup returns the spec for one command path.
func Lookup(cat Catalog, path string) (*Spec, bool) {
	for _, spec := range cat.Specs() {
		if spec.Path == path {
			return spec, true
		}
	}
	return nil, false
}

// universalFailures are the two failures every command that talks to the server can meet, whatever
// it is for: the credential is not usable, and the server cannot be reached. They live here rather
// than in fifty declarations because they are the same sentence everywhere, and they are shown only
// on commands whose Exits say they can return a credential failure — a command that never leaves
// this machine has neither.
var universalFailures = []Failure{
	{Code: "unauthenticated", Summary: "凭据不可用", Next: "运行 skmr login 或换一把 API key"},
	{Code: "network", Summary: "连不上服务端", Next: "检查网络与服务端地址"},
}

// UniversalFailures returns the shared failures for this command, minus any it declares itself —
// a command that says something more specific about its 401 keeps its own sentence.
func UniversalFailures(cat Catalog, path string) []Failure {
	spec, ok := Lookup(cat, path)
	if !ok || !canRefuseCredential(spec.Exits) {
		return nil
	}
	declared := map[string]bool{}
	for _, failure := range spec.Failures {
		declared[failure.Code] = true
	}
	shared := make([]Failure, 0, len(universalFailures))
	for _, failure := range universalFailures {
		if !declared[failure.Code] {
			shared = append(shared, failure)
		}
	}
	return shared
}

// canRefuseCredential reports whether the command's own exit list includes 4.
func canRefuseCredential(exits []int) bool {
	for _, code := range exits {
		if code == 4 {
			return true
		}
	}
	return false
}

// TopicOf returns a help topic by name.
func TopicOf(cat Catalog, name string) (Topic, bool) {
	return cat.Topic(name)
}

// TopicNames returns the topic names in listing order, for the one line an unknown topic gets.
func TopicNames(cat Catalog) []string {
	topics := cat.Topics()
	names := make([]string, 0, len(topics))
	for _, topic := range topics {
		names = append(names, topic.Name)
	}
	return names
}

// Universal returns the flags every command at that path accepts. --help and -h are on all of them;
// --json is on those whose JSONFields is non-empty, which is the only source of that applicability.
func Universal(cat Catalog, path string) []Flag {
	flags := []Flag{{
		Name:    "--help",
		Aliases: []string{"-h"},
		Summary: "打印这份帮助",
	}}
	spec, ok := Lookup(cat, path)
	if ok && len(spec.JSONFields) > 0 {
		flags = append(flags, Flag{Name: "--json", Summary: "以 JSON 输出（诊断走 stderr）"})
	}
	return flags
}

// tree is the command tree as the declarations describe it: every spec is a node, and its children
// are the nodes it names. Nothing else keeps a second copy of the shape.
func tree(cat Catalog) []Node {
	nodes := make([]Node, 0, len(cat.Specs()))
	for _, spec := range cat.Specs() {
		nodes = append(nodes, Node{Path: spec.Path, Children: spec.Children})
	}
	return nodes
}

// Validate checks the declaration set against a tree: every node has a spec, every spec is a node,
// and every failure's code is in the registry. Tests may pass synthetic trees to prove a new group
// cannot be added without a declaration.
func Validate(cat Catalog, trees ...[]Node) error {
	declared := make(map[string]*Spec, len(cat.Specs()))
	for _, spec := range cat.Specs() {
		if _, duplicate := declared[spec.Path]; duplicate {
			return fmt.Errorf("duplicate declaration for %q", spec.Path)
		}
		declared[spec.Path] = spec
	}

	nodes := tree(cat)
	if len(trees) > 0 {
		nodes = trees[0]
	}
	inTree := make(map[string]bool, len(nodes))
	for _, node := range nodes {
		inTree[node.Path] = true
		if _, ok := declared[node.Path]; !ok {
			return fmt.Errorf("no declaration for %q", node.Path)
		}
		for _, child := range node.Children {
			childPath := joinPath(node.Path, child)
			if _, ok := declared[childPath]; !ok {
				return fmt.Errorf("no declaration for %q (child of %q)", childPath, node.Path)
			}
		}
	}
	for _, spec := range cat.Specs() {
		if !inTree[spec.Path] {
			return fmt.Errorf("declaration %q is not in the command tree", spec.Path)
		}
		for _, failure := range spec.Failures {
			if _, ok := codeByName(failure.Code); !ok {
				return fmt.Errorf("%q names unregistered code %q", spec.Path, failure.Code)
			}
		}
	}
	return nil
}

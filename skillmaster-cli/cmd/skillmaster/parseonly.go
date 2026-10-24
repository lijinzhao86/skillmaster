package main

import (
	"fmt"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// parseOnly parses one command's arguments without running it: no request, no state change, no
// stdin. The conformance tests use it to check that every declared flag is accepted and that every
// positional binds to the key its declaration names (v16 做 #2, test-plan T03 / T22).
//
// **It reads the declaration, not the handler's code.** That is deliberate and it is not circular:
// the declaration is what a caller reads, and the separate `go/ast` scan in `tree_test.go` proves
// the handler's own flag literals are all declared. What this side proves is the other direction —
// everything declared is reachable — which a hand-written parser per command would have to repeat
// fifty-six times, in a version whose point is that help has one source.
//
// The binding rule for positionals is the declaration's own order. When more tokens arrive than the
// declaration names, the last one absorbs them: a `...` argument keeps the list, and a phrase
// argument (`skills list`'s keyword) keeps the words joined, because that is how those commands
// read them.
func parseOnly(path string, args []string) (map[string]any, error) {
	spec, ok := help.Lookup(commandHelp, path)
	if !ok {
		return nil, fmt.Errorf("no declaration for %q", path)
	}
	flags := map[string]help.Flag{}
	for _, flag := range spec.Flags {
		flags[flag.Name] = flag
		for _, alias := range flag.Aliases {
			flags[alias] = flag
		}
	}
	for _, flag := range help.Universal(commandHelp, path) {
		flags[flag.Name] = flag
	}

	bound := map[string]any{}
	var positionals []string
	for i := 0; i < len(args); i++ {
		word := args[i]
		if !strings.HasPrefix(word, "-") {
			positionals = append(positionals, word)
			continue
		}
		name, inline, hasInline := strings.Cut(word, "=")
		flag, ok := flags[name]
		if !ok {
			return nil, fmt.Errorf("不认识的参数 %s", name)
		}
		if flag.Value == "" {
			bound[strings.TrimLeft(name, "-")] = true
			continue
		}
		if hasInline {
			bound[strings.TrimLeft(name, "-")] = appendValue(bound[strings.TrimLeft(name, "-")], inline)
			continue
		}
		if i+1 >= len(args) {
			return nil, fmt.Errorf("%s 后面要跟值", name)
		}
		i++
		bound[strings.TrimLeft(name, "-")] = appendValue(bound[strings.TrimLeft(name, "-")], args[i])
	}

	tokens := positionals
	for index, arg := range spec.Args {
		last := index == len(spec.Args)-1
		if arg.Required && len(tokens) == 0 {
			return nil, fmt.Errorf("缺少 %s", arg.Name)
		}
		if len(tokens) == 0 {
			continue
		}
		if last && len(tokens) > 1 {
			if strings.HasSuffix(arg.Name, "...") {
				bound[arg.Key] = append([]string(nil), tokens...)
			} else {
				bound[arg.Key] = strings.Join(tokens, " ")
			}
			tokens = nil
			continue
		}
		bound[arg.Key] = tokens[0]
		tokens = tokens[1:]
	}
	if len(tokens) > 0 {
		return nil, fmt.Errorf("多出来的位置参数 %v", tokens)
	}
	return bound, nil
}

// appendValue keeps a repeated flag's values in the order they were given.
func appendValue(existing any, value string) any {
	if existing == nil {
		return []string{value}
	}
	return append(existing.([]string), value)
}

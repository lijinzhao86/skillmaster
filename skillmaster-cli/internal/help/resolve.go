package help

// Requested decides whether an argument list is asking for help, and for what. It is pure: no
// environment, no requests, no side effects, so the same arguments always give the same answer.
//
// The order is the contract (v16 technical-design §一次 --help 的数据流):
//
//  1. walk the declaration tree to the deepest node the leading words name;
//  2. any --help or -h after that point is a help request for that node — **values are not
//     skipped**, so --help can no longer be a flag value;
//  3. the top level's `help` is a topic page when one more word follows, the top-level help when
//     none does, and a usage error when several do. **This must precede step 4**, or `help env`
//     would be read as the top-level help;
//  4. at any node with children, a bare `help` as the next word asks for that node;
//  5. otherwise this is not a help request and the caller dispatches as usual.
func Requested(cat Catalog, args []string) Resolution {
	path, i := walk(cat, args)

	for _, arg := range args[i:] {
		if arg == "--help" || arg == "-h" {
			return Resolution{Kind: KindCommand, Path: path}
		}
	}

	if path == "" && i < len(args) && args[i] == "help" {
		switch len(args) - i {
		case 1:
			return Resolution{Kind: KindCommand, Path: ""}
		case 2:
			name := args[i+1]
			if _, ok := TopicOf(cat, name); ok {
				return Resolution{Kind: KindTopic, Topic: name}
			}
			return Resolution{Kind: KindUnknownTopic, Topic: name}
		default:
			return Resolution{Kind: KindTopicUsage}
		}
	}

	if i < len(args) && args[i] == "help" {
		if spec, ok := Lookup(cat, path); ok && len(spec.Children) > 0 {
			return Resolution{Kind: KindCommand, Path: path}
		}
	}

	return Resolution{Kind: KindNone}
}

// walk descends the declaration tree as far as the leading arguments name a child, and returns the
// path reached and the index of the first argument that is not part of it.
func walk(cat Catalog, args []string) (string, int) {
	path := ""
	i := 0
	for {
		if _, ok := Lookup(cat, path); !ok || i >= len(args) {
			return path, i
		}
		next := joinPath(path, args[i])
		if _, ok := Lookup(cat, next); !ok {
			return path, i
		}
		path = next
		i++
	}
}

// Package help is the CLI's self-description: the table of what every command does, how it is
// called, how it fails, and what it exits with.
//
// **This package holds no commands.** It knows how to render a spec, resolve which spec an argument
// list is asking about, and suggest a near miss. The declarations themselves live next to the
// handlers, in package main (v16 做 #7: the help and the command have one home).
package help

// Spec is one command's declaration. The eight items of the help contract (v16 做 #2) map onto it
// one for one: Summary, Usage, Flags (+ Args), Output (+ JSONFields), Failures, Examples, Parent,
// Exits.
type Spec struct {
	// Path is the space-separated command path, e.g. "skill invoke". The top level is "".
	Path string
	// Summary is one line saying what the command does, and at most 12 runes: it is rendered
	// verbatim as a line of its parent's "子命令：" block.
	Summary string
	// Usage is the usage line, including how each flag's value is written.
	Usage string
	// Flags lists only the flags that change behaviour. Universal flags (--help, --json) come from
	// Universal and are not repeated here.
	Flags []Flag
	// Args are the positional arguments, in order.
	Args []Arg
	// Output says what is printed on success. A command that only refuses says what the refusal is
	// and where the action lives instead.
	Output string
	// JSONFields are the success object's fields, in order. **Non-empty means this command supports
	// --json**, and that is the only source of applicability.
	JSONFields []string
	// Failures are the ways this command can fail, each with its next step.
	Failures []Failure
	// Examples are 0 to 2 paste-ready command lines.
	Examples []string
	// Parent is the enclosing command path. Empty for the three top-level commands, whose parent
	// line renders as "skillmaster help".
	Parent string
	// Next is an optional single line naming an obvious follow-up command.
	Next string
	// Exits are the process statuses this command can return (0, 1, 2, 4). The rendered exit line
	// comes from here, and every failure's code must exit within this set.
	Exits []int
	// Children are the direct subcommand names. Empty means a leaf.
	Children []string
	// PureGroup marks a group that only routes: it has children and nothing of its own to run.
	// `skills my-tags` is not one — it lists tags when given no verb — so this cannot be inferred
	// from the other fields.
	PureGroup bool
	// SharedFlags names the flags a shared parser accepts without the spec listing them, e.g. the
	// short forms that live in parseGrepArgs. They are an upper bound for the conformance test only;
	// they are never rendered.
	SharedFlags []string
}

// Flag is one behavioural flag.
type Flag struct {
	Name    string   // "--print"
	Aliases []string // short spellings of the same flag, e.g. ["-h"]
	Value   string   // "" for a boolean; "<值>" / "<a|b>" when it takes a value
	Sep     string   // "" or " " renders "--flag <值>"; "=" renders "--flag=<值>"
	Summary string   // at most 25 runes
}

// Arg is one positional argument.
type Arg struct {
	// Name is only for rendering the usage line, e.g. "<命名空间>/<名字>[@版本]".
	Name string
	// Key is the key parseOnly returns this argument under. Unique within a command.
	Key string
	// Required says whether the argument must be present.
	Required bool
}

// Failure is one way a command can fail. Its exit status lives on the Code, not here: the class of
// a failure is a property of the code, and two failures sharing a code cannot disagree about it.
type Failure struct {
	Code    string // a name from Codes()
	Summary string // at most 35 runes
	Next    string // at most 35 runes
}

// Code is one entry of the error registry.
type Code struct {
	Name string // "not_found"
	Text string // one line of meaning
	Exit int    // 1, 2 or 4 — 0 is never an error and is not in this table
}

// ExitClass is one of the four lines the top-level help and the CLI README show. It is a projection
// of Code.Exit plus the success status 0.
type ExitClass struct {
	Code int // 0 / 1 / 2 / 4
	Text string
}

// Topic is a page of help that is not a command: today only "env".
//
// **The catalog enumerates topics**, so adding one is one map entry: the top-level help lists it and
// an unknown-topic error names the real set, without either place holding a second list. Label is
// the word the top-level line uses for it ("环境变量"), because a topic can be about something else
// than environment variables.
type Topic struct {
	Name  string // "env"
	Label string // "环境变量"
	Body  string // at most 512 bytes
}

// Node is one node of the command tree. The tree is derived from Spec.Children; there is no second
// list to keep in step.
type Node struct {
	Path     string
	Children []string
}

// Kind says what an argument list turned out to be asking for.
type Kind int

const (
	// KindNone means this is not a help request; the caller dispatches as usual.
	KindNone Kind = iota
	// KindCommand means: render the spec at Path.
	KindCommand
	// KindTopic means: render the topic named Topic.
	KindTopic
	// KindUnknownTopic means: the topic does not exist; one line and exit 2.
	KindUnknownTopic
	// KindTopicUsage means: "help" was given more than one topic word; one line and exit 2.
	KindTopicUsage
)

// Resolution is what Requested decided.
type Resolution struct {
	Kind  Kind
	Path  string // KindCommand
	Topic string // KindTopic, KindUnknownTopic
}

// Catalog is everything the kernel needs from the declarations. It is implemented in package main so
// that this package never depends on it.
type Catalog interface {
	Specs() []*Spec
	Topic(name string) (Topic, bool)
	// Topics returns every topic, in the order the top-level help should list them.
	Topics() []Topic
}

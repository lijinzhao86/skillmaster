package help

import (
	"fmt"
	"strconv"
	"strings"
)

// Render returns the help text for one command path.
//
// The layout is the contract the conformance test parses (v16 technical-design §接口): a summary
// line, then labelled sections. A command with children also gets a "子命令：" block, which is
// terminated by a blank line. A pure group has only four items — description, usage, block, parent —
// because it does not run, has no output, and cannot fail on its own.
func Render(cat Catalog, path string) string {
	spec, ok := Lookup(cat, path)
	if !ok {
		return ""
	}
	var b strings.Builder

	fmt.Fprintf(&b, "%s\n\n", spec.Summary)
	fmt.Fprintf(&b, "用法：%s\n", usageLine(spec))

	if len(spec.Children) > 0 {
		b.WriteString("\n子命令：\n")
		width := 0
		for _, child := range spec.Children {
			if len(child) > width {
				width = len(child)
			}
		}
		for _, child := range spec.Children {
			childSpec, ok := Lookup(cat, joinPath(path, child))
			if !ok {
				continue
			}
			fmt.Fprintf(&b, "  %-*s  %s\n", width, child, childSpec.Summary)
		}
	}

	if flags := append(append([]Flag{}, spec.Flags...), Universal(cat, path)...); len(flags) > 0 && !isPureGroup(spec) {
		b.WriteString("\n参数：\n")
		for _, flag := range flags {
			fmt.Fprintf(&b, "  %-18s %s\n", flagSpelling(flag), flag.Summary)
		}
	}

	if spec.Output != "" {
		fmt.Fprintf(&b, "\n输出：%s\n", spec.Output)
	}
	if len(spec.JSONFields) > 0 {
		fmt.Fprintf(&b, "字段：%s\n", strings.Join(spec.JSONFields, ", "))
		// Required of every --json command by 做 #2: which stream carries what. It is the same
		// sentence everywhere, so it is written here once rather than in twelve declarations.
		b.WriteString("正文走 stdout，诊断走 stderr。\n")
	}

	failures := append(append([]Failure(nil), spec.Failures...), UniversalFailures(cat, path)...)
	if len(failures) > 0 {
		b.WriteString("\n失败：\n")
		for _, failure := range failures {
			// **The sentence, not the machine's name for it.** `content_already_exists` is what
			// `--json` reports and what the registry keys on; a reader here is asking what went
			// wrong and what to do, and twenty characters of identifier per line is what makes a
			// help too long to read (v16: necessary information, no more).
			fmt.Fprintf(&b, "  %s。下一步：%s\n", failure.Summary, failure.Next)
		}
	}

	if len(spec.Examples) > 0 {
		b.WriteString("\n例子：\n")
		for _, example := range spec.Examples {
			fmt.Fprintf(&b, "  %s\n", example)
		}
	}

	fmt.Fprintf(&b, "\n上级：%s\n", parentLine(spec))
	if spec.Next != "" {
		fmt.Fprintf(&b, "下一步：%s\n", spec.Next)
	}

	if len(spec.Exits) > 0 && !isPureGroup(spec) {
		fmt.Fprintf(&b, "\n退出码：%s\n", exitLine(spec.Exits))
	}
	return b.String()
}

// isPureGroup reads the declaration's own marker. It is not inferred from the other fields: a
// seven-item body and an empty one look alike from the outside, and `skills my-tags` is executable
// with almost no items.
func isPureGroup(spec *Spec) bool {
	return spec.PureGroup
}

func usageLine(spec *Spec) string {
	if spec.Usage != "" {
		return spec.Usage
	}
	if len(spec.Children) > 0 {
		return "skillmaster " + spec.Path + " <子命令> [参数]"
	}
	return "skillmaster " + spec.Path
}

func parentLine(spec *Spec) string {
	if spec.Parent == "" {
		return "skillmaster help"
	}
	return "skillmaster " + spec.Parent + " --help"
}

func flagSpelling(flag Flag) string {
	spelling := flag.Name
	for _, alias := range flag.Aliases {
		spelling += ", " + alias
	}
	switch {
	case flag.Value == "":
		return spelling
	case flag.Sep == "=":
		return spelling + "=" + flag.Value
	default:
		return spelling + " " + flag.Value
	}
}

// exitLine is this command's own statuses, and only the numbers: the four classes and what each one
// means are written once in the top-level help, and repeating them on all fifty-six commands is
// fifty-six copies of the same sentence.
func exitLine(exits []int) string {
	parts := make([]string, 0, len(exits))
	for _, exit := range exits {
		parts = append(parts, strconv.Itoa(exit))
	}
	return strings.Join(parts, " · ")
}

func joinPath(parent, child string) string {
	if parent == "" {
		return child
	}
	return parent + " " + child
}

// RenderTop returns the top-level help. It is deliberately not a command body: it lists every
// top-level name with its help address, points at every topic the catalog carries, shows the four
// exit classes one per line, and ends with the documentation line.
func RenderTop(cat Catalog) string {
	root, ok := Lookup(cat, "")
	if !ok {
		return ""
	}
	var b strings.Builder
	fmt.Fprintf(&b, "%s\n\n", root.Summary)
	b.WriteString("用法：skillmaster <命令> [参数]\n\n命令：\n")
	for _, child := range root.Children {
		childSpec, ok := Lookup(cat, child)
		if !ok {
			continue
		}
		fmt.Fprintf(&b, "  %-8s %s（%s --help）\n", child, childSpec.Summary, child)
	}
	for _, topic := range cat.Topics() {
		label := topic.Label
		if label == "" {
			// A topic without a label still gets a line rather than a dangling "：".
			label = topic.Name
		}
		fmt.Fprintf(&b, "\n%s：skillmaster help %s\n", label, topic.Name)
	}
	b.WriteString("\n退出码：\n")
	for _, class := range exitClasses {
		fmt.Fprintf(&b, "  %d %s\n", class.Code, class.Text)
	}
	b.WriteString("\n文档：（待定）\n")
	return b.String()
}

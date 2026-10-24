package main

import (
	"strconv"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// **T02, the direction the declaration cannot check on its own: what the renderer actually put on
// the page.** The other tests read the declaration and ask the parser whether it is true; this one
// reads the rendered text back, by its own labels, and asks whether it says what the declaration
// says. Without it a bug in `Render` — a flag dropped from the 参数 block, the field list in the
// wrong order, an exit line built from the wrong list — would be invisible to every other test,
// because they never look at the text a person sees.
//
// The parsing here is deliberately literal: the labels are the contract (technical-design §接口),
// and a test that trusted anything more would stop noticing when a label moved.

// sectionLabels are the labels `Render` writes, in the order it can write them. The parser keys on
// these and nothing else.
var sectionLabels = []string{"用法", "子命令", "参数", "输出", "字段", "失败", "例子", "上级", "下一步", "退出码"}

// helpSections splits rendered help into labelled sections. A label whose content is on the same
// line (`输出：…`) keeps that content as its first entry; the rest of a section is the indented
// lines that follow it, up to a blank line or the next label.
func helpSections(text string) map[string][]string {
	sections := map[string][]string{}
	current := ""
	for _, line := range strings.Split(text, "\n") {
		if trimmed := strings.TrimSpace(line); trimmed == "" {
			current = ""
			continue
		}
		if label, content, found := strings.Cut(line, "："); found {
			if contains(sectionLabels, label) {
				current = label
				sections[current] = nil
				if strings.TrimSpace(content) != "" {
					sections[current] = append(sections[current], strings.TrimSpace(content))
				}
				continue
			}
		}
		if current != "" && strings.HasPrefix(line, "  ") {
			sections[current] = append(sections[current], strings.TrimSpace(line))
			continue
		}
		current = ""
	}
	return sections
}

func contains(list []string, want string) bool {
	for _, item := range list {
		if item == want {
			return true
		}
	}
	return false
}

// flagSpelling is the renderer's first column for one flag, rebuilt from the declaration: the name,
// its aliases, and the value in the form the declaration chose.
func flagSpelling(flag help.Flag) string {
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

func TestRenderedHelpAgreesWithItsDeclaration(t *testing.T) {
	for _, spec := range commandHelp.Specs() {
		if spec.Path == "" {
			continue // the top level has its own shape, pinned by TestTopLevelHelpShape
		}
		t.Run(spec.Path, func(t *testing.T) {
			text := help.Render(commandHelp, spec.Path)
			if text == "" {
				t.Fatalf("%q renders nothing", spec.Path)
			}
			if first, _, _ := strings.Cut(text, "\n"); first != spec.Summary {
				t.Errorf("the first line is %q, want the summary %q", first, spec.Summary)
			}
			sections := helpSections(text)

			// 用法： either the declaration's own line, or the one the renderer derives for a group.
			wantUsage := spec.Usage
			if wantUsage == "" {
				wantUsage = "skillmaster " + spec.Path + " <子命令> [参数]"
			}
			if got := firstOr(sections["用法"]); got != wantUsage {
				t.Errorf("用法 is %q, want %q", got, wantUsage)
			}
			// The positionals in the usage line, in the declaration's order — `strings.Index` is
			// enough: first occurrence, in sequence.
			at := 0
			for _, arg := range spec.Args {
				found := strings.Index(wantUsage[at:], arg.Name)
				if found < 0 {
					t.Errorf("the usage line does not name %q where the declaration puts it: %q", arg.Name, wantUsage)
					break
				}
				at += found + len(arg.Name)
			}

			// 参数： exactly the declared flags plus the universal ones, each once.
			if spec.PureGroup {
				for _, unwanted := range []string{"参数", "输出", "失败", "例子", "退出码"} {
					if _, ok := sections[unwanted]; ok {
						t.Errorf("a pure group renders a %s section", unwanted)
					}
				}
			} else {
				declared := append(append([]help.Flag{}, spec.Flags...), help.Universal(commandHelp, spec.Path)...)
				want := map[string]bool{}
				for _, flag := range declared {
					want[flagSpelling(flag)] = false
				}
				if len(sections["参数"]) != len(declared) {
					t.Errorf("参数 has %d lines, want %d: %v", len(sections["参数"]), len(declared), sections["参数"])
				}
				for _, line := range sections["参数"] {
					matched := ""
					for spelling := range want {
						if strings.HasPrefix(line, spelling+" ") || line == spelling {
							matched = spelling
						}
					}
					if matched == "" {
						t.Errorf("参数 has a line for no declared flag: %q", line)
						continue
					}
					if want[matched] {
						t.Errorf("参数 lists %q twice", matched)
					}
					want[matched] = true
				}
				for spelling, seen := range want {
					if !seen {
						t.Errorf("the flag %q is declared but not rendered", spelling)
					}
				}
			}

			// 字段： the JSON fields, in declaration order, and only where the declaration has them.
			fields, hasFields := sections["字段"]
			switch {
			case len(spec.JSONFields) == 0 && hasFields:
				t.Errorf("a command without JSONFields renders 字段: %v", fields)
			case len(spec.JSONFields) > 0:
				if got := firstOr(fields); got != strings.Join(spec.JSONFields, ", ") {
					t.Errorf("字段 is %q, want %q", got, strings.Join(spec.JSONFields, ", "))
				}
				if !strings.Contains(text, "正文走 stdout，诊断走 stderr。") {
					t.Error("a --json command does not say which stream carries what")
				}
			}

			// 失败： the declared ones, then the shared ones, each as `Summary。下一步：Next`.
			wantFailures := append(append([]help.Failure{}, spec.Failures...), help.UniversalFailures(commandHelp, spec.Path)...)
			gotFailures := sections["失败"]
			if len(gotFailures) != len(wantFailures) {
				t.Errorf("失败 has %d lines, want %d: %v", len(gotFailures), len(wantFailures), gotFailures)
			}
			for i, failure := range wantFailures {
				if i >= len(gotFailures) {
					break
				}
				want := failure.Summary + "。下一步：" + failure.Next
				if gotFailures[i] != want {
					t.Errorf("失败 line %d is %q, want %q", i, gotFailures[i], want)
				}
			}

			// 例子： exactly the declared examples, in order.
			if got, want := sections["例子"], spec.Examples; !equalStrings(got, want) {
				t.Errorf("例子 is %v, want %v", got, want)
			}

			// 上级： the parent's help address.
			wantParent := "skillmaster help"
			if spec.Parent != "" {
				wantParent = "skillmaster " + spec.Parent + " --help"
			}
			if got := firstOr(sections["上级"]); got != wantParent {
				t.Errorf("上级 is %q, want %q", got, wantParent)
			}

			// 退出码： this command's own numbers, in the declaration's order.
			if spec.PureGroup {
				if _, ok := sections["退出码"]; ok {
					t.Error("a pure group renders an exit line")
				}
			} else {
				parts := make([]string, 0, len(spec.Exits))
				for _, exit := range spec.Exits {
					parts = append(parts, strconv.Itoa(exit))
				}
				if got := firstOr(sections["退出码"]); got != strings.Join(parts, " · ") {
					t.Errorf("退出码 is %q, want %q", got, strings.Join(parts, " · "))
				}
				for _, failure := range spec.Failures {
					if !containsInt(spec.Exits, exitOf(t, failure.Code)) {
						t.Errorf("the failure %q can exit %d, which the exit line does not list",
							failure.Code, exitOf(t, failure.Code))
					}
				}
			}
		})
	}
}

func firstOr(lines []string) string {
	if len(lines) == 0 {
		return ""
	}
	return lines[0]
}

func equalStrings(got, want []string) bool {
	if len(got) != len(want) {
		return false
	}
	for i := range got {
		if got[i] != want[i] {
			return false
		}
	}
	return true
}

func containsInt(list []int, want int) bool {
	for _, item := range list {
		if item == want {
			return true
		}
	}
	return false
}

// exitOf is the registry's class for one code — the single place that decides it, read here rather
// than restated.
func exitOf(t *testing.T, code string) int {
	t.Helper()
	for _, entry := range help.Codes() {
		if entry.Name == code {
			return entry.Exit
		}
	}
	t.Fatalf("%q is not a registry code", code)
	return 0
}

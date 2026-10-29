package main

import (
	"errors"
	"fmt"
	"os"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// helpRequest answers a help request before anything else happens: before authentication, before
// argument validation, before any side effect. `logout --help` no longer clears the credential.
//
// It returns handled=false when the arguments are not asking for help, and the caller dispatches as
// usual. That is the only path that reaches a handler.
func helpRequest(args []string) (bool, error) {
	resolution := help.Requested(commandHelp, args)
	switch resolution.Kind {
	case help.KindNone:
		// A bare pure group names no verb, so nothing was done: print its table to stderr and fail
		// as a usage error (v16 做 #3). `skills my-tags` is not one — it lists tags when bare.
		if path, ok := bareGroup(args); ok {
			fmt.Fprint(os.Stderr, help.Render(commandHelp, path))
			return true, usagef("没有给出子命令。用法见：%s", helpAddress(path))
		}
		return false, nil
	case help.KindCommand:
		if resolution.Path == "" {
			fmt.Print(help.RenderTop(commandHelp))
			return true, nil
		}
		text := help.Render(commandHelp, resolution.Path)
		if text == "" {
			return false, nil
		}
		fmt.Print(text)
		return true, nil
	case help.KindTopic:
		topic, ok := help.TopicOf(commandHelp, resolution.Topic)
		if !ok {
			return true, usagef("没有这个帮助主题 %s。%s", namedArgument(resolution.Topic), topicList())
		}
		fmt.Print(topic.Body)
		return true, nil
	case help.KindUnknownTopic:
		return true, usagef("没有这个帮助主题 %s。%s上级：skmr help",
			namedArgument(resolution.Topic), topicList())
	case help.KindTopicUsage:
		return true, usagef("help 只接一个主题。%s", usageAt(""))
	}
	return false, nil
}

// unknownName is the one line an unknown command or verb gets: what was not understood, the closest
// same-level name when exactly one is close, and where to read the list. It never dumps a help text.
func unknownName(parent, word string, candidates []string) error {
	// A dash-word at a group level is a flag somebody expected the group to take, not a verb: it
	// gets the flag sentence (PRD 做 #4's two prefixes) and a flag suggestion.
	if strings.HasPrefix(word, "-") {
		return unknownFlag(parent, word)
	}
	line := fmt.Sprintf("不认识的子命令 %s。", namedArgument(word))
	if suggestion, ok := help.Suggest(word, candidates); ok {
		line += fmt.Sprintf("你是不是想用 %s？", suggestion)
	}
	line += "用法见：" + helpAddress(parent)
	return usagef("%s", line)
}

// helpAddress is where the list for a level lives. The top level is `skmr help`; anything
// else is that node's own --help.
func helpAddress(path string) string {
	if path == "" {
		return "skmr help"
	}
	return "skmr " + path + " --help"
}

// topicList names the topics that do exist. It is read from the catalog rather than written here,
// so a second topic cannot leave an error message claiming there is only one.
func topicList() string {
	names := help.TopicNames(commandHelp)
	if len(names) == 0 {
		return "还没有帮助主题。"
	}
	return "可用的主题：" + strings.Join(names, "、") + "。"
}

// retargetUsage points a usage error at the command that was actually typed. Every dispatcher calls
// it on the way out, because the parser that produced the sentence cannot know who called it: the
// version group's parsers are shared across fourteen verbs, and `--offset` serves both `read` and
// `invoke`. Without it a typo in `version note` sends the reader to a page that lists fourteen verbs.
func retargetUsage(err error, path string) error {
	if err == nil {
		return nil
	}
	if _, ok := help.Lookup(commandHelp, path); !ok {
		return err
	}
	var usage *usageError
	if errors.As(err, &usage) {
		return usagef("%s", pointAt(usage.message, path))
	}
	var failure *cliFailure
	if errors.As(err, &failure) && failure.Code == "usage" {
		failure.Message = pointAt(failure.Message, path)
	}
	return failureOr(err, failure)
}

// pointAt makes a usage sentence end at this command's help: it appends the address when the
// sentence carries none, and moves it deeper when it names an ancestor. **Every** usage error has to
// end there (PRD 做 #4), and a sentence written deep inside a parser has no way to know which verb
// reached it — so the tail is settled here, once per dispatcher.
//
// **A tail that already names something deeper is left alone.** `skill` → `skill tag` → `skill tag
// add` each settle on the way out; without this rule the outermost one would pull every address back
// up to itself, and `skill tag add` would answer with `skill --help`.
func pointAt(message, path string) string {
	trimmed := strings.TrimSpace(message)
	at := strings.LastIndex(trimmed, "用法见：")
	if at >= 0 {
		// **A tail with nothing in front of it says nothing.** "错误：用法见：…" tells the reader
		// where to look but not what was wrong, so the command's own usage line fills that half —
		// the shape they were trying to type, without a second page.
		if strings.TrimSpace(trimmed[:at]) == "" {
			if spec, ok := help.Lookup(commandHelp, path); ok && spec.Usage != "" && !spec.PureGroup {
				return "参数不对。用法：" + spec.Usage + "。" + usageAt(path)
			}
		}
		named, ok := tailPath(trimmed[at:])
		if !ok || (named != path && !strings.HasPrefix(path, named+" ")) {
			return message
		}
		return trimmed[:at] + usageAt(path)
	}
	if strings.HasSuffix(trimmed, "。") {
		return trimmed + usageAt(path)
	}
	return trimmed + "。" + usageAt(path)
}

// tailPath reads the command out of a rendered tail (`用法见：skmr skill tag --help`). The
// root's tail (`用法见：skmr help`) is not a command address, so it reports ok=false.
func tailPath(tail string) (string, bool) {
	const head, foot = "用法见：skmr ", " --help"
	if !strings.HasPrefix(tail, head) || !strings.HasSuffix(tail, foot) {
		return "", false
	}
	return strings.TrimSuffix(strings.TrimPrefix(tail, head), foot), true
}

// failureOr returns the flattened failure when there is one, and the original error otherwise.
func failureOr(err error, failure *cliFailure) error {
	if failure != nil {
		return failure
	}
	return err
}

// usageAt is the tail a usage error ends with: where this command's help lives. It is the same
// address `unknownName` and `unknownFlag` give, so a caller who mistyped anything ends up in one
// place (v16 验收 #17 T10).
func usageAt(path string) string { return "用法见：" + helpAddress(path) }

// childNames lists a spec's direct children, for suggestions.
func childNames(path string) []string {
	spec, ok := help.Lookup(commandHelp, path)
	if !ok {
		return nil
	}
	return spec.Children
}

// writeCommandTable prints the top-level help to stderr, for a bare invocation that named nothing.
func writeCommandTable() {
	fmt.Fprint(os.Stderr, help.RenderTop(commandHelp))
}

// bareGroup reports whether the arguments name a pure group and nothing else.
func bareGroup(args []string) (string, bool) {
	path := ""
	for _, arg := range args {
		next := arg
		if path != "" {
			next = path + " " + arg
		}
		if _, ok := help.Lookup(commandHelp, next); !ok {
			return "", false
		}
		path = next
	}
	if path == "" {
		return "", false
	}
	spec, _ := help.Lookup(commandHelp, path)
	return path, spec.PureGroup
}

// unknownFlag is the one line an unrecognised flag gets: the flag that was not understood, the
// closest flag this command accepts when exactly one is close, and where the list is.
func unknownFlag(path, word string) error {
	line := fmt.Sprintf("不认识的参数 %s。", namedArgument(word))
	spec, ok := help.Lookup(commandHelp, path)
	if ok {
		candidates := make([]string, 0, len(spec.Flags)+2)
		for _, flag := range spec.Flags {
			candidates = append(candidates, flag.Name)
			candidates = append(candidates, flag.Aliases...)
		}
		for _, flag := range help.Universal(commandHelp, path) {
			candidates = append(candidates, flag.Name)
			candidates = append(candidates, flag.Aliases...)
		}
		if suggestion, ok := help.Suggest(word, candidates); ok {
			line += fmt.Sprintf("你是不是想用 %s？", suggestion)
		}
	}
	line += "用法见：" + helpAddress(path)
	return usagef("%s", line)
}

// Three parsers serve two commands each, so the refusal has to name the one that was called: an
// unknown flag answered with the other command's address would send the reader to the wrong list.

func joinLeavePath(join bool) string {
	if join {
		return "skill join"
	}
	return "skill leave"
}

func tagChangePath(attach bool) string {
	if attach {
		return "skill tag add"
	}
	return "skill tag remove"
}

func queryPath(search bool) string {
	if search {
		return "skills search"
	}
	return "skills list"
}

// checkAddressSyntax refuses an address that is not `<namespace>/<name>[@version]` before any
// credential is involved: a typo in the address is a usage error (exit 2), not a login prompt. It is
// the syntax half of `resolve`, callable on its own — `resolve` stays where the request is.
//
// A bare `@` is refused here too, and for the same reason `versionInAddress` refuses it: the empty
// version would otherwise be read as "nothing named", which is not what the caller wrote.
func checkAddressSyntax(path, address string) error {
	skill, version, at := strings.Cut(address, "@")
	if at && version == "" {
		return usagef("地址里的 @ 后面要跟一个版本名或 sha256:… 摘要。%s", usageAt(path))
	}
	if _, err := api.SkillPath(skill); err != nil {
		return usagef("%s。%s", err, usageAt(path))
	}
	return nil
}

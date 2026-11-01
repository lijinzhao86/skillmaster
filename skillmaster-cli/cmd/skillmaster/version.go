// The `version` group: the author plane an agent may read and manage without a browser.
//
// **What is here is what does not change what a caller can read.** Listing, reading, comparing,
// renaming, annotating and discarding a draft all leave the consumer plane alone; publishing,
// rolling back, revoking, restoring and taking a whole skill offline are deliberately absent from
// this group's help and refused by name (ADR 0031, 0046, 0061). The refusal always carries the exact
// `version open` command, because that is the handover: the person confirms on the page.
package main

import (
	"context"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/apikey"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/auth"
	"strings"
)

// openBrowser is a seam, for the same reason `startLogin` is one: a test must not open a window on
// whoever's screen is in front of the machine.
var openBrowser = auth.OpenBrowser

func versionVerb(ctx context.Context, args []string) (err error) {
	if len(args) == 0 {
		return usagef("%s", usageAt("version"))
	}
	// The parsers under this group are shared by several verbs, so they can name only the group.
	// A usage error is retargeted to the verb that was actually typed, once, on the way out.
	defer func() {
		if err != nil {
			err = retargetUsage(err, "version "+args[0])
		}
	}()
	switch args[0] {
	case "list":
		return versionList(ctx, args[1:])
	case "show":
		return versionShow(ctx, args[1:])
	case "diff":
		return versionDiff(ctx, args[1:])
	case "rename":
		return versionRename(ctx, args[1:])
	case "note":
		return versionNote(ctx, args[1:])
	case "release-type":
		return versionReleaseType(ctx, args[1:])
	case "discard":
		return versionDiscard(ctx, args[1:])
	case "open":
		return versionOpen(ctx, args[1:])
	case "publish", "rollback", "revoke", "restore", "offline", "online", "delete":
		return pageOnlyVerb(args[0], args[1:])
	default:
		// A machine contract covers a mistyped verb too: `--json` gets one object on stderr, not a
		// usage line a script cannot parse.
		return versionErr(hasJSONFlag(args), unknownName("version", args[0], childNames("version")), "")
	}
}

// pageOnlyVerb refuses one of the actions that changes what a caller reads.
//
// **The refusal is the whole point, and it names the way forward.** Hiding the verb from help is not
// enough: an agent that has seen the word in the web UI will try it, and the useful answer is "a
// person confirms this, here is the page". The full `version open` command is printed so it can be
// copied into a message to that person.
func pageOnlyVerb(verb string, args []string) error {
	address := ""
	bump := ""
	seenBump := false
	badArgument := ""
	extraAddress := ""
	jsonMode := jsonModeIn(args, "--bump")
	for i := 0; i < len(args); i++ {
		switch arg := args[i]; {
		case arg == "--json":
			jsonMode = true
		case arg == "--bump":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("%s", "--bump 后面要跟 patch、minor 或 major。"), "")
			}
			value := args[i+1]
			i++
			// Only 上线 picks a number; the other six verbs take none at all.
			if verb != "publish" {
				return versionErr(jsonMode, usagef("只有 publish 选版本号，%s 不接受 --bump。", verb), "")
			}
			if !isBumpKind(value) {
				return versionErr(jsonMode, usagef("--bump 只接受 patch、minor 或 major（%s）。", redactOr(value)), "")
			}
			if seenBump {
				return versionErr(jsonMode, usagef("%s", "--bump 只能给一次。"), "")
			}
			seenBump = true
			bump = value
		case strings.HasPrefix(arg, "-"):
			// Keep parsing: the refusal below needs the address to build the handoff, and a second
			// bad token should not hide the first.
			if badArgument == "" {
				badArgument = namedArgument(arg)
			}
		case address != "":
			if extraAddress == "" {
				extraAddress = arg
			}
		default:
			address = arg
		}
	}
	if address == "" {
		address = "<命名空间>/<名字>@<版本>"
	}
	// The ADR sentence is part of the message for the same reason as `discard`'s consequence: this
	// refusal is not a permission failure, so a hint would be dropped in `--json` mode.
	//
	// The last line points at the page that owns the action (ADR 0072): the version's own page for
	// publish / rollback / revoke / restore, and the skill's settings page for offline / online /
	// delete, which are skill-level and live in its danger zone. The version-level handoff also names
	// the pane (ADR 0074): all four actions live in 操作, not in the default 概览.
	if badArgument != "" {
		return versionErr(jsonMode, &refusalError{message: fmt.Sprintf("不认识的参数 %s。%s 只能在网页上由人确认；交接用 %s。",
			badArgument, verb, handoffCommand(verb, address, bump))}, "")
	}
	if extraAddress != "" {
		// `namedArgument` redacts a credential pasted one token too far: the message is written to
		// stderr, and a key in a CI log is the same leak as a key in a chat message.
		return versionErr(jsonMode, &refusalError{message: fmt.Sprintf("%s 只接一个地址（收到 %s）。交接用 %s。",
			verb, namedArgument(extraAddress), handoffCommand(verb, address, bump))}, "")
	}
	return versionErr(jsonMode, &refusalError{message: fmt.Sprintf("%s 会改变调用者能读到什么，只能在网页上由人确认，CLI 不执行。"+
		"这不是失败。\n%s", verb, handoffLine(verb, address, bump))}, "")
}

// handoffCommand is the copyable command for the page that owns this verb. A credential typed where
// an address belongs is not echoed back: the handoff is meant to be pasted and run, and a key in it
// would be both useless and a leak.
func handoffCommand(verb, address, bump string) string {
	// A prefix match is not enough: a namespace may legitimately start with `skm_`. Only a token
	// that cannot be parsed as one of our addresses is treated as a credential pasted by mistake.
	if apikey.IsKey(address) {
		skill, _, _ := strings.Cut(address, "@")
		if _, err := api.SkillPath(skill); err != nil {
			address = apikey.InAnArgument
		}
	}
	if verb == "offline" || verb == "online" || verb == "delete" {
		skill := address
		if at := strings.Index(skill, "@"); at >= 0 {
			skill = skill[:at]
		}
		return fmt.Sprintf("skmr skill open %s --tab settings", skill)
	}
	command := fmt.Sprintf("skmr version open %s --sub actions", address)
	if bump != "" {
		command += " --bump " + bump
	}
	return command
}

// handoffLine is the sentence the refusal ends with: the command plus where it lands.
func handoffLine(verb, address, bump string) string {
	switch verb {
	case "offline", "online", "delete":
		return "到这个 skill 的设置页去做：" + handoffCommand(verb, address, bump)
	default:
		return "到那一版自己的页面上去做：" + handoffCommand(verb, address, bump)
	}
}

// isBumpKind guards the three choices the publish form draws. The value is carried into the address,
// so anything else is refused here rather than silently producing a page with no preselection.
func isBumpKind(value string) bool {
	return value == "patch" || value == "minor" || value == "major"
}

// redactOr is `namedArgument` without the quoting: a value inside a sentence, not a token at the
// command line. A credential pasted as a flag value is replaced; anything else is shown as written.
func redactOr(value string) string {
	if apikey.IsKey(value) {
		return apikey.InAnArgument
	}
	return value
}

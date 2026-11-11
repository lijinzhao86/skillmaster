// `skill delete`: the one page action that also executes, and its refusal shape.

package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// skillDelete is the one page action that also executes: a skill that never had a version go live
// may be deleted by its owner with --yes (ADR 0127). Without the flag it only says what would
// happen — the consequence first, then the command, the same shape as `version discard`.
func skillDelete(ctx context.Context, args []string) error {
	spec := pageSpecs["skill delete"]
	parsed := pageArgs{jsonMode: jsonModeIn(args)}
	yes := false
	for _, arg := range args {
		switch {
		case arg == "--json":
			parsed.jsonMode = true
		case arg == "--yes":
			yes = true
		case strings.HasPrefix(arg, "-"):
			return versionErr(parsed.jsonMode, unknownFlag(spec.path, arg), "")
		default:
			if parsed.address != "" {
				return versionErr(parsed.jsonMode,
					usagef("%s 只接一个地址（收到 %s）。%s", spec.path, namedArgument(arg), usageAt(spec.path)), "")
			}
			parsed.address = arg
		}
	}
	if parsed.address == "" {
		return versionErr(parsed.jsonMode, usagef("%s", usageAt(spec.path)), "")
	}
	if strings.Contains(parsed.address, "@") {
		return versionErr(parsed.jsonMode,
			usagef("删除作用于整个 skill；地址不要带 @版本。%s", usageAt(spec.path)), "")
	}
	if _, err := api.SkillPath(parsed.address); err != nil {
		return versionErr(parsed.jsonMode, &usageError{message: err.Error()}, "")
	}

	settings := skillWebURL(parsed.address, "settings", "")
	if !yes {
		facts := readPageFacts(ctx, spec, parsed)
		command := "skmr skill delete " + parsed.address + " --yes"
		// Ownership comes from the author plane. Without it, a rung below `admin` still rules the
		// caller out; `admin` alone cannot tell an owner from an admin grant.
		if facts.known && !facts.fromAuthor && rungRanks[facts.rung] < rungRanks["admin"] {
			return deleteRefusal(parsed.jsonMode, "insufficient_permission",
				fmt.Sprintf("删除要%s才能做，你在这个 skill 上是 %s。", rungNames["owner"], rungNames[facts.rung]))
		}
		if facts.fromAuthor && !facts.owned {
			return deleteRefusal(parsed.jsonMode, "insufficient_permission",
				"删除要所有者才能做；你不是这个 skill 的所有者。")
		}
		if facts.fromAuthor && facts.everPublished {
			sentence := "这个 skill 上线过，不能由凭据删除；到网页上由人确认：" + settings +
				"。如果它已公开且有入库者，先走完 30 天下线中，再删除。"
			if parsed.jsonMode {
				return &cliFailure{Code: "skill_published", Message: sentence, JSON: true}
			}
			return &refusalError{message: sentence}
		}
		if !facts.fromAuthor {
			// The facts are unknown, so they are said to be unknown. **Never "it never went live"**,
			// and **no confirmation command either**: the switch appears only once the precheck has
			// said the delete is allowed. Somebody who cannot read the author page is pointed at
			// the web console, where a person confirms.
			message := "没能确认这个 skill 是否上线过：本机读不到它的作者页。先在能读到作者页的环境里重跑，" +
				"或到网页上由人确认删除：" + settings
			// **Not `confirmation_required`**: that code means "add the switch", and this branch
			// deliberately hands out no command (ADR 0128).
			return deleteRefusal(parsed.jsonMode, "precheck_unavailable", message)
		}
		if parsed.jsonMode {
			return &cliFailure{Code: "confirmation_required",
				Message: "删除要确认。确认后执行：" + command, JSON: true}
		}
		fmt.Println("它从未上线，可以直接删。删除后名字立即释放，可以用同一个名字新建；" +
			"要找回这一条用 skmr skill restore " + parsed.address + "。")
		fmt.Printf("确认后执行：\n%s\n", command)
		return &refusalError{message: "没有执行：删除要加 --yes。"}
	}

	_, err := authenticated(ctx, func(client api.Client) (struct{}, error) {
		return struct{}{}, client.DeleteNeverPublishedSkill(ctx, parsed.address)
	})
	if err != nil {
		var server *api.ServerError
		if errors.As(err, &server) && server.Status == http.StatusConflict {
			message := fmt.Sprintf("%s\n%s", versionMessages["skill_published"], settings)
			if parsed.jsonMode {
				return &cliFailure{Code: "skill_published", Message: message, JSON: true,
					ServerCode: server.Code}
			}
			return &refusalError{message: message}
		}
		return versionErr(parsed.jsonMode, err, "")
	}
	if parsed.jsonMode {
		return writeJSON(struct {
			Action    string `json:"action"`
			Performed bool   `json:"performed"`
			Address   string `json:"address"`
			Deleted   bool   `json:"deleted"`
		}{"delete", true, parsed.address, true})
	}
	// v61 (ADR 0183): the name is free the moment this returns; restoring is still possible, and
	// if somebody has taken the name by then the restore answers under a suffixed address.
	fmt.Printf("已删除 %s。名字已释放，可以用同一个名字新建；要找回这一条用 skmr skill restore %s。\n",
		parsed.address, parsed.address)
	return nil
}

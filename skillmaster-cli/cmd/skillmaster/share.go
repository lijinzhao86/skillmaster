// Sharing one skill: grant, revoke, and list.

package main

import (
	"context"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"slices"
	"strings"
)

// shareSkill hands one skill to one account, which is the first thing this CLI can do that changes
// what somebody else reads (ADR 0034).
//
// The boundary is worth stating where the person can see it, because the five rungs are not
// variations of one another: `write` adds versions and still cannot publish them, `maintain` can, and
// neither can change visibility or the collaborator list — that is `admin`. Deletion is the owner's
// alone and is not on the ladder at all (ADR 0047).
func shareSkill(ctx context.Context, args []string) error {
	address, handle, role, err := parseShare(args)
	if err != nil {
		return err
	}

	result, err := authenticated(ctx, func(client api.Client) (api.ShareResult, error) {
		return client.Share(ctx, address, handle, role)
	})
	if err != nil {
		return err
	}
	if result.AlreadyGranted {
		fmt.Printf("%s 已经有 %s（%s），没有发邀请。\n", result.Handle,
			rungLabel(result.Role), roleLine(result.Role))
		return nil
	}
	fmt.Printf("已邀请 %s 接受 %s：%s，等待对方接受。\n", result.Handle, address,
		roleLine(result.Role))
	if result.ExpiresAt != "" {
		fmt.Printf("邀请 30 天内有效（至 %s）；对方在 skmr notifications 里接受或拒绝。\n",
			result.ExpiresAt)
	} else {
		fmt.Println("对方在 skmr notifications 里接受或拒绝。")
	}
	fmt.Printf("看谁还没接受：skmr skill shares %s\n", address)
	return nil
}

// parseShare reads the arguments, and every rejection here is one the server would also make — this
// only makes it before a round trip and in a language the caller is reading. ADR 0011's line holds:
// the server is the authority, and these are courtesies.
func parseShare(args []string) (address, handle, role string, err error) {
	if len(args) == 0 {
		return "", "", "", usagef("要接地址和 --to。%s", usageAt("skill share"))
	}
	address = args[0]
	// The lowest rung: a share that grants nothing is not a share, and the default has to be one of
	// the five. It used to be `viewer`, whose meaning is `browse` now — see the refusal below.
	role = api.RoleInvoke

	rest := args[1:]
	for len(rest) > 0 {
		switch rest[0] {
		case "--to":
			if len(rest) < 2 {
				return "", "", "", usagef("--to 后面要跟一个用户名。%s", usageAt("skill share"))
			}
			handle, rest = rest[1], rest[2:]
		case "--role":
			if len(rest) < 2 {
				return "", "", "", usagef("--role 后面要跟 invoke、browse、write、maintain 或 admin。%s", usageAt("skill share"))
			}
			role, rest = rest[1], rest[2:]
		default:
			return "", "", "", unknownFlag("skill share", rest[0])
		}
	}

	// `--to` has no default value, and that is the point: there is no such thing as sharing with
	// somebody in general, and a default here would mean the command could share with a person nobody
	// named.
	if handle == "" {
		return "", "", "", usagef("--to 是必填的。%s", usageAt("skill share"))
	}
	// **The retired words are refused by name, not mapped.** `viewer` once meant reading and `editor`
	// once meant drafting, and a silent translation would answer "已授权" while granting something
	// other than what was asked (technical-design §一处要连带改的契约).
	switch role {
	case "viewer":
		return "", "", "", usagef("`viewer` 这个名字已经不再接受；只看文件与已上线的版本用 browse。%s", usageAt("skill share"))
	case "editor":
		return "", "", "", usagef("`editor` 这个名字已经不再接受；提交版本用 write。%s", usageAt("skill share"))
	}
	if !slices.Contains(api.Roles, role) {
		return "", "", "", usagef("角色只能是 %s，收到 %q。", strings.Join(api.Roles, "、"), role)
	}
	// The address's grammar first, so a malformed one is refused here rather than after a round trip.
	if _, err := api.SkillPath(address); err != nil {
		return "", "", "", usagef("%s", err)
	}
	// Then the pin, which parses and would be dropped on the floor: a share is a property of the
	// skill, not of a version of it. `@` cannot appear anywhere else in an address — it is the pin
	// separator, which is why a name containing one is not addressable at all — so this cannot
	// refuse a legitimate address.
	if strings.Contains(address, "@") {
		return "", "", "", usagef("共享是针对整个 skill 的，不是某一版——地址后面不要带 @版本。%s", usageAt("skill share"))
	}
	return address, handle, role, nil
}

// roleLine says what the role a skill was just shared with actually lets that person do.
//
// Not the constant's name repeated: `viewer` and `editor` are the wire's words, and someone who has
// just typed one of them is owed the answer to "so what can they do".
func roleLine(role string) string {
	switch role {
	case api.RoleInvoke:
		return "可以调用，但看不到文件清单"
	case api.RoleBrowse:
		return "可以调用并查看文件，但不能提交版本"
	case api.RoleWrite:
		return "可以提交新版本，但不能上线"
	case api.RoleMaintain:
		return "可以提交并上线版本，但不能修改可见性与协作者"
	case api.RoleAdmin:
		return "可以提交、上线，并修改可见性与协作者"
	default:
		// A role from a newer server than this build. Saying nothing about what it grants is the
		// honest answer; the name is above, and the server is the authority on what it means.
		return role
	}
}

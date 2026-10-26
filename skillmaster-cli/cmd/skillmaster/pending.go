package main

import (
	"context"
	"fmt"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// `skills pending` — every draft waiting on a skill this account may publish from (v18).
//
// **The console's 待上线 page, in the terminal.** ADR 0081 asks every console operation for a CLI
// counterpart, and this is the read that page is built on: what is waiting, what it would become,
// and which draft to publish. The command does not publish — that stays on the browser plane
// (ADR 0031) — it lists, and hands over an address the person can open.
//
// **A row is a draft, not a skill**, exactly as on the page: a skill with three waiting drafts is
// three rows here. The skill's own line carries what is live, because "what is it becoming" is not
// answerable without it.
func listPending(ctx context.Context, args []string) error {
	jsonMode := hasJSONFlag(args)
	for _, arg := range args {
		if arg == "--json" {
			continue
		}
		return versionErr(jsonMode, usagef("skills pending 不接参数。%s", usageAt("skills pending")), "")
	}

	queue, err := authenticated(ctx, func(client api.Client) (api.PublishQueue, error) {
		return client.PublishQueue(ctx)
	})
	if err != nil {
		return versionErr(jsonMode, err, "")
	}
	if jsonMode {
		return writeJSON(queue)
	}

	if len(queue.Skills) == 0 {
		fmt.Println("没有待上线的草稿。提交新版本后，尚未上线的草稿会出现在这里。")
		return nil
	}

	drafts := 0
	for _, skill := range queue.Skills {
		drafts += len(skill.Drafts)
		fmt.Printf("%s/%s\n", skill.Namespace, skill.Name)
		fmt.Printf("    %s · %d 份草稿\n", liveLabel(skill.Current), len(skill.Drafts))
		for _, draft := range skill.Drafts {
			fmt.Printf("    %s  %s · %s · %s\n", draft.Code, draftNameLabel(draft.Name),
				draftTypeLabel(draft.ReleaseType), firstTen(&draft.SubmittedAt))
		}
	}
	fmt.Printf("（共 %d 个 skill、%d 份草稿。上线在网页上由人点：skillmaster skill open <上面某一行的地址> --tab versions）\n",
		len(queue.Skills), drafts)
	if queue.Truncated {
		// **Said out loud.** A listing that stops without saying so reads as "this is everything",
		// and somebody acting on that would conclude the rest has nothing waiting (PRD 验收 19).
		fmt.Printf("（只列出了最近 %d 个 skill；剩下的没有列进来。）\n", api.ListPageSize)
	}
	return nil
}

// liveLabel is what the skill's status line says: the three states the page also distinguishes.
func liveLabel(current *api.QueueNow) string {
	if current == nil {
		return "从未上线"
	}
	if current.Version == nil || *current.Version == "" {
		// Published, but that version carries no number — not the same fact as "never published".
		return "已上线 @—"
	}
	return "已上线 @" + *current.Version
}

// draftNameLabel writes a draft's name, including the rows from before drafts had one.
func draftNameLabel(name *string) string {
	if name == nil || *name == "" {
		return "「未命名」"
	}
	return "「" + *name + "」"
}

// draftTypeLabel is the kind a draft declares, or the fact that it has none.
//
// **The type is not a version number** (ADR 0091): a draft that has one still has no number, and the
// number is computed when somebody publishes. The wording keeps that apart, because an agent that
// read 「次版本」 as 1.3.0 would plan around a number nobody assigned.
func draftTypeLabel(releaseType *string) string {
	if releaseType == nil || *releaseType == "" {
		return "未指定版本类型"
	}
	switch strings.ToLower(*releaseType) {
	case "major":
		return "主版本"
	case "minor":
		return "次版本"
	case "patch":
		return "修订"
	default:
		return *releaseType
	}
}

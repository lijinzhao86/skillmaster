package main

import (
	"context"
	"fmt"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// publishQueueLimit is how many skills the publish queue returns at most.
//
// **Its own number, and not the listing's.** `ReadPublishQueueUseCase.LIMIT` is 500; the 50 that
// `api.ListPageSize` mirrors is `LibraryListUseCase.API_DEFAULT_LIMIT`, a different endpoint with a
// different question. Using the listing's constant here made this line claim 50 rows after the
// server had returned 500.
const publishQueueLimit = 500

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
		// The old sentence promised that any submitted draft would show up here, which is false for
		// anyone below `maintain`: the queue lists what you can publish, not what you can write (v22 F4).
		fmt.Println("没有你能上线的草稿。这里只列 maintain 及以上的 skill。")
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
	fmt.Printf("（共 %d 个 skill、%d 份草稿。上线在网页上由人点：skmr version open <地址>@<草稿码> --sub actions）\n",
		len(queue.Skills), drafts)
	if queue.Truncated {
		// **Said out loud.** A listing that stops without saying so reads as "this is everything",
		// and somebody acting on that would conclude the rest has nothing waiting (PRD 验收 19).
		fmt.Printf("（只列出了最近 %d 个 skill；剩下的没有列进来。）\n", publishQueueLimit)
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

package main

import (
	"context"
	"fmt"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/local"
)

// stateLine is the sentence `startup` prints above the journey: where this person actually is.
//
// **The journey body is static; this line is the resume point.** Read cold, a six-step document
// makes a person who stopped at step four re-read all of it to find out what is left. The line says
// it in one sentence, and it is computed rather than guessed: local copies by scanning, drafts from
// the publish queue, live skills from the listing (v17 acceptance 29).
//
// Counts, not names: the named answer is one command away (`skills pending`), and a line that grows
// with the library stops being a line.
func whereYouAreLine(localCount, drafts, live int, liveTruncated bool) string {
	liveText := fmt.Sprintf("%d 条", live)
	if liveTruncated {
		// A page that stops without saying so reads as "this is everything" — the failure the
		// listing itself refuses to make, and this line must not reintroduce it.
		liveText = "50 条以上"
	}

	var next string
	switch {
	case drafts > 0:
		next = "下一步：上线（第三步）。"
	case localCount > 0 && live > 0:
		next = "下一步：写提示词、验证，然后下线（第四到六步）。"
	case localCount > 0:
		next = "下一步：搬运（第二步）。"
	case live > 0:
		next = "已完成：平台可用，本机没有副本。"
	default:
		next = "下一步：搬运（第二步）。"
	}

	return fmt.Sprintf("现在的位置：本机候选目录里 %d 个 skill，库里 %s线上、%d 份草稿在等上线 —— %s",
		localCount, liveText, drafts, next)
}

// whereYouAre gathers the three numbers. **Any failure means no line at all**, which is the honest
// answer: printing a zero because the server was unreachable would say "your library is empty" to
// somebody whose library is not, and the body is served anonymously anyway, so the journey still
// prints (v17 acceptance 29).
func whereYouAre(ctx context.Context, client api.Client) (string, bool) {
	skills, err := local.Scan(nil)
	if err != nil {
		return "", false
	}
	queue, err := client.PublishQueue(ctx)
	if err != nil {
		return "", false
	}
	drafts := 0
	for _, row := range queue.Skills {
		drafts += len(row.Drafts)
	}
	page, err := client.QuerySkills(ctx, api.QueryOptions{Show: "live"})
	if err != nil {
		return "", false
	}
	return whereYouAreLine(len(skills), drafts, len(page.Skills), page.NextCursor != ""), true
}

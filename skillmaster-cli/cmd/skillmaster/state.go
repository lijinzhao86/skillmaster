package main

import (
	"context"
	"fmt"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/local"
)

// stateLine is the sentence `startup` prints above the journey: where this person actually is.
//
// **The journey body is static; this line is the resume point.** Read cold, a step-by-step document
// makes a person who stopped halfway re-read all of it to find out what is left — and with the
// journey skill it also picks the file to read next. The line says it in one sentence, and it is
// computed rather than guessed: local copies by scanning, drafts from the publish queue, live skills
// from the listing (v17 acceptance 29).
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

	// **Phases, never step numbers.** The steps live in the journey skill, where they are 1–5 and
	// will change as the journey does; a second numbering here would disagree with it the first time
	// somebody inserted a step (ADR 0096). The skill's own table maps the phase to the file.
	var next string
	switch {
	case drafts > 0:
		next = "下一步：上线。"
	case localCount > 0 && live > 0:
		next = "下一步：写提示词、验证，然后下线。"
	case localCount > 0:
		next = "下一步：搬运。"
	case live > 0:
		next = "已完成：平台可用，本机没有副本。"
	default:
		next = "下一步：搬运。"
	}

	return fmt.Sprintf("现在的位置：本机候选目录里 %d 个 skill，库里 %s线上、%d 份草稿在等上线 —— %s",
		localCount, liveText, drafts, next)
}

// whereYouAre gathers the three numbers. **Any failure means no line at all**, which is the honest
// answer: printing a zero because the server was unreachable would say "your library is empty" to
// somebody whose library is not (v17 acceptance 29).
//
// **A missing line is not a promise that the journey still prints.** It used to be, while the body
// was an anonymous document; since the journey became a skill (ADR 0096) the body needs the same
// server and a credential the server accepts, so a network or credential failure takes both. Only
// the *state* is best-effort, never the journey.
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

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
// whereabouts is what the line is computed from: the names on this machine, the names of the
// caller's own skills that are live, and the names waiting as drafts.
//
// **Names, not counts, for the verdict.** A count cannot tell "33 local skills, every one of them
// up there" from "33 local skills, none of them up there" — and 1.0.0 read the second as the first,
// pointing a first-time user past the import step (KI-1, 2026-10-26). The counts still describe the
// library in the sentence; they no longer decide which sentence it is.
type whereabouts struct {
	local   map[string]struct{} // distinct skill names in the candidate directories
	live    map[string]struct{} // names of the caller's own live skills (one page)
	pending map[string]struct{} // names with at least one draft waiting
	drafts  int                 // waiting drafts: the sum of len(Drafts) over this person's own rows
	user    string              // the account the credential belongs to; empty when unknown

	liveTruncated   bool
	draftsTruncated bool
}

// unmoved counts the local skills the platform does not know yet: neither live nor waiting as a
// draft.
//
// **Same name is enough.** Content is not compared — the design allows two skills to share a name,
// and a resume line that re-scanned bodies would be the import flow (which stops and asks), not a
// line. Understating by one is the smaller lie (see the note in whereYouAre).
func (w whereabouts) unmoved() int {
	count := 0
	for name := range w.local {
		if _, up := w.live[name]; up {
			continue
		}
		if _, waiting := w.pending[name]; waiting {
			continue
		}
		count++
	}
	return count
}

// online counts the local skills that are live — the number the sentence shows as 「其中 N 个已上线」.
func (w whereabouts) online() int {
	count := 0
	for name := range w.local {
		if _, up := w.live[name]; up {
			count++
		}
	}
	return count
}

func whereYouAreLine(w whereabouts) string {
	liveText := fmt.Sprintf("%d 条", len(w.live))
	if w.liveTruncated {
		// A page that stops without saying so reads as "this is everything" — the failure the
		// listing itself refuses to make, and this line must not reintroduce it. The page size is
		// read from the constant rather than typed here: it moved 100 → 50 once already.
		liveText = fmt.Sprintf("%d 条以上", api.ListPageSize)
	}

	// **The same rule for the queue.** `pending` says out loud when it has listed only a page
	// (pending.go); a count taken from that page and printed as exact would contradict it.
	draftsText := fmt.Sprintf("%d 份草稿在等上线", w.drafts)
	if w.draftsTruncated {
		draftsText = fmt.Sprintf("%d 份以上草稿在等上线（更早的没有列进来）", w.drafts)
	}

	// **Phases, never step numbers.** The steps live in the journey skill, where they are 1–5 and
	// will change as the journey does; a second numbering here would disagree with it the first time
	// somebody inserted a step (v20 TD §状态行, ADR 0096). The skill's own table maps the phase to
	// the file.
	//
	// **Importing is optional (ADR 0108).** Unmoved local copies no longer beat drafts or the prompt:
	// the person may decline the import, or upload only part of it, and still continue. The line
	// still shows 「其中 N 个已上线」 so an unmoved copy is visible; it no longer decides the next
	// step. This intentionally reverses the old KI-1 ordering — the original KI-1 bug was claiming
	// the import was done, which the name sets still prevent.
	//
	// **Drafts come first** because publishing is the next required action once anything was
	// uploaded; the prompt is the last required step after that.
	var next string
	switch {
	case w.drafts > 0:
		next = "下一步：上线。"
	case len(w.local) > 0:
		next = "下一步：写提示词、验证，然后下线。"
	case len(w.live) > 0:
		next = "已完成：平台可用，本机没有副本。"
	default:
		next = "下一步：写提示词、验证。"
	}

	// **A cut page makes the intersection a lower bound.** Names missing from page one may still be
	// live further down, so 「其中 N 个」 would read as an exact count this cannot support.
	onlineText := fmt.Sprintf("其中 %d 个已上线", w.online())
	if w.liveTruncated {
		onlineText = fmt.Sprintf("其中至少 %d 个已上线", w.online())
	}

	return fmt.Sprintf("现在的位置：本机候选目录里 %d 个 skill（%s），我的库里有 %s线上、%s —— %s",
		len(w.local), onlineText, liveText, draftsText, next)
}

// whereYouAre gathers the three name sets — scanning this machine, asking who the caller is, then
// reading the queue and the live listing. **Any failure means no line at all**, which is the honest
// answer: printing a zero because the server was unreachable would say "your library is empty" to
// somebody whose library is not (v17 acceptance 29).
//
// **A missing line is not a promise that the journey still prints.** It used to be, while the body
// was an anonymous document; since the journey became a skill (ADR 0096) the body needs the same
// server and a credential the server accepts, so a network or credential failure takes both. Only
// the *state* is best-effort, never the journey.
func whereYouAre(ctx context.Context, client api.Client) (string, bool) {
	w, err := gatherWhereabouts(ctx, client)
	if err != nil {
		return "", false
	}
	return whereYouAreLine(w), true
}

// gatherWhereabouts is the structured half. `startup --plan` uses it because it needs the pieces,
// not the one-line verdict; `whereYouAre` keeps the old "any failure means no line" contract.
func gatherWhereabouts(ctx context.Context, client api.Client) (whereabouts, error) {
	skills, err := local.Scan(nil)
	if err != nil {
		return whereabouts{}, err
	}
	return gatherWhereaboutsFrom(ctx, client, skills)
}

// gatherWhereaboutsFrom takes the local scan as an argument so `startup --plan` can still show the
// local half when the server is unreachable.
func gatherWhereaboutsFrom(ctx context.Context, client api.Client, skills []local.Skill) (whereabouts, error) {
	// **Which namespace is this person's.** The queue below is a *permission* surface — it lists
	// what the caller may publish, which includes skills shared with them at maintain or above
	// (ADR 0090 decision 7) — so knowing one's own namespace is what keeps a teammate's skill from
	// being mistaken for one of these local copies.
	me, err := client.Me(ctx)
	if err != nil {
		return whereabouts{}, err
	}
	// **An answer without a namespace is not the answer "my namespace is empty".** Filtering on ""
	// would drop every queue row and still print a confident line — the one failure this line is
	// not allowed to make (v17 acceptance 29).
	if me.User.Namespace == "" {
		return whereabouts{}, fmt.Errorf("服务端没有给出命名空间，没法判断这台机器的 skill 是否已经传上去")
	}
	queue, err := client.PublishQueue(ctx)
	if err != nil {
		return whereabouts{}, err
	}
	// **Only the caller's own skills count.** The listing's default scope is the library, which is
	// created, shared and joined at once (SearchScope): a teammate's skill that happens to share a
	// directory name would make a local copy look moved — KI-1 through another door. The server
	// does the filtering, so the page it returns is all relevant rows too.
	page, err := client.QuerySkills(ctx, api.QueryOptions{Show: "live", Sources: []string{"created"}})
	if err != nil {
		return whereabouts{}, err
	}
	// **Distinct names, not directories.** The same skill routinely sits in two candidate
	// directories as two independent copies (v17 PRD §各 agent 的技能目录 结论 3 measured 28 in
	// `~/.agents/skills` and 33 in `~/.claude/skills`, the former all named in the latter), and
	// counting both turns "how many do I have" into a number bigger than the library.
	//
	// The opposite error is accepted here on purpose: two *different* skills that share a directory
	// name merge into one. The PRD says the import flow must stop and ask in that case; a one-line
	// resume point cannot ask, and understating by one is the smaller lie.
	here := make(map[string]struct{}, len(skills))
	for _, skill := range skills {
		here[skill.Name] = struct{}{}
	}

	// **Same rule for the queue, by namespace.** It lists what the caller may publish, and maintain
	// on somebody else's skill is a way to get their drafts into that list. A local directory that
	// shares a name with one of those would look moved — so only this person's own namespace counts.
	waiting := make(map[string]struct{}, len(queue.Skills))
	drafts := 0
	for _, row := range queue.Skills {
		if row.Namespace != me.User.Namespace {
			continue
		}
		drafts += len(row.Drafts)
		if len(row.Drafts) > 0 {
			waiting[row.Name] = struct{}{}
		}
	}

	up := make(map[string]struct{}, len(page.Skills))
	for _, card := range page.Skills {
		up[card.Name] = struct{}{}
	}

	return whereabouts{
		local:   here,
		live:    up,
		pending: waiting,
		drafts:  drafts,
		user:    me.User.Username,

		liveTruncated:   page.NextCursor != "",
		draftsTruncated: queue.Truncated,
	}, nil
}

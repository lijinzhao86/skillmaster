// The skills collection: search, list, query parsing, and card output.

package main

import (
	"context"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"strings"
)

// The semantic shortlist's default and ceiling (v19 TD §接口). They are the server's numbers too;
// the client repeats them only to send a page size the server would otherwise have to guess.
const (
	searchPageSize  = 10
	searchPageLimit = 50
)

func search(ctx context.Context, args []string) error {
	options, err := parseQueryArgs(args, true)
	if err != nil {
		return err
	}

	found, err := authenticatedWith(ctx, options.noLogin, func(client api.Client) (listed, error) {
		if options.note != "" {
			return listed{note: options.note}, nil
		}
		page, err := client.QuerySkills(ctx, queryOptionsFrom(options))
		if err != nil {
			return listed{}, err
		}
		return listed{cards: page.Skills, more: page.NextCursor != "", note: page.Note}, nil
	})
	if err != nil {
		return v14Error(err)
	}
	// **The hint goes first, not last** (v30). It is about the next search, and both ways this output
	// is cut keep the head and drop the tail: Claude Code inlines only the first ~30,000 characters
	// of a successful command and spills the rest to a file, and callers routinely pipe the listing
	// through `| head`. A footer would be invisible in exactly the runs where the advice matters.
	if len(found.cards) > 0 && len(options.excludes) == 0 {
		fmt.Println("（要换说法再查：加 --exclude 带上这次给过的地址，重复的不再占上下文。）")
	}
	if options.limitClamped {
		fmt.Printf("（--limit 最多 %d 条，这次按 %d 条取。）\n", searchPageLimit, searchPageLimit)
	}
	if len(found.cards) == 0 {
		if found.note != "" {
			fmt.Println(found.note)
		} else if len(options.excludes) == 0 {
			fmt.Println("没有匹配的 skill。")
		}
		if len(options.excludes) > 0 {
			// **Said whenever `--exclude` was in play, even when the server has its own sentence.**
			// An empty semantic result always carries a note ("没有足够相关的 skill。"), so gating on
			// an absent note would silence this line in exactly the ordinary case. The wording is a
			// *possibility* rather than a diagnosis, because the server cannot say whether exclusion
			// caused the emptiness (v30 不做 #4) — and it stays useful beside a vocabulary note too,
			// where the next step is a different one anyway.
			fmt.Println("（这种空结果也可能是 --exclude 排掉的：去掉 --exclude 再看。）")
		}
		if found.note == "" {
			for _, hint := range filterHints(options) {
				fmt.Println(hint)
			}
		}
		if options.scope == api.ScopeLibrary {
			fmt.Println("（如果已有目标 skill、只是说法对不上：先问用户，再 skmr skill my-when-to-use set <地址> --text <说法>；只对库里的 skill 有效。）")
		}
		return nil
	}
	if found.note != "" {
		// The server's own sentence, printed before the results: a keyword answer that came back
		// because semantic search fell back must not read like a semantic one (v19 PRD #7).
		fmt.Println(found.note)
	}
	printCards(found.cards, true, options.verbose)
	if found.more {
		// Said rather than silently dropped: a listing that stops without saying so reads as
		// "that is everything", which is a claim the CLI would be making on the server's behalf.
		fmt.Printf("（还有更多结果；缩小关键词、加分类或公开标签再试。）\n")
	}
	return nil
}

// listSkills prints the skills in this caller's library: one address per line, and nothing else.
//
// **This is an inventory, not a card** (v31, ADR 0115). It answers "what do I have", and the address
// is the whole answer — it is what a later command takes, whole, on a line by itself. **The address
// carries no version** (ADR 0035). Discovery ("which one might match") belongs to `skills search`,
// which prints the description it ranks by, and inspection of one skill belongs to `skill show`;
// `--verbose` is the one place this command still prints descriptions in bulk.
//
// **Since v14 the question is the library, not "everything I may read"** (ADR 0082): a public skill
// the caller has not joined is found with `skills search`, and appears here only after `skill join`.
// The default state is the consumable one — published, online, not deleted — and the other states are
// asked for with `--show`.
//
// **Every page by default, and no cursor a caller has to carry.** The listing used to stop at fifty
// rows, a size chosen when each row carried ~0.6 KB of description; an address is ~25 bytes, so the
// cap lost its reason and was the one thing that made the inventory incomplete (v31). The page token
// this command once printed was 191 characters of arbitrary alphanumeric, and asking the *model* to
// reproduce that is asking for the wrong thing; the loop lives in `collect`, and `--all` is still
// accepted — it just no longer changes anything.
func listSkills(ctx context.Context, args []string) error {
	options, err := parseQueryArgs(args, false)
	if err != nil {
		return err
	}

	found, err := authenticatedWith(ctx, options.noLogin, func(client api.Client) (listed, error) {
		if options.note != "" {
			return listed{note: options.note}, nil
		}
		// Always every page (v31). `options.all` is still parsed so that a caller's `--all` keeps
		// working; every page is now what the command does with or without it.
		return collect(ctx, client, queryOptionsFrom(options))
	})
	if err != nil {
		return v14Error(err)
	}

	if len(found.cards) == 0 {
		if found.note != "" {
			fmt.Println(found.note)
			return nil
		}
		fmt.Println(emptyLibraryLine(options))
		for _, hint := range filterHints(options) {
			fmt.Println(hint)
		}
		return nil
	}
	// The reading only under `--verbose` (v31): the default is the inventory. `--verbose` asks for
	// the old card, which is the same list with a description and the small fields under each row.
	printCards(found.cards, options.verbose, options.verbose)

	// The address above is what a later command takes, and the next step is not obvious — the
	// address alone does not load anything, and `invoke` is the command that both loads the body
	// and decides which version the rest of the task will read.
	fmt.Printf("（共 %d 个。加载某一个：skmr skill invoke <上面某一行的地址>。）\n", len(found.cards))
	return nil
}

// emptyLibraryLine is what the library says when it has nothing to show.
//
// Two sentences rather than one, because the two situations are different: an empty library is a
// place to put something, and a filter that matched nothing is a word to change. The console draws
// the same distinction (PRD §展示与计数口径 空态); this is its command-line half.
func emptyLibraryLine(options queryArgs) string {
	if options.query != "" || len(options.categories) > 0 || len(options.myTags) > 0 ||
		len(options.publicTags) > 0 || len(options.sources) > 0 ||
		len(options.namespaces) > 0 {
		return "没有匹配的 skill。"
	}
	switch options.show {
	case "deleted":
		return "没有已删除的 skill。"
	case "draft":
		return "没有草稿。"
	case "offline":
		return "没有已下线的 skill。"
	case "pending":
		return "没有下线中的 skill。"
	default:
		return "你的库里还没有能显示的 skill。"
	}
}

// printCards is how one row looks, in the two places that print a listing.
//
// **The address is alone on its own line**, and that is for the caller rather than for looks: it is
// the value a later command takes, whole, and a line with nothing else on it is a line that can be
// copied without deciding where it ends.
//
// What goes under it is asked for, one switch at a time:
//
//   - **`withReading`** prints 「描述 - 适用场景」. `skills search` always sets it — relevance is
//     judged by exactly that text. `skills list` sets it only under `--verbose` (v31): an inventory
//     is an address list, and descriptions have their own commands (`show`, and `--verbose` itself).
//   - **`verbose`** adds the source / state / version / updated line (PRD #51). It goes below the
//     reading, so a caller that reads the first line still gets the address alone.
func printCards(cards []api.Card, withReading, verbose bool) {
	for _, card := range cards {
		fmt.Println(card.Address())
		if withReading {
			if summary := card.Summary(); summary != "" {
				if card.MyWhenToUseMatched {
					summary += "（命中你写的适用场景）"
				}
				fmt.Printf("    %s\n", summary)
			}
		}
		if verbose {
			if line := verboseLine(card); line != "" {
				fmt.Printf("    %s\n", line)
			}
		}
	}
}

// verboseLine is `--verbose`'s one line: where the skill came from, what state it is in, and the
// facts a person narrowing a list needs (PRD §CLI 命令面 `--verbose`).
//
// **A field with no value is left out rather than printed empty.** A draft has no version, a skill
// need not be on a shelf, and a list may have no tags; printing `版本：` for each would be three
// blanks pretending to be answers.
// verboseLicense is the compact licence fact. It never prints the wire sentinel `NOASSERTION`:
// `missing` and `other` carry that sentinel in `license_spdx`, and a person reading a list should
// see the status word (`skill show`'s wording) rather than an internal value.
func verboseLicense(card api.Card) string {
	switch card.LicenseStatus {
	case "missing":
		return "未声明"
	case "other":
		return "其他许可"
	case "declared-only":
		if card.LicenseSpdx == "" {
			return ""
		}
		return card.LicenseSpdx + "（仅声明，未附全文）"
	default:
		if card.LicenseSpdx == "" || card.LicenseSpdx == "NOASSERTION" {
			return ""
		}
		return card.LicenseSpdx
	}
}

func verboseLine(card api.Card) string {
	var parts []string
	if card.Source != "" {
		parts = append(parts, "来源："+sourceLabel(card.Source))
	}
	var public []string
	if card.OriginLabel != "" {
		public = append(public, card.OriginLabel)
	}
	if name := verboseLicense(card); name != "" {
		public = append(public, name)
	}
	if card.CallsBucket != "" {
		// The row compares "how much is it used", so the cumulative bucket is the number here;
		// the distinct-account bucket stays on `skill show` (PRD §CLI 命令面).
		public = append(public, callsBucket(card.CallsBucket))
	}
	if len(public) > 0 {
		// 「公域来源」 rather than a second 「来源」: the line above is the caller's library
		// relation, and two fields with one name would read as one fact.
		parts = append(parts, "公域来源："+strings.Join(public, " · "))
	}
	if card.State != "" {
		// v61: a ramp carries the date that makes the state actionable; without it 「下线中」 says
		// what but not when.
		state := cardStateLabel(card.State)
		if until := card.OfflinePendingUntil; until != "" {
			if len(until) >= 10 {
				until = until[:10]
			}
			state += " · " + until
		}
		parts = append(parts, "状态："+state)
	}
	if card.Version != "" {
		parts = append(parts, "版本："+card.Version)
	}
	when, whenLabel := card.UpdatedAt, "更新于"
	if card.State == "deleted" {
		// The deleted view answers "when did it go", not "when did it last change" — the row is in
		// this list because of the former.
		whenLabel = "删除于"
		if card.DeletedAt != nil {
			when = *card.DeletedAt
		}
	}
	if when != "" {
		parts = append(parts, whenLabel+"："+dayOf(when))
	}
	if card.Category != nil && card.Category.Name != "" {
		parts = append(parts, "分类："+card.Category.Name)
	}
	if len(card.PublicTags) > 0 {
		// No `#` here: the hash is the console's way of writing this layer (voice-spec §公开标签
		// 写成 #名字). The CLI and the documentation print the word itself.
		names := make([]string, 0, len(card.PublicTags))
		for _, tag := range card.PublicTags {
			names = append(names, tag.Name)
		}
		parts = append(parts, "公开标签："+strings.Join(names, "、"))
	}
	return strings.Join(parts, " · ")
}

// dayOf keeps the date half of an RFC 3339 timestamp. A listing is read to decide what to open, and
// the clock time has never decided that; the server's own spelling is kept rather than reformatted.
func dayOf(timestamp string) string {
	if len(timestamp) >= 10 {
		return timestamp[:10]
	}
	return timestamp
}

func sourceLabel(source string) string {
	switch source {
	case "created":
		return "我创建的"
	case "shared":
		return "共享给我的"
	case "joined":
		return "我加入的"
	default:
		return source
	}
}

func cardStateLabel(state string) string {
	switch state {
	case "live":
		return "在用"
	case "draft":
		return "草稿"
	case "offline":
		return "已下线"
	case "pending":
		return "下线中"
	case "deleted":
		return "已删除"
	default:
		return state
	}
}

// listed is what a call to this command read, whether there was more it did not read, and — when a
// filter named something the vocabulary does not have — the sentence to print instead of a request.
type listed struct {
	cards []api.Card
	more  bool
	note  string
}

// collect reads every page.
//
// The pages are gathered before anything is printed, so a request that fails in the middle prints no
// half list. A half list and a complete one look exactly alike, and this is a listing whose whole
// purpose is to be the caller's picture of what exists.
//
// **There is no one-page mode** (v31): the listing is an inventory of addresses, and an inventory
// that stops at a page boundary is not one. `--all` is still accepted by the command for callers
// that send it, and parsing it changes nothing here — every listing reads every page.
func collect(ctx context.Context, client api.Client, options api.QueryOptions) (listed, error) {
	var cards []api.Card
	cursor := ""
	seen := map[string]struct{}{}
	for {
		// **The filter goes on every page, and that is the one thing this loop has to get right.**
		// The server's cursor carries the ordering and not the predicate, so a page fetched without
		// the filter is accepted and quietly answers a different question — the silent skip the
		// whole cursor design exists to prevent, arriving from this side. Passing the same options
		// each turn is what makes it unrepresentable here; a test asserts the query carries it.
		options.Cursor = cursor
		page, err := client.QuerySkills(ctx, options)
		if err != nil {
			return listed{}, err
		}
		cards = append(cards, page.Skills...)
		if page.NextCursor == "" {
			return listed{cards: cards}, nil
		}
		// A server that answers with a cursor it has already given is not paging, and looping on it
		// would hang with no output. Any repeat counts, not just the one just sent: A→B→A walks
		// forever without ever sending the same cursor twice in a row. Stopped and said out loud
		// rather than repeated: the silence of an infinite loop reads as a slow network, and this
		// one is a bug on the far end.
		if _, repeated := seen[page.NextCursor]; repeated {
			return listed{}, fmt.Errorf("服务端反复给出同一个游标，列表走不下去了（已取到 %d 条）", len(cards))
		}
		seen[page.NextCursor] = struct{}{}
		cursor = page.NextCursor
	}
}

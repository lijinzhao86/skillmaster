package main

// v14's tags, categories and vocabularies (PRD §CLI 命令面 §`skills`, TD §CLI 映射).
//
// **The two groups divide along the grammar**: `skills` owns the words themselves — the vocabularies,
// the definitions, the search filters — and `skill` owns one skill's attachment to them. `skills
// my-tags add` creates a word; `skill tag add` uses one. That split is why both files exist, and why
// neither command grows a second spelling for the other's job.

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// ---------------------------------------------------------------------------
// Markdown tables
// ---------------------------------------------------------------------------

// printTable writes one Markdown table. **The column headings are the definition**, not decoration:
// `公域` and `用在` are two different counts (PRD §展示与计数口径), and a table that renamed either
// would invite them to be read as one number.
func printTable(headers []string, rows [][]string) {
	fmt.Printf("| %s |\n", strings.Join(headers, " | "))
	fmt.Println("|" + strings.Repeat("---|", len(headers)))
	for _, row := range rows {
		cells := make([]string, 0, len(row))
		for _, value := range row {
			cells = append(cells, tableCell(value))
		}
		fmt.Printf("| %s |\n", strings.Join(cells, " | "))
	}
}

// tableCell keeps a value from breaking the table. A `|` is escaped rather than dropped — the name
// is still the name — and a newline becomes a space, because a table row is one line by definition.
func tableCell(value string) string {
	value = strings.ReplaceAll(value, "|", `\|`)
	value = strings.ReplaceAll(value, "\r\n", " ")
	value = strings.ReplaceAll(value, "\n", " ")
	return value
}

// ---------------------------------------------------------------------------
// skills categories / public-tags / my-tags
// ---------------------------------------------------------------------------

// categoriesCmd prints the platform vocabulary in its own order.
//
// **The order is the vocabulary's, not this command's**: the ten words have a fixed position, and a
// listing that re-sorted them by count would move a word every time somebody tagged a skill. That is
// the difference between this table and `public-tags`, which is ordered by usage.
func categoriesCmd(ctx context.Context, args []string) error {
	noLogin := false
	for _, arg := range args {
		switch arg {
		case "--no-login":
			noLogin = true
		default:
			if strings.HasPrefix(arg, "-") {
				return unknownFlag("skills categories", arg)
			}
			return usagef("skills categories 不接受参数；分类词表总是全部列出。%s", usageAt("skills categories"))
		}
	}

	rows, err := authenticatedWith(ctx, noLogin, func(client api.Client) ([]api.CategoryRow, error) {
		return client.Categories(ctx)
	})
	if err != nil {
		return v14Error(err)
	}

	table := make([][]string, 0, len(rows))
	for _, row := range rows {
		name := row.Name
		if row.State == "stopped" {
			// The state is written on the word rather than given a column of its own: one bucket for
			// every row would be a column that says 「启用」 nine times (PRD §CLI 命令面).
			name += "（停用）"
		}
		table = append(table, []string{name, strconv.Itoa(row.PublicTotal)})
	}
	printTable([]string{"分类", "公域"}, table)
	return nil
}

// publicTagListing is one answer from the public-tag vocabulary and whether more exist.
type publicTagListing struct {
	tags []api.TagCount
	more bool
}

func publicTagsCmd(ctx context.Context, args []string) error {
	all := false
	noLogin := false
	var words []string
	for _, arg := range args {
		switch arg {
		case "--all":
			all = true
		case "--no-login":
			noLogin = true
		default:
			if strings.HasPrefix(arg, "-") {
				return unknownFlag("skills public-tags", arg)
			}
			words = append(words, arg)
		}
	}
	query := strings.TrimSpace(strings.Join(words, " "))

	found, err := authenticatedWith(ctx, noLogin, func(client api.Client) (publicTagListing, error) {
		return collectPublicTags(ctx, client, query, all)
	})
	if err != nil {
		return v14Error(err)
	}

	// Count descending, ties by name — the order the management page uses, so the same vocabulary
	// reads the same way in both places (PRD §展示与计数口径).
	sort.SliceStable(found.tags, func(i, j int) bool {
		if found.tags[i].PublicTotal != found.tags[j].PublicTotal {
			return found.tags[i].PublicTotal > found.tags[j].PublicTotal
		}
		return found.tags[i].Name < found.tags[j].Name
	})

	rows := make([][]string, 0, len(found.tags))
	for _, tag := range found.tags {
		rows = append(rows, []string{tag.Name, strconv.Itoa(tag.PublicTotal)})
	}
	printTable([]string{"公开标签", "公域"}, rows)

	if len(found.tags) == 0 {
		// The header stays: an empty vocabulary is an answer, not an error (PRD §CLI 命令面).
		// With no keyword there was nothing to match, so "换个词试试" would ask for the impossible.
		if query == "" {
			fmt.Println("还没有公开标签。作者在信息页给 skill 贴一个，它会出现在这里。")
		} else {
			fmt.Println("没有匹配的公开标签。换个词试试。")
		}
		return nil
	}
	if found.more {
		fmt.Printf("（这一页 %d 个，还有更多。用 skmr skills public-tags --all 取全。）\n",
			len(found.tags))
	}
	return nil
}

// collectPublicTags reads one page, or every page when `--all` was asked for.
//
// **The cursor never leaves this function.** Same rule as the library listing, and the same reason:
// a value a caller cannot reliably reproduce must not be a value it has to carry between calls.
func collectPublicTags(ctx context.Context, client api.Client, query string,
	all bool) (publicTagListing, error) {
	var tags []api.TagCount
	cursor := ""
	seen := map[string]struct{}{}
	for {
		page, err := client.PublicTags(ctx, query, cursor)
		if err != nil {
			return publicTagListing{}, err
		}
		tags = append(tags, page.Tags...)
		if page.NextCursor == "" {
			return publicTagListing{tags: tags}, nil
		}
		if !all {
			return publicTagListing{tags: tags, more: true}, nil
		}
		if _, repeated := seen[page.NextCursor]; repeated {
			return publicTagListing{}, fmt.Errorf("服务端反复给出同一个游标，列表走不下去了（已取到 %d 个）",
				len(tags))
		}
		seen[page.NextCursor] = struct{}{}
		cursor = page.NextCursor
	}
}

// myTagsCmd is the `skills my-tags` family: the list, and the three definition writes.
//
// **There is no `delete`, and its absence is the contract** (ADR 0079): deleting a definition takes
// every attachment with it, including the sleeping ones, and a person cannot undo that from a
// terminal. The red dialog on the console is where it happens.
func myTagsCmd(ctx context.Context, args []string) (err error) {
	if len(args) > 0 {
		// The command's own address is settled once, here: a parser deep inside has no way to know which
		// verb reached it (`--offset` serves both `read` and `invoke`), and every usage error has to end
		// at the help of the command that was actually typed (PRD 做 #4).
		defer func() { err = retargetUsage(err, "skills my-tags "+args[0]) }()
	}
	if len(args) > 0 {
		switch args[0] {
		case "add":
			return addMyTag(ctx, args[1:])
		case "rename":
			return renameMyTag(ctx, args[1:])
		case "describe":
			return describeMyTag(ctx, args[1:])
		case "delete":
			// **Not a search term.** Deleting a definition takes every attachment with it, including
			// the ones a person cannot see, and the console's red dialog is the only place that
			// happens (ADR 0079, PRD §不做 #8). Answering "no matches" here would leave somebody
			// believing the command exists and their tag was just spelled differently.
			return errors.New("删除标签定义只在控制台：它会摘掉所有 skill 上的贴法（含你现在看不到的），不能恢复。")
		}
	}
	return listMyTags(ctx, args)
}

func listMyTags(ctx context.Context, args []string) error {
	noLogin := false
	var words []string
	for _, arg := range args {
		switch arg {
		case "--no-login":
			noLogin = true
		default:
			if strings.HasPrefix(arg, "-") {
				return unknownFlag("skills my-tags", arg)
			}
			words = append(words, arg)
		}
	}
	query := strings.TrimSpace(strings.Join(words, " "))

	tags, err := authenticatedWith(ctx, noLogin, func(client api.Client) ([]api.MyTag, error) {
		return client.MyTags(ctx, query)
	})
	if err != nil {
		return v14Error(err)
	}

	sort.SliceStable(tags, func(i, j int) bool {
		if tags[i].UsedIn != tags[j].UsedIn {
			return tags[i].UsedIn > tags[j].UsedIn
		}
		return tags[i].Name < tags[j].Name
	})

	rows := make([][]string, 0, len(tags))
	for _, tag := range tags {
		description := ""
		if tag.Description != nil {
			description = *tag.Description
		}
		rows = append(rows, []string{tag.Name, fmt.Sprintf("%d 个 skill 上", tag.UsedIn), description})
	}
	printTable([]string{"标签", "用在", "说明"}, rows)
	if len(tags) == 0 {
		if query == "" {
			fmt.Println("还没有标签。用 skmr skills my-tags add <名字> 新建一个。")
		} else {
			fmt.Println("没有匹配的标签。换个词试试。")
		}
	}
	return nil
}

// addMyTag creates a definition and attaches it to nothing.
//
// **A word with no attachments is the point**: a person (or an agent) wants the word ready before
// they have decided which skills belong under it, and the only other way to make one is to tag a
// skill first.
func addMyTag(ctx context.Context, args []string) error {
	name := ""
	var description *string
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--describe":
			value, err := flagValue(args, i, "--describe", "skills my-tags add")
			if err != nil {
				return err
			}
			description = &value
			i++
		default:
			if strings.HasPrefix(args[i], "-") {
				return unknownFlag("skills my-tags add", args[i])
			}
			if name != "" {
				return usagef("%s", usageAt("skills my-tags add"))
			}
			name = args[i]
		}
	}
	if strings.TrimSpace(name) == "" {
		return usagef("%s", usageAt("skills my-tags add"))
	}

	if _, err := authenticated(ctx, func(client api.Client) (api.Change, error) {
		return client.CreateMyTag(ctx, name, description)
	}); err != nil {
		return v14Error(err)
	}
	if description != nil && strings.TrimSpace(*description) != "" {
		fmt.Printf("已新建标签「%s」，并写了说明。\n", name)
		return nil
	}
	fmt.Printf("已新建标签「%s」。\n", name)
	return nil
}

// renameMyTag changes the definition, and every attachment follows it.
//
// **The old name is resolved to an id first** (TD §CLI 映射): the definition's identity survives a
// rename, and sending a name to the write path would make the server answer "no such tag" for the one
// operation that is supposed to change it.
func renameMyTag(ctx context.Context, args []string) error {
	oldName := ""
	newName := ""
	named := false
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--name":
			value, err := flagValue(args, i, "--name", "skills my-tags rename")
			if err != nil {
				return err
			}
			newName = value
			named = true
			i++
		default:
			if strings.HasPrefix(args[i], "-") {
				return unknownFlag("skills my-tags rename", args[i])
			}
			if oldName != "" {
				return usagef("%s", usageAt("skills my-tags rename"))
			}
			oldName = args[i]
		}
	}
	if strings.TrimSpace(oldName) == "" || !named || strings.TrimSpace(newName) == "" {
		return usagef("%s", usageAt("skills my-tags rename"))
	}

	if _, err := authenticated(ctx, func(client api.Client) (struct{}, error) {
		tag, found, err := findMyTag(ctx, client, oldName)
		if err != nil {
			return struct{}{}, err
		}
		if !found {
			return struct{}{}, noSuchMyTag(oldName)
		}
		_, err = client.UpdateMyTag(ctx, tag.ID, &newName, nil)
		return struct{}{}, err
	}); err != nil {
		return v14Error(err)
	}
	fmt.Printf("已改名：「%s」→「%s」。\n", oldName, newName)
	return nil
}

// describeMyTag writes the definition's one sentence. **An empty `--text` clears it**, which is why
// the flag's presence is tracked separately from its value (TD Q6).
func describeMyTag(ctx context.Context, args []string) error {
	name := ""
	text := ""
	given := false
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--text":
			value, err := flagValue(args, i, "--text", "skills my-tags describe")
			if err != nil {
				return err
			}
			text = value
			given = true
			i++
		default:
			if strings.HasPrefix(args[i], "-") {
				return unknownFlag("skills my-tags describe", args[i])
			}
			if name != "" {
				return usagef("%s", usageAt("skills my-tags describe"))
			}
			name = args[i]
		}
	}
	if strings.TrimSpace(name) == "" || !given {
		return usagef("%s", usageAt("skills my-tags describe"))
	}

	if _, err := authenticated(ctx, func(client api.Client) (struct{}, error) {
		tag, found, err := findMyTag(ctx, client, name)
		if err != nil {
			return struct{}{}, err
		}
		if !found {
			return struct{}{}, noSuchMyTag(name)
		}
		_, err = client.UpdateMyTag(ctx, tag.ID, nil, &text)
		return struct{}{}, err
	}); err != nil {
		return v14Error(err)
	}
	if strings.TrimSpace(text) == "" {
		fmt.Printf("已清空「%s」的说明。\n", name)
		return nil
	}
	fmt.Printf("已更新「%s」的说明。\n", name)
	return nil
}

func findMyTag(ctx context.Context, client api.Client, name string) (api.MyTag, bool, error) {
	// The server resolves the word; the CLI does not compare display names.
	return client.ResolveMyTag(ctx, name)
}

func noSuchMyTag(name string) error {
	return fmt.Errorf("没有叫「%s」的标签定义。看你的标签：skmr skills my-tags。", name)
}

// ---------------------------------------------------------------------------
// skill tags / tag / category
// ---------------------------------------------------------------------------

// skillTags reads the three groups, one word per row.
//
// **The groups are in a fixed order and each has its own reader**: category (one), public tags
// (everybody), my tags (the caller, and only while their library entry is effective). The table says
// which group a word belongs to, because the words themselves do not — 「文档与演示」 and「客户项目」
// are indistinguishable without the left column.
func skillTags(ctx context.Context, args []string) error {
	address := ""
	noLogin := false
	for _, arg := range args {
		switch arg {
		case "--no-login":
			noLogin = true
		default:
			if strings.HasPrefix(arg, "-") {
				return unknownFlag("skill tags", arg)
			}
			if address != "" {
				return usagef("%s", usageAt("skill tags"))
			}
			address = arg
		}
	}
	if address == "" {
		return usagef("%s", usageAt("skill tags"))
	}
	if err := checkBareAddress(address); err != nil {
		return err
	}

	tags, err := authenticatedWith(ctx, noLogin, func(client api.Client) (api.SkillTags, error) {
		return client.Tags(ctx, address)
	})
	if err != nil {
		return v14Error(err)
	}

	var rows [][]string
	if tags.Category != nil && tags.Category.Name != "" {
		rows = append(rows, []string{"分类", tags.Category.Name})
	}
	for _, name := range tagNames(tags.PublicTags) {
		rows = append(rows, []string{"公开标签", name})
	}
	if tags.MyTags.Readable {
		for _, name := range myTagNames(tags.MyTags.Tags) {
			rows = append(rows, []string{"我的标签", name})
		}
	}
	printTable([]string{"组", "名字"}, rows)

	switch {
	case !tags.MyTags.Readable:
		// The group is missing on purpose, and the reason is the answer rather than an empty one.
		fmt.Println(myTagsUnreadable(tags.MyTags.Reason))
	case len(rows) == 0:
		fmt.Println("这个 skill 还没有分类或标签。")
	}
	return nil
}

// tagVerb is `skill tag add` / `skill tag remove`.
func tagVerb(ctx context.Context, args []string) (err error) {
	if len(args) == 0 {
		return usagef("%s", usageAt("skill tag"))
	}
	// The command's own address is settled once, here: a parser deep inside has no way to know which
	// verb reached it (`--offset` serves both `read` and `invoke`), and every usage error has to end
	// at the help of the command that was actually typed (PRD 做 #4).
	defer func() { err = retargetUsage(err, "skill tag "+args[0]) }()
	switch args[0] {
	case "add":
		return tagChange(ctx, args[1:], true)
	case "remove":
		return tagChange(ctx, args[1:], false)
	default:
		return unknownName("skill tag", args[0], childNames("skill tag"))
	}
}

// tagChange attaches or detaches one tag. **One implementation for the two directions** because the
// parse, the permission story and the two layers' names are identical; only the verb and its sentence
// differ.
func tagChange(ctx context.Context, args []string, attach bool) error {
	public := false
	var positional []string
	for _, arg := range args {
		switch arg {
		case "--public":
			public = true
		default:
			if strings.HasPrefix(arg, "-") {
				return unknownFlag(tagChangePath(attach), arg)
			}
			positional = append(positional, arg)
		}
	}
	if len(positional) != 2 {
		return usagef("%s", usageAt(tagChangePath(attach)))
	}
	address, name := positional[0], positional[1]
	if strings.TrimSpace(name) == "" {
		return usagef("%s", usageAt(tagChangePath(attach)))
	}
	if err := checkBareAddress(address); err != nil {
		return err
	}

	// Said in the sentence rather than left to `--public` being remembered: the two layers are
	// different promises — one is everybody's, one is only yours.
	label := "「" + name + "」"
	if public {
		label = "公开标签「" + name + "」"
	}

	change, err := authenticated(ctx, func(client api.Client) (api.Change, error) {
		if attach {
			return client.AttachTag(ctx, address, name, public)
		}
		return client.DetachTag(ctx, address, name, public)
	})
	if err != nil {
		return v14Error(err)
	}

	if !attach {
		fmt.Printf("已从 %s 摘掉%s。\n", address, label)
		return nil
	}
	if !change.Changed {
		fmt.Printf("已经贴过%s了，没有需要再做的：%s\n", label, address)
		return nil
	}
	fmt.Printf("已把%s贴到 %s。\n", label, address)
	return nil
}

func categoryVerb(ctx context.Context, args []string) (err error) {
	if len(args) == 0 {
		return usagef("%s", usageAt("skill category set"))
	}
	// The command's own address is settled once, here: a parser deep inside has no way to know which
	// verb reached it (`--offset` serves both `read` and `invoke`), and every usage error has to end
	// at the help of the command that was actually typed (PRD 做 #4).
	defer func() { err = retargetUsage(err, "skill category "+args[0]) }()
	switch args[0] {
	case "set":
		return categorySet(ctx, args[1:])
	case "clear":
		return categoryClear(ctx, args[1:])
	default:
		return unknownName("skill category", args[0], childNames("skill category"))
	}
}

// categorySet replaces a skill's shelf. **Replace, not append** (ADR 0078): a skill is on one shelf,
// and a second `set` is how a person moves it.
func categorySet(ctx context.Context, args []string) error {
	var positional []string
	for _, arg := range args {
		switch arg {
		default:
			if strings.HasPrefix(arg, "-") {
				return unknownFlag("skill category set", arg)
			}
			positional = append(positional, arg)
		}
	}
	if len(positional) != 2 {
		return usagef("%s", usageAt("skill category set"))
	}
	address, name := positional[0], positional[1]
	if err := checkBareAddress(address); err != nil {
		return err
	}

	change, err := authenticated(ctx, func(client api.Client) (api.Change, error) {
		// **The command line takes the word; the route takes its key.** Resolving here is also what
		// turns a typo into a sentence naming the vocabulary instead of a 400 from the server about a
		// key nobody typed.
		key, found, err := categoryKey(ctx, client, name)
		if err != nil {
			return api.Change{}, err
		}
		if !found {
			return api.Change{}, fmt.Errorf("词表里没有「%s」这个分类。看词表：skmr skills categories。", name)
		}
		return client.SetCategory(ctx, address, key)
	})
	if err != nil {
		return v14Error(err)
	}
	if !change.Changed {
		fmt.Printf("已经是这个分类，没有需要再做的：%s\n", address)
		return nil
	}
	fmt.Printf("已把 %s 放到「%s」。\n", address, name)
	return nil
}

func categoryClear(ctx context.Context, args []string) error {
	address := ""
	for _, arg := range args {
		switch arg {
		default:
			if strings.HasPrefix(arg, "-") {
				return unknownFlag("skill category clear", arg)
			}
			if address != "" {
				return usagef("%s", usageAt("skill category clear"))
			}
			address = arg
		}
	}
	if address == "" {
		return usagef("%s", usageAt("skill category clear"))
	}
	if err := checkBareAddress(address); err != nil {
		return err
	}

	change, err := authenticated(ctx, func(client api.Client) (api.Change, error) {
		return client.ClearCategory(ctx, address)
	})
	if err != nil {
		return v14Error(err)
	}
	if !change.Changed {
		fmt.Printf("本来就没有分类，没有需要再做的：%s\n", address)
		return nil
	}
	fmt.Printf("已清除 %s 的分类。\n", address)
	return nil
}

// ---------------------------------------------------------------------------
// Shared vocabulary lookups and sentences
// ---------------------------------------------------------------------------

// categoryKey resolves the one word a write takes to the key the route takes.
//
// **Only the write needs this.** `skill category set` addresses a shelf by key (the API's
// `CategoryBody`), while the listing filters take names and the server resolves them; a resolution
// helper shared by both would be one rule with two different inputs.
func categoryKey(ctx context.Context, client api.Client, name string) (string, bool, error) {
	rows, err := client.Categories(ctx)
	if err != nil {
		return "", false, err
	}
	for _, row := range rows {
		if row.Name == name {
			return row.Key, true, nil
		}
	}
	return "", false, nil
}

// tagNames is the public names in the order the vocabulary uses, which inside one skill is simply by
// name (each word appears at most once).
func tagNames(tags []api.Tag) []string {
	names := make([]string, 0, len(tags))
	for _, tag := range tags {
		names = append(names, tag.Name)
	}
	sort.Strings(names)
	return names
}

func myTagNames(tags []api.MyTag) []string {
	names := make([]string, 0, len(tags))
	for _, tag := range tags {
		names = append(names, tag.Name)
	}
	sort.Strings(names)
	return names
}

// myTagsUnreadable says why the private group is not there. **Not the same sentence for the two
// reasons**: 「不在库」 is something a person can fix with `skill join`, while 「休眠」 is a state
// that comes back on its own once the entry is visible again.
func myTagsUnreadable(reason *string) string {
	if reason != nil {
		switch *reason {
		case "not_in_library":
			return "不在你的库里，所以读不到（我的标签只属于库）。"
		case "dormant":
			return "这个 skill 的库条目现在不生效（可能已下线或暂时不可见），暂时不显示。"
		}
	}
	return "这一组读不到。"
}

// checkBareAddress refuses an address this command cannot use. **The version pin is refused rather
// than dropped**: every v14 command acts on the skill, and a pin here would be a version nobody
// honoured.
func checkBareAddress(address string) error {
	if _, err := api.SkillPath(address); err != nil {
		return usagef("%s", err)
	}
	if strings.Contains(address, "@") {
		return usagef("这个命令针对整个 skill，地址后面不要带 @版本。")
	}
	return nil
}

// v14DenialMessage is the one 403 whose answer is not "get another credential": writing a private
// tag to a skill the caller can see but has not added to their library. The server puts the next
// step in the envelope's message and marks the case in `details.reason`; this reads both.
//
// **`not_in_library` only.** Every other 403 keeps the v4 sentences, which are about the credential
// and the account — and `insufficient_scope` in particular must not be answered with a join command.
func v14DenialMessage(err error) (string, bool) {
	var denial *api.Denial
	if !errors.As(err, &denial) {
		return "", false
	}
	if denial.Details["reason"] != "not_in_library" {
		return "", false
	}
	if denial.Message != "" {
		return denial.Message, true
	}
	return "这个 skill 不在你的库里，我的标签只能贴在库里的 skill 上。" +
		"要加入：skmr skill join <地址> --yes。", true
}

// v14Error turns the four new refusal codes into the sentences the PRD promises, and leaves every
// other failure as it came.
//
// **The codes are read rather than the status**, because 409 is three different situations — already
// in your library, not an added reference, entry asleep — and one sentence for all three would tell
// somebody to fix the wrong thing.
func v14Error(err error) error {
	if err == nil {
		return nil
	}
	var server *api.ServerError
	if !errors.As(err, &server) {
		// `metadata` is an older read and answers 404 with a bare `ErrNotFound`; the sentence should
		// not depend on which endpoint this command happened to call first.
		if errors.Is(err, api.ErrNotFound) {
			return errors.New("找不到这个 skill，或者你看不到它。")
		}
		return err
	}
	// **Compared without case** because the wire spelling is lower case like every other code in
	// the envelope, while an older build of this client compared upper case. Accepting both keeps
	// the sentence right against either, and costs nothing.
	switch strings.ToUpper(server.Code) {
	case api.CodeSkillNotJoinable:
		return errors.New("这个 skill 现在加入不了：它可能已经在你的库里（你创建的或共享给你的），" +
			"也可能已下线、或还没有可用的版本。看它现在是什么：skmr skills list --verbose。")
	case api.CodeLibraryReferenceMissing:
		return errors.New("这个 skill 没有可移除的加入引用。" +
			"只有「我加入的」那条引用能移；共享来源但引用仍在时也可以移，你创建的则不在其列。")
	case api.CodeLibraryEntryDormant:
		return errors.New("这个 skill 的库条目现在不生效（可能已下线或暂时不可见），" +
			"暂时读写不了它的我的标签。恢复可见后再试。")
	case api.CodeTagNotAttached:
		return errors.New("这个 skill 上没有这个标签，没有可摘的。看它有哪些：skmr skill tags <地址>。")
	case api.CodeTagDefinitionNotFound:
		return errors.New("这个标签定义不在你的账号里了——它可能已经被删掉。" +
			"看你的标签：skmr skills my-tags。")
	case "SKILL_NOT_DELETED":
		return errors.New("这个 skill 没有被删除，不需要恢复。")
	case "SKILL_NOT_FOUND":
		return errors.New("找不到这个 skill，或者你看不到它。")
	}
	if server.Status == http.StatusNotFound {
		return errors.New("找不到这个 skill，或者你看不到它。")
	}
	if server.Code == "invalid_request" {
		for _, issue := range issueValues(server.Details) {
			if message, known := issueMessages[issue]; known {
				return errors.New(message)
			}
		}
	}
	// **Return the ServerError, not its raw message**: its Error() carries the sentences for the
	// codes that used to reach this path (skill_offline, version_revoked). Returning only
	// `server.Message` would print the server's English even though the CLI has a sentence for it.
	return server
}

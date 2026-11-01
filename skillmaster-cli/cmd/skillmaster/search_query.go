// Parsing the skills list/search query string and its exclusions.

package main

import (
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"slices"
	"strconv"
	"strings"
)

// queryArgs is the parsed form of either command's line, where `search` records that its args came
// from the public half.
//
// One type for both because they share seven flags and every rule about repetition; the differences
// are which flags are refused and which question the request asks.
type queryArgs struct {
	search bool
	query  string
	all    bool
	// scope is where the search looks (v19): library by default, public, or all. `skills list`
	// does not read it — a listing is the library by definition.
	scope string
	// semantic is the tri-state the wire's `semantic` parameter has. **Nil omits the parameter, and
	// the server treats an absent parameter as off** — the route also serves v14 listings that must
	// keep their meaning. The product default is still semantic because `parseQueryArgs` sets this
	// to true for `skills search`; dropping that default would silently turn the feature off with no
	// visible symptom.
	semantic *bool
	limit    int
	// limitClamped records that --limit asked for more than the ceiling, so the command can say
	// it truncated instead of silently returning fewer rows than were asked for.
	limitClamped bool
	verbose      bool
	noLogin      bool
	namespaces   []string
	categories   []string
	myTags       []string
	publicTags   []string
	sources      []string
	// excludes are the addresses the caller has already seen (v30), deduplicated and in the order
	// they were typed. Only `skills search` accepts them.
	excludes []string
	sort     string
	show     string
	// note is a sentence to print instead of a request — used by `--show deleted` with a source that
	// cannot apply. It is an answer (exit 0), not a refusal.
	note string
}

func parseQueryArgs(args []string, search bool) (queryArgs, error) {
	path := queryPath(search)
	options := queryArgs{search: search, sort: "recent", show: "live"}
	if search {
		options.sort, options.show = "", ""
		options.scope = api.ScopeLibrary
		options.limit = searchPageSize
		// PRD #13: a search is semantic unless told otherwise. It travels explicitly because the
		// server treats the parameter as opt-in — the route is shared with listings that predate
		// v19 and must keep their meaning.
		on := true
		options.semantic = &on
	}
	var words []string

	value := func(i int, flag string) (string, error) {
		return flagValue(args, i, flag, path)
	}

	for i := 0; i < len(args); i++ {
		// `--semantic=false` is the one flag whose value is attached, because it is a switch rather
		// than a value: `--semantic false` would read as the word "false" being the question.
		if strings.HasPrefix(args[i], "--semantic=") {
			if !search {
				return queryArgs{}, usagef("skills list 不看 --semantic；列表不是检索。%s", usageAt(path))
			}
			switch args[i] {
			case "--semantic=true":
				on := true
				options.semantic = &on
			case "--semantic=false":
				off := false
				options.semantic = &off
			default:
				return queryArgs{}, usagef("--semantic 只接受 =true 或 =false，收到 %q。%s", args[i], usageAt(path))
			}
			continue
		}
		switch args[i] {
		case "--all":
			if search {
				return queryArgs{}, usagef("skills search 不接受 --all；检索只取一页。%s", usageAt(path))
			}
			// **Accepted, and no longer needed** (v31): the listing reads every page with or without
			// it, and 1.x does not remove a flag a caller may already send (RELEASE.md §1). The field
			// is kept parsed so `--all` stays a valid word; nothing reads it.
			options.all = true
		case "--scope":
			if !search {
				return queryArgs{}, usagef("skills list 不看 --scope；列表就是你的库。%s", usageAt(path))
			}
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			if !slices.Contains([]string{api.ScopeLibrary, api.ScopePublic, api.ScopeAll}, item) {
				return queryArgs{}, usagef("--scope 只接受 library、public 或 all，收到 %q。%s", item, usageAt(path))
			}
			options.scope = item
			i++
		case "--limit":
			if !search {
				return queryArgs{}, usagef("skills list 不接受 --limit；它按一页 50 条读。%s", usageAt(path))
			}
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			number, convErr := strconv.Atoi(item)
			if convErr != nil || number < 1 {
				return queryArgs{}, usagef("--limit 要给一个正整数，收到 %q。%s", item, usageAt(path))
			}
			// Clamped rather than refused, and said out loud when it happens (PRD #4): asking for
			// more is a smaller answer, not a mistake; asking for zero is a mistake.
			if number > searchPageLimit {
				number = searchPageLimit
				options.limitClamped = true
			}
			options.limit = number
			i++
		case "--semantic":
			if !search {
				return queryArgs{}, usagef("skills list 不看 --semantic；列表不是检索。%s", usageAt(path))
			}
			on := true
			options.semantic = &on
		case "--verbose":
			options.verbose = true
		case "--no-login":
			options.noLogin = true
		case "--namespace":
			if search {
				return queryArgs{}, usagef("skills search 不接受 --namespace；用 --scope 选范围。%s", usageAt(path))
			}
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			options.namespaces = append(options.namespaces, item)
			i++
		case "--category":
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			options.categories = append(options.categories, item)
			i++
		case "--my-tag":
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			options.myTags = append(options.myTags, item)
			i++
		case "--public-tag":
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			options.publicTags = append(options.publicTags, item)
			i++
		case "--exclude":
			if !search {
				return queryArgs{}, usagef("skills list 不接受 --exclude；它只用于检索。%s", usageAt(path))
			}
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			// Both spellings are accepted — `--exclude a/b,c/d` and a repeat of the flag — because
			// the caller has a list either way and neither shape is more natural than the other.
			for _, address := range strings.Split(item, ",") {
				address = strings.TrimSpace(address)
				if address == "" {
					continue
				}
				if !isExcludableAddress(address) {
					return queryArgs{}, usagef("--exclude 要写 <命名空间>/<名字>，收到 %q。%s",
						address, usageAt(path))
				}
				options.excludes = append(options.excludes, address)
			}
			i++
		case "--source":
			if search {
				return queryArgs{}, usagef("skills search 不接受 --source；公域没有库来源。%s", usageAt(path))
			}
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			if !slices.Contains([]string{"created", "shared", "joined"}, item) {
				return queryArgs{}, usagef("--source 只接受 created、shared、joined，收到 %q。%s", item, usageAt(path))
			}
			options.sources = append(options.sources, item)
			i++
		case "--sort":
			if search {
				return queryArgs{}, usagef("skills search 不接受 --sort；公域检索只有相关度。%s", usageAt(path))
			}
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			if item != "recent" && item != "name" {
				return queryArgs{}, usagef("--sort 只接受 recent 或 name，收到 %q。%s", item, usageAt(path))
			}
			options.sort = item
			i++
		case "--show":
			if search {
				return queryArgs{}, usagef("skills search 不接受 --show；公域只有可发现的 skill。%s", usageAt(path))
			}
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			if !slices.Contains([]string{"live", "draft", "offline", "deleted", "all"}, item) {
				return queryArgs{}, usagef("--show 只接受 live、draft、offline、deleted 或 all，收到 %q。%s",
					item, usageAt(path))
			}
			options.show = item
			i++
		default:
			if strings.HasPrefix(args[i], "-") {
				return queryArgs{}, unknownFlag(queryPath(search), args[i])
			}
			words = append(words, args[i])
		}
	}
	options.query = strings.TrimSpace(strings.Join(words, " "))

	if options.show == "deleted" {
		// The deleted view is the author's own rows: three tag filters cannot apply to it, and a
		// source other than created describes a row that is not there (PRD §CLI 命令面 列表视图).
		if len(options.categories) > 0 || len(options.myTags) > 0 || len(options.publicTags) > 0 {
			return queryArgs{}, usagef("--show deleted 不看标签：去掉 --category / --my-tag / --public-tag。%s", usageAt(path))
		}
		for _, source := range options.sources {
			if source != "created" {
				options.note = "「已删除」只含我创建的 skill，--source 只接受 created；这次没有结果。"
				break
			}
		}
	}
	if search && options.query == "" && len(options.categories) == 0 && len(options.publicTags) == 0 &&
		len(options.myTags) == 0 {
		return queryArgs{}, usagef("skills search 要给关键词、--category、--public-tag 或 --my-tag 至少一个。%s", usageAt(path))
	}
	// `all` is the union of two result sets and only a semantic search can answer it — and only
	// around a question, because a filter alone has no neighbour to look for. Both refusals are
	// here so the caller gets a usage error (exit 2) instead of a server 400 arriving as exit 1.
	if search && options.scope == api.ScopeAll && options.semantic != nil && !*options.semantic {
		return queryArgs{}, usagef("--scope all 只有语义检索能答；去掉 --semantic=false。%s", usageAt(path))
	}
	if search && options.scope == api.ScopeAll && options.query == "" {
		return queryArgs{}, usagef("--scope all 要一句问题；只有分类或标签时用 --scope library 或 public。%s", usageAt(path))
	}
	// The public plane has no private tags, on either path. The server refuses it too; catching it
	// here is what makes it a usage error rather than a 400 from the network.
	if search && options.scope == api.ScopePublic && len(options.myTags) > 0 {
		return queryArgs{}, usagef("--scope public 不接受 --my-tag；我的标签只属于你的库。%s", usageAt(path))
	}
	// Deduplicated after parsing, so `--exclude a/b --exclude a/b,c/d` sends each address once and
	// the ceiling counts what will actually travel. The server enforces the same number.
	options.excludes = uniqueAddresses(options.excludes)
	if len(options.excludes) > maxExcludeAddresses {
		return queryArgs{}, usagef("--exclude 最多 %d 个地址，收到 %d 个。%s",
			maxExcludeAddresses, len(options.excludes), usageAt(path))
	}
	return options, nil
}

// maxExcludeAddresses is the ceiling on --exclude, the same number the server enforces.
const maxExcludeAddresses = 50

// isExcludableAddress reports whether s is one skill address: exactly one slash, both sides
// non-empty, no version and no whitespace. **A file path or a pinned address is refused rather than
// reinterpreted** — `--exclude a/b@1.0.0` would otherwise exclude nothing and say nothing, which is
// the silent no-op the flag exists to prevent.
func isExcludableAddress(s string) bool {
	if strings.ContainsAny(s, "@ \t") {
		return false
	}
	slash := strings.Index(s, "/")
	return slash > 0 && slash < len(s)-1 && !strings.Contains(s[slash+1:], "/")
}

// uniqueAddresses keeps the first occurrence of each address and its position.
func uniqueAddresses(in []string) []string {
	if len(in) == 0 {
		return nil
	}
	seen := make(map[string]struct{}, len(in))
	out := make([]string, 0, len(in))
	for _, address := range in {
		if _, ok := seen[address]; ok {
			continue
		}
		seen[address] = struct{}{}
		out = append(out, address)
	}
	return out
}

// queryOptionsFrom is the request the command line describes.
//
// **The filter names travel as typed.** The API plane takes the words a person types and resolves
// them itself — categories, private-tag definitions and public-tag keys all have one server-side
// reader (TD §接口, `LibraryListUseCase.api` / `SearchPublicSkillsUseCase.api`). Resolving them here
// would be a second implementation of that rule, and a client that sent a key where a name was
// expected would ask a filter that selects nothing.
func queryOptionsFrom(options queryArgs) api.QueryOptions {
	query := api.QueryOptions{
		Scope:      api.ScopeLibrary,
		Query:      options.query,
		Namespaces: options.namespaces,
		Categories: options.categories,
		MyTags:     options.myTags,
		PublicTags: options.publicTags,
		Sources:    options.sources,
		Sort:       options.sort,
		Show:       options.show,
		Excludes:   options.excludes,
	}
	if options.search {
		query.Scope = options.scope
		query.Semantic = options.semantic
		query.Limit = options.limit
	}
	return query
}

// filterHints are what an empty result adds when a filter was in play: the name may simply have
// matched nothing, and the command that prints the vocabulary is where the next attempt starts.
//
// **Said even for a name that does exist**, because the CLI cannot tell the two apart: the server
// answers an unknown name with an empty page by design (a filter narrows; it does not fail), and a
// word that is in the vocabulary can also match nothing. The pointer is true in both cases.
func filterHints(options queryArgs) []string {
	var hints []string
	if len(options.categories) > 0 {
		hints = append(hints, "（分类的名字以 skmr skills categories 为准。）")
	}
	if len(options.myTags) > 0 {
		hints = append(hints, "（我的标签的名字以 skmr skills my-tags 为准。）")
	}
	if len(options.publicTags) > 0 {
		hints = append(hints, "（公开标签的名字以 skmr skills public-tags 为准。）")
	}
	return hints
}

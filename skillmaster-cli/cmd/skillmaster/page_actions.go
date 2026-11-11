// Page actions (ADR 0126): commands that exist, open the page that owns the action, and say in the
// first line that nothing has happened yet. v37 adds the precheck (ADR 0127): one read of the author
// page decides whether this credential could do the thing, so a caller who cannot is told before a
// browser opens rather than after.
//
// **A failed precheck never blocks the page.** No credential, a 404, a broken network — the command
// falls back to opening, because the browser's session is a different credential (ADR 0040) and the
// page is still where the action lives. Only a definitive answer refuses: the server said which
// rung this caller holds, or which state the version is in.

package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"slices"
	"strings"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

// pageSpec names one action a person confirms on the console.
//
// **The rung and the states are the CLI's copy of the page's own rules** (the console's
// `versionActions.ts` and the server's gates). The copy is deliberate and bounded: it decides only
// whether to open a page, never whether anything happens — the server remains the one executor, so
// a stale copy makes a sentence wrong, not a permission wider (ADR 0127).
type pageSpec struct {
	path      string
	action    string
	what      string // follows 「还没执行：」
	rung      string // browse / maintain / admin / owner
	states    []string
	stateHint string
	offline   *bool
}

// precheckBudget is how long the optional precheck may take, end to end (author read, then the
// consumer read when it is needed). Five seconds is a choice, not a measurement: the precheck is
// advice that may be skipped, so a slow network should produce 「没能预检」 quickly rather than hold
// the page for the API's two-minute call timeout.
const precheckBudget = 5 * time.Second

func wantOffline(state bool) *bool { return &state }

var pageSpecs = map[string]pageSpec{
	"version publish": {
		path: "version publish", action: "publish", what: "上线只能在网页上由人确认",
		rung: "maintain", states: []string{"draft", "history"},
		stateHint: "上线只能用在草稿和历史版本上",
	},
	"version rollback": {
		path: "version rollback", action: "rollback", what: "回滚只能在网页上由人确认",
		rung: "maintain", states: []string{"live"},
		stateHint: "回滚只能用在线上版本上",
	},
	"version revoke": {
		path: "version revoke", action: "revoke", what: "下架只能在网页上由人确认",
		rung: "maintain", states: []string{"history"},
		stateHint: "下架只能用在历史版本上",
	},
	"version restore": {
		path: "version restore", action: "restore", what: "重新上架只能在网页上由人确认",
		rung: "maintain", states: []string{"revoked"},
		stateHint: "重新上架只能用在已下架的版本上",
	},
	"skill delete": {
		path: "skill delete", action: "delete", what: "删除只能在网页上由人确认",
		rung: "owner",
	},
	"skill offline": {
		path: "skill offline", action: "offline", what: "下线只能在网页上由人确认",
		rung: "owner", offline: wantOffline(false),
	},
	"skill online": {
		path: "skill online", action: "online", what: "恢复服务只能在网页上由人确认",
		rung: "owner", offline: wantOffline(true),
	},
	"skill visibility": {
		path: "skill visibility", action: "visibility", what: "改可见性只能在网页上由人确认",
		rung: "admin",
	},
	"skills publish": {
		path: "skills publish", action: "publish-batch", what: "批量上线只能在网页上由人确认",
	},
}

// actionNames is what a refusal calls each action. Kept apart from pageSpec.what so a sentence can
// put the action in the middle ("下架只能用在历史版本上").
var actionNames = map[string]string{
	"publish": "上线", "rollback": "回滚", "revoke": "下架", "restore": "重新上架",
	"delete": "删除", "offline": "下线", "online": "恢复服务", "visibility": "改可见性",
	"publish-batch": "批量上线",
}

var rungRanks = map[string]int{"invoke": 0, "browse": 1, "write": 2, "maintain": 3, "admin": 4}

// rungAllows answers with the server's own answer. The author plane reports an owner as `admin`, so
// ownership has to be carried separately (ADR 0127); `owned` also answers every rung by itself.
func rungAllows(rung string, owned bool, required string) bool {
	if required == "" {
		return true
	}
	if required == "owner" {
		return owned
	}
	if owned {
		return true
	}
	return rungRanks[rung] >= rungRanks[required]
}

var rungNames = map[string]string{
	"invoke": "调用", "browse": "浏览", "write": "写入", "maintain": "维护", "admin": "管理",
	"owner": "所有者",
}

var statusNames = map[string]string{
	"draft": "草稿", "live": "线上", "history": "历史版本", "revoked": "已下架", "discarded": "已丢弃",
}

// pageArgs is what a page action's line carries: an address, and on publish a bump choice.
type pageArgs struct {
	skill     string
	pin       string
	address   string
	bump      string
	printOnly bool
	jsonMode  bool
}

func parseVersionPageArgs(spec pageSpec, args []string) (pageArgs, error) {
	parsed, positional, err := parsePageSwitches(spec, args)
	if err != nil {
		return parsed, err
	}
	return bindVersionAddress(spec, parsed, positional)
}

// parsePublishArgs is the one parser that knows `--bump`. **Its own loop rather than a branch in
// the shared one**: the help's "a command only reads the flags it lists" is checked by walking the
// call graph, so a `--bump` literal reachable from rollback would be a flag rollback does not have.
func parsePublishArgs(spec pageSpec, args []string) (pageArgs, error) {
	// `--json` is read before anything can be refused: a script that asked for one shape must get it
	// even when the parse fails (the same pre-scan `version open` does).
	parsed := pageArgs{jsonMode: jsonModeIn(args, "--bump")}
	positional := make([]string, 0, 1)
	seenBump := false
	for i := 0; i < len(args); i++ {
		arg := args[i]
		switch {
		case arg == "--json":
			parsed.jsonMode = true
		case arg == "--print":
			parsed.printOnly = true
		case arg == "--bump":
			if seenBump {
				return parsed, usagef("%s", "--bump 只能给一次。")
			}
			seenBump = true
			if i+1 >= len(args) {
				return parsed, usagef("--bump 后面要跟 patch、minor 或 major。%s", usageAt(spec.path))
			}
			value := args[i+1]
			i++
			if !isBumpKind(value) {
				return parsed, usagef("--bump 只接受 patch、minor、major（%s）。%s", redactOr(value), usageAt(spec.path))
			}
			parsed.bump = value
		case strings.HasPrefix(arg, "-"):
			return parsed, usagef("不认识的参数 %s。%s", namedArgument(arg), usageAt(spec.path))
		default:
			if len(positional) == 1 {
				return parsed, usagef("%s 只接一个地址（收到 %s）。%s", spec.path, namedArgument(arg), usageAt(spec.path))
			}
			positional = append(positional, arg)
		}
	}
	return bindVersionAddress(spec, parsed, positional)
}

func parsePageSwitches(spec pageSpec, args []string) (pageArgs, []string, error) {
	parsed := pageArgs{jsonMode: jsonModeIn(args)}
	positional := make([]string, 0, 1)
	for _, arg := range args {
		switch {
		case arg == "--json":
			parsed.jsonMode = true
		case arg == "--print":
			parsed.printOnly = true
		case strings.HasPrefix(arg, "-"):
			return parsed, positional, usagef("不认识的参数 %s。%s", namedArgument(arg), usageAt(spec.path))
		default:
			if len(positional) == 1 {
				return parsed, positional, usagef("%s 只接一个地址（收到 %s）。%s", spec.path, namedArgument(arg), usageAt(spec.path))
			}
			positional = append(positional, arg)
		}
	}
	return parsed, positional, nil
}

func bindVersionAddress(spec pageSpec, parsed pageArgs, positional []string) (pageArgs, error) {
	if len(positional) == 0 {
		return parsed, usagef("%s", usageAt(spec.path))
	}
	target, err := parseVersionTarget(positional[0])
	if err != nil {
		return parsed, err
	}
	parsed.skill, parsed.pin = target.skill, target.pin
	return parsed, nil
}

func parseSkillPageArgs(spec pageSpec, args []string) (pageArgs, error) {
	parsed := pageArgs{jsonMode: jsonModeIn(args)}
	positional := make([]string, 0, 1)
	for _, arg := range args {
		switch {
		case arg == "--json":
			parsed.jsonMode = true
		case arg == "--print":
			parsed.printOnly = true
		case strings.HasPrefix(arg, "-"):
			return parsed, usagef("不认识的参数 %s。%s", namedArgument(arg), usageAt(spec.path))
		default:
			if len(positional) == 1 {
				return parsed, usagef("%s 只接一个地址（收到 %s）。%s", spec.path, namedArgument(arg), usageAt(spec.path))
			}
			positional = append(positional, arg)
		}
	}
	if len(positional) == 0 {
		return parsed, usagef("%s", usageAt(spec.path))
	}
	address := positional[0]
	if strings.Contains(address, "@") {
		return parsed, usagef("这个命令作用于整个 skill；地址不要带 @版本。%s", usageAt(spec.path))
	}
	if _, err := api.SkillPath(address); err != nil {
		return parsed, &usageError{message: err.Error()}
	}
	parsed.address = address
	return parsed, nil
}

// pageFacts is what the precheck learned. `fromAuthor` marks the author plane's answer, which is the
// only source of a version's state, of `owned`, and of `offline`; the consumer plane answers a rung
// at every tier (ADR 0051), which is why it is the fallback.
type pageFacts struct {
	known      bool // a rung is known
	fromAuthor bool
	rung       string
	owned      bool
	status     string
	offline    bool
	// pending is the 下线中 ramp: still serving, but `skill offline` and `skill online` are both
	// meaningful there — the page switches it back on.
	pending       bool
	everPublished bool
	// pinMissing is the author plane's 404 for a caller who *can* read the skill: the version is not
	// in this caller's visible rows (or does not exist), so its page would say 「没有这一版」.
	pinMissing bool
}

// readPageFacts reads once on the author plane, without starting a login, and — when that plane
// refuses — once on the consumer plane to learn the rung. **Anything else is "unknown"**, not an
// error: the page is still the place the action lives, and a navigation command that refused because
// the network was down would be worse than one that opened a page.
func readPageFacts(ctx context.Context, spec pageSpec, parsed pageArgs) pageFacts {
	// **The optional precheck gets its own budget.** The API's call timeout is two minutes, and two
	// reads under it would leave a page that was supposed to open immediately still closed after
	// four. A timeout lands in "unknown", which opens the page and says the precheck did not finish.
	ctx, cancel := context.WithTimeout(ctx, precheckBudget)
	defer cancel()
	if strings.HasPrefix(spec.path, "version ") || spec.path == "version open" {
		envelope, err := authenticatedWith(ctx, true,
			func(client api.Client) (api.AuthorVersionEnvelope, error) {
				return client.ReadAuthorVersion(ctx, parsed.skill, parsed.pin)
			})
		if err == nil {
			return pageFacts{
				known: true, fromAuthor: true,
				rung: envelope.Skill.Access, owned: envelope.Skill.Owned,
				status: envelope.Version.Status, offline: envelope.Skill.Offline,
				pending:       envelope.Skill.OfflinePendingUntil != "",
				everPublished: envelope.Skill.EverPublished,
			}
		}
		fallback := consumerFacts(ctx, parsed.skill)
		// A 404 from the author plane is only meaningful once the caller can see the skill: below
		// `browse` that plane answers 403, so a 404 means the row is filtered or absent.
		if fallback.known && httpStatus(err) == http.StatusNotFound {
			fallback.pinMissing = true
		}
		return fallback
	}
	return skillFacts(ctx, parsed.address)
}

// skillFacts reads the skill-level facts once, for the actions that act on the whole skill and for
// `skill open`.
func skillFacts(ctx context.Context, address string) pageFacts {
	ctx, cancel := context.WithTimeout(ctx, precheckBudget)
	defer cancel()
	listed, err := authenticatedWith(ctx, true,
		func(client api.Client) (api.AuthorVersionList, error) {
			return client.AuthorVersions(ctx, address, api.AuthorVersionQuery{Limit: 1})
		})
	if err == nil {
		return pageFacts{
			known: true, fromAuthor: true,
			rung: listed.Skill.Access, owned: listed.Skill.Owned,
			offline:       listed.Skill.Offline,
			pending:       listed.Skill.OfflinePendingUntil != "",
			everPublished: listed.Skill.EverPublished,
		}
	}
	return consumerFacts(ctx, address)
}

// consumerFacts asks the plane every rung can read (ADR 0051). Its rung is the caller's own, so a
// rung it reports is the person's; whether the skill is theirs to change is the page's judgement.
// **The three author-only facts stay unknown**, so an action that needs them is left to the page.
func consumerFacts(ctx context.Context, address string) pageFacts {
	if ctx.Err() != nil {
		// The budget is gone (the author read used it): no request would go out anyway, and the
		// answer is the one every other failure gives — unknown.
		return pageFacts{}
	}
	metadata, err := authenticatedWith(ctx, true,
		func(client api.Client) (api.Metadata, error) {
			return client.Metadata(ctx, address, "")
		})
	if err != nil {
		// A 404 here is "this caller cannot see the skill", which ADR 0127 §决定 5 answers by
		// opening the page anyway: the browser's session is what the page will judge them by.
		return pageFacts{}
	}
	return pageFacts{known: true, rung: metadata.Access}
}

// httpStatus is the server's status when the refusal carried one, and 0 for a transport failure.
// The consumer read reports its 404 as the wrapped sentinel rather than a `ServerError`, so both
// shapes answer here.
func httpStatus(err error) int {
	var server *api.ServerError
	if errors.As(err, &server) {
		return server.Status
	}
	if errors.Is(err, api.ErrNotFound) {
		return http.StatusNotFound
	}
	return 0
}

// pageReason says why the page is not being opened, or returns "" when it should be. The code is
// the machine half: a rung refusal and a state refusal are different things to act on.
func pageReason(spec pageSpec, facts pageFacts, address string) (string, string) {
	// **The rung is answered before the version's visibility.** A `browse` caller publishing a draft
	// gets 404 from the author plane (drafts are `write`), which is the same shape as "no such
	// version"; what they actually need is told by the rung they do hold.
	//
	// Ownership is only on the author plane. A consumer rung below `admin` still proves the caller
	// is not the owner; at `admin` the two possibilities (owner, admin grant) cannot be told apart,
	// so the page is opened rather than guessed about.
	if spec.rung == "owner" && !facts.fromAuthor {
		if rungRanks[facts.rung] < rungRanks["admin"] {
			return "insufficient_permission", fmt.Sprintf("%s要%s才能做，你在这个 skill 上是 %s。",
				actionNames[spec.action], rungNames["owner"], rungNames[facts.rung])
		}
		return "", ""
	}
	if !rungAllows(facts.rung, facts.owned, spec.rung) {
		standing := rungNames[facts.rung]
		if facts.owned {
			standing = rungNames["owner"]
		}
		// **Ownership is above the ladder, not a rung on it** (ADR 0047), so it is never called a
		// 「档」: the ladder has five rungs and this is not one of them.
		if spec.rung == "owner" {
			return "insufficient_permission", fmt.Sprintf("%s要%s才能做，你在这个 skill 上是 %s。",
				actionNames[spec.action], rungNames["owner"], standing)
		}
		return "insufficient_permission", fmt.Sprintf("%s 要 %s 档，你在这个 skill 上是 %s。",
			actionNames[spec.action], rungNames[spec.rung], standing)
	}
	if facts.pinMissing {
		return "not_found", "看不到这一版：草稿要写入档、下架或丢弃的要维护档，也可能它不存在。"
	}
	// The version's state and the skill's offline flag are author-plane facts; without them the page
	// is opened and the caller is told the precheck did not complete.
	if !facts.fromAuthor {
		return "", ""
	}
	if len(spec.states) > 0 && !slices.Contains(spec.states, facts.status) {
		return "wrong_version_state", fmt.Sprintf("这一版现在是%s，%s。",
			statusNames[facts.status], spec.stateHint)
	}
	// A ramp is a state of its own: `online` opens the page that cancels it, and that is the
	// command a reader types for 「取消下线」. `offline` must not open a page whose only button
	// does the opposite of the command.
	if spec.offline != nil {
		if !*spec.offline && facts.pending {
			return "wrong_version_state",
				"这个 skill 正在下线中，要取消用：skmr skill online " + address + "。"
		}
		if *spec.offline && facts.pending {
			return "", ""
		}
		if facts.offline != *spec.offline {
			if *spec.offline {
				return "wrong_version_state", "这个 skill 没有下线，恢复服务没有可做的。"
			}
			return "wrong_version_state", "这个 skill 已经下线了，下线没有可做的。"
		}
	}
	return "", ""
}

// pageTarget is the address a page action opens. One builder per kind, and both reuse the commands
// that already own their spelling.
func pageTarget(spec pageSpec, parsed pageArgs) string {
	switch {
	case spec.path == "skills publish":
		return config.WebURL() + "/publish"
	case strings.HasPrefix(spec.path, "version "):
		// The console's address grammar has no `draft:` prefix (ADR 0063), so a legacy spelling is
		// normalised the same way `version open` normalises it. Pasting it unchanged would select no
		// row and the page would say 「没有这一版」.
		return versionWebURL(parsed.skill, normalizedPin(parsed.pin), "actions", parsed.bump)
	default:
		return skillWebURL(parsed.address, "settings", "")
	}
}

// runPageAction is the whole of a page action: parse, read once, decide, open or refuse.
func runPageAction(ctx context.Context, spec pageSpec, args []string) error {
	var parsed pageArgs
	var err error
	if strings.HasPrefix(spec.path, "version ") {
		parsed, err = parseVersionPageArgs(spec, args)
	} else {
		parsed, err = parseSkillPageArgs(spec, args)
	}
	if err != nil {
		return versionErr(parsed.jsonMode, err, "")
	}
	return executePageAction(ctx, spec, parsed)
}

// versionPublish is `version publish`, which is the only page action with a choice of number.
func versionPublish(ctx context.Context, args []string) error {
	spec := pageSpecs["version publish"]
	parsed, err := parsePublishArgs(spec, args)
	if err != nil {
		return versionErr(parsed.jsonMode, err, "")
	}
	return executePageAction(ctx, spec, parsed)
}

func executePageAction(ctx context.Context, spec pageSpec, parsed pageArgs) error {
	target := pageTarget(spec, parsed)

	facts := readPageFacts(ctx, spec, parsed)
	code, reason := "", ""
	if facts.known {
		code, reason = pageReason(spec, facts, parsed.address)
	}
	if reason != "" {
		return pageRefusal(parsed.jsonMode, code, reason, target)
	}
	return openActionPage(spec, target, parsed, !facts.fromAuthor)
}

// deleteRefusal says why the delete did not happen, in the shape `--json` promised.
func deleteRefusal(jsonMode bool, code, message string) error {
	if jsonMode {
		return &cliFailure{Code: code, Message: message, JSON: true}
	}
	return &refusalError{message: message}
}

// pageRefusal is "the page was not opened, and here is why" — a success of this command's job,
// reported as a refusal so a script stops rather than pretending something happened.
func pageRefusal(jsonMode bool, code, reason, target string) error {
	message := fmt.Sprintf("没有打开页面：%s\n%s", reason, target)
	if jsonMode {
		return &cliFailure{Code: code, Message: message, JSON: true}
	}
	return &refusalError{message: message}
}

// openActionPage opens (or prints) the page and says what has not happened.
//
// **The first line is the sentence, not the address**: unlike `open`, this command's job is to hand
// a person an action, and the one thing that must not be missed is that the action still has to be
// taken there.
func openActionPage(spec pageSpec, target string, parsed pageArgs, unchecked bool) error {
	opened := false
	warning := ""
	if !parsed.printOnly {
		if err := openBrowser(target); err != nil {
			warning = fmt.Sprintf("没能自动打开浏览器（地址在上面，也可以自己打开）：%v", err)
		} else {
			opened = true
		}
	}
	if warning != "" {
		fmt.Fprintln(os.Stderr, warning)
	}
	if parsed.jsonMode {
		return writeJSON(struct {
			Action    string `json:"action"`
			Performed bool   `json:"performed"`
			URL       string `json:"url"`
			Opened    bool   `json:"opened"`
		}{spec.action, false, target, opened})
	}
	fmt.Printf("还没执行：%s。\n", spec.what)
	fmt.Println(target)
	switch {
	case opened:
		fmt.Println("（已在浏览器里打开。）")
	case parsed.printOnly:
		fmt.Println("（--print：只打印地址，没有打开浏览器。）")
	default:
		fmt.Println("（没能自动打开浏览器；地址就在上面。）")
	}
	if unchecked {
		fmt.Fprintln(os.Stderr, "（没能预检：读不到作者页（没有可用凭据、网络失败，或这把凭据的范围不够）；页面会按你的登录状态显示。）")
	}
	return nil
}

// skillsPublish is the collection-level page action: no address, no precheck.
func skillsPublish(ctx context.Context, args []string) error {
	_ = ctx
	spec := pageSpecs["skills publish"]
	parsed := pageArgs{jsonMode: jsonModeIn(args)}
	for _, arg := range args {
		switch {
		case arg == "--json":
			parsed.jsonMode = true
		case arg == "--print":
			parsed.printOnly = true
		case strings.HasPrefix(arg, "-"):
			return versionErr(parsed.jsonMode, unknownFlag(spec.path, arg), "")
		default:
			return versionErr(parsed.jsonMode,
				usagef("skills publish 不接受参数。%s", usageAt(spec.path)), "")
		}
	}
	return openActionPage(spec, pageTarget(spec, parsed), parsed, false)
}

// skillWebURL is the skill page with an optional tab and sub-pane. Two callers share it — `skill
// open` and the page actions — because a second spelling of this address would drift.
func skillWebURL(address, tab, sub string) string {
	namespace, name, _ := strings.Cut(address, "/")
	target := fmt.Sprintf("%s/skills/%s/%s", config.WebURL(),
		url.PathEscape(namespace), url.PathEscape(name))
	params := url.Values{}
	if tab != "" {
		params.Set("tab", tab)
	}
	if sub != "" {
		params.Set("sub", sub)
	}
	if encoded := params.Encode(); encoded != "" {
		target += "?" + encoded
	}
	return target
}

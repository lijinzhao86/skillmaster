// The `version` group: the author plane an agent may read and manage without a browser.
//
// **What is here is what does not change what a caller can read.** Listing, reading, comparing,
// renaming, annotating and discarding a draft all leave the consumer plane alone; publishing,
// rolling back, revoking, restoring and taking a whole skill offline are deliberately absent from
// this group's help and refused by name (ADR 0031, 0046, 0061). The refusal always carries the exact
// `version open` command, because that is the handover: the person confirms on the page.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/apikey"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/auth"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

const versionUsage = `用法：skillmaster version <子命令>

  list <命名空间>/<名字> [--status <状态>]... [--query <文字>] [--limit <n>] [--all] [--json]
                                          作者视角：线上、草稿、历史版本、已下架、已丢弃（按你的档位）
                                          --status 可重复：live / draft / history / revoked / discarded
                                          调用者问的版本列表见 skillmaster skill versions
  show <命名空间>/<名字>@<版本> [--json]    一版的事实（名称、版本号或版本码、状态、时间、说明…）
  diff <命名空间>/<名字>@<版本> [--from <版本>] [--patch] [--file <路径>] [--max-lines <n>] [--json]
                                          与上一版比较（--from 换基线）；--patch 给统一 diff
                                          （不带 --file 时是整个比较；--file 只看一个路径）
  rename <命名空间>/<名字>@<版本码> --name <名称> [--json]
                                          改草稿的名称
  note <命名空间>/<名字>@<版本码> (--note <文字> | --note-file <文件> | --clear) [--json]
                                          改草稿的说明（完整替换；- 表示从标准输入读）
  discard <命名空间>/<名字>@<版本码> [--yes] [--json]
                                          丢弃草稿；没有 --yes 时只打印后果与完整命令，不执行
  open <命名空间>/<名字>@<版本> [--sub record|diff|actions] [--bump patch|minor|major] [--print] [--json]
                                          打印这一版的网页地址并尝试打开浏览器；不改任何东西
                                          --sub 选落到哪一栏（record 是缺省，不写进地址）
                                          --bump 让上线表单预选一档：只是修正 / 新增能力 / 旧调用会失效
  help, -h, --help                        打印这段说明

写命令必须写明是哪一版。页面专属的动作不在这里：它们改变调用者能读到什么，只能由人在网页上确认。`

// openBrowser is a seam, for the same reason `startLogin` is one: a test must not open a window on
// whoever's screen is in front of the machine.
var openBrowser = auth.OpenBrowser

func versionVerb(ctx context.Context, args []string) error {
	if len(args) == 0 {
		return usagef("%s", versionUsage)
	}
	switch args[0] {
	case "list":
		return versionList(ctx, args[1:])
	case "show":
		return versionShow(ctx, args[1:])
	case "diff":
		return versionDiff(ctx, args[1:])
	case "rename":
		return versionRename(ctx, args[1:])
	case "note":
		return versionNote(ctx, args[1:])
	case "discard":
		return versionDiscard(ctx, args[1:])
	case "open":
		return versionOpen(ctx, args[1:])
	case "publish", "rollback", "revoke", "restore", "offline", "online", "delete":
		return pageOnlyVerb(args[0], args[1:])
	case "help", "-h", "--help":
		fmt.Fprintln(os.Stderr, versionUsage)
		return nil
	default:
		// A machine contract covers a mistyped verb too: `--json` gets one object on stderr, not a
		// usage line a script cannot parse.
		return versionErr(hasJSONFlag(args), usagef("未知的 version 子命令 %s\n%s", namedArgument(args[0]), versionUsage), "")
	}
}

// pageOnlyVerb refuses one of the actions that changes what a caller reads.
//
// **The refusal is the whole point, and it names the way forward.** Hiding the verb from help is not
// enough: an agent that has seen the word in the web UI will try it, and the useful answer is "a
// person confirms this, here is the page". The full `version open` command is printed so it can be
// copied into a message to that person.
func pageOnlyVerb(verb string, args []string) error {
	address := ""
	bump := ""
	seenBump := false
	badArgument := ""
	extraAddress := ""
	jsonMode := jsonModeIn(args, "--bump")
	for i := 0; i < len(args); i++ {
		switch arg := args[i]; {
		case arg == "--json":
			jsonMode = true
		case arg == "--bump":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("%s", "--bump 后面要跟 patch、minor 或 major。"), "")
			}
			value := args[i+1]
			i++
			// Only 上线 picks a number; the other six verbs take none at all.
			if verb != "publish" {
				return versionErr(jsonMode, usagef("只有 publish 选版本号，%s 不接受 --bump。", verb), "")
			}
			if !isBumpKind(value) {
				return versionErr(jsonMode, usagef("--bump 只接受 patch、minor 或 major（%s）。", redactOr(value)), "")
			}
			if seenBump {
				return versionErr(jsonMode, usagef("%s", "--bump 只能给一次。"), "")
			}
			seenBump = true
			bump = value
		case strings.HasPrefix(arg, "-"):
			// Keep parsing: the refusal below needs the address to build the handoff, and a second
			// bad token should not hide the first.
			if badArgument == "" {
				badArgument = namedArgument(arg)
			}
		case address != "":
			if extraAddress == "" {
				extraAddress = arg
			}
		default:
			address = arg
		}
	}
	if address == "" {
		address = "<命名空间>/<名字>@<版本>"
	}
	// The ADR sentence is part of the message for the same reason as `discard`'s consequence: this
	// refusal is not a permission failure, so a hint would be dropped in `--json` mode.
	//
	// The last line points at the page that owns the action (ADR 0072): the version's own page for
	// publish / rollback / revoke / restore, and the skill's settings page for offline / online /
	// delete, which are skill-level and live in its danger zone. The version-level handoff also names
	// the pane (ADR 0074): all four actions live in 操作, not in the default 概览.
	if badArgument != "" {
		return versionErr(jsonMode, usagef("不认识的参数 %s。%s 只能在网页上由人确认；交接用 %s。",
			badArgument, verb, handoffCommand(verb, address, bump)), "")
	}
	if extraAddress != "" {
		// `namedArgument` redacts a credential pasted one token too far: the message is written to
		// stderr, and a key in a CI log is the same leak as a key in a chat message.
		return versionErr(jsonMode, usagef("%s 只接一个地址（收到 %s）。交接用 %s。",
			verb, namedArgument(extraAddress), handoffCommand(verb, address, bump)), "")
	}
	return versionErr(jsonMode, usagef("%s 会改变调用者能读到什么，只能在网页上由人确认，CLI 不执行。"+
		"\n这些动作按 ADR 0031 / 0046 / 0061 只在浏览器面存在。\n%s", verb, handoffLine(verb, address, bump)), "")
}

// handoffCommand is the copyable command for the page that owns this verb. A credential typed where
// an address belongs is not echoed back: the handoff is meant to be pasted and run, and a key in it
// would be both useless and a leak.
func handoffCommand(verb, address, bump string) string {
	// A prefix match is not enough: a namespace may legitimately start with `skm_`. Only a token
	// that cannot be parsed as one of our addresses is treated as a credential pasted by mistake.
	if apikey.IsKey(address) {
		skill, _, _ := strings.Cut(address, "@")
		if _, err := api.SkillPath(skill); err != nil {
			address = apikey.InAnArgument
		}
	}
	if verb == "offline" || verb == "online" || verb == "delete" {
		skill := address
		if at := strings.Index(skill, "@"); at >= 0 {
			skill = skill[:at]
		}
		return fmt.Sprintf("skillmaster skill open %s --tab settings", skill)
	}
	command := fmt.Sprintf("skillmaster version open %s --sub actions", address)
	if bump != "" {
		command += " --bump " + bump
	}
	return command
}

// handoffLine is the sentence the refusal ends with: the command plus where it lands.
func handoffLine(verb, address, bump string) string {
	switch verb {
	case "offline", "online", "delete":
		return "到这个 skill 的设置页去做：" + handoffCommand(verb, address, bump)
	default:
		return "到那一版自己的页面上去做：" + handoffCommand(verb, address, bump)
	}
}

// isBumpKind guards the three choices the publish form draws. The value is carried into the address,
// so anything else is refused here rather than silently producing a page with no preselection.
func isBumpKind(value string) bool {
	return value == "patch" || value == "minor" || value == "major"
}

// redactOr is `namedArgument` without the quoting: a value inside a sentence, not a token at the
// command line. A credential pasted as a flag value is replaced; anything else is shown as written.
func redactOr(value string) string {
	if apikey.IsKey(value) {
		return apikey.InAnArgument
	}
	return value
}

// ---------------------------------------------------------------------------
// Failures: one shape for people, one for --json
// ---------------------------------------------------------------------------

// cliFailure is an error with the machine-readable half `--json` promises.
type cliFailure struct {
	Code       string
	Message    string
	Hint       string
	ServerCode string
	// Issue is the name the server put in `details[].issue` (e.g. `name_too_long`). It travels
	// beside the code because several refusals share one top-level code (`invalid_request`) and a
	// script needs the specific one (v10 PRD #19).
	Issue string
	JSON  bool
}

func (f *cliFailure) Error() string {
	if f.Hint != "" {
		return f.Message + "\n（" + f.Hint + "）"
	}
	return f.Message
}

type usageError struct{ message string }

func (e *usageError) Error() string { return e.message }

func usagef(format string, args ...any) error {
	return &usageError{message: fmt.Sprintf(format, args...)}
}

// versionErr turns any failure into what the command promised: a sentence on stderr in text mode, or
// the stable `{ok:false,error:{...}}` object in `--json` mode.
func versionErr(jsonMode bool, err error, hint string) error {
	if err == nil {
		return nil
	}
	var existing *cliFailure
	if errors.As(err, &existing) {
		return err
	}
	failure := classifyFailure(err)
	// **The hint explains a refusal of permission, so only that refusal gets it.** Every other
	// failure on the same command used to carry it too: a note that was too long came back as an
	// English sentence with a paragraph about the `write` rung underneath it, which describes a
	// different problem entirely.
	// **`forbidden` only.** `insufficient_scope` also classifies as `insufficient_permission`, and
	// its own sentence already names the missing scope; a rung paragraph under it would describe a
	// different problem. A credential refusal (401) is not a rung refusal at all.
	if hint != "" && failure.Code == "insufficient_permission" && failure.ServerCode == "forbidden" {
		failure.Hint = hint
	}
	failure.JSON = jsonMode
	return failure
}

// versionMessages is what the CLI says for the v6 refusals, in the interface's own voice.
//
// The server's `message` is English and written for an operator reading a log (the same choice the
// browser plane makes in `api/errors.ts`). In text mode that message is the whole answer, so the
// codes the version commands can actually receive are translated here; `server_code` still carries
// the original code for scripts, and an unknown code falls back to the server's sentence.
var versionMessages = map[string]string{
	// The server's 404 is one answer for "no such version", "no such skill" and "not yours" — it
	// does not say which, and neither does this. The sentence is the browser plane's own
	// (`api/errors.ts`), because a person reading either surface is answering the same question.
	"skill_not_found":       "这个地址在你的 skill 里找不到。",
	"draft_name_required":   "这一版需要一个名称，1–40 个字符。",
	"draft_name_too_long":   "名称最多 40 个字符。",
	"draft_name_is_frozen":  "这一版已经不是草稿，名称不能再改。",
	"note_too_long":         "说明最多 200 个字符。",
	"note_is_frozen":        "这一版已经不是草稿，说明不能再改。",
	"invalid_version_state": "这一版目前的状态不能做这个操作。",
	"version_revoked":       "这一版已下架，调用会失败；要恢复读取，先重新上架。",
	"skill_offline":         "这个 skill 已下线，暂时不提供服务。",
	"invalid_request":       "提交的内容有问题，请检查后重试。",
	"too_many_requests":     "请求太频繁了，等一会儿再试。",
	"internal_error":        "服务端出错了，请稍后重试。",
	// The name still belongs to a deleted skill; the only way out is restoring it in the browser
	// (ADR 0071). Said in the interface's voice rather than quoting the server's English.
	"skill_deleted": "这个名字属于一个已删除的 skill；要到网页上恢复它，才能继续用这个名字。",
	// v10's authoring verbs. Their refusals arrive as top-level codes or as `details[].issue`; both
	// are translated here so a person does not get the server's English in a Chinese CLI.
	"skill_already_exists":   "这个名字已经被一个 skill 占着。",
	"content_already_exists": "同样的内容已经有一版了；改点东西再提交。",
	"digest_mismatch":        "这一版刚变过，重读再来。",
}

// issueMessages are the names the server puts in `details[].issue`, which are not codes of their
// own. Read only for a 400 `invalid_request`, which is where every one of them travels.
var issueMessages = map[string]string{
	"no_such_user":                     "没有这个用户。",
	"not_a_base":                       "这一版不能当底稿：底稿必须是线上版本或草稿。",
	"not_above_current_rung":           "申请要高于你现在持有的档位。",
	"owner_has_no_higher_rung":         "你是所有者，上面没有更高的档位。",
	"not_a_rung":                       "档位只有调用、浏览、写入、维护、管理。",
	"required_field_missing":           "缺少必填字段。",
	"name_too_long":                    "名字最多 64 个码点。",
	"name_not_usable_as_a_directory":   "名字里不能带 /。",
	"name_contains_version_separator":  "名字里不能带 @。",
	"name_contains_unaddressable_char": "名字里不能带 % 或 ;。",
	"invalid_format":                   "格式不对。",
	"unexpected":                       "空白底稿不带 digest。",
}

// issueValues reads `details` as the set of issue names it carries. The envelope keys details by
// field, so the values are the names; sorted, because a map has no order and the same refusal must
// print the same name every time.
func issueValues(details map[string]string) []string {
	values := make([]string, 0, len(details))
	for _, value := range details {
		if value != "" {
			values = append(values, value)
		}
	}
	sort.Strings(values)
	return values
}

func classifyFailure(err error) *cliFailure {
	failure := &cliFailure{Message: err.Error()}

	var denial *api.Denial
	if errors.As(err, &denial) {
		failure.Code = "insufficient_permission"
		failure.ServerCode = denial.Code
		return failure
	}
	var refusal *api.Refusal
	if errors.As(err, &refusal) {
		// A 401 is about the credential, not the action; keep the server's own reason so a script
		// can tell an expired key from a revoked one. An empty code means the refusal came from
		// in front of the application — still about the credential, never about the caller's rung,
		// so it must not borrow `insufficient_permission` (which brings a rung hint with it).
		failure.Code = refusal.Code
		if failure.Code == "" {
			failure.Code = "unauthenticated"
		}
		failure.ServerCode = refusal.Code
		return failure
	}
	var server *api.ServerError
	if errors.As(err, &server) {
		failure.ServerCode = server.Code
		// `details[].issue` carries an issue name only on the two "the request itself was wrong"
		// codes. On the others the same slot holds a value (`state: published`, `pin: 1.0.0`), and
		// calling that an issue would make the field mean two things.
		if server.Code == "invalid_request" || server.Code == "invalid_upload" {
			if issues := issueValues(server.Details); len(issues) > 0 {
				failure.Issue = issues[0]
			}
		}
		switch {
		case server.Code != "":
			failure.Code = server.Code
			if message, known := versionMessages[server.Code]; known {
				failure.Message = message
			}
			for _, issue := range issueValues(server.Details) {
				if message, known := issueMessages[issue]; known {
					failure.Message = message
					break
				}
			}
		case server.Status == 404:
			failure.Code = "not_found"
		default:
			failure.Code = "server_error"
		}
		return failure
	}
	var usage *usageError
	if errors.As(err, &usage) {
		failure.Code = "usage"
		return failure
	}
	if errors.Is(err, api.ErrNotFound) {
		failure.Code = "not_found"
		return failure
	}
	// Everything left is a transport failure or a local I/O problem; the design's vocabulary calls
	// that a network failure and a script can still read the message.
	failure.Code = "network"
	return failure
}

// exitError is an answer with a status of its own: `grep`'s no-match is 1 and its failure is 2, and
// the two must not be told apart by reading the message (v7 TD §退出码机制).
type exitError struct {
	code int
	err  error
}

func (e *exitError) Error() string {
	if e.err == nil {
		// A command that already printed one error per file exits without repeating itself; the
		// status is the whole message.
		return "退出码 " + strconv.Itoa(e.code)
	}
	return e.err.Error()
}
func (e *exitError) Unwrap() error { return e.err }

// reportError is what `main` exits through. It returns the process status.
func reportError(err error) int {
	var exited *exitError
	if errors.As(err, &exited) {
		// A nil error is a command that already said what failed, once per file. Printing a summary
		// on top would turn one failure into two sentences — see `cat` and `rm`.
		if exited.err != nil {
			fmt.Fprintf(os.Stderr, "错误：%v\n", exited.err)
		}
		return exited.code
	}
	var failure *cliFailure
	if errors.As(err, &failure) && failure.JSON {
		payload := struct {
			Ok    bool `json:"ok"`
			Error struct {
				Code       string `json:"code"`
				Message    string `json:"message"`
				Hint       string `json:"hint"`
				ServerCode string `json:"server_code,omitempty"`
				Issue      string `json:"issue,omitempty"`
			} `json:"error"`
		}{Ok: false}
		payload.Error.Code = failure.Code
		payload.Error.Message = failure.Message
		payload.Error.Hint = failure.Hint
		payload.Error.ServerCode = failure.ServerCode
		payload.Error.Issue = failure.Issue
		encoded, marshalErr := json.Marshal(payload)
		if marshalErr != nil {
			fmt.Fprintf(os.Stderr, "错误：%v\n", err)
			return 1
		}
		fmt.Fprintln(os.Stderr, string(encoded))
		return 1
	}
	fmt.Fprintf(os.Stderr, "错误：%v\n", err)
	return 1
}

func writeJSON(value any) error {
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(value); err != nil {
		return fmt.Errorf("输出 JSON：%w", err)
	}
	return nil
}

func hasJSONFlag(args []string) bool {
	valueFlags := map[string]bool{
		"--status": true, "--query": true, "--limit": true, "--cursor": true,
		"--from": true, "--file": true, "--max-lines": true, "--name": true,
		"--note": true, "--note-file": true,
	}
	for i := 0; i < len(args); i++ {
		arg := args[i]
		if valueFlags[arg] {
			// The next token is this option's value, even when it happens to spell a flag.
			i++
			continue
		}
		if arg == "--json" {
			return true
		}
	}
	return false
}

// ---------------------------------------------------------------------------
// Addresses and pins
// ---------------------------------------------------------------------------

type versionTarget struct {
	skill string
	pin   string
}

// parseVersionTarget reads `<namespace>/<name>@<pin>`. Every read/write verb under `version` takes
// one: the design refuses "the current one" as a default because a slip would change a different
// version than the one the person was looking at.
func parseVersionTarget(arg string) (versionTarget, error) {
	skill, pin, found := strings.Cut(arg, "@")
	if !found || pin == "" {
		return versionTarget{}, usagef("要写明是哪一版：<命名空间>/<名字>@<版本>。\n%s", versionUsage)
	}
	if _, err := api.SkillPath(skill); err != nil {
		return versionTarget{}, &usageError{message: err.Error()}
	}
	return versionTarget{skill: skill, pin: pin}, nil
}

// parseSkillAddress reads `<namespace>/<name>`, and refuses a pin: a version list is about the whole
// skill, and a pin here would be silently dropped.
func parseSkillAddress(arg string) (string, error) {
	if strings.Contains(arg, "@") {
		return "", usagef("版本列表是针对整个 skill 的，地址后面不要带 @版本。")
	}
	if _, err := api.SkillPath(arg); err != nil {
		return "", &usageError{message: err.Error()}
	}
	return arg, nil
}

// isRowID reports whether a pin is a version row's own id: a v7 eight-character short code, or a
// v6 26-character ULID (ADR 0063).
//
// The alphabet is Crockford's — no I, L, O or U — which is what keeps an eight-character code from
// being confused with a typo of a version number. A ULID's first character additionally encodes the
// timestamp's top bits, so it never exceeds 7; that is the same check the server makes.
func isRowID(pin string) bool {
	if len(pin) == 8 {
		return crockford.MatchString(pin)
	}
	if len(pin) == 26 && crockford.MatchString(pin) {
		return pin[0] <= '7'
	}
	return false
}

var crockford = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]+$`)

// isDraftPin is the row-id question a read asks before choosing a plane. It still accepts the v6
// `draft:` prefix, which an older pin file or an older command line may carry; the canonical
// spelling is the bare id.
func isDraftPin(pin string) bool {
	return strings.HasPrefix(pin, "draft:") || isRowID(pin)
}

func isDigestPin(pin string) bool { return strings.HasPrefix(pin, "sha256:") }

// writablePin is the pin a rename, note or discard may send.
//
// **A digest is refused locally; a number is not.** The draft's identity is its row id, not its
// content hash, so `@sha256:…` would name something this write cannot address. A published or
// revoked *number* goes to the server unchanged: it, not the CLI, decides the frozen/state answer,
// because the same command must give the same reason under every rung.
func writablePin(arg string) (versionTarget, error) {
	target, err := parseVersionTarget(arg)
	if err != nil {
		return versionTarget{}, err
	}
	if isDigestPin(target.pin) || strings.Contains(target.pin, ".") {
		return versionTarget{}, usagef("写只针对草稿；草稿的地址是 @<版本码>，不是版本号或内容摘要。"+
			"先列出版本、找到它的版本码：skillmaster version list %s", target.skill)
	}
	return target, nil
}

// ---------------------------------------------------------------------------
// version list
// ---------------------------------------------------------------------------

var versionStatuses = []string{"live", "draft", "history", "revoked", "discarded"}

type listOptions struct {
	statuses []string
	query    string
	limit    int
	all      bool
	json     bool
}

func versionList(ctx context.Context, args []string) error {
	jsonMode := hasJSONFlag(args)
	address, options, err := parseVersionListArgs(args)
	if err != nil {
		return versionErr(jsonMode, err, "")
	}

	listed, more, err := readAuthorVersions(ctx, address, options)
	if err != nil {
		return versionErr(jsonMode, err, "作者面的版本列表需要「浏览」及以上。")
	}

	if options.json {
		// The cursor is the CLI's, not the caller's: `--all` already decided what to fetch. What the
		// caller does need is whether this is the whole list — a truncated answer that does not say
		// so is a wrong answer to "which versions exist".
		listed.Next = nil
		return writeJSON(struct {
			api.AuthorVersionList
			HasMore bool `json:"has_more"`
		}{listed, more})
	}
	printAuthorVersions(address, listed, more)
	return nil
}

// readAuthorVersions reads one page, or every page when `--all` was asked for.
//
// The pages are gathered before anything is printed, so a request that fails in the middle prints
// no half list. The cursor is only ever a local loop variable; it never reaches stdout or `--json`.
// The same `--status` / `--query` slice goes on every request because the server's cursor carries
// ordering, not the predicate.
func readAuthorVersions(ctx context.Context, address string, options listOptions) (api.AuthorVersionList, bool, error) {
	if !options.all {
		listed, err := authenticated(ctx, func(client api.Client) (api.AuthorVersionList, error) {
			return client.AuthorVersions(ctx, address, api.AuthorVersionQuery{
				Statuses: options.statuses,
				Query:    options.query,
				Limit:    options.limit,
			})
		})
		if err != nil {
			return api.AuthorVersionList{}, false, err
		}
		return listed, listed.Next != nil && *listed.Next != "", nil
	}

	var all api.AuthorVersionList
	cursor := ""
	seen := map[string]struct{}{}
	for {
		page, err := authenticated(ctx, func(client api.Client) (api.AuthorVersionList, error) {
			return client.AuthorVersions(ctx, address, api.AuthorVersionQuery{
				Statuses: options.statuses,
				Query:    options.query,
				Limit:    options.limit,
				Cursor:   cursor,
			})
		})
		if err != nil {
			return api.AuthorVersionList{}, false, err
		}
		all.Skill = page.Skill
		all.Versions = append(all.Versions, page.Versions...)
		all.Counts = page.Counts
		next := ""
		if page.Next != nil {
			next = *page.Next
		}
		if next == "" {
			all.Next = nil
			return all, false, nil
		}
		if _, repeated := seen[next]; repeated {
			return api.AuthorVersionList{}, false, fmt.Errorf(
				"服务端反复给出同一个游标，列表走不下去了（已取到 %d 条）", len(all.Versions))
		}
		seen[next] = struct{}{}
		cursor = next
	}
}

func parseVersionListArgs(args []string) (string, listOptions, error) {
	options := listOptions{limit: 50}
	address := ""

	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--json":
			options.json = true
		case "--status":
			if i+1 >= len(args) {
				return "", options, usagef("--status 后面要跟一个状态：%s", strings.Join(versionStatuses, " / "))
			}
			status := args[i+1]
			if !containsString(versionStatuses, status) {
				return "", options, usagef("--status 只能是 %s，收到 %q",
					strings.Join(versionStatuses, " / "), status)
			}
			options.statuses = append(options.statuses, status)
			i++
		case "--query":
			if i+1 >= len(args) {
				return "", options, usagef("--query 后面要跟一段文字。")
			}
			options.query = args[i+1]
			i++
		case "--limit":
			if i+1 >= len(args) {
				return "", options, usagef("--limit 后面要跟一个 1–200 的数。")
			}
			limit, parseErr := strconv.Atoi(args[i+1])
			if parseErr != nil || limit < 1 || limit > 200 {
				return "", options, usagef("--limit 要是 1–200 之间的整数，收到 %q", args[i+1])
			}
			options.limit = limit
			i++
		case "--all":
			options.all = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return "", options, usagef("不认识的参数 %s。\n%s", namedArgument(args[i]), versionUsage)
			}
			if address != "" {
				return "", options, usagef("version list 只接一个 skill 地址。")
			}
			address = args[i]
		}
	}

	if address == "" {
		return "", options, usagef("%s", versionUsage)
	}
	validated, err := parseSkillAddress(address)
	if err != nil {
		return "", options, err
	}
	return validated, options, nil
}

func printAuthorVersions(address string, listed api.AuthorVersionList, more bool) {
	for _, version := range listed.Versions {
		fmt.Printf("%s@%s\n", address, pinOf(version))
		fmt.Printf("    %s · %s · %s\n", statusLabel(version.Status), versionDisplayName(version),
			firstTen(version.SubmittedAt))
		if version.Note != nil && *version.Note != "" {
			fmt.Printf("    %s\n", *version.Note)
		}
	}
	fmt.Printf("（%d 行；可见 %s）\n", len(listed.Versions), countsLine(listed.Counts))
	if more {
		fmt.Println("（还有更多；用 --all 取全，或用 --status / --query 收窄。）")
	}
}

func countsLine(counts api.AuthorCounts) string {
	parts := []string{fmt.Sprintf("全部 %d", counts.All)}
	for _, part := range []struct {
		label string
		value int
	}{
		{"线上", counts.Live},
		{"草稿", counts.Draft},
		{"历史版本", counts.History},
		{"已下架", counts.Revoked},
		{"已丢弃", counts.Discarded},
	} {
		if part.value > 0 {
			parts = append(parts, fmt.Sprintf("%s %d", part.label, part.value))
		}
	}
	return strings.Join(parts, " · ")
}

func statusLabel(status string) string {
	switch status {
	case "live":
		return "线上"
	case "draft":
		return "草稿"
	case "history":
		return "历史版本"
	case "revoked":
		return "已下架"
	case "discarded":
		return "已丢弃"
	default:
		return status
	}
}

// pinOf is what follows the `@` when a row is printed. The server sends `pin` on every version;
// the two fallbacks are for a build reading a response from a server that has not filled it in yet,
// and they use the same precedence the address grammar does.
func pinOf(version api.AuthorVersion) string {
	if version.Pin != "" {
		return version.Pin
	}
	if version.Version != nil && *version.Version != "" {
		return *version.Version
	}
	return version.Digest
}

func versionDisplayName(version api.AuthorVersion) string {
	if version.Name != nil && *version.Name != "" {
		return *version.Name
	}
	if version.Version != nil && *version.Version != "" {
		return *version.Version
	}
	return "—"
}

func firstTen(value *string) string {
	if value == nil || *value == "" {
		return "—"
	}
	if len(*value) >= 10 {
		return (*value)[:10]
	}
	return *value
}

func stringOrDash(value *string) string {
	if value == nil || *value == "" {
		return "—"
	}
	return *value
}

// ---------------------------------------------------------------------------
// version show
// ---------------------------------------------------------------------------

func versionShow(ctx context.Context, args []string) error {
	jsonMode := hasJSONFlag(args)
	target, err := parseOneTarget(args, "--json")
	if err != nil {
		return versionErr(jsonMode, err, "")
	}

	envelope, err := authenticated(ctx, func(client api.Client) (api.AuthorVersionEnvelope, error) {
		return client.ReadAuthorVersion(ctx, target.skill, target.pin)
	})
	if err != nil {
		return versionErr(jsonMode, err, "作者面的单版读：已发布要「浏览」及以上；草稿要「写入」及以上；已下架与已丢弃要「维护」及以上。")
	}

	if jsonMode {
		return writeJSON(envelope)
	}
	printAuthorVersion(envelope)
	return nil
}

func printAuthorVersion(envelope api.AuthorVersionEnvelope) {
	version := envelope.Version
	address := version.Address
	if address == "" {
		address = envelope.Skill.Address
	}
	fmt.Printf("%s@%s\n", address, pinOf(version))
	fmt.Printf("  名称：%s\n", versionDisplayName(version))
	if version.Version == nil || *version.Version == "" {
		if version.Status == "draft" {
			fmt.Println("  版本号：—（草稿还没有版本号，上线时分配）")
		} else {
			fmt.Println("  版本号：—（这一行没有版本号，按摘要寻址）")
		}
	} else {
		fmt.Printf("  版本号：%s\n", *version.Version)
	}
	// The row id as a value of its own — the same fact the console's 「版本码」 row shows. For a
	// draft it is also the address above; for a published version the address is the number, so this
	// is the only place the terminal can see which row it is looking at.
	fmt.Printf("  版本码：%s\n", stringOrDash(&version.Code))
	fmt.Printf("  状态：%s\n", statusLabel(version.Status))
	fmt.Printf("  提交人：%s · %s\n", stringOrDash(version.SubmittedBy),
		firstTen(version.SubmittedAt))
	fmt.Printf("  首次上线：%s\n", firstTen(version.FirstPublishedAt))
	fmt.Printf("  最近状态变更：%s\n", firstTen(version.StateAt))
	fmt.Printf("  说明：%s\n", stringOrDash(version.Note))
	fmt.Printf("  文件：%d 个 · %d 字节\n", version.Files, version.Bytes)
	fmt.Printf("  摘要：%s\n", version.Digest)
	if version.RevokedReason != nil && *version.RevokedReason != "" {
		fmt.Printf("  已下架原因：%s\n", *version.RevokedReason)
	}
}

// parseOneTarget reads `[--json] <address>` for the verbs that take one address and nothing else.
func parseOneTarget(args []string, optional ...string) (versionTarget, error) {
	address := ""
	for _, arg := range args {
		if arg == "--json" || containsString(optional, arg) {
			continue
		}
		if strings.HasPrefix(arg, "-") {
			return versionTarget{}, usagef("不认识的参数 %s。\n%s", namedArgument(arg), versionUsage)
		}
		if address != "" {
			return versionTarget{}, usagef("只接一个地址。\n%s", versionUsage)
		}
		address = arg
	}
	if address == "" {
		return versionTarget{}, usagef("%s", versionUsage)
	}
	return parseVersionTarget(address)
}

// ---------------------------------------------------------------------------
// version diff
// ---------------------------------------------------------------------------

type diffOptions struct {
	from     string
	file     string
	patch    bool
	maxLines int
	json     bool
}

func versionDiff(ctx context.Context, args []string) error {
	jsonMode := hasJSONFlag(args)
	target, options, err := parseVersionDiffArgs(args)
	if err != nil {
		return versionErr(jsonMode, err, "")
	}
	// **`--patch --file` reads one file, in one request.** Without `--file`, `--patch` asks for the
	// ordinary diff: small comparisons carry every hunk, large ones the first batch. It must not
	// invent a path — a file that did not change is not part of the comparison at all (404), and
	// defaulting to SKILL.md is how a draft that only touched another file becomes an error.
	file := options.file

	diff, err := authenticated(ctx, func(client api.Client) (api.AuthorDiff, error) {
		return client.AuthorDiff(ctx, target.skill, target.pin, options.from, file, "")
	})
	if err != nil {
		var server *api.ServerError
		if file != "" && errors.As(err, &server) && server.Code == "file_not_found" {
			// The named file is not one of the changed files. That is the answer, not a failure:
			// say so the way the page does. **The code, not the status**: the author plane answers
			// 404 for two different things, and `skill_not_found` — no such skill, version or
			// baseline — is a failure that must not read as "nothing changed".
			if jsonMode {
				return writeJSON(emptyPatchJSON(target))
			}
			fmt.Printf("%s@%s\n（没有差异。）\n", target.skill, target.pin)
			return nil
		}
		return versionErr(jsonMode, err, "改动按两侧自己的可见性取最高档：已发布要「浏览」及以上，涉及草稿要「写入」，涉及已下架或已丢弃要「维护」。")
	}

	var lines []string
	remaining := 0
	if options.patch {
		lines, remaining = truncateLines(buildPatch(diff, file), options.maxLines)
	}
	truncated := diff.Truncated || remaining > 0

	if jsonMode {
		return writeJSON(diffJSONOf(diff, lines, truncated))
	}
	if options.patch {
		fmt.Printf("%s@%s\n", target.skill, target.pin)
		for _, line := range lines {
			fmt.Println(line)
		}
		if remaining > 0 {
			fmt.Printf("（还有 %d 行没有显示；--max-lines 调到更大可以看全。）\n", remaining)
		}
		if diff.Truncated {
			fmt.Println("（服务端截断了这份比较；加 --file 只看一个文件。）")
		}
		return nil
	}
	printDiffStat(target, diff, file)
	return nil
}

func parseVersionDiffArgs(args []string) (versionTarget, diffOptions, error) {
	options := diffOptions{maxLines: 2000}
	address := ""

	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--json":
			options.json = true
		case "--patch":
			options.patch = true
		case "--from":
			if i+1 >= len(args) {
				return versionTarget{}, options, usagef("--from 后面要跟一个版本（版本号或 sha256:…）。")
			}
			options.from = args[i+1]
			i++
		case "--file":
			if i+1 >= len(args) {
				return versionTarget{}, options, usagef("--file 后面要跟一个相对路径。")
			}
			options.file = args[i+1]
			i++
		case "--max-lines":
			if i+1 >= len(args) {
				return versionTarget{}, options, usagef("--max-lines 后面要跟一个正整数。")
			}
			value, parseErr := strconv.Atoi(args[i+1])
			if parseErr != nil || value < 1 {
				return versionTarget{}, options, usagef("--max-lines 要是正整数，收到 %q", args[i+1])
			}
			options.maxLines = value
			i++
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionTarget{}, options, usagef("不认识的参数 %s。\n%s", namedArgument(args[i]), versionUsage)
			}
			if address != "" {
				return versionTarget{}, options, usagef("version diff 只接一个地址。")
			}
			address = args[i]
		}
	}

	if address == "" {
		return versionTarget{}, options, usagef("%s", versionUsage)
	}
	target, err := parseVersionTarget(address)
	return target, options, err
}

func buildPatch(diff api.AuthorDiff, only string) []string {
	var lines []string
	for _, file := range diff.Files {
		if only != "" && file.Relpath != only {
			continue
		}
		if file.Hunks == nil {
			if file.Binary {
				lines = append(lines, fmt.Sprintf("（%s 是二进制文件，没有行级差异。）", file.Relpath))
			} else {
				lines = append(lines, fmt.Sprintf("（%s 没有展开：超过大小上限，或者不在这一批里。）", file.Relpath))
			}
			continue
		}
		before, after := "a/"+file.Relpath, "b/"+file.Relpath
		switch file.Status {
		case "added":
			before = "/dev/null"
		case "removed":
			after = "/dev/null"
		}
		lines = append(lines, "--- "+before, "+++ "+after)
		for _, hunk := range file.Hunks {
			lines = append(lines, hunk.Header)
			lines = append(lines, hunk.Lines...)
		}
	}
	if len(lines) == 0 {
		return []string{"没有差异。"}
	}
	return lines
}

func truncateLines(lines []string, max int) ([]string, int) {
	if max <= 0 || len(lines) <= max {
		return lines, 0
	}
	return lines[:max], len(lines) - max
}

type diffJSON struct {
	From      string         `json:"from"`
	To        string         `json:"to"`
	Added     int            `json:"added"`
	Removed   int            `json:"removed"`
	Files     []diffFileJSON `json:"files"`
	Truncated bool           `json:"truncated"`
	Next      *string        `json:"next"`
	Patch     string         `json:"patch"`
}

type diffFileJSON struct {
	Path    string `json:"path"`
	Status  string `json:"status"`
	Binary  bool   `json:"binary"`
	Added   *int   `json:"added"`
	Removed *int   `json:"removed"`
}

func diffJSONOf(diff api.AuthorDiff, patchLines []string, truncated bool) diffJSON {
	result := diffJSON{
		From:      diff.From,
		To:        diff.To,
		Truncated: truncated,
		Next:      diff.Next,
		Patch:     strings.Join(patchLines, "\n"),
		Files:     make([]diffFileJSON, 0, len(diff.Files)),
	}
	for _, file := range diff.Files {
		if file.Added != nil {
			result.Added += *file.Added
		}
		if file.Removed != nil {
			result.Removed += *file.Removed
		}
		result.Files = append(result.Files, diffFileJSON{
			Path:    file.Relpath,
			Status:  file.Status,
			Binary:  file.Binary,
			Added:   file.Added,
			Removed: file.Removed,
		})
	}
	return result
}

// emptyPatchJSON is the machine answer for "the named file did not change": no files, no patch.
func emptyPatchJSON(target versionTarget) diffJSON {
	return diffJSON{
		To:    target.pin,
		Patch: "",
		Files: []diffFileJSON{},
	}
}

func printDiffStat(target versionTarget, diff api.AuthorDiff, only string) {
	added, removed := 0, 0
	for _, file := range diff.Files {
		if file.Added != nil {
			added += *file.Added
		}
		if file.Removed != nil {
			removed += *file.Removed
		}
	}
	baseline := diff.From
	if baseline == "" {
		baseline = "（没有基线）"
	}
	fmt.Printf("%s@%s\n", target.skill, target.pin)
	fmt.Printf("对照：%s\n", baseline)
	fmt.Printf("+%d −%d　%d 个文件\n", added, removed, len(diff.Files))
	for _, file := range diff.Files {
		if only != "" && file.Relpath != only {
			continue
		}
		fmt.Printf("%s  %s  %s\n", diffStatusMark(file.Status), file.Relpath, numstat(file))
	}
	if diff.Truncated {
		fmt.Println("（服务端截断了这份比较；用 --file 只看一个文件。）")
	}
	if diff.Next != nil && *diff.Next != "" {
		fmt.Println("（还有更多文件没有取；--json 里的 next 是续取游标。）")
	}
}

func diffStatusMark(status string) string {
	switch status {
	case "added":
		return "A"
	case "removed":
		return "D"
	case "modified":
		return "M"
	default:
		return "?"
	}
}

func numstat(file api.DiffFile) string {
	if file.Added == nil || file.Removed == nil {
		if file.Binary {
			return "—"
		}
		return "—（没有展开）"
	}
	return fmt.Sprintf("+%d −%d", *file.Added, *file.Removed)
}

// ---------------------------------------------------------------------------
// version rename / note / discard
// ---------------------------------------------------------------------------

func versionRename(ctx context.Context, args []string) error {
	jsonMode := hasJSONFlag(args)
	target, name, err := parseRenameArgs(args)
	if err != nil {
		return versionErr(jsonMode, err, "")
	}

	result, err := authenticated(ctx, func(client api.Client) (api.AuthorWriteResult, error) {
		return client.AuthorRename(ctx, target.skill, target.pin, name)
	})
	if err != nil {
		return versionErr(jsonMode, err, "改草稿需要你是它的提交人（写入档），或者有「维护」及以上。")
	}
	if jsonMode {
		return writeJSON(result)
	}
	fmt.Printf("已改名：%s@%s → 「%s」\n", target.skill, target.pin, name)
	return nil
}

func parseRenameArgs(args []string) (versionTarget, string, error) {
	address, name := "", ""
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--json":
		case "--name":
			if i+1 >= len(args) {
				return versionTarget{}, "", usagef("--name 后面要跟版本名称。")
			}
			name = args[i+1]
			i++
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionTarget{}, "", usagef("不认识的参数 %s。\n%s", namedArgument(args[i]), versionUsage)
			}
			if address != "" {
				return versionTarget{}, "", usagef("version rename 只接一个地址。")
			}
			address = args[i]
		}
	}
	if address == "" {
		return versionTarget{}, "", usagef("%s", versionUsage)
	}
	if strings.TrimSpace(name) == "" {
		return versionTarget{}, "", usagef("要写上新名称：--name <名称>。")
	}
	target, err := writablePin(address)
	return target, name, err
}

func versionNote(ctx context.Context, args []string) error {
	jsonMode := hasJSONFlag(args)
	target, note, err := parseNoteArgs(args)
	if err != nil {
		return versionErr(jsonMode, err, "")
	}

	result, err := authenticated(ctx, func(client api.Client) (api.AuthorWriteResult, error) {
		return client.AuthorNote(ctx, target.skill, target.pin, note)
	})
	if err != nil {
		return versionErr(jsonMode, err, "改草稿需要你是它的提交人（写入档），或者有「维护」及以上。")
	}
	if jsonMode {
		return writeJSON(result)
	}
	if note == "" {
		fmt.Printf("已清空说明：%s@%s\n", target.skill, target.pin)
		return nil
	}
	fmt.Printf("已更新说明：%s@%s → 「%s」\n", target.skill, target.pin, note)
	return nil
}

func parseNoteArgs(args []string) (versionTarget, string, error) {
	address := ""
	note := ""
	mode := ""
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--json":
		case "--clear":
			if mode != "" {
				return versionTarget{}, "", usagef("--note、--note-file 与 --clear 只能给一个。")
			}
			mode = "clear"
		case "--note", "--note-file":
			if mode != "" {
				return versionTarget{}, "", usagef("--note、--note-file 与 --clear 只能给一个。")
			}
			if i+1 >= len(args) {
				return versionTarget{}, "", usagef("%s 后面要跟一个值。", args[i])
			}
			mode = args[i]
			value := args[i+1]
			i++
			if mode == "--note-file" {
				content, err := readNoteSource(value)
				if err != nil {
					return versionTarget{}, "", &usageError{message: err.Error()}
				}
				note = content
			} else if value == "-" {
				content, err := io.ReadAll(os.Stdin)
				if err != nil {
					return versionTarget{}, "", usagef("读标准输入：%v", err)
				}
				note = string(content)
			} else {
				note = value
			}
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionTarget{}, "", usagef("不认识的参数 %s。\n%s", namedArgument(args[i]), versionUsage)
			}
			if address != "" {
				return versionTarget{}, "", usagef("version note 只接一个地址。")
			}
			address = args[i]
		}
	}
	if address == "" {
		return versionTarget{}, "", usagef("%s", versionUsage)
	}
	if mode == "" {
		return versionTarget{}, "", usagef("要给一句说明或清空它：--note <文字>、--note-file <文件> 或 --clear。")
	}
	target, err := writablePin(address)
	return target, note, err
}

// readNoteSource reads `--note-file`, with `-` meaning standard input.
func readNoteSource(path string) (string, error) {
	if path == "-" {
		content, err := io.ReadAll(os.Stdin)
		if err != nil {
			return "", fmt.Errorf("读标准输入：%w", err)
		}
		return string(content), nil
	}
	content, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("读 %s：%w", path, err)
	}
	return string(content), nil
}

func versionDiscard(ctx context.Context, args []string) error {
	jsonMode := hasJSONFlag(args)
	target, yes, err := parseDiscardArgs(args)
	if err != nil {
		return versionErr(jsonMode, err, "")
	}

	command := fmt.Sprintf("skillmaster version discard %s@%s --yes", target.skill, target.pin)
	if !yes {
		// **Nothing is sent, and the exit code is non-zero.** The consequence is the first sentence
		// because it is the one that cannot be undone: the draft is not recoverable.
		if jsonMode {
			// The consequence and the command are in the message rather than the hint: a hint is
			// attached only to a permission refusal, and this one is a usage answer a machine
			// contract still has to carry in full.
			return versionErr(true, usagef("丢弃草稿要确认。这一版无法找回。确认后执行：%s", command), "")
		}
		fmt.Println("丢弃这一版会结束这个草稿：这一版无法找回，也不会占用任何版本号；线上不受影响。")
		fmt.Printf("确认后执行：\n%s\n", command)
		return errors.New("没有执行：丢弃草稿要加 --yes。")
	}

	// **A second discard is an answer, not a failure.** The server treats it as idempotent, so the
	// only way to say "already gone" rather than "just discarded" is to look before acting — and
	// only for a draft pin, where both answers are possible and neither is a different version.
	//
	// **One `authenticated`, not two.** That call reads the credential (and may start a login), so
	// doing the pre-read and the write through it separately read the store twice — two keychain
	// waits on a locked machine, and two browser windows when nobody had logged in yet.
	alreadyDiscarded := false
	preReadInvisible := false
	var before api.AuthorVersionEnvelope
	var result api.AuthorWriteResult
	result, err = authenticated(ctx, func(client api.Client) (api.AuthorWriteResult, error) {
		if !isDraftPin(target.pin) {
			return client.AuthorDiscard(ctx, target.skill, target.pin)
		}
		var readErr error
		before, readErr = client.ReadAuthorVersion(ctx, target.skill, target.pin)
		switch {
		case readErr == nil && before.Version.Status == "discarded":
			alreadyDiscarded = true
			return api.AuthorWriteResult{Ok: true, Version: before.Version}, nil
		case readErr != nil && authorNotFound(readErr):
			// A discarded row is `maintain`+ only: to a `write` submitter running the command a
			// second time, the pre-read is a 404. The discard itself is still idempotent for them;
			// this flag is what lets the success below say "already" rather than "just did".
			preReadInvisible = true
		}
		return client.AuthorDiscard(ctx, target.skill, target.pin)
	})
	if err != nil {
		return versionErr(jsonMode, err, "丢弃草稿需要你是它的提交人（写入档），或者有「维护」及以上。")
	}
	if alreadyDiscarded {
		if jsonMode {
			return writeJSON(result)
		}
		fmt.Printf("这一版已经是丢弃状态，没有需要再做的：%s@%s\n", target.skill, target.pin)
		return nil
	}
	if jsonMode {
		return writeJSON(result)
	}
	if preReadInvisible && result.Version.Status == "discarded" {
		fmt.Printf("这一版已经是丢弃状态，没有需要再做的：%s@%s\n", target.skill, target.pin)
		return nil
	}
	fmt.Printf("已丢弃 %s@%s（这一版无法找回）。\n", target.skill, target.pin)
	return nil
}

// authorNotFound is the author plane's "this version is not visible to you" answer.
func authorNotFound(err error) bool {
	var server *api.ServerError
	if errors.As(err, &server) && server.Status == 404 {
		return true
	}
	return errors.Is(err, api.ErrNotFound)
}

func parseDiscardArgs(args []string) (versionTarget, bool, error) {
	address := ""
	yes := false
	for _, arg := range args {
		switch arg {
		case "--json":
		case "--yes":
			yes = true
		default:
			if strings.HasPrefix(arg, "-") {
				return versionTarget{}, false, usagef("不认识的参数 %s。\n%s", namedArgument(arg), versionUsage)
			}
			if address != "" {
				return versionTarget{}, false, usagef("version discard 只接一个地址。")
			}
			address = arg
		}
	}
	if address == "" {
		return versionTarget{}, false, usagef("%s", versionUsage)
	}
	target, err := writablePin(address)
	return target, yes, err
}

// ---------------------------------------------------------------------------
// version open
// ---------------------------------------------------------------------------

func versionOpen(ctx context.Context, args []string) error {
	_ = ctx
	// The two value flags must be known to the pre-scan: `--sub --json` names the value `--json`,
	// and reading it as a switch would change the shape of the error a script receives.
	jsonMode := jsonModeIn(args, "--sub", "--bump")
	target, options, err := parseOpenArgs(args)
	if err != nil {
		return versionErr(jsonMode, err, "")
	}

	// The console's address grammar has no `draft:` prefix (ADR 0063). A legacy spelling is
	// normalised here rather than pasted into `?version=`, where it would match no row; a bare row id
	// is left as it is — for a published version it is not a web address, and the page says so with
	// 「没有这一版」 rather than silently opening another one.
	pin := target.pin
	if strings.HasPrefix(pin, "draft:") {
		if id, ok := rowIDOfPin(pin); ok {
			pin = id
		}
	}
	address := versionWebURL(target.skill, pin, options.sub, options.bump)
	opened := false
	warning := ""
	if !options.printOnly {
		if openErr := openBrowser(address); openErr != nil {
			warning = fmt.Sprintf("没能自动打开浏览器（地址在上面，也可以自己打开）：%v", openErr)
		} else {
			opened = true
		}
	}
	if warning != "" {
		fmt.Fprintln(os.Stderr, warning)
	}

	if jsonMode {
		return writeJSON(struct {
			URL    string `json:"url"`
			Opened bool   `json:"opened"`
		}{URL: address, Opened: opened})
	}
	fmt.Println(address)
	switch {
	case opened:
		fmt.Println("（已在浏览器里打开。）")
	case options.printOnly:
		fmt.Println("（--print：只打印地址，没有打开浏览器。）")
	default:
		fmt.Println("（没能自动打开浏览器；地址就在上面。）")
	}
	return nil
}

// openOptions are the two address facts `version open` can add: which pane, and which publish
// choice the form should start on. Both are absent by default, and absent means "leave the address
// exactly as it has always been" (v11 PRD 验收 #12).
type openOptions struct {
	printOnly bool
	sub       string
	bump      string
}

func parseOpenArgs(args []string) (versionTarget, openOptions, error) {
	address := ""
	options := openOptions{}
	seenSub := false
	seenBump := false
	for i := 0; i < len(args); i++ {
		switch arg := args[i]; {
		case arg == "--json":
		case arg == "--print":
			options.printOnly = true
		case arg == "--sub":
			if seenSub {
				return versionTarget{}, openOptions{}, usagef("%s", "--sub 只能给一次。")
			}
			seenSub = true
			if i+1 >= len(args) {
				return versionTarget{}, openOptions{}, usagef("%s", "--sub 后面要跟 record、diff 或 actions。"+versionUsage)
			}
			value := args[i+1]
			i++
			if value != "record" && value != "diff" && value != "actions" {
				return versionTarget{}, openOptions{}, usagef("--sub 只接受 record、diff、actions（%s）。\n%s", redactOr(value), versionUsage)
			}
			// `record` is the pane the address already means; writing it would be a second spelling.
			if value != "record" {
				options.sub = value
			}
		case arg == "--bump":
			if seenBump {
				return versionTarget{}, openOptions{}, usagef("%s", "--bump 只能给一次。")
			}
			seenBump = true
			if i+1 >= len(args) {
				return versionTarget{}, openOptions{}, usagef("%s", "--bump 后面要跟 patch、minor 或 major。"+versionUsage)
			}
			value := args[i+1]
			i++
			if !isBumpKind(value) {
				return versionTarget{}, openOptions{}, usagef("--bump 只接受 patch、minor、major（%s）。\n%s", redactOr(value), versionUsage)
			}
			options.bump = value
		case strings.HasPrefix(arg, "-"):
			return versionTarget{}, openOptions{}, usagef("不认识的参数 %s。\n%s", namedArgument(arg), versionUsage)
		default:
			if address != "" {
				return versionTarget{}, openOptions{}, usagef("version open 只接一个地址（收到 %s）。", namedArgument(arg))
			}
			address = arg
		}
	}
	if address == "" {
		return versionTarget{}, openOptions{}, usagef("%s", versionUsage)
	}
	target, err := parseVersionTarget(address)
	return target, options, err
}

// versionWebURL is the single-version page: one route, with the pin in the query.
//
// Both path segments are escaped because a skill name may be non-ASCII. The pin is escaped only
// when it has to be: `:`, `.` and `-` are legal in a query value, and leaving them readable keeps
// `<版本码>` and `sha256:…` copyable from the terminal.
//
// `sub` and `bump` are appended only when asked for, and in that order, so an address that names
// neither is byte-for-byte the one this command has always printed (v11 PRD 验收 #12). Both values
// come from closed sets validated by the caller, so they need no escaping.
func versionWebURL(skill, pin, sub, bump string) string {
	namespace, name, _ := strings.Cut(skill, "/")
	base := fmt.Sprintf("%s/skills/%s/%s", config.WebURL(),
		url.PathEscape(namespace), url.PathEscape(name))
	address := base + "?tab=versions&version=" + queryValue(pin)
	if sub != "" {
		address += "&sub=" + sub
	}
	if bump != "" {
		address += "&bump=" + bump
	}
	return address
}

func queryValue(value string) string {
	for _, r := range value {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') ||
			strings.ContainsRune("-._~:", r) {
			continue
		}
		return url.QueryEscape(value)
	}
	return value
}

func containsString(values []string, want string) bool {
	for _, value := range values {
		if value == want {
			return true
		}
	}
	return false
}

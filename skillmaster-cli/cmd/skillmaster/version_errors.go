// Refusals, exit codes, and the error text a failed version command prints.

package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
)

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
	// Exit is the process status. Two places set it: `classifyFailure` sets 4 for a credential that
	// cannot be used (it still sees the original error there), and `classifyExit` sets 2 for a usage
	// failure and 4 for a refused credential when `reportError` is about to print. It is not
	// serialised: the --json payload is built field by field, and the exit status is a separate axis
	// from the error object (ADR 0087). Zero means nothing set it, which is status 1.
	Exit int
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
	"skill_not_found":        "这个地址在你的 skill 里找不到。",
	"draft_name_required":    "这一版需要一个名称，1–40 个字符。",
	"draft_name_too_long":    "名称最多 40 个字符。",
	"draft_name_is_frozen":   "这一版已经不是草稿，名称不能再改。",
	"note_too_long":          "说明最多 200 个字符。",
	"note_is_frozen":         "这一版已经不是草稿，说明不能再改。",
	"release_type_is_frozen": "这一版已经不是草稿，类型不能再改。",
	"invalid_version_state":  "这一版目前的状态不能做这个操作。",
	"version_revoked":        "这一版已下架，调用会失败；要恢复读取，先重新上架。",
	"skill_offline":          "这个 skill 已下线，暂时不提供服务。",
	"invalid_request":        "提交的内容有问题，请检查后重试。",
	"too_many_requests":      "请求太频繁了，等一会儿再试。",
	"internal_error":         "服务端出错了，请稍后重试。",
	// The name still belongs to a deleted skill; the only way out is restoring it in the browser
	// (ADR 0071). Said in the interface's voice rather than quoting the server's English.
	"skill_deleted": "这个名字属于一个已删除的 skill；要到网页上恢复它，才能继续用这个名字。",
	// v10's authoring verbs. Their refusals arrive as top-level codes or as `details[].issue`; both
	// are translated here so a person does not get the server's English in a Chinese CLI.
	"skill_already_exists":   "这个名字已经被一个 skill 占着。",
	"content_already_exists": "同样的内容已经有一版了；改点东西再提交。",
	// v54 复制. The copy's target name is a skill name, and its source may declare a SKILL.md that
	// the one-field rewrite cannot edit safely; both sentences say what to do next.
	"invalid_name":        "复制后的名字要是 1–64 个小写字母、数字和连字符，不能以连字符开头或结尾，也不能用官方保留的前缀。",
	"name_not_rewritable": "这个 skill 的 SKILL.md 不能安全地改名；用它的原名复制一次，再在编辑器里改。",
	// v37: the narrow delete's one refusal that is about the skill rather than the credential.
	"skill_published":            "这个 skill 上线过，不能由凭据删除；要到网页上由人确认。",
	"notification_not_found":     "这条通知不在你的列表里——它可能已经被收口，或你已失去处理它的权限。",
	"invalid_notification_state": "这条通知现在已经不能处理了。",
	"digest_mismatch":            "这一版刚变过，重读再来。",
}

// versionRefusal applies versionMessages to an error, and returns it unchanged when the code is not
// one of them. The four places that used to repeat this three lines now share it.
func versionRefusal(err error) error {
	var server *api.ServerError
	if errors.As(err, &server) {
		// v39's size refusals: the server sends the actual and allowed counts, so the sentence is
		// built here rather than being one fixed limit (voice-spec §上传与大小的四句话). Checked
		// before the issue table, which has one static line per issue and would drop the numbers.
		if server.Code == "invalid_upload" {
			if sentence, ok := sizeRefusal(server); ok {
				return errors.New(sentence)
			}
		}
		// The notification answer's reason is the only half that says what to do next; the code
		// alone would read as a generic "cannot answer now".
		if server.Code == "invalid_notification_state" {
			for _, issue := range issueValues(server.Details) {
				if message, known := notificationStateMessages[issue]; known {
					return errors.New(message)
				}
			}
		}
		// An issue name is more specific than `invalid_request`, and it is the only half of a 400
		// that says what to change — so it is looked up first. Without this, a refusal that carries
		// `bad_pattern` reads as 「提交的内容有问题」, which names nothing the caller can act on.
		if server.Code == "invalid_request" || server.Code == "invalid_upload" {
			// ADR 0179's text refusals carry the field's actual and allowed code points. Read
			// them before the issue table, whose single `too_long` line is about a tag's 200-word
			// note and would put that number under a description with a 1024 ceiling.
			if sentence, ok := textRefusal(server); ok {
				return errors.New(sentence)
			}
			for _, issue := range issueValues(server.Details) {
				if message, known := issueMessages[issue]; known {
					return errors.New(message)
				}
			}
		}
		if message, known := versionMessages[server.Code]; known {
			return errors.New(message)
		}
	}
	return err
}

// textRefusal writes the sentence for a skill-text field refusal (ADR 0179). It exists because
// `too_long` alone does not say which field or which ceiling: description is 1024 code points and
// the author's when_to_use is 200, and the tag note has its own `too_long` in the issue table.
func textRefusal(server *api.ServerError) (string, bool) {
	for _, detail := range server.DetailList {
		if detail.Issue == "field_not_text" {
			return fmt.Sprintf("%s要填文本，不能是数字或列表。", fieldLabel(detail.Field)), true
		}
		if detail.Issue == "required_field_missing" && detail.Field == "description" {
			return "描述是必填的。", true
		}
		if detail.LimitCodePoints > 0 {
			if detail.ActualCodePoints > 0 {
				return fmt.Sprintf("%s有 %d 个字，超过 %d 个的上限。",
					fieldLabel(detail.Field), detail.ActualCodePoints, detail.LimitCodePoints), true
			}
			return fmt.Sprintf("%s最多 %d 个字。", fieldLabel(detail.Field), detail.LimitCodePoints), true
		}
	}
	return "", false
}

// fieldLabel names a skill-text field the way the interface does. The author's field can arrive as
// `when_to_use` or `metadata.when_to_use` depending on whether it was written at the top level.
func fieldLabel(field string) string {
	switch {
	case field == "description":
		return "描述"
	case strings.Contains(field, "when_to_use"):
		return "适用场景"
	case field == "":
		return "这个字段"
	default:
		return "「" + field + "」"
	}
}

// importCeilingBytes is the interim import cap (P3 removes it). It covers two guards — the
// package's own size and its decompressed total — so a `package_too_large` naming it is about the
// import, not about the version's total.
const importCeilingBytes = int64(128 * 1024 * 1024)

// sizeRefusal writes one of v39's four size sentences from the numbers the server sent, and reports
// false when the refusal is not about a size or carried no numbers.
//
// **`package_too_large` is one issue for two ceilings** — a version's total and the import cap
// (which itself covers the package's own size and its decompressed total) — and only the limit tells
// them apart. Choosing by the command instead would make the sentence wrong for whichever path was
// not thought of.
func sizeRefusal(server *api.ServerError) (string, bool) {
	for _, detail := range server.DetailList {
		if detail.LimitBytes == 0 {
			continue
		}
		switch detail.Issue {
		case "file_too_large":
			if detail.ActualBytes == 0 {
				return fmt.Sprintf("这个文件超过了单文件上限 %s。", mib(detail.LimitBytes)), true
			}
			if detail.Field != "" && detail.Field != "file" {
				return fmt.Sprintf("文件 %s 有 %s，超过单文件上限 %s。",
					detail.Field, mib(detail.ActualBytes), mib(detail.LimitBytes)), true
			}
			return fmt.Sprintf("这个文件有 %s，超过单文件上限 %s。",
				mib(detail.ActualBytes), mib(detail.LimitBytes)), true
		case "package_too_large":
			if detail.LimitBytes <= importCeilingBytes {
				return fmt.Sprintf("整包导入最多 %s；更大的包请逐个文件上传。",
					mib(detail.LimitBytes)), true
			}
			if detail.ActualBytes > 0 {
				return fmt.Sprintf("这一版有 %s，超过 %s 的上限。",
					mib(detail.ActualBytes), mib(detail.LimitBytes)), true
			}
			return fmt.Sprintf("这一版超过了 %s 的上限。", mib(detail.LimitBytes)), true
		case "too_many_files":
			return fmt.Sprintf("这一版最多 %d 个文件。", detail.LimitBytes), true
		}
	}
	return "", false
}

// addressRefusal is the same sentence plus the one step a person needs after a 404 on the author
// plane, and it is what every read command answers with (v22 验收 #6/#14 follow-up).
//
// **The English envelope never reaches the person.** The server's 404 is one answer for "no such
// skill", "no such version" and "not yours"; `no skill at that address` names none of them and
// reads as a different product. The 404 check is on the status rather than the code because the
// author plane answers some 404s with `skill_not_found` and others with `version_not_found`.
func addressRefusal(err error) error {
	var server *api.ServerError
	if errors.As(err, &server) && server.Status == http.StatusNotFound {
		return fmt.Errorf("%s下一步：用 skmr version list 核对。", versionMessages["skill_not_found"])
	}
	return versionRefusal(err)
}

// notificationStateMessages are the reasons a notification cannot be answered now. They travel as
// `details[].issue` under the `invalid_notification_state` code.
var notificationStateMessages = map[string]string{
	"expired":               "这条邀请已经过期了，不能再接受。",
	"already_resolved":      "这条通知已经处理过了，或对应的邀请 / 申请已经不在了。",
	"not_an_invitation":     "这条通知不是共享邀请。",
	"not_an_access_request": "这条通知不是权限申请。",
	"revoked":               "这条邀请指向的 skill 已被删除。",
}

// issueMessages are the names the server puts in `details[].issue`, which are not codes of their
// own. Read only for a 400 `invalid_request`, which is where every one of them travels.
var issueMessages = map[string]string{
	"no_such_user":                     "没有这个用户。",
	"not_a_base":                       "这一版不能当底稿：底稿必须是线上版本或草稿。",
	"not_above_current_rung":           "申请要高于你现在持有的档位。",
	"owner_has_no_higher_rung":         "你是所有者，上面没有更高的档位。",
	"not_in_library":                   "先把它入库，再申请更高档位：skmr skill join <地址> --yes。",
	"not_a_rung":                       "档位只有调用、浏览、写入、维护、管理。",
	"raise_needs_invitation":           "升档要发共享邀请，等对方接受；用 skmr skill share <地址> --to <用户名> --role <档位>。",
	"already_yours":                    "这是你自己的 skill；所有者已经拥有全部档位。",
	"invitee_is_owner":                 "对方是这个 skill 的所有者，已经拥有全部档位。",
	"not_shared":                       "这个人还没有已生效的授权；要新共享请发邀请。",
	"required_field_missing":           "缺少必填字段。",
	"name_too_long":                    "名字最多 64 个码点。",
	"name_not_usable_as_a_directory":   "名字里不能带 /。",
	"name_contains_version_separator":  "名字里不能带 @。",
	"name_contains_unaddressable_char": "名字里不能带 % 或 ;。",
	"invalid_format":                   "格式不对。",
	"unexpected":                       "空白底稿不带 digest。",
	// v51's symlink refusal. The server refuses the entry and names it; without this line the
	// person is told the upload was not accepted and nothing about the one file to fix.
	"symlink_not_allowed": "这个包里有一个快捷方式（符号链接）。平台不保存快捷方式；把它改成真实文件，或删掉它再重新提交。",
	// v39's size refusals. The client checks the same ceilings before sending, so these are the
	// server's answer when a race or another client got there first.
	"file_too_large":    "这个文件超过了单文件 100 MiB 的上限。",
	"package_too_large": "这一版超过了容量上限。",
	"too_many_files":    "这一版最多 512 个文件。",
	// v34's grep. The server compiles the pattern with RE2 and refuses what RE2 cannot run; the help
	// page promises this sentence, so the runtime has to be the one that says it — the generic
	// 「提交的内容有问题」 names nothing the caller can change.
	"bad_pattern": "服务端不支持这个正则（RE2）：去掉反向引用与 lookaround，或改用 -F。",
	// v14's tag definitions and attachments (TD §错误形状). These arrive as `details[].issue` on a
	// 400; the code itself is `invalid_request`, so the issue is the only part that says what to fix.
	"name_taken":          "同一个名字已经有了。要贴到某个 skill 上用 skmr skill tag add <地址> <名字>；要换名字用 skmr skills my-tags rename。",
	"category_name_taken": "这个名字和公开分类重名，换一个。",
	"tag_limit_reached":   "标签数量到上限了：每个 skill 最多 20 个我的标签、10 个公开标签；每个账号最多 100 个定义。",
	"invalid_name":        "名字要是 1–24 个字（去掉首尾空格、内部连续空格压成一个），不能带 #。",
	"too_long":            "说明最多 200 个字。",
	// The category write's refusals (v14). The vocabulary is the caller's next step in every case,
	// which is why each sentence ends there rather than repeating the rules.
	"missing":          "缺少分类。用法见：skmr skill category set --help。",
	"unknown_category": "词表里没有这个分类。看词表：skmr skills categories。",
	"category_retired": "这个分类已经退役，不能再用了。看词表：skmr skills categories。",
	"category_stopped": "这个分类已停用，不能再新设；已经贴着的可以保留或换走。",
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
			// **Only the two "the request itself was wrong" codes carry issues.** A top-level
			// `invalid_name` (v54's copy target) also happens to use `invalid_name` as a *reason*
			// inside `details`, and looking it up here would replace the copy sentence with the
			// tag-name rule (1–24 个字) — a different field's rule answering this one.
			if server.Code == "invalid_request" || server.Code == "invalid_upload" {
				if sentence, known := textRefusal(server); known {
					failure.Message = sentence
				} else {
					for _, issue := range issueValues(server.Details) {
						if message, known := issueMessages[issue]; known {
							failure.Message = message
							break
						}
					}
				}
			}
		case server.Status == 404:
			failure.Code = "not_found"
		default:
			failure.Code = "server_error"
		}
		// v39: a size refusal says the actual value and the ceiling, so the sentence is written from
		// the numbers the server sent rather than from the static line above — which is one fixed
		// sentence per issue and cannot name either (voice-spec §上传与大小的四句话).
		if server.Code == "invalid_upload" {
			if sentence, ok := sizeRefusal(server); ok {
				failure.Message = sentence
			}
		}
		return failure
	}
	// **The credential itself is the problem**, so this is the one failure that classifies to 4
	// before anything else is looked at: `--no-login` with nothing stored, or a SKILLMASTER_API_KEY
	// that is not a key. It has to happen here, where the original error is still visible — after
	// `versionErr` flattens it into a `cliFailure` the type is gone (ADR 0087).
	if credentialUnavailable(err) {
		failure.Code = "unauthenticated"
		failure.Exit = 4
		return failure
	}
	var usage *usageError
	if errors.As(err, &usage) {
		failure.Code = "usage"
		return failure
	}
	var refused *refusalError
	if errors.As(err, &refused) {
		failure.Code = "refused"
		return failure
	}
	var missingSource sourceNotFound
	if errors.As(err, &missingSource) {
		failure.Code = "not_found"
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
	status := 1
	var failure *cliFailure
	if errors.As(err, &failure) {
		classifyExit(failure)
		status = failureExit(failure)
	}
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
		return status
	}
	var usage *usageError
	if errors.As(err, &usage) {
		status = 2
	}
	if credentialUnavailable(err) {
		status = 4
	}
	var refusal *api.Refusal
	if errors.As(err, &refusal) && isRefusedCredential(refusal.Code) {
		status = 4
	}
	fmt.Fprintf(os.Stderr, "错误：%v\n", err)
	return status
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

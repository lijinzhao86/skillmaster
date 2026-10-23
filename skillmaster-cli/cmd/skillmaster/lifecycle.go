package main

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"os"
	"strconv"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

// The CLI's lifecycle verbs (v10): creating a skill from nothing, the collaborator list and its
// revocation, asking for access, and opening the page an action lives on.
//
// **What is not here is as deliberate as what is.** Publishing, rolling back, revoking a version,
// restoring a version, taking a skill offline and deleting one change what a caller reads; they live
// on the browser plane (ADR 0061), and the refusal in version.go hands the person the exact page.

// jsonModeIn answers whether `--json` is on the line before the command has parsed anything, so a
// usage error that comes first still answers in the shape the flag promises. Each command names its
// own value-taking flags: `--status` takes a value for `version list` and is a switch here, and a
// shared table cannot know both.
func jsonModeIn(args []string, valueFlags ...string) bool {
	takesValue := make(map[string]bool, len(valueFlags))
	for _, flag := range valueFlags {
		takesValue[flag] = true
	}
	for i := 0; i < len(args); i++ {
		if takesValue[args[i]] {
			i++
			continue
		}
		if args[i] == "--json" {
			return true
		}
	}
	return false
}

const createUsage = `用法：skillmaster skill create <名字> --description <描述> [--name <版本名称>] [--json]

  在你的个人命名空间里建一个 skill 与它的第一份草稿（根 SKILL.md 由服务端生成）。
  名字是一段，不带 / 或 @；版本名称省略时为「第一版」。
  skill 建出来是「草稿」，不会上线。`

func createSkill(ctx context.Context, args []string) error {
	name := ""
	description := ""
	versionName := ""
	nameGiven := false
	jsonMode := jsonModeIn(args, "--description", "--name")
	var positional []string
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--description":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("%s", "--description 后面要跟描述。"+createUsage), "")
			}
			description = args[i+1]
			i++
		case "--name":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("%s", "--name 后面要跟版本名称。"+createUsage), "")
			}
			versionName = args[i+1]
			nameGiven = true
			i++
		case "--json":
			jsonMode = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionErr(jsonMode, usagef("不认识的参数 %s。%s", namedArgument(args[i]), createUsage), "")
			}
			positional = append(positional, args[i])
		}
	}
	if len(positional) != 1 {
		return versionErr(jsonMode, usagef("%s", createUsage), "")
	}
	name = positional[0]
	if name == "" {
		return versionErr(jsonMode, usagef("%s", createUsage), "")
	}
	if description == "" {
		return versionErr(jsonMode, usagef("%s", "新建 skill 要有一句描述（--description）。"+createUsage), "")
	}
	if strings.ContainsAny(name, "/@") {
		return versionErr(jsonMode, usagef("名字是一段，不能带 / 或 @（%s）。", name), "")
	}
	// Omitted is "第一版"; **given empty is a request for a nameless version**, which the server
	// refuses (`draft_name_required`) and the CLI must not paper over by filling in a default.
	if nameGiven && strings.TrimSpace(versionName) == "" {
		return versionErr(jsonMode, usagef("%s", "版本名称不能是空的（--name）。"+createUsage), "")
	}
	if !nameGiven {
		versionName = "第一版"
	}

	created, err := authenticated(ctx, func(client api.Client) (api.CreatedSkill, error) {
		return client.CreateSkill(ctx, name, description, versionName)
	})
	if err != nil {
		return versionErr(jsonMode, err, "")
	}
	if jsonMode {
		return writeJSON(created)
	}
	fmt.Printf("已新建 %s/%s@%s（版本名称：%s）。\n", created.Namespace, created.Name,
		created.VersionCode, created.VersionName)
	return nil
}

const sharesUsage = `用法：skillmaster skill shares <命名空间>/<名字> [--query <文字>] [--page <页码>] [--json]

  列出谁在这个 skill 上有什么档位、从什么时候开始。每页 10 人；--query 按用户名过滤。`

func shares(ctx context.Context, args []string) error {
	address := ""
	query := ""
	page := 0
	jsonMode := jsonModeIn(args, "--query", "--page")
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--query":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("%s", "--query 后面要跟要过滤的文字。"+sharesUsage), "")
			}
			query = args[i+1]
			i++
		case "--page":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("%s", "--page 后面要跟页码。"+sharesUsage), "")
			}
			parsed, err := strconv.Atoi(args[i+1])
			if err != nil || parsed < 1 {
				return versionErr(jsonMode, usagef("%s", "--page 要从 1 开始。"+sharesUsage), "")
			}
			page = parsed
			i++
		case "--json":
			jsonMode = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionErr(jsonMode, usagef("不认识的参数 %s。%s", namedArgument(args[i]), sharesUsage), "")
			}
			if address != "" {
				return versionErr(jsonMode, usagef("%s", sharesUsage), "")
			}
			address = args[i]
		}
	}
	if address == "" || strings.Contains(address, "@") {
		return versionErr(jsonMode, usagef("%s", sharesUsage), "")
	}

	result, err := authenticated(ctx, func(client api.Client) (api.GrantsPage, error) {
		return client.ListGrants(ctx, address, query, page)
	})
	if err != nil {
		return versionErr(jsonMode, err, "")
	}
	if jsonMode {
		return writeJSON(result)
	}
	if result.Total == 0 {
		fmt.Println("这个 skill 还没有共享给任何人。")
		return nil
	}
	for _, grant := range result.Grants {
		fmt.Printf("%s\t%s\t%s\n", grant.Handle, rungLabel(grant.Role), grant.CreatedAt)
	}
	if len(result.Grants) == 0 {
		fmt.Println("这一页没有人。")
	}
	fmt.Printf("第 %d 页，共 %d 人。\n", result.Page, result.Total)
	return nil
}

const unshareUsage = `用法：skillmaster skill unshare <命名空间>/<名字> --user <用户名> [--json]

  撤销某个人在这个 skill 上的访问。撤销不存在的共享也算成功。`

func unshare(ctx context.Context, args []string) error {
	address := ""
	handle := ""
	jsonMode := jsonModeIn(args, "--user")
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--user":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("%s", "--user 后面要跟用户名。"+unshareUsage), "")
			}
			handle = args[i+1]
			i++
		case "--json":
			jsonMode = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionErr(jsonMode, usagef("不认识的参数 %s。%s", namedArgument(args[i]), unshareUsage), "")
			}
			if address != "" {
				return versionErr(jsonMode, usagef("%s", unshareUsage), "")
			}
			address = args[i]
		}
	}
	if address == "" || handle == "" || strings.Contains(address, "@") {
		return versionErr(jsonMode, usagef("%s", unshareUsage), "")
	}

	if _, err := authenticated(ctx, func(client api.Client) (struct{}, error) {
		return struct{}{}, client.RevokeShare(ctx, address, handle)
	}); err != nil {
		return versionErr(jsonMode, err, "")
	}
	if jsonMode {
		return writeJSON(map[string]any{"handle": handle, "revoked": true})
	}
	fmt.Printf("已撤销 %s 在 %s 上的访问。\n", handle, address)
	return nil
}

const requestUsage = `用法：skillmaster skill request <命名空间>/<名字> --role <档位> | --status | --cancel [--json]

  --role    提交申请（档位必须高于你现在持有的那一档）
  --status  读你在这个 skill 上等着的申请
  --cancel  撤回申请；没有在等的申请也算成功

  申请是写操作：只有 skills:read 的只读密钥申请不了访问（服务端会答 403）。`

func requestAccess(ctx context.Context, args []string) error {
	address := ""
	rung := ""
	wantStatus := false
	wantCancel := false
	jsonMode := jsonModeIn(args, "--role")
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--role":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("%s", "--role 后面要跟档位。"+requestUsage), "")
			}
			rung = args[i+1]
			i++
		case "--status":
			wantStatus = true
		case "--cancel":
			wantCancel = true
		case "--json":
			jsonMode = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionErr(jsonMode, usagef("不认识的参数 %s。%s", namedArgument(args[i]), requestUsage), "")
			}
			if address != "" {
				return versionErr(jsonMode, usagef("%s", requestUsage), "")
			}
			address = args[i]
		}
	}
	chosen := 0
	if rung != "" {
		chosen++
	}
	if wantStatus {
		chosen++
	}
	if wantCancel {
		chosen++
	}
	if address == "" || chosen != 1 || strings.Contains(address, "@") {
		return versionErr(jsonMode, usagef("%s", requestUsage), "")
	}

	switch {
	case wantStatus:
		pending, err := authenticated(ctx, func(client api.Client) (*string, error) {
			return client.PendingAccess(ctx, address)
		})
		if err != nil {
			return versionErr(jsonMode, err, "")
		}
		if jsonMode {
			return writeJSON(map[string]any{"pending_rung": pending})
		}
		if pending == nil {
			fmt.Println("没有在等的申请。")
		} else {
			fmt.Printf("正在等待「%s」的审批。\n", rungLabel(*pending))
		}
		return nil

	case wantCancel:
		if _, err := authenticated(ctx, func(client api.Client) (struct{}, error) {
			return struct{}{}, client.WithdrawAccess(ctx, address)
		}); err != nil {
			return versionErr(jsonMode, err, "")
		}
		if jsonMode {
			return writeJSON(map[string]any{"cancelled": true})
		}
		fmt.Println("已撤回申请。")
		return nil

	default:
		asked, err := authenticated(ctx, func(client api.Client) (string, error) {
			return client.RequestAccess(ctx, address, rung)
		})
		if err != nil {
			return versionErr(jsonMode, err, "")
		}
		if jsonMode {
			return writeJSON(map[string]any{"rung": asked})
		}
		fmt.Printf("已申请「%s」档，等待审批。\n", rungLabel(asked))
		return nil
	}
}

const openUsage = `用法：skillmaster skill open <命名空间>/<名字> [--tab info|files|versions|settings] [--sub general|collaborators|details] [--print] [--json]

  打印这个 skill 的网页地址并尝试打开浏览器；不改任何东西。
  --sub 只在 --tab settings 下有效。要看某一版自己的页面，用 version open。`

func openSkill(ctx context.Context, args []string) error {
	_ = ctx
	address := ""
	tab := ""
	sub := ""
	printOnly := false
	jsonMode := jsonModeIn(args, "--tab", "--sub")
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--tab":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("%s", "--tab 后面要跟分区。"+openUsage), "")
			}
			tab = args[i+1]
			i++
		case "--sub":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("%s", "--sub 后面要跟子页。"+openUsage), "")
			}
			sub = args[i+1]
			i++
		case "--print":
			printOnly = true
		case "--json":
			jsonMode = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionErr(jsonMode, usagef("不认识的参数 %s。%s", namedArgument(args[i]), openUsage), "")
			}
			if address != "" {
				return versionErr(jsonMode, usagef("%s", openUsage), "")
			}
			address = args[i]
		}
	}
	if address == "" {
		return versionErr(jsonMode, usagef("%s", openUsage), "")
	}
	if strings.Contains(address, "@") {
		return versionErr(jsonMode, usagef("%s", "这个命令打开的是整个 skill；要看某一版自己的页面，用 version open。"+openUsage), "")
	}
	if tab != "" && tab != "info" && tab != "files" && tab != "versions" && tab != "settings" {
		return versionErr(jsonMode, usagef("%s", "--tab 只接受 info、files、versions、settings。"+openUsage), "")
	}
	if sub != "" && tab != "settings" {
		return versionErr(jsonMode, usagef("%s", "--sub 只在 --tab settings 下有效。"+openUsage), "")
	}
	if sub != "" && sub != "general" && sub != "collaborators" && sub != "details" {
		return versionErr(jsonMode, usagef("%s", "--sub 只接受 general、collaborators、details。"+openUsage), "")
	}

	namespace, name, ok := strings.Cut(address, "/")
	if !ok || namespace == "" || name == "" || strings.Contains(name, "/") {
		return versionErr(jsonMode, usagef("%s", "地址要写成 <命名空间>/<名字>。"+openUsage), "")
	}
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

	opened := false
	warning := ""
	if !printOnly {
		if openErr := openBrowser(target); openErr != nil {
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
		}{URL: target, Opened: opened})
	}
	fmt.Println(target)
	switch {
	case opened:
		fmt.Println("（已在浏览器里打开。）")
	case printOnly:
		fmt.Println("（--print：只打印地址，没有打开浏览器。）")
	default:
		fmt.Println("（没能自动打开浏览器；地址就在上面。）")
	}
	return nil
}

// ---------------------------------------------------------------------------
// v14: the library reference, and restoring a deleted skill
// ---------------------------------------------------------------------------

const joinLeaveUsage = `用法：skillmaster skill join <命名空间>/<名字> --yes
       skillmaster skill leave <命名空间>/<名字> --yes`

// joinLibrary adds or removes a library reference. **`--yes` is required and the consequence is
// printed first** (PRD §CLI 命令面 §`skill`): joining is additive but changes what a later listing
// shows, and leaving takes a group of private tags out of view until the skill is joined again. A
// caller with nobody in front of it gets a non-zero exit and the exact command to run, not a prompt.
func joinLibrary(ctx context.Context, args []string, join bool) error {
	address := ""
	yes := false
	for _, arg := range args {
		switch arg {
		case "--yes":
			yes = true
		case "help", "-h", "--help":
			fmt.Fprintln(os.Stderr, joinLeaveUsage)
			return nil
		default:
			if strings.HasPrefix(arg, "-") {
				return fmt.Errorf("不认识的参数 %s。%s", namedArgument(arg), joinLeaveUsage)
			}
			if address != "" {
				return errors.New(joinLeaveUsage)
			}
			address = arg
		}
	}
	if address == "" {
		return errors.New(joinLeaveUsage)
	}
	if err := checkBareAddress(address); err != nil {
		return err
	}

	verb, action := "join", "加入"
	if !join {
		verb, action = "leave", "移出库"
	}
	command := fmt.Sprintf("skillmaster skill %s %s --yes", verb, address)
	if !yes {
		// **Nothing is sent.** The consequence is on stdout (it is the answer to "what would this
		// do"), and the refusal is what makes the exit non-zero.
		if join {
			fmt.Println("加入是引用：不复制内容、不产生版本、不改变访问权；作者发新版，库里跟新版。")
		} else {
			fmt.Println("移除只删「我加入的」这条引用；这个 skill 上的我的标签会休眠，重新加入后回来。" +
				"它仍在公域，仍可直接调用。")
		}
		fmt.Printf("确认后执行：\n%s\n", command)
		return fmt.Errorf("没有执行：%s要加 --yes。", action)
	}

	change, err := authenticated(ctx, func(client api.Client) (api.Change, error) {
		if join {
			return client.JoinLibrary(ctx, address)
		}
		return client.LeaveLibrary(ctx, address)
	})
	if err != nil {
		return v14Error(err)
	}
	if join {
		if !change.Changed {
			// The reference was already there, including the sleeping kind — and a sleeping
			// reference is still a reference (PRD #3), so "nothing changed" is the honest answer.
			fmt.Println("已经在你的库里。")
			return nil
		}
		fmt.Printf("已把 %s 收进你的库。\n", address)
		return nil
	}
	fmt.Printf("已把 %s 移出你的库。\n", address)
	return nil
}

const restoreUsage = "用法：skillmaster skill restore <命名空间>/<名字>"

// restoreSkill brings a soft-deleted skill back. **No `--yes`**: the action is reversible in the
// direction that matters — the skill can be deleted again from the console — and it changes
// visibility back to what it was rather than opening anything new.
func restoreSkill(ctx context.Context, args []string) error {
	address := ""
	for _, arg := range args {
		switch arg {
		case "help", "-h", "--help":
			fmt.Fprintln(os.Stderr, restoreUsage)
			return nil
		default:
			if strings.HasPrefix(arg, "-") {
				return fmt.Errorf("不认识的参数 %s。%s", namedArgument(arg), restoreUsage)
			}
			if address != "" {
				return errors.New(restoreUsage)
			}
			address = arg
		}
	}
	if address == "" {
		return errors.New(restoreUsage)
	}
	if err := checkBareAddress(address); err != nil {
		return err
	}

	if _, err := authenticated(ctx, func(client api.Client) (api.Change, error) {
		return client.RestoreSkill(ctx, address)
	}); err != nil {
		return v14Error(err)
	}
	fmt.Printf("已恢复 %s。\n", address)
	return nil
}

// rungLabel is the interface's word for a wire rung (voice-spec §名词表: 调用 / 浏览 / 写入 /
// 维护 / 管理). The wire value is still what `--json` carries; this is only the sentence.
func rungLabel(rung string) string {
	switch rung {
	case "invoke":
		return "调用"
	case "browse":
		return "浏览"
	case "write":
		return "写入"
	case "maintain":
		return "维护"
	case "admin":
		return "管理"
	default:
		return rung
	}
}

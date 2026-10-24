package main

import (
	"context"
	"fmt"
	"net/url"
	"os"
	"slices"
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
				return versionErr(jsonMode, usagef("--description 后面要跟描述。%s", usageAt("skill create")), "")
			}
			description = args[i+1]
			i++
		case "--name":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--name 后面要跟版本名称。%s", usageAt("skill create")), "")
			}
			versionName = args[i+1]
			nameGiven = true
			i++
		case "--json":
			jsonMode = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionErr(jsonMode, unknownFlag("skill create", args[i]), "")
			}
			positional = append(positional, args[i])
		}
	}
	if len(positional) != 1 {
		return versionErr(jsonMode, usagef("%s", usageAt("skill create")), "")
	}
	name = positional[0]
	if name == "" {
		return versionErr(jsonMode, usagef("%s", usageAt("skill create")), "")
	}
	if description == "" {
		return versionErr(jsonMode, usagef("新建 skill 要有一句描述（--description）。%s", usageAt("skill create")), "")
	}
	if strings.ContainsAny(name, "/@") {
		return versionErr(jsonMode, usagef("名字是一段，不能带 / 或 @（%s）。", name), "")
	}
	// Omitted is "第一版"; **given empty is a request for a nameless version**, which the server
	// refuses (`draft_name_required`) and the CLI must not paper over by filling in a default.
	if nameGiven && strings.TrimSpace(versionName) == "" {
		return versionErr(jsonMode, usagef("版本名称不能是空的（--name）。%s", usageAt("skill create")), "")
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

func shares(ctx context.Context, args []string) error {
	address := ""
	query := ""
	page := 0
	jsonMode := jsonModeIn(args, "--query", "--page")
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--query":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--query 后面要跟要过滤的文字。%s", usageAt("skill shares")), "")
			}
			query = args[i+1]
			i++
		case "--page":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--page 后面要跟页码。%s", usageAt("skill shares")), "")
			}
			parsed, err := strconv.Atoi(args[i+1])
			if err != nil || parsed < 1 {
				return versionErr(jsonMode, usagef("--page 要从 1 开始。%s", usageAt("skill shares")), "")
			}
			page = parsed
			i++
		case "--json":
			jsonMode = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionErr(jsonMode, unknownFlag("skill shares", args[i]), "")
			}
			if address != "" {
				return versionErr(jsonMode, usagef("%s", usageAt("skill shares")), "")
			}
			address = args[i]
		}
	}
	if address == "" || strings.Contains(address, "@") {
		return versionErr(jsonMode, usagef("%s", usageAt("skill shares")), "")
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

func unshare(ctx context.Context, args []string) error {
	address := ""
	handle := ""
	jsonMode := jsonModeIn(args, "--user")
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--user":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--user 后面要跟用户名。%s", usageAt("skill unshare")), "")
			}
			handle = args[i+1]
			i++
		case "--json":
			jsonMode = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionErr(jsonMode, unknownFlag("skill unshare", args[i]), "")
			}
			if address != "" {
				return versionErr(jsonMode, usagef("%s", usageAt("skill unshare")), "")
			}
			address = args[i]
		}
	}
	if address == "" || handle == "" {
		return versionErr(jsonMode, usagef("要给一个 skill 地址和 --user。%s", usageAt("skill unshare")), "")
	}
	// The grammar is refused here, before a credential is asked for: a typo is a usage error, and
	// reaching for the credential first would answer a mistyped address with a login prompt.
	if err := checkAddressSyntax("skill unshare", address); err != nil {
		return versionErr(jsonMode, err, "")
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
				return versionErr(jsonMode, usagef("--role 后面要跟档位。%s", usageAt("skill request")), "")
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
				return versionErr(jsonMode, unknownFlag("skill request", args[i]), "")
			}
			if address != "" {
				return versionErr(jsonMode, usagef("%s", usageAt("skill request")), "")
			}
			address = args[i]
		}
	}
	if rung != "" && !slices.Contains(api.Roles, rung) {
		return versionErr(jsonMode, usagef("档位只能是 %s，收到 %q。%s",
			strings.Join(api.Roles, "、"), rung, usageAt("skill request")), "")
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
	if address == "" || chosen != 1 {
		return versionErr(jsonMode, usagef("要给一个 skill 地址，并且 --role、--status、--cancel 里只给一个。%s", usageAt("skill request")), "")
	}
	if err := checkAddressSyntax("skill request", address); err != nil {
		return versionErr(jsonMode, err, "")
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
				return versionErr(jsonMode, usagef("--tab 后面要跟分区。%s", usageAt("skill open")), "")
			}
			tab = args[i+1]
			i++
		case "--sub":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--sub 后面要跟子页。%s", usageAt("skill open")), "")
			}
			sub = args[i+1]
			i++
		case "--print":
			printOnly = true
		case "--json":
			jsonMode = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionErr(jsonMode, unknownFlag("skill open", args[i]), "")
			}
			if address != "" {
				return versionErr(jsonMode, usagef("%s", usageAt("skill open")), "")
			}
			address = args[i]
		}
	}
	if address == "" {
		return versionErr(jsonMode, usagef("%s", usageAt("skill open")), "")
	}
	if strings.Contains(address, "@") {
		return versionErr(jsonMode, usagef("这个命令打开的是整个 skill；要看某一版自己的页面，用 version open。%s", usageAt("skill open")), "")
	}
	if tab != "" && tab != "info" && tab != "files" && tab != "versions" && tab != "settings" {
		return versionErr(jsonMode, usagef("--tab 只接受 info、files、versions、settings。%s", usageAt("skill open")), "")
	}
	if sub != "" && tab != "settings" {
		return versionErr(jsonMode, usagef("--sub 只在 --tab settings 下有效。%s", usageAt("skill open")), "")
	}
	if sub != "" && sub != "general" && sub != "collaborators" && sub != "details" {
		return versionErr(jsonMode, usagef("--sub 只接受 general、collaborators、details。%s", usageAt("skill open")), "")
	}

	namespace, name, ok := strings.Cut(address, "/")
	if !ok || namespace == "" || name == "" || strings.Contains(name, "/") {
		return versionErr(jsonMode, usagef("地址要写成 <命名空间>/<名字>。%s", usageAt("skill open")), "")
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
		default:
			if strings.HasPrefix(arg, "-") {
				return unknownFlag(joinLeavePath(join), arg)
			}
			if address != "" {
				return usagef("%s", usageAt(joinLeavePath(join)))
			}
			address = arg
		}
	}
	if address == "" {
		return usagef("%s", usageAt(joinLeavePath(join)))
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

// restoreSkill brings a soft-deleted skill back. **No `--yes`**: the action is reversible in the
// direction that matters — the skill can be deleted again from the console — and it changes
// visibility back to what it was rather than opening anything new.
func restoreSkill(ctx context.Context, args []string) error {
	address := ""
	for _, arg := range args {
		switch arg {
		default:
			if strings.HasPrefix(arg, "-") {
				return unknownFlag("skill restore", arg)
			}
			if address != "" {
				return usagef("%s", usageAt("skill restore"))
			}
			address = arg
		}
	}
	if address == "" {
		return usagef("%s", usageAt("skill restore"))
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

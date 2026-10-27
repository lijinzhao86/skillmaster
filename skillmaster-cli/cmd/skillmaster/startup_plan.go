package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/local"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/prompt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/updater"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/version"
)

// planItem is one line of the read-only checklist. The status is internal: the printed output uses
// ✓ for done, numbers for todo, and separate lines for skipped/unknown.
type planItem struct {
	name   string
	status string // "done", "todo", "skip", "unknown"
	detail string
}

// isReadOnlyPlan is the one place that knows a command promises to write nothing. `run` uses it to
// skip the stale-block notice and the auto-updater, both of which write.
func isReadOnlyPlan(args []string) bool {
	if len(args) == 0 || args[0] != "startup" {
		return false
	}
	for _, arg := range args[1:] {
		if arg == "--plan" {
			return true
		}
	}
	return false
}

// parseStartupArgs separates the read-only plan from the journey. `--agent` and `--file` only make
// sense with `--plan`; keeping that rule here means the normal startup path cannot accidentally
// accept them and ignore them.
func parseStartupArgs(args []string) (plan bool, agent, file string, err error) {
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--plan":
			plan = true
		case "--agent":
			value, valueErr := flagValue(args, i, "--agent", "startup")
			if valueErr != nil {
				return false, "", "", valueErr
			}
			agent = value
			i++
		case "--file":
			value, valueErr := flagValue(args, i, "--file", "startup")
			if valueErr != nil {
				return false, "", "", valueErr
			}
			file = value
			i++
		default:
			return false, "", "", unknownFlag("startup", args[i])
		}
	}
	if !plan && (agent != "" || file != "") {
		return false, "", "", usagef("startup 的 --agent / --file 只和 --plan 一起用。%s", usageAt("startup"))
	}
	return plan, agent, file, nil
}

// startupPlan is the read-only answer to "what is left". It never logs in, fetches the journey,
// pins a version, opens a browser, or writes a state file.
func startupPlan(ctx context.Context, agent, file string) error {
	fmt.Fprintln(os.Stderr, "正在检查你的电脑和 SkillMaster…")

	skills, err := local.Scan(nil)
	if err != nil {
		return fmt.Errorf("扫描本机技能：%w", err)
	}
	localNames := make(map[string]struct{}, len(skills))
	for _, skill := range skills {
		localNames[skill.Name] = struct{}{}
	}

	server := config.Server()
	store, _ := openStore(server)
	stored, found, loadErr := store.Load()
	hasCredential := loadErr == nil && found && stored.AccessToken != ""

	var w whereabouts
	var serverErr error
	serverKnown := false
	if hasCredential {
		client := api.Client{BaseURL: server, Token: stored.AccessToken}
		serverCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
		defer cancel()
		w, serverErr = gatherWhereaboutsFrom(serverCtx, client, skills)
		if serverErr == nil {
			serverKnown = true
		} else {
			w = whereabouts{local: localNames}
		}
	} else {
		w = whereabouts{local: localNames}
		if loadErr != nil {
			serverErr = loadErr
		}
	}

	dir, dirErr := config.Dir()
	items := []planItem{
		planCLI(ctx),
		planLogin(hasCredential, serverKnown, serverErr, w),
		planSubmit(hasCredential, serverKnown, len(localNames), w),
		planPublish(hasCredential, serverKnown, len(localNames), w),
		planPromptItem(dir, dirErr, file, agent),
		planPermission(hasCredential, serverKnown, len(localNames), w),
		planRetire(hasCredential, serverKnown, len(localNames), w),
	}
	stdout, next := renderPlan(items)
	fmt.Print(stdout)
	fmt.Fprintf(os.Stderr, "下一步：%s\n", next)
	return nil
}

// planCLI asks the same question `update --check` asks, without the updater's write path. A check
// that fails is "暂时查不到", not "有新版本" and not a failure of the whole plan.
func planCLI(ctx context.Context) planItem {
	item := planItem{name: "小工具"}
	if version.Version == "dev" || updateBase() == "" {
		item.status = "done"
		item.detail = "小工具已经装好（开发构建，不检查新版本）"
		return item
	}
	checkCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	remote, err := updater.Check(checkCtx, updateBase(), &http.Client{Timeout: 2 * time.Second})
	if err != nil {
		item.status = "unknown"
		item.detail = "小工具已经装好；暂时查不到新版本"
		return item
	}
	current, err := updater.ParseRelease(version.String())
	if err != nil {
		item.status = "unknown"
		item.detail = "小工具已经装好；暂时查不到新版本"
		return item
	}
	if updater.Newer(current, remote) {
		item.status = "todo"
		item.detail = "小工具有新版本，先更新一下"
		return item
	}
	item.status = "done"
	item.detail = "小工具已经装好，是最新版"
	return item
}

func planLogin(hasCredential, serverKnown bool, serverErr error, w whereabouts) planItem {
	item := planItem{name: "登录"}
	if !hasCredential {
		item.status = "todo"
		item.detail = "登录或注册"
		return item
	}
	if serverKnown {
		item.status = "done"
		item.detail = "已经登录"
		if w.user != "" {
			item.detail = "已经登录（" + w.user + "）"
		}
		return item
	}
	if errors.Is(serverErr, api.ErrUnauthorized) {
		item.status = "todo"
		item.detail = "登录过期了，重新登录一下"
		return item
	}
	item.status = "unknown"
	item.detail = "登录了，但服务端暂时读不到"
	return item
}

func planSubmit(hasCredential, serverKnown bool, localCount int, w whereabouts) planItem {
	item := planItem{name: "传技能"}
	if !hasCredential {
		item.status = "unknown"
		item.detail = "登录后检查"
		return item
	}
	if !serverKnown {
		item.status = "unknown"
		item.detail = "暂时读不到"
		return item
	}
	if localCount == 0 {
		item.status = "skip"
		item.detail = "本机没有可上传的技能"
		return item
	}
	if n := w.unmoved(); n > 0 {
		item.status = "todo"
		item.detail = fmt.Sprintf("你电脑上还有 %d 个技能没传", n)
		return item
	}
	item.status = "done"
	item.detail = "你电脑上的技能都已经在 SkillMaster 上了"
	return item
}

func planPublish(hasCredential, serverKnown bool, localCount int, w whereabouts) planItem {
	item := planItem{name: "正式上线"}
	if !hasCredential {
		item.status = "unknown"
		item.detail = "登录后检查"
		return item
	}
	if !serverKnown {
		item.status = "unknown"
		item.detail = "暂时读不到"
		return item
	}
	if localCount == 0 {
		item.status = "skip"
		item.detail = "本机没有技能，不用正式上线"
		return item
	}
	if w.drafts > 0 {
		item.status = "todo"
		item.detail = fmt.Sprintf("有 %d 份草稿在等正式上线", w.drafts)
		return item
	}
	item.status = "done"
	item.detail = "没有等着正式上线的草稿"
	return item
}

// planPermission is the one-time ask that lets the agent use SkillMaster's own folder without a
// prompt every time. It is only needed before the optional cleanup; the CLI cannot see the agent's
// permission system, so this item is a reminder, never a claim that it is done.
func planPermission(hasCredential, serverKnown bool, localCount int, w whereabouts) planItem {
	item := planItem{name: "让 AI 用 SkillMaster 自己的文件夹"}
	if !hasCredential {
		item.status = "unknown"
		item.detail = "登录后检查"
		return item
	}
	if !serverKnown {
		item.status = "unknown"
		item.detail = "暂时读不到"
		return item
	}
	if localCount == 0 || w.online() == 0 {
		item.status = "done"
		item.detail = "没有要清理的，不用加"
		return item
	}
	item.status = "skip"
	item.detail = "清理电脑上原来那份时才需要；只给 SkillMaster 自己的文件夹"
	return item
}

func planRetire(hasCredential, serverKnown bool, localCount int, w whereabouts) planItem {
	item := planItem{name: "电脑上原来的那份"}
	if !hasCredential {
		item.status = "unknown"
		item.detail = "登录后检查"
		return item
	}
	if !serverKnown {
		item.status = "unknown"
		item.detail = "暂时读不到"
		return item
	}
	if localCount == 0 || w.online() == 0 {
		item.status = "done"
		item.detail = "电脑上没有要清理的旧副本"
		return item
	}
	item.status = "skip"
	item.detail = "可选，以后也能做"
	return item
}

// planPromptItem checks the current AI's instruction file without writing. `--file` wins over
// `--agent`; without either, the plan can say how many files this machine wrote but not which one is
// this AI's. That is a todo, not a guess.
func planPromptItem(dir string, dirErr error, file, agent string) planItem {
	item := planItem{name: "让 AI 先来 SkillMaster 找技能"}
	if dirErr != nil {
		item.status = "todo"
		item.detail = promptLine("读不到这台电脑上的设置")
		return item
	}
	store, has, err := prompt.LoadStore(dir)
	if err != nil {
		item.status = "todo"
		item.detail = promptLine("读不到这台电脑上的设置")
		return item
	}
	records := store.Files
	if file != "" {
		path, warnings, err := resolveInstallPath(file, false)
		if err != nil {
			item.status = "todo"
			item.detail = promptLine("这个位置不能写：" + plainPathProblem(err))
			return item
		}
		item = inspectPromptFile(path, item.name, records)
		if len(warnings) > 0 {
			if item.status == "done" {
				item.status = "todo"
				item.detail = promptLine("这个位置只对当前项目生效；我们想让它对整台电脑生效")
			} else {
				item.detail += "；" + strings.Join(warnings, "；")
			}
		}
		others := len(records)
		if _, found := prompt.Find(records, path); found {
			others--
		}
		if others > 0 {
			item.detail += fmt.Sprintf("；记录里还有 %d 个别的文件", others)
		}
		return item
	}
	if agent != "" {
		matches := make([]prompt.Record, 0, len(records))
		for _, record := range records {
			if record.Agent == agent {
				matches = append(matches, record)
			}
		}
		switch len(matches) {
		case 0:
			item.status = "todo"
			item.detail = promptLine("还没写")
		case 1:
			item = inspectPromptFile(matches[0].File, item.name, records)
		default:
			item.status = "todo"
			item.detail = promptLine(fmt.Sprintf("这个 AI 写过 %d 份；告诉我用哪一份", len(matches)))
		}
		return item
	}
	if !has || len(records) == 0 {
		item.status = "todo"
		item.detail = promptLine("还没写")
		return item
	}
	item.status = "todo"
	item.detail = promptLine(fmt.Sprintf("这台电脑上写过 %d 份；告诉我这个 AI 用哪一份", len(records)))
	return item
}

// plainPathProblem turns the install path checks into the checklist's plain words. The error itself
// carries the path and the flag names; the checklist must not (v26 acceptance 17).
func plainPathProblem(err error) string {
	message := err.Error()
	switch {
	case strings.Contains(message, "是一个目录"):
		return "这个位置是个文件夹，不是说明文件；换一个文件"
	case strings.Contains(message, "上级目录不存在"):
		return "这个位置上一层的文件夹不存在；先建好，或换一个文件"
	case strings.Contains(message, "不是目录"):
		return "这个位置上一层的不是文件夹；换一个文件"
	case strings.Contains(message, "不在你的主目录下"):
		return "这个位置不在你的主目录里；应该放在整台电脑都能用的地方"
	case strings.Contains(message, "找不到你的主目录"):
		return "找不到你的主目录；问一下用户文件放在哪"
	case strings.Contains(message, "链接"):
		return "这个位置打不开；换一个文件"
	default:
		return "这个位置不能写；换一个文件"
	}
}

func inspectPromptFile(path, name string, records []prompt.Record) planItem {
	item := planItem{name: name}
	body, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		item.status = "todo"
		item.detail = name + "：要加一段说明"
		return item
	}
	if err != nil {
		item.status = "todo"
		item.detail = name + "：读不到这个文件"
		return item
	}
	region, err := prompt.Locate(path, body)
	if errors.Is(err, prompt.ErrMissing) {
		item.status = "todo"
		item.detail = name + "：要加一段说明"
		return item
	}
	var ambiguous *prompt.AmbiguousError
	if errors.As(err, &ambiguous) {
		item.status = "todo"
		item.detail = name + "：文件里有重复的说明，先修好"
		return item
	}
	if err != nil {
		item.status = "todo"
		item.detail = name + "：读不到这段说明"
		return item
	}
	if !bytes.Equal(prompt.Normalise(body[region.Start:region.End]), prompt.Normalise(prompt.Block())) {
		item.status = "todo"
		item.detail = name + "：这段说明是旧版，要更新"
		return item
	}
	record, found := prompt.Find(records, path)
	if !found {
		item.status = "todo"
		item.detail = name + "：已经有这段说明，只记下位置"
		return item
	}
	if record.Digest != prompt.Digest() {
		item.status = "todo"
		item.detail = name + "：这段说明是旧版，要更新"
		return item
	}
	item.status = "done"
	item.detail = "已经写好，是最新版"
	return item
}

func promptLine(detail string) string {
	return "让 AI 先来 SkillMaster 找技能：" + detail
}

// renderPlan is the user-facing half: todo first, completed collapsed, skipped and unknown named
// so the plan never says "0" about something it could not read.
func renderPlan(items []planItem) (string, string) {
	var todo, done, skipped, unknown []planItem
	for _, item := range items {
		switch item.status {
		case "todo":
			todo = append(todo, item)
		case "done":
			done = append(done, item)
		case "skip":
			skipped = append(skipped, item)
		case "unknown":
			unknown = append(unknown, item)
		}
	}

	var b strings.Builder
	b.WriteString("SkillMaster 安装清单\n")
	b.WriteString("让这台电脑上的技能，在任何 AI 里都能用。\n\n")
	if len(todo) > 0 {
		fmt.Fprintf(&b, "还差 %d 件：\n", len(todo))
		for i, item := range todo {
			fmt.Fprintf(&b, "%d. %s\n", i+1, item.detail)
		}
		b.WriteString("\n")
	}
	if len(done) > 0 {
		names := make([]string, 0, len(done))
		note := ""
		for _, item := range done {
			names = append(names, item.name)
			// The prompt item carries a note about the other files this machine has written. Keep it
			// visible after the item becomes done; the collapsed line prints names only.
			if item.name == "让 AI 先来 SkillMaster 找技能" {
				if i := strings.Index(item.detail, "记录里还有"); i >= 0 {
					note = item.detail[i:]
				}
			}
		}
		fmt.Fprintf(&b, "已经好了（%d 件）：%s。\n", len(done), strings.Join(names, "、"))
		if note != "" {
			fmt.Fprintf(&b, "%s。\n", note)
		}
	}
	if len(skipped) > 0 {
		parts := make([]string, 0, len(skipped))
		for _, item := range skipped {
			parts = append(parts, item.name+"（"+item.detail+"）")
		}
		fmt.Fprintf(&b, "跳过（%d 件）：%s。\n", len(skipped), strings.Join(parts, "、"))
	}
	if len(unknown) > 0 {
		parts := make([]string, 0, len(unknown))
		for _, item := range unknown {
			parts = append(parts, item.name+"（"+item.detail+"）")
		}
		fmt.Fprintf(&b, "还要确认：%s。\n", strings.Join(parts, "、"))
	}
	if len(todo) == 0 {
		// A checklist with unread items is not complete. Saying "全部好了" beside "还要确认" is the
		// one lie the plan must never tell: the person may leave with several required steps undone.
		if len(unknown) > 0 {
			b.WriteString("\n还有几件要先确认；现在还不能算完成。等网络或 SkillMaster 恢复后再看一次。\n")
			return b.String(), "稍后再检查一次"
		}
		if hasPlanItem(skipped, "电脑上原来的那份") {
			b.WriteString("\n必做项都好了；要不要清理电脑上原来那份？（可选）\n")
			return b.String(), "steps/06-permission.md"
		}
		b.WriteString("\n全部好了。\n")
		return b.String(), "旅程完成"
	}
	b.WriteString("\n要从第 1 件开始吗？\n")
	return b.String(), planNextStep(todo)
}

func hasPlanItem(items []planItem, name string) bool {
	for _, item := range items {
		if item.name == name {
			return true
		}
	}
	return false
}

func planNextStep(todo []planItem) string {
	if len(todo) == 0 {
		return "旅程完成"
	}
	switch todo[0].name {
	case "小工具":
		return "先更新小工具"
	case "登录":
		return "先登录"
	case "传技能":
		return "steps/01-import.md"
	case "正式上线":
		return "steps/02-publish.md"
	case "让 AI 先来 SkillMaster 找技能":
		return "steps/03-prompt.md"
	case "让 AI 用 SkillMaster 自己的文件夹":
		return "steps/06-permission.md"
	case "电脑上原来的那份":
		return "steps/07-retire.md"
	default:
		// Every todo name has a case; keep the prompt step as the conservative answer.
		return "steps/03-prompt.md"
	}
}

// promptRecordLine is the local, best-effort answer to "have I already configured another AI?".
// It is on stderr because stdout is the journey, and it never asks the server.
func promptRecordLine() (string, bool) {
	dir, err := config.Dir()
	if err != nil {
		return "", false
	}
	store, has, err := prompt.LoadStore(dir)
	if err != nil || !has || len(store.Files) == 0 {
		return "", false
	}
	seen := map[string]bool{}
	labels := make([]string, 0, len(store.Files))
	for _, record := range store.Files {
		label := record.Agent
		if label == "" {
			// A v25 record has no label. The path is an internal word the person should not be asked
			// to read; one "未命名" is the honest answer for every such record.
			label = "未命名"
		}
		if seen[label] {
			continue
		}
		seen[label] = true
		labels = append(labels, label)
	}
	if len(labels) == 0 {
		return "", false
	}
	return "这台电脑上写过这段说明的 AI：" + strings.Join(labels, "、") +
		"。如果你不是其中之一，按第三步再写一份。", true
}

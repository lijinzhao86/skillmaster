// doctor: say what this machine can and cannot do, in one screen.

package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/agentconfig"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/sandbox"
)

// doctorCmd answers the question a failing command cannot: which of the four things this CLI needs
// is the one that is not working.
//
// **Bare `doctor` still only prints.** `--fix` is the explicit command that writes (ADR 0186); its
// scope is the state directory and the two hosts, nothing else.
func doctorCmd(ctx context.Context, args []string) error {
	fix, undo, dryRun, err := parseDoctorArgs(args)
	if err != nil {
		return err
	}
	if fix {
		return doctorFix(undo, dryRun)
	}
	fmt.Println("SkillMaster 自检")

	var problems []string
	report := func(name, state, detail string) {
		fmt.Printf("  %-10s %-4s %s\n", name, state, detail)
		if state != "ok" {
			problems = append(problems, name)
		}
	}

	// 1. State directory: the one a sandbox takes away first.
	stateDir, stateErr := config.Dir()
	if stateErr != nil {
		report("状态目录", "失败", stateErr.Error())
	} else {
		detail := stateDir
		if note := config.DirNote(); note != "" {
			detail = note
		}
		report("状态目录", "ok", detail)
	}

	// 2. Credential: what will be presented, and where it lives.
	server := config.Server()
	presented := "未登录"
	if os.Getenv("SKILLMASTER_API_KEY") != "" {
		presented = "环境变量 SKILLMASTER_API_KEY"
	} else if stor, notice := credentials.Open(credentialLocation(server), server); stor != nil {
		// The notice is ADR 0025's announcement that this machine keeps the credential in a plain
		// file because the keychain is not available — the answer to "why is my credential here"
		// and, in a sandbox, to "why did the keychain not take it". It is read even when the file
		// already exists, because that is exactly the state a degraded `login` leaves behind: the
		// file is there *and* the reason it is there is the half worth printing.
		if notice != "" {
			presented = notice
		} else if where := stor.Where(); credentialFilePresent(where) {
			// `Where` names the copy that exists, which in a sandbox may be the older one in a
			// directory this run cannot write. A zero-byte file is the tombstone `logout` leaves
			// (ADR 0101), so it is not a credential and is not reported as one.
			presented = "文件 " + where
		} else {
			// **No read here.** `Where` is the place a login will write; reading the keychain to
			// answer a diagnostic line can raise an authorization dialog in front of an agent.
			if _, err := os.Stat(where); err == nil {
				presented = "文件 " + where + "（是空的，像已退出登录；当前凭据也可能在系统钥匙串里）"
			} else {
				presented = "文件 " + where + "（当前没有这个文件；登录默认写在这里）"
			}
		}
	}
	report("凭据", "ok", presented)

	// 3. Cache: where `run` puts a downloaded version.
	cacheRoot := filepath.Join(os.TempDir(), config.Name, "cache")
	if err := writableDir(cacheRoot); err != nil {
		report("缓存目录", "失败", fmt.Sprintf("%s：%v", cacheRoot, err))
	} else {
		report("缓存目录", "ok", fmt.Sprintf("%s（已缓存 %d 个版本）", cacheRoot, countDirs(cacheRoot)))
	}

	// 4. Skills directory: where an installed skill has to land.
	//
	// **A directory that is not there is not a problem, and it is not created here either.** This
	// command prints; a machine that has never installed a skill should not gain a directory, and
	// least of all one with a probe file in it — a non-skill file in the place an agent scans for
	// skills is a footprint nobody asked for.
	if skillsDir, err := config.SkillsDir(); err != nil {
		report("技能目录", "失败", err.Error())
	} else if !isDir(skillsDir) {
		report("技能目录", "ok", skillsDir+"（还没有，安装技能时会创建）")
	} else if !writableByMode(skillsDir) {
		// **A mode-bit answer rather than a probe file.** This directory belongs to the agent, not
		// to this CLI: writing a fixed-name file into the place an agent scans for skills is a
		// footprint nobody asked for, and it is the kind that survives (ADR 0101 — the CLI never
		// deletes). The bit says "no" in exactly the case that matters, a read-only mount.
		report("技能目录", "只读", skillsDir+"（目录没有写权限）")
	} else {
		report("技能目录", "ok", skillsDir)
	}

	// 5. Network: one request, three seconds. Reaching the server at all is what matters; a 404
	// means the name resolves and something answers.
	report("网络", networkState(ctx, server), server)

	// 6. Sandbox: the markers a client sets, plus the one inference this command can still make —
	// a state directory that will not take a write. **The detection is the sandbox package's**
	// (ADR 0167): a client's name only chooses which snippet is printed, never a code path.
	env := sandbox.Detect()
	switch {
	case env.Sandboxed:
		report("沙箱", "注意", env.Summary())
	case config.DirNote() != "":
		report("沙箱", "注意", "状态目录写不进去，多半在沙箱里（客户端没有自报）")
	default:
		// **Not "no sandbox".** Several clients (WorkBuddy among them) run one without setting any
		// marker; saying "没有检测到" reads as "you are not in one", which this command cannot know.
		report("沙箱", "ok", "客户端没有自报")
	}

	if env.Sandboxed || config.DirNote() != "" || len(problems) > 0 {
		printSandboxConfig(env.Client, stateDir, cacheRoot)
	}
	if len(problems) > 0 {
		fmt.Printf("\n有 %d 项没通过：%s\n", len(problems), strings.Join(problems, "、"))
	}
	return nil
}

// parseDoctorArgs reads the three flags --fix takes. `--undo` and `--dry-run` only mean something
// with `--fix`, so they are refused on their own rather than silently ignored.
func parseDoctorArgs(args []string) (fix, undo, dryRun bool, err error) {
	for _, arg := range args {
		switch arg {
		case "--fix":
			fix = true
		case "--undo":
			undo = true
		case "--dry-run":
			dryRun = true
		default:
			return false, false, false, usagef("%s", usageAt("doctor"))
		}
	}
	if !fix && (undo || dryRun) {
		return false, false, false, usagef("%s", usageAt("doctor"))
	}
	return fix, undo, dryRun, nil
}

// doctorFix writes the narrow allowlist into every client this machine has (ADR 0186). It is meant
// to run outside the sandbox: most clients keep their own settings out of reach of a sandboxed
// command, so the caller is the installer, the person's terminal, or an agent that asked for this
// one command to run outside.
func doctorFix(undo, dryRun bool) error {
	home, err := os.UserHomeDir()
	if err != nil {
		return err
	}
	state := stateDirToAllow()
	if state == "" {
		return fmt.Errorf("找不到用户配置目录，没法确定要放行的状态目录")
	}
	want := agentconfig.Want{
		Home:       home,
		WritePaths: []string{state},
		Hosts:      sandbox.Hosts(),
	}
	changes := agentconfig.Apply(want, undo, dryRun)
	switch {
	case dryRun:
		fmt.Println("SkillMaster 沙箱白名单（只预览，不落盘）")
	case undo:
		fmt.Println("SkillMaster 沙箱白名单（撤销）")
	default:
		fmt.Println("SkillMaster 沙箱白名单")
	}
	for _, change := range changes {
		fmt.Printf("  %-14s %s\n", change.Client, describeChange(change, undo, dryRun))
		if change.Err != nil {
			// **The fragment is the next step.** A parse error names a position; the lines around
			// it are what the person can actually edit.
			printFragment(change.File)
		}
	}
	failed := failedChanges(changes)
	// **An undo never opens a switch**, so the "opened / already on" split only describes a fix.
	if !undo {
		switched, already := networkSwitchClients(changes)
		if len(switched) > 0 {
			// ADR 0186: for these clients the switch is not a domain list, and saying otherwise
			// would misstate what was granted.
			verb := "这次打开的是"
			if dryRun {
				verb = "将会打开的是"
			}
			fmt.Printf("%s 没有域名白名单：%s该客户端的全部出站网络。\n", strings.Join(switched, "、"), verb)
		}
		if len(already) > 0 {
			fmt.Printf("%s 没有域名白名单：网络总开关已经是开启状态，效果仍是全部出站网络。\n",
				strings.Join(already, "、"))
		}
		codexWorkspaceWarning(changes, dryRun)
		geminiContainerNote(changes, dryRun)
	}
	printNoConfigSurfaceClients()
	if dryRun && !undo && hasClient(changes, "Codex") {
		fmt.Println("注意：Codex 的 TOML 会重新排版，文件里的注释可能不保留。")
	}
	fmt.Println()
	if undo {
		if failed > 0 {
			// A failed client's `Removed` was computed but never written; do not claim otherwise.
			fmt.Println("撤销没有全部完成：下面列出的失败项没有改动。")
		} else {
			printUndoSummary(dryRun, totalRemoved(changes))
		}
	}
	if failed > 0 {
		if !undo {
			fmt.Println("在你自己的终端里跑一次：skmr doctor --fix（沙箱里通常写不了这些配置文件）")
		}
		return fixFailure(failed)
	}
	if !undo && !dryRun && anyChanged(changes) {
		fmt.Println("重启这些客户端后生效。要撤销：skmr doctor --fix --undo")
	}
	return nil
}

// printUndoSummary says what an undo did, or would do. **It never claims a write a dry run did not
// make**, and it names the one thing `--undo` cannot tell apart: a user's entry with the same value.
func printUndoSummary(dryRun bool, removed int) {
	switch {
	case removed == 0 && dryRun:
		fmt.Println("没有可撤销的条目（实际未改动）。")
	case removed == 0:
		fmt.Println("没有可撤销的条目。")
	case dryRun:
		fmt.Printf("将会撤销与 SkillMaster 写入值完全一致的条目（实际未改动）。"+
			"纯字符串条目无法区分来源，同值条目也会被移除；WorkBuddy 的域名规则按 id 以 %s- 开头识别。\n", config.LegacyName)
	default:
		fmt.Printf("已撤销与 SkillMaster 写入值完全一致的条目（WorkBuddy 的域名规则按 id 以 %s- 开头识别）。"+
			"纯字符串条目无法区分来源，同值条目也会被移除；"+
			"为放行而打开的开关（allowLocalBinding、networkAccess、sandbox_mode）与可能留下的空数组不会还原（ADR 0186）。\n", config.LegacyName)
	}
}

// totalRemoved counts only what was actually written: a client whose backup or write failed has a
// `Removed` list that never reached the file.
func totalRemoved(changes []agentconfig.Change) int {
	total := 0
	for _, change := range changes {
		if change.Err == nil {
			total += len(change.Removed)
		}
	}
	return total
}

func anyChanged(changes []agentconfig.Change) bool {
	for _, change := range changes {
		if change.Err == nil && change.Changed() {
			return true
		}
	}
	return false
}

// fixFailure is the exit path a write failure takes. It is a function so the mapping from "some
// clients failed" to "a non-zero status" can be tested without a real home directory.
func fixFailure(failed int) error {
	if failed == 0 {
		return nil
	}
	return fmt.Errorf("有 %d 个客户端没配上，其余已按上面的结果处理", failed)
}

// codexWorkspaceWarning says what the old Codex switch actually widens. **`workspace-write` is the
// workspace, not the state directory**: `writable_roots` adds the state directory on top, so a
// person who read "the SkillMaster folder" must see that the project becomes writable too.
// `--undo` removes arrays but does not restore the mode (ADR 0186 §后果).
func codexWorkspaceWarning(changes []agentconfig.Change, dryRun bool) {
	for _, change := range changes {
		if change.Client != "Codex" || change.Err != nil || !change.WorkspaceWrite {
			continue
		}
		toggled := false
		for _, toggle := range change.Toggled {
			if toggle == "sandbox_mode" {
				toggled = true
			}
		}
		verb := "已经是"
		if dryRun {
			verb = "将会是"
		} else if toggled {
			verb = "这次打开成了"
		}
		fmt.Printf("Codex 旧式：sandbox_mode %sworkspace-write，Codex 的工作区本身可写，"+
			"不只是 SkillMaster 的状态目录；--undo 不还原这个开关。\n", verb)
		return
	}
}

// geminiContainerNote keeps "written" from reading as "writable". On Linux the container sandbox
// mounts `allowedPaths` read-only; writing the key there does not let the credential be saved, and
// without this sentence the person would read the failure as a SkillMaster bug.
func geminiContainerNote(changes []agentconfig.Change, dryRun bool) {
	// The read-only mount is Linux-container behaviour; macOS uses Seatbelt and the sentence
	// would be false there.
	if runtime.GOOS != "linux" {
		return
	}
	for _, change := range changes {
		if change.Client != "Gemini" || !change.Processed || change.Err != nil {
			continue
		}
		verb := "写进"
		if dryRun {
			verb = "会写进"
		}
		fmt.Printf("Gemini：如果你在 Linux 上用它的容器沙箱，allowedPaths 是只读挂载；%s配置"+
			"不等于目录可写，要可写需 SANDBOX_MOUNTS=...:rw（本版不写）。\n", verb)
		return
	}
}

// networkSwitchClients splits the clients whose grant is a total network switch rather than a
// domain list (Codex old form, Gemini) into "opened by this run" and "already on". Both are worth
// saying: the cost exists whether or not this run flipped the switch, and Codex's `sandbox_mode`
// flip is what makes an already-set `network_access` take effect.
func networkSwitchClients(changes []agentconfig.Change) (switched, already []string) {
	for _, change := range changes {
		if !change.Processed || change.Err != nil || (change.Client != "Codex" && change.Client != "Gemini") {
			continue
		}
		opened := false
		for _, toggle := range change.Toggled {
			if toggle == "network_access" || toggle == "networkAccess" || toggle == "sandbox_mode" {
				opened = true
				break
			}
		}
		if opened {
			switched = append(switched, change.Client)
		} else {
			already = append(already, change.Client)
		}
	}
	return switched, already
}

// stateDirToAllow is the state directory to let through: `SKILLMASTER_HOME` when it is set (it is
// still the first choice for where a credential lives, PRD #1), and the ordinary directory
// otherwise. The sandbox's temporary fallback is never written into a client's allowlist.
func stateDirToAllow() string {
	if dir := os.Getenv(config.HomeEnv); dir != "" {
		// An allowlist wants an absolute path; a relative SKILLMASTER_HOME is resolved against the
		// directory this command happens to run from, which is not what the client will see.
		if absolute, err := filepath.Abs(dir); err == nil {
			return absolute
		}
		return dir
	}
	return config.OrdinaryDir()
}

// printNoConfigSurfaceClients is PRD #10: the clients this command cannot write for get a sentence
// of their own, so a person who uses only one of them is not left thinking there was nothing to do.
func printNoConfigSurfaceClients() {
	for _, line := range noConfigSurfaceLines() {
		fmt.Println(line)
	}
}

// noConfigSurfaceLines is the text as data, so a test can read the client names without capturing
// stdout.
func noConfigSurfaceLines() []string {
	return []string{
		"",
		"没有可写的权限键：Cline、Roo Code、Aider、Amp、旧 Windsurf——只能靠客户端的命令级审批。",
		"没有域名键或没有本地配置面：OpenHands（挂载卷）、DSH（只有文件键 additionalWritableRoots）、GrokBot（企业面板）。",
	}
}

func hasClient(changes []agentconfig.Change, name string) bool {
	for _, change := range changes {
		if change.Client == name && change.Err == nil && change.Changed() {
			return true
		}
	}
	return false
}

// failedChanges counts the clients whose configuration could not be read, backed up or written.
// A deliberate skip is not a failure; a line this command could not do what it said is.
func failedChanges(changes []agentconfig.Change) int {
	failed := 0
	for _, change := range changes {
		if change.Err != nil {
			failed++
		}
	}
	return failed
}

// printFragment shows the first lines of a file this command refused to parse.
func printFragment(path string) {
	blob, err := os.ReadFile(path)
	if err != nil {
		return
	}
	lines := strings.Split(strings.TrimRight(string(blob), "\n"), "\n")
	if len(lines) > 5 {
		lines = lines[:5]
	}
	fmt.Println("    文件开头（修好后重跑）：")
	for _, line := range lines {
		fmt.Printf("    | %s\n", line)
	}
}

func describeChange(change agentconfig.Change, undo, dryRun bool) string {
	switch {
	case change.Err != nil:
		prefix := change.Skip
		if prefix == "" {
			prefix = "没配上"
		}
		return fmt.Sprintf("%s：%v", prefix, change.Err)
	case change.Skip != "":
		return change.Skip
	case !change.Changed():
		return "已是最新"
	}
	if undo {
		action := "已撤销"
		if dryRun {
			action = "将撤销"
		}
		return action + " " + strings.Join(change.Removed, "、")
	}
	var parts []string
	if len(change.Added) > 0 {
		parts = append(parts, strings.Join(change.Added, "、"))
	}
	if len(change.Toggled) > 0 {
		parts = append(parts, "打开 "+strings.Join(change.Toggled, "、"))
	}
	action := "已写入"
	if dryRun {
		action = "将写入"
	}
	return action + " " + strings.Join(parts, "；")
}

func networkState(ctx context.Context, server string) string {
	client := &http.Client{Timeout: 3 * time.Second}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, server, nil)
	if err != nil {
		return "失败 " + err.Error()
	}
	response, err := client.Do(request)
	if err != nil {
		return "失败 " + err.Error()
	}
	response.Body.Close()
	return "ok"
}

// printSandboxConfig is the part a person copies. It is printed, not applied.
//
// **Two halves, two owners.** The write paths are this command's: only it knows the state and cache
// directories it just probed. The network and loopback snippets come from internal/sandbox, which
// also produces the ones a failed command prints — one table, so doctor and an error can never name
// different domains or different keys for the same client (ADR 0167).
func printSandboxConfig(client sandbox.Client, stateDir, cacheRoot string) {
	for _, line := range sandboxConfigLines(client, stateDir, cacheRoot) {
		fmt.Println(line)
	}
}

// sandboxConfigLines is what printSandboxConfig prints, as data so a test can read it.
//
// **One client, one snippet.** A recognised client gets its own; anything else gets the four known
// ones plus the generic sentence, because a person running doctor outside an agent is asking in
// advance and has not told us which client they use. The `excludedCommands` line is Claude Code's
// escape hatch only — printing it to a Codex user would name a file and a key that do not exist.
func sandboxConfigLines(client sandbox.Client, stateDir, cacheRoot string) []string {
	lines := []string{
		"",
		"如果上一步是沙箱拦的，先放行写路径（各客户端的写法不同）：",
		fmt.Sprintf("  Claude Code  filesystem.allowWrite = [%q, %q]", stateDir, cacheRoot),
		fmt.Sprintf("  Codex        sandbox_workspace_write.writable_roots = [%q, %q]", stateDir, cacheRoot),
		fmt.Sprintf("  Cursor       additionalReadwritePaths = [%q, %q]", stateDir, cacheRoot),
		fmt.Sprintf("  WorkBuddy    sandbox.extraAllowWrite = [%q, %q]", stateDir, cacheRoot),
		"",
		"网络与回环：",
	}
	if client == sandbox.Unknown {
		for _, known := range []sandbox.Client{sandbox.ClaudeCode, sandbox.Codex, sandbox.Cursor, sandbox.Gemini} {
			lines = append(lines, "", sandbox.Snippets(known))
		}
		// The generic snippet carries the one measured fact about a client with no marker:
		// WorkBuddy 5.8.3 allows TLS, loopback and network by default.
		lines = append(lines, "", sandbox.Snippets(sandbox.Unknown))
	} else {
		lines = append(lines, "", sandbox.Snippets(client))
	}
	if client == sandbox.ClaudeCode || client == sandbox.Unknown {
		lines = append(lines, fmt.Sprintf(
			"\nClaude Code 里仍然连不上，就把这条命令放到沙箱外跑：\"excludedCommands\": [%q]", config.Name+" *"))
	}
	return append(lines,
		"\n登录、登出、更新这三件事可能仍然要在你自己的终端里跑一次：它们要写状态目录、替换二进制，"+
			"或者在沙箱里连不上回环。")
}

func writableDir(dir string) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	probe, err := os.OpenFile(filepath.Join(dir, ".write-probe"),
		os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	return probe.Close()
}

func isDir(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

// credentialFilePresent distinguishes a credential from the zero-byte tombstone `logout` leaves.
func credentialFilePresent(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular() && info.Size() > 0
}

// writableByMode answers "may this user write here" from the directory's own bits.
//
// It is an inference, not a proof: an ACL or a read-only mount can say otherwise while the owner
// bit is set. That is the right trade for a directory this CLI does not own — the alternative is a
// probe file left behind forever, and the question is only ever asked to explain a failure.
func writableByMode(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir() && info.Mode().Perm()&0o200 != 0
}

func countDirs(root string) int {
	entries, err := os.ReadDir(root)
	if err != nil {
		return 0
	}
	count := 0
	for _, entry := range entries {
		if entry.IsDir() {
			count++
		}
	}
	return count
}

package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/prompt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/version"
)

// noteStaleBlock is the one-line notice that the person's file has an older rule block.
//
// **Only the record is compared, never the file.** Reading the instruction file on every command
// would put a file read the person did not ask for in front of every command — and the answer it
// would produce (their edits) is not the question. `prompt status` reads the file; this only says
// "the binary now carries a different block than the one this machine wrote".
//
// **Once per new block.** A person who decides not to update should not be told again on every
// command; the notice marks the digest as seen, and `prompt status` still answers whenever asked.
func noteStaleBlock(args []string) {
	if len(args) == 0 {
		return
	}
	// Not a `switch args[0]`: that shape means "this function dispatches commands", and
	// `TestTheCommandTreeMatchesTheSource` would rightly demand a declaration for it.
	if args[0] == "--version" || args[0] == "help" || args[0] == "prompt" {
		return
	}
	for _, arg := range args {
		if arg == "--help" || arg == "-h" {
			return
		}
	}
	dir, err := config.Dir()
	if err != nil {
		return
	}
	store, has, err := prompt.LoadStore(dir)
	if err != nil || !has {
		return
	}
	digest := prompt.Digest()
	stale := make([]int, 0, len(store.Files))
	for i, record := range store.Files {
		if record.Digest == digest || record.Notified == digest {
			continue
		}
		// **A file that is not there has no old block.** One stat, no read: the content is still
		// `prompt status`'s job, but "deleted" and "stale" are different answers and only one of them
		// is worth a line on a command that has nothing to do with the block.
		if _, err := os.Stat(record.File); err != nil {
			continue
		}
		stale = append(stale, i)
	}
	if len(stale) == 0 {
		return
	}
	for _, i := range stale {
		store.Files[i].Notified = digest
	}
	_ = prompt.SaveStore(dir, store)
	if len(stale) == 1 {
		fmt.Fprintln(os.Stderr, "（系统提示词里的规则段是旧版：skillmaster prompt status 看差异，prompt update --yes 更新。）")
		return
	}
	fmt.Fprintf(os.Stderr, "（%d 份系统提示词里的规则段是旧版：skillmaster prompt status 看差异，prompt update --yes 更新。）\n", len(stale))
}

// `prompt` — the rule block that goes into an agent's instruction file.
//
// **The block belongs to this binary** (ADR 0102), so every question about it is local: which text
// it should be, whether the file has it, what the difference is. None of these commands talks to
// the server, needs a credential, or waits for input.
//
// **Nothing here prompts.** `update` without `--yes` prints the difference and exits 1 — the asking
// happens between two invocations, by whoever is driving the CLI. A command that sat waiting for a
// "y" would hang an agent in every sandbox there is (the same lesson v22's A4 recorded about the
// browser login).
func promptVerb(ctx context.Context, args []string) error {
	_ = ctx
	if len(args) == 0 {
		return usagef("%s", usageAt("prompt"))
	}
	switch args[0] {
	case "status":
		return promptStatus(args[1:])
	case "print":
		return promptPrint(args[1:])
	case "install":
		return promptInstall(args[1:])
	case "update":
		return promptUpdate(args[1:])
	case "uninstall":
		return promptUninstall(args[1:])
	default:
		return unknownName("prompt", args[0], childNames("prompt"))
	}
}

// promptStatus answers "is what is in the file what this binary carries".
//
// It exits 1 whenever the answer is not a plain yes — no record, file gone, markers gone, text
// differs — because in every one of those cases there is something for the person to do, and a
// script can tell without parsing the prose.
func promptStatus(args []string) error {
	if len(args) > 0 {
		return unknownFlag("prompt status", args[0])
	}
	fmt.Printf("这个二进制自带：%s\n", prompt.Digest())
	dir, err := config.Dir()
	if err != nil {
		return err
	}
	store, has, err := prompt.LoadStore(dir)
	if err != nil {
		return err
	}
	if !has || len(store.Files) == 0 {
		fmt.Println("本机还没有记录。把它写进你的指令文件：skillmaster prompt install --file <路径>")
		return &exitError{code: 1}
	}
	fmt.Printf("记录的文件（%d 份）：\n", len(store.Files))
	allOK := true
	changed := false
	for i, record := range store.Files {
		fmt.Printf("\n%d. %s", i+1, record.File)
		if record.Agent != "" {
			fmt.Printf("（agent：%s）", record.Agent)
		}
		fmt.Println()
		if record.Digest != "" {
			fmt.Printf("   记录里写的：%s\n", record.Digest)
		}
		if record.Version != "" && record.Version != version.String() {
			fmt.Printf("   写它的时候：%s\n", record.Version)
		}
		file, err := os.ReadFile(record.File)
		if err != nil {
			fmt.Printf("   读不到这个文件：%v\n", err)
			allOK = false
			continue
		}
		block, _, err := prompt.BlockIn(record.File, file)
		if errors.Is(err, prompt.ErrMissing) {
			fmt.Println("   这个文件里没有标记段。重新写入：skillmaster prompt install --file " + record.File)
			allOK = false
			continue
		}
		var ambiguous *prompt.AmbiguousError
		if errors.As(err, &ambiguous) {
			fmt.Printf("   %s\n", ambiguous.Error())
			allOK = false
			continue
		}
		if err != nil {
			fmt.Printf("   读标记段：%v\n", err)
			allOK = false
			continue
		}
		fmt.Printf("   文件里现在：%s\n", prompt.DigestOf(block))
		if bytes.Equal(block, prompt.Normalise(prompt.Block())) {
			fmt.Println("   一致。")
			// **The file is the truth, and the record follows it.** A record that is behind a file
			// the person fixed by hand would otherwise keep announcing a difference forever, and the
			// next update would still show the right diff but the wrong history.
			if record.Digest != prompt.Digest() || record.Version != version.String() {
				store.Files[i].Digest = prompt.Digest()
				store.Files[i].Version = version.String()
				store.Files[i].Notified = ""
				changed = true
			}
			continue
		}
		fmt.Println("   不一致——文件里是旧版：")
		printBlockDiff(string(block), string(prompt.Normalise(prompt.Block())))
		fmt.Println("   更新它：skillmaster prompt update")
		allOK = false
	}
	if changed {
		if err := prompt.SaveStore(dir, store); err != nil {
			return err
		}
	}
	if !allOK {
		return &exitError{code: 1}
	}
	return nil
}

// promptPrint writes the block this binary carries, verbatim.
//
// **The manual escape hatch, and the one that works offline.** A person who would rather paste it
// themselves, or whose sandbox refuses the write, gets exactly the bytes the updater would write.
func promptPrint(args []string) error {
	if len(args) > 0 {
		return unknownFlag("prompt print", args[0])
	}
	os.Stdout.Write(prompt.Block())
	return nil
}

// promptInstall writes the block into a file for the first time, and remembers where.
//
// **The place is chosen once, by the person.** The CLI has no way to know which instruction file an
// agent reads — there are dozens of conventions — and guessing would mean reading files nobody asked
// us to read. `--file` is that one piece of information, and the record is what makes every later
// update a one-word command.
func promptInstall(args []string) error {
	file := ""
	agent := ""
	confirmed := false
	force := false
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--file":
			value, err := flagValue(args, i, "--file", "prompt install")
			if err != nil {
				return err
			}
			file = value
			i++
		case "--agent":
			value, err := flagValue(args, i, "--agent", "prompt install")
			if err != nil {
				return err
			}
			agent = value
			i++
		case "--yes":
			confirmed = true
		case "--force":
			force = true
		default:
			return unknownFlag("prompt install", args[i])
		}
	}
	if file == "" {
		return usagef("prompt install 要用 --file 指定写进哪个文件。%s", usageAt("prompt install"))
	}
	path, warnings, err := resolveInstallPath(file, force)
	if err != nil {
		return err
	}

	existing, mode, exists, err := readMaybe(path)
	if err != nil {
		return err
	}
	if !exists {
		// New file: it is the person's instruction file, so it gets an ordinary file's permissions.
		// An existing empty file keeps whatever mode it already had.
		mode = 0o644
	}

	block := prompt.Block()
	region, locateErr := prompt.Locate(path, existing)
	switch {
	case locateErr == nil:
		if bytes.Equal(prompt.Normalise(existing[region.Start:region.End]), prompt.Normalise(block)) {
			// **Already there, and identical.** Record it and touch nothing: a person who pasted the
			// block by hand during the journey should not see their file rewritten to say the same
			// thing (their editor's undo history, their mtime, their trust).
			return saveBlockRecord(path, agent, fmt.Sprintf("已经是最新，只记下位置：%s", path))
		}
		if !confirmed {
			printInstallPlan("替换原有的一段", warnings)
			return &exitError{code: 1}
		}
		return writeBlock(path, existing, prompt.Replace(existing, region, block), mode, agent)
	case errors.Is(locateErr, prompt.ErrMissing):
		action := "追加"
		if !exists {
			action = "新建"
		}
		if !confirmed {
			printInstallPlan(action, warnings)
			return &exitError{code: 1}
		}
		return writeBlock(path, existing, prompt.Append(existing, block, prompt.UsesCRLF(existing)), mode, agent)
	default:
		return locateErr
	}
}

// resolveInstallPath applies the four generic checks without guessing whether the agent will read
// the file. It returns the path to write and the warnings that belong in the plan.
//
// **The home check follows symlinks.** A link inside the home that points outside would otherwise
// pass a string-prefix check and write somewhere the person did not name. The record keeps the path
// the person gave us; only the check and the write follow the link.
func resolveInstallPath(file string, force bool) (string, []string, error) {
	path, err := expandHome(file)
	if err != nil {
		return "", nil, err
	}
	path, err = filepath.Abs(path)
	if err != nil {
		return "", nil, fmt.Errorf("解析路径 %s：%w", file, err)
	}
	var warnings []string

	info, statErr := os.Stat(path)
	exists := statErr == nil
	switch {
	case statErr == nil && info.IsDir():
		return "", nil, fmt.Errorf("%s 是一个目录，不是指令文件。换一个文件路径。", path)
	case statErr != nil && !errors.Is(statErr, os.ErrNotExist):
		return "", nil, fmt.Errorf("读 %s 的信息：%w", path, statErr)
	}

	parent := filepath.Dir(path)
	parentInfo, err := os.Stat(parent)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return "", nil, fmt.Errorf("%s 的上级目录不存在：%s。先建好目录，或换一个路径。", path, parent)
		}
		return "", nil, fmt.Errorf("读 %s 的信息：%w", parent, err)
	}
	if !parentInfo.IsDir() {
		return "", nil, fmt.Errorf("%s 的上级 %s 不是目录。换一个路径。", path, parent)
	}

	checkedPath := path
	if exists {
		checkedPath, err = filepath.EvalSymlinks(path)
		if err != nil {
			return "", nil, fmt.Errorf("解析 %s 的链接：%w", path, err)
		}
	} else {
		realParent, err := filepath.EvalSymlinks(parent)
		if err != nil {
			return "", nil, fmt.Errorf("解析 %s 的链接：%w", parent, err)
		}
		checkedPath = filepath.Join(realParent, filepath.Base(path))
	}

	home, err := os.UserHomeDir()
	if err != nil {
		return "", nil, fmt.Errorf("找不到你的主目录：%w", err)
	}
	homeReal := home
	if resolved, err := filepath.EvalSymlinks(home); err == nil {
		homeReal = resolved
	}
	if !isUnder(homeReal, checkedPath) {
		if !force {
			return "", nil, fmt.Errorf("%s 不在你的主目录下。用户级指令文件应该在主目录里；"+
				"确定要写这里就加 --force（仍然要 --yes 才落笔）。", path)
		}
		warnings = append(warnings, "这个位置不在你平时放文件的地方；只在你确认过的情况下才写。")
	}

	if cwd, err := os.Getwd(); err == nil {
		// Resolve the working directory too: on macOS `t.TempDir()` and `/var` are symlinks, and
		// comparing a logical cwd with a physical target would miss every project path.
		if resolved, err := filepath.EvalSymlinks(cwd); err == nil {
			cwd = resolved
		}
		if filepath.Clean(cwd) != filepath.Clean(homeReal) && isUnder(cwd, checkedPath) {
			warnings = append(warnings, "这个位置只对当前项目生效；我们想让它对整台电脑生效。")
		}
	}
	return path, warnings, nil
}

// isUnder reports whether target is base itself or inside it. filepath.Rel is used rather than a
// string prefix so `/Users/me/.claude-evil` is not inside `/Users/me/.claude`.
func isUnder(base, target string) bool {
	rel, err := filepath.Rel(base, target)
	if err != nil || rel == ".." {
		return false
	}
	return rel == "." || !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

// rerunAtTheTerminal is the answer to a sandbox or a read-only home (v24 TC-05): the same command,
// with the same arguments, to run in a terminal the person controls. No retry and no escalation.
func rerunAtTheTerminal(command string) string {
	return "\n如果这个 AI 写不进这个位置，请在你自己的终端里原样重跑：\n  " + command
}

// shellQuote keeps a path with spaces or punctuation as one argument in both sh and PowerShell.
func shellQuote(value string) string {
	return "'" + strings.ReplaceAll(value, "'", "''") + "'"
}

func installRerun(path, agent string) string {
	command := "skillmaster prompt install --file " + shellQuote(path)
	if agent != "" {
		command += " --agent " + shellQuote(agent)
	}
	return command + " --yes"
}

// printInstallPlan is the five sentences the agent relays to the person. **No path and no command
// on stdout** (v26 acceptance 17): the person just approved a change to "this AI's settings", and
// the path they may already see in the approval box is not something to repeat. The confirmation
// line goes to stderr — that is the agent's cue, not the person's.
func printInstallPlan(action string, warnings []string) {
	for _, warning := range warnings {
		fmt.Printf("注意：%s\n", warning)
	}
	fmt.Printf("我要做什么：给这个 AI 加一段说明（%s），让它以后先来 SkillMaster 找技能。\n", action)
	fmt.Println("只会动到什么：只改这个 AI 每次开始会读的那份说明里的一段；文件其它内容不动。")
	fmt.Println("做完会怎样：这个 AI 以后每次开始都会读到这段说明，先来 SkillMaster 找技能。")
	fmt.Println("不想做了怎么办：可以只去掉这段说明，文件本身保留。")
	fmt.Println("不做会怎样：这个 AI 还是按原来的方式找技能，可能用不上平台上的技能。")
	fmt.Fprintln(os.Stderr, "确认要写，就加 --yes 重跑。")
}

// promptUpdate refreshes the file the record points at.
func promptUpdate(args []string) error {
	confirmed := false
	for _, arg := range args {
		switch arg {
		case "--yes":
			confirmed = true
		default:
			return unknownFlag("prompt update", arg)
		}
	}
	dir, err := config.Dir()
	if err != nil {
		return err
	}
	store, has, err := prompt.LoadStore(dir)
	if err != nil {
		return err
	}
	if !has || len(store.Files) == 0 {
		return errors.New("本机还没有记录。先跑一次 skillmaster prompt install --file <路径>，" +
			"它会把规则段写进去并记住位置。")
	}
	block := prompt.Block()
	normalisedBlock := prompt.Normalise(block)
	anyDiff := false
	anyFailure := false
	writeFailed := false
	changed := false
	for i, record := range store.Files {
		file, err := os.ReadFile(record.File)
		if err != nil {
			fmt.Printf("%s：读不到这个文件：%v\n", record.File, err)
			anyFailure = true
			continue
		}
		region, err := prompt.Locate(record.File, file)
		if errors.Is(err, prompt.ErrMissing) {
			fmt.Printf("%s：没有标记段。重新写入：skillmaster prompt install --file %s\n", record.File, record.File)
			anyFailure = true
			continue
		}
		var ambiguous *prompt.AmbiguousError
		if errors.As(err, &ambiguous) {
			fmt.Printf("%s：%s\n", record.File, ambiguous.Error())
			anyFailure = true
			continue
		}
		if err != nil {
			fmt.Printf("%s：读标记段：%v\n", record.File, err)
			anyFailure = true
			continue
		}
		current := prompt.Normalise(file[region.Start:region.End])
		if bytes.Equal(current, normalisedBlock) {
			if record.Digest != prompt.Digest() || record.Version != version.String() {
				store.Files[i].Digest = prompt.Digest()
				store.Files[i].Version = version.String()
				store.Files[i].Notified = ""
				changed = true
			}
			continue
		}
		anyDiff = true
		if !confirmed {
			fmt.Printf("\n%s 里的是旧版：\n", record.File)
			printBlockDiff(string(current), string(normalisedBlock))
			continue
		}
		mode, err := fileMode(record.File)
		if err != nil {
			fmt.Printf("%s：%v\n", record.File, err)
			anyFailure = true
			continue
		}
		if err := writeFile(record.File, file, prompt.Replace(file, region, block), mode); err != nil {
			fmt.Printf("%s：%v\n", record.File, err)
			anyFailure = true
			writeFailed = true
			continue
		}
		store.Files[i].Digest = prompt.Digest()
		store.Files[i].Version = version.String()
		store.Files[i].Notified = ""
		changed = true
		fmt.Printf("已更新 %s\n", record.File)
	}
	if changed {
		if err := prompt.SaveStore(dir, store); err != nil {
			return err
		}
	}
	if writeFailed {
		fmt.Println(rerunAtTheTerminal("skillmaster prompt update --yes"))
	}
	if !confirmed && anyDiff {
		fmt.Println("\n确认要替换它们，加上 --yes 重跑。")
		return &exitError{code: 1}
	}
	if anyFailure {
		return &exitError{code: 1}
	}
	if !anyDiff {
		fmt.Println("已是最新。")
	}
	return nil
}

// promptUninstall removes the marked block, never the file (ADR 0101). It is the way back for a
// wrong agent, a wrong path, or a failed verification; `--all` is the way to clear every record this
// machine made.
func promptUninstall(args []string) error {
	file := ""
	all := false
	confirmed := false
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--file":
			value, err := flagValue(args, i, "--file", "prompt uninstall")
			if err != nil {
				return err
			}
			file = value
			i++
		case "--all":
			all = true
		case "--yes":
			confirmed = true
		default:
			return unknownFlag("prompt uninstall", args[i])
		}
	}
	if all && file != "" {
		return usagef("prompt uninstall 一次只做一件事：--file <路径> 或 --all。%s", usageAt("prompt uninstall"))
	}
	if !all && file == "" {
		return usagef("prompt uninstall 要用 --file <路径> 或 --all 指定去掉哪里。%s", usageAt("prompt uninstall"))
	}

	dir, err := config.Dir()
	if err != nil {
		return err
	}
	store, _, err := prompt.LoadStore(dir)
	if err != nil {
		return err
	}

	if all {
		if len(store.Files) == 0 {
			fmt.Println("本机还没有记录，没有可去掉的。")
			return &exitError{code: 1}
		}
		if !confirmed {
			printUninstallPlan(store.Files, true)
			return &exitError{code: 1}
		}
		kept := make([]prompt.Record, 0, len(store.Files))
		cleaned := 0
		anyFailure := false
		writeFailed := false
		for _, record := range store.Files {
			body, readErr := os.ReadFile(record.File)
			if readErr != nil {
				fmt.Printf("%s：读不到这个文件（记录会去掉）：%v\n", record.File, readErr)
				cleaned++
				continue
			}
			region, locateErr := prompt.Locate(record.File, body)
			if errors.Is(locateErr, prompt.ErrMissing) {
				fmt.Printf("%s：没有标记段（记录会去掉）\n", record.File)
				cleaned++
				continue
			}
			var ambiguous *prompt.AmbiguousError
			if errors.As(locateErr, &ambiguous) {
				fmt.Printf("%s：%s（记录保留）\n", record.File, ambiguous.Error())
				kept = append(kept, record)
				anyFailure = true
				continue
			}
			if locateErr != nil {
				fmt.Printf("%s：读标记段：%v（记录保留）\n", record.File, locateErr)
				kept = append(kept, record)
				anyFailure = true
				continue
			}
			mode, modeErr := fileMode(record.File)
			if modeErr != nil {
				fmt.Printf("%s：%v（记录保留）\n", record.File, modeErr)
				kept = append(kept, record)
				anyFailure = true
				writeFailed = true
				continue
			}
			if writeErr := writeFile(record.File, body, prompt.Remove(body, region), mode); writeErr != nil {
				fmt.Printf("%s：%v（记录保留）\n", record.File, writeErr)
				kept = append(kept, record)
				anyFailure = true
				writeFailed = true
				continue
			}
			fmt.Printf("已去掉 %s 里的标记段；文件保留。\n", record.File)
			cleaned++
		}
		if err := prompt.SaveStore(dir, prompt.Store{Schema: 2, Files: kept}); err != nil {
			return err
		}
		if writeFailed {
			fmt.Println(rerunAtTheTerminal("skillmaster prompt uninstall --all --yes"))
		}
		if anyFailure {
			return &exitError{code: 1}
		}
		if cleaned == 0 {
			return &exitError{code: 1}
		}
		return nil
	}

	path, err := expandHome(file)
	if err != nil {
		return err
	}
	path, err = filepath.Abs(path)
	if err != nil {
		return fmt.Errorf("解析路径 %s：%w", file, err)
	}
	body, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("读不到 %s：%w。确认路径后再试。", path, err)
	}
	region, locateErr := prompt.Locate(path, body)
	if errors.Is(locateErr, prompt.ErrMissing) {
		return fmt.Errorf("%s 里没有标记段；没有可去掉的。", path)
	}
	var ambiguous *prompt.AmbiguousError
	if errors.As(locateErr, &ambiguous) {
		return ambiguous
	}
	if locateErr != nil {
		return locateErr
	}
	if !confirmed {
		printUninstallPlan(nil, false)
		return &exitError{code: 1}
	}
	mode, err := fileMode(path)
	if err != nil {
		return err
	}
	if err := writeFile(path, body, prompt.Remove(body, region), mode); err != nil {
		return fmt.Errorf("%w%s", err, rerunAtTheTerminal("skillmaster prompt uninstall --file "+shellQuote(path)+" --yes"))
	}
	if _, found := prompt.Find(store.Files, path); found {
		store.Files = prompt.RemoveRecord(store.Files, path)
		if err := prompt.SaveStore(dir, store); err != nil {
			return err
		}
	}
	fmt.Printf("已去掉 %s 里的标记段；文件保留。\n", path)
	return nil
}

// printUninstallPlan is the same five sentences as install, for taking the block away. Paths stay
// off stdout (v26 acceptance 17); the AI labels are what the person recognizes.
func printUninstallPlan(records []prompt.Record, all bool) {
	target, subject := "这个 AI", "它"
	if all {
		target, subject = "这些 AI", "它们"
	}
	fmt.Printf("我要做什么：去掉%s的那段说明，让%s以后不再自动来 SkillMaster 找技能。\n", target, subject)
	fmt.Println("只会动到什么：那段说明；文件其它内容不动，文件本身也保留。")
	fmt.Printf("做完会怎样：%s不再自动读这段说明。\n", subject)
	fmt.Println("不想做了怎么办：可以重新加上这段说明。")
	fmt.Println("不做会怎样：这段说明继续留在文件里。")
	if all {
		seen := map[string]bool{}
		labels := make([]string, 0, len(records))
		for _, record := range records {
			label := record.Agent
			if label == "" {
				label = "未命名"
			}
			if !seen[label] {
				seen[label] = true
				labels = append(labels, label)
			}
		}
		if len(labels) > 0 {
			fmt.Printf("会动到：%s。\n", strings.Join(labels, "、"))
		}
	}
	fmt.Fprintln(os.Stderr, "确认要去掉，就加 --yes 重跑。")
}

// writeBlock writes the file and then records it.
//
// **The write is staged and renamed, never `O_TRUNC` on the person's file.** These commands edit an
// instruction file this CLI does not own; a disk that fills up, or a process killed mid-write, would
// otherwise leave a prefix of the file with no copy anywhere. The credential store already does
// exactly this (`internal/credentials/file.go`), and ADR 0101 allows the rename.
//
// **And it refuses if the file changed since it was read.** An agent editing the same file is the
// ordinary case, not a corner one; writing a result derived from a stale read would delete the
// paragraph somebody saved a moment earlier.
//
// **Record after the write, never before.** A record that names a file the write never reached
// would make the next `update` report a difference that the person already approved.
func writeBlock(path string, original, content []byte, mode os.FileMode, agent string) error {
	if err := writeFile(path, original, content, mode); err != nil {
		return fmt.Errorf("%w%s", err, rerunAtTheTerminal(installRerun(path, agent)))
	}
	return saveBlockRecord(path, agent, fmt.Sprintf("已写入 %s（%s）", path, prompt.Digest()))
}

// writeFile is the staged replacement without the record update. `prompt update` writes several
// files and saves the set once per success; `prompt install` writes one and then records it.
func writeFile(path string, original, content []byte, mode os.FileMode) error {
	real, err := realPath(path)
	if err != nil {
		return err
	}
	current, readErr := os.ReadFile(real)
	if readErr != nil && !errors.Is(readErr, os.ErrNotExist) {
		return fmt.Errorf("读 %s：%w", real, readErr)
	}
	if !bytes.Equal(current, original) {
		return errors.New("文件在这期间被改过，没有写。重跑一次。")
	}
	staged := real + ".skillmaster.new"
	if err := os.WriteFile(staged, content, mode); err != nil {
		return fmt.Errorf("写 %s：%w", staged, err)
	}
	if err := os.Chmod(staged, mode); err != nil {
		return fmt.Errorf("设置 %s 的权限：%w", staged, err)
	}
	if err := os.Rename(staged, real); err != nil {
		return fmt.Errorf("替换 %s：%w（留下了 %s，可以自行删除）。"+
			"如果这个文件正在编辑器里打开，关掉它再试", real, err, staged)
	}
	return nil
}

// realPath follows a symlink so an update writes through the link instead of replacing it with a
// regular file — the common dotfiles layout would otherwise silently break.
func realPath(path string) (string, error) {
	info, err := os.Lstat(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return path, nil
		}
		return "", fmt.Errorf("读 %s 的信息：%w", path, err)
	}
	if info.Mode()&os.ModeSymlink == 0 {
		return path, nil
	}
	real, err := filepath.EvalSymlinks(path)
	if err != nil {
		return "", fmt.Errorf("解析 %s 的链接：%w", path, err)
	}
	return real, nil
}

func saveBlockRecord(path, agent, message string) error {
	dir, err := config.Dir()
	if err != nil {
		return err
	}
	store, _, err := prompt.LoadStore(dir)
	if err != nil {
		return err
	}
	store.Files = prompt.Upsert(store.Files, prompt.Record{
		File:    path,
		Agent:   agent,
		Digest:  prompt.Digest(),
		Version: version.String(),
	})
	if err := prompt.SaveStore(dir, store); err != nil {
		return err
	}
	fmt.Println(message)
	return nil
}

// readMaybe reads a file that may not exist yet.
func readMaybe(path string) ([]byte, os.FileMode, bool, error) {
	blob, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, 0, false, nil
	}
	if err != nil {
		return nil, 0, false, fmt.Errorf("读 %s：%w", path, err)
	}
	mode, err := fileMode(path)
	if err != nil {
		return nil, 0, false, err
	}
	return blob, mode, true, nil
}

func fileMode(path string) (os.FileMode, error) {
	info, err := os.Stat(path)
	if err != nil {
		return 0, fmt.Errorf("读 %s 的信息：%w", path, err)
	}
	return info.Mode().Perm(), nil
}

// printBlockDiff is a line diff, good enough for a five-line block and honest about what changed.
//
// Not a unified diff with hunk headers: the whole block is always shown, because "which three lines
// of a paragraph changed" is the question, and a five-line excerpt with no context answers it worse
// than the paragraph itself.
func printBlockDiff(before, after string) {
	oldLines := splitBlock(before)
	newLines := splitBlock(after)
	common := make([][]int, len(oldLines)+1)
	for i := range common {
		common[i] = make([]int, len(newLines)+1)
	}
	for i := len(oldLines) - 1; i >= 0; i-- {
		for j := len(newLines) - 1; j >= 0; j-- {
			switch {
			case oldLines[i] == newLines[j]:
				common[i][j] = common[i+1][j+1] + 1
			case common[i+1][j] >= common[i][j+1]:
				common[i][j] = common[i+1][j]
			default:
				common[i][j] = common[i][j+1]
			}
		}
	}
	for i, j := 0, 0; i < len(oldLines) || j < len(newLines); {
		switch {
		case i < len(oldLines) && j < len(newLines) && oldLines[i] == newLines[j]:
			fmt.Println("  " + oldLines[i])
			i++
			j++
		case j >= len(newLines) || (i < len(oldLines) && common[i+1][j] >= common[i][j+1]):
			fmt.Println("- " + oldLines[i])
			i++
		default:
			fmt.Println("+ " + newLines[j])
			j++
		}
	}
}

func splitBlock(text string) []string {
	lines := strings.Split(strings.TrimSuffix(text, "\n"), "\n")
	if len(lines) == 1 && lines[0] == "" {
		return nil
	}
	return lines
}

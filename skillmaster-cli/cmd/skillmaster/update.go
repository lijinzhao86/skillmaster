package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/updater"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/version"
)

// AutoUpdateEnv turns the automatic check off. **Only the automatic path**: `skillmaster update`
// still works, the way `DISABLE_AUTOUPDATER` in Claude Code leaves `claude update` alone.
const AutoUpdateEnv = "SKILLMASTER_AUTO_UPDATE"

// UpdateBaseEnv points the updater somewhere else: a mirror, or a test server.
//
// **A seam, not a channel switch.** The channel is compiled in (ADR 0103), and this variable is the
// one way to exercise the path against something other than the real bucket — which is why it is
// documented as such rather than advertised as a feature.
const UpdateBaseEnv = "SKILLMASTER_UPDATE_BASE"

// executablePath is this process's own file, and a variable only so tests can point the updater at
// a file they own: the real answer would make every test of the install path replace the test
// binary itself.
var executablePath = os.Executable

// installedExecutable resolves the running file, following a symlink.
//
// **Not a formality on macOS.** `os.Executable` there returns the path that was exec'd without
// resolving links (Linux resolves through `/proc/self/exe`), so a `~/.local/bin/skillmaster` that
// points into a versioned tools directory would pass the official-directory gate and then be
// replaced by a regular file — leaving the versioned target the person manages stale. Resolving
// first means such an install is treated as "outside the official directory", which is the same
// answer Linux already gives.
func installedExecutable() (string, error) {
	target, err := executablePath()
	if err != nil {
		return "", err
	}
	if resolved, err := filepath.EvalSymlinks(target); err == nil {
		return resolved, nil
	}
	return target, nil
}

// updateBase is the channel this binary belongs to.
//
// **Compiled in, not discovered.** A binary that could be pointed at another channel by a file
// would be a binary that can be moved from formal to beta without the person knowing. The
// environment override exists for tests and mirrors, and it is the only way in.
func updateBase() string {
	if fromEnv := strings.TrimSpace(os.Getenv(UpdateBaseEnv)); fromEnv != "" {
		return strings.TrimRight(fromEnv, "/")
	}
	return strings.TrimRight(version.UpdateBase, "/")
}

// officialInstallDirs is where the install scripts put the binary, one entry per platform.
//
// **The updater only replaces a binary it was installed as.** Anywhere else — a copy in
// `/usr/local/bin`, a build output, a symlink in `~/bin` — the file belongs to whoever put it
// there, and replacing it is the CLI deciding what to do with somebody else's machine. The explicit
// `skillmaster update` says so and points at the installer; the automatic path stays quiet.
func officialInstallDirs() []string {
	home, err := os.UserHomeDir()
	if err != nil {
		return nil
	}
	if runtime.GOOS == "windows" {
		local := os.Getenv("LOCALAPPDATA")
		if local == "" {
			local = filepath.Join(home, "AppData", "Local")
		}
		return []string{filepath.Join(local, "SkillMaster", "bin")}
	}
	return []string{filepath.Join(home, ".local", "bin")}
}

// isOfficialInstall compares resolved directories, so an install directory that is itself a
// symlink still answers yes.
func isOfficialInstall(target string) bool {
	dir := resolveDir(filepath.Dir(target))
	for _, official := range officialInstallDirs() {
		if dir == resolveDir(official) {
			return true
		}
	}
	return false
}

func resolveDir(dir string) string {
	if resolved, err := filepath.EvalSymlinks(dir); err == nil {
		return filepath.Clean(resolved)
	}
	return filepath.Clean(dir)
}

// autoUpdateAllowed answers the cheap half of "should this run check": the flags that mean the
// person is asking about the binary itself rather than using it.
//
// **The install script runs `--version` as its smoke test.** That command must stay offline even
// when it is the first thing a fresh binary ever runs. **The `prompt` group is local by
// definition** — it reads and writes one file on this machine — and a network request in front of
// it would make "prompt print works offline" false.
func autoUpdateAllowed(args []string) bool {
	if os.Getenv(AutoUpdateEnv) == "0" {
		return false
	}
	if updateBase() == "" || version.Version == "dev" {
		return false
	}
	if len(args) == 0 {
		return false
	}
	// An `if`, not a `switch args[0]`: the switch shape is reserved for command dispatch, and the
	// command-tree test reads it that way.
	if args[0] == "--version" || args[0] == "help" || args[0] == "update" || args[0] == "prompt" {
		return false
	}
	for _, arg := range args {
		if arg == "--help" || arg == "-h" {
			return false
		}
	}
	return true
}

// autoUpdate is the hook `run` installs for the length of one command.
//
// It returns the function to call on the way out. **Everything it does is bounded**: the check has
// its own 1.5 s timeout and the download gets 10 s at the end. There is no goroutine left running
// after the process exits, and nothing to clean up if it does.
func autoUpdate(ctx context.Context, args []string) func() {
	if !autoUpdateAllowed(args) {
		return func() {}
	}
	dir, err := config.Dir()
	if err != nil {
		return func() {}
	}
	target, err := installedExecutable()
	if err != nil {
		return func() {}
	}
	if !isOfficialInstall(target) {
		return func() {}
	}
	job, notice, err := updater.Prepare(ctx, updater.Options{
		Base:    updateBase(),
		Dir:     dir,
		Target:  target,
		Current: version.String(),
		GOOS:    runtime.GOOS,
		GOARCH:  runtime.GOARCH,
	})
	if notice != "" {
		fmt.Fprintln(os.Stderr, "（"+notice+"）")
	}
	if err != nil || job == nil {
		return func() {}
	}
	return func() {
		reportUpdate(job.Drain(updater.DrainBudget))
	}
}

// reportUpdate is the one line an update is allowed to add to a command's output.
//
// **stderr, because stdout is the command's.** A command whose stdout changed because the CLI
// updated would be a command that cannot be scripted (v16's output contract).
func reportUpdate(outcome updater.Outcome) {
	switch {
	case outcome.Err == nil && outcome.Version != "" && outcome.Scheduled:
		fmt.Fprintf(os.Stderr, "（skillmaster 已下载 %s，这条命令结束后自动替换，下一条命令起生效。）\n", outcome.Version)
	case outcome.Err == nil && outcome.Version != "":
		fmt.Fprintf(os.Stderr, "（skillmaster 已更新到 %s，下一条命令起生效。）\n", outcome.Version)
	case outcome.CannotWrite && !outcome.Suppress:
		fmt.Fprintf(os.Stderr, "（发现新版本 %s，但这台机器装不进去：%v。更新方法：重跑安装说明里那条命令。）\n",
			outcome.Version, outcome.Err)
	case outcome.Verify && !outcome.Suppress:
		fmt.Fprintf(os.Stderr, "（新版本 %s 下载下来没有通过校验：%v。已经用旧版本继续，一个小时后重试。）\n",
			outcome.Version, outcome.Err)
	}
	// Anything else (a check that failed, a download that missed its budget) is silent by design.
}

// updateCmd is the explicit door: `skillmaster update [--check]`.
//
// **Not throttled, and not affected by the auto-update switch.** Both exist to keep the automatic
// path quiet; a person who typed `update` has asked.
func updateCmd(ctx context.Context, args []string) error {
	checkOnly := false
	for _, arg := range args {
		switch arg {
		case "--check":
			checkOnly = true
		default:
			return unknownFlag("update", arg)
		}
	}
	base := updateBase()
	if base == "" || version.Version == "dev" {
		return errors.New("这个构建不参与更新（版本号 " + version.String() +
			"）。要装新版本，重跑安装说明里那条命令。")
	}
	target, err := installedExecutable()
	if err != nil {
		return fmt.Errorf("找不到本机这个可执行文件：%w", err)
	}
	dir, err := config.Dir()
	if err != nil {
		return err
	}
	client := &http.Client{Timeout: 30 * time.Second}

	remote, err := updater.Check(ctx, base, client)
	if err != nil {
		return fmt.Errorf("检查新版本：%w。也可以直接重跑安装说明里那条命令。", err)
	}
	current, err := updater.ParseRelease(version.String())
	if err != nil {
		return fmt.Errorf("读懂本机版本号：%w", err)
	}
	if !updater.Newer(current, remote) {
		fmt.Printf("已是最新（%s）\n", version.String())
		return nil
	}
	if checkOnly {
		fmt.Printf("有新版本：%s → %s\n", version.String(), remote.Raw)
		return nil
	}
	// **Only where it was installed.** Further out, the file is not this CLI's to replace; the
	// installer is the one that knows how to put it there.
	if !isOfficialInstall(target) {
		return fmt.Errorf("这个二进制不在官方安装位置（%s 之一），不替它更新。重跑安装说明里那条命令。",
			strings.Join(officialInstallDirs(), "、"))
	}
	unlock, ok, err := updater.AcquireLock(dir)
	if err != nil {
		return err
	}
	if !ok {
		return errors.New("另一个 skillmaster 正在更新，稍后再试。")
	}
	defer unlock()

	fmt.Printf("下载 %s → %s …\n", version.String(), remote.Raw)
	staged, err := updater.Fetch(ctx, base, remote, filepath.Dir(target), runtime.GOOS, runtime.GOARCH, client)
	if err != nil {
		return fmt.Errorf("下载新版本：%w。也可以直接重跑安装说明里那条命令。", err)
	}
	state := updater.LoadState(dir)
	state.Pending = &updater.Pending{Path: staged, Revision: remote.Revision, Version: remote.Raw}
	_ = updater.SaveState(dir, state)

	result, err := updater.Apply(staged, target)
	if err != nil {
		return fmt.Errorf("安装新版本：%w（重跑安装说明里那条命令也能装）", err)
	}
	if result.Scheduled {
		fmt.Printf("已下载 %s，这条命令结束后自动替换，下一条命令起生效。\n", remote.Raw)
		return nil
	}
	state = updater.LoadState(dir)
	state.Pending = nil
	_ = updater.SaveState(dir, state)
	fmt.Printf("已更新到 %s，下一条命令起生效。\n", remote.Raw)
	return nil
}

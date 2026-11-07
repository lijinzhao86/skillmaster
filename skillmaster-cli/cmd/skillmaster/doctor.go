// doctor: say what this machine can and cannot do, in one screen.

package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// doctorCmd answers the question a failing command cannot: which of the four things this CLI needs
// is the one that is not working.
//
// **Print, never change.** The configuration a person may need to add is shown as text they can
// copy; nothing here writes to their settings, because widening a sandbox is a security decision
// and not this command's to make.
func doctorCmd(ctx context.Context, args []string) error {
	if len(args) > 0 {
		return usagef("%s", usageAt("doctor"))
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
		} else if where := stor.Where(); isFile(where) {
			// A file copy can be newer than the keychain's (ADR 0025), so its presence wins over
			// "where the store would keep things". `Where` names the copy that exists, which in a
			// sandbox may be the older one in a directory this run cannot write.
			presented = "文件 " + where
		} else {
			presented = where
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

	// 6. Sandbox: two clients announce it, the other two only show it by refusing a write.
	sandbox := detectSandbox()
	if sandbox == "" {
		report("沙箱", "ok", "没有检测到")
	} else {
		report("沙箱", "注意", sandbox)
	}

	if sandbox != "" || len(problems) > 0 {
		printSandboxConfig(stateDir, cacheRoot)
	}
	if len(problems) > 0 {
		fmt.Printf("\n有 %d 项没通过：%s\n", len(problems), strings.Join(problems, "、"))
	}
	return nil
}

// detectSandbox names what it can prove and does not guess further.
//
// **Two clients set a variable, two do not.** Codex and Cursor say so; Claude Code and WorkBuddy
// only show it by refusing a write, which the state-directory line above already reports. A missing
// variable is therefore not evidence of no sandbox.
// **No probe in `$HOME` or in the skills directory, on purpose.** The state directory is already
// probed, it lives under the same home on every platform this runs on (macOS
// `~/Library/Application Support`, Linux `~/.config`, Windows `%AppData%`), and it is ours — so a
// refusal there is the same evidence without leaving a file in somebody's home.
func detectSandbox() string {
	for _, name := range []string{"CODEX_SANDBOX", "CURSOR_SANDBOX", "SANDBOX_RUNTIME", "GEMINI_SANDBOX"} {
		if value := os.Getenv(name); value != "" {
			return fmt.Sprintf("%s=%s", name, value)
		}
	}
	if config.DirNote() != "" {
		return "状态目录写不进去，多半在沙箱里（客户端没有自报）"
	}
	return ""
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
func printSandboxConfig(stateDir, cacheRoot string) {
	dlHost := config.LegacyName + "-dl.oss-cn-hangzhou.aliyuncs.com"
	fmt.Println("\n如果上一步是沙箱拦的，按你用的客户端挑一段（都是加配置，不改我们的东西）：")
	fmt.Println("\nClaude Code（~/.claude/settings.json）：")
	fmt.Printf(`  {"sandbox":{"filesystem":{"allowWrite":["%s","%s"]},
    "network":{"allowedDomains":["skillmasterai.com","*.skillmasterai.com",
      "%s"]}}}
`, stateDir, cacheRoot, dlHost)
	fmt.Printf("  还是连不上，就把命令放到沙箱外跑：\"excludedCommands\": [%q]\n", config.Name+" *")
	fmt.Println("\nCodex（~/.codex/config.toml）：")
	fmt.Printf("  [sandbox_workspace_write]\n  writable_roots = [%q, %q]\n  network_access = true\n",
		stateDir, cacheRoot)
	fmt.Println("\nCursor（~/.cursor/sandbox.json）：")
	fmt.Printf("  {\"additionalReadwritePaths\":[%q,%q],\n   \"networkPolicy\":{\"allow\":[\"skillmasterai.com\"]}}\n",
		stateDir, cacheRoot)
	fmt.Println("\nWorkBuddy（安全中心 → 沙箱安全，或 ~/.workbuddy/settings.json 的 sandbox.extraAllowWrite）：")
	fmt.Printf("  [%q, %q]  —— 另需在网络规则里放行 skillmasterai.com\n", stateDir, cacheRoot)
	fmt.Println("\n登录、登出、更新这三件事可能仍然要在你自己的终端里跑一次：它们要写钥匙串或替换二进制。")
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

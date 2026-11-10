#!/usr/bin/env bash
# 发布动作的提醒：Bash 里出现上线 / 合并 / 打 tag 这类命令时，把 .dev-sop/release/ 里
# 那几条只有翻文档才能看到的顺序性约束（等 Build 绿、上线是人按的、迁移回不去）放进模型上下文。
#
# 不阻断——决定权仍在当下这个任务。同一会话只提醒一次：否则每次命令都注入一遍，
# 提醒会变成噪声，还白烧 token。
#
# 为什么不管界面：界面那套规矩已经在 CLAUDE.md 的红线里常驻，钩子再说一遍是白念。
#
# 输入：PreToolUse 的 stdin JSON（tool_name / tool_input / session_id）。
# 命中即输出 JSON，未命中原样退出 0、不输出。

set -uo pipefail

input=$(cat)
[ -n "$input" ] || exit 0
command -v jq >/dev/null 2>&1 || exit 0

[ "$(printf '%s' "$input" | jq -r '.tool_name // ""')" = "Bash" ] || exit 0

cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // ""')
printf '%s' "$cmd" | grep -qE 'gh[[:space:]]+workflow[[:space:]]+run|gh[[:space:]]+pr[[:space:]]+merge|gh[[:space:]]+release|git[[:space:]]+tag' || exit 0

# 每会话只提醒一次。
marker_dir="${TMPDIR:-/tmp}/claude-dev-sop-hook"
mkdir -p "$marker_dir" 2>/dev/null || exit 0
marker="$marker_dir/$(printf '%s' "$input" | jq -r '.session_id // "default"')-release"
[ -e "$marker" ] && exit 0
: >"$marker" 2>/dev/null || exit 0

msg='【发布动作 · 提醒一次】门禁在 PR 上，上线是人按下去的一步。
- 服务端 / 控制台 / 官网：人手动 workflow_dispatch；CLI 是打 tag cli/v*。
- Deploy 不放行没有绿色构建的 sha：服务端查 `Build`，控制台/站点查 `Console check` / `Site check`；都只下载那份 artifact，不重新构建。
- 服务端回滚 = dispatch 时 `release: <已有 sha>` + `confirm_rollback=rollback`；服务端一次只跑一个部署。只有 Flyway 迁移回不去。
- 详情见 .dev-sop/release/README.md 与对应端的 server.md / console.md / site.md / cli.md。'

jq -n --arg ctx "$msg" \
  '{hookSpecificOutput: {hookEventName: "PreToolUse", additionalContext: $ctx}}'

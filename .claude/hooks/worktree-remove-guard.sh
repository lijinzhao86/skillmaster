#!/usr/bin/env bash
# worktree 删除前的门禁：目标目录还开在 VS Code 里就拒绝这件事。
#
# 为什么要有这道门禁：删掉一个正被 VS Code 当工作区打开的目录，杀掉的不只是那个窗口。
# Code 的主进程发现自己的启动目录没了，会整个退出——所有窗口一起挂。2026-11-04 在本机
# 复现过，上游是 microsoft/vscode#2259（根源 nodejs/node#1184：进程的 cwd 被删掉）。
#
# 两道检查，任一命中即阻断：
#   1. Bash 的当前目录就在目标里 —— 同样是「删掉自己脚下的目录」。
#   2. code --status 的 Workspace Stats 里还有这个目录 —— Folder (<名字>) 就是它。
#
# 取不到证据时放行：code 没装、--status 报错都当作「没开着」。误放行的代价是回到没有
# 门禁的从前；误拦的代价是每次清理都被挡住，那这道门禁很快会被绕过去。
#
# 它只管 Bash 工具里的 `git worktree remove`。写进脚本、或用别的工具删目录，它看不到。
#
# 输入：PreToolUse 的 stdin JSON。阻断 = 退出码 2 + stderr 上的一段话。

set -uo pipefail

input=$(cat)
[ -n "$input" ] || exit 0
command -v jq >/dev/null 2>&1 || exit 0

[ "$(printf '%s' "$input" | jq -r '.tool_name // ""')" = "Bash" ] || exit 0

cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // ""')
printf '%s' "$cmd" | grep -qE 'worktree[[:space:]]+remove' || exit 0

# `worktree remove` 之后第一个不以 - 开头的词就是目标。选项（--force / -f）会被跳过。
target=$(printf '%s' "$cmd" | sed -E 's/.*worktree[[:space:]]+remove//' \
  | tr ' \t' '\n\n' | grep -v '^-' | grep -v '^$' | head -1)
[ -n "$target" ] || exit 0
target=${target%\"}; target=${target#\"}; target=${target%\'}; target=${target#\'}
[ -n "$target" ] || exit 0

base=$(basename -- "$target")
[ "$base" != "/" ] && [ "$base" != "." ] && [ "$base" != ".." ] || exit 0

# 绝对路径，目标还在时才有。拿不到就只按名字比。
real=""
if real=$(cd -- "$target" 2>/dev/null && pwd -P); then :; else real=""; fi

inside() {
  [ -n "$real" ] || return 1
  [ "$1" = "$real" ] && return 0
  case "$1/" in "$real"/*) return 0 ;; esac
  return 1
}

# ---- 1. 当前目录是否就在目标里 ----
for dir in "${CLAUDE_PROJECT_DIR:-}" "$PWD"; do
  [ -n "$dir" ] || continue
  inside "$dir" || continue
  cat >&2 <<EOF
【worktree 门禁】没有删：Bash 的当前目录就在这个 worktree 里。

  目标：$target

删掉自己所在的目录，后续每条命令都会失败（shell 读不到 cwd）；它也是
VS Code 整个退出那条路上的一半原因。先 cd 到别处（比如主仓库），再重跑。
EOF
  exit 2
done

# ---- 2. 这个目录是否还开在 VS Code 里 ----
command -v code >/dev/null 2>&1 || exit 0
status=$(code --status 2>&1) || exit 0
[ -n "$status" ] || exit 0

# 目录名原样进正则，除了名字本身，不让任何字符有别的意思。
base_re=$(printf '%s' "$base" | sed 's/[][\.^$*+?(){}|]/\\&/g')

open_window=0
if [ -n "$real" ] && printf '%s' "$status" | grep -Fq -- "$real"; then
  open_window=1
elif printf '%s' "$status" | grep -Eq "(Folder|Window) \(${base_re}(:[^)]*)?\)"; then
  open_window=1
elif printf '%s' "$status" | grep -Eq "(Folder|Window) \([^)]*${base_re}(:[^)]*)?\)"; then
  open_window=1
fi
[ "$open_window" -eq 1 ] || exit 0

cat >&2 <<EOF
【worktree 门禁】没有删：$base 还开在 VS Code 里。

  目标：$target
  依据：code --status 的 Workspace Stats 里还有它的窗口 / 文件夹

现在删这个目录，VS Code 会整个退出——所有窗口一起挂，不是只关那一个
（microsoft/vscode#2259，本机 2026-11-04 复现过）。

先请所有者关掉那个窗口（File → Close Folder，或 ⌘W），再重跑同一条命令。
关完可以确认一次：code --status | grep "$base"  —— 没有输出就是关干净了。
EOF
exit 2

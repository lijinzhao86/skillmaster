#!/usr/bin/env bash
# .dev-sop 的两道提醒：改界面文件时指向 design/，跑发布动作时指向 .dev-sop/release/。
#
# 不阻断——只把一段话放进模型上下文（hookSpecificOutput.additionalContext），
# 决定权仍在当下这个任务。同一会话里每类只提醒一次：否则每次 Edit 都注入一遍，
# 提醒会变成噪声，还白烧 token。
#
# 输入：PreToolUse 的 stdin JSON（tool_name / tool_input / session_id）。
# 命中即输出 JSON，未命中原样退出 0、不输出。

set -uo pipefail

input=$(cat)
[ -n "$input" ] || exit 0
command -v jq >/dev/null 2>&1 || exit 0

session=$(printf '%s' "$input" | jq -r '.session_id // "default"')
tool=$(printf '%s' "$input" | jq -r '.tool_name // ""')

kind=""
case "$tool" in
  Edit|Write|MultiEdit|NotebookEdit)
    path=$(printf '%s' "$input" | jq -r '.tool_input.file_path // .tool_input.notebook_path // ""')
    # 界面实现（控制台 / 官网）与设计稿本身。design/scripts/ 是工具，不算。
    if printf '%s' "$path" | grep -qE '(^|/)(design/skillmaster-[^/]+\.html|skillmaster-console/|skillmaster-site/)'; then
      kind=design
    fi
    ;;
  Bash)
    cmd=$(printf '%s' "$input" | jq -r '.tool_input.command // ""')
    if printf '%s' "$cmd" | grep -qE 'gh[[:space:]]+workflow[[:space:]]+run|gh[[:space:]]+pr[[:space:]]+merge|gh[[:space:]]+release|git[[:space:]]+tag'; then
      kind=release
    fi
    ;;
esac
[ -n "$kind" ] || exit 0

# 每类每会话只提醒一次。
marker_dir="${TMPDIR:-/tmp}/claude-dev-sop-hook"
mkdir -p "$marker_dir" 2>/dev/null || exit 0
marker="$marker_dir/${session}-${kind}"
[ -e "$marker" ] && exit 0
: >"$marker" 2>/dev/null || exit 0

case "$kind" in
  design)
    msg='【界面改动 · 提醒一次】界面权威是 design/，不是实现里的现成写法。
- 结构、类名、文案、图标、尺寸、间距、圆角、颜色、各状态逐条照设计稿；数值与类名照抄设计稿 CSS，不换算、不重命名。
- 设计稿没覆盖的态（窄屏、空态、错误态、加载态、字段级校验）先问，不要自己发明。
-「测试全绿」不是视觉验收；改完要请人看。
- 过程规则见 .dev-sop/design.md；产物规格见 design/README.md 与 design/voice-spec.md。'
    ;;
  release)
    msg='【发布动作 · 提醒一次】门禁在 PR 上，上线是人按下去的一步。
- 服务端 / 控制台 / 官网：人手动 workflow_dispatch；CLI 是打 tag cli/v*。
- 服务端要等同一个 sha 的 Build 绿了再 dispatch——Deploy 自己不跑测试。
- 回滚 = 切回上一个 release；只有 Flyway 迁移回不去。
- 详情见 .dev-sop/release/README.md 与对应端的 server.md / console.md / site.md / cli.md。'
    ;;
  *) exit 0 ;;
esac

jq -n --arg ctx "$msg" \
  '{hookSpecificOutput: {hookEventName: "PreToolUse", additionalContext: $ctx}}'

#!/usr/bin/env bash
# Guide 回归测试机。改 `skills/skillmaster-guide/` 之后跑这一条。
#
#   scripts/guide-regression.sh          # 全套：结构 + 文档 + 43 题模型评测
#   scripts/guide-regression.sh --fast   # 跳过模型评测（约 2 秒）
#
# 退出码 0 表示全部通过；任何一步失败都会立刻退出，并保留那一步的输出。
# 题表在 skillmaster-cli/testdata/guide-eval.json；加一道题就加一个 task，
# 不用改本脚本。评测结果可用 SKILLMASTER_GUIDE_EVAL_OUT=<文件> 落盘。
set -euo pipefail

script="$0"
case "$script" in /*) ;; *) script="$PWD/$script" ;; esac
root="$(cd "$(dirname "$script")/.." && pwd)"
cd "$root"

fast=0
for arg in "$@"; do
  case "$arg" in
    --fast) fast=1 ;;
    -h|--help)
      sed -n '2,9p' "$script"
      exit 0
      ;;
    *) echo "不认识的参数：$arg" >&2; exit 2 ;;
  esac
done

step() { printf '\n== %s\n' "$1"; }

step "1/6 Guide 结构与预算（Go 漂移测试）"
(cd skillmaster-cli && SKILLMASTER_GUIDE_EVAL=0 go test ./cmd/skillmaster -run 'TestGuide|TestHelpBudgets' -count=1)

step "2/6 链接、可达性与行数"
python3 scripts/guide_links.py

step "3/6 文档四件套与相对链接"
python3 .claude/skills/docs-architecture/scripts/check_docs.py
echo "✓ check_docs.py"

step "4/6 生成物与源一致"
python3 scripts/docs/generate.py --check
echo "✓ generate.py --check"

step "5/6 引用与文件上限"
python3 scripts/docs/impact.py --check
echo "✓ impact.py --check"
node scripts/check-file-size.mjs
echo "✓ check-file-size.mjs"

if [ "$fast" -eq 1 ]; then
  printf '\n全套结构检查通过（--fast：未跑模型评测）\n'
  exit 0
fi

step "6/6 Guide 评测（43 题，真实模型）"
if ! command -v claude >/dev/null 2>&1; then
  echo "找不到 claude，评测无法运行；装好再跑（--fast 可跳这一步）" >&2
  exit 1
fi
if [ -z "${ANTHROPIC_API_KEY:-}" ] && [ -z "${ANTHROPIC_AUTH_TOKEN:-}" ]; then
  echo "提示：没看到 ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN；claude --bare 不读 OAuth，整题报错时先补凭据" >&2
fi
# 清掉上一个终端留下的 LIMIT：否则「全套」会只跑前 n 题并且不设门槛。
(cd skillmaster-cli && SKILLMASTER_GUIDE_EVAL=1 SKILLMASTER_GUIDE_EVAL_LIMIT= \
  go test ./cmd/skillmaster -run 'TestGuideEval$' -count=1 -v)

printf '\nGuide 回归通过：结构、链接、文档、生成物与 43 题评测全绿。\n'

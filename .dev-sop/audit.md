# skillmaster · 审计标准

> **一个规范一套审计标准。** 这一份对应 [`profile.md`](profile.md)；规范改了，这里跟着改。
> 判据在 `code-dev-sop` 的 `stages/`（什么算过）；这里只写**用哪条命令证明**。

| 关 / 时机 | 跑什么 | 期望 | 人/机 |
|---|---|---|---|
| 1 需求 | 范围里有「不做」；每条验收能映射到用例 | 过一遍 | 人 |
| 2 界面 | `npm run build:home && npm run build:console`（在 `design/`） | 之后 `git status` 干净（两个演示件与源一致） | 机 |
| 2 界面 | `node design/scripts/console-states.mjs "design/skillmaster-控制台演示.html" artifacts/design/console --only <组>` | 全绿 | 机 |
| 2 界面 | `node design/scripts/home-check.mjs "design/skillmaster-首页演示.html" artifacts/design/home` | 全绿 | 机 |
| 2 界面 | `node design/scripts/interaction-check.mjs "design/skillmaster-控制台演示.html" artifacts/design/states` | 全绿 | 机 |
| 2 界面 | 打开页面看渲染结果 | 有人看过 | **人** |
| 3 技术设计 | `python3 scripts/docs/impact.py --check` | 退出 0 | 机 |
| 4 测试方案 | 照「环境与数据」一节实际做一遍 | 起得来 | 人 |
| 5 实现 | `node scripts/check-file-size.mjs` | 退出 0 | 机 |
| 6 验证 | 改到哪个子项目就跑哪个（见 `profile.md` 的命令表） | 全绿 | 机 |
| 6 验证 | `cd qa/role-matrix && uv run pytest` | 代码收口之后才跑 | 机 |
| 收口 | `check_docs.py`、`impact.py --check`；`generate.py` 之后工作区干净 | 退出 0 | 机 |

## 不抄数值

阈值、组名、允许清单，一律看脚本本身与 `--help`。这里只写命令。

## 豁免

| 不跑的检查 | 为什么 | 什么时候补 |
|---|---|---|
| 三套设计检查的**全量**走查 | 有审批闸；默认只跑改动那一组，整套仍要所有者发话 | 每个大版本交付前 |
| 全量回归 | 默认只验改动面 | 所有者指令 |

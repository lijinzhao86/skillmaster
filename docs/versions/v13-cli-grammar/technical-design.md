# v13-cli-grammar · 技术设计

> **最后更新**：2026-10-22
> **状态**：已实现（`cli/skills-grammar` 分支；实现改动尚未提交）
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文只链接、不复述理由。

## 架构

本版只改 CLI 与它随包发布的网关正文，**服务端代码、API、权限和版本状态机都不动**。边界如下：

```
                    ┌──────────────────────────────┐
  终端 / agent ───▶ │ skillmaster-cli               │
                    │  run → skills / skill / version│
                    │  help / login / auth / setup  │
                    └──────────────┬───────────────┘
                                   │ HTTPS（既有 API，不改）
                    ┌──────────────▼───────────────┐
                    │ skillmaster-server           │
                    │ /api/v1/skills、/author/...  │
                    └──────────────────────────────┘
```

- 顶层分派在 `skillmaster-cli/cmd/skillmaster/main.go:46` 的 `run`。本版固定 8 个入口：`login`、`logout`、`auth`、`setup`、`skills`、`skill`、`version`、`help`。
- `skillVerb`（`main.go:192`）只保留作用于一个 skill 的动词；`skillsVerb`（`main.go:276`）只保留 `list` / `search`；`versionVerb`（`version.go:55`）保留 7 个正式动作与 7 个只拒绝的页面动作。
- 地址解析继续复用既有入口：`api.SkillPath`（`internal/api/client.go:644`）、`versionInAddress`（`main.go:1710`）、`parseVersionTarget` / `writablePin`（`version.go:495` / `:551`）、`writeTarget`（`files.go:223`）、`pinFor`（`main.go:1680`）、`resolve`（`files.go:54`）。
- 本机钉仍由 `internal/pins` 保存；本版不新增持久状态。
- `version list` 的翻页游标只在 CLI 进程内循环使用；调用者只看到列表、`--all` 和“还有更多”的提示。
- 网关正文随服务端构建打包、启动时发布（`pom.xml:175`、`GatewayConfig.java:40`）；本版改 `gateway/skillmaster/SKILL.md` 后需要重建/重启服务端才能被 `setup` 拉到。

## 数据模型

**没有数据库或服务端数据模型改动。** CLI 侧只使用既有值对象：

| 值 | 位置 | 本版用途 |
|---|---|---|
| `api.AuthorVersionList` | `internal/api/author.go:63` | `version list` 的单页/多页结果；`Next` 只在 CLI 内部使用，不进入 stdout 或 `--json` |
| `api.AuthorVersionQuery` | `internal/api/author.go:99` | `--status` / `--query` / `--limit` / 内部 cursor |
| `api.AuthorFileList` / `api.File` | `internal/api` | `files` 的文件清单 |
| `pins.Pin` | `internal/pins` | `invoke` 记住的版本；本版不改语义 |

`AuthorVersionList.Next` 的 JSON tag 从 `json:"next"` 改为 `json:"next,omitempty"`；`versionList` 在输出前把 `Next` 置空。这样 `version list --json` 不再出现服务端游标字段，同时保留内部翻页能力。JSON 对象另外带一个 CLI 自有的布尔 `has_more`（不是游标）：不带 `--all` 而服务端还有下一页时，脚本也必须看得出这份列表被截断了（PRD §3「截断必须明说」、§4 允许的 `has_more` 信号）。

## 接口

### CLI 命令面

| 组 | 命令 | 入参 | 输出 / 行为 |
|---|---|---|---|
| 顶层 | `help` | — | 8 个顶层命令；stderr；退出 0 |
| 顶层 | `login` / `logout` / `auth` / `setup` | 既有 | 不变 |
| `skills` | `list` | `[--all] [--namespace …]` | 卡片：第一行地址、缩进第二行描述；默认一页 100 |
| `skills` | `search` | `<关键词>` | 一页卡片；有更多时提示缩小关键词；不提供 `--json` |
| `skill` | `files` | `<ns>/<name>[@pin]` | `printFiles`：relpath 行、缩进提示、`read` 命令、stdout 版本说明行 |
| `skill` | `read` | `<ns>/<name>[@pin] <relpath> [--offset]` | 带行号；不提供 `--json` |
| `skill` | `rm` | `<ns>/<name>@<版本码> <路径>...` | 批量删除；部分失败逐项报错并非 0；不提供 `--json` |
| `skill` | `versions` | `<ns>/<name>` | 一次给全；不提供 `--json` |
| `version` | `list` | `<ns>/<name> [--status …] [--query …] [--limit n] [--all] [--json]` | 正式动作；不出现 `--cursor` / 游标 |
| `version` | `show` / `diff` / `rename` / `note` / `discard` / `open` | 既有 | 既有行为不变；`discard` 的 `--yes` 规则不变 |
| `version` | `publish` / `rollback` / `revoke` / `restore` / `offline` / `online` / `delete` | 地址 | 只拒绝并交接，不进帮助 |

### 服务端接口

本版**不新增、不改**任何服务端路由。`version list --all` 复用 `GET /api/v1/author/skills/{ns}/{name}/versions` 的既有 `cursor` / `limit` / `status` / `q` 参数；`skills list --all` 复用 `GET /api/v1/skills` 的既有 cursor。服务端返回的 `next` 只供 CLI 循环，不再转交给调用者。

### 错误形状

- 人话错误：stderr 一句人话 + 下一步，退出非 0。
- `--json` 错误：stdout 为空，stderr 恰好一个 `{ok:false,error:{...}}` JSON 对象；登录/进度提示可以在它之前。
- 不提供 `--json` 的命令（`cat` / `files` / `wc` / `grep` / `download` / `read` / `rm` / `skills list` / `skills search` / `skill versions`）收到 `--json` 时按未知参数拒绝，不把它当作查询词、地址或路径。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 顶层 8 个入口；`skill` / `skills` 分组；移除 `skill list` / `skill search` / `skill ls` 别名；`files` 改走 `files()` + `printFiles` | #1–#4、#17 |
| P1 | `version list` 增加 `--all`；删除 `--cursor`；人话/JSON 不出现游标；`--all` 每页带同一组过滤 | #6、#14 |
| P2 | `read` / `rm` / `skills search` / `skill versions` 拒绝 `--json`；`rejectJSON` 文案改成通用拒绝 | #13、#15 |
| P3 | 帮助文本：`skill help` / `skills help` / `version help` 分开，`versions` ↔ `list` 互相指；`version help` 宣传 `--all`、说明行含“线上”与“历史版本” | #8、#17 |
| P4 | 网关第 1 步说明；QA 探针旧命令；测试与文档检查 | #9、#10、#11 |

**每期明确不做**：P0 不碰服务端；P1 不新增 `--cursor` 兼容层；P2 不改变 `rm` 的删除语义，只拒绝 `--json`；P3 不新增帮助命令；P4 不跑 `qa/`，只改探针里的命令名。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | `version list --json` 怎么去掉 `next` 字段？ | 给 `AuthorVersionList.Next` 加 `omitempty`，输出前置空；在输出处临时包一层加 `has_more`，不改 `api.AuthorVersionList` 本身 | 单页/多页 `--json` 都不含 `next`，测试断言键不存在；`has_more` 与是否截断一致 |
| Q2 | `files` 的版本说明行放 stdout 还是 stderr？ | 沿用 `printFiles`：文件行、`read` 命令、版本说明行都在 stdout；`wc` / `grep` 的解析提示仍在 stderr | 命令级输出测试断言 stdout 包含版本说明行 |
| Q3 | `--all` 与 `--limit` 同时出现时谁优先？ | `--limit` 是每页大小，`--all` 用它循环取到底 | 假服务端两页 + `--all --limit 2` 断言每页 2、取全 |
| Q4 | `--all` 失败时是否保留已取到的页？ | 不保留；先收集，失败直接返回，不打印半份 | 假服务端第二页 500，断言 stdout 为空 |
| Q5 | 旧命令被当成未知命令后，错误文案怎么说？ | 用现有“未知的 skill 子命令” + `skillUsage`；不转发、不提示旧名字 | CLI 子进程负向用例断言 stdout 为空、退出非 0 |

## 附录 A · 与现有代码的关系

| 文件 / 符号 | 本版动作 |
|---|---|
| `main.go` `run` / `usage` | 补 `help`，去掉旧命令说明，保持 8 个入口 |
| `main.go` `skillVerb` | 删除 `list` / `search` / `ls` 分支；`files` 调 `files()`；`read` / `rm` 加 `rejectJSON` |
| `main.go` `skillsVerb` | `search` 加 `rejectJSON`；`list` 仍由 `parseListArgs` 拒绝 `--json` |
| `main.go` `search` / `listSkills` | 输出形状不变；`skills search` 仍是单页 + 收窄提示 |
| `main.go` `files` / `printFiles` | 成为 `skill files` 的唯一实现；`ls()` 删除；旧的 `filesFromAuthor` 也删除（`files()` 先 `resolve`，再按解析结果取文件） |
| `version.go` `versionUsage` / `parseVersionListArgs` / `versionList` / `printAuthorVersions` | 加 `--all`、删 `--cursor`、去游标输出、保留同一组过滤 |
| `version.go` `versions` | 加 `rejectJSON`；保持一次给全 |
| `files.go` `rm` / `rejectJSON` | `rm` 入口拒绝 `--json`；文案通用化 |
| `internal/api/author.go` `AuthorVersionList.Next` | JSON tag 加 `omitempty` |
| `gateway/skillmaster/SKILL.md` | 第 1 步“通常两行；描述为空只有地址一行” |
| `qa/role-matrix/README.md` / `role_matrix/expectations.py` | `skill ls` → `skill files` |

## 附录 B · 来源与出处

- 需求：[`prd.md`](prd.md) 的 #1–#17。
- 分组决定：[ADR 0077](../../decisions/0077-the-cli-grammar-is-singular-and-plural.md)。
- 既有行为：`skillmaster-cli/cmd/skillmaster/{main,version,files,lifecycle}.go` 与 `internal/api/{client,author}.go` 的现有实现。
- 网关发布：`skillmaster-server/pom.xml:175`、`GatewayConfig.java:40`、`GatewaySource.java:38`。

## 附录 C · 已测量的数据

本版没有新增性能指标；测试数据由 `pointAtServer` 的假服务端与临时 home 构造，口径见 [`test-plan.md`](test-plan.md)。

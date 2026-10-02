# skillmaster 系统架构设计

> 最后更新：2026-09-27
> 状态：技术设计稿，尚未进入实现
> 所属版本：[v1-hosting](README.md)——版本由文件夹承担，本文不再自带版本号
> **定位：skill 的托管与远程加载服务。** skill 全部活在服务端；用户通过网关 skill 与 CLI 远程搜索、读取、使用 skill，**本地不留 skill 副本**。
> **本版不含收费**：无定价、无权益、无门控、无钱包。已设计过的渐进付费方案见附录 B（推迟，非废弃）。
> 上一轮修订把架构从「下载到本地 + 客户端原生加载」改为「服务端权威 + 网关 skill + API」，并按此写出表结构与接口。
> 第 1 章是核实过的调研事实，与架构选择无关，改动架构时不必重做。**架构决策已独立成 [ADR](../../decisions/README.md)**，本文只链接、不复述理由。

---

## 第 0 章 · 结论摘要

### 三条结论

1. **不做「下载到本地再靠客户端原生加载」。** 本项目采用**服务端权威**：skill 的目录与文件都在服务器上，客户端通过远程地址按需读取。好处是**渐进加载天然成立**（搜索只给 L1、正文接口只给 L2、文件接口只给 L3，服务端在接口层强制），且**本地不落任何 skill 副本**。

2. **唯一需要本地安装的是一个「网关 skill」+ 一个 CLI。** 网关 skill 的 frontmatter 是常驻上下文的全部成本（≈80 tokens），它的正文记录服务地址与调用协议；CLI 负责登录与持有凭据。目录**不常驻**——搜索、排序、查找全部在服务端。

3. **一个网关 skill 解决了 L1 天花板。** 本机 30 个真实 SKILL.md 实测：每个 skill 的 L1 平均 318 字符，**其中约 33% 是 CJK 字符**——按 4 字符/token 折算约 80 tokens，但 CJK 接近 1 token/字符，实际**约 160 tokens**。500 个 skill 全量安装 = **约 8 万 tokens 常驻**（口径见 §1.6）。网关模型下常驻的只有网关一个，**目录成本从 O(N) 降到 O(1)**。

### 架构总览

```
┌──────────────────────── 服务端（唯一的 skill 真相）────────────────────────┐
│                                                                            │
│   ┌─ 身份与鉴权 ─────────────┐   ┌─ 内容 ──────────────────────────────┐   │
│   │ user / credential       │   │ namespace / skill / skill_version   │   │
│   │ identity（预留 SSO）     │   │ version_file / blob（内容寻址）      │   │
│   │ oauth_client / token    │   │                                     │   │
│   └─────────────────────────┘   └─────────────────────────────────────┘   │
│                                                                            │
│   ┌─ 检索 ───────────────┐      ┌─ 分发 ──────────────────────────────┐   │
│   │ 只索引 L1            │      │ /api/v1/skills              （搜索）    │   │
│   │ 搜索 / 排序 / 查找    │      │ /api/v1/skills/{ns}/{name}[@v]          │   │
│   └──────────────────────┘      │                    （详情+清单）    │   │
│                                 │   …/body           （L2）           │   │
│                                 │   …/files/{p}      （L3）           │   │
│                                 │ /.well-known/...  （只发布网关）     │   │
│                                 └─────────────────────────────────────┘   │
└───────────────────────────────────┬────────────────────────────────────────┘
                                    │
              ┌─────────────────────┴─────────────────────┐
              ▼                                           ▼
   ┌─ CLI（普适客户端）──────────┐          ┌─ MCP 适配器（可选，以后加）─┐
   │ login / setup / search /   │          │ skills/list ≡ /api/v1/skills    │
   │ show / get                 │          │ resources/read ≡ body+files │
   │ 持有凭据，不进模型上下文    │          │ 复用同一个 AS               │
   └────────────┬───────────────┘          └─────────────────────────────┘
                │ 安装一个网关 skill
                ▼
   ┌─ 网关 skill（唯一的本地产物）───────────────────────────────────────┐
   │ frontmatter：常驻上下文（≈80 tokens），description 写得足够广        │
   │ 正文：服务地址 + 调用协议（先搜索 → 看清单 → 按需读正文/文件）        │
   └──────────────────────────────────────────────────────────────────────┘
                │ agent 依协议调用
                ▼
        远程取到 skill 内容 → 使用
```

### 已锁定的架构决策

| 决策 | 内容 |
|---|---|
| 定位 | 托管 + 远程加载 + 使用；**不含收费** |
| 权威位置 | **服务端**；本地不落 skill 副本 |
| 主契约 | **API**（四个读接口 + 服务端搜索排序）；MCP 是可选适配器 |
| 客户端 | **自研 CLI**，持有凭据；普适（交互式 loopback PKCE + 无人值守 Client Credentials） |
| 网关粒度 | **一个通用网关 skill** |
| 搜索排序 | **全部在服务端**（目录不常驻，服务端就是索引） |
| 鉴权 | **自建登录 + 自建令牌签发**（OAuth 2.1 AS）。终局要支持 CIMD + DCR + 预注册；**v1 只做预注册**，CIMD 到 P2、DCR 不启用（[ADR 0011](../../decisions/0011-server-and-cli-stack.md)、§7） |
| skill 主键 | **不透明的 `id`**，永不变；`name` 是属性，唯一性约束在 `(namespace_id, name)` |
| 脚本执行 | P0 不做 |

---

## 第 1 章 · 调研结论（可核实的事实）

本章只写有出处的事实，推断单独标注。**每条结论都给了出处**，核实到 2026-09-26；个别客户端细节的把握程度见 §1.7 的信心说明——**不要把这一章读成"每条都验证到底"**。

### 1.1 Agent Skills 是真实的开放标准

出处：[agentskills.io/specification](https://agentskills.io/specification)、[客户端实现指南](https://agentskills.io/client-implementation/adding-skills-support)。

- 一个 skill = 一个目录，至少含 `SKILL.md`（YAML frontmatter + Markdown 正文），可选 `scripts/`、`references/`、`assets/`。
- frontmatter 约束（规范原文）：

| 字段 | 必填 | 约束 |
|---|---|---|
| `name` | 是 | ≤64 字符；仅小写字母数字与连字符；不得以连字符开头/结尾；不得连续连字符；**必须等于父目录名** |
| `description` | 是 | 1–1024 字符，非空 |
| `license` | 否 | 许可证名或文件引用 |
| `compatibility` | 否 | ≤500 字符 |
| `metadata` | 否 | string→string 映射（**实际生态里会被嵌套**，见 1.5 的飞书案例） |
| `allowed-tools` | 否 | 空格分隔的预批准工具（实验性） |

- 三层渐进加载（规范原文的分层与 token 指引）：
  1. **Metadata**（~50–100 tokens）：`name` + `description`，启动时为**所有** skill 加载
  2. **Instructions**（建议 <5000 tokens）：SKILL.md 正文，skill 被激活时加载
  3. **Resources**（按需）：`scripts/` `references/` `assets/` 里的文件，仅在正文引用到时加载

- **`.agents/skills/` 是跨客户端约定**（`~/.agents/skills/` 与 `<project>/.agents/skills/`），实现指南明确列出该路径用于跨客户端互操作。
- 客户端实现指南给了**两种激活模式**：文件读取激活，与**专用工具激活**（如 `activate_skill`）。指南说专用工具在"模型无法直接读文件"时是**必需**的、在能读时"可选但有用"，并明确"两种做法在实践中都可行"——**它没有把哪一种排在前面**。专用工具的好处包括：控制返回内容、用结构化标签包裹、列出附属资源、执行权限控制或请求用户同意、记录激活用于分析。
- 实现指南明确云端/沙箱 agent 的处境：没有本地文件系统时，"需要一个替代的发现机制——一个 API、一个远程 registry，或者内置资源"。**这正是本项目的立足点。**
- 过滤器规则：被排除的 skill 必须整个从目录里隐藏，而不是列出来再在激活时拦截。

> **对方案的直接意义**：标准只规定 skill 目录里放什么，**不规定它从哪来**。所以"服务端托管 + 远程读取"在标准层面完全开放——我们不需要自创概念。

### 1.2 Claude Code 的实际加载行为

出处：[code.claude.com/docs/en/skills](https://code.claude.com/docs/en/skills)、[plugins/host-marketplace](https://code.claude.com/docs/en/plugins/host-marketplace)、[mcp](https://code.claude.com/docs/en/mcp)、[issue #11054](https://github.com/anthropics/claude-code/issues/11054)。

**加载位置**（至少这五个）：企业托管目录、`~/.claude/skills/`、`<project>/.claude/skills/`、plugin 内的 `skills/`、claude.ai 同步的 `~/.claude/skills/synced/`。官方表还列了嵌套的 `<subdir>/.claude/skills/` 与 `--add-dir` 指定的目录，共七行。

**三层的实际成本**：
- frontmatter 的 `description` + `when_to_use` 每轮常驻，**截断于 1536 字符**
- 正文在被调用时加载，且**此后整个会话常驻**
- 附属文件按需读取

**MCP 的能力边界（决定了 MCP 只能是适配器）**：
- **MCP 无法注入 skill。** skill 是宿主特性，SDK 不提供编程注册接口。
- MCP resources **不会自动注入**，只在模型调用 `ListMcpResourcesTool` / `ReadMcpResourceTool` 或用户 `@server:uri` 时可见。
- MCP prompts **对模型完全不可见**（issue #11054）。
- MCP 只能"把 skill 文本作为工具结果返回"，模型把它当数据读——没有自动调用，没有原生三层披露。

**官方支持的、能落盘的远程路径：plugin marketplace**
- `marketplace.json` 可托管在**一个普通 HTTPS URL** 上，且**只下载那一个文件** → 支持按请求动态生成。
- entry 的 `archive` source 支持 `sha256` 固定，**拒绝 digest 不匹配的下载**（需 Claude Code ≥ v2.1.224）。
- 认证 header 放在 **marketplace 的 `url` source** 上；放到 entry 的 `headersHelper` 上会**弄坏后台自动更新**。
- 自动更新对第三方 marketplace **默认关闭**。

> **本项目不使用 marketplace 通道**（它意味着把内容下载到本地）。记录在此是因为 MCP 适配器将来若要支持"原生安装体验"会用到。

### 1.3 MCP 的 Skills 扩展（就是我们的 API 形状）

出处：[extensions/skills/overview](https://modelcontextprotocol.io/extensions/skills/overview)、[SEP-2640](https://modelcontextprotocol.io/seps/2640-skills-extension)、[客户端支持矩阵](https://modelcontextprotocol.io/extensions/client-matrix)。

- 标识符 `io.modelcontextprotocol/skills`，**SEP-2640 已 Final**。方法：`skills/list`、`skills/get`（**必须**实现）、`resources/read`、可选的 `resources/directory/read`。

**与我们的接口逐条对应**（所以 MCP 适配器会很薄）：

| 扩展机制 | 我们的接口 |
|---|---|
| `skills/list` → `frontmatter` + `uri` + 完整文件清单 | `GET /api/v1/skills`（搜索）+ `GET /api/v1/skills/{ns}/{name}[@v]`（清单） |
| `resources/read` on `skill://<name>/SKILL.md` | `GET /api/v1/skills/{ns}/{name}[@v]/body` |
| `resources/read` on 清单里的每个 URI | `GET /api/v1/skills/{ns}/{name}[@v]/files/{relpath}`——**清单每项自带这条 `uri`** |

**规范中我们直接采用的条款**（原文）：

- **"Hosts MUST NOT retrieve files ahead of need, including on connection, listing, or approval."** —— 渐进披露是协议强制的。
- 清单**必须**含 `SKILL.md` 与每个附属文件，每项带 URI、SHA-256 digest、字节大小。**清单让客户端无需下载即可枚举 L3。**
- 服务端 **SHOULD NOT** 超过 **每 skill 512 文件 / 16 MiB**（含 SKILL.md）。→ **成为我们上传校验的上限依据。**
- **"Persisted approval MUST bind to the complete set of file URIs and digests. A changed, added, or removed file revokes that approval."** → **发布新版本即改变 digest 集合，客户端据此重新取得批准。这层完整性机制不用我们做。**
- `SKILL.md` 父目录路径的最后一段 **MUST** 等于 `name`。
- 未知 skill/文件 → `-32602`。
- 安全：宿主 **MUST** 防止同名 skill 静默互相替换；**MUST** 把 skill 内容视为不可信输入。

**客户端支持现状**：矩阵里 Claude (web)/Desktop、Cursor、VS Code Copilot、Microsoft 365 Copilot、Goose、Postman 等**全空白**；只有 ChatGPT、fast-agent、MCP Inspector 标 Partial。**扩展已 Final，宿主没跟上。**

### 1.4 MCP 协议现状与鉴权（为什么 MCP 只当适配器）

出处：[2026-07-28 changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog)、[authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)。

**协议 churn 是实证的**：**上一个修订版 2025-11-25 → 2026-07-28 是一次破坏性重写**——移除协议级会话与 `Mcp-Session-Id`、移除 `initialize` 握手、服务端不得再发起 JSON-RPC 请求、elicitation 改由 MRTR 承载。（changelog 的原话是"自上一个修订版以来"；2025-06-18 是更早的版本，不要拿它当基线。）Roots/Sampling/Logging 进入废弃；OAuth DCR 被 CIMD 取代（仅保留兼容）。规范还引入了功能生命周期与废弃策略（最短 12 个月窗口 + 废弃登记表）。**Claude Code 侧的具体成本**：它有两个 runtime（v1 基于 TS SDK 1.x；v2 基于 SDK 2.0 才支持 2026-07-28），**每次启动自己挑一个** → 服务端必须跨修订可用。

**鉴权规范的关键约束**（若将来做 MCP 适配器，这些是 MUST）：

- MCP server = OAuth 2.1 **resource server**；MCP client = OAuth 2.1 **client**（**客户端做 OAuth 流程**）
- server **MUST** 实现 RFC 9728 Protected Resource Metadata；client **MUST** 用它做 AS 发现
- AS **MUST** 至少提供 RFC 8414 AS Metadata 或 OIDC Discovery 之一
- client **MUST** 实现 RFC 8707 resource indicators；server **MUST** 校验 token audience，**MUST NOT** 中转任何其他 token
- 注册方式：CIMD **SHOULD**（推荐默认）；DCR **MAY** 但已废弃；另有预注册
- token 走 `Authorization: Bearer`，**MUST NOT** 出现在 query string
- 授权是可选的："Authorization is **OPTIONAL** for MCP implementations"

### 1.5 现成的远程托管约定：well-known 索引

**消费方已查清：`skills`（Vercel Labs）。**

- npm 包 `skills`（bin `skills`/`add-skill`），仓库 [vercel-labs/skills](https://github.com/vercel-labs/skills)，支持 80 个 agent（含 Claude Code、Codex、Cursor）。安装命令 `npx skills add <url>`。**symlink 模式是该 CLI 推荐的安装模式。**
- lock 路径：`$XDG_STATE_HOME/skills/.skill-lock.json`，否则 `~/.agents/.skill-lock.json`。
- lock 字段：`source`、`sourceType`、`sourceUrl`、`sourceBaseUrl`、`wellKnownDigest`、`installedAt`、`updatedAt`、`skillFolderHash`。

**协议**（`/.well-known/agent-skills/` 首选，`/.well-known/skills/` 是 v0.1.0 遗留回退）：

| | 形状 | 更新检测 |
|---|---|---|
| **V1** | `{ skills: [{ name, description, files: string[] }] }`——逐文件，客户端逐个拉取 | **客户端自行计算**：按路径排序后对 `path` + `\0` + 字节 + `\0` 逐项 sha256。**分隔符是 NUL，不是空格**（已对本机缓存的 CLI 1.5.24 反查 `computeWellKnownSkillDigest` 确认） |
| **V2** | `{ $schema, skills: [{ name, type: "skill-md"\|"archive", description, url, digest }] }` | 直接取 entry 的 `digest` |

解析顺序：`agent-skills/index.json` → `skills/index.json`；**先按路径相对、再按根**。该 provider 刻意拒绝在带 scope 的路径没内容时回退到根索引。

**这不是 Agent Skills 标准的一部分**——`agentskills.io/llms.txt` 的文档索引里**没有 discovery/publishing/hosting 章节**，它是这个 CLI 的私有约定。一个例外值得记：V2 索引的 `$schema` 指向 `agentskills.io` 域下的 `discovery/0.2.0` 草案，CLI 源码称其为 "the v0.2.0 draft"——所以它挂在标准轨道边上，但**仍是草案、未进标准**（该 schema URL 目前 DNS 不解析，内容无法核实）。**所以它随时可能变，要当外部依赖管理。**

**飞书是这个通道的真实生产用户**（2026-09-26 本机实测）：

| URL | 结果 |
|---|---|
| `open.feishu.cn/.well-known/skills/index.json` | **200，`application/json`，39,955 B，28 条**，entry 为 `{name, description, files[]}` → **V1** |
| `open.feishu.cn/.well-known/skills/lark-approval/SKILL.md` | 200，`text/markdown`，6,969 B |
| `open.feishu.cn/.well-known/agent-skills/index.json` | **200 但 `text/html`** —— SPA catch-all，**不是发布的索引** |

**两个必须记住的坑**：

1. **首选路径在不发布它的主机上返回 200 + HTML**（不是 404）。所以我们要发布时**两条路径都发**，且**自己未发布时必须返回真 404**。
2. 旧版 CLI 只认 legacy 路径——首选路径是 2026-03-23 才加进 CLI 的，**分界在 1.4.5**：实测 npm 上 **1.4.5 及以前没有**该路径，**1.4.6 起才有**。本机缓存的 1.5.12 与 1.5.24 都已认（已反查确认），所以"本机版本"不是风险来源，风险来自更老的客户端。**两条路径都发仍然是对的，但理由是兼容 1.4.5 及以前**，不是"本机版本可能不认"。

飞书用的 `version`、`metadata.requires.bins`、`metadata.cliHelp` 都是**发布方扩展**，不属标准字段集（且 `metadata` 实际是嵌套的）。**我们要原样透传未知字段**，否则会丢作者的元数据。

**两条硬约束**：**没有认证**（provider 里 `authorization`/`Bearer` 零出现）；**没有任何门控概念**（全仓搜 entitlement/paywall/billing 无结果）。→ **本项目的用法：这条通道只用来发布「网关 skill」这一个公开产物**，其余全部走鉴权后的 API。

### 1.6 实测数据（决定设计的数字）

对本机 30 个真实 SKILL.md 的测量（`~/.agents/skills/lark-*` 等 28 个，加 `~/.claude/skills/lark/` 与 `lark/feishu-bridge/`）。**每行都写明口径，以便复现**：

| 指标 | 口径 | 数值 |
|---|---|---|
| L1（frontmatter）平均 | `---` 之间的内容，不含分隔符 | **318 字符** |
| 其中 CJK 字符占比 | 同上；CJK＝表意文字 + CJK 标点 + 全角 + 假名 | **32.7%**（`lark-task` 最高 58.7%） |
| L1 tokens / skill | 4 字符/token（只对拉丁文本成立） | ≈80 |
| L1 tokens / skill | CJK 按 ~1 token/字符折算 | **≈160** ← 本样本的正确量级 |
| L1 / 全部内容 | L1 ÷（L1 + 正文） | **4.2%** |
| 500 个 skill 全装时的 L1 常驻 | 按 CJK 折算 | **≈ 8 万 tokens** |
| L2 正文 中位数 / 最大 | SKILL.md 去掉 frontmatter，字符 | 3,962 / 25,213 |
| L3 单文件字节 中位数 / 最大 | 每个非 `SKILL.md` 文件 | 4,362 / 746,862 |
| L3 每 skill 合计字节 中位数 / 最大 | 一个 skill 的全部非 `SKILL.md` 文件之和；**9 个无 L3 的按 0 计入** | 29,736 / 1,570,809 |
| ↳ 同上，只算有 L3 的 21 个 skill | 剔除那 9 个 0 | 148,017 |
| 没有 L3 文件的 skill | — | 9 / 30（4 个正文仅 **133** 字符，是指针型；另 5 个有实质正文） |
| `name` 与父目录名不符 | — | `lark/im/`（name `lark-im`）等**不符**；`~/.agents/skills/lark-*` 扁平布局**符合** |

**两条推论**：

- **L1 是天花板。** 单个 skill 的 L1 看着很小（本机样本约 160 tokens），但它在"所有已安装 skill"上线性常驻——500 个就是 8 万。**→ 这是网关模型的直接理由**：常驻 1 个网关，而不是 N 个 skill。
- **命名合规不是形式问题。** 标准要求 `name` 等于父目录名。服务端托管时这个约束作用在**导出/打包**上；内部存储用 `relpath`，不受影响。

### 1.7 MCP 鉴权的客户端支持现状（决定「CLI 是普适客户端」）

出处：MCP 扩展支持矩阵、各客户端官方文档（详见附录 C）。

| 客户端 | 浏览器 OAuth | 静态 header |
|---|---|---|
| Claude Code | ✅ `/mcp`、`claude mcp login/logout` | ✅ `--header` / `headersHelper` |
| Claude Desktop / Claude.ai (web) | ✅ | — |
| ChatGPT | ✅ OAuth 2.1 + PKCE，**CIMD 优先** | API key 与其 OAuth connector 不原生兼容 |
| Cursor | ✅ | ✅ `headers` |
| VS Code / Copilot | ✅ 由 VS Code 跑流程 | ✅ `${input:}` 安全提示 |
| Codex CLI | ✅ `codex mcp login`，**仅 HTTP** | 走 config |
| Gemini CLI | ✅ **DCR**；文档明说无浏览器环境不工作 | ✅ `-H` |
| OpenCode / Antigravity | ✅ | — |
| Zed / Cline / Continue / Open WebUI / Goose / LibreChat | 部分 | — |
| Windsurf / Amazon Q | 未知 / 未实现 | — |

**两类结论**：

1. **交互式鉴权：覆盖广。** 主流客户端都做浏览器 OAuth。但**连接方式受限**——Codex 是 HTTP-only；Gemini CLI 明说「无浏览器、远程 SSH 无 X11 转发、无浏览器的容器环境都不工作」。且**静态 header 是人人都有、但没有一个是"登录体验"**——都要用户去配置文件粘贴 token。

2. **无人值守 / M2M：现在没人支持。** OAuth **Client Credentials 扩展还是 Draft，客户端矩阵里只有 Archestra.AI 打勾**。官方 SDK 已实现（TS/Python 都有），所以**服务端容易做**，但**没有现成 agent 能用**。调研原话：**「CI/headless 需要一个基于 SDK 自研的客户端，而不是现成 agent。」**

> **对方案的直接意义**：**CLI 不是 MCP 的退路，它是唯一覆盖无人值守场景的客户端。** 分工是——交互式用户可用 MCP OAuth（体验好），**无人值守只有 CLI 能覆盖**。

**一个实现上的坑：CIMD 支持很薄。** 已确认发布 CIMD 的只有 Claude Code、VS Code、ChatGPT；Cursor / Gemini CLI / Codex 未确认（Gemini CLI 文档压根没提，明确说走 DCR）；且 2025-12 时 Auth0、Okta、Cognito、Entra、Google Identity **都还没实现 CIMD**。→ 所以 AS 最终**必须同时支持三种**，只做一种会卡死一批客户端。

> **2026-09-27 补充**：**MCP 规范 2026-07-28 已把 DCR 标为 deprecated**，原话 *"New implementations should use Client ID Metadata Documents instead."*；2025-11-25 那版是 CIMD `SHOULD` / DCR `MAY` / 预注册 `SHOULD`，RFC 8707 `resource` 是 `MUST`。**方向已经明确：CIMD 是主路径**。而 CIMD 本身**仍只是 Internet-Draft**（`draft-ietf-oauth-client-id-metadata-document-02`）——这也正是现成产品都不支持它的原因。
>
> **这不改变「三种都要」的最终要求，改变的是「什么时候要」**：预注册 v1 就要，CIMD 到 P2 才要，DCR 只作兼容。分期见 §7，依据见 [ADR 0011](../../decisions/0011-server-and-cli-stack.md)。

**信心说明**：本节部分依赖社区维护的矩阵与文档镜像（Codex 官方文档与部分厂商文档抓取 403，只能靠二手）。**「交互式覆盖广、M2M 无人支持」这两个结论稳；「每个客户端的具体细节」按需再核。**

---

## 第 2 章 · 系统架构

### 2.1 三个平面

```
管理面 ── 上传 / 校验 / 版本 / 命名空间 / 成员（v1 仅所有者） / 审计
   │ 发布：不可变版本 + digest
存储面 ── blob（按 sha256 内容寻址）/ skill / version / manifest
   │ 检索与分发
分发面 ── 搜索 / 详情 / 正文 / 文件 / 鉴权 / （可选）MCP 适配器
   │
客户端 ── CLI（持有凭据）→ 安装网关 skill → agent 依协议按需读取
```

**最重要的边界：服务端是唯一的 skill 真相。** 本地只有两样东西——CLI 与网关 skill。**skill 的正文与文件永远不落到本地**（agent 为完成任务的中间产物落盘不算，那是两回事，见 4.6）。

### 2.2 组件

| 组件 | 职责 | 起步形态 |
|---|---|---|
| **API（资源服务器）** | 搜索、详情、正文、文件；校验令牌、按 subject 过滤 | Java + Spring Boot 4 / Spring Security 7 + PostgreSQL |
| **AS（授权服务器）** | 登录、授权、令牌签发与刷新、撤销 | 与 API 同进程（规范允许），**用 Spring Security 的 Authorization Server**——[ADR 0011](../../decisions/0011-server-and-cli-stack.md) |
| **Blob Store** | 按 sha256 存文件字节 | PostgreSQL 的 `bytea`（单独表 + 单独表空间），藏在 `BlobStore` 接口之后——[ADR 0010](../../decisions/0010-storage-in-postgres.md) |
| **Blob GC** | 回收无版本引用的 blob | 后台任务，**与版本变更在同一个事务里**（按引用计数） |
| **CLI** | 登录、装网关、搜/看/取；**持有凭据** | 独立的 `skillmaster-cli/`（§2.4）；**Go**（[ADR 0011](../../decisions/0011-server-and-cli-stack.md)）。基线里没有可复用的客户端代码 |
| **MCP 适配器** | 把 `/api/v1/*` 包成 `skills/list` + `resources/read` | P2，能力协商 |

**起步全部可以跑在一个进程 + 一个 PostgreSQL 上**，不要过早拆服务。**PostgreSQL 是本项目唯一的状态存储**——元数据、权限、审计与文件字节都在里面（[ADR 0010](../../decisions/0010-storage-in-postgres.md)）。

### 2.3 一次读的完整路径

```
用户提问
   ↓
网关 skill 的 description 命中（常驻上下文，≈80 tokens）
   ↓
agent 读网关正文（L2，本地）→ 知道服务地址与调用协议
   ↓
CLI search "<关键词>"  →  GET /api/v1/skills?q=...   （只返回 L1 卡片）
   ↓
挑中一个 → CLI show <ns>/<name>  →  GET /api/v1/skills/{ns}/{name}
   ↓                                  （L1 + 文件清单，零内容；响应里把版本解析成 @3 并钉住）
判断正文相关 → CLI get <ns>/<name>@3  →  GET /api/v1/skills/{ns}/{name}@3/body   （L2）
   ↓
正文引用了某个文件 → CLI get <ns>/<name>@3 <relpath>  →  …/files/{relpath}  （L3）
   ↓
agent 依 skill 指示完成任务
```

**服务端在接口层强制分层**：搜索接口物理上拿不到正文，正文接口拿不到文件，文件接口只给单个文件。**这就是渐进加载的落实方式**——不靠模型自觉，靠接口形状。

**`@3` 是从详情的 `uri` 里抄来的，不是 agent 自己拼的。** 第二步没写版本（等于 `latest`），响应把它解析出来并写进每个文件的 `uri`；后面两步照抄。这样「一次任务里版本钉死」不需要服务端记任何东西——中间谁发布了 `@4` 都不影响，因为后续请求**没有再问过 latest**（[ADR 0012](../../decisions/0012-addressing-and-version-pinning.md)）。

### 2.4 代码仓库布局

**一个仓库，多个独立构建的子项目。** 每个子项目在自己的目录里自包含——工具链、测试、Dockerfile、CI 都是它自己的事；**仓库根不假设任何语言**，所以第三个子项目是 Node、Rust 还是别的，都不必改动已有的。

| 路径 | 是什么 |
|---|---|
| `skillmaster-server/` | API + AS + Blob Store + GC，**同一个进程**（§2.2 的表）。**Java 25（LTS）/ Spring Boot 4**（[ADR 0011](../../decisions/0011-server-and-cli-stack.md)）：`pom.xml` + `src/main/java/com/skillmasterai/`，`mvnw` 随仓库走，`Dockerfile` 也在这里。它的 CI 是仓库根的 `.github/workflows/server.yml`——workflow 只能放在仓库根，**不能放进子项目目录**。目录里的 `reference-python/` 是**归档的设计参考**，不参与构建，见第 6 章 |
| `skillmaster-cli/` | CLI（§4.6 的两组子命令）。**技术栈是 Go**（[ADR 0011](../../decisions/0011-server-and-cli-stack.md)）；写出代码之前，目录里仍只有一份说明 |
| `skillmaster-web/` | 浏览器端页面（登录 / 注册 / 找回密码）。**Vue 3 + Vite + TypeScript**（[ADR 0015](../../decisions/0015-web-frontend-stack.md)）：`src/` + `tests/`，产物是静态文件（`dist/`，**不提交**）。它是一个纯客户端——**没有新增任何服务端端点**，接的是服务端已有的 `/web/*`（[`iterations/0011`](iterations/0011-register-flow-and-sms-state.md) 之后是十个）。它的 CI 是 `.github/workflows/web.yml`，不需要数据库也不需要服务端 |
| `gateway/skillmaster/` | 网关 skill 的源（§5）。**发布时用的就是它这个目录** |
| `.claude/skills/docs-architecture/` | 文档约定的权威：规则（`SKILL.md`）、模板，以及 `scripts/` 里那个校验器与它的测试。跨子项目，不属于任何一个包 |
| `.github/workflows/` | 一个 workflow 服务一个子项目，外加一个服务 `docs/` 与 `.claude/`（它们不属于任何子项目）；各自带 `paths` 过滤，改 A 不会触发 B 的 CI。**当前状态：`server.yml` 与 `docs.yml` 已配好、也在 CI 上实跑验证过（两个 check 均 pass），但已用 `gh workflow disable` 停用**——按产品负责人的要求，现在只把仓库当版本库用。`web.yml` 是这一轮新加的，**从没跑过**；注意 `disable` 是**远端状态、不在 git 里**：改名或新增 workflow 文件会被 GitHub 当成新 workflow 而**自动启用**（本仓库已经这样意外启用过一次），所以 `web.yml` 一推上去，这个 check 就会开始跑 |

**四条约束**：

1. **仓库根不放任何单一语言的东西。** 没有根 `pyproject.toml`，也没有根 `package.json`。根上只留跨子项目的内容：`docs/`、`gateway/`、`.github/`、`.claude/`，以及各子项目自己的目录。每个子项目的工具链配置在**它自己的目录里**——这正是「技术栈可以异构」与「可以单独 CI/CD」这两条要求的落点。
   - `.claude/skills/docs-architecture/scripts/` 里确实有一个 Python 小项目，但它**不是子项目**，而是跨子项目的仓库工具的宿主：把它的配置放进那个 skill 目录，正是为了**不在仓库根放语言配置**。校验器与它的测试同处一目录，ruff 因此就近找到规则集，不必传 `--config`。
2. **不为可能出现的子项目预留目录**（不用 `packages/*`、`apps/*`）。现在有几个就摆几个；真要多一个，加一个目录、加一个 workflow 即可，已有子项目一个字都不用动。同理，不预先造 `cli.yml`：CLI 的技术栈虽已定（Go，[ADR 0011](../../decisions/0011-server-and-cli-stack.md)），但在有代码之前，一个跑不出任何东西的 workflow 只会假装绿。
3. **`gateway/` 是跨子项目的，所以它不放在任何子项目目录里。** 服务端要它（`GET /gateway/SKILL.md`、发布到 well-known），CLI 也要它（`setup` 装它）。它同时是一个 skill 目录——标准要求目录名等于 `name`（§1.1），所以是 `gateway/skillmaster/`；直接放在仓库根下会得到 `skillmaster/skillmaster/`。名字用 `gateway` 而不是 `skills`：复数会暗示这里有一堆 skill，而实际永远只有 §4.5 那一个。
4. **Dockerfile 跟着它构建的东西走。** 服务端镜像的定义在 `skillmaster-server/Dockerfile`，不在仓库根的 `.cicd/`。构建上下文**仍是仓库根**（镜像要一并带上 `gateway/`），由 `.dockerignore` 收敛；服务端的 workflow 因此也在 `gateway/**` 变化时触发。

### 2.5 逻辑模块

§2.2 的「组件」是**运行时单位**——它回答「哪些东西跑在同一个进程里」。这一节是**业务逻辑的切分**：每个模块有自己的表、自己的不变量、自己承载的接口。两者不是一回事：`Blob Store` 与 `Blob GC` 在 §2.2 里是两个组件，而逻辑上「按 sha256 存字节」属于 M6、「引用计数回收」属于 M7——后者还受 §3.3 点 5 那条「与版本变更同事务」的约束。

**11 个模块，外加一层用例编排。**

| # | 模块 | 职责 | 拥有（表 / 接口） | 分期 |
|---|---|---|---|---|
| M1 | 账号登录 | 注册、登录、登出、密码重置、凭据、浏览器会话、短信与频控 | `app_user`、`credential`、`identity`、`phone_verification`、`auth_throttle`、`spring_session`、`spring_session_attributes`；`/web/login`、`/web/logout`、`/web/register`、`/web/reset`、`/web/session` | P1（**已实现**，令牌除外） |
| M2 | 令牌与 AS | 授权、签发、刷新、撤销、发现端点、客户端查找 | `oauth_client`、`auth_code`、`access_token`、`refresh_token`；`/oauth/*`、`/.well-known/oauth-authorization-server`、`/.well-known/oauth-protected-resource`、`/.well-known/jwks.json` | P1 |
| M3 | 请求鉴权 | 校验 Bearer、取出 subject 与 scope、401/403 的 MCP 形状 | 无表；横切 `/api/v1/**` 与 `WWW-Authenticate` 形状 | **P0** |
| M4 | 命名空间与权限 | 个人命名空间生命周期、保留 slug、受权判定、可见性过滤 | `namespace`、`namespace_member` | P0（所有者）/ P1（可见性） |
| M5 | 上传与校验 | 接收上传物、完整 YAML 解析 frontmatter、路径 / 大小 / 符号链接校验 | 无表；产物是「一批 (relpath, bytes)」 | P0 |
| M6 | 内容寻址存储 | 按 sha256 存 / 取字节、天然去重 | `blob`、`blob_content` | P0 |
| M7 | 版本与发布 | digest、不可变版本、幂等发布、当前版本指针、软删 / 恢复 / 回滚、引用计数 GC | `skill`、`skill_version`、`version_file` | P0（发布、软删）/ P2（回滚、版本历史） |
| M8 | 检索与排序 | 索引维护（只 L1 三字段）、查询、所有权过滤、可解释排序 | `GET /api/v1/skills`；索引物理形态待定（§8 问题 3） | P0（启发式）/ P2（用上 `skill_stat`） |
| M9 | 分发 | 详情 + 文件清单（零内容）、正文 L2、单文件 L3 | `GET /api/v1/skills/{ns}/{name}[@版本]`、`/body`、`/files/{relpath}` | P0 |
| M10 | 审计与统计 | 留痕、计数 | `audit_event`、`skill_stat` | P0（审计）/ P1（统计） |
| M11 | 网关发布与发现 | well-known 两条路径的索引、逐文件拉取、`/gateway/SKILL.md`；把仓库 `gateway/` 灌进保留命名空间 | §4.5 那 4 个端点；**不拥有表**，取字节调 M6 / M7 | P0 |
| — | **用例层** | 每个对外用例一个编排者；**跨模块事务的边界在这里划定** | 无表 | P0 起 |
| — | **基础约定**（非模块） | 迁移、连接池、游标分页、错误形状、配置 | — | P0 |

**跨模块用例至少有四条**：注册（M1 + M4，必须同事务写 `app_user` + `namespace` + `namespace_member`）、发布（M5 → M6 → M7 → M10）、读详情（M3 → M4 → M7 → M9）、搜索（M3 → M4 → M8）。

**三条依赖规则**（都可用包边界加测试检查）：

1. **一个模块只能读写自己拥有的表。** 跨模块取数必须经对方接口。
2. **跨模块的事务边界只能由用例层划定。** 模块内部自己的不变量可以有自己的事务——例如 M7 的 GC 必须与版本变更同事务（§3.3 点 5）。
3. **禁止循环依赖。**

#### 规则①的落法：表的所有者执行，另一个模块只出谓词

上面那张表把**职责**和**表**分给了不同模块，于是规则①在上面的职责划分下会自相矛盾。实现期撞到三次，每次都按同一条落法解决——记在这里，因为再遇到时不该重新讨论：

| 冲突 | 落法 |
|---|---|
| M7 的「引用计数 GC」要删 M6 的 `blob` | M6 出 `BlobStore.deleteUnreferenced(Set<String>)`；M7 从自己的 `version_file` 算出「仍被引用的 hash 集合」传过去 |
| M4 判断个人命名空间要读 M1 的 `app_user.handle` | M1 开只读接缝 `AccountDirectory.handleOf(userId)`，M4 依赖 M1 |
| M8 的搜索要读 M7 的 `skill`/`skill_version` | M7 出只读 catalog 接缝（`SkillCatalogService.page(...)`）；M8 只出谓词（pattern、权重、排序身份、游标），**不持有任何 SQL** |

**判据是一句话：一条 SQL 语句不得同时点两个模块的表。** 让所有者执行、另一模块传普通值（`Set`/`int`/`String`），接口里就不会出现对方模块的类型，也就不会产生循环依赖。

配套的一条，同样来自分层规则：**`config` 层不得被任何模块访问**，所以模块需要的配置值必须由 `config` 造好成 bean 传进去（`config/RankingConfig` 造 M8 的权重、`config/GatewayConfig` 造 M11 的设置），模块不能直接读配置。

> **M1 的细节已迁出**：那个模块的职责、边界、拥有的表与不变量在 [`architecture/modules/M01-account-login.md`](../../architecture/modules/M01-account-login.md)，**这一节只保留模块清单**。注册与重置的端点在 §4.4。

---

## 第 3 章 · 领域模型与表结构

引擎是 **PostgreSQL**（[ADR 0010](../../decisions/0010-storage-in-postgres.md)）——它是本项目**唯一**的状态存储，元数据、权限、审计与**文件字节**都在里面。时间一律 RFC3339 UTC 字符串；主键一律 ULID（不透明、可按时间排序、无自增泄露）。

> **先看概念模型**：本章写的是**表结构**（DDL 意图）。实体之间的关系、**每个实体的身份是什么**、四层粒度（字节 / 文件 / 版本 / skill）为什么各有各的 id、以及如何寻址——在 [`architecture/model.md`](../../architecture/model.md)（**已移出版本文档，架构不随版本变**）。两者分工是：模型讲「有哪些东西、各自的身份是什么」，本章讲「落到表上长什么样」。**身份混淆（尤其是把版本序号当成版本身份）是这里最容易犯的错，模型那篇专门讲这个。**

### 3.1 身份与鉴权

> **模块文档**：本节是**表结构**——「这一版建哪些表」。M1 的职责、边界、不变量与对外契约在
> [`architecture/modules/M01-account-login.md`](../../architecture/modules/M01-account-login.md)；
> 登录标识与公开身份为什么分开，见 [ADR 0013](../../decisions/0013-phone-login-and-username-slug.md)。
> **理由不在这里复述。**

> **表名是 `app_user`，不是 `user`。** `user` 是 PostgreSQL 的保留字，`CREATE TABLE user` 直接是
> 语法错误——本文档早先的 DDL 就是这么写的，实现时才暴露。只改表名，字段与语义不变。

```sql
CREATE TABLE app_user (
  id           TEXT PRIMARY KEY,              -- ULID
  handle       TEXT NOT NULL UNIQUE,          -- 用户名；也是个人命名空间的 slug（公开）
  phone_hash   TEXT UNIQUE,                   -- HMAC-SHA256(手机号)：登录查找用，不是明文
  phone_enc    TEXT,                          -- AES-GCM(手机号)：需要展示时才解
  display_name TEXT NOT NULL DEFAULT '',
  email        TEXT UNIQUE,
  status       TEXT NOT NULL DEFAULT 'active',-- active | suspended
  created_at   TEXT NOT NULL,
  CONSTRAINT app_user_phone_paired CHECK ((phone_hash IS NULL) = (phone_enc IS NULL))
);

CREATE TABLE credential (                     -- 自建登录
  user_id     TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  type        TEXT NOT NULL,                  -- password | totp
  secret_hash TEXT NOT NULL,
  updated_at  TEXT NOT NULL,
  PRIMARY KEY (user_id, type)
);

CREATE TABLE identity (                       -- 预留 SSO，成本为零
  provider    TEXT NOT NULL,                  -- lark | github | google
  external_id TEXT NOT NULL,
  user_id     TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  linked_at   TEXT NOT NULL,
  PRIMARY KEY (provider, external_id)
);
```

`identity` 现在就留着：产品设计第 2.1 章明确要求**把身份提供方降级成一行数据**，将来接 SSO 只是加一行，不动主键。

**手机号两列可空**，与 `email` 同一形态：P0 的三行 seed 用户没有手机号、也永远登不进来（`V2__seed_owner_and_namespaces.sql` 已写明）。`CHECK` 保证两列要么都在、要么都不在——单写一列的状态没有任何一条代码路径会产生，也就没有任何一条读路径该去猜它。

```sql
CREATE TABLE phone_verification (             -- 短信验证码：注册与重置共用
  id          TEXT PRIMARY KEY,               -- ULID
  phone_hash  TEXT NOT NULL,                  -- 与 app_user.phone_hash 同一算法
  purpose     TEXT NOT NULL,                  -- register | reset
  code_hash   TEXT NOT NULL,                  -- sha256(手机号 + 验证码)
  attempts    INTEGER NOT NULL DEFAULT 0,
  expires_at  TEXT NOT NULL,
  consumed_at TEXT,
  created_at  TEXT NOT NULL
);
CREATE INDEX idx_pv_lookup ON phone_verification(phone_hash, purpose, created_at);

CREATE TABLE captcha (                        -- 图形验证码：两个发短信的端点之前的那道关
  id          TEXT PRIMARY KEY,               -- ULID，也是客户端答回来时带的那个句柄
  answer_hash TEXT NOT NULL,                  -- sha256(大写化后的答案)
  attempts    INTEGER NOT NULL DEFAULT 0,
  expires_at  TEXT NOT NULL,
  consumed_at TEXT,
  created_at  TEXT NOT NULL
);
```

**验证码的哈希不是防线，别指望它。** 四个字符取自 32 个字母表，约一百万个取值，离线枚举是几秒钟的事——真正管用的是那张五分钟的**寿命**、**三次尝试**与**签发限流**，与上面 `phone_verification.code_hash` 那句是同一个道理。这也是为什么这里的哈希只做 `sha256(答案)`：把 id 拌进去不会让它更难枚举，因为 id 就在同一行里。

**它没有指向 `app_user` 的外键**，因为注册时那一行还不存在——这张表按手机号的哈希索引，不按用户。索引只服务一件事：取最近一条未消费的码。**频控的计数不在这张表里**（早先的设计说这个索引兼做计数），因为登录的计数在这里根本没有行；计数统一在下面那张 `auth_throttle`。**码的哈希只挡到它挡得住的程度**：六位数字可枚举，真正管用的是 `expires_at` 与 `attempts`。

```sql
CREATE TABLE auth_throttle (                  -- 频控计数：一处一张表
  scope        TEXT    NOT NULL,              -- 哪条规则：见 PgAuthThrottle.Rule（这里不列清单，它每次加规则都会漂）
  key_hash     TEXT    NOT NULL,              -- 手机号的带密钥哈希，或 IP 的普通 sha256
  window_start TEXT    NOT NULL,              -- RFC3339，窗口起点（时钟按窗口长度截断得到）
  attempts     INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (scope, key_hash, window_start)
);
```

**规则在代码里，不在这张表的行里**：窗口长度与上限是一个整体（数什么、多长、多少），拆进配置就会出现「把短信上限调成一万」的部署——而那不是部署该决定的事。窗口是**固定窗口**，所以跨边界能连发两次（t=59s 与 t=61s），这是已知的不精确；换来的是计数一次往返、原子自增（`INSERT … ON CONFLICT DO UPDATE … RETURNING`），没有读改写竞态。行按 25 小时清扫，表自然有界，不需要定时任务。

**浏览器会话不进业务表**（2026-10-01 改）：原设计里的 `browser_session` 表已由 `V3__account_login.sql` **删除**——会话改由 **Spring Session JDBC** 存进 `spring_session` / `spring_session_attributes`。两张表的 DDL 由我们的迁移建，内容逐字抄自 `spring-session-jdbc` 的 `schema-postgresql.sql`（只做小写化），**不在本文档复述**：迁移脚本是它的权威，抄一份到这里就是第二个会漂的副本。Boot 自带的建表器由 `spring.session.jdbc.initialize-schema: never` 关掉。取舍与被否掉的替代见 [ADR 0014](../../decisions/0014-browser-session-via-spring-session.md)。

```sql
CREATE TABLE oauth_client (
  client_id          TEXT PRIMARY KEY,        -- CIMD 时是一个 HTTPS URL
  name               TEXT NOT NULL,
  registration       TEXT NOT NULL,           -- cimd | dcr | preregistered
  redirect_uris      TEXT NOT NULL,           -- JSON 数组
  grant_types        TEXT NOT NULL,           -- JSON 数组
  client_secret_hash TEXT,                    -- 机密客户端才有
  metadata           TEXT NOT NULL DEFAULT '{}',
  created_at         TEXT NOT NULL
);

CREATE TABLE auth_code (
  code_hash      TEXT PRIMARY KEY,
  client_id      TEXT NOT NULL REFERENCES oauth_client(client_id),
  user_id        TEXT NOT NULL REFERENCES app_user(id),
  redirect_uri   TEXT NOT NULL,
  scope          TEXT NOT NULL,
  code_challenge TEXT NOT NULL,
  method         TEXT NOT NULL,               -- S256
  resource       TEXT,                        -- RFC 8707 audience
  expires_at     TEXT NOT NULL,
  used_at        TEXT
);

CREATE TABLE access_token (
  token_hash TEXT PRIMARY KEY,                -- 只存哈希，永不存明文
  client_id  TEXT NOT NULL,
  user_id    TEXT NOT NULL REFERENCES app_user(id),
  scope      TEXT NOT NULL,
  audience   TEXT NOT NULL,                   -- 必须校验
  expires_at TEXT NOT NULL,
  revoked_at TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX idx_at_expiry ON access_token(expires_at);

CREATE TABLE refresh_token (
  token_hash   TEXT PRIMARY KEY,
  client_id    TEXT NOT NULL,
  user_id      TEXT NOT NULL REFERENCES app_user(id),
  scope        TEXT NOT NULL,
  expires_at   TEXT NOT NULL,
  revoked_at   TEXT,
  rotated_from TEXT,                          -- 轮换链，便于检出重放
  created_at   TEXT NOT NULL
);
```

**三条实现约束**：

1. **令牌只存哈希**（`sha256`）。数据库泄露不等于令牌泄露。
2. **`audience` 必须校验**：只接受签给本服务的令牌。这是 MCP 规范的 MUST，在纯 API 下同样是对的。
3. **refresh token 轮换**：每次刷新签发新的并置 `rotated_from`；旧 token 被再次使用即视为重放，整链撤销。

### 3.2 命名空间与成员

```sql
CREATE TABLE namespace (
  id            TEXT PRIMARY KEY,
  slug          TEXT NOT NULL UNIQUE,         -- URL 里用
  title         TEXT NOT NULL DEFAULT '',
  owner_user_id TEXT NOT NULL REFERENCES app_user(id),
  visibility    TEXT NOT NULL DEFAULT 'private', -- public | unlisted | private
  created_at    TEXT NOT NULL
);

CREATE TABLE namespace_member (
  namespace_id TEXT NOT NULL REFERENCES namespace(id) ON DELETE CASCADE,
  user_id      TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role         TEXT NOT NULL,                 -- owner | editor | viewer（v1 只写 owner）
  added_at     TEXT NOT NULL,
  PRIMARY KEY (namespace_id, user_id)
);
```

> **v1 范围**：`namespace_member` 只写入**所有者那一行**（`role = 'owner'`）；**成员管理与角色判定推迟**——这一版只做「一个自然人登录并管自己的 skill」，不做组织 / 成员 / 角色体系（见 [`prd.md`](prd.md) §用户与场景）。表结构保留，将来开团队命名空间时不需要迁移。

> **一个用户可以拥有多个命名空间**，模型上今天就是如此——[`architecture/model.md`](../../architecture/model.md) 的实体图写的是 `User ──1:N── Namespace`，`namespace.owner_user_id` 上也没有唯一约束。v1 在注册时只建**一个**个人命名空间（`slug = handle`）；**建库能力与「发布落到哪一个命名空间」留给后续版本**。放开它们要动的是 M8 的入口（现在只收一个命名空间）与发布路径的取名方式，**不是 schema**。读路径今天按「slug 必须等于自己的 handle」判断，在只有一个命名空间时与「我拥有的」不可区分。

> **`visibility` 在 v1 的用法**：v1 不做分享与公共发现（见 [`prd.md`](prd.md) §范围 不做 #7），实际只会用到 `private`；`public` / `unlisted` 保留给 v2，**v1 不必为它们写测试**。

**注册时自动为每个用户建一个个人命名空间**（`slug = handle`）。这样 `(namespace_id, name)` 唯一这一个约束**同时覆盖了「个人内不重名」与将来的「团队内不重名」**——正是你提的 `user_id + skill_name` 的意图，且将来升级成团队命名空间不需要迁移。

> **保留 slug**：网关 skill 发布在一个**保留命名空间**里（如 `skillmaster`，见 §4.5），而 `namespace.slug` 有 UNIQUE 约束。所以注册时**必须拒绝**落在保留名单里的 `handle`——否则该用户注册会直接撞 UNIQUE 失败，或更糟：遮蔽网关的发布路径。

### 3.3 Skill 与版本

```sql
CREATE TABLE skill (
  id                 TEXT PRIMARY KEY,        -- ULID，永不变
  namespace_id       TEXT NOT NULL REFERENCES namespace(id) ON DELETE CASCADE,
  name               TEXT NOT NULL,           -- 作者的 name，标准约束
  title              TEXT NOT NULL DEFAULT '',
  description        TEXT NOT NULL,           -- 热路径，冗余自 frontmatter
  frontmatter        TEXT NOT NULL,           -- 原始 frontmatter（JSON，透传未知字段）
  visibility         TEXT NOT NULL DEFAULT 'private',
  current_version_id TEXT,                    -- 指向当前版本
  created_by         TEXT NOT NULL REFERENCES app_user(id),
  created_at         TEXT NOT NULL,
  updated_at         TEXT NOT NULL,
  deleted_at         TEXT,                    -- 软删
  UNIQUE (namespace_id, name)
);
CREATE INDEX idx_skill_ns  ON skill(namespace_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_skill_vis ON skill(visibility)   WHERE deleted_at IS NULL;

CREATE TABLE skill_version (
  id           TEXT PRIMARY KEY,
  skill_id     TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  number       INTEGER NOT NULL,              -- 这个 skill 的第 N 份不同内容；永不重用（ADR 0012）
  digest       TEXT NOT NULL,                 -- 文件集合的确定性摘要
  file_count   INTEGER NOT NULL,
  total_bytes  INTEGER NOT NULL,
  changelog    TEXT NOT NULL DEFAULT '',
  source       TEXT NOT NULL DEFAULT '',      -- 来源描述（上传/zip/git url）
  published_by TEXT NOT NULL REFERENCES app_user(id),
  published_at TEXT NOT NULL,
  UNIQUE (skill_id, digest),                  -- 幂等发布：同样内容不产生新版本
  UNIQUE (skill_id, number)                   -- 序号是不可变别名，不是身份
);
CREATE INDEX idx_ver_skill ON skill_version(skill_id, published_at DESC);

CREATE TABLE version_file (
  version_id  TEXT NOT NULL REFERENCES skill_version(id) ON DELETE CASCADE,
  relpath     TEXT NOT NULL,                  -- 相对 skill 根，POSIX 分隔符，字典序
  blob_sha256 TEXT NOT NULL REFERENCES blob(sha256),
  size        INTEGER NOT NULL,
  is_binary   INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (version_id, relpath)
);

CREATE TABLE blob (                           -- 只有元数据行，字节不在这里
  sha256      TEXT PRIMARY KEY,
  size        INTEGER NOT NULL,
  created_at  TEXT NOT NULL
);

-- 字节单独一张表，并放在单独的表空间（ADR 0010 决定 2）：这样它与元数据可以分开备份
-- （元数据小可频繁备，字节大走低频备），blob 的页也不会把元数据的页挤出 shared_buffers。
-- 主键就是 sha256 —— 「字节存在哪」由 BlobStore.get/put(sha256) 那层接口挡住，表结构不是接缝。
--
-- 这里**没有** TABLESPACE 子句，与本文早先的写法不同：表空间必须先存在才能被引用，而
-- CREATE TABLESPACE 需要超级用户，所以它不能出现在迁移脚本里。搬表是部署步骤：
--   ALTER TABLE blob_content SET TABLESPACE blob_ts;
-- 未验证：阿里云 RDS 是否允许用户表空间（见第 8 章）。
CREATE TABLE blob_content (
  sha256  TEXT PRIMARY KEY REFERENCES blob(sha256) ON DELETE CASCADE,
  bytes   BYTEA NOT NULL
);
```

**五个关键设计点**：

1. **`id` 是身份，`name` 是属性。** 改名只 `UPDATE skill.name`，不影响任何引用、任何已发出的 URL、任何审计记录。这就是选不透明主键而非 `(owner, name)` 的理由。
2. **`UNIQUE(skill_id, digest)` 直接实现幂等发布**——同样内容重复发布不产生新版本。这也是"内容不变 → digest 不变"这条不变量的落点。实现上必须是 `ON CONFLICT DO NOTHING`，**不能**靠捕获唯一约束异常：PostgreSQL 下一个报错会中止整个事务，捕获它等于毒化这次发布。
   **`number` 是这一条的配套，不是它的替代**（[ADR 0012](../../decisions/0012-addressing-and-version-pinning.md)）：序号是「这个 skill 的第 N 份**不同**内容」，所以**重发相同内容不消耗号码**——否则同一个 digest 会拿到两个号，幂等就死了。`UNIQUE(skill_id, number)` 让号码永不重用，于是 `@3` 永远指向同一份内容，它等价于 git 的 tag 而不是分支。分配不需要新的并发机制：发布时 `upsertLive` 已经对 `skill` 行做过 `DO UPDATE`、持有那行的锁，同一个事务里取 `max(number)+1` 天然串行。
3. **manifest 就是 `version_file` 的投影**，按 `relpath` 字典序排序即规范要求的确定性顺序。**顺序必须显式排序，不能依赖数据库返回顺序。** 而且「按 relpath 排序」有歧义——Java 的 `String.compareTo`（UTF-16 码元）、PostgreSQL 默认 collation、`COLLATE "C"`（UTF-8 字节）是三种不同顺序，在普通 ASCII 标点上就会分叉。**digest 与 HTTP 清单必须用同一个顺序**，所以排序在 Java 里用一个显式比较器算一次，两边共用，查询不负责排序。
4. **`frontmatter` 存原始 JSON 并透传未知字段。** 飞书的 `metadata.requires.bins` 是嵌套的，现有 `parse_frontmatter` 会丢——必须换更完整的 YAML 解析，且**解析失败不得静默降级**。
5. **`blob` 只增，删除版本不立刻删 blob**（可能被其他版本引用），靠 GC 比对引用计数——而且这次回收**必须与版本变更在同一个事务里完成**，否则会留下「字节还在、引用没了」或反过来的窗口（[ADR 0010](../../decisions/0010-storage-in-postgres.md)）。内容寻址天然去重——不同 skill 共享同一份 `references/` 时只存一份。
   **P0 的实际答案是「版本变更时清扫、不延迟、不归档」，且 P0 一次都删不掉东西**：软删只置 `deleted_at`，`skill_version`/`version_file` 行都还在，所以没有 blob 失去引用。接缝存在是为了 P2 的版本裁剪有落点。

**`relpath` 的取值规范**（P0 定的最小集，上传校验按此执行）：POSIX 分隔符 `/`；不以 `/` 开头、不含 `..` 段、不含反斜杠、不含空段；大小写敏感；允许非 ASCII。这些由 M5 在解压时逐项强制，**符号链接项一律拒绝**——一个 zip 把符号链接存成普通条目加一个 unix mode，跟随它就会发布作者没上传过的字节。


**digest 算法（必须确定性）**：

```
digest = sha256( concat( for f in files_sorted_by_relpath:
                          f.relpath + "\0" + f.blob_sha256 + "\0" ) )
```

不依赖文件系统遍历顺序，不依赖时间戳。**同一份内容永远得到同一个 digest**——这是更新检测与幂等发布的前提。

### 3.4 检索与统计

**索引范围**：索引建在 `skill` 上，**只覆盖 L1 三个字段**。**所有权过滤在查询时做**——检索必须 JOIN `namespace`，把结果限定在**调用者有权访问的命名空间**内，绝不能把索引的匹配结果直接返回。v1 每个用户只有个人命名空间，所以实际等于「只搜自己」；写漏这个谓词就会泄露别人的 `private` skill。

**索引的物理形态待定。** 原计划的 SQLite `FTS5 + trigram` 已实测**不可行**（理由与证据见 [ADR 0010](../../decisions/0010-storage-in-postgres.md)，待验事项见第 8 章开放问题 3），方向转为 PostgreSQL 侧的 `pg_bigm`；排序的文本相关性算法也随之一并待定。所以下表**只保留与索引形态无关的部分**：

```sql
CREATE TABLE skill_stat (
  skill_id       TEXT PRIMARY KEY REFERENCES skill(id) ON DELETE CASCADE,
  search_hits    INTEGER NOT NULL DEFAULT 0,  -- 出现在搜索结果里
  detail_views   INTEGER NOT NULL DEFAULT 0,  -- 被看了详情
  body_reads     INTEGER NOT NULL DEFAULT 0,  -- 正文被读
  file_reads     INTEGER NOT NULL DEFAULT 0,
  last_access_at TEXT
);
```

**只索引 L1**（标准的 `name` + `description`，外加平台自己的 `title` 元数据字段），**绝不索引正文或文件**。这既是性能考虑，也是安全约束：正文一旦进全文索引，就可能通过片段检索被反推出来。

**排序（P0 已定，必须可解释）**：结果按一个**元组**比较，而不是按一个加权总分：

```
ORDER BY relevance DESC, updated_at DESC, id ASC

relevance = 100·命中 name + 40·命中 title + 20·命中 description
```

三个权重是配置（`skillmaster.search.weights`，实现时定），可调而不必发版；`RelevanceWeights` 在启动时拒绝「命中 description 比命中 name 还重」这类配置。命中按字段相加——同时命中三个字段的排在只命中 name 的前面，这是有意的读法：每个字段是独立证据。

**为什么不是加权总分。** 早先的写法是 `w1·relevance + w2·freshness + w3·manual`，那样分数里会含 `now()`。含当前时间的分数在两次求值之间会变，于是**游标携带的 keyset 就不再是它签发时的那个值**——翻页会静默地跳过或重复行，而响应里没有任何东西表明这件事发生了。元组没有这个问题：每个分量要么是存储的列，要么是存储列的函数。新鲜度因此降级成**同一 tier 内的破平局项**，不需要时钟参与比较，表达的意图反而更直接。

**`text_relevance` 的具体算法仍随索引形态待定**——原计划写死的「FTS5 bm25」在中文上拿不到。P0 的实现是 `LIKE '%词%'`（`pg_bigm` 是 GIN 索引、加速同样的 `LIKE`，但不给相关性分数，所以它是纯索引优化、后置）。**`LIKE` 解决的是延迟不是排序**，所以上面这套排序是独立于索引形态的、P0 必须自己定死才有的可回归。`manual_weight` 与 `skill_stat` 仍留给 P1/P2——P0 没有使用数据。
**排序质量在这个模型下就是产品的核心**——目录不常驻，「这个任务正好有个 skill 能用」完全靠它。

### 3.5 审计

```sql
CREATE TABLE audit_event (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  actor_user_id TEXT,
  action        TEXT NOT NULL,                -- publish|delete|rollback|token_issue|...
  target_type   TEXT NOT NULL,
  target_id     TEXT,
  detail        TEXT NOT NULL DEFAULT '{}',
  at            TEXT NOT NULL
);
CREATE INDEX idx_audit_at ON audit_event(at DESC);
```

skill 的内容会被客户端取走、在客户端环境里使用，事后追溯是底线。**发布、删除、回滚、令牌签发与撤销一律留痕。**

---

## 第 4 章 · 接口定义

### 4.1 通用约定

- **三个面，靠前缀区分**（2026-10-01 定）：一条路径本身就该说明它要什么凭据，所以按**调用者**分三组：

  | 面 | 前缀 | 谁在敲 | 凭据 |
  |---|---|---|---|
  | 对外 API | `https://<host>/api/v1` | CLI / agent / 第三方客户端 | `Authorization: Bearer`（M2 签发） |
  | 对外网页 | `https://<host>/web` | 浏览器 | 会话 cookie + CSRF |
  | 服务内部 | `https://<host>/inner` | 运维 / 负载均衡 / 本机 | 网络层——**不暴露到公网** |

  `web` 与 `inner` **不带版本号**：HTML 页面与运维端点不是要被外部按版本兼容的 API。

  **前缀不是安全边界**，`/inner` 尤其如此——它得靠**绑定到另一个端口**（反代不转发那个端口）才成立，否则只是一个装饰性的路径段。今天的事实是反过来的：actuator 挂在**主端口**上，`SecurityConfig` 显式放行 `/actuator/health` 以便负载均衡探活。把这一条落实是 P1 的工作项，见 §7。
- **不由前缀区分的路径**（**封闭清单**，加第四条要有理由，理由写在下面这一列）：

  | 路径 | 为什么加不上前缀 |
  |---|---|
  | `/.well-known/**` | RFC 8414 / RFC 9728 与 Skills 约定**按标准规定的路径**来取，加了前缀就再也发现不到 |
  | `/oauth/*` | AS 的端点由 `/.well-known/oauth-authorization-server` 广告，客户端从那份文档里读；它自带一套客户端认证模型，塞进 `/api/` 会让人以为它要 Bearer |
  | `/gateway/**` | 网关正文，CLI 在**登录之前**取它。它与第一条同属「发现与引导」通道（§4.5），`SecurityConfig` 今天就把两者写进同一条 `permitAll` |

  **ADR 正文里的 `/api/v1/skills/…` 不改**（ADR 不可变）：那只说明它写下的当时是那个前缀，寻址这个决定本身没有被推翻。
- **skill 的地址是 `namespace/name`，不是 `id`**（[ADR 0012](../../decisions/0012-addressing-and-version-pinning.md)）。`id` 仍是主键与内部身份，只是不出现在 URL 里。v1 里每个用户只有一个个人命名空间（模型允许更多，见 §3.2），所以第一段读作自己的 handle。
- **版本用 `@` 后缀**，三种写法，省略即 `latest`：

  | 写法 | 含义 |
  |---|---|
  | `demo/feishu-tasks` | `latest`——每次请求重新解析，会漂 |
  | `demo/feishu-tasks@3` | 钉在第 3 版。序号是不可变别名，`@3` 永远指向同一份内容 |
  | `demo/feishu-tasks@sha256:…` | 钉在内容上。搜索卡片与详情都带 `digest`，所以这个形式不需要额外请求 |

  **凡是这个地址解析不出东西，一律 `404` 加同一个错误码 `skill_not_found`**：没有这个 skill、不是你的、skill 已软删、版本号或 digest 不存在——四种情况一个答案。这是 §4.1 下面那条原则的延伸：**地址不解析就没有更多可说的**，多一个码就多一处可以用来探测的信息。
  **实现上「skill 活着」和「版本存在」是两个条件，都要查。** 不能写成「版本行还在就发」——软删之后 `skill_version`/`version_file` 行**都还在**（§3.3 点 5），写漏了就变成「删了还能读到」。
- **认证**：API 面上每个请求带 `Authorization: Bearer <access_token>`。**令牌绝不放在 query string**（规范要求，也防日志泄露）。
- **未认证/令牌无效** → `401` + `WWW-Authenticate: Bearer resource_metadata="https://<host>/.well-known/oauth-protected-resource"`
- **scope 不足** → `403` + `WWW-Authenticate: Bearer error="insufficient_scope", scope="...", resource_metadata="..."`
- **浏览器面（`/web/*`）不照上面两条发的挑战来**（2026-10-01 定，随 M01 实现）：它的 401 只有信封、**不带 `WWW-Authenticate`**——收到 `Bearer` 挑战的浏览器会被支去取一个它根本不需要的令牌。它的 403 是 `forbidden`，唯一的来源是 CSRF 校验失败（写接口加 CSRF、双提交 cookie：`XSRF-TOKEN` 可被脚本读、会话 cookie HttpOnly，这个不对称就是设计），所以消息里直接写明怎么恢复。
  **两个面各有自己的链**（`SecurityConfig` 里两条 `SecurityFilterChain`，各自显式 `@Order` 且按前缀 `securityMatcher`）：令牌不能鉴权 `/web/**`，会话 cookie 不能鉴权 `/api/v1/**`，两边都有集成测试钉着。漏掉 `@Order` 的后果是静默的——Spring 可以任选顺序，于是每个登录都被 bearer 链答成 401。
  **客户端拿第一个 CSRF 令牌的方式是先读一次 `GET /web/session`**：响应无论成败都会带上 `XSRF-TOKEN` cookie，而这也是任何客户端本来就会发的第一个请求。
- **分页**：`?limit=&cursor=`，响应带 `next_cursor`（不透明游标，不用 offset）
- **时间**：RFC3339 UTC

**错误信封**（P0 新增；上文只定了 401/403 的**响应头**，正文形状此前是空的）：

```json
{"error": {"code": "invalid_upload", "message": "…",
           "details": [{"field": "files[3].relpath", "issue": "path_traversal"}]}}
```

| `code` | 何时 |
|---|---|
| `unauthenticated` | 无令牌或令牌无效（401） |
| `invalid_credentials` | 登录没通过（401）。三种失败——没这个手机号、密码错、账号 `suspended`——**逐字节同一个响应**，否则这个端点就是账号存在性的预言机 |
| `insufficient_scope` | 令牌有效但 scope 不足（403） |
| `forbidden` | 请求本身被拒，与调用者是谁无关（403）——浏览器面上只有一种：CSRF 令牌缺失或过期 |
| `too_many_requests` | 花光了窗口内的配额（429），响应带 `Retry-After`。没有这个头，客户端唯一能做的事就是重试，而那正是限额要拦的 |
| `invalid_request` | 参数或状态不合法（400）：`limit<1`、游标读不懂、发布到已软删的名字、**账号请求里某个字段被拒**（配 `details[{field, issue}]`：`username/already_taken`、`password/too_weak` 一类） |
| `invalid_upload` | 上传被 M5 拒绝（400），或请求体超过 multipart 上限（413） |
| `verification_code_invalid` | 短信验证码错、过期、用过、或猜太多次（400）——四种一个码，而且**失败原因只进日志不进正文**：告诉调用者错在哪，就是告诉攻击者该继续试哪一样 |
| `skill_not_found` | 不存在**或**无权（404）——**同一个码、同一个状态** |
| `file_not_found` | 清单里没有这个 `relpath`（404） |
| `internal_error` | 服务端异常（500） |

**「不存在」与「无权限」必须同码同状态**：403 会确认 skill 存在，而 404 不会。实现上这不做成两个分支——所有权谓词写在找到该行的同一条 SQL 里，于是「不是你的」和「没有这个」结构上就是同一个空结果，没有可以写漏的第二个判断。

`details` 只在字段级错误上出现，非空时形如 `[{field, issue}]`，一次给全而不是只给第一个——作者修一个 skill 不必来回试。

**为什么连纯 API 也照 MCP 的错误形状来**：将来加 MCP 适配器时不用改错误语义，客户端也不会遇到"两套 401"。

### 4.2 四个读接口

#### `GET /api/v1/skills` —— 搜索（只返回 L1）

**结果范围**：只返回**调用者有权访问的命名空间**里的 skill。v1 每个用户只有自己的个人命名空间，所以实际就是「只能搜到自己的」——**不做跨用户发现**（见 [`prd.md`](prd.md) §用户与场景）。`namespace` 参数是在这个范围内再收窄，不是绕过它的开关。

| 参数 | 说明 |
|---|---|
| `q` | 关键词；空则按 `sort` 返回 |
| `namespace` | 限定命名空间 slug |
| `sort` | `relevance`（默认）\| `recent` |
| `limit` | 默认 20，上限 100 |
| `cursor` | 分页游标 |

```json
{
  "skills": [
    { "id": "01J...", "namespace": "alice", "name": "pdf-tools", "title": "PDF 工具",
      "description": "…", "visibility": "private",
      "version": { "number": 3, "digest": "sha256:…" },
      "updated_at": "2026-09-26T10:00:00Z" }
  ],
  "next_cursor": null
}
```

**只返回 L1 字段**：不含正文、不含文件清单、不含文件内容。这是渐进加载的第一道闸。

**卡片带 `id` 是为了让客户端能钉版，不是为了寻址**——`id` 不出现在 URL 里。`version.digest` 让 agent **只调用一次搜索**就能钉到内容级（`@sha256:…`），不必先取 latest 再钉。

#### `GET /api/v1/skills/{namespace}/{name}[@version]` —— 详情 + 文件清单（零内容）

```json
{
  "id": "01J...", "namespace": { "slug": "alice", "title": "个人" },
  "name": "pdf-tools", "title": "PDF 工具", "description": "…",
  "visibility": "private",
  "frontmatter": { "name": "pdf-tools", "description": "…", "metadata": { "…": "原样透传" } },
  "version": {
    "number": 3, "digest": "sha256:…", "published_at": "2026-09-26T10:00:00Z",
    "file_count": 25, "total_bytes": 364869,
    "is_latest": true
  },
  "files": [
    { "relpath": "SKILL.md",
      "uri": "/api/v1/skills/alice/pdf-tools@3/files/SKILL.md",
      "sha256": "sha256:…", "size": 15251, "is_binary": false },
    { "relpath": "references/checklist.md",
      "uri": "/api/v1/skills/alice/pdf-tools@3/files/references/checklist.md",
      "sha256": "sha256:…", "size": 4096, "is_binary": false }
  ],
  "resources": { "body": "/api/v1/skills/alice/pdf-tools@3/body" }
}
```

**这是整个设计的枢纽**：给出**完整文件清单但零内容**——正是 MCP 扩展里 `skills/list` 的语义（"清单让客户端无需下载即可枚举 L3"）。它让 agent 能**在不取任何内容的前提下**判断"这个 skill 里有没有我要的东西"。

**请求里没写版本时，响应把它解析出来并钉住**（`version.number`），**每个文件带一条已经钉好版本的 `uri`**。于是钉版不需要任何机制：

> **从入口进一次，之后只跟着清单给的 URL 走。**

agent 照抄 `uri`，而那些 `uri` 里已经写着 `@3`。中间谁发布了 `@4` 都不影响它——不是服务端记住了什么，而是它**根本没再问过 latest**。反过来，**「每次调用都从入口进」就是每次重新解析，任务中途会漂**，漂的结果是清单与字节对不上且不报错（[ADR 0012](../../decisions/0012-addressing-and-version-pinning.md) §背景 记的就是这个漏洞）。

**每项带 `uri` 是外部硬要求**，不是我们的选择：§1.5 记的 MCP 扩展明确要求清单「每项带 URI、SHA-256 digest、字节大小」。此前只有 `relpath` 加一个 `{relpath}` 模板，两个都能拼出同一个 URL——**留一个，不留两个**，否则就是第二个真相来源。

**服务端不记忆任何东西。**「在这一次任务里版本钉死」是客户端携带数据的结果，不是会话——服务端不知道什么是「一次任务」（[ADR 0012](../../decisions/0012-addressing-and-version-pinning.md) §理由）。

**可见性过滤在服务端算**：`private`/`unlisted` 的 skill，无权者得到 `404`（不是 `403`——不泄露存在性）。

#### `GET /api/v1/skills/{namespace}/{name}[@version]/body` —— L2

返回**原始 SKILL.md 字节**（含 frontmatter），`Content-Type: text/markdown`。不做任何改写——托管要保真。

#### `GET /api/v1/skills/{namespace}/{name}[@version]/files/{relpath}` —— L3

返回**单个文件的原始字节**，`Content-Type` 按扩展名（未知则 `application/octet-stream`）。

**必须实现的约束：`relpath` 必须精确匹配所请求版本 manifest 里的某一项，否则 `404`。** 绝不能用 `relpath` 直接拼路径去读磁盘——那是路径穿越漏洞。这一条要写成测试。

**L2/L3 的 `@version` 由清单给出，不由客户端自己拼**——见上。钉住之后 `version_file` 不可变，所以清单里的 `sha256` 与取回的字节永远一致，客户端可以逐字节校验。

### 4.3 写接口（管理）

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/skills` | 发布新 skill（上传目录树 / zip / git 源） |
| `POST` | `/api/v1/skills/{namespace}/{name}/versions` | 发布新版本 |
| `PATCH` | `/api/v1/skills/{namespace}/{name}` | 改元数据（title / description / visibility） |
| `DELETE` | `/api/v1/skills/{namespace}/{name}` | 软删 |
| `POST` | `/api/v1/skills/{namespace}/{name}/restore` | 恢复 |
| `GET` | `/api/v1/skills/{namespace}/{name}/versions` | 版本历史 |
| `POST` | `/api/v1/skills/{namespace}/{name}/rollback` | 回滚（指针前移，不删版本） |

写接口一律**不接受版本后缀**：它们作用在 skill 这个实体上，版本由操作本身产生或移动。唯一例外是回滚，它需要一个目标——那个目标写在请求体里（用序号或 digest），不写进路径。

**元数据变更不产生新版本**（`title`/`description`/`visibility` 不在 skill 文件内），但会触发搜索索引更新。

#### `POST /api/v1/skills` 的 P0 契约

上表只有方法、路径和一句用途——P0 实现它时必须把契约补全，以下是补齐的结果。**P0 只实现这一种形态**（zip 上传）；`git 源` 与 `目录树` 推迟。

请求：`multipart/form-data`，part `file` 是 zip。**没有 `namespace` 参数**——这是一条否定决定：目标命名空间由令牌 subject 的个人空间推出，否则发布就变成一个跨命名空间写入的原语。同理**没有 `visibility` 参数**——§4.2 给元数据单独的端点，在发布上接受它会让人在没注意到的字段上把 private 变成 public。

响应：`201` 首次发布，`200` 且 `created:false` 表示同样内容已存在。**version 不变、`published_at` 不变、当前版本指针不动**——指针前移等于偷偷实现了 P2 的回滚。

```json
{ "id": "01J…", "namespace": "alice", "name": "pdf-tools", "created": true,
  "version": { "number": 3, "digest": "sha256:…", "file_count": 25, "total_bytes": 364869,
               "published_at": "2026-09-26T10:00:00Z" } }
```

**上传被拒时是 `400 invalid_upload`**，可能的原因：不是 zip、含符号链接项、绝对路径或 `..` 段、超过 512 文件、解压后超过 16 MiB、根目录名与 frontmatter 的 `name` 不符、缺 `SKILL.md`、frontmatter 缺 `name`/`description` 或解析失败。**每一项都不得静默降级**——§3.3 点 4。
**请求体超过 multipart 上限时是 `413`**。那个上限**不是 skill 上限**：它是「读进内存之前的保护」，特意设在合法 skill 的最大可能体积之上，好让超限的 skill 由校验器带着理由拒绝，而不是被容器用一个裸 413 拒掉。

**发布到已软删的名字 → `400 invalid_request`**，不是静默复活：§4.3 的 `restore` 就是为这件事存在的，让 publish 兼任它会让删除变成建议。

### 4.4 鉴权接口（自建 AS）

下表跨两个面（§4.1）：`/web/*` 是**浏览器面**，凭据是会话 cookie；`/oauth/*` 与 `/.well-known/*` 是**协议面**，路径不由我们定，客户端从元数据文档里读。

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/web/session` | 当前会话是谁（`{user_id, username, namespace}`）；也是客户端取第一个 CSRF 令牌的请求 |
| `GET` | `/web/captcha` | 取一张图形验证码 → `{captcha_id, image}`（base64 PNG）。**注册的第一个发码请求免这一关**——每个地址每个窗口一次（[ADR 0020](../../decisions/0020-first-code-send-without-a-captcha.md)）；其余发码请求都要过。签发本身按 IP 限流 |
| `GET` | `/web/register/code/captcha-required` | 问一句「这个地址下一次注册发码要不要图形验证码」→ `{required}`。**只读**：不认领也不消耗那次免费额度（理由见 [ADR 0020](../../decisions/0020-first-code-send-without-a-captcha.md)）|
| `GET` | `/web/username/availability?username=` | 问一句候选用户名还能不能用 → `{available, issue}`。**200 是「问题得到了回答」，不是「通过了」**：`issue` 用的就是注册会拒它的那个错误码。匿名、按 IP 限流 |
| `POST` | `/web/logout` | 登出（幂等；**受 CSRF 保护**，否则任何页面都能把人登出） |
| `POST` | `/web/register/code` | 发注册验证码：手机号 + `captcha_id` + `captcha_answer`。**该地址本窗口的第一次发送不需要图形验证码，这两个字段传 `null` 即可**；其余情况必须带，缺了答 `{field: captcha, issue: required}`（400）——那不是调用方做错了什么，而是告诉它该把图形验证码摆出来。**号码合法、该过的关都过了且没被限流就一律 204，即使该号已注册**——否则它就是账号存在性预言机 |
| `POST` | `/web/register` | 注册：手机号 + 验证码 + 密码 + 用户名。成功 201 并**直接建立会话** |
| `POST` | `/web/login` | 登录：手机号 + 密码（**无验证码、不发短信**）。成功 200 并建立会话。**失败一律 401 `invalid_credentials`，不区分「号没注册 / 密码错 / 已被停用」**——区分它就是一个账号存在性预言机 |
| `POST` | `/web/reset/code` | 发重置验证码：手机号 + 图形验证码，同上。**免费那一次只给注册**（ADR 0020）：找回密码能拿走一个账号，所以它每次都过图形验证码 |
| `POST` | `/web/reset` | 重置密码：手机号 + 验证码 + 新密码。成功 204，**不自动登录**，并撤销该账号全部会话 |
| `GET` | `/oauth/authorize` | 授权端点（PKCE 必需） |
| `POST` | `/oauth/token` | `authorization_code` / `refresh_token` / `client_credentials` |
| `POST` | `/oauth/register` | DCR（**v1 不启用**，见下） |
| `GET` | `/.well-known/oauth-authorization-server` | RFC 8414 |
| `GET` | `/.well-known/openid-configuration` | OIDC discovery（可选） |
| `GET` | `/.well-known/oauth-protected-resource` | RFC 9728 |
| `GET` | `/.well-known/jwks.json` | 若用 JWT 签名 |

> **页面的地址不在这张表里。** 登录、注册、找回密码三个页面由 `skillmaster-web/` 提供，路径是 `/login`、`/register`、`/reset`——**服务端没有、也不会有 `/web/login`**（这里原先写的就是它，是错的：`WebAccountController` 只映射 `/web` 下的 API 路由）。`/web/*` 永远只是 API，页面是 SPA 自己的路径。

**注册方式按分期来**（[ADR 0011](../../decisions/0011-server-and-cli-stack.md)）：**v1 只做预注册**——v1 的客户端只有我们自己的 CLI，这是一方客户端，`client_id` 随 CLI 发布即可。**CIMD**（识别 URL 形式的 `client_id`，去那个 URL 取元数据）要等 **P2 的 MCP 适配器**接入第三方客户端时才需要；**DCR**（`/oauth/register`）只作兼容，**v1 不启用**。

> **一条实现约束**：客户端查找从 v1 就走接口（Spring Security 的 `RegisteredClientRepository`），**不要硬编码成「反正只有一个客户端」**——那样 P2 加 CIMD 就变成重构授权流程，而不是新增一个实现。

> **实施状态**（2026-10-01）：上表原先只有 `/login` 与 `/logout`，而 [`prd.md`](prd.md) §验收与指标 第 1 条要求走通「注册 → 登录 → 拿到令牌」。注册与重置两组端点早先已补进上表（**`POST /web/login` 之前一直漏在表外，这一轮补上**）；**十个 `/web/*` 端点已全部实现**（2026-10-02 新增的两个是 `GET /web/register/code/captcha-required` 与 `GET /web/username/availability`），字段、不变量与失败形态见 [`architecture/modules/M01-account-login.md`](../../architecture/modules/M01-account-login.md)。
> **人走的三个页面已由 `skillmaster-web/` 提供**（[迭代 0004](iterations/0004-web-frontend.md)、[ADR 0015](../../decisions/0015-web-frontend-stack.md)）：它是这些端点的客户端，没有新增契约。**注册这条已经在浏览器里对着真服务端走通过**（2026-10-02，[迭代 0011](iterations/0011-register-flow-and-sms-state.md)），在此之前它只被类型检查、测试与构建验证过——两半对线路格式的理解是否一致，有一段没有自动化证据的历史，见 [`test-plan.md`](test-plan.md) §已知问题。
> **有一个生产前必须关掉的口子**：`skillmaster.sms.accept-any-code`（默认 `false`；要用就在 `.env` 里打开，`.env.example` 里以注释形式给出）**不比对验证码，任意六位数字都通过**。它存在是因为短信签名与模板还没过审、真码发不出来，而整条流程要先能走通（[迭代 0011](iterations/0011-register-flow-and-sms-state.md)、[`test-plan.md`](test-plan.md) §未验证）。**它只摘掉比对这一步**：码仍要先请求、5 分钟过期、只能用一次。配了 AccessKey 又开着它会**启动失败**。
> **仍未实现的是全部 `/oauth/*` 与 `/.well-known/*`**（属 M2）。所以验收第 1 条的后半句「拿到令牌」**仍未成立**，这一点如实记在 [`test-plan.md`](test-plan.md) §结果；M01 与 M2 的耦合是单向的 M2→M1，且走的是框架契约（SecurityContext 的 `getName()` 返回 userId），不是 M1 的自定义 API——见 [ADR 0014](../../decisions/0014-browser-session-via-spring-session.md)。

### 4.5 网关 skill 的分发接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/.well-known/agent-skills/index.json` | V2，**只含网关 skill 一个 entry** |
| `GET` | `/.well-known/skills/index.json` | V1，同上（旧版 CLI 用） |
| `GET` | `/.well-known/<base>/skillmaster/<relpath>` | V1 的逐文件拉取；`<base>` 见下 |
| `GET` | `/gateway/SKILL.md` | 网关 skill 正文（CLI `setup` 用） |

**四条路径全部匿名可达**，这是设计不是疏漏：§1.5 实测这条通道既没有认证、也没有任何门控概念，而它正是「一台还没登录的机器怎么知道去哪登录」的答案——要求令牌会让它恰好对唯一需要它的客户端不可达。安全配置里这四条是显式放行的，其余仍然默认拒绝。

**逐文件拉取的 `<base>` 有三个别名**：`gateway`、`skills`、`agent-skills`，同一份字节都发。原因是 §1.5 记的解析顺序（「先按路径相对、再按根」）与本文把该路由放在 `gateway/` 之下可能对不上，在 P0b 的 CLI 实测判定之前不猜是哪一个。**别名是一个封闭集合**：别的 `<base>` 一律 404，不然 `/.well-known/` 下任何路径都会变成入口。

**V2 索引里的 `digest` 是「内容 digest」，不是版本 digest。** 两者哈希的东西不同：ADR 0005 的版本 digest 哈希的是 `relpath + NUL + blob_sha256 + NUL` 拼成的清单（不必读内容就能算），而客户端期望的这个哈希的是 `path + NUL + 文件字节 + NUL`（§1.5，自 CLI 的 `computeWellKnownSkillDigest` 反查而来）。在 V2 里声明前者，客户端会拿自己的内容哈希去比一个清单哈希，每次都不等，于是**永远认为网关有更新**——ADR 0005 自己警告过的形态。**两个都实现**，各配冻结向量测试。

**未发布时必须返回真 404**（见 1.5 的坑）：一个不发布这套约定但用 SPA 兜底的主机，首选路径会返回 **200 + HTML**，客户端无法把它和真索引区分开。从 classpath 提供一份模板会复现同一句谎话——所以这四条路径读的是**已发布的那个 skill**，不是仓库里的源文件。
**网关 skill 发布在一个保留命名空间里**（`skillmaster`），它本身也是一个普通 skill，有版本、有 digest。

**索引路径是唯一不需要认证的读取面**：V2 索引会广告一个绝对 URL（取自 `skillmaster.public-base-url`），因为从这个索引安装的客户端不一定正在跟发它的那台主机说话。

### 4.6 CLI 命令

| 命令 | 作用 |
|---|---|
| `skillmaster login` | loopback PKCE 登录，令牌存 keychain |
| `skillmaster login --client-credentials` | 无人值守（CI），用 Client Credentials |
| `skillmaster logout` | 撤销并清除本地令牌 |
| `skillmaster setup` | 检测本机 agent → 装网关 skill → 软链 |
| `skillmaster search <q>` | 调 `/api/v1/skills` |
| `skillmaster show <namespace/name>[@版本]` | 调详情；`@版本` 可省 |
| `skillmaster get <namespace/name>[@版本] [relpath]` | 取正文或单个文件，落到临时目录 |
| `skillmaster publish <path>` | 管理端：发布 |

**三个设计点**：

- **agent 用 CLI 而不是裸 curl**：令牌由 CLI 从 keychain 读，**永不进模型上下文、不进命令记录**。裸 curl 会让令牌出现在 Bash 命令里。
- **`get` 落到临时目录是允许的**——那是 agent 完成任务的中间产物，与"把 skill 下载到本地"是两回事。前者用完即弃，后者是一份可长期阅读的副本。
- **`@版本` 要显式写进后续命令。**`show` 返回的 `uri` 里已经带着 `@3`，但 `get` 是**另一条命令**——如果不把 `@3` 带上，它就等于「从入口再进一次」，会重新解析 latest（[ADR 0012](../../decisions/0012-addressing-and-version-pinning.md) §后果）。所以 CLI 接受显式钉版，agent 把第一次结果里的 `@3` 抄进后面的命令。**另一种做法是 CLI 在本地记「本任务钉在哪」**（像 lockfile），但「任务」的边界在 CLI 里同样不清楚，而且要多维护一份状态。

---

## 第 5 章 · 网关 skill

### 5.1 它是什么

一个普通 skill，但有特殊职责：**它是本地唯一常驻的东西，也是协议本身。**

**源文件在 [`gateway/skillmaster/SKILL.md`](../../../gateway/skillmaster/SKILL.md)，那份文件是权威**——本文不抄它的正文（§2.3 的铁律：一个事实只有一个权威位置；抄一份就一定会漂移）。这里只记设计层面必须成立的三件事：

| 字段 | 必须是什么 |
|---|---|
| `name` | `skillmaster`。标准的 MUST 要求它等于父目录名，所以源文件落在 `gateway/skillmaster/`（§2.4） |
| `description` | 写得**足够广**——目录不常驻，「该不该去查 skill 库」全靠这一句。见 §5.2 第 1 条 |
| `metadata.platform_api_version` | 协议版本号；CLI `setup` 据此判断本地网关是否过期。见 §5.2 第 3 条 |

#### 源文件里的 `https://<host>` 占位符，在发布时替换一次

源文件里写的是字面量 `https://<host>`，因为那份文件提交在一个**公开仓库**里、还要装到**还没跟任何服务器说过话**的机器上，它没法写死一个地址。服务端在**发布时**把它换成 `skillmaster.public-base-url`。

**不能改成每次请求时替换。** 那会让发出去的字节与索引里声明的 digest 对不上——§4.2 的「托管要保真」和 ADR 0005 的 digest 都要求服务端返回的字节就是被哈希过的字节。按请求改写等于让每次读都返回一个「新版本」。

**替换必须确定性**：无时间戳、无随机，同一份配置跑两次得到同样的字节。这不是洁癖——服务端每次启动都重发一次网关，确定性才让这次重发是**幂等空操作**（`UNIQUE(skill_id, digest)`）。否则每次重启都会产生新版本，于是每个装过网关的客户端都以为自己过期了。
源文件保持是模板；**已发布的版本是渲染后的**。

### 5.2 三条设计约束

1. **`description` 决定了整个发现体验。** 目录不常驻，所以"该不该去查 skill 库"全靠这句。它必须写得**广**（覆盖多类任务），而不是窄（只描述某一个场景）。这是全项目最需要打磨的一段文字。

2. **正文必须短且强指令。** 它每次被触发都会进上下文并**在整个会话常驻**。冗长的协议说明会持续占用预算。[那份正文](../../../gateway/skillmaster/SKILL.md)大约 200 tokens，是合适的量级。

3. **它必须能更新。** 网关正文就是协议——如果 API 变了而用户本地的网关是旧的，agent 会按旧协议调用。所以：
   - 网关 frontmatter 里带 `metadata.platform_api_version`
   - `GET /gateway/SKILL.md` 永远返回最新
   - **CLI `setup` 时比对版本，不一致就更新本地网关**——这就是「安装必须由 CLI 主导」的理由（well-known 那条路没有可靠的更新机制）

---

## 第 6 章 · 与现有代码的关系

原基线是单包 `skill_platform/`，分层是对的。**2026-09-27 技术栈定为 Java 之后（[ADR 0011](../../decisions/0011-server-and-cli-stack.md)），它的定位变了：不再是「搬过来复用的代码」，而是一份「可执行的设计参考」。**

这句话改变了下表的读法：**每一行记的是「这份行为值不值得在 Java 里重建」，而不是「这段 Python 代码怎么处理」。** 因此**行号不再是有效锚点**——[`known-issues.md`](known-issues.md) 里指向这些模块的锚点（条数见该文件开头）随之成为历史记录。

代码一共 **1285 行**（四个模块 + 测试）。按此定位，**值得在 Java 里重建的大约只有 150–250 行**；其余本来就要重写：`parse_frontmatter` 必须换、`permissions.py` 要收窄、`store.py` 的连接管理要改、`schema.py` 的迁移机制要新建。

| 现有 | 在 Java 里怎么处置 |
|---|---|
| `importer.parse_frontmatter` | **重建，且换实现**。它只支持扁平 `key: value` 与缩进列表，而 `metadata` 实际是嵌套的（飞书案例），会静默丢字段。用完整 YAML 解析，**解析失败不得静默降级** |
| `importer.discover_skills` / `_collect_files` | **重建**为上传校验骨架。原行为（怎么界定一个 skill、怎么收文件）是这份基线最值钱的部分。需加：标准字段校验、512 文件 / 16 MiB 上限、符号链接与路径穿越检查 |
| `importer._extract_links` | **重建**，用于"正文引用了不存在的文件"这类警告级诊断 |
| `store.py` | **重建**：namespace / skill / version / version_file / blob / blob_content / user / token 等表。原设计是「每操作开新连接 + WAL + busy_timeout」，那是 SQLite 的东西——PostgreSQL 下是连接池 + `lock_timeout`（[ADR 0010](../../decisions/0010-storage-in-postgres.md)） |
| `store.permissions_version` + `permissions.PermissionCache` | **保留这套机制，重写实现**——"版本计数器失效 + TTL 兜底 + 失败 fail-closed"正好是搜索索引缓存需要的 |
| `materializer.py` | **已删除**（2026-09-26），不重建。本项目不再把 skill 落到本地，[ADR 0001](../../decisions/0001-server-authoritative.md) 早已判它作废。归档/导出若将来真需要，从 git 历史取回改写 |
| `permissions.py` | **收窄后再重建**：不做门控，只做**可见性**（public/unlisted/private）；命名空间成员判定推迟（v1 只有所有者一行）。family 匹配（`lark` 覆盖 `lark-*`）对集合型 skill 仍有用 |
| `__main__.py` | **已删除**，不重建。它的子命令全属旧模型。新的两组子命令——客户端（login/setup/search/show/get）与管理端（publish/versions/…）——**在 `skillmaster-cli/` 里用 Go 另写**（[ADR 0011](../../decisions/0011-server-and-cli-stack.md)、§4.6） |
| `schema.py` | **重建**：加表；`meta` + **schema_version 的校验与迁移需要新建**——基线只写入版本号、从不与常量比对，并不存在可「保留」的迁移机制（证据见 [`known-issues.md`](known-issues.md) L2） |

**Python 基线暂不删除**，已归档到 `skillmaster-server/reference-python/`（不参与构建、不跑测试），留到 Java 覆盖同一片语义之后再删：它是 `_collect_files` 的行为、权限族匹配这些边角语义的**唯一可执行记录**——本文是散文，不是可运行的。

**要新增的核心能力**：完整 YAML 解析器、严格校验器、digest 计算、blob store + GC、搜索与排序、OAuth AS、HTTP 服务、CLI 登录流程、网关 skill 的安装与更新。

---

## 第 7 章 · 分期

### P0a · 服务端（已实现）

P0 原本是一条端到端的验收，但它的验收需要 CLI，而 CLI 不存在——**所以 P0 拆成两半**，服务端这部分先做完。P0a 交付的是下面这些，**不含 CLI**：

- 表结构落地（第 3 章）+ 严格校验 + digest + 不可变版本
- 四个读接口 + 服务端搜索排序
- **服务端可运行入口**（HTTP 服务）。此前镜像构建得出来但起不来——没有入口；现在有了
- **鉴权用一把静态令牌先行**（AS 放 P1），把链路跑通。令牌绑定的 `app_user` 与 `namespace` 由迁移脚本 seed，因为注册属于 P1
- 一个最小写接口（`POST /api/v1/skills` 收 zip）与一个软删端点（§4.3）——P0 的清单里原本没有任何写接口，而验收要求「服务端已托管至少一个真实 skill」，没有摄入就无从触发「严格校验 + digest + 不可变版本」
- 网关 skill 发布到 well-known 索引（V2 + V1 两条路径），**服务端每次启动重发一次**（幂等）
- **验收**：服务端闭环——发布一个 zip → 搜到 → 看清单（确认零内容）→ 取正文 → 取单个文件 → 取网关；以及 §4.2 的契约负例（无令牌 401、越权 404、`../` 取文件 404、未发布时 well-known 真 404、同内容重发 version 不变）

### P0b · CLI + 网关安装（下一轮）

- CLI：`setup` / `search` / `show` / `get`
- 网关 `setup` 装到本地并按 `metadata.platform_api_version` 比对更新
- **验收**（也就是 P0 原本的验收）：在一个干净环境里 `setup` 装上网关，然后在 agent 里问一个需要某 skill 的任务，agent 能自己搜到、读到正文、按需取文件并完成任务——**且全程没有任何 skill 内容被当作副本落到本地**（`get` 落到临时目录是 agent 的中间产物，不算，见 §4.6）

**P0b 同时是若干未验证项的收口处**，因为只有真实客户端能判定它们：well-known 索引的 digest 算法是否符合客户端期望、V2 索引的 `$schema` 到底是什么 URL（现在留空不猜）、逐文件拉取的 `<base>` 究竟是哪几个别名。

### P0c · 寻址改成 `namespace/name` + 版本钉（**已实现**）

[ADR 0012](../../decisions/0012-addressing-and-version-pinning.md) 的寻址模型已落地：`skill_version` 有了序号列与 `UNIQUE(skill_id, number)`，三个读端点与 `DELETE` 都按 `namespace/name[@版本]` 寻址，详情响应的 `files[]` 每项带一条已钉版本的 `uri`（`resources.file` 那个 `{relpath}` 模板随之删掉），发布分配序号。**验收**见 [`test-plan.md`](test-plan.md) §结果。

**P0a 曾按旧寻址（`/api/v1/skills/{id}`、无版本概念）实现并测试通过**，所以这次是把那批断言**逐条重写**而不是打补丁——旧契约的测试全绿，不构成新契约成立的证据。


### P1 · 自建登录与令牌

**M01 已实现**（2026-10-01），**M02 尚未**——这一节因此分成两半。

已完成（M01，十个 `/web/*` 端点 + 会话 + 短信与频控）：

- **自助注册与找回密码**：手机号 + 短信验证码 + 图形验证码 + 密码 + 用户名（§4.4），语义见 [`architecture/modules/M01-account-login.md`](../../architecture/modules/M01-account-login.md)
- **注册的第一个发码请求免图形验证码**（[ADR 0020](../../decisions/0020-first-code-send-without-a-captcha.md)、[迭代 0011](iterations/0011-register-flow-and-sms-state.md)）：每个地址每个窗口一次，**找回密码不适用**。前端因此多了一个只读的 `GET /web/register/code/captcha-required`——开页时就知道要不要把图形验证码摆出来，而不是等被拒了才显示
- **用户名可用性可以先问**：`GET /web/username/availability` 答「注册会不会拒它」，与注册共用同一套规则——**两张表都要问**，因为注册的守卫是 `UNIQUE(app_user.handle)` 与 `UNIQUE(namespace.slug)` 两条。保留名 `skillmaster` 两张表里都有（V2 故意给系统账号起了同名 handle，靠约束而不是代码里的黑名单挡住它），所以它不是这一路要挡的情况；`namespace.slug` 那一路覆盖的是**没有同名 handle 的 slug**——v1 不产生这种行，schema 允许，组织命名空间就会有。它**不构成账号存在性预言机**：用户名本来就出现在每个已发布地址的第一段、每张搜索卡片和访问日志里（[ADR 0013](../../decisions/0013-phone-login-and-username-slug.md)）
- **图形验证码**（[`iterations/0003`](iterations/0003-captcha-and-sms.md)）：ADR 0013 三条防刷里的第三条，也是唯一能挡多 IP 攻击者的一条——**免费那次不受它约束**，所以它挡的是「同一个地址的第二次及以后」，这个放宽的代价记在 ADR 0020。用 Hutool 画图（活跃维护的那个），**但答案由我们自己的 `SecureRandom` 生成**——它的默认生成器走 `ThreadLocalRandom`，可预测
- **浏览器面那一半的三个面兑现**（§4.1）：`/web/**` 有自己的链，会话 cookie + CSRF（双提交），且**与 `/api/v1/**` 的链互相鉴权不了对方**
- schema 变更**已落，但是新增 `V3__account_login.sql`，不是改 `V1`**：`app_user` 加 `phone_hash` / `phone_enc`、`handle` 的注释改语义（原文写的是 "Login name"，与 [ADR 0013](../../decisions/0013-phone-login-and-username-slug.md) 冲突）、新增 `phone_verification` 与 `auth_throttle`、删掉 `browser_session`、建 Spring Session 的两张表；`ModuleMap` 随之更新。**本段此前写的是「直接改 `V1__baseline.sql`（沿用 P0c 的先例）」，那是错的**：V1 已合进 `main` 且在本机应用过，Flyway 按校验和拒跑，改它等于要求每台已有库重建一次；P0c 的先例不适用，那次是同一次提交里改的、还没被别人拉过。见 [`iterations/0002`](iterations/0002-m01-login-server.md)
- **阿里云短信客户端已接**（`com.aliyun:dysmsapi20170525`，SDK 只出现在 `config/SmsConfig` 一个文件里；`SmsGateway` 是那道缝，`AliyunSmsSender` 负责「被拒不能算成功」）。**但它与阿里云的真实调用没有被执行过**——签名与模板的审核是外部前置（§8 问题 12），也没有凭据。没配凭据时仍是 `LoggingSmsSender`，它**明确拒绝发送**而不是静默成功；凭据有而签名或模板为空则**启动失败**
- **并且当前不比对验证码**：`skillmaster.sms.accept-any-code`（默认 `false`）打开时任意六位数字都通过。**这是一个生产前必须关掉的口子**，存在的理由、它没摘掉什么、以及「配上凭据还开着它就启动失败」这条保护，见 §4.4 的状态注与 [迭代 0011](iterations/0011-register-flow-and-sms-state.md)

未完成（M02，同一个 P1 里剩下的）：

- OAuth 2.1 AS：`/oauth/authorize`、`/oauth/token` + 发现端点；**注册方式只做预注册**（CIMD 留到 P2，DCR 不启用——[ADR 0011](../../decisions/0011-server-and-cli-stack.md)）
- 授权同意页（`/oauth/authorize` 未登录时的落点是**已经有的** `/login`，它由 [`skillmaster-web/`](iterations/0004-web-frontend.md) 提供——M2 要加的是用户**已**登录、但还没同意授权时的那一页）
- CLI `login`（loopback PKCE）+ 无人值守 `--client-credentials`
- **`/inner/**` 挪到独立端口**（反代不转发），actuator 随之离开主端口而不再需要那条 `permitAll`；最后一条规则改成 **`anyRequest().denyAll()`**——今天是 `authenticated()`，任何一个新加的、不带前缀的 controller 都会变成「任何有效令牌都能进」
- 可见性生效（**成员判定推迟**，v1 只有所有者一行）；审计日志
- 管理端写接口

### P2 · MCP 适配器与检索质量

- MCP 适配器：`skills/list` / `skills/get` / `resources/read`（能力协商，客户端不支持则不影响 API）
- **AS 加 CIMD**：第三方客户端（Claude Code / VS Code / ChatGPT）从这里接进来。CIMD 是它们唯一的路径，所以这一项与适配器同批交付；DCR 仍只作兼容
- 排序用上 `skill_stat` 的信号；检索质量回归测试
- 版本历史 / diff / 回滚

### P3 · 之后

- 渐进付费（附录 B）

---

## 第 8 章 · 开放问题

按需要先解决的顺序：

1. **网关 skill 的 `description` 怎么写。** 它决定发现体验的上限，且没有数据可依。建议先写，然后用真实任务集做回归（"给这 20 个任务，看它该不该去查 skill 库"）。
2. **排序权重调到多少。** P0 的公式与权重位置已定（§3.4），值是启发式起点（100/40/20），可配置。仍未解决的是**怎么判定调好了**——需要一套真实查询与期望结果的回归集，P0 没有。
3. **搜索的中文分词与索引形态。** 原计划（SQLite FTS5 + `trigram`）已实测**不可行**：它对少于 3 个字符的查询返回 0，而两个汉字是最常见的一次查询（证据见 [ADR 0010](../../decisions/0010-storage-in-postgres.md)）。方向是 PostgreSQL 侧的 **`pg_bigm`**（2-gram 索引）。**扩展的可用性已核实**（2026-09-28）：阿里云 RDS for PostgreSQL 官方文档有专页讲用 `pg_bigm` 做模糊查询，写明它对中日文这类非字母语言、以及 1–2 个字符的短关键词有效（来源：阿里云帮助中心《Fuzzy query (pg_bigm)》，2026-03-28 更新）。**仍未核实的是部署时那台实例上的两件事**：RDS **基础版**是否可用、`shared_preload_libraries` 能否配上——两者都要等真正部署才验得了。**P0a 已用 `LIKE` 直查绕开这个问题**（功能可用、没有索引），所以本条现在只关延迟，不关正确性。`pg_bigm` 是 GIN 索引、加速同样的 `LIKE` 但不给相关性分数——见 §3.4。
4. **`relpath` 的取值规范。** P0 已定最小集并写进 §3.3（POSIX 分隔符、不以 `/` 开头、无 `..`、无反斜杠、大小写敏感、允许非 ASCII）。仍未定的是**是否要放开**——一旦发布就不好改，且它进 URL。
   **新增一维**：地址改成 `namespace/name` 之后，`name` 也进了 URL，于是 `name` 的取值规范变成对外契约的一部分。P0c 已禁掉 `@`（版本后缀的分隔符，留着地址就不可判定），现行规则是 ≤64 字符、不含空白与路径分隔符、**不含 `@`**、**允许非 ASCII**（`SkillUploadValidator`）。仍未定的是**要不要收紧到 ASCII**：一个叫 `飞书任务` 的 skill 会得到一条百分号编码的地址——功能上正确（`uri` 会编码，客户端照抄即可），但它既不好看也不好手写。**另有两个收紧理由已实测**：`;` 与 `%` 都会让 `uri` 取不到（`StrictHttpFirewall` 分别拒绝分号与 `%25`），见 [`test-plan.md`](test-plan.md) §已知问题（缺陷的权威在那里，此处不重述）。**其中 `name` 那一半已在 2026-09-28 的审计轮收口**：M5 现在拒绝含 `%` 或 `;` 的名字（错误码 `name_contains_unaddressable_char`），因为那一半更严重——名字里带上它们，连详情、正文与删除地址都一起失效。`relpath` 那一半仍留着。
5. **无人值守的 Client Credentials 怎么发放**：谁有权创建、绑哪个用户身份、scope 怎么限。
6. **blob GC 的策略**：延迟多久回收、是否需要"回收前先归档"。P0 的占位答案是「版本变更时清扫、不延迟、不归档」，且 P0 一次都删不掉东西（§3.3 点 5）。**另有一个 P2 才需要回答的**：现在清扫要把「仍被引用的 hash 全集合」搬过 M7→M6 的接缝，版本历史大了之后这个集合会很大——见 §2.5 规则①的落法。
7. **是否支持从别的 registry 镜像**（如把飞书的 `lark-*` 导入进来）。
8. **阿里云 RDS 是否允许用户表空间。** 没有核实过。P0 把 `TABLESPACE` 降级成部署步骤（§3.3），所以它只影响「字节能不能搬到单独表空间」，不影响能否上线。
9. **well-known 索引的三件事**（都只能等 P0b 的 CLI 实测）：V2 的 `digest` 用哪个算法才符合客户端期望（§4.5，P0 按 §1.5 反查的结果实现，但那是读反编译代码得来的）、V2 的 `$schema` 到底是什么 URL（§1.5 说它挂在 `agentskills.io` 下且当前 DNS 不解析，所以 P0 留空不猜——见 `GatewayIndex.V2`）、逐文件拉取的 `<base>` 该是哪几个（§4.5，P0 三个别名都发）。
10. **改名之后旧地址怎么办。** 地址改成 `namespace/name` 之后，改名就会**断掉已经发出去的地址**——那些地址可能写在别的 skill 正文里、写在文档里、写在 agent 的上下文里。三条路：**断链**（最简单，`404`）、**留别名**（旧名永久解析到同一个 skill，代价是改过的名字像域名一样永久占位、且需要一张别名表）、**只允许软改**（改名 = 新建一个 skill + 把旧的标成「已迁移」，地址不回退）。ADR 0004 当初选不透明 id 正是为了躲开这个取舍，现在取舍回来了——**未定**。
11. **草稿要不要做、以什么形态做。** v1 明确不做（[ADR 0012](../../decisions/0012-addressing-and-version-pinning.md) §理由：v1 里没有第二个消费者，草稿与已发布在可见性上没有区别）。P2 有共享之后再做，形态待定——「版本上的一个状态位」与「独立的可变工作副本」是两种东西，后者更贴 git 的工作区语义但要处理 GC（§3.3 已记下那个坑）。
12. **短信签名与模板的审核。** 注册与找回密码都要发短信（[ADR 0013](../../decisions/0013-phone-login-and-username-slug.md)），而短信的签名与模板**要审核通过才能发**——所以这是 `prd.md` 验收 #1 的**外部前置**，周期不在我们手上。**这个项目用的阿里云账号是企业认证**（2026-10-02 确认），所以「标准国内短信」这条路走得通。
    **已核实（2026-10-02，来源是阿里云自己的帮助中心，不是二手转述）**：
    - **只有企业资质能报备并发短信。** 个人认证的用户，自用资质**无法通过签名实名制报备**；官方给这类用户的出路是改用「短信认证服务」产品，或升级为企业认证（《短信服务使用须知》，2025-10-30 更新）。
    - **签名来源只剩两条**：`企事业单位名` 与 `已注册商标名`。`已备案网站`、`公众号或小程序`、`电商平台店铺名`、`测试或学习`、`线上试用` 自 **2025-09-28** 起不再支持（《关于短信签名申请规则变更的通知》）；`已上线 APP` 自 **2026-04-27** 起不再支持，并明确禁止「基于 ICP 备案信息申请」的签名。用企事业单位名时：全称要与证照**逐字一致**；简称必须是全称的**连续子集、不许跳字乱序、必须含地域**（省/市/区），建议 4 字以上（《签名来源及申请要求》）。
    - **国内短信不支持全英文签名**，也不支持繁体、全数字、首字母缩写、空格与特殊符号（《短信签名、短信模板、资质审核不通过》）。**所以纯英文的产品名做不了落款**——即使它名下有商标。
    - **周期有两段，第二段才是长杆**：资质 ≤2 个工作日；签名与模板审核 ≤2 小时（每日 9:00–21:00）；通过后**系统自动提交运营商报备，报备要 7–10 个工作日甚至更久**，而**未报备的签名会被运营商拦截**（《快速使用控制台发送短信》）。所以从申请到真能收到短信是**两周左右**，不是两小时。
    - **按号码的频控比我们自己的严**：同一签名发给同一号码的验证码，**1 条/分钟、5 条/小时、10 条/天**（经短信服务发给同一号码的验证码合计 40 条/天）。分钟与小时按 UTC+8 整点窗口，天按 24 小时滚动（《什么是短信发送流控限制？》，2026-09-25 更新）。我们自己只按地址限流，所以手工联调时先撞到的是它们的 5 条/小时。
    **仍未核实**：单条计价与有无套餐、这个签名与模板能否一次过审。**要验的**：在准入账号下实际申请一次，把周期与单价记下来——**别照抄任何二手数字**（上面这些是阿里云自己的文档，单价不在其中）。
    **代码这一侧已经做完了**（[`iterations/0003`](iterations/0003-captcha-and-sms.md)）：客户端接的是官方 SDK，`AliyunSmsSenderTest` 钉住了「模板参数形状」与「被拒必须抛出来」，而**与阿里云的真实往返一次都没跑过**——没有凭据就验不了。所以剩下的是：申请签名与模板 → 把四个 `SKILLMASTER_SMS_*` 配上 → 发一条真短信，看它到不到。在那之前，这一条的状态是**未验证**，不是**已完成**。
    **等待期间它不再挡住整条流程**：`accept-any-code` 让注册与找回先跑起来（[迭代 0011](iterations/0011-register-flow-and-sms-state.md)），但那是**临时口子，上线前必须关掉**。
13. **两把手机号密钥怎么轮换。** M01 把手机号存成盲索引（HMAC）+ 密文（AES-GCM），两把密钥都来自配置。**今天没有任何轮换方案**：丢 `phone-hmac-key`，所有账号按手机号都找不回来（那是带密钥的单向映射，没有第二次机会）；丢 `phone-enc-key`，号码全都显示不出来。也**没有 key id 列**，所以「用新密钥重加密」连标记都做不了。**v1 可接受**（密钥在部署的 secret 里），但这是要还的债，不是意外。要做的话：`phone_enc` 那一半加 key id + 按需重加密；`phone_hash` 那一半只能靠「下次登录时按新密钥重写」，而那就需要一列旧哈希或一张迁移表——**未定**。

---

## 附录 A · 架构决策

本设计里的**决策不写在这里**，而是独立成编号 ADR —— 决策跨版本存活，不该随设计文档一起被重写。

完整索引见 [`decisions/README.md`](../../decisions/README.md)。与本版相关的十三条：

| ADR | 决策 | 本文对应章节 |
|---|---|---|
| [0001](../../decisions/0001-server-authoritative.md) | 服务端权威，本地不落 skill 副本 | 第 0、2 章 |
| [0002](../../decisions/0002-gateway-skill-and-cli.md) | 交付形态：一个通用网关 skill + 自研 CLI | 第 5 章 |
| [0003](../../decisions/0003-api-primary.md) | API 为主契约，MCP 为可选适配器 | 第 4 章 |
| [0004](../../decisions/0004-opaque-id-primary-key.md) | 身份模型：不透明 `id` 作主键，`name` 为属性 | 3.2、3.3 |
| [0005](../../decisions/0005-content-addressing.md) | 内容寻址与幂等发布 | 3.3 |
| [0006](../../decisions/0006-search-server-side.md) | 检索在服务端，且只索引 L1 | 3.4、4.2 |
| [0007](../../decisions/0007-self-built-oauth-as.md) | 自建 OAuth 2.1 AS，支持三种客户端注册方式 | 3.1、4.4、4.6 |
| [0008](../../decisions/0008-no-script-execution.md) | P0 不做脚本执行 | 第 7 章 |
| [0009](../../decisions/0009-drop-l2n.md) | 砍掉 `l2#n`，付费边界只落在层与层之间 | 附录 B |
| [0010](../../decisions/0010-storage-in-postgres.md) | 存储全部落在 PostgreSQL（含字节） | 第 2、3 章 |
| [0011](../../decisions/0011-server-and-cli-stack.md) | 技术栈：服务端 Java + Spring，CLI 用 Go | 第 2、4、6、7 章 |
| [0012](../../decisions/0012-addressing-and-version-pinning.md) | 寻址：`namespace/name` + 版本钉 | 4.1、4.2、4.3、第 7 章 |
| [0013](../../decisions/0013-phone-login-and-username-slug.md) | 登录凭据与公开身份分离：手机号登录，用户名做 slug | 3.1、4.4、第 7、8 章 |

**本文只链接、不复述理由。** 若发现正文里重复解释了某条决策的原因，那是需要清理的重复——理由只有一处权威来源，就是 ADR 本身。

---

## 附录 B · 已推迟：渐进付费

**不是废弃。** 记录要点供日后重启时不必重推。

- **服务端门控是唯一正确的门控位置**——内容一旦落到客户端，门控只能靠"不下发这些字节"。本项目的服务端权威模型天然满足这一点，**接入付费的成本比"下载到本地"模型低得多**。
- **MCP Skills 扩展已规定好完整性语义**：approval 必须绑定完整 URI+digest 集合，文件增删改即吊销。不需要自创版本锁定。
- **支付必须走 URL mode elicitation**，form mode 被规范明令禁止承载支付凭据（原文核实）。
- **业界现状**：只有整包买断（Agensi、ClawHub）与按次计费（腾讯 SkillPay、x402、Cloudflare `paidTool`、Stripe MPP）两类，**无人做"按内容层级计费"**。既是空白，也无转化率证据。
- **`l2#n` 已否决**：平台自定义概念，标准只有三层。标准对"想少加载正文"给的答案是**把深度内容移到 `references/`**（即 L3）。付费边界只应落在层与层之间。
- **接入方式**：在 `version_file` 上挂节点价格，在四个读接口加权益判定，在 `auth_code`/`access_token` 之外加钱包与订单表。

---

## 附录 C · 来源与出处

> 下面绝大多数是一手来源；标了"社区维护"的那条是二手，只作参考。

- Agent Skills 规范：https://agentskills.io/specification
- Agent Skills 客户端实现指南：https://agentskills.io/client-implementation/adding-skills-support
- MCP Skills 扩展：https://modelcontextprotocol.io/extensions/skills/overview
- MCP 扩展支持矩阵：https://modelcontextprotocol.io/extensions/client-matrix
- MCP 2026-07-28 变更：https://modelcontextprotocol.io/specification/2026-07-28/changelog
- MCP authorization：https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization
- Claude Code skills：https://code.claude.com/docs/en/skills
- Claude Code MCP：https://code.claude.com/docs/en/mcp
- Claude Code marketplace 托管：https://code.claude.com/docs/en/plugins/host-marketplace
- MCP prompts 对模型不可见：https://github.com/anthropics/claude-code/issues/11054
- well-known 通道的消费方（Vercel `skills` CLI）：https://github.com/vercel-labs/skills
- MCP 客户端 OAuth 支持矩阵（社区维护，供参考）：https://www.redcaller.com/docs/references/mcp-client-oauth-refresh-token-support
- Cursor MCP：https://cursor.com/docs/context/mcp
- VS Code MCP 配置：https://code.visualstudio.com/docs/agents/reference/mcp-configuration
- Gemini CLI MCP：https://google-gemini.github.io/gemini-cli/docs/tools/mcp-server.html

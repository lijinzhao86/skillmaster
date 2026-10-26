# v20-guidance-on-platform · 产品需求

> **最后更新**：2026-10-26
> **所属版本**：[`README.md`](README.md)

## 用户与场景

**谁在用**：第一次接触 SkillMaster 的 agent 用户（`skillmaster startup` 跟随者）；以及维护引导文案的产品方。

**在什么场景下用**：

- 用户按首页的安装说明装好 CLI，运行 `skillmaster startup`，agent 跟着旅程一步步把本机 skill 搬到平台、上线、写系统提示词、验证、下线本机副本。
- 维护者要改规则里的一句话（触发条件、命令、措辞），希望**第二天对所有人生效**，而不是等一次 CLI 发版。

**现在的替代做法是什么**：v17 的设计把旅程正文编进服务端 jar（`/gateway/STARTUP.md`），把规则段编进 CLI 的 `help usage`。两者都是**内容**，却都跟着**二进制**走：改一句规则要发一版 CLI，改一段旅程要发一版服务端，而且规则段与旅程会各改各的。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | 旅程正文作为官方技能 `skillmaster/startup`，多文件、一次只读一步 | 平台技能（已上线 1.0.0，本版并入规则段） |
| 2 | `skillmaster startup` 改为**加载** `skillmaster/startup`，不再取 `/gateway/STARTUP.md` | CLI |
| 3 | 可粘贴的规则段写进该技能的第三步步骤文件（`steps/03-prompt.md`），逐字使用已批准的文本 | 平台技能内容 |
| 4 | `help usage` 主题与顶层 help 的指路行删除；`help` 只讲命令 | CLI |
| 5 | 服务端退休 `GET /gateway/STARTUP.md`；仓库删掉整个 `gateway/` 目录（它唯一的文件就是这份正文），服务端 jar 不再把 `gateway/` 拷进 classpath | 服务端 / 仓库 / CI |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 在 CLI 或服务端保留规则 / 旅程副本 | 第二份文本必然与平台版本漂移；规则只有一个权威位置 | 不做 |
| 2 | 技能不可用时打印内置兜底正文 | 兜底就是副本；宁可明确失败并指路 | 不做 |
| 3 | 把规则段做成独立技能 | 它只被旅程的第三步读取，多一个技能多一处维护 | 若出现「不进旅程、只想取规则」的需求 |
| 4 | 技能版本与 CLI 版本的兼容性协商 | 正文只讲命令名，不依赖版本特性 | 若正文开始依赖新命令 |
| 5 | 新增控制台操作 | 技能的编辑与上线是既有流程 | 不做 |

## 控制台操作与 CLI 对应

本版**不新增**控制台操作：规则段与旅程正文都是 `skillmaster/startup` 的内容，用既有的「编辑技能 → 待上线 → 上线」流程（[v18](../v18-batch-publish/README.md)），CLI 侧对应的是 `skills pending` 与新版本的 `skill read`。

| # | 控制台操作 | CLI 命令 | 备注 |
|---|---|---|---|
| 1 | 编辑技能文件并上线新版本 | `skillmaster skills pending`（核对队列）+ `skillmaster skill read`（核对内容） | 上线本身是高风险、只在网页做（[ADR 0061](../../decisions/0061-what-a-credential-may-do-to-versions.md)），不是本版新增 |

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| 1 | 已登录机器上 `skillmaster startup` 先打印现场状态行，再打印 `skillmaster/startup` 的 `SKILL.md` 正文 | 状态行之后的部分与 `skillmaster skill cat skillmaster/startup SKILL.md`（原样，不加行号）逐字一致；`skill read` 会加行号与尾注，不和它比 |
| 2 | 正文里给的下一步命令可执行：`skillmaster skill read skillmaster/startup steps/01-import.md` 返回该文件 | 命令退出码 0，正文非空 |
| 3 | `startup` 加载后，同一次旅程里的 `skill read` 用的是**同一版本**（invoke 钉住版本） | 旅程中途发布新版本后，`skill read` 仍返回旧版本的步骤文件 |
| 4 | `skillmaster help usage` 不再存在，顶层 help 不再出现「流程：skillmaster help usage」 | `skillmaster help usage` 以未知主题失败；`skillmaster help` 输出里搜不到该行 |
| 5 | 技能第三步文件包含规则段，逐字等于已批准的三行文本（含起止标记） | `skillmaster skill read skillmaster/startup steps/03-prompt.md` 取出后与批准文本比对 |
| 6 | 服务端不再提供 `/gateway/STARTUP.md` | `GET https://console.skillmasterai.com/gateway/STARTUP.md` 返回 404，且不是 HTML 页面 |
| 6a | 仓库里不再有 `gateway/` 目录，jar 里也不再带它 | `ls gateway` 不存在；`unzip -l app.jar \| grep gateway/` 无输出 |
| 7 | 无凭据且无浏览器时，`startup` 以凭据退出码（4）结束，并给出两条进入方式（登录 / 控制台签发的 key） | 在无凭据、无浏览器的环境运行，检查退出码与 stderr |

**成立条件**：验收 6 依赖服务端部署，且**服务端必须在新 CLI 发版之后才能部署**——老客户端仍在调用这条路由，先删会让它们的 `startup` 直接失败（见 TD §分期）。

### 指标

本版没有需要长期跟踪的量化指标。触发质量沿用 v17 的评测口径：规则段改动后按 [`test-plan.md`](test-plan.md) T6 重跑一次触发评测，通过率 12/12 才算不退化。

## 依赖与前置

- **外部依赖**：无第三方服务；需要生产环境可访问（技能已上线）。
- **内部前提**：
  - [ADR 0096](../../decisions/0096-guidance-lives-on-the-platform.md)（本版唯一一条决策）；
  - [ADR 0094](../../decisions/0094-load-the-library-at-session-start.md) 的规则文本与位置由 0096 取代；
  - [ADR 0093](../../decisions/0093-no-skill-discovery-index.md) 保留的 `/gateway/STARTUP.md` 路由由 0096 退休。
- **排除了什么前置**：不需要新的服务端路由、不需要数据库迁移、不需要新的控制台页面。

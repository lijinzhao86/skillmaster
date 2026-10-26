# v20-guidance-on-platform · 技术设计

> **最后更新**：2026-10-26
> **状态**：P1 已落地；P0 的规则段已上线（`@1.0.1`），旅程改成可执行命令的那一版还在草稿里待上线；P2 代码已合并、**生产未部署**（部署是手动 workflow_dispatch，`server.yml` 里 `Deploy` 只在 `workflow_dispatch` 上跑）
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR 0096](../../decisions/0096-guidance-lives-on-the-platform.md)**——本文只链接、不复述理由。

## 架构

引导内容全部搬到平台，CLI 与 服务端不再持有副本：

```
skillmaster startup
  ├─ 确保登录（没有凭据 → login 流程；无浏览器 → 退出码 4）
  ├─ 打印现场状态行（本机扫描 + publish-queue + live 列表；任何一步取不到就不打印）
  └─ invoke skillmaster/startup  ──►  GET /api/v1/skills/skillmaster/startup/metadata
                                    GET /api/v1/skills/skillmaster/startup/body
                                        （版本走请求头 X-Skill-Version，不写进路径）
                                    （加载后把这一版钉在本机）
        │
        └─ 正文告诉 agent：下一步跑
              skillmaster skill read skillmaster/startup steps/NN-*.md
           read 沿用 invoke 钉住的版本，旅程中途别人发布新版本也不换文件

服务端：不再有 /gateway/STARTUP.md，也没有 gateway 资产随 jar 打包
CLI：help 只讲命令；规则段与旅程正文都不在二进制里
```

**为什么加载而不是取文档**：技能是内容，内容按内容的方式更新（控制台编辑 → 上线新版本），CLI 与 服务端都不用发版。**为什么 invoke 顺手钉版本**：旅程是多文件、一次读一步，翻页途中换版本会让步骤与现场对不上；钉住版本让整趟旅程读的是同一份（[prd](prd.md) 验收 3）。

**规则段的位置**：`skillmaster/startup` 的 `steps/03-prompt.md` 内部，就是第三步要写给用户的那段文本。它不需要单独的文件，因为只有第三步读它；`help usage` 退休后，想重取规则的人走 `skillmaster skill read skillmaster/startup steps/03-prompt.md`。

## 数据模型

**没有新表、没有迁移。** 技能内容走既有的 skill / version / file 三张表；本版只在平台上发布一个新版本。

## 接口

### 服务端

**不新增路由。** 退休一条：

| 方法 | 路径 | 变化 | 说明 |
|---|---|---|---|
| `GET` | `/gateway/STARTUP.md` | **删除** | 正文改由技能提供（ADR 0096） |

连带删除的实现（[附录 A](#附录-a--与现有代码的关系)）：`StartupBody`、`StartupController`、`StartupConfig`、`pom.xml` 里把 `gateway/` 拷进 classpath 的那段、以及仓库根目录的 `gateway/skillmaster/STARTUP.md`。

**`SecurityConfig` 的 `"/gateway/**"` permit 保留。** 路由没了以后它是一段没有处理器的放行规则，作用只剩一个：老客户端请求 `/gateway/STARTUP.md` 时拿到服务端的 **404（JSON）**，而不是 401，也不是代理回的 HTML 页面。去掉它换来的是 401——对一个来晚了的客户端，含义反而更模糊。

`deploy/nginx/skillmaster.conf` 里那条代理 location 的 `gateway` 前缀同样保留，理由相同：这一层要保证请求到达服务端，由服务端给 JSON 404；从代理层拿掉，请求会落到 SPA 的 `try_files`，返回一张 200 的 HTML。

### CLI

| 命令 | 变化 |
|---|---|
| `skillmaster startup` | 取正文从 `api.Startup`（`/gateway/STARTUP.md`）改为加载 `skillmaster/startup`：`fetchBody` + 钉版本，正文原样打印 |
| `skillmaster help usage` | **删除**该主题；顶层 help 不再有「流程：…」那一行（该行由 `Topics()` 渲染，主题没了它就没了） |
| `skillmaster help` / `help env` | 文案里不再指向 `help usage` |

**`startup` 复用 `invoke` 的那条加载路径，不另写一份。** 现成的 `fetchBody`（`cmd/skillmaster/main.go`）先取 metadata 再按它给的版本取 bytes；钉版本由 `consumerPin` + `rememberPin` 做，`invoke` 与 `startup` 共用同一个定义。两条命令对「加载」必须有一致的语义，复制一份就是把「版本怎么算」写第二遍。

**状态行不再写步号。** 改动前它写「下一步：搬运（第二步）」「写提示词、验证，然后下线（第四到六步）」，那是 v17 把登录算成第一步的编号。旅程现在归技能，步骤是 1–6 且以后还会调整——**编号只能有一个来源**，所以状态行改说阶段（搬运 / 上线 / 写提示词 / 已完成，`cmd/skillmaster/state.go` 的 `whereYouAreLine`），由技能的起点表把它映射到文件。**第六步（重启 agent）判断不到**：CLI 无法知道人有没有重启，所以本机没有副本时仍报「已完成」，由 `SKILL.md` 的对照表先确认第三步的提示词在不在指令文件里，再指到 `steps/03-prompt.md` 或 `steps/06-restart.md`。

**旅程不再依赖两个还没实现的命令**（2026-10-26 定）：第一步改用 `skill submit <目录>`（它把整个目录打成包提成草稿），第五步改成手动流程（先 `skills list` / `skills pending` 核对 → `zip` 备份到 `~/.skillmaster/` → 才逐个删）。`skills import` / `skills retire` 后来被 [v17](../v17-cli-startup/prd.md) 砍掉（2026-10-26，见该版「不做」表），所以这不再是「等命令做出来」，而是本条取代的最终形状。

**失败形状**：
- 技能不存在 / 被下线 / 不可见 → 服务端的错误原样透出，并补一句「旅程在 `skillmaster/startup`，去控制台确认它还上线着」。
- 未登录且无浏览器 → 退出码 4，两句进入方式（沿用 v17 验收 1）。
- 状态行取不到（队列读不到、本机扫描失败）→ 不打印状态行，正文照常。**断网时两者都失败**：正文不再是匿名文档，它和状态行要的是同一个服务端（ADR 0096），所以断网时 `startup` 整体失败，这是 v17 验收 29 口径在 v20 下的正确答案。

## 分期

| 期 | 交付 | 验收 | 这一期明确不做 |
|---|---|---|---|
| P0 技能 | 把规则段并进 `steps/03-prompt.md`，发布并上线这个版本（`@1.0.1`） | 5 | 不改步骤顺序、不改其它步骤文件 |
| P1 CLI | `startup` 改为加载；删 `help usage`、`usage_test.go`、`api.Startup` / `StartupPath`；help 文案 | 1–4、7 | 不动 `skills import` / `retire`（v17 已砍，见该版「不做」表） |
| P2 服务端 | 删路由与资产、pom 拷贝、CI 断言与路径过滤；测试改为断言路由已退休 | 6 | 不动 `/.well-known/**` 与 oauth 链 |

**顺序是硬约束**：P0 上线之后 P1 才有东西可加载；P2 必须**在 P1 发版之后**部署——先删路由，会让还没升级的客户端 `startup` 直接失败。客户端发版前，服务端保留 `P2` 的删除。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 技能被下线或删除时，`startup` 是否应该有内置兜底？ | 不兜底，明确报错并指路控制台 | 验收 7 只覆盖凭据失败；技能缺失是运维事故，报错比一份会过期的副本诚实 |
| Q2 | 旅程中途技能发布了新版本，已开始的旅程怎么办？ | invoke 钉版本，read 沿用 pin，整趟读同一份 | 验收 3 用「中途发布」实测 |
| Q3 | `steps/` 的文件名要不要带序号？ | 带（`01-import` …），顺序是信息 | 序号只是排序提示，正文的下一步命令写明具体文件名 |

## 附录

### 附录 A · 与现有代码的关系

**删除：**

| 路径 | 是什么 |
|---|---|
| `gateway/`（整个目录） | 唯一的文件是 `gateway/skillmaster/STARTUP.md`（旅程正文的 jar 资产）；删掉它之后目录为空，整个删除 |
| `skillmaster-server/src/main/java/com/skillmasterai/modules/gateway/StartupBody.java` | 读 classpath、替换 `<host>` |
| `skillmaster-server/src/main/java/com/skillmasterai/api/StartupController.java` | `GET /gateway/STARTUP.md` |
| `skillmaster-server/src/main/java/com/skillmasterai/config/StartupConfig.java` | 构建 `StartupBody` 的 bean |
| `skillmaster-cli/internal/api/startup.go` | `StartupPath` 与 `Client.Startup` |
| `skillmaster-cli/cmd/skillmaster/usage_test.go` | 钉住 `help usage` 快照的测试 |
| `skillmaster-server/src/main/java/com/skillmasterai/common/ModuleMap.java` 里的 M11 行 | 指向已删除的 `…modules.gateway` 包 |
| `skillmaster-server/Dockerfile` 的 `COPY gateway/ ./gateway/` | 构建上下文是仓库根，目录没了就无法构建镜像（**第一轮审计发现，CI 每次合并都会红**） |

**修改：**

| 路径 | 改什么 |
|---|---|
| `skillmaster-cli/cmd/skillmaster/declarations.go`（`usage` 主题 / `startup` 声明） | 删 `usage` 主题；`startup` 的 Output 文案改为「加载 `skillmaster/startup`」 |
| `skillmaster-cli/cmd/skillmaster/main.go` 的 `startup` 与 `startupUnavailable` | `startup` 改为加载技能 |
| `skillmaster-cli/cmd/skillmaster/cli_test.go`（顶层 help 那条断言） | 断言反过来：顶层 help **不**出现 `help usage` |
| `skillmaster-server/src/test/java/com/skillmasterai/api/StartupIT.java` | 改名为 `GatewayRetirementIT`，断言 `/gateway/STARTUP.md` 404，保留 ADR 0093 的 SKILL.md / 索引 404 断言 |
| `skillmaster-server/pom.xml` 的 `copy-gateway-skill` 执行段 | 删除 |
| `.github/workflows/server.yml` 的路径过滤 | 去掉 `gateway/**` |
| `.github/workflows/server.yml` 里「jar 必须含 STARTUP.md」的断言 | 删除 |
| `SkillmasterProperties.java` 里 `publicBaseUrl` 的 javadoc | 去掉 `//gateway/STARTUP.md` 那个例子 |
| `SkillRoutes.java` 里提到启动/网关的注释 | 改为泛指——`StartupBody` 已经从仓库里消失，那一行是在说这次改动做过什么 |
| `deploy/README.md` | 「部署」一节里「断言 jar 带网关 skill」的句子随之更新 |

### 附录 B · 来源与出处

- 已批准并实测的规则文本：本仓库会话记录（2026-10-26 触发评测，12/12 符合预期；口径见附录 C）。
- 技能格式与渐进披露：[Agent Skills 规范](https://agentskills.io/specification)（`SKILL.md` 正文 <500 行、细节放引用文件、引用一层深）。
- 现有旅程设计：[v17 技术设计](../v17-cli-startup/technical-design.md) §架构 ③④。

### 附录 C · 已测量的数据

| 指标 | **口径**（定义/样本/是否计入零值） | 数值 |
|---|---|---|
| 规则段触发评测 | 12 道题（8 应触发 / 4 不应触发），每题**只跑 1 次**，题目由看过规则文本的人编写，测「第一个动作」 | 12/12 符合预期（2026-10-26） |
| 偏置 | 题目措辞贴近触发条件；未做 3 次重复；未做独立命名的验证集 | 结论只能算「骨架没有走反」，不是触发率 |

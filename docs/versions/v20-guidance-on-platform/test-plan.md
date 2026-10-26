# v20-guidance-on-platform · 测试方案

> **最后更新**：2026-10-26
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #1 `startup` 打状态行（stderr）、正文（stdout） | T1 | 通过（见 §结果） |
| #2 正文里的下一步命令可执行 | T2 | 通过（见 §结果） |
| #3 旅程中途发布新版本，`skill read` 仍返回旧版本 | T3 | 未执行 |
| #4 `help usage` 不再存在，顶层 help 不再指它 | T4 | 通过（见 §结果） |
| #5 第三步文件含已批准的规则段 | T5、T6 | T5 通过；T6 未执行 |
| #6 `/gateway/STARTUP.md` 返回 404（非 HTML） | T7 | 部分通过（本地 IT 通过；生产待部署） |
| #6a 仓库与 jar 里都没有 `gateway/` | T7 | 部分通过（仓库与 jar 已验；Dockerfile 的 `COPY gateway/` 第一轮审计发现并已删） |
| #7 无凭据且无浏览器时退出码 4 | T8 | 部分通过（单测通过；未在真实无浏览器机器上跑） |

## 用例

### T1 · startup 的输出

- **前置**：本机已登录；生产环境可访问；技能 `skillmaster/startup` 已上线；**本机钉住的版本就是线上当前版本**（否则 stderr 会多一行 pin 迁移提示，见下面的期望）。
- **步骤**：运行 `skillmaster startup`，同时运行 `skillmaster skill cat skillmaster/startup SKILL.md`（原样，不加行号）。
- **期望**：`stdout` 与 `skill cat` 的输出**逐字一致**（`skill read` 会加行号与尾注，不和它比）；`stderr` 以现场状态行开头（「现在的位置：」），空一行。除此之外 stderr **还可能**有「上次钉在 @X，现在是 @Y——中间有人发布过」或「没能记下这一版：…」两行之一——两者都是正常输出，不算 T1 失败；除这三种以外没有别的内容。两个流分开，是因为 `startup > journey.md` 要存下的正是旅程正文。
- **判定**：逐字比对通过即通过。

### T2 · 下一步命令可执行

- **前置**：同 T1。
- **步骤**：`skillmaster skill read skillmaster/startup steps/01-import.md`。
- **期望**：退出码 0，正文以「# 第一步 · 搬运本机的 skill」开头。
- **判定**：退出码与首行都对上即通过。

### T3 · 钉版本

- **前置**：T1 已跑过（本机钉住当前版本）。
- **步骤**：以官方账号发布一个新版本（改动任意一行的措辞）；再次运行 `skillmaster skill read skillmaster/startup steps/01-import.md`。
- **期望**：仍返回**旧版本**的文件；`skillmaster skill invoke skillmaster/startup` 才会切到新版本，并打印「上次钉在 @… 现在是 @…」。
- **判定**：文件内容未变即通过；变了说明 pin 没生效。
- **清理**：测试用的新版本保持上线（它就是正式内容），不额外回滚。

### T4 · `help usage` 已经不在

- **前置**：本地构建的 CLI。
- **步骤**：`skillmaster help usage`；`skillmaster help`。
- **期望**：前者以未知主题失败（退出码 2），错误里列出的主题不含 `usage`；后者输出里搜不到「流程：skillmaster help usage」。
- **判定**：两条都对上即通过。

### T5 · 规则段在第三步文件里，逐字一致

- **前置**：技能已发布含规则段的版本。
- **步骤**：`skillmaster skill read skillmaster/startup steps/03-prompt.md`，取出 `<!-- skillmaster:begin -->` 到 `<!-- skillmaster:end -->` 之间的内容。
- **期望**：与已批准的三行文本逐字一致（含标点、`<地址>`、`--scope public`、`skillmaster help`）。
- **判定**：逐字比对通过；任何一处不同都算失败。

### T6 · 触发评测复跑

- **前置**：T5 通过。
- **步骤**：按附录 C 的口径跑 12 道题（8 应触发 / 4 不应触发），每题 1 次，看第一个动作。
- **期望**：12/12 符合预期，不劣于 2026-10-26 那次。
- **判定**：低于 12/12 就回看措辞；**不把 12/12 当触发率的结论**（口径见 TD 附录 C）。

### T7 · 服务端路由已退休

- **前置**：P2 已部署。
- **步骤**：`GET https://console.skillmasterai.com/gateway/STARTUP.md`（不跟随重定向）；仓库里 `ls gateway`；本地打包后在 jar 里 `unzip -l … | grep gateway/`。
- **期望**：404，`Content-Type` 是 JSON；`gateway/` 目录不存在；jar 里没有任何 `gateway/` 条目。
- **判定**：三项全部对上即通过。

### T8 · 无凭据、无浏览器

- **前置**：清空本机凭据（`skillmaster logout` 或在临时 HOME 下运行），并让 `browserAvailable()` 为假（无 `DISPLAY`、非 darwin 图形会话；本机可用 `SKILLMASTER_NO_BROWSER=1`，若该开关存在，否则在容器里跑）。
- **步骤**：`skillmaster startup`。
- **期望**：退出码 4；stderr 给出两条进入方式（`skillmaster login` / 控制台签发的 API key 放进 `SKILLMASTER_API_KEY`）；stdout 不打印任何正文。
- **判定**：退出码与两句话都对上即通过。

## 环境与数据

- **环境怎么造**：本地构建 CLI（`cd skillmaster-cli && go build ./cmd/skillmaster`），`SKILLMASTER_SERVER=https://console.skillmasterai.com`；服务端侧本地用测试库（`scripts/init-test-db.sh`）跑 `./mvnw -o verify -Dit.test=GatewayRetirementIT`。
- **数据**：技能 `skillmaster/startup` 的现行版本（生产）；T3 需要以官方账号发布一个测试版本。
- **清理**：T3 发布的新版本就是正式内容，不回滚；T8 的清凭据动作要在测完后恢复（或直接用临时 HOME）。

## 结果

实现完成后填写。每条附证据（命令输出片段）。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过（对生产） | `skillmaster startup` 的 stderr 首行「现在的位置：本机候选目录里 33 个 skill，库里 2 条线上、0 份草稿在等上线 —— 下一步：写提示词、验证，然后下线。」（**无步号**）；stdout 是 `skillmaster/startup` 的 `SKILL.md` |
| T2 | 通过（对生产） | `skillmaster skill read skillmaster/startup steps/01-import.md` 退出码 0，首行「# 第一步 · 搬运本机的 skill」 |
| T3 | 未执行 | 需要先发布一个新版本再读旧步骤；规则段发布后进行 |
| T4 | 通过 | `skillmaster help usage` 退出码 2，提示「可用的主题：env」；`skillmaster help` 输出中搜不到 `help usage` |
| T5 | 通过（线上 `@1.0.1`） | `skill cat skillmaster/startup@1.0.1 steps/03-prompt.md` 读回的块与批准的三行逐字一致（含两行标记） |
| T6 | 未执行 | 规则文本与 2026-10-26 那次 12/12 的完全相同，本轮没有复跑 |
| T7 | 部分通过 | `GatewayRetirementIT` 2/2 通过（`/gateway/STARTUP.md`、`/gateway/SKILL.md`、两条索引均 404）；`ls gateway` 不存在；clean build 后的 jar 里无 `BOOT-INF/classes/gateway/`。**生产上的 404 要等服务端部署后再验**（P2 部署前不验） |
| T8 | 部分通过 | 单测 `TestStartupRefusesWithoutACredentialAndNamesBothWays` 通过（退出码 4、两句进入方式）；没有在真实的「无浏览器」机器上跑过 |

## 已知问题

| 编号 | 缺陷 | 状态 |
|---|---|---|
| 1 | v17 的 `help usage` 相关验收（#18 的 `--no-login` 口径）由本版取代，v17 test-plan 需要回写 | **已修复**（2026-10-26：v17 的 README / prd #18 #20 / TD 接口表 / test-plan T04 T18 T20 已改成指向本版） |
| 2 | 现场状态行原来写着「第二步 / 第四到六步」，与技能里的 1–5 编号对不上 | **已修复**（2026-10-26：`state.go` 改成只说阶段） |
| 3 | 第一步与第五步原先引用的 `skills import` / `skills retire` 不是 CLI 里存在的命令（v17 的 #207 / #209） | **已修复**（2026-10-26，用户定：第一步改用 `skill submit <目录>`，第五步改成手动「先核对已上线 → 再 zip 备份 → 才删」；两条命令仍留在 v17 的范围里） |
| 4 | `skillmaster version diff --patch` 的草稿侧内容会滞后（最后一次 `skill write` 的文本不在 diff 里，`skill cat` 才是最新的） | **已复现**（2026-10-26，复核草稿时发现） |

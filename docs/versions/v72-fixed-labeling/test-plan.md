# v72-fixed-labeling · 测试方案

> **最后更新**：2026-11-14
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #A1 规则覆盖 21 个值，默认 `prompt-only` | T1、T2、T5 | 通过 |
| #A2 每条标签都有可核对的证据 | T3 | 通过 |
| #A3 `gpu` / `docker` / `database` 按三口径 | T4、T13 | 夹具通过；金标集 77 条无违例 |
| #A4 导入即写、上线可见 | T6 | 通过（见 §结果） |
| #A5 刷新整组替换 | T7 | 通过（单元级） |
| #A6 幂等 | T8 | 通过（单元级） |
| #A7 生产表只有两列 | T9 | 通过 |
| #A8 V55 可 dry-run、可回滚 | T9 | 通过 |
| #A9 打标失败不拖垮导入 | T10 | 通过（单元级） |
| #A10 对外契约不变 | T11 | 通过 |
| #A11 刷新只处理公域已上线 | T12 | 通过 |
| #A12 金标集准确率达标 | T13 | 通过（见 §结果） |

## 用例

### T1 · 逐值正例

- **前置**：每个 `runtime` 非默认值各一份最小夹具（`SKILL.md` + 文件清单），只含该值的一条强信号。
- **步骤**：对每份夹具跑 `RuleLabeler.classify`。
- **期望**：包含该值的 `definition_id`；不包含与本夹具无关的其他非默认值（允许同一条事实同时命中多个值时，夹具刻意只造一条）。
- **判定**：21 个值全过；词表新增值而规则表没跟上时，一条「词表与规则对齐」的测试先失败。

### T2 · `prompt-only` 与「配置文件型」skill

- **前置**：三份夹具——纯提示词（无代码、无命令）、只带 `Dockerfile`、只带 `docker-compose.yml`。
- **步骤**：分别跑规则。
- **期望**：纯提示词 → 只有 `runtime:prompt-only`；只带 `Dockerfile` / compose → **零标签**（不写 `prompt-only`，也不写 `docker`）。
- **判定**：三份都符合。

### T3 · 证据校验

- **前置**：一份命中了 `python` 的夹具，证据指向 `SKILL.md` 第 N 行。
- **步骤**：① 原样校验；② 把 quote 改一个字符；③ 把 file 改成一个不存在的路径；④ 故意把行号指向空行。
- **期望**：① 通过；②③④ 都不写这条，并计入 `rejected`。
- **判定**：四种结果逐条断言。

### T4 · 三口径正反例

- **前置**：v66 PRD 点名的五组样本的等价夹具（命令、Dockerfile、Verify 步骤、公开数据库 API、云 GPU）。
- **步骤**：跑规则，比较集合。
- **期望**：`orchestra-training-llms-megatron` → `docker` + `gpu`；`jeffallan-devops-engineer` → 无 `docker`；`jeffallan-sql-pro` → `database`；`database-lookup` → `online-service` + `api-key`，无 `database`；`orchestra-modal-serverless-gpu` → 无 `gpu`。
- **判定**：逐条断言；任何一条不符都不算过。

### T5 · 操作系统不限定不标

- **前置**：三份夹具——「支持 macOS / Linux / Windows」、正文提到 macOS、`compatibility: macOS only`。
- **步骤**：跑规则。
- **期望**：前两份零 OS 标签；第三份 `runtime:macos`。
- **判定**：三份都符合。

### T6 · 导入端到端

- **前置**：server 测试库 + ops 测试库；一份可导入的夹具与一个本地 HTTP stub（或真实 `/inner/v1`）。
- **步骤**：触发一次导入 → 检查 ops 的 `label_claim` / `label_entry` → 检查 server 的 `skill_label` → 发布这一版 → `GET /api/v1/skills` / `skill show`。
- **期望**：ops 有这一版的证据；server 有同一组 `(skill_id, definition_id)`；发布后读接口返回这组标签。
- **判定**：三处一致；标签集合与 `RuleLabeler` 的输出逐项相同。

### T7 · 刷新整组替换

- **前置**：同一条 skill 的两个版本：A 命中 `python`，B 命中 `node`。
- **步骤**：先刷新 A，记录最终表；把当前版本切到 B，再刷新。
- **期望**：最终表从 `{python}` 变成 `{node}`；`python` 被删除；ops 的 `label_claim` 两轮都在（历史可查）。
- **判定**：最终表与两轮 claim 都符合。

### T8 · 幂等

- **前置**：同一份内容。
- **步骤**：连续跑两次导入（同一 revision）与两次刷新。
- **期望**：`label_claim` 的集合、证据、`rule_version` 逐字一致；`skill_label` 没有重复行；第二次刷新记 `unchanged`。
- **判定**：两次输出 diff 为空。

### T9 · V55 迁移与 dry-run

- **前置**：一份 V52 形状的测试库（含空 `skill_label` 与七列 `outcome_*`）。
- **步骤**：① 在事务里跑 dry-run 脚本并回滚；② 正式迁移；③ 查表结构；④ 跑回滚脚本。
- **期望**：① 回滚后表形状与数据不变；② 迁移成功；③ `skill_label` 只有两列、`skill_extraction_run` / `label_audit_sample` 不存在、`skill` 没有 `outcome_*` 来源列；④ 回滚后回到 V52 形状。
- **判定**：四条都符合。

### T10 · 故障注入

- **前置**：可让 server 的 `PUT labels` 返回 500 的桩；可让规则抛异常的一个开关。
- **步骤**：① 导入时 PUT 失败；② 导入时规则抛异常。
- **期望**：两次导入都成功创建草稿；`label_entry` 分别是 `push_failed` / `failed`；下一次导入或刷新能补上。
- **判定**：导入结果与 `label_entry` 都对。

### T11 · 对外契约

- **前置**：v66 P0 的读接口与 CLI 输出快照。
- **步骤**：升级后取同一组 skill 的搜索结果、信息页、`skmr skills search` / `skill show` / `skills labels` 输出。
- **期望**：字段名、顺序、空值省略规则不变；差异只有标签从空变成有值。
- **判定**：逐字段比较；出现新字段 / 删字段 / 改形状即失败。

### T12 · 刷新范围

- **前置**：server 测试库里有公开已上线、私有、草稿、已删除四种 skill。
- **步骤**：跑一次刷新。
- **期望**：只有公开已上线的被列出与打标；其他三种没有 `label_entry`，也没有 `skill_label` 行。
- **判定**：逐条检查。

### T13 · 金标集测量（人工）

- **前置**：从**本账号的 SkillMaster 库**（`skmr skills list`，77 条；不取 GitHub，产品负责人的口径是「库里的才是我们要打标的对象」）取每条当前版本：`skmr skill versions` 钉版本 → `skmr skill read <地址@版本> SKILL.md` → `skmr skill ls <地址@版本> -R -l` 取清单。事实快照写到 `artifacts/label-gold/library/`。
- **步骤**：① 用 `SkillFacts` 回放快照并跑规则，输出 `hits.csv`（每条 claim 的证据）、`discrepancies.csv`（宽口径命中但规则没命中）、`review.md`；② 逐条核对 claim 的证据行，判断该值是否真的成立；逐条判断宽口径漏掉的候选是真漏还是正文提及；③ 用核对后的标签集算每个值的 precision / recall，并改规则重跑。
- **期望**：有 ≥10 个正样本的值 precision ≥ 95%；语言值 recall ≥ 90%；工具与凭据 recall ≥ 80%；OS precision ≥ 98%。未达标的值记录在 §结果 并改规则重跑。
- **判定**：结果表逐值列出；低于门槛的值给出原因与下一步。

## 环境与数据

- **OPS 单测**：不需要数据库；规则是纯函数，夹具放在测试资源里。
- **OPS 集成测试**：需要一台 PostgreSQL；CI 在 `ops.yml` 加 `postgres:16` service，用 `OPS_TEST_DB_URL` / `OPS_TEST_DB_USER` / `OPS_TEST_DB_PASSWORD` 指向它；本地用 `skillmaster-postgres` 容器建一个 `skillmaster_test_ops_v72` 库。
- **server 测试**：沿用 `skillmaster-server` 的测试库（`SKILLMASTER_TEST_DB_*`），V55 会在测试启动时应用。
- **金标集**：不进 CI；样本与人工标注放 `artifacts/label-gold/`（gitignore），结论写进本文 §结果。
- **清理**：测试库每次重建；金标集的临时下载文件跑完删除。

## 结果

实现完成后填写。**每条都要有证据**（命令输出、日志片段、截图），不要只写「通过」。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | `RuleLabelerTest.everyNonDefaultValueHasARule`、`multiSelectKeepsValuesThatReallyApply` |
| T2 | 通过 | `RuleLabelerTest.promptOnlyWhenThereIsNoExecutableContent` |
| T3 | 通过 | `RuleLabelerTest.evidenceIsFindableAndMutatingItFailsTheCheck` |
| T4 | 通过 | `RuleLabelerTest.theThreeAmbiguousValuesFollowTheirOwnRule` |
| T5 | 通过 | `RuleLabelerTest.osIsOnlyWrittenWhenTheSkillRestrictsToIt`、`absentEvidenceWritesNothingNotEverything` |
| T6 | 通过 | 本机独立栈（server 8080 + inner 8081、ops 8091，新库 `skillmaster_v72_e2e` / `skillmaster_ops_v72`）导入 `browser-act/skills`：run `500c8e1f-c63d-415f-abd1-c47a20f3ca98` 的 entry `created`；ops `label_claim` = `runtime:browser` / `browser.allowed-tools`，`label_entry` = `labeled`；server `skill_label` 有 `browser-act → runtime:browser`；公开读接口返回 `label=浏览器`，`?label=runtime:browser` 命中、`?label=runtime:python` 不命中 |
| T7 | 通过（单元级） | `LabelRunnerTest.pushesTheClassifiedSetAndRecordsIt` + `InnerLabelApiIT.replacingSetsTheWholeFinalTable`（空集删除也覆盖） |
| T8 | 通过（单元级） | `LabelRunnerTest.anUnchangedSkillIsSkippedEntirely` + `JdbcLabelStoreTest.aSuccessfulEvaluationIsTheSkipKeyAndTheFailureIsNot` |
| T9 | 通过 | 测试上下文应用 V55 成功；`dry-run-v55-label-migration.sh skillmaster_dryrun_v72` 输出「0 行保留、旧列 0 行、ROLLBACK」，回滚后 `skill_label` 仍有 14 列 |
| T10 | 通过（单元级） | `LabelRunnerTest.aRefusedPushIsRecordedAndNotSkippedNextTime`；「导入照样成功」只在导入层保证，没有端到端注入 |
| T11 | 通过 | `SkillLabelApiIT`（7 例）与 `WebDiscoverSemanticIT`（11 例）全绿 |
| T12 | 通过 | `InnerLabelApiIT.theListOnlyReturnsDiscoverableSkills` |
| T13 | 通过 | 77 条库内 skill：132 条 claim，核对后 0 条误标、3 条漏标；总体 precision 100%、recall 97.8%，语言 recall 100%、工具 96.5%、凭据 96.7%、OS precision 100%（逐值见下） |

**全量命令的结果**：`skillmaster-server` 的 `mvn verify` 783 例、0 失败；`skillmaster-ops` 带 `OPS_TEST_DB_URL` 的 `mvn verify` 90 例、0 失败（规则 25、runner 5、store 2、ArchUnit 含规则纯度）。两次都跑在独立的测试库上。

### T6 明细（2026-11-14）

- **栈**：`skillmaster-server` 分支构建跑在 8080，内接口按 `SKILLMASTER_INNER_ADDRESS=127.0.0.1` / `SKILLMASTER_INNER_PORT=8081` 起在 8081；`skillmaster-ops` 跑在 8091。两套全新库：`skillmaster_v72_e2e`（V55 已应用，`skill_label` 只有两列）与 `skillmaster_ops_v72`（V2 已应用）。没有碰本机 `skillmaster` dev 库。
- **来源**：只含一条的 `sync-sources.yml`（`browser-act/skills`，`path: browser-act`）；GitHub 用本机 `gh auth token`，避免共享出口 IP 的 60 次/小时限额。
- **结果**：`POST /inner/v1/jobs/import/runs` → `{"status":"ok","created":1}`；entry 带 `skill_id=01M7DC06RH4J1R8HC2C38RH015`、`draft_id=0TBX7AT0`。ops 里 `label_claim` 一行（`runtime:browser`，规则 `browser.allowed-tools`），`label_entry` 为 `labeled`；server `skill_label` 一行。
- **上线可见**：导入产出的是草稿，公开读接口要求 `current_version_id`。为验证「上线可见」，在**测试库**里把该 skill 置为 public、把草稿设为 current（模拟发布；发布流程本身不属于本版），再用只读 key 调 `/api/v1/skills?scope=public&q=browser-act`：`labels` 为 `runtime/browser`（`需要浏览器`）；`?label=runtime:browser` 返回它，`?label=runtime:python` 返回空。
- **说明**：导入时该 skill 没有 current 版本，按 `InnerLabelUseCase.replaceLabels` 的规则标签立即写入（`applied=true`）；若已有 live 版本而导入的是新草稿，则会记 `deferred`，由刷新在发布后补写——这条分支由 `InnerLabelApiIT.aVersionThatIsNotLiveIsDeferredAndLeavesTheTableAlone` 覆盖。

### T13 明细（2026-11-14，样本 77 条）

**口径**：预测集 = 规则在快照上产出的 claim；金标集 = 逐条核对证据行后判为成立的值（另加 3 条规则漏掉、但 SKILL.md 证据决定性的人工补标）。一个值在一条 skill 上最多算一次。核对人是实现者本人（agent），依据是每条 claim 记录的文件、行号与原行引文；未做第二人复核。

| 值 | TP | FP | FN | precision | recall |
|---|---:|---:|---:|---:|---:|
| `python` | 26 | 0 | 0 | 100% | 100% |
| `node` | 10 | 0 | 0 | 100% | 100% |
| `shell` | 5 | 0 | 0 | 100% | 100% |
| `macos` / `linux` | 1 / 1 | 0 | 0 | 100% | 100% |
| `browser` | 4 | 0 | 0 | 100% | 100% |
| `online-service` | 50 | 0 | 2 | 100% | 96.2% |
| `api-key` | 5 | 0 | 0 | 100% | 100% |
| `account` | 24 | 0 | 1 | 100% | 96.0% |
| `system-permission` | 1 | 0 | 0 | 100% | 100% |
| `prompt-only` | 5 | 0 | 0 | 100% | 100% |
| **合计** | **132** | **0** | **3** | **100%** | **97.8%** |

分组：语言 recall 100%（41/41）；OS precision 100%（2/2）；工具 recall 96.5%（55/57）；凭据 recall 96.7%（29/30）。

**3 条漏标**（规则看不到，已计入 recall，不再加规则）：

| skill | 值 | 为什么漏 |
|---|---|---|
| `lijinzhao/bilibili` | `account` | 需要登录 cookie，但只在正文写成「需要 wbi 签名 + 登录 cookie」，没有登录命令 |
| `lijinzhao/tools` | `online-service` | 索引型 skill，远程调用都在 `amap/`、`zhihu/`、`xiaohongshu/` 子文档里，根 `SKILL.md` 没有命令 |
| `lijinzhao/competitive-analysis` | `online-service` | 每一步都是联网调研，但通篇只有「搜 Reddit/知乎/小红书」，没有可执行命令行 |

**8 条规则没有产出的 skill**：`ai-investing-frameworks`、`code-audit`、`competitive-analysis`、`investment-framework`、`investment-philosophy`、`life`、`tools`、`web-design`。其中 `competitive-analysis` 与 `tools` 已人工补标（见上表）；其余 6 条判断为确实没有可执行事实：`ai-investing-frameworks`、`investment-framework`、`investment-philosophy` 是方法论，`code-audit` 是审计路线索引，`life` 是路由（能力在同库的 `tools`），`web-design` 是流程规范。

**T13 期间按数据改掉的规则（`fixed-r1` → `fixed-r2`）**：

- 命令按 `|` / `&&` / `||` / `;` 拆段，管道后段也参与判断（`curl … | python3 -c`）。
- 读结构化字段：`metadata.requires.bins`（lark 全家的 `lark-cli`）、`metadata.requires.credentials`（`SERPER_API_KEY`）、`metadata.requires.skills`、`platforms`（`[linux, macos]` 就是这两个系统）、`prerequisites.commands`。
- `--as user` 视为需要账号；`X auth login/status`、`X login` 泛化到任意 CLI。
- 远程调用：`curl` / `wget` / `gh` / `az` / `aws` / `gcloud` / `kubectl` / `skmr` / `lark-cli` 与非 localhost URL 记为 `online-service`；`skmr skill read|invoke` 只是加载自己的说明，不算。
- 精度修正（全部被金标集抓到）：`ENV_KEY` 只认全大写变量名，`refresh_token` / `doc_token` 不再是 api-key；裸 `python3` 这种内联词不算步骤；引用外部链接的行里的命令不算步骤；无语言标签的围栏只认已知可执行文件；`.jsx` 与 `starter-components/` 下的 `.js` 不是 Node；`js` 围栏不是 Node；`go install` 不算需要 Go；`assets/` 下的 `.js` 仍然是 Node（`bp-builder` 的 deck 渲染）。
- `prompt-only` 收紧：有子目录说明文件、`metadata.requires.skills`、或正文用 `skill_view` / `skmr skill read` 转交给另一个 skill 的，不算纯提示词。

## 已知问题

本版范围内暂无。实现中发现的缺陷按四个状态记录：**已复现** / **部分复现** / **未验证** / **已修复**。

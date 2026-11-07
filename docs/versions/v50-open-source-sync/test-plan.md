# v50-open-source-sync · 测试方案

> **最后更新**：2026-11-07
> **状态**：第 4 关定稿（2026-11-07 增补导入重命名、来源状态定稿、进度日志与触发窗口、整包每仓库一次、批量发布超时与同步上限），已实现并通过全量 `verify`，结果见 §结果（真机与 CI 启用路径 2026-11-07 在生产上跑过一次；401 与 nginx 规则未验）
> **所属版本**：[`README.md`](README.md)

## 验收映射

| 验收 | 用例 | 类型 |
|---|---|---|
| A1 首次同步产生官方草稿 | T1 | 集成 |
| A2 来源可追溯、发布后 CLI 可读 | T2 | 集成 + CLI 冒烟 |
| A3 同一 revision 幂等 | T3 | 集成 |
| A4 新 revision 产生新草稿、线上不变 | T4 | 集成 |
| A5 非白名单许可被拦 | T5 | 单元 + 集成 |
| A6 未声明许可留空 | T6 | 集成 |
| A7 冲突 / 取消许可的说明 | T7 | 集成 |
| A8 扫描阻断与警告 | T8 | 单元 + 集成 |
| A9 人工发布前不进公域 | T9 | 集成 |
| A10 失败隔离 | T10、T15 | 单元 + 集成 |
| A11 不越界 | T11 | 集成 |
| A12 底座与来源无关 | T12 | 单元 + 静态检查 |
| A13 CI 手动触发 | T14 | 集成 + 工作流语法检查 |
| A14 并发度配置 | T15、T16 | 集成 |
| A15 旧草稿清理 | T17 | 集成 |
| A16 丢弃/撤销不重建 | T18 | 集成 |
| A17 目录展开 | T21 | 集成 |
| A18 跨来源同名 | T23 | 集成 |
| A19 前缀/别名校验 | T15、T23 | 单元 + 集成 |
| A20 地址稳定 | T24 | 集成 |
| A21 只改 name | T23、T24 | 单元 + 集成 |
| A22 同步时快照定稿 | T25 | 集成 |
| A23 仓库元数据每轮每仓库一次 | T25 | 集成 |
| A24 发布与回滚不调 GitHub | T26 | 单元 + 集成 |
| A25 按 skill 目录判断变更 | T27 | 集成 |
| A26 每日任务只补缺口 | T28 | 集成 |
| A1 / A8 的整包安全补充 | T19 | 集成 |
| A1 / A11 的来源冲突与软删除补充 | T20 | 集成 |
| A5 / A6 的许可路径补充 | T22 | 单元 + 集成 |
| 真机跑一条公开仓库 | T13 | 手工 |

## 用例

### T1 · 首次同步

**前置**：测试库有官方命名空间与官方用户（种子）；假 GitHub 返回两条：
① `acme/one-skill` 的仓库根就是一个 skill（`SKILL.md` 在根，`path` 省略）；
② `acme/tools` 的 `skills/ab-testing/` 是一个 skill（`SKILL.md` + `run.py` + `LICENSE` MIT）。
两条都通过 `GET /repos/.../commits` 返回 commit，通过 `GET /codeload/.../tar.gz/<sha>` 返回整包。

**步骤**：配置两条清单（一条省略 `path`，一条给 `path`）→ 调用同步任务一次。

**期望**：官方命名空间出现两个 skill；都存在 `draft` 版本；文件集与上游对应目录一致；`changelog` 各写来源与许可；没有发布、没有公开；未请求 `raw.githubusercontent.com`。

### T2 · 溯源

**前置**：T1 的结果。

**步骤**：读 `skill_source` / `skill_source_snapshot` / `skill_version_license`；以官方身份发布该草稿；调 `GET /api/v1/skills/skillmaster/ab-testing`（作者面/公域面按可见性）与 `skmr skill show`。

**期望**：origin 为 `github`、locator `acme/tools`、path `skills/ab-testing`；快照 `revision=a1b2c3d` 与 `status=ok` 在草稿阶段就已写定，发布前后都不被移动；许可 `MIT`、`status=ok`；发布后 `skill show` 打印 GitHub 来源与 MIT。

### T3 · 同一 revision 幂等

**步骤**：同一清单、同一 revision 再跑一次；再跑第三次。

**期望**：不新增版本；草稿文件与 `changelog` 不变；日志 `result=unchanged`；`SourceService.anchors` + `newestVersionIdAmong` 在下载前就跳过（假 GitHub 的文件请求计数不增加）。

### T4 · 新 revision

**步骤**：假 GitHub 把 commit 改为 `d4e5f6a`、`run.py` 内容变化；跑一次；发布新草稿。

**期望**：出现第二份草稿；第一版（已发布）内容与版本号不变；发布后版本号为 `1.0.1`；新快照 `revision=d4e5f6a`；旧快照仍在。

### T5 · 非白名单许可

**步骤**：分别用 `LICENSE` 为 GPL-3.0、`NOASSERTION`、自定义文本的仓库跑一次。

**期望**：不建草稿；日志 `result=blocked reason=license_not_allowed:<spdx>`；已有 skill 与线上版本不变。

### T6 · 未声明许可

**步骤**：仓库既没有 `LICENSE`，`SKILL.md` 也没有 `license`；跑一次。

**期望**：建草稿；`skill_version_license.status=missing`、`spdx=NOASSERTION`；草稿说明含「未声明」；发布后页面不画许可、CLI 写「未声明」。

### T7 · 冲突与取消

**步骤**：① `LICENSE` MIT、`SKILL.md` 声明 GPL-3.0，跑一次；② 新 commit 同时改动 skill 内容并删除 `LICENSE` 与声明，再跑一次；③ 只改仓库根 `LICENSE`、不改 skill 目录，再跑一次。

**期望**：① 建草稿，许可按文件为 MIT，`detail.declared=GPL-3.0`，草稿说明含「待校准」；② 新草稿许可 `missing`，说明含「已取消」；旧版本许可不变；③ 不触发下载、不建草稿（不做 #15），日志无该条的新结果。

### T8 · 扫描

**步骤**：① 仓库含私钥块 / `.env` / `curl … | sh`；② 仓库只含外部脚本链接与可执行脚本。

**期望**：① 不建草稿，日志 `reason=scan_blocked:<rule>`；② 建草稿，日志含 `warnings=…`（至少一条），草稿说明不受影响。

### T9 · 人工发布闸

**步骤**：T1 的草稿不发布，查公域搜索与「发现」；再以官方身份发布并设为公开，再查。

**期望**：发布前搜不到、发现页没有；发布后可见；同步本身从不改变 `visibility`。

### T10 · 失败隔离

**步骤**：清单两条：第一条假 GitHub 的 commits API 返回 500/超时，或 codeload 返回超时；第二条正常。

**期望**：第一条日志 `result=failed reason=…`；第二条正常建草稿；任务整体不抛到调度器；已有 skill 与线上版本不变。

### T11 · 不越界

**步骤**：清单只有一条；另造一个其他命名空间下的同名 skill；跑一次。

**期望**：只写官方命名空间；其他命名空间的 skill、版本、来源行完全不变；清单外的仓库没有被请求。

### T12 · 底座与来源无关

**步骤**：用 `FakeContentSource`（不联网、kind=`fake`）注册进 registry，跑完整 pipeline；并对 `SyncPipeline` 做静态依赖检查。

**期望**：清单、许可闸、扫描、幂等、写入全部走通；`SyncPipeline` 不 import `GitHubContentSource` 或任何 GitHub 类。

### T13 · 真机

**步骤**：在生产 ECS 上（或本机走代理）配 `SKILLMASTER_GITHUB_TOKEN`，用一条真实公开仓库清单跑一次，例如 `coreyhaines31/marketingskills` 的 `skills/ab-testing`；检查日志与草稿。免 token 的 60 次/小时只作为背景记录，不作为上线配置。

**期望**：建成草稿；日志有 revision；许可结论与仓库 `LICENSE` 一致；不发布；全程只请求 `api.github.com` 与 `codeload.github.com`，不请求 `raw.githubusercontent.com`。

### T14 · CI 手动触发

**步骤**：① 用正确 `X-Sync-Trigger` 调 `POST /internal/sync/run`；② 同时再调一次；③ 用错误 token 调；④ token 为空时启动服务；⑤ 让定时任务与手动触发同时发生；⑥ 跑 `.github/scripts/check-workflows.rb`；⑦ 用四条真实格式的样本日志（`sync run expanding` / `sync run started` / `sync progress done=` / `sync run finished`）跑工作流里的 grep；⑧ 用一份 snake_case 的报告 JSON 跑工作流里的 jq；⑨ 变量分别设成 `abc`、`1`、`360`、`0001` 触发一次（应被 step 拒绝并说明范围，且 job 不在解析期死掉），再设成 `090` 与 `60` 各触发一次（前导零先归一化，两者都应正常等待）。

**期望**：① 返回逐条摘要，跑完与定时任务同样的结果；② 第二次 409 `sync_already_running`；③ 401；④ token 为空时 `POST` 返回 404；⑤ 只有一个运行，另一个 409 或记 `sync run skipped: already_running`；⑥ 工作流语法检查通过，且工作流不包含构建/发布/重启步骤；⑦ 四条 `run` / `progress` 行全部命中，`sync entry=` 不命中；⑧ 起止时间与计数正确、失败条目带名字；⑨ 非数字与越界都由 step 报错并说明范围；前导零先归一化再判范围，`090`（→90）与 `60` 都正常等待。

### T15 · 配置校验

**步骤**：分别用 `enabled=true` 但文件缺失、`kind` 未注册、`path` 含 `..`、`max-repo-bytes` 非正数、`concurrency` 为 0/17 启动；再分别用 `prefix` 含大写/下划线/连续连字符、`prefix: skillmaster`、`prefix` 与 `name` 同时出现、`name` 与 `select` 同时出现、存在但为空的 `prefix` / `name`、未知键 `paths` 启动。

**期望**：启动失败并指出出错条目/键；不进入半启动状态。名称相关的错误在启动时拒绝，不等到联网后才失败。

### T16 · 并发度

**步骤**：清单 10 条（假 GitHub 每条返回不同 skill），`concurrency=5` 跑一次；再 `concurrency=1` 跑一次；第三次让两条清单解析出同一个平台地址。

**期望**：① 10 条全部处理，结果与 `concurrency=1` 一致；② `concurrency=1` 按清单顺序建草稿；③ 重复地址的后一条记为 `blocked` 并记录，不出现两个 skill 或两份重复草稿；原因看时序——前一条已落库则 `origin_conflict`，两条同批被写入阶段的名字集合拦下则 `duplicate_address`；④ 写入仍串行（由 `runBatch` 的结构保证：并行只到结果收集，写入在之后；本版没有钩子做运行时断言）。

### T17 · 旧草稿清理

**步骤**：假 GitHub 先给 commit A，同步出草稿 A；手工改一下草稿 A 的文件与说明；再给 commit B，同步一次。

**期望**：建出草稿 B；草稿 A 变为 `discarded`；该 skill 只剩草稿 B 一份未发布的同步草稿；日志记下被丢弃的 A；手工修改随 A 一起丢弃（这是已接受的代价）。

### T18 · 丢弃/撤销不重建

**步骤**：同步出草稿 A 后，运营丢弃 A；同一 revision 再同步一次；再让上游出新 commit C，同步一次。另造一个已发布版本，运营撤销它，同 revision 再同步。

**期望**：同 revision 不重建草稿（日志 `result=unchanged`）；新 commit C 正常建新草稿；撤销后的同 revision 也不重建。

### T19 · 整包安全

**步骤**：假 codeload 返回四种整包：含 `../evil` 路径、含符号链接、含绝对路径、解压后超过 `max-unpacked-bytes` 的炸弹。

**期望**：四种都整条阻断（`reason=unsafe_archive` 或 `too_large`），不写任何 skill、版本、文件或来源行；其它条目继续。

### T20 · 来源冲突与软删除

**步骤**：① 官方命名空间已有一个同名、origin 为 `official` 的 skill，清单条目解析出同一地址；② 另一个同名 skill 已被软删除。

**期望**：① 预检阶段阻断（`result=blocked`，`reason=origin_conflict`），不新增草稿、不改原 skill；② 捕获 `SkillDeletedException`，记为 `blocked`（`reason=skill_deleted`），任务不崩。

### T21 · 目录展开

**步骤**：① 清单一条 `select: all`，父目录下有 `a`、`b`、`no-skill`（没有 `SKILL.md`）与 `.hidden`；② 清单一条 `select: [a, c]`，其中 `c` 不存在；③ `select: all` 但适配器不支持列子目录；④ `select` 给了但 `path` 为空；⑤ `select: all` 配 `prefix: many`，子目录的 `SKILL.md` 分别叫 `a`、`b`。

**期望**：① 展开出 `a`、`b` 两条，`no-skill` 跳过并记录，`.hidden` 不展开；② 只处理 `a`，`c` 记录不存在并跳过；③ 启动拒绝；④ 启动拒绝；⑤ 两条子条目都继承 `many`，平台名为 `many-a`、`many-b`。

### T22 · 自定义许可路径

**步骤**：① 清单写 `license: legal/LICENSE-MIT`，仓库根另有 `LICENSE`（MIT），显式文件是 Apache-2.0；② 清单写 `license: legal/NOPE`，本轮那棵树里不存在；③ 清单元数据里 `license` 分别指向 `../LICENSE`、`./legal/LICENSE`、`/legal/LICENSE`。

**期望**：① 草稿许可取显式文件（Apache-2.0），不取最近的 MIT；② 不建草稿，`result=blocked reason=license_path_missing`；③ 三种路径都在启动时拒绝。

### T23 · 跨来源同名与可选前缀

**前置**：假 GitHub 有 `alpha/tdd` 与 `beta/tdd` 两个仓库，目录都是 `skills/tdd`，`SKILL.md` 都写 `name: test-driven-development`，正文与附加文件不同。**步骤**：① 两条都不配前缀，跑一次；② `alpha` 不配、`beta` 配 `prefix: beta`，跑一次；③ 两条分别配 `alpha`、`beta` 前缀，跑一次；④ 单 skill 条目配 `name: curated-pdf`，地址空闲，跑一次；⑤ 另一条单 skill 条目也配 `name: curated-pdf`（该地址已被另一个来源的 origin 占用），跑一次；⑥ `select: all` 条目配 `name`，启动；⑦ `prefix` 使组合名超过 64 字符，跑一次。

**期望**：① 第一条建 `test-driven-development`，第二条 `blocked`（`duplicate_address` 或 `origin_conflict`），不静默改名；② 两个 skill 分别为 `test-driven-development` 与 `beta-test-driven-development`，各有草稿；③ 分别为 `alpha-test-driven-development` 与 `beta-test-driven-development`；④ 建立 `curated-pdf`；⑤ 后到的 `blocked`（地址冲突）；⑥ 启动拒绝；⑦ `blocked`（`name_too_long`），不截断、不加哈希。所有成功条目：zip 根与 `SKILL.md` 的 `name` 等于平台名；除该行外正文与其它文件逐字节不变；`skill_source.locator/path` 保留原值。**按上游原名搜索不在本版验收内**：搜索只读 `name`/`title`/`description`/`when_to_use`/标签，不读来源；它随二期来源卡片（[TODO](../../current/TODO.md)「导入来源的署名与介绍」）一起做，见 [§已知问题](#已知问题)。

### T24 · 地址稳定与上游改名

**前置**：`alpha/tdd`（`skills/tdd`，`prefix: alpha`）首次同步得到 `alpha-test-driven-development`；另有一条 `beta/tdd` 先以裸名导入，得到 `test-driven-development`。**步骤**：① 在 alpha 条目上出新 commit，把 `SKILL.md` 的 `name` 改成 `tdd-v2`，正文也变，再同步；② 在①之后再出新 commit，清单把该条 `prefix` 改成 `beta`，再同步；③ **另起一条**（同样 `prefix: alpha`、从 `skills/tdd` 开始，独立于①②的状态），上游把目录从 `skills/tdd` 移到 `skills/tdd-v2`，再同步；再给新路径配 `prefix: gamma`，再同步；④ 裸名条目出新 commit（`name` 不变），再同步；⑤ 给裸名条目加 `prefix: beta` 并出新 commit，再同步。

**期望**：① 不新建 skill，地址仍是 `alpha-test-driven-development`；新草稿的 `SKILL.md` 的 `name` 仍被改写成 `alpha-test-driven-development`，正文随上游变化；② 地址不变，出现一条 `address_kept` 日志/警告，不新建 skill；③ 新目录是新的 `(locator, path)`，按新条目解析，但解析出的地址仍被旧 skill 占用，所以该条 `blocked`（`origin_conflict`）——不接管旧 skill、不建第二个同名 skill；换成 `gamma` 前缀才得到新地址并新建 skill，旧 skill 不被删除或改写；④ 裸名地址保持不变，`SKILL.md` 的 `name` 写回裸名，不新建 skill；⑤ 裸名地址保持不变，出现同样的 `address_kept` 警告，不新建 skill。

### T25 · 同步写全来源快照

**前置**：假来源 `acme/repo-<suffix>` 下三个目录 `skills/a`、`skills/b`、`skills/c`；信号替身为每个 locator 固定返回 `stars=1234`、`forks=56`、`last_commit=2026-11-06`。另一个 locator `acme/other-<suffix>` 配一条。假来源记 `signalsCalls`。

**步骤**：① 一轮同步（4 条都建草稿）；② 再跑一轮；③ 让 `acme/repo-<suffix>` 的信号返回失败，并给 `skills/d` 加一条新目录，再跑一轮；④ 另造一份与上游同内容的手工草稿（发布前没有快照），让所在 locator 的信号失败，再同步一次。

**期望**：① 四个版本的快照 `status=ok`、`written_by_sync` 真、`revision` 等于该目录的 revision、`signals` 含 `stars` / `forks` / `last_commit` 三个 key 且值来自替身；`signalsCalls` 为 2——每个 locator 1 次，不是每个 skill 1 次；② `signalsCalls` 不变（第二轮全部因 revision 未变跳过，整轮不取）；③ `d` 仍建草稿，快照 `pending`、`signals=[]`，结果 `warnings` 含 `signals_pending`；④ replay 到一份没有快照的手工草稿、且信号取不到时，结果仍记 `unchanged`，但同样报 `signals_pending`、快照为 `pending`。

### T26 · 发布与回滚不读上游

**前置**：从 T25 的同步出一份草稿；另有一份手工上传的草稿（`submit.submit`）；再把第二份同步草稿发布成 `1.0.1`，用于回滚。

**步骤**：① 记录同步版本的快照整行；② 发布它；③ 回滚到更早的已发布版本；④ 发布手工草稿。

**期望**：同步版本的快照在两次发布前后逐字段相同（`status` / `revision` / `signals` / `fetched_at`）；手工版本在发布后有一条 `pending` 快照（发布事务写的，不发来源请求）；整个过程中 `SourceService.refresh` 从未被调用（单测 `verify(sources, never()).refresh(...)`）。

### T27 · 按 skill 目录判断变更

**前置**：同一仓库 `acme/repo-<suffix>` 下 `skills/a` 与 `skills/b`，首次都同步在 revision `r1`。

**步骤**：① 首次同步；② 只把 `skills/a` 的 revision 改成 `r2`，`skills/b` 保持 `r1`，再跑一轮。

**期望**：② `a` 新建草稿、快照 revision 为 `r2`；`b` 为 `unchanged`，其快照 revision 仍 `r1`、`fetched_at` 不变；下载计数只加 1（没有下载 `b` 的目录），信号在本轮为 `a` 所在的 locator 取 1 次。

### T28 · 每日任务只补缺口

**前置**：① 一个已发布的手工版本，快照为 `pending`；② 一条 `ok` 的快照，`fetched_at` 由 SQL 改成 2020 年；③ 一条 `error` 的快照。三者都是当前线上版本。

**步骤**：用一个记账的来源适配器构造 `SourceService` 与 `SourceRefreshJob`，直接调用 `refresh()`。

**期望**：只取 `pending` 与 `error` 两条（各 1 次），`ok` 那条不被取、`fetched_at` 不变；两条补完后 `status=ok`。本用例的适配器只回成功；取到失败时落 `error` / `stale`、任务不抛由 `SourceServiceTest` 的单元用例与任务里的 catch 覆盖。

### T29 · 一个仓库每轮只下载一次（[ADR 0163](../../decisions/0163-a-repository-is-downloaded-once-per-run.md)）

**前置**：假 GitHub 的 commits 端点对不同目录回同一个 tip SHA；codeload 端点按请求计数。

**步骤**：① `GitHubContentReader.download` 一次、`filesIn` 分别读两个目录；② `GitHubContentSource` 在同一轮里 `fetch` 两个同仓库、不同目录的条目；③ 让该仓库的下载返回非 200，再用 `GitHubContentSource.fetch` 取它的两个条目；④ 跑一轮 `SyncPipeline`，断言 `beginRun` / `endRun` 各调用一次，并在抛异常的一轮里也各调用一次。

**期望**：① 两个目录都读得出来，codeload 只被请求 1 次；② 只下载 1 次；③ 只尝试 1 次，两条都失败且原因相同；④ 生命周期成对调用，异常路径也不漏。

### T30 · 同步上限与流式超时（[迭代 0003](iterations/0003-批量发布超时与同步上限.md)）

**前置**：无。自动部分绑真实的 `application.yml`（并移除本机环境变量源），不构造属性对象。

**步骤**：① `ScaleLimitsBindingTest` 绑定 `skillmaster.sync`；② 另一个用例在**不加载 YAML** 的环境里绑 `Sync`——只提供字符串字段与并发度，数值上限只能来自 `@DefaultValue`——再与 YAML 绑出的三个数值上限逐项比较；③ 第三个用例把 `spring.mvc` 绑到 Boot 的 `WebMvcProperties` 并读 `getAsync().getRequestTimeout()`。

**期望**：① `max-files` 等于 `IngestLimits.STANDARD_MAX_FILES`、`max-file-bytes` 等于 `IngestLimits.STANDARD_MAX_FILE_BYTES`、`max-total-bytes` 为 100 MiB 且不超过 `max-unpacked-bytes`；② 无 YAML 时的 `max-files` / `max-file-bytes` / `max-total-bytes` 与 YAML 绑定值相同；③ 超时为 5 分钟，不再吃框架默认的 30 秒。

**实机补充（未执行）**：本地起 8080 + 控制台，勾 50 条以上批量上线，确认流不中断、库里条数与界面一致。本地没有那批草稿，本次没有执行。

## 环境与数据

- **数据库**：测试库每次重建 schema；种子自带官方用户 `01M3HTG7GDZGTDME9B136ZVAW4` 与官方命名空间 `skillmaster`（`official=true`）。
- **上游替身**：`GitHubContentReaderTest` 内启动 `com.sun.net.httpserver.HttpServer` 假 GitHub，挂 commits、contents 与 codeload 三个端点；两个 base URL 通过构造参数指向它，不访问真实网络。整包用 `commons-compress` 现场生成；覆盖 200、404、429。**超时与超大整包没有 HTTP 层用例**——炸弹与上限由 `GitHubTarTest` 直接喂归档覆盖。
- **清单**：`SyncSourcesLoaderTest` 用临时 YAML 文件（`file:` 前缀）；`SyncPipelineIT` 与 `SyncNamingIT` 直接注入 `SyncEntry` 列表，共用 `SyncPipelineFixture`（真库接线、内存来源与断言辅助；拆开是因为命名用例把单类推过了行数上限）。两条都覆盖；空前缀 / 前缀 / 显式 `name` 三种命名都由这两层造。
- **重命名夹具**：T23/T24 用假 GitHub 的两个 locator（示意名为 `alpha/tdd`、`beta/tdd`；实际用的是带随机后缀的 `acme/alpha-<suffix>`、`acme/beta-<suffix>`，避免跨类撞名）与同一目录名、同名不同内容；上游改名/移目录用同一假服务器换 commit 与文件路径。纯改写用一个新增的名称改写单测直接喂 `SKILL.md` 字节；不可改写的 frontmatter 用引号键（`"name": x`）或 flow 映射造数——YAML 能解析，行改写改不了。
- **配置**：`application-test.yml` **没有**启用同步（默认 `enabled=false`），所以启动校验只在 `SyncSourcesLoaderTest` 的 loader 层覆盖：版本、未知 key、尾随 `/`、`path` 含 `..`、`select` 缺 path / 非法名字 / 空列表。`SyncConfig` 的缺 `github-token`、`concurrency` 越界、`max-*` 非正数没有单独的 Spring 上下文用例。
- **手动触发**：`SyncTriggerDisabledIT` 用真实 HTTP 栈验证「同步关闭 → 404」；`SyncTriggerGuardTest` 覆盖 token 比较。启用后的 401/409 与 nginx 规则没有自动化用例。
- **信号替身**：`FakeContentSource` 按 locator 记一组固定信号（JSON 字符串）与 `signalsCalls`；`signals` 失败走一个 `failingSignals` 集合，不抛异常。
- **每日任务**：不启动 Spring 调度；用例自己造 `SourceRepository(jdbc)` + `SourceService(…, new SourceRegistry(List.of(记账适配器)), json)` + `SourceRefreshJob`，直接调 `refresh()`。记账适配器的 `kind` 必须与来源行一致（`fake`）。
- **清理**：每个集成测试类用唯一的 locator/skill 名，不依赖跨类清库；假服务器在 `@AfterEach` 关闭；测试内新 blob 由 `BlobGc` 的宽限期容忍。

## 结果

执行时间：2026-11-07（**迭代 0003 后**；此前七次是 646 / 430 / 1076、646 / 428 / 1074、646 / 427 / 1073、645 / 426 / 1071、644 / 426 / 1070、639 / 422 / 1061、628 / 406 / 1034）。命令：`SKILLMASTER_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/skillmaster_test SKILLMASTER_TEST_DB_USER=skillmaster SKILLMASTER_TEST_DB_PASSWORD=skillmaster ./mvnw -B clean verify`（`skillmaster-server/`）。**集成测试（failsafe）646、单测（surefire）431，合计 1077，全部通过**，`BUILD SUCCESS`。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 首次同步 | 通过 | `SyncPipelineIT.aFirstRunCreatesOfficialDraftsAndNothingIsPublished`：两个官方草稿、文件数 2、`current_version_id` 为空 |
| T2 溯源 | 部分通过（未跑 CLI） | 同一用例断言 origin 为 `fake` / locator / path；`publishingASyncedDraftKeepsItsOriginSnapshotAndLicense` 断言发布之后 origin、快照 revision、`status=ok`、信号与许可仍符合预期；发布前后快照整行对比在 `SyncSignalsIT.publishingAndRollingBackLeaveTheSyncedSnapshotAlone`；`aReplayedManualDraftKeepsItsProvenanceAndThePublishKeepsTheOrigin` 覆盖 replay 到手工草稿且声明了另一来源时仍保留同步 origin；CLI 读取未执行 |
| T3 同一 revision 幂等 | 通过 | `theSameRevisionIsSkippedBeforeTheDownload`：第二次 `unchanged`，`fetchCalls` 仍为 1；`aReplayMarksAnExistingSnapshotAsSyncWritten`：replay 命中未标记的旧快照时补上 `written_by_sync` |
| T4 新 revision | 部分通过 | `aNewRevisionProducesANewDraftAndLeavesTheOldOneAlone`：版本数 1→2；`aManualPublishDoesNotHideANewerCommitFromTheSync`：手工发布版本后上游新 commit 仍建草稿；**未断言发布后的版本号与旧版本内容** |
| T5 非白名单许可 | 部分通过（无自定义文本用例） | `aNonPermissiveLicenseBlocksTheDraft` + `LicenseGateTest.aCopyleftFileIsBlocked` / `anUnrecognisedFileIsBlocked`（GPL-3.0 与 `NOASSERTION`）+ `SpdxTest.aThreeClauseBsdIsRecognised` / `aFourClauseBsdIsNotMistakenForTheThreeClauseOne`（BSD-4 不再误判为 BSD-3）+ `aNonTextLicenseDeclarationIsBlockedRatherThanTreatedAsMissing`（列表值 → `blocked`）；自定义文本未单独造数 |
| T6 未声明许可 | 通过 | `aMissingLicenseStillSyncsAndRecordsMissing`：`missing`、`spdx=NOASSERTION`，说明含「未声明」 |
| T7 冲突与取消 | 通过 | `aFileThatDisagreesWithTheDeclarationWinsAndSaysPendingCalibration`、`aLicenseRemovedByANewCommitSaysCancelled`、`aRootLicenseChangeWithNoContentCommitIsNotFetched`、`theNestedLicenseDeclarationWinsLikeTheV49Reader`（`metadata.license` 优先于顶层） |
| T8 扫描 | 通过 | `SecurityScannerTest` 覆盖全部 13 条规则（含可执行二进制阻断、可执行脚本警告、`.env.example` 不误杀、宽泛权限警告）；`aWarningRidesAlongWithoutBlockingTheDraft` 断言警告进报告（日志由同一列表生成，未捕获） |
| T9 人工发布闸 | 部分通过 | 同步后 `current_version_id` 为空；搜索/发现页未在用例中查询 |
| T10 失败隔离 | 通过 | `oneBrokenEntryDoesNotStopTheOthers`（上游失败）+ `aWriteThatTheValidatorRefusesFailsOnlyItsOwnEntry`（写入期 `IngestException`） |
| T11 不越界 | 部分通过 | 所有写入都落在官方命名空间；「另一个命名空间同名 skill 完全不变」未单独造数 |
| T12 底座与来源无关 | 通过 | `SyncPipelineIT` 全程用 `FakeContentSource`（kind=`fake`），`SyncPipeline` 不 import GitHub；registry 收 `List<ContentSource>` |
| T13 真机 | 部分执行（非受控验收） | 2026-11-07 部署 `efffc05`（114 条清单）后用 Actions 触发一次全量导入（展开约 932 个 skill），服务端持续运行；工作流当时仍固定 20 分钟，先被取消而服务端未停——这正是本轮迭代的起因。**401、nginx 规则与按本用例的断言仍未验** |
| T14 CI 手动触发 | 部分通过 | `SyncTriggerDisabledIT`（`enabled=false` → 404）、`SyncTriggerGuardTest`、`check-workflows.rb`（只做 `bash -n`，不检查步骤内容）；2026-11-07 生产上实际触发过一次（20 分钟被取消），同日四次探活都拿到 409 `sync_already_running`（如 run 42352942985、42354712127）。token 为空 → 404 与启用后的 401 未跑，工作流「不含构建/发布/重启」仅人工核对；⑦⑧ 的本机样本验证见 [iterations/0001](iterations/0001-同步进度日志与触发窗口.md)，⑨ 未跑 |
| T15 配置校验 | 部分通过 | `SyncSourcesLoaderTest` 覆盖版本、未知 key（`paths`）、尾随 `/`、`..`、`select` 无 path / 非法名字 / 空列表，以及 `prefix`/`name` 的解析、互斥、`name`+`select` 拒绝、大写/下划线/连续连字符/`skillmaster` 保留、存在但为空的键；`theWhitelistThisRepositoryDeploysLoads` 断言部署文件能通过服务端同一解析器加载、且没有两条指向同一个 `(kind, locator, path)`（2026-11-07 起；此前断言的是两条测试来源的 5 条 `select: all`）；`SyncConfig` 的缺 `github-token` / concurrency / `max-*` / `cron` 未单独测试 |
| T16 并发度 | 部分通过 | `aRunProcessesMoreEntriesThanTheConcurrency`（5 条、concurrency 2，跨批）、`twoEntriesThatResolveToTheSameAddressDoNotCreateTwoSkills`（并发 2、同批 → `duplicate_address`）、`twoSourcesWithTheSameNameNeedAPrefixAndOtherwiseOneIsBlocked`（并发 1、已落库 → `origin_conflict`）；「同一时刻只有一个提交事务」仍由结构保证，无钩子断言 |
| T17 旧草稿清理 | 通过（日志未断言） | `aNewRevisionDiscardsThePreviousUnpublishedSyncDraft`：草稿 1 + 丢弃 1；清理日志已加，测试未捕获日志 |
| T18 丢弃/撤销不重建 | 通过（丢弃） | `aDiscardedRevisionIsNotRebuilt`；锚点改为「同步写的快照里最新版本」，撤销路径未单独造数 |
| T19 整包安全 | 通过 | `GitHubTarTest`：路径穿越、顶层 `..`、绝对路径、保留范围内的符号链接、单文件超限、条目超限、解压总量超限、压缩上限 → `too_large`、兄弟许可不保留、可执行位保留、保留范围外的链接被忽略（真实仓库的 `CLAUDE.md -> AGENTS.md`）；归档层通过，pipeline 的 `unsafe_archive` 分支未单独造数 |
| T20 来源冲突与软删除 | 通过 | `aSyncedNameAlreadyHeldByAnotherOriginIsBlockedBeforeAnythingIsWritten`（origin_conflict）+ `aSoftDeletedSkillIsSkippedWithItsOwnReason`（skill_deleted）；`findByAddress` 忽略软删除行 |
| T21 目录展开 | 部分通过（启动拒绝未覆盖） | `SyncNamingIT.selectAllChildrenInheritThePrefix`（⑤ `many-a` / `many-b`）、`selectAllExpandsEveryChildAndWritesEachOne`、`selectAllSkipsAChildWithoutASkillFile`（`missing_skill_md`）、`aSelectedNameThatDoesNotExistIsSkipped`（`not_found`）、`GitHubContentReaderTest.childrenListsVisibleDirectoriesOnly`（隐藏目录）；`SyncSourcesLoaderTest` 只覆盖格式校验，「适配器不支持列子目录 → 启动拒绝」没有用例 |
| T22 自定义许可路径 | 通过 | `GitHubContentReaderTest.aConfiguredLicensePathWinsOverTheNearestOne`（Apache 胜最近的 MIT）、`aConfiguredLicensePathMissingFromTheCommitFailsTheEntry`（`license_path_missing`）、`GitHubTarTest.anExplicitLicenseOutsideTheAncestryIsKept`、`aSymbolicLinkAtTheExplicitLicensePathStopsTheWholeEntry`（链接在显式许可路径上 → `unsafe_archive`）、`SyncSourcesLoaderTest`（解析、`..`/`./`/绝对路径拒绝）、`SyncPipelineIT.aConfiguredLicensePathThatIsNotInTheCommitBlocksTheEntry`（`blocked`） |
| T23 跨来源同名与可选前缀 | 通过（搜索未验） | `SyncNamingIT.twoSourcesWithTheSameNameNeedAPrefixAndOtherwiseOneIsBlocked`（① 裸名重复：并发 1 时后一条 `blocked` `origin_conflict`；② alpha 裸名 + beta 前缀：两条都建，zip 根与 `SKILL.md` 的 `name` 为 `beta-…`（整文件相等断言：只有 name 行不同）、`run.py` 字节不变、`skill_source.path` 保留、草稿说明含「原名 → 平台名」）、`twoPrefixedSourcesCanBothImportTheSameUpstreamName`（③）、`anExplicitNameIsUsedAndACollisionBlocks`（④⑤）、`aComposedNameThatIsTooLongIsBlockedWithoutTruncation`（⑦ `name_too_long`）、`aSkillFileWhoseNameLineCannotBeRewrittenFailsAsNotRewritable`（引号键 frontmatter 无法改写 → `failed` `name_not_rewritable`，不建草稿）；`SkillNameTransformTest`（整体缩进的根映射可改写、列 0 注释不影响识别、CRLF 行尾保留、flow 映射/块标量/CR-only 行尾拒绝）；`SyncSourcesLoaderTest`（prefix/name 解析、互斥、`name`+`select` 拒绝、非法/保留前缀、空值拒绝）；⑥ 启动拒绝在 loader 层。**按上游原名搜索未验**，见已知问题 |
| T24 地址稳定与上游改名 | 通过 | `aStableAddressSurvivesAnUpstreamNameChangeAndAPrefixChange`（①②：上游改名与改 `prefix` 都不新建 skill、`address_kept` 警告、`SKILL.md` 的 `name` 仍改写成旧地址，且 `run.py` 随上游变成 `print(2)`）、`addingAPrefixAfterABareImportKeepsTheBareAddress`（⑤：裸名导入后加前缀保留裸名并记 `address_kept`）、`aBareSkillRenamedUpstreamKeepsItsAddressAndStillRewritesTheNameLine`（④：无前缀的裸名 skill 被上游改名：地址不变、name 行写回旧地址）、`movingTheDirectoryIsANewEntryAndDoesNotTakeOverTheOldAddress`（③：`prefix: alpha` 导入后，同 prefix 的目录移动 `blocked` `origin_conflict`；换 `gamma` 才新建 skill，旧 skill 版本数与 origin 路径不变） |
| T25 同步写全来源快照 | 通过 | `SyncSignalsIT.aDraftCarriesItsSnapshotAndOneReadServesTheRepository`：四个版本的快照 `status=ok`、`written_by_sync` 真、revision 与信号齐全；`signalsCalls` 为 2（两个 locator）；第二轮全部 `unchanged` 且计数不变；信号失败时 `d` 仍建草稿、快照 `pending`、`warnings` 含 `signals_pending`；`aReplayThatWritesTheFirstSnapshotReportsPendingSignals`（④）replay 到无快照的手工草稿、信号失败时为 `unchanged` + `signals_pending` + `pending`。适配器侧 `GitHubSourceAdapterTest.theSignalsReadIsOneRepositoryCallWithoutCommitsOrLicense` 断言只打 1 次 `/repos/...`、`anUnreadableRepositoryHasNoSignals` 断言取不到时为空 |
| T26 发布与回滚不读上游 | 通过 | `SyncSignalsIT.publishingAndRollingBackLeaveTheSyncedSnapshotAlone`：回滚前后两个同步版本的快照整行不变，手工版本发布后为 `pending`；`PublishNeverRefreshesSourceTest.publishingDoesNotReadTheSource`：发布路径 `verify(sources, never()).refresh(...)` |
| T27 按 skill 目录判断变更 | 通过 | `SyncSignalsIT.onlyTheDirectoryThatChangedIsFetchedAndDrafted`：`a` 新草稿 revision `r2`，`b` 为 `unchanged` 且 revision / `fetched_at` 不变，下载计数只 +1；信号计数两轮累计 2（每轮各 1 次） |
| T28 每日任务只补缺口 | 通过 | `SyncSignalsIT.theDailyJobOnlyFillsGaps`：只取本测试的 `pending` 与 `error` 两条，`ok` 那条的 `fetched_at` 与信号不变，两条补完后为 `ok`；`SourceServiceTest.aSyncedVersionKeepsItsDraftTimeLicenseWhenTheGapIsFilled` 断言补缺口不改同步版本的许可 |
| T29 一个仓库每轮只下载一次 | 通过 | `GitHubContentReaderTest.theSourceDownloadsOneArchiveForEverySkillInTheRepository`（同仓库两个条目 → `codeloadCalls` 为 1）、`aRepositoryThatCannotBeDownloadedIsTriedOnceAndFailsEverySkillInIt`（403 仓库两个条目 → 只打 1 次、两条都 `rate_limited`）、`oneDownloadReadsTheDirectoryAndItsNearestLicense`；`SyncPipelineIT.aRunOpensAndClosesItsSourcesEvenWhenItThrows`（第二轮在展开阶段抛 `IllegalStateException`，`runsStarted` / `runsEnded` 都为 2） |
| T30 同步上限与流式超时 | 自动通过；实机未执行 | `ScaleLimitsBindingTest` 三条：绑 YAML 断言同步的文件数与单文件上限等于 `IngestLimits` 常量、总量 100 MiB 且不超过 `max-unpacked-bytes`；不加载 YAML 时绑出的三个数值上限与 YAML 绑定值相同；`spring.mvc` 绑到 Boot 的 `WebMvcProperties` 后 `requestTimeout` 为 5 分钟。**50+ 条批量发布的实机补充未执行**——本地没有那批草稿，流不再被截断这一点没有实机证据 |

## 已知问题

- **T13、T14 的启用路径未在 CI 中跑。** T13 只在生产上非受控地跑过一次（2026-11-07，见 §结果）；T14 的 401 需要一个带 `skillmaster.sync.enabled=true` 和触发 token 的 Spring 测试上下文，本版没有为它单独启动上下文；409 已在同日的生产探活中观察到，nginx 规则只人工核对。
- **T4 的发布版本号未断言。** 版本数 1→2 与发布后的来源保留有测试；「发布后版本号 1.0.1」没有用例。
- **T15 的 `SyncConfig` 启动校验未覆盖。** loader 层覆盖了格式错误；缺 `github-token`、`concurrency` 越界、`max-*` 非正数、`cron` 不合法都没有单独的 Spring 上下文用例。默认 cron（值见 `config.md`）也只在配置里声明，没有断言下一次触发的测试。
- **并发的一条硬断言缺失。** 批处理与串行写入由实现结构保证（写入在 `runBatch` 的并行结果收集之后），但测试没有钩子去证明「同一时刻只有一个提交事务」。
- **撤销路径未单独造数（丢弃已覆盖）。** `aDiscardedRevisionIsNotRebuilt` 覆盖丢弃；撤销的行仍在表中，锚点取「同步写的快照里最新版本」，逻辑相同但没有单独用例。
- **改 `license:` 对已同步 revision 不生效。** 锚点相同就在下载前跳过，已有草稿的许可不会被改写；要纠正需等上游新 commit 或人工处理。这不是测试缺口，是当前语义。
- **T21 的「适配器不支持列子目录 → 启动拒绝」没有用例。** `SyncConfig` 里已有该校验，测试没有为它单独启动 Spring 上下文。
- **快照的 `error` / `stale` 状态没有端到端用例。** 同步只在取不到信号时写 `pending`，`error` / `stale` 仍由每日任务的刷新产生；T28 覆盖了「只补缺口」的选择，刷新失败后的状态转换由 `SourceServiceTest` 的单元用例覆盖。
- **T5 的自定义许可文本没有用例。** GPL-3.0 与 `NOASSERTION` 已覆盖；自定义文本按识别结果应归为 `NOASSERTION` 阻断，但没有造数。
- **重命名后按上游原名搜索命不中。** 搜索只匹配 `skill.name` / `title` / `description` / `when_to_use` / 标签，不读 `skill_source`；带前缀的导入只有在描述里写了原名才搜得到。**已复现**（`SqlFilters` 的检索子句不含 `skill_source`）。这是本版的有意推迟：按上游原名检索与来源卡片一起放到二期（[TODO](../../current/TODO.md)「导入来源的署名与介绍」），本版不验。
- **上游原名不落库。** `skill_source` 只有 `kind` / `locator` / `path`，改名后的原名只出现在草稿说明的 `原名 → 平台名` 里，且说明超过 200 字符时会被省去。二期要按原名检索或展示，需要新增来源字段并从 revision 回填——不是「数据已经留着」。**已复现**（`SourceRepository.putOrigin` 无 name 列）。
- **改 `prefix` 但上游没有新 commit 时没有 `address_kept`。** 条目在下载前就短路成 `unchanged`，而算这次配置的名字需要上游 `SKILL.md` 的 `name`；配置被忽略的信号要等下一次内容 commit 才出现。这是保住「下载前跳过」的代价，**已接受**。
- **目录移动不会自动接管。** 上游把目录改名后，新 `path` 算出的地址通常仍被旧 skill 占用，条目会一直 `blocked`（`origin_conflict`），直到给新条目配不同 `prefix` 或上游同时改 `name`。这是地址唯一性的直接后果，不是缺陷。
- **信号取不到的同步草稿要等发布才补齐。** 每日任务的 `pending()` 只扫当前线上版本；草稿的 `pending` 快照在它发布后的下一个周期才补。这是 A26 与 [ADR 0161](../../decisions/0161-imported-skill-state-is-fixed-when-the-draft-is-created.md) 的有意口径，不是缺陷。
- **v49 的 `declared-only` / `missing` 许可每日重试不再存在。** 许可在草稿阶段（同步）或首次刷新（手工上传）时落定，之后不随 HEAD 变化；每日任务补缺口时也不改同步版本的许可。**已接受**（ADR 0161：刷新读的是 HEAD，允许它改写会让上游后来的改许可污染老版本）。


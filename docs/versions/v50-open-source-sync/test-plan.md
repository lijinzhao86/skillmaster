# v50-open-source-sync · 测试方案

> **最后更新**：2026-11-06
> **状态**：第 4 关定稿，已实现；结果见 §结果（真机与 CI 启用路径未跑）
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

**期望**：origin 为 `github`、locator `acme/tools`、path `skills/ab-testing`；快照 `revision=a1b2c3d` 在发布前后不被移动（`status` 由发布后的刷新推进为 `ok` / `error` / `stale`）；许可 `MIT`、`status=ok`；发布后 `skill show` 打印 GitHub 来源与 MIT。

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

**步骤**：① 用正确 `X-Sync-Trigger` 调 `POST /internal/sync/run`；② 同时再调一次；③ 用错误 token 调；④ token 为空时启动服务；⑤ 让定时任务与手动触发同时发生；⑥ 跑 `.github/scripts/check-workflows.rb`。

**期望**：① 返回逐条摘要，跑完与定时任务同样的结果；② 第二次 409 `sync_already_running`；③ 401；④ token 为空时 `POST` 返回 404；⑤ 只有一个运行，另一个 409 或记 `sync run skipped: already_running`；⑥ 工作流语法检查通过，且工作流不包含构建/发布/重启步骤。

### T15 · 配置校验

**步骤**：分别用 `enabled=true` 但文件缺失、`kind` 未注册、`path` 含 `..`、`max-repo-bytes` 非正数、`concurrency` 为 0/17 启动。

**期望**：启动失败并指出出错条目/键；不进入半启动状态。

### T16 · 并发度

**步骤**：清单 10 条（假 GitHub 每条返回不同 skill），`concurrency=5` 跑一次；再 `concurrency=1` 跑一次；第三次让两条清单解析出同一个平台地址。

**期望**：① 10 条全部处理，结果与 `concurrency=1` 一致；② `concurrency=1` 按清单顺序建草稿；③ 重复地址的后一条记为 `blocked`（`duplicate_address`）并记录，不出现两个 skill 或两份重复草稿；④ 写入仍串行（由 `runBatch` 的结构保证：并行只到结果收集，写入在之后；本版没有钩子做运行时断言）。

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

**步骤**：① 清单一条 `select: all`，父目录下有 `a`、`b`、`no-skill`（没有 `SKILL.md`）与 `.hidden`；② 清单一条 `select: [a, c]`，其中 `c` 不存在；③ `select: all` 但适配器不支持列子目录；④ `select` 给了但 `path` 为空。

**期望**：① 展开出 `a`、`b` 两条，`no-skill` 跳过并记录，`.hidden` 不展开；② 只处理 `a`，`c` 记录不存在并跳过；③ 启动拒绝；④ 启动拒绝。

### T22 · 自定义许可路径

**步骤**：① 清单写 `license: legal/LICENSE-MIT`，仓库根另有 `LICENSE`（MIT），显式文件是 Apache-2.0；② 清单写 `license: legal/NOPE`，该 commit 里不存在；③ 清单元数据里 `license` 分别指向 `../LICENSE`、`./legal/LICENSE`、`/legal/LICENSE`。

**期望**：① 草稿许可取显式文件（Apache-2.0），不取最近的 MIT；② 不建草稿，`result=blocked reason=license_path_missing`；③ 三种路径都在启动时拒绝。

## 环境与数据

- **数据库**：测试库每次重建 schema；种子自带官方用户 `01M3HTG7GDZGTDME9B136ZVAW4` 与官方命名空间 `skillmaster`（`official=true`）。
- **上游替身**：`GitHubContentReaderTest` 内启动 `com.sun.net.httpserver.HttpServer` 假 GitHub，挂 commits、contents 与 codeload 三个端点；两个 base URL 通过构造参数指向它，不访问真实网络。整包用 `commons-compress` 现场生成；覆盖 200、404、429。**超时与超大整包没有 HTTP 层用例**——炸弹与上限由 `GitHubTarTest` 直接喂归档覆盖。
- **清单**：`SyncSourcesLoaderTest` 用临时 YAML 文件（`file:` 前缀）；`SyncPipelineIT` 直接注入 `SyncEntry` 列表。两条都覆盖。
- **配置**：`application-test.yml` **没有**启用同步（默认 `enabled=false`），所以启动校验只在 `SyncSourcesLoaderTest` 的 loader 层覆盖：版本、未知 key、尾随 `/`、`path` 含 `..`、`select` 缺 path / 非法名字 / 空列表。`SyncConfig` 的缺 `github-token`、`concurrency` 越界、`max-*` 非正数没有单独的 Spring 上下文用例。
- **手动触发**：`SyncTriggerDisabledIT` 用真实 HTTP 栈验证「同步关闭 → 404」；`SyncTriggerGuardTest` 覆盖 token 比较。启用后的 401/409 与 nginx 规则没有自动化用例。
- **清理**：每个集成测试类用唯一的 locator/skill 名，不依赖跨类清库；假服务器在 `@AfterEach` 关闭；测试内新 blob 由 `BlobGc` 的宽限期容忍。

## 结果

执行时间：2026-11-06（最近一次改变代码的审计轮次后，干净 `target/` 上重跑）。命令：`SKILLMASTER_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/skillmaster_test SKILLMASTER_TEST_DB_USER=skillmaster SKILLMASTER_TEST_DB_PASSWORD=skillmaster ./mvnw -B clean verify`（`skillmaster-server/`）。**集成测试（failsafe）628、单测（surefire）406，合计 1034，全部通过**，`BUILD SUCCESS`。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 首次同步 | 通过 | `SyncPipelineIT.aFirstRunCreatesOfficialDraftsAndNothingIsPublished`：两个官方草稿、文件数 2、`current_version_id` 为空 |
| T2 溯源 | 部分通过（未跑 CLI） | 同一用例断言 origin 为 `fake` / locator / path；`publishingASyncedDraftKeepsItsOriginSnapshotAndLicense` 覆盖发布后 origin 与快照 revision / 许可不变（**快照 status 的发布后推进未断言**——替身 origin 在真实 registry 里没有适配器，刷新被吞掉，行停在 `pending`）；`aReplayedManualDraftKeepsItsProvenanceAndThePublishKeepsTheOrigin` 覆盖 replay 到手工草稿且声明了另一来源时仍保留同步 origin；CLI 读取未执行 |
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
| T13 真机 | 未执行 | 需要生产 ECS 与真实 GitHub token；设计前已做只读连通性 PoC，见 TD |
| T14 CI 手动触发 | 部分通过 | `SyncTriggerDisabledIT`（`enabled=false` → 404）、`SyncTriggerGuardTest`、`check-workflows.rb`（只做 `bash -n`，不检查步骤内容）；token 为空 → 404 与启用后的 401/409 未跑，工作流「不含构建/发布/重启」仅人工核对，nginx 规则仅人工核对 |
| T15 配置校验 | 部分通过 | `SyncSourcesLoaderTest` 覆盖版本、未知 key、尾随 `/`、`..`、`select` 无 path / 非法名字 / 空列表；`theWhitelistThisRepositoryDeploysLoads` 断言部署文件 5 条且全部 `select: all`；`SyncConfig` 的缺 `github-token` / concurrency / `max-*` / `cron` 未单独测试 |
| T16 并发度 | 部分通过 | `aRunProcessesMoreEntriesThanTheConcurrency`（5 条、concurrency 2，跨批）、`twoEntriesThatResolveToTheSameAddressDoNotCreateTwoSkills`（重复地址）；「同一时刻只有一个提交事务」仍由结构保证，无钩子断言 |
| T17 旧草稿清理 | 通过（日志未断言） | `aNewRevisionDiscardsThePreviousUnpublishedSyncDraft`：草稿 1 + 丢弃 1；清理日志已加，测试未捕获日志 |
| T18 丢弃/撤销不重建 | 通过（丢弃） | `aDiscardedRevisionIsNotRebuilt`；锚点改为「同步写的快照里最新版本」，撤销路径未单独造数 |
| T19 整包安全 | 通过 | `GitHubTarTest`：路径穿越、顶层 `..`、绝对路径、保留范围内的符号链接、单文件超限、条目超限、解压总量超限、压缩上限 → `too_large`、兄弟许可不保留、可执行位保留、保留范围外的链接被忽略（真实仓库的 `CLAUDE.md -> AGENTS.md`）；归档层通过，pipeline 的 `unsafe_archive` 分支未单独造数 |
| T20 来源冲突与软删除 | 通过 | `aSyncedNameAlreadyHeldByAnotherOriginIsBlockedBeforeAnythingIsWritten`（origin_conflict）+ `aSoftDeletedSkillIsSkippedWithItsOwnReason`（skill_deleted）；`findByAddress` 忽略软删除行 |
| T21 目录展开 | 部分通过（启动拒绝未覆盖） | `selectAllExpandsEveryChildAndWritesEachOne`、`selectAllSkipsAChildWithoutASkillFile`（`missing_skill_md`）、`aSelectedNameThatDoesNotExistIsSkipped`（`not_found`）、`GitHubContentReaderTest.childrenListsVisibleDirectoriesOnly`（隐藏目录）；`SyncSourcesLoaderTest` 只覆盖格式校验，「适配器不支持列子目录 → 启动拒绝」没有用例 |
| T22 自定义许可路径 | 通过 | `GitHubContentReaderTest.aConfiguredLicensePathWinsOverTheNearestOne`（Apache 胜最近的 MIT）、`aConfiguredLicensePathMissingFromTheCommitFailsTheEntry`（`license_path_missing`）、`GitHubTarTest.anExplicitLicenseOutsideTheAncestryIsKept`、`aSymbolicLinkAtTheExplicitLicensePathStopsTheWholeEntry`（链接在显式许可路径上 → `unsafe_archive`）、`SyncSourcesLoaderTest`（解析、`..`/`./`/绝对路径拒绝）、`SyncPipelineIT.aConfiguredLicensePathThatIsNotInTheCommitBlocksTheEntry`（`blocked`） |

## 已知问题

- **T13、T14 的启用路径未在 CI 中跑。** T13 需要生产 ECS；T14 的 401/409 需要一个带 `skillmaster.sync.enabled=true` 和触发 token 的 Spring 测试上下文，本版没有为它单独启动上下文；nginx 规则只人工核对。
- **T4 的发布版本号未断言。** 版本数 1→2 与发布后的来源保留有测试；「发布后版本号 1.0.1」没有用例。
- **T15 的 `SyncConfig` 启动校验未覆盖。** loader 层覆盖了格式错误；缺 `github-token`、`concurrency` 越界、`max-*` 非正数、`cron` 不合法都没有单独的 Spring 上下文用例。默认 cron（值见 `config.md`）也只在配置里声明，没有断言下一次触发的测试。
- **并发的一条硬断言缺失。** 批处理与串行写入由实现结构保证（写入在 `runBatch` 的并行结果收集之后），但测试没有钩子去证明「同一时刻只有一个提交事务」。
- **撤销路径未单独造数（丢弃已覆盖）。** `aDiscardedRevisionIsNotRebuilt` 覆盖丢弃；撤销的行仍在表中，锚点取「同步写的快照里最新版本」，逻辑相同但没有单独用例。
- **改 `license:` 对已同步 revision 不生效。** 锚点相同就在下载前跳过，已有草稿的许可不会被改写；要纠正需等上游新 commit 或人工处理。这不是测试缺口，是当前语义。
- **T21 的「适配器不支持列子目录 → 启动拒绝」没有用例。** `SyncConfig` 里已有该校验，测试没有为它单独启动 Spring 上下文。
- **T2 的「发布后快照 status 推进」未断言。** 用例的 origin 是 `kind=fake`，真实 `SourceRegistry` 里没有它的适配器，发布后的刷新被静默跳过，快照停在 `pending`；状态推进（`ok` / `error` / `stale`）没有用例。
- **T5 的自定义许可文本没有用例。** GPL-3.0 与 `NOASSERTION` 已覆盖；自定义文本按识别结果应归为 `NOASSERTION` 阻断，但没有造数。


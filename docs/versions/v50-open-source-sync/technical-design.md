# v50-open-source-sync · 技术设计

> **最后更新**：2026-11-07
> **状态**：第 3 关定稿（2026-11-07 增补导入重命名、增补来源状态定稿）；前两个增补已实现，来源状态定稿已实现、全量 `verify` 已通过；真机与 CI 触发未跑；结果见 [`test-plan.md`](test-plan.md) §结果
> **所属版本**：[`README.md`](README.md)

## 架构

相对 [`docs/current/`](../../current/README.md) 的差异：**不新增用户面 HTTP 路由、不新增表、不新增 Maven 模块**。新增一个 `usecase/sync/` 包（编排与纯组件，含可选的 `SkillNameTransform`）、一个 `config/SyncConfig`（装配）、一个 `@Scheduled` 任务、一个只走本机回环的内部触发端点与一条 GitHub Actions 工作流；M3 / M4 / M7 / M12 各加若干公有方法（见 §接口 的方法表）。模块边界与依赖方向不变（[ADR 0157](../../decisions/0157-sync-is-a-source-neutral-pipeline.md)）；任务留在 server 进程内（[ADR 0158](../../decisions/0158-sync-runs-inside-the-server.md)）；手动触发只走 CI（[ADR 0159](../../decisions/0159-manual-sync-is-triggered-by-ci.md)）；导入重命名见 [ADR 0160](../../decisions/0160-imports-are-renamed-by-an-optional-source-prefix.md)；来源状态在草稿阶段定稿、发布与回滚不再读 GitHub 见 [ADR 0161](../../decisions/0161-imported-skill-state-is-fixed-when-the-draft-is-created.md)。

### 三层

```text
config/SyncConfig
  ├─ 读 skillmaster.sync.* 与 sources 文件
  ├─ 建 registry: kind -> ContentSource
  ├─ 建 LicenseGate / SecurityScanner
  └─ 建 SyncDraftWriter + SyncJob

usecase/sync/
  ├─ SyncPipeline        来源无关的编排（清单 → 逐条执行）
  ├─ ContentSource       适配器接口（kind / revision / fetch / signals）
  ├─ GitHubContentSource 第一根管子，委托 M12 的 GitHubContentReader
  ├─ LicenseGate         许可判定（白名单 / 未声明 / 冲突）
  ├─ SecurityScanner     静态规则（阻断 / 警告）
  ├─ SkillNameTransform  可选前缀/别名的平台名计算与 SKILL.md name 改写（纯函数）
  ├─ SyncDraftWriter     唯一接触 domain 的层
  └─ SyncJob             @Scheduled(cron)：按配置的 cron（默认见 config.md），北京时间，启动不跑

api/SyncTriggerController   POST /internal/sync/run：只做 token 校验与调用
.github/workflows/sync-trigger.yml  workflow_dispatch → SSH → 本机回环端点
```

- **依赖方向**：`config → usecase → modules`；模块之间仍只通过公有类型。没有新模块，`ModuleMap` 不动。
- **pipeline 不 import 具体来源**：只认 `ContentSource`；新增来源 = 一个实现 + 注册，不改 pipeline（[ADR 0157](../../decisions/0157-sync-is-a-source-neutral-pipeline.md)）。
- **写入器是唯一写 domain 的层**：它构建内存 zip，调用现有提交用例；适配器不碰 skill / version / blob。pipeline 读取 M4 / M5 / M7 / M12 的公有只读方法（官方命名空间、`SKILL.md` 解析、锚点、地址预检），但它自己不写任何模块的表。

### 并发与调度

- **一次运行**：定时任务与手动触发共用一个 `SyncRunner` 的 guard；已在跑时手动触发返回 409，定时任务记一条 `sync run skipped: already_running`。guard 在 runner 里，不在控制器里——否则两条入口会各判各的。
- **并发度可配置**：`skillmaster.sync.concurrency`（1–16，默认值见 [`docs/current/config.md`](../../current/config.md)）控制**同时拉取、解析、扫描**的条目数。条目按批处理：一批最多 `concurrency` 条并行做网络与纯计算，做完再**串行写入**，然后处理下一批。
- **写入必须串行，这不是保守**：每次提交走 `underExclusiveWrite`，它对 `version_file` 取 `SHARE ROW EXCLUSIVE` **表锁**；并行写入只会在同一把锁上排队，还多占连接。串行写入让失败归因与日志保持一条一条可读。
- **内存上界**：一批最多 `concurrency × (max-total-bytes + max-unpacked-bytes)`（两个数字都见 `config.md`）；串行写入时 `SyncDraftWriter` 再复制一份文件集做导入 zip，峰值再加 `1 × max-total-bytes`。整包里只保留配置目录的文件、该目录祖先链上的许可文件，以及清单 `license:` 指定的那一个文件，所以一个满是 vendored LICENSE 的仓库不会把 licence 全读进内存；许可文件（祖先链上的与清单指定的）都不计入 `max-total-bytes`，但每读一份都计入 `max-unpacked-bytes`，所以解压预算同时是保留字节的硬上界。批处理再给下载与扫描加一层背压。
- **地址冲突**：同一轮里两条清单解析出同一个平台地址时，后一条记为 `blocked` 并记录——原因看时序：地址已被**更早批次（或上一轮）**的写入占住时，后一条在预检阶段撞到别的 origin，记 `origin_conflict`；两条在同一批里都被预处理、由写入阶段的名字集合拦下时记 `duplicate_address`。两者都是阻断，不写第二个 skill；批内先收集名字再写入，避免并发把同一地址写成两个 skill。**没有 origin 的同名 skill 不阻断**：官方命名空间里这类行通常是运营手工建的同名草稿，提交路径会按同摘要 replay 或给它加版本，这是既有语义；已发布的 skill 都有 origin（`official` 或外部来源），不同来源由预检拦下，同源复用见「平台名解析」第 1 步。
- **调度线程池**：Spring 默认单线程，同步与现有两个 `@Scheduled` 任务共享。`application.yml` 里单独设了 `spring.task.scheduling.pool.size`（值见 `config.md`），让来源刷新不被一次长同步堵住；它不是条目并发度。
- **CI 层**：`sync-trigger.yml` 加 `concurrency: {group: sync-trigger, cancel-in-progress: false}`，避免重复点；服务端 guard 仍是最终防线。
- **网络超时**：连接 3s、commits API 读取 10s、codeload 60s；单条超时记为 `failed`，不拖住整批。

### 关键选择：复用提交路径，不复制 domain

同步**不新写一套「建 skill + 建版本 + 存文件」的代码**，而是把上游目录打成一个内存 zip，调用现有 `SubmitSkillUseCase.submit(zip, draftName, note, releaseType, subject)`：

- 内容摘要、归一化、上传上限、manifest、版本状态机、审计行全部沿用现有路径，不会出现第二套摘要或第二条状态机。
- 目标命名空间来自 `subject.userId()` 的 **个人命名空间**。同步用官方服务身份：`AuthenticatedSubject.ofService(official.ownerUserId())`（M3 新增的命名工厂），其个人命名空间就是官方命名空间。这与运营今天登录官方账号发布走的是同一条路，只是没有登录。
- **幂等由现有 `VersionSubmitter.record` 决定**：非草稿行持有同一摘要、或同一提交者的草稿持有同一摘要，都算 replay，不新建版本。同步重跑因此不会重复造草稿。
- **重试套在 `DraftRecorder.record(...)` 里**：主键/版本号撞车要靠外层新事务重试，这是现有控制器对提交用例的包法；同步不走控制器，必须自己套同一层。
- **版本行的 `source` 记 `sync`**：给 `SubmitSkillUseCase.submit` 加一个带 `source` 参数的重载，现有五参方法以 `"zip"` 委托；同步传 `"sync"`，让版本记录能区分手工上传与同步。
- **地址 = 平台名**：清单配了 `prefix` 就是 `<prefix>-<上游 name>`，配了单 skill `name` 就是它，否则就是上游 `name`（[ADR 0160](../../decisions/0160-imports-are-renamed-by-an-optional-source-prefix.md)）。两条清单落到同一地址、或地址已被另一个来源占用时阻断并记录（`result=blocked`）（[ADR 0156](../../decisions/0156-sync-writes-as-the-official-namespace.md)）。

### 数据流

```text
源码清单 YAML
  └─ 展开：ALL 调 adapter.children 列直接子目录；NAMES 直接生成；ONE 原样
       └─ 每条 SyncEntry(kind, locator, path, ONE, [])
       ├─ adapter.revision(entry)           1 次请求：该目录最近一次 commit
       ├─ M12.anchors(namespace, kind, locator, path) + M7.newestVersionIdAmong(skillId, ids)
       │    └─ revision 相同 → 跳过（不下载整包）
       ├─ adapter.fetch(entry)              按该 commit 拉 codeload 整包；只解出 path/；许可从同一棵树取
       ├─ SkillDocument.parse(SKILL.md)      name / title / frontmatter / 许可声明
       ├─ 平台名解析                         已有 (kind, locator, path) 的地址 → 复用；否则 prefix-name / name / 上游 name
       ├─ 地址预检                           官方命名空间下同名 skill 的 origin 与本条不同 → 阻断
       ├─ LicenseGate.decide(file, declared) 非白名单 → 阻断
       ├─ SecurityScanner.scan(...)          阻断项 → 阻断
       ├─ adapter.signals(entry)             每轮每仓库 1 次 GET /repos/{locator}；取不到 → 快照留 pending
       ├─ SyncDraftWriter.write(...)
       │    ├─ 内存 zip（单一根 = 平台名；与上游 name 不同时改写 SKILL.md 的 name）
       │    ├─ DraftRecorder.record:
       │    │    ├─ 事务内再查一次 origin（防竞态）
       │    │    ├─ SubmitSkillUseCase.submit(...)（官方服务身份）
       │    │    └─ M12.recordSynced(...)（同一事务；带 signals 时快照 status=ok）
       │    └─ 提交成功后丢弃旧的未发布同步草稿
       └─ 日志一行（result / reason / revision）
```

**目录展开**：`Mode.ALL` 先调一次 `adapter.children` 列出 `path` 下的直接子目录（过滤 `.` 开头的隐藏目录），每个子目录生成一条 `SyncEntry(path/name, ONE)`；`Mode.NAMES` 不联网，直接生成；展开发生在切批之前，所以并发与日志仍按「一个 skill 一条」计算。没有 `SKILL.md` 的子目录在后续 fetch/parse 时跳过并记录。

**平台名解析（[ADR 0160](../../decisions/0160-imports-are-renamed-by-an-optional-source-prefix.md)）**：

1. 先按 `(kind, locator, path)` 查 M12 已分配的地址；查到就用它，第 3 步（校验名字）不做。第 2 步仍算一次，只为和已分配地址比较：不同就记一条 `address_kept` 警告（不采用算出的名字、不阻断），这是「上游改 `name` 不新建 skill」与「改 `prefix` 不静默改名」的实现点。这条查询只看 origin 的 kind/locator/path，所以手工导入并声明了同源 `metadata.source` 的 skill 也会被复用（它的地址可能不是本版规则算出来的）；复用不校验名字。上游 revision 未变时条目在下载前就短路成 `unchanged`，此时不算名字、也就没有 `address_kept`。警告随结果返回，即该条随后被许可闸、扫描或改写预检阻断时也照样带着。
2. 没查到，配了 `name` 的用 `name`；配了 `prefix` 的用 `<prefix>-<上游 SKILL.md 的 name>`；都没配用上游 `name`。
3. 用 `SkillDocument` 的同一套名称规则 + Agent Skills 的 `^[a-z0-9]+(?:-[a-z0-9]+)*$` 校验组合结果；超长、非法或保留（`skillmaster` / `skillmaster-` 开头）→ `blocked`（`name_too_long` / `invalid_name` / `name_reserved`），不截断。只在首次分配时校验。
4. 地址预检用这个平台名；平台名与上游 `name` 不同时（配了前缀/别名，或地址已分配而上游改名），`SkillNameTransform` 只替换 frontmatter 顶层的 `name` 行并换 zip 根，正文与其它文件原样。改写先在预检里试一次：文档能解析但行改写改不了（块标量、锚点、引号键、flow 映射、CR-only 行尾）→ `failed`（`name_not_rewritable`）；解析本身就失败 → `failed`（`invalid_skill_md`）。都不建草稿——不把内容缺陷留到写入事务里变成 `write_failed`。

**三个顺序上的硬约束**：

- **来源预检必须在提交之前**。先提交再写来源，会把草稿加到别人的同名 skill 上，然后 `recordSynced` 才发现 origin 冲突——草稿已经落库。
- **提交与来源写入必须同一事务**。`DraftRecorder.record` 里依次做「再查 origin → submit → recordSynced」；`recordSynced` 幂等，重试安全；任何一步失败整笔回滚，不会留下没有来源的草稿。
- **旧草稿清理放在提交成功之后**。先建新草稿，再丢弃同一 skill、同一来源、未发布的旧同步草稿；清理失败只记日志，下次运行继续清。运营手工改过的那份也会被丢弃（PRD 做 #14）。

**「已见过」的口径**：底座取该 skill **同步写的快照里最新的那一个版本**（`written_by_sync`，含 `discarded` / `revoked`）的 revision——发布后刷新写的快照不算，否则一个手工发布的版本会把上游 HEAD 提前记成「已见过」，同步就再也不会为那个 commit 建草稿。上游回滚到更早的 revision 时，锚点仍指向更新的同步快照，因此那一轮会重新下载；内容摘要命中已有版本（含丢弃/撤销、且未被编辑）就报 `unchanged`，否则新建草稿。不产生错误数据，本版接受这次重新下载。M12 不读 M7 的 `skill_version`，所以由 `SourceService.anchors` 给出该 origin 在目标命名空间内**由同步写下**的全部快照、由 M7 的 `newestVersionIdAmong` 给出这些版本里最新的 id，底座把两者配对。配对只在仅有一个 skill 持有该 origin 时进行；多一个就下载后按地址判定，避免猜错。运营丢弃或撤销一份同步版本后，只要它的 revision 仍是最近的同步快照，就不会重建；被编辑过的历史版本摘要已变，可能重建。这也意味着「丢弃」在内容未变时是一个会生效的决定。

**许可必须与内容读同一个 commit。** v49 的每日刷新读的是仓库 HEAD 的许可，对「当前线上版本」可以接受；同步搬的是某个 commit，许可必须来自同一棵树，否则会出现「内容来自 A、许可来自 B」。因此 `GitHubContentReader` 先取该目录最近 commit 的 SHA，再按这个 SHA 拉 `codeload` 整包，在解出来的树里按路径找最近的 `LICENSE`；SPDX 识别从 `GitHubSourceAdapter` 抽成一个共享的解析器，两处共用。

**为什么是 codeload 整包，不是逐文件 API。** 2026-11-06 在生产 ECS 实测：`api.github.com` 可用但未认证只有 60 次/小时；`raw.githubusercontent.com` 需要跟随重定向且单文件 3.4s；`codeload.github.com` 取真实技能仓库 2.0 MB / 2.45s，且不计 API 额度。于是每个条目每个周期只花 **1 次 commits API**；只有变了的条目再花 1 次 codeload（`select: all` 的父目录另加 1 次 children），整包解出配置目录即可，不被文件数拖慢。**token 仍然必填**（2026-11-06 用户定），用来把 API 额度提到 5000/小时；codeload 省的是逐文件请求与额度，不是替代 token。整包有 `max-repo-bytes` 上限，超过即阻断并记录（`result=blocked`）。

**整包解压的边界**（安全）：

- 逐条校验路径：不得为空、不得以 `/` 开头、不得含 `..`。**要保留的范围（配置目录、祖先链许可、清单指定的许可文件）内只接受普通文件与目录，出现符号链接、硬链接、设备文件即整条阻断；范围外的这类条目直接忽略**（从不跟随）——真实仓库常在根目录放 `CLAUDE.md -> AGENTS.md` 这类链接，为一个不会被读取的文件废掉整个条目没有意义。
- 先只保留配置目录 `path/` 前缀下的文件；许可文件按下一条保留，其余丢弃；剥掉 tar 的单一顶层目录。
- 解压上限：条目数、单文件字节、该目录总字节都受 `max-*` 约束；**整包解压后的累计字节一旦超过 `max-unpacked-bytes` 立即中止**（每条 entry 的 512 字节头也计入，且保留一个由 `max-files` 推导的 entry 上限），不依赖压缩包大小——这是解压炸弹的防线。它与 `max-total-bytes`（只算配置目录里要同步的文件）分开，因为整包里其余目录也会解压，但不能因为它们大就放弃一个小 skill。
- 只保留配置目录祖先链上的许可文件，加上清单 `license:` 指定的那一个（可以在祖先链之外）；兄弟目录里的 LICENSE（vendored 依赖）进不了内存，也永远不是「最近的许可」。
- 任一越界 → 整条阻断，`reason=unsafe_archive` 或 `too_large`。

### 发布时的保留

v49 的 `SourceService.record` 在发布时从 `SKILL.md` 读来源；同步内容没有声明 `source`，官方命名空间会落到 `kind=official`，**覆盖同步写下的 GitHub origin**。本版只改这一处：

- 版本没有声明来源、命名空间是官方、且已有 `kind != official` 的 origin 时，**保留已有 origin**；只有没有任何 origin 时才写 `official` 兜底。
- **同步版本即使声明了来源也保留**：判据是**这个版本有没有同步写的快照**（M12 的 `hasSyncSnapshot(versionId)`，读 `skill_source_snapshot.written_by_sync`）——同步在草稿阶段就为版本写快照并置位，未被同步 replay 过的人工草稿没有。`PublishSkillVersionUseCase` 把这条事实告诉 M12，声明不能让 `putOrigin` 覆盖白名单条目。它不读 `skill_version.source`：replay 可能命中官方账号手工上传的同摘要草稿，那份草稿的 `source` 仍是 `zip`，改写它还会让下一轮旧草稿清理把它当成同步草稿丢掉。
- **replay 不改写草稿的 `source`**：同步提交同样的内容时，M7 可能返回官方账号此前手工建的同摘要草稿；它保持 `zip`，同步照样在事务内为它写快照，发布时上面的保留因此生效。
- 草稿阶段已写入的快照不被重置（现有逻辑已如此）；已落定的许可不被覆盖（现有逻辑已如此）。
- **`putOrigin` 会替换整个 origin 并级联删除旧快照**（外键 `ON DELETE CASCADE`）。所以这条改动不是「先写后覆盖也没关系」：如果 `record` 仍然无条件 `putOrigin(official)`，发布一次就会把 GitHub origin 和它的历史快照全部删掉。测试必须断言发布后旧快照仍在。
- `SourceRefreshJob` 只补**当前已发布版本**里 `pending` / `error` / `stale` 的快照；同步草稿的 revision 与许可在草稿阶段定稿，指标取不到时留 `pending`、到发布后由每日任务补，发布时不再联网（见上一节）。
- 这条改动有独立用例：同步草稿 → 发布 → origin 仍是 GitHub、快照 revision 不变、许可不变。

### 来源状态在草稿阶段定稿（[ADR 0161](../../decisions/0161-imported-skill-state-is-fixed-when-the-draft-is-created.md)）

v49 的快照是在发布事务提交后刷新的，每日任务还会按「超过一天」把线上版本全量刷一遍。对同步进来的版本这是重复劳动：同一个 commit 的 revision 与许可刚取过，只差 stars / forks / 最近提交；同一仓库的 N 个 skill 还会各刷一遍。本版把这一层前移到草稿阶段，发布与回滚不再联网。

- **信号从哪里取**：`ContentSource` 新增 `signals(entry)`；`GitHubContentSource` 委托 `GitHubSourceAdapter.signals(binding)`，只发 1 次 `GET /repos/{locator}`，产出 v49 的信号形状（`stars` / `forks` / `last_commit`，`last_commit` 取仓库 `pushed_at` 的日期部分）。**不取 commits、不走许可祖先链**——revision 来自该目录的 commits 调用，许可来自同一 commit 的整包，重复取只会把两个事实分开。
- **每轮每仓库一次**：`SyncPipeline.run()` 建一个运行内的 `ConcurrentHashMap`，键是 `kind + "\u0000" + locator`。条目通过全部闸门、即将产出 `PreparedEntry` 时才取值；同一批里同仓库的其它条目在同一个键上等待那次请求（上限是适配器的 3 秒超时）。**失败也缓存**：这个仓库本轮其它条目一律留 `pending`，不会因并发重复打接口，失败原因每仓库每轮只记一条。全部条目都因 revision 未变在下载前跳过时，没有条目走到这一步，整轮零调用；replay 的条目 revision 是新的、会走到写入，因此会取一次。
- **写入时机**：信号随 `PreparedEntry` 进 `SyncDraftWriter`，在同一事务里交给 `recordSynced`。取到时快照 `status=ok` 并写入 `signals`；取不到时仍建草稿，快照 `status=pending`、`signals=[]`，**在草稿发布后**由每日任务补——每日任务只扫当前线上版本，草稿期间不会重取。replay 到一份没有快照的手工草稿时同样会写一条快照，状态规则相同。
- **发布与回滚不读来源**：删除 `PublishSkillVersionUseCase` 的 `afterCommit` 与 `refreshSource`；`sources.record(...)` 保留——手工上传的版本仍在发布事务里写一条 `pending` 快照（不发来源请求），由每日任务填。发布仍按既有设计在内容首次上线时同步算一次检索向量（`EmbeddingIndex`，同一内容已有向量时跳过）。
- **每日任务收窄**：`SourceRefreshJob` 不再算 cutoff，`SourceRepository.pending()` 只查「当前线上版本、未软删除、且 `status IN (pending, error, stale)`」。`ok` 的快照无论多旧都不重取；周期刷新留到周任务（[`TODO.md`](../../current/TODO.md) 第 15 条）。
- **补缺口不改许可**：`refresh()` 只对非同步版本（`written_by_sync` 为假，即手工上传）合并许可；同步版本的许可是草稿阶段按内容所在的 commit 定的，刷新读的是 HEAD，合进来就会让上游后来的改许可改写老版本的答案。相应地，v49「`declared-only` / `missing` 每日重试」不再成立：许可在草稿阶段（同步）或首次刷新（手工上传）落定后不再随 HEAD 变化。
- **失败语义不变**：信号取不到不是同步失败，`result` 仍是 `created` / `unchanged`，结果行的 `warnings` 多一条 `signals_pending`。判据是**这次写入是否真的留下了一条 pending 快照**（`recordSynced` 的返回值），不是「是否新建了版本」：replay 命中已有快照时不报；replay 给一份没有快照的手工草稿补写时，同样报。

## 数据模型

**不加表；只有一条加列的迁移。** 全部复用 v49 的四张表；`skill_source_snapshot` 增加 `written_by_sync`（默认 false），用来区分「同步在草稿阶段写的快照」与「发布后刷新写的快照」——只看有没有快照会把两者混为一谈，回滚时就会错记 origin。导入重命名不新增列：`skill_source` 已有的 `kind` / `locator` / `path` 就是稳定身份的键。**上游原名因此不落库**——它只出现在草稿说明里，按原名检索需要二期新增来源字段并从 revision 回填。

| 表 | 用途 |
|---|---|
| `skill` | 官方命名空间下的 skill 行，地址来自平台名（`<prefix>-<上游 name>`、单 skill `name`，或未配置时的上游 `name`） |
| `skill_version` | 同步产生的是 `draft`；`changelog` 承载同步说明；`source='sync'` |
| `version_file` / `blob` | 上游目录的文件集，走现有 manifest 与 blob 写入 |
| `skill_source` | `role=origin`、`kind=github`、`locator`、`path` |
| `skill_source_snapshot` | 每个**同步**草稿版本的 `revision`（该目录最近一次 commit）与 stars / forks / 最近提交；`status=ok`，信号取不到时 `pending` |
| `skill_version_license` | 草稿阶段就落定：`ok`（文件在白名单）/ `declared-only`（无文件、声明在白名单）/ `missing`（完全没有）；非白名单不落库，直接阻断。`other` 只留给 v49 的刷新与历史行；`detail` 保留声明与冲突标记 |

约束：

- **origin 每个 skill 一条**。同步只在「没有 origin」或「origin 与清单一致」时写；已有其他来源的 origin → 阻断并记录（`result=blocked`），不覆盖。**不能先写再让发布覆盖**：`putOrigin` 换 kind/locator 会级联删掉旧快照。
- **平台名只分配一次**。首次导入按 [ADR 0160](../../decisions/0160-imports-are-renamed-by-an-optional-source-prefix.md) 算出平台名；之后按 `(kind, locator, path)` 取已分配的 `skill.name` 直接复用。上游改 `SKILL.md` 的 `name` 不新建 skill；改 `prefix` 不静默改名；目录改名是新的 `path`，按新条目处理——算出的地址若仍被旧 skill 占用则该条 `blocked`（`origin_conflict`），不接管旧 skill。
- **快照 revision 不移动**。replay 不更新已有版本的 revision；同一 revision 的重复同步在下载前就被跳过。锚点取**同步写的快照里最新版本**（含 `discarded` / `revoked`）的 revision，发布后刷新写的快照不参与，所以运营丢弃或撤销过的 revision 只要仍是最新的同步快照就不会被重新下载。
- **快照在草稿阶段定稿**。revision、信号与 `status=ok` 与草稿同一事务写入；取不到信号时草稿停在 `pending`，发布后由每日任务补（每日任务只扫当前线上版本）。发布与回滚不重写它，每日任务只碰 `pending` / `error` / `stale` 的版本，且不改同步版本的许可。信号是「草稿创建那一刻」的事实，不是「页面打开那一刻」的。
- **仅许可变化不触发同步**。revision 只看 skill 目录最近的 commit；根 `LICENSE` 单独变化不会下载，等下一次内容 commit 时新许可才生效（PRD 不做 #15）。
- **许可按版本落定**。有许可文件且在白名单时 `ok`；识别为 `LicenseRef…` / `NOASSERTION` / 非白名单时**阻断，不落库**；没有文件但声明在白名单时 `declared-only`；完全没有许可时 `missing`，页面显示「未声明」。
- **草稿说明**写在 `changelog`：`同步 · github owner/repo@<短sha> · <许可结论>`；导入时重命名的追加 ` · <原名> → <平台名>`；冲突追加「（待校准）」；上一版有许可、这一版没有时写「（已取消）」；从来没有许可时写「（未声明）」——后两者是不同的事实。上限 200 字符，放不下时先丢「原名 → 平台名」，再截来源；结论本身超长才截结论。
- **没有同步结果表**：结果只进日志，指标从日志统计（PRD §指标）。

## 接口

### 用户面

**没有新增用户面路由、CLI 或界面。** 只新增一个本机回环的内部触发端点（见「手动触发」）；运营发布沿用现有 `POST /web/skills/{ns}/{name}/publish` 与 `skmr version publish`。

### 内部契约

```java
// usecase/sync
public interface ContentSource {
    String kind();
    boolean supportsChildren();                  // select: all 需要
    SourceBinding binding(SyncEntry entry);      // 写进 skill_source 的来源事实
    ChildrenResult children(SyncEntry entry);    // 只列直接子目录名
    RevisionResult revision(SyncEntry entry);    // 失败返回 FetchFailed，不抛
    FetchResult fetch(SyncEntry entry, String revision); // 该 commit 的文件集与许可文件
    SignalsResult signals(SyncEntry entry);      // 仓库级指标（stars/forks/最近提交）：1 次请求，失败不抛
}

public record SyncEntry(String kind, String locator, String path, Mode mode,
        List<String> names, String licensePath, Naming naming) {
    public enum Mode { ONE, ALL, NAMES }         // path 空串 = 容器根；licensePath 空串 = 自动找最近的许可
    public record Naming(String prefix, String explicitName) {
        public static final Naming NONE = new Naming("", "");
    }
}

/** 只做两件事：算平台名；平台名与上游 name 不同时改写 SKILL.md 的顶层 name。 */
public final class SkillNameTransform {
    public static String platformName(String upstreamName, Naming naming);
    public static void requireValid(String name);          // 运行期：name_too_long / invalid_name / name_reserved
    public static void requireValidComponent(String value); // 启动期：校验 prefix / name 本身
    public static byte[] rewriteSkillMd(byte[] content, String platformName); // 抛 NotRewritable
}

public sealed interface ChildrenResult permits ChildrenOk, FetchFailed {}
public record ChildrenOk(List<String> names) implements ChildrenResult {}

public sealed interface RevisionResult permits RevisionOk, FetchFailed {}
public record RevisionOk(String revision) {}

public sealed interface FetchResult permits Fetched, FetchFailed {}
public record Fetched(List<FetchedFile> files, String revision, LicenseFile license) {}
public record FetchedFile(String relpath, byte[] content, boolean executable) {}
public sealed interface SignalsResult permits SignalsOk, FetchFailed {}
/** v49 的信号数组（最多 8 条），由适配器负责形状与链接。 */
public record SignalsOk(String signalsJson) implements SignalsResult {}
// executable 来自归档条目的 mode 位：可执行二进制阻断、可执行文本脚本只警告。
public record FetchFailed(String reason)
        implements RevisionResult, FetchResult, ChildrenResult, SignalsResult {}

/** 上游的许可文件；声明不在这里，由底座从 SKILL.md 取。 */
public record LicenseFile(String spdx, String name, String text, String path, String url) {}

public final class LicenseGate {
    public LicenseDecision decide(LicenseFile file, String declared);
}
public record LicenseDecision(Action action, String spdx, String name, String text, String path,
        String url, String copyright, LicenseStatus status, String detail, String note) {
    public enum Action { SYNC, SYNC_EMPTY, BLOCK }
}

public final class SecurityScanner {
    public SecurityReport scan(List<FetchedFile> files);
}
public record SecurityReport(List<SecurityFinding> blocked, List<SecurityFinding> warnings) {}
```

M 侧新增的公有方法（都只扩大公有面，不改现有签名）：

| 模块 | 方法 | 用途 |
|---|---|---|
| M3 | `AuthenticatedSubject.ofService(String userId)` | 服务身份的命名工厂；只读 `userId`，与 `ofSession` 同样不携带 scope |
| M4 | `NamespaceService.official()` → `Optional<Namespace>` | 按 `namespace.official = true` 查找；没有或多于一个时记录并中止本次运行 |
| M12 | `SourceService.anchors(namespaceId, kind, locator, path)` → `List<SyncAnchor>` | 该 origin 在目标命名空间内**由同步写下**的全部快照（skill / version / revision），**含丢弃/撤销**；发布后刷新写的快照不算；不读 M7 的 `skill_version` |
| M7 | `SkillVersionService.newestVersionIdAmong(skillId, ids)` → `Optional<String>` | 这些版本里最新的 id；底座把它与 M12 的快照配对，得到「已见过」的 revision |
| M12 | `SourceService.hasSyncSnapshot(versionId)` → `boolean` | 这个版本是不是同步写的：`written_by_sync` 置位即成立；发布时据此保留同步写入的 origin，不读 `skill_version.source` |
| M12 | `SourceService.findByAddress(namespaceId, name)` → `Optional<SkillSource>` | 地址预检：同名 skill 的 origin 是否与本条一致 |
| M12 | `SourceService.syncedName(namespaceId, kind, locator, path)` → `Optional<String>` | 稳定地址：按来源身份取首次导入时分配的 `skill.name`；只读 join，不加列 |
| M7 | `SkillVersionService.discardSyncDrafts(Access, String keepVersionId, String submittedBy)` | 丢弃同一 skill 下由官方提交、`source='sync'`、仍未发布的其它草稿 |
| M12 | `SourceService.recordSynced(skillId, versionId, SourceBinding binding, revision, signalsJson, SkillLicense)` | 草稿阶段写 origin / 快照 / 许可；`signalsJson` 非空时快照 `status=ok` 并写入信号，为空时 `pending`；幂等，不移动 revision，不覆盖已落定许可，不替换不同来源的 origin。`SourceBinding` 携带 kind / locator / path / label / url |
| M12 | `GitHubSourceAdapter.signals(SourceBinding)` → `Optional<String>`（v49 适配器新增公有方法） | 只发 1 次 `GET /repos/{locator}`，产出 stars / forks / `last_commit`；不取 commits、不走许可祖先链；失败返回空，由调用方把快照留 `pending` |
| M12 | `GitHubContentReader`（公有类） | 该 commit 的 codeload 整包，解出配置目录 + 同一棵树上的许可文件（清单指定了 `license:` 就取它，否则取最近的）；只返回事实，不解析 `SKILL.md`；SPDX 识别与 `GitHubSourceAdapter` 共用一个解析器 |
| usecase | `SubmitSkillUseCase.submit(zip, draftName, note, releaseType, subject, source)` 重载 | 同步传 `source="sync"`；现有五参方法以 `"zip"` 委托，行为不变 |
| M5 | 复用 `SkillDocument.parse(byte[])`（公有） | 底座解析 `SKILL.md` 取 `name` / `title` / `frontmatter` / 许可声明；结构校验交给提交用例 |

**底座解析 `SKILL.md`，适配器不解析。** 每个来源都只提供「文件 + revision + 许可文件」这一种事实；名字、标题、声明由底座用 M5 的同一套解析器读出来。这样新增来源时不需要各自实现一遍 frontmatter。zip 的单一根目录用**平台名**；平台名与上游 `name` 不同时，`SkillNameTransform` 只替换 frontmatter 顶层的 `name` 行（保留引号与其它字节，不做 YAML 重新序列化），校验器因此看到根目录名与内容名一致。平台名相同则完全不改字节。

**写入器必须套 `DraftRecorder`。** `submit` 内部会分配版本号与主键，撞车时只有外层新事务重试才正确；现有控制器都是 `drafts.record(() -> submitSkill.submit(...))`。同步的自定义用例直接调用模块服务，因此要自己套同一层，不能只依赖 `@Transactional`。

**`recordSynced` 的语义**（写成可重复调用的方法，而不是「只在新建时调」）：

1. 读现有 origin：不存在 → `putOrigin`；存在且 `(kind, locator, path)` 相同 → 复用；存在但不同 → 不写，返回 `origin_conflict`。
2. 快照：`(source_id, version_id)` 不存在 → 写入 `revision`、信号与状态（有信号 `ok`，没有 `pending`）；已存在 → 不移动 `revision`，只补 `written_by_sync`。
3. 许可：不存在 → 写入判定结果；已存在且 `ok` / `other` → 不覆盖；`missing` / `declared-only` → 可按本次结果补上。同步只写 `ok` / `declared-only` / `missing`。
4. 不写 `changelog`：说明在提交时已经作为 `note` 传入；replay 时保留原说明。
5. 不触发刷新：发布/回滚路径不再调用来源适配器；补信号只发生在同步（草稿阶段）与每日任务的缺口扫描里。

### 配置

`application.yml` 新增一组键；**键、默认值与对应的环境变量以 [`docs/current/config.md`](../../current/config.md) 为准**（那份是从 `application.yml` 生成的，本节不抄数字）。语义只在下面写一次：

- `enabled`：总开关；`false` 时不读清单、不注册任务与端点。
- `sources-file`：`classpath:`（jar 内）或 `file:`。生产用 `file:/etc/skillmaster/sync-sources.yml`，由部署工作流从仓库的 `deploy/sync-sources.yml` 复制过去（同 `server.env`，主机上改的不保留），切换前校验存在；**`file:` 前缀不能省**——裸路径会被当作 classpath 资源。文件在启动时读取，改清单要随部署重启生效。
- `github-token`：启用同步时必填（未认证只有 60 次/小时）。
- `trigger-token`：为空时手动触发不可用（`POST` 返回 404）；非空时端点只为本机回环服务。
- `concurrency`：同时拉取/解析/扫描的条目数，1–16；写入仍串行。
- `cron`：多久跑一次、几点跑，六段式（秒 分 时 日 月 周），按北京时间解释（时区固定 `Asia/Shanghai`）；默认值与可覆盖方式见 `config.md`。启动时不额外跑，立刻要跑用手动触发。
- `max-repo-bytes` / `max-files` / `max-file-bytes` / `max-total-bytes` / `max-unpacked-bytes`：整包、条目数、单文件、配置目录总量、解压总量的上限；任一越界整条阻断。

`application.yml` 另外设了 `spring.task.scheduling.pool.size`（值见 `config.md`）：它不是 `skillmaster.sync.*` 的键，而是全局调度池，用来让来源刷新不被一次长同步堵住。

文件格式：

```yaml
version: 1
sources:
  # 情况 1：仓库即 skill —— 省略 path，仓库根目录就是 skill；不配 prefix 就用上游 name
  - kind: github
    locator: some-owner/one-skill-repo

  # 情况 2：仓库里的固定目录；配了 prefix 就得到 <prefix>-<上游 name>
  - kind: github
    locator: coreyhaines31/marketingskills
    path: skills/ab-testing
    prefix: coreyhaines

  # 情况 3：某个目录下所有直接子目录各是一个 skill
  - kind: github
    locator: some-owner/many-skills
    path: skills
    select: all
    prefix: many

  # 情况 4：某个目录下只选这几家子目录
  - kind: github
    locator: some-owner/many-skills
    path: skills
    select: [ab-testing, seo]
    prefix: many

  # 情况 5：许可文件名为非标准名，或不在技能目录的祖先链上
  - kind: github
    locator: some-owner/repo
    path: skills/ab-testing
    prefix: some-owner
    license: legal/LICENSE-MIT

  # 情况 6：单 skill 条目的裸名例外
  - kind: github
    locator: some-owner/pdf-skill
    name: pdf-tools
```

- `kind` 决定适配器；`locator` 是来源里的容器；`path` 是容器里的子路径，**省略或空 = 容器根目录**。
- `prefix`（可选；与单 skill `name` 互斥）：平台名 = `<prefix>-<上游 name>`；`select` 展开出的子条目继承它。必须符合 Agent Skills 的 name 规则，`skillmaster` 本身或以 `skillmaster-` 开头保留；组合后超过 64 字符 → 该条 `blocked`。
- `name`（可选，仅 `Mode.ONE`）：直接作为平台地址；不能与 `prefix` 或 `select` 同用。名称规则同上。
- `select` 省略 = 这条就是一个 skill；`all` = 列出 `path` 下每个直接子目录各一条；`[名字…]` = 只取列出的直接子目录。
- `license`（可选，仓库相对路径）：配了就以该文件为准，`select` 展开出的子条目继承它；该 commit 里没有它 → 整条 `blocked`（`license_path_missing`）。不配时自动取祖先链上最近的许可文件（`LICENSE` / `LICENSE.MD` / `LICENSE.TXT` / `COPYING` / `NOTICE`）。
- 加同类型条目只改配置；加新平台要加适配器，但配置格式、底座、表结构、页面都不改。

**地址稳定性**：平台名在首次成功导入时分配；之后按 `(kind, locator, path)` 复用。没配前缀的条目就用上游 `name`；以后加 `prefix` 不会静默重命名已有地址。

**启动校验**（`SyncConfig` 构造时）：

- `version` 必须是 `1`；未知版本启动拒绝，避免以后格式变了被旧代码误读。
- `enabled=true` 时必须能读到文件、至少一条、`kind` 已注册；否则启动失败，报出错的条目。
- `locator` / `path` / `license` 交给加载器与适配器校验（locator 正则；路径不以 `/` 开头、不含 `..`、反斜杠、空段或尾随 `/`，也不含 `.` 段；`path` 与 `license` 允许为空）。非字符串值、未知键启动拒绝。
- `prefix` / `name` 为非空字符串时必须符合 Agent Skills 的 name 规则（小写字母/数字/连字符，不以连字符开头或结尾，无连续连字符），`skillmaster` 与 `skillmaster-` 开头是保留前缀；两者同时出现、`name` 与 `select` 同用、或键存在但值为空（`prefix: ""` / `prefix:`）→ 启动拒绝。上游 `name` 拼接后超长/非法/保留是运行期 `blocked`，不在启动时判断（启动时还不知道展开后的名字）。
- `cron` 必须能被 `CronExpression` 解析；`concurrency` 必须在 1–16；所有 `max-*` 必须为正数。任一不合法启动拒绝。
- **运行期与启动期的分界**：清单语法、未知 kind、非法路径、坏 `prefix`/`name`、坏 cron、缺 `github-token`、`max-*` 非正数是启动失败；`license:` 指向的文件在该 commit 里不存在、上游目录不存在、组合后的平台名超长/非法/冲突、扫描阻断是运行期结果（`blocked` / `skipped`），服务照常启动。
- `select: all` 要求 `path` 非空且适配器支持列子目录；`select: [名字…]` 要求 `path` 非空、名字是非空单段目录名（不含 `/`、`..`）、无重复；`select` 字段与单条目互斥的表达方式由加载器解析，非法值启动拒绝。
- `enabled=true` 时 `github-token` **必须非空**：未认证只有 60 次/小时，清单变大或频繁手动触发会撞限额；启动直接拒绝，不跑到一半才失败（2026-11-06 用户定）。
- `trigger-token` 为空时手动触发不可用（`POST` 返回 404，见「手动触发」）；非空时端点只为本机回环服务，nginx 拒绝 `/internal/`。
- `enabled=false` 时不读文件、不注册任务，测试与本地默认行为不变。

### 日志

每条一行，结构化、可 grep，不打印文件内容与 token：

```text
sync entry=github:coreyhaines31/marketingskills@skills/ab-testing result=created reason= revision=abc1234 warnings=
sync entry=... result=unchanged revision=abc1234 warnings=
sync entry=... result=blocked reason=license_not_allowed:GPL-3.0 revision=abc1234 warnings=
sync entry=... result=blocked reason=scan_blocked:private_key revision=abc1234 warnings=
sync entry=... result=blocked reason=origin_conflict revision=abc1234 warnings=
sync entry=... result=skipped reason=missing_skill_md revision=abc1234 warnings=
sync entry=... result=failed reason=upstream_timeout revision= warnings=
sync entry=... result=failed reason=name_not_rewritable revision=abc1234 warnings=
sync entry=... result=created revision=abc1234 warnings=address_kept:existing=foo requested=acme-foo,external_url:README.md
```

结果码共五种：`created`（新草稿）/ `unchanged`（没有新草稿：revision 未变，或同摘要命中已有草稿——replay 也记这一种）/ `blocked`（闸拒绝）/ `skipped`（路径不存在等预期跳过）/ `failed`（运行期失败或内容缺陷）。

### 手动触发（本机回环 + CI）

```text
GitHub Actions workflow_dispatch
  └─ SSH（复用 DEPLOY_HOST / DEPLOY_USER / DEPLOY_KEY）
       └─ curl -X POST http://127.0.0.1:8080/internal/sync/run
               -H "X-Sync-Trigger: $SKILLMASTER_SYNC_TRIGGER_TOKEN"
            └─ 同步执行一次；返回逐条 JSON 摘要
```

- 端点 `POST /internal/sync/run` 放在 api 层，只做鉴权与调用；nginx 对 `/internal/` 返回 404（`deploy/nginx/skillmaster.conf` 加 `location /internal/ { return 404; }`）。
- 鉴权：`X-Sync-Trigger` 与 `SKILLMASTER_SYNC_TRIGGER_TOKEN` 常量时间比较；不匹配 401。token 为空时控制器仍随 `enabled=true` 注册，但没有 guard，`POST` 返回 404（`GET` 会得到 405，它本来就不是触发面）。
- 并发：`AtomicBoolean.compareAndSet`；已在跑 → 409 `sync_already_running`。
- 同步执行，返回 `{started_at, finished_at, entries:[{kind,locator,path,result,reason,revision,warnings}]}`（字段按全局 Jackson `SNAKE_CASE` 序列化，见 `application.yml`）；工作流超时设 20 分钟。请求超时不代表任务停止，日志仍会写。
- 新增 `.github/workflows/sync-trigger.yml`（`workflow_dispatch`），不构建、不发布、不重启。
- 不加 CLI、不加控制台；[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 只要求控制台操作有 CLI，本版没有新增控制台操作。

## 许可闸与安全扫描

### 许可闸（[ADR 0155](../../decisions/0155-open-source-sync-passes-three-gates.md)）

按一个权威取一个值：清单指定的许可文件（`license`）> 祖先链上最近的许可文件 > `SKILL.md` 声明 > 无。清单指定了路径却在该 commit 里找不到，是**阻断**，不是「无许可」——否则一次改名就会把本该拦下的内容当成未声明放进来。

| 输入 | 动作 | 落库 |
|---|---|---|
| 文件 SPDX 在白名单 | 同步 | `ok`，许可名与全文 |
| 文件 SPDX 是 `LicenseRef…` / `NOASSERTION` / 非白名单 | 阻断 | —— |
| 没有文件，声明在白名单 | 同步 | `declared-only` |
| 没有文件，声明非白名单 | 阻断 | —— |
| 文件与声明都在且不同 | 同步（文件为准） | 文件在白名单则 `ok`，非白名单则阻断；`detail.declared` 保留；草稿说明标「待校准」 |
| 完全没有许可 | 同步 | `missing`，页面「未声明」；草稿说明标「未声明」 |
| 完全没有许可，但上一版有 | 同步 | `missing`；草稿说明标「已取消」（A7 要求把「被取消」与「从来没有」分开） |

白名单：MIT、Apache-2.0、BSD-2-Clause、BSD-3-Clause、ISC、MPL-2.0、Unlicense、CC0-1.0、CC-BY-4.0。

`license:` 的值按**仓库内精确路径**匹配（区分大小写，不以 `./` 开头）。它只在一个 revision **尚未同步过**时生效：锚点 revision 相同就跳过下载，改许可路径不会重建已有草稿；要纠正一个已同步的 skill，等上游出新 commit，或人工处理。

### 安全扫描

规则放在 `skillmaster-server/src/main/resources/sync/security-rules.yml`，由 `SyncConfig` 读入、`SecurityScanner` 执行；规则与来源无关。

| 级别 | 规则（首版） |
|---|---|
| 阻断 | 私钥块（`BEGIN … PRIVATE KEY`）；常见凭据前缀（`AKIA…`、`ghp_…`、`sk-…`、`xox…`）；`.env` / `id_rsa` / `*.p12` 路径；`rm -rf /`、`mkfs`、`dd … of=/dev/`、fork bomb；下载内容直接管道到 `sh` / `bash` / `iex` 的命令；**可执行二进制** |
| 警告 | 脚本里的外部 `http(s)://` 引用；宽泛权限声明（`allowed-tools` / `permissions` 含 `*`）；可执行文本脚本；二进制文件 |

- 规则文件里是 9 条阻断 + 4 条警告。「文件数/字节数接近上限」不在这份文件里：底座在文件集已知后由 `SyncPipeline` 追加，因为规则扫描器只拿得到单个文件。
- 只扫文本（UTF-8、无 NUL）；二进制按路径与可执行位判。`.env.example` / `.env.sample` / `.env.template` 是文档不是凭据，不拦；私钥靠 PEM 头识别，不按 `.pem` 后缀误杀公共证书。
- **「可执行二进制阻断」与「可执行文本脚本警告」并不矛盾**：可执行**二进制**阻断，可执行**文本脚本**只警告——tar 的 mode 位就是判据，`GitHubTar` 把它带到 `FetchedFile.executable`。
- 阻断项命中 → 不建草稿，只记日志；警告项记录后继续。
- 上限：`max-files`、`max-file-bytes`、`max-total-bytes` 任一超出即整条阻断（reason=`too_large`）。

## 分期

| 期 | 交付 | 对应验收 |
|---|---|---|
| **P0 通路** | 配置与装载、`ContentSource`/`SyncEntry`、目录展开（`select`）、GitHub revision + 该 commit 的文件与许可、整包解压安全、锚点跳过（含丢弃/撤销）、可选前缀/别名与稳定地址、地址预检、有界并发拉取、事务化提交 + `recordSynced`、`source=sync` 重载、`SyncJob` | A1–A4、A9–A12、A14、A16–A21 |
| **P1 两闸与清理** | 许可闸全状态、扫描规则与分级、发布时 origin / 快照 / 许可保留、旧草稿清理 | A5–A8、A15 |
| **P2 运维** | 手动触发端点 + Actions 工作流 + nginx 拒绝规则、部署文件/环境变量说明、真机跑一条公开仓库、日志可读性 | A13、T13、T14 |
| **P3 状态定稿** | 草稿阶段写入完整快照（信号 + `ok`）、仓库元数据每轮每仓库一次、发布/回滚去掉刷新、每日任务只补缺口 | A22–A26 |

P1 不是可选项：A5、A8 是成立条件，必须与本版一起交付。

## 开放问题

1. **整包目录名与 LFS**——codeload 整包有一个顶层目录（`<repo>-<sha>`），统一剥一层；Git LFS 文件在整包里可能是指针而不是内容。倾向：顶层目录按单根剥离；LFS 指针当作普通文本，并在日志里记一条警告，本版不解析 LFS。
2. **草稿版本名**——倾向：`sync-<短 sha>`（≤40 字符）；运营发布时可改。是否需要写成中文「同步 2026-11-06」由实现时按可读性定。
3. **冲突说明的字面**——倾向：`同步 · github owner/repo@abc1234 · MIT（待校准：声明 GPL-3.0）`，≤200 字符；超出时截断来源。
4. **触发 token 的轮换**——定为：与部署密钥分开的专用随机串只存在 GitHub secret；改完 secret 重新部署，`server.env` 由部署流程渲染，主机上改的不保留。
5. **多实例**——本版沿用单实例假设；若上线前已多实例，先加 PostgreSQL advisory lock，再启用定时任务。
6. **同仓库多目录的整包缓存**——同一仓库配了多个 skill 目录时，每个目录会各下一次整包。倾向：v50 不做缓存，量小时收益低；条目变多再加一个按 `(locator, revision)` 的有界缓存。
7. **重命名草稿说明的字面**——已按「倾向」实现：`同步 · github owner/repo@<短sha> · <许可结论> · <原名> → <平台名>`；超过 200 字符时先丢原名映射，保留来源与许可结论。原名没有单独落库，被丢掉后只能按 `locator/path` + 快照 revision 回源重取。字面仍可再调。
8. **指标快照的陈旧展示**——同步导入的 stars / forks / 最近提交停在草稿创建那一刻，周刷新还没做。页面与 CLI 要不要显示快照时间？倾向：本版不改界面与 CLI（v49 已定信息页不显示校验时间、`skill show` 打印「最近校验」），周任务落地时一并定，见 [`TODO.md`](../../current/TODO.md) 第 15 条。

**本轮已定的命名细节**：基础名用 `SKILL.md` 的 `name`（Agent Skills 要求它与目录名一致，不单独用路径名）；组合后超长或非法直接阻断，不截断、不加哈希；改写只动 frontmatter 顶层的 `name` 行，保留引号与其它字节；已导入地址在配置改 `prefix` 后保持不变，需要改名另做迁移。

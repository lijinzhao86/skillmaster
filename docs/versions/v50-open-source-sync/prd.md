# v50-open-source-sync · 产品需求

> **最后更新**：2026-11-07
> **所属版本**：[`README.md`](README.md)
> **来源**：原 `docs/current/TODO.md`「开源 skill 自动同步」条目（已搬入本版，2026-11-06）；产品负责人定下四条边界——人工白名单、仅宽松许可、草稿后人工发布、内置静态扫描。2026-11-07 增补「导入时按来源前缀重命名」，原 TODO「批量导入时的重名地址策略」已搬入本版。

## 用户与场景

**谁在用**：平台运营者（维护同步清单、审核并发布）；公开 skill 的读者——普通用户，以及替他们执行命令的 agent。

**在什么场景下用**：

- 运营者选中一个公开开源仓库里的 skill，要把它收进官方账号；此后上游每次更新都能跟进，而不是每次重新搬运一遍。
- 读者在「发现」页或 `skmr skills search` 里看到一条官方 skill，来源写着 GitHub 的某个仓库，许可是 MIT；他据此判断能不能带走、要不要用。
- agent 在没有网页的环境里读到同一组来源与许可事实，替用户做初筛。

**现在的替代做法是什么**：

- 运营者手工 clone 仓库、整理成 skill 包，登录官方账号逐条发布。仓库一更新就要重来一遍，平台上还说不清「这一版对应上游哪个 commit」。
- 或者不收：公域在 v48 收口后只有少量官方自建 skill。

## 现在的问题、诱因与改法

### 问题 1：手工搬运不可持续，也补不上溯源

**现在的流程**：运营者从开源仓库下载内容 → 整理成 zip → 登录官方账号 → 逐条提交、发布。

**问题**：每收一个 skill 都要重复一遍；上游更新后没有机制提醒，也没有机制把新内容带进来。平台上只有发布时写下的 `SKILL.md` 元数据，看不出这一版来自哪个仓库、哪个 commit。

**为什么会这样**：平台只有单次导入，没有「来源 + 上游版本」的同步路径；v49 的 `skill_source` / `skill_source_snapshot` 只在发布时从 `SKILL.md` 声明里读取，不会自己去上游取内容。

**怎么改能解决**：新增**同步清单**与**同步任务**。清单由运营者人工维护，每条写明仓库、目录，以及可选的 `select` 与许可路径；任务在**启动时读取一次清单**（改清单要重启），把上游目录内容变成官方命名空间下的**草稿版本**，并把上游 commit 记进版本级来源快照。

### 问题 2：没有许可闸

**现在的流程**：内容进平台之前，由人看一眼仓库首页的许可；没有规则，也没有记录。

**问题**：开源不等于随便用。GPL / AGPL 有传染性义务，未声明许可的仓库连再分发依据都没有；平台把内容收进公域，就是在替它背书。只靠人看，迟早会漏。

**为什么会这样**：v49 只做许可的**展示**，明确不做入库时的裁决（[ADR 0154](../../decisions/0154-v49-does-not-adjudicate-license-conflicts.md)）；同步是第一个「内容自动进入平台」的入口，闸必须设在这里。

**怎么改能解决**：同步在取到内容后先过**许可闸**：按离 skill 最近的许可文件识别 SPDX，只接受**宽松许可白名单**里的值；识别出非白名单许可（GPL、AGPL、自定义、`NOASSERTION`）时不同步。**完全没有许可**（没有文件、`SKILL.md` 也没声明）时仍同步，但平台上的许可留空，并在草稿说明里标注「未声明」；文件与声明不一致时以文件为准，草稿说明里标注「待校准」。白名单与理由见 [ADR 0155](../../decisions/0155-open-source-sync-passes-three-gates.md)。

### 问题 3：没有安全闸

**现在的流程**：没有任何内容安全扫描；`ingest` 模块也没有密钥扫描。

**问题**：公开仓库里的 skill 可能带硬编码凭据、私钥、下载即执行的命令或可执行二进制。平台自动收进来再自动发布，等于把这些风险原样分发给所有用户。

**为什么会这样**：平台今天的内容都来自已登录作者，安全责任在作者；自动同步改变了这个前提——内容由平台主动收进来。

**怎么改能解决**：同步在落库前跑**内置静态检查**：凭据与私钥、破坏性命令、下载即执行、可执行二进制、外链脚本与权限声明。命中断级规则的内容不建草稿；只命中警告级规则的照常建草稿，但在同步结果里记录。规则清单与分级见 [ADR 0155](../../decisions/0155-open-source-sync-passes-three-gates.md)。

### 问题 4：上游更新靠人记，版本号也没有稳定映射

**现在的流程**：上游仓库更新后，运营者凭印象决定要不要重新搬一次；平台版本号由发布时人填。

**问题**：要么漏掉更新，要么重复搬运；同一个上游 commit 被重复发布会制造多个内容相同的版本，读者也看不出「这一版对应上游哪一次提交」。

**为什么会这样**：平台没有「上游 commit → 平台版本」的映射，也没有幂等判断。

**怎么改能解决**：同一个上游 commit 只同步一次；commit 变了才建一份新草稿，版本号沿用现有发布机制按补丁号递推（1.0.0 之后 1.0.1、1.0.2…），发布时运营者仍可改成别的版本名。规则见 [ADR 0156](../../decisions/0156-sync-writes-as-the-official-namespace.md)。

### 问题 5：跨来源同名会把后来者挡在门外

**现在的流程**：同步以 `SKILL.md` 的 `name` 作为官方地址；同一轮或跨来源出现同名时，后到的记为 `blocked`（`duplicate_address` / `origin_conflict`）。

**问题**：批量导入时，不同作者常给 skill 起同一个通用名。真实样本：`obra/superpowers` 与 `addyosmani/agent-skills` 都有 `test-driven-development`，内容不同。只阻断等于让先到者垄断名字，后来者永远进不来；加 `-2` 或随机后缀会让地址不可预测，检索也会被污染。

**为什么会这样**：官方命名空间是扁平的，地址直接等于上游 `name`；「来源」没有进入地址。

**怎么改能解决**：清单可选配 `prefix`：配了，平台名 = `<prefix>-<上游 name>`；没配，保持上游 `name`。重名仍按现有规则 `blocked`；要让两个同名来源共存，给其中一个来源配不同的 `prefix`（或给单 skill 配 `name`）。平台名与上游 `name` 不同时才改 `SKILL.md` 的 `name` 与 zip 根，正文不动——配了前缀/别名是一个来源，地址已分配而上游后来改名是另一个来源；两者都不发生时逐字节不变。平台侧保留原路径与 revision；上游原名只写在草稿说明的 `原名 → 平台名` 里（受 200 字上限，放不下时省去），不单独落库。地址一旦分配，按 `(kind, locator, path)` 固定，上游改名不新建 skill。方案选择见 [ADR 0160](../../decisions/0160-imports-are-renamed-by-an-optional-source-prefix.md)。

### 问题 6：发布要把第三方拉进来，同一个 commit 的状态取两遍

**现在的流程**（v49）：发布事务提交后再花一次 GitHub 往返刷新来源（3–5 次接口），3 秒超时、失败只标 `error`；每日任务按「快照超过一天」扫**线上版本全量**刷新。2026-11-07 首批 53 条批量导入时，每条发布 2.2–2.9 秒，53 条撞上流式接口的 30 秒超时（界面收到 12 条、库里 13 条；异步超时的处理见 [`iterations/0003`](iterations/0003-批量发布超时与同步上限.md)）。

**问题**：对同步进来的版本，发布时那次刷新几乎全是重复劳动——同一个 commit 几分钟前刚取过内容、落定了许可，只差 stars / forks / 最近提交这类慢变信号；同一仓库的 N 个 skill 各刷一遍，取的是同样的仓库元数据。每日全量刷新又把慢变信号当成每日事实，白花额度；仓库别处有 commit 时，连目录没变的 skill 也被刷新。

**为什么会这样**：v49 的场景是官方手工发布的少量 skill，「发布即取最新」是当时合理的口径；v50 把数量放大到上千，而内容来源本身就是一个固定 commit。

**怎么改能解决**：同步建草稿时把来源状态一次写全（revision、许可、stars / forks / 最近提交；仓库元数据每个仓库每轮只取一次）；发布与回滚不再调 GitHub；每日任务只补 `pending` / `error` / `stale` 的缺口；是否产生新草稿只按**该 skill 目录**的 revision 判断。stars / forks 改成每周刷新留到 [`TODO.md`](../../current/TODO.md) 第 15 条。方案见 [ADR 0161](../../decisions/0161-imported-skill-state-is-fixed-when-the-draft-is-created.md)。

## 范围

### 做

**两个交付单元：一个与来源无关的同步底座，一个 GitHub 来源适配器。** 底座管清单、调度、幂等、许可闸、安全扫描、草稿写入与说明；适配器只管「这个来源怎么取内容、怎么找许可」。以后接 SkillHub 只加适配器，不动底座（[ADR 0157](../../decisions/0157-sync-is-a-source-neutral-pipeline.md)）。

#### 通用同步底座

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | **同步清单（人工白名单，配置文件）**：服务端配置里的 YAML 列表，每条是「来源类型 + 定位 + 可选 `prefix`（或单 skill 的 `name`）+ 可选目录 + 可选 `select` + 可选许可路径」；平台地址见做 #15。只同步清单里的条目，不做跨仓库或按 topic 的自动发现。**不进数据库、不做运营界面**；格式错误或来源类型未注册时启动拒绝 | 服务端配置文件；沿用 [ADR 0144](../../decisions/0144-a-new-account-starts-with-the-official-skills.md) 的配置做法 |
| 2 | **调度与失败隔离**：服务端内部的定时任务（Spring `@Scheduled`，不用系统 crontab）：按 cron 在配置的时间跑（默认值见 `config.md`，按北京时间解释；频率与时间都改这一个表达式）；部署或重启不额外触发，要立刻跑用手动触发；单条失败（上游不可达、超时、许可不合规、扫描阻断）只记为 `failed` / `blocked` 并记录日志，不影响其它条目与线上内容 | 同步任务；单实例部署假设，与现有两个定时任务一致 |
| 3 | **幂等与版本映射**：同一来源、同一目录、同一 revision 只同步一次；新 revision 产生一份新草稿（发布时按补丁号递推，运营者可改名）；**运营丢弃或撤销过的 revision 也算见过，不再重建**；已在线上或已存在的版本不被改写 | 同步任务；版本号规则见 ADR 0156 |
| 4 | **许可闸**：只接受 [ADR 0155](../../decisions/0155-open-source-sync-passes-three-gates.md) 的宽松许可白名单；识别出非白名单许可（含 `NOASSERTION`）不同步；**完全没有许可时同步但留空**；文件与声明不一致时以文件为准并标注待校准。适配器只提供「许可文件」，判定与 `SKILL.md` 声明解析都在底座 | 同步任务内部；结论写进草稿说明 |
| 5 | **安全扫描**：内置静态检查，分「阻断」与「警告」两级；阻断项命中即不建草稿，警告项记录后继续。规则与来源无关 | 同步任务内部；结果可读 |
| 6 | **官方写入**：把通过两闸的内容写成官方命名空间 `skillmaster` 下的 skill 与**草稿版本**；新建 skill 或给已有 skill 加草稿，两种都只产生草稿，不自动发布、不自动公开 | 服务端内部同步用例；沿用现有作者面读模型 |
| 7 | **来源与许可落库**：同步进来的 skill 必须能追溯到来源、目录与 revision；发布后来源与许可按 v49 的读模型展示，不依赖上游 `SKILL.md` 自己声明 | `skill_source` / `skill_source_snapshot` / `skill_version_license` 的同步写入路径 |
| 8 | **草稿说明**：每个同步草稿在版本的 `changelog` 里写一行（≤200 字）：来源 `owner/repo@commit`、许可结论；导入时重命名的写「原名 → 平台名」；冲突写「待校准」，上一版有许可而这一版没有写「已取消」，从来没有许可写「未声明」。运营按这行说明筛草稿，不另建提醒表 | 版本草稿的 `changelog`；不新增界面 |
| 15 | **导入时的可选来源前缀重命名**：清单可选配 `prefix`（符合 Agent Skills 的 name 规则，`skillmaster` 本身或以 `skillmaster-` 开头保留）或单 skill 条目的 `name`；配了前缀，平台名 = `<prefix>-<上游 SKILL.md 的 name>`，配了 `name` 则直接用它，都没配则保持上游 `name`。平台名与上游 `name` 不同时才改 `SKILL.md` 的 `name` 与 zip 根（配了前缀/别名，或地址已分配而上游改了名），正文与其它文件不动；相同时逐字节不变。`skill_source` 保留原路径；上游原名不单独落库，按原名检索留到二期来源卡片（今天搜索不读来源）。地址按 `(kind, locator, path)` 固定：上游改 `name` 不新建 skill；改 `prefix` 不静默改名。名称超长、非法、保留或与已有地址重复时该条 `blocked` 并记录原因；`select: all` 不支持逐个别名。 | 同步底座；清单字段与写入器；不改前端 / CLI |
| 16 | **来源状态在同步时定稿**：同步建草稿时把该 skill 版本的来源快照写完整——revision、stars / forks / 最近提交，取得到时 `status=ok`，取不到时仍建草稿、快照留 `pending`（见 A26）；许可本来就在草稿时落定。仓库元数据按 `(kind, locator)` 每轮（一次同步运行，含手动触发）只取一次，条目通过全部闸门后取，不按 skill 重复取。**发布与回滚不再调用 GitHub**：发布不读来源平台（内容首次上线时仍按既有设计同步算一次检索向量，与来源无关）；每日来源任务只补 `pending` / `error` / `stale` 的快照，不再按「超过一天」刷新线上版本。是否建草稿仍只看**该 skill 目录**的 revision——仓库别处有 commit 但该 skill 目录没变，就不建草稿、也不刷新它。 | 同步底座与 GitHub 适配器；发布路径去掉刷新；每日任务收窄；不改前端 / CLI / API |

#### 第一个来源适配器：GitHub

| # | 做什么 | 交付形态 |
|---|---|---|
| 9 | **GitHub 内容拉取**：变化检测取默认分支上**该目录最近一次 commit**；整包**每个仓库每轮下载一次**，取默认分支 tip，每个 skill 从同一个包里解出自己的目录；只读文件，不 clone、不执行、不跑上游脚本；返回文件集与**目录 revision**（变化检测身份，不一定是内容所在的树，[ADR 0163](../../decisions/0163-a-repository-is-downloaded-once-per-run.md)） | GitHub 适配器；实现底座的来源接口 |
| 10 | **GitHub 许可定位**：在**本轮下载的那个 tip 整包**里（与内容同一棵树），从 skill 目录逐级向上找 `LICENSE` / `LICENSE.MD` / `LICENSE.TXT` / `COPYING` / `NOTICE`，识别 SPDX；**不解析 `SKILL.md`**——声明由底座用同一个 `SkillDocument` 读取，和手工上传走同一套解析 | GitHub 适配器 |

**同步清单的格式（2026-11-06 锁定；2026-11-07 增补 `prefix` / `name`）**

```yaml
version: 1
sources:
  # 情况 1：一个仓库就是一个 skill —— 省略 path，仓库根目录就是 skill；不配 prefix 就用上游 name
  - kind: github
    locator: some-owner/one-skill-repo

  # 情况 2：仓库里的一个固定目录 —— 给 path
  - kind: github
    locator: coreyhaines31/marketingskills
    path: skills/ab-testing
    prefix: coreyhaines

  # 情况 3：某个目录下的所有子文件夹各是一个 skill
  - kind: github
    locator: some-owner/many-skills
    path: skills
    select: all
    prefix: many

  # 情况 4：某个目录下只选这几家子文件夹
  - kind: github
    locator: some-owner/many-skills
    path: skills
    select: [ab-testing, seo]
    prefix: many

  # 情况 5：许可文件的名字不是标准名，或不在技能目录的祖先链上
  - kind: github
    locator: some-owner/repo
    path: skills/ab-testing
    prefix: some-owner
    license: legal/LICENSE-MIT

  # 情况 6：单 skill 条目的裸名例外 —— 只给平台策展用
  - kind: github
    locator: some-owner/pdf-skill
    name: pdf-tools
```

- `kind`：来源类型，决定用哪个适配器；本版只有 `github`。
- `locator`：来源里的「容器」；GitHub 是 `owner/repo`。
- `path`：容器里的子路径；**省略或空 = 容器根目录**。
- `prefix`（可选；与单 skill 的 `name` 互斥）：来源前缀，平台名 = `<prefix>-<上游 SKILL.md 的 name>`。必须符合 Agent Skills 的 name 规则（小写字母/数字/连字符，不以连字符开头或结尾，无连续连字符），`skillmaster` 本身或以 `skillmaster-` 开头保留；首次分配时，前缀与 upstream name 拼起来超过 64 字符或落到保留名，该条 `blocked`。没配且没有 `name` 时，平台名就是上游 `name`。
- `name`（可选，仅单 skill 条目）：直接作为平台地址；与 `prefix` 互斥，不能和 `select` 同用。名称规则同上，`skillmaster` 本身或以 `skillmaster-` 开头保留（`name_reserved`），重复时 `blocked`。
- `select`：省略 = 这条就是一个 skill；`all` = `path` 下每个直接子目录各是一个 skill；`[名字…]` = 只取列出的子目录（单段目录名，不能带 `/` 或 `..`）。`select` 要求 `path` 非空；用 `select` 时只能用 `prefix`，不能用 `name`。
- `license`（可选）：仓库内的许可文件相对路径。配了就以它为准，不再找「最近的许可」；本轮下载的那棵树里没有这个文件时整条**阻断**（`license_path_missing`），不会静默退回「未声明」。不配时自动找：`LICENSE` / `LICENSE.MD` / `LICENSE.TXT` / `COPYING` / `NOTICE`，离技能目录最近的一份。
- `version`：配置格式版本；本版只接受 `1`。

**地址的稳定性**：平台名在第一次成功导入时分配，之后按 `(kind, locator, path)` 固定。没配前缀的条目地址就是上游 `name`；以后给它加 `prefix` 不会静默重命名已有地址。上游后来改了 `SKILL.md` 的 `name` 不新建 skill；改了 `prefix` 不会静默重命名已有地址，运营需要另做迁移。上游把目录改名视为新的 `(locator, path)`，按新条目处理：新路径若算出已被占用的地址（通常就是旧 skill 的地址），该条 `blocked`（`origin_conflict`），不会接管旧 skill；给新条目配不同的 `prefix` 或上游同时改名才得到新地址。

加同类型的新条目只改配置；加一个新平台要写一个适配器（代码），但配置格式、表结构、页面与底座都不改。

**`select: all` 的边界**：上游新增的子目录会自动进入**草稿队列**，但仍然要人工发布才进公域；没有 `SKILL.md` 的子目录跳过并记录。要绝对可控就用 `select: [名字…]` 或逐个写单条目。

#### 交付流程

| # | 做什么 | 交付形态 |
|---|---|---|
| 11 | **人工发布**：运营者登录官方账号，在现有控制台按已有流程审核、发布；发布并可公开之后，内容才进入公域 | 复用现有控制台与 CLI 发布路径，不新增界面 |
| 12 | **手动触发**：GitHub Actions 的 `workflow_dispatch` 按一下，经现有部署 SSH 到服务器，调本机回环端点触发一次同步；不发布、不重启服务。同一时刻只允许一次，正在跑时返回冲突 | 新增触发工作流 + 服务端回环端点；不加 CLI、不加控制台；nginx 明确拒绝 `/internal/` |
| 13 | **并发度可配置**：同时拉取、解析、扫描的条目数，范围 1–16（默认值见 [`docs/current/config.md`](../../current/config.md)）；写入仍串行（提交路径有表锁），并发只省网络与扫描的时间 | 配置键 同步并发配置；非法值启动拒绝 |
| 14 | **旧草稿清理**：新 commit 建出新草稿后，丢弃同一 skill、同一来源、仍未发布的旧同步草稿；运营手工改过的那份也会被丢弃（2026-11-06 用户定） | 同步写入器；日志记录被丢弃的草稿 |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 跨仓库自动发现（按 topic / 搜索抓仓库） | 「谁进公域」是平台背书，必须由人选定；自动发现会把背书变成算法推荐。清单里 `select: all` 只展开**已选父目录**的直接子目录，仍要人工发布 | 未定 |
| 2 | SkillHub 或其他来源 | 第二个来源的 API 与合规边界未定；底座已按适配器留好扩展点，本版只交付 GitHub 适配器 | 来源扩展那版只加适配器，不动底座 |
| 3 | 自动发布或自动公开 | 公开是平台背书（[ADR 0146](../../decisions/0146-public-is-platform-endorsement.md)），内容安全与适用性要人确认 | 不做：政策 |
| 4 | 第三方安全扫描（VirusTotal 等） | 引入外部依赖、限额与数据出境问题；内置静态检查先覆盖已知高风险 | 扫描升级那版 |
| 5 | 同步管理页（清单、结果、手动重跑） | 本版复用现有发布流程；同步结果先看日志与官方账号的草稿列表 | 有多个运营者或条目变多时 |
| 7 | 执行或测试上游代码 | 同步只搬文件；运行上游内容会引入不可控的执行风险 | 不做 |
| 8 | 指定分支或 commit（只跟默认分支上该目录的最近 commit） | 本版先把「跟默认分支」跑通；指定 ref 需要额外的清单字段与冲突规则 | 来源扩展或需要锁版本时 |
| 9 | 文件级增量对比 | 每次按目录取全量；同一 revision 在下载前就跳过，不做摘要比较（commit SHA 是内容寻址）；增量对比的收益不值得多一套状态 | 同步量变大时 |
| 10 | 在同步里改写上游内容（`name` 字段除外） | 内容要与上游一致，否则溯源失效。本版只允许改写导入地址所必需的 `SKILL.md` `name` 与 zip 根（做 #15），其它字段一律不动；平台需要的来源事实写在平台自己的表里 | 不做；没有计划 |
| 11 | 同步的 CLI 命令 | 同步不是用户在界面上做的一次操作，[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 不要求；本版也不新增控制台操作 | 有同步管理页时一起做 |
| 12 | 许可变更提醒表或通知 | 变更与冲突写在草稿说明里，由运营按说明筛选；另建一套状态要维护，收益不抵成本（2026-11-06 用户定） | 同步条目变多或运营不止一人时 |
| 13 | 收藏、排序、评测卡 | 与本版无关 | 见各自待办 |
| 14 | 在 `select: all` 里为单个 skill 指定别名 | 本版只给单 skill 条目 `name:` 裸名例外；给批量条目逐个配名字会让清单与上游目录脱节，冲突规则也复杂 | 来源变多、需要逐条策展时 |
| 15 | 仅许可变化、skill 内容不变的同步 | 版本由内容摘要定义，内容相同无法建新版本；等下一次内容 commit 时新许可自然生效（2026-11-06 用户定） | 模型支持元数据版本时 |
| 16 | **stars / forks / 最近提交的每周刷新** | 本版只负责「不每日刷、发布与回滚也不刷」：这三个数在同步建草稿时定稿（取不到时留 `pending`，发布后由每日任务补）。改成每周刷新要先定周任务的 cron、失败重试与「多久算陈旧」的展示口径（信息页 / CLI 要不要显示快照时间），不阻塞本次导入（2026-11-07 用户定） | [待办第 15 条](../../current/TODO.md)：v50 收口后开一条小版本 |
| 17 | 发布后异步补刷新来源信号 | 手工上传（非同步）的 skill 没有「同步时刻」，信号只能等每日任务补。再引入一条「发布后异步刷新」会把第三方依赖塞回发布路径，与做 #16 的「状态在同步时定稿」冲突 | 若手工上传的来源信号长期为空，再并入周任务 |
> **编号缺口与调整**：不做 #6 已移入「做」#12（手动触发，2026-11-06 用户定）；#10/#14 在 2026-11-07 因导入重命名调整了边界（见做 #15），编号不复用。**#18「批量发布超过 30 秒被截断」已于 2026-11-07 由 [迭代 0003](iterations/0003-批量发布超时与同步上限.md) 处理，移出本表**；剩余容量问题见迭代 0003 §影响。

## 拥有面

| 要动的文件 / 面 | 是不是共享面 | 谁在用 |
|---|---|---|
| `skillmaster-server/src/main/java/com/skillmasterai/config/SyncSourcesLoader.java`、`usecase/sync/SyncEntry.java` | 否（v50 同步面） | 清单解析 |
| `skillmaster-server/src/main/java/com/skillmasterai/usecase/sync/SyncPipeline.java`、`SyncDraftWriter.java`、`SkillNameTransform.java`、`PreparedEntry.java` | 否（v50 同步面） | 预检、许可/扫描、命名改写、写草稿 |
| `skillmaster-server/src/main/java/com/skillmasterai/modules/skill/SourceRepository.java`、`SourceService.java` | **是**（v49 来源读取共用；加只读查询与草稿阶段的来源写入 `recordSynced` / `markSyncSnapshot`） | v49 信息页、同步 |
| `skillmaster-server/src/main/java/com/skillmasterai/usecase/sync/GitHubContentSource.java`、`modules/skill/GitHubContentReader.java`、`GitHubSourceAdapter.java` | **是**（v49 来源读取与刷新共用） | v49 信息页与刷新、同步 |
| `skillmaster-server/src/main/java/com/skillmasterai/usecase/PublishSkillVersionUseCase.java` | **是**（所有发布/回滚都走它） | 控制台发布、CLI 发布、同步草稿发布、回滚 |
| `skillmaster-server/src/main/java/com/skillmasterai/modules/skill/SourceRefreshJob.java` | **是**（v49 每日任务） | 来源信号兜底 |
| 同步测试（`SyncSourcesLoaderTest`、`导入端到端用例`、`同步命名用例`、`SkillNameTransformTest`） | 否 | — |
| 发布与来源刷新的既有测试（`PublishSkillVersionUseCase`、`SourceRefreshJob`、`SourceService` 相关） | **是**（这些用例的行为改了） | 发布、回滚、来源兜底 |
| `deploy/sync-sources.yml`、`deploy/README.md` | **是**（部署清单，运营与部署共用） | 运营、部署工作流 |
| v50 `README.md` / `prd.md` / `technical-design.md` / `test-plan.md`、新增 ADR | 文档 | — |
| 控制台、CLI、`design/` | **不改** | — |

## 控制台操作与 CLI 对应

**本版新增控制台操作：0。** 同步是服务端定时任务；审核与发布沿用现有流程，已有对应命令。

| # | 操作 | CLI 命令 | 备注 |
|---|---|---|---|
| — | 无新增控制台操作 | — | 发布沿用 `skmr version publish` / `skmr skills publish`（打开网页确认），可见性沿用 `skmr skill visibility` |
| — | 手动触发一次同步 | **不加 CLI** | 由 GitHub Actions 的 `sync-trigger.yml`（`workflow_dispatch`）经部署 SSH 调本机回环端点；用户确认时间是 2026-11-06 |

导入重命名会改变同步产物的地址（例如 `skillmaster/mattpocock-test-driven-development`），但控制台与 CLI 仍按通用的 `namespace/name` 读取，**本版不改前端与 CLI 代码**；草稿名变化属于数据，不需要新的操作对应。

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| A1 | 清单内一条合规的 GitHub skill 首次同步后，官方命名空间出现该 skill 与一个**草稿**版本；除按配置重命名 `SKILL.md` 的 `name` 外，草稿内容与上游目录一致 | T1 |
| A2 | 该 skill 的来源可追溯：`skill_source` 为 `kind=github`、`locator` 与 `path` 与清单一致；对应版本的快照 `revision` 等于该 skill 目录最近一次上游 commit（变化检测身份；内容取自本轮仓库 tip 的整包，见做 #9）；发布后 `skmr skill show` 显示 GitHub 来源与许可 | T2 |
| A3 | 同一上游 commit 再同步一次，不新增版本、不改已有草稿内容 | T3 |
| A4 | 上游产生新 commit 后同步，出现一份新草稿；发布后版本号为下一个补丁号；已发布版本与线上内容不变 | T4 |
| A5 | 识别出 GPL-3.0、AGPL-3.0、`NOASSERTION` 或其他非白名单许可时，不建草稿并记录原因；线上内容不受影响 | T5 |
| A6 | 完全没有许可（没有文件、`SKILL.md` 也没声明）时，建草稿且平台上的许可为空；草稿说明写「未声明」 | T6 |
| A7 | 文件与声明不一致、且文件值在白名单时，以文件值建草稿且草稿说明写「待校准」；上游产生新内容 commit 且本轮那棵树（默认分支 tip）里的许可被取消时，新草稿许可为空且草稿说明写「已取消」。仅根许可变化、内容不变时不触发同步（见不做 #15） | T7 |
| A8 | 命中扫描阻断规则（如硬编码凭据、下载即执行）时，不建草稿并记录原因；只命中警告规则时建草稿且记录 | T8 |
| A9 | 同步产物在运营发布并设为公开之前不出现在公域；运营用官方账号能在现有控制台看到并发布 | T9 |
| A10 | 上游不可达、超时等**运行时**失败只记为 `failed` 并记录，其它条目继续；**清单文件或条目的静态格式错误在启动时拒绝**（见做 #1 / T15）。两种情况都不改变已有 skill 与线上版本 | T10、T15 |
| A11 | 不在清单里的仓库不会被同步；同步不写入任何非官方命名空间 | T11 |
| A12 | **底座与来源无关**：用测试替身适配器能完整跑通清单、许可闸、扫描、幂等与写入；底座代码不直接依赖 GitHub | T12 |
| A13 | **手动触发**：Actions 工作流触发一次同步并返回逐条摘要；同一时刻第二次触发返回冲突；错误 token 返回 401；公网访问 `/internal/` 被拒绝 | T14 |
| A14 | **并发度**：清单 10 条时全部处理且结果与串行一致；设为 1 时按清单顺序处理；0、17 等非法值启动拒绝（默认值见 `config.md`）；同一轮出现重复平台地址时后一条记为 `blocked` 并记录 | T16、T15 |
| A15 | **旧草稿清理**：上游新 commit 建出新草稿后，同一 skill、同一来源的旧未发布同步草稿变为 `discarded`；只保留一份未发布的同步草稿 | T17 |
| A16 | **丢弃/撤销不重建**：运营丢弃或撤销一份同步版本后，同 revision 再同步不会重建草稿 | T18 |
| A17 | **目录展开**：`select: all` 把父目录下每个直接子目录当成一条 skill；`select: [a,b]` 只取列出的；没有 `SKILL.md` 的子目录跳过并记录；`select` 缺 `path`、名字不合法或适配器不支持列子目录时启动拒绝 | T21 |
| A18 | **跨来源同名**：两个不同来源有同名 skill 时，若至少给一个来源配了不同 `prefix`（或单 skill `name`），平台生成两个不同地址，都能建草稿；两个都不配时，后到的仍按现有规则 `blocked`，不静默改名 | T23 |
| A19 | **前缀校验**：`prefix` 可选；配了必须符合 Agent Skills 名称规则，且不能是 `skillmaster` 或以 `skillmaster-` 开头；`prefix` 与单 skill `name` 互斥；**首次分配**时组合后超过 64 字符、含非法字符、是保留名或被另一个来源的 origin 占用时该条 `blocked` 并记录原因；已分配地址的条目改配置、且上游有新 revision 时只记 `address_kept`、不改地址（无新 revision 时整条 `unchanged`，见 [test-plan](test-plan.md) §已知问题）；`select` 条目带 `name` 时启动拒绝 | T15、T23 |
| A20 | **地址稳定**：同一 `(kind, locator, path)` 的新 revision 沿用第一次分配的平台名；上游 `SKILL.md` 改 `name` 不新建 skill；改 `prefix` 不静默改名；上游移目录是新的 `path`，算出的地址被另一个来源的 origin 占用时 `blocked` | T24 |
| A21 | **只改 name**：平台名与上游 `name` 不同时（配了前缀/别名，或地址已分配而上游改名）只改 `SKILL.md` 的 `name` 与 zip 根，正文与其它文件逐字节不变；相同时完全不改写；`skill_source` 保留原路径，上游原名只写进草稿说明、不单独落库（二期检索需要新增字段，见 [test-plan](test-plan.md) §已知问题）；单 skill 条目的 `name:` 裸名例外生效，被另一个来源的 origin 占用时阻断 | T23、T24 |
| A22 | **同步时快照定稿**：同步建出草稿后，该版本的来源快照已有 revision 与 stars / forks / 最近提交，取得到信号时 `status=ok`、`written_by_sync` 为真；许可已在草稿时落定；三个信号与上游仓库当时的值一致，日期取上游值而不是本机时刻 | T25 |
| A23 | **仓库元数据每轮每仓库一次**：同一 `locator` 下 10 个 skill 都建草稿时，仓库元数据接口只被调用 1 次（测试替身计数）；全部条目都因 revision 未变而跳过时整轮不调用它（replay 的条目 revision 是新的，会取一次）；取不到元数据不影响建草稿 | T25 |
| A24 | **发布与回滚不调 GitHub**：对同步进来的版本，发布、批量发布、回滚过程中来源适配器的调用次数为 0，快照与草稿时逐字段相同（不因发布而改写 revision、信号或状态），发布耗时不含取来源的时间；手工上传的版本仍按现有规则补一条 `pending` 快照，但不发网络请求 | T26 |
| A25 | **按 skill 目录判断变更**：仓库别处有新 commit 而某 skill 目录的 revision 没变 → 该条 `unchanged`，不建草稿、不更新该 skill 的快照、不下载该目录；该目录变了才建草稿并写下新快照 | T27 |
| A26 | **每日任务只补缺口**：每日任务只处理**当前线上版本**里 `pending` / `error` / `stale` 的快照，`ok` 的快照再旧也不重取；仓库元数据取不到时草稿照建、快照留 `pending`，草稿发布后由每日任务补齐（草稿期间不进每日任务）；手工上传的 skill 仍由它补信号 | T28 |

**成立条件**：A5、A8、A9 不满足就不算交付——许可闸、安全闸与人工发布是这一版存在的理由；缺一条就等于把未经审查的开源内容放进公域。

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| 同步成功率 | 本次运行中「建成草稿或确认无变化」的条目 ÷ 展开后的 skill 条目总数（`select: all` 的父条目不计入） | 同步日志（本版不建结果表） | ≥ 90%（上游故障除外） |
| 上游更新时延 | 上游 revision 时间到平台草稿出现的时间 | 按 revision 查上游 commit 的 committer date，与草稿 `created_at` 相减 | ≤ 一个调度周期（默认值见 `config.md`）；首次纳入从纳入时刻起算 |
| 闸门拦截数 | 被许可闸或扫描阻断的条目数，按原因分组 | 同步日志 | 记录即可，不设目标 |
| 发布耗时 | 单条发布从请求到响应的服务端时间，含内容首次上线时的一次向量计算（同一内容已有向量则跳过）（本机 53 条批量，2026-11-07 **改动前**实测约 2.2–2.9 秒，其中几乎全是发布后刷新；改动后的值尚未实测） | 批量发布的逐行时间戳 | ≤ 0.5 秒；53 条不再触发流式接口的 30 秒超时 |

## 依赖与前置

- **外部依赖**：GitHub 公开 API 与 `codeload.github.com`。2026-11-06 在生产 ECS 实测：`api.github.com` 200/0.28s；`raw.githubusercontent.com` 需跟随重定向且 3.4s（不用它）；`codeload` 取真实仓库 2.0 MB / 2.45s；未认证额度 60 次/小时。每个**展开后的 skill 条目**每个周期只花 1 次 commits API（判断该目录有没有变）；**包含变更条目的仓库每轮至多下载 1 次 codeload 整包**（做 #9，[ADR 0163](../../decisions/0163-a-repository-is-downloaded-once-per-run.md)），全部条目都没变时零下载。仓库元数据（stars / forks / 最近提交，供快照）每个 `(kind, locator)` 每轮最多 1 次，条目通过全部闸门后取：全部条目都无变更时一次也不取；replay 或写入失败的条目可能先取一次，上限不变。**启用同步时必须配 `SKILLMASTER_GITHUB_TOKEN`**（2026-11-06 用户定），把额度提到 5000/小时，避免清单变大或频繁手动触发时被限流。官方账号的登录凭据由运营者持有。
- **手动触发前置**：GitHub Actions 的 `workflow_dispatch`；现有 `DEPLOY_*` SSH 密钥；服务器与 Actions 共用一个触发 token（`SKILLMASTER_SYNC_TRIGGER_TOKEN`）。
- **内部前提**：
  - [ADR 0146](../../decisions/0146-public-is-platform-endorsement.md)：公开是平台背书，公域内容只能由官方收进来——本版是它点名的「官方收录」入口。
  - [ADR 0155](../../decisions/0155-open-source-sync-passes-three-gates.md)：许可白名单与未声明留空、安全扫描分级、人工发布闸，以及冲突写进草稿说明的口径。
  - [ADR 0156](../../decisions/0156-sync-writes-as-the-official-namespace.md)：官方写入身份与上游 commit 到平台版本的映射。
  - [ADR 0157](../../decisions/0157-sync-is-a-source-neutral-pipeline.md)：同步底座与来源适配器分开，加来源不改底座。
  - [ADR 0160](../../decisions/0160-imports-are-renamed-by-an-optional-source-prefix.md)（2026-11-07）：导入时按可选来源前缀重命名，只对 `SKILL.md` 的 `name` 字段开例外；推翻本版不做 #10/#14 原先的「一律不改写」边界。
  - [ADR 0161](../../decisions/0161-imported-skill-state-is-fixed-when-the-draft-is-created.md)（2026-11-07）：同步来源的状态在**建草稿时**定稿，发布与回滚不读 GitHub；取代 v49 技术设计 §刷新时机里「发布后刷新」「每日全量刷新」「`declared-only` / `missing` 每日重试」三条。
  - [ADR 0144](../../decisions/0144-a-new-account-starts-with-the-official-skills.md)：清单放服务端配置——同步清单沿用同一做法，不进数据库。
  - v49 的来源、快照与许可模型；v48 的「只有官方命名空间能公开」。
- **排除了什么前置**：不需要先做「命名空间成员可写」——同步走服务端内部用例，不依赖账号切换；也不需要先做通用任务队列——沿用现有 `@Scheduled` 单实例约定。

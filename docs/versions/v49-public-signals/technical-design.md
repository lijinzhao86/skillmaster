# v49-public-signals · 技术设计

> **最后更新**：2026-11-06
> **状态**：公域信号、使用度量（M15）与控制台 P3 已实现（2026-11-06）；验证结果见 [`test-plan.md`](test-plan.md) §结果
> **所属版本**：[`README.md`](README.md)

## 架构

相对 [`docs/current/`](../../current/README.md) 的差异：**新增一个模块 M15 `metrics`，没有新路由**。公域信号新增三张表（来源绑定、来源快照、版本许可）；使用度量由 M15 拥有 `skill_usage_day` / `skill_usage_total`，公域读模型只通过它的接口取数。模块边界的取舍见 [ADR 0153](../../decisions/0153-usage-metrics-is-its-own-module.md)。

**两个需求单元，两个模块：** 公域决定「哪些加载算公域信号」，度量决定「怎么记、怎么聚合」。它们不共享表，也不共享仓储类。

### 公域信号（M12 `skill` + 读模型）

**核心判断：来源是「有类型的连接 + 适配器解释的通用信号」，不是每个平台一列。**

```text
skill_source            一条连接：origin / mirror + kind + locator
  └─ skill_source_snapshot   某个版本上，适配器看到的快照
       └─ signals            [{key,label,kind,value,display,url}] 通用数组

skill_version_license   某个版本适用的许可（SPDX / 版权 / 全文指针 / 状态）
  └─ license_text      全文存在许可行里，不挂 version_file
```

**加一种来源只加适配器**：实现 `SourceAdapter`、注册 `kind`、补测试。表结构、API 字段、控制台组件、CLI 格式化都不改。

**许可证与来源分开。** 来源回答「从哪来」；许可回答「能不能带走、要保留什么」。二者可以一起展示，但存储与判定互不依赖。

### 使用度量（M15 `metrics`）

```text
UsageRecorder.record(SkillLoad)      一次加载的事实：skill / account / 天 / scope
  └─ skill_usage_day                 (skill, day, account, scope) 去重
  └─ skill_usage_total               累计调用次数，(skill, scope) 一行

UsageQuery.snapshots(ids, scope)     普通值：分桶下限（不返回精确数）
```

- **M15 不认识公开 / 私有**，只认识 `scope`（度量的用途）。本版只有一个取值 `public`。
- **策略在公域侧**：`ReadSkillUseCase` 判断「公开、有线上版本、非作者」，成立才把 `SkillLoad(scope=public)` 交给 `UsageRecorder`。
- **依赖方向**：编译期是「公域 → M15」，M15 不 import `skill` 模块。入库数留在 M12，由读模型拼装，不进度量模块。
- **故障隔离**：读用例只返回「这次可计数」的标记；`REQUIRES_NEW` 的记录调用在**读事务结束之后**由 API 层发起，失败只记日志。这样度量既不回滚读，也不会在读事务里再占一条连接。

## 数据模型

### `skill_source`

```sql
CREATE TABLE skill_source (
  id         TEXT PRIMARY KEY,
  skill_id   TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  role       TEXT NOT NULL CHECK (role IN ('origin', 'mirror')),
  kind       TEXT NOT NULL,            -- official / github / skillhub / ...
  locator    TEXT NOT NULL,            -- owner/repo、平台 slug、官方地址
  path       TEXT NOT NULL DEFAULT '', -- 仓库内 skill 目录，如 skills/ab-testing
  label      TEXT NOT NULL,            -- 展示名
  url        TEXT,
  created_at TEXT NOT NULL,
  UNIQUE (skill_id, role, kind, locator)
);

CREATE UNIQUE INDEX skill_source_one_origin
  ON skill_source (skill_id) WHERE role = 'origin';
```

- 一个 skill 最多一条 `origin`，`mirror` 可多条。
- `kind` 由适配器注册表校验；未注册的 kind 在发布时拒绝，不落库。

### `skill_source_snapshot`

```sql
CREATE TABLE skill_source_snapshot (
  source_id        TEXT NOT NULL REFERENCES skill_source(id) ON DELETE CASCADE,
  skill_version_id TEXT NOT NULL REFERENCES skill_version(id) ON DELETE CASCADE,
  revision         TEXT,                -- GitHub commit / 平台版本
  signals          TEXT NOT NULL DEFAULT '[]',
  status           TEXT NOT NULL,       -- ok / stale / error / pending
  fetched_at       TEXT NOT NULL,
  PRIMARY KEY (source_id, skill_version_id)
);
```

- 快照绑**版本**：1.2.0 来自 commit A，1.3.0 来自 commit B，各自一条。
- 指标刷新只更新 `signals` / `status` / `fetched_at`，不改 `revision`——`revision` 是「这一版来自哪个 commit」，不是上游当前 HEAD。
- 失败保留上一次成功的快照（`signals` 与 `revision` 不动），`status=stale`；从没成功过才是 `error`。页面仍画来源；指标只在 CLI，`status` 不是 `ok` 时 `skill show` 省略指标行。信息页不显示校验时间，最近校验由 `skill show` 打印。

### `skill_version_license`

```sql
CREATE TABLE skill_version_license (
  version_id          TEXT PRIMARY KEY REFERENCES skill_version(id) ON DELETE CASCADE,
  spdx                TEXT NOT NULL,     -- MIT / Apache-2.0 / NOASSERTION
  name                TEXT NOT NULL,
  copyright           TEXT,
  status              TEXT NOT NULL,     -- ok / declared-only / missing / other
  license_text        TEXT,
  source_path         TEXT,              -- 上游路径：LICENSE / skills/x/LICENSE
  source_url          TEXT,
  detail              TEXT NOT NULL DEFAULT '{}',
  fetched_at          TEXT NOT NULL
);
```

- **全文不进 `version_file`**：它不参与内容摘要、不出现在 `skill ls`、不改变导入后的 digest。
- 全文存在 `license_text` 里，不挂 `version_file`：许可文本很小，而 blob GC 只跟随 `version_file`，挂在那里会被误删；去重收益不值得多一条引用规则。
- `status` 四态：`ok`（有全文）、`declared-only`（只有 SKILL.md 声明）、`missing`、`other`。
- `detail` 保留作者的声明：`{"declared":"Apache-2.0"}`。有来源许可文件时以文件为准，声明不参与裁决；冲突拦截留到 下一版的入库流程。

### 使用度量（M15）

两张表由 M15 拥有（`ModuleMap` 的表归属从 M12 移过来）。`scope` 是**度量的用途**，不是可见性；本版只写 `public`。

```sql
CREATE TABLE skill_usage_day (
  skill_id   TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  day        TEXT NOT NULL,             -- UTC YYYY-MM-DD
  account_id TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  scope      TEXT NOT NULL DEFAULT 'public',  -- public（本版唯一取值）
  PRIMARY KEY (skill_id, day, account_id, scope)
);

CREATE TABLE skill_usage_total (
  skill_id TEXT NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
  scope    TEXT NOT NULL,
  calls    BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (skill_id, scope)
);
```

- 一条 `skill_usage_day` = 「这个账号在这一天用过这个 skill，且这次度量属于该 scope」；同一天重复调用不增行。
- 保留期按 scope 走：`public` 90 天，由 M15 的清理任务删除；`skill_usage_total` 不清理。
- 不存 IP、UA、调用的具体文件。
- `scope` 的取值由 M15 校验；未知 scope 的写入拒绝。
- 表结构变更由 `V37__usage_scope.sql` 完成：给 `skill_usage_day` 加 `scope`、新建 `skill_usage_total`。**不改已应用的 `V36`**（Flyway 校验和）。已有行由 `DEFAULT 'public'` 回填；默认值保留作兜底，应用层始终显式写 `scope`（M15 的枚举保证），不依赖默认值。
- `V38__legacy_licenses_keep_their_declaration.sql` 补 V36 回填的一个缺口：V36 没读 SKILL.md，所有存量官方版本都标成 `missing`。V38 只在 `missing` 且 `detail='{}'` 的占位行上，从版本自己的 frontmatter 取 `metadata.license`（其次顶层 `license`）并改成 `declared-only`；已被来源回答的行不动。不读 frontmatter 的旧版本仍如实 `missing`。

## 来源解析与刷新

### 写入路径

来源是**内容元数据**，写在 `SKILL.md` 的可选字段里，随版本走：

```yaml
metadata:
  source:
    kind: github
    locator: coreyhaines31/marketingskills
    path: skills/ab-testing
```

- 发布时解析：`kind` 必须在适配器注册表里；`locator` 由适配器 `parse` 校验并规范化。
- **官方命名空间且没有声明 source**：自动写 `kind=official`、`label=官方`、`locator=<namespace>/<name>`；不联网、无 signals。
- **声明了 source**：即使 skill 在官方命名空间，也写声明的 kind（例如 GitHub）。这正是官方背书的外部来源。
- 解析失败 → 发布拒绝，错误码 `invalid_source`；不落半条来源。

### 适配器接口

```java
public interface SourceAdapter {
    String kind();

    /** 校验并规范化 locator；不联网。失败抛 InvalidSourceException。 */
    SourceBinding parse(String locator, String path);

    /** 联网取快照；超时/限流/404 返回带 status 的结果，不抛到页面。 */
    FetchedSource fetch(SourceBinding binding, String revisionHint);
}
```

- `GitHubSourceAdapter`：locator `owner/repo`；调 `GET /repos/{owner}/{repo}` 读 `stargazers_count`、`forks_count`、`pushed_at`，`GET /repos/{owner}/{repo}/commits?path=<path>` 读该目录最近一次 commit。
- **许可按「最近的 LICENSE」找**：从 skill 目录 `path` 开始，逐级向上到仓库根，用 `GET /repos/{owner}/{repo}/contents/<dir>` 列目录、命中 `LICENSE` / `LICENSE.md` / `LICENSE.txt` / `COPYING` / `NOTICE` 就取该文件全文并按固定口径表识别 SPDX；一级都没有才退回 `GET /repos/{owner}/{repo}/license`。`sourcePath` 记录命中的文件路径，`sourceUrl` 用该文件的 `html_url`。
- `OfficialSourceAdapter`：不联网，`signals=[]`。
- 注册表是 `Map<String, SourceAdapter>`；重复 kind 启动失败。

### 刷新时机

| 时机 | 行为 |
|---|---|
| 发布后 | 事务提交后同步尝试一次，3 秒超时；失败写 `status=error`，不阻塞发布。**不在发布事务里做这次往返**：GitHub 慢时不占着行锁与连接池 |
| 每天一次 | `@Scheduled` 扫线上版本的来源快照，逐个刷新；单实例部署，不需要分布式锁 |
| 页面读 | 只读快照，不发网络请求；信息页不显示校验时间，`skill show` 打印「最近校验」 |

GitHub 未认证限额 60 次/小时；官方 skill 数量少，够用。配置可选 `SKILLMASTER_GITHUB_TOKEN` 提高限额；token 只在服务端。

**许可是版本级的、一次性落定。** 来源回答过之后（`ok` / `other`），刷新只更新快照，不再改许可：适配器读的是上游 HEAD，不是这一版对应的 commit，按 HEAD 重算会让上游后来的改许可改写老版本的说明。`declared-only` / `missing` 还没有源码答案，每日刷新继续重试首次解析。声明原文一直留在 `detail`（有文件时以文件为准，声明只作保留）。同一版本被重新发布或回滚时，已有的快照不重置为 pending；已落定的许可保持原样，占位许可（含 V36 回填的 `missing`）按这一版 frontmatter 补上声明。

### 信号契约

```json
[
  {"key":"stars","label":"Stars","kind":"count","value":"1234","display":"1.2k",
   "url":"https://github.com/owner/repo/stargazers"},
  {"key":"last_commit","label":"最近提交","kind":"date","value":"2026-10-30","display":"2026-10-30"}
]
```

- 最多 8 条；`label` 最多 12 字；`kind` 只有 `count` / `date` / `text`。
- `url` 由适配器生成；服务端只接受 `https`，页面不渲染 HTML。
- v49 只展示，不参与排序。

## 许可证解析与侧车

### 解析顺序

```text
skill 目录/License
  → 逐级向上找 LICENSE / LICENSE.md / LICENSE.txt / COPYING / NOTICE
  → 仓库根为止
  → 同时读 SKILL.md 的 license 声明
```

规则：

1. 最近的许可文件优先；记录 `source_path`。
2. SKILL.md 的 `license` 是声明，不是全文。
3. 有来源许可文件 → `status=ok`（文件的值）；只有声明 → `declared-only`；都没有 → `missing`；不在固定表 → `other`。声明与文件不一致时**仍然用文件的值**，本版不判冲突。
4. `missing` 不导出到公域（开源同步那版的发布闸）；本版只展示状态。冲突拦截与校验属于 下一版的入库流程。

### 侧车生成

`.skillmaster/` 是**平台生成的元数据目录**，不是版本内容：

```text
.skillmaster/
  license.json      结构化：spdx / copyright / status / origin / license_file
  LICENSE           全文（上游正文没有许可文件时才写）
  attribution.md    来源、路径、commit、版权、修改说明
```

- **zip**：归档流在写包根时插入这三个条目，不读 `version_file`。
- **`skill get`**：下载的就是这个归档，所以侧车随包落地；CLI 不再自己拼一份。
- **`skill invoke`**：只是流式读正文，不落盘，因此不产生侧车。
- `skill ls` 默认过滤 `.skillmaster/`（保留目录）。
- 上游正文已有 `LICENSE` 时，保留原文件；`license.json.license_file` 指向它，侧车不再重复写 `LICENSE`。
- `.skillmaster/` 是保留名：上传内容里的同名目录被**剥掉**，不成为版本内容（[ADR 0148](../../decisions/0148-a-license-belongs-to-the-version-and-not-to-the-file-tree.md)）；唯一例外是 `.skillmaster/metadata.json`，它按版本元数据解析，内容不是合法元数据时才拒绝 `reserved_path`（[ADR 0069](../../decisions/0069-metadata-is-a-sidecar-not-content.md)）。

## 使用信号

### 计数点与策略

`ReadSkillUseCase.streamFile` 成功返回正文（`BODY_RELPATH`）时，由**公域侧**判断这次加载是否算公域信号：

- 公开、有线上版本、未下线；
- 调用者已登录（匿名读面不存在，ADR 0048 的现状）；
- 不是作者读自己的 skill。

成立才在 `StreamedFile` 里带上一个 `SkillUseCandidate`；**度量模块不重复这些判断**。同一次成功读同时写 `skill_usage_day`（去重）与 `skill_usage_total.calls`（累计），见 [ADR 0152](../../decisions/0152-cumulative-calls-are-their-own-signal.md)。

**故障隔离**：读用例只负责判断与返回标记，不写库；API 层在用例返回、读事务已提交之后调 `RecordSkillUsageUseCase`（`REQUIRES_NEW`），异常只记日志。这样度量失败不会回滚读，读事务里也不会嵌套第二笔事务——否则并发时一个请求会占两条连接，把「度量不影响读」反过来打破。

**每日封顶** = `skill_usage_day` 的「账号 × 天」去重；`skill_usage_total` 每次加载都 +1，不封顶（[ADR 0152](../../decisions/0152-cumulative-calls-are-their-own-signal.md)）。

不把「打开信息页」算作使用——那只是浏览元数据，不是加载 skill。

### 聚合与展示

```text
30 天不同账号数 = SELECT count(DISTINCT account_id)
                  FROM skill_usage_day
                  WHERE skill_id = ? AND day >= today-29
```

| 值 | 显示 |
|---|---|
| 0–9 | 「少于 10 个账号用过」 |
| 10–99 | 「10+ 个账号用过」 |
| 100–999 | 「100+ 个账号用过」 |
| 1000+ | 「1k+ 个账号用过」 |
| 近 30 天有记录 | 追加「近 30 天有人用」 |

```text
累计调用分桶 = skill_usage_total.calls
```

| 值 | 显示 |
|---|---|
| 0 | `—`（行上不写数；页头的证据行不画） |
| 1–9 | 「少于 10 次调用」 |
| 10–999 | 「10+」/「100+」 |
| 1000+ | 「1k+」/「1万+」/「100万+」 |

**只传分桶，不传精确次数**——与账号分桶同一条口径，避免把「用了多少次」变成可被逐次核对的流水。

加入数直接读 `skill_library_join` 的生效条目，不另建表。

**`lastUsed` 取保留期内（90 天）最后一次有记录的日期**，不是 30 天窗口内：它回答「最近有没有人用」，不是「近 30 天有没有」。

**两个已知且接受的口径**：账号删除时 `skill_usage_day` 随外键级联消失，`skill_usage_total` 保留，历史累计仍包含那次加载；客户端重试会让累计次数加两次，去重表不受影响——度量接受这一点，不为它加幂等键。

## 接口

### 使用度量接口（M15，模块间）

```java
public enum UsageScope { PUBLIC }

public record SkillLoad(String skillId, String accountId, LocalDate day, UsageScope scope) {}

public record UsageSnapshot(
        boolean hasCalls,            // false = 这个 scope 下没人用过
        int callsBucketFloor,        // 0 / 10 / 100 / 1000 / 10000 / 1000000
        int invokers30dBucketFloor,  // 同一套下限
        LocalDate lastUsed) {}

public interface UsageRecorder {
    /** 一次加载；重复由主键兜底，调用方不必先查。 */
    void record(SkillLoad load);
}

public interface UsageQuery {
    /** 批量取；没有记录的 skill 不出现在结果里。 */
    Map<String, UsageSnapshot> snapshots(Collection<String> skillIds, UsageScope scope);
}
```

- **只发布这几个类型**；仓储与 SQL 在 `modules/metrics/internal/`，外部不引用。
- **不返回精确账号数**：分桶下限是唯一离开模块的形式（A6）。分桶下限是**隐私口径**，住在 M15；把下限渲染成 `"1k+"` 这类文案是读模型的事。
- 新增 scope 是 M15 的枚举与策略扩展，不是新表；按 [ADR 0153](../../decisions/0153-usage-metrics-is-its-own-module.md) 需要一条新 ADR。

### 读模型

`SkillMetaResponse` 与 `SkillDetailResponse` 各加三个可选块（`origin` / `license` / `usage`）；`SearchResponse.Card` 加紧凑的扁平字段（`origin_label`、`origin_kind`、`origin_locator`、`origin_url`、`license_spdx`、`license_status`、`usage_bucket`、`calls_bucket`、`last_used`、`joins`），列表不挂整块：悬停名是 `origin_label · origin_locator`（如 `GitHub · owner/repo`），`origin_url` 是点击来源标识要打开的网页。

```json
"origin": {
  "kind": "github",
  "role": "origin",
  "label": "GitHub",
  "locator": "coreyhaines31/marketingskills",
  "path": "skills/ab-testing",
  "url": "https://github.com/coreyhaines31/marketingskills",
  "revision": "8f3c2a1",
  "checked_at": "2026-11-05T...",
  "status": "ok",
  "signals": [ ... ]
},
"license": {
  "spdx": "MIT",
  "name": "MIT License",
  "copyright": "© 2025 Corey Haines",
  "status": "ok",
  "url": "https://github.com/.../LICENSE",
  "allows": ["commercial", "modify", "redistribute"],
  "requires": ["notice"]
},
"usage": {
  "bucket": "100+",
  "calls_bucket": "1k+",
  "last_used": "2026-11-05",
  "joins": 45
}
```

- **本版没有镜像来源**：API 不返回 `mirrors`，CLI 不打印「也在」；镜像留在数据模型里，等同步那版再接。
- `usage` 由读模型在 `api` 层拼装：M15 给分桶、M12 给入库数；两者只出普通值，不在模块间互相 import，也不在这里开跨模块事务。
- **不下发精确账号数**：`bucket` 就是账号分桶（0–9 为「少于 10 个账号」），没有 `invokers_30d` 字段（A6）。
- `usage.calls_bucket` 是累计调用次数的分桶（不是精确次数），与 `bucket` 同一套口径；列表卡片以 `calls_bucket` 作为行上的调用量。
- `usage` 在 `/metadata` 与详情上**总是返回**（没人用过时 `bucket` 就是「少于 10 个账号」）；列表卡片在计数全为 0 时省略整组，避免每张新卡都挂一行噪声。
- **「还没有人用过」的判据**：`calls_bucket` 与 `last_used` 都缺省、`joins` 为 0；CLI 按这一组事实判断，不另加字段。
- **API 返回不等于界面展示**：私有 skill 也有 `usage` 块，控制台按可见性决定画不画（私有 skill 不画，P3）。
- **外部来源的字段名是 `origin`，不是 `source`。** 现有 `Card.source` 表示库来源（我创建的 / 共享给我的 / 我加入的），两者语义不同，不改旧字段，避免客户端把「从 GitHub 来」与「从我的库来」混在一起。
- 旧客户端忽略新字段。

### 来源写入

没有面向用户的新写路由。来源随发布解析：

```text
POST 发布
  → 解析 SKILL.md metadata.source
  → SourceRegistry.parse(kind, locator, path)
  → 写 skill_source(origin) + 快照(pending)
  → 发布成功
  → 后台 fetch → 快照 ok / error
```

### CLI

- `skmr skill show`：输出顺序固定为「地址 → 描述 → 适用场景 → 运行环境 → 版本 → 分类 → 公开标签 → 来源 → 对应版本 → 指标 → 最近校验 → 许可证 → 使用 → 我的标签」；空字段不打印。**指标只在快照 `status=ok` 且有 `signals` 时打印**；**最近校验只打日期**；**许可只给名字与状态**，要点与全文在 `skill license`。

  ```text
  skillmaster/ab-testing
    设计、规划或实现 A/B 实验，判断两个方案哪个更好。
    适用场景：想比较两个版本、需要一个可解释的实验计划时
    运行环境：需要 Node.js 18+
    版本：2.0.4
    分类：数据
    公开标签：experiment、conversion
    来源：GitHub · coreyhaines31/marketingskills
    对应版本：commit 8f3c2a1
    指标：Stars 1.2k · 最近提交 2026-10-30
    最近校验：2026-11-05
    许可证：MIT
    使用：1k+ 次调用 · 100+ 个账号用过 · 最近使用 2026-11-05 · 入库 45
    我的标签：growth
  ```

  变体：官方来源只写「来源：官方」，不打印「对应版本」与「最近校验」；许可状态是 `declared-only` 时写「MIT（仅声明，未附全文）」；`missing` / `other` 只写状态；没有使用记录写「还没有人用过」。`我的标签` 只在调用者自己的库条目生效时打印标签；不生效时打印一句原因，不打印标签（理由行是既有行为）。

- `skmr skill license`（**新增**）：读 `/metadata` 的同一个 `license` 块，**没有新接口**。输出「许可证 → 状态 → 版权 → 可做 → 要履行 → 全文」，空字段不打印；四种状态的说法与 PRD 的固定表同一套（`ok` 给允许与义务，`declared-only` 不给，`missing` 一句说明，`other` 只给全文）；页面只写许可名，状态词不上页面。**不打印全文本身**（[ADR 0151](../../decisions/0151-full-license-text-links-to-its-source.md)）。

- `skmr skills search --verbose`：每条结果多一行 `公域来源 · 许可证 · 使用分桶`，不展开许可全文与版权行。「公域来源」不叫「来源」：同一条 verbose 行上已经有「来源」表示调用者与这个 skill 的库关系（我创建的 / 共享给我的 / 我加入的），两个字段同名会读成一件事。
- `skmr skill get`：整版下载时带 `.skillmaster/` 侧车；`skill ls` 过滤 `.skillmaster/`。**`skill invoke` 不写任何文件**（见上一条），没有侧车。
- 没有新写命令；来源不能用 CLI 改（随内容版本走）。
- **不再打印 `score`**：`verboseLine` 去掉相似度分支，`declarations_other.go` 的帮助文案同步改；服务端响应仍带 `score`，只用于排序与测试，CLI 不把它当展示字段（[ADR 0095](../../decisions/0095-semantic-retrieval-is-accepted.md) §理由：0.42 的相似度对人没有意义）。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | `skill_source` / `skill_source_snapshot` / `skill_version_license`；`SourceAdapter` 与 GitHub/Official 适配器；发布解析；读模型字段；许可解析与状态 | A1、A2、A3、A11 |
| P1 | `.skillmaster/` 侧车生成（zip / `skill get`）；`skill ls` 过滤 | A12 |
| P2 | **M15 `metrics`**：模块骨架与表归属；`V37` 加 `scope` 与 `skill_usage_total`；`UsageRecorder` / `UsageQuery`；读路径策略与 `RecordSkillUsageUseCase`（读事务之后的独立事务）；读模型拼装；CLI `--verbose`；CLI 停止打印相似度；申请先入库 | A5、A6、A13、A16、A17 |
| P3 | 控制台卡片 / 信息页；设计件；文档与生成视图 | A4、A7、A8、A9、A10、A14 |

A1、A5、A6、A11 的控制台展示部分随 P3 的页面一并验；P2 只交付它们的服务端与 CLI 部分。

## 开放问题

需求阶段 1 条待定（Q11）；本关新增 1 条未定（Q10，不阻塞本版），其余已定。

| # | 问题 | 结论 | 状态 |
|---|---|---|---|
| Q1 | 来源写在哪？ | SKILL.md 可选元数据，随版本走；官方无声明时自动 `official` | 已定 |
| Q2 | 是否真的调 GitHub API？ | 调；发布后同步尝试 + 每日刷新；失败不阻塞页面 | 已定 |
| Q3 | 使用信号按调用还是调用 + 加入？ | 三个都算：累计调用进 `skill_usage_total`、账号去重进 `skill_usage_day`、入库读现有库表 | 已定 |
| Q4 | 阈值？ | 少于 10 个不同账号只写「少于 10 个账号用过」 | 已定 |
| Q5 | 官方 skill 没声明许可怎么办？ | 如实 `missing`，不默认 MIT；默认平台许可另开决定 | 已定 |
| Q6 | 仓库 LICENSE 与 SKILL.md 不一致？ | 本版不判冲突：有来源文件就用文件的值，声明只作保留；冲突拦截与入库校验放到下一版（许可逻辑） | 已定（2026-11-06 用户定） |
| Q7 | 许可全文放哪？ | 存在 `skill_version_license.license_text`，不挂 `version_file`；下载/安装生成 `.skillmaster/` 侧车 | 已定 |
| Q8 | 来源快照刷新失败怎么展示？ | 保留上次成功值，`status=stale`；CLI 在 `status` 非 `ok` 时省略指标行；最近校验只在 `skill show` 打印 | 已定 |
| Q9 | 使用度量的写入用事务内直写还是提交后事件？ | 直写 + `RecordSkillUsageUseCase`（`REQUIRES_NEW`）：仓库没有事件基础设施，且记录的语义本来就是「查找成功即计一次」，不等投递确认 | 已定 |
| Q10 | 将来 `owner` scope 的保留期与展示授权怎么定？ | 与 `public` 分开定；开 scope 时一并定「谁能看」与保留期，并写进那条 ADR | 未定（不阻塞本版） |
| Q11 | CLI 拿不到来源 URL，怎么和控制台的「打开来源网页」对齐？ | 二选一：`skill show` 的来源行后面补 URL，或给 `skill show --json`；不做两个 | 待定（不阻塞本版） |

## 被否掉的做法

- **每个来源加列**（`github_stars`、`skillhub_installs`）：表、API、页面都要跟着改，且无法表达新平台。
- **signals 用自由 JSON 对象**：页面要按平台判断字段；改成约定数组。
- **许可全文塞进 `version_file`**：污染内容摘要与 `skill ls`，导入后的 digest 与上游不可比。
- **只写 `license: MIT`**：不满足 MIT 的声明保留义务。
- **页面实时请求 GitHub**：把第三方可用性绑进读路径；用快照 + 每日刷新。
- **把「打开信息页」算使用**：浏览不是加载，会把流量当效果。
- **把相似度打印给用户**：它是内部排序细节，两位小数会让人以为分数有产品含义；服务端保留 `score`，CLI 不展示（ADR 0095 的理由仍然成立）。
- **M15 的取舍**（留在 M12、读路径同事务直写、记录所有加载、每个 scope 一张表）：见 [ADR 0153](../../decisions/0153-usage-metrics-is-its-own-module.md)。

# v7-cli-file-io · 技术设计

> **最后更新**：2026-10-20
> **状态**：**待写**（PRD 已写；本文件在 PRD 确认后填写）
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。

<!--
本章之外的硬约束：
- 测出来的数字必须带口径（见下方表格的「口径」列），让读者能自己复现。
- 引用外部事实必须带出处，且区分一手与二手。
- 不复述 ADR 的理由；不负责任何产品决策的论证。
-->

## 架构

待写。已定的一部分：

- **服务端是唯一真相**：文件命令不落本地副本（[ADR 0001](../../decisions/0001-server-authoritative.md)、[ADR 0035](../../decisions/0035-the-pin-lives-on-the-client-machine.md)）；`cat` 只把响应体流到 stdout。
- **读的两条面按凭据分，不按状态分**：`browse` 及以上走作者面（`/api/v1/author/skills/{ns}/{name}/versions/{pin}/files/…`，任何该档可读的状态）；`invoke` 档走消费面，只能按路径取单个文件，没有清单（[ADR 0047](../../decisions/0047-permissions-are-a-ladder.md)）。**今天不是这样**：`read` 按钉的类型/状态选面（草稿钉走作者面；其余走消费面，只在 `version_revoked` / 404 时回落作者面），`authorPlaneAnswers` 也不认 503；按凭据分面、把 503 留给作者面，是本版要为 `ls` / `cat` / `wc` / `grep` 接上的行为。
- **grep 在服务端按版本作用域扫描，只作用于草稿**（`write`+ 能读到的草稿），固定串先在数据库内预筛（[ADR 0062](../../decisions/0062-grep-is-a-scoped-scan-not-an-index.md)）。
- **A/B 分界**：`cat` 是唯一的内容原语；`head` / `tail` / `sort` / `uniq` / `awk` / `sed` 由系统命令执行；`grep`（递归）、`wc` 由服务端执行。

## 数据模型

待写。已知约束：

- 不改表也能做：草稿行的 `digest` / `file_count` / `total_bytes` 随写更新，`version_file` 行按主键增删改。
- **`UNIQUE (skill_id, digest)` 会与编辑撞上**：把草稿改成与同 skill 某一版相同的内容会违反约束，答案要在 TD 里定死（拒绝并指明已有版本，或别的处理）。
- **v6 的不变量要改写**：v6 是「内容不可变，状态/名称/说明可变」；本版变成「发布后的内容只插不改；草稿的 `digest` / `file_count` / `total_bytes` 随写更新」——要落成新 ADR。
- 并发原语二选一：用 `digest` 做 `If-Match`（不加列），或给 `skill_version` 加修订号（要迁移）。

## 接口

待写。已知必须有的：

| 方法 | 路径 | 作用 | 鉴权 |
|---|---|---|---|
| `GET` | `/api/v1/author/skills/{ns}/{name}/versions/{pin}/files/{*relpath}` | 读一个文件的原始字节（已有） | 按状态：草稿 `write`+、已发布 `browse`+、已下架/已丢弃 `maintain`+；`skills:read` |
| `GET` | `/api/v1/author/skills/{ns}/{name}/versions/{pin}/files` | 清单（已有） | 同上 |
| `PUT` | `/api/v1/author/skills/{ns}/{name}/versions/{pin}/files/{*relpath}` | 覆盖或新增一个文件 | `write`（本人草稿）或 `maintain`+；`skills:write` |
| `DELETE` | 同上 | 删除一个文件 | 同上 |
| `POST` | `/api/v1/author/skills/{ns}/{name}/versions/{pin}/files/{*relpath}/move` | 移动/复制（路径要定） | 同上 |
| `GET` | `/api/v1/author/skills/{ns}/{name}/versions/{pin}/grep` | 服务端 grep（只草稿） | `write`+ / `skills:read` |
| `GET` | `/api/v1/author/skills/{ns}/{name}/versions/{pin}/count` | 服务端计数 | 按状态同读文件 |

**形状、字段与错误码在 PRD 确认后写死。** 已知要动的对外契约：

- `AuthorVersionResponse.Version` 增加 `code`（这一行的 id，8 位短码或旧 ULID）——网页的版本列表与事实栏要显示它，发布过的版本也要；`pin` 的语义不变（草稿/已丢弃是行 id，发布是版本号，无名是摘要）。
- 网页的地址后缀由 `AuthoredSkillResponse.Version` 现有字段算出：`name` 非空用版本号；草稿/已丢弃用行 id（新增的 `code`）；其余（无名的旧已发布版本）用 `digest`（`sha256:…`）。任何已发布行都有 `name` 或 `digest`，不会出现既无钉又无名称的地址。
- `SkillAddress.pinOf` / `VersionPin` 增加「行 id」形式（8 位 Crockford 或 26 位 ULID）；作者面接受，消费面 404；`@draft:<ULID>` 过渡期继续接受，`@draft:<短码>` 不接受。
- `skillmaster-web/src/version.ts` 的 `isVersionSuffix` 要接受 8 位短码与 26 位 ULID（草稿页的 `?version=` 才会落到单版页）。
- `pins.Pin` 增加行 id（或把 `Name`/`Digest` 的表示扩展成「行 id」），`invoke` 经作者面取草稿的 `pin` 并记住；`read` / `files` 发送这个钉。
- `VersionRepository.insertIfAbsent` 的冲突目标改成 `(skill_id, digest)`；主键撞码要在新事务里换码重试。插入时 `version` 是 `NULL`，`(skill_id, version)` 的唯一约束在插入时不触发（它属于发布/改号路径），所以插入路径只需区分 `(skill_id, digest)` 与主键两种冲突。

## 分期

待写。预期：

- **P0 服务端**：草稿写接口（改/增/删/移/复）、摘要与行更新、并发守卫、校验与上限、审计、grep 与计数。
- **P1 CLI**：`ls` / `cat` / `wc` / `grep` / `write` / `edit` / `rm` / `mv` / `cp`，GNU 风格的参数解析、退出码、stdout/stderr 契约。
- **P2 文档与 ADR**：草稿可变的 ADR；CLI 帮助与 README；网关 skill 的协议文本补上编辑流程。

## 开放问题

1. **并发用 `digest` 还是修订号？** `If-Match: <digest>` 不加列，但「同一内容连改两次」会撞上；加 `revision INTEGER` 要一次迁移。**当前倾向**：先用 digest，撞上时 409 并提示重读。
2. **每次写要不要跑 blob GC？** 今天的回收在每次版本变更时 `LOCK TABLE version_file` 并扫全表；逐文件写照搬会把所有写全局串行化。**当前倾向**：写路径不回收，发布/丢弃时回收，并在 ADR 里重新解释 0010 decision 4。
3. **`grep` 的正则边界是什么？** `-F` 可以完全一致；正则的语义（Java 与应用层）与不支持写法要在帮助里写清。**当前倾向**：先承诺 `-F`、`-i`、`-n`、`-l`，正则只保证常见语法。
4. **`mv` / `cp` 是一个接口还是两个？** REST 里没有天然的「移动」；**当前倾向**：一个 `PATCH` 带 `{from,to,mode}`，或两个动词。
5. **`edit` 的 0 处 / 多处匹配**：0 处拒绝、多处拒绝（除非 `--all`）——`--all` 已在 PRD 定，TD 只需给输出与退出码。
6. **撞码重试放在哪一层？** 主键冲突会中止 PostgreSQL 事务，不能在同一事务里换码再插；而 `insertIfAbsent` 现在是**无目标**的 `ON CONFLICT DO NOTHING`。**当前倾向**：点名 `(skill_id, digest)` 处理内容重复；主键冲突按约束名识别，在新事务里换码重试一次。插入时 `version` 是 `NULL`，`(skill_id, version)` 的唯一约束在插入时不触发——`VersionRepository` 里那句「点名 digest 会让 `(skill_id, version)` 冲突变成 500」说的是发布/改号路径，不是这条 INSERT，实施时顺手订正那句注释。
7. **网页与 CLI 怎么拿到 `code`？** `AuthorVersionResponse` 今天只有 `pin`，`AuthoredSkillResponse` 只在草稿上给 `draftId`。**当前倾向**：作者面响应加一个只读的 `code`（行 id），`pin` 语义不变；网页的 `isVersionSuffix` 接受 8 位短码与 26 位 ULID；不改消费面。
8. **`pins.Pin` 怎么装行 id？** 今天只有 `Name` / `Digest`，`invoke` 读的是消费面的 `Version`，不读 `pin`。**当前倾向**：本机钉直接存服务端给的那一个 `pin` 字符串（码 / 版本号 / 摘要），加一个 `display` 供提示行使用；`invoke` 走作者面取草稿的 `pin`，`read` / `files` 原样发送。

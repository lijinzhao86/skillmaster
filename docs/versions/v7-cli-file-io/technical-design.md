# v7-cli-file-io · 技术设计

> **最后更新**：2026-10-20
> **状态**：实现与验收状态见 [`README.md`](README.md) §状态（此处不复述）；未执行项见 [`test-plan.md`](test-plan.md) §结果。
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。
>
> **本版技术约束（2026-10-20 锁定，贯穿全文）**
> 1. **整版字节不出服务端**：客户端拿不到「所有文件」；凡是跨文件计算（`grep` / `wc` / 清单统计）都在服务端算完只回结果；唯一的内容出口是单文件 `GET`。
> 2. **查询与写入性能是设计约束**：读路径不整版进内存、写路径没有全局表锁；预算见 §架构 §性能预算。
> 3. **`edit` 与 Claude Code 的 Edit / Write 语义对齐**：精确串替换、唯一匹配、`--replace-all`、0 处拒绝、读→改→带 `If-Match` 写。
> 4. **架构可扩展**：命令按「解析目标 → 执行原语 → 排版输出」分层，新增命令复用同一组 M7/M6 原语，不新增第二套读写路径。
>
> **用词**：本文的技术叙述用 `state = published` / 「发布」（库里的状态与 `Publish*` use case）；界面文案按 `design/voice-spec.md` 用「上线 / 线上」。

<!--
本章之外的硬约束：
- 测出来的数字必须带口径（见下方表格的「口径」列），让读者能自己复现。
- 引用外部事实必须带出处，且区分一手与二手。
- 不复述 ADR 的理由；不负责任何产品决策的论证。
-->

## 架构

### 一条命令落在哪里

| 命令 | 谁执行 | 面 / 入口 | 说明 |
|---|---|---|---|
| `ls` | 服务端出清单，CLI 排版 | 作者面 `GET …/files` | 清单已有；CLI 新增大小与二进制两列 |
| `cat` | 服务端出字节，CLI 原样写到 stdout | 作者面 `GET …/files/{*relpath}`；`invoke` 档走消费面 `GET /api/v1/skills/{ns}/{name}/files/{*relpath}`（版本走 `X-Skill-Version` 头，不在路径里） | 流式，不缓冲整份 |
| `wc` | 服务端计数 | 作者面 `GET …/count` | 只回数字，不回字节 |
| `grep` | 服务端扫描 | 作者面 `GET …/grep` | 只作用于草稿（[ADR 0062](../../decisions/0062-grep-is-a-scoped-scan-not-an-index.md)） |
| `write` | 服务端写 | 作者面 `PUT …/files/{*relpath}` | stdin → 覆盖/新增 |
| `edit` | CLI 读、改、再写 | 作者面 `GET` + `PUT` | 0 处 / 多处匹配在 CLI 拒绝；不新增服务端「edit」语义 |
| `rm` | 服务端写 | 作者面 `DELETE …/files/{*relpath}` | CLI 对多个路径逐个请求 |
| `mv` / `cp` | 服务端写 | 作者面 `POST …/files`（`{op,from,to}`） | 不搬字节，只改 `version_file` |
| `read` / `files` | 不动 | 原路径 | `read` 保持现状；`files` 成为 `ls` 的别名 |

**A/B 分界的落点**：`cat` 是唯一的内容原语；`head` / `tail` / `sort` / `uniq` / `awk` / `sed` 由系统命令接在 `cat` 后面执行；`grep`（递归）、`wc` 由服务端执行。`edit` 只在 CLI 组装，服务端只认 `PUT`。

### 面与档位

**读与写是两把独立的尺，鉴权按两把尺分别判，谁也不蕴含谁。**

- **档位决定能进哪一族路由，scope 决定这一族里过不过。** 读路由（`cat` / `ls` / `wc` / `grep`、单文件 `GET`、版本读接口）过入口档（`invoke` 走消费面单文件，`browse`+ 走作者面）再认 `skills:read`；写路由（五条写命令、`PUT` / `DELETE` / `POST` 文件）只有 `write`+ 能进，再认 `skills:write`。两者缺一都是 403，代码里是两个独立的检查，不是一个检查的两个开关。**scope 规则的两个既有例外要照抄，不要重新发明**：`GET /api/v1/me` 只要求已认证（`SecurityConfig` 的注释写明理由），`HEAD` / `OPTIONS` 与 `GET` 同属读（`Scopes.requiredForMethod`）。
- **读档进不了写路由**：`invoke` / `browse` 即使凭据带 `skills:write`，五条写命令仍然全部 403——Spring Security 的 scope 规则（`SecurityConfig` 的 GET → read / 非 GET → write）在今天先放行，但入口档检查在 use case 拿到 `Access` 之后做（`Access.atLeast`）并拒绝；**不要**把写路由的放行条件写成「有 `skills:write` 即可」。
- **写档缺读 scope 是「能写不能读」**：`write` 档 + `skills:write` 缺 `skills:read` 时，**带 `If-Match` 的 `PUT` 成功**，skill 与文件路由上的 `GET`（含 `cat` / `ls` / `grep` / `wc`，以及 `edit` 先做的 GET）全部 403（`GET /api/v1/me` 除外）。**CLI 的五条写命令在这把凭据下都走不通**：它们一律先 `GET …/files` 拿清单与 `ETag`，这次 GET 就 403；带摘要的 `PUT` 只能直接调 API。**API key 签发造不出这把凭据**——签发时把 `skills:read` 补进带 `skills:write` 的行（[ADR 0039](../../decisions/0039-api-key-defaults-and-their-edges.md) §决定 2，`Scopes.withImpliedRead`）；**OAuth 客户端只注册 `skills:write` 时可以产出这样的令牌**（`ClientRegistry` 原样读客户端 scope，令牌签发不补）。鉴权仍按两条路由各自的 scope 判，**不依赖**签发时的补齐（补的是词汇表里的缺省，不是鉴权规则）。
- **都回 403，但两把尺的 403 不是同一种**：scope 不够走 Spring 的 `insufficient_scope`（`InsufficientScopeAccessDeniedHandler`：`WWW-Authenticate` 点名 `Scopes.requiredForMethod` 算出的 scope，错误体 `insufficient_scope`）；入口档不够走 `NotPermittedException` → `403 forbidden`，**不带**挑战、错误体是 `forbidden`——它的 javadoc 原话是把调用方送去重新授权 `skills:write` 会指向一个不是缺的那个权限。实现时**不要把两者合成一个检查**，否则挑战会点名一个调用方其实已经拿到的 scope。

- **面按命令与凭据分，不按状态分**：写命令与 `ls` / `wc` / `grep` 只有作者面——`browse`+ 走作者面（任何该档可读的状态），`invoke` 档对这些命令就是 403。`cat` 按钉的形式选面（见下）。`invoke` 档的 `cat` 走消费面，只能按路径取单个文件（[ADR 0047](../../decisions/0047-permissions-are-a-ladder.md) 的「能取用、不能发现」）。
- **今天 `read` 不是这样**：它按钉的类型/状态选面（草稿钉走作者面；其余走消费面，只在 `version_revoked` / 404 时回落作者面），`authorPlaneAnswers` 也不认 503。本版为 `ls` / `cat` / `wc` / `grep` 接上按凭据分面；`read` 保持现状（验收 #20）。**v7 的 `cat` 另有一个只属于内容读的回落**：消费面 410 / 404 / 503 都问一次作者面，成功就用它，失败则保留消费面的答案。
- **写命令只走作者面**：`write` / `edit` / `rm` / `mv` / `cp` 的目标必须是草稿，作者面解析后由服务端检查 `state = draft`；不存在「消费面写」。
- **短码只在作者面解析（本版改成）**：今天 `SkillAddress.pinOf(suffix, allowDraft=true)` 只接受 `draft:<ULID>`；v7 扩成接受裸 8 / 26 位行 id 与旧 `draft:<ULID>`。消费面仍是版本号 / `sha256:`，解析不出的一律 404（`@draft:<短码>` 也是 404，与其它无法解析的后缀同一条）。
- **`invoke` 是消费动作，读命令不是**：`invoke <行 id>` 走作者面（草稿只能在这里）；`invoke @1.2.3` / `@sha256:…` 走消费面，**不回落作者面**——revoked 的 410 就是 410，维护者要看内容用 `version show` / `skill read`。缺省钉先走消费面；没有上线版本时，`write`+ 才回落到作者面的最新可见版本。
- **新的读命令按命令与钉选面**：
  - `cat`：**短码钉只走作者面**（消费面不认识短码）。**命名/摘要钉先走消费面**——那是公开视图，也是 `invoke` 档唯一的面；消费面回 **410**（已下架）、**404** 或 **503**（skill 下线）时再问作者面：成功就用作者面的字节（`maintain`+ 读已下架、`browse`+ 读下线中的 skill），失败则保留消费面的答案（410 / 404 / 503）。已发布版本直接由消费面回答，不付作者面的往返。
  - `ls` / `wc` / `grep`：只有作者面。消费面没有清单 / 计数 / grep；`invoke` 档问它们就是 403（`ls` 在消费面的清单接口本身要 `browse`，所以也不存在回落）。
  - CLI 不把面记进钉，因为同一个钉在不同命令里走哪个面是命令的规则，不是钉的属性。

### 安全边界：整版字节不出服务端

- **客户端永远拿不到整版内容。** 唯一的内容出口是**单文件** `GET …/files/{*relpath}`：`cat` 一个路径一次；`cat a b` 是两次单文件请求按序串接，不是「整版打包」。服务端不提供「整版 zip / 全量文本 / 全量字节」这类给 CLI 的接口。
- **跨文件计算一律在服务端完成**：`grep` 只回命中行，`wc` 只回计数，`ls` 只回清单元数据（路径 / 大小 / 是否二进制），`mv` / `cp` 只改引用、不搬字节。字节在服务端内部只从 M6 到应用层（[ADR 0062](../../decisions/0062-grep-is-a-scoped-scan-not-an-index.md)）。
- **清单也按档位限制**：`invoke` 档没有 `ls` / `wc` / `grep`（[ADR 0047](../../decisions/0047-permissions-are-a-ladder.md) 的「能取用、不能发现」），单文件 `cat` 仍需 `skills:read` + 版本按档位可见。
- **`invoke` 档的「不能发现」是文件发现的属性，不只是 CLI**：
  - **现状已经满足的**：消费面 detail 对 `invoke` 是 403（`detailedSnapshot` 要求 `browse`）；`/metadata` 本来没有 `files`；作者面单文件对 `invoke` 是 403；网页不画文件树（设计稿 `design/README.md:471`）。
  - **v7 要补的**：作者面**清单路由**（`GET …/files`，`ls` 复用它）现在**先解析、后查档**——`invoke` 带草稿/已下架钉会先落 404。P0 把它改成先做 `browse` 入口检查，`invoke` 一律 403。这是 **#31 这一项**唯一需要的服务端改动。
  - **不在限制内**：**消费面** `GET /api/v1/skills/{ns}/{name}/versions`（只列线上）与**网页版本栏**（只列可读版本）、网页版本搜索；它们不是文件结构。**作者面**版本列表 / `version show` 对 `invoke` 仍是 403（`ListAuthorVersionsUseCase` / `ReadAuthorVersionUseCase` 先查 `BROWSE`）。版本事实的公开面是 `/metadata` 与网页事实栏，不是作者面 `version show`。
  - **豁免**：网关索引 V1 是**匿名公开**接口（`/gateway/**` permitAll），只暴露公开的 gateway skill，字节本来就由 `/gateway/…` 公开提供；没有「invoke 档」可言，不能按档位过滤，也不该改 `GatewayIndex.V1` 的 `files`（它是 `skills` CLI 的发现契约）。
- 这是**安全不变量**，不是实现偏好：将来加 `find` / `stat` / `diff` 时，凡是需要读整版的，都在服务端算，只回结果。

### 性能预算

两条硬指标来自 [PRD §指标](prd.md#指标)（单写 P95 < 300 ms；1 MiB `cat | head -n 5` 服务端写出 < 100 KiB）。下面是让它们成立的设计手段与内部目标；内部目标在实现后实测，结果写进 test-plan，不在本文冒充已验证。

| 路径 | 做法 | 内部目标（本机，实测后回填） |
|---|---|---|
| `ls` | 一条 `version_file` 查询（主键 `(version_id, relpath)` 前缀），**不读 `blob_content`** | 512 文件 < 50 ms |
| `cat` | `BlobStore.open` 32 KiB 分块流式；`Content-Length` 取自 `version_file.size`；断开即停（§流式） | 首字节 < 100 ms；提前退出按 PRD 指标 |
| `wc -c` | 只对清单的 `size` 求和；整版总量直接用 `skill_version.total_bytes`，**不读字节** | O(文件数) |
| `wc -l` / `-w` | **逐文件**读入（二进制也计），文本文件复用 grep 的按 `sha256` 文本 LRU；不把整版装进一个数组，峰值是一份文件的大小（受 16 MiB 上限约束） | 16 MiB < 500 ms |
| `grep` | M6 在 `blob_content` 内按**这一版的 sha 集合**用 `position(… IN bytes)` 预筛（主键查找，不全表扫描）；只把候选读进应用层；文本 LRU 64 MiB；正则由 RE2 执行（[ADR 0066](../../decisions/0066-grep-uses-a-linear-time-regex-engine.md)） | 16 MiB 固定串 < 500 ms；重复 grep 走缓存 |
| 写 | **不拿 `version_file` 表锁**（见 §blob 回收）；行锁只锁同一版；一次 `put` + 清单增量 + 一行 `UPDATE` | 单写 P95 < 300 ms |
| 并发写 | 同版靠 `If-Match` + 行锁；不同 skill 互不阻塞；**同 skill 的不同版只在「查重 + UPDATE」这一小段**（按 skill 的咨询锁）串行 | 两路并发写不同 skill 不排队；同 skill 不同版只等这一小段 |

### 数据流

**读（`cat` / `ls` / `wc` / `grep`）**

```
CLI: 地址 + 钉（显式 → invoke 记下的 → 服务端当前）
  → 作者面（browse+）或消费面（invoke 档）
    → M7 SkillVersionService 解析 version（按档位可见状态）
      → M7 读 version_file（路径 / sha / size / binary）
        → M6 BlobStore 读字节（或只给候选 sha）
          → 流式 / 计数 / 匹配结果回到 CLI
```

**写（`write` / `edit` / `rm` / `mv` / `cp`）**

```
CLI: 目标钉 + If-Match（上一次读到的版本 digest）
  → 作者面写路由 → use case（@Transactional，1–6 同一事务）
    0. 不拿 version_file 表锁、不跑 sweep（见 §blob 回收的 grace 方案）
    1. 入参形状（地址语法 / 路径 / 上限）先校验，不依赖目标；然后 M12 解析 Access + 按 §并发 的门序（⓪ skill 不可见 404 → ① 入口档 403 → ② 状态不可见 404 → ③ 归属 403），不进入行锁
    2. M5 校验路径 / 大小 / 二进制检测 / SKILL.md（只有写 SKILL.md 时）
    3. M6 BlobStore.put（新字节；内容寻址，可重复）
    4. M7：SELECT … FOR UPDATE 锁版本行 → 校验 state/digest → 改 version_file
       → 重算 Manifest.digest / file_count / total_bytes
       → lockContentOf(skill) → 查重 → UPDATE skill_version
    5. M12 刷新 skill 行上的草稿投影（`newest_*` 三列），skill 的 title/description 不动
    6. M10 AuditLog
    7. 返回新版本事实（digest / pin / code / counts）
  → CLI 更新本机钉的 digest（若写的是 invoke 钉住的那一版）
```

**grep**

```
M7: version_id → text 文件的 {relpath, sha}（跳过 is_binary）
M6: 在这些 sha 内用 position(bytea IN bytea) 预筛（固定串）
应用层: 读候选字节 → 按行匹配（KMP 固定串或 RE2 正则）→ 行号
  → 结果上限 + truncated
```

**wc**

```
M7: version_id → 清单（relpath, sha, size, is_binary）
  → 只要 -c：直接用 size，不读 M6；总量是所选文件 size 之和
  → 要 -l / -w：M6 逐个读入字节（文本文件可复用 grep 的按 sha 文本 LRU）；
    二进制也计入 lines / bytes，**不跳过**（本地 wc 也不跳；只有 grep 跳过二进制）
应用层: 计 lines / words / bytes → 每文件 + 总计
```

### 模块边界

- **M7**（`modules/version`）拥有 `skill_version` 与 `version_file`；**M6**（`modules/blob`）拥有 `blob` 与 `blob_content`。任何一条 SQL 不点名两边的表（§2.5 规则①）。
- **新增的 M6 面**（`BlobStore`）：
  - `InputStream open(String sha256Hex)` 或等价的「按块写出」接口——给 `cat` 的流式与提前退出；
  - `List<String> containing(Set<String> shas, String needle)`——给 `grep` 的固定串预筛（大小写敏感；`-i` 不走它）；
  - `put` / `get` / `exists` 不变；`deleteUnreferenced` 加 cutoff（§blob 回收）。
- **M7 新增**：内容写（`putFile` / `deleteFile` / `renameFile` / `copyFile`）、清单读、`findByRowIdForUpdate`（行锁）、`updateContent`，以及 `lockContentOf`（按 skill 的内容提交咨询锁，见 §并发）。
- **use case 负责编排**：M7 给文件行/sha 集合，M6 给字节，M5 给校验，M10 记审计；模块之间不直接互调。
- **写只有 4 个 use case**：`WriteDraftFileUseCase`（`write`，`edit` 复用它的 PUT）、`DeleteDraftFileUseCase`、`MoveDraftFileUseCase`、`CopyDraftFileUseCase`。它们共用同一个前置流程（解析 Access → 校验 → 行锁 → `If-Match` → 审计），差异只在改 `version_file` 的那一步；不按命令各写一套权限/状态判断。
- **读是 3 个原语**：`listManifest`（`ls`）、`countFiles`（`wc`）、`grepFiles`（`grep`），外加单文件 `open`（`cat`）。新增命令（`stat` / `find` / `diff`）优先组合这些原语；组合不出来时再加一个 M7 读方法，不新开第二条读路径。
- **CLI 也分层**：`resolveTarget`（地址 + 钉 + 面 + 缺省规则）→ 每个命令只做「调接口 + 排版」→ 统一的 `exitError` / stdout-stderr 约定。新增命令不重复解析地址、不重复决定面。
- **M5 复用**：把 `ZipReader.validateName` 提取为共享的路径校验 `SkillPaths.check` / `validate`，把 `SkillUploadValidator` 里的 frontmatter + `name`/`description` 校验提取为 `SkillDocument.parse` / `requireNameMatches`。写路径调用这套共享方法；提交路径保留现有 validator 的调用点（规则相同，入口不同），不强行合并两处错误码文案。
- **M12 / M1**：use case 用现有的 `NamespaceService.bySlug` + `SkillMetadataService.accessFor` 取 `Access`（照 `UpdateDraftNameUseCase` 的形状）。

### 流式与提前退出

- **作者面与消费面的单文件 `GET` 都改成 `StreamingResponseBody`**：`invoke` 档的 `cat` 走消费面（`GET /api/v1/skills/{ns}/{name}/files/{*relpath}`，版本走 `X-Skill-Version` 头；现在是 `ResponseEntity<byte[]>`，整份缓冲）。两条路由共用一个 `BlobStore.open(sha)` 写出函数，以 32 KiB 块从 `blob_content` 读（`substring(bytes FROM ? FOR ?)`），写一块、flush 一块；客户端断开时写抛出 `IOException`，循环停止，不再读剩余块。
- 响应带 `Content-Length`（来自 `version_file.size`）与 `ETag`；`Content-Length` 是给调用方的，不会让服务端为了凑长度把整份读完。
- CLI 用 `io.Copy(os.Stdout, resp.Body)`，不缓冲；多个路径按参数顺序串接。读到 `EPIPE` 时安静停止（管道下游已经走开），不再打印错误。
- 指标口径：1 MiB 文件跑 `cat | head -n 5`，服务端写出的响应字节 < 100 KiB（PRD §指标）。块大小与 flush 频率是这个数字的唯一旋钮；实现后按访问日志量一次，写进 test-plan 的结果。

### grep 的作用域与上限

- **只在草稿上**：服务端先解析版本并检查 `state = draft`；已发布 / 已下架 / 已丢弃一律拒绝（CLI 提示用 `cat | grep`）。
- **只扫文本文件**：`version_file.is_binary = false` 的行才进候选；二进制跳过，帮助里写明。
- **固定串（`-F`，区分大小写）**：M6 直接对 bytea 做 `position(CAST(:needle AS bytea) IN bytes) > 0` 预筛——不解码，也就不会因为 NUL 或非法 UTF-8 抛错（二进制已排除，但 NUL 是合法 UTF-8 码点，不能靠 `is_binary` 挡）。PostgreSQL 没有 bytea 的 `strpos`，`position` 是同一语义的可用拼写。按 `blob_content` 主键在**这一版的 sha 集合**内查找，不全表扫描。`LIKE` 只在模式语义需要时用；固定串用 `position` 更直白。
- **`-i` 不走数据库预筛**：数据库的 `lower()` 只保证 ASCII 折叠，非 ASCII 的大小写对会被漏掉。`-i`（无论是否同时 `-F`）在应用层读全部文本文件：**`-F` 的两种大小写模式都走 KMP 字面量搜索（`-i` 时按 Unicode 简单小写映射折叠）；正则 + `-i` 走 RE2 的 `CASE_INSENSITIVE`**。16 MiB 有界，服务端 LRU 让重复 grep 不必重新解码。帮助里说明 `-i` 的扫描面比 `-F` 大。
- **正则**：由 **RE2/J** 执行（[ADR 0066](../../decisions/0066-grep-uses-a-linear-time-regex-engine.md)），线性时间，逐行匹配；不承诺 BRE / PCRE / `-P`，也不支持反向引用与 lookaround（这类写法与语法错误一样回 400 `invalid_request`，detail 为 `bad_pattern`）。pattern 上限 1000 字符（超出 `too_long`）。**执行成本另有硬上界（[ADR 0067](../../decisions/0067-grep-has-a-cost-ceiling.md)）**：`{n}` / `{n,m}` 的计数超过 100、或「计数乘积 × pattern 长度」超过 1,000,000（每个计数至少按 1 计，`{0}` 不归零）直接 400 `too_expensive`（detail 字段 `pattern`；这是 RE2 编译期展开的上界，在 `compile` 之前判），正则匹配另有请求级 1 秒截止时间——每 1024 次 `charAt` 与每 64 行各查一次时钟，超长行与大量短行都覆盖——超时同一个 400。不支持的写法与成本边界写进 `skill grep --help`。`-F` 与 `-i` 可组合。
- **输出**：服务端回 `{path, line, text}`；CLI 按本地 `grep` 排版——`-n` 时 `path:line:text`，否则 `path:text`，`-l` 只列路径。命中过多时服务端按**条数**截断（默认 1000、最大 5000）并回 `truncated: true`；CLI 在 stderr 说一句「结果被截断」，stdout 只放命中。
- **服务端文本缓存**：按 `sha256` 缓存解码后的文本，有界 LRU（暂定 64 MiB）。blob 不可变，缓存没有失效问题；它只影响重复 grep 的延迟，不是正确性的一部分。

## 数据模型

### 不新增表

v7 不新增表、不加列。`skill_version.id` 是 `TEXT PRIMARY KEY`（`V1__baseline.sql`），8 位短码放得下；旧行继续是 26 位 ULID。

### 行的可变性

- **发布后内容冻结**：`state = published` 的行的 `digest` / `file_count` / `total_bytes` / `version_file` 不随本版任何命令变化。
- **草稿内容可变**：每次写更新 `skill_version.digest` / `file_count` / `total_bytes`，并增删改 `version_file`；`state` 保持 `draft`，`version` 保持 `NULL`，`number` / `submitted_at` / `submitted_by` 不变。
- **写 `SKILL.md` 时（已定 2026-10-20，见 §开放问题 §已定 3）**：重新解析 frontmatter，更新该版本的 `title` / `description` / `frontmatter` 三列——它们是这一版自己的快照，跟随文件；**skill 行的 `title` / `description` 不动**（那是 ADR 0049 的元数据线，而 ADR 0049 属于尚未实现的版本；发布仍按 ADR 0031 §决定 9 的现有规则：skill 行是当前上线版本的投影）。这是对「元数据不在本版」的边界：改的是版本内的 SKILL.md，不是 skill 的元数据。
- **已落成 [ADR 0064](../../decisions/0064-a-draft-may-change-a-published-version-may-not.md)**：v6 的不变量在代码里已经是「内容只插不改；状态/名称/说明可变」；本版把它精确成「**发布后的内容只插不改；草稿的内容随写更新**」。

### digest 与唯一约束

- `Manifest.digest()`（`modules/version/Manifest.java`）是唯一的摘要算法：按 relpath 排序，`sha256(relpath + "\0" + blob_sha + "\0")`。写后从 `version_file` 重读、重建 `Manifest`、再算 digest；不在应用里另拼一份。
- `UNIQUE (skill_id, digest)`（`V1__baseline.sql`）在**写**时可能撞上：把草稿改成与同 skill 某一版完全相同的内容（例如撤回一次编辑，改回已发布版本的内容）。**已定（2026-10-20）**：409 `content_already_exists`，detail 总带已有那一版的 `state`，`pin` 只在它已上线（对 `write`+ 可见）时带；不静默合并、不删草稿。代价是「把草稿退回成某一版的原文」会被拒绝；提示语要说清内容已经存在、那一版是什么（见 §开放问题 §已定 1）。
- `UNIQUE (skill_id, version)`（`V11__version_names_are_semver.sql`）在写路径**不会触发**：草稿的 `version` 是 `NULL`，PostgreSQL 把 NULL 视为互不相同；这条约束属于发布/改号路径。
- **提交路径的 id 撞码**：`VersionRepository.insertIfAbsent` 的冲突目标改成 `(skill_id, digest)`——摘要冲突仍是 `DO NOTHING`（同内容重放），而主键撞码会抛唯一约束错误，可以换码重试。**重试必须包住整个提交**：两个 controller 提交入口改调一个**不带事务**的 `DraftRecorder`（Spring bean），它在 `TransactionTemplate.execute` 里调用现有的 `@Transactional` use case 方法（`REQUIRED` 加入模板事务），使整个方法体——校验、解析 namespace/Access、`BlobStore.put`、`underExclusiveWrite(findOrCreate/submit)`、审计——都在同一个新事务里。捕获 `DuplicateKeyException` 必须发生在 `execute()` **之外**（此时事务已回滚、连接已释放），然后重跑整个方法；每次重跑 `insertIfAbsent` 都会生成一个新的短码。最多 3 次，仍失败是 500。**不能**把重试放进 use case 自己的 `@Transactional` 方法里（事务已被标记 rollback-only，`TransactionTemplate` 只会加入它），也不能用同类私有方法的自调用（不经过代理）。
- **入口不止两个 controller**：`PublishGatewaySkillUseCase` 在 `@Transactional publish()` 里调用 `SubmitSkillUseCase.submit`（网关启动时每个版本一次），是第三个生产调用点。它**不经过** `DraftRecorder`：在已有事务里用 `TransactionTemplate` 只会加入外层事务，第一次撞码就把事务判死，重试不可能成功。网关保持自己的事务、**不重试**；单次对单行的撞码概率约 2⁻⁴⁰（库里有 n 行时约 n/2⁴⁰），启动期失败可见、可重启。这是明写的取舍，不是遗漏。
- **这是行为变更，不只是注释**：改成 `(skill_id, digest)` 目标后，主键冲突与 `(skill_id, number)` 冲突都不再被 `DO NOTHING` 吞掉，会抛 `DuplicateKeyException`；主键由 `DraftRecorder` 重试，`(skill_id, number)` 在表锁下不可达（约束保留作护栏）。「不改变行为」只指 `MAX(number)+1` 的串行化来源说明的订正，不指冲突语义。
- **`insertIfAbsent` / `VersionRepository` 的注释要一并订正**：现存注释说「点名 `(skill_id, digest)` 会让 `(skill_id, version)` 的冲突变成 500」——这条 INSERT 的 `version` 是 NULL，`(skill_id, version)` 在它上面不可能冲突。注释还把 `MAX(number)+1` 的安全归功于 skill 行锁；`SubmitSkillVersionUseCase` 根本不走 `findOrCreate`，两个提交入口真正的串行化来自 `underExclusiveWrite` 的 `version_file` 表锁。
- **`skill_version.id` 的列注释**仍写「ULID」；v7 新行是 8 位短码，P0 一并订正注释（不改列）。

### 并发：If-Match + 行锁

- 写请求必须带 `If-Match`（`"sha256:<hex>"`；也接受不带引号的 `<hex>` / `sha256:<hex>`）。缺失：428 `if_match_required`。
- **写路由只接受行 id**：`{version}` 是 8 位短码或 26 位 ULID（或 `draft:<26 位 ULID>` 旧拼写）；semver / `sha256:` 在路由或 use case 层拒绝（`version_is_not_draft`），不走「按摘要找到草稿」的路径。
- **入参形状先于门序。** 地址语法、`relpath`、pattern 的校验在解析请求时完成，且不依赖目标是否存在：一个坏地址得到 400/404，而不是先花一次权限查询。**门序只管「目标看不看得见、能不能动」**，不承担「这个字符串像不像地址」。**写路由的钉形状检查也是这一类**：`@1.2.3` / `@sha256:…` 对任何档位都是 409 `version_is_not_draft`，`grep` 的命名/摘要钉对任何档位都是 409 `grep_requires_draft`——它们不泄露目标状态，也不该先等一次权限查询。
- **先过门，再进锁。** 门序与 PRD 一致：⓪ **skill 不存在或对该凭据不可见 → 404**（与 `accessFor` 返回空同一条，先于档位比较；不泄露存在性）；① **入口档**（这条路由要求的最低档）不够 → 403（例如 `invoke` 档打开作者面、`browse` 档调写路由）；② **状态对该档不可见** → 404（例如 `browse` 读草稿、`write` 读已下架 / 已丢弃）；③ **归属**——可见但不是本人提交的草稿、写它 → 403。⓪ 的 404 与①的 403 都不依赖目标状态。不先 `FOR UPDATE`，否则一个不可见的调用方也能让这一行排队；能看见才进入事务：
  1. `SELECT … FROM skill_version WHERE id = :id FOR UPDATE`。
  2. 校验 `state = 'draft'`（此时调用方已过入口档，即 `write`+）。不是草稿分两类：**已发布**的行对 `write`+ 可见，回 409 `version_is_not_draft`，detail 带状态与当时的版本号，提示写只针对草稿；**已下架 / 已丢弃**的行对 `write` 不可见，回 404；`maintain`+ 回 409 并说状态。`browse` / `invoke` 在入口档就 403，走不到这里。
  3. 校验权限（本人提交的草稿才给 `write`；`maintain`+ 不受提交人限制）。
  4. 比较 `If-Match` 与行上的 `digest`；不等就 409 `digest_mismatch`，detail 带当前 `digest`。
  5. **no-op 判定在 `If-Match`、状态、权限都通过之后**：新 digest 与当前行相同 → 200，不改行、**不写审计**（没有变更）；否则应用 `version_file` 变更、重算清单、更新行、写审计，提交。
- 两个写从同一个摘要出发：第一个拿锁、改完提交；第二个拿到锁后看到的 digest 已经变了，409。**没有静默覆盖**。
- **版本行锁只序列化同一版的写，不串行化提交路径。** 提交路径（`submit` / `submitVersion`）的串行化来自 `underExclusiveWrite` 的 `version_file` 表锁，不是 skill 行锁：`SubmitSkillVersionUseCase` 不调用 `findOrCreate`。`SubmitSkillUseCase` 的 skill 行锁在表锁**之后**取得，顺序不能反（否则与软删除互锁）。

### 路径与上限

- 路径校验复用 ingest 的规则：拒绝绝对路径、`\`、`..` 段；路径是 POSIX 相对路径，`/` 分隔。**不新增「文件与目录同名」规则**——ingest 允许 `a` 与 `a/b` 并存；写路径单方面收紧会让已有草稿无法再编辑。要收紧就同时改 ingest 与 PRD，不在本版私下加。
- 根 `SKILL.md` 特殊：不能删、不能 `mv` 走（否则 skill 没有根文件）；**嵌套的 `references/SKILL.md` 是普通文件**，可写可删（ingest 也允许）。写根 `SKILL.md` 时按 ingest 的 frontmatter 规则校验（`name` 必填且与 skill 名一致，`description` 必填）。
- **地址名与 frontmatter `name` 的一致性**：提交路径今天在 `SubmitSkillVersionUseCase` 里比较（`name.equals(upload.name())`）；写根 `SKILL.md` 复用同一个比较。把「解析 frontmatter + 校验 name/description + 与地址名比较」放进共享校验，避免两处各写一份。
- **二进制检测**：每次写用 ingest 的同一套规则（`CharsetDecoder` 解码失败即二进制）算出 `is_binary`，写进 `version_file`；`cat` / `wc` 不跳过，`grep` 跳过。`mv` / `cp` 直接复制源行的 `is_binary`，不重新检测（字节没变）。
- 每次写后检查 `IngestLimits.STANDARD`：`file_count <= 512`、`total_bytes <= 16 MiB`；`rm` 后至少剩 1 个文件。超限用 `INVALID_UPLOAD` + detail（`too_many_files` / `too_large`），不写入。
- 空 stdin 写空文件允许（PRD 验收 #10）；写空 `SKILL.md` 会因 frontmatter 校验失败被拒。

### 审计

每次写一条 `AuditEvent`（与写同一事务）：`action = "version_file_write"`，`targetType = "skill"`，`targetId = skillId`，`detail = {version: pin, code, op: write|delete|move|copy, path, from/to, digest_before, digest_after, files, bytes}`。**只放本次真实存在的键**：`write` 没有 `from`，`delete` 没有 `to`，`bytes` 是操作前的清单字节数。`AuditEvent` 用 `Map.copyOf`，不接受 null，所以不用的键必须省略，不能填 null。`AuditLog` 是 `@FunctionalInterface`，由 use case 显式调用。

### blob 回收

- **写路径不加表锁、不跑 sweep。** `BlobStore.put` 写入的 blob 在同一个写事务里马上被 `version_file` 引用；文件写不需要 `underExclusiveWrite` 的 `version_file` 表锁（那会让所有写、提交、GC 全局串行，违反 §性能预算）。GC 的 `sweep` 改为**带 grace period 的回收**：只删「`blob.created_at` 早于 cutoff（now − 10 分钟）且没有被引用」的行。`blob.created_at` 已存在（`V1__baseline.sql`），不加列；`BlobStore.deleteUnreferenced` 增加一个 cutoff 参数，由 `BlobGc` 传入。写事务的 put→引用窗口是秒级，远小于 10 分钟，所以新 blob 不会被并发的 sweep 当成孤儿。
- **实际的 sweep 触发点只有 `underExclusiveWrite` 的调用者**：`SubmitSkillUseCase`（新 skill）、`SubmitSkillVersionUseCase`（已有 skill 提交）与 `SoftDeleteSkillUseCase`。发布 / 丢弃（`PublishSkillVersionUseCase` / `DiscardSkillVersionUseCase`）**不**走 `underExclusiveWrite`，也不 sweep——它们不删除 `version_file` 行，没有需要回收的引用。文档此前把它们写成回收点，是错的。
- **v7 自己的 `rm` 会造出「老的、未被引用的」blob**：`DeleteDraftFileUseCase` 删除 `version_file` 行后，blob 失去引用；`blob.created_at` 仍是当初存进来的时间。10 分钟后如果 `write` 相同内容，内容寻址会复用这条老 blob（`put` 的 `ON CONFLICT DO NOTHING` 不刷新 `created_at`），并发的 sweep 的 grace 保护不了它：要么这次写撞 FK 失败（可见、可重试；客户端重跑 `write` 会重新插入 blob），要么将来改为「复用时 touch `created_at` / 加 `last_written_at`」。本版接受前者；test-plan 的 T-CONC-06 覆盖这一路。
- **cutoff 的口径**：`blob.created_at` 是 `TEXT`（RFC3339 UTC），不是 `timestamp`；cutoff 由 Java 用 `Timestamps` 算出同格式的 RFC3339 字符串，按字典序比较（同格式等价于时间序），或在 SQL 里 `created_at::timestamptz < :cutoff`。实现选一种，测试夹具按同一口径造。
- **sweep 仍保留表锁**：submit / 软删除走 `underExclusiveWrite`，仍拿 `version_file` 表锁再 sweep；它们稀少，代价可接受。文件写不经过这条路径。
- **要一并订正两处 javadoc**：`BlobGc.beginExclusiveWrite` 与 `SkillVersionService.underExclusiveWrite` 现在写的是「写 `version_file` 之前必须拿锁，顺序不可协商」；v7 的文件写有意绕开它。P0 在 `writeFile` 上写明为什么安全（grace + 新 blob），并把那两段 javadoc 改成「提交 / 软删除必须拿；文件写由 grace 保护」，否则代码与注释互相打脸。
- 这条改的是 v6 的 `BlobGc`，是跨版本取舍，已落成 [ADR 0065](../../decisions/0065-blob-reclamation-waits-for-a-grace-period.md)（GC 的 grace period、写路径不拿表锁、以及发布 / 丢弃不 sweep 的事实）。
- 代价：一份长期不发布、反复编辑的草稿会留下不再被引用的 blob，直到下一次 **submit / 软删除**（不是发布 / 丢弃）触发 sweep。16 MiB / 512 文件的上限约束的是「一版」，不是「一版的编辑历史」；这个增长要在 test-plan 里记成已知代价，不能假装不存在（见 §开放问题 §已定 2）。

## 接口

### 作者面路由

现有 `AuthorSkillRoutes.BASE = /api/v1/author/skills`，`ONE_VERSION = /{namespace}/{name}/versions/{version}`。

| 方法 | 路径 | 作用 | 鉴权 |
|---|---|---|---|
| `GET` | `ONE_VERSION + /files` | 清单（已有；加 `ETag`） | 按状态：草稿 `write`+、已发布 `browse`+、已下架/已丢弃 `maintain`+；`skills:read` |
| `GET` | `ONE_VERSION + /files/{*relpath}` | 一个文件的原始字节（已有；改流式，加 `ETag`） | 同上 |
| `PUT` | `ONE_VERSION + /files/{*relpath}` | 覆盖或新增一个文件（body = 原始字节） | 草稿 + `write`（本人）或 `maintain`+；`skills:write` |
| `DELETE` | 同上 | 删除一个文件 | 同上 |
| `POST` | `ONE_VERSION + /files` | `{op:"mv"|"cp", from, to}` | 同上 |
| `GET` | `ONE_VERSION + /grep` | 服务端 grep（只草稿） | 草稿 + `write`+；`skills:read` |
| `GET` | `ONE_VERSION + /count` | 服务端计数 | 按状态同读文件（草稿 `write`+、已发布 `browse`+、已下架/已丢弃 `maintain`+）；`skills:read` |

**路径里的 `{version}`（本版改动）**：作者面接受裸的 8 位短码 / 26 位 ULID、`@1.2.3`、`@sha256:…`，以及过渡期的 `@draft:<26 位 ULID>`；`@draft:<短码>` 解析不出，与其它无法解析的后缀同一条 404。解析在 `SkillAddress.pinOf(suffix, true)`（今天只认 `draft:<ULID>`，本版扩成裸 id）。

**读响应的头**：

- `ETag: "sha256:<version digest>"`——这一版的摘要，给下一次写当 `If-Match`；
- `Content-Type` 照 `MediaTypes.forRelpath`（已有）。

**作者面没有 `latest`**：`ONE_VERSION` 路径里的 `{version}` 必须是一个具体的钉。缺省钉由 CLI 先解析（显式 → invoke 钉 → `version list` 里 `status = live` 的那一行；没有上线版本时，`write`+ 取最新可见的一行），再带着它调用文件路由；消费面则由 `Metadata` 的当前版本解析。这样服务端不需要多一种「latest」拼写，CLI 也总知道自己按哪一版操作。

**写的请求头**：`If-Match` 必填；body 是原始字节，`Content-Type: application/octet-stream`。响应 `200` + `AuthorVersionResponse.Written`（含新的 `version`，见下）。删除与 mv/cp 的响应相同。

**新错误码**（加到 `ErrorCode`，由 `ApiExceptionHandler` 映射）：

| 码 | 状态 | 何时 |
|---|---|---|
| `if_match_required` | 428 | 写请求没有 `If-Match` |
| `digest_mismatch` | 409 | `If-Match` 与当前 digest 不同；detail 带 `current_digest` |
| `content_already_exists` | 409 | 写后的 digest 与同 skill 的另一版相同；detail 总带 `state`，`pin` 只在该行已上线（对 `write`+ 可见）时带 |
| `file_already_exists` | 409 | `mv` / `cp` 的目标已存在 |
| `skill_md_cannot_be_deleted` | 409 | `rm` / `mv` 试图移走根 `SKILL.md` |
| `last_file_cannot_be_deleted` | 409 | 删除后清单为空。根 `SKILL.md` 的检查在前，所以正常数据先命中 `skill_md_cannot_be_deleted`；本码是损坏数据的护栏 |
| `version_is_not_draft` | 409 | 写目标是**已发布**的行（`write`+ 可见），或 `maintain`+ 写已丢弃 / 已下架；detail 总带 `state`；`pin` 在该状态对调用方可见时带：已发布对所有 `write`+，已下架/已丢弃对 `maintain`+。**`write` 对已丢弃 / 已下架返回 404（不可见），不用本码** |
| `grep_requires_draft` | 409 | `grep` 的目标不是草稿 |
| `invalid_path` | 400 | 绝对路径 / `..` / `\`；detail 带 `issue` |

- **`file_is_binary` 是 CLI 的本地判断，不是服务端码**：`edit` 先读清单里的 `is_binary`，命中就拒绝（stdout/stderr 是中文句子，不回一个机器码），不把二进制内容交给 `edit`。服务端不对 `PUT` 拒绝二进制——`write` 允许写二进制（验收 #3 的 `cat` 要能原样读回）。
- **409 / 428 需要新的异常类型与 handler 分支**：现有 `VersionStateException` 与 `RequestException` 的 handler 都固定 400，不能复用。加一个 `ConflictException`（409，带码与 detail）与 `PreconditionRequiredException`（428），或给 `ApiExceptionHandler` 加显式的 409/428 分支。
- **`content_already_exists` 先查后写**：PostgreSQL 的唯一约束报错会 abort 整个事务，不能在 catch 里再 SELECT。**新 digest 与当前行相同 → no-op（200，不改行）**；变了才在 **M7 的写方法内部**用 `findBySkillAndDigest` 预检，并**排除当前 version id**（只对「另一版」409；`findBySkillAndDigest` 会把当前行也返回）。预检放在 M7，因为 `VersionRepository` 在 `modules.version.internal`，use case 直接引用会被 `ArchitectureTest` 拦。内容摘要在同一 skill 内唯一。写路径先拿 `lockContentOf(skillId)`（事务级咨询锁），再做「查重 + UPDATE」，二者成为一个按 skill 串行、不同 skill 互不阻塞的临界区；因此并发写同内容得到的是 409 `content_already_exists`（带 detail），不是 500。
- **单请求 body 上限**：`PUT …/files/{*relpath}` 在读取时按 16 MiB + 1 封顶（`Content-Length` 先判，chunked 也无界），超出回 400 `INVALID_UPLOAD`（detail `files` / `too_large`）。它发生在门序之前：body 必须在 use case 之前读入，但读取量有界，且不对不可见 skill 暴露任何信息。
- 上限 / frontmatter 仍用既有的 `INVALID_UPLOAD`（detail 的 `issue` 与 zip 上传同一套词），不新造一套。

**`AuthorVersionResponse.Version` 加 `code`**：这一行的 id（新行 8 位短码，旧行 ULID），发布过的版本也有。`code` 是网页与 CLI 展示「版本码」的字段，不参与寻址。`pin` 的**含义**不变（草稿/已丢弃是行 id，发布是版本号，无名是摘要），但**拼写变了**：草稿/已丢弃不再带 `draft:`，旧行输出裸 ULID。这是**破坏性的线上契约变更**——服务端仍接受 `draft:<ULID>` 作为输入，但旧 CLI 的 `read` / `files` / `invoke` 用 `isDraftPin` 判断草稿钉；裸 ULID 不再命中 `draft:`，这些读命令会把它送到消费面并得到 404。`rename` / `note` 走 `writablePin`（只拒绝摘要），`discard` 只是跳过预读，它们仍能把裸 id 发给服务端并成功。发布说明要求 CLI 与服务端同版升级；web 与服务端一起部署，不存在旧 web。

**`FilesResponse`**：现有 `{relpath, size, is_binary}` 足够 `ls`；不加 sha（`cat` 不需要，`edit` 用 `ETag`）。

### `grep` 的请求与响应

```
GET …/versions/{version}/grep
  ?pattern=<字面量或正则>&fixed=true|false&ignore_case=true|false&path=<前缀>&limit=<n>
```

```json
{
  "digest": "sha256:…",
  "pin": "7K3M9QX2",
  "hits": [{"path": "SKILL.md", "line": 12, "text": "…"}],
  "truncated": false
}
```

- `pattern` 为空：400 `invalid_request`。
- 正则编译失败：400 `invalid_request`，detail `bad_pattern`。
- 正则太贵：400 `invalid_request`，detail `too_expensive`（单个计数 > 100、「计数乘积（每项至少按 1 计）× pattern 长度」> 1,000,000，或匹配超过 1 秒；ADR 0067）。
- `pin` / `digest` 与 `AuthorVersionResponse` 同义：`pin` 是草稿/已丢弃的裸行 id、发布版本的版本号、无名版本的摘要；`digest` 是版本 digest（`sha256:`）。
- `path` 是 relpath 前缀（按段匹配，`references/` 匹配它下面，不匹配 `references-old/`）。
- `limit` 默认 1000，最大 5000；`truncated` 是响应的属性。
- 只回命中；不命中的文件不回。

### `count` 的响应

```
GET …/versions/{version}/count[?path=<relpath>]…
```

```json
{
  "digest": "sha256:…",
  "pin": "7K3M9QX2",
  "files": [{"path": "SKILL.md", "lines": 120, "words": 800, "bytes": 4096}],
  "total": {"lines": 120, "words": 800, "bytes": 4096}
}
```

- `path` 可重复；不写 = 整版。写了但不在清单里 → 404 `file_not_found`。
- `pin` / `digest` 与 `AuthorVersionResponse` 同义（同 §grep）。
- 三个数字总是都算；CLI 按 `-l` / `-w` / `-c` 选列。无标志时按本地 `wc` 打印三列 + 总计。
- 口径：`lines` = `\n` 的个数；`bytes` = 文件的字节数（`size`）；文本文件的 `words` = 由 `Character.isWhitespace` 分隔的连续非空白段。`is_binary=true` 的文件也计数（本地 `wc` 不跳过），`words` 按 ASCII 空白字节（空格 / `\t` / `\n` / `\r` / `\f` / `\v`）分。夹具用 ASCII 空白；非 ASCII 空白的差异在帮助里注明（见 §其余问题 2）。

### CLI

#### 命令与输出契约

| 命令 | 参数 | stdout | 退出码 |
|---|---|---|---|
| `skill ls [地址]` | 地址可选（钉从 invoke / 当前解析） | 每行 `path\t<bytes>\t<text\|binary>`；**没有 `--json`** | 0 / 1 |
| `skill cat 地址 路径…` | 至少一个路径 | 原始字节串接；不加分隔、不加提示 | 0；任一失败 1（继续读其余） |
| `skill wc 地址 [路径…] [-l] [-w] [-c]` | 无路径 = 整版 | 照 `wc`：每文件一行，多个文件加 `total` | 0 / 1 |
| `skill grep 地址 <pattern> [-F] [-i] [-n] [-l] [--path <前缀>]` | pattern 必填 | `path:text` 或 `path:line:text`；`-l` 只列路径；截断说明走 stderr | 0 命中 / 1 无命中 / 2 出错 |
| `skill write 地址 路径` | stdin | 不打印内容；成功一行摘要到 stderr | 0 / 1 |
| `skill edit 地址 路径 --old <文本> --new <文本> [--replace-all]` | `--old` 必填且非空 | 成功一行摘要到 stderr；0/多匹配报错且不改 | 0 / 1 |
| `skill rm 地址 路径…` | 至少一个 | 每删一个在 stderr 打一行「已删除 X。」；错误也到 stderr | 0；任一失败 1（继续删其余） |
| `skill mv 地址 <from> <to>` / `skill cp` | 两个路径 | 成功一行摘要到 stderr | 0 / 1 |

- **地址与钉**：`namespace/name[@钉]`。读命令的钉不写时用 invoke 记下的；再没有按 PRD §invoke 的缺省规则解析（读命令与 `invoke` 同一口径）：先解析出具体的一版（作者面 `version list` 的 `status = live`，没有上线版本时 `write`+ 取最新可见的一行；消费面用 `Metadata` 的当前版本），再带着这个钉调用。作者面路由不接受 `latest`。
- **写的钉不套用读的缺省规则**：`write` / `edit` / `rm` / `mv` / `cp` 只认显式钉或 `invoke` 记下的钉；两者都没有就拒绝（PRD 验收 #1）。**钉必须是行 id**（8 位短码 / 26 位 ULID，或 `draft:<26 位 ULID>` 旧拼写）：`@1.2.3` / `@sha256:…` 在 CLI 就拒绝（「写只针对草稿；草稿的地址是 `@<版本码>`」），invoke 钉是命名/摘要时同样拒绝——即使那个摘要恰好等于某份草稿的内容，也不把摘要当草稿地址。服务端再按行状态拒绝（已发布 409、已下架/已丢弃 404 或 `maintain`+ 409），CLI 原样显示。
- **写的基线 digest**：`If-Match` 用这一版的 digest。CLI 一律先 `GET …/files` 拿 `ETag` 再发写（本机钉里的 digest 只用于记录，不作为 `If-Match`）；同一命令内多路径用响应里的新 digest 串接。`rm` / `mv` / `cp` 同理。
- **提示行**：新内容命令（`ls` / `cat` / `wc` / `grep`）每次用钉在 **stderr** 打一行「按 X 操作」；`read` 保持现状；`files` 跟 `ls` 走。
- **`--json`**：内容命令（`ls` / `cat` / `wc` / `grep`）不接受；`version list` / `version show` 的 `--json` 不变。
- **退出码机制**：新增一个 `exitError{code}`（或等价的 `reportError` 分支），让 `grep` 的无命中（1）与出错（2）不靠错误消息区分；`main` 的 `os.Exit(reportError(err))` 直接返回它。
- **多路径 `rm`**：逐个请求；每次成功用响应的新 `digest` 作为下一次的 `If-Match`（只在本次命令内；目标若不是 invoke 钉，不改本机钉）。中间一个失败时继续删其余文件，最后退出 1。

#### 与 Claude Code 的 Edit / Write 对齐

| Claude Code | 这里的命令 | 相同点 / 差异 |
|---|---|---|
| `Write` | `skill write 地址 路径`（stdin） | 整文件覆盖，不存在则创建；空内容允许（空 `SKILL.md` 会被 frontmatter 校验拒绝）。 |
| `Edit`（`old_string` / `new_string` / `replace_all`） | `skill edit 地址 路径 --old … --new … [--replace-all]` | 精确子串匹配、区分空白与换行；默认要求恰好一处；0 处拒绝，多处未给 `--replace-all` 也拒绝；`--replace-all` = `replace_all`，`--all` 作为短别名保留（PRD 的写法）。 |
| 读后编辑 | `edit` 内部先 `GET` 单文件 | 不必先手动 `cat`；`GET` 同时拿 `ETag`，写时带 `If-Match`——等价于「基于刚读到的内容改」，但并发变了会 409，而不是静默覆盖。 |
| 失败不改 | 0 / 多匹配、`--old` 为空、二进制、超限 | 一律退出 1，**不写**；错误到 stderr。 |

`edit` 不新增服务端语义：服务端只认 `PUT`，`GET` + 本地替换 + `PUT` 三步都在 CLI。这样 Claude Code 的编辑语义只在 CLI 一处定义，服务端不出现第二种「编辑」。

#### 钉的表示

`internal/pins.Pin` 从 `{Name, Digest}` 扩成：

```go
type Pin struct {
    Kind   string `json:"kind"`   // "code" | "name" | "digest" — 当初按哪种形式 invoke
    Name   string `json:"name"`   // 版本号；Kind != "name" 时为空
    Digest string `json:"digest"` // 线上 digest 的裸 hex；`Suffix()` 在 digest 钉上补 "sha256:"
    Code   string `json:"code"`   // 行 id（短码或旧 ULID）；只用于展示，不决定寻址
}
```

- `Suffix()` 按 `Kind` 返回：`code` → `Code`，`name` → `Name`，`digest` → `sha256:` + `Digest`（存储统一裸 hex；旧文件里带前缀的值原样返回，不重复拼）。`read` / `files` / 新命令发送它。`Code` 非空**不**意味着地址用短码——一个 `@1.2.3` 的钉也带 `code`（展示用）。
- 旧 pin 文件没有 `Kind`：读取时按 `Name` 非空 → name，否则 digest 兼容；旧记录没有 `Code`。`Kind` 为空时 `Suffix()` 走这条回退。
- `Suffix()` 只决定**地址拼写**，不决定走哪个面；面由命令规则决定（§面与档位）。
- 写成功后 CLI 用响应里的新 `digest` 更新本机钉；写的是显式钉（不是 invoke 钉）时不改本机钉。

#### `invoke` 的选面与钉

```
invoke <地址>@<行 id>   → 作者面（只此一条）
invoke <地址>@1.2.3     → 消费面（不回落作者面）
invoke <地址>@sha256:…  → 消费面
invoke <地址>           → 先消费面；没有上线版本时，`write`+ 用作者面回落最新可见版本
```

- 行 id 的 `invoke`：作者面 `ReadAuthorVersion` 取事实 + `AuthorFile(SKILL.md)` 取正文；成功即记钉。**不再在 CLI 本地拒绝草稿**；`version.go` 的 `isDraftPin` 仍要用于 `read` / `files` / `invoke` 的地址校验——改成接受裸 8 / 26 位 Crockford 与旧的 `draft:`（`rename` / `note` 走 `writablePin`，不受影响）。`version list/show` 输出的钉改为裸 id，相关提示语（「草稿的钉是 draft:<id>」）同步改。
- **`invoke` 档用短码**：作者面回 **403**（这一档能看见 skill 但低于 `browse`，现有作者的可见性语义就是 403，不是 404），CLI 说清「调用档没有作者面入口；行 id 是作者面的地址（需要 browse 及以上），请改用版本号或摘要」；不回落消费面（短码在消费面也解析不了）。
- 命名/摘要钉走消费面：revoked 的 410 保持 410；消费面 404 就是 404（维护者用 `version show` / `skill read`）。
- 缺省钉的回落：消费面 **404**（没有上线版本）后，`write`+ 用作者面的 `version list` 找最新可见版本并加载；`browse` 档得到「还没有上线版本」；`invoke` 档得到 404。消费面 **503**（skill 下线）时反过来先问作者面：`browse`+ 读到最新可见版本，`invoke` 保留 503。
- CLI 在调用前就解析出了具体的钉，所以「按 X 操作」用它打印；消费面用 `Metadata` 的版本号/摘要。

#### `edit` 的客户端步骤

1. 解析目标钉与面；写只走作者面，钉必须是行 id（semver / 摘要在 CLI 拒绝）。目标是不是草稿以服务端为准，CLI 不预判状态。
2. 先 `GET …/files`（清单）找到这个 relpath：不在 → 404；`is_binary=true` → 本地拒绝（不读内容）。再 `GET` 文件：字节 + `ETag`（版本 digest）。
3. `--old` 为空 → 报错退出 1。按 UTF-8 解码；`strings.Count(text, old)`：
   - 0 → 报错，退出 1，不写；
   - >1 且没有 `--replace-all`（或 `--all`）→ 报错，退出 1，不写；
   - 1（或给了替换全部）→ `strings.ReplaceAll`。
4. `PUT` 新字节，`If-Match: <ETag>`；409 时提示「这一版已被改动，重新读一遍」。
5. 成功一行摘要到 stderr；显式钉不改本机钉，invoke 钉更新 digest。

#### 写拒绝的文案

所有写拒绝共享前缀「写只针对草稿」：

- 命名 / 摘要钉（CLI 本地）：`写只针对草稿；草稿的地址是 @<版本码>。用 version list --status draft 找到它。`
- 已上线（可见）：`写只针对草稿；这一版现在是「线上」（@1.2.3）。要改内容，先找一份草稿：skillmaster version list --status draft。`
- 已丢弃 / 已下架（`maintain`+）：`写只针对草稿；这一版现在是「已丢弃」/「已下架」。要改内容，先找一份草稿：skillmaster version list --status draft。`

CLI 的其它用户可见句子遵循 `design/voice-spec.md` 的错误句式（发生了什么 + 还能做什么）；CLI 专用句子在这里定义；网络 / 解析错误保留原始错误。

### 网页（P2）

- `AuthoredSkillResponse.Version` 加 `code`（行 id，所有状态）；`AuthoredVersion`（`skillmaster-web/src/api/types.ts`）加 `code`。
- `version.ts`：
  - 删除 `DRAFT_PIN` 与所有 `draft:` 分支：设计稿只认裸行 id，**旧 v6 `?version=draft:<ULID>` 不再兼容**（旧书签落「没有这一版」）。要兼容就先改设计稿，不在实现里单方面加。
  - `parseVersionRoute` 对**任何非空 `version`** 都保留（不认识的交给单版页落「没有这一版」）；现在不认识的会被丢掉并落版本列表——不补这一条，T-WEB-04 的「旧 `draft:` 落没有这一版」就实现不出来。
  - `isVersionSuffix` 接受 8 位短码、26 位 ULID；消费面仍只认 semver / `sha256:`。
  - `versionSuffix`：草稿 / 已丢弃用 `code`（裸；`code` 缺省时回退到旧的 `draft_id` 字段，只为读旧响应）；线上用 `name`，无名为 `digest`。
  - `answersPin`：草稿 / 已丢弃只接受裸 `code`（`draft_id` 同理，读旧响应）；线上只接受 `name` 与 `digest`，**不接受 `code`**（版本码只展示，不参与寻址）。
  - `useVersionActions.ts` 里硬编码的 `draft:` 一并改走 `versionSuffix` / `answersPin`，不再自己拼前缀。
- **`VersionFacts.vue` 是替换，不是新增**：把现在草稿专用的「版本标识」行（`status === 'draft'` 时追加在末尾）**换成「版本码」**，所有作者面状态都画，位置在名称、版本号之后、状态之前（设计稿第 3 行）；调用档仍是四项、没有版本码。地址 = `ns/name@<versionSuffix>`；**地址里已经包含 code，版本码行是行 id 的单列展示**（一个用于复制整串，一个用于核对行身份），不是「同一个串的再次展示」。
- `VersionRow.vue`：说明子行右端加版本码 + 复制（`browse`+）；版本码在**作者与时间之前**、同一格**不截断**；复制按钮**压在整行链接之上**（点复制不触发整行导航）；调用档不画。
- 版本页 URL：草稿 / 已丢弃 `?version=<code>`；线上 `?version=<name|digest>`。**不兼容旧 `draft:` 前缀**（见上）。`version open` 把旧 `draft:<ULID>` 规范成裸 id；它不把**线上版本的裸行 id** 解析成版本号——行 id 不是网页地址，页面会落「没有这一版」，而不是静默打开另一版（要打开线上版本请用 `version show` 给出的 `@<版本号>`）。

## 分期

### P0 服务端

1. **地址与行 id**
   - `common/ShortCode`（8 位 Crockford，`SecureRandom`）；`VersionPin` 的 `Draft` 改名/扩展成 `RowId`，`suffix()` 输出裸 id；`SkillAddress.pinOf` 接受 8/26 位与旧 `draft:`；消费面拒绝。
   - `VersionRepository.insertIfAbsent` 的冲突目标 `(skill_id, digest)`（PK 与 `(skill_id, number)` 从静默变异常）；新增不带事务的 `DraftRecorder`（`TransactionTemplate` 包住整个提交），**两个 controller 入口**改调它；`PublishGatewaySkillUseCase` 保持自己的事务、不重试（见 §digest 与唯一约束）；订正 `insertIfAbsent` / `VersionRepository` 的注释（表锁归属）与 `skill_version.id` 的列注释。
   - `AuthorVersionResponse` / `AuthoredSkillResponse` 加 `code`；`pin` 对草稿/已丢弃输出裸 id（破坏性变更，发布说明要求同版升级 CLI）。
2. **草稿写**
   - `BlobStore.put` 复用；新增 M7 的 `writeFile` / `deleteFile` / `moveFile` / `copyFile` / `findForUpdate` / `updateDigestAndCounts`。
   - 4 个 use case（write / delete / move / copy；`edit` 复用 write 的 PUT，不新增）：权限、状态、If-Match、路径、frontmatter、上限、审计。写路径**不拿** `version_file` 表锁；订正 `BlobGc` / `SkillVersionService` 的 javadoc。
   - `BlobGc` 的 grace period：`deleteUnreferenced` 加 cutoff（RFC3339 字符串或 SQL cast），`sweep` 只删早于 cutoff 的未引用 blob（见 [ADR 0065](../../decisions/0065-blob-reclamation-waits-for-a-grace-period.md)）。
   - 新错误码（`version_is_not_draft` / `grep_requires_draft` 等）+ **新的 409 / 428 异常类型与 handler 分支**；`content_already_exists` 先查后写。
3. **读**
   - `BlobStore.open` 流式；作者面与消费面单文件都改 `StreamingResponseBody`（共用写出函数）；作者面加 `ETag`；清单加 `ETag`（**不加 `X-Skill-Pin`**）。
   - `grep` / `count` 的 use case + M6 预筛（`position`）+ 文本缓存。
   - **调用档不暴露文件结构**：作者面清单路由（`GET …/files`）改成先做 `browse` 入口检查，`invoke` 一律 403（现在先解析后查档，带草稿/已下架钉会落 404）。消费面 detail 已 403、`/metadata` 无 `files`、**消费面单文件**对 `invoke` 开放——现状已满足，**不改 `SkillDetailResponse`**。网关索引 V1 是匿名公开面，豁免。
4. **测试**：`AbstractIT` 真端口 + 真库；写/并发/权限/状态/上限/审计/grep/流式；`VersionRepository` 的撞码（注入固定 id 的 seam：`ShortCode` 生成器可替换）；`DraftRecorder` 的「撞码 → 新事务重试」；GC 的 grace period 不删新 blob、`rm` 后复用旧 blob 的 FK 失败可重试；作者面 `files` 对 `invoke` 403；消费面 detail 403 与 `/metadata` 无 `files` 是现状（回归）；网关索引 V1 豁免。

### P1 CLI

1. `pins.Pin` 扩 `Kind` / `Code` + `Suffix()`（digest 不重复加前缀）；`invoke` 选面与草稿钉；`version list/show` 输出裸 `code`；`isDraftPin` 改为服务 `read` / `files` / `invoke`，接受裸 8/26 位与 `draft:`；相关提示语同步。
2. 新命令 `ls` / `cat` / `wc` / `grep` / `write` / `edit` / `rm` / `mv` / `cp`；`files` 别名；`read` 不动；`ls` **没有 `--json`**。
3. `edit` 对齐 Claude Code：`--old` 非空、唯一匹配、`--replace-all`（`--all` 别名）、先读清单判二进制；写前缺 digest 时先 `GET` 拿 `ETag`。
4. 退出码（grep 0/1/2）、stderr 提示、流式、EPIPE、多路径。
5. 端到端：真二进制 + 真服务端；**五个可授予档**（invoke / browse / write / maintain / admin）各一把 + 一把**所有者账号**的凭据（owner 不可授予，不在梯子上）+ 缺 scope 的 key。

### P2 网页与文档

1. `code` 字段、`version.ts`（**删除 `DRAFT_PIN` 与 `draft:` 分支**、`answersPin` 只认裸 code/name/digest）、`VersionFacts`（**替换**版本标识行为版本码、所有作者面状态、第 3 位）、`VersionRow`（子行右端、作者/时间之前、不截断、复制压整行链接）、`useVersionActions.ts`、URL（不兼容旧 `draft:`）。
2. 新 ADR：[0064](../../decisions/0064-a-draft-may-change-a-published-version-may-not.md)（草稿内容可变、发布后冻结）、[0065](../../decisions/0065-blob-reclamation-waits-for-a-grace-period.md)（GC 的 grace period、「写路径不拿表锁」与「发布/丢弃不 sweep」）、[0066](../../decisions/0066-grep-uses-a-linear-time-regex-engine.md)（grep 用线性时间正则引擎）、[0067](../../decisions/0067-grep-has-a-cost-ceiling.md)（grep 的执行成本有硬上界）。
3. CLI 帮助与 README；`skill grep --help` 的正则/二进制/截断说明；`skill edit --help` 说明与 Claude Code 的对应。
4. 设计稿的 26 位 ULID / 无名旧行口径已锁；网页实现照设计件搬。**`voice-spec.md` 已在 2026-10-20 登记「版本码」**；P2 只核对界面用词，不再改设计文件。
5. README / 测试方案的状态与「回收触发点」已同步到本 TD（submit / 软删除，不是发布 / 丢弃）。

## 开放问题

### 已定（2026-10-20）

1. **写后 digest 与同 skill 另一版相同**：409 `content_already_exists`，detail 给出已有的 `state`（`pin` 只在已上线时给出）；不静默合并、不删草稿。保留 `UNIQUE(skill_id, digest)`；代价是「把草稿退回成某一版的原文」被拒绝（§digest 与唯一约束）。
2. **长期不发布的草稿的 blob 回收**：本版接受孤儿 blob；写路径不加锁、不 sweep，**下一次 submit / 软删除**（发布 / 丢弃并不 sweep）按现有 `BlobGc` 全量扫（sweep 带 10 分钟 grace）；test-plan 记成已知代价（§blob 回收）。
3. **写根 `SKILL.md` 的 frontmatter**：更新该版本的 `title` / `description` / `frontmatter`；skill 行不动，发布时按现有规则取该版本的值（ADR 0031 §决定 9）（§行的可变性）。
4. **网页旧 `?version=draft:<ULID>`**：**不兼容**——设计稿只认裸行 id；旧 v6 书签落「没有这一版」。要兼容就先改设计稿，不在实现里单方面加。CLI 的 `@draft:<ULID>` 输入兼容不受影响（PRD #23）。

### 其余问题（3–5 已定，1–2 仍开放）

1. **正则的边界**：RE2 正则，逐行；不承诺 BRE/PCRE。不支持反向引用与 lookaround（给 400 `invalid_request`，detail `bad_pattern`）；`\p{…}` 由 RE2 的 Unicode 组支持。帮助里列出边界（[ADR 0066](../../decisions/0066-grep-uses-a-linear-time-regex-engine.md)）。
2. **`wc -w` 的空白定义**：当前倾向 `Character.isWhitespace`；与 GNU `wc` 在非 ASCII 空白上的差异写进帮助。验收夹具用 ASCII 空白。
3. **`mv` / `cp` 的接口形状（已定）**：`POST …/files` 带 `{op,from,to}`；不新增 `/files/{*relpath}/move`（`{*relpath}` 会把 `move` 当成文件名）。
4. **`invoke` 的选面（已定）**：已按钉的形式定死——行 id 走作者面，命名/摘要走消费面，缺省走消费面再按 `write`+ 回落作者面；新的 `cat` 命名/摘要钉先走消费面，410 / 404 再问作者面（见 §面与档位）。没有运行时的梯子名次可用；要不要在作者面响应里加 `rung` 以省掉一次失败请求？当前倾向不加——多一次请求比多一个会过期的字段便宜。
5. **`edit` 在客户端还是服务端（已定）**：客户端（读 + 替换 + PUT + If-Match）。服务端 `edit` 能少传一次文件，但会多一种写语义；本版不引入。
6. **写后的时间戳**：版本行没有 `updated_at`；本版不新增列，写时间只进审计。要不要在版本事实里展示「最后编辑」？当前倾向不展示，避免新列与新事实。

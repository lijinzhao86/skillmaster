# v44-cli-read-write-contract · 技术设计

> **最后更新**：2026-11-04
> **状态**：设计稿
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**：
> [0135](../../decisions/0135-a-draft-may-be-copied-from-a-version.md)（草稿可从一版复制）、
> [0136](../../decisions/0136-cli-file-verbs-follow-intent.md)（读写命令按意图分）、
> [0137](../../decisions/0137-edit-takes-stdin-and-batches.md)（`edit` 的输入通道）。

## 架构

### 服务端：建草稿从「第一次改动」里拆出来

今天的入口是 `AuthorSkillWriteController.createVersionDraft`（`skillmaster-server/src/main/java/com/skillmasterai/api/AuthorSkillWriteController.java:345`）。它把 `body.edit()` 原样传下去，`CreateDraftFromVersionUseCase.create`（`.../usecase/CreateDraftFromVersionUseCase.java:120`）在 `edit == null` 时抛 `invalid_request`。

本版只改这一条分支：

```
POST /author/skills/{ns}/{name}/versions   { name, note, from?, from_digest?, edit: null }
  → CreateDraftFromVersionUseCase.create
      → prepare(access, from, from_digest)       读底稿：线上 → 最新可见草稿 → skill 行最小 SKILL.md
      → Manifest.of(prepared.entries)           原样，不应用任何改动
      → requireWithinLimits / requireTree
      → versions.submitFromBase(...)            插一行，文件引用原 blob
```

带 `edit` 的旧分支（控制台与 `@new`）保持不动；本版之后 CLI 不再走它，控制台仍走。

内容唯一性从草稿写路径移除：

- `VersionSubmitter.submitFromBase`（`.../modules/version/VersionSubmitter.java:144`）不再查 `findBySkillAndDigest`。
- `VersionDraftEditor.commitContent`（`.../modules/version/VersionDraftEditor.java:134`）不再查 `findBySkillAndDigest`；保留 `lockContentOf`，它现在只保护同一行的并发写。
- 非草稿行之间的唯一性交给数据库局部唯一索引；上线路径在更新状态前先查同内容的非草稿行（`findNonDraftBySkillAndDigest`），命中就返回 409 `content_already_exists`，而不是让唯一索引在 UPDATE 里炸成 500。

### CLI：四条路径

```
draft create ──→ 查「我的未上线草稿」──→ 有且无 --new：拒绝并列出
                        └─ 无 ──→ api.CreateDraft(edit: null) ──→ 版本码 + 下一步命令

write --from ──→ 目标已存在？──→ 本机读记录 + 当前 ETag 都对得上？──→ 算 diff 行数 ──→ AuthorWriteFile(If-Match)
                        └─ 否 ──→ 拒绝，提示先 read

get --all ──→ 空目录检查 ──→ StreamArchive ──→ 解包 + 旁车（沿用 download 的路径）

edit --stdin-json ──→ AuthorFileBytes 一次 ──→ 内存里应用 N 处 ──→ AuthorWriteFile 一次(If-Match)
```

“读过”的记录落在版本钉存储：`skillmaster-cli/internal/pins/store.go:47` 的 `Pin` 增加一个 `read` 映射，随记录原地覆写（[ADR 0101](../../decisions/0101-the-cli-never-deletes-files.md)）。

`SKILL.md` 裸读拦截放在四个视图命令共用的解析处，而不是各命令各写一份：

- `skillmaster-cli/cmd/skillmaster/read.go:135` 的 `pinFor` 已经回答“这一版从哪来”；
- `skillmaster-cli/cmd/skillmaster/fileviews.go:420` 的 `contentFor` 是 `read` / `cat` / `head` / `tail` 共用的读取入口；
- 在两处之间加一个判断：`pin == ""` 且路径是 `SKILL.md` → 退出 2，提示先 `invoke`。

## 数据模型

### 1. `skill_version` 的唯一约束

基线在 `skillmaster-server/src/main/resources/db/migration/V1__baseline.sql:391`：`UNIQUE (skill_id, digest)`。新增一条迁移（下一个空号）：

```sql
ALTER TABLE skill_version
  DROP CONSTRAINT skill_version_skill_id_digest_key;

CREATE UNIQUE INDEX uq_skill_version_content_nondraft
  ON skill_version (skill_id, digest)
  WHERE state <> 'draft';
```

- 约束名是 PostgreSQL 为内联 `UNIQUE` 生成的默认名；迁移里用 `DROP CONSTRAINT IF EXISTS`，并在测试库上先 `\d skill_version` 核对一次。
- `UNIQUE (skill_id, number)` 不动：草稿也要有独立的提交序号。
- 局部索引只覆盖 `published` / `discarded` / `revoked`（`VersionState` 的四个值见 `V23__version_lifecycle.sql:24`）。
- 上线一份与线上同内容的草稿会被索引挡住；`VersionPublisher` 在状态更新前先查同内容的非草稿行，命中即 409 `content_already_exists`（附 `state` / `pin` / `code`），与现有错误形状一致，也不依赖异常翻译。

### 2. 版本钉记录旁的“读过”

读记录**不塞进 `Pin` 文件**，而是放在钉记录目录旁的 `reads/`（`skillmaster-cli/internal/pins/reads.go`）。理由是键不同：pin 记录是“这个地址钉了哪一版”，读记录是“这台机器看过 `(地址, 版本码, 路径)`”。读一个显式版本并不产生 pin，硬塞进去就得凭空造一个调用方从没钉过的 pin。

```json
{
  "address": "dev/my-skill",
  "reads": {
    "7V5N319A": {
      "SKILL.md": { "etag": "\"sha256:…\"", "at": "2026-11-04T09:12:00Z" }
    }
  }
}
```

- 一个地址一个文件，文件名是地址的 SHA-256 前 8 字节，与 pin 的同目录布局一致。
- **键**：`pin`（写入时地址里的那个拼写）→ `path` → `etag`。`pin` 用地址里写的字符串（`writeTarget.pin`），不用解析后的行 id——读和写必须用同一种拼写才能对上。
- **值**：读时文件清单响应的 ETag（版本摘要）。草稿任何一次写入都会换掉它，旧记录自然作废。
- **容量**：每份记录最多 200 条，按 `at` 淘汰最旧的；淘汰只改映射再原地覆写，不删文件（ADR 0101）。
- **写入**：`OpenReads(...).Record(address, pin, path, etag, at)` 与 `Lookup(...)`，读—改—写同一个文件；损坏时按 `CorruptError` 修复，与 pin 记录的损坏处理同构。

### 3. 没有新表

## 接口

### 服务端

`POST /api/v1/author/skills/{namespace}/{name}/versions`

```json
{ "name": "v2", "note": "可选", "from": "1.2.0", "from_digest": "sha256:…", "edit": null }
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `name` | 是 | 1–40 字符，沿用 `VersionLabels.name` |
| `note` | 否 | ≤200 字符 |
| `from` | 否 | 版本名 / 版本码 / 摘要；省略时服务端按 `resolveBase` 选底稿 |
| `from_digest` | 否 | 调用方读到的底稿摘要，用来拒绝底稿已变 |
| `edit` | 否 | **本版新增可为 null**；null 表示原样复制底稿 |

- 成功：201，响应形状沿用 `AuthorDraftResponse`（`version` + `manifest`）。
- 失败：`draft_name_required` / `draft_name_too_long`（400）、`not_found`（404，skill 或底稿）、`insufficient_permission`（403）、`invalid_request`（400，`from` 格式不对）。
- 不再返回 `content_already_exists`（草稿可以与任何一行同内容）。

### CLI

退出码沿用现有约定：成功 0；拒绝 / 找不到 / 冲突 1；用法错误 2。

#### `draft create`

```bash
skmr draft create <命名空间>/<名字> [--from <版本|版本码>] --name <名称> [--note <说明>] [--new]
```

- `--new` **不进 help**，只在报错里出现。
- 先查「我的未上线草稿」（`AuthorVersions` + `status=draft`，过滤 `submitted_by == Me.User.Username`）：
  - 有且无 `--new` → 退出 1，输出草稿码、名称、时间与两条下一步；
  - 有且带 `--new` → 继续；
  - 只有协作者的草稿 → 不拦。
- 成功输出：版本码 + 一条可直接复制的下一步写命令。

#### `skill write`

```bash
skmr skill write <地址>@<版本码> <远端路径|目录/> [--from <本地文件>...] [--overwrite] [--allow-empty]
```

- 无 `--from`：从 stdin 读文本，沿用现有归一与 `--allow-empty` 规则。
- 有 `--from`：每个本地文件一个来源；多个时远端必须以 `/` 结尾，按 basename 落进目录；二进制按原字节流式上传（复用 `put.go` 的读取与上限：单文件 100 MiB、整 skill 512 MiB / 512 文件）。
- `--overwrite`：目标已存在时，先查 `LookupRead(address, pin, path)`，再取当前文件 ETag；两者一致才放行。否则退出 1：

```text
这个版本已经有 SKILL.md；你还没读过它，或读过之后这一版变过。
先看一眼：skmr skill read <地址>@<版本码> SKILL.md
以后确实要整份覆盖，再带 --overwrite 跑一次。
```

- 放行时先打印 `将改动 N 行（+a / -b）`，N = 新增行 + 删除行；二进制文件打印 `将替换 N 字节`。
- 写回用 `AuthorWriteFile` 带当前 ETag（If-Match），期间别人改过就是 409，不静默覆盖。

#### `skill get`

```bash
skmr skill get <地址>@<版本码> <路径> [-o <本地路径>] [--overwrite]
skmr skill get <地址>@<版本码> --all [--into <目录>]
```

- `--all` 与 `<路径>` / `-o` 互斥；目标规则沿用 `checkTarget`（不存在或空目录；`.` / `..` / 根目录拒绝）；旁车 `.skillmaster/metadata.json` 照写；仍受 `maxArchiveBytes`（约 17 MiB）限制。
- 单文件 `get` 记一条读记录；`--all` 不记。

#### `skill edit`

```bash
skmr skill edit <地址>@<版本码> <路径> (--old … | --old-file <文件|->) [--new … | --new-file <文件|->] [--replace-all] [--regex]
skmr skill edit <地址>@<版本码> <路径> --stdin-json < <JSON>
```

- `--old-file -` / `--new-file -`：从 stdin 读；两端都写 `-` → 用法错误。
- `--stdin-json` 接受 `{"old","new","replace_all","regex"}` 或 `{"edits":[…]}`；每项键名相同。
- `--stdin-json` 与任何输入 flag 同给 → 用法错误。
- 批量：`AuthorFileBytes` 一次 → 内存里逐项应用（复用现有 `applyEdit` / `editdiff.go`）→ `AuthorWriteFile` 一次；任一项失败 → 不写，错误带 `第 N 处：…`。

#### `read` / `cat` / `head` / `tail`

裸地址 + 本机未 `invoke` + 路径 `SKILL.md` → 退出 2：

```text
SKILL.md 是技能正文；读它要先加载这一版。
  skmr skill invoke <地址>
只想看别的文件：直接给路径；已经知道版本：在地址里写 @<版本>。
```

`cat` 多路径时，只要有一个是 `SKILL.md` 且未钉版本，整个命令拒绝，不输出任何半份内容。

## 分期

| 期 | 交付 | 覆盖验收 |
|---|---|---|
| P1 服务端模型与建草稿 | 迁移、`create` 的 null-edit 分支、移除草稿内容检查、CLI `draft create`、`--new` 拦截 | A1、A2、A3、A12、A13 |
| P2 词表与覆盖保护 | `write --from`、`get --all`、删 `put` / `download`、读记录、覆盖前检查与 diff 行数 | A4、A5、A6、A7、A11 |
| P3 输入通道与裸读拦截 | `edit` 三种输入、`SKILL.md` 拦截、生成视图与技能正文 | A8、A9、A10 |

每一期结束时既有测试保持绿；P1 的迁移与 API 先于 P2 合并进分支，避免 CLI 与模型同时漂。

## 开放问题

**本版没有会改变范围的未决问题。** 以下是已定的实现取舍，记在这里供复核：

1. **读记录放哪**：与版本钉并排的 `reads/` 目录（一个地址一个文件，原地覆写），不进 `Pin` 本身——读显式版本并不产生 pin。上限 200 条，按时间淘汰。
2. **`get --all` 是否算读过**：不算。它没有让调用方看见哪个文件；要覆盖仍要逐个 `read` 或单文件 `get`。
3. **没有线上版本时 `draft create` 的底稿**：沿用服务端现有 `resolveBase`（最新可见草稿 → skill 行的最小 `SKILL.md`），不另造一条规则。
4. **`get --all` 的内存上限**：保持约 17 MiB；流式落盘是 v39 的技术欠账，不在本版。
5. **`--new` 的形态**：隐藏 flag、非交互可用、报错要求先问用户；不做 TTY 确认。
6. **上线同内容草稿**：`VersionPublisher` 更新状态前先查同内容的非草稿行并返回 409；局部唯一索引仍是最后一道闸。
7. **`submit` 的回放按作者算**：同内容现在可能对应多行。先找同内容的非草稿行（对所有人是同一版），找不到再找**本人**的同内容草稿；别人的草稿不是「你提交过了」。`findBySkillAndDigest` 因此加 `ORDER BY` + `LIMIT 1`（published → draft → 其他）。

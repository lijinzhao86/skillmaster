# v54-skill-copy · 技术设计

> 本文只写本版相对 [`docs/current/`](../../current/README.md) 的差异；取舍的理由在 [ADR 0173](../../decisions/0173-a-copy-is-a-snapshot-in-your-own-namespace.md)，这里只写形状。

## 架构

**一条新用例，复用已有的读、存与提交原语；没有第三条提交管道。**

- **读源**：`CopySkillUseCase`（新，`usecase/CopySkillUseCase.java`）先用 `SkillMetadataService.accessFor` 拿到源 skill 的 `Access`（与所有读写路径同一个谓词），要求 `!access.owns()` 且 `access.atLeast(GrantRole.BROWSE)`；再用 `SkillVersionService.resolveForConsumption(access, pin)` 解析出版本快照。`pin` 由请求里的版本（可空 = `VersionPin.Latest`）经 `SkillAddress.pinOf(value, false)` 得到——草稿短码在这里解析不出来，复制只接受已发布版本。
- **改名**：目标名字与源快照的 `SKILL.md` `name` 不同时，用 `SkillNameTransform.rewriteSkillMd(byte[], targetName)` 只改顶层 `name`（[ADR 0160](../../decisions/0160-imports-are-renamed-by-an-optional-source-prefix.md) 的规则），从 `BlobStore.get` 读 `SKILL.md`、把新字节 `BlobStore.put` 回去，并替换 manifest 里的那一项；目标名字先用 `SkillNameTransform.requireValid` 校验。名字相同则**一个字节都不读**，直接复用源 manifest。
- **建行与草稿**：`namespaces.personalNamespaceOf(subject.userId())` 拿目标命名空间；`SkillMetadataService.createOwned` **只建不找**（同名活 skill → `skill_already_exists`，同名已删除 → `skill_deleted`），随后 `SkillVersionService.submit` 写一条草稿。两者在 `versions.underExclusiveWrite` 的同一个事务里；`SkillMetadata` 用源版本的 `title`/`description`/`frontmatterJson`，改名时用重新解析后的值。
- **复用而不是重造**：`Uploads` 与 `SubmitSkillUseCase` 是 zip 入口；复制没有 zip，也不需要把所有文件读进内存——manifest 与源版本共享同一批 blob（内容寻址，[ADR 0005](../../decisions/0005-content-addressing.md)）。因此不经过 `SubmitSkillUseCase` 的 `findOrCreate`（它会把内容追加到同名 skill，而不是拒绝），而是用 `createOwned` + `submit` 这两个已有原语。上限检查调 `DraftFileWrite.requireWithinLimits(Manifest)`（同包、protected static），与上传同一条边界。
- **许可与出处**：`SourceService.copyLicense(sourceVersionId, newVersionId)` 把 `skill_version_license` 那一行复制到新版本（只换 `versionId`）；`SourceService.recordCopy(...)` 写 `skill_copy`。两者与建行、提交在同一事务。
- **审计**：`AuditLog.record(new AuditEvent(userId, "copy", "skill", newSkillId, detail))`，detail 含源地址、源版本、目标名字、草稿 id、`created=true`。
- **三个入口同一件事**：网页 `WebSkillCopyController`、API `AuthorSkillWriteController` 的 `copy` 方法、CLI `skmr skill copy`，都调同一个用例。

## 数据模型

**新增一张 `skill_copy`（迁移 `V43__skill_copy.sql`），不改 `skill` / `skill_version` 的列。** 意图：

| 列 | 含义 |
|---|---|
| `skill_id` | 复制出来的新 skill；**主键**。一次复制只产出一个新 skill，所以 provenance 是 skill 级的，后续版本沿用 |
| `version_id` | 复制出来的第一条草稿；唯一。它是「复制发生的那一版」 |
| `source_skill_id` / `source_version_id` | 源的身份；保留 id，源后来删除或改名都不影响这一行 |
| `source_address` | 复制那一刻的 `namespace/name`，**快照文本**；以后源地址变了也不改 |
| `source_version_label` | 复制那一刻的版本标识（`SkillSnapshot.addressSuffix()`：版本号或 digest），用于信息页与 CLI |
| `copied_by` / `copied_at` | 谁、什么时候 |

不设外键：`skill_version` 的 `current_version_id` 已经证明这条模型可以只存 id（[`current/model.md`](../../current/model.md)）；源 skill 可以软删除，外键只会让复制记录跟着不可用。`source_skill_id` 上加一条普通索引，供「谁复制了我」这类将来查询；本版只按 `skill_id` 读。

**许可**不新增列：`skill_version_license` 每个版本一行，复制插入新的一行。**可见性**不新增列：`createOwned` 建出来的行是 private（与新建 skill 同一条路径），本版不给它任何改可见性的入口。

## 接口

### `POST /web/skills/{namespace}/{name}/copy` 与 `POST /api/v1/author/skills/{namespace}/{name}/copy`

路径里的 `{namespace}/{name}` 是**源 skill**；目标名字在 body 里，因为目的地永远是调用者的个人命名空间（与新建 skill 同一条规则：目标不从请求里来）。网页用会话 + CSRF，API 用 bearer；两个控制器只做鉴权、解析与错误映射。

请求：

```json
{ "name": "pdf-tools-copy", "version": "1.2.3" }
```

`version` 可空或省略（等于 `VersionPin.Latest`）；给了就按 `SkillAddress.pinOf(value, false)` 解析——只接受版本号或 `sha256:`，草稿短码不进来。

响应（201）：

```json
{
  "skill": { "namespace": "you", "name": "pdf-tools-copy" },
  "draft": "7K3M9QX2",
  "draft_name": "复制自 dev/pdf-tools",
  "version": null,
  "source": { "address": "dev/pdf-tools", "version": "1.2.3" }
}
```

失败：

| 情况 | 状态 / 码 |
|---|---|
| 源 skill 不可见 | 404（与所有读路径同一个 404） |
| 调用者是源的所有者 | 403 `forbidden`，detail `owner`（要改自己的 skill 直接新建版本） |
| 调用者不到「浏览」 | 403 `forbidden`，detail `browse_required` |
| 指定的版本是草稿 / 已丢弃 | 404 `skill_not_found`（消费面解析不到；与「读一个草稿版本」同一个答案） |
| 指定的版本已下架 | 410 `version_revoked`（`VersionRevokedException` 现有的映射） |
| 源已下线 | 503 `skill_offline`（`SkillOfflineException`；与所有读路径同一个答案） |
| 目标名字不合法 / 保留名 | 422 `invalid_name`，detail 带 `name_too_long` / `invalid_name` / `name_reserved` |
| 目标名字已被占用 | 409 `skill_already_exists` |
| 目标名字属于已删除的 skill | 409 `skill_deleted` |
| 源的 `SKILL.md` 无法安全改名 | 409 `name_not_rewritable`（新增错误码；客户端给「用原名复制」的出路） |
| 超过文件数 / 单文件 / 总量 | 与上传同一条 `IngestException` 映射 |

### 读模型

- `SkillMetaResponse` 增加 `copied_from: {address, version}`（可空，`NON_NULL` 不输出），由 `SkillMetaResponse.copiedFromOf(SourceService, skillId)` 组装。同一个块同时挂在 `SkillMetaResponse`（CLI `skill show` 读的 `/metadata`）、`SkillDetailResponse` 与 `AuthoredSkillResponse`（网页面，控制台信息页读的是它）上，三处不能各说各的。
- 版本列表不需要新接口：复制弹窗读的是页面已经拿到的可读已发布版本（`browse` 及以上才拿得到历史版本，[ADR 0051](../../decisions/0051-the-author-plane-serves-by-tier.md)）。

### CLI

```text
skmr skill copy <命名空间>/<名字>[@版本] --name <新名字>
```

- `<地址>` 的 `@版本` 走现有地址解析；`--name` **必填**，缺失是用法错误（退出码 2，打印 usage），不进交互。
- 草稿短码这类消费面不认的 pin 在本地就拒（用法错误，不发请求），与服务端的 404 不重复报。
- 成功后 stdout 打印新地址与草稿 pin，形状与 `skill create` / `submit` 一致；失败按上表的码给下一句，`skill_already_exists` / `skill_deleted` 复用现有话术。
- 帮助声明加在 `cmd/skillmaster/declarations_skill.go`，分发加在 `dispatch.go` 的 `case "copy"`；实现放在 `cmd/skillmaster/copy.go`。
- **只加这一个命令**：不新增 `skmr copy` / `skmr skills copy`，也不在本版改 help 的分组渲染；`skmr skill --help` 仍是平铺，copy 与 `cp` 的区分靠两份摘要（`cp` 是「复制草稿里的文件」，`copy` 是「把一版内容复制成我的 skill」）。help 的整体分组记在 [`TODO.md`](../../current/TODO.md)「CLI help 的分组与收口」那条。

### 控制台

- `skillmaster-console/src/pages/SkillPage.tsx` 的页头动作组加一颗无边框「⋯」（设计与 `design/src/console/pages/SkillPage.tsx` 同一位置、同一个 `CommandMenu`）。菜单只有一项「复制」；`canCopy` 与设计同一条：`browse` 及以上、非所有者、有可读的已发布版本、未下线；库条目处于休眠或不可加入时也不画——复制要写进一个能用的库。
- 新 `components/skill/CopyDialog.tsx`：版本下拉、名字必填、冲突置灰、成功态两个去向；与 `NewSkillDialog` / `ImportDialog` 共用一个弹窗壳。API 调用放 `src/api/`（新增 `copySkill` 与类型）。
- 成功后「查看新 skill」去响应里的 `skill.namespace/name` 的信息页（不是固定的 demo 地址）——副本下一步多半由 agent 改，信息页才是能接着动手的地方。

## 分期

**一期交付全部**：服务端用例 + 迁移 + 两个平面 + 读模型 + 控制台 + CLI + 设计件。复制是单一动作，拆开会产生「服务端能复制但没有人能发起」或「界面能点但服务端没有出处」的半成品。验收按 PRD A1–A14 一次走完。

## 开放问题

1. **已删除的同名 skill**：实现只给一句「这个名字已经有了」并让用户换名字，没有「去恢复」链接——设计只有一个冲突态，按「设计稿没覆盖的态先问」没有自创。服务端仍以 `skill_deleted` 与 `skill_already_exists` 区分（恢复要按身份走，不能靠猜名字）；恢复入口在「我的 skill › 已删除」。要不要在弹窗里直达，留待下一版决定。
2. **`attribution.md` 是否继续上溯到源的 GitHub 来源**：本版写「复制自 `<源地址>` @`<版本>`」，不上溯；源 skill 自己的信息页会显示 GitHub。倾向不在这一版做多级溯源。
3. **源 skill 被软删除后 `skill_copy` 是否保留**：倾向保留（历史事实，且新 skill 仍要显示出处）；读取只按新 skill 的 `skill_id`，不依赖源的存活。

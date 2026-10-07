# ADR 0034 · 共享是 skill 级的授权，不是命名空间成员

- **状态**：已采纳
- **日期**：2026-10-07
- **依赖**：[ADR 0004](0004-opaque-id-primary-key.md)（身份模型：id 作主键、name 是属性）、[ADR 0012](0012-addressing-and-version-pinning.md)（寻址与版本钉）、[ADR 0031](0031-submitting-and-publishing-are-two-actions.md)（提交与上线分开）
- **扩展**：ADR 0012 的 404 规则——本决定引出这个系统里**第一个 403**（读得到但写不了；见 §理由）
- **后续**：§决定 9 与 §后果 里的 `skillmaster share`、`list`、`submit --to` 现在写作 `skillmaster skill share` / `skill` 组下的动词（2026-10-07 把作用于 skill 的动词收进 `skill` 组，见 [`technical-design.md`](../versions/v1-hosting/technical-design.md) §4.6）。**正文按当时的样子保留**——那是当时决定的事，不是现在的接口

## 背景

[`prd.md`](../versions/v1-hosting/prd.md) §范围 不做 #7 把「组织 / 成员 / 角色体系、**分享**、公共发现与购买」捆成一条，推迟到 v2，理由是「这一版只做『一个自然人登录并管自己的 skill』；**这几项都要先有组织模型**，而它会显著拉大范围」。

2026-10-07 用户要求 skill 要能共享给别的用户。核实下来，那条理由**只对其中一半成立**：

- 组织 / 成员 / 角色、公共发现、购买——确实要先有组织模型。
- **skill 级授权不需要。** 它要的只是「一个 skill」与「一个用户」，两个实体今天都已经存在。当初把它们捆在一起，是因为它们来自同一个需求场景，不是因为它们有共同的依赖。

同一次讨论里还有读取面的重构（CLI 的 `list`），而它把这件事从"顺便"变成"必须先定"：`list` 的语义是「**我有权限的**全部 skill」，而这句话今天只能答成「我自己那一个命名空间」——因为**「有权限」这个概念本身还不存在**。

摆上桌的选项：

| | 做法 | 为什么被否 |
|---|---|---|
| a | 继续推迟到 v2 | 用户明确要的能力，而它并不是被组织模型挡住的 |
| b | **skill 级授权**（一张表 + 两个角色） | 采纳 |
| c | 用 `namespace_member` 表达（把人加进我的命名空间） | 粒度太粗：分享**一个** skill 却让人读到我**全部** skill。而且它才是真正需要组织模型的那条 |
| d | 用已有的 `skill.visibility = public / unlisted` | 那是「任何人」，不是「某个人」。而 v1 只写 `private`，另外两个值是留给公共发现的 |
| e | 授权挂在 `(namespace, name)` 上 | 地址会变。ADR 0004 §理由 的原话：改名就等于换了一个 skill，version、安装记录、审计日志、**以及将来的权益记录**都要跟着迁移 |

## 决定

1. **一张表**：`skill_grant(skill_id, grantee_id, role, granted_by, created_at)`，主键 `(skill_id, grantee_id)`，`role ∈ {viewer, editor}`。归 **M7**——外键指向它拥有的 `skill`。
2. **挂在 `skill.id` 上，不挂在地址上。** 地址只在**授权那一刻**用来称呼它——与「钉存 digest、调用时说版本名」同一个形状。改名之后授权仍然有效，被授权的人 `list` 时看到的是新地址。
3. **角色复用 `namespace_member.role` 已有的词**：`viewer`（只读）/ `editor`（可读写）。不新造 `read` / `write` / `ro` / `rw`。
4. **读判据与写判据是两个问题**：读 = `可读命名空间 ∨ 授权给我`；写 = `拥有 ∨ editor 授权`。两者不得共用一个谓词。
5. **`editor` 能做的恰好两件**：提交新版本；丢弃**自己提交的**草稿。**不能**上线、不能删除 skill、不能丢弃别人的草稿、不能再授权给第三人。
6. **只有能写那个 skill 的人能授权它**（命名空间所有者）。**授权不传染。**
7. **引入 `403`**：调用者读得到、但无权写。**不带 `insufficient_scope` 挑战**——他的令牌 scope 是对的，是**这个资源**拒绝。
8. **只做 skill 级授权。** `namespace_member` 的角色管理继续推迟——那一项仍然需要组织模型。
9. **入口两个**：网页面（与 ADR 0031 同形）与 CLI（`skillmaster share`）。授权与撤销落 `audit_event`（撤销**删行**，历史在审计里——软撤销会造出第二个真相，而这里没有「被撤销了但还要读」的需求）。
10. **`visibility` 与授权是两回事。** 被授权的 skill 仍然是 `private`。

## 理由

**为什么把 403 引进来，而不是继续一律 404。** ADR 0012 的规则是「地址解析不出东西就一律 404，四种情况一个答案」，它的价值在于**不确认存在性**。但在这里，存在性**已经被确认过了**——那个 skill 就在调用者刚跑出来的 `list` 里。这时候回 404 说「没有这个 skill」是一句**假话**，而假话比 403 更坏：调用方会去查地址拼错没有，而问题在别处。**403 在此不泄露任何新东西**，这就是它成立的全部条件——也是为什么这条不能推广回读路径。

**为什么 `editor` 不能上线。** 上线改变的是**每一个**读者拿到什么，ADR 0031 正是为此把它放在浏览器面上、由人点。editor 若能上线，owner 只会在事后发现自己的 skill 被换掉了——那是「协作」与「接管」的区别。

**为什么授权不可传染。** 一个 editor 若能再授权，一条授权就会自己扩散，而扩散链上的每一环都不在 owner 的视野里。授权是 owner 的决定。

**为什么不做成命名空间成员。** 「谁能进我的命名空间」与「谁能读这一个 skill」是两个问题。后者今天就能做完；前者要邀请、要角色管理、要一个全新的外发动作。

**为什么不新造权限词。** `namespace_member.role` 的列注释已经写着 `owner | editor | viewer`，只是 v1 只写 `owner`。同一个概念两套词，是把将来的合并变成一次迁移。

**为什么必须连写路径一起改（不是加固，是不改就漏）。** `readableNamespaceOf` 今天被 **10 处**调用（分布在 **6 个用例**），其中三处是**写**——上线（`PublishSkillVersionUseCase`）、丢弃（`DiscardSkillVersionUseCase`）、软删（`SoftDeleteSkillUseCase`）。它们用 readable，**只因为 v1 里 readable ≡ owned**；`NamespaceService` 的类文档已经写下了这件事：「When sharing and public discovery arrive this is what changes」。共享一旦让 `readableNamespaceOf` 开始返回「我能读的」，**能读就能删**。

## 后果

- **判据当场劈成两个。** 那 10 处按读 / 写分成两组：读走析取，写走「拥有 ∨ editor」。
- **单条读的解析顺序要改。** 今天是「先解析命名空间 → 再找 skill」；有授权之后必须变成「先找到 skill → 再判 `可读 ∨ 已授权`」。这个换序很容易把 ADR 0012 要求**一模一样**的那四种 404 拆成两种答案。
- **作者面要接受 editor 授权的 skill**：`ListAuthoredSkillsUseCase` 从「我的命名空间」变成「我的命名空间 ∪ 我能写的」；`ReadAuthoredSkillUseCase` 走写判据。**`viewer` 不进作者面**——只读的人走消费面，看不到草稿。
- **创建 skill 的接口不变。** `POST /api/v1/skills` 仍然**没有**目标命名空间参数（那是一条否定决定，见 TD §4.3）。editor 给别人的 skill 加版本走的是**另一条已经在设计表里的路**——`POST /api/v1/skills/{namespace}/{name}/versions`。于是「跨命名空间写原语」没有出现：**创建**仍然只能在自己的命名空间里，**加版本**是对一条**被授权写的东西**的写。
- **CLI 两侧都动**：新增 `list` 与 `share`；撤掉 `describe` 与 `show`（见 TD §4.6）；`submit` 加一个可选的 `--to <ns>/<name>`，走上面那条 `/versions` 端点。
- **一个命名空间集合仍不完整。** `list` 的默认范围是「我的命名空间 ∪ 授权给我的 skill」，而**命名空间成员关系仍未生效**，所以「用户有多个命名空间」这件事今天依然不可达。`SearchSkillsUseCase` 里那行 `personalNamespaceOf` 是将来改成一组 id 的地方，本决定不动它。
- **没有做到的**：公共发现、组织 / 成员 / 角色。仍然在 v2，[`prd.md`](../versions/v1-hosting/prd.md) §范围 不做 #7 保留后半句。

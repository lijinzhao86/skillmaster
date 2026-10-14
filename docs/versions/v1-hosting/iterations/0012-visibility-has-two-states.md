# 0012 · 可见性收成两档（`private` / `public`），`unlisted` 不要了

> **日期**：2026-10-10
> **所属版本**：[`../README.md`](../README.md)
> **类型**：小版本迭代——**未改变对外可验收的能力边界**

## 改了什么

- **新增** [`../../../decisions/0037-visibility-has-two-states.md`](../../../decisions/0037-visibility-has-two-states.md)（跨版本：这个产品里「公开」是什么意思），并在 `decisions/README.md` 的表里登记一行。
- `technical-design.md`：
  - **§3.2** —— `namespace` 的 DDL 注释（`-- public | unlisted | private` → `-- private | public`），以及那一节里「`visibility` 在 v1 的用法」那段说明（原文：「`public` / `unlisted` 保留给 v2」）。
  - **§4.2** —— 可见性过滤那句（原文：「`private`/`unlisted` 的 skill，无权者得到 `404`」）。
  - **第 6 章** —— Python 基线表里 `permissions.py` 那行的值域（原文：「只做**可见性**（public/unlisted/private）」）。
- `prd.md` §范围 不做 #7 下面那条注、`README.md` §候选方向：「公共发现」括注里的 `public` / `unlisted` 收成 `public`。
- `docs/target/README.md` 差异清单第 10 条：`unlisted` 从「没做」改记为「不要了」，并写明两条缺口的性质不同（`org` 是没做，`unlisted` 是不要）。
- `design/README.md` 「已知的取舍」里那条 🔴：从「与 schema 的冲突」改写成「已决、等落地」，并指向 ADR 0037。
- **本次没有改**：迁移文件里那两处列注释（`V1__baseline.sql` §3.2 / §3.3）与 `V2__seed_owner_and_namespaces.sql` 里那一句、`docs/target/design.md` 的值表与 S11。

## 为什么

触发它的是设计控制台演示件的权限页（2026-10-09）。那一页按 [ADR 0034](../../../decisions/0034-skill-level-sharing.md) §10 把「可见性」与「被授权的人」摆成两节，而可见性一节要摆几档，取决于「公开是一件事还是两件事」。

当时的口径（用户原话，2026-10-09）：**「只要公开了就能被搜索到。只是公开是只能调用，还是说用户可以看到整个文件夹」**——也就是说，真正要分的是**权限**，`unlisted`（不可搜到但拿到链接可访问）不是一个要保留的状态。2026-10-10 确认这条覆盖 schema 里的两列，`namespace.visibility` 一起收。

被否掉的两个做法（把「搜得到」与「进得去」当两个轴、以及「`public` 但可选不进目录」）为什么不成立，写在 [ADR 0037](../../../decisions/0037-visibility-has-two-states.md) §理由，这里不重述。

## 影响

- **影响哪些已交付行为**：无。v1 不写这一列、也没有接口读它——三个值收成两个不改任何一条已交付行为，也不会让任何测试失败。这一点本身就是这条记录的理由：没有测试会替我们记住它。
- **需要同步改哪些文档**：上面「改了什么」里列的都改了。**待改**：迁移里那三处复述（要等下一次真正动 `namespace` / `skill` 两张表的迁移，回改已应用的迁移会破坏 Flyway 校验和）、`docs/target/design.md` 的可见性值表与 S11（要改 S11 得先回答那个场景改用 `skill_grant` 之后长什么样）。
- **是否需要新开 ADR**：需要，见 [ADR 0037](../../../decisions/0037-visibility-has-two-states.md)（跨版本：「公开」在这个产品里是什么意思，以及「有链接就能进」为什么不作为一个状态存在）。

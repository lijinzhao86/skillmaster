# v5-skill-management · 谁能对这个 skill 做什么

> **最后更新**：2026-10-20
> **状态**：已实现并合入 `main`（PR [#12](https://github.com/lijinzhao86/skillmaster/pull/12)，服务端 P0 / 网页 P1 / CLI P2）；**验收已执行（2026-10-20），自动化全过**——服务端 IT、真 CLI 子进程矩阵与网页用例的数量与证据见 [`test-plan.md`](test-plan.md) §结果。**视觉验收待人看**。
> **含收费**：否

<!--
README 是「版本记录主页」，不是详述。每个问题只写一句话 + 链接。
细节一律写在 prd.md / technical-design.md / test-plan.md 里。
这不是风格偏好——复述必然漂移。
-->

## 目标

把「谁能对**这一个** skill 做什么」从两档扩成设计件已经画好的一条梯子——**六行（五档可授，加上不授的所有者）**，并补上今天完全缺失的两件事：**删除一个 skill（还能找回来）**，以及**够得到它的人可以申请一档、有权的人批准**。

**这一版的边界是「权限 + 生命周期」**：谁能对它做什么，以及它什么时候不再存在。**元数据**（它对外说自己是什么）整块留给下一版——见 §这一版明确不做什么。

细节见 [`prd.md`](prd.md) §用户与场景。

## 验收标准

一句话：**同一个 skill、同一个动作，换一个档位的人来做，结果就该不一样——而每一档差在哪，与设计件逐条对得上。**

完整十条与判定方式见 [`prd.md`](prd.md) §验收与指标（映射见 [`test-plan.md`](test-plan.md) §验收映射）。

**成立条件是第 1 条**（五档的能力逐条对得上）。这一版最容易做成的事是「把两个角色换成五个名字」——那样做出来的东西读起来对、一条能力都不对。真正的验收是**负向的那一半**：`调用` 档拿不到文件清单、`管理` 档删不掉 skill。

## 这一版明确不做什么

摘要四条最要紧的；**完整范围与每条的理由见 [`prd.md`](prd.md) §范围**。

- 不做**元数据**（改描述；`name` / `title` / `description` 三个字段各自的地位）—— **整块留给元数据那一版（尚未开始）**。它与权限没有共同依赖，而半做会造出一个「作者编辑的那一列与读者看到的那一列不是同一列」的状态（[prd §不做 #8](prd.md)）
- 不做**组织 / 命名空间成员**（把一个人加进命名空间、一次授给他名下全部 skill）—— 那要一整套组织模型，与 skill 级授权没有共同依赖（[ADR 0034](../../decisions/0034-skill-level-sharing.md)）
- 不做**改名** —— 改名就是换地址，要先回答「旧地址 404 还是重定向」，而 [ADR 0012](../../decisions/0012-addressing-and-version-pinning.md) 说没有重定向这个概念；它是一条独立的 ADR
- 不做**版本废弃 / 版本删除 / 版本历史接口 / changelog** —— 四件都没有需求驱动，也都没有设计件

**还有一条看着像"少做"其实是结构性的**：API key 的档位**不动**——scope 回答「这把凭据能做哪一类动作」，梯子回答「这个人在这一个 skill 上是什么」，一把 key 代表一个人，而那个人在不同 skill 上档位不同。

## 与目标态的关系

[`target/`](../../target/README.md) 描述的是**完整形态**（公开市场 + 渐进付费，含组织与权益模型）。

- **保留的部分**：交付形态（网关 skill + API + 自建鉴权）与 v1 起的那套托管模型，本版一个字不改。
- **已经分叉的部分**：目标态的权限是「个人 / 组织 / 公开」三层再叠权益与钱包；本版是**一个用户对一个 skill 的一条梯子**，与组织模型无关——这一处分叉从 v1 起就在，差异记在 [`target/README.md`](../../target/README.md) 的差异清单里（第 9/10 条那一带）。
- **本版新增的一处**：可见性的 `public` **第一次有了写入路径**，于是公开的 skill 真的会被别人搜到（[ADR 0037](../../decisions/0037-visibility-has-two-states.md) §决定 2 给 `public` 的定义）。目标态里的「公共发现」因此走近了半步——**只走近了读的那一半**，发现页、排序与推荐仍然不做。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、验收与指标、依赖与前置 |
| [`technical-design.md`](technical-design.md) | 架构、数据模型、接口、分期、开放问题。**含 §分期 末尾「设计稿：已经动过的地方」**——2026-10-17 已全部画完，P1 已照它搬（验收见 [`test-plan.md`](test-plan.md) §结果） |
| [`test-plan.md`](test-plan.md) | 验收映射、用例（T1–T16）、环境与数据、结果、已知问题 |
| `iterations/` | 小版本迭代记录（只增不改）；本版尚无迭代记录，目录未建 |

**本版实现六条 ADR**（跨版本存活，不在本文件夹里）：[0047 权限是一条梯子](../../decisions/0047-permissions-are-a-ladder.md)、[0048 公开是把某一档交给所有人](../../decisions/0048-visibility-hands-out-a-rung.md)、[0050 删除可以撤销而名字不释放](../../decisions/0050-deleting-a-skill-can-be-undone.md)、[0051 作者面按档位分](../../decisions/0051-the-author-plane-serves-by-tier.md)、[0052 申请不是授权](../../decisions/0052-a-request-is-not-a-grant.md)、[0053 skill 元数据是它自己的模块](../../decisions/0053-skill-metadata-is-its-own-module.md)。

**其中两条修订了更早的 ADR**（被修订的那两条正文不改，改动记在新 ADR 的抬头）：[0047](../../decisions/0047-permissions-are-a-ladder.md) 修订 [0034](../../decisions/0034-skill-level-sharing.md) §决定 3 / §决定 5 / §决定 6；[0051](../../decisions/0051-the-author-plane-serves-by-tier.md) 修订 [0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md) §决定 8，并取代 [0034](../../decisions/0034-skill-level-sharing.md) §后果 里的一行。

**还有一条不属于本版**：[0049 作者改过的那句描述压着版本](../../decisions/0049-an-edited-description-outranks-the-version.md) 是**本版讨论时定下的**，但它属于元数据那一版（[prd §不做 #8](prd.md)）。它落在 `decisions/` 里是对的——决策跨版本存活，而[它修订的那条](../../decisions/0031-submitting-and-publishing-are-two-actions.md)在哪个版本被实现与它记在哪里无关。

## 影响最大的未决问题

完整列表见 [`technical-design.md`](technical-design.md) §开放问题。最要紧的一条：

1. **`namespace_member.role` 的词表与 `skill_grant` 从本版起不再共享。** [ADR 0034](../../decisions/0034-skill-level-sharing.md) 当初特意借 `viewer` / `editor` 那两个词，理由是「同一个概念两套词，是把将来的合并变成一次迁移」——那条理由随本版换词而失效。将来做成员体系时，要么把两处统一、要么明确它们本来就是两套，而这一次要**先回答**而不是先写。

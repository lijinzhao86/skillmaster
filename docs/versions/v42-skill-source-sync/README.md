# v42-skill-source-sync · 仓库里的 skillmaster 技能正文与本机全局版对齐

> **最后更新**：2026-11-02
> **状态**：进行中（实现已落地，本地校验通过；PR 待合并）
> **来源**：用户 2026-11-02 的指令——把 `~/.claude/skills/skillmaster` 复制进 `skills/skillmaster/` 并发布到线上；同日决定不引入 `sop/00-model.md`，也不在本版做 `invoke` / `read` 的纠错。
> **含收费**：否

## 目标

仓库发布出去的那一份 [`skills/skillmaster/SKILL.md`](../../../skills/skillmaster/SKILL.md) 与用户本机正在使用的全局版一致。只换正文；`sop/` 下保留仓库已有的 03 / 05 / 07。细节见 [`prd.md`](prd.md)。

## 验收标准

五条（A1–A5），摘要：与全局版逐字一致（只差两处表格编辑）；技能包仍是 8 个文件；frontmatter 的 `name: skillmaster` 仍在；`SKILL.md` 的 `sop/*.md` 链接双向闭合；`check_docs.py` 退出 0。完整验收见 [`prd.md`](prd.md) §验收与指标。

**成立条件**：A2 与 A4 不满足就不算交付——包形状错了，安装文档的「数够 8 个文件」会让每一次安装失败；链接悬空，等于技能包里有一页读不到。

## 这一版明确不做什么

- 不引入 `sop/00-model.md` — 全局版把模型同时写在 `SKILL.md` 与 `00-model.md` 两处；仓库选择把模型留在 `sop/05-manage.md`，加进来会把包变成 9 个文件。
- 不采用全局的 `sop/03-edit-draft.md` 与 `sop/05-manage.md` — 前者没有二进制文件提示、后者指向不存在的 `00-model.md`；仓库版本覆盖 v39 的能力。
- 不在正文补 `invoke` / `read` 的分工，也不做 CLI 拦截 — 用户 2026-11-02 决定先记入 [`TODO`](../../current/TODO.md)。
- 不改服务端、控制台、CLI 与设计稿 — 本版只改技能包里的一个 Markdown 文件。

## 与目标态的关系

[`target/`](../../target/) 描述完整形态。本版不改任何对外能力边界，只让已发布技能包的正文与作者本机版本一致；不新增能力，也不与 `target/` 分叉。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、控制台与 CLI 对应、验收与指标、依赖与前置 |
| [`technical-design.md`](technical-design.md) | 架构、数据模型、接口、分期、开放问题 |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、环境与数据、结果 |

## 影响最大的未决问题

完整列表见 [`technical-design.md`](technical-design.md) §开放问题。按重要性排前两条：

1. **`sop/00-model.md` 要不要回归**——加回来就是 9 个文件，安装文档与 `site.yml` 三处闸都要一起改；本版不动。
2. **`invoke` / `read` 的纠错落在哪**——正文、CLI 提示、宿主 hook 三选一；已记入 [`TODO`](../../current/TODO.md)，不在本版回答。

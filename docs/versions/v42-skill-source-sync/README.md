# v42-skill-source-sync · 仓库里的 skillmaster 技能与本机全局版对齐

> **最后更新**：2026-11-02
> **状态**：进行中（实现已落地，本地校验通过；PR 待合并）
> **来源**：用户 2026-11-02 的指令——把 `~/.claude/skills/skillmaster` 复制进 `skills/skillmaster/` 并发布到线上；同日决定把 `sop/00-model.md` 一并放回去，包从 8 个文件变成 9 个。
> **含收费**：否

## 目标

仓库发布出去的技能包与用户本机全局版对齐：[`SKILL.md`](../../../skills/skillmaster/SKILL.md)、[`sop/00-model.md`](../../../skills/skillmaster/sop/00-model.md)、[`sop/05-manage.md`](../../../skills/skillmaster/sop/05-manage.md) 换成全局版；保留仓库已有的 `sop/03` 二进制提示与 `sop/07`。包从 8 个文件变成 9 个，安装文档与 CI 闸同步。细节见 [`prd.md`](prd.md)。

## 验收标准

七条（A1–A7），摘要：三个文件与全局版一致；包是 9 个文件；frontmatter 的 `name: skillmaster` 仍在；`SKILL.md` 的 `sop/*.md` 链接双向闭合；文档检查通过；没有残留的 8 文件断言；线上字节与构建产物一致（部署时）。完整验收见 [`prd.md`](prd.md) §验收与指标。

**成立条件**：A2 与 A6 不满足就不算交付——文件数错了，安装说明的「数够 9 个文件」会让每一次安装失败；残留的 8 会让安装说明与真实包对不上。

## 这一版明确不做什么

- 不采用全局的 `sop/03-edit-draft.md` — 它缺二进制文件的提示；仓库版本保留，并指向 `07-files.md`。
- 不删除 `sop/07-files.md` — 它是 v39 的二进制上传 / 读取说明。
- 不在正文补 `invoke` / `read` 的分工，也不做 CLI 拦截 — 用户 2026-11-02 决定先记入 [`TODO`](../../current/TODO.md)。
- 不改服务端、控制台、CLI 行为与设计稿 — 本版只改技能包、安装文档与 CI 闸。

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

1. **`invoke` / `read` 的纠错落在哪**——正文、CLI 提示、宿主 hook 三选一；已记入 [`TODO`](../../current/TODO.md)，不在本版回答。
2. **包形状以后是否稳定在 9 个文件**——每加一个 SOP 都要同步安装文档与 `site.yml` 的三处闸；这是当前计数口径的固定代价。

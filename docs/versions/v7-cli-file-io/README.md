# v7-cli-file-io · 草稿可以像本地目录一样被读写

> **最后更新**：2026-10-20
> **状态**：进行中（**PRD 与设计稿已锁**；技术设计已写、**待评审**；技术约束已锁——整版字节不出服务端、查询/写入性能、`edit` 对齐 Claude Code、命令分层可扩展，见 [`technical-design.md`](technical-design.md) 头部；测试方案已写、**待执行**；尚未实现）
> **含收费**：否

<!--
README 是「版本记录主页」，不是详述。每个问题只写一句话 + 链接。
细节一律写在 prd.md / technical-design.md / test-plan.md 里。
这不是风格偏好——复述必然漂移。
-->

## 目标

让 agent 在 CLI 里用 Linux 的命令读写**一份已有草稿**：`ls` / `cat` / `grep` / `wc` 读，`write` / `edit` / `rm` / `mv` / `cp` 写；新草稿的地址是 8 位短码 `@<短码>`（旧行仍是 ULID，两种都接受；地址里不写状态），网页上（草稿页与版本列表行）可复制，CLI 用 `version list` 搜到；上线仍由人在浏览器确认。细节见 [`prd.md`](prd.md) §用户与场景。

## 验收标准

一句话：**同一套命令在本地目录与服务端草稿上给出同样的结果——`cat` 的字节、`wc` 的计数、`ls` 的清单与 `grep -F` 的命中一致；退出码与管道行为照 Linux；正则不承诺与本地逐字相同；写只落在草稿上，权限与并发都不静默（读与写是两把独立的尺：`invoke` / `browse` 带 `skills:write` 也写不了，`write` 缺 `skills:write` 也写不了）；调用档对用户 skill 拿不到文件列表或文件内容检索（消费面版本列表仍可读；作者面 `version list` / `version show` 403）。**

完整清单与判定方式见 [`prd.md`](prd.md) §验收与指标（映射见 [`test-plan.md`](test-plan.md) §验收映射）。

**成立条件**：验收 #1（写只认草稿）、#14（权限）与 #31（调用档不暴露结构）——不满足就不算交付。

## 这一版明确不做什么

摘要三条最要紧的；**完整范围与每条的理由在 [`prd.md`](prd.md) §范围**。

- 不做**网页端编辑器** —— 本期交付 CLI 的读写；网页只改版本页与版本列表行的地址/版本码/复制，文件栏仍只读
- 不做**新建草稿 / 上传 zip / 网页「新建版本」** —— 本期只改已有草稿
- 不做**上线与生命周期动作** —— 改变调用者所读的内容只有浏览器面（[ADR 0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md)、[ADR 0061](../../decisions/0061-what-a-credential-may-do-to-versions.md)）

## 与目标态的关系

[`target/`](../../target/README.md) 描述的是完整形态。

- **保留的部分**：服务端权威、内容寻址、提交与上线分开——一个字不改。
- **走近的半步**：本地编辑的那套命令第一次能用在服务端草稿上；「改一个错字就要发一个新版本」结束。
- **仍然分叉的部分**：没有工作区、没有本地副本、没有 undo；这三件事是目标态里没有、本版也不引入的。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、验收与指标、依赖与前置 |
| [`technical-design.md`](technical-design.md) | 架构、数据模型、接口、分期、开放问题（**待评审**） |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、结果、已知问题（**已写、待执行**） |
| `iterations/` | 小版本迭代记录（只增不改）；本版尚未实现，目录不存在 |

**本版涉及的 ADR**：[0062](../../decisions/0062-grep-is-a-scoped-scan-not-an-index.md)（grep 是有界扫描）、[0063](../../decisions/0063-a-draft-is-addressed-by-a-short-code.md)（草稿的地址是行 id：新行 8 位短码，旧行 ULID）；执行的是 [0001](../../decisions/0001-server-authoritative.md)、[0035](../../decisions/0035-the-pin-lives-on-the-client-machine.md)（本地不落副本）、[0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md)、[0055](../../decisions/0055-drafts-have-a-required-name.md)、[0061](../../decisions/0061-what-a-credential-may-do-to-versions.md)、[0047](../../decisions/0047-permissions-are-a-ladder.md)、[0051](../../decisions/0051-the-author-plane-serves-by-tier.md)。**还欠两条新 ADR**：草稿行可变（上线后仍不可变）；GC 用 grace period、写路径不拿 `version_file` 表锁（见 [`technical-design.md`](technical-design.md) §blob 回收）。

## 已定的关键口径（2026-10-20）

完整列表见 [`technical-design.md`](technical-design.md) §开放问题（分「已定」与「仍开放」）。四条产品口径已定：

1. **写后 digest 撞同 skill 另一版**——409 `content_already_exists`，detail 指出已有那一版；代价是「把草稿退回成某一版的原文」被拒绝。写相同内容（digest 未变）是 no-op。
2. **长期不上线的草稿的 blob 回收**——本版接受孤儿 blob；写路径不加锁、不 sweep，**下一次 submit / 软删除**（不是上线 / 丢弃）触发 sweep，带 10 分钟 grace。
3. **写 `SKILL.md` 的 frontmatter**——更新该版本的快照（`title` / `description` / `frontmatter`），skill 行不动，上线时按 ADR 0049 处理。
4. **网页旧 `?version=draft:<ULID>`**——不兼容（设计稿只认裸行 id）；旧 v6 书签落「没有这一版」。要兼容先改设计稿。CLI 的 `@draft:<ULID>` 输入兼容不受影响。

并发用 `digest` 做 `If-Match` 已由 TD 定为「行锁 + 摘要比较」的 CAS，不再需要修订号。

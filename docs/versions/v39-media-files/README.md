# v39-media-files · 媒体文件就是普通文件

> **最后更新**：2026-11-02
> **状态**：进行中（P0–P2 已实现，测试未执行；P3 的整包流式化、CLI `download` 大包与回收清扫器未做）
> **含收费**：否

## 目标

让 Word / PDF / 图片 / 音视频等任意文件像 `SKILL.md` 一样进版本、能上传、能远程读写、能预览；帮助与技能里写清「文本用 `write`、二进制用 `put` / `get`」。细节见 [`prd.md`](prd.md)。

## 验收标准

任意类型、单文件 100 MiB / 每版本 512 MiB 以内的文件，都能从控制台或 CLI 上传到一个草稿，按版本冻结、被 agent 读出、在控制台看见（图片与 PDF 内嵌，其余下载）。**整包导入在 P1/P2 另受 128 MiB 的临时上限**（压缩包本身与解压后都算；P3 解除；更大的包逐个文件上传）。完整验收见 [`prd.md`](prd.md) §验收与指标。

**成立条件**：第 1 条「任意类型文件可上传并逐字节取回」不满足就不算交付——本版的目的就是让媒体文件和文本文件没有区别。

## 这一版明确不做什么

摘要三条最要紧的；**完整范围与每条的理由在 [`prd.md`](prd.md) §范围**。

- 不抽文本、不做 OCR —— 平台只当字节文件，解析交给调用方
- 不做 Office 在线预览、不内嵌播放音视频 —— 这两类只给下载
- 不做断点续传、分片上传 —— 100 MiB 以内整包上传，失败重传整包

## 与目标态的关系

[`target/`](../../target/) 描述的是完整形态。这一版是它的哪个子集、在哪些地方已经分叉：

- 保留的部分：「文件清单 + 二进制存储 + 按版本冻结」的模型原样保留，媒体没有另立一套身份。
- 已经分叉的部分：`target/design.md` 没有规定大字节的物理位置；本版按 [ADR 0130](../../decisions/0130-large-blobs-live-in-object-storage.md) 把大于 1 MiB 的字节分流到 OSS，小文件仍留在 PostgreSQL。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、控制台与 CLI 对应、验收与指标、依赖与前置 |
| [`technical-design.md`](technical-design.md) | 架构、数据模型、接口、分期、开放问题 |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、结果、已知问题 |
| [`../../decisions/0130-large-blobs-live-in-object-storage.md`](../../decisions/0130-large-blobs-live-in-object-storage.md) | 大字节为什么分流到 OSS，小文件为什么留下 |
| [`../../decisions/0131-any-file-type-is-accepted-but-only-safe-types-render.md`](../../decisions/0131-any-file-type-is-accepted-but-only-safe-types-render.md) | 为什么全收文件、却只让位图与 PDF 内联 |
| [`../../decisions/0132-media-files-are-version-files.md`](../../decisions/0132-media-files-are-version-files.md) | 为什么媒体不另立素材库 |
| [`../../decisions/0133-wc-streams-and-is-not-text-capped.md`](../../decisions/0133-wc-streams-and-is-not-text-capped.md) | 为什么 `wc` 流式计数、不受 16 MiB 文本上限 |

## 影响最大的未决问题

完整列表见 [`technical-design.md`](technical-design.md) §开放问题。按重要性排前三条：

1. **Q3 整包导入何时改成流式**——P1/P2 在 128 MiB 处明确拒绝（压缩包本身与解压后各一道）；P3 流式化后解除。
2. **Q1 OSS 凭据**——ECS RAM 角色优先，没有角色时退回最小权限 AK；不把 AK 写进仓库。
3. **Q7 100 MiB 是压缩前还是解压后**——解压后（包内单条目），与 `version_file.size` 一致。

**已经定下的**：上传的流式化落在「服务端临时文件 + 边算 sha256」（P1 已实现）；桶是新建的私有桶，与 CLI 下载桶分开（TD §OSS 对象）；PDF 预览直连同一个会话 URL（Q4）；`get` 默认拒绝覆盖本地文件（Q5，PRD 做 #7 已定）。

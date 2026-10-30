# v34-file-toolbox · 让在线编辑像本地一样能看、能搜、能读一段

> **最后更新**：2026-10-30
> **状态**：进行中（已实现；本机测试见 [`test-plan.md`](test-plan.md) §结果，服务端 grep 两个用例、清单摘要与 capabilities 的 IT 断言在有库时补跑）
> **含收费**：否

<!--
README 是「版本记录主页」，不是详述。每个问题只写一句话 + 链接。
-->

## 目标

补齐 agent 在终端里浏览、搜索、读一段文件所需的那组命令，并让 `skill files` 退役到 `skill ls`：命令名与语义跟 Linux 一致，agent 不需要另学一套。另加 `edit --regex` 这一处写侧的增量。顺带把 `skill invoke` 头部的能力清单改成能落到命令上的写法（[ADR 0119](../../decisions/0119-permissions-carry-commands.md)）；改名的依据见 [ADR 0120](../../decisions/0120-file-enumeration-is-ls.md)。细节见 [`prd.md`](prd.md)。

## 验收标准

A1–A9 与 A11 是新增与改动（命令面与 invoke 头部），A10 是既有契约不变。完整验收见 [`prd.md`](prd.md) §验收与指标。

**成立条件**：A6（`read --raw`）与 A7（`grep` 带上下文）——这两条不满足，本版不算交付：前者是「按版本钉住、可分段的原样读」，后者是「改一处之前先看见周围」的唯一通道（整份原文还有 `skill cat`）。

## 这一版明确不做什么

摘要三条最要紧的；**完整范围与每条的理由在 [`prd.md`](prd.md) §范围**。

| 不做 | 原因 |
|---|---|
| `sed` / `awk` | 是语言不是命令；`s///` 的能力由 `edit --regex` 提供 |
| 目录（`mkdir`、目录级 `mv/rm/cp`、`-r/-f`） | 要动数据模型，单独一版 |
| `patch` | 格式合规本身是一个独立失败面（v32 已否） |

## 与目标态的关系

[`target/`](../../target/) 描述完整形态。这一版补的是「agent 在服务端浏览与读取的粒度」，不改变版本与草稿模型。

- 保留的部分：服务端权威、草稿身份（ADR 0063）、`If-Match` 基线、改一处文本只走 `edit`（`write` 是整份覆盖）。
- 已经分叉的部分：无新增分叉。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、验收与指标、依赖与前置 |
| [`technical-design.md`](technical-design.md) | 架构、数据模型、接口、分期、开放问题 |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、结果、已知问题 |

关键决策：[0118](../../decisions/0118-computation-follows-the-data.md)（计算跟着数据走）、[0119](../../decisions/0119-permissions-carry-commands.md)（invoke 头部带命令名）、[0120](../../decisions/0120-file-enumeration-is-ls.md)（`files` → `ls`）。

## 影响最大的未决问题

完整列表见 [`technical-design.md`](technical-design.md) §开放问题。按重要性排前三条：

1. **`ls -R` 要不要保留目录行**——保留是结构完整，去掉是脚本友好。
2. **`grep` 的 `-e` 多模式在服务端怎么编译**——逐个编译还是拼成一个。
3. **`head` / `tail` 与 `read --raw --limit` 长期是否该合并**。

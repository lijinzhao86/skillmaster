# v37-cli-page-actions · 产品需求

> **最后更新**：2026-11-01
> **状态**：已锁定（2026-11-01）
> **回写**：2026-11-01 产品负责人分三次加范围——① 批量上线新增 `skills publish`；② 从未上线的 skill 允许 owner 用 `skill delete --yes` 删除，其它档位返回没有权限；③ 页面命令先预检档位，`skill open` / `version open` 一并纳入，`skills pending` 的提示不再承诺档位，同意屏 `skills:write` 文案随之更新。当天又定两条帮助规则：④ 确认开关不进帮助（`skill delete` 与既有的 `version discard`），完整命令只由命令打印；⑤ 每个页面动作的帮助写明动作在网页上由人完成。此前已写的内容不变。
> **所属版本**：[`README.md`](README.md)

## 用户与场景

**谁在用**：用 agent 在本地干活的人（agent 手里是一把 `skmr` 凭据），以及直接敲 `skmr` 的人。

**在什么场景下用**：

1. 手上的草稿要上线、线上要回滚、某版要下架或恢复读取；整个 skill 要下线、要删、要改可见性——这些动作在控制台都能做，命令行里却走不通。
2. **误传了一批 skill 还没上线**，要清掉。今天只能一个个打开网页点，或者带着它们留在库里。
3. 一个档位不够的人敲了页面命令，**先被带到一页自己动不了的地方**，再发现没权限。

**现在的替代做法是什么**：读拒绝句里那条 `skmr version open … --sub actions`，或自己拼 `/skills/<ns>/<name>?tab=settings`；批量清理则完全回到浏览器。拼得出来的人不需要这一版；拼不出来的人会以为 CLI 不支持。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | 四个版本动作改为「打开那一页」：`version publish\|rollback\|revoke\|restore` | CLI |
| 2 | `skill` 组新增 `delete`、`offline`、`online`、`visibility`；`skills` 组新增 `publish`（批量上线，打开「待上线」页） | CLI |
| 3 | 移除 `version delete\|offline\|online`（skill 级动作挂在版本组下，原名退役） | CLI |
| 4 | **从未上线过的 skill 允许 owner 用 `skill delete --yes` 真删除**；其它档位返回没有权限。**上线过的 skill 在网页上照旧可以删，只是 CLI 不做** | CLI + 服务端 |
| 5 | **页面动作先预检档位**，不够就不打开、直接说清「你的档位 / 需要的档位」；判定不了时照常打开并说明 | CLI + 服务端三个字段 |
| 6 | `skill open` / `version open` 纳入同一套预检 | CLI |
| 7 | `skills pending` 结尾的提示不再承诺「你去点上线」 | CLI |
| 8 | 同意屏 `skills:write` 那行补上「从未上线过的可以删」 | design + 控制台 |
| 9 | 帮助、README、版本文档同步 | CLI + docs |

**服务端的改动有三处**：作者页多三个字段（`owned`、`offline`、`ever_published`——预检要判「是不是所有者」「有没有下线」「有没有上线过」）；一条只允许「owner + 从未发布过任何版本」的删除入口；以及发布侧的并发保护（`recordPublish` 不写已删除的行，发布输掉行锁就回滚）。

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 让 **CLI** 删掉**上线过**的 skill | 它改变调用者读到什么，仍然只在网页上由人确认（[ADR 0046](../../decisions/0046-deleting-a-skill-happens-in-the-browser.md)）；控制台那条路不变 | 不做 |
| 2 | 让 CLI 真正执行上线 / 回滚 / 下架 / 重新上架 / 下线 / 恢复服务 / 改可见性 | 同上（[ADR 0061](../../decisions/0061-what-a-credential-may-do-to-versions.md) §决定 1） | 不做 |
| 3 | 旧名 `version delete\|offline\|online` 留别名 | 产品负责人 2026-11-01 确认：未正式发布，可以直改 | 不做 |
| 4 | 可见性命令带目标值（`skill visibility <地址> public`） | 网页设置页没有「按参数预选」；收下参数却不生效比不收更误导 | 网页支持预选时 |
| 5 | `skills publish` 做逐条预检 | 它没有单一地址，每条草稿的档位都可能不同；页面只列出发布得了的 | 不做 |
| 6 | 审批访问申请（`approve` / `deny`） | 上一轮盘点里的 C 组，需要单独确认 | 未定 |
| 7 | API key、登录设备、注册与找回密码 | 账号面，历史上一直只在控制台；[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 的例外是否覆盖它们需要另定 | 未定 |

## 控制台操作与 CLI 对应

本版覆盖控制台的九个网页专属动作，以及一条受限的执行（[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md)）。**每一行都有命令。**

| # | 控制台操作 | CLI 命令 | 权限与行为 |
|---|---|---|---|
| 1 | 上线一版（可预选 patch / minor / major） | `skmr version publish <地址> [--bump …]` | 要 `maintain`；够则打开「操作」，不够则说明并给地址 |
| 2 | 回滚 | `skmr version rollback <地址>` | 同上 |
| 3 | 下架一版 | `skmr version revoke <地址>` | 同上 |
| 4 | 重新上架（恢复读取） | `skmr version restore <地址>` | 同上 |
| 5 | 下线 skill | `skmr skill offline <地址>` | 要 owner；够则打开设置页，不够则说明并给地址 |
| 6 | 恢复服务 | `skmr skill online <地址>` | 同上 |
| 7 | 删除 skill（**上线过的**） | `skmr skill delete <地址>` | 要 owner；CLI 不删，给出设置页地址（网页上照旧可删） |
| 8 | 删除 skill（**从未上线过**） | `skmr skill delete <地址> --yes` | 要 owner，服务端原子判定「从未发布过任何版本」；成功即删除 |
| 9 | 改可见性 | `skmr skill visibility <地址>` | 要 `admin`；同上打开设置页 |
| 10 | 批量上线（「待上线」页的多选） | `skmr skills publish` | 不预检；打开 `/publish` |

**名字不释放**：删除是软删，同名再建会得到 `skill_deleted`，要先 `skmr skill restore`。

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| 1 | **七个带地址的页面动作**（四个版本动作 + `skill offline/online/visibility`）：档位够 → 打开页面、退出 0、首句「还没执行」；档位不够 → **不打开**、说清「你的档位 / 需要的档位」并给出地址、退出 1；判定不了（无凭据 / 网络失败 / 看不到）→ 照常打开并写明没能预检。`skills publish` 直接打开 `/publish`、不预检 | 假服务端逐档位跑；假打开函数记录是否被调用 |
| 2 | `skill delete` 不带 `--yes`：不发任何写请求、打印后果与完整命令、退出非 0；看得到但不是 owner 时只说「要所有者」；判定不了时**只说「没能确认」**并给设置页地址，不说「它从未上线」，也不给确认命令。**帮助里不出现确认开关**，它只在预检确认可删后由命令打印 | 单测断言请求计数为 0、措辞，以及 `--help` 不含 `--yes` |
| 3 | `skill delete --yes`：owner + 从未上线 → 已删除；owner + 上线过 → **CLI 不删**，给出网页地址，退出 1；非 owner 但看得到 → 没有权限（403）；看不到 → 404 | 服务端 IT 覆盖四种结果；CLI 单测用假服务端覆盖同样的四种 |
| 4 | 「从未上线」判定用 `first_published_at` 的投影 `skill.ever_published_at`（`recordPublish` 写、V31 回填），**上线→下架→再删必须被拒** | 一条专门的 IT：publish → revoke → `skill delete --yes` 返回拒绝 |
| 5 | 「从未上线」与「未删除」两个条件在服务端**同一条 UPDATE 内**成立（owner 是前置读）；发布与删除并发时，发布若输掉行锁就回滚，不留下线上版 | 代码审查 + 并发用例（删除同时发布的投影写）+ V31 回填用例 |
| 6 | 七个页面动作不写任何东西；有凭据时最多两次只读（作者面一次，读不到时消费者面一次）；`skills publish` 零请求 | 假服务端计数断言 |
| 7 | `skill open` / `version open` 用同一套预检：`skill open` 按分区判档位（信息 / 版本 → 调用，文件 → 浏览，设置 → 管理），`version open` 在作者面 404 且 skill 可见时判「看不到这一版」；判定不了照常打开并说明；`skills pending` 不再断言「你去点上线」 | 逐条断言输出与是否打开 |
| 8 | `version delete\|offline\|online` 变成未知命令（退出 2），帮助不再列出 | 逐条跑并断言 |
| 9 | 同意屏文案与设计稿一致 | 设计检查 + 人工对照（Claude 看不到渲染结果） |
| 10 | 帮助、README、ADR、本版三份文档互相一致 | `check_docs.py` 通过 + 人工对照 |

**成立条件**：第 1、7 条不满足就不算交付——这一版要的正是不再把人带到做不了的页面。

### 指标

本版不设量化指标；批量清理的收益按「一次删 N 个」自证，不另立口径。

## 依赖与前置

- **决策**：[ADR 0126](../../decisions/0126-page-only-actions-open-the-page.md)（页面动作打开那一页）、[ADR 0127](../../decisions/0127-never-published-skills-may-be-deleted-by-a-credential.md)（从未上线的删除与权限预检，部分取代 [ADR 0046](../../decisions/0046-deleting-a-skill-happens-in-the-browser.md) 的决定）、[ADR 0072](../../decisions/0072-the-handoff-points-at-the-page-that-owns-the-action.md) / [ADR 0074](../../decisions/0074-a-handoff-may-carry-a-preselection.md)（落点与预选）、[ADR 0087](../../decisions/0087-cli-exit-codes-are-a-public-contract.md)（退出码）、[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md)。
- **外部依赖**：无。
- **排除了什么前置**：不需要新 scope，不需要让 CLI 持有浏览器会话；预检读的是作者面已有的读接口。

---
name: skillmaster
description: 按需取用已托管的 skill。当任务需要特定领域能力（文档处理、数据分析、
  飞书操作、代码规范等）时，先用它列出可用的 skill，再按结果取用。
metadata:
  platform_api_version: "10"
---

# 我的 skill 库

服务地址：`https://<host>`

## 使用协议（按顺序，不要跳步）

1. 列出我的库：`skillmaster skills list --no-login`
   → 通常两行：**第一行是地址本身**（整行就是待会儿要抄的），缩进的第二行是描述与
   「什么时候该用它」，看它判断相关性。描述为空时只有地址一行；取地址只依赖第一行。
   库里没有需要的，再去公域找：`skillmaster skills search --no-login "<关键词>"`。
   **公域只搜不列**：关键词、`--category`（分类）或 `--public-tag`（公开标签）至少给一个。
   末尾说「还有更多」时**先想收窄**——库里用 `--all` 一次列完，或用下面的分类 / 标签收窄；
   公域换更窄的关键词。
2. 加载：把选中那一行的**地址整段抄下来**：`skillmaster skill invoke <地址>`
   → 正文印在下面。**这一步不能跳**：它同时把「这一版」钉在这台机器上，第 3 步靠的就是它。
3. 看这一版有什么、再取需要的文件：
   `skillmaster skill files <地址>` → 列出这一版所有文件的相对路径（哪个是正文、哪个是二进制，
   它会标出来）。取其中一个：`skillmaster skill read <地址> <相对路径>`
   ——**只取正文引用到的，或任务确实要用到的那几个**。
4. 要换版本：`skillmaster skill versions <地址>` 看有哪些版本可选，再把那一行的地址整段抄进
   `skillmaster skill invoke <地址>@<版本>`。

七个读命令（`skills list` / `skills search` / `skills categories` / `skills public-tags` /
`skills my-tags` / `skill tags` / `skill show`）都带 `--no-login`：会话里没有凭据时**立即失败
并指向 `skillmaster login`**，不会弹出浏览器。`invoke` / `files` / `read` / `versions` 没有这个
旗标，缺凭据时按 v13 自动登录（没有浏览器时会失败并提示）。

## 按标签收窄

标签有三层，各管各的：**分类**是平台的固定词表（每个 skill 一个），**公开标签**是作者贴的、
所有人可见，**我的标签**是你自己的、只有你看得见。

- 先读词表：`skillmaster skills categories --no-login`（分类与公域计数）、
  `skillmaster skills public-tags --no-login`（公开标签与公域计数）。
- 再读 `skillmaster skills my-tags --no-login` 里每个标签的**说明**——那一句就是「什么时候该贴它」，
  用它判断该不该按这个标签找。
- 然后收窄：`skills list --no-login --category <分类> --my-tag <标签> --public-tag <标签>`；
  公域检索用 `skills search --no-login --category <分类>` 或 `--public-tag <标签>`。
- `--verbose` 在每张卡下面多一行：来源 / 状态 / 版本 / 更新时间 / 分类 / 公开标签。

加入一个公开 skill 只是把它收进自己的库（引用），**不挡加载**：没加入的公开 skill 一样能
`skill invoke`。

## 整理标签与我的库（用户明确要求时才做）

- 贴 / 摘一个 skill 上的标签：
  `skillmaster skill tag add <地址> <名字> [--public]`
  `skillmaster skill tag remove <地址> <名字> [--public]`
  （默认我的标签；`--public` 是公开标签，只有所有者 / 管理能写）
- 改分类：`skillmaster skill category set <地址> <名字>` /
  `skillmaster skill category clear <地址>`（分类是替换，不是追加）
- 新建一个**不贴 skill** 的标签：`skillmaster skills my-tags add <名字> [--describe <说明>]`
- 改名 / 改说明：`skillmaster skills my-tags rename <旧名> --name <新名>` /
  `skillmaster skills my-tags describe <名字> --text <说明>`（空串清空说明）
- 加入 / 移出我的库：`skillmaster skill join <地址> --yes` /
  `skillmaster skill leave <地址> --yes`（没有 `--yes` 只打印后果，不执行）
- **删除标签定义只能去控制台**：它会摘掉所有 skill 上的贴法（含现在看不到的），CLI 没有这个命令。

## 先 invoke，再 files / read

`invoke` 把当时那一版记在本地，`files` 与 `read` 都从那里取，**所以不用记版本号**。
**跳过 invoke 直接取，拿到的是「当前版本」**：中途有人发布了新版，手里的正文和后面列到/取到的文件
就不是同一版，**而且不会报错**（真发生了命令会印出来）。`describe` 与 `get` 都已取消——
「这一版有哪些文件」现在是 `files`，而正文本身由 `invoke` 给；要看整个 skill 的信息用
`skillmaster skill show <地址>`。

## 停止条件

- 我的库和公域检索都没有相关项 → 直接告诉用户没找到，**不要**逐个试。
- **`files` 是给你看「有没有漏」，不是让你把整版读一遍**：正文没让你看的文件不要顺手取。
- `read` 说「这个版本里没有某个路径」→ 先跑一次 `skillmaster skill files <地址>` 看清正确的相对
  路径，**不要凭猜反复试**；`files` 里也没有的，就是没有。

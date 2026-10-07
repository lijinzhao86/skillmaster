---
name: skillmaster
description: 按需取用已托管的 skill。当任务需要特定领域能力（文档处理、数据分析、
  飞书操作、代码规范等）时，先用它列出可用的 skill，再按结果取用。
metadata:
  platform_api_version: "8"
---

# 我的 skill 库

服务地址：`https://<host>`

## 使用协议（按顺序，不要跳步）

1. 列出：`skillmaster skill list`
   → 每条两行：**第一行是地址本身**（整行就是待会儿要抄的），缩进的第二行是描述与
   「什么时候该用它」，看它判断相关性。
   末尾说「还有更多」时**先想收窄**——`skillmaster skill search "<关键词>"`（不要一次搜很多词）；
   确实要全部才用 `skillmaster skill list --all`。
2. 加载：把选中那一行的**地址整段抄下来**：`skillmaster skill invoke <地址>`
   → 正文印在下面。**这一步不能跳**：它同时把「这一版」钉在这台机器上，第 3 步靠的就是它。
3. 看这一版有什么、再取需要的文件：
   `skillmaster skill files <地址>` → 列出这一版所有文件的相对路径（哪个是正文、哪个是二进制，
   它会标出来）。取其中一个：`skillmaster skill read <地址> <相对路径>`
   ——**只取正文引用到的，或任务确实要用到的那几个**。
4. 要换版本：`skillmaster skill versions <地址>` 看有哪些版本可选，再把那一行的地址整段抄进
   `skillmaster skill invoke <地址>@<版本>`。

## 先 invoke，再 files / read

`invoke` 把当时那一版记在本地，`files` 与 `read` 都从那里取，**所以不用记版本号**。
**跳过 invoke 直接取，拿到的是「当前版本」**：中途有人发布了新版，手里的正文和后面列到/取到的文件
就不是同一版，**而且不会报错**（真发生了命令会印出来）。`describe`、`show`、`get` 都已取消——
「这一版有哪些文件」现在是 `files`，而正文本身由 `invoke` 给。

## 停止条件

- 列表和检索都没有相关项 → 直接告诉用户没找到，**不要**逐个试。
- **`files` 是给你看「有没有漏」，不是让你把整版读一遍**：正文没让你看的文件不要顺手取。
- `read` 说「这个版本里没有某个路径」→ 先跑一次 `skillmaster skill files <地址>` 看清正确的相对
  路径，**不要凭猜反复试**；`files` 里也没有的，就是没有。

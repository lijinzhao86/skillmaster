---
name: skillmaster-guide
description: SkillMaster 官方使用指南——用 skmr 找、建、改、上线、共享、删除 skill，运行脚本、配服务凭据、处理邀请与权限申请，以及登录与排障。
when_to_use: 要用 skmr 操作 skill，或搞不清草稿与线上、可见性、共享档位、登录与服务凭据、邀请与权限申请，或遇到上传、加载、运行、上线的报错时读它。闲聊、问别家产品时不用。
---

# SkillMaster 使用指南

按你要做的事找一篇读。做事篇从判断到步骤、出错处理；概念篇直接给结论。

## 第一次用

| 你要做 | 读 |
|---|---|
| 装或更新命令行客户端 | [装 skmr](start/install.md) |
| 第一次走完建→上线 | [从建到上线](start/first-skill.md) |
| 让 Agent 记得先查库再用 | [让 Agent 用上 skmr](start/agent.md) |

## 先弄懂概念

| 你想知道 | 读 |
|---|---|
| skill 是什么、地址怎么构成 | [一个 skill 到底是什么](concepts/what-is-a-skill.md) |
| 改了内容别人看不到 | [草稿与线上](concepts/versions-and-drafts.md) |
| 该选公开还是私有 | [公开还是私有](concepts/visibility.md) |
| 「我的库」是什么、收进移出 | [我的库影响什么](concepts/library.md) |
| 地址第一段是什么 | [命名空间决定了什么](concepts/namespace.md) |

## 用一个 skill

| 你要做 | 读 |
|---|---|
| 按意思找，或列我的库 | [找与列](use/search.md) |
| 读文件、加载 skill | [读一个 skill 里的文件](use/read-files.md) |
| 看清值不值得用：许可、场景、版本 | [看清一个 skill 是什么](use/inspect.md) |
| 跑它里面的脚本（`skmr run`） | [跑 skill 里的脚本](use/run.md) |
| 配服务凭据（`skmr secret`） | [给 skill 配服务凭据](use/secrets.md) |
| 复制一份 | [把别人的 skill 复制成我的](use/copy.md) |
| 看用过什么、谁在用 | [看使用记录](use/usage.md) |

## 做与改

| 你要做 | 读 |
|---|---|
| 起名、改名与描述 | [建一个 skill](author/create.md) |
| 开一份新草稿（`skmr draft create`） | [开一份新草稿](author/draft-create.md) |
| 改一个已有的 skill（含丢弃草稿） | [改一个 skill](author/edit.md) |
| 把本地一整个目录提上去 | [整目录上传](author/upload.md) |
| 传图片、PDF、Office、音视频 | [传二进制文件](author/media.md) |
| 让别人搜得到：描述与标签 | [描述与标签](author/describe.md) |
| 草稿里移动、复制、删除、取本地 | [草稿里的文件](author/files.md) |
| 改这一版的名称、说明、类型 | [版本名称与说明](author/draft-meta.md) |

## 发出去

| 你要做 | 读 |
|---|---|
| 上线、回滚、下架/重新上架 | [上线与回滚](ship/release.md) |
| 改可见性 | [改可见性](ship/change-visibility.md) |
| 共享给某个人、申请或查权限 | [共享](ship/share.md) |
| 接受邀请、处理权限申请（`notifications`） | [接受邀请、处理权限申请](ship/notifications.md) |
| 下线、删除、恢复 | [下线与删除](ship/delete.md) |

## 账号与凭据

| 你要做 | 读 |
|---|---|
| 还没有账号、忘密码、改密码 | [我还没有能用的账号](account/register.md) |
| 登录（网页、命令行、无浏览器） | [登录](account/sign-in.md) |
| 换账号、退出 | [凭据：怎么换账号、怎么退出](account/credentials.md) |
| 让某台机器失效、发 API key | [登录设备与 API key](account/devices.md) |
| 彻底注销账号 | [注销账号](account/delete.md) |

## 卡住了

| 症状 | 读 |
|---|---|
| 登录不上 | [登录不上](trouble/sign-in.md) |
| 上传不上去 | [上传不上去](trouble/upload.md) |
| 加载不了、读不到 | [加载不了、读不到](trouble/invoke.md) |
| 上线不了 | [上线不了](trouble/release.md) |
| 连不上、跑不起来、被沙箱拦 | [环境问题](trouble/env.md) |
| 都试过了还是不行 | [提交一份反馈](trouble/feedback.md) |

## 上限与写法

| 你想知道 | 读 |
|---|---|
| 一个 skill 能装多大、多少文件 | [上限](limits/quotas.md) |
| 内容怎么组织才被用上 | [内容怎么组织：让 agent 真的用得上](tips/for-authors.md) |

## 常见坑

1. **提交不等于上线。** 提交只落成草稿；上线要在网页上完成。
2. **上线过的版本改不了。** 要改，从线上开一份新草稿。
3. **已有草稿时 `submit` 会被拒绝。** 先 `skmr version list <地址> --status draft`；接着改，另开先问用户。
4. **`skmr skill write` 默认只新建。** 替换已有文件要 `--overwrite`，而且要先完整读过它。
5. **改已有 skill 就地改。** 取回本地再 `submit` 会被拒绝，`--new` 会多开。
6. **`read` 不等于加载。** 要生效走 `invoke`。
7. **网页登录与命令行登录是两件事。** 换账号先在浏览器换再 `skmr login`。
8. **共享是邀请，对方接受才生效。** 「让他管理」要显式写管理。
9. **服务凭据取不回来。** 提高每日上限必须由人在终端确认。

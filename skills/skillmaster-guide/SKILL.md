---
name: skillmaster-guide
description: SkillMaster 官方使用指南——用 skmr 找、建、改、上线、共享、删除 skill，以及登录与故障排查。
when_to_use: 要用 skmr 操作 skill，或搞不清草稿与线上、可见性、共享档位、登录凭据，或遇到上传、加载、上线的报错时读它。闲聊、问别家产品时不用。
---

# SkillMaster 使用指南

按你要做的事找一篇读。**做事的篇目**从判断开始，然后是步骤、经验和出错处理；**概念篇**直接给结论。

## 第一次用

| 你要做 | 读 |
|---|---|
| 装命令行客户端 | [装 skmr](start/install.md) |
| 第一次建一个 skill 并让它上线 | [第一次：从建到上线](start/first-skill.md) |

## 先弄懂概念

| 你想知道 | 读 |
|---|---|
| 一个 skill 到底是什么、地址怎么构成 | [一个 skill 到底是什么](concepts/what-is-a-skill.md) |
| 为什么改了内容别人看不到 | [草稿和线上版本的区别](concepts/versions-and-drafts.md) |
| 该选公开还是私有 | [公开还是私有](concepts/visibility.md) |
| 「我的库」里有什么 | [我的库影响什么](concepts/library.md) |
| 地址第一段是什么 | [命名空间决定了什么](concepts/namespace.md) |

## 用一个 skill

| 你要做 | 读 |
|---|---|
| 按意思找一个 skill 来用 | [找一个 skill 来用](use/search.md) |
| 读它的某个文件 | [读一个 skill 里的文件](use/read-files.md) |

## 做与改

| 你要做 | 读 |
|---|---|
| 从零建一个 skill | [从零建一个 skill](author/create.md) |
| 改一个已有的 skill | [改一个已有的 skill](author/edit.md) |
| 把本地一整个目录提上去 | [把本地一整个目录提上去](author/upload.md) |
| 传图片、PDF、Office、音视频 | [传二进制文件](author/media.md) |
| 让别人搜得到：描述、标签、分类 | [描述、标签与分类](author/describe.md) |
| 在草稿里移动、复制、删除、取到本地 | [草稿里的文件](author/files.md) |
| 给这一版起名、写说明、定版本类型 | [给这一版起名、写说明、定版本类型](author/draft-meta.md) |

## 发出去

| 你要做 | 读 |
|---|---|
| 把一版草稿上线、或回滚 | [把一版草稿上线](ship/release.md) |
| 改可见性 | [改一个 skill 的可见性](ship/change-visibility.md) |
| 共享给某个人 | [共享给某个人](ship/share.md) |
| 下线、删除、恢复 | [下线、删除与恢复](ship/delete.md) |

## 账号与凭据

| 你要做 | 读 |
|---|---|
| 还没有账号、忘了密码 | [我还没有能用的账号](account/register.md) |
| 登录（网页、命令行、无浏览器） | [登录](account/sign-in.md) |
| 换账号、退出 | [凭据：怎么换账号、怎么退出](account/credentials.md) |
| 让某台机器失效、发 API key | [登录设备与 API key](account/devices.md) |

## 卡住了

| 症状 | 读 |
|---|---|
| 登录不上 | [登录不上](trouble/sign-in.md) |
| 上传不上去 | [上传不上去](trouble/upload.md) |
| 加载不了、读不到 | [加载不了、读不到](trouble/invoke.md) |
| 上线不了 | [上线不了](trouble/release.md) |
| 都试过了还是不行 | [提交一份反馈](trouble/feedback.md) |

## 上限与写法

| 你想知道 | 读 |
|---|---|
| 一个 skill 能装多大、多少文件 | [上限](limits/quotas.md) |
| 内容怎么组织才真的会被 agent 用上 | [内容怎么组织：让 agent 真的用得上](tips/for-authors.md) |

## 常见坑

1. **提交不等于上线。** 提交只落成草稿；上线要在网页上完成。
2. **上线过的版本改不了。** 要改，基于它开一份新草稿。
3. **每轮都 `submit` 会攒草稿。** 动手前先 `skmr version list <地址> --status draft`。
4. **`skmr skill write` 默认只新建**，替换已有文件要加 `--overwrite`，而且内容从标准输入读。
5. **二进制用 `put`，不用 `write`**；而且 `put` 不新建草稿。
6. **改已有 skill 要就地改，别搬回本地。** `submit` 落成的是另一份草稿，不是你手上那份；内容没变时它干脆返回旧版。
7. **`read` 只是读，不等于加载。** 要让它生效，走 `invoke`。
8. **网页登录和命令行登录是两件事。** 换账号先在浏览器换，再 `skmr login`。
9. **共享默认给最低档「调用」。** 说「让他管理」就必须显式写管理。

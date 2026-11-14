# 描述、标签与分类：让别人找得到你

> 三样东西分工不同：**`description` 决定 agent 用不用你**，**公开标签决定人搜不搜得到你**，**分类决定你在哪个抽屉里**。

## 一、description：写给 agent 看

写在 `SKILL.md` 的 frontmatter 里。搜索和列表里只有名字和这一句；决定用之前还能读许可、环境与版本，正文要等它决定用了才读。

- **写「做什么 + 什么时候用」**：「处理 PDF：合并、拆分、提取文字。需要读或改 PDF 时使用」。
- **不要写**「一个强大的工具」「效率利器」——这类词对「什么时候用」没有任何信息。

改它也是改文件：走草稿 → 改文件 → 上线，不能只改线上那一版。见 [改一个 skill](edit.md)。

## 二、公开标签 vs 我的标签

两者是**两种不同的东西**，别混着用。

| | 公开标签 | 我的标签 |
|---|---|---|
| 谁能看到 | 所有人 | 只有你 |
| 干什么用 | 别人**搜索时靠它找回这个 skill** | 你自己给库里的东西分堆 |
| 怎么贴 | `skmr skill tag add <地址> <标签> --public` | `skmr skill tag add <地址> <标签>` |

摘掉：

```bash
skmr skill tag remove <地址> <标签>
skmr skill tag remove <地址> <标签> --public
```

网页上在 skill 详情的「信息」栏贴与摘。

- **完成信号**：`skmr skill tags <地址>` 里能看到（或看不到）这个词。

**怎么用**：

- 公开标签贴**别人会搜的词**——即用户会怎么称呼这件事，不是你内部的叫法。
- 同一件事的别名可以贴两个（「图片压缩」「压缩图片」），但别堆十几个无关的词。
- 我的标签只影响你自己怎么找，不影响别人。

查现成的：`skmr skills public-tags` 看平台上都在用哪些；`skmr skills my-tags` 看你贴过哪些。

## 三、分类

只能用官方词表里的词，先看：`skmr skills categories`。

```bash
skmr skill category set <地址> <分类>
skmr skill category clear <地址>
```

## 四、管理我的标签

```bash
skmr skills my-tags add      <名字> --describe "说明"
skmr skills my-tags rename   <旧名> --name <新名>
skmr skills my-tags describe <名字> --text "说明"
skmr skills my-tags delete   <名字>
```

`delete` 会拒绝并指向控制台：删除定义会摘掉所有 skill 上的贴法，且不能恢复，只能在控制台做。

## 五、标签页：集中管理

https://console.skillmasterai.com/my-tags

- **我的标签**：你自己建的，可以新建、删除。**删除不能恢复**——它会从所有贴过这个词的 skill 上一起移除（那些 skill 本身不受影响）。标签有数量上限，满了要先删一个。
- **公开标签**：你在自己创建的、未删除的 skill 上贴过的词，这一页只做查看；贴与摘在 skill 的信息页。

## 贴标签与写描述时要注意的

- **先看公开标签词表里别人在用什么**，再决定自己贴什么。全平台通用的词比你自创的更容易被搜到。
- **标签能随时改，`description` 不能**——后者要重走一遍上线。

## 贴标签出错怎么办

标签贴不上、分类报错，多半是这个 skill 不归你管——见 [共享给某个人](../ship/share.md) 确认你手上是什么权限。

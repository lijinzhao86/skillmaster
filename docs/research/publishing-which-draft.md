# 一份内容有多份草稿时，发布哪一份

**调研日期**：2026-10-25　**问题来源**：v18 的批量发布要知道"点这一行会发哪一份"。

这份记录只回答一件事：**内容平台怎么处理"同一份东西有好几份待发布版本"这个局面**。它不评价 SkillMaster 该抄谁——
那一步在下面 §对 SkillMaster 意味着什么 里，并且是要你来定的。

## 结论先说

**主流系统不是订一条"发最新那份"的规则，而是让这个局面根本不出现：一份内容最多只有一份草稿。**

| 系统 | 一份内容的待发布状态 | 怎么保证不出现多份 |
|---|---|---|
| [Contentful](https://www.contentful.com/help/faq/versioning/) | 四态：Draft / **Changed**（已发布但有待发的改动）/ Published / Archived | **只有一份草稿**。`Changed` 的意思是"当前草稿改过、但还没发"，不是"堆了几份" |
| [Sanity](https://www.sanity.io/docs/content-lake/drafts-and-versions) | Published / **Draft（至多一份）** / Version（属于某个 release） | 原话是 **"at most one"** draft。多份只可能出现在**不同的 release** 里，且每份都有名字 |
| [App Store Connect](https://developer.apple.com/documentation/appstoreconnectapi/working-with-subscription-group-versions) | 一个平台只有一个可编辑版本 | 建第二个会被 **409 拒掉**（`STATE_NOT_SUITABLE`），跨 `PREPARE_FOR_SUBMISSION` / `WAITING_FOR_REVIEW` / `IN_REVIEW` 都算 |
| Shopify | 单个 "unpublished changes" 标记 | [第三方工具](https://creativehub.io/help/how-to-push-a-product-to-shopify/)把它做成一个 `Changes pending` 开关，推一次就清掉 |
| WordPress | 一份草稿 + 无限**修订历史** | 修订（revision）是历史快照，不是可以同时发布的候选；草稿只有当前那一份 |

**唯一的例外是有分支模型的系统**（Git / GitHub PR）。它允许同一份代码有多条并行提案，代价是**每一份都有自己的名字和身份**，
而且合并是逐条的、显式的，从来不存在"发最新那个 PR"这种规则。

## 两份有意思的先例

**Sanity 的 Content Releases**（上表里唯一"允许多份"的形态）：一份文档可以在**每个 release 里各有一份 version**，
但**每份都属于一个有名字的 release**，发布的是「整个 release」。也就是说：**多份是被"装进容器"变成不用猜的**——
你发的不是"那份草稿"，是"这一批"。

**Pack 的 Content Releases**（Shopify 生态的第三方）：把跨对象的改动攒成一个 release，发布前**检查冲突**——
如果线上内容在草稿创建之后被人改过，会逐字段给 diff，让人选取消还是照发。这与我们 v18 的「预演不是承诺」是同一个问题，
他们用 diff 回答。

## 批量发布的交互共识

四份独立来源（[SAP Fiori](https://www.sap.com/design-system/fiori-design-web/v1-148/foundations/best-practices/global-patterns/messaging/processing-multiple-items)、
[Pixso 的两篇](https://pixso.net/articles/bulk-action-selection-ux/)、[emdash 的实现](https://github.com/emdash-cms/emdash/discussions/1523)、
[JVDS](https://www.jvds.cn/share/website-design/cms-bulk-publish-selection-scope)）说的是同一组事：

1. **选择范围要说死**：是"这一页这 12 项"还是"筛选后的全部 47 项"，写在按钮旁边。
2. **确认写影响，不写"确定吗"**：多少项、变成什么、哪些不会被处理、能不能撤回。
3. **部分失败要分开报**：成功的、没动的、出错的各一组，**失败项留着可以重试**，且**把所有错误都列出来**——
   [Drupal Canvas 的一个 bug](https://www.drupal.org/project/canvas/issues/3536236)就是"多条错误只显示第一条"。
4. **选中不要因为翻页/改筛选悄悄消失**；真要清掉，得说出来。

## 对 SkillMaster 意味着什么

我们的形状与上面几家有一处实质差别：**草稿是不可变的一行，`submit` 每次不同内容就新增一行**（ADR 0005 内容寻址）。
所以"多份草稿"在我们这儿不是异常，是**重复提交的自然结果**——一个人改了两次、都没发布，就有两份。

于是三条路：

| 路 | 做法 | 代价 |
|---|---|---|
| **约束**（业界默认） | 一个 skill 至多一份草稿：再提交时要么覆盖它、要么要求先发布或丢弃 | 覆盖 = 悄悄丢内容；要求先处理 = agent 每改两次就卡一次。而"丢弃"是一票不可反悔（ADR 0031） |
| **容器**（Sanity 的 releases） | 多份可以并存，但发布针对的是一个有名字的批次 | 要新建"批次"这个实体，还要它自己的生命周期；v18 的规模撑不起 |
| **逐份列出来**（GitHub PR 的读法） | 待上线那一格**一行就是一份草稿**，行上写清是哪一份；勾哪份发哪份 | 同一页四个 tab 的行不再同质（草稿按版本、已发布按 skill） |

**我倾向第三条**，理由是它是唯一既不猜、又不丢东西的一条：选择就是勾选本身，没有"发哪份"的规则需要记住；
而前两条各自要用一种我们不愿意付的代价换掉这个局面。

## 没查到的

- **没有找到**任何一家在"同一对象多份待发版本"下提供"默认发最新那份"的规则。它们要么只有一份，要么每份有名字。
- 免费/社区版无法核实的部分：Sanity 的 releases 是 Enterprise 功能（Scheduled Drafts 在 Growth+），
  它们的**免费版是否也只允许一份草稿**没有查到——这不影响上表里"至多一份"这条，那一条在文档里是明确的。

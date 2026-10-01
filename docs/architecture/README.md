# 系统架构（跨版本）

> **最后更新**：2026-09-28
> **这一轴是什么**：**不随版本变的工程架构**——系统怎么切、模块之间的契约与依赖规则、以及数据与资源的身份模型。
> **这一轴不是什么**：不是产品目标（那在 [`target/`](../target/README.md)），不是取舍记录（那在 [`decisions/`](../decisions/README.md)），也不是某一版的接口与表结构（那在 [`versions/`](../versions/)）。

## 为什么单开一轴

文档五轴按「**是否随版本迭代**」分组，判断口诀是「这个事实三年后还成立吗？」。**模块切分与依赖规则是三年后还成立的东西**——[`technical-design.md`](../versions/v1-hosting/technical-design.md) §2.5 那张模块表甚至带一列分期（P0/P1/P2），它本身就横跨版本。

所以「架构」原先**没有家**，只能挤在某一版的 `technical-design.md` 里。挤的代价是具体的：同一份 §2 内容会在每一版各留一份，而 `docs-architecture` §2.3 的铁律说同一个事实只能有一个权威位置。**这一轴就是给它腾出来的位置。**

## 与版本 TD 的分工

| 问题 | 在哪里 |
|---|---|
| 系统怎么切、模块之间允许什么依赖、数据与资源的身份是什么 | **这里** |
| 这一版建哪些表、接口长什么样、分几期、还有哪些开放问题 | [`versions/vN-主题/technical-design.md`](../versions/v1-hosting/technical-design.md) |

**迁移是渐进的，而且是单向的**：内容从这里讨论清楚、落到本目录，版本 TD 里那一段改成链接。**不先在这里造一份副本**——两个权威一定会漂。所以在迁移完成之前，下面标「现在在」的位置**仍然是权威**。

## 目录形状

校验器把这一轴也当成**封闭形状**（认识的之外一律报错）：

```
docs/architecture/
├── README.md      必需。本文件：这一轴是什么、与谁分工、模块索引
├── rules.md       可选。依赖规则与它们的可执行检查
├── model.md       可选。资源与版本模型：从用户到字节的实体与身份
├── *.svg / *.png  可选。文档旁的插图，文件名随意
└── modules/       可选。每个模块一篇，名为 M<NN>-<slug>.md
```

后三项与 `modules/` **只在存在时才检查**——与 `versions/` 下的 `iterations/` 同一做法。理由同上：要求一个空文件先就位，等于邀请一份与它要取代的东西重复的副本。

**图片是唯一不按名字白名单放行的一类**（校验器里 `ASSET_SUFFIXES`）。因为图与文档不同：它没有散文，也就**不可能成为某个事实的第二个权威**——而封闭形状防的正是这个。所以图的名字随便起，将来加图也不必再动校验器。版本文件夹同样允许图片。

## 现在有什么

| 文档 | 内容 | 状态 |
|---|---|---|
| [`model.md`](model.md) | 从用户到字节的实体、**四层粒度各自的身份**、关联、寻址、版本生命周期、与 git 的对照 | **已就位** |
| `rules.md` | 三条依赖规则、规则①的三种落法、两种可执行检查（ArchUnit + 表归属 lint） | **未迁**——现在在 TD §2.5 与 `ArchitectureTest`/`TableOwnershipTest` 的类注释里 |
| [`modules/M01-account-login.md`](modules/M01-account-login.md) | M1 账号登录：职责、边界、拥有的表、不变量与规则、对外契约 | **已就位** |
| `modules/M<NN>-<slug>.md` | 其余模块同上 | **未开始**——按模块讨论后逐个落 |

## 模块索引

模块编号、包名与「拥有哪些表」的权威目前在 TD §2.5，并由代码侧强制：

- **包结构**：`com.skillmasterai.modules.<name>`，仓库里的 `internal/` 放仓储与 SQL，外部不得引用
- **可执行检查**：[`ArchitectureTest`](../../skillmaster-server/src/test/java/com/skillmasterai/ArchitectureTest.java)（分层与环）与 [`TableOwnershipTest`](../../skillmaster-server/src/test/java/com/skillmasterai/TableOwnershipTest.java)（表归属 lint），模块清单本身在 [`ModuleMap`](../../skillmaster-server/src/main/java/com/skillmasterai/common/ModuleMap.java)

**这里不重列那张表**——重列就是第二个权威。`modules/` 下的每篇文档写的是**那个模块自己的事**（它为什么存在、边界在哪、对外契约是什么），而不是那张表的又一份副本。

## 与 `target/design.md` 的区别

[`target/design.md`](../target/design.md) 是**远期完整形态**的设计——它描述的是目标产品，包括还不存在的东西；两者之间的差异由 [`target/README.md`](../target/README.md) 那份清单跟踪。

这里写的是**当前系统实际怎么切**。所以：`target/design.md` 说 `Org` / `Membership(role)`，这里说 `namespace` / `namespace_member`——**不是矛盾，是两个时间点**，差异那一处已记在 target 的清单里。

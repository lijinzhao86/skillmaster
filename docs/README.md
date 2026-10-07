# 文档索引

本项目文档分**五类，按「是否随版本迭代」分成两组**。以前的混乱正来自把它们混在同一层目录里——现在分开：

| 轴 | 目录 | 随版本迭代？ |
|---|---|---|
| **开放性调研** | [`research/`](research/) | ❌ 不随版本变，长期参考 |
| **目标态** | [`target/`](target/) | ❌ 不随版本变（远期的完整形态） |
| **决策记录** | [`decisions/`](decisions/) | ❌ 跨版本存活，逐条编号、可被取代 |
| **工程架构** | [`architecture/`](architecture/) | ❌ 不随版本变（系统怎么切、模块契约、数据身份） |
| **版本** | [`versions/vN-主题/`](versions/) | ✅ 每次迭代新增一个目录 |

## 目录结构

```
docs/
├── README.md                 ← 本文件
├── research/
│   └── competitors.md        竞品逐家拆解（分发 / 计价 / 门控 / 信任 四层）
├── target/
│   ├── README.md             ⚠️ 与当前决策的差异清单——读 target/ 之前先读它
│   ├── blueprint.md          为什么做、为谁做、商业模式
│   └── design.md             完整形态的模块、场景、页面、概念模型
├── decisions/
│   ├── README.md             ADR 索引
│   └── 0001..0036-*.md       编号决策记录（逐条见该目录的 README.md）
├── architecture/
│   ├── README.md             这一轴是什么、与版本 TD 的分工
│   ├── model.md              资源与版本模型：从用户到字节的实体与身份
│   ├── rules.md              （待迁）依赖规则与它们的可执行检查
│   └── modules/              每模块一篇，M<NN>-<slug>.md（现有 M01、M02）
└── versions/
    └── v1-hosting/
        ├── README.md             版本记录主页（薄）：状态 + 链接 + 五问摘要
        ├── prd.md                需求：用户与场景 / 范围 / 验收与指标
        ├── technical-design.md   设计：架构 / 数据模型 / 接口 / 分期 / 开放问题
        ├── test-plan.md          测试：验收映射 / 用例 / 结果
        ├── known-issues.md       本版范围外的历史代码缺陷
        └── iterations/           小版本迭代记录（只增不改）
```

## 版本

| 版本 | 主题 | 状态 | 含收费 |
|---|---|---|---|
| [`v1-hosting`](versions/v1-hosting/) | skill 托管与远程加载 | **进行中**（设计已定稿；**实现到哪一步、哪一部分没验证过，权威是该版 `README.md` 的状态行**） | 否 |

**当前活跃版本是 `v1-hosting`。** 新版本在 `versions/` 下新建目录（`vN-主题`）。版本内的四份文档是**活文档**——小版本**就地修订**它们，历史由 `iterations/` 保存。

## 四条约定

**1. 版本由文件夹承担，文件内部不再写版本号。**

以前每份文档自己带 `v0.1` / `v0.2` / `v0.3`，但这些数字**互不可组合**——它们分别指产品轴和技术轴，放在一起没有任何意义。现在文件开头只写「最后更新 + 状态」，版本信息由它所在的文件夹表达。

**2. 决策不进版本文件夹，独立成 [`decisions/`](decisions/) 下的编号 ADR。**

决策跨版本存活。要推翻某条，就新增一条"取代 000N"，而不是修改原文——这样"当初为什么这么定"永远查得到。

**3. 每个版本的 `README.md` 是版本记录的主体——但它是薄的。**

它固定回答五件事：这一版的目标、**明确不做什么**、验收标准、与目标态的关系、当前状态。**每问只写一句话 + 链接**，细节在 `prd.md`。README 不复述别处已有的内容——这不是风格偏好，复述必然漂移。

**4. 版本文件夹的形状是固定的，且由 skill 强制。**

每个 `versions/vN-主题/` 恰好是 `README.md` + `prd.md` + `technical-design.md` + `test-plan.md` + `iterations/`（外加可选的 `known-issues.md`）。大版本与小版本的判据、每份文档必须回答什么、以及**「一个事实只有一个权威位置」**这条铁律，都写在 [`../.claude/skills/docs-architecture/`](../.claude/skills/docs-architecture/SKILL.md)——**那里是权威，本文不重复**。

写完跑校验器：

```bash
python3 .claude/skills/docs-architecture/scripts/check_docs.py
```

## 阅读顺序

1. [`target/README.md`](target/README.md) —— ⚠️ **先读这份差异清单**。
2. [`target/blueprint.md`](target/blueprint.md) —— 为什么做这个产品、为谁做。**注意**：目标态文档写于 2026-09-20，早于当前的架构决策，照着实现会做错——差异见上一条。
3. [`decisions/`](decisions/) —— 已经定下来的关键取舍（每条一页；**条数与主题见该目录的索引**，这里不重列）。
4. [`architecture/model.md`](architecture/model.md) —— 从用户到字节有哪些实体、**各自的身份是什么**、怎么寻址。模块表仍在版本 TD §2.5（见该轴 [`README.md`](architecture/README.md)）。
5. [`versions/v1-hosting/`](versions/v1-hosting/) —— **当前要做的这一版**具体是什么。
6. 需要背景时看 [`research/competitors.md`](research/competitors.md) —— 竞品都怎么做的、空在哪。

## 已知的待清理项

**目标态文档（`target/`）与当前决策的差异已逐条记录在 [`target/README.md`](target/README.md)**——那里是唯一权威清单，不要在本文件里重复维护（本行原先还附了一段「概要」，2026-09-26 删掉：复述就是漂移的起点）。

尚待处理：

- [`target/design.md`](target/design.md) 与 [`blueprint.md`](target/blueprint.md) 开头仍带旧的 `v0.1` / `v0.2` 版本号，按约定 1 应删掉。**删之前留意**：[`target/README.md`](target/README.md) 里引用了行号，改动正文会让行号漂移（该清单以章节名为主、行号为辅）。
- [`target/design.md`](target/design.md) 的阶段路线把 M1 定义为「私有托管 **+ 渐进付费**」，而 v1-hosting 是「托管，**不含收费**」——两者已分叉。关系在 [`v1-hosting/README.md`](versions/v1-hosting/README.md) 里说明，目标态那份路线图待下次修订时一并处理。

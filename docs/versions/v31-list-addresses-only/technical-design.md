# v31-list-addresses-only · 技术设计

> **最后更新**：2026-10-30
> **所属版本**：[`README.md`](README.md)

<!--
接口与数据模型的权威。取舍的理由在 ADR，不在这里复述。
-->

## 架构

只动 CLI，不动服务端、不动 API。

```
skills list   → listSkills() → collect(ctx, client, options) → printCards(cards, withReading=false, verbose)
skills search → search()     → 单页请求          → printCards(cards, withReading=true,  verbose)
skill show    → showSkill()  → printSkillShow()  （不变）
```

`printCards` 现在有两个开关：

| 开关 | `list` 默认 | `list --verbose` | `search` |
|---|---|---|---|
| `withReading`（描述 - 适用场景） | false | **true** | true |
| `verbose`（来源 / 状态 / 版本 / 更新于） | false | true | 跟 `--verbose` |

`list --verbose` 的两个开关一起为真，所以它的输出与今天的 `list` 完全一样；`search`
两个开关都不变。**唯一减少的是 `list` 的默认输出。**

## 数据模型

没有变化。卡片仍由服务端给全（`description` / `when_to_use` 仍在响应里），只是 `list`
默认不再把它们打出来——**不是不取**，所以不需要改接口，也不需要新增请求参数。

## 接口

### `skmr skills list` 的输出

| 场景 | 输出 |
|---|---|
| 默认、非空 | 每条一行 `命名空间/名字`；最后一行 `（共 N 个。加载某一个：skmr skill invoke <上面某一行的地址>。）` |
| `--verbose`、非空 | 每条三行：地址；缩进的「描述 - 适用场景」；缩进的「来源：… · 状态：… · 版本：… · 更新于：… · 分类：… · 公开标签：…」（空字段不打印） |
| 空库 / 筛选为空 | 与今天相同（`emptyLibraryLine` + 筛选提示），本版不动 |

**卡片之间不加空行**，默认输出因此可以直接 `| head` / `| grep` / `| xargs`。

### 分页

- 默认**翻完所有页**：`listSkills` 调 `collect(ctx, client, options)`（v31 起 `collect` 只有一个模式：读到底）。服务端每页 50 条，循环取到底。
- `--all` **仍被接受**，但不再改变行为（默认已经是全部）。1.x 内命令面只增不减（`RELEASE.md` §1），所以不删除它；帮助里改成「保留；现在默认就是全部」。
- 原来的「（这一页 N 个，还有更多…）」与「要全部就用 --all」两行成为死代码，一并删除——`collect` 只剩读到底这一个模式，不会返回 `more`。

### 帮助

| 字段 | 改动 |
|---|---|
| `Usage` | 保留 `[--all]`（仍然接受），参数表里说明它不再改变行为 |
| `--all` 的 Summary | 改成「保留；列表现默认列全」 |
| `Output` | 改成「默认每条一行地址，列全；描述用 --verbose 或 skill show；页脚给出加载命令」 |
| `Examples` | 不变 |

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0（本版） | 默认纯地址 + 默认列全 + `--verbose` 保留描述 + 帮助 | prd A1–A8 |
| P1（不在本版） | 平台技能 `skillmaster/startup` 里若有对 list 输出形状的描述，另走草稿 | — |

## 开放问题

1. **超大库要不要硬上限？** 当前倾向：不加。地址约 25 B/条，1,000 条约 25 KB；再大就用
   `--show` / `--namespace` / 分类 / 标签 / 关键词收窄。若实测撑爆上下文再议。
2. **`--show` 非默认时要不要给非 live 行加标记？** 当前倾向：不。调用方自己选了要看哪些状态，
   状态行在 `--verbose` 里。
3. **空结果的两句话要不要跟着精简？** 当前倾向：不动。它们只在没有结果时出现，不是体积问题。

> `--verbose` 的详情行是否收窄到「来源 / 状态 / 版本 / 更新于」**已经不是开放问题**：本版不动
> `verboseLine`，它打的是卡片上有的那一行（含分类与公开标签，空字段不打印），见 §接口。

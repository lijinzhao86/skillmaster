# v40-code-writing · 技术设计

> **最后更新**：2026-11-02

## 架构

### 三层文本

```
docs/
├── current/       ← 唯一描述「系统现在长什么样」。活文档。
├── versions/      ← 冻结的变更记录。v40 起只写「相对 current 的 delta」。
├── decisions/     ← 为什么。正文只增不改。
├── research/      ← 不变
└── target/        ← 不变
```

**一层一个时间点。** `current/` 随实现改；`versions/` 合入即只读；`decisions/` 记当时的取舍。一份文档不能同时承担两个时间点。

`docs/architecture/` 整体改名为 `docs/current/`——它本来就是"当前形状"，名字没说出来。

### 事实按三类给家

| 类 | 家 | 谁检查 |
|---|---|---|
| 派生型（上限、模块表、错误码、接口形状） | 代码符号 / 配置 | 生成器 + `generate.py --check` |
| 断言型（行为、边界、不变量） | 测试 | 测试红 |
| 散文型（为什么、否掉了什么、代价） | ADR / 版本 delta | 人，只在新增时读 |

**注释不在这张表里**：它只能指向事实，不能成为事实的家。

### 三个 skill 的分工

| skill | 回答 | 不回答 |
|---|---|---|
| `docs-architecture` | 放哪：轴、目录、每份文档必须回答什么、版本化 | 句子怎么写 |
| `lijinzhao/code-writing` | 怎么写：事实归属、注释三类四禁、六条硬规则 | 放在哪个目录 |
| `dev-sop` | 什么时候：六个阶段、锁定、设计稿先行 | 文本细节 |

**同一条规则只在一个 skill 里定义**，另两处只链接。这是本设计要治的病，所以它先适用于三个 skill 自己。

### 数据流

```
代码符号 ──► scripts/docs/generate.py ──► docs/current/{system,limits,errors,schema,cli,config}.md
目录与文件头 ──► 同一个脚本 ──► docs/README.md、docs/versions/README.md、docs/decisions/README.md

CI:  python3 scripts/docs/generate.py --check（在 arch.yml，每个 PR 都跑）
```

生成物**入库**（不是构建时临时生成），理由：读文档的人不需要先跑脚本；差异会出现在 PR 里，而不是被构建藏起来。

## 文档模型

### 实体

| 实体 | 定义 | 身份 |
|---|---|---|
| **事实** | 一条可判定真假、且会被引用的陈述 | 它的家（符号 / 测试 / ADR） |
| **视图** | 呈现事实的文档 | 路径 |
| **生成物** | 由脚本从家算出来的视图 | 源 + 生成器版本 |
| **指针** | 指向事实的引用（链接、符号全名、ADR 编号、测试名） | 目标的身份 |

### 生成物的头

每份生成物第一行固定为：

```html
<!-- 生成物，勿手改。源：<源文件>；重新生成：python3 scripts/docs/generate.py -->
```

第二行起是正文。**注释、格式、emoji 都不进生成物**——它是当前状态的快照，不是叙事。

### 版本记录的两种形态

| 形态 | 适用 | 判据 |
|---|---|---|
| 全量 | v1–v39（归档） | README 的引导块带「**归档**」 |
| delta | v40 起 | TD 的 §架构 写「与 `current/` 的差异」，不重述现状 |

**归档不改正文**：只在 README 的引导块加一行「**归档**」，叙述原样。

**唯一的例外是链接目标。** `docs/architecture/` 改名后，v1–v39 正文里指向它的相对链接必须改到 `../../current/…`，否则 A1 不成立、读者走空。这一处只换括号里的目标，链接的显示文字与整句不动——与 ADR「只允许改文件头的状态与引用」同一条口径。

## 接口

### 生成器

```
python3 scripts/docs/generate.py            # 写文件
python3 scripts/docs/generate.py --check    # 只比对，不写；不一致退出 1
```

输入与输出：

| 输出 | 源 | 抽取方式 |
|---|---|---|
| `docs/current/system.md` | `skillmaster-server/.../common/ModuleMap.java` | 按 `new Module(...)`、`Set.of(...)` 与 `PUBLISHED_TYPES` 解析 |
| `docs/current/limits.md` | `modules/ingest/IngestLimits.java`、`modules/blob/BlobSettings.java` | 按 `public static final` 与默认值解析 |
| `docs/current/errors.md` | `common/ErrorCode.java` | 按 `NAME("wire")` 解析 |
| `docs/current/schema.md` | `resources/db/migration/V*.sql` | 按版本序应用 `CREATE TABLE`（含 `IF NOT EXISTS`）/ `ADD COLUMN`（含 `IF NOT EXISTS`）/ `DROP COLUMN` / `RENAME COLUMN` / `RENAME TO` / `DROP TABLE` |
| `docs/current/cli.md` | `cli/cmd/skillmaster/declarations*.go` | 每个 `help.Spec` 从 `Path:` 到下一个 `Path:` 之间取 `Summary` / `Usage` |
| `docs/current/config.md` | `resources/application.yml` | 按缩进还原键路径；`${ENV:default}` 里的变量另列 |
| `docs/README.md` | `docs/` 目录结构 | 扫描五个轴与版本目录 |
| `docs/versions/README.md` | `versions/*/README.md` 的头 | 读状态行 |
| `docs/decisions/README.md` | `decisions/NNNN-*.md` 的头 | 读编号、标题、状态、日期 |

**解析失败必须报错退出，不许静默生成半份**——生成器最坏的失败是"看起来生成成功"。每条规则都断言抽取数不少于一个下限（模块、发布类型、表、命令、配置、迁移、版本、决策各有下限，具体值在生成器里）；错误码另做逐条比对（源里的枚举常量数必须等于生成行数），因为漏掉最后一个常量时，一个 `> 0` 的断言照样通过。

**下限挡的是解析器整体失效，不挡单条丢失**——这是有意的：精确基线会让「新增一张表」也必须先改生成器，代价大于收益。单条丢失只有错误码这一类能判，因为它的源是一份封闭枚举。

### 检查器

四道检查，各管一件事：

| 检查 | 管什么 | 强度 |
|---|---|---|
| `check_docs.py` | 形状、主题、链接；`current/` 的封闭形状；**v40 之前的版本 README 含「归档」** | 阻断 |
| `generate.py --check` | 生成物与源一致（六份 current 视图 + 三个索引） | 阻断（CI） |
| `impact.py --check` | 活文档与代码注释里的引用可解析 | 阻断（CI） |
| `check_comments.py` | 注释里的历史叙述与重复数值（**版本号不算**：它是指针，由 `impact.py` 保证可解析） | **警告**——存量未清完，阻断等于逼人一次改行 |

`check_comments.py` 接受路径，所以日常只对改动的模块跑；`--all` 打印默认范围的总量并恒退出 0，它是一张计数表，不是闸。

### 反向索引

```
python3 scripts/docs/impact.py <符号 | --adr NNNN | --version vN | --file 路径>
python3 scripts/docs/impact.py --check
```

它扫一遍活文档（`docs/`、`.claude/skills/`、`CLAUDE.md`）与代码注释（服务端、CLI、控制台、站点），索引五类可追踪的引用：`Class.MEMBER`（含测试类）、`ADR NNNN`、`vNN`、测试类名、配置键（`skillmaster.*` 判失效；`spring.*` / `server.*` 只索引不判——框架属性可能真实存在而不在我们的 `application.yml` 里）。一次查询回答「改这条事实会波及谁」，而不是每次重搜全仓。**裸方法名不判**——它可能是散文、标准库或别的项目的 API；可判的形式是 `Class.MEMBER`。

**`--check` 是锁**：**已知类上的** `Class.MEMBER` 改名、ADR 重编号、版本目录改名、自有配置键（`skillmaster.*`）改名，所有仍指向旧名字的活文档与注释会红，和代码里删掉一个符号一样。**豁免只有两处**：`decisions/` 的正文与 v40 之前的版本——它们是记录，允许指向已经不存在的东西；要求历史可解析，等于逼人重写历史。`research/` 与 `target/` 不豁免。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| **P0** | 改名 `current/`、链接全部改对、三个索引改为生成、ADR 状态清零、版本归档标记 | A1–A4 |
| **P1** | 三个代码派生的现状文档（模块表、上限、错误码）；P3 再加表结构、命令语法、配置项 | A5 |
| **P2** | 三个 skill 重新分工；注释按规则清理（ingest / blob / auth 优先） | A6、A7 |
| **P3** | 反向索引与引用检查；注释里的重复数值清零 | A7、A8 |

本版交付 P0–P3。P3 之后，改一条事实的路径是：`impact.py <事实>` 查影响面 → 改家 → 重生成 → 跑四道检查，不再全仓搜索。

## 开放问题

**Q1 · 生成器的解析强度：正则还是结构化清单？**
当前倾向：正则 + 条数断言（错误码逐条比对）。理由：不引入构建依赖，且失败立刻可见。代价：源文件重构（比如把 `Set.of` 换成 builder）会让解析器失效。缓解：解析失败即退出，不会静默。

**Q2 · 注释存量清到什么程度？**
当前倾向：只清规则能判定的（裸数值、`原先`/`以前`），且只对审计最频繁的三个模块做。**版本号不清**——它是指针，清掉反而丢了溯源；语义类（决策复述）靠评审。理由：自动改写无法判定语义，全量重写是新的漂移源。

**Q3 · `target/` 要不要并入 `current/`？**
当前倾向：不并。两者都描述"应该是什么"，但时间点不同——`target/` 是远期，`current/` 是现在。答案取决于 `target/README.md` 的差异清单能否清零；不在本版回答。

**Q4 · 生成物入库还是构建时生成？**
已定：入库。理由见 §架构 · 数据流。

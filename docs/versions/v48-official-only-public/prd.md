# v48-official-only-public · 产品需求

> **最后更新**：2026-11-05
> **所属版本**：[`README.md`](README.md)
> **来源**：产品负责人 2026-11-05 的方向——「现在不要做用户公开，只允许官方账号 `skillmaster` 公开」；存量处置同日的盘点与确认。

## 用户与场景

**谁在用**：公开 skill 的读者（普通用户，以及替他们执行命令的 agent）、skill 作者、平台运营者。

**在什么场景下用**：

- 读者在控制台的「发现」页或 `skmr skills search "<问题>" --scope public` 里看到一条公开 skill，据此决定要不要用。
- 官方账号的作者在 `skillmaster` 命名空间的 skill 设置页里，决定它是私有还是公开；其他作者只看到「私有」。
- 运营者要在出问题时指出「这条公开内容是平台收进来的，来源与责任在谁」。

**现在的替代做法是什么**：

- **作者可以自己公开，中间没有人审**：任何 skill 的 `admin` 档持有者都能在设置页选中「公开 · 调用」或「公开 · 浏览」；服务端只校验值域，不校验这个 skill 属于谁。
- **读者无法从公域判断来源**：公开 skill 的卡片只写命名空间、名字、描述与标签；「公开」本身不代表平台看过它。
- **运营者没有下架与追责的依据**：公开是一个作者随时可做的设置，平台没有留下「谁批准它进入公域」的记录。

## 现在的问题、诱因与改法

### 问题 1：任何作者都能把 skill 设为公开，没有审核

**现在的流程**：

```text
作者打开 设置 › 常规 › 可见性
→ 选「公开 · 调用」或「公开 · 浏览」
→ PATCH /web/skills/{ns}/{name}/visibility
→ 服务端只校验值域 → 写入 visibility=public + public_rung
→ 这条 skill 立刻进入公域搜索与发现
```

**问题**：公开意味着任何持凭据的账号都搜得到、调得到，agent 也可能在对话里加载它。平台既没有审核内容，也没有在出事时能找到的责任主体；「公开」被读成平台背书，而平台其实什么都没做。

**为什么会这样**：[ADR 0048](../../decisions/0048-visibility-hands-out-a-rung.md) 只定了公开的**深度**（陌生人拿到 `invoke` 还是 `browse`），没有定公开的**主体**；`UpdateSkillVisibilityUseCase` 因此只校验 `private` / `public` 与两个档位，`admin` 档就能改。

**怎么改能解决**：服务端把 `public` 限定到命名空间上 `official = true` 的那些（今天只有种子里的 `skillmaster`），其他命名空间返回 403 `public_is_official_only`。公开的语义随之变成「平台背书」，两态可见性与档位都不变。决定记在 [ADR 0146](../../decisions/0146-public-is-platform-endorsement.md)。

### 问题 2：只改服务端，会让作者点到一个必然失败的选项

**现在的流程**：控制台的设置页把三个选项直接摆给所有作者；服务端改了规则之后，非官方作者仍会看到「公开」，选中后收到 403。

**问题**：用户看到能点、点了失败，而且失败原因不在他的操作里。这是把一条平台策略伪装成一次操作错误。

**为什么会这样**：页面现在没有「这个命名空间能不能公开」这个事实，只能按命名空间硬编码，或者按当前是否公开反推。

**怎么改能解决**：作者面响应新增只读布尔 `can_publish_public`（属于官方命名空间的 skill 为 `true`）。页面只按它决定摆不摆公开选项；非官方页面只显示「私有」并写明「公开目前只对官方 skill 开放」。页面不硬编码 `skillmaster`；将来加官方命名空间是一次迁移/种子，不改任何配置。

### 问题 3：存量已经有非官方公开 skill

**盘点结果**（2026-11-05，只读查生产）：公域共 4 条——`skillmaster/skillmaster-startup`、`skillmaster/skillmaster-guide` 两条官方；`skillmaster-test/pdf-table`、`skillmaster-test/meeting-notes` 两条来自测试账号。产品负责人确认 `skillmaster-test` 是测试账号，**清掉或转私有都行**。

**怎么改能解决**：一条 Flyway 迁移把 `official = false` 的命名空间里的公开行改成私有、档位置空；不删除 skill 与版本。迁移先把种子里的 `skillmaster` 标为官方，再按同一列清理，不按账号名写死。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | 服务端拒绝非官方命名空间设置 `public`，返回 403 `public_is_official_only`，原值不变 | `UpdateSkillVisibilityUseCase` 与错误码；官方判定来自 `namespace.official` 列，默认只有种子里的 `skillmaster` 为 `true` |
| 2 | 作者面响应新增只读布尔 `can_publish_public` | `AuthoredSkillResponse`；控制台与 CLI 只读它 |
| 3 | 控制台设置页按能力渲染：非官方只显示「私有」并解释；官方仍显示三档 | `SettingsTab` 与接口类型；文案进 `design/` |
| 4 | 存量一次性转私有 | Flyway 迁移：`official = false` 且 `visibility = 'public'` 的行改成 `private`、`public_rung = NULL` |
| 5 | 设计件按第 3 条更新，并重新生成两个演示件 | `design/src/console/` 与 `design/skillmaster-控制台演示.html` |
| 6 | 一条 ADR、本版四件套、目标态差异清单补一行 | `docs/decisions/0146-*`、`docs/versions/v48-*`、`docs/target/README.md` |
| 7 | 代码改动后重出生成视图 | `docs/current/errors.md`、`docs/current/schema.md` 等由 `scripts/docs/generate.py` 生成 |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 用户公开的申请 / 审批流 | 没有审核标准、审核人、响应时限、下架与申诉；做出来也是一个没人负责的队列（2026-11-05 产品负责人定：现在不做用户公开） | 有真实用户公开需求、且平台愿意承担审核责任时，新开一条 ADR |
| 2 | 按账号的白名单或例外 | 地址的第一段就是命名空间；按命名空间判定与寻址一致，也能覆盖官方命名空间下将来的多个维护者 | 不做——这就是选命名空间而不是选账号的理由 |
| 3 | 公开内容的自动安全扫描、许可检查 | 那是开源同步那件事的一部分，需要来源、许可证与扫描规则一起定 | 下一版之后的「开源 skill 自动同步」 |
| 4 | 删除存量的非官方公开 skill | 转私有已足够退出公域；删除不可逆，内容仍属于作者 | 作者自己要求删除时，用已有的删除流程 |
| 5 | 通知存量作者 | 盘点确认只有测试账号；没有真实作者可通知 | 若将来发现真实作者受影响，做一次明确的一次性通知 |
| 6 | 新增 CLI 设置可见性的命令 | 改可见性仍是高风险网页操作，已有 `skmr skill visibility <地址>` 打开网页 | 不做——沿用 [ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 的高风险例外 |
| 7 | 公域信息增强与排序 | 与「谁能公开」是两件事，先让公域内容有背书，再谈怎么展示 | 下一版「公域信息与排序」 |
| 8 | 组织 / 团队命名空间 | 组织模型还没有落地，本版只认保留命名空间 | 组织模型落地时 |

## 控制台操作与 CLI 对应

本版**不新增控制台操作**，只改设置页里「可见性」一节的选项呈现。已有的那一处操作及其 CLI 对应不变：

| # | 控制台操作 | CLI 命令 | 备注 |
|---|---|---|---|
| 1 | 改 skill 可见性（设置 › 常规） | `skmr skill visibility <命名空间>/<名字>` | 打开网页由人确认，不直接改；高风险例外，见 [ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 与 [ADR 0126](../../decisions/0126-page-only-actions-open-the-page.md) |

本版没有新增例外，也没有需要补齐的映射。

## 拥有面

| 要动的文件 / 面 | 是不是共享面 | 谁在用 |
|---|---|---|
| `UpdateSkillVisibilityUseCase`、`SkillSharingService.setVisibility` | 否 | 可见性写入 |
| `ErrorCode` 与 API 异常映射 | 否 | 全部 API |
| `namespace.official` 列、`Namespace` 与仓储读取 | 否 | 可见性写入与作者面读取 |
| `AuthoredSkillResponse` 与对应 use case 模型 | **是**（API 契约） | 控制台作者面与 CLI |
| `SettingsTab`、`api/types.ts`、控制台测试 | 否 | 控制台设置页 |
| `design/src/console/` 设置页与 fixtures | **是**（`fixtures/skill.ts`、`styles.css`、生成件） | SkillPage 与一组 skill 组件 |
| `design/skillmaster-控制台演示.html` | 生成物，入库 | 设计检查 |
| Flyway 迁移与 `docs/current/schema.md` | 否 | 数据库与生成视图 |
| `docs/decisions/0146-*`、`docs/versions/v48-*`、`docs/target/README.md`、`docs/current/TODO.md` | 否 | 文档 |

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 | 对应用例 |
|---|---|---|---|
| A1 | 非官方命名空间的 skill 设 `public` 被拒绝，原值不变 | 以非官方账号对自有 skill 调 `PATCH /web/skills/{ns}/{name}/visibility`（`public` + `invoke` / `browse`）：返回 403，错误码 `public_is_official_only`；`visibility` 仍为 `private`、`public_rung` 仍为空 | T1、T2、T3 |
| A2 | 官方命名空间的 skill 仍能公开与收回 | 以官方账号对 `skillmaster` 下的 skill 设 `public` + `invoke`：204，读到 `visibility=public`、`public_rung=invoke`；再设 `private`：204，`public_rung` 为空 | T4、T12 |
| A3 | 控制台非官方设置页没有公开选项，且有解释；官方仍有三档 | 非官方 skill 的设置页只有「私有」，并出现「公开目前只对官方 skill 开放」；官方 skill 的设置页仍有「私有 / 公开 · 调用 / 公开 · 浏览」 | T5、T13 |
| A4 | `can_publish_public` 正确且只读 | 作者面响应里：官方命名空间的 skill 为 `true`，非官方为 `false`；请求体里伪造它不影响服务端判定；响应只新增字段，既有字段形状不变 | T6 |
| A5 | 存量迁移后，非官方公开行为零，官方公开不受影响 | 迁移后查库：`official = false` 的命名空间里 `visibility='public'` 的行数为 0；官方两条仍为 `public` 且 `public_rung` 不变 | T7 |
| A6 | 迁移后非官方 skill 不再出现在公域 | `skmr skills search "<它的名字>" --scope public --semantic=false` 不再返回它；`/web/discover` 与公开标签计数也不含它；官方两条仍在 | T8 |
| A7 | 私有、共享、授权等既有行为不变 | 原有可见性相关测试全绿；私有 skill 的读取与共享行为与改动前一致 | T9、T14 |
| A8 | 设计件与源码一致，对应设计检查组全绿 | 在 `design/` 跑 `npm run build:home && npm run build:console` 后 `git status` 对两个演示件干净；设置页对应检查组全绿 | T10 |
| A9 | 文档检查全绿 | `check_docs.py`、`impact.py --check` 退出 0；`generate.py` 之后工作区干净 | T11 |

**成立条件**：A1（非官方写入被服务端拒绝）、A5（存量清零）不满足就不算交付——这一版要解决的就是「公域里不再出现没有背书的 skill」。A3 是产品可见的收口；不满足时说明服务端规则生效了但用户在界面上仍会被误导，同样不算完成。

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| 非官方公开 skill 数 | 命名空间 `official = false`、`visibility = 'public'`、未删除的 skill 行数 | 迁移后查生产库；口径固定为「按 `namespace.official` 判定」 | 0 |
| 官方公开 skill 数 | 命名空间 `official = true`、`visibility = 'public'`、未删除的 skill 行数 | 迁移前后各查一次 | 不减少（当前 2） |
| 被拒绝的公开尝试 | 非官方命名空间调用可见性接口返回 `public_is_official_only` 的次数 | 服务端日志按错误码计数 | 只作为观测，不设目标 |

## 依赖与前置

- **决策**：[ADR 0146](../../decisions/0146-public-is-platform-endorsement.md)（公开 = 平台背书）是本版的范围依据；[ADR 0037](../../decisions/0037-visibility-has-two-states.md) / [ADR 0048](../../decisions/0048-visibility-hands-out-a-rung.md) 的两态与档位不变；[ADR 0082](../../decisions/0082-public-and-private-with-my-library.md) 的库引用与休眠规则不变。
- **官方命名空间**：`skillmaster` 由 V2 种子迁移建立，属于系统账号；V35 给 `namespace` 加一列 `official` 并把它标成 `true`。**注册路径永远写 `false`**，所以官方性不是可以自己注册出来的。
- **存量盘点**：2026-11-05 只读查生产，公域 4 条，其中 2 条来自 `skillmaster-test`；产品负责人确认它是测试账号，可以清掉或转私有。
- **设计权威**：设置页的选项与文案以 [`design/`](../../../design/README.md) 为准；本版先改设计件，再改前端。
- **排除的前置**：不需要先做组织模型、审批队列或开源同步；本版只关闭入口。

# v47-welcome-and-consent · 技术设计

> **最后更新**：2026-11-05
> **状态**：设计稿
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。

## 架构

本版有两个互不依赖的改动点，都在「新用户 / 公域 skill」这条线上：

```
注册 (POST /web/register)
  WebAccountController
    └─ RegisterAccountUseCase            # 事务：M1 账号 + M4 命名空间 + M10 审计
         ↓ 提交
    └─ WelcomeLibraryUseCase             # 独立事务：清单来自配置 → M4 解析命名空间 → M12 读 standing → M12 写库引用
         ↓ 失败只记日志，不改变注册结果
    201

skmr skill invoke <地址>
  GET /api/v1/skills/{ns}/{name}/metadata
    └─ ReadSkillUseCase.metadata         # M12 accessFor（多带一个 source）→ M9 版本
    └─ SkillMetaResponse.of              # invoke_requires_confirmation = (source == null)
  ← invoke_requires_confirmation
       ├─ false          → 继续 GET 正文
       └─ true 且无 --yes → 不取正文，退出 1，给两条命令
```

**什么在服务端、什么在客户端，为什么这么分**：确认的**策略**在服务端——「这个调用者要不要先确认」由 M12 已经算出的库关系决定；确认的**交互**在 CLI——只有 CLI 知道自己在加载一个 skill。服务端不新增拒绝（[ADR 0143](../../decisions/0143-public-skills-need-confirmation-in-the-cli.md) §决定 1）。

**`skmr startup` 不走这道确认**：共享的取正文函数接一个 `confirm` 开关——`invoke` 传 `!yes`，`startup` 传 `false`。它是安装旅程，不是用户发起的 skill 加载，地址写死为 `skillmaster/skillmaster-startup`。

## 数据模型

**没有新表、没有迁移。** 两处都用现有结构：

1. 默认库写入 `skill_library_join`（M12，[ADR 0082](../../decisions/0082-public-and-private-with-my-library.md)）：

```sql
-- 既有表；UNIQUE (user_id, skill_id) 就是幂等的来源
INSERT INTO skill_library_join (user_id, skill_id, created_at)
VALUES (:userId, :skillId, :at)
ON CONFLICT (user_id, skill_id) DO NOTHING;
```

2. `source` 不落库、不新增列。它由 `SkillPredicates.IDENTIFIED` 的 CTE 一次算出（created / shared / joined / null），`SkillRepository.permitted` 的 `SELECT` 多取这一列即可；再加一次查询会让同一次读里的两个答案有机会不一致。

**默认清单来自配置，不是表结构、也不是代码常量**（[ADR 0144](../../decisions/0144-a-new-account-starts-with-the-official-skills.md) §决定 5）：

配置键是 `skillmaster.welcome.skills`（逗号分隔的「命名空间/名字」）。**默认值与当前值不在这里复述**——生成视图 [`docs/current/config.md`](../../current/config.md) 是它的家。

- `SkillmasterProperties` 增加 `Welcome(List<String> skills)`；紧凑构造器逐条 trim、拒绝地址里的空白、校验恰好一段 `/`、两段非空、不带 `@`，写错抛 `IllegalArgumentException`，启动失败。
- `config/WelcomeConfig` 把它转成 usecase 层的 `WelcomeSkills` bean（形状同 `RankingConfig` / `DiffConfig`），注册链路不直接读 `SkillmasterProperties`。
- 配置里写了、但运行时不存在或不可加入的地址，在注册时跳过并记日志——和「清单为空」是同一种降级。

## 接口

### 服务端

| 方法 | 路径 | 变化 | 鉴权 |
|---|---|---|---|
| `GET` | `/api/v1/skills/{ns}/{name}/metadata` | 响应新增只读布尔 `invoke_requires_confirmation` | 既有 `invoke` 档 |
| `GET` | `/api/v1/skills/{ns}/{name}` | 同上，一个字段 | 既有 `browse` 档 |
| `POST` | `/web/register` | 契约不变；注册成功后库里多两条引用 | 匿名 + 短信码 |

新字段的取值：

| 调用者与这个 skill 的关系 | `source`（内部） | `invoke_requires_confirmation` |
|---|---|---|
| 我创建的 | `created` | `false` |
| 共享给我的 | `shared` | `false` |
| 我加入的 | `joined` | `false` |
| 只是因为公开可见 | 无 | `true` |

**旧服务端兼容**：字段缺省为 `false`，旧服务端上 CLI 的行为与今天一致。这是显式布尔而不是让客户端从 `source` 推导的原因（[ADR 0143](../../decisions/0143-public-skills-need-confirmation-in-the-cli.md) §理由）。

### CLI

```bash
skmr skill invoke <命名空间>/<名字>[@版本] [--offset <行号>] [--yes]
```

`--yes` 是接受的语法，但**不出现在帮助里**——它只在拒绝的两条命令里被打印，以及分块续读的续读命令里（ADR 0143 / ADR 0128 的理由）。作者面的行 id 也过同一道闸；消费者面答不出（草稿、下线、当前版本已撤）时放行——那些状态按 ADR 0082 不属于公域，读的是作者面自己的授权（ADR 0143 §决定 5）。

| 情况 | 行为 |
|---|---|
| `invoke_requires_confirmation = false` | 与今天一致；`--yes` 是 no-op |
| `= true` 且没有 `--yes` | 不取正文；stderr 输出下面四行；退出码 **1** |
| `= true` 且有 `--yes` | 正常加载；不写库、不记「已同意」；版本钉住照旧 |

拒绝时的输出（地址一栏是调用者写的那个；`join` 那条用去掉 `@版本` 的裸地址）：

```text
<地址> 是公开 skill，不在你的库里，这次没有加载。
代用户执行时，请用户二选一：
  只这一次（现在加载，下次仍会确认）：  skmr skill invoke <地址> --yes
  收进你的库（引用，不复制），以后不再确认：  skmr skill join <裸地址> --yes
```

**错误形状**：沿用 [ADR 0087](../../decisions/0087-cli-exit-codes-are-a-public-contract.md) 的退出码；确认被拒是普通失败（1），不是用法错误（2）。文本走 stderr，stdout 不出现正文——这样 `> file` 的调用者不会拿到半份 skill。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 服务端：`source` 进 `Access`、两个响应加 `invoke_requires_confirmation`；`WelcomeLibraryUseCase` 与注册接线 | A1–A3、A10、A11 |
| P1 | CLI：`invoke --yes`、拒绝输出、`skill join` 帮助写明「以后不再确认」；平台 skill 正文 | A4–A9、A12 |

**P0 明确不做**：不改任何服务端拒绝语义；不给默认库做配置或管理面；不补存量账号。
**P1 明确不做**：不拦 `read` / `cat` / `head` / `tail` / `grep`；不让 `--yes` 写库；不改 `startup` 的加载。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 存量账号什么时候补官方 skill？ | 本版不补（[ADR 0144](../../decisions/0144-a-new-account-starts-with-the-official-skills.md) §决定 4）；若之后有证据表明老用户找不到，再做一次性迁移 | 看有没有「老用户搜不到官方指南」的实际反馈；没有就不做 |
| Q2 | 默认加入失败只有日志，够不够？ | 够——它不影响注册，用户仍可自己 `join` | 等出现「注册了却没进库」的实际反馈，再加可观测性 |
| Q3 | 将来要不要也拦 `read` 等读命令？ | 不拦；`invoke` 才是「加载来用」 | 出现绕开 `invoke` 误用公域内容的证据时，按 ADR 0143 的形状扩 |
| Q4 | 确认被拒要不要单独的退出码？ | 不单独开；复用普通失败（1） | 退出码是公开契约（[ADR 0087](../../decisions/0087-cli-exit-codes-are-a-public-contract.md)），没有「调用方需要按码分流」的证据就不加 |

## 附录

### 附录 A · 与现有代码的关系

| 位置 | 动作 |
|---|---|
| `WebAccountController.register` | 注册成功后调用默认库用例；会话建立仍在最后 |
| `resources/application.yml`、`config/SkillmasterProperties`、`config/WelcomeConfig` | 新增 `skillmaster.welcome.skills` 与对应的 `Welcome` 记录、`WelcomeSkills` bean；格式校验在启动时 |
| 新 `usecase/WelcomeLibraryUseCase` | 遍历配置清单 + 解析命名空间 + 逐个 `standing` + `library.join`；异常只记日志 |
| `modules/skill/Access`、`internal/Permitted` | 增加 `source`（不落库） |
| `modules/skill/internal/SkillRepository.permitted` | `SELECT` 多取 `s.source` |
| `modules/skill/SkillMetadataService.accessFor` | 把 `source` 填进 `Access` |
| `modules/distribution/SkillDetail` | 增加 `source`，由 `toDetail` 从 `Access` 带过来 |
| `api/SkillDetailResponse`、`api/SkillMetaResponse` | 增加 `invoke_requires_confirmation`（由 `detail.source() == null` 决定） |
| `skillmaster-cli/internal/api/client_resources.go` | `Metadata` 增加 `InvokeRequiresConfirmation bool` |
| `skillmaster-cli/cmd/skillmaster/invoke.go` | 解析 `--yes`；在取正文前判断；作者面的行 id 也走同一道判断；分块续读的命令带上 `--yes` |
| `skillmaster-cli/cmd/skillmaster/flagattribution_test.go` | 把 `skill invoke` 的 `--yes` 登记为「帮助不宣传」 |
| `skillmaster-cli/cmd/skillmaster/declarations_skill.go` | `skill invoke` 的帮助**不列** `--yes`；`skill join` 的 `--yes` 写明「以后不再确认」 |
| `skills/skillmaster/SKILL.md` | 官方地址的示例带 `--yes` |
| `docs/current/cli.md` | 由 `scripts/docs/generate.py` 重出，不手改 |
| `docs/current/TODO.md` | 两条搬进本 PRD 后删除 |

### 附录 B · 来源与出处

- 两个官方 skill 的地址与在线状态：`skmr skill show skillmaster/skillmaster-startup`、`skmr skill show skillmaster/skillmaster-guide`（2026-11-05 查得，guide 1.0.1 / startup 1.0.0）。
- 「默认只搜库」：`skillmaster-cli/cmd/skillmaster/search_query.go` 的 `parseQueryArgs`（`options.scope = api.ScopeLibrary`）。
- 「公开即可调用」：[ADR 0082](../../decisions/0082-public-and-private-with-my-library.md) §决定 2。

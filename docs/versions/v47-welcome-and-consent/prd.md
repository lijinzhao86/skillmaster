# v47-welcome-and-consent · 产品需求

> **最后更新**：2026-11-05
> **所属版本**：[`README.md`](README.md)
> **来源待办**：「注册后把官方指南默认收进新用户的库」「公共 skill 加载先经同意」两条已从 [`docs/current/TODO.md`](../../current/TODO.md) 搬进本版并删除，历史留在本文件与 [`test-plan.md`](test-plan.md)。

## 用户与场景

**谁在用**：刚注册 SkillMaster 的新用户，以及替他们执行命令的 agent；还有搜到公域 skill 后想先用一下、或想收进库的老用户。

**在什么场景下用**：

- 新人装好 `skmr`，注册完，想知道「这个产品怎么用」——`skmr skills search "怎么建 skill"` 默认只搜自己的库。
- agent 搜到一条公域 skill，准备 `skmr skill invoke` 加载它来用；用户未必知道这条 skill 来自别人的命名空间。
- 用户同意用一次之后，决定以后都不再被问。

**现在的替代做法是什么**：

- **新人靠介绍页或别人的转述拿到地址**：注册后库是空的，官方指南不在库里；`skills search` 默认又只搜库，所以新人搜不到它，只能先知道 `skillmaster/skillmaster-guide` 这个地址。
- **公域 skill 直接加载，中间没有人被问过**：agent 拿到搜索结果就 `invoke`；唯一的护栏是提示词里那句「加载前先问用户」，agent 可以不听，也没有任何机制能让它停下来。

## 现在的问题、诱因与改法

### 问题 1：新账号的库是空的，而默认搜索只搜库

**现在的流程**：

```text
注册 → 库里什么也没有
skmr skills search "怎么建 skill"        # 默认 scope=library
→ 没有匹配的 skill
```

**问题**：新用户搜不到官方指南，而官方指南正是告诉他「接下来怎么做」的那一份。得先有人把地址给他，他才能 `skmr skill join skillmaster/skillmaster-guide --yes`。

**为什么会这样**：注册只写账号与个人命名空间，不初始化库引用；而「默认只搜库」是 [ADR 0082](../../decisions/0082-public-and-private-with-my-library.md) 定对了的——公域无界，不能整表列。缺的是「新账号的库里先有什么」。

**怎么改能解决**：注册成功后，平台把两个官方 skill 的库引用插进新账号的库（引用，不是复制）。幂等；skill 不存在或不可加入时跳过；这一步失败不影响注册。用户仍可自己 `leave` 掉。默认清单放在服务端配置 `skillmaster.welcome.skills`（逗号分隔的「命名空间/名字」）里，以后加新的官方 skill 只改配置，不改代码。

### 问题 2：公域 skill 调用前没有人被问过

**现在的命令**：

```bash
skmr skill invoke <命名空间>/<名字>
```

**问题**：只要服务端发得出票（公开、有当前版本、未下线），这条命令就会加载。agent 搜到一条公域 skill 就直接用了；用户可能根本不知道它的来源。

**为什么会这样**：[ADR 0082](../../decisions/0082-public-and-private-with-my-library.md) §决定 2 明确「加入是组织动作，不是权限动作」，未加入的公开 skill 可以直接 `invoke`；[ADR 0048](../../decisions/0048-visibility-hands-out-a-rung.md) 说公开就是给所有人一张票。这两条都对，缺的是一道**交互轨**：不改变谁能读，只改变 CLI 在加载前停不停。

**怎么改能解决**：服务端在 `/metadata` 与 `/detail` 上给出只读布尔 `invoke_requires_confirmation`（公域且调用者没有库关系时为 `true`）。CLI 读到 `true` 且没带 `--yes` 时，不加载正文，退出码 1，输出两条命令：

```text
<地址> 是公开 skill，不在你的库里，这次没有加载。
代用户执行时，请用户二选一：
  只这一次（现在加载，下次仍会确认）：  skmr skill invoke <地址> --yes
  收进你的库（引用，不复制），以后不再确认：  skmr skill join <裸地址> --yes
```

`--yes` 只对本次生效：不写库、不记「已同意」，`invoke` 本来就要写的版本钉住照旧；「以后不再确认」只能靠 `skill join`。`--yes` 不进 `invoke` 的帮助，只出现在拒绝的这两条命令里，以及分块续读时那一条续读命令里（[ADR 0128](../../decisions/0128-cli-help-does-not-advertise-the-confirmation-switch.md) 的理由）。确认发生在 `invoke`，`read` / `cat` / `head` / `tail` / `grep` 不拦。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | 注册成功后把 `skillmaster/skillmaster-startup` 与 `skillmaster/skillmaster-guide` 的库引用加入新账号 | 服务端；清单来自 `skillmaster.welcome.skills` 配置；引用、幂等、失败不影响注册 |
| 2 | `/metadata` 与 `/detail` 新增只读布尔 `invoke_requires_confirmation` | 服务端 API |
| 3 | `skmr skill invoke` 新增 `--yes`（不进帮助，只在拒绝里出现）；公开且未加入时拒绝并给两条命令；`skill join` 的帮助写明「以后不再确认」 | CLI |
| 4 | 平台 skill 正文：官方地址的示例带 `--yes` | `skills/skillmaster/SKILL.md` |
| 5 | 两条 ADR、本版四件套、从 TODO 删掉两条 | 文档 |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 存量账号补官方 skill | 不静默改动已有用户的库；他们仍可自己 `join`（产品负责人 2026-11-05 定） | 有证据表明老用户确实找不到官方指南时，做一次明确的一次性迁移 |
| 2 | 拦 `read` / `cat` / `head` / `tail` / `grep` | 它们是显式读文件，不是「加载来用」；裸地址读 `SKILL.md` 已被要求先 `invoke`（产品负责人 2026-11-05 定） | 出现绕开 `invoke` 误用公域内容的证据 |
| 3 | `--yes` 顺带入库 | 一次性与入库是两个选项，由用户选（产品负责人 2026-11-05 定） | 不做——这就是设计 |
| 4 | 控制台界面改动 | 本版没有新的控制台操作；库里多出的两条「我加入的」是数据，不是新界面 | 不做 |
| 5 | 改 [ADR 0048](../../decisions/0048-visibility-hands-out-a-rung.md) 的权限模型 | 服务端仍然「公开即可调用」；确认是 CLI 的交互轨，不是门禁 | 不做 |
| 6 | 默认清单的管理界面 | 改配置就够；做一个管理界面是另一类工作 | 不做 |
| 7 | 本机记住「已同意」 | 那是第二份库状态；「以后不问」只能靠 `skill join` | 不做 |

## 控制台操作与 CLI 对应

本版**不新增、不改变任何控制台操作**。注册后库里多出的两条「我加入的」是数据；用户要把它们移出，用已有的 `skmr skill leave <地址> --yes`（[`docs/current/cli.md`](../../current/cli.md)）。因此本版没有需要补齐的「控制台操作 → CLI 命令」映射，也没有例外。

## 拥有面

| 要动的文件 / 面 | 是不是共享面 | 谁在用 |
|---|---|---|
| `RegisterAccountUseCase` 之后的默认加入步骤；新用例 | 否 | 注册链路 |
| `application.yml` 的 `skillmaster.welcome.skills`；`SkillmasterProperties` 与 config 层的 bean | 否 | 服务端启动与注册链路 |
| M12：`Access` / `Permitted` / `SkillRepository.permitted` / `SkillMetadataService` | 否 | 内容读、库、搜索 |
| `SkillDetail` / `SkillDetailResponse` / `SkillMetaResponse` | **是**（API 契约） | 控制台与 CLI 都读 |
| CLI：`invoke`、`skill join` 的命令声明、`flagattribution_test`、帮助与金样测试 | 否 | CLI |
| `skills/skillmaster/SKILL.md` | 否（但改了要重新发布平台 skill 才生效） | agent 的常驻规则 |
| `docs/decisions/`、`docs/versions/v47-welcome-and-consent/`、`docs/current/TODO.md` | 否 | 文档 |
| `design/` | 无改动 | — |

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| A1 | 新账号注册成功后，库里立刻有两条官方 skill 的引用，来源是「我加入的」 | `POST /web/register` 返回 201 后，`skill_library_join` 有对应两行；`GET /api/v1/skills?scope=library&show=all` 列出两个名字且 `source=joined` |
| A2 | 新账号默认搜索能命中官方指南 | 新账号执行 `skmr skills search "SkillMaster 官方使用指南" --semantic=false`（不加 `--scope`，即默认搜库），结果含 `skillmaster/skillmaster-guide` |
| A3 | 注册时官方 skill 不存在或不可加入，不影响注册 | ① 在没有造这两个 skill 的库上注册，仍返回 201、库里没有对应条目；② 造一个存在但没有当前版本的公开 skill 再注册，同样 201、没有条目 |
| A4 | 公域且不在库的 skill，`invoke` 不加载并给出两条命令 | 退出码 1；stderr 同时含 `skmr skill invoke <地址> --yes` 与 `skmr skill join <地址> --yes`；stdout 没有正文 |
| A5 | `--yes` 只对本次生效，不写库 | 带 `--yes` 能加载，版本钉住照旧；库引用仍不存在；紧接着不带 `--yes` 再调，仍按 A4 拒绝 |
| A6 | 收进库之后不再确认 | `skmr skill join <裸地址> --yes` 后，`skmr skill invoke <地址>` 直接加载 |
| A7 | 自己创建的、共享给我的、已加入的 skill 都不确认 | 三种 `source` 各取一例，`invoke` 都不停 |
| A8 | 官方 skill 在 CLI 侧不豁免 | 把官方 skill `leave` 出库后，`invoke` 按 A4 拒绝；带 `--yes` 可过。平台 skill 正文对官方地址给出的是提示词层预授权（agent 直接带 `--yes`），不是 CLI 的例外 |
| A9 | 读命令不受影响 | `skmr skill read` / `cat` / `head` / `tail` / `grep` 一个公开 skill 的文件，行为与改动前一致 |
| A10 | `invoke_requires_confirmation` 正确 | 公域未加入为 `true`；`created` / `shared` / `joined` 三种为 `false`；`/metadata` 与 `/detail` 一致；既有字段不变 |
| A11 | 默认加入幂等 | 对同一账号重复执行默认加入，不报错、不产生第二行 |
| A12 | 平台 skill 正文写清了官方地址可以带 `--yes` | 人工读 `skills/skillmaster/SKILL.md`，官方地址的示例带 `--yes` |

**成立条件**：A4、A5 不满足就不算交付——这一版要解决的就是「用户能在中间被问一次，而且问的方式不偷偷改变库」。

### 指标

本版没有新增的可采集指标。`skills search` 的命中与 `invoke` 的拒绝都不上报（跨会话的检索与调用痕迹是另一条待办），所以判断只靠上面的验收。

## 依赖与前置

- **外部依赖**：生产环境上 `skillmaster/skillmaster-startup` 与 `skillmaster/skillmaster-guide` 必须是公开、已发布、未下线的 skill；缺失时默认加入退化为 no-op，注册不受影响。
- **内部前提**：
  - [ADR 0048](../../decisions/0048-visibility-hands-out-a-rung.md)：可见性只是发一张票，不是门禁。
  - [ADR 0082](../../decisions/0082-public-and-private-with-my-library.md)：加入是引用；本版由 [ADR 0143](../../decisions/0143-public-skills-need-confirmation-in-the-cli.md) 部分取代其 §决定 2 的「不挡加载」。
  - [ADR 0087](../../decisions/0087-cli-exit-codes-are-a-public-contract.md)：确认被拒是普通失败（1）。
  - [ADR 0128](../../decisions/0128-cli-help-does-not-advertise-the-confirmation-switch.md)：确认开关不进帮助；`invoke` 沿用它的理由。
  - [ADR 0144](../../decisions/0144-a-new-account-starts-with-the-official-skills.md)：默认库清单与幂等要求。
- **排除了什么前置**：不需要控制台或设计件改动；不需要新的权限、scope 或数据表；不需要存量账号迁移。

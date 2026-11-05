# v48-official-only-public · 技术设计

> **最后更新**：2026-11-05
> **状态**：第 3 关已定稿，**已实现**（结果见 [`test-plan.md`](test-plan.md) §结果）
> **所属版本**：[`README.md`](README.md)

## 架构

相对 [`docs/current/`](../../current/README.md) 的差异：**没有新模块、没有新路由**。新增一列 `namespace.official`，改动落在可见性写入路径、一个作者面响应的只读字段，以及一条一次性迁移。

**官方性是一个命名空间上的列，不是一个字符串。** 命名空间的 slug 就是账号的 handle，注册允许人自己选；所以「官方」不能来自一份 slug 配置——否则运维把 `partner` 加进清单而该名字还没被平台占住时，任何人都可以注册它并拿到公开权。`namespace.official` 只由迁移/种子写，注册路径不碰它。

**一个判据，两个消费者。** 写路径与读路径都读同一个 `Namespace.official()`：

```text
PATCH /web/skills/{ns}/{name}/visibility
  → UpdateSkillVisibilityUseCase.update
      → validateAndNormalise（现有：值域、private 时档位必须为空）
      → namespaces.bySlug（现有：解析命名空间，带上 official）
      → SkillSharingService.setVisibility(..., namespace.official())
          → permittedForAdministration（现有：看不见 → 空 → 404；看得见但不够 admin → 403）
          → public 且 !publicAllowed → PublicIsOfficialOnlyException → 403
          → 写两列
      → audit.record（现有）

GET 作者面 / 页面读回
  → ReadAuthoredSkillUseCase.authored
      → AuthoredSkill(..., canPublishPublic = namespace.official())
      → AuthoredSkillResponse
```

- **判定在 `SkillSharingService.setVisibility` 里，但在 admin 档之后。** 这是本设计最容易写反的一处：如果先判命名空间再判档位，一个看不见这个 skill 的调用者会收到 `public_is_official_only`，而现有契约要求 404；一个看得见但只有 `browse` 的调用者会收到策略错误，而现有契约要求 403 `forbidden`。顺序是：404 → 403 档位 → 403 策略。测试 T14 钉的就是这个顺序。
- **`private` 永远放行**，包括非官方命名空间；只有 `public` 过官方判定。这样迁移前后的历史行、以及将来收回公开，都不会被拦住。
- **读路径与写路径用同一个列**，所以页面上的能力与接口的拒绝不会出现「页面说能点、服务端说不行」。
- 决定见 [ADR 0146](../../decisions/0146-public-is-platform-endorsement.md)；它只补「谁能公开」，[ADR 0048](../../decisions/0048-visibility-hands-out-a-rung.md) 的档位与 [ADR 0037](../../decisions/0037-visibility-has-two-states.md) 的两态都不变。

## 数据模型

**没有新表；新增一列。**

`namespace.official BOOLEAN NOT NULL DEFAULT FALSE`：这个命名空间里的 skill 能不能设为公开。迁移/种子把它设为 true；注册创建的命名空间用默认值 false。

**一条一次性迁移** `V35__public_is_official_only.sql`：

```text
ALTER TABLE namespace ADD COLUMN official BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE namespace SET official = TRUE WHERE slug = 'skillmaster';
UPDATE skill s SET visibility = 'private', public_rung = NULL
  FROM namespace n
 WHERE s.namespace_id = n.id AND s.visibility = 'public' AND n.official = FALSE;
```

- **只转私有，不删内容**：`skill`、`skill_version`、`skill_library_join` 都不动。已加入的库条目按 [ADR 0082](../../decisions/0082-public-and-private-with-my-library.md) §决定 6 休眠。
- **迁移与运行时读的是同一个事实**：清理条件 `n.official = FALSE` 与线上判定读的是同一列。迁移里写死 `skillmaster` 只是「本次升级把种子里的那个命名空间标成官方」，不是另一条判定规则；将来加官方命名空间是另一次迁移/种子，不是改配置。
- **迁移不写审计事件**：它没有操作主体，写一条系统事件会污染审计流。条数记在 PR 描述与 [`test-plan.md`](test-plan.md) §结果。

## 接口

### `PATCH /web/skills/{namespace}/{name}/visibility`

形状不变。行为新增一条拒绝：

| 条件 | 状态 | 错误码 | 说明 |
|---|---|---|---|
| `visibility = private`，`public_rung` 为空 | 204 | — | 永远允许；原行为 |
| `visibility = public`，命名空间 `official = true`，档位是 `invoke` / `browse` | 204 | — | 原行为 |
| `visibility = public`，命名空间 `official = false`，调用者能管理它 | **403** | **`public_is_official_only`** | 新增；不写任何值 |
| 值域不对、`private` 带了档位、`public` 没带档位 | 400 | `invalid_request`（现有） | 原行为 |
| skill 不在，或调用者看不见它 | 404 | `skill_not_found`（现有） | 原行为；不透露存在性 |
| 看得见但调用者不够 `admin` | 403 | `forbidden`（现有） | 原行为；档位问题，不是命名空间策略 |

**403 而不是 400**：请求本身是合法的，拒绝来自「这个命名空间不能做这件事」；**也不是 404**：skill 已经在调用者的作者面里，说「没有这个地址」是假话。与 `NotPermittedException` 的 403 同一条理由，但错误码不同——客户端要能区分「档位不够」和「命名空间不能公开」。

实现上新增 `PublicIsOfficialOnlyException`（与 `NotPermittedException` 同包），在 `ApiExceptionHandler` 里映射为 403 + `ErrorCode` 的新增项 `PUBLIC_IS_OFFICIAL_ONLY`。错误消息用英文（与现有服务端消息一致），控制台按错误码给自己的中文句子。

### 作者面响应新增只读布尔 `can_publish_public`

- Java 侧：`AuthoredSkill` 与 `AuthoredSkillResponse` 各加一个 `boolean canPublishPublic`；线名由 `SNAKE_CASE` 策略生成为 `can_publish_public`。
- 值：`namespace.official()`。这是一个**命名空间级**的能力，不是调用者的档位；同一命名空间下的 admin 与 owner 读到同一个值。
- 旧客户端忽略未知字段；新客户端只读它，不按命名空间硬编码。**新客户端遇到旧服务端**（字段缺失）按「可以公开」渲染，因为旧服务端对任何命名空间都还允许公开；只有显式的 `false` 才隐藏公开选项。

### 控制台

`SettingsTab` 用 `skill.can_publish_public !== false` 决定摆不摆公开两档。**一个防御态**：读到 `can_publish_public = false` 而 skill 当前是 `public`（只在滚动部署窗口里旧实例刚写进去、或有人手工撤掉标记时出现），页面在选项上方补一句「这个 skill 目前是公开的，但所在命名空间已不能再设公开。改回私有不影响已经发生的下载与复制。」，并保留私有一项让作者收回。不画灰掉的公开项。

### CLI

**没有代码改动。** `skmr skill visibility <地址>` 仍是打开网页由人确认（[ADR 0126](../../decisions/0126-page-only-actions-open-the-page.md)），页面按 `can_publish_public` 渲染。CLI 的帮助、退出码与输出形状都不变。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | `namespace.official` 列与 `Namespace` / 仓储读取；`PublicIsOfficialOnlyException` 与 403 映射；`UpdateSkillVisibilityUseCase` 传标记；`AuthoredSkill` / `AuthoredSkillResponse` 的 `can_publish_public`；Flyway 迁移 | A1、A2、A4、A5、A6 |
| P1 | 控制台 `SettingsTab` 按能力渲染（含防御态）；`api/types.ts` 加字段；错误码的中文句子 | A3、A8 |
| P2 | 回归测试、生成视图（`errors.md` / `schema.md`）、文档与目标态差异清单 | A7、A9 |

P0 与 P1 可以在同一条分支里先后落地；P1 依赖 P0 的字段，但不依赖迁移。

## 开放问题

需求阶段留下的问题都已在第 3 关定下；本关新增的两条也已定，不留悬空项。

| # | 问题 | 结论 / 当前倾向 | 状态 |
|---|---|---|---|
| Q1 | 官方判定放在配置允许清单还是代码常量？ | **都不是：放在 `namespace.official` 列。** 配置里的 slug 清单不安全（slug 是用户可注册的 handle）；代码常量无法表达「将来再加一个官方命名空间」。列由迁移/种子写，注册不碰它 | 已定 |
| Q2 | `can_publish_public` 放在作者面响应，还是单独一条能力路由？ | 作者面响应。设置页本来就要读它，一次读全；单独路由会多一次请求，也会多一个要同步的判据 | 已定 |
| Q3 | 非官方但历史上仍是 `public` 的行，页面读到怎么显示？ | 迁移在应用开始接受请求之前跑完，正常情况下不可达；滚动部署窗口里可达。控制台保留一句说明与「私有」一项，让作者收回，不画灰选项 | 已定 |
| Q4 | 迁移要不要写审计事件？ | 不写。迁移没有操作主体；条数记在 PR 与 test-plan | 已定 |
| Q5 | 官方判定放在 use case 还是 `SkillSharingService`？ | 判定本身放在 `SkillSharingService.setVisibility`，**在 admin 档之后**；use case 只把 `namespace.official()` 传进去。先判档位再判策略，404/403 的既有契约才不会被新策略盖掉 | 已定 |
| Q6 | `namespace.visibility` 要不要一起挡？ | 今天没有写入路径，本版不改；将来若有，判定用同一个 `official` 列 | 已定 |

# v53-api-key-expiry · 需求

## 用户与场景

**在控制台给自己签 API 密钥的人。** 产品面向不熟悉技术的用户；密钥是 CLI 与 agent 访问平台的凭据，签发一次之后通常被贴到家里的机器、开发机或 CI 上长期使用。

今天的选择只有「90 天内的某一天」：想要一年，得让运维改部署配置；想要不过期，得关掉整个部署的过期开关，而这会让**所有人**的新密钥都不过期。用户真正想说的是「这把 key 用在哪、该活多久」，而不是「请给我一个日期」。

三个具体场景：

1. **家里那台机器**：贴一次，希望至少一年不用回来续期。
2. **CI / 流水线**：希望不过期，避免某个半夜流水线突然 401；出问题时靠撤销而不是到期。
3. **临时试用**：30 天足够，过期就换。

运维侧则希望保留一个总闸：这个部署允不允许不过期；最长寿命由产品统一固定为 365 天。

## 范围

### 做

1. **期限选项**：新建表单的日期选择器改为期限下拉，档位 **30 / 60 / 90 / 365 天、自定义日期、不过期**；默认预选 **90 天**。
2. **不过期的语义**：选中「不过期」签出的密钥 `expires_at` 为 `NULL`；列表显示「永不过期」；可以正常调用。只有部署允许时才出现这一档；选中时表单给出「不建议」的提示，并隐藏日期框。
3. **自定义日期**：仍是「哪一天结束」（上海日），上限是产品的第 365 天；超过第 365 天的日期被拒绝。档位是产品固定的六档，不随部署变化。
4. **上限与开关**：期限上限**固定 365 天**，是产品常量，不是部署配置；默认 90 天也是产品常量。部署唯一的有效期开关是**是否允许不过期**（默认允许）。
5. **续期**：有日期的密钥按默认 90 天重算（现行规则不变）；**不过期的密钥续期后仍不过期**（部署仍允许时；不再允许时拒绝，并提示新建一把带日期的）。
6. **自动撤销**：所有未撤销、且 **365 天未使用**的密钥由每日任务自动撤销——保留行、标 `revoked_at`，列表显示「已撤销」。「使用」以 `last_used_at` 为准；从未使用过的以 `created_at` 为准。阈值按连续 365×24 小时算，不按到期日。
7. **契约与文档**：接口能表达「这一把永不过期」；配置文档、M02 模块页与现有 ADR 的收窄按本版更新；新增一条 ADR 记这次取舍反转。

### 不做

| 不做 | 理由 | 什么时候可能做 |
|---|---|---|
| 到期前的邮件提醒 | 没有邮件通道；控制台已有「两周内到期」计数 | 有通知通道时 |
| 自动撤销前的提前通知 | 同上，没有可用的通知出口 | 有通知通道时 |
| 修改已有密钥的有效期 | 现在只能续期换新；编辑要新增接口与审计 | 有明确需求时 |
| 7 天档 | 比「两周内到期」窗口还短，对续期节奏没有意义 | 用户提出时 |
| 对已有密钥追溯新配置 | 改 `allow-no-expiry` 不溯及已有凭据（[ADR 0039](../../decisions/0039-api-key-defaults-and-their-edges.md) §决定 1） | 不计划 |
| CLI 侧 API key 管理（签发、列出、续期、撤销） | 四件事现在都只在控制台（[ADR 0040](../../decisions/0040-api-key-in-the-cli-now-the-browser-path-later.md)）；产品负责人 2026-11-09 确认的例外。**本版已评估：CLI 零改动**——`skmr auth status` 已把 `expires_at: null` 显示为「不过期」，CLI 无签发/续期/撤销命令，401 已区分 `token_expired` / `token_revoked`。**签发已记 [TODO](../../current/TODO.md)**；列出/续期/撤销与它一并评估 | 后续开版做签发；已记 TODO |

## 拥有面

| 要动的文件 / 面 | 是不是共享面 | 谁在用 |
|---|---|---|
| `skillmaster-server/.../modules/token/ApiKeyPolicy.java`、`usecase/IssueApiKeyUseCase.java`、`usecase/RenewApiKeyUseCase.java` | 否 | M2 与签发路径 |
| `skillmaster-server/.../api/WebKeyController.java`、`ApiKeysResponse.java` | 否 | 控制台 |
| `skillmaster-server/.../config/SkillmasterProperties.java`、`src/main/resources/application.yml` | **是**（与 `feat/service-credentials` 重叠） | 全局配置 |
| 自动撤销任务（新增）及其测试 | 否 | 后台 |
| `skillmaster-console/src/components/key/KeyCreateForm.tsx`、`src/api/types.ts`、`src/api/errors.ts` | 否 | 控制台密钥页 |
| `design/src/console/pages/KeysPage.tsx`、`design/src/console/fixtures/keys.ts` | 否 | 设计件 |
| `design/README.md` | **是**（与 `feat/service-credentials` 重叠） | 全控制台 |
| `design/src/console/styles.css`、`design/voice-spec.md` | **是**（评估过，未改：沿用既有 `.choice-warn` 与名词表） | 全控制台 |
| 生成物 `design/skillmaster-控制台演示.html` | 生成 | 设计检查 |
| `docs/versions/v53-api-key-expiry/`、本版新增 ADR | 否 | 本版 |
| `docs/current/modules/M02-token-and-as.md` | 否 | 模块现状 |
| `docs/current/config.md` | 生成 | 由 `application.yml` 重出 |
| CLI | 否 | **不改**：已评估，见 §不做 |

## 控制台操作与 CLI 对应

| 操作 | 界面 | CLI / API / 其他面 |
|---|---|---|
| 新建密钥并选期限 | 控制台新建表单 | `POST /web/keys`，`expires_at` 或「永不过期」标记；CLI 无签发命令（例外；后续做，已记 TODO） |
| 续期 | 控制台列表行 | `POST /web/keys/{id}/rotate`；CLI 无（例外；与签发一并评估） |
| 看见不过期 | 列表「永不过期」 | `GET /web/keys` 的 `expires_at: null`；CLI `auth status` 已显示「不过期」，**本版不改** |
| 自动撤销 | 列表「已撤销」 | 每日后台任务；无用户操作 |

## 验收与指标

| # | 验收标准 | 怎么判定 |
|---|---|---|
| A1 | 新建表单出现期限下拉，含 30/60/90/365 天、自定义、不过期；默认 90 天 | 打开表单看默认值与档位 |
| A2 | 选 365 天签出的密钥在 365 天后的上海日末到期 | 读返回的 `expires_at` |
| A3 | 选不过期签出的密钥 `expires_at` 为 null，列表显示「永不过期」，能正常调用 | 签发 + 调用 + 看列表 |
| A4 | 部署禁止不过期时，表单不出现该档；直接请求不过期被拒绝 | 改配置 + 看表单 + 调接口 |
| A5 | 上限固定 365 天：请求超过 365 天的日期被拒绝 | 直接调接口 |
| A6 | 不过期的密钥续期后仍不过期；有日期的密钥续期后是默认 90 天 | 各续期一次 |
| A7 | 365 天未使用的密钥被每日任务自动撤销，列表显示「已撤销」，调用返回 `token_revoked` | 造数据 + 跑任务 |
| A8 | 未满 365 天的密钥不被自动撤销 | 边界数据 + 跑任务 |
| A9 | 改不过期开关不改变已有密钥的 `expires_at` | 改配置前后读同一行 |
| A10 | 文档检查与生成视图一致：`check_docs.py`、`impact.py --check`、`generate.py` 后工作区干净 | 跑命令 |
| A11 | 服务端改动面测试、控制台 `npm test` 与 `type-check` 通过 | 跑命令 |
| A12 | 设计检查改动组全绿，且有人看过渲染 | 跑设计检查 + 人看 |

**指标**：本版不新增采集。自动撤销每次写一条审计（沿用现有审计出口）；上线后可从「已撤销」行的 `revoked_at` 与 `last_used_at` 量出被自动撤销的数量。

## 依赖与前置

- [ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md)、[ADR 0039](../../decisions/0039-api-key-defaults-and-their-edges.md)、[ADR 0040](../../decisions/0040-api-key-in-the-cli-now-the-browser-path-later.md)、[ADR 0042](../../decisions/0042-the-expiry-setting-is-a-cap.md) 是现行依据；本版新增 [ADR 0171](../../decisions/0171-api-key-expiry-is-a-choice.md)，收窄 0039 §决定 1 与 0042 的「一个数兼默认值与上限」。
- `api_key.expires_at` 已可空，预计不需要数据库迁移。
- 自动撤销任务沿用现有的 `@Scheduled` 基础设施，与 `UsageRetentionJob` 同形（启动 60 秒后首次、此后每 24 小时）。
- 与 `feat/service-credentials` 分支（服务凭据）共享 `application.yml`、`SkillmasterProperties.java`、`design/` 与配置文档；按所有者决定并行，合并时解冲突。

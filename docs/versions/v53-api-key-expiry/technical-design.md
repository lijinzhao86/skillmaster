# v53-api-key-expiry · 技术设计

## 架构

**这一版只动 M2（凭据）与它的两个调用方（控制台、每日任务）；表结构不变。** 取舍的理由在 [ADR 0171](../../decisions/0171-api-key-expiry-is-a-choice.md)，本文只写形状。

- **策略**：`modules/token/ApiKeyPolicy.java` 的记录里只剩 `allowNoExpiry`（这个部署是否允许「不过期」）与 `lastUsedWindow`；`MAX_LIFETIME = Duration.ofDays(365)` 与 `DEFAULT_LIFETIME = Duration.ofDays(90)` 是静态常量。`defaultExpiry(now)` 取 `now + DEFAULT_LIFETIME` 与 `latestExpiry(now)` 的较早者（90 < 365，所以总是 90 天）；`latestExpiry` / `latestDay` 用 `MAX_LIFETIME`。`defaultDays()` 给接口用，等于 90。
- **签发**：`usecase/IssueApiKeyUseCase.java` 的 `Request` 增加 `neverExpires`；三条互斥分支——`neverExpires`、没带日期、显式日期。`neverExpires` 与 `expiresAt` 同时出现是 400；部署不允许 `neverExpires` 也是 400。
- **续期**：`usecase/RenewApiKeyUseCase.java` 先读旧行。旧行 `expiresAt == null` 时保留不过期（部署仍允许）；否则按 `defaultExpiry(now)` 重算。拒绝时用 `ApiKeyRequestException.Problem.EXPIRY` / `not_allowed`。
- **自动撤销**：新增 `modules/token/StaleApiKeys.java`（M2 的系统面，不是用户面），由 `modules/token/internal/ApiKeyRegistry.java` 实现；新增 `usecase/RevokeUnusedApiKeysUseCase.java`（`@Transactional`）与 `usecase/RevokeUnusedApiKeysJob.java`（`@Scheduled`）。任务每天把 `COALESCE(last_used_at, created_at)` 早于 `now - 365 天` 的未撤销密钥标成 `revoked_at`，每把写一条 `api_key_auto_revoke` 审计（`actorUserId = null`）。事务边界在 usecase；模块只读写自己的 `api_key`。
- **接口**：`api/WebKeyController.java` 的 `IssueRequest` 增加 `neverExpires`；`api/ApiKeysResponse.Policy` 改成 `{allow_no_expiry, default_days, lifetime_days, latest_day}`。`api/ApiExceptionHandler` 把新的 `NEVER_EXPIRY` 字段映射到 `invalid_expiry`。
- **配置**：`application.yml` 删除 `api-keys.expire` 与 `api-keys.lifetime`，只留 `api-keys.allow-no-expiry`（默认 `true`）与 `api-keys.last-used-window`；`config/SkillmasterProperties.java` 的 `apiKeys` 记录随 `ApiKeyPolicy` 自动绑定，只改它的说明。
- **控制台**：`skillmaster-console/src/components/key/KeyCreateForm.tsx` 从日期框改成下拉（30 / 60 / 90 / 365 / 自定义 / 不过期）；四个预设是产品常量，部署开关只决定「不过期」是否出现；表单还读 `policy.default_days`（预选）与 `policy.latest_day`（365 档与自定义日期上限），不按本地钟重算。请求带 `never_expires`。`src/api/types.ts` 与 `src/api/errors.ts` 跟着改。
- **设计件**：`design/src/console/pages/KeysPage.tsx` 已按第 2 关改好；界面规格只写在 [`design/README.md`](../../../design/README.md) §访问凭据，本文不重复。

## 数据模型

**没有数据库迁移，也没有新列。** `api_key` 的列定义以 [`current/schema.md`](../../current/schema.md) §`api_key` 为准；本版只依赖其中四处：`expires_at`（`NULL` 即「永不过期」）、`last_used_at`、`created_at`、`revoked_at`。

自动撤销的判定是 `revoked_at IS NULL AND COALESCE(last_used_at, created_at) < :threshold`。三个时间都是 RFC3339 UTC，字符串比较即时间比较（`common/Timestamps`）。审计沿用 `modules/audit` 的 `audit_event`，不新增表。

## 接口

### `GET /web/keys`

`policy` 改成四个字段，`keys[]` 不变：

| 字段 | 类型 | 含义 |
|---|---|---|
| `allow_no_expiry` | boolean | 这个部署是否允许签发永不过期的密钥 |
| `default_days` | number | 请求没带日期时的默认天数，固定 90 |
| `lifetime_days` | number | 可签发的最长天数，固定 365 |
| `latest_day` | string | 可签发的最后一天（`YYYY-MM-DD`，上海日；上海今天 + 365 天，含当天） |

`latest_day` 来自服务端的 `ApiKeyPolicy.latestDay`，不由客户端按自己的钟重算（[ADR 0042](../../decisions/0042-the-expiry-setting-is-a-cap.md) §决定 5 的理由仍然成立）。

### `POST /web/keys`

请求体：

```json
{ "name": "笔记本", "note": "CI 用", "scopes": ["skills:read"], "expires_at": "2027-01-13T15:59:59Z" }
```

或：

```json
{ "name": "CI", "scopes": ["skills:read"], "never_expires": true }
```

判定顺序：

| 请求 | 结果 |
|---|---|
| `never_expires=true` 且 `expires_at` 非空 | `400 invalid_expiry` / `field=never_expires` / `issue=conflicts_with_expiry` |
| `never_expires=true` 且部署不允许 | `400 invalid_expiry` / `field=never_expires` / `issue=not_allowed` |
| `never_expires=true`（其余） | `201`，`expires_at: null` |
| `never_expires` 缺省或 `false` 且 `expires_at` 为 `null` | `201`，`expires_at = min(now + 90 天, 上限)` |
| `expires_at` 非空且在将来、不晚于第 365 天 | `201`，按该日期 |
| `expires_at` 在过去 | `400 invalid_expiry` / `field=expires_at` / `issue=not_in_the_future` |
| `expires_at` 超过 365 天 | `400 invalid_expiry` / `field=expires_at` / `issue=beyond_maximum` |

`never_expires` 是请求体上的字段名；`details[].field` 用它，控制台据此把句子放回表单。

### `POST /web/keys/{id}/rotate`

没有请求体。服务端读旧行：

| 旧 `expires_at` | 部署允许不过期 | 新 key 的 `expires_at` |
|---|---|---|
| `null` | 是 | `null`（续期保留这个选择） |
| `null` | 否 | `400 invalid_expiry` / `field=expires_at` / `issue=not_allowed`，让用户新建一把带日期的 |
| 有日期 | — | `defaultExpiry(now)`（90 天，封顶在上限内） |

其余不变：同一事务里旧行标撤销、新行插入、一条 `api_key_rotate` 审计。

### 自动撤销（没有 HTTP 接口）

每日任务调用 `RevokeUnusedApiKeysUseCase.revokeUnused(now)`：

- 阈值 `now - 365 天`；`UNUSED_FOR` 是 usecase 里的常量。这是**连续 365×24 小时**，与到期日的「上海日末」是两个口径——一把到期日仍在今天的 key 也可能先被清理；这是 `unused for 365 days` 的直接结果。
- `StaleApiKeys.revokeUnusedBefore(threshold)` 在模块内完成「选行 → 条件更新（同一陈旧谓词）→ 写审计」；事务由 usecase 开。条件更新里重复陈旧谓词，是为了让 SELECT 与 UPDATE 之间刚被使用的 key 不被误撤销。
- 审计 action 是 `api_key_auto_revoke`，`actorUserId = null`，`detail = {reason: "unused_for_365_days", last_used_at, created_at}`；从未使用时 `last_used_at` 写字符串 `"never"`，与签发审计的 `expires_at` 同约定。
- 不新增错误码。下一次用被撤销的 key 调用，现有的 `token_revoked` 路径就是回答。

### 错误码与字段

`ApiKeyRequestException.Problem` 增加 `NEVER_EXPIRY("never_expires")`：

- `NEVER_EXPIRY` 允许 `not_allowed`、`conflicts_with_expiry`。
- `EXPIRY` 增加 `not_allowed`（续期旧行不过期、但部署已不允许时用）。
- `ApiExceptionHandler.onApiKeyRequestException` 把 `NEVER_EXPIRY` 映射到 `ErrorCode.INVALID_EXPIRY`（与 `EXPIRY` 同一个 wire code，靠 `field` / `issue` 区分）。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| **P1** | 策略、签发 / 续期、接口、控制台表单、设计件 | A1–A6、A9–A12 |
| **P2** | `StaleApiKeys` + 每日任务 + 审计 | A7、A8 |

P1 与 P2 在同一个 PR；P2 不改变 P1 的接口。

## 开放问题

1. **Q1**：自动撤销按 365 天还是 12 个日历月？**已定：365 天**——与 `MAX_LIFETIME`、默认值一样以天为单位，避免月份长度；边界测试写死 365 天。
2. **Q2**：续期一把「不过期」的密钥时，如果部署已经不允许不过期，拒绝还是降级成 90 天？**已定：拒绝**（不静默改变用户的选择），错误给 `invalid_expiry` / `not_allowed`，让用户新建一把带日期的。

这一版没有未决的开放问题；Q1 / Q2 留在这里是为了记下它们问过、答过。

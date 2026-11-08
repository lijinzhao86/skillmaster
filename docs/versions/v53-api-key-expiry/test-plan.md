# v53-api-key-expiry · 测试

## 验收映射

| 验收 | 用例 |
|---|---|
| A1 | T1 |
| A2 | T2 |
| A3 | T3、T4 |
| A4 | T5、T6 |
| A5 | T7 |
| A6 | T8、T9、T10 |
| A7 | T11、T12；列表「已撤销」行由既有 revoked 渲染测试覆盖 |
| A8 | T13 |
| A9 | T14 |
| A10 | T15 |
| A11 | T16 |
| A12 | T17 |

一条验收对应多个用例时，每个用例只证明它那一半；没有「顺手全测了」的用例。T6b（`never_expires` 与 `expires_at` 冲突）是 ADR 0171 §决定 4 的额外契约用例，不对应 A1–A12 的某一条。

## 用例

### T1 · 新建表单的默认值与档位

- **前置**：控制台已登录；`GET /web/keys` 返回 `policy = {allow_no_expiry: true, default_days: 90, lifetime_days: 365, latest_day: …}`。
- **步骤**：打开「新建 API 密钥」。
- **期望**：有效期是下拉；默认选中「90 天」；档位文本依次是 `30 天 / 60 天 / 90 天 / 365 天 / 自定义日期 / 不过期`。

### T2 · 365 天档签出的日期

- **前置**：测试库可写；默认策略（`MAX_LIFETIME = 365 天`）。
- **步骤**：`POST /web/keys`，`expires_at` 取「今天 + 365 天」的上海日末。
- **期望**：`201`，返回的 `expires_at` 是那一天的上海日末对应的瞬时（wire 上是 `...T15:59:59Z`）。

### T3 · 不过期签出

- **前置**：默认策略（`allow_no_expiry=true`）。
- **步骤**：`POST /web/keys`，`never_expires: true`。
- **期望**：`201`，`key.expires_at` 为 `null`；`keys[]` 里这一行也是 `null`。

### T4 · 不过期的密钥能用，列表写「永不过期」

- **前置**：T3 的密钥已拿到整串。
- **步骤**：用这把 key 调 `GET /api/v1/me`；在控制台列表看这一行。
- **期望**：`/me` 返回 200；列表的到期格写「永不过期」；四格计数把它算进「活跃」，不算进「已过期」。

### T5 · 部署禁止不过期时，表单不出现这一档

- **前置**：`policy.allow_no_expiry = false`（控制台测试的 fixture；服务端由 T6 证明）。
- **步骤**：打开新建表单。
- **期望**：下拉只有 30 / 60 / 90 / 365 / 自定义；没有「不过期」。

### T6 · 直接请求不过期被拒绝

- **前置**：`@SpringBootTest(properties = "skillmaster.api-keys.allow-no-expiry=false")`。
- **步骤**：`POST /web/keys`，`never_expires: true`。
- **期望**：`400 invalid_expiry`，`details[].field = never_expires`，`details[].issue = not_allowed`。

### T6b · 「不过期」与日期同时出现被拒

- **前置**：默认策略（`allow_no_expiry=true`）。
- **步骤**：`POST /web/keys`，同时带 `never_expires: true` 与一个将来的 `expires_at`。
- **期望**：`400 invalid_expiry`，`details[].field = never_expires`，`details[].issue = conflicts_with_expiry`。

### T7 · 上限固定 365：365 档存在，366 天被拒

- **前置**：默认策略（`MAX_LIFETIME = 365 天`）。
- **步骤**：打开新建表单；再 `POST /web/keys`，`expires_at` 取「今天 + 366 天」。
- **期望**：下拉有「365 天」；请求 `400 invalid_expiry` / `beyond_maximum`，`field = expires_at`。

### T8 · 部署已不允许不过期时，续期一把不过期的 key 被拒绝

- **前置**：一把 `expires_at = NULL` 的未撤销 key（先按 `allow-no-expiry=true` 签发，或直接 SQL 插入）；`@SpringBootTest(properties = "skillmaster.api-keys.allow-no-expiry=false")`。
- **步骤**：`POST /web/keys/{id}/rotate`。
- **期望**：`400 invalid_expiry` / `field=expires_at` / `issue=not_allowed`；旧的 key **仍然活着**（拒绝发生在 `rotate` 之前，不能先撤后拒）；同一上下文里一把带日期的 key 仍能正常续期。

### T9 · 不过期的密钥续期后仍不过期

- **前置**：T3 的 key；部署仍允许不过期。
- **步骤**：`POST /web/keys/{id}/rotate`。
- **期望**：`200`，新 key 的 `expires_at` 仍为 `null`；旧的已撤销（再用旧串得到 `token_revoked`）；一条 `api_key_rotate` 审计，`replaced` 指向旧 id。

### T10 · 有日期的密钥续期后是默认 90 天

- **前置**：一把 `expires_at` 有日期的 key。
- **步骤**：续期。
- **期望**：新 key 的 `expires_at` 落在请求窗口的 `now + 90 天` 上（误差只来自请求本身；不是旧日期，也不是新上限 365 天）。

### T11 · 365 天未使用的密钥被自动撤销

- **前置**：一把未撤销的 key，`last_used_at = NULL`、`created_at = now - 366 天`；另一把 `last_used_at = now - 366 天`。
- **步骤**：用固定 `now` 调 `RevokeUnusedApiKeysUseCase.revokeUnused(now)`；再用同一个 `now` 调一次。
- **期望**：两把的 `revoked_at` 都非空；每把有一条 `api_key_auto_revoke` 审计，`actorUserId = null`，`detail.reason = unused_for_365_days`；第一次返回计数 2；第二次返回 0，审计仍是 2 条（不重复写）。

### T12 · 被自动撤销的密钥再调用得到 token_revoked

- **前置**：T11 的一把 key 的整串还在。
- **步骤**：用它调 `GET /api/v1/me`。
- **期望**：`401`，`code = token_revoked`。

### T13 · 未满 365 天的不撤销（边界）

- **前置**：三把 key：`COALESCE(last_used_at, created_at)` 分别是 `now - 364 天`、`now - 365 天 - 1 秒`、`now - 365 天`。
- **步骤**：用固定 `now` 调 `revokeUnused(now)`。
- **期望**：只有早于 `now - 365 天` 的那把被撤销；正好等于阈值的那把**不**被撤销（SQL 是 `<`，不是 `<=`）。

### T14 · 改「不过期」开关不追溯已有 key

- **前置**：`allow-no-expiry=true` 时签发一把 `expires_at = NULL` 的 key。
- **步骤**：用 `allow-no-expiry=false` 起一份新上下文（或直接构造 `ApiKeyPolicy(false, …)`）；重新读第一把；再请求一把不过期的。
- **期望**：第一把的 `expires_at` 仍为 `NULL`，仍能用；新的不过期请求被拒；列表里两把并存。

### T15 · 文档与生成视图一致

- **前置**：改过 `application.yml` 与文档。
- **步骤**：在仓库根跑
  `python3 .claude/skills/docs-architecture/scripts/check_docs.py`、
  `python3 scripts/docs/impact.py --check`、
  `python3 scripts/docs/generate.py`，再 `git status`。
- **期望**：前两条退出 0；生成后对生成物没有未提交的改动（或改动已提交）。

### T16 · 服务端与控制台的自动化测试

- **前置**：本机 Postgres 在跑；`SKILLMASTER_TEST_DB_*` 指向 v53 的测试库。
- **步骤**：`cd skillmaster-server`，跑改动面
  `./mvnw -Dtest=ApiKeyPolicyTest,SkillmasterPropertiesTest -Dit.test=ApiKeyIT,ApiKeyExpiryPolicyIT,ApiKeyAutoRevokeIT verify`；
  `cd skillmaster-console && npm test && npm run type-check`。
- **期望**：改动面全绿；失败的测试附输出，不写「应该没问题」。全量 `./mvnw verify` 不在本版强制范围内。

### T17 · 设计检查与有人看图

- **前置**：`design/` 装了依赖；`design/skillmaster-控制台演示.html` 已重出。
- **步骤**：`node design/scripts/console-states.mjs "design/skillmaster-控制台演示.html" artifacts/design/console --only keys`；再打开 `?page=keys&state=new` 切默认 / 不过期 / 自定义三种选择。
- **期望**：检查全绿；有人看过渲染，并确认三个态的布局与文案（「测试全绿」不是视觉验收）。

## 环境与数据

- **服务端**：Docker Postgres 容器 `skillmaster-postgres`；`skillmaster-server/` 的 `./mvnw verify` 用 `SKILLMASTER_TEST_DB_*` 指向 `skillmaster_test_v53`。测试库每次重建 schema，不需要手工迁移。
- **控制台**：`skillmaster-console/` 的 `npm ci`、`npm test`、`npm run type-check`；测试 fixture 里的 `policy` 按用例改，不连真服务端。
- **设计件**：`design/` 的 `npm ci`、`npm run build:console`；设计检查用本机 Chrome（`puppeteer-core`），窗口默认无头。
- **自动撤销的数据**：直接在测试库里改 `created_at` / `last_used_at`，不等待真实时间；用例把 `now` 固定成一个参数，避免边界随运行时间漂。
- **清理**：测试库每次重建；设计检查的截图落在 `artifacts/design/console`（被 gitignore），不入库。

## 结果

**2026-11-09 执行。** 服务端只跑改动面（A11 的范围），未跑全量 `./mvnw verify`；设计检查只跑 `keys` 分组。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | 控制台 `keys-expiry` 六档/默认 90；设计检查「新建：有效期是下拉，默认 90 天，档位齐全」 |
| T2 | 通过 | `ApiKeyIT.theThreeHundredSixtyFifthDayIsTheLastIssuableDay`，返回值精确等于第 365 天上海日末（wire `Z`） |
| T3 | 通过 | `ApiKeyIT.aKeyIssuedWithNeverExpiresHasNoDateAndWorks`；控制台 `never_expires` 请求体断言 |
| T4 | 通过 | 同一 IT 的 `/me` 200；设计检查「不过期的密钥在列表里写「永不过期」，仍是活跃」；控制台新增「counts a never key as active, not expired」 |
| T5 | 通过 | 控制台「offers 「不过期」 only when the deployment allows it」 |
| T6 | 通过 | `ApiKeyExpiryPolicyIT.aDirectRequestForNeverIsRefusedWhenTheDeploymentForbidsIt` |
| T6b | 通过 | `ApiKeyIT.neverExpiresAndADateTogetherAreRefused` |
| T7 | 通过 | `ApiKeyIT.theThreeHundredSixtyFifthDayIsTheLastIssuableDay`（365 收、366 拒）；控制台 365 档发送 `latest_day` |
| T8 | 通过 | `ApiKeyExpiryPolicyIT.renewingANeverKeyIsRefusedWhenTheDeploymentNoLongerAllowsIt`，旧 key 仍 200，同一上下文里带日期的 key 仍能续期 |
| T9 | 通过 | `ApiKeyIT.renewingANeverKeyKeepsItNever`，旧串 `token_revoked`，`api_key_rotate` 一条且 `detail.replaced` 指向旧 id |
| T10 | 通过 | `ApiKeyIT.renewalReplacesTheKeyAndRekeysTheDate`，新日期落在请求窗口的 `now + 90 天` |
| T11 | 通过 | `ApiKeyAutoRevokeIT.aKeyUnusedForMoreThanAYearIsRevokedAndAudited` + `twoStaleKeysAreBothRevokedAndAuditedOnce`（计数 2、审计 2、第二次清理 0） |
| T12 | 通过 | `ApiKeyAutoRevokeIT.aKeyUnusedForMoreThanAYearIsRevokedAndAudited`，被撤销后 `/me` 为 `token_revoked` |
| T13 | 通过 | `ApiKeyAutoRevokeIT.theBoundaryIsStrictlyOlderThanThreeHundredSixtyFiveDays`（364 / 365 / 365 天 + 1 秒） |
| T14 | 通过 | `ApiKeyExpiryPolicyIT.aKeyWithNoDateIssuedBeforeTheSwitchKeepsWorking`：第一把仍 null、仍能用，第二把带日期并存，新的不过期请求被拒 |
| T15 | 通过 | `check_docs.py`、`impact.py --check`、`generate.py --check` 退出 0 |
| T16 | 通过（改动面） | 服务端 41 例（`ApiKeyIT` 25、`ApiKeyExpiryPolicyIT` 3、`ApiKeyAutoRevokeIT` 4、`ApiKeyPolicyTest` 6、`SkillmasterPropertiesTest` 3）；控制台 696 例；两侧 `tsc --noEmit` |
| T17 | 部分 | 设计检查 `--only keys` 8 个状态全过（含六档、默认 90、危险色提示、自定义日期 `max`）；**人工看图待产品负责人确认** |

**未执行**：服务端全量 `./mvnw verify`、设计检查全套（超过 `keys` 的分组）。

## 已知问题

（尚无。）

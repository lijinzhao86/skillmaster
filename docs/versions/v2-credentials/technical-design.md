# v2-credentials · 技术设计

> **最后更新**：2026-10-15
> **状态**：设计已定稿，**三个组件都已实现**（服务端 / 控制台 / CLI）。哪一条跑到什么程度、证据是什么，权威在 [`test-plan.md`](test-plan.md) §结果——这里与 README 只跟着它说
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。

<!--
本版只写它改动的部分。托管、检索、读取面与作者面的设计权威仍在
../v1-hosting/technical-design.md，在这里复述会造出第二个会漂的副本（docs-architecture §2.3）。
-->

## 架构

**只换凭据，不动分层。** v1 的三个平面（浏览器面 `/web/*` 会话、协议面 `/oauth/*`、API 面 `/api/v1/*` 令牌）、模块切分与依赖规则全部原样——权威在 [`v1-hosting/technical-design.md`](../v1-hosting/technical-design.md) §2 与 [`architecture/`](../../architecture/README.md)。

**换的是这条链的两端**：

```
   v1                                    v2
─────────────                     ─────────────────────
CLI: loopback 监听                 控制台: 新建 key（会话面，CSRF）
   ↑ 浏览器回调                        ↓ 整串显示一次，人复制走
/oauth/authorize → 同意页            机器: 环境变量 / stdin
   ↓                                  ↓
/oauth/token → 一对令牌 ──┐         /api/v1/* 带着它
                          │              ↓
/oauth/* 与 /.well-known/* │         M2 的 TokenValidator
   ↓                       │        （按前缀路由 → api_key 表）
/api/v1/* ─────────────────┘              ↓
                                      M3 的接缝，以上全不变
```

**两个平面各管一半，且不互相越界**（§4.1 的约定不变）：

- **签发与管理走浏览器面**（`/web/keys`）：它是「谁在管理自己的凭据」，身份由会话证明，写受 CSRF 保护。
- **使用走 API 面**（`/api/v1/*`）：凭据在 `Authorization` 头里。
- **API 面上没有 key 的管理端点**，这是有意的：`logout` 只管本机（[ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §决定 7），撤销在控制台，所以 CLI 不需要管理面。少一个面就少一处「我能拿这把 key 去签/撤别的 key 吗」的问题。
- **API 面上唯一新增的是「自述」**（`GET /api/v1/me`）：它读自己、不签发也不撤销，所以它不构成管理面。**能造凭据的东西仍然只有会话**（[ADR 0040](../../decisions/0040-api-key-in-the-cli-now-the-browser-path-later.md) §决定 1）。

**M3 的形状不动**：`BearerToken`、`BearerTokenSecurityContextRepository`、`Scopes`、链上的规则、以及 401/403 的信封与 challenge 全部原样。**唯一的例外是 401 的 handler 多读一个请求属性**（原因码）——这是本版加在那个接缝上的东西，见 §接口。派发在 M2 的实现里。

## 数据模型

**新增一张表，其余表的权威仍在 v1**（§3.1 身份与鉴权 / §3.2 命名空间 / §3.3 版本 / §3.4 检索 / §3.5 审计）。本版**没有改动任何已有的表**。

```sql
CREATE TABLE api_key (
  id           TEXT PRIMARY KEY,                            -- ULID：控制台由它寻址（整串是不可再读的）
  user_id      TEXT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  name         TEXT NOT NULL,                               -- 人给它起的名字，列表里的短标识
  note         TEXT,                                        -- NULL = 没写说明；一句话，比名字长
  token_hash   TEXT NOT NULL UNIQUE,                        -- 整串（含前缀）的 sha256，十六进制
  scope        TEXT NOT NULL,                               -- 空格分隔，与 access_token.scope 同一套词表
  expires_at   TEXT,                                        -- NULL = 永不过期
  last_used_at TEXT,                                        -- 节流写：只在超过陈旧窗口时才重写
  last_used_from TEXT,                                      -- 与 last_used_at 同一个窗口一起写：最近一次来自哪个地址
  revoked_at   TEXT,                                        -- 撤销是状态不是删除
  created_at   TEXT NOT NULL
);

CREATE INDEX idx_api_key_user ON api_key(user_id);          -- 控制台按人列表
```

**四项要与相邻设计对齐，逐条说明**：

| 项 | 决定 | 与谁对齐 |
|---|---|---|
| **为什么另起一张表** | `access_token` 的行是 `oauth_authorization` 的子行，且由框架经 `TokenStore` 写。key 没有授权码、同意与刷新链，塞进去会让那条存活判断（对父行的 `EXISTS`）**有条件为真** | [ADR 0021](../../decisions/0021-opaque-tokens-not-jwt.md)（不透明 + 撤销立刻生效）在两种凭据上都成立，所以它们是**同一种东西的两张表**，不是两种机制 |
| **`expires_at` 可空** | `NULL` 表示永不过期。是否要求填是**部署开关**（[ADR 0039](../../decisions/0039-api-key-defaults-and-their-edges.md) §决定 1），所以列必须能表达两种答案 | 唯一不会被读错的写法；用哨兵时间戳会被读成「1970 年过期了」 |
| **没有 `audience`** | key 在本服务签发、存储、出示，「签给谁」不是它能失败的性质 | `access_token.audience` 在那边是协议要求（RFC 8707） |
| **没有 `created_by`** | v1 里签发者与主体是同一个人；「谁建的」是关于过去的问题 | `audit_event`——V12 对 `skill_grant` 写下了同样的理由 |
| **撤销是 `revoked_at` 时间戳，不是布尔** | 「什么时候撤的」是列表要回答的问题之一（[ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §决定 2）。**参照产品在这里只有一个布尔**，唯一的时间痕迹是 `updated_at`——它自己的模型注释承认了这一点。**这一处我们有意不同**，不是照抄漏了 |
| **另有 `note`，可空** | 名字只够认出是哪一把；「当初为什么签它」是半年以后才会问的问题，而那时写下它的人往往已经不在。它与 `name` 是两件事——名字是身份，说明是意图——所以不是一栏的两段，而是两栏。**参照产品也有这一栏**（Description）。**可空**：签发时强制写一句说明，换来的是一句敷衍 | 与 `skill.description` 同一分工；列表那一栏的宽度也说明它不该被要求写满 |

**撤销与 `last_used_at` 都在这一张表上，但读写方不同**：撤销由控制台写，`last_used_at` 由**鉴权路径**写（§接口）。后者是本版唯一一处「读路径上发生写」——见 §开放问题 Q1。

## 接口

### 新增：`/web/keys`（浏览器面，会话 + CSRF）

| 方法 | 路径 | 作用 | 出参 |
|---|---|---|---|
| `GET` | `/web/keys` | 列出自己的 key + **这次部署的有效期策略** | `{keys: [{id, name, note, scope, expires_at, last_used_at, revoked_at, created_at}], policy: {expires, lifetime_days, latest_day}}`。**永远不含整串**。`policy` 是给新建表单的：界面预选 90 天，但**关掉开关的部署签出来的是不过期的 key**——所以它必须问，不能猜。**`lifetime_days` 同时是默认值和上限**（[ADR 0042](../../decisions/0042-the-expiry-setting-is-a-cap.md)）：`lifetime_days` 是提示语里那个数，**`latest_day` 是这个部署签得出的最后一天**（`YYYY-MM-DD`，`expires: false` 时为 null），表单拿它当预选值与选择器的 `max`。这一天由服务端算好下发而不是客户端自己算——**理由见 ADR 0042 §决定 5**（本文不复述，§2.3） |
| `POST` | `/web/keys` | 新建，入参 `{name, note?, scopes, expires_at?}`；`expires_at` 不传就取部署的默认值，传了就**必须在将来、且不晚于「`lifetime` 天之后那一天的最后一秒」（按控制台写日期的时区）**——超了是 `400 invalid_expiry` / `beyond_maximum`（[ADR 0042](../../decisions/0042-the-expiry-setting-is-a-cap.md)）。**上限是一天的结束而不是 `now + lifetime`**：后者会在当天已经过去的那几秒上比那一天更早，于是控制台按预选日期提交的那一次必然被拒；而这个界是闭的，预选的那一天正好落在界上 | `{key: {id, name, note, scope, expires_at, last_used_at, revoked_at, created_at}, **token**: "skm_…"}`——**整串只在这一个响应里出现一次**。**嵌一层**是为了那八个字段只写一遍：续期返回同一个形状 |
| `POST` | `/web/keys/{id}/rotate` | 续期：作废旧的、按同一名字、说明与 scope 发一把新的，**有效期按签发默认值重算，不继承旧的** | 同 `POST` 的响应（含新的整串） |
| `POST` | `/web/keys/{id}/revoke` | 撤销 | `204` |

**错误形状**（沿用 §4.1 的信封与既有约定）：

| 情况 | 答 |
|---|---|
| 没登录 | `401`（浏览器面的 entry point，不是 Bearer 挑战——两个平面不越界） |
| `id` 不存在、或不属于调用者 | **`404`，两者同一个答案**——与 §4.2 「不泄露存在性」同一条规矩 |
| `scopes` 里有不认识的值 | `400` `invalid_scope`（`issue: unknown`）|
| `scopes` 一个都没有 | `400` `invalid_scope`（`issue: empty`）——**一把什么都做不了的凭据不是更小的凭据**，是看起来坏掉的那把，参照产品同样要求至少一个 |
| `expires_at` 是过去的时间 | `400` `invalid_expiry`（`issue: not_in_the_future`）|
| `name` 为空或超过 100 字符 | `400` `invalid_request`（`issue: blank` / `too_long`）——**列是 TEXT，两个上限是产品决定而不是数据库要求**：没有上限的、可以粘一整份文件的字段，最后会进一个必须把它渲染出来的列表 |
| `note` 超过 500 字符 | `400` `invalid_request`（`issue: too_long`）|
| 撤销一把已撤销的 | `204`（幂等），**而且撤销时间不动**——「什么时候失效的」不该因为点了两次而改变 |
| 续期一把已撤销的 | `400` `invalid_request`（`field: id`、`issue: revoked`）——撤销过的东西没有可延续的 |
| `POST` 缺 CSRF | `403`（框架的 CSRF 过滤器在路由之前就拒）|

**五条不写在这张表里的语义**（前四处是读过参照产品之后定的，最后两处是它踩过或踩歪的）：

- **「快到期」有且只有一个阈值：14 天。** 它是控制台那一屏的一格统计，也是行上的一个标记，**两处读同一个常量**。参照产品在这件事上自己是不一致的（行徽章 14 天、统计卡 15 天），那不是设计意图、是两处各写了一遍——所以这里把它说成一个数，并且**落成一个具名常量**，而不是散在视图里。

- **续期是原子的、没有宽限期。** 先撤旧的、再建新的，**在同一个事务里**；新的建不出来就整体回滚，**旧的原封不动**。也就是说 200 返回的那一刻旧整串就死了，两者不重叠。这比「先发新的、过一阵子再撤旧的」好：重叠窗口看着平滑，实际上是把「两把都有效」的那段时间变成一段没人负责的时间。
  **两个续期同时到达时（控制台的确认按钮点两下就会），谁撤到旧的谁发新的**：第二个把旧行标掉的 `UPDATE` 匹配 0 行——它读到的是「还活着」的旧行，但那条语句的条件是 `revoked_at IS NULL`——于是它**不建新 key**，返回的答案与「续期一把已撤销的 key」同一句（`400 invalid_request` / `field: id` / `issue: revoked`）。没有这条判断，两个请求会各建一把、账号上留下**两把活的 key**，而人只见过其中一把。实现时这条找出来靠的是「测试里两个请求真的并发」（`ApiKeyIT.twoRenewalsAtOnceLeaveExactlyOneLiveKey`）。

- **续期的有效期是重新签发的那个，不是继承的。** 它出的是**一把全新的凭据**，所以按签发默认值（[ADR 0039](../../decisions/0039-api-key-defaults-and-their-edges.md) §决定 1 的 90 天）算，而不是把旧日期搬过去。**这条不是风格问题**：验收 1 要求终端在 key 过期时把那句话指向控制台续期，而一把过期 key 的旧日期搬到新串上仍旧是过期——指向就落空了，而控制台没有任何别的地方能改一个有效期。参照产品的 `rotate` 同样给新令牌重新定日期（按实例的有效期策略），它另有一个「保持原有效期」的开关；**我们的界面没有那个开关，所以不实现**。已撤销的不能续期（`revoked_at` 非空即拒绝），这与参照产品一致。
- **配置只约束新建，不溯及已有的 key。** 把有效期上限调低，不改任何已存在的 `expires_at`。（参照产品在这里相反——它降上限会**撤销**超期的令牌。改一个部署开关不该悄悄弄死别人正在用的凭据。）**「上限」这个词是按字面办的**（[ADR 0042](../../decisions/0042-the-expiry-setting-is-a-cap.md)）：带日期的签发请求超过它就是拒绝，而不只是「没人指定时用它」——否则控制台永远送日期的这条路径会让这个开关一次都不生效。
- **`skills:write` 蕴含 `skills:read`，在签发时补。** 库里那一行因此就是真相，不会出现「实际权限比声明的多」这种要读两处才知道的结果；而 M3 的链一行不用动，「本版 M3 的形状不动」那条才立得住。
- **两条审计信号**，是 [ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §后果 对「失去轮换检测」的补偿：**一把 key 从一个新地址被使用**（`api_key_new_address`）、以及**一把已撤销的 key 再次出现**（`api_key_revoked_reuse`），各记一条。它们**不是每请求的记录**——那是 `last_used_at` 的事；而第一条是**按窗口采样的**，为什么以及它比 ADR 的文字弱在哪里，写在 §接口 里。
  **第二条不节流，代价写在这里**：一把已撤销的 key 每被使用一次就写一行（这正是不节流的理由——洪水本身就是信号，见 §接口）。也就是说**只持有一把已撤销凭据的人可以按请求量增长 `audit_event`**。收的话很便宜（`(key_id, address)` 加一个最小间隔，节流窗口的形状现成），本版选择不收，因为这行是「有人拿着已经作废的凭据在敲」的唯一痕迹。

### 改动：鉴权路径（API 面）

**M3 的形状不动**（它只是多读一个请求属性，见下）。改的是 M2 对 M3 那个接缝的实现（`IssuedTokenValidator`），它在已有的三条判断之外多三件事：

1. **按前缀路由**：整串以 `skm_` 开头 → 查 `api_key`；否则 → 查 `access_token`（原路径）。**路由不是判定**——决定权仍在哈希查询，所以路由错了是「查不到」而不是「放行」。OAuth 的**访问**令牌是一个 UUID 串（36 个字符的十六进制与连字符），刷新令牌是 96 字节 base64url，两者都**永远不会**以 `skm_` 开头，所以不可能混淆。（这里原先把访问令牌写成「96 字节 base64url」——那是刷新令牌的形状，2026-10-15 核过 `TokenConfiguration` 里的 `OAuth2AccessTokenGenerator` 之后改的；路由结论不受影响。）
   **前缀是两处语言级常量，不是配置**（服务端 `ApiKeyFormat.PREFIX` 与 CLI `internal/apikey.Prefix`）：把它做成部署配置，会让 CLI 那份常量与任何一次部署分叉，而分叉的后果是终端把一把 key 当 OAuth 令牌去续期。[ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §后果 的待办清单里列了「前缀字符串」，实现按这一条办——**落进配置的是有效期开关与缺省值、以及 last-used 窗口**，前缀不在其中。
2. **两条路都要问一次「这个账号还能不能用」**：不能就拒。**这一条对两条路都要加**——它今天是个已登记的债，而 90 天的凭据让它从债变成必需。
   **而这一问必须走 M1 的公开面，不能 JOIN `app_user`。** §2.5 rule 1 不让一个模块读另一个模块的表，`TableOwnershipTest` 会当场拒绝一次带 `app_user` 的查询（实现时正是它拦下来的）。所以 M1 的 `AccountDirectory` 上多一个**问句** `isActive(userId)`——**问句而不是状态值**：`AccountRow` 当年特意写着「把 `status` 交出去会诱使调用方自己判断」，而「能不能用」的答案留在 M1，判断就还在拥有那一列的人手里。代价是这条路上多一次查询（只对已经查到的凭据发生，猜测永远走不到）。
3. **key 那条路要说清「为什么不能用」**，写成一个请求属性交给 401 的 handler——见下。

**401 要说清是哪一种。** 今天 `error.code` 恒为 `unauthenticated`，`WWW-Authenticate` 只带 `resource_metadata`，于是「过期」与「已撤销」在终端里是同一句话。而验收 1 要求把它们分开：**过期的能续期，撤销的不能**（[ADR 0039](../../decisions/0039-api-key-defaults-and-their-edges.md) §决定 3）。

| `error.code` | 什么时候 |
|---|---|
| `token_revoked` | 哈希查到了行，`revoked_at` 非空 |
| `token_expired` | 查到了行，`expires_at` 不是 NULL 且已过 |
| `account_suspended` | 查到了行，但 `app_user.status` 不是 `active` |
| `unauthenticated` | 没带凭据、或哈希压根没查到（含格式垃圾）——**今天的行为，不新增分支** |

**同时成立时的优先级：`token_revoked` → `account_suspended` → `token_expired`。** 次序的理由是**刻意的动作排在时间之前**：撤销与停用都是有人做过的决定，过期只是时间到了。先报那个决定，人才知道该找谁——而**这三句提示分别指向不同的动作**（撤销的那把只能新签一把，停用的要先解停用，过期的可以续期），所以次序不是排版问题。

**机制**：原因只有校验器知道（M2），而响应由 M3 的 `BearerAuthenticationEntryPoint` 写。两者之间走一个**请求属性**，但**写它的是 M3 的仓储而不是校验器**——这一点是被架构规则逼出来的、也正是它该在的地方：校验器若要把原因交给 M3，M2 与 M3 就互相依赖，`ArchitectureTest` 的无环检查会拒绝构建。而仓储是 M3 的、手里就有那个 request、也刚拿到校验器的答复——**两端都在同一个模块里**，所以这既不跨模块、也不需要线程上下文。

接缝本身的形状因此改了一处：`TokenValidator.validate` 从「要么给主体、要么给空的 `Optional`」变成 `Accepted` / `Rejected(原因)`（`TokenValidation`），输入多一个调用方地址。**它原来的 javadoc 明写「不得区分『没有这个令牌』与『已过期/已撤销』」——那句话被有意改掉了**，改的边界写在 `TokenValidation` 上：**「不是我们的」与「没带凭据」仍然不可区分**（猜测学不到任何东西，因为要查到一个哈希命中得先持有 256 位秘密），说清原因只发生在**已经查到行**之后。`WWW-Authenticate` **保持现状**（`error="invalid_token"`，不带 description）：机器读的是信封里的 `code`，而**一个事实只写一遍**——challenge 是 M3 的形状，本版不碰。

**这套原因码只用于 key 那条路，OAuth 那条路维持 `unauthenticated`。** 它那条路的存活判断是框架的 join，「OAuth 那条路一行不改」是 [ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) 与本文反复承诺的边界；为了给一个**过渡形态**的凭据（[ADR 0040](../../decisions/0040-api-key-in-the-cli-now-the-browser-path-later.md) §决定 5）换一句更细的话去动它，是拿本版最小的那条承诺去换一句文案。**代价如实写在这里**：过渡期的 `login` 拿到的 401 仍是笼统一句。

`api_key` 那条查询**按哈希取回整行**，判定在代码里（仍是一次查询、无缓存）：

```sql
SELECT id, user_id, scope, expires_at, revoked_at, last_used_from
  FROM api_key
 WHERE token_hash = :hash
```

**存活判断从 `WHERE` 搬进了代码，而且必须搬**：三个原因码要求「查到了、但为什么不能用」是可分辨的，而带 `revoked_at IS NULL` 这类谓词的查询只会返回「能用」或「什么都没有」。代价要说清——**这一层不再有 SQL 兜底，漏掉一条判断就是放行一把死 key**，所以三条各有一条断言钉住（[`test-plan.md`](test-plan.md) 的 T3 停用 / T10 过期 / T13 优先级），而不是靠读一遍 SQL 放心。查询本身仍是一次、无缓存；账号那一问是它之后的第二次（见上）。

**`last_used_at` 的写**（鉴权成功之后、同一个请求内）：

```sql
UPDATE api_key SET last_used_at = :now, last_used_from = :address
 WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :stale_before)
```

节流的理由写在 SQL 里：**条件在 `WHERE` 上而不是先读再判**，所以热 key 是「绝大多数请求不写」，而不是「每次都写一次再回滚」。**并发也不需要另加锁**：同一瞬间两条请求都判「该更新了」时，第二条会因为第一条已提交而匹配不到行——**这个 `WHERE` 自己就是那个租约**，再叠一把锁就是同一个东西的第二种实现。窗口取值见 §开放问题 Q1。

**`last_used_from` 与它写在一起，所以「从别处用了」这条信号的采样率就是这个窗口。** 一条 SQL 比对不了「这个地址以前见过没有」，而为了这件事给每个请求加一次写是不划算的——ADR 0039 §决定 4 要的是一条便宜的信号，不是一份完整的访问日志。因此这条信号的准确读法是「**至少隔了一个窗口，地址变了**」：一个以前出现过、但中间换过别的地址的地址会再报一次。**这一处比 ADR 的文字弱**，写在这里是为了它下次被读到时不用猜。

**`last_used_at` 那一列没有配套的 `updated_at`，所以不存在「使用记录污染了修改时间」这件事**——这张表刻意不记「最后改动」，因为 key 一旦签发就只被撤销或续期，两件事都有自己的列。

> **「主体带凭据 id」这一版做了，但不是为了审计。** 曾写过「本版不做」，理由是它只服务于一个尚未建的功能——那个理由在 `/api/v1/me` 加进来之后**不成立了**：`auth status` 与 `login --with-key` 都要回答「你在用哪一把」，而答案是主体的属性还是别的东西都不合适。所以 `AuthenticatedSubject` 多一个 `apiKeyId`（空 = 这张凭据不是 key），**身份而不是快照**：名字、权限、到期日仍在 M2 的行里，谁要谁去问（与 `AccountDirectory` 给 handle 的形状相同）。
>
> **审计那一半仍然没做**：[ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §后果 要的「审计能答出是哪一把 key」还没落地，因为今天没有那个消费者。现在多了一个字段，但**没有一处把它写进审计行**——想做时它就是现成的，这条债因此只剩一半。

### 新增：`GET /api/v1/me`（API 面，自述）

| 方法 | 路径 | 作用 | 出参 |
|---|---|---|---|
| `GET` | `/api/v1/me` | 调用者自述：**我是谁** + **手里这张凭据是什么** | `{"user": {user_id, username, namespace}, "key": <与 `/web/keys` 列表里那一行**同一个形状**，即 `id, name, note, scope, expires_at, last_used_at, revoked_at, created_at`> \| null}`。**复用同一个响应类**，因为「一把 key 长什么样」只该写一遍——多一个给 `/me` 的精简形状就是同一件事的第二个权威。多出来的四个字段在这条路上没有害处：`revoked_at` 恒为 null（撤销了的 key 认证不过），`note` 是调用着自己的话 |
| `POST` | `/web/keys` 的**错误信封** | 每个 400 都带 `details[].field` | `field` 是**请求体上的那个名字**：`name` / `note` / `scopes`（不是概念的 `scope`）/ `expires_at`；`400 invalid_request` 的续期失败用 `id`。改这一处之前它写的是 `scope`，于是照 `field` 回填表单的客户端（包括本仓的控制台）找不到那个字段，为它写的句子永远到不了屏幕 |

**它是浏览器面 `/web/session` 在 API 面上的孪生。** 那个端点今天是这样写的：它是**该平面唯一的已认证读**，既是「客户端怎么知道自己在扮演谁」，也是 CSRF token 的来源。API 面缺的正是对应的一件——它今天没有任何「我是谁」。

**三个用它的人**：

- `login --with-key`：**存之前先验一次**——一把存不进去的凭据不该被写进钥匙串（401 就按原因码说清，且**不写任何东西**）。
- `auth status`：本机那一半 CLI 自己知道，**服务端那一半只能问这里**。
- 报错时指认是哪一把：终端说「过期了」，人得知道是哪一把过期了。

**`key` 为 `null` 表示这张凭据不是 key**（是一张 OAuth 令牌）。CLI 据此说「这份凭据不是一把 key」，而不是编一个名字印出来。

**`user` 复用 `/web/session` 那个 record，不另造一份自述**：`user_id` / `username` / `namespace` 三个字段的分工已经在那里写清楚了（`namespace` 不是 `username` 的冗余，它是客户端拼地址要用的那个量）。两个平面说同一件事，就该是同一段代码。

**它不在「API 面上没有管理端点」那条禁令里**（§架构）：它不能签发、不能撤销、不列别人的东西——**它只读自己**。

**不含整串，也不含哈希。** 它照 `WebAccount` 的同一句规矩办：能回答「我是谁」就够了，值不值得放进响应体是另一回事。

**`last_used_at` 是节流写的**（§开放问题 Q1 的窗口），所以这个字段可能落后至多一个窗口——**`auth status` 不许把它读成「刚刚用过」**。

**它吃同一套 401 原因码**——这正是 `login --with-key` 拿它来验的原因：一次请求同时验「这张凭据活着」与「它是我以为的那把」。

**它要认证，但不要 scope。** 一个「问自己是谁」的请求如果要 `skills:read`，就等于要一项权限才能问自己的事——而它最要紧的时刻恰恰是凭据出了问题的时候：一把缺了读权限的 key 应该还能说清自己是什么，否则 `auth status` 在最需要它的场合失灵。规则因此写在 scope 那两条**之前**（`SecurityConfig` 里那句 `authenticated()`）。

### 改动：CLI

| 命令 | 改成 |
|---|---|
| `login --with-key`（新） | 从 **stdin** 读一整串 → 校验形状（前缀 + 长度 + 字符集）→ **拿它请求一次 `GET /api/v1/me`**：`200` 才存，`401` 就按原因码说清并**不写任何东西**。存法按 [ADR 0025](../../decisions/0025-cli-credential-storage.md)（keychain 优先、0600 文件兜底并告知）。**不接受命令行参数**——那会进 shell history 与 `ps`。成功后印出用户名与这把 key 的名字、权限、到期日 |
| （环境变量） | `SKILLMASTER_API_KEY` 优先于本机存储；**没有任何东西被落盘**（无人值守/CI 的路）；它压过本机那一份时提示一句 |
| `auth status`（新） | 两半都说清：**本机那半**（来自环境变量还是本机存储、存在哪）+ **服务端那半**（`GET /api/v1/me`：用户名、这把 key 的名字 / 权限 / 到期 / 上次使用）。这一条回答的是「我到底在用哪把」——而这正是粘贴式凭据最容易搞错的地方 |
| `login`（不带 `--with-key`） | 仍然走 v1 的浏览器路（过渡形态，[ADR 0040](../../decisions/0040-api-key-in-the-cli-now-the-browser-path-later.md) §决定 5）。**在没有浏览器时它必须失败，且失败信息指向 `--with-key` 与环境变量** |
| `logout` | **只删本机那一份**（钥匙串与文件都删），不再调 `/oauth/revoke`（[ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §决定 7）。要撤服务端那把去控制台（**key 有入口**；过渡期 `login` 留下的授权没有——[ADR 0040](../../decisions/0040-api-key-in-the-cli-now-the-browser-path-later.md) §后果）；凭据来自环境变量时说清**本机没有东西可删** |
| 其余全部命令 | **不动**：它们只拿 `api.Client{BaseURL, Token}` |

**CLI 不做**：签发、列出、撤销、续期 key（四件事都在控制台，[ADR 0040](../../decisions/0040-api-key-in-the-cli-now-the-browser-path-later.md) §决定 4）；不接受命令行参数形式的凭据；不碰 `/web/*`。

**凭据在本机的形状**：沿用 [ADR 0025](../../decisions/0025-cli-credential-storage.md) 那个 blob，**`internal/credentials` 一行不动**——一把 key 存成 `access_token = "skm_…"`、`refresh_token` 留空、`expires_at` 取 `/me` 的回答。**空的 `refresh_token` 本来就有语义**（`internal/auth/session.go`：「这份凭据没有刷新令牌…不能续期；请重新运行 `skillmaster login --client-credentials`」）。本版**在它前面加一条判据**：以 `skm_` 开头的凭据**根本不去问那句话**，而是把服务端的 401 原因码原样报出来（见下）。**那句话因此一个字都不用改**——它仍然说给 `--client-credentials` 那条路听，而那条路本来就是这样。

**401 在 key 这条路上不走「先续期」那一步。** 今天一条命令拿到 401 会调 `Renew(refused)`，而 key 没有刷新令牌——于是人看到的是 `ErrNotRenewable` 那句「请重新运行 `skillmaster login --client-credentials`」，**与真实原因无关**。本版：**整串以 `skm_` 开头 = 不续期**，401 直接按服务端的原因码报（撤销 / 过期 / 停用各说各的，各指各的下一步）。判据用前缀而不是另加一个标记位，是因为**前缀已经是这条路上载重的事实**（服务端就是按它路由的），而加标记位会让本机那份 blob 与 [ADR 0025](../../decisions/0025-cli-credential-storage.md) 的形状分叉。**代价**：前缀因此出现在两种语言里（服务端的 `ApiKeyFormat` 与 CLI 的一个常量），两边必须一致——所以它在 Go 里也只有一处，形状校验与 401 分支读同一个常量。

**而本机存的那份 `expires_at` 对 key 不是判据。** 它来自 `/me`，只用来显示（`auth status` 说「还剩几天」）。**若拿它当判据，一把刚到期的 key 会在请求发出之前就被本地判死**，人看到的是 `ErrNotRenewable` 而不是服务端那句「过期了、去控制台续期」——而验收 1 要的正是后者。服务端是权威（[ADR 0001](../../decisions/0001-server-authoritative.md)），所以 key 这条路**每次都把请求发出去**。

**报错里的那个地址**：过期与撤销的提示都指向控制台的密钥页，而那个页面的路由是 `SKILLMASTER_WEB_URL` + `/account/keys`（见 附录 A 的「网页」那一行——**路由的权威在网页侧**，CLI 只用环境变量拼）。


## 分期

| 期 | 交付 | 验收 | **这一期明确不做** |
|---|---|---|---|
| **P0 · 服务端** | 迁移 `V13`（`api_key`）+ `ModuleMap` 归属 M2；`/web/keys` 四个端点（四个用例）；`IssuedTokenValidator` 的前缀路由、**走 M1 问句的账号判断**、存活判断从 SQL 搬进代码、`last_used_at` 与 `last_used_from` 的节流写；**`GET /api/v1/me`**；**401 的三个原因码**（撤销 / 过期 / 停用）与它们的优先级；M1 的 `AccountDirectory.isActive`；审计（签发 / 撤销 / 续期 + 两条使用信号） | 用例见 [`test-plan.md`](test-plan.md)：签发→使用→撤销、只读 key 被拒写、停用当场失效、整串只出现一次、三个 401 原因码与优先级 | **不做**控制台那一屏、不做 CLI 改动——所以这一期结束时，key 只能用 `curl` 走通 |
| **P1 · 控制台** | `/account/keys` 那一屏（**路由就定这个**，CLI 的报错文案指向它）：四格计数（各是一次筛选，**计数跟着搜索走**）、筛选片、五列列表、三种空态、**行内问后果的撤销与续期**、新建的表单与「整串只出现一次」那一屏。**实现时定下五处**（都在这张屏上，不影响服务端契约）：① 面包屑三屏都画（列表屏也画：它说的是「设置 › API 密钥」，而左栏说的是「你在哪一类页面」——原先写的是「只出现在表单与那一刻两屏」，2026-10-15 核对交付件与代码之后改的）；② 搜索词用 `replaceState` 进地址（每敲一个字都真导航是荒谬的），筛选仍然是真的链接；③ 表单的日期按**当地那一天的结束**发（`23:59:59+08:00`）而不是 UTC 零点——后者会让「1 月 13 日到期」在当天早上八点就死；④ 表单的 `maxlength`（名称 60 / 说明 255）**比服务端的上限（100 / 500）更紧**，是产品选择而不是契约；⑤ 「读写」只发 `skills:write`，**蕴含读是服务端在签发时补的**（规则只留一处） | 那一屏走完一遍，且**刷新后拿不到整串** | **不做**「按机器分」这类分组——key 的名字是人自己起的，产品不猜 |
| **P2 · CLI** | `login --with-key`（stdin + **先验 `/me` 再存**）、`SKILLMASTER_API_KEY`、`auth status`（两半）、`logout` 改成本机；key 不续期（401 直说）与 `expires_at` 不当判据；`login` 无浏览器时的失败信息 | 在无浏览器容器里只靠环境变量走完读链路（验收 #2）；反证：不带 `--with-key` 的 `login` 必须失败；`auth status` 说清两半 | **不做** CLI 侧的 key 管理（列出/撤销/签发/续期）——四件事都在控制台 |
| **P3 · 文档与验收** | v1 文档指向 v2 的那一句、`docs/research/` 那一节、`test-plan.md` §结果 | `check_docs.py` 退出码 0；v1 的十条验收在 v2 下重新判定一遍 | **不做** `PATCH` 元数据、`restore` 等 v1 遗留项 |

**OAuth 那条路在四期里都不动**——包括 `/oauth/*`、同意页、轮换、宽限窗口与 loopback 固定端口（[ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) 头部「范围收窄」那一栏）。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| **Q1** | `last_used_at` 的陈旧窗口取多少？它是**鉴权路径上唯一的写**，而那条路今天只读 | **10 分钟**——**已核实**：参照产品那个常量就是它。也就是热 key 每小时最多写 6 次 | 实现后压一次并发读，确认窗口内没有写放大；窗口太长会让「刚用过没有」骗人。**这个数落进配置**，与有效期开关同一段 |
| **Q2** | 「谁有权签发」的组织那半 | **本版只答第一方**（用户签自己的）。组织那半要等组织模型，届时 key 的行主从「一个人」变成「一个合成用户」 | 它已经是 [ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §后果 里点名的债。**回答方式是组织模型那一版的设计，不是这一版的补丁** |
| **Q3** | 整串的长度与编码 | **`skm_` + 32 字节 `SecureRandom` 的 base64url**（43 字符，合计 47）。熵是唯一的安全边界，所以生成必须走 `SecureRandom` | 手抄不是需求（粘贴式），所以长度不是 UX 约束。**要验的是**它与 OAuth 令牌的字符集互不重叠，前缀路由才成立——一次断言即可 |
| **Q4** | 前缀 `skm_` 要不要在 `BearerToken` 那一层就做形状校验（超长 / 非 ASCII / 空值一律当成「没带凭据」） | **倾向不做**：`BearerToken` 是 M3 的，而形状是 M2 的词表；哈希查询本来就会把任何垃圾变成一次未命中 | 若实测出「超长 header 造成可感知的哈希开销」再做，那时它是一条性能取舍而不是安全取舍 |

## 附录

### 附录 A · 与现有代码的关系

| 模块 | 关系 |
|---|---|
| **M2** `modules/token` | **主体**：新增 `api_key` 表与 `ApiKeyRegistry`（`ModuleMap` 里归它）、公开面 `ApiKeys` / `ApiKey` / `IssuedApiKey` / `ApiKeyFormat` / `ApiKeyPolicy` / `ApiKeyRequestException`、`IssuedTokenValidator` 的四处改动（前缀路由 / 账号判断走 M1 / 存活判断搬进代码 / 撤销后重现的信号）、审计。`/web/keys` 的四个端点长在 `api` + `usecase` 上，不在这里。**OAuth 那条路一行不改** |
| **M3** `modules/auth` | **信封、challenge 与链上规则原样，但接缝的形状改了一处**：`TokenValidator` 从 `Optional<AuthenticatedSubject>` 变成 `Accepted` / `Rejected(原因)`（新的 `TokenValidation`），输入多一个调用方地址；仓储在被拒时把原因写进请求属性，401 的 handler 读它；`AuthenticatedSubject` 多一个 `apiKeyId`。`Scopes` 多两个静态方法（词汇表的问句与「写蕴含读」）。理由与边界都在 §接口 |
| **M1** `modules/account` | 几乎不动：`app_user.status` 的**写**仍归它，而**读**发生在每次请求上——但要以**问句**的形式出门，所以 `AccountDirectory` 多一个 `isActive(userId)`（`AccountRepository` 实现）。**状态值本身没有离开这个模块**，理由在 §接口 第 2 条 |
| **M4/M5/M6/M7/M8/M9/M10/M11** | 不动 |
| **网页** `skillmaster-web` | 新增**一页**（账号下的「访问凭据」，不再挂在「设置」里当一个区块），沿用既有形状：不引路由、会话经 `/web/*`、写带 CSRF。**它借的是参照产品那一页的信息架构，也借它的盒子**（统计 → 筛选 → 表格式列表 → 行尾操作）：四格统计就是四张卡片，外一层 `--wash` 的壳、里一层白底，照它自己的 `card.scss` 读出来的配方。表格式的那个列表是**全站第二处允许画盒子的地方**，与 skill 页的文件列表同一个理由——那是「仓库页的形状本身」。**路由定死为 `/account/keys`**（不是 `/account` 下的一个区块）：CLI 的报错文案要指向一个地址，而一个地址必须稳定——**路由的权威在这一侧**，CLI 只拿 `SKILLMASTER_WEB_URL` 拼它 |
| **CLI** `skillmaster-cli` | `login --with-key` 与 `auth status` 两条新路径、`logout` 改语义、`internal/auth/session.go` 里 key 那条不走续期；新增 `internal/apikey`（形状检查）与 `internal/credentials` 的 `EnvStore`（环境变量优先，**只在 `openStore` 一处包上**）；`internal/api` 增 `Me`（`GET /api/v1/me`）与 401 的 `Refusal`（带原因码，仍包着 `ErrUnauthorized`，所以老调用方不受影响）。存的东西**多了一个字段**：blob 里加了 `saved_at`（写下这份的时刻，由序列化时打戳），因为两个副本不能再用 `expires_at` 比新旧——key 的到期日由人选、「不过期」是零值，于是刚粘进去的那把会输给被它替换的旧凭据、被静默忽略（第二轮审计实测）。其余形状不变（[ADR 0025](../../decisions/0025-cli-credential-storage.md) 的存储位置与优先级都不动）；无戳的旧 blob 按 `expires_at` 比较，而它们只可能是令牌。另：`internal/auth` 的 `Logout`（撤销→删本机）随本次改语义**删掉了**——它唯一的调用方就是旧 `logout`，而 `login` 替换旧授权走的是 `RevokeAuthorization`（保留） |
| **`/api/v1/me`** | 新增，落在 `com.skillmasterai.api` ——与 `/web/session` 那个控制器同一层、同一形状（`user` 直接复用 `WebAccount`）。**它不是模块**：读自己，不碰任何模块的表 |
| **`/oauth/*`、`/.well-known/*`、同意页** | **全部保留**，服务第三方宿主（P2）。ADR 0038 头部那一栏列的 0011/0023/0024/0026/0028 不取代、不改写 |

### 附录 B · 来源与出处

- **一手**：[ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md)、v1 的 [`technical-design.md`](../v1-hosting/technical-design.md) 与 [`architecture/modules/M02-token-and-as.md`](../../architecture/modules/M02-token-and-as.md)。
- **一手（读代码得来）**：参照产品的凭据模型——令牌与「项目/群组令牌」**共用一张表**，后者由**一个合成用户**持有；令牌有可识别前缀；last-used 按**十分钟**节流更新；有效期是**部署开关**。这些结论是在本地克隆的源码上读出来的（克隆不进版本库，见 `.gitignore` 里 `references/` 那一条），**这里不复述它的文件路径**——那是一个随时会消失的坐标。
- **二手**：无。

### 附录 C · 已测量的数据

| 指标 | **口径**（定义/样本/是否计入零值） | 数值 |
|---|---|---|
| 本版新增的表 | 迁移文件里 `CREATE TABLE` 的条数 | 1（`api_key`） |
| 本版改动的已有表 | 迁移文件里 `ALTER TABLE` 的条数 | 0 |
| 鉴权路径上新增的查询 | 一次请求里最多的额外语句数（key 路径：1 次凭据查询 + 1 次账号问句 + 条件写，存活判断在代码里而不在 SQL 的谓词里；OAuth 路径：0 次凭据之外的查询 + 1 次账号问句） | OAuth 1 / key 2（+节流写） |
| 本版新增的路由 | 路由注解新增的条数（同一路径的不同方法各算一条） | 5（`/web/keys` 的 4 条 + `GET /api/v1/me`） |
| 本版新增的 `error.code` 取值 | `ErrorCode` 里本版新增的常量条数 | 6（401 的三个：`token_revoked` / `token_expired` / `account_suspended`；控制台的三个：`key_not_found` / `invalid_scope` / `invalid_expiry`） |
| key 请求路径上的查询 | 一次成功请求里最多的语句数（凭据一次 + 账号状态一次 + 至多一次节流写） | 2 次查询，写 ≤1（每窗口） |

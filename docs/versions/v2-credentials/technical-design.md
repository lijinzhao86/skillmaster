# v2-credentials · 技术设计

> **最后更新**：2026-10-15
> **状态**：设计已定稿，**尚未实现**
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

**M3 不动**：`BearerToken`、`BearerTokenSecurityContextRepository`、401/403 两个 handler、`Scopes`、链上的规则全部原样。派发在 M2 的实现里——见 §接口。

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
  revoked_at   TEXT,                                        -- 撤销是状态不是删除
  created_at   TEXT NOT NULL
);

CREATE INDEX idx_api_key_user ON api_key(user_id);          -- 控制台按人列表
```

**四项要与相邻设计对齐，逐条说明**：

| 项 | 决定 | 与谁对齐 |
|---|---|---|
| **为什么另起一张表** | `access_token` 的行是 `oauth_authorization` 的子行，且由框架经 `TokenStore` 写。key 没有授权码、同意与刷新链，塞进去会让那条存活判断（对父行的 `EXISTS`）**有条件为真** | [ADR 0021](../../decisions/0021-opaque-tokens-not-jwt.md)（不透明 + 撤销立刻生效）在两种凭据上都成立，所以它们是**同一种东西的两张表**，不是两种机制 |
| **`expires_at` 可空** | `NULL` 表示永不过期。是否要求填是**部署开关**（决定 3），所以列必须能表达两种答案 | 唯一不会被读错的写法；用哨兵时间戳会被读成「1970 年过期了」 |
| **没有 `audience`** | key 在本服务签发、存储、出示，「签给谁」不是它能失败的性质 | `access_token.audience` 在那边是协议要求（RFC 8707） |
| **没有 `created_by`** | v1 里签发者与主体是同一个人；「谁建的」是关于过去的问题 | `audit_event`——V12 对 `skill_grant` 写下了同样的理由 |
| **撤销是 `revoked_at` 时间戳，不是布尔** | 「什么时候撤的」是列表要回答的问题之一（[ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §决定 2）。**参照产品在这里只有一个布尔**，唯一的时间痕迹是 `updated_at`——它自己的模型注释承认了这一点。**这一处我们有意不同**，不是照抄漏了 |
| **另有 `note`，可空** | 名字只够认出是哪一把；「当初为什么签它」是半年以后才会问的问题，而那时写下它的人往往已经不在。它与 `name` 是两件事——名字是身份，说明是意图——所以不是一栏的两段，而是两栏。**参照产品也有这一栏**（Description）。**可空**：签发时强制写一句说明，换来的是一句敷衍 | 与 `skill.description` 同一分工；列表那一栏的宽度也说明它不该被要求写满 |

**撤销与 `last_used_at` 都在这一张表上，但读写方不同**：撤销由控制台写，`last_used_at` 由**鉴权路径**写（§接口）。后者是本版唯一一处「读路径上发生写」——见 §开放问题 Q1。

## 接口

### 新增：`/web/keys`（浏览器面，会话 + CSRF）

| 方法 | 路径 | 作用 | 出参 |
|---|---|---|---|
| `GET` | `/web/keys` | 列出自己的 key | `{keys: [{id, name, note, scope, expires_at, last_used_at, revoked_at, created_at}]}`。**永远不含整串** |
| `POST` | `/web/keys` | 新建，入参 `{name, note?, scope, expires_at}` | `{id, name, note, scope, expires_at, created_at, **token**: "skm_…"}`——**整串只在这一个响应里出现一次** |
| `POST` | `/web/keys/{id}/rotate` | 续期：作废旧的、按同一名字、说明与 scope 发一把新的，**有效期按签发默认值重算，不继承旧的** | 同 `POST` 的响应（含新的整串） |
| `POST` | `/web/keys/{id}/revoke` | 撤销 | `204` |

**错误形状**（沿用 §4.1 的信封与既有约定）：

| 情况 | 答 |
|---|---|
| 没登录 | `401`（浏览器面的 entry point，不是 Bearer 挑战——两个平面不越界） |
| 写请求缺 CSRF | `403`（框架的 CSRF 过滤器在路由之前就拒） |
| `id` 不存在、或不属于调用者 | **`404`，两者同一个答案**——与 §4.2 「不泄露存在性」同一条规矩 |
| `scope` 里有不认识的值 | `400` `invalid_scope` |
| `expires_at` 不合法（过去的时间、或没开开关时缺了它） | `400` `invalid_expiry` |
| 撤销一把已撤销的 | `204`（幂等） |

**五条不写在这张表里的语义**（前四处是读过参照产品之后定的，最后两处是它踩过或踩歪的）：

- **「快到期」有且只有一个阈值：14 天。** 它是控制台那一屏的一格统计，也是行上的一个标记，**两处读同一个常量**。参照产品在这件事上自己是不一致的（行徽章 14 天、统计卡 15 天），那不是设计意图、是两处各写了一遍——所以这里把它说成一个数，并且**落成一个具名常量**，而不是散在视图里。

- **续期是原子的、没有宽限期。** 先撤旧的、再建新的，**在同一个事务里**；新的建不出来就整体回滚，**旧的原封不动**。也就是说 200 返回的那一刻旧整串就死了，两者不重叠。这比「先发新的、过一阵子再撤旧的」好：重叠窗口看着平滑，实际上是把「两把都有效」的那段时间变成一段没人负责的时间。

- **续期的有效期是重新签发的那个，不是继承的。** 它出的是**一把全新的凭据**，所以按签发默认值（[ADR 0039](../../decisions/0039-api-key-defaults-and-their-edges.md) §决定 1 的 90 天）算，而不是把旧日期搬过去。**这条不是风格问题**：验收 1 要求终端在 key 过期时把那句话指向控制台续期，而一把过期 key 的旧日期搬到新串上仍旧是过期——指向就落空了，而控制台没有任何别的地方能改一个有效期。参照产品的 `rotate` 同样给新令牌重新定日期（按实例的有效期策略），它另有一个「保持原有效期」的开关；**我们的界面没有那个开关，所以不实现**。已撤销的不能续期（`revoked_at` 非空即拒绝），这与参照产品一致。
- **配置只约束新建，不溯及已有的 key。** 把有效期上限调低，不改任何已存在的 `expires_at`。（参照产品在这里相反——它降上限会**撤销**超期的令牌。改一个部署开关不该悄悄弄死别人正在用的凭据。）
- **`skills:write` 蕴含 `skills:read`，在签发时补。** 库里那一行因此就是真相，不会出现「实际权限比声明的多」这种要读两处才知道的结果；而 M3 的链一行不用动，「本版 M3 不动」那条才立得住。
- **两条审计信号**，是 [ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §后果 对「失去轮换检测」的补偿：**一把 key 第一次从一个新地址被使用**、以及**一把已撤销的 key 再次出现**，各记一条。它们**不是每请求的记录**——那是 `last_used_at` 的事。

### 改动：鉴权路径（API 面）

**M3 一行不动。** 改的是 M2 对 M3 那个接缝的实现（`IssuedTokenValidator`），它在已有的三条判断之外多两件事：

1. **按前缀路由**：整串以 `skm_` 开头 → 查 `api_key`；否则 → 查 `access_token`（原路径）。**路由不是判定**——决定权仍在哈希查询，所以路由错了是「查不到」而不是「放行」。OAuth 令牌是 96 字节 base64url，**永远不会**以 `skm_` 开头，所以两者不可能混淆。
2. **两条路都多看一次 `app_user.status`**：`active` 之外一律拒。**这一条对两条路都要加**——它今天是个已登记的债，而 90 天的凭据让它从债变成必需。

`api_key` 那条查询与 `access_token` 那条同形（一次查询判完，无缓存）：

```sql
SELECT user_id, scope FROM api_key
 WHERE token_hash = :hash
   AND revoked_at IS NULL
   AND (expires_at IS NULL OR expires_at > :now)      -- NULL = 永不过期
   AND EXISTS (SELECT 1 FROM app_user u
               WHERE u.id = api_key.user_id AND u.status = 'active')
```

**`last_used_at` 的写**（鉴权成功之后、同一个请求内）：

```sql
UPDATE api_key SET last_used_at = :now
 WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :stale_before)
```

节流的理由写在 SQL 里：**条件在 `WHERE` 上而不是先读再判**，所以热 key 是「绝大多数请求不写」，而不是「每次都写一次再回滚」。窗口取值见 §开放问题 Q1。

两个工程细节，都是参照产品那边已经踩过的：

- **只写 `last_used_at` 那一列，不碰 `updated_at`。** 否则「这把 key 什么时候被改过」会被使用记录污染，而这两件事在列表上要分别回答。
- **写之前先拿一把按 key 的排他租约。** 并发请求会同时判定「该更新了」，租约让绝大多数竞争变成「别人刚写过，不用了」——写放大正是这个功能唯一的代价。

> **本版不再做「主体带凭据 id」。** [ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §后果把它列成要还的债（审计要能答出「是哪一把 key」），但它只服务于一个**本版尚未建**的功能；给一个被广泛使用的 record 加字段属于投机性改动。`last_used_at` 不需要它——鉴权路径自己知道刚验的是哪一行。**它挂在下一次真正需要审计归属的时候。**

### 改动：CLI

| 命令 | 改成 |
|---|---|
| `login --with-key` | 从 **stdin** 读一整串并校验形状（前缀 + 长度 + 字符集），然后按 [ADR 0025](../../decisions/0025-cli-credential-storage.md) 存（keychain 优先、0600 文件兜底并告知）。**不接受命令行参数**——那会进 shell history 与 `ps` |
| （环境变量） | `SKILLMASTER_API_KEY` 优先于本机存储；**没有任何东西被落盘**（无人值守/CI 的路） |
| `login`（不带 `--with-key`） | 仍然走 v1 的浏览器路（它服务第三方宿主的同一条协议）。**在没有浏览器时它必须失败，且失败信息指向 `--with-key` 与环境变量** |
| `logout` | **只删本机那一份**，不再调 `/oauth/revoke`（[ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §决定 7）。要撤服务端那把去控制台 |
| 其余全部命令 | **不动**：它们只拿 `api.Client{BaseURL, Token}` |

## 分期

| 期 | 交付 | 验收 | **这一期明确不做** |
|---|---|---|---|
| **P0 · 服务端** | 迁移 `V13`（`api_key`）+ `ModuleMap` 归属 M2；`/web/keys` 四个端点；`IssuedTokenValidator` 的前缀路由、`status` 判断与 `last_used_at`；审计（签发 / 撤销 / 续期） | 用例见 [`test-plan.md`](test-plan.md)：签发→使用→撤销、只读 key 被拒写、停用当场失效、整串只出现一次 | **不做**控制台那一屏、不做 CLI 改动——所以这一期结束时，key 只能用 `curl` 走通 |
| **P1 · 控制台** | `/account` 那一屏：新建 / 列表 / 撤销 / 续期 | 那一屏走完一遍，且**刷新后拿不到整串** | **不做**「按机器分」这类分组——key 的名字是人自己起的，产品不猜 |
| **P2 · CLI** | `login --with-key`（stdin）、`SKILLMASTER_API_KEY`、`logout` 改成本机；`login` 无浏览器时的失败信息 | 在无浏览器容器里只靠环境变量走完读链路（验收 #2）；反证：不带 `--with-key` 的 `login` 必须失败 | **不做** CLI 侧的 key 管理（列出/撤销）——撤销在控制台 |
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
| **M2** `modules/token` | **主体**：新增 `api_key` 表与它的仓储、`/web/keys` 四个端点、`IssuedTokenValidator` 的三处改动、审计三处。**OAuth 那条路一行不改** |
| **M3** `modules/auth` | **不动**。接缝 `TokenValidator` 的形状、401/403 的 MCP 形状、`Scopes`、链上规则全部原样 |
| **M1** `modules/account` | 不动。`app_user.status` 的**写**仍归它；本版只是让**读**发生在每次请求上 |
| **M4/M5/M6/M7/M8/M9/M10/M11** | 不动 |
| **网页** `skillmaster-web` | 新增**一页**（账号下的「访问凭据」，不再挂在「设置」里当一个区块），沿用既有形状：不引路由、会话经 `/web/*`、写带 CSRF。**它借的是参照产品那一页的信息架构，也借它的盒子**（统计 → 筛选 → 表格式列表 → 行尾操作）：四格统计就是四张卡片，外一层 `--wash` 的壳、里一层白底，照它自己的 `card.scss` 读出来的配方。表格式的那个列表是**全站第二处允许画盒子的地方**，与 skill 页的文件列表同一个理由——那是「仓库页的形状本身」 |
| **CLI** `skillmaster-cli` | `login` 加一条路径、`logout` 改语义；`internal/credentials` 不动（存的还是「一串 + 过期时间」） |
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
| 鉴权路径上新增的查询 | 一次请求里最多的额外语句数（key 路径：1 次查询 + 条件写；OAuth 路径：0 次查询 + 0 次写） | OAuth 0 / key 1（+节流写） |

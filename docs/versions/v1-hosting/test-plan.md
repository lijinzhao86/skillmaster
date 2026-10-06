# v1-hosting · 测试方案

> **最后更新**：2026-10-01
> **状态**：**部分执行**。P0a 与 P0c（服务端）已实现并自测通过；**T0–T4 属于 P0b**（需要 CLI），尚未执行
> **所属版本**：[`README.md`](README.md)

## 验收映射

[`prd.md`](prd.md) §验收与指标 的每一条都在这里映射到用例。

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #1 用户能注册并登录、拿到令牌 | T0 | **P1 + P0b**——注册与登录属 P1，CLI 属 P0b |
| #2 `setup` 能装上网关 skill | T1 | **P0b** |
| #3 agent 自己搜到需要的 skill | T2 | **P0b**。前提「提交后要点上线才生效」在 P0d 做完，见 #6 |
| #4 读到正文、按需取文件并完成任务 | T3 | **P0b** |
| #5 **全程无 skill 副本落盘**（成立条件） | T4（+ T4b 做反证） | **P0b** |
| #6 提交产生草稿、上线是网页上的另一个动作 | 「提交与上线分开的用例」整节 | **P0d**——服务端/网页/CLI 三侧都有用例，且**那一次联合手工验证已走过（2026-10-06）**，见 §结果 |

**为什么 #1–#5 是 P0b 而不是「未执行」**：它们全部要在 agent 里、经 CLI 走一遍，而写它们的当时 CLI 还不存在（见 [`technical-design.md`](technical-design.md) §7 的分期说明）。把它们留在「未执行」会读成「还没做」，实际是「那一轮不做」。CLI 现在有了，所以 #3–#5 剩下的只是**在真 agent 里跑一次**，见 §结果的 T2/T3/T4。

以下用例不对应验收标准，但验证接口契约的安全性。**这三条只依赖服务端，所以 P0a 就执行了**：

| 契约 | 对应用例 | 状态 |
|---|---|---|
| 未认证 → `401` 且形状符合规范 | T5 | **通过**（`AuthContractIT`、`InsufficientScopeIT`） |
| 无权者访问私有 skill → `404`（不泄露存在性） | T6 | **通过**（`SkillDetailIT`、`SkillContentIT`） |
| 中文检索可用 | T7 | **部分通过 / 阻塞**——见下 |

**T7 为什么是「部分通过 / 阻塞」而不是「通过」**：功能层已通过——2 字中文查询能命中，`SkillSearchIT` 钉住了这一条。但 T7 的原意是验证**选定的索引**在目标实例上可用，而 P0a 根本没有用索引（`LIKE` 直查，`pg_bigm` 后置为纯优化）。所以「索引是否可用」仍未验证，而它依赖阿里云 RDS 是否支持 `pg_bigm`——这一条仍未核实（第 8 章开放问题 3）。**功能不是靠索引做到的，这一点要说清楚**：`LIKE` 解决的是延迟不是相关性，所以 T7 通过不构成「索引可用」的证据。

## 用例

### T0 · 注册与登录（**P1 + P0b**——需要注册实现与 CLI）

- **前置**：干净环境，服务端可访问；**短信号码可用且签名与模板已过审**（[`technical-design.md`](technical-design.md) §8 问题 12）。
- **步骤**：注册一个用户（**手机号 + 短信验证码 + 密码 + 用户名**）→ 登录 → CLI 拿到令牌 → 用它调一个需鉴权的接口（如 `GET /api/v1/skills`）。
- **期望**：注册即得到**个人命名空间**，其 `slug` 是所填的**用户名**、**不是手机号**（[ADR 0013](../../decisions/0013-phone-login-and-username-slug.md)）；登录后令牌可用；**令牌不出现在命令行历史或模型上下文里**（由 CLI 从系统 keychain 读）。
- **判定**：通过／失败。P1 落地前本条一直是**未执行**（不是失败）。

### T0b · 密码重置（**P1**）

- **目的**：验证找回密码真的能找回——**包括把旧凭据挡在外面**。
- **前置**：已注册的账号，且手上有它的一个有效令牌。
- **步骤**：用该令牌调一个需鉴权的接口（应 `200`）→ 走重置流程（手机号 + 验证码 + 新密码）→ 用**同一个旧令牌**再调一次 → 用新密码登录。
- **期望**：旧令牌被撤销（`401`），新密码可登录、旧密码不可登录。**漏掉撤销那一步就等于没重置**，这是这类流程最常见的一处漏洞（[`architecture/modules/M01-account-login.md`](../../architecture/modules/M01-account-login.md)）。
- **判定**：通过／失败。

### T1 · setup 装上网关 skill（**P0b**——需要 CLI）

- **前置**：干净环境，没有装过任何 skill。
- **步骤**：跑 `setup`；检查网关 skill 的落点与 frontmatter。
- **期望**：网关 skill 落地；frontmatter 常驻成本 < 200 tokens；**除网关外没有别的 skill 被装上**。
- **判定**：通过／失败。

### T2 · agent 自主搜索命中（**P0b**——需要 CLI）

- **前置**：已 setup 并 login；服务端已托管至少一个含 L3 的真实 skill。
- **步骤**：在 agent 里提出一个**不点名 skill** 的任务；观察 agent 是否通过网关 skill 的 `description` 命中并调用 CLI `search`。
- **期望**：agent 自己搜到正确 skill；`search` 只返回 L1 卡片（响应里不含正文或文件内容）。
- **判定**：通过／失败。失败时要记录 agent 实际说了什么、调了什么。

### T3 · 按需读取并完成任务（**P0b**——需要 CLI）

- **前置**：同 T2，已 `show` 到目标 skill。
- **步骤**：agent 读正文（`/body`）→ 需要时取文件（`/files/{relpath}`）→ 完成任务。
- **期望**：任务产出正确；**L2 与 L3 是在需要时才被取用的**，不是一次性拉全。
- **判定**：通过／失败。

### T4 · 全程无本地副本（成立条件；**P0b**——需要 CLI）

- **前置**：同 T1，干净环境。
- **步骤**：
  1. 记录跑之前的 `~/.agents/`、`~/.claude/skills/` 快照（若不存在则记为空）；
  2. 完整跑 T1 → T3；
  3. 再记录一次快照并比对。
- **期望**：新增内容里**不存在任何托管 skill 的完整副本**——只有网关 skill、CLI 的凭据与配置、以及任务中间产物（临时目录）。
- **判定**：通过／失败。**这一条失败则整版不成立**，即使 T1–T3 全通过。

### T4b · 反证：副本检测确实能发现副本（**P0b**——需要 CLI）

- **目的**：T4 是一条"没发现即通过"的断言，必须证明检测手段**不是永远通过**。
- **步骤**：故意用替代做法（把某个 skill 下载到 `~/.agents/skills/`）造一个副本，再跑 T4 的比对。
- **期望**：检测**报告出**这个副本。
- **判定**：通过／失败。**T4b 不过则 T4 的结论无效。**

### T5 · 未认证访问

- **步骤**：不带 `Authorization` 头请求 `/api/v1/skills`；再带一个无效 token 请求一次。
- **期望**：`401` + `WWW-Authenticate: Bearer resource_metadata="…"`；带无效 token 时**不泄露**任何资源信息。
- **判定**：通过／失败。

### T6 · 私有 skill 对无权者返回 404

- **前置**：存在一个 `private` skill，请求者不是该命名空间的所有者（即另一个用户）。
- **步骤**：请求 `GET /api/v1/skills/{ns}/{name}`，指向另一个用户的 skill。
- **期望**：**`404`（不是 `403`）**——不泄露该 skill 是否存在。
- **判定**：通过／失败。

### T7 · 中文检索

- **目的**：验证中文检索在**选定的 PostgreSQL 实例上**真的可用（[`technical-design.md`](technical-design.md) §8 开放问题 3）。SQLite 时代的方案已实测不可行，理由见 [ADR 0010](../../decisions/0010-storage-in-postgres.md)。
- **前置**：索引扩展在目标实例上可用，且已加进 `shared_preload_libraries`——**这一条本身要先验，它可能直接阻塞本用例**。
- **步骤**：托管一个中文 `description` 的 skill；分别用 **2 字**关键词与 3 字以上关键词 `search`。
- **期望**：两者都能命中。**2 字查询是重点**——它是中文最常见的一次查询，也正是 SQLite 方案失效的地方。
- **判定**：通过／失败／阻塞（目标实例不支持该索引扩展）。

### 服务端用例（P0a 新增）

T0–T7 是**端到端验收**，服务的**契约**要另有一层。P0a 与 P0c 加的这一批不替代上面的用例：它们把端点逐个钉住，好让 T0–T4 在 P0b 失败时能指出是服务端还是 CLI。

全部打**真 PostgreSQL**（本机 16.14，`scripts/init-test-db.sh` 建库）与**真 Tomcat 随机端口**——不用 MockMvc，因为其中一部分断言的就是容器的行为（编码斜杠怎么处理、穿越会不会在路由前被规范化、防火墙在选 controller 之前拒绝请求会变成什么）。**Docker 不可用，所以没有 Testcontainers**；连不上库时是**失败**并给出指引，不是跳过——静默跳过的集成测试就是会烂掉的集成测试。

| 用例 | 钉住什么 |
|---|---|
| `AuthContractIT`、`InsufficientScopeIT` | T5：401 的精确挑战形状、坏令牌不泄露、scope 不足 403 |
| `SkillSubmitIT` | 提交幂等（同内容不产生新版本）、**序号按「不同内容」计数且重发不消耗**、**并发提交各拿各的号**、**提交不移动指针也不改 skill 行的元数据**（P0d 新增）、blob 去重、GC 接缝、软删、跨用户拒绝、错误信封、multipart 上限 |
| `SkillDetailIT` | T6：他人的 private skill 是 404 而非 403，且与「不存在」**逐字节**不可区分；清单顺序与每项 `uri` 已钉版本；`resources` 只剩 `body`；frontmatter 嵌套与未知字段透传 |
| `SkillContentIT` | L2/L3 逐字节相同；`../`、`%2e%2e%2f`、编码斜杠一律 400/404，**绝不 200 或 5xx** |
| `SkillSearchIT` | T7：2 字中文命中；他人 skill 不出现；`namespace` 不能当越权开关；`%`/`_` 按字面搜；**卡片字段集固定且 `version` 嵌套**；排序分层；游标翻页无重复无跳过；畸形游标与异排序游标都 400 |
| `GatewayIT` | 未发布时四条路径**全真 404**；两条索引都发；`digest` 不是版本 digest；三个别名同字节、别的 base 404；重启重发不产生新版本 |
| `ServerSmokeIT` | 端到端一遍，**跨两个面**（P0d 改）：API 提交 → `/web` 上线 → 搜到 → 详情（零内容）→ **照抄详情给的 `uri`** 取正文与文件 → 删除后从搜索里也消失 |
| `SkillVersionPinIT`（P0c 新增） | `@序号` 与 `@sha256:` 两种钉法；`is_latest` 的两种取值；**发布新版本后旧清单的 `uri` 仍取得到旧字节**（ADR 0012 那个 bug 的回归）；钉住的版本随软删一起 404；四类「解析不出东西」的回答逐字节相同；写地址不接受版本后缀 |
| `SkillVersionServiceIT`（P0c 新增） | **所有权谓词在 M7 自己那一层**：直接传一个不属于调用者的 namespace id，读与删都是空。经 HTTP 测不到它——用例层会在 M7 之前就把地址挡掉。**审计轮加强**：钉住「序号是按 skill 计的」——两个**都活着**的 skill 拿**不同**的序号，交叉查必须都落空（原先两个 fixture 同号又被软删，空结果来自 `deleted_at` 而不是版本查找） |
| `SkillAddressTest`（P0c 新增，纯单测） | 地址语法：`@0`/`@+3`/`@03`/长度不对的 digest/大写 hex/第二个 `@` 都不解析 |

审计轮还加/改了这一批，全部是**先能复现旧行为**才留下的：

| 用例 | 钉住什么 |
|---|---|
| `SkillContentIT`（审计轮加强） | 断言到**错误码**：清单里没有的 `relpath` 是 `file_not_found`，他人的 skill 仍是 `skill_not_found`（后者要是变成前一个，就等于确认了这个 skill 存在） |
| `SkillSearchIT`（审计轮加强） | 上限**真的撞到**（插 105 条，`limit=101`/`limit=100000` 都回 100——原先插 25 条断言 25，删掉 clamp 也照样通过）；`namespace` 两个方向都验（只插他人 skill 时，「空」分不出「过滤」与「参数被忽略」）；游标三处：**键内容不合法是 400 而不是 500**（`k0` 会被 `CAST(… AS integer)`）、**时间戳非规范写法被拒**（键是按文本比较的）、**非 ASCII 数字被拒**（`Integer.parseInt` 收 Unicode 数字，`CAST` 只收 ASCII）；**同宽度不同权重的游标被拒**（原先那条只证到了宽度检查） |
| `SkillUploadValidatorTest`（审计轮加强） | 含 `%` 或 `;` 的名字被拒；**空值 frontmatter 键（`license:`）不再 500**；`title: ""` 回落到 name；**自引用 YAML 别名被拒**（否则 frontmatter 是无限结构，Jackson 抛异常 → 500）；**重复 `relpath` 被拒**；**字节上限打在「边读边计数」上**（声明大小是谎的 zip——原先那条被声明大小先挡下，删掉计数界也照样通过） |
| `SkillSubmitIT`（审计轮新增） | 两条**并发**回归：不同 skill 的并发提交不死锁（锁位置）；**共享文件、zip 内顺序相反**的两个提交不死锁（blob 的存储顺序） |
| `InsufficientScopeIT`（审计轮加强） | HEAD 走**读** scope 而不是写：只有写 scope 的令牌请求 HEAD 得 403，且挑战里写的是 `skills:read` |
| `TableOwnershipTest`（审计轮加强） | 与**全部** `V*.sql` 迁移**双向**对表（原先只数 `ModuleMap` 自己的条目；后来只读 V1、且正则区分大小写，都被突变测试证伪后修掉）；另加一条正向断言钉住「表名匹配器本身认得 SQL」——否则「无违规」在什么都不匹配时也成立 |
| `SkillmasterPropertiesTest`（审计轮新增，纯单测） | `public-base-url` 结尾的斜杠被去掉——留着会发出一条 `//` 的取不到的 URL |
| `RelevanceWeightsTest`（审计轮新增，纯单测） | 三个权重之和超出 int4 时在**启动时**失败，而不是让每次检索都 500 |

### M01 账号用例（**已执行**，2026-10-01）

服务端在 M01 里实现（[`technical-design.md`](technical-design.md) §7）。语义的权威在 [`architecture/modules/M01-account-login.md`](../../architecture/modules/M01-account-login.md)。

每个用例一个类，全部打**真 PostgreSQL + 真 Tomcat**，cookie jar 是真的、CSRF 令牌是从响应里读出来再回填的（`support/Browser`）——**不用 MockMvc**：这一面成立与否，一半取决于过滤器的顺序、cookie 的属性、以及异常落进哪个 handler，那些在 mock 环境里都不存在。

| 用例 | 钉住什么 | 在哪 |
|---|---|---|
| 用户名校验 | 非法字符（大写、非 ASCII）、长度越界、保留名（`skillmaster`）都得到**字段级 400**；撞 `UNIQUE` 时也是 400，不是 500。**字母表先于长度判**：`飞书` 报 `invalid_format` 而不是 `invalid_length`——答「太短」会让人去补四个同样被拒的字符 | `WebRegistrationIT.aUsernameThatCouldNotBeAnAddressIsRefused`、`theReservedUsernameIsRefused`、`aUsernameSomebodyElseHasIsRefused`；单测 `UsernamePolicyTest` |
| 注册的落库形态 | 同事务里 `app_user` + `credential` + `namespace(slug=username)` + `namespace_member(role=owner)` 都在；**库里查不到明文手机号**且 `decrypt(phone_enc)` 等于原号；**`spring_session.principal_name` 是 userId 而不是用户名** | `WebRegistrationIT.registrationCreatesTheAccountItsNamespaceAndItsSession` |
| 验证码 | 错误、过期、超过尝试上限都被拒；**错误的码与过期的码、猜太多次的回答逐字节相同**；用注册流程发的码在重置流程上不被接受 | `WebRegistrationIT` 四条、`WebPasswordResetIT.aCodeIssuedForRegistrationIsNotAcceptedForReset` |
| 短信频控 | 冷却内的第二条被拒且**真的没发**（记录的码没变）；`Retry-After` 在；**日限 10 条**在没有冷却的情况下仍拦得住；被拒的请求**不消耗**日限的额度 | `AccountThrottleIT` 四条 |
| 图形验证码 | 签发给出 id + 一张真 PNG、**答案不在响应里**（字段数也钉住）；**注册的首个发码免它**：不带验证码 → 204 且**短信真的发了**，而**同一地址第二次**不带就 400（`{field: captcha, issue: required}`）且一条短信都没发；答错 → 400 且没发；答对才放行；**用过的不能再用**；猜错 3 次后连正确答案也拒；过期的拒；签发本身限流；过期行被清扫而新签发的不受影响 | `WebCaptchaIT` 十七条 |
| 免费额度只读地问 | 新地址答 `{required:false}`；**问这一句本身不认领也不消耗**（问完仍然发得出那条免验证码的短信，而问完之后的答案才变成 `required:true`）；这个只读端点自己限流 | `WebCaptchaIT` 前三条 |
| 用户名可用性 | 空闲名可用；已注册名、**保留名 `skillmaster`**（只查 `app_user` 会答错的那个用例）、非法格式、长度越界都答不可用且 `issue` 与注册会拒它的码一致；按 IP 限流 | `WebUsernameAvailabilityIT` 七条 |
| 不比对模式（`accept-any-code`） | 任意六位数字注册成功；**码仍要先请求过**（没有未消费的行 → 400）；**码仍得长得像码**（只有一位、或干脆没有 `code` 字段 → 400——摘掉的是比对，不是形状）；仍会过期；找回密码同样放行。自带一个 Spring 上下文，因为它是启动时读的配置 | `WebAcceptAnyCodeIT` 五条 |
| 画出来的那部分 | 答案 4 位、字母表不含 `0/O/1/I`、图片是 PNG、**不是每次同一个答案**。真实图片的可读性测不了（那是字形渲染的判断）；测的是**替换成常量生成器或类加载时播种会被抓住** | `HutoolCaptchaRendererTest` 三条（纯单测） |
| 短信发送那一侧 | 模板参数是模板期望的形状；**提供方拒了必须抛出来**（阿里云对一条被拒的短信答 HTTP 200，失败在响应体里），并且异常里带着它自己的说法；接受则安静通过 | `AliyunSmsSenderTest` 三条（纯单测，假的 gateway） |
| 登录失败 | **不区分**「没这个手机号」与「密码错」，两者**逐字节**相同；`suspended` 第三次也是同一份字节 | `WebLoginIT` 前两条 |
| 登录退避 | 第 11 次失败是 429 且带 `Retry-After`，**窗口内正确密码也 429**；未知手机号同样被限流 | `WebLoginIT` 中两条 |
| 会话 | 匿名时 401；**匿名请求不建任何会话**（这两条钉住的是关掉请求缓存与 `IF_REQUIRED` 的决定）；登录后读到身份，`user_id` 是 ULID | `WebSessionIT`、`WebSecurityPlaneIT` |
| CSRF | **第一个响应就带 `XSRF-TOKEN`**；不带头的写请求 403；`permitAll` 端点上的 CSRF 失败是 403 而**不是 401**；`XSRF-TOKEN` 非 HttpOnly 而会话 cookie 是 | `WebCsrfIT` 五条 |
| 两个面互相鉴权不了 | 有效 bearer 令牌不能鉴权 `/web/**`；有效会话 cookie 不能鉴权 `/api/v1/**`（且后者返回的是 **bearer 挑战**，这一点证明请求确实落在 API 链上）；`/web/**` 的 401 不带 `WWW-Authenticate` | `WebSecurityPlaneIT` 三条 |
| 登出 | 204 且会话行没了；**再登出仍 204**；缺 CSRF 是 403 **且会话还在**（第三方页面不能把人登出）；会话已过期时登出也是 204 | `WebLogoutIT` 四条 |
| 改密码 | 旧密码 401、新密码 200；**该账号的全部会话都没了**（两个浏览器各一个，两个都被踢）；重置后**不自动登录** | `WebPasswordResetIT` 前三条 |
| 改密码连带撤销令牌 | 重置前签发的 `access_token` 打 API 变 401、`refresh_token` 续期被拒 `invalid_grant` | `AuthorizationServerIT.resettingThePasswordEndsTheCredentialsAMachineWasStillHolding` |

### 提交与上线分开的用例（P0d 新增，**已执行**）

语义的权威是 [ADR 0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md)与 [`technical-design.md`](technical-design.md) §4.3 的作者面那一节。三件事各自成组：**提交不生效**、**作者面看得见而消费面看不见**、**差异是真的差异**。

| 用例 | 钉住什么 | 在哪 |
|---|---|---|
| 提交不改消费面 | 提交之后搜索里没有它、地址是 404、**`skill.title` / `description` / `updated_at` 都没变**（`SkillSubmitIT` 直接读行）。第二条尤其重要：它是「提交是低风险动作」这句话的全部内容——只断言搜索为空，改元数据那条路仍然漏着 | `SkillSubmitIT.aSubmissionIsInvisibleToTheConsumptionPlane`、`aSubmissionDoesNotTouchTheSkillRow` |
| 草稿不可寻址，即使 skill 已上线 | 已上线 `@1` 仍 200，刚提交的 `@2` **404**，而裸地址仍给 `@1`。这是 published-only 谓词存在的理由：没有它，`@2` 会把没人批过的内容发出去 | `SkillVersionPinIT.aDraftIsNotAddressableEvenWhenTheSkillIsLive` |
| 从未上线的 skill，什么都不可寻址 | 提交两版、一版都没上线 → 裸地址、`@1`、`@2` **全是 404**。这一条抓到过一个真 bug：`versionOf` 在没有指针时**无视了 pin**，于是 `@99` 会被答成最新那一版草稿 | `SkillVersionPinIT.aPinnedAddressOnASkillNothingIsPublishedFromIsNotFound` |
| 作者面看得见草稿 | `/web/skills` 列出它、`current` 为 `null`、`drafts: 1`；`/web/skills/{ns}/{name}@2` 是 200 且 `state: draft`；正文取得到且是那一版的；`resources.body` 给的地址真的能取 | `WebSkillsIT` 前四条 |
| 同一个地址两个面两个答案 | `/web/skills/{ns}/{name}` 200 而 `/api/v1/skills/{ns}/{name}` 404。**两半都断言**——只测可见的那半，漏掉的那半仍然在 | `WebSkillsIT.theSameAddressAnswersOnOnePlaneAndNotTheOther` |
| 全部版本与各自状态 | 丢弃的那版仍在列表里、标 `discarded`；被顶替的标 `published` 且 `is_current: false`；指针仍指着 `@1` | `WebSkillsIT.everyVersionComesBackWithWhatWasDecidedAboutIt` |
| 上线 | 上线之后消费面才读得到；**重复上线是空操作**（200、`changed: false`、不写审计行） | `WebSkillsIT.publishingAVersionThatIsAlreadyLiveWritesNothing`、`SkillVersionPinIT` |
| **回滚**（上线一个已上线过的旧版本） | 指针回移、`live_at` 仍是那次**首次**上线的时刻、**被移开的那一版仍是 `published` 而不是被打回 draft**、消费面给的确实是旧版、而它自己的号仍然可寻址。这一条是本轮唯一一个测试全绿却在真库上炸掉的缺陷，见 §结果 | `WebSkillsIT.publishingAnOlderPublishedVersionRollsThePointerBack` |
| 丢弃是一票制 | 丢弃后**不能上线**（400 `invalid_request`，消息里说明「已被丢弃」）、不能再丢弃一次（400）、线上版本全程没动 | `WebSkillsIT.discardingIsOneWayAndOnlyADraftCanBeDiscarded` |
| diff：默认基准是线上 | `?to=2`（省略 `from`）→ `from: 1`；改动文件的 `added`/`removed` 与 hunk 行都对；**没变的文件不在答案里** | `WebSkillsIT.theDiffOfADraftAgainstLiveIsWhatPublishingItWouldChange` |
| diff：没有基准时全靠新增 | 没上线过时 `from` 为 `null`、每个文件都是 `added`。这不是错误——它正是「发布这一版会带来什么」 | `WebSkillsIT.beforeAnythingIsPublishedTheBaselineIsNothing` |
| diff：任意两版 | `?from=1&to=3`，中间那版不参与比较 | `WebSkillsIT.anyTwoVersionsCanBeCompared` |
| diff：上限如实标出 | 单元测试（真上传到 100 个文件太重）：文件数、行长、单文件大小三个上限各自触发时 `truncated` 为真、**未渲染的文件的增删行数是 `null` 而不是 0**；二进制文件只标「二元」且**不**置 `truncated`（那是文件的属性，不是被截断的） | `DiffServiceTest` 九条、`LineDiffTest` 六条（纯单测） |
| 源码里改的地址仍然解析得对 | `/web/skills` 是 `authenticated()`；令牌拿不到它、匿名 401 且不带 `WWW-Authenticate` | `WebSecurityPlaneIT` 新增两条 |
| 一个账号同时有两个面的凭据 | `registerAndSignIn` 返回 `user_id` → 按同一形状种一行 `client_credentials` 客户端 → 铸令牌。两份凭据都来自真端点，**没有削弱两个面** | `AbstractIT.tokenFor`，被 `SkillVersionPinIT` / `ServerSmokeIT` / `WebSkillsIT` 使用 |
| CLI 的三条解析 | 裸名字 → 技能目录下（与 `setup` 同一个事实）；含 `/` 或以 `.`/`~` 开头 → 按路径；不带参数 → 列出候选（且只列**有 SKILL.md 的**目录）。分类只看参数自身的字符，**不探文件系统** | `main_test.go` 六条（纯单测） |
| CLI 的深链 | 用 web 基址拼、两个段都转义、默认与服务端同源 | `main_test.go` 两条（纯单测） |

**前端**（`skillmaster-web`）：版本由号与状态两个字段组成的一行、**只有草稿才有「丢弃」按钮**、上线按钮发的是 `POST …/publish` 且带 `X-XSRF-TOKEN`、行前缀 `' '`/`'-'`/`'+'` 真的渲染出来（用 `textContent` 断言，因为 `text()` 会把那个前导空格剪掉）、`hunks` 为 `null` 时不打印 0、超限时明说、404 不报成错误、未登录跳 `/login?return_to=…` 且**回跳地址被转义**。用例数在 §结果 里，那里是唯一权威。

## 环境与数据

- **环境怎么造**：一台**没有装过任何 skill** 的机器或容器——`~/.agents/` 与 `~/.claude/skills/` 不存在或为空。这是 T1/T4 能成立的前提；在不干净的环境里跑，T4 的结论无意义。
- **数据**：至少一个**真实的、含 L3 文件**的 skill 被托管。建议用本机已有的 `lark-*` 系列（其 L1 约三分之一是中文，口径见 [`technical-design.md`](technical-design.md) §1.6，同时覆盖 T7）。不要用手写的玩具 skill——它测不出渐进加载的真实收益。
- **清理**：每次跑完记录快照；两次快照都要留档，作为 T4 的证据。

## 结果

**P0a 已执行**（2026-09-28）。`JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw -B verify` → **BUILD SUCCESS，138 个测试通过**（72 单测 + 66 集成）。**T0–T4b 未执行**，属 P0b。

**P0c 已执行**（2026-09-28，同一条命令）→ **BUILD SUCCESS，159 个测试通过**（80 单测 + 79 集成）。这一轮的动作有两半：把 P0a 那批**断言旧寻址的测试逐条重写**成 `namespace/name[@版本]`（旧契约的测试全绿不是新契约成立的证据，所以没有一条是靠打补丁留下的），以及**新增** `SkillVersionPinIT`、`SkillVersionServiceIT`、`SkillAddressTest` 三个类钉住序号语义、钉版与地址语法。**T0–T4b 仍未执行**，仍属 P0b。**P1 的账号设计已于 2026-10-01 定稿（[ADR 0013](../../decisions/0013-phone-login-and-username-slug.md)），尚未实现**——所以 T0 还要等 P1 的注册与登录，T0b 与上面那批 P1 用例同样都还没有证据。

**P0c 之后的循环审计已执行**（2026-09-28，`clean verify`）→ **BUILD SUCCESS，172 个测试通过**（89 单测 + 83 集成）。共四轮独立审计，范围是整条 `p0a-server` 分支相对 `main` 的全部改动；每一轮换一批**全新的** agent，主 agent 逐条复核后才动手。

修掉的 high 有六条，其中两条是**第一轮的修法自己引入的**（这正是循环审计要抓的东西）：

| 缺陷 | 怎么发现的 |
|---|---|
| 合法 SKILL.md 里一个空值 frontmatter 键（`license:`）→ 发布 500——`Map.copyOf` 拒绝 null 值 | 第 1 轮 |
| 畸形游标的第一段会被 `CAST(… AS integer)` → 500，而 §4.1 说该是 400 | 第 1 轮（复现） |
| blob 回收与并发发布之间的快照窗口 | 第 1 轮 |
| `%` 或 `;` 出现在 skill 名里 → 发得出去、**取不到、也删不掉** | 第 1 轮 |
| **修法引入**：第 1 轮那把锁放在 sweep 里太晚，两个**不同** skill 的并发发布互相等对方的插入锁 → 死锁 | 第 2 轮（`deadlock detected` 实测复现） |
| **修法引入**：第 2 轮把锁提前了，但仍没覆盖 blob 写入阶段——两个发布若共享 ≥2 个文件且 zip 内顺序相反，在 `ON CONFLICT DO NOTHING` 上 ABBA 死锁（这条 P0a 起就有） | 第 3 轮（实测复现） |

另有若干 medium（框架 404 回错码并丢掉 405 的 `Allow`、zip 里重复 `relpath` 撞主键变 500、`title: ""` 不回落到 name、`file_not_found` 定义了却从不发出、**自引用 YAML 别名**让 frontmatter 变成无限结构 → 500）与一批 low（注释与代码不符、测试名不副实）。

**每一条修复都配了会失败的回归测试**，其中三条关键的另做了突变验证（把生产代码改回旧行为，确认测试确实变红）：边读边计数的字节上限、锁的位置、blob 的存储顺序。测试数见本节末尾关于「数字从哪儿来」的说明。

**测试数一律取当次 `verify` 的输出。** 上面 P0c 那一行照抄的 159 与本轮 `clean verify` 重跑的结果对不上；**它当初怎么来的已经无从还原，此处不编一个原因**。能记下的是方法上的一条：`target/surefire-reports/` 会留下历史报告（本轮就有一个早已删除的临时探针类留下的报告，时间戳是几小时前的），**按报告文件数去数会数进历史遗留**，所以这一节的数字只能从命令输出读，不要沿用上一行。

| 用例 | 结果 | 证据 |
|---|---|---|
| T0 | **通过** | 服务端那半（注册 → 登录 → 会话）已通过：`WebRegistrationIT` 8 例、`WebLoginIT` 5 例、`WebSessionIT` 4 例、`WebCsrfIT` 5 例、`WebSecurityPlaneIT` 3 例。**CLI 那半与「拿到令牌」那半已在第十一轮执行完毕**：真账号在真浏览器里登录 → 同意页 → CLI 在 `127.0.0.1:51004` 收到码 → PKCE 换到令牌 → 带令牌的 publish/search/show/get 全通（证据见第十一轮那张表）。**至此 T0 整条走完**——包括那个此前唯一的空白：同意页被真人渲染并批准过 |
| T0b | **部分通过** | 会话那半已通过：`WebPasswordResetIT.resetEndsEverySessionTheAccountHad`（两个浏览器的会话都被撤销）。**令牌那半未执行**——M2 才有令牌可撤 |
| T1 | **部分通过** | `setup` 第一次对着真服务端跑（第十轮）：网关 skill 落在 `--dir` 指的目录里，**它是唯一被装上去的东西**。frontmatter 实测 **147 字符 / 271 字节**；**token 数没有用分词器量过**——按「每个汉字至少一个 token」这个下界算是 147 上下，离 200 的判据还有余量，所以这条**记作未严格验证**。未执行的另一半：默认落点 `~/.claude/skills` 没试（用的是 `--dir`），所以「装进真环境会怎样」不算有证据 |
| T2 | **部分通过** | 机械那半有证据（第十轮）：`search` 对着真服务端拿着真令牌跑通，**响应里只有地址、标题、描述**——没有正文、没有文件内容，也就是 T2 期望里「只返回 L1 卡片」那一条成立。**没跑的是判据的主体**：agent 自己从不点名的任务里命中网关 skill 并决定去搜——那要一个真 agent，不是一条命令 |
| T3 | **部分通过** | 同上：`show`（清单，无正文）→ `get` 正文 → `get <relpath>` 单文件，三步都在真服务端上跑通，且 `get` 用的 URI 是清单给的那个（钉版由此生效，`@1` 与 `@2` 分别取到两份不同的正文）。**没跑的是「按需」这个性质**——那是 agent 的取用顺序，不是命令的 |
| T4 | **未执行** | 机制那半看得见：`get` 把正文写进 `os.MkdirTemp` 的临时目录并**只打印路径**，全程没有往技能目录里落任何托管 skill 的副本；`setup` 只装了网关那一个。但 T4 的判据是**跑完 T1→T3 后对 `~/.claude/skills` 等目录做快照比对**，那次走查用的是另一个 `HOME` 与 `--dir`，所以不算执行过 |
| T4b | **未执行** | 要故意造一个副本再证明比对能发现它——依赖 T4 的快照手段先真跑一次 |
| T5 | **通过** | `AuthContractIT` 6 例、`InsufficientScopeIT` 2 例（含审计轮加的 HEAD 走读 scope） |
| T6 | **通过** | `SkillDetailIT.anotherUsersSkillIsNotFoundRatherThanForbidden`、`SkillContentIT.anotherUsersFileIsNotFound` |
| T7 | **部分通过 / 阻塞** | 功能：`SkillSearchIT.aTwoCharacterChineseQueryFindsTheSkill` 通过；索引：P0a 未用索引。`pg_bigm` 的可用性与其剩余待验项见 [`technical-design.md`](technical-design.md) §8 开放问题 3（同一个事实的权威在那里） |

**P0d 已执行**（2026-10-06，`JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw -B clean verify`）→ **BUILD SUCCESS，400 个测试通过**（185 单测 + 215 集成）。另外两条命令同期全绿：`cd skillmaster-cli && go test ./...`（六个包）、`cd skillmaster-web && npm run type-check && npm test && npm run build`（**261 个 vitest 用例**，`dist/` 211.8 KB JS / 9.3 KB CSS——比拆分前大了一倍，原因是 `SKILL.md` 从「原文等宽」改成「渲染成文档」（`markdown-it` 自己就约 41 KB gzip），另加作者面那四个页面）。测试数按惯例**取当次命令输出**。

这一轮把「提交」与「上线」拆成两个动作，落在三处：服务端（迁移 `V10` + M7 的三态与指针 + 作者面六个端点 + M9 的 diff）、网页（`/skills` 列表、详情、正文、diff、上线、丢弃）、CLI（`publish` → `submit` + 深链）。用例见上一节。

**这一轮抓到三条真缺陷**，三条都属于「原来的测试全绿也照样存在」：

1. **一版都没上线的 skill，`@99` 会被答成最新那一版草稿。** `versionOf` 在 `current_version_id` 为 `NULL` 时**直接返回最新未丢弃的版本，无视 pin**。ADR 0012 的整个语义就是「`@3` 是第 3 版或什么都没有」，而这个 bug 让它取决于这个 skill 有没有上线过。P0c 的用例没抓到它，因为那些用例里的 skill 都至少上线过一次。修法是一行判断加 `pin instanceof VersionPin.Latest`，回归用例是 `aPinnedAddressOnASkillNothingIsPublishedFromIsNotFound`——**先看到它红，再改的代码**。
2. **回滚抛异常，指针根本不动。** `publishVersion` 把 `markPublished` 返回 0 一律当成「并发丢弃赢了竞态」，而那个 0 还有一个正当来源：**版本已经是 `published`（被顶替过的），守卫 `state = 'draft'` 本来就该拒绝它**——而那正是回滚。于是「上线一个旧版本」在 P0d 的实现里是坏的，而它在别处都是对的（`VersionRepository.markPublished` 的注释写着回滚要保持原时间戳、网关**每次源文件回退**都会走到它）。**发现方式值得记下来**：单元与集成测试全绿，因为**没有任何一条测试覆盖过回滚**；它是**把当前代码对着一个有数据的真库启动时炸出来的**——网关开机自举走的正是这条路径，异常让整个应用起不来（`version 1 of 'skillmaster' was discarded while being published`）。修法是把状态分成两支（draft 才 `markPublished`，published 只前移指针），回归用例 `publishingAnOlderPublishedVersionRollsThePointerBack` 并做了**变异验证**（把分支改回 `if (true)`，那条新用例变红，且报的就是开机时那句原话）。
3. **前端拿着 `responseType` 缺省去读 `text/markdown`**（在实现期发现，未进主干）：正文端点的响应不是 JSON，走原来的成功分支会被 `JSON.parse` 判成 `internal_error`——一个关于我们自己读取方式的错误，被呈现成服务端的失败。加了 `responseType: 'text'` 这一支。

**迁移 `V10` 在一台有数据的库上单独验过一次**（2026-10-06），因为 `clean verify` **验不到它**：测试用的 Flyway 先 `clean()` 再 `migrate()`，所以 V10 在测试里永远跑在空表上，而那两条 `UPDATE` 回填（`state`/`state_at` 与三列元数据）是为**已有行**写的。做法是拿本机那台有 2 个 skill、3 个版本的 dev 库起一次服务端：`Migrating schema "public" to version "9" / "10"` → `Successfully applied 2 migrations … now at version v10`（14 ms）。回填后 3 行全部 `state='published'` 且 `state_at = submitted_at`，3 行都拿到了**真实的** `title`/`description`/`frontmatter`（不是空串），两个 skill 的指针都还在。`CHECK ((state = 'draft') = (state_at IS NULL))` 没被违反。

这条验证同时暴露了一件操作上的事，记在这里以免重演：**跑迁移会让正在运行的旧进程失效**。那台 dev 服务端是 1 天前起的、跑的是 V10 之前的代码，schema 一变，它的 `/gateway/SKILL.md` 立刻 500（`skill_version.published_at` 这个列名已经不存在了）。迁移是单向的，所以**库一旦前进，能跑这套栈的就只剩当前这棵树**——升级前先停服务端，或者接受重启。

**那次联合手工验证已经走过**（2026-10-06）：当前树的 CLI + 真服务端（`8080`，dev 库）+ 真浏览器（Vite `5173` 上的 SPA）。用的是本机那个测试 skill `lijinzhao/hello-skillmaster`，新的一版改了 frontmatter 的描述、改了一行、加了 `references/advanced.md`（这个文件在两段路径下）。

| 步 | 动作 | 观测 |
|---|---|---|
| 1 | `skillmaster submit <目录>` | 返回 `@2` 草稿与 digest `sha256:07ddba24…`，并打印「这一版还是草稿，线上没有任何变化」 |
| 2 | 提交后从**消费面**读 | `search` 仍是旧描述；`show` 仍是 `@1`、2 个文件 373 字节；`show @2` → **404** |
| 3 | 在网页上点「上线」（只能由人在浏览器里做，§4.3） | —— |
| 4 | 再从**消费面**读 | `search` 换成新描述；`show` 给 `@2`、3 个文件 784 字节，digest 与第 1 步打印的**是同一个** |
| 5 | 读 `@1` | `show @1` 仍 200，且报的是**它自己的**描述与自己的 2 个文件 373 字节；`get @1` 的正文仍是旧的那版 |
| 6 | `get` 与 `get references/advanced.md` | 跟指针取到 `@2` 的原始字节（含 frontmatter，服务端不改写）；两段路径的文件取得到 |

于是三条断言第一次有了端到端证据：**提交不碰消费面**；**上线是唯一让内容生效的动作**；**被顶替的版本既按地址取得到、也带着自己的元数据**——最后一条是 `V10` 给 `skill_version` 加那三列的直接后果，没有它，上线会连历史版本的描述一起改写，钉版就成了空话。

**仍未走的是回滚**（把 `@1` 再上线一次）：它是唯一一条曾经真的坏过、而缺陷只在「对着有数据的真库启动」时才现形的路径（本节第 2 条缺陷），集成测试已用变异验证钉住，**但浏览器里那一次没点过**。

**T0–T4b 的状态没有变**：agent 侧的验收仍然要一个真 agent。CLI 那半（深链的形状与转义、三条参数解析）与网页那半（按钮、状态标签、diff 渲染）各自有单测，而**两者合起来那一次现在开始有证据了**。

**P0d 的循环审计**（2026-10-06，三轮，每轮换一个全新的审计员；范围逐轮收窄——第一轮整棵改动、第二轮针对第一轮的修复、第三轮针对第二轮改出来的东西）。**三轮都没有出 blocker**，修掉的缺陷按面分：

- **服务端**：作者面详情在一个「一版都还没有」的 skill 上 500（`AuthoredSkillResponse.of` 对着空版本列表抛），改成 404；`publishVersion` / `discardVersion` 把 `markPublished`/`markDiscarded` 返回 0 一律当成竞态，于是**并发丢弃与并发上线**各走错一边（新增 `concurrentPublishesOfTheSameVersionAllSucceed`、`concurrentDiscardsOfTheSameVersionAllSucceed`），修法是重读那一行再分辨它到底是「已被丢弃」还是「已经是这个状态」；`moveCurrentVersion` 缺 `IS DISTINCT FROM`，于是把同一个版本上线两次也报「指针动了」并写一条审计行；`versionOf` 的 `Latest` 兜底会把**已丢弃**的版本当成最新；`DiffService` 的单文件超限分支没有置 `truncated`（页面于是自称完整，其实少了一个文件）。
- **网页**：`/skills/<ns>/<name>/files/<relpath>` **从来没被路由到**（段数上限把它挡在外面），失败的表象是列表页在一个没有 `skills` 键的载荷上崩掉；正文页把 401 吞掉，于是页面永远停在「加载中」；diff 页在 `?to=` 指向一个不存在的版本时把整页判成「没有这个 skill」，为一个手误的版本号否掉作者自己的 skill。
- **CLI**：一次内容**已经在线上或已被丢弃**的提交，`submit` 仍旧说「这一版是草稿、去点上线」，而那一版根本点不了——响应里的 `state` 由此进契约，CLI 照它说话；`Archive` 走 `Stat` 而非 `Lstat`，一个**符号链接指向的目录**被打成**合法但空**的包（服务端于是说「上传里没有文件」，把链接的账算在 skill 头上）。

**第三轮（收口轮）**的结论是**没有 blocker 也没有 high，三条 low**，两条是代码、一条是产品面：`BlobGc` 注释里一个改名后失效的符号（`upsertLive`）；`DiffService` 把「是不是二进制」判在「文件数上限」**之后**，于是一个仅仅排序落在界外的二进制文件会让响应自称被截断（类文档写明二进制不该置 `truncated`）；**作者列表**对「一版都没上线的 skill」读的是 `skill.title`，而它**只由建行与上线写、从不由提交写**，于是永远停在第一次提交的标题上，和这张卡片自己打开的那个详情页说的**不是同一个名字**（详情页走的是「指针，否则最新未丢弃」）。三条都已修，最后一条的回归用例是 `theListingNamesASkillAfterItsNewestSubmissionWhileNothingIsLive`。

**审计后的收口**：`clean verify` BUILD SUCCESS，**400 个测试通过**（185 单测 + 215 集成）——比审计前多的一条就是上面那条新用例；`cd skillmaster-cli && go test ./... && go vet ./...` 全绿（`Archive` 的尾斜杠那一条做过**变异验证**：去掉 `Clean` 只它红）；`cd skillmaster-web && npm run type-check && npx vitest run && npm run build` 全绿（261 用例）。


**M01 已执行**（2026-10-01，`JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./mvnw -B clean verify`）→ **BUILD SUCCESS，230 个测试通过**（109 单测 + 121 集成）。这一轮新增 9 个集成测试类与 3 个单测类，见上一节。**T0b 仍未通过**——它要的是「改完密码后拿新密码走一遍完整登录并拿到令牌」，会话那半边已被 `WebPasswordResetIT` 钉住，**令牌那半边要等 M2**；所以 T0b 记作**部分通过**。

**本轮证据同样是自动化测试**。计划里原本还写着一条「手工走一遍 `/web/register/code` → 日志取码 → `/web/register` → `/web/session` → `/web/logout` → `/web/session`」，**没有单独执行**：同一串请求已经被 `WebSessionIT` 端到端钉住（唯一差别是取码走 `RecordingSmsSender` 而不是读日志），手工再走一遍不会多出信息。**没有证据的仍然只有 CLI 与 agent 侧**，那一层还不存在。

**同一天的第二轮**（`iterations/0003`：图形验证码 + 阿里云短信）→ `clean verify` **BUILD SUCCESS，246 个测试通过**（115 单测 + 131 集成）。新增 `WebCaptchaIT`（10 例）、`HutoolCaptchaRendererTest`（3 例）、`AliyunSmsSenderTest`（3 例），并在 `AbstractAccountIT` 里加了「取一张验证码并答对它」的编排——四个发短信的用例因此一行没改就继续跑。

**这一轮有一件事是「做了但没验证」**：`AliyunSmsSender` 与阿里云之间**一次真实往返都没有**（没有凭据、签名与模板也未过审）。它这一侧的行为全被单测钉住了，但「短信真的能到」这件事没有证据，见本节末尾的未验证清单。

**本轮的两条发现**值得单列，因为两条都属于「测试全绿也照样存在」的那一类：

1. **验证码的尝试次数被事务回滚掉了**（拒绝猜错靠抛异常表达，异常回滚了计数）——`WebRegistrationIT` 里那条专门钉它的用例第一次跑就红了，而**其余每一条都是绿的**。修法与不变量见 [`architecture/modules/M01-account-login.md`](../../architecture/modules/M01-account-login.md) §验证码。
2. **「把 `initialize-schema` 改成 `always` 再跑一遍」这个验证方法是无效的**：测试用的 Flyway 先 `clean()` 再 `migrate()`，第二个建表器留下的痕迹被 clean 掉了，于是**改坏了也全绿**。改看「bean 在不在」才看得见，`SessionSchemaOwnershipTest` 是这个断言，并做了突变验证（`always` 变红、`never` 变绿）。详见 [`iterations/0002`](iterations/0002-m01-login-server.md)。

**同一天的第三轮**（[`iterations/0004`](iterations/0004-web-frontend.md)：浏览器端页面）→ 新增子项目 `skillmaster-web/`，**没有改服务端一行**。`cd skillmaster-web && npm ci && npm run type-check && npm run test && npm run build` 全绿：`vue-tsc` 在 strict + `noUncheckedIndexedAccess` 下无错，**48 条 vitest 用例通过**，`dist/` 产出 80 KB JS（gzip 30 KB）。CI 是新增的 `.github/workflows/web.yml`，**从没跑过**——它不需要数据库也不需要服务端，所以能在服务端 CI 停着的时候独立跑起来。

这 48 条钉住的是**前端这一侧**特有的错误，而不是「服务端也会拒」的那些：每个 POST 都从 cookie 现读 CSRF 头（**登录后轮换过的那一个也要跟上**，模块级缓存正是最容易出的 bug）、`credentials` 是 `same-origin` 不是 `include`、429 在**没有** `Retry-After` 时给兜底而不是 `NaN`、反代返回 HTML 502 也能解成可显示的错误、**25 个汉字的密码是 75 字节因此必须被拒**（用 `.length` 会放过去）、登录页**一个验证码请求都不发**、以及 `return_to` 只接受同站路径（`//evil.example` 也要挡住）。

**本轮又发现一条「测试全绿也照样存在」的缺陷**，与前两轮同类：`RegisterPage` 的手机号输入框只渲染第一步（发码）的错误，于是 `POST /web/register` 对 `phone` 的拒绝——**正是 `already_registered` 这一条**——被静默丢掉。是新增的用例先红才暴露的，已修。

**这一轮的核心未验证项，是上面那段测试清单的边界**：48 条全是 stub `fetch` 的单测，它们证明的是前端**自己的**理解自洽——把 `captcha_id` 打成 `captchaId`，这些用例会跟着一起错。

**所以补了一次「经 Vite 代理打真服务端」的走查**（2026-10-01，本机，PostgreSQL + `spring-boot:run` + `npm run dev`，全程 `curl` 打 `http://localhost:5173`，也就是浏览器会走的那个源）。**没有浏览器**——所以它验的是线路格式与整条会话/CSRF 时序，不是 Vue 的渲染。走完的是：

| 步骤 | 结果 |
|---|---|
| `GET /web/session` 匿名 | **401 `unauthenticated`**，且**响应本身带 `XSRF-TOKEN` cookie**、`details` 是 `[]`——前端「第一个请求就是 CSRF 引导」这个设计成立 |
| `GET /web/captcha` | **200**，字段恰好是 `captcha_id` / `image`；`image` 是**裸 base64**（不带 `data:` 前缀），与 `useCaptcha` 自己拼前缀的写法一致 |
| `POST /web/register/code`（`phone`/`captcha_id`/`captcha_answer`） | **204**。**这一步是线路格式的正面证据**：字段名、`X-XSRF-TOKEN` 头名、双提交 cookie 三样全对，服务端才可能收下 |
| `POST /web/register` | **201**，响应恰好是 `{user_id, username, namespace}`；同时下发 `XSRF-TOKEN`（**轮换了**）与 `SKILLMASTER_SESSION`（`HttpOnly; SameSite=Lax`，与设计一致） |
| 用**轮换后**的新 token 调 `POST /web/logout` | **204，不是 403**——已知问题 8「登录后第一个写请求必被 403」在真实链路上确认修好了 |
| `POST /web/login`（不发验证码） | **200**——登录确实不要图形验证码，与前端不发那一步一致 |
| `POST /web/login` 密码错 | **401 `invalid_credentials`，`details` 为 `[]`**——正是前端把它整条挂横幅、不落到密码字段的依据 |
| `POST /web/reset` 全流程 | **204**；重置**之前**那个会话随后 **401**（全部会话被撤销）；旧密码 **401**、新密码 **200** |
| 11 次登录失败 | 第 11 次 **429 且带 `Retry-After: 557`**——倒计时读的就是这个头；**窗口内用正确密码也是 429** |
| 不带 CSRF 头的 POST | **403 `forbidden`**，与前端 `CODE_MESSAGES` 里的码一致 |
| 四条镜像规则的 issue 码 | `password/too_long`（**25 个汉字 = 75 字节**）、`username/invalid_format`（前导连字符）、`username/invalid_format`（`飞书`——**服务端也先判字母表再判长度**）、`phone/invalid_format`。**四个码与前端 `ISSUE_MESSAGES`/`validation.ts` 的假设逐个对上** |

**因此线路格式已不再是「零证据」，但也没有全绿**：剩下的是**浏览器里那一层**——Vue 是否真的渲染、点击是否真的发请求、`data:` 前缀的图能不能显示、`/register` 刷新会不会 404（那要看反代，见 ADR 0015）。这些没有自动化也没有手工证据。要在 CI 里补自动化得先有真服务端加真库，那是另一个决定，不是本轮能顺手做的事。

> 顺带一条不是缺陷但值得记的：验证码的**人工可读性**此前登记为「未验证」。这次走查里连读两张（`RJUG`、`2CXL`）都一次读对——**但那是一台能读图的机器，不等于一个人在小屏手机上看得清**，所以这一条仍留在未验证清单里，只是不再是零证据。

**同一天的第四轮**（[`iterations/0005`](iterations/0005-completing-the-validation-tests.md)：补齐校验类测试）→ 服务端 `clean verify` **BUILD SUCCESS，265 个测试通过**（130 单测 + 135 集成，**全跑完 12.2 秒**）；`skillmaster-web` 的 vitest **48 → 64 条通过**（全跑完 0.5 秒）。服务端**一行未改**，前端改了一行。

补的原因是：`PasswordPolicy`、`PhoneNumberPolicy`、`LoggingSmsSender` 三个类**在 `src/test` 里一次都没被引用过**——把 72 字节上界整个删掉，上一轮那 246 条仍然全绿。补上的是 15 条单测（6 + 5 + 4）与 4 条端点级断言：

| 补在哪 | 钉住什么 |
|---|---|
| `PasswordPolicyTest` | 8 / 72 两个压线、7 字节、**24 与 25 个汉字（72 与 75 字节）**、73 字节、两条 `same_as_*`、`required`、「太短」**先于**「与用户名相同」，以及**相等而不是包含** |
| `PhoneNumberPolicyTest` | 第二位 3–9（10/11/12 皆拒）、10 与 12 位、`+86` / 带空格 / 带连字符 / 尾随字母 / **全角数字**、`required` |
| `LoggingSmsSenderTest` | 写码时**号码只出现掩码形态**；未配置时**抛异常、且一行都不写**（对着 Logback 的 `ListAppender` 断言） |
| `WebLoginIT` +3 | 手机号形状错 → **400 字段错**（与三种分不清的凭据失败有意不同）；**空密码/缺失密码 → 401 且两者逐字节相同**（登录从不校验密码形状，这条同时把「别用 500 回答它」钉住——实测 Spring 的 `matches(null, …)` 返回 false）；**成功登录会清掉累计失败次数** |
| `WebRegistrationIT` +1 | 发码端点的非法手机号 → 400 且**一条短信都没发**（断言 Sender，不是状态码） |
| `WebPasswordResetIT` | `no_account` 的 **issue 码**本身（此前只有 `field`），它是前端「这个手机号还没有注册」那句文案的依据 |
| 前端 `reset-flow.test.ts` | 找回密码页此前**没有测试文件**：发码字段名、**提交体没有 `username`**、204 后**显示「密码已重置」且不跳转**、`no_account` 落字段 |
| 前端 `countdown.test.ts` | 倒计时用**假时钟**：逐秒、到 0 停住、重启不留下旧 interval、卸载不留定时器 |
| 前端 `validation` / `errors` | 73 字节；`123` / `a--b` / 尾连字符（照服务端用例抄，让两侧钉在**相同答案**上）；**词汇表**：服务端能发的每个 issue 码都有中文文案，API 面专属的四个码**必须回落** |

**第二轮**（同一天，要求从「补齐校验类」扩到「前后端全部补齐」）→ 服务端 **265 → 285**（150 单测 + 135 集成，`clean verify` 整条 **12.0 秒**）；前端 **64 → 105**（13 个文件，全跑完 **0.6 秒**）。

这一轮先做了一次**零测试普查**：不按文件名找，而是按「有没有任何测试——含集成测试——断言过这个行为」找。20 个有分支的候选里 4 个是真缺口，其余都被集成测试覆盖（`CaptchaService` 的五个分支由 `WebCaptchaIT` 十条覆盖，`SearchRequest` 的 limit 规则由 `SkillSearchIT` 覆盖，等等）。补的是：

| 新测试类 | 此前没有任何证据的那条行为 | 条数 |
|---|---|---|
| `CursorCodecTest` | `escape()` 只有 `encode` 走得到，而没有任何 fixture 把 `"` / `\` / 换行 / 控制字符放进游标；手拼的 JSON 一旦非法，`decode` 返回空 → 读的人当成「从头再来」，**查询成功而页是错的**。另把 `decode` 的拒绝形状钉全（「坏查询串绝不是 500」） | 6 |
| `TimestampsTest` | 截断到整秒**不是装饰**：`audit_event(at DESC)` 与键集游标按字符串比，带不定长小数时文本序与时刻序相反 | 5 |
| `WellKnownDigestTest` | 集成测试只断言形状。它是**内容**摘要靠两条性质：按 path 排序（否则客户端每次检查都以为变了、永远重下）与 NUL 分隔（空格分隔会让 `("a b","c")` 撞 `("a","b c")`）。两条都是从反编译客户端读出来的，最容易在整理时丢掉。另补了类注释里声称存在、实际并不存在的**冻结向量** | 4 |
| `LikePatternTest` | `%` 与 `_` 有集成测试，**转义符自己（`\`）没有**——不翻倍则搜 `\d` 到 LIKE 就是字面 `d`，静默返回错的行 | 5 |
| 前端 `session` / `app` / `home` | 401 是「没人登录」而不是失败；**请求根本没到也要把 `loaded` 置真**（否则永远停在「加载中…」）；按路径分发页面；**会话读回来之前什么都不渲染**（服务端只在应答时写 CSRF cookie，渲染早了的表单第一次提交必被拒） | 18 |
| 前端 `captcha` / `code-request` / `components` | 取图失败时**同时清掉 id**；两条换图规则（换，与**不该换时不换**）；429 用**服务端给的秒数**；四个小控件的三态按钮与「没话说时什么都不渲染」 | 23 |

**四条关键断言做了突变验证**（改坏生产代码确认变红，随后逐字节还原、`git diff` 为空）：`LikePattern` 去掉转义符分支、`WellKnownDigest` 去掉 `.sorted(...)`。

**速度这条是要求，也已经成立**：整套测试树里**没有任何 `Thread.sleep`**（只有 `SkillPublishIT` 用 `CountDownLatch`，那是并发同步）。依赖时钟的一律**改数据而不等**——冷却与日限挪 `auth_throttle.window_start` 那一行，验证码过期改 `expires_at`；前端倒计时用 `vi.useFakeTimers()`，共用一个假时钟的 8 个发码用例也不等待。

**本轮又找到一条真缺陷**，与已知问题 13 是同一个模式：`ResetPage` 的手机号字段也只渲染第一步的错误，于是 `POST /web/reset` 的 `no_account` 被静默丢掉——用户点下「重置密码」，屏幕上什么都不变。同样是新增用例先红才暴露的，已修。

**同一天的第五轮**（[`iterations/0006`](iterations/0006-password-blocklist.md)：密码策略加黑名单）→ 服务端 `clean verify` **BUILD SUCCESS，299 个测试通过**（162 单测 + 137 集成，12.1 秒）；前端 **105 → 109**。

这一轮改的是**一条规则**，不是新能力：密码多一条被拒的理由。触发是一句反问（「不该要求大小写特殊字符数字吗，参考 Apple」），查证后发现 Apple 的注册表单确实要求四类字符，而 NIST / OWASP 明确反对组合规则并要求黑名单——**取舍与理由全在 [ADR 0016](../../decisions/0016-password-blocklist-not-composition.md)**，§结果 只记证据：

| 加了什么 | 钉住什么 |
|---|---|
| `PasswordBlocklistTest`（新，6 条） | 真文件能加载且 >9000 条；大小写不敏感；**逐字节的 sha256**（换名单必须同时改类注释与这条断言，让「换掉一份安全数据」成为有意识的动作）；CRLF / 空行 / 前后空格的解析；文件缺失与条数不足**两种响亮失败**；过短的条目由「太短」先拒而不是「太常见」 |
| `PasswordPolicyTest`（6 → 12 条） | 黑名单命中与大小写；**包含但不等于必须通过**（`password-and-then-some-more`，否则被拒的是策略想鼓励的长口令）；handle + 数字，含**handle 自己以数字结尾**这一例；手机号在任意位置；长口令不因握有 handle 被误伤 |
| `WebRegistrationIT`（+2 条） | **端点真的查了名单**（`password` → 400 `password`/`too_common`），以及 handle 派生。单测证明规则、这两条证明 bean 接上了——「一份没人查的名单」是两者共同防的那件事 |
| 前端 `validation.test.ts` / `errors.test.ts` | 两条派生规则与它们的边界；重置页（无用户名）不被误伤；词表加 `too_common` |

**一条实现缺陷是集成测试抓出来的**，值得单列：派生规则若写成「去掉密码**尾部**的数字再与 handle 比较」，当 handle 自己以数字结尾时会漏掉——后缀剥离会吃掉 handle 的末位数字。`randomUsername()` 生成的正是 `u` + 16 位十六进制（天生于数字结尾），所以它第一次跑就红；手挑的 `demo-user` 永远抓不到。已改成「以 handle 开头、其后只剩数字」，两侧同改并各自加了用例。

**这份防御的边界要如实读**：名单是 vendored 的 SecLists 10k（英文泄露语料），10001 条里只有 **2087 条长到能被提交**，其余先被 8 字节下限拒掉——是死重量而不是漏洞；中文场景的高频弱口令只覆盖一部分，那半边由「手机号 / 用户名派生」两条规则兜。**「挡住了所有弱密码」不是这份证据支持的结论。**

**同一天的第六轮**（[`iterations/0007`](iterations/0007-password-charset-ascii.md)：密码字符集收成 ASCII）→ 服务端 `clean verify` **BUILD SUCCESS，304 个测试通过**（166 单测 + 138 集成，12.0 秒）；前端 **113**。

这一轮是上一轮的**直接后果**：黑名单加完的当天实测发现，全角 `ｐａｓｓｗｏｒｄ`（每字符 3 字节）长度合法、名单是 ASCII 的查不到——**绕过黑名单**，而它对中文用户不是刁钻输入，输入法留在全角模式就是这个。取舍见 [ADR 0017](../../decisions/0017-password-printable-ascii-only.md)。

| 加了什么 | 钉住什么 |
|---|---|
| `PasswordPolicyTest`（12 → 16 条） | 键盘字符全收（含空格，长口令友好的设计没丢）；**中文与全角被拒**；**字符集先于长度**（`密码` 得到字符集错而不是「太短」）；纯空白算没填（半角与全角各一） |
| `WebRegistrationIT`（+1 条） | **端点真的按新规则拒**：全角 `ｐａｓｓｗｏｒｄ` → 400 `password`/`invalid_format` |
| 前端 `validation.test.ts` / `register-flow.test.ts` | 规则同改同顺序；**全角密码在页面上被本国拒且一个请求都不发** |

**四条旧用例被改写而不是改值**，这是这一轮最值得记的操作：那几条用中文密码当 fixture，测的是「**字节与字符不一致**」——而这条属性**现在不可观测**了（非 ASCII 到不了长度那一步）。所以长度用例换成 ASCII 压线值，字节语义移回还能观察它的 `BCryptPasswordHasherTest`；两个页面的用例保留原意（本地拒绝 → 不发请求），fixture 换掉。**当旧断言与新规则直接矛盾时，正确动作是重新问「这条属性现在还可测吗」，不是把它改成新值继续绿着。**

**这一轮的边界**：中文口令不再可用，这与 NIST / OWASP 的建议相悖（OWASP 原话是「不应有限制字符类型的规则」）——它是**有意的产品取舍**，不要读成合规做法。方向不可逆：将来要支持中文必须连同归一化一起做，并处理存量；而现在是零迁移成本的窗口。

**同一天的第七轮**（[`iterations/0008`](iterations/0008-blank-criterion-alignment.md)：空白判据对齐）→ 服务端 `clean verify` **BUILD SUCCESS，307 个测试通过**（169 单测 + 138 集成，12.0 秒）；前端 **117**。

这一轮**不是新能力，也不是新的拒绝理由**——是核查「前后端都适配了吗」时，用**差分法**发现两侧对「什么算没填」的定义不一致：

| | 服务端（改前） | 前端 |
|---|---|---|
| 8 个 NBSP（U+00A0） | `invalid_format` | `required` |

做法是**把同一批 22 条输入同时喂给两份真实实现、逐条比码**（Java 侧用 `jshell` 调 `PasswordPolicy`，前端侧用一个临时 vitest 文件，跑完即删）：**20 条一致、3 条是有意的黑名单缺口**（客户端不下发名单，所以它只可能更弱）、**1 条是真分歧**。顺着查发现用户名与手机号同样分家——所以这是三个字段共有的，不是密码独有。

| 加了什么 | 钉住什么 |
|---|---|
| `TextTest`（新，3 条） | **逐字符覆盖整个 White_Space 集合**（25 个码点，含两个 Java 判据都漏掉的 U+0085）；两个「语言内置会答错」的反例——U+001C–U+001F 是 Java 的 whitespace 却不是 White_Space，U+FEFF 是 JS 的 whitespace 却不是 |
| 前端 `validation.test.ts`（+4 条） | 同一张表；U+FEFF 对 `trim()` 是空、对镜像不是；U+0085 反之 |

**这条分歧用户看不见**：两侧都在拒绝，只是理由不同，而客户端先判、判完不发请求。修的是**镜像的纯度**。**修在服务端一侧**，因为 White_Space 是 Unicode 的字符属性、有定义，而 `Character.isWhitespace` 是 Java 关于换行的规则——反过来要在 TS 里硬编码 Java 的例外表，是把一份语言怪癖冻进第二种语言。

**第一版改法是错的，被自己的测试当场打回**：我先写成「两个 Java 判据取并集」，跑测试红在 U+0085——那两个判据**都**漏掉它，所以并集并不等于 White_Space。教训是「靠内置」与「靠定义」的差别**只有在把集合逐字符写出来之后才看得见**。最终两侧都改成显式列出集合。

**同一天的第八轮**（[`iterations/0009`](iterations/0009-four-round-audit.md)：四轮循环审计）→ 服务端 `clean verify` **BUILD SUCCESS，320 个测试通过**（170 单测 + 150 集成，约 11 秒）；前端 **131**（外加 `vue-tsc` 与 `npm run build`）。

这一轮的**方法**本身就是结论的一部分：范围是当时全部未提交的改动，**每轮换一批全新的、互不共享上下文的 agent**，各自按同一份定级程序判定（blocker/high 必须附可复现的失败场景，拿不准就往低报并标 `uncertain`），主 agent 逐条复现后才动手。四轮共 13 条 CONFIRMED，其中 **2 个 blocker、6 个 high**。

**四轮里有两轮抓到的是上一轮修复本身引入的缺陷**，这是唯一值得记下的经验：

| 轮次 | 上一轮修了什么 | 修法自身的缺陷 |
|---|---|---|
| 2 | `return_to` 只比 origin | 只挡跨源：**同源** URL 的 pathname 以 `//` 开头时，交出去的字符串被 `location.assign` 二次解析成新 authority（已用 node 复现） |
| 2 | 按地址的登录预算改为「只在失败时计」 | 该规则在鉴权之后判断，拒绝时整笔事务回滚，把**手机号**的计数一并抹掉——单个账号的猜测遂无上限 |
| 3 | 给请求加 15 秒超时 | 发码端点先消费验证码、再等短信通道：超时后用户手里的码用不了，验证码也死了；且超时被报成「网络请求失败」 |

| 加了什么 | 钉住什么 |
|---|---|
| `WebLoginIT`（+2 条） | 地址预算只由失败花掉、成功不花；地址被耗尽时手机号的计数**仍在走**（去掉 `noRollbackFor` 即变红） |
| `AccountThrottleIT` / `WebCaptchaIT`（各 +1 条） | 按地址的两条限流（原先六条规则里两条无人能测）；`X-Forwarded-For` 真的被读——两个转发地址必须是两个计数桶 |
| `WebPasswordResetIT`（+3 条） | 三次错码后连正确码也被拒且 `attempts = 3`；同码重放被拒；**新码作废旧码**；重置只撤销本账号的会话 |
| `WebSessionIT`（+1 条） | 会话 id 在登录时轮换——原用例先登出，于是新 id 怎么都会变，绿得没有意义 |
| `PasswordPolicyTest` / `AesGcmPhoneCipherTest` | 8 与 72 两个数字写成字面量；AES 短值判据逐档长度（0–27） |
| 前端 `client` / `login` / `code-request` / `captcha` / `session` / `errors` | 超时真的会 abort（信号感知的 stub，不是模拟）；跨 realm 的 `TimeoutError` 仍认作超时；body 流中断、非 204 空 body；发码**结果未知**时打开验证码那一步；`return_to` 的两种反斜杠与三种退化值；验证码/会话拿到 204 或 JSON `null` 时不抛异常 |

**这一轮的边界**：审计是**代码与测试层面**的，浏览器那一层仍未跑过（§已知问题 12 不变）；`forward-headers-strategy` 只能测到「头被读了」，测不出「代理是覆写还是追加」——那条前提靠人守，见 [ADR 0018](../../decisions/0018-caller-address-behind-the-proxy.md)。

**夹在第八与第九轮之间的 [`iterations/0010`](iterations/0010-local-database-in-a-container.md)**（本地数据库进容器）**没有单独一轮**，因为它**不改对外可验收的行为**——没有新端点、没有改契约、`application.yml` 的默认值一个没动。它要证的是另一件事：**同一套测试换个数据库环境仍然全绿**（容器里 320 条，见 0010；第九轮那 340 条也是在同一个容器上跑的，`docker ps` 里是 `skillmaster-postgres` / `postgres:16.15`）。环境怎么造与排序规则为什么选 Debian 版，权威在 0010 与 [ADR 0019](../../decisions/0019-local-database-in-a-container.md)，这里不复述。

**同一天的第九轮**（[`iterations/0011`](iterations/0011-register-flow-and-sms-state.md)：注册流程重做、首个发码免图形验证码、`accept-any-code`）→ 服务端 `clean verify` **BUILD SUCCESS，341 个测试通过**（170 单测 + 171 集成）；`skillmaster-web` 的 `vue-tsc` 无错、**203 条 vitest 通过**、`npm run build` 通过。新增 `WebAcceptAnyCodeIT`（5 例，**自带一个 Spring 上下文**，因为这个开关是启动时读的配置）、`WebUsernameAvailabilityIT`（8 例），`WebCaptchaIT` 从 10 例扩到 17 例。

**第九轮跑完后又跑了多轮循环审计**（同一天，改动尚未提交，所以算作这一轮的一部分）。每一轮都是**全新的独立 agent**，发现的问题由主 agent 逐条复现后才动手改，改动都带钉子：

- **第一轮**：两条**高危**。一条是环境隔离——`SKILLMASTER_SMS_ACCEPT_ANY_CODE=true` 一旦从环境里导出，会盖过 `application-test.yml`，于是**整个测试套件都在不比对模式下跑**，`WebRegistrationIT` 里那条「码不对就拒」的用例变成绿的假象；已复现（导出后单跑那条用例，期望 400 实得 201），修法是给 surefire/failsafe 钉上系统属性（系统属性优先于环境变量）。另一条是「重新获取验证码」这个按钮**根本发不出第二条**——一分钟冷却过后它仍然只会前进，于是码超时或没收到时没有任何办法再要一条。同轮还修了三态校验的两处、清空/编辑后残留的服务器消息，以及服务端 `accept-any-code` 不校验码形状（`""` 也会通过）。
- **第二轮**：又一条**高危**——「重新获取验证码」真的再发一条码**失败**时（图形验证码填错、或撞上限流），页面仍然前进到验证码那一步并宣称短信已发出，而那句拒绝渲染在**下一页没有的字段**里。根因是拿 `sent` 判断「要不要进下一步」，而它从第一次发码起就一直是 `true`；改成由发码本身回答（`requestCode()` 返回布尔）。同轮还修了：按钮文案与动作不符（回退修用户名的状态下按钮只会前进却说「重新发送验证码」，而且被 60 秒冷却挡着）、清空字段不撤掉服务器那句话、两处 `data == null` 未设防、改用户名会抹掉服务器对**密码**的判定。服务端同轮修了三处注释与一条**名不副实的用例**（`WebUsernameAvailabilityIT` 里那条「保留名没有 app_user 行」——V2 其实给系统账号起了同名 handle，所以把查命名空间那一路删掉它照样绿；已改成真插入一行没有同名 handle 的 `namespace`，并用**变异验证**确认：删掉那一路，只有新用例会红）。
- **第三轮**：一条**中等**是上一轮修复**自己引入的回归**——密码规则读用户名与手机号，而上一轮把「依赖变了」也当成「这个字段被改了」，于是把手机号改对之后，那句「密码不能与手机号相同」赖着不走，而消息在就代表「不合格」，于是按钮永久变暗、只能靠重打一个本来没问题的密码脱身。修法是让本地规则自己的话可以被依赖变更撤回，而服务器说的话不能——最后落在「记住本地那句话的原文」上（页面会整张替换 `problems`，标记会失效，原文不会）。同轮还修了：手机上正在飞的那一次请求期间改号码，第二页会宣称短信发到了新号码（改成请求发出前先记下号码，第二页也跟着显示这个号码），以及两个页面的步骤级消息 watch 会连另一个框的消息一起清掉。服务端同轮只有注释与一条名不副实的指针。
- **第四轮**是定向复核第三轮那处修复（前两轮各有一条修复自己带出新问题，所以改动共享逻辑的那一处要单独再验一遍）。两个方向都被攻破，两条都是**中等**：一条是**服务器那句被覆盖**——本地规则对「密码由用户名拼出来」与服务器对「密码在常见口令表里」用的是**同一句话**（`too_common` 一个字面两用，是有意的），于是「依赖变更时本地规则也说不行」会把服务器那句顶掉、并让归属变成本地，接着改回去时那句就被撤了，留下一个勾对着下一次提交仍会被拒的密码；修法是**依赖触发的重判不覆盖已有的句子**。另一条是**本页自己的预检写的话撤不回来**——找回密码页提交时不再自己写那句话，而是交给三态校验去判（空值除外，校验器对空框有意不表态），于是改对手机号之后那句「密码不能与手机号相同」会跟着走。两条都配了**变异验证**（把修复改回去，只有对应的新用例会红）。同一轮还修了两处小的：问不到服务器那句话在服务器恢复后、再失焦一次撤不掉（只能靠改值），以及首次发码被限流时按钮说「N 秒后可重发」而其实一条都没发过。
- **第五轮**（最后一轮）又攻破同一条要求的两处新缝，都是**中等**：一是「问不到服务器」那句话在一个**没被改过**的字段上会顶掉服务器已经给过的拒绝（失焦就会重新问一次，而那次问失败本身不是答案）；二是两个页面的提交开头会把整张问题表清空，于是一次**连服务器都没到**的提交（预检就拒了）会顺手抹掉上一次服务器对**另一个**字段说的话，留下一个勾。两条都按「谁写的话谁能撤、服务器的话只在字段被编辑时才消失」补齐，并各自配了变异验证。同一轮还让**消息与结论同步**——页面自己写入服务器拒绝时不再只写消息、不改结论（否则那句拒绝在屏幕上、按钮却还亮着），并订正了第二轮里一条**因为错误的原因而通过**的断言。
- 五轮到此为止。根因是一条**架构性**的东西，不是某一处笔误：服务器的话与本地规则的话共用**一个槽**，归属只能靠反推（先按「是不是我写的」记住原文，再要求页面别自己写），于是每补一处就露出下一处。**已在报告里提请决定**是否值得改成两个独立的存储；不改的话，剩下的都是窄而可恢复的（都是消息/结论不同步，没有数据损坏、没有多发短信）。
- 五轮之后**按架构改了一次**（这一条是产品上的判断，不只是修 bug）：三态校验里「服务器说的话」与「本地规则说的话」原来共用**一个槽**，归属只能靠记住自己写过什么原文来反推——而两边有时说**一模一样**的话（`too_common` 一个字面同时表示「在常见口令表里」与「密码由用户名拼出来」），所以反推必然出错，每补一处就换一个入口漏。定下的分工是**服务端提供能力、校验逻辑封装在前端**：服务端继续提供「这个用户名还能不能用」这类**能力**，并在提交环节保留**权威**（口令表、手机号有没有账号、抢占这三样只有它知道）；前端把校验器的三句话按**产出者**分开、生命周期各自写死——规则判定（每次重判都改写）、服务器拒绝（**连同它当时是针对哪个值说的**一起存，值一换就不再显示）、问不到服务器那句话（只有在没有别的可说时才显示）。页面提交也不再自己往消息表里写，改为交给校验器登记。**反推那套机制整块删掉了**。
- 这一步**不是**「审计说改就改」：它是把前五轮反复攻破的**同一个要求**从结构上消掉，而不是再堵一个入口。两条要求各自用**变异验证**钉住（把生命周期改回去，只有对应的用例会红，别的都还是绿的），并补了第五轮那条具体实例的用例（提交时服务器在验证码那一步就停了，**没有**再看密码——密码那句拒绝必须还在）。前端 **200 全绿**、`vue-tsc` 无错、`npm run build` 通过。
- 每一轮跑完都拿真实数字收口：**服务端 341 全绿**（170 单测 + 171 集成）、**前端 203 全绿**、`vue-tsc` 无错、`npm run build` 通过。

**这一轮把上一轮那条「浏览器里那一层从没跑过」收口了一半**（2026-10-02，本机，PostgreSQL + `spring-boot:run` + `npm run dev`，真浏览器）：注册两页走通——页 1 三项校验各自打勾、点「下一步」发出的短信**没有要求图形验证码**（当天该地址的免费额度未用）、页 2 填任意六位数字即注册成功、成功屏显示用户名与命名空间。**找回密码那条仍只有 `curl` 走查**，登录那条从浏览器发到过服务端一次（401，原因见下）。

**同一轮里修掉的一个测试自身的错**：`WebRegistrationIT` 里那条钉 201 的用例，响应桩多塞了一个「取图形验证码」，而注册这条实际不发这个请求——于是注册吃到的是验证码的那个桩，**真正返回 201 的桩从没被消费过**。它只断言「跳转了」，所以一直绿。新写的断言看的是返回内容，一跑就露出来。这一条记在这里是因为它是**测试的**缺陷，不是产品的。

> 一条不是缺陷但值得记的：这次走查里，一个真实账号注册成功之后**登录不进去**，查下来是**密码不匹配**（账号、手机号哈希、`credential` 行都在，服务端 401 且登录端点本身正常）。最可能的原因依次是：密码里带了看不见的字符（字符集 `/^[\x20-\x7e]+$/` **允许空格**）、浏览器自动填充了另一个密码、或注册时打错了一个字符而注册页没有「确认密码」栏（那是有意的，见 M01 架构 §密码）。**产品侧不改**，但它是「不设确认密码框」这个选择要付的代价，记在这里以免下次重新发现。

**第十一轮（2026-10-05）：浏览器登录这条终于真的跑了一次。** 这一轮不是新增功能，是把上一轮明确记着「没跑」的那条路走完——服务端在 dev 库上起、前端在 5173、CLI 装进 `~/.local/bin/skillmaster`、`oauth_client` 里种一行 `skillmaster-cli`（redirect URI 逐字等于 `http://127.0.0.1:51004/callback`），然后用一个真账号（`lijinzhao`，2026-10-02 注册的，登录标识是手机号）走完：授权 URL → 浏览器 → `/login?return_to=…` → 登录 → 同意页 → 回调 `127.0.0.1:51004` → PKCE 换令牌 → 凭据落进**系统钥匙串**。

**跑完能确认的，按「以前只有推断没有证据」的顺序**：

| 断言 | 证据 |
|---|---|
| 授权能穿过 SPA 的源完成 | 同意记录表里**有一行**（`oauth2_authorization_consent`，该表由今天才应用的 `V5` 建，所以这行只能是今天产生的——也就是说**同意页被真人渲染并批准过**，那是它此前唯一的空白） |
| 只存哈希 | 三张表的全部值都是 64 位小写十六进制，`access_token`/`refresh_token`/`auth_code` 各一行（与 P0a 起的那条 IT 同一条断言，这次是**真实签发**的令牌） |
| 主体是 userId 而不是用户名 | `oauth_authorization.principal_name = 01M3X59DSCAZ9B4W05Y1DJ5VRM`（ADR 0014：M3 的 `AuthenticatedSubject` 拿到的就是它） |
| 带令牌的读写全通 | `publish` → `search` → `show` → `get` 正文 → `get notes.md`，两文件的清单与逐文件取用都对得上 |
| **同意一次之后不再问** | 第二次 `skillmaster login` **一次点击都没有**：授权端点直接换了码（模块文档那条断言此前只有设计依据） |
| **重启不掉线** | 换了个 `SKILLMASTER_ACCESS_TOKEN_TTL=PT10S` 的服务端重启后，**重启前那枚凭据照旧可用**——授权状态在库里不在内存里（ADR 0024 那条「读库不读内存」的实测） |
| **刷新与轮换真的会发生** | 用 10 秒 TTL 逼出来的两次刷新：`refresh_token` 3→4、`revoked_at` 非空 1→2、`rotated_from` 非空 1→2，即每次刷新都签发新的一张并把被替换的那张标掉（ADR 0024）——这是**第一次由真 CLI 触发**的轮换 |
| **`logout` 只撤被递上来那条链** | 那次 `logout` 时库里正好有两条授权（今天登录了两次），撤销后**只有它持有的那一条**全灭（4 个 access + 4 个 refresh 全部 `revoked_at` 非空），另一条一个令牌都没动——这正是 ADR 0024「撤销沿链、别一锅端」要的形状。审计行也记了：`token_revoke` / `authorization` / `{"trigger":"refresh_token_revoked"}`。撤完之后 CLI 如实说「not signed in」 |

**这一轮抓到三个缺陷，全是「跑起来才看得见」那一类**：

| 缺陷 | 为什么静止的测试抓不到 |
|---|---|
| **`changeOrigin: true` 把 Host 改写成了 `localhost:8080`**，于是授权端点回的 `Location` 是 `http://localhost:8080/login`——那个源上没有任何页面，登录会在一片「已经成功」里断掉 | 前端那 48+ 条测的是它自己的渲染与请求；服务端的 IT 直接打 8080，没有代理。而**这个缺陷只在「浏览器经代理访问 oauth 端点」这一种组合下存在** |
| **`/gateway` 没进代理表**，而 Vite 对未匹配路径回 SPA 的 `index.html` + 200，于是 `skillmaster setup` **把一张网页写进了 `SKILL.md` 并报成功**——agent 随即以 `description: <!doctype html>` 加载了它 | 两边各自的测试都是对的：CLI 只断言「拿到 200 就写」，而它测的假服务端不会回网页。**要两边都对、合起来才错**，这正是上一轮那条缝的同类 |
| **刷新锁的目录在钥匙串用户那里根本不存在**，于是每次刷新都以 ENOENT 失败、打印一句像是「有人正持有锁」的警告，然后不带锁继续 | 锁的单测用的是 `t.TempDir()`——目录**总是存在**。而钥匙串凭据不碰凭据文件，那个目录就永远没人建 |
| **access token 在有效期内被撤销时，CLI 什么也做不了**——它只按**自己存的** `expires_at` 决定要不要刷新（留 2 分钟余量），从不问服务端；于是一次 401 之后，人看到的是服务端那句原始错误，既没有自动恢复也没有「请重新登录」，而本地那份凭据已经死了、只会一直复现 | **没有哪一层的单测能看见它**：`api` 的测试用的是好令牌，`auth` 的测试假服务端对什么都回 200，而「服务端不认这枚令牌」要求两边都对。这条不是跑出来的，是**问「什么情况下会触发重新登录」时逐条追出来的** |

前两条都改了实现：代理不重写 Host（这正是 ADR 0015 的 nginx `proxy_set_header Host $host` 做的事），`/gateway` 进了代理表，并且 CLI 的 `setup` 现在会校验拿到的是不是 SKILL.md——**200 不等于内容对**，不是就带着「第一行长什么样」失败（生产上反代的 `try_files … /index.html` 会造成一模一样的事）。第三条在 `Lock.Acquire` 里建目录，并补了一条「目录不存在也要能拿到锁」的用例。

**第四条按产品判断当轮修掉了**：`api` 现在把 401 单独报成 `ErrUnauthorized`（**403 不算**——`insufficient_scope` 是令牌没问题而授权范围不够，重新登录会拿到同样的 scope，所以当成认证问题会把人送进一个绕不出去的圈），`main` 收到它就**续期一次并重试一次**：`auth.Renew` 不看本地那个 `expires_at`（这正是它的存在理由——令牌可以在有效期还剩五十分钟时被撤销，而本地存的那个时间戳不可能知道），并被告知**被拒的是哪一枚**，所以同一条命令里第二次 401 不会再去转一次链。「只重试一次、绝不自己拉起浏览器」是有意的：`Renew` 要么换到新令牌，要么如实说授权已经没了，而后者它已经把本地凭据删掉并说了该跑哪条命令——从 `search` 里打开浏览器是惊吓不是服务。

**实测两条分支**：把最新那条授权的**所有 access token 撤掉、refresh token 留着** → `skillmaster search` 一声不响地续期重试成功（库里 `access_rows` 1→2、轮换正好一次）；把**两者都撤掉**（就是改密码时 `revokeAllFor` 做的事）→ `错误：授权已被撤销，需要重新登录：skillmaster login`，本地凭据随之删除，再跑一次变成 `本机没有可用的凭据：先运行 skillmaster login`。

顺带修掉的两句消息：`ErrAuthorizationRevoked` 原来是英文的「the authorization was revoked」，那只是把错误码翻译了一遍、**没告诉人该做什么**（ADR 0024 §后果 明确要求这里要能读出「请重新登录」），`ErrNotSignedIn` 同理——现在两句都是中文且点名了下一条命令。

**同一轮里暴露出来、但**不是**缺陷的一件事，因为它是设计的直接后果**：一条机器上**登录两次就有两条活着的授权**。CLI 的凭据是「每 (机器, 服务端) 一份」，第二次登录把它覆盖掉，于是**第一次那条授权的 refresh token 就再也没人持有了**——它不会被 `logout` 撤（`logout` 撤的是凭据里那一张），也不在任何界面里，只能活到自然过期（闲置 30 天）。上面那张表里 `logout` 只撤一条、另一条安然无恙，正是同一个事实的另一面。

这条与「已授权的凭据列表」是同一件事，而那条被推迟时的理由（「v1 只有一个客户端，**按应用列只有一行**」）**只覆盖了一个维度**：按 (`client_id`, user) 确实只有一行，但按**授权**（= 一次登录）可以有任意多行，而且分布在不同机器上时连「这是谁」都无从看起。它不改变那条推迟本身（列表仍然是 v2 的事），但改变了「为什么可以推」——**现在的理由只能是「今天这条只会让本人自己的多设备变乱」，不是「不会有内容」**。

**当轮就定了并做掉了**（产品判断，2026-10-05）：重新 `login` 时把上一次那张授权撤掉，于是「一台机器最多一条活着的授权」重新成立。顺序是**先登录成功、再撤旧的**——反过来会在登录失败（没人点浏览器、同意被拒）时把人踢下线，而那正是最不该发生的时刻。撤销失败只**警告**不失败：新的凭据已经在手且可用，让命令失败等于对「你现在是什么状态」撒谎。实现上把 `logout` 的撤销那一半提成了 `auth.RevokeAuthorization`（两个调用方，一个实现），`login` 调它但**不删**凭据——那一刻库里的已经是新的那一份。**实测**：登录前后逐条对授权，被替换的那条 `live_refresh` 1→0，新的那条为 1，CLI 打出「上一次那张授权已撤销，这台机器上只剩这一次。」

**那个决定管不到昨天那条孤儿**：上面 `d1a1f710` 那次登录的 refresh token 已经不在任何地方了，没有东西可以递上去撤它，所以它仍是活的（闲置 30 天自然过期；或者下次改密码时随 `revokeAllFor` 一起没）。这正是「列表」那条推迟所留的口子，由本轮的真登录实测暴露出来。

**这一轮之后仍未验证的**：T0b（改密码后再登录）没走——这次登录一次就成功，没触发找回密码；`get` 与 `setup` 之外的 agent 自主那条（T2/T3 的判据主体）仍然要一个真 agent；T4/T4b 的快照比对仍未执行。

**第十二轮（2026-10-05）：「退出登录之后凭据会不会过期」——答案是**不对称的**，两端各取了一份证据。** 这一轮不是新功能，是把上面两轮留下的一个产品问题问到底。做法：用一个一次性账号（`probef5cef6`，走真实端点注册）在**网页面**建立会话，并在网页面完整走一遍授权码流程（`GET /oauth/authorize` → 同意页 POST → 回调 → PKCE 换令牌）拿到一对真令牌，再逐条量。

| 步骤 | 实测 |
|---|---|
| 网页端 `POST /web/logout` | 204 |
| 退出后 `GET /web/session` | **401**——会话真的没了 |
| 退出后那张**访问令牌**打 `GET /api/v1/skills` | **200**——没被吊销 |
| 退出后那张**刷新令牌**换新 | **200**——没被吊销 |
| 对照：直接 `POST /oauth/revoke` 同一张访问令牌后再打一次 | **401**——所以上面那个 200 不是「令牌本来就不校验」，而是网页退出确实没碰它 |

**结论**：

| 在哪退出 | 会结束什么 | 不会结束什么 |
|---|---|---|
| **网页端退出登录** | 只结束网页会话（`WebSession.clear`＝`session.invalidate()` + 清 `SecurityContext`）——**不碰任何令牌表** | CLI 那台机器的授权原样活着（上表 200/200） |
| **CLI `logout`** | 立即吊销**被递上来的那一条授权**（访问令牌与刷新令牌一起死，ADR 0024 沿链撤销），再删本地凭据 | 别的授权（另一台机器、网页会话）一个都不动 |
| **重置密码** | `revokeAllFor` 吊销该账号**全部**授权——三个动作里唯一会跨端踢人的 | — |

> **这一格里「重置密码吊销令牌」当时是错的，是这一轮追下去才发现并当轮修掉的**——见下面「追出来的一处漏接」。表格留着是因为它描述的是修完之后的真相。

**重新鉴权**：网页端退出后再用网页是 401 → 回登录页；而 CLI 侧**不需要**重新登录，它的凭据没被动。反过来 CLI `logout` 之后，下一条命令会说「本机没有可用的凭据：先运行 skillmaster login」。所以「退出登录」这句话在两端指的是两件事，这不是 bug，是两种东西本来就叫一个名字。

**这一轮顺带标出一个产品上的口子（只记，不修）**：网页上**看不到、也不能单独撤销**某一台机器的授权（「已授权的应用」列表仍是 v2 的事），所以从网页想去掉某台机器的 CLI 凭据，只有`改密码`这一个（全有或全无的）开关——**没有「就撤这一台」的动作**。这与第十一轮暴露的「孤儿授权」是同一个口子的两面：**授权的可见与可撤销都只存在于它自己所在的那台机器上**。

**追出来的一处漏接（2026-10-05，当轮修）：改密码根本没有撤销令牌。** 上面那张表把「重置密码吊销全部授权」当成既有事实写了进去，核代码时发现**不是**：`ResetPasswordUseCase` 只调了 `sessions.revokeAllFor`（会话），M2 的 `tokenRevocation.revokeAllFor` **在生产代码里一个调用者都没有**——全仓库唯一调用它的是 `AuthorizationServerIT` 的一条测试，也就是说那条测试钉住的是「SQL 能跑」和「模块边界成立」，**「有人能走到它」这一层从来没被钉过**。用例自己的 javadoc 写着「When M2 exists this use case grows a second call on the same seam — tokens」，M2 到了，那一行没加。

**后果不是理论上的**：改密码**踢不掉任何 CLI 凭据**。一个拿到过密码的人在自己机器上登录过，受害者改完密码之后那台机器**照旧能用**，最长到那条链的绝对上限 180 天（只要他一直在用就永不闲置）。这直接违反 ADR 0013 定的那条——*改密码必须撤销该用户全部令牌与会话，否则「找回密码」等于没找回*——也违反 ADR 0021 选不透明令牌的理由（§理由 那一条就是「撤销立刻生效」）。

**修法是一行**（用例层注入 `TokenRevocation`，在 `sessions.revokeAllFor` 旁边加 `tokens.revokeAllFor`），与 M02/M01 早就记着的「接缝已经在了，就是用例层加一行」一致；**难的不是修，是它一直没被钉住**。补的用例走**真实入口**（`POST /web/reset`），断言两半：那条还在机器文件里的 `access_token` 打 API 变 **401**（ADR 0021 要的「立刻生效」，而不是再工作一小时），以及那条 `refresh_token` 续期被拒 `invalid_grant`（否则机器自己就会续回来）。**变异验证**：把那一行注释掉，只有这条新用例红（断在「expected: 401 but was: 200」），原有那条直接调模块方法的用例照旧绿——正好说明它此前为什么是绿的。

**三处文档都记着这件事，但记的理由已经过期**：§已知问题 #9 写的是「未修复（等 M2）：**令牌表还不存在**」——令牌表（V5/V8）今天在，实现与审计（`token_revoke_all`）今天也在，缺的只是那一行；M02 与 M01 的模块表同理。这一轮一并订正。

**收口数字**：服务端 `clean verify` **BUILD SUCCESS，355 个测试通过**（170 单测 + 185 集成）——比修之前正好多一条，就是那条新用例。文档校验器通过（相对链接全有效）。

**第十三轮（2026-10-05）：M2 的第一次循环审计。** 这一轮不是走查某个功能，是把**整份未提交的 M2 变更集**（89 个文件、约 8900 行新增）交给独立审计员：服务端令牌模块与授权服务器接线、Go CLI、以及 CLI↔服务端那条线加网页端同意页。做法是**三个互不通气的审计员按面切开**（各自只读补丁与真实文件，没有上下文），每条 blocker/high 必须自带一个可复现的失败场景，报不出场景就不许定到 medium 以上；主 agent 逐条**对着代码复核**后才动手，改完的东西用**变异验证**（把修复改回去，看那条新用例是否变红）钉住。

**13 条唯一发现（14 条报告，其中两条不同审计员各自独立报了同一处 `setup` 超时）**：

| # | 级别 | 位置 | 是什么 | 处置 |
|---|---|---|---|---|
| 1 | **high** | `TokenStore.upsertRefreshToken` | **一条刚被撤销的授权会被并发刷新复活**：撤销落地时 `live` 读成 null，而「没有活的」也正是**一条全新授权的第一次插入**的样子，于是新签的 refresh token 被以「活的」写进去，那条授权带着剩余几个月的上限回来。宽限窗口兜不住——它只管**被呈现**的令牌，这是**写入** | **已修**（V9，见下） |
| 2 | **high** | `cmd/skillmaster/main.go` | `setup` 是唯一一个裸 `http.Get`：没有 context、没有期限，而其余每个调用都有（discovery 10s / token 30s / api 2min）。一个接了连接就不再回答的代理会把 `setup` 挂死，屏幕上什么都没有 | 已修 + 用例 |
| 3 | **high** | `internal/config/config.go` | **兜底凭据文件不按 server 分**，而 blob 里也没记 server——钥匙串是**有意**按 server 分键的（它的注释就写了「本地那套与线上那套」这个场景）。于是换 server 会把 A 的令牌发给 B，接着拿 A 的刷新令牌去 B 换、读到 `invalid_grant`、**把本地凭据删掉**：用 A 反而把 B 注销了 | 已修 + 用例 |
| 4 | **high** | `cmd/skillmaster/main.go` | 网关装进 `skillmaster-gateway`，而文件里写着 `name: skillmaster`——违反 §1.1 那条 MUST，而整个仓库的目录布局正是为它而设的（`gateway/skillmaster/`）。失败是静默的：检查这条规则的 agent 会忽略这个 skill，于是**唯一的发现通道失效**，而 `setup` 打印「已安装」并以 0 退出 | 已修 + 用例 |
| 5 | med | `internal/credentials/lock.go` | 被杀的进程留下的锁**永远不会被回收**——而 `Acquire` 的注释把「进程死掉留下的锁」当成**暂时**的。它不是暂时的：此后每次刷新都要等满超时并打印一句像是竞争中的警告 | 已修 + 用例 |
| 6 | med | `cmd/skillmaster/main.go` | `login` 在**任何**读凭据失败时中止——而那个读只是为了**事后**知道该撤哪张。坏掉/手改过的凭据会让「本来就是用来替换它的那条命令」永久拒绝执行 | 已修 + 用例 |
| 7 | med | `cmd/skillmaster/main.go` | `login --client-credentials` 覆盖浏览器凭据时**不撤销**它——与浏览器那条路刚修掉的缺陷同一形状 | 已修 |
| 8 | med | `config/SecurityConfig.java` | **OAuth 面的错误被 API 面的 401 顶掉**：`/error` 不属于任何一条前面的链，于是 ERROR dispatch 落到兜底那条，未知 `client_id` 本该得到的 400 被换成 `401 unauthenticated` + Bearer 挑战（指着一个调用方不在的平面）。**当天手工走查时撞到过同一个症状** | 已修 + 用例 |
| 9 | med | `TokenStore.upsertAccessToken` | **撤 access token 不结束授权**，与 M02 §撤销「按 authorization_id 把码、access、refresh 一起标掉」相反：只撤了那一行，refresh 还活着，于是「客户端登出了」与「客户端还能用几个月」同时为真 | **已修**（V9，见下） |
| 10 | med | `IssuedTokenValidator` | 验令牌那条路只查 `access_token` 自己 | **已修**（V9 的一部分） |
| 11 | med | `/oauth/token` | M02 §91 明确要求限流，代码里没有 | **记为已知缺口**（见下） |
| 12 | low | `cmd/skillmaster/main.go` | `setup` 从不比对 `metadata.platform_api_version`，而 TD §5.2 第 3 条要求它 | 已修 |
| 13 | med | 回调处理 | 任何**不是我们发起的**回调请求都会中止本次登录（端口固定，ADR 0028） | **不改**（见下） |

**第 1 与第 9 条同一个根因，也是这一轮最大的收获**：`oauth_authorization` **根本没有 revoked 列**——「这次授权结束了」只表达为「它的三张令牌行都被标了」。那个推断支撑着一个撤销（扫一遍 UPDATE）与一个写入（INSERT）之间的竞态，而**任何一组 UPDATE 都不可能和它没见过的 INSERT 原子**。V9 给父行加上 `revoked_at`，于是正确性不再依赖扫描是否完整：

- **载入路径说了算**：`load()` 读到父行已撤销就把**它下面每一张令牌都标成失效**，所以一条逃过扫描的活行是**惰性的**；`refreshToken` 的 `familyAlive` 也先问父行（否则那条活行会被当成活着的继任者，宽限窗口于是会把一次已结束的授权救回来）。
- **API 那条路也要问**：`IssuedTokenValidator` 是唯一决定「这张令牌能不能做事」的查询，它加了一个 `EXISTS`——它是这条路唯一必须不被残留行骗过的地方。
- **写入不再制造逃逸行**：三个 upsert 在父行已撤销时把新行直接写成已撤销。
- **顺带补齐第 9 条**：access token 一失效就结束整条授权（安全前提是**刷新不会让被替换的 access token 失效**——7.1.1 的 refresh provider 里 `invalidate` 一次都没出现，是读字节码确认的，不是推测）。

**这一轮的四条验证**（把修复改回去，各只有一条用例变红，且红在预期的那个断言上）：载入路径不看父行 → 「逃逸行仍然惰性」红；验令牌那条路不问父行 → 同一条用例的 API 断言红；撤 access token 不结束授权 → 「撤 access token」红；写入时不看父行 → 「并发的刷新不把它带回来」红。三条修复各配一条新用例，另有两条专门钉住「不被不该管的东西管」（不是目录的锁路径永不回收、**新鲜**的锁不因等待者的耐心而被抢）。

**两条不改，理由记在这里而不是留在代码里当谜**：

- **第 13 条（回调）**：审计员认为一个非我方请求不该中止登录。查下来 mux **只**把 `/callback` 交给 handler，所以那句「favicon、扫描器都被忽略」本来是**别的路径**的事，不是「state 不对也忽略」；而既有那条 `TestLoginRefusesACallbackWhoseStateIsNotOurs` 钉的是一个**安全**行为：不匹配的 state 必须被**拒绝且说出来**、且一个码都不换。改成静默等待会把一次真实的状态错误变成五分钟的无言挂起，同时让「有人把 CLI 引到自己的授权请求上」不再留下信号。代价（本机上任何进程都能取消一次登录，重试即可）落在 ADR 0028 已经声明的威胁模型里，明说接受。
- **第 12 条里那个「state 当 token 递上去会 500」的子项**（审计员报的 med）：Spring 自己的 JDBC store 有**同一个** state 回退，所以这不是我们的偏离；第 8 条修完之后它至少是一个**诚实的 500**，而不是被伪装成 401。

**第 11 条（`/oauth/token` 限流）按你的判断记为已知缺口**，见 §已知问题。

**这一轮的服务端收口**：`clean verify` BUILD SUCCESS，**356 个测试通过**（170 单测 + 186 集成）——比审计前多两条，就是本轮新增的那两条。CLI 侧 `go build` / `go vet` 干净、`gofmt` 无输出、六个包全绿。

**同一轮的定向复核（第二轮）**：一个全新的审计员，范围就是这一轮改出来的东西，任务是**把修复打穿**。结论：**没有 blocker 也没有 high**，五条 medium/low。

| 级别 | 是什么 | 处置 |
|---|---|---|
| med | **`revokeAllFor` 够不到无人值守那条授权**：`client_credentials` 的框架主体是客户端认证，所以父行的 `principal_name` 是 **client id**，而它的令牌行写的是绑定账号（ADR 0022 与 `subjectOf` 说的就是这件事）。只按 `principal_name` 扫，令牌全被标掉、**父行留着**，审计详情于是报出 `authorizations: 0` 配一个非零的令牌数——而父行正是 V9 的权威 | **已修**：先写一条会红的用例（无人值守换令牌 → `revokeAllFor` → 父行仍为活，实测红），再把扫描改成两条路都匹配（`principal_name`，或该用户绑定的那个 client）。今天的安全后果为零（那条 grant 没有 refresh token，且每次请求都新建一条授权），但「权威对两种 grant 之一不成立」正是这一轮要消掉的那一类东西 |
| med/low | **两处只在「跑过改名之前那个构建」的机器上存在的东西**：兜底凭据文件的旧名 `credentials`，与网关 skill 的旧目录名 `skillmaster-gateway` | **接受，不加代码**：两个旧拼法**都没有发布过**，只存在于本次未提交变更集里更早的那几个构建，所以能撞上的只有在这棵工作树里跑过中途构建的人（本机上就有一个——09:09 那次 `setup` 装出来的目录）。为它加「旧路径探测/迁移」等于给一过性的状态留永久代码。改的是**文档**（ADR 0025 与 TD 里写死的路径），并把本机那两个残留报给人 |
| low | **审计 `trigger` 记的是「哪一行被标着失效递上来」，不总是原因**：一次**授权码重放**也走那个分支（框架那次会去失效它签发过的 refresh token），于是审计说 `refresh_token_revoked` 而真实原因是重放 | **记录不改**：在这个方法里分辨不出来——换过码的授权，其码本身也已经失效，所以「码用过了」在每次兑换之后的写入里都成立。不去猜，写进 M02 的 §撤销 与审计行；原因仍可从那张码的 `used_at` 复原 |
| low | `TokenRevocations` 的注释说「中途到达的读者已经能看到它们结束了」——在用例的 `@Transactional` 里不成立（READ COMMITTED，提交前谁也看不见） | **已修**（注释改成实话：顺序对 autocommit 那条路有意义，对事务那条路不亏也不赚） |
| low | `Lock.age()` 那个「不是目录」的错误被丢掉，于是锁路径上放了个**普通文件**时，每次刷新都要等满十秒、然后报一句「有人正持有它」，永远不说真实原因 | **已修**（把真实原因带进那条消息；既有那条用例断言的是消息里含「was held for」，所以仍然过） |

**第二轮被独立复核、最终站住的东西**（审计员自己的话：试图打穿而打不穿）：撤 access token 那条分支**不可能**在正常刷新时触发（refresh provider 从 `OAuth2Authorization.from(auth)` 起、其 lambda 写的是 `INVALIDATED=false`，builder 按类键覆盖而非合并——**它独立复现了同一段字节码结论**）；`revokeAuthorization` 的提前返回不会留下有害的不完整扫描（每个在乎的读者都问父行）；`load()` 的全量失效不破坏任何合法流程（同意、兑换、首次插入都发生在父行还活着的时点）；V9 这条只加列的迁移动不了 `TableOwnershipTest` 与 `ModuleMap`；`/error` 在默认 `server.error.*` 下既不吐消息也不吐栈；`isSkillName` 绕不过去；抢一把**活着的**锁退化成加锁之前的行为，而宽限窗口本来就兜着它。

**循环到此结束**：第一轮修掉的 high（`setup` 超时、兜底文件不分 server、网关装错目录名）触发了第二轮；第二轮**没有**确认出新的 blocker/high，按规则退出。第二轮的收口：服务端 `clean verify` **BUILD SUCCESS，360 个测试通过**（170 单测 + 190 集成）；CLI `go build` / `go vet` 干净、`gofmt` 无输出、六个包全绿。

**同一天的第十轮**（M2 令牌与授权服务器接上，并**第一次让 CLI 对着真服务端跑**）→ 服务端 `clean verify` **BUILD SUCCESS，354 个测试通过**（170 单测 + 184 集成）；`skillmaster-web` 的 `vue-tsc` 无错、**210 条 vitest 通过**（14 个文件）。

**这一轮第一次有的证据，是 CLI 与服务端真的见过面。** 在此之前两边的每一侧都只有对着**自己造的假**跑过的测试（CLI 的假服务端、服务端的 `curl` 式 IT），而它们之间那条线——发现文档的路径、令牌端点的 `Authorization: Basic`、检索响应的字段名、发布那一个叫 `file` 的 multipart 段、清单里那些绝对 URI——**一次都没有被真东西验过**。做法：另起一个库（`skillmaster_e2e`，Flyway 从零迁到 `V8`）、真服务端跑在 8080、按部署的方式手种一行 `oauth_client`，然后让 CLI 走。走通的是 `login --client-credentials` → `publish`（首次 / 改内容 / 内容不变三种）→ `search` → `show` → `get`（含 `@版本` 钉版与不存在的文件）→ `logout` → `setup --dir`。**唯一没走的是浏览器那条 `login`**：它要一个人在浏览器前，而注册要图形验证码——据实记作只验到「服务端接受了 CLI 构造的那个授权请求」（回 302 到 `/login?return_to=…`；`redirect_uri` 或 `client_id` 有一个不对就不会是这个答复）。

**跑这一遍抓到的都是 CLI 侧的缺陷，四条，其中一条是「测试全绿也照样存在」那一类**（前九轮反复出现的同一类，这次轮到跨端那条缝）：

| 缺陷 | 为什么单测抓不到 |
|---|---|
| `publish` 把服务端的 `created` 读成「这个 skill 是新建的」，而它的语义是「这次调用新建了**版本**」——于是给一个改过内容的旧 skill 打上「已创建」 | 客户端那个测试只造了 `created: true, number: 1`，而错的正是 `created: true, number: 2` 那一格。假数据是自己写的，所以只会写对的那一格 |
| 刷新时用 CLI 编译进去的 `client_id`（`skillmaster-cli`），而不是凭据自己的那个——于是无人值守凭据的续期以**另一个客户端**的身份发出去 | 假的存储里 ClientID 与 `SessionOptions.ClientID` 恰好相同，两个值分不开。修法是把 `SessionOptions.ClientID` **整个删掉**：能传错的接口就是会被传错 |
| 无人值守凭据（没有 refresh token）过期时发一个空 `refresh_token`，拿回服务端一句裸的 `invalid_client`，读起来像凭据坏了 | 假服务端对任何请求都回一个正常令牌，所以「服务器会拒绝」这条路从没被走到 |
| **`logout` 对无人值守凭据什么都不撤销，却报「已撤销授权」**，令牌继续能用最多一小时 | 假服务端不判断被撤销的是哪一种令牌。**这条是拿真令牌验的**：撤销前后各打一次 `/api/v1/skills`，200 → 200 就是假话 |

第四条修起来还带出一个只有真服务端才说得出的答案：**无人值守的凭据只能带客户端密钥撤销，而这台服务端注册的是 `client_secret_basic`**——只带 `client_id` 和把密钥放进表单，各自都被回一次 `invalid_client`（前者是服务端对的：裸 `client_id` 不该认出一个机密客户端）。密钥**有意不存**在凭据旁边，所以 `logout` 从环境变量取；环境里没有它时**拒绝并保留凭据**，不假装成功。

**还有一条是文档与实现不一致，走一遍才看见**：§4.6 把 `setup` 写成「装网关 skill → **软链**」，而实现不建软链（写进 agent 的技能目录，对只认一个 agent 的 v1 来说落点就是那里）。已就地改正，理由写在那一行里。

**同一轮里修掉的第五条，是那台机器自己的问题**：走查时 `login` 在写凭据那一步**静默挂死**（测到 17 分钟以上，什么也没写、什么也没说），根因是 macOS 的钥匙串锁着，而 `security -i` 会一直等一个没人能给的授权。**修法不是给 `keychainUsable()` 加探测**——读一个不存在的条目在锁着的钥匙串上也是瞬间返回的，所以任何便宜的探针都会对「写会挂」这一种说「没问题」；改成给真实调用加五秒上限，超时改用文件并**通过 `Where()` 说出来**。修完在真机上复跑：5 秒返回、凭据落进 0600 文件、消息说明原因，`search` 随后能从那份凭据里跑通。**这里有一条是「修完还得再跑一次」才发现的**：第一版只在**超时**时才看文件，而钥匙串的**读**是快的（条目不存在），于是 `login` 说「凭据存放于 <文件>」、下一条命令却说「not signed in」——补上「钥匙串说没有时也看文件」才闭环。**两份凭据可能同时存在**这件事因此必须处理：`Load` 取较晚的那一份，因为旧那份的 refresh token 服务端已经轮换过，递上去就是重放（ADR 0024 会撤销整条链）。

**服务端侧这一轮确认了一条已经写进 ADR 但从未被证实的事**：框架的 refresh provider 对一个失效的 refresh token **只抛 `invalid_grant`，不撤销任何东西**（读 7.1.1 的字节码确认，再用真实端点确认）。所以 ADR 0024 的「窗口外撤销整条链」必须落在 `TokenStore` 里，且必须在框架放弃之前——`AuthorizationServerIT` 因此多了三条：窗口内放过（不撤任何东西）、窗口外整链撤销（含那条活着的）、超过绝对上限只拒不撤。

**这一轮里被自己的测试当场打回的一次**：宽限窗口的第一版只看时钟，于是**一次登出之后的重试会拿到一对能用的新令牌**——一次只持续 60 秒的登出。它不是新写的测试抓到的，是**既有那条**「撤销后刷新必须被拒」的 IT 变红抓到的。修法是窗口多一个条件：家族里还有活着的才叫竞态（竞态总留下一个活的继任者，而家族全死说明有人有意结束它）。

**同一轮里还有一条是「启动才看得见」的**：五个令牌寿命进了 `skillmaster.tokens`，但**只有 record 上的 `@DefaultValue`、没有 yml 里的键**，于是嵌套组对绑定器来说不存在、`Tokens` 是 null、整个上下文起不来（`clean verify` 一跑就红，报的是 `skillmaster.tokens is required`）。结论记在 M02 模块文档：**`@DefaultValue` 让单个键可省，不让组存在**。

跑完后逐行填，**每条都要有证据**（命令输出、快照 diff、日志片段）。失败或阻塞的用例单列说明，不要埋在表格里。

**未验证、且本轮无法验证的**（单列，因为埋在表格里会被读成已通过）：

- Docker 镜像——本机无 Docker，`Dockerfile` 仍从未构建过
- `pg_bigm` 在阿里云 RDS **基础版**是否可用（P0a 不依赖它，但 T7 的长期形态依赖）
- 阿里云 RDS 是否允许用户表空间（`ALTER TABLE blob_content SET TABLESPACE`）
- **well-known 索引的 digest 是否符合真实客户端期望**、V2 的 `$schema` 是什么 URL、逐文件拉取的 `<base>` 该是哪几个——三者都要 **一个真客户端**（`npx skills add` 那种）拉一次真服务器才能判定。**2026-10-04 的 CLI 走查没有把这个收口**：那次 `setup` 取的是 `/gateway/SKILL.md`（§4.5 的专用端点），不是 `/.well-known/` 下那份索引，两者不是一回事，别把这次的绿读成那三条的证据
- **短信的签名与模板能否过审、周期多长、单条多少钱**——P1 的外部前置，只能申请一次才知道（[`technical-design.md`](technical-design.md) §8 问题 12）。**2026-10-02 把门槛与周期核实到了原文**（只有企业资质能报备、签名来源只剩企事业单位名与已注册商标名、通过后运营商报备 7–10 个工作日、按号码频控 1 条/分钟 5 条/小时 10 条/天），**仍未核实的是单价与能否一次过审**——那两样只能在准入账号里真申请一次才知道，**别照抄任何二手数字**
- **阿里云短信的真实往返**（2026-10-01 新增）：客户端已接、`AliyunSmsSenderTest` 钉住了代码这一侧，但**一条真短信都没发过**。要验的：申请到签名与模板 → 配上 `SKILLMASTER_SMS_*` 四个变量 → 走一遍注册，看短信到不到、多久到、失败时日志里是不是提供方自己的说法
- **真人能不能读懂那张图**（2026-10-01 新增）：验证码的可读性没有任何自动化证据。`HutoolCaptchaRendererTest` 只证明它是 4 位、字母表干净、是 PNG、不是常量。**2026-10-01 走查时连读两张都一次读对**（`RJUG`、`2CXL`）——但那是一台能读图的机器，**不等于一个人在小屏手机上看得清**，所以仍记为未验证，只是不再是零证据。要验的：让人在手机上打开那张图看一眼
- **浏览器里的注册这条已经走通**（2026-10-02 更新，此前记的是「从没跑过」）：**注册两页在真浏览器里对着真服务端走完了**（§结果第九轮）。**那次走查里一张图形验证码都没出现过**——注册的第一次发码免它，而那次用的是没花过免费额度的地址；找回密码页才固定要它，而找回密码只走了 `curl`。**仍未在浏览器里走的是**：找回密码那条（只有 `curl` 走查，因此**图形验证码从没在浏览器里渲染过**）、`/register` 刷新之后是什么（那要看反代）、以及那张图**一个人**在小屏手机上能不能读出来（见下一条）。要验的：`npm run dev` + 起服务端，把找回密码点一遍，并在 `/register` 上按一次刷新
- ~~**CLI 的浏览器登录那条从没端到端跑过**~~ **已执行（2026-10-05，第十一轮）**：权限范围内那一段全跑通了——同意页被真人渲染并批准、跳回 `127.0.0.1:51004`、CLI 收到码并换到令牌、凭据落进钥匙串、带令牌的读写全通；刷新与轮换也在那一轮用 10 秒 TTL 逼出来并观察到。剩下的只有 **T0b**（改密码之后再拿新密码登录一次）——那次登录一次就成功，没有走到找回密码那一步
- **生产上的 cookie 会不会带 `Secure`**（2026-10-01 新增）：两个 cookie 的 `Secure` 由 Spring 依 `request.isSecure()` 决定，而它要认反代发来的 `X-Forwarded-Proto`，需要有 `server.forward-headers-strategy`——**本轮没有改服务端**，所以这个组合从没验过。反代的 nginx 片段写在 [ADR 0015](../../decisions/0015-web-frontend-stack.md) 里，同样标注未验证（没有地方可部署）

## 已知问题

**本版范围内**的未修缺陷。

| # | 问题 | 状态 |
|---|---|---|
| 1 | **文档与实现不一致：寻址。** [ADR 0012](../../decisions/0012-addressing-and-version-pinning.md) 已定 `namespace/name[@版本]`，而 P0a 的实现与全部集成测试仍是 `/v1/skills/{id}`、无版本。 | **已修复**（P0c，2026-09-28）：代码与测试都改到新寻址，`SkillVersionPinIT` 与 `SkillAddressTest` 钉住新契约 |
| 2 | **L2/L3 在旧寻址下钉不住版本。** 三个端点各自解析 `current_version_id`，看完清单再取文件会拿到另一个版本，且不报错。 | **已修复**（P0c）：详情把解析出的版本写进每条 `uri`，回归测试是 `SkillVersionPinIT.aManifestStaysReadableAfterSomebodyPublishesAgain` |
| 3 | **`file_not_found` 这个码定义了、但从不发出。** TD §4.1 给了它「清单里没有这个 `relpath`」的语义，而 L3 把「版本读不到」与「`relpath` 不存在」折叠成同一个 404 `skill_not_found`。 | **已修复**（2026-09-28 审计轮）：M9 的 `fileOf` 改返回 sealed `FileLookup`（`Found` / `NotFoundInManifest`），L3 路由按它分别给 `skill_not_found` 与 `file_not_found`。**不泄露任何东西**：能走到 `file_not_found` 的调用者，其版本已经解析成功、也就是本来就看得见这个 skill，而 L1 详情早把清单里每个 `relpath` 都给了它。回归测试是 `SkillContentIT.aFileThatIsNotInTheManifestIsNotFound`（断言到码）与 `anotherUsersFileIsNotFound`（断言他人 skill 仍是 `skill_not_found`） |
| 4 | **`relpath` 含 `%`、`;` 或 `.` 段会得到一条取不到的 `uri`。** 详情发出的 `uri` 里 `%` 与 `;` 要百分号编码（`%25`、`%3B`），而 Spring Security 的 `StrictHttpFirewall` 把两种写法**都**拒绝；`. ` 段则被它的路径规范化检查（`/./`）拒绝——照抄那条 `uri` 就得到 `400`。**实测**（2026-09-28，本机、真 Tomcat、临时集成测试）：`uri` = `/v1/skills/demo/probe@1/files/references/a;b.md` → 跟随它 `400`；`a%3Bb.md` → `400`。**同日复核**（对着 `spring-security-web-7.1.1` 的字节码）：`encodedUrlBlocklist` 由 `;`/`%3b`、`%2f`、`//`、`\`/`%5c`、`%00`、`%0a`、`%0d` **加一条显式的 `%25`** 组成，所以 `%` 与 `;` 同等。 | **部分修复**：`name` 那一半已由 M5 收口（含 `%` 或 `;` 的名字一律拒绝，错误码 `name_contains_unaddressable_char`）——那一半更严重，因为它会让 skill **连详情与正文都取不到、删除也取不到**。**`relpath` 那一半仍未修复，且是有意不修**：收紧它意味着含这类字符的**文件**会让整个 zip 上传失败，而用户 2026-09-28 明确选择只收紧 `name`。代价是这三类文件名各自会得到一条取不到的 `uri` |
| 5 | **错误信封总是带 `details`，而 TD §4.1 说它「只在字段级错误上出现」。** `ApiError.Error` 的 `details` 默认为空列表并被序列化，于是 404/401/`invalid_request` 这些非字段级错误也带一个 `"details":[]`。**实测**（2026-09-28，手工走查）：未知版本 → `{"error":{"code":"skill_not_found","message":"no skill at that address","details":[]}}`。一行注解（`details` 非空才序列化）就能改对。 | **未修复**（既有行为，P0c 之前所有错误码都如此，P0c 的 `skill_not_found` 只是照既有写法实现；修它要动**所有**错误响应的线格式，不属于寻址，所以本轮没顺手改） |

| 6 | **`namespace` 指到别人的命名空间时，游标不再被校验。** `?namespace=other&cursor=<垃圾>` 得 `200` 加一个空页，而同一个游标在 `?namespace=demo` 下是 `400`（§4.1 说读不懂的游标是 400）。**审计轮实测**（第 4 轮）。**无后果**：这不是他命名空间的请求本来就该是空页，客户端读到的结论与它应得的一致，也不会翻页循环；所以这是契约上的一处不一致，不是会造成错误行为的缺陷。修它要把「命名空间过滤」这件事下移进 M8，或者让用例层重做一遍游标的解析——为一个没有后果的不一致改模块边界，不值得。 | **未修复**（有意；见左栏理由） |

| 7 | **验证码的尝试次数被事务回滚掉，六位数字可无限猜。** 拒绝猜错是用抛异常表达的，而异常回滚了同一个事务里刚写下的「记一次猜错」——于是 `attempts` 永远停在 0，`MAX_ATTEMPTS` 形同虚设，一条码在它的五分钟有效期内可以被枚举完。**这是本轮唯一的 high**，且它是「除专门钉它的那条，所有测试都绿」的那一类：`WebRegistrationIT.aCodeStopsBeingUsableAfterTooManyGuesses` 第一次跑就红（预期 400 得到 201），而其余全绿。 | **已修复**（2026-10-01）：两个用例声明 `noRollbackFor = VerificationCodeException`。安全性来自「码的校验排在所有写入之前」，所以那一刻待提交的只有计数器本身——这条不变量写在模块文档 §验证码里，由上述用例钉住 |
| 8 | **登录成功后会删掉 CSRF cookie，于是登录后的第一个写请求必被 403。** 原实现用 `csrfTokenRepository.saveToken(null, …)` 表达「轮换」；在 `CookieCsrfTokenRepository` 里 null 的语义是**删除**（空值 + `maxAge=0`），不是重置。 | **已修复**（2026-10-01）：改成保存一个新建的 `DefaultCsrfToken`（名字取自当前令牌，值是新造的）。对着 `spring-security-web-7.1.1` 的字节码核实过 `saveToken` 的两种分支 |
| 9 | **重置密码只撤销了会话，没有撤销令牌。** 模块文档说不撤就等于没重置。 | **已修复**（2026-10-05，第十二轮）：`ResetPasswordUseCase` 注入 `TokenRevocation` 并在 `sessions.revokeAllFor` 旁边调 `tokens.revokeAllFor`。此前记的「等 M2：令牌表还不存在」**已经过期**——令牌表（V5/V8）、实现、审计都在了，缺的是那一行，而它一直没被钉住，因为唯一调用 `revokeAllFor` 的测试是直接调模块方法的。新用例走 `POST /web/reset` 真实入口，断言访问令牌立刻 401 且刷新被拒 `invalid_grant`；**变异验证**过（注释掉那一行只有它红） |
| 10 | **浏览器面上，指向不存在路径的 404 报的是 `skill_not_found`。** `ApiExceptionHandler` 的兜底把任何 404 都映射成这个码（这在 API 面上是**有意的、已写进契约的**：Spring 路由没匹配到也报同一个码），而 `/web/**` 上出现这个码是在说一件与 skill 无关的事。**实测**：`GET /web/typo` → `{"error":{"code":"skill_not_found",…}}`。 | **未修复**（有意）：修它要么在兜底里按路径前缀分支（把「这条路径属于哪个面」的逻辑塞进异常映射），要么给浏览器面加一个 `not_found` 码并让所有人知道有第二个 404 码。而触发它的唯一方式是客户端把地址打错——没有任何客户端会依赖这个响应。为一个打错字的情形改一处**已经写进契约**的行为，不划算 |
| 11 | **没有图形验证码。** [ADR 0013](../../decisions/0013-phone-login-and-username-slug.md) 的三条防刷里，冷却与日限本轮都做了，图形验证码没做——而它是三条里唯一能挡住**多 IP** 攻击者的。 | **已修复**（2026-10-01，[`iterations/0003`](iterations/0003-captcha-and-sms.md)）：`captcha` 表 + `GET /web/captcha` + 两个发码端点的校验，`WebCaptchaIT` 十条钉住。**答案由 `SecureRandom` 生成**——Hutool 的默认生成器走 `ThreadLocalRandom`，可预测 |
| 12 | **线路格式没有自动化验证。** `skillmaster-web/` 的测试全部 stub 了 `fetch`，断言的是前端**自己的**理解——把 `captcha_id` 打成 `captchaId`，这些用例会跟着一起错。 | **部分解决、未自动化**（2026-10-01）：已用 `curl` 经 Vite 代理对着真服务端走完注册/登录/重置换码/登出与全部失败形态，字段名、状态码、错误码、`Retry-After`、四个 issue 码逐个对上（证据表见 §结果第三轮）。**剩下的缺口是浏览器那一层**，且**仍然没有自动化**——回归一次要人再走一遍。要自动化，得在 CI 里起真服务端加真库，那是一个独立决定 |
| 13 | **注册页的手机号字段丢过整条服务端拒绝。** `RegisterPage` 的手机号输入框只渲染第一步（发码）的错误，而 `POST /web/register` 对 `phone` 的拒绝（**`already_registered` 就是这一条**）落在页面自己的错误表里，没有位置显示——用户提交后被拒，屏幕上什么都不变。 | **已修复**（2026-10-01，[`iterations/0004`](iterations/0004-web-frontend.md)）：改成 `problems.phone ?? stepProblems.phone`（两步都可能拒手机号，第二步更新）。是新增用例先红才暴露的 |
| 14 | **找回密码页丢过 `no_account`，同一个缺陷的第二处。** `ResetPage` 的手机号字段同样只渲染第一步的错误，而 `POST /web/reset` 对 `phone` 的 `no_account`（这个流程**独有**的那条拒绝）落在页面自己的错误表里——用户填完验证码与新密码、点了「重置密码」，屏幕上什么都不变。 | **已修复**（2026-10-02，[`iterations/0005`](iterations/0005-completing-the-validation-tests.md)）：与 13 同一处改法。**两条是同一个模式**：页面把「第一步的错误」与「提交的错误」分成两张表，模板只渲染了其中一张 |
| 15 | **图形验证码拉取失败时，页面上是服务端的英文原文。** `useCaptcha.refresh()` 的失败分支写的是 `error.value = result.message`——封套里那句写给看日志的人的英文（如 `Too many requests.`），而不是 `codeMessage(result.code, result.message)`。这是全应用**唯一**一处没走码表的地方，所以凭据、验证码、会话、登出的报错都是中文，只有这一处会冒出英文。 | **已修复**（2026-10-02，`iterations/0005`）：改用码表。是新增用例先红才暴露的 |
| 16 | **`/oauth/token` 与 `/oauth/revoke` 没有限流。** [M02 §协议面上的实现细节](../../architecture/modules/M02-token-and-as.md) 写着「**`/oauth/token` 要限流**（按调用方地址，另按 `client_id`）：M1 的 `auth_throttle` 是现成的，加两条规则即可」，而代码里两条规则都没有——一个未认证的调用方可以任意打这个端点。**不是可枚举面**（授权码与令牌的熵足够高、猜不出来），所以这不是一个洞，缺的是那层硬化。 | **未修复，记为缺口**（2026-10-05，第十三轮结束时你的判断）：留到 P1。**它卡在一个架构决定上而不是工作量上**——M2 不能反向依赖 M1（[M02 §邻接面](../../architecture/modules/M02-token-and-as.md) 明写「M2 不反向依赖任何邻居」），所以「复用 M1 的 `auth_throttle`」要么把限流器的读取提成 M1 的一个公共出口，要么新开一个共享模块；两条都是跨模块边界的决定，不该顺手做 |

未验证项（上面单列的那几条）**不在此表**——它们是「没有证据」，不是「已知有问题」。

**本版范围外的**历史代码缺陷不写在这里，在 [`known-issues.md`](known-issues.md)：那是首次提交（`c8a7362`）对**重写前的基线代码**的审计结论（**条数与分布见该文件开头**），随架构重写一并处理。

> 两者的分工：本版交付范围内的缺陷 → 本节；范围外、本版决定不改的历史缺陷 → `known-issues.md`。

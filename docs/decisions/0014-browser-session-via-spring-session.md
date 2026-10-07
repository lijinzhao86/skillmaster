# ADR 0014 · 浏览器会话交给 Spring Session JDBC，M2 通过框架契约接入

- **状态**：已采纳
- **日期**：2026-10-01
- **依赖**：[ADR 0007](0007-self-built-oauth-as.md)、[ADR 0010](0010-storage-in-postgres.md)、[ADR 0011](0011-server-and-cli-stack.md)、[ADR 0013](0013-phone-login-and-username-slug.md)

## 背景

M01 要落地浏览器面：人用手机号 + 密码在 `/web/*` 上登录，此后每个请求靠会话 cookie 证明身份。TD §3.1 原先为此设计了一张自建表 `browser_session(session_id, user_id, expires_at, created_at)`。

实现前浮现两件事：

1. **那张表只写了「会话是什么」，没写它会怎样被用。** 一个能用的会话要处理：登录时换 id（防会话固定）、空闲超时、并发写入、过期清扫、以及「撤销这个人的全部会话」。
2. **M01 与 M2 之间有一处已知耦合。** M2 的 `/oauth/authorize` 必须知道「浏览器当前登录的是谁」，才能签发令牌。耦合方向是单向的 M2 → M1，但接缝长什么样没定——TD 只写了一句「让 M2 直接读 `browser_session`」是**不允许**的。

三个选项：

1. **自建 `browser_session`，自己写那五件事**，并给 M2 加一个 M1 的只读接口（`AccountDirectory` 上加个方法，或另开一个）。
2. **用 Spring Session JDBC**（`spring-boot-starter-session-jdbc`）：会话由框架存进 PostgreSQL 的两张表，M2 从 `SecurityContext` 读身份，不经过 M1 的任何 API。
3. **JDK 自带的 `HttpSession` 放在 JVM 堆里**，不持久化。

## 决定

**选 2。** 会话由 Spring Session JDBC 管理，`browser_session` 表删除；M1 与 M2 之间的接缝是一条**框架契约**而不是 M1 的 API：

> M2 读 `SecurityContext`，`Authentication.getName()` 返回 **userId**。Spring Session 按这个名字（`spring_session.principal_name`）建索引。

两张框架表的 DDL 由 `V3__account_login.sql` 建（逐字抄自 `spring-session-jdbc` 的 `schema-postgresql.sql`，只做小写化），在 `ModuleMap` 里算 M1 拥有；Boot 自带的建表器由 `spring.session.jdbc.initialize-schema: never` 关掉，Flyway 仍是唯一建 schema 的东西。

## 理由

**被否掉的选项 1，第一半是因为那五件事会写错。** 会话固定的防护不是加一行 `session_id = random()` 就完事：要在**认证成功那一刻**换 id，而且要在正确的时机把旧数据搬过去。超时与清扫同样——自建表要么没有清扫（表无限长），要么自己写一个定时任务。这些是框架已经做过上千次、且被大量生产部署检验过的东西，重写一遍的收益只是「表名是自己的」。

**第二半是接缝的形状。** 选项 1 要求 M2 要么读 M1 的表（TD 明确禁止），要么 M1 长出一个「这个会话属于谁」的方法。后者看着无害，实则把一个**框架本来就有的事实**包装成 M1 的公开契约：每加一种登录方式、每换一次会话存储，那个方法都要跟着改。选 2 之后 M2 读的是 `SecurityContext`——Spring Security 与 Spring Authorization Server 之间本来就存在的契约，**M2 完全不需要知道 M1 存在**。

**被否掉的选项 3 是因为它不满足已有的决定，不是因为它慢。** [ADR 0010](0010-storage-in-postgres.md) 说状态全在 PostgreSQL；堆内会话还会在重启时把所有人登出、在多实例下直接失灵（`/oauth/authorize` 落到另一台机器上就找不到会话）。为省两张表去违反一条已有 ADR，不划算。

**代价被接受的前提是它是可逆的**：会话存储是 Spring Security 的 `SecurityContextRepository` 接缝，换成 Redis 是换一个实现，不是改 M1 的代码。

## 后果

- **`spring_session` / `spring_session_attributes` 的 schema 不归我们定。** 升级 Spring Session 时，如果它改了 schema，改的是 V3 里那份抄件——**没有任何东西会替你发现这件事**：`TableOwnershipTest` 只比对表名集合。
- **会话属性用 JDK 序列化**（`spring_session_attributes.attribute_bytes`）。所以放进去的东西必须 `Serializable`，且**类一改名，所有存量会话就反序列化失败**——表现为用户被登出，而不是报错。`WebAuthentication` 因此只带一个 `String`，并声明了 `serialVersionUID`。
- **`principal_name` 必须放 userId，不能放用户名。** 这条现在没有编译期约束：写成用户名，登录照样成功、会话照样建立，只有「撤销这个人的全部会话」会静默地什么都不撤，以及 M2 的令牌 `sub` 会变成用户名。**它是本轮唯一一个「做错了测试也全绿」的点**，所以写在模块文档的不变量里，并由 `WebRegistrationIT` 断言 `principal_name == userId`。
- **`app_user` 与 M2 的 schema 一处都不用改。** M2 需要的东西（`app_user.id`、会话、`oauth_client` 等四张表）都已经在了——这是选 2 换来的：耦合停在框架契约上，没有渗透到 schema。
- 依赖多一个 `spring-boot-starter-session-jdbc`，且**必须与 `initialize-schema: never` 同一个提交**：只加依赖会武装 Boot 的建表器，它要么在 Flyway 建好的表上失败，要么在 Flyway 之外建表。

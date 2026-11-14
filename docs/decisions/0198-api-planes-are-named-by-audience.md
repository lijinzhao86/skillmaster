# 0198 · API 面按使用方命名：oauth / web / api / inner / admin

- **状态**：已采纳
- **日期**：2026-11-14
- **所属版本**：v69-ops-service（版本目录随开工单建立）
- **依赖**：[0015](0015-web-frontend-stack.md)（页面归 SPA，服务端只给 JSON）、[0088](0088-cli-major-version-is-the-api-generation.md)（CLI 主版本 = API 代次）、[0159](0159-manual-sync-is-triggered-by-ci.md)（内部触发与 nginx 404）
- **实现注记**（2026-11-14）：实际路由是 server 的 `POST /inner/v1/sync/submissions` 与 ops 的 `POST /inner/v1/jobs/import/runs`；正文里写的 `/inner/v1/sync` 是当时的占位名。§后果 里点名的「路由面清单测试、nginx 404 行为测试、`/admin` 拒绝测试」尚未落地，现状以 [`docs/current/rules.md`](../../docs/current/rules.md) §七为准。
- **相关**：v1-hosting 技术设计里的旧提法「`/inner` 是运维面」——历史正文不改，现状以本条为准

## 背景

仓库里今天实际存在四个入口面，但没有一条写下来的命名规范：

| 面 | 前缀 | 凭据 |
|---|---|---|
| OAuth 协议 | `/oauth/**`、`/.well-known/**` | 协议自带 |
| 浏览器控制台 | `/web/**` | 会话 cookie + CSRF |
| CLI 与 agent | `/api/v1/**` | Bearer token / API key + scope |
| 内部触发 | `/internal/sync/run` | 专用头 token + nginx 404 + 回环 |

同时，v1 文档与部分注释把 `/inner` 叫作「运维面」，但没有路由；`/admin` 完全没有。本版要把后台运营拆成独立服务，ops 与 server 之间需要一条**服务与服务**的写通道，于是三个名字开始互相挤：

- 服务间调用、CI 触发、运维页面，三者都不是「用户 API」，但不该共用一个前缀；
- `/internal` 已经存在，`/inner` 只是文档里的旧名；
- `/admin` 不能等到实现时才想它落在哪条安全链、暴露给谁。

## 决定

1. **五个面，一个前缀一个面**：

   | 前缀 | 使用方 | 凭据 | 暴露 |
   |---|---|---|---|
   | `/oauth/**`、`/.well-known/**` | OAuth 协议 | 协议自带 | 公网 |
   | `/web/**` | 浏览器控制台 | 会话 + CSRF | 公网 |
   | `/api/v1/**` | **CLI 与 agent** | Bearer / API key + scope | 公网 |
   | `/inner/v1/**` | **服务与服务**及 CI 等内部触发 | `X-Inner-Token` + 私网 IP 白名单 | **不对外** |
   | `/admin/v1/**` | **运营 / 管理控制台** | 运营角色（未来） | 公网但角色门禁 |

2. **路径前缀决定凭据。** 一条路由只能属于一个面；不得同一条路径在两种凭据下都可用。每个面一条 `securityMatcher` 精确匹配的链。
3. **`/inner` 不暴露给任何面外的东西。** nginx 对 `/inner/` 返回 404；server 另开一个只绑 VPC 私网 IP 的连接器（默认 :8081）专供 `/inner`；安全组只放 ops 的私网地址；应用层再校验 `X-Inner-Token`（常数时间比较）。三层缺一不可。
4. **`/admin` 是管理控制台的 API 面，不是服务端渲染页面。** 页面归 SPA（ADR 0015）；`/admin/v1` 只返回 JSON。运营角色落地前，`/admin/**` 显式拒绝，不能落到 `permitAll` 或 `authenticated()`。
5. **`/api/v1` 是 CLI 与 agent 共用面，不是 CLI 专用。** 写「CLI 与 agent」，避免以后把 agent 接入误判成越界。
6. **退役 `/internal`。** 现有 `/internal/sync` 在同步搬去 ops 时改成 `/inner/v1/sync`，server 上不留 `/internal`；nginx 的 404 规则随之改。
7. **默认拒绝。** 每个面的链最后用 `denyAll()`；公开例外逐条命名（`/actuator/health`、`/error`、`/cli-sign-ins/exchange`、`/gateway/**`）。`anyRequest().authenticated()` 不再作为兜底。
8. **每个面自己的版本。** `/api/v1` 跟 CLI 大版本（ADR 0088）；`/inner/v1`、`/admin/v1` 各自独立，不互相绑。
9. **同一套 JSON 与错误形状。** 所有 JSON 面使用 snake_case 与 `{"error":{"code","message","details"}}`；`inner` 不另造一套。
10. **`/admin` 与 skill 权限里的 `admin` 档不是一回事。** 前者是平台运营面，后者是某条 skill 的共享权限档位（`GrantRole.ADMIN`）；文档与代码注释必须写清这一句。

## 理由

- **为什么按使用方而不是按功能命名。** `/api/v1/skills` 与 `/web/skills` 可能调同一个 usecase，但凭据、暴露与失败形状不同；按功能命名会让两条路在一个前缀下分叉，最后变成「同一 URL 因调用者不同给不同答案」。按使用方命名，路径本身就说清了「该带什么凭据」。
- **为什么 `/inner` 要单独一个连接器。** 只靠 nginx 404 不够：nginx 是本机进程，ops 在另一台机器，直接打 server 的 8080 就绕过了。连接器绑私网 IP，才能让「不对外」成为网络事实，而不是一条配置约定。
- **为什么 `/admin` 先保留不实现。** 运营角色还没有；先给名字和拒绝规则，避免将来在 `/web` 下再长一套管理接口，最后两套权限模型打架。
- **为什么 `/internal` 必须退役。** 两个名字都表示「内部」，一个实现一个文档，读代码的人无法判断哪个是现状；保留兼容只会让新接口继续长在错的名字下。
- **为什么默认 `denyAll()`。** v1 就记过：`authenticated()` 让任何新加的无前缀 controller 被任何有效令牌碰到。面一多，这个洞的后果从「多一个接口」变成「跨面越权」。
- **为什么不把 `/api/v1` 改成 `/admin`。** `/api/v1` 已经是 CLI 与 agent 的发布契约；改名会破坏所有已发二进制，需要兼容期与新 ADR，收益只是名字整齐。

被否掉的：**继续用 `/internal`**（与文档旧名冲突，且不区分服务间与运维）；**`/inner` 同时做服务间和运维**（凭据不同，无法一条链）；**`/admin` 让 server 渲染页面**（违反 ADR 0015 的 SPA 边界）；**把用户 API 改名 `/admin`**（破坏 CLI/agent 契约）；**只靠 nginx 404 保护 `/inner`**（ops 不在本机，绕过 nginx 直达端口）。

## 后果

- **`SecurityConfig` 增加 `/inner/**` 链**：只认 `X-Inner-Token`，不认用户 Bearer；`/admin/**` 在运营角色落地前拒绝；兜底改 `denyAll()`。
- **部署多一个私网连接器**：server 主端口仍绑 `127.0.0.1:8080`；`/inner` 走 VPC 私网 IP:8081；安全组只放 ops。
- **nginx 规则从 `/internal/` 改成 `/inner/`**；ops 的同步触发端点用 `/inner/v1/sync`。
- **文档与测试**：`docs/current/rules.md` 增加「API 面与暴露规则」；`M01`、`model.md`、`TODO.md` 修正旧提法；新增路由面清单测试、nginx 404 测试、`/admin` 拒绝测试。
- **历史正文不改**：v1 技术设计里「`/inner` 是运维面」的句子是历史记录；现状以本条与 `rules.md` 为准。
- **`/inner` 的服务鉴权先做到「专用 token + 私网 + IP 白名单」**；mTLS 作为后续加固，不在本条一次性做。

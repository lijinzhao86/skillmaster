# ADR 0093 · 不发布 skill 发现索引

- **状态**：已采纳
- **日期**：2026-10-25
- **依赖**：[ADR 0089](0089-no-local-skills.md)（本机不装任何 skill）、[`versions/v17-cli-startup/`](../versions/v17-cli-startup/)

## 背景

服务端今天在匿名通道上发布三样东西：两条 Agent Skills 发现索引（`/.well-known/skills/index.json`、`/.well-known/agent-skills/index.json`）与网关 skill 本体（`/gateway/SKILL.md`，以及 `/.well-known/{gateway,skills,agent-skills}/skillmaster/*` 三个别名）。它们的共同前提是**客户端要在本机装一个 skill**——索引告诉它装什么、去哪取。

[ADR 0089](0089-no-local-skills.md) 删掉了那个前提：本机不装任何 skill，三条规则进系统提示词，旅程正文走 `/gateway/STARTUP.md`。但那条 ADR 只写了「网关 skill 与 `setup` 一并删除」，没有说索引怎么办。

## 决定

**两条发现索引一并删除**，连同它们背后的发布机制（`GatewayService`、`GatewayIndex`、`PublishGatewaySkillUseCase` 与启动时的自举）。匿名通道上只留 `GET /gateway/STARTUP.md`。

## 理由

- **索引没有对象了。** 它描述的是「这台主机发布了哪些 skill 可供本机安装」；这台主机不再发布任何本机要装的 skill，返回一份空列表只会让客户端多走一次没有结果的取用。
- **留一个永远为空的公开契约，比删掉它更贵。** 空索引要写测试、要防止它某天被别的功能填上、还要在文档里解释「为什么它永远是空的」。
- **被否的「保留但返回空列表」有一个具体代价**：客户端仍然可能把它当成一条可用的发现路径，先请求、再被告诉没有——这正是 ADR 0089 想消灭的那种「看起来有个入口」。

## 后果

- `/.well-known/skills/index.json` 与 `/.well-known/agent-skills/index.json` 从此是 404。**这是一次公开契约的删除**，发生在 `1.0.0` 之前（[ADR 0088](0088-cli-major-version-is-the-api-generation.md)）。
- `/.well-known/oauth-authorization-server` 不受影响：它属于授权服务器的元数据，走的是另一条路由与另一条安全链。
- 保留 `skillmaster` 这个保留命名空间与它的种子迁移（历史数据与保留名规则都还在），只是不再往里发布任何东西。

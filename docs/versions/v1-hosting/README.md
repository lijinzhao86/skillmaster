# v1-hosting · skill 托管与远程加载

> **最后更新**：2026-10-07
> **状态**：进行中。设计已定稿；**至今每一期都已实现**：P0a（读接口 / 提交 / 检索 / 网关）、P0c（`namespace/name[@版本]` 寻址与钉版，[ADR 0012](../../decisions/0012-addressing-and-version-pinning.md)）、P1 的 **M01**（注册 / 登录 / 登出 / 密码重置，2026-10-01，十个 `/web/*` 端点，含图形验证码与阿里云短信客户端——[ADR 0013](../../decisions/0013-phone-login-and-username-slug.md)、[ADR 0014](../../decisions/0014-browser-session-via-spring-session.md)）与 **M02**（授权同意页 + `/oauth/*` 令牌签发，2026-10-05，自建存储、只存哈希——[ADR 0023](../../decisions/0023-spring-authorization-server-with-our-own-storage.md)）、**P0d**（提交与上线拆成两个动作，[ADR 0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md)，2026-10-06）、**P0e**（版本名换成作者声明的 semver、只给 L1 的 `/metadata`、读接口上的 `X-Skill-Version`，[ADR 0033](../../decisions/0033-version-names-are-semver.md)）、**P0f**（读取面改成 `list`、skill 级共享，[ADR 0034](../../decisions/0034-skill-level-sharing.md)）与 **P0g**（读取面拆成 `invoke` / `read`，版本钉改由 CLI 记在本机，[ADR 0035](../../decisions/0035-the-pin-lives-on-the-client-machine.md)，2026-10-07）与 **P0h**（枚举一个版本的文件：CLI 的 `files`，[ADR 0036](../../decisions/0036-enumeration-is-a-read.md)，2026-10-07）。**网页**在独立子项目 `skillmaster-web/` 里（[ADR 0015](../../decisions/0015-web-frontend-stack.md)）：先是注册/登录/重置三个页面，`/skills` 的作者面（版本列表、原文、diff、上线、丢弃、共享）随 P0d 与 P0f 一起来了。**CLI 的命令也都实现了**（login / logout / setup，以及 `skill` 组下的 list / search / invoke / files / read / versions / submit / share），浏览器登录那条 **2026-10-05 在真浏览器里端到端走通**并留下了证据；**读取面（`invoke` / `files` / `read` / `versions`）在 2026-10-07 跑过一整轮**（真服务端 + 真库 + 真 CLI，含「别人的私有 skill 得到同一条 404」那一步）。**没跑过的**是 `list` / `share` / `submit --to` 那几条——缺的每一步写在 [`test-plan.md`](test-plan.md) §结果。
>
> **仍未实现**：`PATCH` 元数据、`restore`、版本历史列表（§4.3 剩下的行）；`/inner/**` 挪端口（P1）；`/oauth/token` 与 `/oauth/revoke` 的限流（记为缺口，见 [`test-plan.md`](test-plan.md) §已知问题）。**已实现但未验证的**：短信与阿里云的真实往返（签名与模板未过审，见 [`technical-design.md`](technical-design.md) §8 问题 12）、**提交 → 打开网页 → 点上线那次联合手工验证**、以及 T2–T4b 那几条要在真 agent 里跑的验收——都在 [`test-plan.md`](test-plan.md) §结果里逐条标着。**上线前必须关掉 `accept-any-code`**——验证码不比对、任意六位数字都通过（[`iterations/0011`](iterations/0011-register-flow-and-sms-state.md)）。
> **含收费**：否

<!--
这是版本记录主页，不是详述。每个问题只写一句话 + 链接；
需求细节在 prd.md，设计细节在 technical-design.md。约定见 docs-architecture skill。
-->

## 目标

把「**用户把 skill 托管上来，agent 通过远程服务搜索、读取、使用它**」这一条链路跑通。

skill 全部活在服务端，**本地不留 skill 副本**；客户端侧只装一个通用网关 skill 与一个自研 CLI。渐进加载由服务端**在接口层强制**（[ADR 0001](../../decisions/0001-server-authoritative.md)、[ADR 0002](../../decisions/0002-gateway-skill-and-cli.md)）。

完整需求见 [`prd.md`](prd.md) §用户与场景。

## 验收标准

一句话：**在一个干净环境里，agent 自己搜到、读到、用到某个 skill，且全程没有任何 skill 副本落到本地。**

完整十条与判定方式见 [`prd.md`](prd.md) §验收与指标（映射见 [`test-plan.md`](test-plan.md) §验收映射）。**其中第 5 条（无本地副本）是成立条件**——它不是附带要求，验的是"服务端权威"能不能真的兑现。

## 这一版明确不做什么

最要紧的三条；**完整范围与每条的理由见 [`prd.md`](prd.md) §范围**。

- 不做**收费与门控**（先把托管跑通）
- 不做**脚本执行**（独立硬工程 → [ADR 0008](../../decisions/0008-no-script-execution.md)）
- 不做 **MCP 适配器**（API 为主契约 → [ADR 0003](../../decisions/0003-api-primary.md)）

## 与目标态的关系

[`target/design.md`](../../target/design.md) 描述的是**完整形态**：公开市场 + 渐进付费，带 M1 / M2 / M3 路线。

**v1-hosting 不等于那份文档里的 M1。** 那份 M1 是"私有托管 **+ 渐进付费**"；本版是"托管，**不含收费**"。两者已经分叉——这正是本版独立成文件夹、不再挂在旧路线里的原因。

本版是目标态的**真子集**：交付形态（网关 skill + API + 自建鉴权）会保留，收费与公开市场留给后续版本。**决策记录（[`decisions/`](../../decisions/)）跨版本共用**，不需要重复。

> 目标态文档里仍有已被 ADR 推翻的机制（`l2#n`、模块 2.5 的本地 Gate 模型）。**逐条差异见 [`target/README.md`](../../target/README.md)**——读 `target/` 之前先读它。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、验收与指标、依赖与前置。**需求的权威来源** |
| [`technical-design.md`](technical-design.md) | 系统架构、表结构、接口定义、分期与开放问题。**设计主体**；概念模型已移到 [`architecture/`](../../architecture/README.md) |
| [`test-plan.md`](test-plan.md) | 验收映射、用例（含成立条件的反证）、环境与数据、结果。**服务端与各轮迭代的结果都在 §结果 里**；仍未执行的是 T2–T4b 那几条要在真 agent 里跑的验收 |
| [`known-issues.md`](known-issues.md) | 首次提交（`c8a7362`）对**重写前基线代码**的审计：**条数与分布见该文件开头**，随架构重写一并处理 |
| [`iterations/`](iterations/) | 小版本迭代记录（只增不改） |

## 影响最大的未决问题

完整列表与当前倾向见 [`technical-design.md`](technical-design.md) §开放问题。最要紧的三条：

1. **网关 skill 的 `description` 怎么写**——目录不常驻，这句决定发现体验的上限，且没有数据可依。
2. **检索与排序的权重**——在这个模型下排序**就是**产品核心（[ADR 0006](../../decisions/0006-search-server-side.md)）。
3. **中文检索的索引形态**——原计划（SQLite FTS5 + `trigram`）已实测不可行，方向转为 PostgreSQL 的 `pg_bigm`，**待验证**（[ADR 0010](../../decisions/0010-storage-in-postgres.md)）。

## 下一版可能是什么

尚未决定。候选方向：**组织与成员体系**（`namespace_member` 的角色管理与邀请——它才是真正需要组织模型的那一条）、脚本执行形态、MCP 适配器、**公共发现**（`visibility` 的 `public` / `unlisted`）、公开市场与付费。

> **一条已经从这张单子上划掉：分享。** 「把一个 skill 给一个人」不需要组织模型，所以它在 v1 里做了（[ADR 0034](../../decisions/0034-skill-level-sharing.md)，P0f）。留在上面的两件事——组织/成员/角色与公共发现——确实需要组织模型。

任何一条都要先在本目录旁边新建 `versions/vN-主题/`，并按 [`.claude/skills/docs-architecture/`](../../../.claude/skills/docs-architecture/SKILL.md) 的四件套补齐，然后跑一次 `check_docs.py`。

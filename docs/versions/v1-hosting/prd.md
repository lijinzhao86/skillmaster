# v1-hosting · 产品需求

> **最后更新**：2026-10-07
> **状态**：需求已确认。实现进度见 [`README.md`](README.md) §状态（此处不复述，同一个事实只有一个权威位置）。
> **所属版本**：[`README.md`](README.md)

<!--
2026-09-26 迁移时由原 README 与 target/ 文档提取而成，不是新写的需求。
同日产品负责人确认了三项：用户范围只到「登录 + 鉴权」（不建组织 / 成员 / 角色）、
搜索命中率只定口径不设目标、不加其他指标。已全部落进正文，无遗留待定项。
-->

## 用户与场景

**谁在用**

| 角色 | 在这一版里做什么 |
|---|---|
| 用户（一个自然人） | 注册 / 登录——**手机号 + 短信验证码 + 密码**，并自取一个**公开的用户名**（它就是个人命名空间的 slug）；把自己手上的 skill 托管到自己的个人命名空间下：**提交**产生一个草稿版本，**上线**是网页上的另一个动作（[ADR 0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md)）。飞书仅作跳转鉴权，不作身份主键 |
| 同一个用户，在 agent 里 | 提出任务；agent 自己搜到 skill、读到正文、按需取文件并完成任务 |

**v1 只有这一个角色。** 没有组织、没有成员、没有角色区分，也没有「别人托管的 skill 能被我搜到」这回事。

**这一版只做「用户登录 + 鉴权」**：注册即得到一个**个人命名空间**，一个用户管自己的 skill。组织 / 成员 / 角色体系、分享、公共发现与购买**都留给后续版本**。

**在什么场景下用**

一个人对 agent 说了一件需要某个 skill 才能做好的事。agent 当前没有这个能力——它**不应该要求用户先把 skill 装到本地**，而应该按需向服务端取用。

具体链路（也是验收要走的那条路径）：

```
用户在 agent 里提问
  → agent 从常驻的网关 skill 知道服务地址与调用协议
  → CLI list    → 服务端只返回 L1 卡片（地址不带版本）
  → CLI invoke  → 加载正文（L2），并把这一版记在本机
  → CLI files   → 看这一版有哪些文件（零内容）
  → CLI read    → 按需取其中某个文件（L3）
  → 完成任务
```

**作者那一侧是另一条链路，与上面那条刻意分开**（理由是「上线改变 agent 能读到什么，所以要一个刻意的人工动作」，见 [ADR 0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md)）：

```
CLI submit <本机目录>            → 服务端存下一个 draft 版本（此刻 agent 读不到它）
  → 网页 /skills/<用户名>/<名字>  → 看到一个 skill 的全部版本与各自状态、看这一版相对线上版本的 diff
  → 点「上线」                    → 指针前移，agent 从此读到的就是它
```

**现在的替代做法**

1. **把 skill 下载到本地**，靠客户端原生分层加载。这是现成生态的做法（Vercel 的 `skills` CLI、`~/.agents/skills/` 落点）。
2. **不托管**，每个人自己维护一份 skill 目录。

替代做法 1 的代价有两条，正是这一版要解决的：

- **目录成本随已安装 skill 数线性常驻上下文**——本机 30 个真实 SKILL.md 实测约 160 tokens/skill，500 个约 8 万 tokens 常驻（口径见 [`technical-design.md`](technical-design.md) §1.6）。
- **内容一旦落盘就收不回**——撤销、封禁、更新都无法即时生效；将来要加的任何授权机制都只能靠客户端配合。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | skill 托管：命名空间下的 skill 与不可变版本、内容寻址存储。**提交**产生一个草稿版本，**上线**是网页上的另一个动作（[ADR 0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md)） | 服务端存储 + 写接口 |
| 2 | 远程搜索：只返回 L1（`name` / `description` / `when_to_use`），服务端排序 | `GET /api/v1/skills` |
| 3 | 远程读详情：返回 L1 + **完整文件清单，零内容**；另有**只给 L1** 的 `/metadata`——渐进加载的第一层，不含清单 | `GET /api/v1/skills/{ns}/{name}[@版本]`、`…/metadata` |
| 4 | 远程读正文（L2）与单个文件（L3），并列出可读的版本 | `GET /api/v1/skills/{ns}/{name}[@版本]/body`、`/files/{relpath}`、`/versions` |
| 5 | 网关 skill + 自研 CLI（`login` / `logout` / `setup`，以及 `skill` 组下的 `list` / `search` / `invoke` / `files` / `read` / `versions` / `submit` / `share`） | 一份网关 skill + 一个 CLI |
| 6 | 自建登录与令牌签发（OAuth 2.1 AS，**v1 的客户端注册方式只做预注册**——客户端只有我们自己的 CLI；CIMD 到 P2 才需要，DCR 不启用，见 [ADR 0011](../../decisions/0011-server-and-cli-stack.md)）。**账号用手机号 + 短信验证码 + 密码自助注册，并自取一个公开的用户名**（[ADR 0013](../../decisions/0013-phone-login-and-username-slug.md)），密码可重置 | 注册页 + 登录页 + 密码重置 + 令牌接口 |
| 7 | **作者面**：自己的 skill 列表、一个 skill 的全部版本与各自状态、版本之间的 diff、上线与丢弃 | `skillmaster-web` 的 `/skills` 页 + `/web/skills` 六个端点 |
| 8 | **版本名**：作者在 `SKILL.md` 顶层 `version:` 声明 semver，它是**不可变别名**（身份仍是 digest）；**不声明就没有名字**，那一版只能按 digest 钉；读接口可用请求头 `X-Skill-Version` 选版本 | 服务端校验 + 地址与头两种选择方式（[ADR 0033](../../decisions/0033-version-names-are-semver.md)） |
| 9 | **skill 级共享**：把一个 skill 授权给某个用户，角色 `viewer`（只读）/ `editor`（可提交版本、可丢弃自己提交的草稿，**不能上线**）。`skill list` 的默认范围随之变成「我的命名空间 ∪ 授权给我的」 | 服务端授权表 + 三个端点；网页面与 CLI 的 `skill share` 两个入口 |

**渐进加载由服务端在接口层强制**：搜索只给 L1、正文接口只给 L2、文件接口只给 L3。不是靠模型自觉。

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 收费、定价、权益、门控、钱包 | 先把托管跑通；设计要点保留在 [`technical-design.md`](technical-design.md) 附录 B | 未定（见 [`target/`](../../target/README.md)） |
| 2 | `l2#n` 正文内细切 | 非标准概念；标准对"想少加载正文"给的答案是拆到 `references/` → [ADR 0009](../../decisions/0009-drop-l2n.md) | 不做（已否决） |
| 3 | 脚本执行（服务端沙箱或客户端执行） | 独立硬工程，且两条路各与既有前提冲突 → [ADR 0008](../../decisions/0008-no-script-execution.md) | 候选方向之一 |
| 4 | MCP 适配器 | API 为主契约，适配器按需再加 → [ADR 0003](../../decisions/0003-api-primary.md) | 候选方向之一 |
| 5 | 私有 skill 的免客户端访问 | 鉴权必须有凭据持有者（CLI）；网关 skill 本身绝不携带 token → [ADR 0007](../../decisions/0007-self-built-oauth-as.md) | 不做（结构性） |
| 6 | 在线 SKILL.md 编辑器、内容审核、个性化推荐 | 沿用目标态文档已界定的边界 | v2 以后 |
| 7 | 组织 / 成员 / 角色体系、**公共发现**与购买 | 这几项都要先有组织模型，而它会显著拉大范围 | v2 以后（**「分享」已从本条移出**，见「做」#9） |

> **「分享」为什么从这条里拿出来了**（2026-10-07，[ADR 0034](../../decisions/0034-skill-level-sharing.md)）：这条把它们捆在一起，理由是「都要先有组织模型」——而那个理由**对分享不成立**。「把**一个** skill 给**一个**用户」只需要 skill 与 user 两个已经存在的实体，组织模型是「谁能进我的命名空间」才需要的。捆在一起的代价是具体的：用户明确要的能力被一条与它无关的依赖挡住了。
>
> 留在本条的仍然是**组织 / 成员 / 角色**（`namespace_member` 的角色管理与邀请）与**公共发现**（`visibility` 的 `public` / `unlisted`）——那两件确实需要组织模型，本轮不做。

## 验收与指标

### 验收标准

在一个干净环境里：

| # | 验收标准 | 怎么判定 |
|---|---|---|
| 1 | 用户能注册并登录，拿到可用的令牌 | 干净环境里走完注册（**手机号 + 短信验证码 + 密码 + 用户名**）→ 登录 → CLI 拿到令牌，并用它成功调一个需鉴权的接口。**短信的签名与模板要先审核通过**，这是本条的外部前置（[`technical-design.md`](technical-design.md) §8 问题 12）；**开发期验证码不比对**（`accept-any-code`，[`iterations/0011`](iterations/0011-register-flow-and-sms-state.md)），**这一条要在那个开关关掉之后才算真的成立** |
| 2 | `setup` 能装上网关 skill | 装完后本机存在网关 skill，且 frontmatter 常驻成本 < 200 tokens |
| 3 | 在 agent 里提出一个需要某个 skill 的任务，agent **自己**搜到它 | 不人工指定 skill 名；agent 通过网关 skill 的 `description` 命中并调用 CLI。**前提**：有人在此之前提交过它、并**在网页上把它上线**了——没人上线就什么也搜不到（[ADR 0031](../../decisions/0031-submitting-and-publishing-are-two-actions.md)） |
| 4 | agent 加载正文、按需取文件，并完成任务 | 任务产出正确；过程中发生过 L2 与 L3 的按需读取。**顺序是硬要求**：先 `skill invoke`（它解析并钉住版本）再 `skill read`，跳过 invoke 的 `read` 取到的是「当前版本」，任务中途有人发布会换版本——命令会把这种情况印出来 |
| 5 | **全程没有任何 skill 内容被当作副本落到本地** | 干净环境跑完后，本机不存在任何 skill 的完整副本。**文本内容不再经过磁盘**（打到 stdout）；**二进制文件是唯一例外**，它落到临时目录、只印路径——宿主 `Read` 能渲染图片，那个路径是模型唯一能看到它的路 |
| 6 | 作者能对同一个 skill 多次提交，并在网页上把其中一版上线 | 提交两版 → 网页上看到全部版本与各自状态 → 看这一版相对线上版本的 diff → 上线其中一版，agent 一侧读到的随之改变；再把旧版上线回去（回滚）也成立 |
| 7 | 版本名按通用规范（**作者声明的 semver**），且能按版本名或内容摘要求到指定版本 | 提交一份带 `version: "1.0.0"` 的 skill → `@1.0.0` 与 `@sha256:…` 都取得到，而 `@1` 是 404；改内容不换号重提被拒（`version_already_exists`）；只带 `X-Skill-Version` 取到的是同一版；提交一份**不带** `version:` 的，确认它只能按 digest 钉 |
| 8 | **`skill list` 是读取的入口，而且共享真的生效** | 一个账号 `skill list` 出自己的全部 skill，每条印出的地址**不带版本**（[ADR 0035](../../decisions/0035-the-pin-lives-on-the-client-machine.md)）、可整段照抄给 `skill invoke`；`skill share --to <另一个账号> --role viewer` 之后，对方 `skill list` 里出现它、`skill invoke` 加载得到正文，而**提交被拒（403）**；改成 `--role editor` 后提交成功，**上线仍被拒**；撤销之后它从对方的 `skill list` 里消失。另：一条多于 100 条的库上 `skill list --all` 能翻到最后一页，且中途失败会报错而不是打出一份半截列表 |
| 9 | **`invoke` 真的钉住版本，`versions` 真的能看到被顶替的旧版本** | `skill invoke <地址>` 后，`skill read <地址> <某文件>` 取到的是**那一版**的字节；这期间在网页上发布新版本，同一个 `read` 仍取到旧版那一份；再 `invoke` 一次会印出「钉从 X 移到 Y」。`skill versions <地址>` 列出全部**已发布**版本（含被顶替的），拿其中一行的地址 `invoke` 能加载那一版；草稿不在列表里，一版都没上线时是 404 |
| 10 | **一个版本有哪些文件问得到，而且只有读得到它的人问得到**（[ADR 0036](../../decisions/0036-enumeration-is-a-read.md)） | `skill files <地址>` 列出该版全部文件的相对路径（**不含 `uri` 与 digest**，正文那一条有标注）；把路径打错时 `read` 的报错指路到 `files`；**未被共享的第二个账号在 `files` 与 `read` 上都是 404**（不是 403、也不是空清单），`share --role viewer` 之后两者都通；未上线的草稿版本不在其中 |

**成立条件：第 5 条。** 它不是附带要求——它验证的是"服务端权威"能不能真的兑现。第 1–4 条只说明链路通了，第 5 条说明选型对了。

> 边界：agent 完成任务时的中间产物落盘**不算**副本（二进制文件落到临时目录属于这一类，而文本内容现在根本不经过磁盘），见 [`technical-design.md`](technical-design.md) §4.6。

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| 网关 skill 的常驻成本 | 网关 frontmatter 的 token 数 | 同 §1.6 的口径 | < 200 tokens（一个网关，而非 N 个 skill） |
| 搜索命中率 | 提出任务后 agent **不点名**也能自己搜到正确 skill 的比例 | 准备一组真实任务（起点 **20 个**），每个任务人工标注「期望命中的 skill」；agent 跑完后由人判定它首次 `search` 的结果里是否包含该 skill（**top-3 内即算命中**） | **不设目标**——先测出基线，再据基线定数。现在拍一个没有依据的数只会自欺 |

**这一版不加别的指标。** 响应延迟、p95、单次取用的 token 成本都需要实现后才能测，现在写数字是猜的；先把托管链路跑通。要加时另开一条迭代记录。

## 依赖与前置

- **内部前提**：架构取舍已定，逐条见 [`decisions/`](../../decisions/) 的索引——**这里不重列**，列一份就等于给它第二个会漂的副本（决策跨版本存活，版本内文档只链接）。
- **外部依赖**：
  - GitHub 仓库 `lijinzhao86/skillmaster`（public）—— 仓库已建、代码已推、`main` 已开强制 PR；CI 配好后实跑通过一次，现**有意停用**（见 [`technical-design.md`](technical-design.md) §2.4）
  - **部署平台是阿里云**（2026-09-27 确认）：原先按 AWS / EKS / ECR 写的路线**作废**。服务端跑在**单台 ECS** 上（[`ADR 0011`](../../decisions/0011-server-and-cli-stack.md)；多副本是将来），数据库是**托管 RDS PostgreSQL、规格已定**（[`ADR 0010`](../../decisions/0010-storage-in-postgres.md) 决定 3）。**待定的只是镜像仓库等登记细节**
  - **阿里云短信**（2026-10-01 新增）：注册与找回密码的验证码（[`ADR 0013`](../../decisions/0013-phone-login-and-username-slug.md)）。**签名与模板需审核通过才能发**，周期不在我们手上，因此它是验收 #1 的前置。单价与套餐**尚未核实**——见 [`technical-design.md`](technical-design.md) §8 问题 12
- **排除了什么前置**：支付通道、实名合规、内容审核——它们是把公开市场做起来的前置，这一版不含收费与公开市场，因此**都不是本版的前置**（这也是先做私有托管的理由之一，见 [`target/blueprint.md`](../../target/blueprint.md)）。

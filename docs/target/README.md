# 目标态产品文档

> 最后更新：2026-09-26
> 这两份文档描述**完整形态（目标态）**：私有托管起步 → 公开市场，含渐进付费与跨生态分发。
> **它们不是当前版本的实现依据。** 当前版本见 [`versions/v1-hosting/`](../versions/v1-hosting/README.md)——只做 skill 托管与远程加载，**不含收费**。

| 文档 | 回答什么 |
|---|---|
| [`blueprint.md`](blueprint.md) | **为什么做、为谁做、凭什么成立**（商业论证、竞品格局、差异化） |
| [`design.md`](design.md) | **怎么做**：模块划分、核心场景、页面结构、概念模型、阶段路线 |

两份文档互为约束：`design.md` 的任何模块若无法解释它服务于 `blueprint.md` 里的哪一条用户价值，就不该做。

---

## ⚠️ 本文与当前决策的差异（必读）

这两份文档写于 **2026-09-20**，**早于 [`decisions/`](../decisions/README.md) 里的架构决策**（2026-09-26）。

正文**按原样保留**——里面的产品思考是有价值的资产，不做重写。但**照着它实现会做错**：有几处机制已经被 ADR 推翻。下面是逐条差异，**一律以 ADR 为准**。

### 一、已被推翻的机制

| # | 文档里的说法 | 位置 | 现在的决定 |
|---|---|---|---|
| 1 | 正文内细切 `l2#n`，每段单独定价 | `design.md` 第 0 章核心机制表 `:23`、设计边界 `:47`；第 5 章概念模型 `kind` 枚举 `:720`；附录 `:826` | **砍掉。** 付费（将来若做）只落在标准已有的**层与层之间** → [ADR 0009](../decisions/0009-drop-l2n.md) |
| 2 | 「节点必须与官方加载层严格对应」 | `design.md` `:39` | 需改述：L1/L3 对应标准层，`l2#n` 正是不对应的那个 → [ADR 0009](../decisions/0009-drop-l2n.md) |
| 3 | 「细切标记的具体语法」列为待决问题 | `design.md` 第 6 章 Q1 | **该问题已被关闭**，不必再定语法 → [ADR 0009](../decisions/0009-drop-l2n.md) |
| 4 | **模块 2.5「分发与运行时门控」整套**：本地缓存实现 Gate、`MaterializePlan` / `LockedManifest` / `GateDecision` / `PaywallPolicy`、materializer 一次性全部落盘 | `design.md` `:237`–`:293`（另散见 `:40`、`:417`、`:428`、`:439`、`:463`、`:607`、`:613`、`:615`、`:697`、`:806`、`:813`、`:824`）；blueprint 侧的同一模型见"按层分发"一节 `:275`–`:281` | **模型整个换掉**：服务端权威、**本地不落 skill 副本**，门控只在服务端。`materializer.py` 已删除 → [ADR 0001](../decisions/0001-server-authoritative.md)、[ADR 0002](../decisions/0002-gateway-skill-and-cli.md) |
| 5 | 「L3 的落盘与门控是核心路径，不是附属功能」 | `design.md` `:40` | 同上——**落盘已不是路径**，L3 由服务端按需下发 → [ADR 0001](../decisions/0001-server-authoritative.md) |
| 6 | 「保留细切能力（在正文内切出「前几节免费」）」 | `blueprint.md` `:353` | 同上，**不保留** → [ADR 0009](../decisions/0009-drop-l2n.md) |

### 二、论证前提已作废

| # | 文档里的说法 | 位置 | 现在 |
|---|---|---|---|
| 7 | skill-platform 的 **materializer** 产出「目录树 + SKILL.md」→「天然跨线」，是被低估的优势 | `blueprint.md` `:96` | **跨线能力仍在，但来源变了**：来自服务端权威 + 网关 skill + API（任何能发 HTTP 的 agent 都能用），不来自 materializer。该模块已删除 → [ADR 0001](../decisions/0001-server-authoritative.md) |

### 三、需要协调或确认

| # | 事项 | 位置 | 状态 |
|---|---|---|---|
| 8 | **沙箱 / 脚本执行**：blueprint 说「**不做**沙箱代理执行」（读起来是永不做）；design 则**明确规划在 M3 做** | `blueprint.md` `:384` vs `design.md` `:243`（"M3 **前**不代理执行 skill 的脚本"）、`:345`（安全治理职责含"沙箱执行"）、`:355`（"M3 沙箱 + 实名 + 官方审核队列"）、`:371`（"M3 需要沙箱执行…缺一不可"）、`:809`（阶段路线 M3 列"沙箱"）。`:813` 只是 M1 的"不做" | **真冲突，不只是表述差异**：一处说永不做，一处说 M3 就做。需与 [ADR 0008](../decisions/0008-no-script-execution.md)（P0 不做脚本执行）对齐后统一口径 |
| 9 | **身份/组织模型**：design 用 `Org` / `Membership(role)` + `Skill(owner)`；实现层用 `namespace` / `namespace_member`，并**为每个用户自动建个人命名空间** | `design.md` `:129`、`:711`–`:737` vs `technical-design.md` §3.2 | **不只是改名**：namespace 模型**多出"个人命名空间"这一档**，design 的 `Org` 没有对应物。以 [ADR 0004](../decisions/0004-opaque-id-primary-key.md) 为准，术语权威在 `technical-design.md` §3.2 |
| 10 | **可见性档数**：target 是 4 档（`public / org / private / unlisted`）；v1-hosting 定的是 **2 档**（`private` / `public`，[ADR 0037](../decisions/0037-visibility-has-two-states.md)——公开即会被搜到，`unlisted` 被**放弃**而不是推迟，`namespace` 与 `skill` 两列都是）——见 `technical-design.md` **§3.2**（`namespace.visibility`）与 **§3.3**（`skill.visibility`） | `design.md` `:87`（值表）、`:390`（发布时确认可见性）、`:584`（S11） | 两条缺口**性质不同**，别一起处理：`org` 是**没做**（v1 不做组织模型，需确认它是被 namespace 成员关系吸收了，还是漏了）；`unlisted` 是**不要了**——`design.md` 那张值表与 S11 一旦要改，S11 得先回答「改用 `skill_grant` 之后这个场景长什么样」。**同一处还有一条新的差异**：v1 现在有**skill 级授权**（`viewer` / `editor`，[ADR 0034](../decisions/0034-skill-level-sharing.md)），而 design 的权限是「个人 / 组织 / 公开」三层再叠权益与钱包计算——**那是另一套机制**，v1 那条不依赖组织模型。照 `design.md` 实现权限会做错 |
| 11 | **权益与版本**：design 说「买断即获得后续更新，所以**权益与版本无关**」；ADR 0005 说内容一变 digest 集合就变、**客户端据此重新取得信任（重新批准）** | `design.md` `:742`、`:155` vs [ADR 0005](../decisions/0005-content-addressing.md) | 两者张力真实存在：**每次发布都会强制重新批准**。需要在实现前定下口径 |
| 12 | **附录「已锁定的产品决策」** 声称 8 项「本文档不得自行改动」，但**其中 2 项已被 ADR 取代**：付费技术强度（本地 Gate → [ADR 0001](../decisions/0001-server-authoritative.md)）、付费点定义（`l2#n` → [ADR 0009](../decisions/0009-drop-l2n.md)） | `design.md` `:817`–`:830` | 决策内容本应进 [`decisions/`](../decisions/README.md)，此处属于重复且部分过期。**注意「计价方式 = 节点买断」并未被取代**——ADR 0009 只移动了付费边界，买断粒度仍是节点；「版本策略」那一项的问题见第 11 条 |

### 四、体例

| # | 事项 | 位置 | 说明 |
|---|---|---|---|
| 13 | 两份文档自带版本号（`design.md` **v0.1**、`blueprint.md` **v0.2**，均标 2026-09-20）与「产品规划稿」状态 | 各自文件头 | 与仓库约定「**版本由文件夹承担**、文件内部不写版本号」冲突，读者容易误以为它们属于 `versions/` 的某版 |
| 14 | **产品名**：正文通篇用旧名 `skill-platform`，含 `blueprint.md` 的标题 `:1`、`design.md` 全篇，以及 `:19` 那段**对外发布文案**（「AI agent 技能托管与分享平台「skill-platform」正式上线」） | `blueprint.md`（50 处）、`design.md`（8 处）、本文件 `:37`（引 `blueprint.md:96` 的原话） | **产品名已于 2026-09-27 改为 `SkillMaster`**（域名 `skillmasterai.com`）。正文按上面的§维护约定**保持旧名**，不作为改名遗漏——读到 `skill-platform` 时按 `SkillMaster` 理解 |

### 五、v14 新增的扩展

| # | 事项 | 位置 | 说明 |
|---|---|---|---|
| 15 | **标签只有一层**：`design.md` 把 `Tag` 列为发现层的关键对象，但没有区分公开分类与私人标签 | `design.md` §2.6「发现与浏览」 | **v14 把标签定义成两层**：公开分类由平台词表提供、**所有能看见该 skill 的人可见**、用于公域发现；私人标签是「人 + skill」、只有本人可见、用于个人整理。目标态没有私人层，这是本版的扩展；两层都不参与排序——标签是过滤条件，不是权重（[`versions/v14-skill-tags/`](../versions/v14-skill-tags/README.md)） |
| 16 | **没有「我的库」**：目标态的消费者直接从公域搜索/链接进入 skill 详情并使用（S2），没有“先把一个公开 skill 加入我的目录”这一层 | `design.md` §2.6「发现与浏览」、S2 | **v14 加了私域库**：库是“**我创建的 + 共享给我的 + 我加入的**”引用集合；加入不复制内容、不改变访问权、不挡加载（公开 skill 仍可直接调用，[ADR 0048](../decisions/0048-visibility-hands-out-a-rung.md) 不动），它解决的是**上下文预算**与**私人标签的归属面**。公域只搜不整表列，也是同一原因（[`versions/v14-skill-tags/`](../versions/v14-skill-tags/README.md)） |

---

### 六、v25 新增的扩展

| # | 事项 | 位置 | 说明 |
|---|---|---|---|
| 17 | **系统提示词里的规则段不再属于平台技能** | `design.md` 里没有这一层；v20 起它是 `skillmaster/startup` 的 `steps/03-prompt.md` | **v25 把规则段搬进 CLI 二进制**（`internal/prompt/block.md`），随版本发布、由 `skillmaster prompt` 管理用户文件里的那一份。理由：规则段描述的是 CLI 的命令面，两处各改各的就会出现「提示词点名一条不存在的命令」；依据是 [`0102`](../decisions/0102-the-rule-block-ships-with-the-cli.md) |

## 维护约定

- **正文不重写。** 差异以本清单承载；`target/` 的正文保持 2026-09-20 的原貌。
- **新增差异就加一行**，写明位置与对应的 ADR。行号会随正文编辑漂移，**以章节名为主、行号为辅**。
- **不要**把 `target/` 的内容当作当前实现依据——判断依据永远是 `decisions/` 与 `versions/v1-hosting/`。

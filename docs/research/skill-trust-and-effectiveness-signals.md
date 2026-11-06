# Skill 市场的信任与效果信号：跨行业调研

> **状态**：调研记录，2026-11-05。数字是时点快照，引用时带日期与口径。
> **问题**：公域 skill 多起来之后，用户和 agent 凭什么判断「这个 skill 可信、有效、适合我」？调用量、GitHub star 之外，还需要什么？
> **范围**：ClawHub / 腾讯 SkillHub、Anthropic 官方市场、Smithery 与 MCP 注册表、GPT Store、VS Code Marketplace、Chrome Web Store、Docker Hub、npm、Hugging Face。生态格局见 [`competitors.md`](competitors.md)，本文不重复它的市场对比。

## 一、先看事实：各平台拿什么替用户做判断

| 平台 | 信任信号 | 效果信号 | 排序 / 策展 | 关键教训 |
|---|---|---|---|---|
| **ClawHub** | 发布者 GitHub 账号年龄；VirusTotal 报告；无事前审核 | 安装量、star、30 天增量 | 安装量 / 下载量榜；无编辑精选 | 安装量是注意力，不是安全（见下） |
| **腾讯 SkillHub** | TRACE 的 T（安全红线，一票否决） | TRACE 的 E：有/无 skill 的对照实验；下载量 | 每月人工 TOP10；不做全量评分 | 效果要用对照实验，不靠口碑；覆盖少而稳 |
| **skillhub.club** | AI 五维评分（S/A/B/C），自称不等于安全审计 | 实用性、清晰度、自动化、质量、影响 | 评分 + popular | AI 评分可规模化，但不能替代安全审计 |
| **Anthropic 官方市场** | 申请 + 自动筛查 + 人工审核；Verified 徽章 | 安装量、页面浏览、搜索词（给发布者） | 官方目录；分类 | 官方审核是最强的信任来源，但供给受限 |
| **Smithery / MCP 注册表** | 命名空间绑定（GitHub OAuth / DNS / OIDC）；manifest 校验 | 安装量 | 目录 + 第三方评分 | 「注册表通过」不等于「代码安全」；身份绑定只解决冒名 |
| **GPT Store** | 发布者资料验证；人工 + 自动审核 | 对话量（近似值）、1–5 星评分、回访率 | 分类榜、编辑精选、周榜 | 评分可刷；对话量是使用，不是效果 |
| **VS Code Marketplace** | Verified Publisher（域名所有权 + 6 个月良好记录）；每次发布多引擎扫描 | 安装量、评分/Q&A、仓库与许可证 | 安装量主导 | 徽章只证明身份；安装量可被更新与捆绑抬高 |
| **Chrome Web Store** | Featured（人工审安全/隐私/设计）、Established Publisher | 用户数、评分数量与均值 | Google 明说评分与数量参与排序 | 评分影响排序，但不验证真实性 |
| **Docker Hub** | Official Images（Docker 策展）、Verified Publisher（付费 + 验证）、签名 | pulls | Verified 有搜索加权 | 信任分层 + 搜索加权是最直接的商业做法 |
| **npm** | 发布者、仓库、许可证；Sigstore provenance（约三成头部包有证明） | 周/月下载、dependents | 2026 改为客观排序：相关度、下载、依赖数、发布时间；去掉主观综合分 | 综合质量分难维护，npm 退回「可量化 + 少量反垃圾」 |
| **Hugging Face** | 模型卡、组织认证 | 下载、likes、trending | 下载 / likes / 最近更新 | 研究：下载与效果只有弱相关；96% 模型卡不写偏见与风险 |

## 二、五个可复用的结论

### 1. 流行度不是质量，更不是安全

- Trent AI 2026 年扫描 ClawHub 全量 52,652 个 skill，对最流行的 2,354 个做行为与 VirusTotal 扫描：**9.6% 良性、86.0% 有漏洞、4.4% 恶意**；下载量与 star、安装量高度相关（r≈0.84–0.86），但与安全、维护几乎无关。原文结论是「popularity is a measure of attention, not trust」。
- Hugging Face 500 个最常下载的情感分析模型研究（ICWSM 2025）：下载/likes 与效果只有弱相关，部分为负相关；约 88% 的模型卡夸大效果，96% 不写偏见与风险。
- VS Code 的安装量可被自动更新与捆绑抬高；有研究记录了刷量案例。
- **对 SkillMaster 的含义**：调用量可以作为「有人在用」的证据，但不能单独成为「好」的结论，更不能成为安全结论。

### 2. 身份、来源、安全、效果是四件不同的事

- **身份**：MCP 官方注册表用 GitHub OAuth / DNS / OIDC 绑定命名空间；VS Code 的 Verified Publisher 证明域名所有权与六个月良好记录；Docker Verified Publisher 是付费 + 验证。
- **来源**：许可证、上游仓库、是否来自开源同步、是否官方维护。ClawHub 的许可证大量「未指定」，这本身就是风险。
- **安全**：VirusTotal、沙箱行为、权限最小化。ClawHub 的教训是「扫描通过」与「行为安全」经常不一致；注册表本身不扫描代码。
- **效果**：TRACE 的 E 维度与 SkillsBench 都用**有/无 skill 的对照实验**，而不是评分或下载量。
- **对 SkillMaster 的含义**：不要把四件事压成一个分数或一枚徽章。分开展示，用户才知道自己在看什么。

### 3. 效果证据正在从「口碑」转向「对照实验」

- SkillsBench（arXiv 2602.12670，NeurIPS 2026）用 87 个任务、8 个领域、18 个模型/框架组合做配对测试：带 curated skill 的平均通过率从 33.9% 升到 50.5%（+16.6pp），但**任务间差异极大**（约 +4 到 +26pp），且「2–3 个模块」比大而全的文档更有效。
- 腾讯 TRACE 的 E 维度同样要求「有/无 skill 的结果对照」，并把 token / 时间成本纳入判断。
- Anthropic 的 `skill-creator` 提供 blinded comparator 与 benchmark 模式；社区还有 ACES / SkillEvaluator 这类按 skill 做 with/without 评测的工具。
- **对 SkillMaster 的含义**：效果可以做成**可复核的评测卡**（任务集、通过率增益、成本），而不是「五星好评」。这比调用量难，但它是「放心使用」最硬的一块。

### 4. 策展正在回归，因为纯算法会被量淹没

- 腾讯 SkillHub 每月只出人工 TOP10；Chrome 的 Featured 是人工审；Anthropic 官方目录是申请 + 人工审；Docker Official Images 是平台策展。
- npm 反而在 2026 年去掉了综合质量分，只保留客观排序——说明「算法综合分」在成熟生态里很难维护，容易变成黑箱。
- **对 SkillMaster 的含义**：官方策展（精选、分类货架、编辑推荐语）不是复古，而是量上来之后的必需品。它也可以解释「为什么推荐这个」。

### 5. 可解释性比分数本身更重要

- Chrome 明确说评分与数量参与排序，但不公开权重；npm 改成客观排序并说明「不做主观排名」；Docker 直接给 Verified 搜索加权。
- 用户真正需要回答的是「**为什么这张卡在我前面**」。给出理由（「官方精选」「近 30 天 120 个不同账号用过」「评测 +18pp」）比给一个 4.7 分更有用，也更难被刷。
- **对 SkillMaster 的含义**：排序要能逐卡解释；不能解释的信号不要放进排序。

## 三、SkillMaster 的约束

1. **公开 = 平台背书**（[ADR 0146](../decisions/0146-public-is-platform-endorsement.md)）。只有官方命名空间能公开，所以「官方审核」不是可选徽章，而是产品定义的一部分。
2. **今天没有用量数据**：库里没有 invocation / download 计数；审计事件不是用量流水。
3. **公域只搜不列**（[ADR 0082](../decisions/0082-public-and-private-with-my-library.md)），「为你推荐」只在控制台（[ADR 0080](../decisions/0080-cli-covers-non-high-risk-operations.md)）。排序改动不能把公域变成可整表拉取的目录。
4. **隐私优先**：用户对「公网暴露」敏感；任何用量信号都必须聚合、去重、不可反推个人。
5. **agent 也要读得懂**：卡片之外，`skmr skills search` 的输出与 `skill show` 要有同样的信号，否则 agent 仍然只能看描述。

## 四、建议的整体方案：三层证据 + 一个解释

### 第一层：平台证据（Trust）

回答「这是谁给的、平台担保到什么程度」。

| 信号 | 来源 | 展示 |
|---|---|---|
| 官方背书 | `namespace.official`（ADR 0146） | 「官方」标记；不叫「认证」 |
| 审核记录 | 发布时的审核事件（新） | 「审核于 2026-11-05」；不展示审核人 |
| 来源与许可 | SKILL.md 元数据 + 开源同步记录 | 上游仓库、许可证、是否派生 |
| 版本不可变 | 现有版本模型（ADR 0064） | 线上版本号 + 发布时间；不展示可变的「最新」 |
| 安全扫描 | 发布流水线（新） | 「扫描通过 · 2026-11-05」；不展示扫描器细节 |

### 第二层：能力证据（What）

回答「它做什么、什么时候用、边界在哪」。

- 结构化摘要：`description` + `when_to_use`（已有）+ **输入 / 输出 / 不适用场景**（新，SKILL.md 可选字段）。
- 内容预览：卡片给一段正文摘要；详情页给文件树与 `SKILL.md` 前若干行（不加载全文）。
- 兼容性：支持哪些 agent / 运行环境（从元数据读，不手填）。
- 示例：一个最小调用示例（可选）。

### 第三层：效果证据（Does it work）

回答「用了之后真的更好吗」。

| 信号 | 口径 | 防刷 |
|---|---|---|
| 评测卡 | 固定任务集上「有/无 skill」的通过率、token/时间成本；标注任务集版本与模型 | 平台跑，不由作者自报 |
| 使用广度 | 近 30 天**不同账号**的调用次数（对数分桶）、加入数 | 按账号去重、每账号每天封顶、作者自用不计 |
| 上游社区 | GitHub stars / forks / 最近提交，**仅当 skill 绑定了开源仓库**（开源同步那版的来源） | 星数只做来源证据与弱加权；仓库要绑定到具体 commit |
| 结构化反馈 | 「这次用成了 / 没成」二选一 + 可选原因；不做五星 | 一账号一 skill 一次；可撤回 |
| 失败信号 | 调用错误率、超时率（若网关可观测） | 只展示聚合，不展示个人 |

### 第四层：策展（Why now）

- 官方精选集：按场景（写作、数据、研发、办公）的人工货架。
- 编辑推荐语：一句话说「适合谁、不适合谁」。
- 「新上架」与「最近更新」独立通道，避免冷启动被老 skill 压死。

### 排序：可解释的加权，而不是黑箱总分

推荐流（控制台「为你推荐」）用：

```text
score = 质量（评测增益，归一化）
      + 使用广度（log 去重账号数，做贝叶斯平滑）
      + 上游社区（log stars，仅绑定仓库的 skill；弱权重）
      + 新鲜度（时间衰减）
      + 策展加权（官方精选）
```

- **每一张卡显示它为什么在这里**：至少一个理由标签；没有理由的卡不出现。
- **搜索不混用**：`skills search` 仍以相关度为主，质量只做小幅 tie-break；否则「搜不到想要的」会被排序掩盖。
- **冷启动**：新 skill 进「新上架」通道；评测分未出前不参与主榜。
- **反刷**：账号去重、每日封顶、作者自用排除、异常调用检测；不展示精确调用数，只展示分桶或「近 30 天有人用」。

## 五、这一版可以做到哪一步

| 优先级 | 交付 | 依赖 | 风险 |
|---|---|---|---|
| **P0** | 平台证据 + 能力证据上卡片/详情/CLI；官方策展字段与精选集；排序仍按更新时间 | 现有数据 + 少量元数据字段 | 低 |
| **P1** | 使用广度：调用去重计数、加入数；推荐流多信号 + 逐卡理由；搜索 tie-break | 新增 invocation 计数表；隐私口径 | 中 |
| **P2** | 评测卡：平台跑固定任务集的 with/without；失败/成功反馈 | 评测 runner、任务集、模型成本 | 高，建议单独一版 |

**建议这一版先做 P0 + P1**，把「可信、看得懂、有使用证据、排序可解释」落地；评测卡留到再后一版单独评估，因为它需要任务集与持续跑模型，成本与口径都不是一版能收住的。

## 六、要用户拍板的三件事

1. **用量展示口径**：精确调用数 / 分桶与「近 30 天有人用」/ 完全不展示。
2. **效果证据的投入**：这一版是否做平台评测卡，还是先用「使用广度 + 结构化反馈」。
3. **反馈形态**：二选一「用成了/没成」+ 原因，还是五星评分；前者更难刷，后者更熟悉。

## 来源

- ClawHub 安全扫描：[Trent AI, 2026](https://trent.ai/blog/openclaw-safe-to-install-skills/)
- ClawHub 生态与规模：[competitors.md](competitors.md)（内部调研，2026-07 时点）
- 腾讯 SkillHub / TRACE：[极客公园，2026](https://www.geekpark.net/news/364566)、[competitors.md](competitors.md)
- skillhub.club 评分：[skillhub.club](https://www.skillhub.club/skills?rating=A&page=1&sort=recommended&per_page=24&view=list)
- Anthropic 官方市场：[Signal, 2026](https://www.readsignal.io/article/anthropic-claude-skills-marketplace-aeo-impact-2026)
- Smithery / MCP 注册表：[Callsphere 对比, 2026](https://callsphere.ai/blog/vw4g-mcp-registry-catalogs-smithery-mcp-so-comparison-2026)、[MCP 官方认证文档](https://modelcontextprotocol.io/registry/authentication)
- MCP 命名空间冒名分析：[Safeguard, 2026](https://safeguard.sh/resources/blog/mcp-registry-namespace-impersonation-defense)
- GPT Store：[OpenAI 发布](https://openai.com/zh-Hans-CN/index/introducing-the-gpt-store/)、[GPT Store Mining and Analysis, ACM 2025](https://dl.acm.org/doi/pdf/10.1145/3755881.3755900)
- VS Code：[Extension runtime security](https://code.visualstudio.com/docs/configure/extensions/extension-runtime-security)
- Chrome Web Store：[Curation and reviews](https://support.google.com/chrome_webstore/answer/12225786)
- Docker Hub：[Verified Publisher Program](https://docs.docker.com/docker-hub/repos/manage/trusted-content/dvp-program/)
- npm 搜索改版：[Socket, 2026](https://socket.dev/blog/npm-updates-search-experience)
- Hugging Face 流行度与效果：[Model Hubs and Beyond, ICWSM 2025](https://ojs.aaai.org/index.php/ICWSM/article/view/35855)
- SkillsBench：[arXiv 2602.12670](https://arxiv.org/abs/2602.12670)、[NeurIPS 2026 poster](https://neurips.cc/virtual/2026/loc/atlanta/poster/139353)

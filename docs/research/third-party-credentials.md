# 用户调用第三方服务的凭据：自建还是第三方

> **调研日期**：2026-10-31
> **用途**：为「平台要不要提供用户第三方 API key / 密码的托管能力、自建还是买」提供事实底稿。
> **本文不下决定**——跨版本的取舍属于 [`docs/decisions/`](../decisions/)；做到哪一步属于 `docs/versions/`。文末 §七 只登记未核实项与候选路线。
> **口径**：本仓事实以 ADR 与源码为准（链接给出）；外部事实带出处；没核实的一律写「未核实」，不当结论用。
> **结论先行**：**产品面必须自建，密码学底座可以买；不要把整件事外包给第三方密码库或 SaaS。** 第一阶段甚至可以先不托管——把凭据留在用户本机的系统钥匙串里，平台只提供注入命令。

## 一、先把问题拆成三层，否则「自建还是第三方」没有答案

| 层 | 问的是什么 | 第三方能不能覆盖 | 结论 |
|---|---|---|---|
| **数据面** | 密文存在哪、根密钥谁管、怎么轮换 | 能。云 KMS、Secrets Manager、Vault 都是现成的 | **买**。不要自己发明密码学 |
| **控制面** | 每个用户能存哪些凭据、哪个 skill 能用、谁同意过、怎么审计、控制台长什么样 | **不能**。没有厂商知道 SkillMaster 的 skill / 权限梯子 / 会话模型 | **自建**。这是产品本身 |
| **执行面** | 密钥怎么到达 skill 手里，而不进入模型上下文与 shell 历史 | 部分能。1Password `op run`、Doppler `doppler run`、Vault Agent 都是这个形状 | **自建一个薄的注入层**；可借鉴它们的形状 |

把三层混在一起谈，就会得到「买一个 1Password 就行」或「全都自己写」两种都不成立的答案。

## 二、本仓现状（事实，带链接）

### 2.1 已有的 API key 能力只管「访问 SkillMaster」，不管「访问第三方」

[`v2-credentials`](../versions/v2-credentials/README.md) 做的是**第一方的默认凭据**：用户在控制台签发 `skm_…`，CLI 用它调 `/api/v1/*`。存储与鉴权在 [`M02`](../architecture/modules/M02-token-and-as.md)（`api_key` 表，只存哈希），决定在 [ADR 0038](../decisions/0038-default-credential-is-an-api-key.md)。**这把 key 的方向是「用户 → 我们」**。

用户调用火山引擎、飞书、模型厂商的 key，方向是「我们（或用户的 agent）→ 第三方」，**今天没有任何一张表、任何一个接口、任何一处控制台界面承接它**。`ModuleMap` 里也没有凭据模块。

### 2.2 目标态明确写着「不存储第三方密码」

[`target/design.md`](../target/design.md) §2.1 的边界原文：

> - 不存储第三方密码，不代管组织外的身份。

这句话当时的语境是**身份**（飞书只作 SSO，不做通讯录同步），不是「用户调用第三方 API 的 key」。但字面上它把「代管第三方凭据」整类挡在门外。**要做这件事，先得把边界改清楚**：是只放开「调用第三方服务的 API key」，还是连密码也放开；是只放开个人，还是也放开组织。这不是措辞问题——它决定数据模型、同意流程与责任边界。按仓库约定，这要一条新 ADR，并同步更新 `target/README.md` 的差异清单。

### 2.3 skill 的脚本不在我们这里执行，凭据的使用发生在用户机器上

[`target/design.md`](../target/design.md) §2.5 的边界：

> - M3 前不代理执行 skill 的脚本（不沙箱、不托管运行时）。

[`v1-hosting/technical-design.md`](../versions/v1-hosting/technical-design.md) §1.2 的边界同样写死：服务端是 skill 的唯一真相，本地只有 CLI；`invoke` 把正文打到 stdout，**脚本由宿主的 agent 在用户机器上跑**。

这带来一个硬约束：**平台即使存了第三方 key，也不能「替用户用」**——它只能把 key 交给用户机器上的某个进程。交付方式因此成为设计的一部分（见 §三 A/B 与 §六 P1/P2），不能回避。

### 2.4 今天的三种替代做法，每一种都在漏

| 做法 | 实际发生什么 | 问题 |
|---|---|---|
| 用户把 key 粘贴进聊天框 | 很多 skill 的说明就是这么写的。本仓 `lijinzhao/byted-web-search` 的 `SKILL.md` §3 原文：「API Key 直接在本聊天框发给我即可」 | key 进入**模型上下文**、对话记录、可能的日志；agent 再把它写进 `.env` 或 shell，又进 shell 历史 |
| key 写进 skill 包 | **实测（2026-10-31）**：`skmr skill download lijinzhao/byted-web-search` 落下的 `.env` 里，`WEB_SEARCH_API_KEY` 是一个 32 位字母数字串（本文不回显）。而 `ingest` / `blob` 两个模块里**没有任何**密钥扫描 | skill 一旦共享或公开，凭据随包下发；作者轮换后旧包仍是旧 key；没有「吊销」这个概念 |
| 用户自己设环境变量 | 技术上可行，今天也能用 | 不懂技术的用户做不到；多设备要各设一遍；没有审计；skill 说明得写一长串「去某某控制台复制 key」 |

**这三条里最急的是第二条**：它不需要平台做任何新功能就能造成泄露，而且已经发生了。第一条也不小——它把「凭据不进模型上下文」这条从 [ADR 0002](../decisions/0002-gateway-skill-and-cli.md) 起就立住的边界绕开了。

### 2.5 CLI 已经有一套凭据存储，可以复用

[ADR 0025](../decisions/0025-cli-credential-storage.md)：keychain 优先，Linux 无 Secret Service 时回落明文文件（0600）并**明确告知位置**。实现在 `skillmaster-cli/internal/credentials/`。这套东西今天只存「本机对 SkillMaster 的凭据」，但它的形状（按 server 分、keychain 优先、失败要说话）**正好是命名凭据需要的东西**。

## 三、候选方案：七个，不是两个

先给一张总表，再逐个说取舍。「适配度」按**这个产品**（多租户、面向终端用户、国内部署、skill 在用户机器上执行）评，不按通用能力评。

| # | 方案 | 数据面 | 控制面 | 执行面 | 中国区/合规 | 成本口径 | 适配度 |
|---|---|---|---|---|---|---|---|
| A | 本地钥匙串 + `skmr secret run` | 用户本机 OS keychain | 自建（CLI 命令） | CLI 注入子进程 | 无出境 | 0 | **高（第一阶段）** |
| B | 平台保险箱 + 云 KMS 信封加密 | 自建（密文进 Postgres，根密钥在 KMS） | 自建（控制台 + CLI） | CLI 取回并注入 | 境内 | 约 1,000 元/月量级起 | **高（目标形态）** |
| C | 阿里云凭据管家 Secrets Manager | 买（托管） | 仍要自建映射与界面 | 调 API 取回 | 境内 | 0.1 元/天/凭据 ≈ 3 元/月/凭据 | 中（只适合少量运营密钥） |
| D | 自建 Vault / Infisical | 买/自托管开源 | 仍要自建 | Vault Agent / CLI | 境内（自托管） | 3 台 ECS + 运维人力（二手估算） | 低（运维面大于收益） |
| E | SaaS 密码库（1Password / Doppler / Infisical Cloud） | 买（境外） | 仍要自建 | `op run` / `doppler run` | **数据出境** | 按人计价，$9–21/人/月 | **低** |
| F | Agent 授权平台（Composio / Nango / Arcade / Auth0 Token Vault） | 买（境外） | 部分（它们有自己的连接 UI） | 它们的 SDK / MCP | **数据出境**；国内服务覆盖弱 | 按连接/按人，未逐家核 | 低（OAuth 场景可另议） |
| G | OAuth 连接账号（不是存储方案） | 自建/买均可 | 自建 | 平台换短命 token | 取决于实现 | 低 | 中（能 OAuth 的服务优先用） |

### A. 本地钥匙串 + `skmr secret run`（自建，平台不托管）

**形状**：`skmr secret set WEB_SEARCH_API_KEY`（从 stdin 读，不回显）→ 进 OS keychain；`skmr secret run --env WEB_SEARCH_API_KEY -- python3 scripts/web_search.py ...` → CLI 把值放进子进程环境，**值不打 stdout、不进模型上下文**；`skmr secret list` 只列名字与最后使用时间；`skmr secret rm` 删除。

**买到什么**：立刻消灭「粘进聊天框」与「写进 skill 包」两条漏；平台不成为托管方，没有拖库即全泄的风险；成本 0。
**买不到什么**：不跨设备、控制台看不见、不能团队共享、没有服务端审计；每台机器各自设一次。
**为什么先做它**：它和 [ADR 0025](../decisions/0025-cli-credential-storage.md) 是同一套存储，增量最小；而且它把「密钥不进模型上下文」这条边界**从一句规则变成一条命令**——agent 可以照着 skill 说明敲 `skmr secret run`，但它看不到值。

### B. 平台保险箱 + 云 KMS 信封加密（自建控制面，买密码学底座）

**形状**：控制台新增「凭据」页；每个凭据一行密文进 Postgres；根密钥在云 KMS；CLI `secret run` 先取回、再注入子进程。
**关键设计**（不是实现细节，是安全边界）：
- **信封加密**：每个凭据一个 DEK（AES-256-GCM），DEK 由 KMS `GenerateDataKey` 产生；库里存「密文 + 加密后的 DEK + 密钥版本」；KMS 只解密 DEK，**明文密钥永远不落库、不进日志**。
- **只在注入时解密**：`secret run` 一次解密一次，不缓存到磁盘；内存中的明文用完即弃。
- **不回显**：控制台只允许写入 / 轮换 / 删除，**没有「查看明文」**；API 也不返回明文。
- **按 skill 绑定**：一个凭据可以限定只给某几个 skill；skill 首次要用时需要用户当场同意（与公开 skill 加载前确认同一套交互）。
- **审计**：创建 / 使用 / 轮换 / 删除四类事件进 M10；`last_used_at` 节流写。
- **删除即加密粉碎**：删掉 DEK 的密文，历史备份里的密文也解不开。
**代价**：我们成了托管方。拖库不再只是「哈希泄露」，而是「所有用户的第三方 key」；需要 KMS 权限隔离、审计告警、应急响应、删除承诺。**这是 B 比 A 贵的地方，不是加密代码。**

### C. 阿里云凭据管家 Secrets Manager（买托管存储）

**能力**（[官方文档](https://help.aliyun.com/zh/kms/support/secrets-manager/)）：凭据全生命周期、加密存储、定期轮换、RAM 细粒度权限、操作审计；默认加密密钥由凭据管家生成且免费，也支持自选 KMS 主密钥。
**价格**（[官方按量付费页](https://help.aliyun.com/zh/kms/key-management-service/pay-as-you-go)，2026-04-08）：凭据 **0.1 元/天/个**，即 **约 3 元/月/个**；实例 30 元/天（软件）；密钥 0.3 元/天/个；QPS 3 元/天/QPS。
**适配性**：如果只有几十个**平台自己的运营密钥**（短信、模型、对象存储），它很合适。但按**每用户每条凭据**算，量级立刻失控（见 §四）。它是「买存储」，控制面仍然要自建——所以它省下的只是 B 里那层加密代码，换来的是一条按凭据线性增长的成本曲线。

### D. 自建 Vault / Infisical

**能力**：动态密钥、租约、Transit 加密、多租户命名空间，功能最全。
**代价**：HA、unseal、升级、备份、监控都要自己来。对本项目这种「一个 Java 服务 + 一个 Postgres」的形态，**运维面大于收益**。除非将来有多云/混合云或强合规要求（例如必须自持 HSM、必须动态数据库凭据），否则不选。

### E. SaaS 密码库（1Password / Doppler / Infisical Cloud）

**能力**：团队共享、审计、CLI 注入（`op run` / `doppler run`），产品成熟。
**为什么不适配**：
1. **计价单位是「人」或「席位」**。1Password Business 官方价 **$8.99/用户/月**（按年）；Doppler 二手资料 **$18–21/用户/月**。给每个终端用户开一个席位，在消费级量级不成立。
2. **它们是「团队的应用密钥」，不是「终端用户的第三方凭据」**。控制台、权限、同意流程仍然是我们的。
3. **境外 SaaS**：用户凭据出境，触发 PIPL 的跨境规则（见 §五）。
4. 1Password Connect 是「云保险库的本地缓存」，不是自托管保险库；断网/账号停用就不可用。

### F. Agent 授权平台（Composio / Nango / Arcade / Auth0 Token Vault）

**它们强在哪**：OAuth 连接、token 刷新、连接 UI、把「用户 → 第三方账号」的授权做成 SDK/MCP。对「agent 直接操作 Gmail / GitHub / Slack」这类场景很成熟。
**为什么不作为底座**：
1. **核心是 OAuth，不是任意 AK/SK**。国内大量服务（火山引擎、飞书自建应用、很多模型厂商）给的是 API key / AK-SK，不是用户级 OAuth。二手个案：有用户报告 Composio 对 GitHub 拒绝 PAT、要求 OAuth（低置信，需复核）。
2. **国内服务覆盖弱**：它们的内置 toolkit 以海外 SaaS 为主。
3. **凭据托管在境外**：与 E 同一条合规问题。
4. **它们会接管「连接」这层 UX**，而连接体验正是我们产品的一部分。
**结论**：不作为基础方案；将来若要做「一键连接 Gmail / GitHub」且用户同意数据出境，可以单点评估。

### G. OAuth 连接账号（替代原始 key 的授权方式）

不是存储方案，但值得单独列：**能 OAuth 的服务优先用 OAuth**，平台存 refresh token、换短命 access token，用户可随时在第三方侧撤销。它比长期 AK/SK 的泄露面小得多。对飞书这类有开放平台的服务，这条比「让用户贴 app secret」好。对火山引擎这类只发 AK/SK 的，仍然需要 A/B 的存储。

## 四、成本口径：为什么「按凭据买」在这个产品里不成立

阿里云按量付费的三个单价（官方页，2026-04-08）：

| 计费项 | 单价 | 说明 |
|---|---|---|
| 软件密钥管理实例 | 30 元/天 | 约 900 元/月；包年包月另有 2,499 元/月档 |
| 密钥 | 0.3 元/天/个 | 多版本按版本数计 |
| 凭据（Secrets Manager） | 0.1 元/天/个 | **约 3 元/月/个**，与凭据版本数无关 |
| QPS 调用 | 3 元/天/QPS | 按当日分钟峰值向上取整 |

**按凭据算**（Secrets Manager）：

| 凭据总数 | 月成本（约） |
|---|---|
| 100 | 300 元 |
| 1,000 | 3,000 元 |
| 10,000 | 30,000 元 |
| 100,000 | 300,000 元 |

**按信封加密算**（自建 B）：1 个实例 + 1 个 CMK + 1 QPS ≈ 30 + 0.3 + 3 ≈ **33 元/天 ≈ 1,000 元/月**，**与凭据条数基本无关**（只随 QPS 增长）。10 万条凭据的边际存储成本是 Postgres 里的几 MB 密文。

**按人算**（SaaS 密码库）：1Password Business $8.99/用户/月 → 1,000 用户约 **$9,000/月**；Doppler $18–21/用户/月 → 1,000 用户约 **$18,000–21,000/月**。这不是「贵一点」，是计价模型不匹配。

**一句话**：**平台自己的几十个运营密钥，用 Secrets Manager 省事；终端用户每人若干条第三方凭据，只有「一个 CMK + 信封加密」的成本模型成立。**

## 五、合规与责任：托管这件事真正的代价

1. **数据出境**。如果数据面选境外 SaaS（E/F），用户凭据被境外机构存储或访问，按 PIPL 属于「向境外提供个人信息」。二手资料给出的实务口径：年出境不满 10 万人（不含敏感个人信息）通常走标准合同备案；达到 10 万人或敏感个人信息不满 1 万人，要申报安全评估（[律所文章](https://www.zhiminglawyer.com/zhiming-dongtai/hangye-zhuandi/9948.html)，二手；[Airbyte](https://airbyte.com/data-engineering-resources/china-pipl-compliance-regional-data-planes)，二手）。**这是法务判断，不是工程判断**——但选境内云 KMS 可以把这条整类问题消掉。
2. **托管责任**。平台一旦存了用户的第三方 key，拖库的后果从「密码哈希泄露」升级为「用户的火山引擎/飞书/模型账号被接管」。需要：最小权限、KMS 权限隔离、操作审计、异常使用告警、事件响应预案、删除与导出承诺。
3. **数据最小化**。能 OAuth 就不存长期 key；能本地就不上云；能只存「引用」就不存明文。A 比 B 更小，B 比 C 更可控（C 把密文交给云厂商的凭据服务，但仍在境内）。
4. **边界变更**。§2.2 的「不存储第三方密码」必须被重新表述：建议把范围收窄为「**不代管身份密码；只托管用户明确授权、用于调用第三方服务的 API 凭据**」，并写进 ADR 与 `target/README.md`。不收窄就做，等于静默越界。

## 六、候选路线（不是决定，供开 ADR 时引用）

### P0 · 止血（不需要新功能）

1. **轮换** `lijinzhao/byted-web-search` 里那把 32 位 key——它已经随 skill 包下发过，视为已暴露。
2. **上传扫描**：`ingest` 对 `.env`、`*.pem`、常见 key 前缀（`sk-`、`AKIA`、`skm_` 等）做拒绝或强提示；至少让「skill 包里带凭据」这件事**响亮地失败**，而不是静默通过。
3. **改 skill 说明**：把「API Key 直接发在聊天框」改成「用 `skmr secret set` / 环境变量，别发在对话里」。这一条今天就能做。

### P1 · 本地保险箱（推荐先做）

- `skmr secret set/list/rm` + `skmr secret run --env NAME -- <cmd>`；存储复用 [ADR 0025](../decisions/0025-cli-credential-storage.md) 的 keychain 逻辑。
- 平台不存任何东西；没有新表、没有新接口、没有合规面。
- 验收一句话：**agent 能跑 `skmr secret run --env WEB_SEARCH_API_KEY -- python3 …` 并拿到结果，而它的上下文里从头到尾没有那把 key。**

### P2 · 平台保险箱（只在需要「控制台可见 / 跨设备 / 团队共享」时做）

- 信封加密 + 阿里云 KMS；密文进 Postgres；控制台只写不读；按 skill 绑定 + 同意；审计；`secret run` 取回并注入。
- **不要**把 Secrets Manager 当每用户存储（成本曲线见 §四）。
- 先写 ADR：托管边界、责任、删除承诺、密钥轮换策略。

### P3 · OAuth 优先

- 对飞书、GitHub、Google 这类支持 OAuth 的服务，接「连接账号」而不是让用户贴长期 key；火山引擎这类只有 AK/SK 的，走 A/B 的存储。
- 这一条可以独立于 P1/P2 推进，不阻塞。

## 七、未核实项（用前复核）

| # | 事项 | 现状 | 怎么核 |
|---|---|---|---|
| 1 | 阿里云 KMS 实例的默认 QPS、突发上限与实际解密延迟 | **未核实** | 买一个按量实例，压一次 `GenerateDataKey` / `Decrypt` |
| 2 | 按量付费是否另收 API 调用费（官方页只列了 QPS 费） | **未核实**；本文按「QPS 费包含调用」理解 | 看账单或问阿里云 |
| 3 | Composio / Nango 对火山引擎、飞书的覆盖；自定义 API key 的限制 | **未核实**；有一个低置信的二手个案说 Composio 拒绝 GitHub PAT | 用免费账号各接一个国内服务实测 |
| 4 | 第三方 API key 是否构成 PIPL 下的「个人信息」；境外 SaaS 的备案路径 | **未核实**，需法务 | 找律师按实际数据流出一份意见 |
| 5 | 共享 / 公开 skill 的 `download` 是否包含 `.env` | **未核实**；本文只实测了自己的 skill | 用另一个账号共享一个带 `.env` 的测试 skill，再 download |
| 6 | `target/design.md` 的「不存储第三方密码」是硬边界还是只针对身份 | **待产品负责人确认** | 开 ADR 时明确 |

## 八、来源、档位与置信度

| 事实 | 出处 | 档位 |
|---|---|---|
| v2 只管平台自己的 API key；`api_key` 表只存哈希；M3 验凭据 | [`v2-credentials/README.md`](../versions/v2-credentials/README.md)、[`M02`](../architecture/modules/M02-token-and-as.md)、[ADR 0038](../decisions/0038-default-credential-is-an-api-key.md) | **本仓规范** |
| 目标态「不存储第三方密码」 | [`target/design.md`](../target/design.md) §2.1 | **本仓规范** |
| 平台不代理执行 skill 脚本；脚本在用户机器上跑 | [`target/design.md`](../target/design.md) §2.5、[`v1-hosting/technical-design.md`](../versions/v1-hosting/technical-design.md) §1.2 | **本仓规范** |
| CLI 凭据存储：keychain 优先、明文兜底并告知 | [ADR 0025](../decisions/0025-cli-credential-storage.md) | **本仓规范** |
| `byted-web-search@1.0.0` 的 `.env` 含 32 位字母数字 key；ingest/blob 无密钥扫描 | 本机 2026-10-31 实测（`skmr skill download` + 源码 grep） | **实测** |
| skill 说明要求把 API key 发在聊天框 | `lijinzhao/byted-web-search` 的 `SKILL.md` §3 | **本仓 skill 内容** |
| 阿里云 KMS / 凭据管家的能力（加密、轮换、RAM、审计、默认密钥免费） | [凭据管家概述](https://help.aliyun.com/zh/kms/support/secrets-manager/) | **[官方文档]** |
| 按量付费单价：实例 30 元/天、密钥 0.3 元/天/个、凭据 0.1 元/天/个、QPS 3 元/天/QPS | [KMS 按量付费](https://help.aliyun.com/zh/kms/key-management-service/pay-as-you-go)（2026-04-08） | **[官方文档]** |
| 包年包月软件实例 2,499 元/月（中国内地） | [KMS 产品计费](https://help.aliyun.com/zh/kms/key-management-service/product-overview/kms-billing)（2026-07-30） | **[官方文档]** |
| 1Password Business $8.99/用户/月（按年） | [1Password 定价](https://1password.com/zh-tw/pricing/business) | **[官方文档]** |
| Doppler $18–21/用户/月 | aicoolies 定价页（2026-09-06 核对） | **[二手]** |
| Vault 自建约 3 台 ECS + 0.5 人/月 | CSDN 文章 | **[二手，低置信]**；只用于量级判断 |
| PIPL 跨境：标准合同 / 安全评估的分档 | 律所文章、Airbyte | **[二手]**；需法务复核 |
| Agent 授权平台以 OAuth 为主，API key 支持因 integration 而异 | [Composio 文档](https://docs.composio.dev/docs/authentication/custom-app-vs-managed-app)、Nango HN 帖 | **[官方文档]**（能力）/ **[二手]**（限制个案） |
| 本地凭据优先的模式在业界存在（`op run` / `doppler run` / Vault Agent） | 1Password / Doppler / Vault 文档 | **[官方文档]** |

## 九、一句话回答

**自建控制面，买密码学底座。** 第一阶段先做「本地钥匙串 + `skmr secret run`」，平台不托管；等到控制台可见、跨设备、团队共享成为真实需求，再加「一个阿里云 KMS CMK + 信封加密 + Postgres 密文」的平台保险箱。**不要**用 1Password / Doppler / Infisical / Vault 托管终端用户的第三方 key——它们的计价单位、数据驻留和控制面都不匹配；**也不要**把这件事交给 Composio / Nango 这类 agent auth 平台，它们强在 OAuth，不覆盖国内服务的 AK/SK。

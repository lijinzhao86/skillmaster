# ADR 0010 · 存储全部落在 PostgreSQL

- **状态**：已采纳（**部分被 [ADR 0130](0130-large-blobs-live-in-object-storage.md) 取代**：决定 2 的「不引入对象存储」与决定 4 的「字节回收与版本变更在同一事务里完成」；`BlobStore` 接缝与其余结论仍有效）
- **日期**：2026-09-27
- **依赖**：[ADR 0001](0001-server-authoritative.md)、[ADR 0005](0005-content-addressing.md)

## 背景

v1 要托管 skill 的不可变版本并按需逐文件下发。服务端权威（[ADR 0001](0001-server-authoritative.md)）意味着**几乎每一次 agent 读取都是一次到服务端的 HTTP 往返**，读是主要流量，写很少（发布是低频人工动作）。每个 skill 是一棵目录树，上传上限 **512 文件 / 16 MiB**（[`technical-design.md`](../versions/v1-hosting/technical-design.md) §1.3）。

体积口径见该文档 §1.6：单文件中位 4,362 B、最大 746,862 B；单个 skill 的非 `SKILL.md` 文件合计中位 29,736 B、最大 1,570,809 B。**两笔账要分开**：

| | 1000 个 skill | 190 万个 skill（见下注） |
|---|---|---|
| **检索索引语料**（只有 L1：`name` / `title` / `description`） | **0.42 MB**（实测） | 约 **0.8 GB** |
| **全部内容**（L1 + L2 正文 + L3 文件，按 §1.6 的中位口径） | 约 **34 MB** | 约 **64 GB** |

> **口径**。L1 那行的「实测」是本机 2026-09-27 用 **1000 行合成语料**跑的（SQLite 3.49.2），它确认的主要是**索引开销**的量级，不是真实 skill 的体积——真实语料的口径见 §1.6。全部内容那行是把 §1.6 的中位值相加，而**中位数不可加**，这是量级估算；若改用「只算有 L3 的 21 个 skill」那一档的中位值 148,017 B 摊平，190 万个会到**数百 GB**。**190 万这个规模**出自 [`research/competitors.md`](../research/competitors.md) 的 SkillsMP 条目，而那份表**自述是「未标日期的快照」**，引用前需重新核对。

**所以 v1（1000 个 skill 量级）在容量上毫无压力，但「搬到对象存储」那条触发线（0.5–1 TB）在百万级 skill 时就会逼近**——它是真实的、可到达的，不是够不着的。真正决定引擎的是部署形态与检索正确性。

候选方案：SQLite（基线代码与本文档原先的计划）、PostgreSQL（托管或自建）、对象存储（阿里云 OSS）、共享文件系统（NAS / CPFS）、自建的 MinIO、内容寻址的专用服务（OCI registry / ORAS），以及**把字节当文件的分布式文件系统**。

两个前提由外部给定，本 ADR 不记录其取舍理由：

- **部署平台是阿里云**（2026-09-27 由产品负责人确认）。
- **不做远程就地编辑。** 内容寻址下已发布版本不可变，且客户端的缓存与信任决定都挂在 digest 上（[ADR 0005](0005-content-addressing.md)）；迭代只能通过发布新版本。因此本 ADR 的写路径是**发布式**的。

## 决定

1. **PostgreSQL 是唯一的状态存储**——元数据、权限、审计、以及**文件字节**全在里面。
2. **字节以 `bytea` 存在单独一张表里，并放在单独的表空间**，藏在 `BlobStore.get/put(sha256)` 接口之后。**不引入对象存储、共享文件系统或分布式文件系统。**
3. **生产形态是阿里云 RDS PostgreSQL 高可用系列 + 多可用区；验证阶段用基础版**（1核1G / 20GB ESSD / PG 17）。
4. **字节回收与版本变更必须在同一个事务里完成**（按引用计数），不做跨系统对账。

## 理由

**否掉 SQLite。** 它在数据量上够，但它把服务钉死在**单副本**上：可单节点挂载的卷不能同时挂两个节点，多副本会各写各的库而**静默分叉**——而「应用可以多副本、没有单点」是已确认的要求。更决定性的原因是**它验证不了本项目的核心**。实测（SQLite 3.49.2）：

- FTS5 + `trigram` 的 `MATCH` 对**少于 3 个字符**的查询返回 0，且无解。官方文档原话：*"Substrings consisting of fewer than 3 unicode characters do not match any rows when used with a full-text query."* 而**两个汉字是中文最常见的一次查询**。
- 默认 `unicode61` 更糟：Han 是 `Lo` 类，连续汉字并成**一个 token**，`MATCH '飞书'` 只命中 7,795 条里的 2,077 条——**位置相关的部分召回**，不可预测。
- 唯一正确的路径是 `LIKE`（3 字符的分水岭只约束 `MATCH`），而 `LIKE` 是布尔匹配、**没有 bm25**。
- 中文分词扩展 `simple` 不在 stock build 里（实测 `no such tokenizer: simple`）。

[ADR 0006](0006-search-server-side.md) 说「排序质量在这个模型下就是产品的核心」——而这条路在 SQLite 上做不了。PostgreSQL 侧的 `pg_bigm` 提供 **2-gram 索引**，正是 2 字中文子串需要的那个形状。

**否掉对象存储（OSS）。** 不是因为容量，而是因为**我们自己的鉴权恰好抵消了它的核心优势**。OSS 的赢面是预签名 URL / CDN 让客户端直连、把出口流量从应用分流走；而私有内容必须逐请求鉴权、要审计，发不了裸链接。业界有现成的编码：Gitea/Forgejo 把这个做成一个开关 `SERVE_DIRECT`，**默认 `false`，而为 `false` 时对象存储就是一块更慢的本地盘**。内容寻址还让**字节层的 ACL 在结构上不成立**——一个 blob 会被不同受众的 skill 共用，它的可达性取决于谁引用了它，所以字节只能经 API 到达。代价一侧还有多一跳（阿里云自家文档说 OSS「不适合低延迟随机读写」）与随流量线性增长的请求费。它换来的好处，从 v1 的体量（全部内容约 34 MB）到百万级 skill（约 64 GB）这一整段，一条都用不上。

**否掉共享文件系统（NAS / CPFS）。** Harbor 的文档明确**不推荐** NFS 作为 registry 后端：元数据密集的操作会出现 `digest invalid` / `blob upload unknown`，而我们的读路径（每次 GET 配一次库查询）**正是那个形状**。NFS 的锁是 advisory 的，且它是第二套没有事务耦合的状态系统。

**否掉自建 MinIO。** 2025 年它删掉了社区管理台（约 11 万行）并转入维护模式。自建一个被抽走的对象存储是负债，不是省钱。

**否掉 OCI registry / ORAS。** 它的单元通常是 tar+gzip 层，还要接管它的 token 鉴权。**形状（blobs + manifest + digest）值得借鉴，系统本身不该引进来。**

**否掉分布式 / POSIX 文件系统。** 这条路被明确提出过（「远程改 skill 要像本地一样，所以需要一个远程分布式文件系统」），值得记下为什么否掉：

- **本地 `patch` 能工作靠的不是文件系统**，是「读到当前字节 + 写回新字节 + 知道别人改没改」这三件事。PostgreSQL 三件都给，而且是事务性的；POSIX 对**文件内容**恰恰没有 compare-and-swap。
- **它没有按内容寻址的逐文件身份**，会毁掉 [ADR 0005](0005-content-addressing.md) 的去重、逐文件 sha256 与确定性 digest。
- **它自带 uid/gid/mode 权限模型**，与「鉴权在元数据层」正相反。
- **没有任何客户端会去挂载它**：消费方是发 HTTP 的 agent 与 CLI。分布式文件系统是给 POSIX 客户端的解，我们没有 POSIX 客户端。
- 业界证据也反过来。云开发环境——这个提案最好的辩护——**大多用本地盘**：Gitpod 用 containerd 的 overlayfs，Coder 是一个工作区一台机器，GitHub 托管 runner 是临时 VM 加本地 SSD，Fly Volumes 明确写着 "not network storage"。它们要真 POSIX 是因为 agent 要在上面**跑代码**；而 [ADR 0008](0008-no-script-execution.md) 已定下本项目不执行脚本，所以我们站在「内容平台」那一侧——内容平台给的都是内容级端点。

**为什么字节也进同一个数据库，而不是另立一处。** 三条：**发布是一个事务**（元数据与字节一起落定，不会出现「库里有、字节没有」的孤儿；换成对象存储就多一个「对象已写、事务未提交」的崩溃窗口和一套对账逻辑）；**字节回收与版本变更同一事务**（决定 4，是 ADR 0005 完整性保证的直接落实）；**幂等发布靠一条 `UNIQUE(skill_id, digest)` 约束**，不必写代码，也没有「先查再写」的竞态。

代价只有一条是真的：**内容的读流量和查询流量共用同一份资源**。它正是「将来搬 OSS」那条触发线的来源。

## 后果

- **`BlobStore.get/put(sha256)` 这层接缝必须现在就做。** 三个实现按序出现：`PgByteaStore`（现在）→ `OssStore`（将来）→ `CachedStore(inner)`（再将来）。换后端是改配置，不是改代码。**本地 sha256 缓存现在不必做**——它当初的价值主要是抵消对象存储那一跳。
- **搬 OSS 的触发条件是容量，不是现在**：语料 > 0.5–1 TB，或备份 + 恢复超出 RTO，或 blob 的 IO 压过单个 PostgreSQL。
- **`bytea` 的备份代价要说准**，别传民间说法：不是 WAL（一次写入不算什么），不是 MVCC 膨胀（只插不改）。`pg_dump` 的 hex 是 2 字符/字节，所以**纯文本格式会翻倍，但 `-Fc` 压缩能赚回来**；单值约 500 MB 时 `pg_dump` 才会失败，而上传上限 16 MiB，永不触发。**真正的约束是语料级的备份与恢复耗时。** 把 blob 放单独表 + 单独表空间，正是为了能把它与元数据分开备份，并隔开 `shared_buffers` 的占用。
- **TD 第 3 章的 DDL 按 PostgreSQL 改**：`audit_event` 的 `AUTOINCREMENT` 换成 `GENERATED … AS IDENTITY`；其余（ULID 文本主键、RFC3339 UTC 文本时间、部分索引）在 PostgreSQL 上都成立。
- **检索的索引设计重新成为开放问题。** SQLite 时代的 `skill_fts`（FTS5 + trigram）计划**作废**；方向是 PostgreSQL 侧的 `pg_bigm`（2-gram，覆盖 2 字中文），**待验证**。[ADR 0006](0006-search-server-side.md) 的三条约束（搜索全在服务端、只索引 L1、排序可解释且可回归）不变；**所有权过滤仍按 [`technical-design.md`](../versions/v1-hosting/technical-design.md) §3.4 在查询时做**。
- **需要核实的托管细节**：`pg_bigm` 在阿里云 RDS PG 的**基础版**上是否可用（官方扩展列表说的是 RDS PG 整体支持 PG13–17，未区分系列）；且 `pg_bigm` / `zhparser` / `pg_jieba` 都要加进 `shared_preload_libraries` 才能 `CREATE EXTENSION`。
- **价格不是承诺。** 本文里的数字是 2026-09-27 用阿里云 CLI 的 `DescribePrice` 实时取的（cn-shanghai、20 GB ESSD、PG 17）：基础版 `pg.n1e.1c.1m` 原价 **30元/月**，首年实付 **216元/年**（18元/月，因为「新客首购 1 年 6 折，限 1 次限 1 件」，续费回 30元/月）；高可用版 `pg.n2m.2c.2m`（2核2G）原价 294元/月。价格**已含 20GB ESSD**（100 GB 是 110元/月，即 1元/GB/月，不要多买）。促销会变，下单前应在控制台复核。

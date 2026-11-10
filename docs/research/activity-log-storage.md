# 行为日志的存储选型：PG、SLS 与它们的组合

> **最后更新**：2026-11-10
> **这一份是什么**：v58「个人使用记录 / 行为明细日志」的存储调研。只记事实与取舍，不代替 ADR；最终决定写进 v58 的 ADR 与技术设计。
>
> **已被最终决定取代的部分**（保留原文，作为当时的比较）：
> - 冷层最终是 **NDJSON.gz**（一周一个对象，不是 Hive 分区）：服务端没有 Parquet 写入依赖，`COPY` 流式导出 + gzip 已够用，DuckDB `read_json_auto` 一样能读。见 [ADR 0179](../decisions/0179-personal-usage-is-visible-only-to-its-owner.md) 决定 8 与 v58 技术设计。
> - 日汇总最终是 `skill_collaborator_day(skill_id, actor_user_id, owner_user_id, day, invokes, versions, last_at)`（`owner_user_id` 可空），只有调用次数与提交版本数，不含 read/write。见 V45 迁移与 ADR 0179 决定 2。
> - 索引最终四条，清单见 v58 技术设计 §数据模型；本文的三条草图不含明细折叠索引，且不建议建 owner 索引。
- 热明细落在**同一个 schema**（默认 schema 新增两张表），不是候选 A 里草图的独立 `activity` schema。

## 问题

要记录已登录用户的全部行为（搜索、浏览、调用、读取、写入、共享、凭据、标签、同步），并支持：

1. **本人**看自己的搜索与调用明细（写后立刻可查）。
2. **所有者**看自己 skill 的聚合（公域）与按协作者的统计（共享）。
3. **内部**按账号做分析与审计。

规模未知，但要求「90 天数据放一个库里，成本低，能 join、能分析」。

## 现有约束

- 生产 RDS：PostgreSQL 17，基础版 `pg.n1e.1c.1m`（**1 核 1G**），**20 GB ESSD**，内网 `pgm-uf64hdx1njd59aih`。
- 主库上已有业务表与 `audit_event`（写入 / 管理动作，事务内写，目前永久保留）。
- 应用账号不是 `rds_superuser`；装扩展要走控制面 API。
- 单机 ECS 2 核 4G，服务端与 nginx 同机。

## 候选方案

### A. 同实例 + 原生分区（推荐）

- 同一 RDS 实例、同一数据库，新建 schema（如 `activity`），表按周分区，90 天后 `DROP` 旧分区。
- 优点：**零新增实例费**；与 `skill` / `skill_grant` / `skill_library_join` 直接 join；写入立刻可查。
- 缺点：与业务共享 1 核 1G；20 GB 存储要扩；`audit_event` 与日志都在同一个备份里，备份体积会涨。

### B. 独立低配 RDS PostgreSQL（日志库）

- 同 VPC 新建一个低配实例（如 1 核 2G / 50 GB），只放行为日志；主库保留业务与 `audit_event`。
- 优点：**资源与备份隔离**；日志库可单独设短备份保留；主库不被分析查询拖住。
- 缺点：多一个实例的费用与运维；跨库 join 做不了。解法是**把统计需要的上下文冗余进事件行**（`owner_user_id`、`visibility`），页面查询不需要 join；真正需要 join 时用 `postgres_fdw` 或应用层两次查询。
- 粗估：低配实例约 ¥150–250/月 + 存储；未实测，以购买页为准。

### C. SLS

- 消费事件写 SLS，PG 只留 `audit_event`。
- 优点：便宜、TTL 长、自带 SQL，高量时成本优势明显。
- 缺点：**近实时而非读己之写**（刚 invoke 完可能查不到）；**不能与业务表 join**；多一套 RAM / LogStore ACL，隐私面净增。
- 适合作为高量后的长期归档，不适合做用户侧「刚写完就要看到」的主存储。

### D. TimescaleDB（阿里云 RDS 扩展）

- 阿里云 RDS 提供的是 **Apache-only 构建**；压缩、保留策略、连续聚合属于 TSL，**在该构建下不可用**（`functionality not supported under the current license "ApacheOnly"`）。
- 结论：**不作为本版方案**。原生分区已经覆盖「按时间切片 + 整片删除」，压缩可以靠 `default_toast_compression = lz4` 与窄列解决。

## 量级估算（未实测，按行宽与事件量估算）

假设每个活跃用户每天 30–50 条事件，单行约 350–450 字节（含行头与对齐），索引约为堆的 20–30%。

| 日活 | 事件/天 | 90 天行数 | 堆 + 索引 | 20 GB 够吗 |
|---|---|---|---|---|
| 1,000 | 3–5 万 | 270–450 万 | 约 1.5–2.5 GB | 够 |
| 10,000 | 30–50 万 | 2,700–4,500 万 | 约 15–25 GB | **不够**，需 50–100 GB |
| 100,000 | 300–500 万 | 2.7–4.5 亿 | 约 150–250 GB | 需独立实例或 SLS |

存储价按 Serverless 公开价约 **1.24 元/GB/月**（0.0017 元/GB/小时 × 730）作代理；包年包月 ESSD 的精确单价以购买页为准。20 GB 扩到 50 GB 的增量按下方 `DescribePrice` 实测为 **+¥30/月**；按 Serverless 代理价粗算是约 ¥40，两者基准不同，引用取实测。

## 变配成本与影响（2026-11-10 用阿里云 `DescribePrice` 实测）

当前实例：`pg.n1e.1c.1m`（Yitian 1 核 1G），20 GB ESSD，包年到期 2027-10-24。目标 2 核 4G 是 `pg.n2e.2c.1m`（同一 Yitian 规格族，在 `DescribeAvailableClasses` 的升级列表里）。

| 变更 | 剩余周期一次性费用 | 续费后月价 |
|---|---|---|
| 只扩存储 20 → 50 GB | **¥348.41** | ¥60/月（+¥30） |
| 只升 1C1G → 2C4G（保持 20 GB） | **¥673.59** | ¥88/月（+¥58） |
| 2C4G + 50 GB 一起 | **¥1,022.00** | ¥118/月（+¥88） |
| 2C4G 标准架构（非 Yitian）+ 50 GB | — | ¥206/月 |

- **存储扩容不中断业务**（云盘在线扩容）。
- **规格升配有短暂业务闪断**：数十秒到几分钟；基础版没有备节点，可能比高可用版更久。实例 ID 与连接地址不变，无需手动重启；建议放维护窗口，应用要有自动重连。
- 结论：可以快速升配，但不是零中断。存储可以先单独在线扩，CPU / 内存择时升。

## 90 天之后的数据去哪

| 层 | 放哪 | 保留 | 能否在线 join |
|---|---|---|---|
| 热 | PG 明细，按周分区 | 90 天 | 能 |
| 温 | PG 日汇总（所有者 × skill × 协作者 × 天） | 1 年 | 能 |
| 冷 | 明细分区导出 Parquet / CSV.gz 到 **OSS 归档**，然后 `DROP` | 1–3 年 | 不能；用 DuckDB / Spark 离线分析 |
| 审计 | PG `audit_event`（写入 / 管理动作） | 永久 | 能 |

- OSS 公开价：标准 ¥0.09/GB/月、低频 ¥0.07、归档 ¥0.03、冷归档 ¥0.015（2025-10 降价后，以账单为准）。按 1 万日活估算，一年明细约 80 GB：放低频层约 **¥5.6/月**（80 × ¥0.07），转归档层约 **¥2.4/月**（80 × ¥0.03）。
- 归档注意最小存储时长：低频 30 天、归档 60 天、冷归档 180 天；提前删除要补费。
- 如果希望 90 天以上仍可在线 join：把明细保留期拉到 365 天，按 1 万日活多约 **¥60–100/月** 存储（含索引），并相应扩存储与备份。

### 为什么冷层用 OSS + Parquet（而不是直接回流 MaxCompute 内部表）

站在未来分析的角度，冷层要满足三件事：**谁都读得了、不锁死、不用重新导出**。

- **Parquet 是开放列式格式**：MaxCompute 外部表、Hologres 外表、Spark、Trino、DuckDB 都能直接读；今天用 DuckDB，明天上 MaxCompute 不用搬迁。
- **Hive 风格分区**：`event_family=.../dt=YYYY-MM-DD/`，任何引擎都能分区裁剪；列式压缩后体量小。
- **冷层存低频访问，不存归档**：归档取回有延迟与取回费；低频约 ¥0.07/GB/月，仍很便宜。可在 1 年后再转归档。
- **事件行冗余维度**：`owner_id`、`visibility` 等随行写入，冷数据自己做所有者 / 协作者分析，不依赖 PG 业务表。
- **回流内部表只在分析日常化后**：日均查询多、或要与大维表频繁 join 时，再把 Parquet 加载进 MaxCompute 内部表；否则保持外表直读。

**结论**：冷层 = OSS + Parquet + Hive 分区 + 低频访问；在线查询留在 PG，离线分析先 DuckDB、后 MaxCompute 外表。

## 低成本的落地要点（原生 PG）

1. **按周分区**：90 天约 13 个分区；创建下一个、`DROP` 最老的一个，不做 `DELETE`（避免膨胀与 VACUUM）。
2. **索引只留三条**：`(actor_id, at DESC)` 看自己的明细；`(owner_id, skill_id, at DESC)` 看所有者统计；`(skill_id, at DESC)` 看公域聚合与内部排查。时间列可再加 BRIN，但周分区下收益有限。
3. **窄行**：`target`（query 或文件路径）截断到 300 字符；`detail` 只在该事件需要时写 JSON，不把整条请求塞进去。
4. **同步 best-effort 写**：跟随 M15 的 `REQUIRES_NEW`，写完即查得到；失败只记日志，不影响读。高量后再改批量队列。
5. **日汇总**：`skill_collaborator_day(owner_id, skill_id, actor_id, day, invoke, read, write)` 由每日任务从明细聚合；明细 90 天，汇总可留 1 年，体量极小。所有者统计读汇总，不扫明细。
6. **公域聚合复用 M15**：公开 skill 的不同账号数、累计次数、入库人数已有来源，不另建一套。
7. **备份**：日志与业务同库时，RDS 备份会一起变大；同实例方案要盯住备份费用。独立实例可把备份保留设短。

## 推荐

**v58 用方案 A（同实例 + 原生分区）起步**，理由：

- 现阶段日活远低于 1 万，90 天数据约几个 GB，20 GB 扩到 50 GB 即可；
- 用户侧要「写完立刻看到」，PG 直接满足；
- 所有者统计要与业务表 join，同库最省事；
- 不引入第二套数据面与 ACL。

**迁移触发条件**（任一满足就评估方案 B 或 SLS）：

- 明细 + 索引超过 30 GB，或存储月费超过一个独立低配实例；
- 页面 p95 查询超过 300 ms，或 1 核实例 CPU 持续 > 70%；
- 日事件量超过 100 万。

## 来源

- [阿里云 SLS 计费项（官方）](https://help.aliyun.com/zh/sls/billing-examples)
- [阿里云 SLS 查询与分析限制](https://help.aliyun.com/zh/sls/limits-46)
- [阿里云 RDS PostgreSQL 支持插件列表](https://help.aliyun.com/zh/rds/apsaradb-rds-for-postgresql/extensions-supported-by-apsaradb-rds-for-postgresql)
- [阿里云 RDS PostgreSQL 使用 TimescaleDB 扩展](https://www.alibabacloud.com/help/zh/rds/apsaradb-rds-for-postgresql/use-the-timescaledb-extension)
- [阿里云 RDS Serverless 费用（该链接指向 SQL Server 实例页，本文只把它当量级代理）](https://help.aliyun.com/zh/rds/apsaradb-rds-for-postgresql/pricing-of-serverless-apsaradb-rds-for-sql-server-instances)
- [PostgreSQL 超大审计日志的分区、索引与保留实践](https://dev.to/instawebhook/storing-billions-of-webhook-audit-logs-in-postgresql-partitioning-indexing-compression-and-4gaa)
- [TimescaleDB 压缩比案例（Cloudflare）](https://www.tigerdata.com/blog/how-timescaledb-helped-us-scale-analytics-and-reporting)

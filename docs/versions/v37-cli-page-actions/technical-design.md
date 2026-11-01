# v37-cli-page-actions · 技术设计

> **最后更新**：2026-11-01
> **状态**：已实现；视觉待验收
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。

## 架构

两段，边界清楚：

```
CLI（本地）                             服务端
┌──────────────────────────────┐        ┌───────────────────────────────┐
│ runPageAction(spec, args)    │        │ M12 skill                     │
│   ├─ 解析地址 / --bump / 开关  │        │   skill.ever_published_at  ←新 │
│   ├─ 读一次作者面 ─────────────┼──────▶ │   作者页 Skill +3 字段  ←新     │
│   │   失败 → 再读一次消费者面   │        │   POST …/delete（窄）  ←新      │
│   │   rung / owned / offline  │        │ M7 version                     │
│   │   / ever_published         │        │   recordPublish 顺带写 ever_pub │
│   ├─ 判定 ─────────────────────┤        │   写前要求行未删除（并发保护）   │
│   │   不够档位 → 不打开、说明   │        └───────────────────────────────┘
│   │   状态不对 → 不打开、说明   │
│   │   够 → 拼地址、开浏览器     │
│   └─ delete --yes → 发删除请求  │
└──────────────────────────────┘
```

- **CLI 侧打开网页有三个 `openBrowser` 调用点**：七个带地址的页面动作与 `skills publish` 共用 `openActionPage`（`runPageAction` / `skillsPublish` 进入）；`version open` 与 `skill open` 各自保留打开逻辑（首行是地址，不是「还没执行」），但档位判定复用同一张表。`skill delete` 是唯一会执行的路径（`skillDelete`）。
- **服务端加一列一路由**：`skill.ever_published_at` 与作者面的窄删除，另加 `recordPublish` 的「行未删除」条件与发布侧的拒绝（并发保护）。见 [ADR 0127](../../decisions/0127-never-published-skills-may-be-deleted-by-a-credential.md)。

## 数据模型

```sql
-- V31：一次上线之后就不能再被凭据删除，而「上线过」这个事实要能被 M12 自己的语句读到。
ALTER TABLE skill ADD COLUMN ever_published_at TEXT;

UPDATE skill s
   SET ever_published_at = (
       SELECT MIN(v.first_published_at) FROM skill_version v
        WHERE v.skill_id = s.id AND v.first_published_at IS NOT NULL);

COMMENT ON COLUMN skill.ever_published_at IS
  'When this skill first had a version go live. Never moves, never clears — not on revoke, rollback, offline or restore. Written by recordPublish; the CLI''s narrow delete reads it (ADR 0127).';
```

- **为什么冗余这一列**：删除的条件必须和删除本身在**同一条 `UPDATE … WHERE`** 里；而 `skill_version` 是 M7 的表，M12 在自己的语句里 join 它违反表归属（`TableOwnershipTest`）。理由见 [ADR 0127](../../decisions/0127-never-published-skills-may-be-deleted-by-a-credential.md)。
- **谁写它**：`SkillRepository.recordPublish` 加 `ever_published_at = COALESCE(ever_published_at, :at)`。这是「一版上线」唯一写 skill 行的路径（回滚也走它，`COALESCE` 保证只记第一次）。
- **并发**：`recordPublish` 的 `WHERE` 另加 `deleted_at IS NULL`。发布与删除抢同一把行锁，谁后提交谁看见对方的结果：删除赢了，`recordPublish` 影响 0 行，`VersionPublisher` 随即读到 `skillDeleted` 为真并抛 `SkillDeletedException`，整个发布事务回滚——不会留下「skill 已删、版本却是线上」的状态。

## 接口

### 作者面的 `Skill` 增加三个字段

`AuthorVersionResponse.Skill`（列表与单版共用）增加：

| 字段 | 类型 | 来源 | 为什么 CLI 不能自己算 |
|---|---|---|---|
| `owned` | bool | `Access.owns()` | 作者面把 owner 也报成 `admin`（`access().owns() ? ADMIN : rung`），CLI 分不出「所有者」和「被授予 admin 的人」；而删除、下线、恢复服务只给 owner |
| `offline` | bool | `Access.offlineAt() != null` | 作者面今天不暴露下线状态；`skill online` 只在已下线时有意义 |
| `ever_published` | bool | `ever_published_at != null` | 要扫全部版本行才能算出来，且必须与服务端删除条件用同一份事实 |

其余字段不变；老客户端忽略新增字段。

### 删除（新，仅 API 平面）

`POST /api/v1/author/skills/{namespace}/{name}/delete`

| 结果 | 状态码 | 错误码 | 说明 |
|---|---|---|---|
| 删除成功 | `204` | — | 软删；名字不释放 |
| 没有这个 skill，或看不到 | `404` | `skill_not_found` | 与现有不泄露规则一致 |
| 看得到，但不是 owner | `403` | `forbidden` | `NotPermittedException`，消息说清「删除是所有者的事」 |
| 是 owner，但上线过 | `409` | `skill_published` | 新错误码；句子说清「只能到网页上由人确认删除」，并说明**下线不会解除这条** |

鉴权沿用 `/api/v1/**` 的既有规则：Bearer + `skills:write`（`SecurityConfig` 的非 GET 规则），**不改安全配置**。

判定与执行在同一条语句里：

```sql
UPDATE skill SET deleted_at = :at, updated_at = :at
 WHERE id = :skillId AND deleted_at IS NULL AND ever_published_at IS NULL
RETURNING id
```

为零行时再读一次这一行，区分两种原因：`deleted_at` 非空 → `404`（并发删除，另一条已经删了）；`ever_published_at` 非空 → `409`。这一次读只影响**拒绝的理由**，不影响动作本身。

用例落在 `SkillMetadataService`（新增 `deleteNeverPublished`，返回 `DELETED / NOT_FOUND / NOT_OWNER / PUBLISHED`），控制器在 `AuthorSkillWriteController`；`SoftDeleteSkillUseCase` 不动（网页那条路继续用它）。

### CLI 命令面

**七个带地址的页面动作**走同一条：**读一次作者面 → 判定 → 打开或拒绝**；`skills publish` 不读，`skill delete --yes` 直接发删除、不预检（见下）。

- **预检的兜底**：作者面读失败时，再读一次消费者面（`GET /api/v1/skills/{ns}/{name}/metadata`，每个档位都读得到，ADR 0051）拿档位。于是「`invoke` 档执行页面动作」会被**真正拒绝**，而不是笼统地算作读不到。
- **判定不了**：没有凭据、网络失败、消费者面也读不到（含 404）→ 不拦，照常打开并在 stderr 写明没能预检。
- **两处使用作者面专属的事实**（版本状态、`owned`、`offline`）只有作者面能回答；兜底读来的档位不足以判它们时不拒绝，交给页面。

| 命令 | 读什么 | 要的档位 | 还要的状态 | 不满足时 |
|---|---|---|---|---|
| `version publish <地址> [--bump]` | 那一版 | `maintain` | 状态是 `draft` 或 `history` | 说明「现在是 X，上线只在草稿/历史版上有意义」+ 页面地址，退出 1 |
| `version rollback <地址>` | 那一版 | `maintain` | `live` | 同上 |
| `version revoke <地址>` | 那一版 | `maintain` | `history` | 同上 |
| `version restore <地址>` | 那一版 | `maintain` | `revoked` | 同上 |
| `skill offline <地址>` | skill | owner | `offline == false` | 说明「它已经下线了」+ 地址 |
| `skill online <地址>` | skill | owner | `offline == true` | 说明「它现在是在线的」+ 地址 |
| `skill visibility <地址>` | skill | `admin` | — | 档位不够就说明 + 地址 |
| `skill delete <地址>` | skill | owner | 见下 | — |
| `skill open [--tab]` | skill | 按分区：信息 / 版本 → `invoke`；文件 → `browse`；设置 → `admin`（控制台的 `useSkillPageRoute`） | — | 说明 + 地址 |
| `version open <地址>` | 那一版 | — | 作者面对这一版 404 且 skill 可见时，判「看不到这一版」 | 说明 + 地址 |
| `skills publish` | 不读 | — | — | 直接打开 `/publish` |

**`skill delete` 的五条分支**（不带 `--yes` 四种，带 `--yes` 一种）。**帮助不宣传确认开关**：`--yes` 不进用法、参数、例子与失败码句子，只在命令自己打印的「确认后执行」里出现——复制得到的第一条命令永远是只说明后果的那一条（[ADR 0128](../../decisions/0128-cli-help-does-not-advertise-the-confirmation-switch.md)；0056 只管浏览器面）。同一条规则也适用于既有的 `version discard`（2026-11-01 产品负责人确认）；`skill join` / `skill leave` 不在其列。**每个页面动作的帮助都要写明动作在网页上完成**：摘要带网页/打开页面的标记（「打开网页确认」「打开待上线页」「删除 skill（网页）」都算），`输出` 里写「命令不执行…确认在网页上由人完成」；`skill delete` 写「上线过的 skill 到设置页「常规」由人在网页上确认删除」。

| 情况 | 行为 | 退出码 |
|---|---|---|
| 不带 `--yes`，`ever_published == false` | 打印后果与完整命令（`skmr skill delete <地址> --yes`） | 1 |
| 不带 `--yes`，`ever_published == true` | 打印「上线过的 skill 在网页上由人确认删除」+ 设置页地址 | 1 |
| 不带 `--yes`，判定不了（读不到作者面） | 只说「没能确认它是否上线过」，并给设置页地址——**不说「它从未上线」，也不给确认命令**（确认开关只在预检确认可删之后才出现） | 1 |
| 不带 `--yes`，看得到但不是 owner | 说明「删除要所有者才能做」，不给命令。作者面拒绝时由消费者面的档位判定（`rung < admin` 即不是所有者） | 1 |
| 带 `--yes` | **不预检**，直接发删除；`204` 打印已删除，`404/403/409` 按服务端的回答 | 0 / 1 |

`--yes` 不预检：服务端的那条条件已经是唯一权威，预检不会改变结果，只会多一次读；服务端拒绝时消息里本来就带着下一步。

### 输出与 JSON

页面动作（stdout 三行）：

```
还没执行：上线只能在网页上由人确认。
https://console…/skills/dev/pdf-tools?tab=versions&version=01JKMNPQRSTVWXYZ0123456789&sub=actions
（已在浏览器里打开。）
```

`--json`：

```json
{"action":"publish","performed":false,"url":"…","opened":true}
```

被拒时 `--json` 走既有的错误形状，语义明确的码：`insufficient_permission`（档位不够）、`wrong_version_state`（状态不对）、`not_found`（这一版看不到）、`skill_not_found`（这个 skill 看不到）、`confirmation_required`（`skill delete` 少了确认开关；`version discard` 的同类情况沿用既有的 `usage`、退出 2）、`precheck_unavailable`（`skill delete` 判定不了，读不到作者页）、`skill_published`（凭据不能删上线过的 skill），都是退出 1。其中 `wrong_version_state`、`confirmation_required`、`precheck_unavailable`、`skill_published` 是本版新加进 `internal/help/registry.go` 的；`insufficient_permission` 与 `not_found` / `skill_not_found` 沿用既有码。

`skill delete --yes` 成功：

```json
{"action":"delete","performed":true,"address":"dev/pdf-tool","deleted":true}
```

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 服务端：V31 迁移、`ever_published_at` 写路径与回填、作者面三个字段、窄删除路由与错误码、发布的并发保护 | 服务端 IT：四类结果 + 「上线→下架→删」+ 并发投影写 + V31 回填 |
| P1 | CLI：`runPageAction` / `executePageAction` 与档位/状态表、九个命令、移除旧名、`open` 纳入预检、`skills pending` 提示、帮助与 README | PRD 验收 #1–#10 |
| P2 | 同意屏文案（设计件 + 两处产品已改） | 设计检查已过；**人看**待做 |

P0 明确不做：网页删除的任何改动；P1 明确不做：`skills publish` 的逐条预检。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| 1 | `skills publish` 要不要逐条预检 | 不预检：没有单一地址，页面只列发布得了的 | 页面若将来支持「按参数预检」，再开一条 |
| 2 | 状态不对时的措辞 | 按动作各自的 `stateHint` 拼接，例如 publish 遇到 live 时说「这一版现在是线上，上线只能用在草稿和历史版本上」；每条 `stateHint` 都只说自己那个动作 | 交付时按 voice-spec 复核一遍 |
| 3 | 没有本机凭据时的页面动作 | 照常打开并说明「没能预检」；不要求先登录 | 若出现误打开太多，再考虑改成提示 |
| 4 | CLI 与服务端不同版时，作者面缺 `owned` / `offline` / `ever_published` 怎么办 | **要求同版部署**：新 CLI 配旧服务端时，这三个字段按零值读，owner 类动作可能误拒。一次发布一起上，不做跨版本兼容 | 若将来必须跨版本，把三个字段改成可缺省的指针，缺失时按「判定不了」打开 |

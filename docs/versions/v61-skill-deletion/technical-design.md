# v61-skill-deletion · 技术设计

> **最后更新**：2026-11-11
> **所属版本**：[`README.md`](README.md)

## 架构

这一版改 M12（skill）的状态与身份规则，M14（notification）加一个 kind 与一个每日任务；M7（version）只动一处异常映射（`SkillDeletedException` 不再单独映射：发布与删除的竞态落到 404 `skill_not_found`）；M15（metrics）不改。相对 [`docs/current/`](../../current/README.md) 的 delta：

- **skill 行新增「下线中」相位。** 用 `offline_pending_at` / `offline_due_at` 两列表达；`offline_at` 继续表示已下线。四个状态由三列推导（见 §数据模型）。
- **名字的唯一性从「含已删行」改为「只看活行」。** 删除不再占名；恢复时原名已被新 skill 占用，就改名为 `原名-restored-<id>`。
- **消费面读到「公开、曾发布、已删除」的行返回 410 `skill_gone`**；私有删除仍是 404。公开 skill 已下线仍是 503 `skill_offline`。
- **下线与删除新增「公开 + 有线上版本 + 有入库者」判定。** 过渡期的触发只看 `skill_library_join`；授权者不触发过渡期，但和入库者一起收通知（收件人是两者的并集，去掉所有者）。
- **时间是状态的一部分，读时就判定。** 所有读路径按 `offline_due_at <= now` 推导「已下线」；`OfflineDueJob` 只负责物化 `offline_at` 与发通知，不是状态的唯一来源。这与邀请过期的现有做法一致（[ADR 0138](../../decisions/0138-sharing-is-an-invitation.md) §决定 4：过期是读出来的，不是扫出来的）。
- **通知新增 `skill_offline_scheduled`**（预告，带日期，客户端给「复制到我的库」）；完成、取消与删除用现有 `announcement`（标题 + 正文，无动作）。
- **CLI 不改命令形状**：新增 `--show pending`，并在 `invoke` / `run` 的 stderr、`join` 的拒绝、`delete` / `restore` 的回执里读新字段。

## 数据模型

迁移 `V49__skill_offline_phase_and_released_names.sql`：

```sql
ALTER TABLE skill ADD COLUMN offline_pending_at TEXT;
ALTER TABLE skill ADD COLUMN offline_due_at     TEXT;
ALTER TABLE skill ADD CONSTRAINT skill_offline_phase CHECK (
  (offline_pending_at IS NULL AND offline_due_at IS NULL)
  OR (offline_pending_at IS NOT NULL AND offline_due_at IS NOT NULL AND offline_at IS NULL)
);

-- 名字只被活着的行占用；已删行可以是任意多个同名墓碑。
ALTER TABLE skill DROP CONSTRAINT skill_namespace_id_name_key;
CREATE UNIQUE INDEX skill_live_name ON skill (namespace_id, name)
  WHERE deleted_at IS NULL;

CREATE INDEX skill_offline_due ON skill (offline_due_at)
  WHERE offline_due_at IS NOT NULL AND offline_at IS NULL AND deleted_at IS NULL;
```

**状态推导**（`deleted_at` 优先）：

| 条件 | 状态 |
|---|---|
| `deleted_at IS NOT NULL` | 已删除 |
| `offline_at IS NOT NULL` 或 `offline_due_at <= now` | 已下线 |
| `offline_due_at > now`（且 `offline_at` 为空） | 下线中 |
| 三列都为空 | 在线 |

**读时判定，不等任务。** 到期的那一刻起，消费面就按已下线返回 503、列表就显示已下线、删除就允许；后台任务只是把 `offline_at` 落下来并发出完成通知。任务挂了一天，状态也不会错。

### 为什么这样选

- **名字释放用 partial unique index，不用「删除时改名」。** 仓库里已经有两处同一模式（`idx_skill_ns`、`idx_skill_vis` 都带 `WHERE deleted_at IS NULL`）；partial index 保留展示名、删除只写一列，恢复冲突交给「按 id / 最新墓碑 + 后缀改名」处理。删除时改名要多存一份展示名、每次删除都改地址，还要处理后缀长度，换来的只是「不出现同名墓碑」——而列表与恢复本来就要能处理多条同名墓碑。
- **下线中用「读时判定 + 任务物化」，不用「只靠每日任务推进」。** 邀请过期已经是这个做法（[ADR 0138](../../decisions/0138-sharing-is-an-invitation.md) §决定 4：过期是读出来的，不是扫出来的）。如果状态只靠任务，任务失败、进程重启或部署当天就会让一个到期的 skill 继续对外服务；读时判定把这个风险消掉，任务只负责通知与落列。
- **通知的恰好一次靠事务与条件更新，不靠去重。** 认领行的那一次条件更新与通知写入在同一事务；没认领到的实例直接跳过。

- `offline_pending_at` 是开始时刻；`offline_due_at` = 开始 + 30 天，RFC3339 UTC。
- **取消下线中**：清空两列。**恢复服务**（已下线）：清 `offline_at`。两者都不动版本与可见性。
- **删除**：只写 `deleted_at = now`，不动 `offline_at` 与两列；名字立即释放由 partial index 完成。
- **已删除列表**按 `deleted_at DESC`；同一个名字可以有多行。恢复选 `deleted_at` 最新的一行；控制台按行恢复时请求带 `id` 精确指定，CLI 只给地址时恢复最新一行。
- **恢复不动服务状态**：清 `deleted_at` 只让行重新存在；删除前是已下线 / 下线中的，恢复后仍是那个状态（下线中的到期日继续有效）。
- **恢复冲突改名**：原名有活行时，`新名 = 原名前缀 + "-restored-" + id`；原名超过长度上限时截断前缀，**再退到上一个段边界**（去掉末尾连字符，名字语法不允许 `--`），保留后缀。改名与清 `deleted_at` 在同一条 `UPDATE` 里。
- **410 判定**：`deleted_at IS NOT NULL AND visibility = 'public' AND ever_published_at IS NOT NULL`，**且同名下没有活着的 skill**（`NOT EXISTS`）。只删过草稿的公开 skill、私有 skill，以及名字已被新 skill 接管的地址，仍是 404。
- **计数**：入库者数读 `skill_library_join`，授权人数读 `skill_grant`，都按 `skill_id`；收件人是两者并集，去掉所有者。

## 接口

**Web 平面（M12）**

| 接口 | 变化 |
|---|---|
| `POST /web/skills/{ns}/{name}/offline` | 返回 `{ offline: boolean, pending_until: string \| null }`（wire 是 snake_case）。公开 + 入库者 > 0 + 有线上版本 → 条件更新写两列（只有从「在线」转过去才算一次），`offline=false`；否则写 `offline_at`，`offline=true`。已在下线中 / 已下线时幂等返回当前状态 |
| `POST /web/skills/{ns}/{name}/online` | 返回 `{ offline: false, pending_until: null }`（wire 是 snake_case）；清空下线中两列或 `offline_at`；只在实际发生转换时才发取消通知 |
| `POST /web/skills/{ns}/{name}/delete` | 公开 + 有线上版本 + 入库者 > 0 且**按读时判定仍在线或下线中** → 409 `offline_required`；否则软删 |
| `POST /web/skills/{ns}/{name}/restore` | 请求体可带 `id`；返回 `{ address, renamed }` |
| 作者面读 / 列表 | 增加 `offline_pending_until`（DTO 字段 `offlinePendingUntil`，Jackson 全局 SNAKE_CASE 输出）；列表状态增加 `pending` |

**API 平面**

- `GET /api/v1/skills/{ns}/{name}`（元数据）：增加 `offline_pending_until`；公开 + 已删除 + 曾发布 → **410 `skill_gone`**。
- 窄删除（从未上线）：语义不变，只是同名可以立即重建。
- `POST /api/v1/skills/{ns}/{name}/restore`：返回 `{ address, renamed }`。

**错误码**

- 移除 `skill_deleted`（创建 / 提交不再拒绝同名）。
- 新增 `skill_gone`（410）、`offline_required`（409，删除被下线流程挡住）、`skill_offline_pending`（409，下线中拒绝入库）。
- `skill_offline` 保持 503。

**通知（M14）**

- 新 kind `skill_offline_scheduled`：带 skill 与作者 handle，**到期日放在通知自己的 `expires_at`**（不另设字段，客户端读它，两处日期不会漂）；收件人是入库者 + 授权者（去掉所有者）。客户端渲染时钟行与「复制到我的库」。feed 与未读数只算未过期的这一 kind。
- 完成、取消、删除用 `announcement`：标题分别是「skill 已下线」「下线已取消」「skill 已删除」，正文带地址与日期；无动作。
- 写入与状态变更同事务；任务里逐条事务，一条失败只记日志并继续下一条。

**任务**

`OfflineDueJob`：`@Scheduled(initialDelayString = "PT120S", fixedDelayString = "PT24H")`、`@Profile("!test")`；核心逻辑放在 `CompleteDueOfflinesUseCase`，测试直接调用。

- 到期条件：`offline_due_at <= now AND offline_at IS NULL AND deleted_at IS NULL`。
- **认领是一次条件更新**：`UPDATE skill SET offline_at = offline_due_at, offline_pending_at = NULL, offline_due_at = NULL WHERE id = :id AND deleted_at IS NULL AND offline_at IS NULL AND offline_due_at IS NOT NULL AND offline_due_at <= :now RETURNING id`。只有拿到行的那一次才发通知；两个实例同时跑也只有一个赢。
- 状态更新与通知写在同一事务里：崩溃则一起回滚，下次重试；不会出现「已下线但没人收到通知」或重复通知。
- 读路径不依赖这个任务（见 §数据模型），所以任务晚跑一天也不改变对外状态。

**影响面**

删除确认不新增接口：作者面详情在同一个响应里加一个 `grantees`（授权人数，与 `usage.joins` 一起读出）；不另开协作者名单的请求。私有 skill 的 `usage` 为空时，入库数按 0。

**CLI**

- `skills list` 的 `--show` 增加 `pending`；`--verbose` 与 `skill show` 的状态写 `下线中 · <日期>` 或 `已下线`。
- `Metadata` 增加 `offline_pending_until`；`skill invoke` / `run` 在下线中成功，stderr 打警告与 `skill copy` 指引。
- `join` 映射 `skill_offline_pending`，并用调用者给的地址拼出可执行的 `skill copy` 命令；`delete` 改回执；`restore` 读 `{ address, renamed }` 并输出新地址。
- 页面动作的预检把「下线中」当一个独立状态：`skill online` 打开取消下线那一页，`skill offline` 在下线中拒绝并给出 `skmr skill online <地址>`。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | V49 迁移、partial unique index、恢复改名、创建/提交去 `skill_deleted`、公开 410 | A1、A2、A5、A12 |
| P1 | 下线中三列与 state、offline/online/delete 判定、作者面与消费面字段、`OfflineDueJob`、通知 | A6–A11 |
| P2 | 控制台（设计件已改）、CLI、通知样张 | A3、A4、A7、A8、A13、A14 |
| P3 | 测试、文档收口与全量检查 | 全部 |

## 开放问题

1. ~~恢复改名时原名超过名字上限怎么办？~~ **已定**（见 §数据模型）：截断原名前缀到上一个段边界，保留 `-restored-<id>` 后缀；`LibraryApiIT#aRestoredLongNameDoesNotEndASegmentWithAHyphen` 钉住。
2. ~~公开但从未发布过的 skill 删除后是 410 还是 404？~~ **已定**：404（见 §接口）；`WebSkillMetadataIT#aPublicSkillThatNeverPublishedStays404AfterDeletion` 钉住。
3. ~~公开且有入库者、但当前没有线上版本时，下线要不要走 30 天？~~ **已定**：即时下线（见 §接口的触发条件），仍通知入库者。
4. ~~恢复改名与并发新建的竞态怎么处理？~~ **已定**：库里已有按名字的事务级 advisory lock（`pg_advisory_xact_lock`，创建 / 提交 / 恢复共用）；恢复在同一把锁下先选墓碑、再查同名活行，撞不上就改名。没有唯一的冲突重试路径。
5. ~~`skill_offline_pending` 的日期要不要进 `--json`？~~ **已定**：错误体的 `details[0]` 带 `pending_until`；文本模式由 `skill join` 自己拼句子（它知道地址，能给出可执行的 `skill copy` 命令），`ServerError` 只留不带占位符的兜底句。

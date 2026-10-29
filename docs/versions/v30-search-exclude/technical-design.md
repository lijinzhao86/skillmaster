# v30-search-exclude · 技术设计

> **最后更新**：2026-10-30
> **所属版本**：[`README.md`](README.md)

<!--
接口与数据模型的权威。取舍的理由在 ADR，不在这里复述。
-->

## 架构

一次 `skmr skills search "<q>" --exclude a/b` 的路径：

```
CLI  parseQueryArgs（--exclude 只属于 search）
  → api.QueryOptions.Excludes
  → GET /api/v1/skills?semantic=true&exclude=a%2Fb&…

服务端  SkillsController.list
  → M12 解析地址为 skill id（NamespaceService.bySlug + SkillRepository.findAnyByName）
  → 语义：SemanticSearchUseCase.search(… excludedIds)
  → 字面：SearchPublicSkillsUseCase.api / LibraryListUseCase.api → SearchRequest(… excludedIds)
```

**边界不变**：M8 拥有检索策略，SQL 在 M7，M12 拥有「这个调用者能看见什么」。排除只是调用方给的一组 id，由 M12 在交出候选集时减掉——三个模块谁都不需要知道「为什么」这些 id 被排除。

## 数据模型

**没有表结构变化。** 排除是请求谓词，不是存储状态：既不写本机，也不写服务端。

- 地址解析只读：`namespace.slug` → `namespace.id`，再 `skill.namespace_id + name` → `skill.id`。
- 不存在的地址解析不到 id，直接从排除集里消失——语义上等于「排除一个不在结果里的东西」，不是错误。
- 软删除的行也能解析到 id；它本来就不在候选集里，减不减都一样。

## 接口

### `GET /api/v1/skills` 新增重复参数 `exclude`

| 项 | 规定 |
|---|---|
| 形状 | `exclude=<命名空间>/<名字>`，**可重复**（Spring 绑成 `List<String>`）；与 `namespace` / `category` 同一约定 |
| 版本 | **不接受 `@版本`**。带 `@` 是 400——排除的是 skill，不是某一版 |
| 上限 | 50 个；超过 → 400 |
| 格式 | 必须恰好一个 `/`，两侧非空；否则 400（错误里点名 `exclude`） |
| 未知地址 | **静默忽略**。过滤器选中空集不是错误，也不回显 |
| 可见性 | 解析只读；排除一个自己看不见的地址不会泄露它存不存在 |
| 分页 | 它是**谓词的一部分**，必须随每一页的请求走（游标只带排序，不带谓词） |
| 响应 | 形状不变：`skills` / `next_cursor` / `note` 都不动 |

### 语义路径（默认）

`SemanticSearchUseCase.search(...)` 多收 `excludedIds`：

- **候选集完整（`admissible.complete()`）**：在 `admissible.ids()` 上减掉排除集，再 `nearestWithin(query, ids, limit)`。这是**精确**的「没看过的 top-N」——页不会缩。
- **候选集过大**：`nearest(query, limit * overFetch + excludedIds.size())`，再按排除集过滤候选。近似，与既有的可见性过滤同一档；排除数加进 overFetch 是为了补偿。
- **降级 / 空结果**：不变。

### 字面路径

`SearchRequest` 多一个 `excludedIds`（已在服务端解析好的 id，不传地址）：

- `SkillSearchService.search` 取 `limit + excludedIds.size() + 1` 行，按排除集过滤，再按 `limit` 切页。**多取 `size()` 是精确的**：被排除的行最多只有 `size()` 条，全部落在这段窗口里也还剩 `limit + 1` 行。
- `hasMore` 用过滤后的行数判断；游标指向**实际返回的最后一行**（被排除的行不会成为游标，下一页从可见行继续）。
- `offset`（控制台页码）路径排除集恒为空，行为不变；`count` 也不改。

### CLI

| 位置 | 改动 |
|---|---|
| `parseQueryArgs` | `--exclude` 只属于 `search`；`skills list` 传了报用法错误。值按逗号拆开、去重；每段必须恰好一个 `/`、两侧非空、不含 `@`；总数 ≤ 50 |
| `api.QueryOptions` | 新字段 `Excludes []string` |
| `api.Client.QuerySkills` | 每个地址 `query.Add("exclude", …)`（与 `namespace` 相同的重复参数写法，不逗号拼接） |
| `search()` | 有结果且未带 `--exclude` → **首行**打印提示；带 `--exclude` 且结果为空 → 提示两种可能 |
| 帮助声明 | `Usage` / `Flags` / `Output` / `Examples` 同步（v16 八项契约） |

**首行提示的原文**：

```
（要换说法再查：加 --exclude 带上这次给过的地址，重复的不再占上下文。）
```

**空结果 + 排除的原文**（服务端若有自己的 `note`，先打 `note`，再打这句）——**只要带过 `--exclude` 就打**，不论服务端是否已给原因；措辞是可能性，不是诊断（服务端不回报是否由排除造成）：

```
（这种空结果也可能是 --exclude 排掉的：去掉 --exclude 再看。）
```

已有的「（还有更多结果；…）」**保持原位**（末尾）：它只在字面路径或语义降级时出现，本版不动它的位置。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0（本版） | 服务端 `exclude` + CLI `--exclude` + 首行提示 + 帮助 | prd A1–A10 |
| P1（不在本版） | `skills list` 的同类处理 | 另开版本 |

## 开放问题

1. **「已看过」应该排除还是降权？** 当前倾向：只做排除。降权会改变排序语义，且需要「看过」的程度（一次还是多次），这是另一个产品问题。
2. **`skills list` 要不要 `--exclude`？** 当前倾向：不要。list 是一次性列全库，不是同一会话里的反复查询；它的重复问题形态不同。
3. **要不要在响应里回报排除计数？** 当前倾向：不回报。空结果话术已经如实覆盖两种情况；计数对调用方没有可行动价值。
4. **排除项是否参与后续版本的分页游标签名？** 当前倾向：不参与。游标只带排序；排除由调用方每页显式带上，与 `category` 等过滤器一致。

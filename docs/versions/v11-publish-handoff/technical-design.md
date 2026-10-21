# v11-publish-handoff · 技术设计

> **最后更新**：2026-10-21
> **所属版本**：[`README.md`](README.md)　需求权威：[`prd.md`](prd.md)

## 架构

**这是一次纯客户端改动：没有迁移、没有配置键、没有新路由，服务端一个字节不动。**

要交过去的不是状态，是「打开时预选哪一个」——所以它可以活在地址里。人如果绕过 CLI 直接打开同一条地址，
得到的就是今天的行为；地址丢了预选，页面也只是回到默认档。没有第二份真相，也没有需要回写的东西。

```
agent ── version publish X@<草稿> --bump minor
          │  （本地拒绝，零请求）
          ▼
  到那一版自己的页面上去做：
  skillmaster version open X@<草稿> --sub actions --bump minor
          │  人复制、执行
          ▼
       CLI 拼地址 ──► 浏览器 ──► 单版页按地址预选 ──► 人确认（POST /web/…/publish，原样）
```

**边界**

| 谁 | 负责什么 | 不碰什么 |
|---|---|---|
| CLI（`cmd/skillmaster/version.go`） | 参数校验、地址拼装、拒绝句的第三行 | 不做、也不代做任何上线类动作（[ADR 0061](../../decisions/0061-what-a-credential-may-do-to-versions.md)） |
| 网页（`version.ts` + 单版页三个组件） | 读地址、预选、切栏时的地址卫生 | 不新增画面、不新增文案；三个单选项是设计件已有的三态 |
| 服务端 | 一切照旧：上线仍是 `POST /web/skills/{ns}/{name}/publish`（会话 + CSRF） | 不读、不校验任何控制台查询参数 |

**为什么不需要设计件改动**：三档预选各自都是设计件画过的状态（点一下「新增能力」之后的样子）。本版只改变
「打开时哪一个是选中的」，不改变任何结构、类名、数值或文案。默认档（patch）写在演示件里，`design/README.md`
没有逐字记录——见 [`prd.md`](prd.md) §依赖与前置。

## 数据模型

**没有表。这一版的数据是地址。** 地址是网页的唯一状态载体（v6 TD §地址），本版只加一个查询参数。

| 参数 | 取值 | 缺省 | 语义 |
|---|---|---|---|
| `tab` | `versions` | 无 | 单版页所在的栏（既有） |
| `version` | 版本号 / 摘要 / 草稿行 id | 无 | 钉住哪一版（既有） |
| `sub` | `diff` / `actions` | 无 = 概览 | 单版页的栏位（既有；`record` 这个拼写不写进地址） |
| `bump` | `patch` / `minor` / `major` | 无 = 页面自己的默认（patch） | 上线表单打开时预选哪一档 |

**生命周期**

- 写入：只有 CLI 拼地址时写；页面从不写回（PRD 不做 #5）。
- 保留：单版页内部切栏（`VersionDetail.subHref` 原样保留查询串）、改动栏导航（`VersionDiffPane` 保留未知参数）。
- 清除：离开版本栏的七处清单，以及上线成功后的 `navigateVersion`（见 §接口 · 网页）。
- 非法值：`bump=foo` 当作没有（`parseVersionRoute` 白名单外一律丢弃），页面照常打开。

## 接口

### CLI · `version open`

```
skillmaster version open <命名空间>/<名字>@<版本>
    [--sub record|diff|actions] [--bump patch|minor|major]
    [--print] [--json]
```

| 输入 | 地址 | 说明 |
|---|---|---|
| 不给新参数 | `{web}/skills/{ns}/{name}?tab=versions&version=<钉>` | 与今天逐字相同（PRD 验收 #12） |
| `--sub actions` | `…&sub=actions` | 落在「操作」 |
| `--sub diff` | `…&sub=diff` | 落在「改动」；没有基线时页面按 v6 规则落回概览 |
| `--sub record` | 不写 `sub` | `record` 是概览的缺省拼写，写进去等于两种说法 |
| `--bump <档>` | `…&bump=<档>` | 与 `--sub` 独立；落在概览时预选仍在地址里，切到「操作」即生效 |

- 参数顺序固定为 `tab`、`version`、`sub`、`bump`（可复制的地址稳定）。
- 取值不在枚举内、同一个取值参数给两次、不认识的参数、第二个地址 → 本地用法错误，零请求；`--json` 下仍是 `code=usage`（既有 `classifyFailure`）。
- `--json` 输出形状不变：`{"url": …, "opened": …}`，`url` 带上新参数（PRD 验收 #13）。
- JSON 模式探测改用 `jsonModeIn(args, "--sub", "--bump")`（`lifecycle.go:26-41`）——把这两个新值参数登记进去，
  否则 `--sub --json` 这类「值被误读成开关」的写法会改变错误输出形状。

### CLI · 页面专属动词（拒绝）

```
skillmaster version publish|rollback|revoke|restore <地址> [--bump patch|minor|major] [--json]
```

- 一律本地拒绝，**零请求**；`publish` 接受 `--bump`，另外三个不接受（版本号只在上线时选）。
- 第三行逐字：

  ```
  到那一版自己的页面上去做：skillmaster version open <地址> --sub actions
  ```

  带 `--bump` 时追加 ` --bump <档>`。
- 不认识的参数、第二个地址、`--bump` 给两次、非 `publish` 上的 `--bump` → 本地用法错误，零请求。
- `offline` / `online` / `delete` 的落点不变（设置页，[ADR 0072](../../decisions/0072-the-handoff-points-at-the-page-that-owns-the-action.md)）。

### 网页

| 位置 | 改什么 | 细节 |
|---|---|---|
| `version.ts` `VersionRoute` / `parseVersionRoute` | 新增可选 `bump?: 'patch' \| 'minor' \| 'major'` | 白名单；缺省或非法时**不生成这个键**（`toEqual` 用例与 `vue-tsc` 都依赖这一点） |
| `VersionActionPanel.vue` | 新增可选 prop `initialBump` | `bump` 的初值取它，缺省 `patch`；`releaseName` 初值取 `suggested[bump]`（`live === null` 时仍是 `1.0.0`） |
| `VersionDetail.vue` | 把 `route.bump` 传给面板；`listHref` 的清除清单加 `bump` | `subHref` **不动**——它必须保留查询串，预选才能跟着切栏走 |
| `SkillPage.vue` | 五处清除清单加 `bump` | `tabHref`（`:158`）、设置子页（`:199`）、`dirHref`（`:544`）、`writeListAddress`（`:718`）、`backToListHref`（`:793`） |
| `useVersionActions.ts` | `backToList` 的清单加 `bump`；`navigateVersion` 删 `bump` | `:201-208` / `:191-198`；后者是上线成功唯一改写地址的地方 |

**惰性范围**（PRD 验收 #9）：`live === null`（第一次上线）不画单选项，号码固定 `1.0.0`；`legacyNamedDraft`
（v6 之前自带版本号的草稿）不画输入框，沿用已有版本号；已上线 / 历史 / 已下架版本没有发布区块。
`bump` 在这些页面上只是一个不起作用的查询参数，不报错、不提示。

## 分期

| 期 | 交付 | 怎么算完 |
|---|---|---|
| **P1 CLI** | `version open` 的两个参数、`pageOnlyVerb` 的参数解析与拒绝句、`versionUsage` 同步 | `go test ./...` 全绿；`version publish … --bump minor` 的输出与 PRD 验收 #5 逐字一致 |
| **P2 网页** | 路由字段、面板预选、七处清单 + `navigateVersion` | `npm run test` 与 `npm run type-check` 全绿；组件用例覆盖三档与三个惰性态 |
| **P3 回写** | v6 / v10 的 `iterations/` 与就地修订、CLI README、ADR 0074 | `check_docs.py` 退出码 0 |

P1 与 P2 的契约由 PRD 的地址语法钉住，可以并行；P3 在两期之后，否则文档会描述还没发生的形状。

## 开放问题

| # | 问题 | 当前倾向 |
|---|---|---|
| 1 | 回滚 / 重新上架成功后，地址里的 `bump` 要不要清？ | **不清**。它们不走 `navigateVersion`（前者原地重读，后者 reload），此时 `bump` 已惰性；为它多改两条路径不划算。若将来这两条也改写地址，再一并清 |
| 2 | 设计 README 要不要补一行「默认选中 patch、地址可在三态间选一个」？ | **本版不补**。`design/` 作为整体是权威，演示件（`:11274`）已经写死默认档；补一行是可选的可发现性改进，不属于本版契约 |
| 3 | `--bump` 是否必须配 `--sub actions`？ | **不强制**。地址只携带人要求的那部分；落在概览时预选暂不可见，切到「操作」即生效。强制会让「只想预选、不想指定栏位」变成一个用法错误 |

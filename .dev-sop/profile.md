# skillmaster · 开发规范

> **每条声明自带「怎么验」**。方法在 `code-dev-sop` skill 里，这里只写这个项目的值与命令。
> 改这个文件等于改「什么叫合格」——只有所有者能定；改完同步改 [`audit.md`](audit.md)。
> 历史版本文档里的 `dev-sop §N` 指的是已下线的那份 `dev-sop`，章节号对不上本文；按内容找，不按编号找。

## 七条声明

| # | 声明 | 值 | 怎么验 | 人/机 |
|---|---|---|---|---|
| 1 | 交付单元 | `docs/versions/vN-主题/`：`README.md` / `prd.md` / `technical-design.md` / `test-plan.md`；可选 `known-issues.md`、`iterations/`。v1–v39 冻结；v40 起只写相对 `docs/current/` 的 delta | 四件套齐 | 机 |
| 2 | 文档位置 | 交付单元同上；决策 `docs/decisions/NNNN-*.md`；现状 `docs/current/`；跨版本待办 `docs/current/TODO.md` | 路径都存在；`check_docs.py` 退出 0 | 机 |
| 3 | 界面权威 | `design/src/`；构建产物是 `design/skillmaster-首页演示.html` 与 `design/skillmaster-控制台演示.html`（入库）。`artifacts/design/` 是检查脚本的截图，被 gitignore，不入库、不作源 | 在 `design/` 跑 `npm run build:home && npm run build:console` 之后，`git status` 对这两个 html 是干净的。**过程规则见 [`design.md`](design.md)** | 机 |
| 4 | 验证命令 | 见下面「命令表」 | 每条在各自目录里跑得起来 | 机 |
| 5 | 环境 | Docker Postgres，容器 `skillmaster-postgres`（`compose.yaml`）；每个 worktree 一个库 | 容器在跑、库连得上 | 机 |
| 6 | 编号 | 版本 `vN`；ADR 四位递增；Flyway `V{N}` | 能算出下一个空号，且不与主干 + 活动分支撞 | 机 |
| 7 | 交付边界 | 一个版本一个绿 PR；合并、撞号改号、集成后重出、全量回归、部署归集成与发布 | —— | 人 |

### 命令表（第 4 条）

| 验什么 | 命令 | 在哪个目录 |
|---|---|---|
| 文档结构、相对链接 | `python3 .claude/skills/docs-architecture/scripts/check_docs.py` | 仓库根 |
| 引用是否都指得到 | `python3 scripts/docs/impact.py --check` | 仓库根 |
| 生成视图是否最新 | `python3 scripts/docs/generate.py` | 仓库根 |
| 文件行数 | `node scripts/check-file-size.mjs` | 仓库根 |
| 服务端 | `./mvnw verify` | `skillmaster-server/` |
| 控制台 | `npm test`、`npm run type-check` | `skillmaster-console/` |
| 官网 | `npm test`、`npm run type-check` | `skillmaster-site/` |
| CLI | `go test ./...` | `skillmaster-cli/` |
| 设计件 | `npm run build:home`、`npm run build:console`、`npm run type-check` | `design/` |

## 补充约定

### 文档

- 五个轴与版本化：见 `.claude/skills/docs-architecture/SKILL.md`（v1–v39 冻结；v40 起 delta；`docs/current/` 是现状的唯一权威）。
- `docs/current/TODO.md`：开版把条目搬进 PRD 并删掉，交付后删；新待办写「是什么 / 为什么现在不做 / 下一步 / 记于」。

### 控制台与 CLI

每一处控制台操作都要有对应的 CLI 命令（[ADR 0081](../docs/decisions/0081-every-console-operation-has-a-cli-command.md)）。

- **PRD 阶段就写**：版本 `prd.md` 带一张「控制台操作 → CLI 命令」映射表；新增一处控制台操作时，同一条需求里写清命令、参数、确认与输出形状。写不出来，说明需求没定完。
- **例外只有两类**：
  - **高风险**——不可逆，或改变「谁能看见 / 调用」：删除我的标签定义、删除 skill、删除 / 撤销版本、把可见性改成公开或私有。留在控制台，用弹窗或 `--yes` 确认（[ADR 0056](../docs/decisions/0056-dangerous-actions-are-confirmed-in-a-modal.md)）。
  - **产品负责人逐条确认的**——目前只有「为你推荐」流（[ADR 0080](../docs/decisions/0080-cli-covers-non-high-risk-operations.md) §决定 3）。要不要豁免由用户确认，不由实现者判断。
- **例外必须显式记录**：写进当版 PRD 的「不做」，写明是什么、为什么、哪天由产品负责人确认。没有这三项的不算例外，按第一条补 CLI。
- **判据**：交付前逐行对映射表——每一行要么有命令，要么是一个记在案上的例外。**静默省略按漏项处理。**

### 前端

- 从设计件搬结构 / 类名 / 数值 / 文案；只改「挪进框架」那一层。
- 一个文件一件事；页面只做状态机 + 编排；纯模块不 import React；依赖单向；不造 barrel。
- 行数上限以 `scripts/check-file-size.mjs` 的 `LIMITS` 为准（当前：页面约 600、组件约 400）。
- 设计检查的审批闸与 `DESIGN_CHECKS_APPROVED=1` 的禁令见仓库根 `CLAUDE.md`；这里不重复。
- `qa/role-matrix` 只在代码收口后跑；写代码期间不跑；不为了让绿而改它。

### 并行：共享面

同一时刻只允许一条分支改：

| 共享文件 | 被谁用 |
|---|---|
| `design/src/console/components/SearchBox` | Skills / Discover / MyTags / Publish |
| `Pager`、`FilterChips`、`MenuMultiSelect` | Skills + Discover |
| `CommandMenu` | Skills + SkillPage |
| `MenuSelect` | Skills + Publish + SkillMenu |
| `design/src/console/fixtures/library.ts` | 四个页面 + shell + 多个组件 |
| `design/src/console/fixtures/skill.ts` | SkillPage + 一组 skill 组件 |
| `design/src/console/styles.css` | 全控制台一份 |
| `ConsoleApp.tsx`、`address.ts`、`demo.ts`、`shell/`、`shared/auth/` | 外壳与认证 |

生成物：`design/skillmaster-控制台演示.html`、`design/skillmaster-首页演示.html`。冲突不要手工解——取任意一份，重跑 `build:console` / `build:home`。

**检查组不等于安全单位。** `console-states.mjs --only` 的组名以脚本的 `GROUPS`（或 `--list`）为准，本文不抄。`list` 与 `discover` 共用 `Pager` / `FilterChips` / `SearchBox`，跑不同的组不能当作可以并行的证据。判定只看拥有面。

### 并行：本地环境（一库一栈）

```bash
# 每个 worktree 一份 .env（.env 不进 git）
cp ../skillmaster/.env .env

docker exec skillmaster-postgres psql -U skillmaster -d postgres \
  -c 'CREATE DATABASE skillmaster_vNN;' \
  -c 'CREATE DATABASE skillmaster_test_vNN;'
```

`.env` 里加两行（`.env.example` 没有这两个键，但服务端与测试都读）：

```
SKILLMASTER_DB_URL=jdbc:postgresql://localhost:5432/skillmaster_vNN
SKILLMASTER_TEST_DB_URL=jdbc:postgresql://localhost:5432/skillmaster_test_vNN
```

- 测试库可抛弃：测试每次重建 schema；撞号改完直接重建这个库，不要修 Flyway 历史。
- **浏览器验证一次只开一套**：vite 的 API 地址写死 `8080`，cookie 不按端口隔离。其他 worktree 只跑单测、类型检查、设计检查。
- `node_modules` 每 worktree 一份（`npm ci`），不做软链——分支可能加依赖。
- 项目记忆目录按**项目路径**命名，换 worktree 就是另一个键，默认读不到那份 `MEMORY.md`。先拷一份索引，不要软链——两个会话同时写会丢更新。

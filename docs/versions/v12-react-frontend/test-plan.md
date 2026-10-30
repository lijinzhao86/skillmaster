# v12-react-frontend · 测试方案

> **最后更新**：2026-10-22
> **所属版本**：[`README.md`](README.md)

## 验收映射

把 [`prd.md`](prd.md) §验收与指标 的**每一条**映射到具体用例。

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #1 `design/` 与 `styles.css` 零 diff | T0 | 通过（含一次设计修正，见 §结果 T0） |
| #2 每个页面的 DOM 骨架逐字一致 | T1 | 通过（P0–P5 的全部地址） |
| #3 24 个测试文件保留、断言不减少 | T3 | 通过（25 个文件 / 493 条） |
| #4 类型检查与构建通过，产物能真服务一次 | T4、T5 | 通过 |
| #5 CLI 层 84 个用例 | T6 | 通过（84 passed） |
| #6 `/web/**` 的 rung 视图 + 5 个门 + 2 个生命周期 | T7 | 通过（38 passed，含对照台自检 4 条） |
| #7 控制台 2 个屏 × 6 档 = 12 个用例 | T8 | 通过（12 passed） |
| #8 六档截图并排看，人判定一致 | T9 | 通过（人判定） |
| #9 页面地址、查询参数、跳转方式一致 | T2 | 通过（23 个地址） |
| #10 包体测量并如实记录 | T10 | 通过（JS +35,749 B gzip） |

**每条验收都有用例**；本版的特殊之处是这些用例量的都是「和以前一样」，所以**基线必须在动手前先量一次**（见 §环境与数据）。

## 用例

### T0 · 样式与设计稿零 diff

- **前置**：**P0 已把 `styles.css` 从 `references/` 搬回 `skillmaster-web/src/`**——在此之前 T0 必然红（文件不在，`git diff` 会把 6,087 行算成删除），那不是样式被改了。
- **步骤**：`git diff main -- design/ skillmaster-web/src/styles.css`
- **期望**：输出为空。
- **判定**：空 = 通过。非空 = 失败，且**必须查清原因**——本版内不接受任何样式改动；若期间发生一次真实的设计交付，按 [ADR 0075](../../decisions/0075-the-browser-client-is-react.md) §后果 的边界停下来重新界定基线，而不是直接判失败。

### T1 · DOM 骨架对照（每个页面）

- **前置**：对照台已建（TD §附录 A）；`qa/role-matrix` 的夹具已 seed；两个 dev server 在跑——**React 在 5173（`qa/role-matrix/.env` 的 `ROLE_MATRIX_SERVER` 指着它，不许改）、冻结副本在 5174**（它自己的 `vite.config.ts` 写死 5173，必须显式 `--port 5174` 覆盖）。
- **步骤**：对每个页面、每个角色：两套实现各开一次同一地址，取骨架（标签名 / 类名 / `id` / `name` / `role` / **`aria-*`** / **`hidden` / `disabled` / `checked` / `selected` / `value` / `type`** / 嵌套顺序）做 diff；再取两边的文本节点（空白归一化）与**属性文案**（`placeholder` / `title` / `alt` / `aria-label`）各做一次 diff。
- **期望**：三份 diff 都为空。role-matrix 有断言的是 **2 个屏**（版本列表 / 上线交接），其余页面按 TD §分期各自搬完时补一次。
- **判定**：任何一处非空都算失败。**差异归因之前不要改代码**——先确认是漏了包裹层，还是 Vue 与 React 对空节点/注释的处理不同（后者要在对照台里显式归一化，并写进 TD）。

### T2 · 地址表逐条走查

- **前置**：P5 完成（`/account` 与 `/skills/...` 分别是 P5 与 P3/P4 才落的）。
- **步骤**：逐条打开 [TD](technical-design.md) §接口 列出的那几类地址，包括带查询参数的形态（`?tab=versions&version=…&sub=actions&bump=…`、`/login?return_to=…`）。
- **期望**：每一条都落在同一页、带同一组参数、由同一种跳转（整页加载）到达；刷新后仍在原处。
- **判定**：任何一条落到不同页面或需要额外点击才到，即失败。

### T3 · 测试搬家不丢断言

- **前置**：动手前先量一次基线：每个测试文件的断言条数。
- **步骤**：对每个 React 版测试文件，逐条指出它对应原 Vue 测试的哪一条断言。
- **期望**：24 个文件全部存在；总断言数 ≥ 基线；没有任何原断言被静默删掉或合并。
- **判定**：缺文件、断言数下降、或有一条原断言说不清去处，即失败。

### T4 · 类型检查与构建

- **前置**：`npm ci`。
- **步骤**：`npm run type-check`；`npm run build`。
- **期望**：两条退出码都是 0；构建产物落在 `dist/`。
- **判定**：任何类型错误或构建失败即失败。**不允许用 `@ts-ignore` / `any` 换绿**——那是把错误藏起来。

### T5 · 产物能真服务一次

- **前置**：T4 通过。
- **步骤**：`npm run preview`（或按生产形态由反代服务 `dist/`），用真实浏览器打开 `/login` 并完成一次登录。
- **期望**：页面正常渲染、登录成功、跳转后仍在应用内。
- **判定**：构建通过但跑不起来，是本仓库历史上真实发生过的失败形态，所以单列。

### T6 · CLI 层（12 个探针 + 2 个额外用例——1 个写路径、1 个上线交接，各 × 6 档 = 84 个用例）

- **前置**：本地服务在 8080；`qa/role-matrix` 的 `.env` 与夹具就绪。
- **步骤**：`cd qa/role-matrix && uv run pytest tests/test_cli_matrix.py`
- **期望**：与迁移前相同的通过数。
- **判定**：任何一条由通过变失败即失败。**注意**：这一层不经过前端，它是**回归的对照组**——如果它变红，说明改动漏到了不该动的地方。

### T7 · 浏览器面（`/web/**`）

- **步骤**：`uv run pytest tests/test_web_matrix.py`
- **期望**：同 T6。
- **判定**：同上。

### T8 · 控制台（2 个屏 × 6 档 = 12 个用例，真 Chrome）

- **前置**：React 版可通过 dev server 访问（**5173，即 `.env` 里 `ROLE_MATRIX_SERVER` 指的那一个**）；Chrome 已装。跑之前先确认打到的是 React 而不是冻结副本——打错了这条证据就是假绿。
- **步骤**：`uv run pytest tests/test_ui_matrix.py --ui`
- **期望**：与迁移前相同的通过数（12 个用例全过）。
- **判定**：任何一条由通过变失败即失败。**这一层是本版最有力的证据**：它按类名和按钮文案断言，完全不认框架，所以它通过就等于「**这 2 个屏**的交互没变」。
- **它不覆盖的**：其余页面**没有**浏览器级断言，那些页面的「没变」只由 T1 的骨架与文本 diff 证明。别把这一层的 12 个用例当成全部页面的证据。

### T9 · 截图并排（人判定）

- **前置**：T8 跑完，`output/` 里有六档截图。
- **步骤**：把迁移前后的同一档截图并排放在一起看。
- **期望**：人判定一致。
- **判定**：**只有人能判**。断言全绿不等于视觉一致——[ADR 0075](../../decisions/0075-the-browser-client-is-react.md) §后果 说明了「测试未必发现」，把视觉交给人的分工写在仓库 `CLAUDE.md` 与 [dev-sop](../../../.claude/skills/dev-sop/SKILL.md) 阶段 6。

### T10 · 包体测量

- **步骤**：迁移前后各跑一次 `npm run build`，量 `dist/assets/*.js` 的 raw 与 `gzip -9`。
- **期望**：记录实数并写进 §结果。
- **判定**：不设阈值（这一版不是优化包体），但**必须记录**。预期净增约 43 KB gzip 是**运行时差值**（ADR 0075 附录）；构建产物的增量以这次实测为准，两者不是一回事。

## 环境与数据

- **环境怎么造**：
  1. 服务端 8080（本地库，`SKILLMASTER_SMS_ACCEPT_ANY_CODE=true`）；
  2. `cd qa/role-matrix && uv run python -m role_matrix.seed`（夹具存在则原样复用）；
  3. **React 版 dev server 5173**（`qa/role-matrix/.env` 的 `ROLE_MATRIX_SERVER` 指着它，**不许改**），**冻结副本 5174**（`cd references/vue-frontend && npm run dev -- --port 5174`；**这一侧 2026-10-22 已随 P6 删除**——重跑前先 `mkdir -p references/vue-frontend && git archive 34df944^ skillmaster-web | tar -x --strip-components=1 -C references/vue-frontend` 再 `npm install`。已经量过的 T1 / T2 / T9 不受影响）——两者代理配置同源（`/web`、`/api/v1`、`/oauth`、`/.well-known`、`/gateway`、`/cli-sign-ins`），**这是对照台成立的前提**。
- **数据**：`qa/role-matrix` 的六角色 + 两个 skill（夹具只读、临时 skill 承接所有写入）。**数据不在本版里变**，所以迁移前后的对照可比。
- **基线**：**动手前必须先量一次并记下来**——`qa/role-matrix` 三层的通过/跳过数、`skillmaster-web/tests/` 每个文件的断言条数、`dist` 的 raw/gzip、六档截图。没有基线，「没有减少」就无法判定。
- **不跑 `design/scripts/` 的两套检查**：`console-states.mjs` 与 `interaction-check.mjs` 接收的是**静态演示文件的路径**（内部走 `file://`），驱动的是 `design/skillmaster-*.html`，**不是产品**——产品换成 React 之后它们原样能跑，但证明不了产品。另外 `check-all.mjs` 的**默认无窗口跑法**会重出与已跟踪那批（有窗口出的图）不同的 PNG，**可能**让验收 #1 变非空；单跑 `--only` 并把输出目录指到 `/tmp` 则不会碰 `design/`。无论哪种，它们都只证明演示件。本版的产品级回归由 `qa/role-matrix` 与对照台承担。
- **清理**：不新增任何持久数据。Vue 版 dev server 与 React 版 dev server 各自关闭即可；`qa/role-matrix` 的临时 skill 本来就会自己复原。

## 结果

**按分期记。P0–P5 已执行的部分写在下面，其余仍未执行**——一行「未执行」不能盖住已经量过的那些，一条「通过」也不能代表整页整档。

| 用例 | 结果 | 证据 |
|---|---|---|
| T0 | 通过（含一次设计修正） | 提交 `2468da1` 之后量：`git diff main --stat -- design/ skillmaster-web/src/styles.css` → **4 files changed, 24 insertions(+), 0 deletions(-)**——`design/README.md` +1、`design/demos/dir3-hara-console.html` +6、`design/skillmaster-控制台演示.html` +6、`skillmaster-web/src/styles.css` +11。非空的那一份**正是** prd 验收 #1 下 2026-10-22 记的那次空态修正（`.skills:empty{display:none}`，外加产品侧的 `.empty{margin:0}`），**没有第二处改动**：三条都是新增行，零删除。设计件那份是构建产物，与 `build-demo.mjs` 的一遍重出逐字节相同。**提交之前这一条不可判定**（`skillmaster-web/src/` 未跟踪时 git 把旧文件读成整份删除）；现在可判定，结论是「除那一次修正外确实没动过」 |
| T1 | 通过（P0–P5 的全部地址） | 无壳五页 `/login`、`/reset`、`/register`、`/consent`（带 `client_id`/`state`/`scope`）、`/cli-sign-in`（带四个参数与 `name`）：骨架 / 文本 / 属性复制三份 diff **全空**。P2 的骨架是 `.topbar` + `.sidenav` 两段，在 `/`、`/account`、`/account/keys`、`/account/sessions`、`/skills/matrix-owner/role-matrix-fixture` 五个地址上**都是零 diff**，另加 `/account/keys` 的**两个展开态**（`权限` 子项原地展开 + 账户菜单打开）也零 diff。对照对象是 5174 上的冻结 Vue 副本，两边都已登录。`.content` 里的页面内容 P4 起才换，所以 P2 比的是外壳。**P3 的列表页**（`/`、`/?q=matrix`、`/?sort=name`、`/?show=deleted`）是整页对照：三份 diff **全空**，登录态。**P4** 在同一夹具上比了 12 个地址：`/skills/…`（信息）、`?tab=files`、`?tab=versions`、`?tab=versions&version=1.0.2`、`?tab=versions&version=<草稿>`、`?sub=diff`（两处）、`?tab=settings` 与其 `sub=collaborators` / `sub=details`、`…/new`、`…@<草稿>/edit`——**全部零 diff**。**P5**：`/account`、`/account/keys`、`/account/sessions` 三个地址零 diff；两次登录都在截图之前完成，否则「登录设备」会因为对照台自己多登一次而多一行 |
| T2 | 通过（23 个地址，逐条两套实现对照） | 2026-10-22 走了一遍，React 在 5173、冻结的 Vue 副本在 5174、服务端 8080、同一账号（owner）、同一夹具 `matrix-owner/role-matrix-fixture`。**未登录 6 条**：`/login`、`/login?return_to=%2Faccount%2Fkeys`、`/register`、`/reset`、`/consent?client_id=…&state=s1&scope=skills%3Aread`、`/cli-sign-in?client_id=…&state=s1&scope=…&code_challenge=abc123&code_challenge_method=S256&name=MacBook`（这一条在两套实现里都落到 `/login?return_to=%2Fcli-sign-in%3F…`，连百分号转义都一样）。**登录后 17 条**：`/`、`/?q=role`、`/?sort=name`、`/?show=deleted`、`/?p=1`、`/account`、`/account/keys`、`/account/sessions`、`/skills/<夹具>`、`?tab=files`、`?tab=versions`、`?tab=versions&version=1.0.2`、`?tab=versions&version=1.0.2&sub=actions&bump=minor`、`?tab=settings`、`?tab=settings&sub=collaborators`、`?tab=settings&sub=details`、`/new`。每条都记三样：落到的地址、画出来的那一屏（`h1` + 当前标签 + 行数 + 有没有外壳/对话框 + 空态那句）、**刷新之后还在不在原处**。**23 条全部一致**：落到的地址逐字符相同，屏幕签名相同，刷新后地址与屏幕都不变（两套实现、23 条，无一例外）；都是整页加载到达，没有一条需要额外点一次。**一处字符串差异，不是屏幕差异**：版本详情的「首次上线 2026/10/21 22:14」，Vue 的文本节点是「首次上线 」带一个尾空格、React 没有——因为 Vue 模板里插值与 `<span>` 之间有一个换行会被压成一个空格，而 JSX 会把带换行的空白整个丢掉。那个 `<span class="time">` 两边都是 `display:block`（`.vp-timeline .time`），所以这一格空格落不到像素上，日期本身逐字相同。验收 #2 的文本 diff 本来就是「空白归一化后」比的，这一处不构成差异。**一条没走到**：`/@<草稿>/edit`——夹具今天没有草稿（版本页里没有 `a[href*="@"]…/edit`），所以这一条没法重走；T1 在 P4 有草稿时比过它，结论在那里的表里 |
| T3 | 通过（P0–P5 的 25 个文件 / 493 条） | `npm test`：**25 files / 493 tests** 全通过（Vue 基线 24 个文件；`skills.test.ts` 按列表/详情拆成 `skills-list` + `skills`，所以文件数多一个，断言数合计不变）；`expect(` 共 **1,410** 条，迁移前基线 **1,375** 条（`skills` 拆成 `skills-list` + `skills`，合计与原文一致；`app` 由 P0 的路由表起步、P5 补齐渲染与外壳）。已搬：`support/client/errors/markdown/time/validation` 原样，`session/app`（P0 重写）、`captcha/countdown`（renderHook）、`components`、`consent/login/cli-sign-in/reset-flow/register-flow`（RTL）、**`shell`**（P2，RTL）、**`skills-list`**（P3，RTL；`skills.test.ts` 的列表一半）、**`skills`**（P4，`one skill` 的 36 条）、**`versions`**（46）、**`version-editor`**（32）、**`skill-transfer`**（35）、**`account`**（3）、**`keys`**（36）、**`sessions`**（17）、**`app`**（13 条路由/渲染 + 24 条从 Vue 版整体搬来的地址断言） |
| T4 | 通过 | `npm run type-check`（`tsc --noEmit`）无输出；`npm run build` 成功：`index.js` **545.77 KB（gzip 174.86）**、`index.css` 76.96 KB（gzip 13.64）（2026-10-22 结构重构后重量；重构前 544.95 / 173.62——拆成模块的代价约 1 KB raw / 1.2 KB gzip）。**这个包体是全部 24 个地址的实测**，T10 要等全量后再记 |
| T5 | 通过 | `vite preview`（4173，跑的是 `npm run build` 的产物）完成一次真实登录：401 的 `/web/session` 经预览服务器的代理回到服务端，登录后落到 `/`，`.sidenav` 在、`h1` 是「我的 skill」 |
| T6 | 通过 | `uv run pytest --ui` 的 CLI 层 **84 passed**（12 个读探针 + 写路径 + 上线交接，各 × 六档） |
| T7 | 通过 | `/web/**` 那一层 **38 passed**（rung 视图 1 + 5 个门 × 六档 + 2 个生命周期） |
| T8 | 通过 | 控制台层 **12 passed**：版本列表与上线交接两屏 × 六档角色，驱动的是 5173 上的 React 实现。三层合计 **134 passed**；另有对照台的 4 条自检（先前因为两侧 dev server 未同时在线是 skipped，这次 **4 passed**）。**2026-10-22 收口后的独立验收复跑**：`uv run pytest --ui -q` → **134 passed / 4 skipped in 32.83s**（CLI 84 + `/web` 34 passed / 4 skipped + 控制台 12 + 对照台自检 4；4 条 skip 的原因是 `tests/test_web_matrix.py:36`「allowed 那一侧跑在 scratch skill 上」） |
| T9 | 通过（人判定） | `qa/role-matrix/output/pairs/`：六个档位 × 两屏（版本列表、上线交接），左列是迁移前（冻结的 Vue 副本，5174）、右列是迁移后的 React（5173），同一账号、同一夹具、同一地址。拼图 `t9-list-six-rungs.png` / `t9-handoff-six-rungs.png`，可放大的原页 `list.html` / `handoff.html`，单张 `{react,vue}-<档>-{list,handoff}.png`。**2026-10-22 用户看过并判定「没问题」** |
| T10 | 通过（已记录实数） | 两套实现都在本机重跑了一次 `npm run build`，量 `dist/assets/`（`wc -c` 与 `gzip -9 -c \| wc -c`）：**JS** Vue `398,442` B raw / `136,778` B gzip → React `546,371` / `172,527`，净增 **+147,929 B raw（+37.1%）／+35,749 B gzip（+26.1%）**；**CSS** Vue `76,925` / `13,500` → React `76,961` / `13,510`，净增 **+36 B raw / +10 B gzip**——就是空态那两条规则，除此之外样式一个字节没动。口径：`gzip -9`（不是 vite 自报的那两个数，它按 kB=1000 报 `175.07` / `140.07`）。**与 ADR 0075 附录的约 43 KB 不是一回事**：那是 React 与 Vue **运行时**的差（68.9 vs 25.6 KB gzip），这里是整个应用的构建产物，两边的业务代码编译出的大小也不同，所以少了 7 KB。判定本来就不设阈值，这一条只要求如实记录 |

P0–P4 期间回归到测试里的**实现**问题，都已改在源头：

- P4：`CommandMenu` 丢了 Vue 的 `v-if="group.length > 0"`，空组会画出一条 `.menu-group + .menu-group` 的 hairline（只有浏览档位的行菜单才触发，对照台用 owner 看不到）；`SkillPage` 在 `await` 之后从渲染期赋值的 ref 读 `skill`，React 把 `setSkill` 的提交排在续体之后——待处理申请计数在 设置/版本 页从未发出，且读取顺序被打乱；
`useCodeRequest.requireCaptcha` 原本每渲染一次换一个身份，而注册页把它放进 effect 依赖——Vue 的 `onMounted` 在 React 里变成了每次渲染都问一次「下次发送要不要验证码」；`tests/setup.ts` 补上 `cleanup()`，因为 `@testing-library/react` 只在 `globals: true` 时自己注册卸载，而这个项目没有开。

**2026-10-22：`/?show=deleted` 的空态被空列表顶到帧脚（用户看图指出；设计件同一个问题，先改设计）。** 原因：`.list-body` 与 `.skills` 都是纵向 `flex`，而 `.skills{flex:1}` 是为「十条行分掉剩下的高度」写的——一行都没有时它做的是反事，把 `.empty` 那句话推到帧脚。改法：设计件加 `.skills:empty{display:none}` 并写进 `design/README.md`；产品照同一条规则，另补 `.empty{margin:0}`（设计件的全局 `p{margin:0}` 产品没有）。**证据**：设计件（1280×900）改前页签底 140、句子顶 623（相隔 483px），改后句子顶 140（相隔 0）；产品 `/?show=deleted` 与 `/?q=zzz` 改后同样是 140/140。`node design/scripts/console-states.mjs … --only list` 全绿、产物与源 `diff` 为空；`tests/skills-list.test.tsx` 11 条通过；列表页对照台四个地址三份 diff 仍全空（CSS 规则不进 DOM 快照，所以这一处只能靠量几何，不能靠对照台）。

**2026-10-22：结构重构（地址收口进 `address.ts`；`formatSize` 合一份；账号页拆成 `KeyList` / `KeyCreateForm` / `KeyMoment` + `keyModel`；编辑器拆成 `EditorFileTree` / `EditorHeader` / `EditorNotices` / `EditorPane` + `editor/files.ts` / `editor/treeState.ts`）。** 逐项验过：`npx tsc --noEmit` 干净；`npx vitest run` **25 files / 491 tests** 全通过；四个对照台脚本（列表、外壳、skill 12 个地址含编辑器、账号三页）**全部零 diff**——这几步当时拿对照台当了逐步护栏；**2026-10-22 起不再这么做**（`qa/` 不进开发循环，见 `dev-sop` §四），本次的验收以收口后单独跑的那一次为准，**该次尚未跑**；T2 地址走查 23 个地址复跑仍一致；`npm run build` 通过（见 T4）。**DOM 没有变**，所以这一节不需要新的用例。

**2026-10-22：代码循环审计（迁移态 + 结构重构后）。** 三路独立审计（页面/外壳、组件与模块、测试搬家），首轮 3 个代理、复核轮回到同一个代理。逐条核实后的处置：

- **high 1 条，已修**：密钥那一刻「复制」与「显示/隐藏」的竞态——复制在剪贴板往返期间被点眼睛，`copied` 会因对象身份变化被丢掉（Vue 原地改对象，所以基线没有这个问题）。`markMoment` 改为按 token 认「同一刻」。
- **medium 3 条**：① 测试里 `disabled` 断言可用 `undefined` 蒙混（已改成「存在且 disabled」）；② 列表页搜索/排序后先渲染旧页再归 1、以及 `?p=` 越界时页面夹紧但地址不纠正（已把归 1 放进同一次更新、渲染期夹紧，并把「列表到达」加进夹紧 effect 的依赖——Vue 的 `watch([matched, pages])` 正是在那时纠正）；③ 「低档位打开 `?tab=settings` 不纠正」——**判为不成立**：用冻结副本自带的 `vue` 跑反应式探针，rung 不变时 Vue 的 `watch(tabs)` 触发 0 次，React 与之一致；这是两边共有的旧缺陷，记进 [`known-issues.md`](known-issues.md)。
- **low 9 条**：`CommandMenu` / `MenuSelect` 的 document 监听器因渲染期函数身份不一致而只加不减（已改为稳定身份）；另 5 条「否定断言在元素缺失时空过」经核实均被相邻正向断言兜住、1 条 singleton 测试已由 `shell`/`app` 覆盖、2 条注释漂移已改。
- 验证：`tsc --noEmit` 干净；`npx vitest run` **25 files / 493 tests** 全通过（新增 2 条回归用例，各自做过变异验证：把修复改回去，用例转红）；`npm run build` 通过（546.37 kB / gzip 175.07）。**qa 未跑**（独立验收，等指令）。

## 已知问题

本版尚未实现，无已知问题。本版范围外的历史缺陷如果发现，另记 `known-issues.md`（本版尚未创建）。

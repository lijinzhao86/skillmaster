# v12-react-frontend · 技术设计

> **最后更新**：2026-10-21
> **状态**：设计稿（**尚未实现**）
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文**只链接、不复述理由**。选型依据见 [ADR 0075](../../decisions/0075-the-browser-client-is-react.md)。

## 架构

**一句话：旧实现冻结到仓库根、不进版本库的 `references/vue-frontend/`，`skillmaster-web/src/` 清空交给 React；逐页搬，最后一次切换。**（**2026-10-22 已删除**：对照台的基线已经取完、结论在 [`test-plan.md`](test-plan.md) §结果；要重跑就取 `34df944^` 那一版的 `skillmaster-web/`）

不把两套实现并排放在 `skillmaster-web/` 里，是因为那会立刻产生两份 `styles.css` 和两份入口——而「样式只有一个权威」正是这次迁移唯一不能破的东西。也不新建 `skillmaster-web-react/`，理由同上：项目目录保持 `skillmaster-web` 一个，换了跑道不等于换项目。

**旧代码放在 `references/` 下，而不是留在 `src/` 旁边。** 那个目录本来就是这个仓库的「只用来读的原材料」区（已在 `.gitignore` 里，见根 `.gitignore` 的注释），放一份冻结的前端副本是同一种用法：可读、可跑、随时可删，**不留任何东西需要维护**。它的历史价值在 git——`git show main:skillmaster-web/src/...` 永远拿得到。

```
skillmaster-web/
├── index.html                 ← 已指向 /src/main.tsx（P0 之前跑不起来，这是预期）
├── vite.config.ts             ← P0 换成 @vitejs/plugin-react
├── package.json               ← 一份；P0 换依赖，P6 收敛
├── src/                       ← 新实现，P0 起
│   ├── styles.css             ← **从 references 搬回来，之后零 diff**（6,087 行，唯一不变量）
│   ├── api/                   ← 从 references 搬回来，零改动（1,973 行，不 import vue）
│   ├── rungs.ts  time.ts  validation.ts  version.ts  markdown.ts  login.ts   ← 同上（793 行）
│   ├── main.tsx
│   ├── App.tsx                ← 路由表（手写 switch，不加依赖）
│   ├── AppShell.tsx
│   ├── store.ts               ← 模块级 store + useSyncExternalStore
│   ├── address.ts             ← 地址的读 / 拼 / 改写 / 整页跳（见下「地址收口」）
│   ├── format.ts              ← 数字的读法（`formatSize`，原先四份）
│   ├── editor/files.ts        ← 编辑器的纯逻辑：树、路径、脏文件（React 无关）
│   ├── editor/treeState.ts    ← 文件树的界面状态形状（页面与组件共用）
│   ├── hooks/
│   ├── pages/                 ← 两个长页面现在只剩状态机与编排
│   └── components/
│       ├── key/               ← KeyList / KeyCreateForm / KeyMoment / keyModel（凭据那三屏）
│       └── version/           ← EditorFileTree / EditorHeader / EditorNotices / EditorPane
└── tests/                     ← React 的测试；5 个与框架无关的原样搬回（`captcha` 随 `useCaptcha`、`session` 随 `store.ts` 重写）

references/                    ← **不进仓库**（根 .gitignore 已含）
└── vue-frontend/              ← 冻结副本：src/ + tests/ + 入口与配置，可独立 npm install 跑起来
```

**2026-10-22：迁移完成后按用户决定做了一次结构与去重（ADR 0075 §后果 说的「迁移期间不改架构」至此解除，理由是迁移本身已逐页验完、对照台与整套测试都能当护栏）。** 三件事，每一件都不改 DOM：

- **地址收口到 `src/address.ts`。** 原先 11 个文件各自 `new URLSearchParams(window.location.search)` 再 `replaceState`/`assign`（24 处读、28 处写），现在只有这一个模块碰 `window.location`。三种写法在模块里各自具名：`addressFor(path, params)`（链接，空查询给裸路径）、`relativeAddress(params)`（相对地址，版本页用）、`addressOrPath(params)`（相对，空则退回当前路径，两个列表页用）——它们不是同一个东西的三种叫法，原先各页对「空查询写成 `?` 还是路径」本来就有分歧，模块化时按各页原有的语义保留。
- **`formatSize` 四份并一份（`src/format.ts`）**，两个签名（可空 / 不可空）并成 `number | null`。
- **两个长页面按「一屏一能力」拆开，页面只剩状态机与编排：**
  - `AccountKeysPage` **1,530 → 631**：`components/key/KeyList.tsx`（328，列表屏）、`KeyCreateForm.tsx`（419，新建屏）、`KeyMoment.tsx`（228，密钥那一刻）、`keyModel.ts`（104，状态与措辞：`stateOf` / `expiryText` / `happenedText` / `scopeLabel` / `LABEL`，以及 `Moment` / `Question` / `Leaving` 三个形状）。
  - `VersionEditorPage` **1,965 → 1,368**：`components/version/EditorFileTree.tsx`（463，文件树）、`EditorHeader.tsx`（156，版本身份与保存）、`EditorNotices.tsx`（110，三条横幅）、`EditorPane.tsx`（111，文本面板），加 `editor/files.ts`（124）与 `editor/treeState.ts`（40）。

**拆出来的接口有多宽，以及为什么还是拆了。** `EditorFileTree` 收 27 个 prop、`EditorHeader` 17 个、`KeyList` 17 个、`EditorPane` 14 个、`KeyMoment` 10 个。这不是为了让调用点好看，而是**把每一屏原来隐式使用的整片页面作用域变成一份可数、可读的清单**：哪些状态它真的碰、哪些动作它真的调。宽度就是这次拆分暴露出来的事实；要收窄它，得把草稿/列表的状态搬进 reducer/context——那是改写，不是搬文件，本版不做。

**还没拆的部分。** 两个页面各自剩下的约 1,300 行与 600 行是**状态机本身**：加载、保存、发布、冲突与重复、离开守卫、文件操作。它们与每一屏共享同一批状态，拆成 hook 需要先定下 reducer 的边界（见上）——`firstRun` 式守卫 21 处也同理，形状各异，能统一的部分不值得为它藏起一个明确的写法。

**共享层（`api/`、`styles.css`、六个纯 TS 模块）只活在 `skillmaster-web/src/` 里，是唯一权威。** `references/vue-frontend/src/` 里那一份是冻结副本的一部分，**不参与构建、不参与类型检查、不参与测试**；它不是第二个权威，是一张照片。唯一的用处是：对照台拿它跑一次取基线，写代码时拿它当抄写来源。P6 整个删掉——**已于 2026-10-22 删除**（97 个文件在 `34df944^` 的历史里一个不少，`node_modules` 不入库）。

**路由仍然是手写的一张表。** `App.tsx` 里的 `routeOf(pathname)` 原样返回今天 `App.vue` 的那个 `Route` 结构——`{page, props?, shell, standalone?}`——页面选择读 `window.location.pathname`，链接是真正的 `<a href>`，跳转是整页加载。**`props?` 不能漏**：skill 路由与版本编辑页都靠它传 `namespace` / `name` / `version` / `mode`，它是路由翻译里唯一需要搬运的载荷。理由见 ADR 0015（由 0075 继承），本版不复述也不修改。

**状态只有一个模块级 store。** 今天的 `useSession` 是「模块级 `ref` + 导出函数」，React 侧的等价物是「模块级变量 + 订阅者集合 + `useSyncExternalStore`」。**这不是一个新架构，是把同一个东西翻译过去**：两边的语义都是「模块单例，刷新页面才重置」。其余状态全是组件内 `useState`。

**数据流不动：**

```
页面/组件 ──→ src/api/*.ts ──→ fetch ──→ /web/** 或 /api/v1/** ──→ Spring
   ↑                                          │
   └──────────── 相同的 JSON 形状 ─────────────┘
```

`src/api/` 是这次迁移**唯一一块不做任何改动的业务代码**（1,973 行，零 Vue 依赖）。它是 Vue 与 React 两个实现之间、以及将来移动端之间真正共用的那一层。

## 数据模型

**不适用。** 浏览器端不持有任何持久状态：

- 唯一的跨页状态是「我是谁」，它的权威与生命周期见 [ADR 0015](../../decisions/0015-web-frontend-stack.md) §理由，本文不复述；
- 其余状态都是组件局部的，随组件卸载消失；
- 没有 `localStorage` / `sessionStorage` / IndexedDB 的使用。

本版**不新增任何存储**，所以没有表、没有迁移、没有清理策略。

## 接口

**本版一个接口都不加、不改、不删。** 这是它能被验证的前提——接口变了，「什么都没变」就不成立。

| 平面 | 路径 | 本版 |
|---|---|---|
| 会话 | `GET /web/session`、`POST /web/login`、`POST /web/logout` | 不变 |
| 凭据 | `/web/keys/**`、`/web/sessions/**` | 不变 |
| 浏览器面 | `/web/skills/**`、`/web/grants/**`、`/web/access-requests/**` | 不变 |
| 作者面 | `/api/v1/**` | 不变 |
| 令牌面 | `/oauth/**`、`/.well-known/**` | 不变 |
| CLI 登录 | `/cli-sign-ins/exchange` | 不变 |

**唯一会变的「接口」是前端自己的路由表**，而它也不变：`/login`、`/register`、`/reset`、`/consent`、`/cli-sign-in`、`/`、`/account`、`/account/keys`、`/account/sessions`、`/skills/<ns>/<name>[@<version>][/files/<path>|/diff]` —— 逐条与今天一致，见验收 #9。

## 分期

**每期的完成判据**分两种，因为前两期交付的是骨架而不是页面：

- **每一期都要求**：`npm run type-check` / `build` / `test` 保持绿——vitest 不做类型检查，Vite dev server 也不做，不写进判据的话类型错误能一路藏到 P6。
- **P0 / P2**：路由表与外壳能选对占位，该期搬的测试全过。**不要求整页 diff 为空**——页面还没搬，比不出来。
- **P1 / P3 / P4 / P5**：这一批**真实页面**在对照台上的**三份 diff（骨架、文本节点、属性文案）都为空**，且它们对应的测试（挂载型重写，以及 `captcha` 这类随依赖重写的）完成并全绿。

**role-matrix 的断言不是每期都适用**——它的控制台层只覆盖 2 个屏（版本列表在 P3 落地、上线交接在 P4 落地），从那时起才跑；CLI 与 `/web/**` 两层不经过前端，任何一期都应保持全绿。

| 期 | 交付 | 验收 | 明确不做 |
|---|---|---|---|
| **P0** | 依赖与插件、`src/` 骨架（`main.tsx` / 路由表 / `store`）、把 `api/`、`styles.css`、六个纯 TS 模块从 `references/` 搬回、**5 个与框架无关的测试 + `tests/support.ts` 原样搬回**、**`session.test.ts` 随 `store.ts` 重写**、**`app.test.ts` 随路由表起步**（只覆盖地址选页，P5 补齐渲染与外壳那部分）、RTL 测试基建、**对照台** | 路由表对三类地址能选出**占位页**（真实页面由 P1–P5 逐个换上）；`npm run type-check` / `build` / `test` 重新变绿；对照台能对占位页出 diff | 不搬任何业务页面；`references/` 只读不改；`captcha.test.ts` 不搬（它测的 `useCaptcha` 依赖 vue，随 P1 重写） |
| **P1** | 无壳五页：`LoginPage`、`RegisterPage`、`ResetPage`、`ConsentPage`、`CliSignInPage` + 它们的表单控件（`CaptchaField`、`CodeField`、`CodeInput`、`PasswordField`、`FieldFeedback`、`FormBanner`）+ 它们用到的 composables（`useCaptcha` / `useCodeRequest` / `useCountdown` / **`useFieldChecks`**） | 这五页的三份 diff 为空；`login`/`register-flow`/`reset-flow`/`consent`/`cli-sign-in`/`code-request`/`countdown`/**`components`** 八个测试文件重写，`captcha` 重写为 RTL 版本（`components.test.ts` 只 import 这六个数控，跟着它们走） | 外壳还没搬，五页在 React 下走 `shell: false` 分支 |
| **P2** | 外壳：`AppShell` + 顶栏 + 侧栏 + 账户菜单 | `shell.test.ts` 重写完成；任一 `shell: true` 的地址在 React 下能进（内容仍是占位） | 不搬列表页内容 |
| **P3** | **列表页**：`SkillsPage` + 它的导入闭包——`CommandMenu`、`NewSkillDialog`、`ImportDialog`、**`versionActions.ts`**（`useVersionActions.ts` 的框架无关上半：`canMaintain` / `canWrite` 等，`ImportDialog` 要用）。**已实施**：`CommandMenu` 的 `defineExpose` → `controlRef` prop、slot → render prop；`ImportDialog` 的 `@input` 截断并入 `onChange` | `skills.test.ts` **拆成两半**（实施时的判断）：列表那 11 条 → `skills-list.test.tsx`，本期的三份 diff 为空；`one skill` 的四节随 `SkillPage` 去 P4。`/?q=`、`?sort=`、`?show=deleted` 三个地址与 Vue 零 diff | `SkillPage` 与版本组件不在这里（见 P4）。**这一行改过 2026-10-21**：原先把 `SkillPage` 放在 P3，但它的**静态导入闭包**是 34 个文件——`VersionDetail` / `VersionFilterBar` / `VersionRow` / `VersionDiffPane` / `VersionActionPanel` / `useVersionDiff` / `ImportDialog` 全在里面，P3 不可能只搬页面而不搬它们。于是「版本列表那一屏的 role-matrix 断言」也随 `SkillPage` 移到 P4 |
| **P4** | **详情与版本**：`SkillPage`、`VersionEditorPage` + `components/version/*`（10 个）+ `DiffFileBlock`、`MarkdownView`、`SkillCollaborators`、`RungMenu`、`VersionPicker` + `useSkillDetail` / `useVersionDiff` / `useVersionActions` 的 hook 部分（**已实施**：三个 hook 落在 `src/hooks/`，与 P1 的四个同址；框架无关的 `versionActions.ts` 落在 `src/`，与 `rungs.ts` / `version.ts` 同址——`composables/` 不再存在） | `versions`/`version-editor`/**`skill-transfer`** 三个测试文件重写（后者 import 了 `ImportDialog`、`VersionDetail`、`VersionFilterBar`、`VersionRow`，都在这一期）；**版本列表与上线交接两屏的 role-matrix 断言从这一期起适用** | 账户三页不动 |
| — | 不搬：`SharingPanel` | 无 | 它自己的文件头就写着「Superseded — no route reaches this component」，全仓没有一处 import 它（v5 起由 `SkillCollaborators` + `RungMenu` 取代）。把死代码搬一遍只是给它换一个框架继续死着 |
| **P5** | 账户：`AccountPage`、`AccountKeysPage`、`AccountSessionsPage` + `ConfirmDialog`；全部页面就位后把 `app.test.ts` 补全（它在 P0 已随路由表起步，这里补渲染、外壳与 standalone） | 三页 + 路由的三份 diff 为空；`account`/`keys`/`sessions`/`app` 四个测试文件重写完成 | — |
| **P6** | 切换 `index.html`、删 Vue 实现与四个 Vue 直接依赖（`vue` / `@vitejs/plugin-vue` / `@vue/test-utils` / `vue-tsc`）、CI 收口、role-matrix 全量复跑、**删冻结副本** | 验收 #1–#10 全部满足；`design/` 与 `styles.css` 零 diff | 不引组件库（那是下一条 ADR） |

**P0 的对照台是本版最重要的基础设施，不是可选件。** 没有它，「视觉不变」只能靠人逐页看，而人看不出来「少了一个包裹层导致某个后代选择器失效」。它的做法见 §附录 A。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 组件层引哪一套：shadcn/ui + Radix、只引 Radix 原语、还是维持手写？ | 倾向 **shadcn/ui 那一系**（源码进仓库、视觉吃自己的 token、不进包体依赖），但它会带来「设计稿还是不是唯一权威」的问题 | 迁移完成后单开一条 ADR 取代 0032；在此之前**不动**。需要先算清楚：引进来会替代 1,665 行里的多少 |
| Q2 | 旧实现怎么处置？ | **已定并已执行**（2026-10-21）：`skillmaster-web` 目录不变，旧 Vue 实现整体冻结到**仓库根、不进版本库**的 `references/vue-frontend/`（64 个 `src/` 文件 + 25 个 `tests/` 文件，与 `main` 逐字节相同），`src/` 清空交给 React | 已确认根 `.gitignore:27` 命中 `references/`，`git ls-files references/` 为空；冻结副本自带 `package.json`，`npm install` 后可独立跑起来（对照台要用）。**2026-10-22：副本已删**（P6），基线已取完 |
| Q3 | 迁移期间功能冻结到什么程度？ | 倾向：**新功能暂停**；bug 修复只改当前还活着的那一份，并在另一份的页面上标记 | 由排期决定，P0 之前定 |
| Q4 | 是否借机把 TypeScript 从 5.9.3 升到 7.x？ | 倾向**不升**（一次只动一个变量）；但 `vue-tsc` 这条约束消失后，升级变成一件便宜的事 | 切换完成后单独一次：升一行、跑 `tsc --noEmit`、看报错量 |
| Q5 | 对照台覆盖到什么粒度：只覆盖 role-matrix **控制台层有断言的那 2 个屏**（版本列表 / 上线交接），还是全部页面？ | 倾向**先按页面覆盖**：2 个有断言的屏必比；其余页面在各自搬完时补一次「搬前 vs 搬后」的骨架，组件级不单独比 | 本版要求每个被搬的页面都有一次骨架 diff（TD §分期每期的验收），role-matrix 只覆盖其中 2 个屏，其余靠 P0 建好的对照台逐个补 |

**不写「待定」**：上面每一条都给了倾向与决定方式。

## 附录 A · 对照台怎么工作

**目标**：把「DOM 骨架是否一致」变成一条可判定的 diff，而不是一次人眼比对。

**做法**（实现在 `qa/role-matrix/role_matrix/compare.py`，自检在 `tests/test_compare_matrix.py`）：`qa/role-matrix` 已经会启动真 Chrome、登录六个角色、按类名断言（`.versions > li`、`.vp-tab.is-current`、`input[name="vp-bump"]`）。对照台复用它已有的登录与夹具，只多一步：

1. 起两个 dev server：新实现在 `skillmaster-web/`（5173），冻结副本在 `references/vue-frontend/`（`npm run dev -- --port 5174`，需先在里面 `npm install`）——两者的代理配置同源，所以打到的是同一个服务端。**这一步从 2026-10-22 起要先把冻结副本取回来**（它已随 P6 删除）：`mkdir -p references/vue-frontend && git archive 34df944^ skillmaster-web | tar -x --strip-components=1 -C references/vue-frontend`，再 `npm install`（实测 99 个文件 / 1.5M，布局与当初一致）；已经量过的基线不受影响，见 [`test-plan.md`](test-plan.md) §结果；
2. 同一角色、同一地址、同一份夹具数据，各打开一次；
3. 从根节点取一棵**骨架**——保留标签名、类名、`id`、`name`、`role`、**`aria-*` 状态属性**（`aria-expanded` / `aria-selected` / `aria-hidden` 等）、**`hidden` / `disabled` / `checked` / `selected` / `value` / `type`**，以及嵌套顺序；丢掉纯样式属性与 `style`。这几类都必须留，因为 `styles.css` 会按它们的值换外观：`[hidden]` 5 条、`:disabled` 37 条、`:checked` 1 条、`aria-*` 7 条。**`hidden` 尤其不能丢**——`styles.css` 自己写着「`[hidden]` wins, wherever it is set」，而 jsdom 看不见这条级联（所以组件测试也看不见）。**`v-show` 不在这张表里**：它编译成内联 `display: none`，所以 `style` 只取 `display` 一项一起比（P4 实测后补的，见附录 C）；
4. **另做两次文本比对**：① 每个元素**自己的直接文本子节点先拼成一条**再空白归一化、逐行比（一个短语在 Vue 里是一个文本节点，在 JSX 里常被插值切成两个；浏览器排版视同一条，元素边界仍然分开两条）——`<textarea>` 跳过，它的文字来自 `value`，Vue 设属性而 React 挂载时还会种一个子文本节点（P4 实测后补的）；② **属性文案**（`placeholder` / `title` / `alt` / `aria-label`）逐个比。两次 diff 都为空才算过。这一条单列，是因为骨架故意丢掉了文本——只比结构的话，改一句同长度的文案两边都发现不了；而 `placeholder=` 30 处、`title=` 23 处、`alt=` 1 处同样是人能读到的话；
5. 两棵骨架做 diff，**diff 为空才算过**。

**为什么骨架里丢掉样式与文本**：骨架要盯的是「结构有没有多一层/少一层」，把样式和文案混进来只会制造噪音；文案由第 4 步单独比对，样式由「`styles.css` 零 diff」保证。

**为什么这能代表视觉**：CSS 本身零 diff，所以 DOM 骨架（含 `aria-*`）就是视觉的全部输入——推导见 [ADR 0075](../../decisions/0075-the-browser-client-is-react.md) §后果，本文不复述。

**限制要如实说**：它证明不了「看起来一样」。伪元素、`nth-child` 计数、flex 布局的细节仍然要人看截图（验收 #8）。

## 附录 B · 与现有代码的关系

| 现有资产 | 处置 | 说明 |
|---|---|---|
| `src/api/`（1,973 行） | **原样保留** | 零 Vue 依赖，是两套实现与将来移动端之间唯一的共用层 |
| `rungs.ts` / `time.ts` / `validation.ts` / `version.ts` / `markdown.ts` / `login.ts`（793 行） | **原样保留** | 同上 |
| `src/styles.css`（6,087 行） | **零 diff** | 迁移的唯一不变量 |
| 39 个 `.vue`（12,237 行） | **重写为 `.tsx`**；旧的曾冻结在 `references/vue-frontend/src/`（2026-10-22 已删，历史在 `34df944^`） | 见附录 C 的对照表 |
| `composables/`（8 个） | 重写为 `hooks/` | `useSession` 改成 store + `useSyncExternalStore`，其余是 `useState`/`useEffect` |
| 17 个挂载型测试（9,360 行） | **重写挂载层，断言逐条保留** | `mount` → `render`，`wrapper.find` → `container.querySelector` / `screen.*` |
| 7 个不直接 import `vue` 的测试文件（920 行） | **其中 5 个原样搬回**（P0，722 行：`client` / `errors` / `time` / `validation` / `markdown`）；**另 2 个要重写**——`captcha.test.ts`（93 行）随 `useCaptcha`（P1）、`session.test.ts`（105 行）随 `store.ts`（P0） | 判据不是「文件里有没有 `import vue`」，而是**它测的东西依赖不依赖框架**：`useCaptcha` 与 `useSession` 都 `import { ref } from 'vue'`，两个测试的断言里都有 `.value`，原样搬进 React 编不过。另 `tests/support.ts`（122 行）是共用脚手架，与框架无关，随 P0 一起搬 |
| `vue`、`@vitejs/plugin-vue`、`@vue/test-utils`、`vue-tsc` | **P6 删除** | 同时删 `@vue/*` 与 `vue` 的锁文件条目 |
| `design/` | **本版内零 diff** | 本版不把它当「改它」的对象，只当「对照它」的尺子；若期间发生真实的设计交付，按 ADR 0075 §后果 的边界重新界定基线 |
| 服务端、CLI、`qa/role-matrix` 的 CLI/Web 两层 | **零改动** | role-matrix 只新增对照台这一层 |

## 附录 C · Vue → React 的逐项对照

计数取自迁移前实测：`grep -o '\b<词>\b' src | wc -l` 与 `grep -o 'v-<指令>' src | wc -l`，2026-10-21，作用于当时的 `references/vue-frontend/src/`（与 `main` 逐字节相同；该副本 2026-10-22 已删，数字是那一次的实测）。**标 ⚠️ 的三项有真实语义差异，是本版最容易出错的地方。**

| Vue | React | 出现次数 | 注意 |
|---|---|---|---|
| `<script setup>` | 函数组件 | 39 文件 | props 用 TS interface 声明 |
| `ref()` / `reactive()` | `useState` | 315 / 7 | `reactive` 的 7 处要保住原来的更新原子性 |
| `computed` | 直接算，重计算才 `useMemo` | 246 | 纯派生值不要 `useMemo` |
| ⚠️ `watch` | `useEffect` | 40 | Vue 的 watcher 默认在**渲染前** flush，`useEffect` 在**绘制之后**。涉及焦点、滚动、选区的逐个核对 |
| ⚠️ `nextTick` | `await Promise.resolve()` / `flushSync` | 53 | 集中在焦点恢复与测试里 |
| ⚠️ `v-show` | **内联 `style.display`**（不是 `hidden`） | 18 | **不能改成条件渲染**——它是保留 DOM 的；也**不能写成 `hidden` 属性**：Vue 写的是内联样式，DOM 不同，而对照台比的是 DOM（P4 里 `edEmpty` / `edUnsaved` / `vp-actions-pane` 等就是这么被对照台抓出来的） |
| `onMounted` | `useEffect(…, [])` | 23 | 其中「读 DOM 尺寸」的要确认绘制后仍成立 |
| `defineProps` / `withDefaults` | TS interface + 解构默认值 | 28 / 4 | |
| `defineEmits` | 回调 prop | 17 | 名字保留语义，不改事件总线 |
| `v-model` | 受控 `value` + `onChange` | 37 | |
| `v-if` / `v-else-if` / `v-else` | 条件渲染 / 三元 / 提前 return | 185 / 42 / 55 | 避开 `&&` 渲染 `0` 的陷阱 |
| `v-for` | `map` + `key` | 32 | `key` 必须与原 `:key` 同源 |
| `v-html` | `dangerouslySetInnerHTML` | **3 次出现 / 1 处指令** | 只有 `MarkdownView.vue` 一处是真正的指令，另两处是注释；来源不变 |
| `defineExpose` | ref prop / `useImperativeHandle` | 2 | React 19 可直接把 `ref` 当 prop |
| `<slot>` | 见右栏 | **3 处** | `AppShell` 默认插槽 → `children`；`CommandMenu` 的 `#trigger="{open, toggle}"` → **render prop**；`DiffFileBlock` 的 `#pending` → `children` + 兜底内容。只有第一处能用 `children` 直接翻译 |
| `Teleport` / `Transition` / `KeepAlive` / `Suspense` / `provide` / `inject` / `defineModel` | — | **全部 0** | 不需要 portal、过渡、依赖注入 |
| `mount()` + `wrapper.find()` | `render()` + `container.querySelector()` / `screen.*` | 17 文件 | 断言逐条对应，不合并 |

**这张表是本版的施工图，也是审计时第一份要核对的东西**——P0 结束时先对它做一次全量复核，避免施工到一半才发现漏了一种语法。

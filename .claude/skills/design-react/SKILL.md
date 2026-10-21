# 设计稿用 React 写（可被前端照搬）

**一句话**：`design/` 里的演示件是 **React 源码**，不是手写 HTML。改设计稿 = 改 `design/src/` 下的组件，然后重新构建出那两个单文件交付件。组件按 `skillmaster-web/src/` 的形状写，前端实现时**整段复制**，只接数据。

**为什么**：设计稿是界面的权威，前端照它实现。两边各写一遍，就一定漂——`ConsentPage.vue` 与设计件同一屏差六处，就是分别写的代价。设计稿用 React 写、按前端的形状写，实现就从「重写一遍」变成「搬过去 + 接数据」。

**适用范围**：任何改 `design/` 的工作——改一屏、加一态、改文案、改样式、加一个交互。**改界面也走这一份**，它是 [`dev-sop`](../dev-sop/SKILL.md) §二「设计稿」那一步的具体做法。

---

## 一、这份设计稿是怎么组装的

```
design/src/console/main.tsx          ← 入口：把 <ConsoleApp> 挂到 #app
design/src/console/ConsoleApp.tsx    ← 地址 → 哪一页；外壳、弹窗、演示条
design/src/console/pages/*.tsx       ← 六个页面，一页一个文件
design/src/console/components/*.tsx  ← 共用的控件与弹窗
design/src/console/fixtures/*.ts     ← 夹具：演示件没有服务端
design/src/console/styles.css        ← 控制台的全局样式
design/src/console/runtime.css       ← 只属于 React 挂载点的一条样式（#app{display:contents}）

design/src/home/main.tsx             ← 入口：把 <HomeApp> 挂到 #app
design/src/home/HomeApp.tsx          ← 营销首页（未登录 / 已登录两态）+ 浮层的开关
design/src/home/styles.css           ← 首页的全局样式

design/src/shared/auth/              ← 两份演示共用的登录浮层
  AuthOverlay.tsx                    ← 浮层、视图切换、焦点、滚动锁
  authModel.ts                       ← 规则、文案、授权模型
  fields/ · flows/ · views/          ← 控件、注册与找回密码的状态机、十一屏
  auth.css

design/demos/dir3-hara-console.html  ← 模板：<div id="app"> + 标记
design/demos/dir3-hara.html          ← 模板：同上
design/scripts/build-demo.mjs        ← esbuild 打包 + 内联 + 图片转 data URI
```

`design/skillmaster-控制台演示.html` 与 `design/skillmaster-首页演示.html` 都是**构建产物**，不手改。构建：

```bash
cd design
npm run build:console     # 模板 + src/console + src/shared/auth → skillmaster-控制台演示.html
npm run build:home        # 模板 + src/home + src/shared/auth → skillmaster-首页演示.html
npm run type-check        # tsc --noEmit
```

模板里两个标记，构建脚本替换（`console` / `home` 是入口名）：

| 标记 | 变成 |
|---|---|
| `<!-- @styles <name> -->` | `<style>` ＋ 打包后的 CSS |
| `<!-- @bundle <name> -->` | `<script>` ＋ 打包后的 JS（React 已内联，`file://` 能直接开） |

**单文件这条承诺没有变**：交付件仍然自包含，双击能开，能发给别人。

---

## 二、组件怎么写：为了「整段复制到前端」

### 1. 一个文件一件事，路径与名字对齐前端

| 设计稿 | 复制到 |
|---|---|
| `src/console/pages/SkillsPage.tsx` | `skillmaster-web/src/pages/SkillsPage.tsx` |
| `src/console/components/version/VersionRow.tsx` | `skillmaster-web/src/components/version/VersionRow.tsx` |
| `src/console/fixtures/keys.ts` | **不复制**（前端从接口拿数据） |

前端的文件名、组件名、目录名就是设计稿的参照系。**不要发明第二套命名**。

### 2. 组件是「props 进、标记出」

- **不碰 DOM**：没有 `document.getElementById` / `createElement` / `querySelector`，除了三处真正属于平台的事（聚焦、测量、剪贴板）。
- **不 import 接口、不 import 会话、不 import 路由**。数据从 props 来，动作从回调走。
- **不在组件里写死夹具**。夹具放 `fixtures/`，页面把它当 props 传下去。

这样复制到前端时，唯一要改的是**谁提供 props**：演示件是 `fixtures/`，产品是接口与 `useState`。

### 3. Props 的形状照前端的类型

能对上 `skillmaster-web/src/api/types.ts` 的，就用那个形状（字段名、可选性、枚举值）。对不上的（演示件独有的计数、夹具日期），在 `fixtures/` 里定义，并写一句「产品今天产不出来」的原因。

### 4. 地址是页面的状态

- 页面状态进地址：`?page=`、`?state=`、`?tab=`、`?version=` …。**发出去的链接要能复现同一屏**。
- 换一屏 = `go(href)`（真跳转）；同一屏改一个事实 = `replace(href)`（`history.replaceState` + 通知）。
- 不要用组件 state 记住「哪一页」；组件 state 只放**这一屏之内**的临时事（菜单开没开、输入框里正在打的字、问句挂在哪一行）。

### 5. 样式：一份全局表，类名与数值照抄

- 只有 `styles.css` 一份。组件不带自己的 `<style>`，也不写 inline 样式（定位菜单那种必须算的除外）。
- 类名、数值、间距、圆角、颜色**从设计稿的 CSS 抄**，不换算、不「优化」。
- 新加一条规则，写在它所属的那一组注释下面，并写清**为什么**——数值本身在规则里，理由在注释里。

### 6. 文案：先看 `voice-spec.md`

一句界面文案的权威是 [`design/voice-spec.md`](../../../design/voice-spec.md)。加一句、改一句之前先看它：名词表、禁用词、按界面类型的句式。**不要自己发明说法**。

### 7. 弹窗走同一个入口

确认弹窗（危险动作、公开确认）、导入、新建 skill 都在 `components/dialogs/store.ts` 里。页面用 `dialogs.openDanger({...})` 这类动作打开它们，**不要为某一页再写一张弹窗**。弹窗的 DOM 是共享的，焦点回收也由它负责。

### 8. 夹具与「产品今天没有的东西」

演示件不连后端，夹具是必要的。规则：

- 夹具放 `fixtures/`，形状照真实数据，**数字要能被自己核对**（计数从列表算出来，不写死）。
- 夹具里出现「产品今天产不出来」的字段（调用次数、假密钥、假会话），在文件头写一句警告与原因。这是设计走在实现前面的地方，**要能被读者看见**。

### 9. 共享浮层：改 `src/shared/auth/`，两份一起变

登录 / 注册 / 找回密码 / 授权请求那一整套是**一个组件** `AuthOverlay`，两份演示都挂它。宿主只通过 props 说话（`open` / `view` / `standalone` / `dismissible` / `consent` / `returnFocus` / `onView` / `onClose` / `onSignedIn`），浮层自己管屏与屏之间的步骤。**不要为某一份演示复制一份浮层**，也不要让浮层直接读 `location`——地址是宿主的事。

改完浮层，**两份演示都要重新构建、都要看一眼**。

---

## 三、改一处，做完这四件事

```bash
# 1 · 构建（源 → 交付件）；动了 src/shared/auth/ 就两条都要跑
cd design && npm run build:console && npm run build:home

# 2 · 逐状态对照：新旧两版渲染出的 DOM 必须一致（改结构时用；只改样式可跳过）
node scripts/dom-compare.mjs <旧产物.html> <新产物.html> --only <状态前缀>   # 控制台，如 keys / editor / version
node scripts/dom-compare.mjs <旧产物.html> <新产物.html> --home        # 首页停在原处

# 3 · 跑改动的那一处检查（不要整套，整套要人批准）
node scripts/console-states.mjs "skillmaster-控制台演示.html" design/shots/console --only <组>
node scripts/interaction-check.mjs "skillmaster-首页演示.html" design/shots/states

# 4 · 请人看
#    Claude 看不到渲染结果；「检查全绿」不是视觉验收。
```

首页那套没有 `--only`：它的流程是点出来的，没有更小的单位（所以它也不设闸）。`--home` 只比首页停在原处的那一屏，浮层里的状态由 `interaction-check` 点着验。

`dom-compare.mjs` 比的是**人看得见的三样**：骨架（标签、类名、id、状态属性、aria，以及输入框当前的值、图标路径 `d`、图片 `src` 的摘要）、文本、属性文案（placeholder / title / alt / aria-label）。它是搬迁与重构的闸；**改设计本身**时它当然会报不同，那时要逐条确认「不同是这次要改的那处」。它按每个状态的宽度渲染（两个窄屏状态是 400px），`--only` 给的是**状态名前缀**、匹配不到会直接失败。

整套检查（`check-all.mjs`）与不带 `--only` 的控制台走查**要人批准**，规则见仓库根 [`CLAUDE.md`](../../../CLAUDE.md)。

---

## 四、复制到前端：一张清单

设计稿改完、产品要跟上时：

1. **复制组件文件**，路径照上表。不要重写——重写就是把漂移请回来。
2. **删掉演示件的那一层**：`fixtures/`、演示条、`?page=`/`?state=` 的假路由、为了单文件而内联的东西。
3. **接上产品的那一层**：接口（`src/api/`）、会话（`useSession`）、地址（`App.tsx` 的路由表）、动作（现有的 hook 或新 hook）。
4. **保留**：类名、DOM 结构、文案、`data-*`、aria、焦点行为、确认弹窗的入口。
5. **补测试**：前端测试按现有的文件组织加，断言行为与文案，不复制演示件。
6. **核对**：`npx tsc --noEmit`、`npm test`；界面请人看一遍。
7. **如果设计稿反过来落后于产品**（产品先做了），把产品那一份搬回 `design/src/`，而不是两边各留一份。

> **反例**：不要「照着设计稿的截图在前端重写一遍」。那正是这一套要消灭的动作。

---

## 五、不许做的事

- **不手改 `design/skillmaster-*.html`**——它是构建产物，下一次构建就没了。
- **不改 `design/demos/*.html`**——它们现在只是模板（`<div id="app">` 加两条标记）。要改行为，改 `design/src/`。
- **不新建「共享片段」**——命令式的那一版（`design/partials/auth-overlay.html`）已随 ADR 0076 删除，浮层的源在 `design/src/shared/auth/`；构建脚本也不再认 `@partial`。
- **不引入组件库 / 路由 / 状态库**——设计稿保持零依赖（React 除外），否则复制到前端的就不是「一段组件」。
- **不在组件里写死一句新文案**——先看 `voice-spec.md`；设计稿没覆盖的态，先问用户，不要自己发明。
- **不把演示逻辑带进前端**——`?state=`、演示条、夹具、`made-*` 这类参数是演示件的替身，复制时删掉。
- **不跳过「请人看」**——测试只能证明结构没坏，证明不了像不像。

---

## 六、开工前 / 交付前

**开工前**

- [ ] 确认改的是哪一份、哪一屏、哪几个状态（`?page=` / `?state=` 列出来）
- [ ] 文案改动先看 `voice-spec.md`
- [ ] 设计稿没覆盖的态：先问用户

**交付前**

- [ ] `npm run build:console`（与 `build:home`，若动了共享浮层）成功，交付件与源一致
- [ ] `dom-compare` 的每一条不同都能说清是这次要改的
- [ ] 改的那一处检查通过（控制台 `console-states --only` / 首页 `interaction-check`）
- [ ] `npm run type-check` 通过
- [ ] 人看过渲染结果
- [ ] 如果产品要跟上：按 §四 的清单复制，而不是重写

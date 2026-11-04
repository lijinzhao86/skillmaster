# v46-account-identity · 产品需求

> **最后更新**：2026-11-05
> **所属版本**：[`README.md`](README.md)

## 用户与场景

**谁在用**：在一台电脑上同时用网页控制台和终端的作者。一个人可能有两个账号（工作、个人），也可能只是借别人的电脑用一下终端。

**在什么场景下用**：

1. **授权时**：他在终端里跑 `skmr login`，浏览器里还登着上一个账号。终端打开的授权页只写了客户端名和权限，没有写「这会把凭据发给谁」；他点「同意并继续」，凭据就发给了浏览器里那个旧账号。等他回到终端用了一阵，才发现文档、草稿都在别人名下。
2. **退出时**：他在网页上点了「退出登录」，以为终端也一起退出了。实际上终端那把凭据仍然有效，最长还能再用 7 天；而退出后的页面什么都不说，直接把他送回首页。

**现在的替代做法是什么**：授权时靠自己记得先退出网页、或者开隐身窗口、或者事后去控制台检查；退出时靠自己去「登录设备」页逐行核对。这些做法都要求他**先知道问题存在**——而问题恰恰是静默的：页面不说，终端也不说。对不知道的人，替代做法等于没有。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | 授权页在标题下方用账号卡片显示 <handle>（头像 + 名字，名字是卡片里最大的字）与「授权将发给这个账号。」；卡片在名称输入之前 | 控制台 + 设计件 |
| 2 | 同一处提供「不是我，换一个账号」：结束当前浏览器会话 → 登录页（带回程）→ 登录后回到同一张授权页，身份随之更新 | 控制台 + 设计件 |
| 3 | `skmr login` 成功后的输出增加「已登录为 <handle>」；交换响应没有该字段时省略这一句，不影响登录 | CLI + 服务端（只读字段） |
| 4 | 退出登录后停在引导页，说明三件事：浏览器登录已结束、这台电脑的 CLI 如果还没退出就仍然可用、要结束它把一句话交给 agent | 控制台 + 设计件 |
| 5 | 引导页把那句话做成可复制的块（含 `skmr logout`），并保留「回到首页」 | 控制台 + 设计件 |
| 6 | 设计件同步：授权页加身份行与换账号入口，退出后加引导页，重出两份演示件与截图；`design/README.md` 最后补写 | 设计件 |
| 7 | `skmr logout`（仅 CLI 登录凭据）在清本机的同时结束服务端这次登录；控制台「登录设备」那一行立即消失；API 密钥不变 | CLI + 服务端 |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | CLI 多份凭据（profile） | `skmr login` 的替换语义已经能换账号；还没有出现「同一台机器同时用两个账号」的需求 | 该需求出现时 |
| 2 | 控制台账号菜单加「切换账号」 | 本版只修授权与退出两处；菜单切换是另一屏，要单独验收。阿里云那种「切换身份」是换角色/权限上下文，不是换登录人；我们还没有角色、组织或成员账号可切 | 这两处交付后，按 TODO 剩余项评估；或组织/角色模型出现时 |
| 3 | CLI 退出时注销**浏览器会话** | CLI 够不到浏览器的 cookie；按账号只能踢掉该账号在**所有设备**上的浏览器登录；业界没有这么做。本版只结束 CLI 自己那次登录，不动浏览器 | 不做 |
| 4 | 改服务端「按浏览器会话签发」的规则，或加「以指定账号授权」的参数 | 身份仍由浏览器会话决定；本版只把它写出来，不改来源 | 不做 |
| 5 | 记住多个账号、做账号选择器 | 需要多会话或记住一份身份列表；共享电脑上会暴露「这台机器登过谁」 | 产品决定做账号选择器时 |
| 6 | 为「换一个账号」或退出引导新增 CLI 命令 | 浏览器身份选择只能在页面上做；终端侧已有 `logout` + `login`；退出引导是只读说明 | 不做 |
| 7 | 在退出引导页提 API 密钥 | 产品负责人 2026-11-05 决定：引导只讲 CLI 登录；用 API 密钥的人从「API 密钥」页撤销 | 出现「用 key 的人照引导找不到」的反馈时 |

### 控制台操作与 CLI 对应

| # | 控制台操作 | CLI 命令 | 备注 |
|---|---|---|---|
| 1 | 授权页显示「以谁的身份授权」 | `skmr auth status` 已经报告「服务端：账号 X」 | 只读，不新增命令 |
| 2 | 授权页「不是我，换一个账号」 | `skmr logout` + `skmr login`（或直接重跑 `skmr login`，它会替换本机凭据） | 认证流程动作，不是内容操作；终端侧等价动作已存在，不申请 [ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 例外 |
| 3 | 终端登录后报账号 | `skmr login` 自身的输出 | 本版 CLI 侧的改动 |
| 4 | 退出引导页「复制这句话」 | 这句话就是让 agent 运行 `skmr logout`；终端侧不新增命令 | 认证流程动作，不是内容操作 |
| 5 | 「登录设备」里结束一次 CLI 登录 | `skmr logout`（本版起它会结束自己那份；控制台仍可结束别的设备） | 同一种动作的两个入口 |

## 拥有面

| 要动的文件 / 面 | 是不是共享面 | 谁在用 |
|---|---|---|
| `design/src/shared/auth/**` | **是**（[`profile.md`](../../../.dev-sop/profile.md) §并行：共享面） | 控制台与官网的登录 / 注册 / 找回 / 授权 / 退出引导 |
| `design/README.md`（授权请求与退出引导两节） | **是**（活动分支 `feat/notifications` 也改这个文件） | 设计检查与实现 |
| `skillmaster-console/src/pages/CliSignInPage.tsx` | 否 | 授权页 |
| `skillmaster-console/src/pages/LogoutPage.tsx`、`src/App.tsx` | 否 | 退出路由与引导页 |
| `skillmaster-console/tests/cli-sign-in.test.tsx`、`tests/logout-page.test.tsx` | 否 | 两页测试 |
| `skillmaster-cli/cmd/skillmaster/auth.go`（`saveAndReport` / `loginFacts`） | 否 | 登录输出 |
| `skillmaster-cli/cmd/skillmaster/auth_logout.go`、`internal/auth`、`internal/api` | 否 | `logout` 的服务端撤销 |
| `skillmaster-cli/cmd/skillmaster/declarations.go`、`skillmaster-cli/README.md` | 否 | `logout` 的帮助与 README；`docs/current/cli.md` 随之重生成 |
| `skillmaster-server/.../CliSignInCredentialResponse.java`、`CliSignIn.java`、新增的撤销端点与其 IT | 否 | 交换响应与服务端撤销 |
| `skillmaster-server/.../config/SecurityConfig.java` | **是**（所有 `/api/v1/**` 的鉴权） | 只加一条 `POST /api/v1/logout` 的 `authenticated()`，让它不受写 scope 约束 |

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| A1 | 浏览器登着 A 时跑 `skmr login`，授权页的账号卡片显示 A；卡片在名称输入之前，名字是卡片里最大的字。 | 组件测试断言卡片与 A、文档顺序、字号；设计件截图 |
| A2 | 点「不是我，换一个账号」后，当前浏览器会话结束；登录页带回程地址；登成 B 后自动回到同一张授权页，卡片显示账号 B。 | 组件测试：点按钮后跳登录页、`return_to` 是当前授权页完整地址；设计件换账号流程走到身份变 B；服务端 `WebLogoutIT` 验证 `POST /web/logout` 返回 204 |
| A3 | 以 B 身份点「同意并继续」，终端交换到的凭据属于 B，并打印「已登录为 B」。 | 服务端 IT：交换响应的账号是 B；CLI 测试：`saveAndReport` 输出该句 |
| A4 | 点「换一个账号」后放弃登录，不签发任何凭据；终端继续等待到自己的超时。 | 手工走查 + 服务端检查没有新的 `access_token` 行、控制台「登录设备」不出现这一行（`start` 会写 authorization 行，那不是凭据） |
| A5 | 既有授权流程不变：名称必填、拒绝、链接不完整、未登录先跳登录。 | 既有 `cli-sign-in.test.tsx` 与 `CliSignInIT` 全绿 |
| A6 | 老服务端（交换响应没有账号字段）配新 CLI：登录照常成功，只是不打印「已登录为 X」。 | CLI 单元测试构造无该字段的响应 |
| A7 | 设计件构建可复现；授权页与退出引导页的界面检查全绿。 | 两次构建后两份演示件的 sha1 不变；`interaction-check.mjs`（覆盖 12/12b/17/17b）与 `console-states.mjs --only auth` 退出码 0 |
| A8 | 网页退出登录后停在 `/logout` 的引导页，不再自动跳回首页；页面说明浏览器登录已结束、这台电脑的 CLI 如果还没退出就仍然可用、如何结束它（把一句话交给 agent）。 | `logout-page.test.tsx` 断言不跳转、三句话在场（第二句是条件句）、页面不出现「重新登录」 |
| A9 | 「复制这句话」把含 `skmr logout` 的那句话复制到剪贴板；按钮的无障碍名变成「已复制这句话」（提示文字是「已复制」，图标换成对勾）；「回到首页」回官网。 | 组件测试：点击后断言写入的文本与无障碍名；真实剪贴板粘贴留给验收时手走（本轮未执行） |
| A10 | 退出请求失败时保留既有失败态：「没能退出登录。可以回去重试，或直接关闭这个页面。」+「回到首页」。 | 组件测试：`POST /web/logout` 失败，断言该句与出口 |
| A11 | CLI 登录凭据 `skmr logout` 成功后，服务端这次登录被结束；控制台「登录设备」不再列出；再拿该凭据请求得到「已结束／不认」。 | 服务端 IT：撤销后列表不含、旧 token 被拒；CLI 测试：成功路径调用端点并输出「服务端那份也已结束」 |
| A12 | 同一台机器上 `skmr logout` 面对 API 密钥时**不撤销密钥**，只清本机；输出仍按 [ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §决定 7 指向控制台。 | CLI 单元测试：key 分支不发撤销请求；服务端那把 key 仍在 |
| A13 | 服务端不可达或拒绝撤销时，本机凭据仍被清除；输出明确说服务端那份还在，并给出控制台地址；不谎称已结束。已经结束的凭据（`token_revoked` / `token_expired` / `token_idle`）再退出，按「已经结束」处理，不指向控制台。 | CLI 单元测试：端点失败与断连各一条，断言本机清掉、输出含「服务端那份」与控制台地址；三种已结束的 401 各一条，断言不说「还在」、不给控制台地址 |

**成立条件**：A2 与 A11 不满足就不算交付——这一版要解决的就是「页面上的身份」与「实际签发的身份」脱节，以及「退出登录」只退了一半。

### 指标

没有单独的量化指标。判据是 A1–A13 全过。

## 依赖与前置

- **已定的决策**：
  - [ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §决定 7——`logout` 只清本机；本版对 **API 密钥**保持不变，**CLI 登录凭据**由 [ADR 0142](../../decisions/0142-cli-logout-ends-its-own-server-login.md) 收窄为「清本机 + 结束服务端那次登录」。
  - [ADR 0040](../../decisions/0040-api-key-in-the-cli-now-the-browser-path-later.md)——终端登录换来的凭据是一台「登录设备」，可在控制台结束。
  - [ADR 0043](../../decisions/0043-the-browser-session-is-bounded-and-revocable.md)——浏览器会话的两条尺子；本版不改。
  - [ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md)——控制台操作与 CLI 的对应；本版的对应动作已在表里。
  - [ADR 0086](../../decisions/0086-the-homepage-reads-the-session.md) §决定 2——www 不写、`/logout` 是控制台的路由；本版新增 [ADR 0141](../../decisions/0141-a-logout-stops-on-a-guide-screen.md) 收窄它「结束当前会话 → 回到首页」那半句。
- **外部依赖**：无。
- **新增 ADR**：[ADR 0141](../../decisions/0141-a-logout-stops-on-a-guide-screen.md)——退出后停一页引导；[ADR 0142](../../decisions/0142-cli-logout-ends-its-own-server-login.md)——`skmr logout` 结束服务端这次登录，收窄 [ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §决定 7 到 API 密钥。

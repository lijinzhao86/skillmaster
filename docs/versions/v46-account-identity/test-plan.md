# v46-account-identity · 测试方案

> **最后更新**：2026-11-05
> **所属版本**：[`README.md`](README.md)
> **状态**：已实现，结果已填。T2 的视觉验收需要产品负责人看截图；T6 的浏览器半需要人在浏览器与终端里走一遍。

## 验收映射

把 [`prd.md`](prd.md) §验收与指标 的**每一条**映射到具体用例。允许一条对多例。

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| A1 授权页账号卡片显示 A、在名称之前、名字最大 | T1、T2 | 通过（T2 视觉待看） |
| A2「换一个账号」结束会话、带回程登录、回来是 B | T2、T3、`WebLogoutIT` | 通过（T3 只到跳转；回程后的身份由 T2 的设计件流程覆盖） |
| A3 以 B 授权，交换到的凭据属于 B，终端打印「已登录为 B」 | T4、T5 | 通过 |
| A4 换账号后放弃，不签发凭据，终端等到超时 | T6 | 部分执行（服务端半通过；浏览器半未执行） |
| A5 既有授权流程不变 | T7 | 通过 |
| A6 老服务端无账号字段，新 CLI 登录照常、少打一句 | T8 | 通过 |
| A7 设计件构建可复现、授权页与退出引导页检查全绿 | T9 | 通过 |
| A8 退出后停在引导页，三句话都在，第二句是条件句 | T10 | 通过 |
| A9 复制含 `skmr logout` 的那句话；「回到首页」可用 | T11 | 通过 |
| A10 退出请求失败时保留既有失败态 | T12 | 通过 |
| A11 CLI 登录凭据 `skmr logout` 后服务端登录结束、列表消失、旧 token 被拒 | T13、T14 | 通过 |
| A12 API 密钥只清本机，不撤销密钥 | T15、T13 | 通过 |
| A13 服务端不可达或拒绝时本机仍清、输出不说谎；已结束的凭据按已结束处理 | T16、T17 | 通过 |
| （TD Q4）环境里有 key 时，本机那份登录仍被结束 | T18 | 通过 |

**没有对应用例的验收标准是不合格的验收标准**——上面每条都有。

## 用例

### T1 · 授权页账号卡片与文档顺序

- **前置**：`skillmaster-console` 依赖已装；测试夹具给 `CliSignInPage` 一个已登录账号 A 的会话。
- **步骤**：渲染页面；读 `#consentAccount` 的文本；比较 `#consentAccount`（卡片）与 `#consentName`（名称输入）在文档中的位置；从 `src/styles.css` 读 `.dialog .consent-account-who strong` 与 `.dialog .consent-account-hint` 两条规则的字号。
- **期望**：卡片文本是 A；卡片的 `compareDocumentPosition` 在名称输入之前；两条规则分别是 18px 与 13px。**「名字是卡片里最大的字」由 T2 的渲染检查量**（jsdom 不处理样式表导入，组件测试只能读规则文件）。
- **判定**：通过／失败（失败时记录实际文本、顺序与字号）。

### T2 · 设计件的授权页两态

- **前置**：`design/` 已 `npm ci`；控制台演示件已用当前源重建。
- **步骤**：在仓库根跑 `node design/scripts/interaction-check.mjs "design/skillmaster-控制台演示.html" artifacts/design/states`；看 `12-consent.png` 与 `12b-consent-switched.png`。
- **期望**：`12-consent` 的卡片写 `dev`，在名称输入上方，名字 18px、提示 13px 且名字更大；点「不是我，换一个账号」后经登录页回到同一张授权页，`12b` 的身份是 `other`。
- **判定**：通过／失败（失败时给出哪一条断言或哪张图不对）。

### T3 · 换账号的请求与回程

- **前置**：控制台测试夹具能记录 `fetch` 调用与导航目标。
- **步骤**：在授权页点 `#consentSwitch`；检查 `POST /web/logout` 的调用；检查跳转地址。
- **期望**：`/web/logout` 只调一次；跳转是 `/login?return_to=<当前授权页完整地址的 URL 编码>`。**回程后身份变成 B 由 T2 的设计件流程覆盖**；产品登录页对 `return_to` 的处理由既有 `tests/login.test.tsx` 覆盖。
- **判定**：通过／失败（失败时记录调用次数与跳转地址）。

### T4 · 交换响应带账号（服务端）

- **前置**：`skillmaster_test_v46` 可用；已有一套走完授权码流程的测试夹具。
- **步骤**：以账号 B 完成一次授权；用交换得到的码调用 `POST /cli-sign-ins/exchange`；再拿返回的 token 调 `/api/v1/me`，并读该 token 所在的 `oauth_authorization.principal_name`。
- **期望**：交换响应 `username` 是 B 的 handle；`/me` 的 `user.username` 与它一致；`oauth_authorization.principal_name` 是 B 的 **user id**（与 `/me` 的 `user.user_id` 一致）。
- **判定**：通过／失败（失败时记录响应体与库里的行）。

### T5 · 终端打印「已登录为」

- **前置**：CLI 测试能替换标准输出。
- **步骤**：构造 `Username: "b"` 的凭据调用 `saveAndReport`；再构造 `Username: ""` 的凭据调用一次。
- **期望**：第一次输出含「已登录为 b」；第二次不含这一句，其余输出不变。
- **判定**：通过／失败（失败时贴输出）。

### T6 · 换账号后放弃（手工）

- **前置**：本机浏览器登着 A；终端跑 `skmr login`；服务端连 `skillmaster_v46`。
- **步骤**：授权页点「不是我，换一个账号」；在登录页登成 B；回到授权页后**不点同意**，直接关掉页面；等终端超时。
- **期望**：终端报既有的等待超时；数据库没有为这次登录新增 `access_token` 行，控制台「登录设备」不出现这一行（`start` 写的 authorization 行不是凭据）。
- **判定**：通过／失败（失败时贴终端的最后一段与 SQL 计数）。

### T7 · 既有授权流程回归

- **前置**：同上两条测试环境。
- **步骤**：跑 `tests/cli-sign-in.test.tsx`、`tests/consent.test.tsx` 与 `CliSignInIT` 全量。
- **期望**：名称必填、拒绝、链接不完整、未登录先跳登录四条既有行为全绿。
- **判定**：通过／失败（失败时贴失败用例名）。

### T8 · 老服务端没有 `username`

- **前置**：CLI 测试的假服务端可定制交换响应。
- **步骤**：返回不带 `username` 字段的交换响应；跑登录。
- **期望**：登录成功、凭据落盘、退出码 0；标准输出没有「已登录为」这一句。
- **判定**：通过／失败（失败时贴响应与输出）。

### T9 · 设计件构建与界面检查

- **前置**：`design/` 依赖已装。
- **步骤**：

  ```bash
  cd design
  npm run build
  shasum skillmaster-控制台演示.html skillmaster-首页演示.html > /tmp/build-1.sha
  npm run build
  shasum skillmaster-控制台演示.html skillmaster-首页演示.html > /tmp/build-2.sha
  diff /tmp/build-1.sha /tmp/build-2.sha
  cd ..
  node design/scripts/interaction-check.mjs "design/skillmaster-控制台演示.html" artifacts/design/states
  node design/scripts/console-states.mjs "design/skillmaster-控制台演示.html" artifacts/design/console --only auth
  ```

- **期望**：两次构建的 sha1 相同（重建可复现）；两条检查退出码 0。**两条脚本自己会 `chdir` 到仓库根，所以检查命令从仓库根跑、路径带 `design/`**。
- **判定**：通过／失败（失败时贴断言名与截图路径）。

### T10 · 退出引导页的三句话

- **前置**：控制台测试夹具能替换 `fetch` 与 `window.location`。
- **步骤**：让 `POST /web/logout` 成功，渲染 `/logout`。
- **期望**：不发生自动跳转；页面依次有「这个浏览器上的登录已经结束。」「这台电脑上的 CLI 如果还没有退出，就仍然可以正常使用。」「要让它在终端也退出，把这句话交给 agent：」三句；不出现「重新登录」「登录设备」「API 密钥」。
- **判定**：通过／失败（失败时记录缺哪一句或多了什么）。

### T11 · 复制那句话

- **前置**：同上；剪贴板 API 用测试替身。
- **步骤**：点 `#logoutCopy`；读写入的文本与按钮的无障碍名；等 1.2 秒再读一次；点「回到首页」。
- **期望**：写入的文本含 `skmr logout`，与 `LOGOUT_INSTRUCTION` 逐字一致；按钮的无障碍名从「复制这句话」变成「已复制这句话」，随后复原（测试只等复原，不钉 1200ms）；「回到首页」跳到官网地址。
- **判定**：通过／失败（组件测试断言写入的文本与无障碍名；真实剪贴板粘贴留给验收时手走，本轮未执行）。

### T12 · 退出失败的既有态

- **前置**：同上。
- **步骤**：让 `POST /web/logout` 返回 500，渲染 `/logout`。
- **期望**：页面是既有失败句「没能退出登录。可以回去重试，或直接关闭这个页面。」加「回到首页」；不出现引导页的三句话；不自动跳转。
- **判定**：通过／失败（失败时贴实际文案）。

### T13 · 服务端结束这次登录（IT）

- **前置**：`skillmaster_test_v46` 可用；`CliSignInIT` 里有覆盖结束登录的用例。
- **步骤**：走完交换拿到 CLI 登录 token；`POST /api/v1/logout` 带该 token；再查 `GET /web/sessions`（控制台「登录设备」的列表接口）；再拿同一 token 调 `/api/v1/me`；最后拿一枚**只带 `skills:read` 的** API key 调 `POST /api/v1/logout`。
- **期望**：第一次 204；列表不再有那一行；旧 token 得到 401 `token_revoked`；只读 key 也得到 400 `invalid_request`（该路由不要 scope），且该 key 仍可用。
- **判定**：通过／失败（失败时贴状态码、响应体与 `oauth_authorization.revoked_at`）。

### T14 · CLI `logout` 的成功路径

- **前置**：CLI 测试的假服务端能记录请求。
- **步骤**：本机存一份 v4 登录凭据；假服务端对 `POST /api/v1/logout` 答 204；跑 `skmr logout`。
- **期望**：请求方法、路径、Bearer 头都对；本机凭据文件被删；输出含「服务端那份也已结束」，不含控制台「登录设备」地址；退出码 0。
- **判定**：通过／失败（失败时贴请求记录与输出）。

### T15 · API 密钥只清本机

- **前置**：本机存的是 API key 凭据，**带 `SavedAt`**（旧版 `login --with-key` 写下的形状）；假服务端记录一切请求。
- **步骤**：跑 `skmr logout`。
- **期望**：没有任何 HTTP 请求；本机凭据被删；输出仍按 [ADR 0038](../../decisions/0038-default-credential-is-an-api-key.md) §决定 7 指向控制台「API 密钥」页；退出码 0。
- **判定**：通过／失败（失败时贴请求记录与输出）。

### T16 · 服务端不可达或拒绝

- **前置**：本机存的是 v4 登录凭据；假服务端分别返回 500 与直接断连（关掉监听）。
- **步骤**：各跑一次 `skmr logout`。
- **期望**：本机凭据都被删；输出明确说「服务端那份还在」，并给出控制台「登录设备」地址；不出现「已结束」；退出码 0（Q2 的倾向）。
- **判定**：通过／失败（失败时贴两种输出）。

### T17 · 凭据在服务端已经结束

- **前置**：本机存一份登录凭据；假服务端对 `POST /api/v1/logout` 分别答 401 `token_revoked`、`token_expired`、`token_idle`。
- **步骤**：每种各跑一次 `skmr logout`。
- **期望**：本机凭据都被删；输出把它当成「已经结束」，不打印控制台地址、不报错；退出码 0（Q3 的倾向）。
- **判定**：通过／失败（失败时贴响应与输出）。

### T18 · 环境里的 key 不阻止结束本机登录（Q4）

- **前置**：本机存一份 v4 登录凭据；`SKILLMASTER_API_KEY` 里有一把 key；假服务端答 204。
- **步骤**：跑 `skmr logout`。
- **期望**：请求带的是**本机登录的** token；本机那份被删；输出既说「服务端那份也已结束」，也按原句指向控制台的「API 密钥」页；退出码 0。
- **判定**：通过／失败（失败时贴请求头与输出）。

## 环境与数据

- **从哪开始**：worktree `/Users/lijinzhao/Developer/skillmaster-consent-identity`，分支 `feat/consent-account-identity`；三端依赖已装（`skillmaster-console` 与 `design` 各 `npm ci`）。
- **数据库**：本机 PostgreSQL 5432 上建 `skillmaster_v46`（手工走查用）与 `skillmaster_test_v46`（IT 用），都用 `TEMPLATE template0` 建，避开 template1 的 collation 版本不一致。
- **服务端 IT**：

  ```bash
  cd skillmaster-server
  SKILLMASTER_TEST_DB_URL=jdbc:postgresql://127.0.0.1:5432/skillmaster_test_v46 \
  SKILLMASTER_TEST_DB_USER=<本机用户> SKILLMASTER_TEST_DB_PASSWORD= \
  ./mvnw -Dit.test=CliSignInIT,WebLogoutIT verify
  ```

  `*IT` 由 failsafe 在 `verify` 阶段跑。跑之前 unset 四个真实发送相关的键（`SKILLMASTER_SMS_ACCESS_KEY_ID` / `_SECRET` / `_SIGN_NAME` / `_TEMPLATE_CODE`），让测试用回退发送器；`accept-any-code` 与 `log-codes` 已由 pom 钉成 false。
- **控制台**：`cd skillmaster-console && npx vitest run tests/cli-sign-in.test.tsx tests/logout-page.test.tsx`。
- **CLI**：`cd skillmaster-cli && env -u SKILLMASTER_AUTO_UPDATE go test ./...`（环境里若有该变量，3 个更新器测试会以为自动更新关闭而失败）。
- **设计件**：见 T9；截图落在 `artifacts/design/`，不进 git。
- **数据**：账号 A、B 用两个测试手机号各自注册；CLI 凭据由真服务端交换得到，或由 CLI 测试的假服务端构造。手工走查的记录（authorization id、token 前缀）写在结果表里，不留在库里。
- **清理**：IT 的库每次由 Flyway 从空库重建；手工走查完退出登录并撤销该次 CLI 登录；`artifacts/` 与 `skillmaster_test_v46` 都可以整个删掉重来。

## 结果

实现完成后填写。**每条都要有证据**（命令输出、截图、日志片段），不要只写「通过」。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | `npx vitest run tests/cli-sign-in.test.tsx`：13 passed；断言 `#consentAccount` 文本、与 `#consentName` 的文档顺序、样式表里两条规则的 18px 与 13px |
| T2 | 通过（视觉待看） | 仓库根跑 `node design/scripts/interaction-check.mjs "design/skillmaster-控制台演示.html" artifacts/design/states`：退出码 0，`failed: 0`；截图 `12-consent.png`、`12b-consent-switched.png` |
| T3 | 通过 | 同 T1：点 `#consentSwitch` 后断言 `POST /web/logout` 一次、`location.replace('/login?return_to=…')`；回程后的身份见 T2 |
| T4 | 通过 | `./mvnw -Dit.test=CliSignInIT,WebLogoutIT verify`：CliSignInIT 20 tests、WebLogoutIT 4 tests，0 failures；首条用例断言 `username` 与 `/me` 的 handle 一致，`principal_name` 与 `/me` 的 user id 一致 |
| T5 | 通过 | `go test ./cmd/skillmaster`：`TestTheLoginReportSaysNameScopeAndDate` 断言「已登录为 alice」 |
| T6 | 部分执行 | 服务端半：`aLoginNobodyRedeemedIssuesNoCredential` 通过（无 `access_token` 行、列表无该行）。浏览器半（点换账号后不点同意、终端等超时）需要人在浏览器里走，未执行 |
| T7 | 通过 | 控制台 38 files / 669 tests 全绿；`CliSignInIT` 20 tests 全绿 |
| T8 | 通过 | `go test ./internal/auth`：`TestAnExchangeWithoutAnAccountStillLogsIn` 通过；CLI 报告测试断言不出现「已登录为」 |
| T9 | 通过 | `npm run build` 两次后两份演示件 sha1 相同；`interaction-check` 退出码 0；`console-states --only auth` 退出码 0；产物重建 diff 无输出 |
| T10 | 通过 | `tests/logout-page.test.tsx`：不跳转、三句在场（逐字）、第二句含「如果还没有退出」、不出现「重新登录」「登录设备」「API 密钥」 |
| T11 | 通过（手工粘贴未执行） | 同文件：剪贴板收到 `LOGOUT_INSTRUCTION`，无障碍名变「已复制这句话」并随后复原；「回到首页」用 `replace` 到官网。真实剪贴板粘贴留给验收 |
| T12 | 通过 | 同文件：500 时是既有失败句「没能退出登录。…」+「回到首页」，不出现引导页的三句 |
| T13 | 通过 | `CliSignInIT`：204 → `/web/sessions` 列表为空 → 旧 token 401 `token_revoked`；只读 API key 得到 400 `invalid_request` 且仍可用 |
| T14 | 通过 | `go test ./cmd/skillmaster`：`TestLogoutEndsTheServerLoginItHolds` 断言方法、路径、Bearer 头、输出与删除次数 |
| T15 | 通过 | `TestLogoutDoesNotSendAKeyToTheLoginRoute`：带 `SavedAt` 的 key 零请求，输出指向控制台密钥页，本机被删 |
| T16 | 通过 | `TestLogoutStillClearsLocallyWhenTheServerRefuses` + `TestLogoutStillClearsLocallyWhenTheServerIsUnreachable`：两种失败都本机已清、输出「服务端那份还在」+ 控制台地址、不说「已结束」 |
| T17 | 通过 | `TestLogoutTreatsAnAlreadyEndedLoginAsEnded`：`token_revoked` / `token_expired` / `token_idle` 三种 401 都按已结束处理，不打印控制台地址 |
| T18 | 通过 | `TestLogoutEndsTheStoredLoginEvenWhenAKeyIsInTheEnvironment`：请求带本机登录 token、本机被删、两句都出现 |

**失败或跳过的用例要单列说明**，不要埋在表格里。

- **T6 的浏览器半没有执行**：它需要一个人在真实浏览器里点「不是我，换一个账号」、登成另一个账号后**不点同意**，再等终端超时。服务端事实已由 IT 覆盖；这一步确认的是终端的行为，留给验收时手走。
- **视觉验收**：T2 的截图已经生成，但「名字是否足够显眼」是看的事——按仓库红线 2，等产品负责人看过再算 A1 的视觉半通过。

## 已知问题

本版范围内没有已知未修缺陷。范围外的历史代码缺陷如有，记在版本文件夹的 `known-issues.md`（本版尚未建立该文件）。

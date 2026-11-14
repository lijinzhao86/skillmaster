# v68-cloud-device-login · 测试方案

> **最后更新**：2026-11-14
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收标准 | 对应用例 |
|---|---|
| #1 无浏览器机器发起，不监听端口，打印链接 | T1 |
| #2 批准后 2 秒内完成 | T2 |
| #3 Ctrl-C 取消，票根随即失效 | T3 |
| #4 一次性、10 分钟过期、二次打开/批准 | T4、T5 |
| #5 拒绝不产生授权 | T6 |
| #6 页面显示名称、来源、权限、账号、别转发 | T7 |
| #7 新用户可在同一页注册完成 | T8 |
| #8 登录可撤销，撤销后立刻失效 | T9 |
| #9 桌面自动弹；打不开不影响 | T10 |
| #10 无人值守不自动发起登录，改用 API Key | T11 |
| #11 两台设备不在同一网络也能完成 | T12 |

每条跑没跑、过没过，只看 §结果——那份表是唯一的状态来源，这里不再抄一遍。

安全与记录是支撑用例，不单列验收：T13（票根/密钥/限流）、T14（授权行与「登录设备」）、T15（退出其他所有设备）。
沙箱是支撑用例：T16（网络默认放行的沙箱）、T17（白名单为空的沙箱）、T18（状态目录写不进）。

## 用例

### T1 · 无浏览器机器发起

- **前置**：服务端可达；一个没有 `DISPLAY`、没有浏览器启动器的 Linux 容器；CLI 已编译。
- **步骤**：在容器里运行 `SKILLMASTER_SERVER=<地址> skmr login`；在宿主机上看容器内监听端口。
- **期望**：不监听任何回环端口；终端打印一条含 `https://…/cli-sign-in?ticket=…` 的句子与「别转发」提示；命令停在等待。
- **判定**：通过／失败（记录实际监听的端口与输出）。

### T2 · 批准后 2 秒内完成

- **前置**：T1 的等待仍在进行；另一台设备（第二个浏览器配置或手机）已登录一个账号。
- **步骤**：打开链接，确认名称与来源，点「同意并继续」；记录服务端 `approved_at` 与 CLI 打印「已登录」的时间。
- **期望**：CLI 打印账号；差值 ≤ 2 秒；凭据落在本机（文件 0600，ADR 0185）。
- **判定**：通过／失败（记录两次时间戳）。

### T3 · 取消

- **前置**：T1 的等待仍在进行。
- **步骤**：在终端按 `Ctrl-C`；确认 CLI 向 `/cli-device-sign-ins/{ticket}/cancel` 发了一次带 `device_secret` 的请求；再用浏览器打开同一链接。
- **期望**：CLI 退出、不留凭据；服务端那一行变成 denied；链接显示「已经用过或过期了」，页面没有「同意」；再运行 `skmr auth status` 仍按未登录处理。
- **判定**：通过／失败。

### T4 · 一次性

- **前置**：一次已完成的登录。
- **步骤**：用同一链接再打开一次；再用 `device_secret` 与旧 `code_verifier` 轮询一次。
- **期望**：页面显示失效态；轮询返回 `410 used`；第一份凭据仍然可用；旧链接若再驱动一次 CLI，它以「这次登录没有完成…」和退出码 1 结束。
- **判定**：通过／失败。

### T5 · 过期

- **前置**：一次待批准的登录。票根 TTL 是代码常量（10 分钟），没有配置项。
- **步骤**：把那一行的 `expires_at` 直接改成过去（集成测试就是这么做的），再打开链接并轮询。
- **期望**：页面显示失效态；轮询 `410 expired`；不产生授权行；CLI 以「这次登录没有完成…」和退出码 1 结束。
- **判定**：通过／失败。

### T6 · 拒绝

- **前置**：一次待批准的登录；记录 `oauth_authorization` 行数。
- **步骤**：在页面点「拒绝」；CLI 继续轮询。
- **期望**：CLI 报「这次登录没有完成：链接被拒绝、已经用过，或者超过了 10 分钟」（`410 denied`），退出码 1；授权行数不变；页面标题「授权未完成」，正文「你拒绝了这次授权请求。」
- **判定**：通过／失败。

### T7 · 页面内容

- **前置**：一次待批准的登录，带名称、来源地址与 scope。
- **步骤**：在手机宽度（390×844）与桌面各打开一次；逐项核对。
- **期望**：名称可改；「请求来自 <地址>，<N> 分钟前发起」；两条权限人话；当前账号；「这条链接只能批准一次登录，不要转发给别人。」；「同意并继续」/「拒绝」。小屏可以滚到按钮。
- **判定**：通过／失败（附两种宽度的截图）。

### T8 · 新用户在同一页注册

- **前置**：一个从未注册过的手机号；测试环境使用固定的短信验证码。
- **步骤**：打开链接 → 未登录 → 注册 → 回到同一页 → 批准。
- **期望**：注册完成后回到同一张授权页；批准成功；该账号没有任何 API Key 行。
- **判定**：通过／失败。

### T9 · 撤销

- **前置**：一次已完成登录；控制台「登录设备」里能看到 `CLI · <名称>`。
- **步骤**：撤销该行；在原机器运行 `skmr auth status`，再运行一条需要凭据的命令。
- **期望**：`auth status` 按未登录/失效处理；下一条命令失败（401）并提示重新运行 `skmr login`，**不会自己发起新的批准**；控制台不再显示该行。
- **判定**：通过／失败。

### T10 · 桌面自动弹与打不开

- **前置**：macOS 与 Windows 各一台桌面。
- **步骤**：运行 `skmr login`；再在启动器不可用的环境（`PATH` 去掉 `open` / `rundll32` 或容器）运行一次。
- **期望**：桌面自动打开浏览器；启动器不可用时不报错、不等待，链接仍在屏幕上，手工打开可以完成。
- **判定**：通过／失败。

### T11 · 无人值守

- **前置**：`SKILLMASTER_API_KEY` 未设置。三种环境各跑一次：`CI=true`、检测到沙箱（`SANDBOX_RUNTIME=1`）、没有可打开链接的浏览器（无启动器或无显示服务器）。
- **步骤**：在每种环境里运行一条会走 `clientFor` 的读命令（例如 `skmr skills list`）。
- **期望**：命令**不自己发起**设备批准、不打印链接；句子同时给出 `skmr login` 与 `SKILLMASTER_API_KEY`（`help auth` 指路），三处判断各自生效。
- **边界**：用户明写的 `skmr login` 在哪都打印链接——链接可以在手机上打开，所以 `skmr login` 本身不按「这台机器有没有浏览器」「终端是不是交互式」分流（ADR 0200 §决定 3）。命令**自己**发起登录时另算：没有人在看，就不该阻塞十分钟。
- **判定**：通过／失败。

### T12 · 跨网络

- **前置**：CLI 在一台云主机上；浏览器在手机上；两者都能访问控制台的公网地址。
- **步骤**：云主机发起；手机批准；CLI 完成。
- **期望**：全程不要求两台设备在同一网络；CLI 不监听端口。
- **判定**：通过／失败。

### T13 · 票根、密钥与限流

- **前置**：一次待批准与一次已完成的登录。
- **步骤**：用错误 `device_secret` 轮询；用错误 `code_verifier` 轮询；同一票根每秒轮询 6 次；`GET` 两次后检查状态；检查响应头。
- **期望**：错误密钥/验证器 `404`（不泄露存在性），**验证器在批准之前就校验**；过快 `429` 且带 `Retry-After` 响应头（body 是通用错误信封，`code=too_many_requests`）；`GET` 不改变状态；`Cache-Control: no-store` 与 `Referrer-Policy: no-referrer`。
- **判定**：通过／失败。

### T14 · 授权行与「登录设备」

- **前置**：一次批准，批准时改过名称。
- **步骤**：查 `oauth_authorization` 的 `label`、`address`、`last_used_at`；在控制台看列表。
- **期望**：`label` 是改后的名称；`address` 是批准者的地址；`last_used_at` 随使用更新；列表显示 `CLI · <名称>`。
- **判定**：通过／失败。

### T15 · 退出其他所有设备

- **前置**：同一账号两份 CLI 登录 + 一个浏览器会话。
- **步骤**：在浏览器点「退出其他所有设备」。
- **期望**：两份 CLI 登录都失效；当前浏览器会话仍在。
- **判定**：通过／失败。

### T16 · 网络默认放行的沙箱（WorkBuddy）

- **前置**：WorkBuddy 沙箱（5.8.x），默认放行 TLS、回环与网络；agent 在沙箱里执行命令。
- **步骤**：在沙箱里运行 `skmr login`；agent 把链接转述给用户；用户在手机批准。
- **期望**：沙箱里不尝试打开浏览器、不等待；链接正常打印；CLI 轮询成功并落凭据；没有出现五分钟等待。
- **判定**：通过／失败（记录是否出现 `open` 调用与等待时长）。

### T17 · 白名单为空的沙箱（Claude Code / Cursor）

- **前置**：一个网络白名单默认拒绝的 agent 沙箱。
- **步骤**：直接运行 `skmr login`；按输出里的片段放行服务端域名并重启；再运行一次。
- **期望**：第一次失败时说清是到服务端的连接被挡，并给出该客户端的放行片段；放行后第二次能完成链接登录。
- **判定**：通过／失败（记录两次输出）。

### T18 · 状态目录写不进

- **前置**：一个状态目录不可写的沙箱。
- **步骤**：完成批准后让 CLI 去存凭据。
- **期望**：报凭据写入失败，并给出两条出路（改用 `SKILLMASTER_API_KEY`，或给配置目录写权限后重试）；不把失败说成登录成功；不留下半份凭据。
- **判定**：通过／失败。

## 环境与数据

- **环境怎么造**：Docker Postgres（`skillmaster-postgres`）；服务端用本工作区的库（每个 worktree 一个库）；控制台与 CLI 从本工作区构建。服务端启动要 `source .env`，并 `unset` 四个 SMS 变量——本地验证码可枚举，这是既有本地端到端做法的前提。
- **数据**：
  - 账号 A：已注册，用于批准；
  - 账号 B：全新手机号，用于 T8；测试环境固定验证码；
  - 一次待批准：由 CLI 发起后保留；
  - TTL 覆盖：T5 直接把那一行的 `expires_at` 改到过去（TTL 是代码常量，没有配置项）；其余用例用生产值 10 分钟。
- **清理**：删除本版新增的 `cli_device_sign_in` 测试行与测试账号；停止本地服务；不删库、不 `rm -rf`。

## 结果

> **自动化那一层已完成（2026-11-14，审计五轮修复后）**：服务端 `./mvnw verify` 全绿（单测 529、集成 799，含 22 个 `CliSignInIT`），CLI `go test ./...` 全绿，控制台 `npm test` 全绿，设计件 `interaction-check.mjs` 0 失败。
>
> **本机端到端也跑过一遍（2026-11-14，同一台 macOS）**：Postgres + 服务端 8080 + Vite 5173 + 真 Chrome（有会话 cookie，走 puppeteer 点页面）+ `/tmp/skmr`。链路是「CLI 起登录 → 链接在真浏览器打开 → 点同意 → 那台机器取回凭据」，结果是**同意到 CLI 结束 322 ms**（拒绝 323 ms），凭据落盘、`auth status` 与 `skills list` 都通。**这一遍查出并修掉了一个五轮审计都没发现的阻断缺陷**：`/cli-device-sign-ins`（服务端的只读视图）不在任何反向代理前缀里——Vite 与 nginx 都只放行 `/cli-sign-ins`，页面拿到 404（本地）或 `index.html`（生产 `try_files`），于是**把一条完好的链接报成「已经用过或过期」**。改的是 `skillmaster-console/vite.config.ts` 与 `deploy/nginx/skillmaster.conf`；控制台的测试桩掉了 `fetch`，所以这一层测试永远看不到它。
>
> **把产品截图和设计件截图并排看，又改了一处**：权限清单的顺序反了——票根里装的是**集合**，服务端发回来是「提交新版本…」在前，而设计稿是「读取…」在前。页面现在按设计的顺序固定这两条（不认识的作用域排在后面、保持服务端顺序），`tests/cli-sign-in.test.tsx` 加了一条「服务端怎么发都按设计顺序」的用例。
>
> **真 WorkBuddy 沙箱也跑了一遍（2026-11-14，T16）**：沙箱里 `curl` 得到 404（回环可达，走它自己的 `HTTP_PROXY` broker），`skmr login` 打印链接，人在浏览器里点了同意（库里那行：`12:54:43Z` 创建 → `12:55:05Z` 批准并当场取回），凭据写到真实 `~/Library/Application Support/skillmaster/`（0600），`auth status` 与 `skills list` 都通。**「是不是自动放行」这一问有账可查**：批准接口要会话 + CSRF，`approved_at` 只有这一条写入路径；同表另有三次「没人点就停在 pending、最后被 Ctrl-C 结束」的行。
>
> **同一个沙箱也顺手记下一条不易观察的事**：跑它的 agent 把 `login` 放在前台、等命令结束后才读整段输出，于是把 22 秒的等待读成「0 秒、没人批准」并报成安全疑点。根因是**等待期间终端不输出任何东西**，观察者无法区分「刚链接出来」与「已经等了 8 分钟」。评估后不改：`skmr login` 是给人跑的，链接那三行已经写着「批准后这条命令会自动继续」；agent 的正规路线是 API key（PRD §无人值守）。`doctor` 报「沙箱：客户端没有自报」也照旧——CLI 不认识 `CODEBUDDY_*` 标记，按 ADR 0167 只探能力、不猜客户端。
>
> **仍然没跑的是目标环境里的那几条**：T8、T12、T17–T18，以及 T10 的 macOS 真机自动弹（本机那遍用 `/tmp/skmr` 起在无浏览器模式下，链接由外部浏览器打开）。下表「通过（自动化）」仍只表示自动化覆盖到的那一层。
>
> **合并后生产要同步 nginx 配置**（`deploy/nginx/skillmaster.conf` → 主机 `/etc/nginx/conf.d/`），否则那条链接在生产仍然打不开页面；`ops-check.yml` 每天比对这份文件与主机，不同步会报漂移。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 部分 | 回环监听已删除（`internal/auth/callback.go` 不存在；`go test ./...` 无监听端口）；`loginWithBrowser` 在没有可用浏览器时不尝试打开（`TestLoginWithoutABrowserPrintsTheLinkAndCollectsTheCredential`）；**本机按无浏览器模式起**（`SANDBOX_RUNTIME=1`，不发 `open`），链接由另一个浏览器进程打开、批准后凭据回到那台 CLI；**真容器 / 云主机未跑** |
| T2 | 通过 | 本机实测：真浏览器点「同意并继续」→ CLI 退出码 0 并打印「已登录为 v68tester」**322 ms**（拒绝那次 323 ms）；目标 ≤2 秒。轮询间隔 1 秒由服务端下发（`CliDeviceSignInStartedResponse.interval`） |
| T3 | 通过 | `CliSignInIT` 四条取消用例、`auth.TestCtrlCStopsTheWait`、`TestCtrlCDuringAPollStillCancelsTheQuestion`、`TestRootContextIsCancelledByAnInterrupt`；**本机真 SIGINT 实跑**：CLI 打印「已取消登录，没有拿到凭据。」并退出 1，随后用同一浏览器打开那条链接，页面是「已经用过或过期」态、没有「同意并继续」按钮 |
| T4 | 通过（自动化） | `CliSignInIT.answeringWritesTheAuthorizationAndTheMachineCollectsItOnce`（第二次采集 `410 used`，第一份凭据仍可用）；`auth.TestAQuestionThatIsOverSaysToStartAgain` |
| T5 | 通过（自动化） | `CliSignInIT.anExpiredQuestionCannotBeAnsweredOrCollected`（行内 `expires_at` 改过去；TTL 是常量，没有配置项）；**过了 10 分钟的真实等待未做** |
| T6 | 通过 | `CliSignInIT.aRefusalWritesNothingAndEndsTheQuestion`；控制台 `tests/cli-sign-in.test.tsx` 的拒绝态；**本机真点「拒绝」**：页面「授权未完成 / 你拒绝了这次授权请求。」，CLI 退出 1 并给出下一步 |
| T7 | 通过 | `skillmaster-console/tests/cli-sign-in.test.tsx` 24 例；小屏数字由设计件实测（390×844 与 320×568，见 `design/README.md`）；**产品页截图已拍**（1440×900 与 390×844，待批准态与授权成功态里逐字核对：链接警告、名称预填「lijinzhao的MacBook Pro」、来源行、两条权限人话、账号卡、「请关闭此页面，那台设备会继续。」）；**真手机未试** |
| T8 | 未执行 | 需要真手机号走一次注册 → 批准 |
| T9 | 通过 | `CliSignInIT.endingALoginIsReflectedOnTheCredentialsNextRequest`；**本机真 `skmr auth status`**：账号 v68tester、机器名、权限与「至少有效到」都在，随后 `skmr skills list` 用这份凭据拿到 200 |
| T10 | 部分 | `TestLoginWithoutABrowserPrintsTheLinkAndCollectsTheCredential` 覆盖「没有浏览器时照样打印链接并完成，且不尝试打开」；**macOS 真机自动弹未跑** |
| T11 | 通过（自动化） | `TestACommandInCIDoesNotStartALogin`、`TestASandboxWithoutCredentialDoesNotStartABrowserLogin`、`TestStartupInASandboxNamesTheWayThatWorksThere`、`TestStartupRefusesWithoutACredentialAndNamesBothWays`；真 CI 环境未跑 |
| T12 | 未执行 | 需要云主机 + 手机 |
| T13 | 通过（自动化） | `CliSignInIT.noTicketAWrongSecretAndAWrongVerifierAreOneAnswer`、`aWrongVerifierIsRefusedWhileTheQuestionIsStillPending`、`pollingFasterThanTheIntervalIsRefusedWithRetryAfter`、`thePageReadsTheQuestionWithoutAnsweringIt`；CLI `TestASlowDownIsWaitedOutRatherThanFatal`、`TestASlowDownLongerThanTheQuestionStillGetsItsLastPoll` |
| T14 | 通过 | `CliSignInIT.theConsoleListsTheLoginAndOnlyItsOwnerCanEndIt`、`theSameMachineNameEndsTheLoginItReplaces`、`anApprovalMadeInTimeIsStillCollectableAfterTheClockRunsOut`；**本机真控制台**：批准后「登录设备」出现 `0:0:0:0:0:0:0:1 / CLI · lijinzhao的MacBook Pro · 登录于 2026/11/14 20:43 · 最近活动 今天 20:43` 与「退出该设备」 |
| T15 | 通过（自动化） | `CliSignInIT.revokeOthersEndsThisAccountsCliLoginsAndLeavesEveryoneElseAlone` |
| T16 | 通过 | 真 WorkBuddy 沙箱（macOS，`CODEBUDDY_*` 标记，`HTTP_PROXY=127.0.0.1:57077`）：回环可达、登录完成、凭据写入真实 HOME 且可用（细节见上面那段）。沙箱标记不被识别，CLI 因此走了桌面分支（发了 `open`），这台机器上无副作用 |
| T17–T18 | 未执行 | 需要白名单为空的沙箱（Claude Code / Cursor）与状态目录不可写的环境 |

## 已知问题

本版范围内的未修缺陷。范围外的历史缺陷写 `known-issues.md`（本版尚未创建）。

| 编号 | 缺陷 | 状态 |
|---|---|---|
| — | 尚无 | — |

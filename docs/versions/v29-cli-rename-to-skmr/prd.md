# v29 · 产品需求

> **最后更新**：2026-10-29 · **所属版本**：[v29](README.md)

## 用户与场景

**谁在用**：两个读者——每天在这台电脑上敲命令的人，以及**每轮对话开头先读那段记忆块的 agent**。后者才是高频调用方：按记忆块里的规则，任何任务动手前都先跑一次 `skills search`，命中还要 `skill invoke`。

**在什么场景下用**：查 skill、加载 skill、写 skill、发布 skill，每一次都是 `skillmaster ...`；记忆块、`--help`、每一条错误提示里也都写着这个名字。它出现在这条链上的每一个环节，而 `skillmaster` 是 11 个字符。

**现在的替代做法是什么**：在 shell profile 里加 `alias skm=skillmaster`。它只对交互式终端里的人有用——**agent 不读交互式 alias**，记忆块里写什么它就敲什么。所以替代做法解决不了「agent 每次都要打 11 个字符」这件事，也解决不了产物与文档里的名字。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | **命令名改为 `skmr`**：二进制名、usage、`--help`、错误提示、「下一步」提示一律写 `skmr` | Go 源码（`cmd/skillmaster/` 与 `internal/`）+ 构建脚本 |
| 2 | **旧名留一处兼容，只在代码里**：`config.LegacyName` 一个常量，启动时若官方目录里只有新名，就补一个指向它的旧名入口；文档与界面不写这一层 | Go 源码（`internal/config`、`internal/updater`） |
| 3 | **记忆块正文改用 `skmr`**：写进 agent 文件的那段说明里，命令示例都是新名 | `internal/prompt/block.md` |
| 4 | **发布产物指向新名**：包名仍是 `skillmaster_<os>_<arch>`，包里的可执行文件改成 `skmr`；自更新按新名取包、按新名替换 | `.github/workflows/cli*.yml` + `internal/updater/` |
| 5 | **站点安装说明改用 `skmr`**：正文里的命令都换新名；**地址 `/install/skillmaster.md` 不变** | `skillmaster-site/public/install/skillmaster.md` |
| 6 | **服务端提示文案改用 `skmr`**：`Organize` 拼出的那句 `skillmaster skill join ...` | `skillmaster-server/` |
| 7 | **设计件与控制台文案改用 `skmr`**：命令出现在界面上的那几处（提交提示、授权不完整页） | `design/` → `skillmaster-console/` |
| 8 | **平台 skill 正文重发**：`skillmaster/startup` 等正文里的命令改成新名 | 人的动作（网页上点），不是代码 |
| 9 | **修 `install.sh` 四个错误分支**：`$artifact（…` 连同后面的全角字符被 `sh` 当成变量名，触发时报 `unbound variable` 而不是原话（实现这一版时实测发现：12 个 `die` 分支里有 4 个中招——第 30、37、54、71 行） | `install.sh` |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | **改仓库名 / Go module 路径 / 域名** | 它们不是命令名；改名会让已有的 clone、CI 缓存、导入路径一起断 | 不做 |
| 2 | **改配置目录 `~/.config/skillmaster`** | 每个人机器上的凭据、pin、`prompt.json` 都在那里；改名＝所有人重新登录一次，换不到任何东西 | 不做 |
| 3 | **在文档与界面里维护两个名字** | 旧名只是代码里的一行兼容（[ADR 0113](../../decisions/0113-the-cli-is-called-skmr.md) 决定 3）；写进文档与帮助，等于告诉新用户有两个名字 | 不做 |
| 4 | **改任何命令的行为、参数、退出码与输出的结构** | 这一版只换名字：同样的命令、同样的参数、同样的行与字段，变的只有程序名 | 不做 |
| 5 | **改 `/install/skillmaster.md` 的地址** | 站点首页的 agent 提示与 [ADR 0088](../../decisions/0088-cli-major-version-is-the-api-generation.md) 决定 4 的 410 文案都指向它；正文里的 SkillMaster 是产品名 | 不做 |
| 6 | **改历史版本文档与 ADR** | 它们是当时发生过什么的记录；改写等于篡改历史 | 不做 |
| 7 | **改平台上的 skill 地址**（`skillmaster/startup`） | 那是发布者命名空间，不是命令名；改了会让所有引用断掉 | 不做 |

## 控制台操作与 CLI 对应

这一版**不新增、不删除任何控制台操作**。只有两处界面文案里出现命令名，换的是文案里的名字，不是操作：

| 界面 | 原话 | 位置 |
|---|---|---|
| 「我的 skill」列表的空态 | 「也可以运行 `skmr skill submit`。」 | 设计件 `design/src/console/pages/SkillsPage.tsx`，产品 `skillmaster-console/src/pages/SkillsPage.tsx` |
| 授权请求的「链接不完整」态 | 「请回到终端重新运行 `skmr login`。」 | 设计件 `design/src/shared/auth/views/ConsentIncompleteView.tsx`；产品里两处（`CliSignInPage.tsx` 是 CLI 那条路，`ConsentPage.tsx` 是第三方那条路） |

| 行为 | 在哪 | CLI |
|---|---|---|
| （无新增操作） | — | — |

## 验收与指标

| # | 验收标准 | 怎么判定 |
|---|---|---|
| 1 | **装完就能用**：`$INSTALL_DIR/skmr` 存在，`skmr --version` 退出 0 | 安装脚本末尾的冒烟段；本机真跑一次（T1） |
| 2 | **`--version` 只报新名**：输出形状是 `skmr <版本> (API v1)` | CLI 测试：以 `skmr ` 开头、逐字节稳定。**`<版本>` 只能在发版后验**——本地构建打印 `dev`，`1.2.0` 由发布 tag 注入 |
| 3 | **面向人的输出只写 `skmr`**：`help`、各子命令 `--help`、错误提示里不再出现以 `skillmaster ` 开头的命令示例 | 遍历所有命令与参数的渲染测试（沿用 `render_conformance_test.go` 的机制）+ 源码 grep |
| 4 | **记忆块用新名**：`prompt.Block()` 正文里的命令示例是 `skmr`，整段不再出现 `skillmaster ` | `internal/prompt/block_test.go` 的断言 |
| 5 | **文案都换了**：站点安装说明、服务端提示、设计件与控制台里都不再有 `skillmaster <子命令>` 形式的命令 | 仓库范围的 grep（见验收 6）；设计件那套检查全绿 |
| 6 | **没有漏网**：除下面五类之外，仓库里不存在以旧名开头的命令示例（含安装脚本、站点文档、服务端字符串） | 照 [`test-plan.md`](test-plan.md) T8 里那条命令跑一遍。**五类记录**：`docs/versions/`（历史）、`docs/decisions/`（决策）、`testdata/golden/`（旧输出的冻结样本）、`design/demos/dir1-swiss.html` 与 `dir2-resend.html`（设计稿里注明「已归档不再维护」的落选方向）、`**/*_test.go`（测试文件——夹具要刻意用旧名）；命令里另有四类**噪声**排除（`node_modules`、`.git`、`design/shots`、`*-演示.html`）。跑完剩 **5 行 / 3 个文件**：前 4 行是数据库角色名与 systemd 服务名；第 5 行 `V9__…sql:17` 的历史迁移注释里**确实有命令名 `skillmaster logout`**——它留着只因为迁移脚本不可修改，**不是因为它无害** |
| 7 | **平台 skill 正文重发**：`skillmaster/startup` 等正文里的命令改成 `skmr` | 由人在网页上改并发布，然后人工确认正文里不再有 `skillmaster `——CLI 这侧没有读它的凭据，量不了 |
| 8 | **安装脚本的错误分支说人话**：`install.sh` 出错时打印的是那几句话、退出码 1，不出现 `unbound variable` | T10（下载失败）、T11（认不出的架构）、T12（不支持的系统）、T13（校验和不符）各跑一次 |

**成立条件**：验收 1、3、8 是「不满足就不算交付」的三条——「装得上」「用户看到的只有一个名字」「装不上时说的话是对的」。**有两处只能记「部分」或「未执行」**：验收 2 的版本号要等发版（记「部分」），Windows 上的安装没有机器跑（T9 记「未执行」）。不把它们写成「通过」。

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| 命令名长度 | ASCII 字符数，`skillmaster` vs `skmr` | 直接数 | 11 → 4 |
| 每轮对话因此省下的 token | 命令名出现的次数 × 每次省下的 7 个字符（11 − 4），折约 1–2 token | 需要埋点，本版不量 | 不设目标 |

**为什么第二条不设目标**：2026-10-29 实测，一次完整 `skmr help` 里命令名出现 4 次——旧名共 44 个字符，新名 16 个，省 28 个字符；一轮对话通常在几十到几百 token 之间。**这不是这一版的理由**——理由是打字与可读性；把它写成指标只会制造一个量不出来的承诺。

## 依赖与前置

- **决策**：[ADR 0113](../../decisions/0113-the-cli-is-called-skmr.md)（新名、产物名不变、旧名只在代码里留一处兼容）、[0088](../../decisions/0088-cli-major-version-is-the-api-generation.md) 决定 2（同一主版本内命令面只增不减）、[0112](../../decisions/0112-retiring-a-command-within-one-generation.md)（将来若有**命令**要退役就走它）。
- **不动的身份**：配置目录、环境变量前缀、平台 skill 地址、仓库名与 module ——完整清单与理由只在 [`technical-design.md`](technical-design.md) §数据模型一份。
- **构件的现实**：产物名（`skillmaster_*`）与包内文件名（现在是 `skmr`）都写在 `internal/updater/release.go`；安装脚本与发布流水线按同一套拼名字。
- **人的动作**：平台上的 skill 正文（`skillmaster/startup` 等）只能由人在网页上重新发布；在那之前它们仍能跑，因为旧名还在。
- **排除了什么前置**：不需要域名与证书变更，不需要服务端换代，不需要动控制台的结构。

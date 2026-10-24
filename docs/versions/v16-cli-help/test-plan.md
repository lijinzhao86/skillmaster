# v16-cli-help · 测试方案

> **最后更新**：2026-10-24
> **所属版本**：[`README.md`](README.md) ｜ 需求：[`prd.md`](prd.md) ｜ 设计：[`technical-design.md`](technical-design.md)
> **状态**：**实现已落地**。三轮三路循环审计后的结果见 §结果：T01–T24 通过（T23 补上了真实 store 的一步，钥匙串那一支按 AC 的写法默认不跑）；**T25 已实测：首次找对率 95% 达标、调用中位数 5.5 未达标**（2026-10-24，`deepseek-v4-flash[1m]`，见 §已知问题 5）；**T25 未验证**（harness 未写、本机没有可调用的模型，2026-10-24 由产品负责人定为「未验证」）。
>
> 第 3 轮之后补上的三处（都回写进了本文的用例与期望）：**所有本地解析与校验失败统一为用法错误**（T10 加了逐命令的穷举断言）；**`skill invoke --offset` 与五个写命令的 `@new` flag 进入声明**（T02 的逐命令比对仍是未做项，但这几处已由声明侧补齐）；**可选位置参数与多 token 合并**（T03）。

## 验收映射

prd 的 21 条验收，逐条落到用例；一条对多例的地方在备注里说明。**没有对应用例的验收 = 漏项**。

| 验收 | 用例 | 备注 |
|---|---|---|
| #1 覆盖与三种写法 | T01 | 56 条跑 `--help` / `-h`；七个节点 + 顶层另跑裸 `help` |
| #2 帮助与声明一致 | T02、T03、T04 | T02 静态比对，T03 反向绑定，T04 长度与“下一步” |
| #3 新增命令必须带帮助 | T05 | 纯函数 fixture + 未登记前缀 + 未登记 dispatch 函数 |
| #4 分组帮助 | T06 | 七个节点 × 三种写法 = 21 次 |
| #5 顶层帮助 | T07 | |
| #6 未知动词 | T08 | |
| #7 未知顶层命令 | T09 | 含 200 字节上限 |
| #8 未知 flag / 用法错误 | T10 | 未知 flag、地址错、参数个数、flag 缺值 |
| #9 四个退出码 | T11 | 六个场景，外加 grep 的无命中 |
| #10 凭据不可用都退 4 | T12 | 含 grep 的两类与 `--json` 一例 |
| #11 不是 4 的两类 | T13 | 退出 1 的五个场景在 T13；`auth status` 对 `token_expired` 的 stderr 断言在 T12 |
| #12 `--help` 在任意位置 | T14 | 属性式 + 裸 `help` 的五个数据用例与一个用法错误用例 |
| #13 只给分组 | T15 | 六个纯分组 + 裸顶层 |
| #14 体积 | T16 | 顶层 8,708 → ≤ 2,048 |
| #15 失败类别与运行时一致 | T17 | registry lint + 每个码一个 fixture |
| #16 `help env` | T18 | |
| #17 正常调用零改动 | T19 | golden 快照 diff |
| #18 无相对时间词 | T20 | 五个词 |
| #19 退出码三处一致 | T21 | 声明 / 顶层帮助 / README |
| #20 例子与下一步 | T22 | 纯解析入口 + 固定下一步表 |
| #21 帮助没有副作用 | T23 | `logout --help` |
| 指标：覆盖率 | T24 | 100% |
| 指标：首次找对率 | T25 | **已测量：95% 达标、中位数 5.5 未达标（见 §结果与 §已知问题 5）** |

## 用例

**通用前置**：`go build ./cmd/skillmaster` 通过；每个用例自己用 `t.Setenv` 设 `SKILLMASTER_SERVER` / `HOME`（`t.TempDir()`）；**无凭据的场景显式 `t.Setenv("SKILLMASTER_API_KEY", "")`**（空串等于没设），其余场景不设。用例之间不共享环境。**没有共享的命令树快照**：`tree_test.go` 每次用 `go/ast` 现扫源码，T01–T05、T14 各自从声明取路径——现扫比快照少一处会过期的副本。凡是「无凭据」的场景，`SKILLMASTER_SERVER` 都指向 `httptest` 的地址，或一个**本次运行随机端口**的 `http://127.0.0.1:<port>`（不用固定端口，否则那台机器上恰好有服务在听就不是「没人用过」），**不靠改 HOME 隔离钥匙串**。

| # | 前置 | 步骤 | 期望 |
|---|---|---|---|
| **T01** 覆盖 | 源码 + 声明里的 `Specs()`（不另存 fixture） | 对 56 条路径各跑 `--help` 与 `-h`；对六个纯分组、`skills my-tags` 与顶层各跑 `--help` / `-h` / `help` | 退出 0；stdout 非空；stderr 空；stdout ≤ 2048 字节；无 `\x1b\[[0-9;]*m` |
| **T02** 声明比对 | 同上 | 两个方向。**渲染 → 声明**：对每条命令把 `help.Render` 的文本按标签拆块，`参数：` 的 flag 行与 `声明.Flags ∪ Universal(cat, path)` 比，`字段：`、`退出码：`、`失败：`、`例子：`、`上级：`、用法行的位置参数逐项比。**代码 → 声明**：从派发函数的 `switch args[0]` 派生「路径 → 入口函数」，沿包内调用图闭包，把 `case "-…"` / `--x ==` 形态的字面量归到真正会读到它的每条命令上，与同一集合比（共享解析器因此自动归到每个调用它的命令） | flag 集合相等（含 `Aliases`）；每条命令到达的字面量 ⊆ 声明 ∪ `Universal`（**只按名字拒绝的除外**，逐条给了理由）；用法行的位置参数名字/必填/顺序相等；「描述」「用法」「输出」「失败」「退出码」非空；每类失败有非空「下一步」；**`Spec.Summary` ≤ 12 字（rune）、`Flag.Summary` ≤ 25 rune、`Failure.Summary` 与 `Failure.Next` ≤ 35 rune**（量说明，不量整行）；有 `--json` 的命令带「正文走 stdout、诊断走 stderr」且有一行 `字段：a, b, c`，**按顺序**等于 `JSONFields`；「退出码：」那一行的码集合 == `Exits`，每个 `Failure.Code` 在 registry 里的 `Exit` 都落在 `Exits` 里；`--flag <值>` 与 `--flag=<值>` 与声明的 `Sep` 一致；例子 0–2 条；含上级路径 + `--help`（**`login` / `logout` / `setup` 的上级行是 `skillmaster help`，不含 `--help`**，单独断言） |
| **T03** 反向绑定 | 同上 | 把每个声明的 flag 传进该命令的 `parseOnly`；再按声明顺序给**每一个**位置参数（必填与可选都算）各一个值，并给最后一个参数多一个 token | 每个 flag 被接受（不出现「不认识的参数」）；**每一个位置参数的 `Arg.Key` 都在**（可选的一并覆盖）；原样传递的位置参数其值等于 argv 里那一段，**多 token 合并的参数（`skills list` / `search` 的关键词）等于合并后的字符串**；两个 `Arg.Key` 相同时失败 |
| **T04** 长度与下一步 | 同上 | 对每条声明逐类失败读「下一步」 | 非空，且 ≤ 35 rune；例子条数与 #20 的下限一致 |
| **T05** 命令树 | 源码（`go/ast` 现扫） | ① 用合成树喂 `help.Validate`：一个节点没有声明；② 同一函数喂一个未登记前缀的新分组；③ 测试侧用 `go/ast` 扫包内所有 dispatch 的 `switch args[0]`（本版删掉 26 处帮助分支后，剩下的每一处都是 dispatch，无需排除）派生真实树，与声明比对；④ 断言每个 dispatch 函数都在登记表里 | ①② 返回错误且含路径；③ 派生集合 == 声明集合；④ 多出一个未登记的即失败 |
| **T06** 分组帮助 | 七个节点 | 各跑 `--help` / `-h` / `help` | stdout 的「子命令：」块（该行到下一个空行）解析出的集合 == 直接子节点集合；描述、用法、上级地址非空；每条子命令有非空说明且 ≤ 12 字；`skills my-tags` 的 `delete` 行含「只在控制台」；退出 0 |
| **T07** 顶层帮助 | — | `skillmaster help`、`--help`、`-h` | 三者逐字节相同；stdout、退出 0；≤ 2048 字节；七个名字各出现一次且同一行有 `<name> --help`；**四个退出类各占一行**；末行以 `文档：` 开头且其后有非空 token |
| **T08** 未知动词 | — | `skillmaster skill invok` | 退出 2；stdout 空；stderr 恰好一行；含 `invoke` 与 `skillmaster skill --help`；合计 ≤ 200 字节 |
| **T09** 未知顶层命令 | — | `skillmaster foo`；`skillmaster skil` | 各退出 2、stdout 空、stderr 一行、合计 ≤ 200 字节；两者都含字面量 **`skillmaster help`**；`foo` 不含「你是不是想用」，`skil` 含 `skill` |
| **T10** 用法错误 | 无凭据（随机端口服务器地址） | ① 验收 #8 的四个场景：`version list --jsno`；`skill invoke 坏地址`；`skill edit a/b`；`skill grep a/b pat --path`；② 四个前移校验：`skill cat a/b@ x` / `skill files a/b@` / `skill wc a/b@` / `skill grep a/b@ p`；③ **每一类本地用法错误各一条**：取值缺失与越界（`--offset`）、两个互相冲突的旗标（`skill request --status` 与 `--cancel`）、地址写错（`skill share bad` / `skill submit --to bad`）、`a/b@` 用在针对整个 skill 的命令上、只有地址没有句子的拒绝；④ **每一条命令各跑一次未知旗标** | 各退出 2（**四条被前移校验的命令必须报 2 而不是 4**）；stderr 以 `skillmaster <该路径> --help` 收尾；`--jsno` 那行含 `--json`；各合计 ≤ 200 字节；**没有句子的拒绝要补上该命令的用法行**；只拒绝的八条退出 1 且说出「控制台」或「网页」 |
| **T11** 六个退出码场景 | 假服务端：空数据、403 | `skills list`；`version publish`；`skills my-tags delete`；`skill grep` 对 403（假服务端回 403）；`skill invok`；`skills list --no-login` + 无凭据（随机端口服务器地址） | 依次 0 / 1 / 1 / 1 / 2 / 4；另跑 `skill grep` 对空命中：退出 1 且 stderr 含「没有命中」（既有行为，ADR 0087 记录） |
| **T12** 凭据不可用 | 假服务端按 `code` 回 401；`startLogin` 换成返回错误的 stub；`browserAvailable` 是变量（`TestMain` 装桩） | `skills list --no-login` 无凭据；**`skills list` 对 `unauthenticated` / `token_idle` / `token_expired` / `token_revoked`（走原始 `*api.Refusal` 那一支）；**`auth status` 对 `token_expired`：退出 4 且 stderr 含 `token_expired` 字面量****；`SKILLMASTER_API_KEY=not-a-key`；`skill grep` 对 `not-a-key` 与一种 401（`token_expired`；另外三个码由 `skills list` 那几行与单点测试覆盖）；**`version list --json` 对 `token_expired`**（走 `cliFailure.Exit` 那一支）；`skills list`（无 `--no-login`）+ 无凭据 + `startLogin` 失败；**`skill grep dev/x TODO` + 无凭据 + `browserAvailable=false`** | **前六个条目退出 4**（`--json` 那个也是 4，且 stderr 是 JSON 错误对象）；提示含该场景的下一步关键词；**后两个条目退出 1**（自动登录失败，与 T13 的「登录失败仍为 1」是同一判据的两个入口） |
| **T13** 不是 4 | 假服务端；`browserAvailable` 置 false 或 `startLogin` 返回错误；`authStatusWith` 注入一个读取报错的 store（它已经收 `credentials.EnvStore`） | `auth status` 对 `account_suspended`；`skill grep` 在 `browserAvailable=false`、无凭据时；`authStatusWith` 配报错 store；假服务端回 401 无 `code`；回 401 未知码 | 五个都退出 1；`account_suspended` 的 stderr 含「账号已被停用」且不含「登录」 |
| **T14** 任意位置 | 每条声明的 `Examples`（含取值 flag 的命令，例子里的值位置即在） | 把 `--help` 与 `-h` 各插到每个位置（0..n）；再对六个**裸 `help` 是数据**的用例查解析结果：`skill grep a/b help`、`skill read a/b help`、`skill download a/b --into help`、`skills search help`、`skills my-tags add help`、`skill show a/b help`（先查 `help.Requested` 断言解析结果是 `KindNone`；再把其中五个**执行一遍**——`skill download a/b --into help` 只查解析，执行会在当前目录建一个叫 `help` 的文件夹） | 插入后各退出 0，stdout 与位置 0 时逐字节相同（**包括插在 `--query` / `--into` 这类取值 flag 的值位置上**——那里从数据变成帮助）；六条的解析结果都是 `KindNone`；执行的那五个：stdout 为空、**不是帮助页**（stderr 无「上级：」，前四个也不出现「用法：」），无凭据时 `skill grep` / `skill read` / `skills search` / `skills my-tags add` 各退出 1，**`skill show a/b help` 退出 2**（它只接一个地址，`help` 是多余的位置参数）；无凭据的服务器地址下 `--help` 仍退出 0 |
| **T15** 只给分组 | — | 六个纯分组各跑一次；裸 `skillmaster` | 各退出 2、stdout 空、stderr 是命令表 |
| **T16** 体积 | — | `skillmaster help` | 8,708 → ≤ 2,048 字节；20 条既有帮助都改为 stdout 且满足 T01 |
| **T17** 失败类别 | 源码 + 假服务端 + `cmd/skillmaster/testdata/error-fixtures.json`（每项 `code` + `argv` 必填；`status` / `body` 由假服务端返回，缺省时不发响应体；`dead` 表示这条要的是「连不上」，用死端口而不是假服务端——所以不是每一项都带全部五个键） | lint：处理函数构造的错误码都来自 registry；对每个声明的码跑它的 fixture | 无未登记码；每个码都被真实触发；「仅防御」清单为空或逐条有理由 |
| **T18** `help env` | — | `skillmaster help env`；再跑 `skillmaster help` | 前者退出 0、stdout、≤ 512 字节、四个变量名都在；后者**不再列这四个变量，且有一行含 `help env`**（指向它） |
| **T19** golden | 用本版代码改动前的提交编出的二进制先录：`cmd/skillmaster/testdata/golden/index.json`（每条一个 case：`argv` / `status` / `stdout` / `stderr`）+ `response.json`（假服务端的答复，按路径覆盖）+ `expected-changes.json` | 对录下的每条 argv 重放到当前实现 | 逐字节相同，**除了** `expected-changes.json` 里列出的那些——该文件的粒度是 `{case, field, reason}`，一条差异一项；**多一条、少一条都失败**（列了但不再发生的也失败）。假服务端默认回 404；`version list --json` 有一条真实的成功对象，钉住 `--json` 的成功形状。`login` / `logout` / `setup` / `skill download` 会动本机，不录 |
| **T20** 相对时间词 | T01 与 T18 的 stdout 快照 | 扫 `目前` / `即将` / `暂时` / `以后` / `未来`（含 `help env` 那一页） | 零命中 |
| **T21** 三处一致 | — | 解析**每条声明的 `Exits`**、顶层帮助、`skillmaster-cli/README.md` 的四行表；另核对 `Code.Exit` 的并集 | 三处投影出的码集合都是 `{0,1,2,4}` 且含义一致；registry 里的**非通用码**每个都至少被一条声明引用（通用码 `usage` / `network` / `server_error` / `insufficient_permission` / `not_found` 不查孤儿） |
| **T22** 例子与下一步 | 假服务端 | 对「2+ 位置参数或 2+ 改变行为的 flag」的命令与三条主链路命令，用 `parseOnly` 跑例子 argv；读下一步 | 每个例子只解析不执行、不报用法错误；下一步表四条都在：`skills search` → `skill invoke`、`skill invoke` → `skill read`、`skill submit` → 浏览器点、`version publish` → `version list` |
| **T23** 无副作用 | 一台**已有凭据**的机器（用注入的 store 装一份，`pointTheMachineAt`） | `run(ctx, []string{"logout","--help"})` | 返回 nil；stdout 是帮助；store 的删除次数为 0、凭据仍能读出来。**与 PRD #21 的偏差**：没有用 `credentials.Open` 播种真实 store（钥匙串 / 0600 文件），所以它证的是「help 在 dispatch 之前被回答」，不是「真实 store 分支也安全」——记在 §已知问题 3 |
| **T24** 覆盖率 | — | 命令树测试打印两个数 | **有声明并有帮助的命令路径 ÷ 命令路径条数 = 100%（当前 56/56）**；分母由命令树测试自己派生、不手写（PRD §验收 #1 的「路径集合由测试产出」）；六个纯分组、顶层与 `help env` 是节点或 topic，不在分母里 |
| **T25** eval | `SKILLMASTER_HELP_EVAL=1`；harness 先找 `claude` 可执行文件，找不到就整条跳过（`t.Skipf`） | 见 §环境与数据与 §结果 | 首次找对率 ≥ 80%；调用次数中位数 ≤ 4（**没在步数上限内作答的题按答错计、调用数记上限值**）；模型不可调用则整条记「**未验证**」（区别于「未执行」与「失败」），且交付说明里必须写明指标未验证，不得当作达标 |

## 人工评审清单

PRD 验收 #2 里明确「不做自动判定」的**三项**（前两项出自 PRD #2，第三项出自 TD §数据模型的 `JSONFields` 行），交付前由人逐条看一遍，结论写进 §结果：

- 每条帮助只列了**改变行为的** flag（声明只登记这类 flag 是判据，但分类对不对要靠人看）。
- 正文里没有背景与设计理由（比如「为什么 skill 与 skills 是两个组」）。
- 按子动作分支的命令（`skill request`）在 `Output` 里注明了「字段按子动作不同」。

## 环境与数据

**单元与 CLI 级测试**：`go test ./...`（在 `skillmaster-cli/` 下跑）。CI 在 `.github/workflows/cli.yml` 里跑同一套（ubuntu-latest：gofmt 检查、`go vet`、`go test -count=1 ./...`），**文件凭据 store 那一支在那里真跑**。假服务端沿用现有写法——`httptest.NewServer` 内联在用例里（`cmd/skillmaster` 下 12 处：`main_test.go` 5、`keys_test.go` 4、`v14_test.go` 1、`version_test.go` 1、`status_test.go` 1；全模块共 45 处，其余在 `internal/api` / `internal/auth`）；服务端地址用 `t.Setenv("SKILLMASTER_SERVER", server.URL)`。**不用 `HOME` 隔离钥匙串**：`internal/credentials/keychain_other.go` 让 macOS 永远走钥匙串，隔离靠「服务器地址从没用过」这条更硬的判据；需要写入的用例（T23）用 `t.Cleanup` 删除条目。

**fixture argv**：**不另开一份 fixture 文件**——参数样例就是每条声明里的 `Examples`，T14 / T22 读的也是它（`parseonly_test.go` 的 `splitExample` 负责把它拆成路径与参数）；T01 走 `Specs()` 的路径、T03 用合成 argv（`value0` / `value1` …）与最后一个位置参数的第二个 token。这样「帮助里写的例子」与「测试跑的参数」是同一份东西，不会出现两份各自漂移。约束照旧：

- **含取值 flag 的命令，例子至少要有一个值位置**（T14 要断言「插在值位置上也会打印帮助」）。
- **位置参数可以多于声明**：多出来的 token 属于最后一个可合并的位置参数（`skills list` / `search` 的关键词）；T03 对它们断言合并后的字符串。
- 每条命令目前都有例子，所以 T14 没有排除项。

**可注入的接缝**：`authStatusWith`（`main.go` 的 `auth` 一节）已经收 `credentials.EnvStore`，T13 的「读不出来」用它注入一个报错的 stub，不必让真实钥匙串出错；`browserAvailable` 与 `startLogin` 都是变量（`TestMain` 把后者换成一个必定失败的桩），T12 / T13 用它们让自动登录确定地失败。**行号不写在这里**：实现期每加一处就会失效，符号名足够定位。

**eval harness（T25）的参数钉死如下**，改动它等于换一把尺，必须同时改本文件：

| 项 | 值 |
|---|---|
| 被测模型 | **`deepseek-v4-flash[1m]`**（固定 ID，不用 `latest`）。**2026-10-24 由产品负责人改定**：本机唯一可调用的端点是 DeepSeek（`ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic`），原定的 `claude-sonnet-5-5` 在这里不可达。**这是一把不同的尺**——换回 Sonnet 必须重跑，两个数不能互换；阈值仍是 80% / ≤4 |
| temperature | **不可设**（Claude Code 的无头模式不暴露 sampling 参数）。原定 `0` 因此落不了地：改由 **3 次运行取中位数** 吸收抖动，并在结果里写明 |
| 被测对象 | Claude Code 无头模式：`claude --bare --print … --model deepseek-v4-flash`。`--bare` 跳过 CLAUDE.md、hooks、skills 与 memory；工作目录是只放了一个 `skillmaster` 二进制的临时目录；除 Bash 外的工具全部移除（`--disallowedTools`）。**只允许 `./skillmaster … --help`**，执行别的命令记为违规、该次记错 |
| 提示词 | 固定模板：给出任务的一句话描述，声明「你只能运行 `skillmaster ... --help` 形式的命令」，要求最后输出**一条**完整命令 |
| 步数上限 | 8 次 `--help` 调用；超过记答错，**调用数记 8** |
| 运行次数 | 3 次，取中位数 |
| 题目 | `testdata/help-eval.json` 20 题：六个分组各 ≥ 2 题、一条多步消费链路、一条从真实错误输出出发的恢复题 |
| 判分 | 作答路径 == 期望路径（叶子命令；顶层 `--help` 是导航不是作答）；调用数 = 从开始到作答的 `--help` 次数（含顶层） |
| 作答协议 | 工具调用与作答分开记：任何被 harness 执行的 `skillmaster … --help` 都是**调用**；**最后一条助手消息**必须恰好是一行、以 `skillmaster ` 开头且不是一次工具调用，才算**作答**。harness 从不执行作答；给不出作答按答错计 |

**跑法**：

```bash
go test ./...                                   # 全部单元与 CLI 级用例

# T25 的跑法（会真的调用模型：20 题 × 3 次 = 60 次无头调用，本机约 3 分钟）：
SKILLMASTER_HELP_EVAL=1 go test -count=1 -run TestHelpEval ./cmd/skillmaster -v
# 需要留下原始结果时：再加 SKILLMASTER_HELP_EVAL_OUT=<文件>，会写出每题的作答、调用数与违规。
```

## 结果

实现已落地，三轮三路循环审计后的状态如下（`cd skillmaster-cli && go test ./...` 全绿，9 个包）。

| 用例 | 结果 | 证据 |
|---|---|---|
| T01 | 通过 | `TestHelpNeedsNoCredential`（62 条路径 × `--help` 与 `-h`，空 HOME、死端口）、`TestEveryHelpFitsTheBudget` |
| T02 | 通过 | 渲染 → 声明：`TestRenderedHelpAgreesWithItsDeclaration`（逐命令按标签拆块比 参数 / 字段 / 退出码 / 失败 / 例子 / 上级 / 用法位置参数）；代码 → 声明：`TestEveryCommandOnlyReadsTheFlagsItsHelpLists`（派发 `switch` → 调用图闭包 → 每条命令读到的 flag ⊆ 其帮助）；另有 `TestDeclarationsMatchTheCommandTree`、`TestUniversalFlagsAreOnEveryCommand`、`TestHelpSentencesFitTheirLimits`。两者都做过反向验证 |
| T03 | 通过 | `TestEveryDeclaredFlagIsAccepted`、`TestEveryDeclaredPositionalBinds`（**可选位置参数与多 token 合并都覆盖**：`skill submit [名字\|目录]`、`skill wc [相对路径...]`、`skills list` / `search` 的关键词）；**`parseOnly` 读声明而非 handler**（TD §接口 已写明取舍） |
| T04 | 通过 | `TestHelpSentencesFitTheirLimits`（Summary ≤12、Flag ≤25、Failure 两段 ≤35） |
| T05 | 通过 | `tree_test.go` 两个测试 + `TestValidateRejectsGaps` |
| T06 | 通过 | `TestGroupHelpListsItsChildren`（7 节点 × 3 写法；含 `skills my-tags` 的 `delete` 行断言「只在控制台」） |
| T07 | 通过 | `TestTopLevelHelpShape` |
| T08 | 通过 | `TestUnknownNamesAreOneLine` |
| T09 | 通过 | 同上（`foo` 无建议、`skil` 有） |
| T10 | 通过 | `TestUsageErrorsEndAtTheHelpAddress`（33 条：验收 #8 的原始 8 条 + 穷举派发的 25 条，覆盖取值缺失/越界、旗标冲突（`--status` 与 `--cancel`）、坏地址、`a/b@` 四类）+ **`TestEveryCommandRefusesAnUnknownFlagAtItsOwnHelp`**（每一条命令各一次；只拒绝的八条断言退出 1、stderr 含「控制台」或「网页」）+ `TestAUsageErrorSaysWhatWasWrong`（只有地址的用法错误要补上该命令的用法行）。**例外**：`help nope` 的收尾是「上级：skillmaster help」——主题不是命令路径，指一个不存在的 `--help` 会把读者送到错的地方 |
| T11 | 通过 | `TestExitCodeScenarios`（0/1/1/1/2/4）、`TestGrepFailureClasses`（403→1、空命中→1 且含「没有命中」） |
| T12 | 通过 | 场景级：`TestCredentialFailuresByScenario`（四个 401、`--json` 那条、`not-a-key` 三条命令、`--no-login`、无凭据无浏览器→1、`auth status` 对 `account_suspended`）+ `TestAuthStatusKeepsTheServersCode`；单点：`TestReportErrorStatus` |
| T13 | 通过 | 场景级：`TestCredentialFailuresByScenario` 的 `account_suspended`（**全路径跑 `auth status`**：退出 1、stderr 含「账号已被停用」且**不含「登录」**）/ 无码 401 / 未知码三行 / **`skill grep` 无凭据无浏览器 → 1**；`TestAuthStatusCannotReadThisMachinesCredential`（本机凭据读不出来 → 1）；单点：`TestReportErrorStatus` |
| T14 | 通过 | 插入法（执行级）：`TestHelpIsRecognisedAtEveryPosition`（**每条声明的每条例子** × 每个位置 × `--help`/`-h`，含值位置；少于 40 条例子直接失败）。裸 `help` 那六个用例：先在**解析级**断言 `help.Requested == KindNone`，再把其中五个**执行一遍**（`TestABareHelpWordInAValuePositionIsData`：stdout 为空、不是帮助页；`skill grep` / `read` / `skills search` / `my-tags add` 各退 1，`skill show a/b help` 退 2）；`skill download a/b --into help` 只做解析——执行会在当前目录建一个叫 `help` 的文件夹 |
| T15 | 通过 | `TestBareGroupsPrintTheTableToStderr`（顶层断言 `命令：`，分组断言 `子命令：`） |
| T16 | 通过 | `TestTopLevelHelpShape`（≤2048 字节） |
| T17 | 通过 | `testdata/error-fixtures.json` 31 条逐码触发 + `TestHandlersOnlyUseRegistryCodes`（复合字面量**与赋值**两种写法都扫：`failure.Code = "…"` 是分类器写码的方式）；**`login_failed` 是唯一的「仅声明」码**，理由写在测试的 `defensiveCodes` 里。**口径**：带 `--json` 的 fixture 断言错误对象里的码，不带 `--json` 的只断言退出码与「有话说」——见 §已知问题 4 |
| T18 | 通过 | `TestHelpEnvPage`（≤512 字节、四个变量、顶层只给指针） |
| T19 | 通过 | `testdata/golden/`：用 `7938773`（本版代码改动前的提交）编出的二进制录 60 条文档示例（含一条**成功的 `--json` 对象**），与新实现逐字节比对；**差异恰好是 `expected-changes.json` 里的三条**（`skill create` 用法错误 1→2 与那一行文案、`skill grep` 404 的 2→1） |
| T20 | 通过 | `TestNoRelativeTimeWordsAnywhere`（含 `help env` 与顶层） |
| T21 | 通过 | `TestDeclarationsProjectEveryExitClass`、`TestTopLevelHelpCarriesEveryExitClass`、`TestCLIReadmeCarriesEveryExitClass`、`TestEveryRegisteredFailureIsDeclaredSomewhere` |
| T22 | 通过 | `TestEveryExampleParses`、`TestMainChainNextSteps` |
| T23 | 通过 | `TestHelpHasNoSideEffects`（注入 store：`logout --help` 一次也不调 `Delete`）+ **`TestLogoutHelpLeavesTheRealCredentialAlone`**（装回真实的 `credentials.Open`，在本次运行唯一的服务器地址与临时 HOME 下播种一份 0600 文件、跑 `logout --help`、再经真实 store 读回；钥匙串那一支由 `SKILLMASTER_REAL_KEYCHAIN_TEST=1` 显式开启——macOS 上它会弹授权框）。文件那一支在 CI 的 ubuntu-latest 上真跑（`.github/workflows/cli.yml`：无 session D-Bus，`Open` 落到文件 store） |
| T24 | 通过 | `TestHelpCoverage` 断言 56/56（日志行 `coverage: 56/56` 只在 `-v` 时可见） |
| T25 | **失败（一个指标未达标）** | 2026-10-24 实测（`deepseek-v4-flash[1m]`，20 题 × 3 次，六路并发；原始结果 `SKILLMASTER_HELP_EVAL_OUT`）：三次运行 **19/20、17/20、20/20** → **首次找对率 95%（≥ 80%，达标）**；`--help` 调用数 **5.0 / 6.0 / 5.5** → **中位数 5.5（> 4，未达标）**。未答对的 5 次逐条见 §已知问题 5 |

**T19 覆盖什么、不覆盖什么**：golden 跑 60 条文档示例：假服务端默认回 404（钉参数解析、错误渲染与退出码），另有一条 `version list --json` 走 `by_path` 的 200 响应（钉一条**成功的 JSON 对象**）。**成功输出**由各命令自己的测试钉住——它们断言逐字节的 stdout；那些断言里**有多处随本版改过**（帮助改到 stdout、退出码改动都落在同一批文件里），所以「成功输出没动」的证据不是「测试一行未改」，而是 golden 里那 60 条逐字节比对加上 `expected-changes.json` 恰好三条。`login` / `logout` / `setup` / `skill download` 不在 golden 里（分别会开浏览器、删凭据、写文件、写目录），各有自己的测试。

结果共四种：**通过 / 失败（附输出）/ 未执行（附原因）/ 未验证（条件不具备，如模型不可调用）**；`未验证` 要在交付说明里单列，不得当作通过。

## 已知问题

1. ~~逐命令的 flag 集合比对没有做~~（**已做，2026-10-24**）。`TestRenderedHelpAgreesWithItsDeclaration` 把渲染文本按标签拆块与声明逐项比；`TestEveryCommandOnlyReadsTheFlagsItsHelpLists` 从派发 `switch` 派生入口、沿包内调用图闭包，把 flag 字面量归到真正读它的每条命令。两者都做过反向验证：抽掉 `skill invoke` 的 `--offset` 声明、或在 `Render` 里漏掉一个 flag，各自立刻变红。
2. ~~T25 的指标未验证~~（**已测，2026-10-24**）。harness 与 `testdata/help-eval.json` 已交付；首次找对率 95% 达标，**调用中位数 5.5 未达标**（目标 ≤ 4），原因见 §已知问题 5。
3. ~~T23 与 PRD #21 的写法有偏差~~（**已补，2026-10-24**）。`TestLogoutHelpLeavesTheRealCredentialAlone` 装回真实的 `credentials.Open` 并在 0600 文件里播种再回读；**钥匙串那一支仍要人显式开启**（macOS 每次 `go test` 都会弹一次授权框，且授权绑在当次未签名的测试二进制上，留不住——见 `TestMain` 的注释）。这一条不是缺陷，是 AC 写明的「CI 无钥匙串时钥匙串那支跳过」。
4. **不带 `--json` 的 fixture 只断言退出码与「有话说」**（已复现，口径限制）。`TestEveryRegisteredCodeIsReallyTriggered` 对带 `--json` 的 fixture 断言错误对象里的码；其余 6 条（`skill_not_joinable`、`library_reference_missing`、`bad_pattern`、`too_expensive`、`usage`、`refused`）的输出是给人看的中文句子，没有机器可读的码可断言。要收紧只能给这几条命令加 `--json`，那是新增能力，超出本版范围。

5. **T25 的调用中位数未达标（5.5 > 4）**，5 次未答对逐条如下：
   - `request-status`（run 0）：作答正确，但中途执行了 `ls -la`——harness 判违规。按口径「只允许 `--help`」，这算答错；它说明模型在只读帮助的约束下仍会伸手看目录。
   - `auth-status-usable`（run 1）、`tag-add-private`（run 1）：作答正确，但 `--help` 用满 8 次后仍继续调用，按上限记 8、判答错。
   - `mytags-delete`（run 1）：8 次内没给出作答。这道题的正确答案是「只在控制台做」的拒绝，模型反复确认它到底存不存在。
   - **中位数 5.5 的成因**从轨迹看得很清楚：自然路径是「顶层 → 分组 → 叶子」3 次，多出来的 2–3 次是模型**去别的分组确认没有更贴的命令**（例如答 `auth status` 那次又开了 `skill` / `skills` / `login` / `version` 四个帮助）。这不是帮助缺内容，而是模型在验证；本版没有据此改帮助——为一次测量调产品，是把尺子反过来画靶心。

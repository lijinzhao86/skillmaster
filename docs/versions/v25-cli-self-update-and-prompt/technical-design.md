# v25-cli-self-update-and-prompt · 技术设计

> **状态**：已定稿（2026-10-27，与 PRD 同步锁定）
> **所属版本**：[`README.md`](README.md)

<!--
本文只写「怎么实现」，不复述 PRD 的需求与 ADR 的理由。
每一条对代码的断言都指向真实存在的文件与符号（dev-sop 阶段 2 的判据）。
-->

## 架构

### 组件

| 组件 | 位置 | 职责 |
|---|---|---|
| `internal/version` | 现有 | 新增 `UpdateBase`（发布基址，编译期注入；空 = 不参与自更新） |
| `internal/updater` | **新增** | 检测、节流、下载、校验、换位、状态文件、锁 |
| `internal/prompt` | **新增** | 规则段文本（`go:embed`）、标记段的抽取与替换、本机记录 |
| `cmd/skillmaster/update.go` | **新增** | `update` 命令；`run()` 里的自动检查挂钩；helper 模式入口 |
| `cmd/skillmaster/prompt.go` | **新增** | `prompt` 组：`status` / `print` / `install` / `update` |
| `cmd/skillmaster/main.go` | 改 | 分发加 `update` / `prompt`；`run()` 里的挂钩与退出前的收尾 |

服务端**零改动**：这一版没有任何新接口。规则段的权威从平台技能搬到 `internal/prompt/block.md`（[ADR 0102](../../decisions/0102-the-rule-block-ships-with-the-cli.md)）。

### 一次命令的生命周期

```
main()
 └─ run(ctx, args)
     ├─ ① helper 模式：SKILLMASTER_UPDATE_TARGET 非空**且自己的文件名是 `.new-` 产物** → 只做换位（重试 rename），返回
     ├─ ② --version / 帮助 / 裸命令 → 照旧处理，**不检查**
     ├─ ③ prompt 过期提示（纯本地判断：记录摘要 vs 二进制摘要 + 文件在不在，命中才打一行，同一摘要只一次）
     ├─ ④ updater.Prepare：先拿锁，再读 update.json 判定（24h + 二进制指纹 + 通道）
     │     ├─ 该检查 → GET <base>/version.txt（≤1.5s）→ 写检查点
     │     └─ 有新版本 → 起后台下载（goroutine；自己的 ctx，由 Drain 的 10 s 预算取消）
     ├─ ⑤ 执行命令本身
     └─ ⑥ defer：updater.Drain（最多 10s）→ 校验 → 换位 → 一行 stderr
```

**④ 失败一律静默**：断网、超时、5xx、解析失败都不影响命令。**③ 与 ⑤ 互不影响**。

### 换位

| 平台 | 动作 |
|---|---|
| macOS / Linux | 下载到同目录 `<exe>.new-<rev>`（`0755`）→ `os.Rename` 覆盖 `<exe>`。运行中的旧 inode 由内核回收，**不调任何删除原语**（[ADR 0101](../../decisions/0101-the-cli-never-deletes-files.md)） |
| Windows | 同上下载 → **父进程 spawn 新二进制**（`SKILLMASTER_UPDATE_TARGET=<exe>`，`DETACHED_PROCESS`）→ 父进程退出 → helper 每 50 ms 试一次 `os.Rename(自己, 目标)`，最长 60 s → 退出。目标路径**全程存在**，没有 `.old` |

**helper 就是新二进制本身**，没有第二个可执行文件要维护。它不读参数、不读 stdin、不打印：

- 目标在父进程退出前被锁（Windows）→ `Rename` 返回 sharing violation → 继续重试；
- 另一台进程也在跑 → 同样重试到超时 → 留一个 `<exe>.new-<rev>`，下一次运行由 `pending` 记录重试（见下）。

**`pending` 记录是失败恢复**：`update.json` 里记下「已经下好、还没换位」的文件与版本；下一次运行在拿锁之后、检查之前先尝试应用它，三种结果分开处理：**rename 成了 → 清记录**；**起了一个 helper（Windows）→ 留着记录**，直到某次运行发现文件已经不在了（换位真的发生）或 helper 反复起不动（三次后丢记录、恢复检查）；**被拒绝 → 留着记录，把「装不进去」说一次，然后照常检查**（不能因为换不动就再也不查了）。所以「下好了但没换成」既不会丢，也不会重下 8 MB。**每个平台都写**，不只在 Windows：进程在「写完文件、还没 rename」之间被杀掉的窗口在任何系统上都存在。**应用前会比一次版本**：记录里的版本不比当前二进制新就丢掉记录——用户可能已经按提示重跑过安装命令，拿旧文件盖上去就是静默降级。**换位一直没成（例如目标文件被长期占用）时，连同第一次最多四次 helper 就丢掉记录**——否则每条命令都会再起一个换不动的 helper，而检查永远轮不到。

### 并发

`<config.Dir()>/update.lock` 上用**非阻塞独占锁**（unix `syscall.Flock` + `LOCK_EX|LOCK_NB`；Windows `windows.LockFileEx` + `LOCKFILE_EXCLUSIVE_LOCK|LOCKFILE_FAIL_IMMEDIATELY`）。拿不到就**跳过本次检查与下载**——另一个进程正在做同一件事。锁文件本身**永不删除**（ADR 0101），所以它的存在不代表有人持锁。

### 一个进程都不常驻

没有 daemon、没有定时器、没有开机项。所有工作都在一次命令的生存期内完成；Windows 的 helper 活到换位完成（毫秒到一秒）。

## 数据模型

### 状态文件（都在 `config.Dir()`，JSON，`0600`，`O_CREATE|O_WRONLY|O_TRUNC` 原地覆写）

**`update.json`**

| 字段 | 类型 | 含义 |
|---|---|---|
| `checked_at` | RFC 3339 | 上一次**成功或失败**的检查时刻（失败也记，否则网络抖动会让每条命令打网） |
| `base` | string | 这次检查用的基址；与当前基址不同 → 立刻重查（换通道/换开发环境） |
| `binary_size` / `binary_mtime` | int64 / RFC 3339 | 被检查的那个二进制的指纹；变了立刻重查（重装、手工替换） |
| `pending` | object? | `{path, revision, version, spawns}`：已下好、未换位；`version` 用来判「它是不是比现在这个还旧」，`spawns` 数起了几次 helper |
| `notified` | string | 「装不进去」提示过的版本串，每个版本只提示一次 |

**`prompt.json`**

| 字段 | 类型 | 含义 |
|---|---|---|
| `file` | string | 规则段写进的那个文件（`prompt install --file` 记下的） |
| `digest` | string | 那次写入的标记段摘要（`sha256:<hex>`） |
| `version` | string | 写它的时候 CLI 的 `version.String()`，只用于显示 |
| `notified` | string | 提示过的摘要，同一个新摘要只提示一次 |

### 远端形状

`<base>/version.txt` 一行，两种形状（[`RELEASE.md`](../../../skillmaster-cli/RELEASE.md) §6）：

- 正式：`1.0.2`
- 内测：`1.1.0-beta (1485383)`——版号固定，**提交号是构建身份**（[ADR 0099](../../decisions/0099-the-cli-has-a-beta-channel.md)）

**比较规则一条**（这是本版唯一容易写错的地方）：

1. 远端串 == 本机 `version.String()` → 不更新。
2. 两边都有提交号且版号相同 → 提交号不同就更新（内测的全部情况）。
3. 其余按版号做 semver 比较，**只升不降**。
4. 本机 `Revision` 为空（正式版）而远端有提交号 → 按 3。

### 本地文件名

| 名字 | 位置 |
|---|---|
| `skillmaster.new-<rev>`（Windows 加 `.exe`） | 与可执行文件同目录 |
| `update.json` / `prompt.json` / `update.lock` | `config.Dir()` |

## 接口

### CLI 命令

#### `skillmaster update [--check]`

- 不受 24h 节流，也不受 `SKILLMASTER_AUTO_UPDATE=0` 影响（那是「自动」的开关，不是「能不能手动更新」的开关）。
- `--check` 只报告，不下载：`有新版本：1.1.0-beta (a1b2c3d) → 1.1.0-beta (9f8e7d6)` 或 `已是最新（1.1.0-beta (a1b2c3d)）`，退出 0。
- 不带 `--check`：下载 + 校验 + 换位；打印 `下载 旧 → 新 …`，成功再打印 `已更新到 …，下一条命令生效`。
- 拿同一把更新锁（拿不到就报「另一个 skillmaster 正在更新」，退出 1）；只在官方安装位置动手，别处退出 1 并给重跑安装命令的办法。
- `SKILLMASTER_UPDATE_BASE` 覆盖基址：**镜像与测试用的接缝**，优先于编译进去的通道，不改变二进制自称的身份。
- 失败：网络/校验 → 1，并说下一次怎么办；安装目录写不进去 → 1，并给重跑安装命令那一句。
- 退出码：`0,1,2`。

#### `skillmaster prompt`（组）

四个子命令，**都不读 stdin、不打开浏览器**：

| 子命令 | 行为 | 退出 |
|---|---|---|
| `prompt status` | 打印：记录的文件、记录里的摘要、文件里现在的摘要、二进制自带的摘要、结论；不一致时附逐行 diff。文件是最新而记录落后时，顺手把记录修正 | 0 一致；1 需要动作（不一致 / 没记录 / 文件或标记段不见了）；2 用法 |
| `prompt print` | 把二进制自带的标记段原样打到 stdout（断网可用） | 0 |
| `prompt install --file <路径> [--yes]` | 文件不存在 → 创建；没有标记段 → 末尾追加（前面补一个空行）；已有相同标记段 → **只记录、不写文件**；已有不同标记段 → diff，没 `--yes` 退出 1 | 0,1,2 |
| `prompt update [--yes]` | 用记录的路径；没有记录 → 指到 `install`（1）；文件/标记段不见了 → 指到 `install`（1）；相同 → `已是最新`（0，并把落后的记录修正）；不同 → diff，没 `--yes` 退出 1；**写入前再读一次文件，与读到的内容不一致就拒绝**，让人重跑 | 0,1,2 |

**helper 不是命令**：它由环境变量 `SKILLMASTER_UPDATE_TARGET` 触发，不出现在帮助里、不进命令表、不接受参数。

### Go 接口

```go
package updater // internal/updater

type Options struct { Base, Dir, Target, Current, GOOS, GOARCH string; Client *http.Client; Now func() time.Time }

// Prepare 拿锁 → 应用上一条运行留下的 pending → 判定节流 → 检查 →（有新版本时）起下载。
// notice 是一行要先说的话（上一条命令留下的 pending 被应用了）；没有就是空串。
func Prepare(ctx context.Context, o Options) (*Job, string, error)
func (j *Job) Drain(budget time.Duration) Outcome   // Outcome{Version, Scheduled, CannotWrite, Verify, Suppress, Err}

func Apply(staged, target string) (ApplyResult, error)  // unix: rename；Windows: spawn helper（Scheduled=true）
func RunHelper(target string) error
func IsHelper() bool                                   // TargetEnv 设了、且自己的文件名是 .new- 的产物
func AcquireLock(dir string) (unlock func(), ok bool, err error)
func Check(ctx context.Context, base string, client *http.Client) (Release, error)
func Fetch(ctx context.Context, base string, release Release, dir, goos, goarch string, client *http.Client) (string, error)
func Newer(current, remote Release) bool
```

```go
package prompt // internal/prompt

func Block() []byte                       // go:embed block.md，含两行标记
func Digest() string                      // sha256(Block())
func DigestOf(block []byte) string
func Locate(path string, file []byte) (Region, error)   // ErrMissing / *AmbiguousError
func BlockIn(path string, file []byte) ([]byte, Region, error)  // 换行归一之后
func Normalise(data []byte) []byte
func Replace(file []byte, region Region, block []byte) []byte
func Append(file, block []byte, crlf bool) []byte
func UsesCRLF(file []byte) bool

type Record struct { File, Digest, Version, Notified string }
func Load(dir string) (Record, bool, error)
func Save(dir string, r Record) error
```

### 发布流水线

两个 workflow 的 `-ldflags` 各加一个变量，与 `DefaultServer` 同一行、同一条路：

```
-X …/internal/version.UpdateBase=https://dl.skillmasterai.com[/beta]
```

并在现有的「从包里 grep 注入值」那条断言旁边加一条同样的断言——`-X` 指向不存在的符号不会报错，只有 grep 能证明它进去了。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| **P0** | 规则段进二进制 + `prompt` 四个子命令 + 记录与提示（纯本地，不联网） | 12、13、14、15、16（`prompt` 部分）、17 |
| **P1** | 检测 + 节流 + `update --check` + `update`（只报告，不换位） | 1、4、5、6、7、9（`--check`）、19（`--check` 那一半） |
| **P2** | 下载 + 校验 + 换位（unix rename / Windows helper）+ 并发锁 + `pending` 恢复 + 官方位置闸门 + CI | 2、3、8、9（安装）、10、11、16、19 |

顺序理由：P0 完全不碰网络与二进制替换，可以先合、先让人用上「规则段不再失联」；P1 只读远端；P2 是唯一会改本机可执行文件的部分，单独验收。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | 检查失败也写检查点，会不会让「今天刚断网」的用户 24 小时内不再重试？ | 会，且是有意的：不记就会每条命令打一次网。想立刻查用 `update` | 真实用户反馈「一直没更新」时再调（例如失败重试改成 1 小时） |
| Q2 | 10 秒内下不完就丢弃，会不会在慢网用户那里每次都重下 8 MB？ | 接受；命令通常几秒内结束，而 8 MB 在 3G 上也只要十几秒 | 若实测频繁，下一版做断点续传（要一个 `.part` 文件，同样不删） |
| Q3 | 官方安装位置之外的二进制怎么判定？ | **已实现**：先把二进制自己的符号链接解析掉（macOS 上 `os.Executable` 不解析、Linux 会），再比较目录；只认两个默认目录。解析后落在别处（例如 `~/.local/bin/skillmaster → ~/tools/...`）就按「不在官方位置」处理。自动路径静默跳过，`update` 拒绝并指路，`--check` 仍可报告 | `SKILLMASTER_INSTALL_DIR` 的用户出现后，再把「安装时写下的路径」记进状态 |
| Q4 | `prompt status` 在「没有记录」时退出 1 会不会让脚本误判为错误？ | 保持 1：它表示「有一件事要你做」，与 v16 的 `version publish` 拒绝同一种语义 | 有脚本把它当错误时改成 0 + 提示行 |
| Q5 | Windows helper 的自重命名在某些 EDR 下被拦，怎么办？ | 拦了就是换位失败：`.new` 留在安装目录，下一次运行再起一次 helper（连同第一次最多四次），之后丢掉记录、恢复检查。**父进程看不到 helper 的结果**，所以这条路径上没有「重跑安装命令」那一行——用户会看到重复的「已下载…自动替换」直到它成功 | 收到第一批误报后，与签名（PRD 不做 #1）一起解决 |
| Q6 | 规则段文件里的标记段出现多次怎么办？ | 拒绝并指出每一处行号，不猜哪一段是真的 | 实现后看有没有人手工复制出两份 |
| Q7 | 内测通道按「提交号不同就是更新」比较，是否等于允许回退？ | 是的：内测的定义就是跟着 main 的最新构建走，标记改回旧提交时内测用户会退回去。正式通道没有这个问题（站点只往前走） | 内测期结束、需要「内测也按版本号排序」时，改 `Newer` 并开 ADR |
| Q8 | `SKILLMASTER_UPDATE_BASE` 要不要限制成只有 dev 构才认？ | 不限制：测试与镜像需要它，而它只能把「这一次运行」指向别处，改不了二进制自称的通道 | 有人误用它切轨道时，再考虑加一道提示 |

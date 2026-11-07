# v51-skill-runtime · 技术设计

> **最后更新**：2026-11-08
> **所属版本**：[`README.md`](README.md)
> **只写与 [`docs/current/`](../../current/README.md) 的差异**：这一版新建了什么、改了什么、删了什么。系统现状不在这里复述。

## 架构

三处改动，各自独立可交付：

### 1. 可执行位贯穿整条搬运链

```
作者手写 / GitHub 仓库
   ↓  zip 上传                    ↓  GitHub tar
ZipReader                        GitHubTar
(ZipArchiveEntry.getUnixMode)    (TarArchiveEntry mode & 0111，已有)
   ↓                              ↓
IngestedFile(relpath, bytes, executable)   FetchedFile(relpath, content, executable)
   ↓                              ↓
        SubmitSkillUseCase → ManifestEntry(+ mode) → version_file.mode
   ↓
SkillDistributionService（改用 ZipArchiveOutputStream + setUnixMode）
   ↓
skmr get --all → download.go unpack → writeEntry（os.Chmod 按 mode）
```

- **上传侧**：`ZipReader` 已经在用 commons-compress 的 `ZipArchiveEntry`，`getUnixMode()` 直接可读，不需要换库。
- **分发侧**：`SkillDistributionService` 现在用 `java.util.zip.ZipEntry`，写不了 unix mode，改用 commons-compress 的 `ZipArchiveOutputStream`（依赖已在 `pom.xml`）。
- **CLI 侧**：Go 的 `archive/zip` 里 `FileHeader.Mode()` 能从 external attributes 读回 mode，落盘时 `os.Chmod`。

### 2. 状态按三层放置（[ADR 0164](../../decisions/0164-state-is-tiered-by-what-it-writes.md)）

CLI 增加一个解析顺序，**写**落在第一个可写候选中：

```
SKILLMASTER_HOME
  → os.UserConfigDir()/skillmaster（今天的位置）
  → ${TMPDIR:-/tmp}/skillmaster/state
```

**`SKILLMASTER_HOME` 不跳过探测**：它只是排在最前面的候选，写不进去（只读挂载、不存在的父目录）就继续往下——这正是它存在的理由。

**读与写是两个路径，不是一个**（v51 审计）。`config.StatePaths(rel)` 返回 `(写入路径, 更旧的副本)`：写入是上面那个链里的第一个可写候选，更旧的副本是第一个**已经有这个文件**的其他候选。读先看写入路径、再看副本；写只写写入路径。一个路径干两件事的后果是具体的——沙箱里凭据读得到却写不进去（`login` 失败），或者写进去却读不到（报未登录后静默退化成 latest，[ADR 0035](../../decisions/0035-the-pin-lives-on-the-client-machine.md) 里最坏的那类失败）。凭据（`credentials.Location`）与 pin（`pins.Store.WithOlder`）都按这个形状接。

短期缓存单独一条路径：`${TMPDIR:-/tmp}/skmr/cache/<digest 前 16 位>/`，`--cache-in` 覆盖。

### 3. 两个新命令

`skmr run` 与 `skmr doctor` 都挂在 `skill` 之外（顶层命令），注册在 `cmd/skillmaster/declarations*.go`。

**边界**：服务端不知道缓存存在，也不知道 `run` 跑过什么；CLI 只在本地记两样东西——「这个 digest 被确认过」（`seen/`）与「这个地址上次解析到哪个 digest」（`resolved/`），都不上报。

## 数据模型

### `version_file` 新增一列

```sql
ALTER TABLE version_file ADD COLUMN mode INTEGER NOT NULL DEFAULT 420;  -- 420 = 0644
```

- 编码方式与 `is_binary` 一致：存十进制整数，含义是 unix 权限位；非 unix 平台一律写 420。
- 列的类型是完整权限位，但**值只归一到两档**：可执行写 `0755`，否则 `0644`。上传入口拿到的就是一档布尔（`IngestedFile.executable`），所以 0700、0640 这些原始位在入口就归一了；将来要保真到完整位，改的是入口的类型，不是这一列。
- 迁移号 **V40**（当前最高是 `V39__sync_snapshots_are_marked.sql`）。

### `mode` 不进 digest

`Manifest.digest()` 是 `sha256(relpath + "\0" + blobSha256 + "\0")`，mode 天然不在其中。**这是必须保持的**：把 mode 加进去会让所有已存在版本重算出不同的 digest，地址、`UNIQUE(skill_id, digest)` 与客户端的「内容变了」判断会一起失效。

**直接后果与已定的处理**（2026-11-08 所有者确认）：只改 mode、内容不变的提交会命中已有的同 digest 版本（不是唯一约束——[ADR 0135](../../decisions/0135-a-draft-may-be-copied-from-a-version.md) 之后草稿可以同内容，replay 是查出来的），新 mode 不生效。**提交被拒绝，并说清楚**——`400 mode_changed_on_replay`，说明含「内容与已有版本相同、可执行位不同；平台按内容判定版本，请改动任意一个文件后重新提交」。判定点是 `VersionSubmitter` 命中已有版本的那个分支（`reconcileModes`）。

**同步路径分两种，区别是那一行冻不冻结**（`VersionSubmitter.policyFor`）：

| 命中的行 | 处理 | 理由 |
|---|---|---|
| 同步自己的草稿（`state = draft`） | **修正** mode 并记日志 | 草稿可以改（[ADR 0135](../../decisions/0135-a-draft-may-be-copied-from-a-version.md)），仓库是事实来源；拒绝会让这条源每次同步重下、重试、再失败 |
| 任何非草稿行（`published` / `revoked` / `discarded`） | **不动**，记一条 WARN，条目报 `unchanged` | [ADR 0064](../../decisions/0064-a-draft-may-change-a-published-version-may-not.md) §决定 1 冻结了这些行的 `version_file`；mode 跟着归档走，改了会让同一个 digest 在不同时间给出不同字节 |

**这条规则的直接后果要明说**：V40 之前入库、已经发布的版本，可执行位停在 0644，直到上游内容变化带来一个新版本。要给存量补位，需要一次显式、带审计的回填——那是另一个决定（记在 [`current/TODO.md`](../../current/TODO.md)），不是同步的副作用。

### 代码侧的类型

| 现在 | 改成 |
|---|---|
| `IngestedFile(String relpath, byte[] bytes)` | 增加 `boolean executable` |
| `GitHubTar.Entry(String path, byte[] content, boolean executable)` | 不变（已有） |
| `FetchedFile(String relpath, byte[] content, boolean executable)` | 不变（已有） |
| `ManifestEntry(String relpath, String blobSha256, long size, boolean isBinary)` | 增加 `int mode` |
| `VersionFileRepository` 的三条 INSERT | 增加 `mode` 列与其映射 |
| `SkillDistributionService` 的打包 | 换 `ZipArchiveOutputStream`，逐项 `setUnixMode` |

## 接口

### `skmr run`

```
skmr run <地址> [--cwd <目录>] [--cache-in <目录>] [--no-cache] [--yes] [--json] -- <命令...>
```

| 项 | 规则 |
|---|---|
| 退出码 | 子进程的退出码原样返回；`run` 自身失败才用 0/1/2/4（[ADR 0165](../../decisions/0165-run-resolves-inside-scripts.md)） |
| 版本 | 地址可带 `@<版本或摘要>`；不带时用 `invoke` 钉住的那个（没有就取当前线上版本）。解析出的 digest 是本机缓存的键 |
| 参数改写 | 只有在 `<skill>/scripts/` 下**唯一命中**的相对路径被换成绝对路径；其余参数不动。白名单按扩展名（`.py .sh .js .ts .rb .pl .ps1` 等），不看 shebang |
| 工作目录 | 默认 `<skill>/scripts/`，不存在时 skill 根；`--cwd` 覆盖（相对路径按 skill 根解析） |
| 环境 | 原样继承 + 注入 `SKILLMASTER_SKILL_DIR` |
| 确认 | 首次执行某个 digest 前确认一次；答过的记在 `<状态目录>/seen/<digest 前 16 位>`（内容是 UTC 时间戳）；`--yes` 跳过并记下。确认发生在路径解析之后——**解析失败**的 `run` 不留记号（解释器缺失时记号已经写下，那是「命令没起来」而不是「没打算跑」） |
| 缓存目录名 | `<缓存根>/<digest 前 16 位>`；命中判据是该目录里有 `SKILL.md`。digest 先校验 `^[0-9a-f]{64}$` 再当路径用 |
| 拉取 | 解析出 digest 后按 `@sha256:<digest>` 钉住请求，并核对回来的 `ETag`；两次请求之间有人发布，不会把另一版的字节写进这个 digest 的目录 |
| 断网 | **只有「没拿到回应」才降级**（判据是传输层的 `*url.Error`，不是一串状态码——404 是普通错误、401/403 各有自己的类型，按状态码列会漏）。此时：地址里写 `sha256:…` 直接用它，连凭据都不读；写版本名就报「这一步需要联网」；什么都没写就用 `<状态目录>/resolved/<地址哈希>` 记住的 digest，并在 stderr 说明 |
| 服务端答了 | **一律不降级**，原样报错。404 / 401 / 403 / 410 都是它对这次请求的意见，用缓存盖过去等于跑它刚拒绝的东西 |
| 坏 pin | 记录解析不了就**报错**（`pinsUnreadable`），不当作「没有 pin」退回当前版本——那是 [ADR 0035](../../decisions/0035-the-pin-lives-on-the-client-machine.md) 里最坏的静默替换 |
| 地址语法 | 先过 `checkAddressSyntax`：拼错的地址是用法错误（退出码 2），不是网络错误 |
| 缓存根 | 默认 `${TMPDIR}/skmr/cache`；**先确认它是本人的目录且无组/他人写位**，不满足就用本次的私有临时目录并在 stderr 说明（Linux 的 `$TMPDIR` 是全员共享的 `/tmp`，缓存名是公开的 digest） |
| 归档校验 | 解析出的 digest 拼进请求，并在**落盘之前**核对回来的 `ETag`——校验放在 rename 之后，错内容已经躺在 digest 的目录里，就成了下一次的「缓存命中」 |
| `--no-cache` | 不碰缓存，取到一个 `MkdirTemp` 出来的临时目录再跑 |
| `--json` | `{ "skill": …, "digest": "sha256:…", "command": […], "exit_code": …, "skill_dir": … }`，在子进程结束后打到 stdout |

错误文案（要能直接看懂）：

- 路径不唯一：`scripts/ 下有 2 个 x.py：a/x.py、b/x.py；请写清相对路径`
- 路径不存在：`scripts/ 下没有 x.py，现有：plan_validation.py、check_response.py、…`（`../x.py` 这类越界的也算不存在）
- 解释器缺失：`找不到 python3。这个 skill 的命令要用它；装法见 skmr doctor。`

### `skmr doctor`

一次输出一张表，每行「项 / 状态 / 位置或原因」：

| 项 | 判定方式 |
|---|---|
| 状态目录 | 在候选链上逐个写探针文件，报第一个成功的与最终位置；回退时说出是哪个目录被拒 |
| 凭据 | 环境变量 / 文件 / 钥匙串。**降级说明优先于位置**：文件已经在的时候也读 `credentials.Open` 的通知，因为「文件在」正是降级 login 留下的状态，原因才是那半句要说的 |
| 缓存目录 | 是否可写、当前 digest 命中数 |
| skills 目录 | 存在时看目录自身的写位（**不落探针文件**——那个目录属于 agent）；不存在也报 ok 且不创建它 |
| 网络 | 对服务器发一次 GET（3 秒超时） |
| 沙箱 | 见下 |

**沙箱判定**：先读 `CODEX_SANDBOX` / `CURSOR_SANDBOX` / `SANDBOX_RUNTIME` / `GEMINI_SANDBOX`（只有前两个是官方文档化的）；没有标记时，看状态目录是否回退——回退就报「多半在沙箱里（客户端没有自报）」。**不再单独探测 `$HOME`**：状态目录就在同一个家下面，探测它对沙箱的判别力没有增加，却会在用户主目录里留下一个探针文件。

发现的异常附一段可直接复制的配置，**只打印、不改用户的配置文件**（2026-11-08 所有者确认）：Claude Code 的 `sandbox.filesystem.allowWrite` / `network.allowedDomains` / `excludedCommands`、Codex 的 `writable_roots` + `network_access`、Cursor 的 `additionalReadwritePaths` + `networkPolicy.allow`、WorkBuddy 的 `sandbox.extraAllowWrite`。

**键名的出处**（采集于 2026-11-08）：Claude Code 见 [sandboxing](https://code.claude.com/docs/en/sandboxing) 与 [settings](https://code.claude.com/docs/en/settings)；Codex 见 [agent-approvals-security](https://learn.chatgpt.com/docs/agent-approvals-security)；Cursor 见 [run-modes](https://cursor.com/docs/agent/security/run-modes)。WorkBuddy 没有公开文档，那一段键名来自它自己的安全中心界面与 profile 实测，**是本表里唯一没有外部出处的一行**——所以它只出现在输出里、不参与任何判定。这些字符串不进生成视图，也没有自动校验：键名写错时用户改了配置不会有任何反馈，这是本版接受的代价，`doctor` 只说「按你用的客户端挑一段」。

### 新增环境变量

| 变量 | 作用 |
|---|---|
| `SKILLMASTER_HOME` | 排在最前面的状态目录候选；不跳过探测，写不进去就用下一个 |
| `SKILLMASTER_SKILL_DIR` | `run` 注入给子进程，指向解出来的 skill 根 |

两者都还没进 `skmr help env` 与 `docs/current/config.md`（后者只收 `application.yml` 里的服务端配置键），本版不改这两个地方。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 可执行位贯穿：`IngestedFile` / `ManifestEntry` / `VersionFileRepository` / `SkillDistributionService` / `writeEntry`，以及四个写入点（上传 / 同步 replay / 草稿首次改动 / 草稿编辑） | 验收 1、12 |
| P1 | 状态目录三层 + 读 / 写分开 + 钥匙串写失败降级 + `SKILLMASTER_HOME` | 验收 7、8 |
| P2 | `skmr run`：地址解析、缓存与 digest 记忆、路径解析、cwd、环境、确认、退出码 | 验收 2、3、4、5、6、11 |
| P3 | `skmr doctor` + 符号链接文案 + 四家配置片段 | 验收 9、10 |

P0 与 P1 互不依赖，可以并行；P2 依赖 P1 的缓存目录解析；P3 只依赖 P0 之后的错误形状稳定。验收 12 在 P0：它判定的是「只改位」这条路径的答复，与搬运链同源。

## 开放问题

1. **Windows 上 mode 写什么？**
   倾向：一律写 420（0644），读回时忽略。Windows 没有可执行位，这条修复在那台机器上不发生；`doctor` 要明说「本平台不支持可执行位」。

2. **子进程被信号杀死时，`run` 报什么退出码？**
   现状：Go 的 `ExitCode()` 此时返回 `-1`，`run` 把它原样交出去，进程以 255 结束，`--json` 里也是 `-1`。倾向：改成 `128 + signal`（shell 的惯例），但那要按平台取 `WaitStatus`；本版先如实记下，不猜。

3. **`--json` 在失败时没有机器可读的对象。**
   现状：只有子进程跑完之后才打那一行；解析、拉取、确认失败时只有 stderr 的中文句子。倾向：错误也发一行 `{"error": {...}}`，但形状要先和现有的错误码表对齐，不在本版临时发明。

4. **读命令也会做一次写探针。**
   `config.Dir()` 用「真写一个文件」判断可写，而凭据路径的解析在每条需要认证的命令上都会经过它——所以在按路径拦截的客户端里，一条 `skill read` 也会尝试写一次状态目录的 `.write-probe`。这与 v24「读不写」的目标相反。方向：读走「扫候选里已经存在的文件」（不探测），写才调 `Dir()`，也就是把 `Location` 的写路径做成惰性求值。代价是凭据与 pin 的 store 各要多一个「读路径列表」，本版先如实记下，不做半成品。

5. **`$TMPDIR` 跨会话是否保留？**
   未核实（Claude Code 说「per-user temp directory」，没说每次会话是否重置）。倾向：**按「可能不保留」设计**——缓存未命中就重拉，`digest` 决定目录名，因此重复拉取不会产生多个副本；如果实测是保留的，`doctor` 可以把命中情况报出来，不需要改架构。

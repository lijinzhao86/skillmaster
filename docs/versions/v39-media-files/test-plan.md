# v39-media-files · 测试方案

> **最后更新**：2026-11-02
> **所属版本**：[`README.md`](README.md)

## 验收映射

把 [`prd.md`](prd.md) §验收与指标 的**每一条**映射到具体用例。允许一条对多例。

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #A1 任意类型上传（控制台） | T1、T2 | 未执行 |
| #A2 CLI 上传 | T3、T4、T5、T6、T7、T8 | 未执行 |
| #A3 CLI 下载 | T9、T10 | 未执行 |
| #A4 上限（单文件 / 总量 / 文件数 / 整包） | T11、T12、T13、T14、T24 | 未执行 |
| #A5 存储分流 | T15、T25 | 未执行 |
| #A6 控制台预览 | T16 | 未执行 |
| #A7 响应头与执行面 | T17 | 未执行 |
| #A8 流式与内存 | T18、T19 | 未执行 |
| #A9 错误一致 | T20 | 未执行 |
| #A10 既有契约不变 | T21、T17 的 md / json 分支 | 未执行 |
| #A11 回收 | T22 | 未执行 |
| #A12 审计 | T23 | 未执行 |
| #A13 命令分工可发现 | T26、T27 | 未执行 |

**没有对应用例的验收标准是不合格的验收标准**——要么补用例，要么回 prd 删掉它。

## 用例

### T1 · 控制台多选上传任意类型

- **前置**：本机服务端与控制台在跑；已登录；打开一个已有草稿的版本编辑页，并打开 `references/` 下的任一文件（上传落到当前打开文件所在的文件夹）。
- **步骤**：在文件树里点「上传文件」，多选 `image.png`、`manual.pdf`、`report.docx`、`demo.mp4` 四个文件。
- **期望**：四项逐个显示进度并成功；文件树出现 `references/image.png` 等四项，大小与本地一致；没有错误提示。

### T2 · 控制台拖拽上传并刷新后仍在

- **前置**：同 T1，当前版本是草稿。
- **步骤**：把 `image.png` 拖到文件树；刷新页面（或重新进入该版本）。
- **期望**：文件仍在 `references/` 下；点开显示预览（见 T16）。

### T3 · CLI 单文件上传

- **前置**：已 `skmr login`；目标是一份**已有草稿**，地址写成 `demo/pdf-tools@<版本码>`（写草稿一律给版本码；裸地址要先 `invoke <地址>@<版本码>` 钉过这份草稿才可用）。
- **步骤**：`skmr skill put demo/pdf-tools@<版本码> ./manual.pdf references/manual.pdf`。
- **期望**：退出码 0；`skmr skill ls demo/pdf-tools@<版本码>` 出现该路径；`skmr skill get demo/pdf-tools@<版本码> references/manual.pdf` 取回的字节与本地一致。

### T4 · CLI 多文件上传到远端目录

- **前置**：同 T3。
- **步骤**：`skmr skill put demo/pdf-tools@<版本码> ./a.png ./b.pdf references/`。
- **期望**：两个文件都落在 `references/` 下；任一个失败时另一个仍继续，最终退出码 1，并逐个报告。

### T5 · CLI 上传到已存在路径、没有 `--overwrite`

- **前置**：远端已有 `references/manual.pdf`。
- **步骤**：再 `put` 一个同名文件，不带 `--overwrite`。
- **期望**：不发写请求（或服务端明确拒绝）；stderr 说明目标已存在并提示 `--overwrite`；退出码非 0。

### T6 · CLI `--overwrite`

- **前置**：同 T5。
- **步骤**：带 `--overwrite` 再传一次。
- **期望**：成功；`get` 取回的是新内容。

### T7 · CLI `If-Match` 冲突

- **前置**：CLI 已读取清单拿到基线；另一个客户端先写一次。
- **步骤**：CLI 用旧基线 `put`。
- **期望**：409 `digest_mismatch`；stderr 说明版本已被别人改过，请重新读取；退出码非 0。

### T8 · CLI 写线上版本

- **前置**：地址钉在一个已上线的版本。
- **步骤**：`skmr skill put`。
- **期望**：按现有门序拒绝。命名版本（如 `@1.2.3`）在客户端就被拒绝并说明「写只针对草稿」；只有「地址里的草稿行后来又上线」这一种情况会拿到服务端 409 `version_is_not_draft`。两种都不产生新内容。

### T9 · CLI 下载逐字节一致

- **前置**：远端有 `references/manual.pdf`，清单里有 `sha256`。
- **步骤**：`skmr skill get demo/pdf-tools references/manual.pdf -o ./out.pdf`；本地算 `sha256sum out.pdf`。
- **期望**：摘要与清单一致；退出码 0。

### T10 · CLI `get` 的 `-o` 规则

- **前置**：远端有文件；本地当前目录可写。
- **步骤**：分别跑省略 `-o`、`-o -`、以及本地已有同名文件且不带 `--overwrite` 三种。
- **期望**：省略时落到 `./<basename>`；`-o -` 写 stdout 且可管道；本地已存在时拒绝并提示 `--overwrite`。

### T11 · 单文件上限

- **前置**：造一个 101 MiB 的文件。
- **步骤**：控制台与 CLI 各上传一次；再用 `curl` 直接绕过客户端发一次。
- **期望**：三处都被拒；413 `invalid_upload` / `file_too_large`；文案带「101 MiB」与「100 MiB」。

### T12 · 单版本总量上限

- **前置**：草稿里已有接近 512 MiB 的内容。
- **步骤**：再上传一个会超过上限的文件。
- **期望**：拒绝；413 `invalid_upload`，issue `package_too_large`；不留下半截文件。

### T13 · 文件数上限

- **前置**：草稿已有 512 个文件。
- **步骤**：上传第 513 个。
- **期望**：拒绝；`too_many_files`。

### T14 · 客户端预检

- **前置**：同 T11。
- **步骤**：观察控制台与 CLI 是否在发请求前就报错（抓包或服务端日志）。
- **期望**：没有请求到达服务端；提示里的数字与上限一致。

### T15 · 存储分流与去重

- **前置**：OSS 测试桶可用；服务端配置指向它。
- **步骤**：上传 2 MiB 的 PNG（二进制）、2 MiB 的 Markdown（文本）、500 KiB 的 PNG；查 `blob` / `blob_content`；对同一 PNG 在另一个 skill 再传一次；对 2 MiB Markdown 跑一次 `grep -F`。
- **期望**：2 MiB PNG 与 2 MiB Markdown 的 `blob.storage='oss'`，`blob_content` 无这两行；500 KiB PNG 的 `storage='db'`；重复内容只有一行 `blob`、一份 OSS 对象；三种文件都能读回；大 Markdown 里的固定串能被 `grep` 找到。

### T16 · 控制台预览

- **前置**：草稿里有 png / jpg / jpeg / gif / webp / pdf / docx / mp4。
- **步骤**：逐个点开。
- **期望**：前六种在编辑器内嵌显示（图片显示图像，PDF 显示页面）；docx 与 mp4 显示下载入口，不出现乱码；二进制文件编辑区只读。

### T17 · 响应头与执行面

- **前置**：草稿里有 png、pdf、svg、html、js、md、json。
- **步骤**：用 `curl -I` 看响应头；再用浏览器打开 svg 与 html 的文件地址。
- **期望**：png / pdf 为 `inline`；svg / html / js / **md / json** 为 `application/octet-stream` + `attachment`；都有 `nosniff`；浏览器打开 svg / html 时只下载或显示为纯文本，不执行脚本。
- **为什么要 md / json**：A10 说既有契约不变，而这两个扩展名这一版**有意**从「按扩展名猜出的类型」变成 `octet-stream` + `attachment`——它们是最容易被浏览器当作可执行内容处理的两类文本，比对结果是 A10 的判据之一。

### T18 · 100 MiB 上传/下载的堆内存

- **前置**：造一个 100 MiB 的二进制文件；**OSS 开启**（无 OSS 时大文件仍整份进库，本用例标「未执行」）；服务端开启堆采样（`jcmd GC.heap_info` 或 JFR）。
- **步骤**：上传一次、下载一次，期间采样。
- **期望**：单请求堆峰值不超过 32 MiB；临时文件在请求结束后被删除。

### T19 · 客户端提前断开

- **前置**：远端有一个大文件。
- **步骤**：`skmr skill get … -o - | head -c 1024`，然后立刻结束管道。
- **期望**：服务端在客户端断开后停止读取（日志里没有继续拉取 OSS 的记录）；没有异常堆栈；CLI 把管道关闭当作成功（退出 0），与 `skill cat` 一致。

### T20 · 三端错误一致

- **前置**：无。
- **步骤**：分别用控制台、CLI、`curl` 触发超限、路径非法、冲突、文件不存在四类错误。
- **期望**：同一场景的错误码一致；文案能读懂且带具体数字；控制台不吞掉服务端细节。

### T21 · 既有契约不变

- **前置**：改造前写入的文本 blob 仍在库里。
- **步骤**：跑服务端与 CLI 的既有测试；用一个旧 skill 做 `ls` / `cat` / `read` / `grep` / `edit` / `submit` / `download`。
- **期望**：全部通过；旧 blob 读取正常；文本的 `grep -F` 与 diff 行为不变。

### T22 · 回收

- **前置**：一个版本引用了只有它引用的 OSS 对象；另一个对象被两个版本共用。
- **步骤**：丢弃 / 删除前一个版本，触发清扫；再造一次 OSS 删除失败（断网或错误凭据）。
- **期望**：独占对象与 `blob` 行被删；共用对象保留；失败时 `blob_deletion` 行还在，恢复后下一次清扫成功。

### T23 · 审计

- **前置**：无。
- **步骤**：上传一个新文件、覆盖一个已有文件。
- **期望**：`audit_event` 各一条，含 skill、路径、字节数、新增 / 覆盖。

### T24 · 整包 512 MiB 导入

- **前置**：造一个解压后总量约 512 MiB、单文件不超过 100 MiB 的 zip。
- **步骤**：`skmr skill submit` 导入；观察服务端堆与临时文件。
- **期望**：P1/P2 期间，超过 128 MiB 的包被明确拒绝（413 `package_too_large`，提示逐个文件上传），不是 OOM——**两个方向都测**：压缩包自身超过 128 MiB（控制器在把整包读进内存之前拒），以及压缩后很小、解压后超过 128 MiB 的高压缩比包（`ZipReader` 拒）。P3 流式化之后，512 MiB 包导入成功且堆峰值不超过 32 MiB；单条目超过 100 MiB 的包被拒（`file_too_large`）。

### T25 · 大文本的 grep / wc 上限

- **前置**：skill 里有一个 17 MiB 的合法 UTF-8 文本文件（会进 OSS）和一个 10 MiB 的文本文件。
- **步骤**：分别对两个文件跑 `skmr skill grep` 与 `skmr skill wc`。
- **期望**：`grep` 对 10 MiB 的文件正常命中，对 17 MiB 的文件明确跳过并说明上限是 16 MiB，不读进内存、不报 500。`wc` 对两个文件都正常计数（流式，不设文本上限，[ADR 0133](../../decisions/0133-wc-streams-and-is-not-text-capped.md)），不报 500。

### T26 · 帮助里的命令分工

- **前置**：本机构建出的 `skmr`。
- **步骤**：分别跑 `skmr skill write --help`、`skmr skill put --help`、`skmr skill get --help`、`skmr skill download --help`、`skmr help`。
- **期望**：`write` 说明它写文本并指向 `put`；`put` 说明它传本地文件（二进制为主）；`get` 说明取单个文件，且把 `download` 说成整版；主流程里出现 `put` / `get`；任何一处都不再出现「二进制要先进草稿再写」。

### T27 · 技能内容与帮助一致

- **前置**：`skills/skillmaster/` 已按本版更新。
- **步骤**：读 `SKILL.md` 的索引与 `sop/07-files.md`；再按 v38 的方式打包 tgz，解包后与源目录 `diff -r`。
- **期望**：索引有「传 / 取单个文件」一行且指向 `sop/07-files.md`；SOP 的说法与 `skmr skill put --help` 一致；tgz 与源逐字节一致。

### T28 · 上传耗时指标（不对应验收标准）

- **前置**：50 MiB 文件；链路约 10 Mbps；OSS 开启；单文件、无并发。
- **步骤**：控制台与 CLI 各上传一次，从点击/回车到成功计时。
- **期望**：两次都 < 60 s（口径见 PRD §指标）。这是指标用例，不映射到 A1–A13。

## 环境与数据

- **环境怎么造**：
  - 服务端：按本机记录的命令启动（`. ./.env`、unset 四个 SMS 变量、DB 走 `127.0.0.1`）；新增 OSS 变量 `SKILLMASTER_OSS_ENDPOINT` / `SKILLMASTER_OSS_BUCKET` / `SKILLMASTER_OSS_ACCESS_KEY_ID` / `SKILLMASTER_OSS_ACCESS_KEY_SECRET`（或 ECS RAM 角色）。没有 OSS 变量时 `SKILLMASTER_OSS_ENABLED=false`，全部落库——此时 T15、T18、T22 标「未执行」。
  - 控制台：`skillmaster-console` 的 dev server。
  - CLI：`skillmaster-cli` 本机构建出的 `skmr`。
- **数据**：
  - 1×1 PNG / PDF / DOCX / MP4 夹具放在 `skillmaster-cli/testdata/` 或测试资源目录；大文件用 `dd if=/dev/urandom of=big.bin bs=1m count=100` 现造。
  - 512 MiB 整包用脚本把若干 100 MiB 文件打包；脚本放 `$CLAUDE_JOB_DIR/tmp`，不进仓库。
- **清理**：删掉测试 skill / 版本；OSS 测试桶按 `blobs/` 前缀清空；临时文件删除；本地 `out.*` 删除。

## 结果

实现完成后填写。**每条都要有证据**（命令输出、截图、日志片段），不要只写"通过"。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1–T28 | 未执行 | — |

**失败或跳过的用例要单列说明**，不要埋在表格里。

## 已知问题

**本版范围内**的未修缺陷。本版范围外的历史代码缺陷写在版本文件夹的 `known-issues.md`（本版没有新建）。

状态只能取四个值之一，不要用"大概、可能"：

| 标注 | 含义 |
|---|---|
| **已复现** | 实际跑出来过 |
| **部分复现** | 只在特定条件下触发 |
| **未验证** | 有人提出，但没独立复现 |
| **已修复** | 已被后续改动解决（注明日期与改动） |

### K1 · 整包导入的 128 MiB 临时上限

`ZipReader.read` + `IngestLimits.PACKAGE_IN_MEMORY_MAX_BYTES` · **未验证**

单文件上传已经流式化，支持 100 MiB / 每版本 512 MiB；**整包导入**（`skill submit` / 网页导入）在
P3 之前仍然把压缩包整份读进内存、把每个 zip 条目读成 `byte[]`，因此服务端在 128 MiB 处拒绝**两次**：控制器在 `getBytes()` 之前按**压缩包自身**大小拒（否则整包进堆），`ZipReader` 按**解压后**总量拒（否则解压后进堆）。两个方向都要防，超过任一者的包都只能逐个文件上传。
P3 完成流式 `ZipReader` 后删除此上限，并把 T24 从「未执行」改为「通过」。

### K2 · 控制台上传同名路径直接覆盖，没有确认

**未验证**

控制台的上传按「当前打开文件所在的文件夹 + basename」落盘，不检查路径是否已存在，
服务端的 `requireNoCollision` 也不拒绝同名文件。同一操作在 CLI 会被 `--overwrite` 挡住。
设计稿没有覆盖确认态，本版**不自行发明**弹窗；等产品定下控制台的覆盖规则（确认弹窗或默认拒绝）
再补。T1 只测新文件，没有覆盖用例。

### K3 · OSS 上传发生在数据库事务内

**未验证**

`writeFromFile` / `createWithFile` 在 `@Transactional` 内先 `blobs.put(file, size)`，OSS 上传的
网络耗时因此占着数据库连接与草稿行锁。OSS 变慢时并发上传会占满连接池。把上传移到事务外需要
调整用例的边界（以及回滚残留的补偿），不是本版能顺手做的；P3 一并处理。

### K4 · 事务回滚可能留下无人引用的 OSS 对象

**未验证**

对象先写进桶、`blob` 行随后在同一个事务里插入；如果事务在插入后回滚（例如提交时数据库错误），
行消失了，对象还在，且没有 `blob_deletion` 记录（那行也随事务回滚）。已把能在无字节阶段完成的
校验前移，正常拒绝路径不再产生孤儿；剩下的数据库故障窗口由运维的生命周期 / 对账兜底。

### K5 · CLI `download` 仍受 17 MiB 内存上限

**未验证**

服务端归档已流式，但 `skillmaster-cli/cmd/skillmaster/download.go` 仍把整包读进内存并在
17 MiB 处拒绝。带媒体的版本目前要用 `skill get` 逐个取。P3 的流式落盘解除此限制，并补一条
>17 MiB 整包下载用例。

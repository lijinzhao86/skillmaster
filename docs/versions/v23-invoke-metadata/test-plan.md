# v23-invoke-metadata · 测试方案

> **最后更新**：2026-10-27
> **所属版本**：[`README.md`](README.md)
> **依据**：PRD 已定（2026-10-27），技术设计见 [`technical-design.md`](technical-design.md)。

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #1 头部只含五个字段 | T1 | 通过 |
| #2 作者字段不出现 | T2 | 通过 |
| #3 `access` / `permissions` 按档位正确 | T3 | 通过 |
| #4 `permissions` 与真实命令行为一致 | T4 | 通过 |
| #5 `cat` 与存储字节一致 | T5 | 通过 |
| #6 页脚消失 | T6 | 通过 |
| #7 规则段与新输出一致 | T7 | 未执行 |

## 用例

### T1 · 头部形状

- **前置**：本机服务端 + 一条**没有** `compatibility` 的已发布 skill、一条**有** `compatibility` 的草稿（另有一份 `compatibility` 里带换行与 `permissions: …` 的输入，用于折叠那一条）。
- **步骤**：`skillmaster skill invoke <地址>` 各一次；草稿用版本码寻址。
- **期望**：`---` 之间是 `address` / `version` / `access` / `permissions`，作者声明了 `compatibility` 时在末尾多一行（**折成一行、加引号**——作者值里的换行不能伪造出第二行 `permissions:`）；没有 `compatibility` 的少一行；草稿没有 `version` 行、`address` 带版本码；头部之后直接是正文。
- **判定**：通过／失败

### T2 · 作者的字段不出现

- **前置**：一个 frontmatter 里同时写着 `name` / `description` / `version: 2.0.0` / `when_to_use` / `license` 的 skill（本机用 `matrix-owner/v23-compat`，草稿）。
- **步骤**：`skill invoke <地址> | grep -E '2\.0\.0|^name:|^description:|^license:|^when_to_use:'`。
- **期望**：无命中。（头部自己的 `version:` 行不在判据里——它是平台的，正是本版要留下的那一个。）
- **判定**：通过／失败

### T3 · 档位矩阵

- **前置**：`qa/role-matrix` 的六档凭据 + 夹具（线上 / 历史 / 草稿 / 已下架 / 已丢弃）。
- **步骤**：每档 × 每种可读状态跑一次 `skill invoke`，记 `access` 与 `permissions`。
- **期望**：`invoke`→`read`；`browse`→`read, list`；`write`→`read, list, write`；`maintain`→`+, discard`；`admin` 与所有者→`+, share`；草稿只在 `write`+ 可见且同样按档位。**`discard` 从 `maintain` 起列**：`write` 只能丢弃自己的草稿，而头部没有「谁的」这一维（见 technical-design §接口）。
- **判定**：通过／失败（附矩阵表）

### T4 · `permissions` 与真实命令一致

- **步骤**：同一个矩阵里，对每一格按 `permissions` 跑命令：列了 `list` 的跑 `skill files`（必须成功）、没列的必须失败；列了 `write` 的跑一次 `skill write …@new`（必须成功）、没列的必须失败。另用一把只有 `skills:read` 的 key 跑一次 `skill invoke` 与 `skill write`。
- **期望**：没有一格「写了能做、实际不能」。反向的不成立只允许一处，且是记录在案的：`write` 档能丢弃**自己的**草稿，而 `discard` 从 `maintain` 起列（限定条件见 technical-design §接口）。只读 key 的头部里不出现 `write` / `discard` / `share`，`skill write` 也确实失败。
- **判定**：通过／失败

### T5 · `cat` 不变

- **步骤**：`skill cat <地址> SKILL.md > a`；`skill download <地址> --into <目录>`；`cmp a <目录>/SKILL.md`。
- **期望**：完全一致，含作者 frontmatter。
- **判定**：通过／失败

### T6 · 页脚消失

- **步骤**：`skill invoke <地址>` 两次（第二次在两个版本之间），看 stdout 与 stderr。
- **期望**：stdout 里没有「（已按 … 加载…）」；头部的 `---` 之后直接是正文；钉移动的提示出现在 stderr。取用上限的说明（正文超过 2000 行 / 128 KiB 时那条）仍留在 stdout——它必须跟着内容走，否则丢掉它就读成「这就是全部」。
- **判定**：通过／失败

### T7 · 规则段一致

- **步骤**：读 `skillmaster/startup` 当前线上版的 `steps/03-prompt.md`。
- **期望**：规则段里没有「末行说明该版本与可执行的操作」这类说法（改由头部承担）。
- **判定**：通过／失败

## 环境与数据

- **栈**：本机 `skillmaster-server`（8080）+ 工作区构建的 CLI。
- **夹具**：`qa/role-matrix` 的六档账号与两个夹具 skill；另有一条带 `compatibility`（且带作者 `version` / `when_to_use` / `license`）的草稿（本版新建，`matrix-owner/v23-compat@JHQRCAXW`）。
- **清理**：夹具都在专用 namespace 里；带 `compatibility` 的那条用完丢弃草稿。

## 结果

**执行环境**：本机 `skillmaster-server`（`target/skillmaster-server-0.0.1-SNAPSHOT.jar`，2026-10-27 12:02 构建）+ 工作区构建的 CLI；凭据是 `qa/role-matrix` 的 `matrix-owner`（所有者）。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | **通过**（消费面 + 草稿两面各一例；折叠一条由单测覆盖） | 无 `compatibility` 的一条（`matrix-owner/role-matrix-fixture`）输出 `address` / `version` / `access` / `permissions` 四行；带 `compatibility` 的草稿（`matrix-owner/v23-compat@JHQRCAXW`）输出四行 `address`（带版本码）/ `access` / `permissions` / `compatibility`——没有 `version` 行，因为草稿从未被分配过号；`compatibility` 输出为 `"需要 python3 与网络"`（加引号的单行） |
| T2 | **通过** | 夹具 frontmatter 里写着 `name` / `description` / `version: 2.0.0` / `when_to_use` / `license`；`skill invoke` 的输出里 `grep -E '2\.0\.0|^name:|^description:|^license:|^when_to_use:'` 无命中，正文从 `# v23-compat` 开始 |
| T3 | **通过** | `qa/role-matrix` 的六档账号各跑一次 `skill invoke`（夹具 `matrix-owner/role-matrix-fixture@1.0.2`），读头部：见下表。草稿（`@WNDC9VND`）只在 `write` 及以上可见——`invoke` / `browse` 都拿不到 |
| T4 | **通过** | 同一次读数里对每一档跑 `skill files`：`list` 在 permissions 里 ⟺ `files` 成功（`invoke` 拒绝，其余五档成功）。`write` 那一半由矩阵自己的 CLI 套件覆盖（六档 × 写入探针，全绿）——两半拼起来没有「写了能做、实际不能」的格子；唯一记录在案的反向例外是 `write` 能丢弃**自己的**草稿而 `discard` 从 `maintain` 起列 |
| T4 | **部分执行** | 矩阵（T3）未跑；另外单独验过一格：同一账号换一把只有 `skills:read` 的 key，`skill invoke` 打出 `permissions: read, list`（`access` 仍是 `admin`，因为档位是账号的），同一把 key 的 `skill write` 被服务端以 `insufficient_scope` 拒绝 |
| T5 | **通过**（本机一条） | `skill cat matrix-owner/role-matrix-fixture SKILL.md` 与 `skill download … --into <目录>` 的 `SKILL.md` 用 `cmp` 比较无差异，sha256 同为 `784eec88762b5367c14b58e8ee40b9a7b0589a91ec2315a955c79881ab2ecd7d`（版本注记走 stderr，不混进 stdout） |
| T6 | **通过** | stdout 里搜不到「（已按」；头部与正文之间没有多余的行。先 `invoke @WNDC9VND` 再 `invoke`，stdout 仍只有头部 + 正文，stderr 收到「（… 上次钉在 @WNDC9VND，现在是 @1.0.2——中间有人发布过。）」 |
| T7 | **未执行** | 规则段的改法已在 `skillmaster/startup` 的草稿 `7N6YQKDK`（1.3.0）里写好，尚未由人在控制台发布；线上 1.2.0 仍是旧说法 |

六档实测（`access` / `permissions` / 能否 `skill files` / 能否读到草稿）：

| 档 | access | permissions | files | 草稿 |
|---|---|---|---|---|
| invoke | `invoke` | `read` | 拒绝 | 看不见 |
| browse | `browse` | `read, list` | 成功 | 看不见 |
| write | `write` | `read, list, write` | 成功 | 看得见 |
| maintain | `maintain` | `read, list, write, discard` | 成功 | 看得见 |
| admin | `admin` | `read, list, write, discard, share` | 成功 | 看得见 |
| owner | `admin` | `read, list, write, discard, share` | 成功 | 看得见 |

矩阵本身不探 `skill invoke` 的头部（它量的是既有能力边界），上表数字由另跑的一次只读读数得到，没有往 `qa/` 里加东西。

**矩阵回归**：`uv run pytest` 118 passed / 20 skipped（跳过的是「允许侧只在临时 skill 上跑」那 4 条 × 5 档）；`uv run pytest --ui` 134 passed / 4 skipped，34 秒。

**指标**：头部字节数（目标 ≤ 200）——所有者（报 `admin`）实测 **118 字节**（`address: matrix-owner/role-matrix-fixture` / `version: 1.0.2` / `access: admin` / `permissions: read, list, write, discard, share`，不含 `---` 行），在目标内。

## 已知问题

本版范围内暂无记录。

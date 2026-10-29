# v31-list-addresses-only · 测试方案

> **最后更新**：2026-10-30
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收 | 用例 |
|---|---|
| A1 默认只有地址 | T1 |
| A2 默认列全 | T2 |
| A3 `--all` 不改变输出 | T3 |
| A4 `--verbose` 保留描述与小字段 | T4 |
| A5 `search` 不变 | T5 |
| A6 `show` 不变 | T6 |
| A7 帮助同步 | T7 |
| A8 空结果文案不变 | T8 |

## 用例

### T1 · 默认只有地址

- **前置**：库里有 ≥ 2 条 skill。
- **步骤**：`skmr skills list`。
- **期望**：除最后一行 `（共 N 个。…）` 外，每个非空行都匹配 `^[^ ]+/[^ /]+$`；没有任何缩进行。
- **判定**：通过／失败

### T2 · 默认列全

- **前置**：库里有 N 条（`skmr skills list --all` 的地址行数即 N）。
- **步骤**：不带 `--all` 跑一次，数地址行。
- **期望**：地址行数 = N；没有「还有更多」字样。
- **判定**：通过／失败

### T3 · `--all` 不改变输出

- **步骤**：`skmr skills list > a`、`skmr skills list --all > b`、`cmp a b`。
- **期望**：完全一致。
- **判定**：通过／失败

### T4 · `--verbose` 保留描述与小字段

- **步骤**：`skmr skills list --verbose`。
- **期望**：每条是地址 + 缩进的「描述 - 适用场景」+ 缩进的「来源：… · 状态：… · 版本：… · 更新于：… · 分类：… · 公开标签：…」（空字段不打印）；与改动前的 `skills list --verbose` 输出逐字节一致（库不变的前提下）。
- **判定**：通过／失败

### T5 · `search` 不变

- **步骤**：同一条查询在改动前后各跑一次（改动前用本地 `main` 构建的二进制，或保存的基线输出）。
- **期望**：输出逐字节一致（地址 + 描述；v30 的首行提示仍在）。
- **判定**：通过／失败

### T6 · `show` 不变

- **步骤**：`skmr skill show <地址>`。
- **期望**：地址、描述、适用场景、运行环境、版本、分类、公开标签、我的标签按既有顺序出现，空字段不打印。
- **判定**：通过／失败

### T7 · 帮助

- **步骤**：`skmr skills list --help`。
- **期望**：Usage 含 `[--all]`；`--all` 的说明写明「保留、默认已列全」；Output 说明默认每条一行地址、描述走 `--verbose` 或 `skill show`；例子可粘贴。
- **判定**：通过／失败

### T8 · 空结果文案不变

- **步骤**：造一个筛选为空的结果（如 `skmr skills list --category 不存在的分类`）。
- **期望**：仍是原来的空库/筛选为空句子，不出现「（共 0 个…）」这类列表页脚。
- **判定**：通过／失败

## 环境与数据

- **栈**：工作区构建的 CLI；`skills list` 读本机凭据与服务端。单元测试用 `newFakeServer` 假服务端即可，不需要真库。
- **基线**：T4/T5 需要改动前的输出做对比；在 `main`（`72e4a1c`）上跑一次并保存即可。

## 结果

**执行环境**：macOS arm64（本机）+ GitHub Actions。CLI 单元测试在 PR #93 的 `Test and vet` job 里跑过；另外在本机用**真二进制**做了一轮端到端对照——构建 `main`（`72e4a1c`）、`fix/invoke-read-bugs`、本分支三个可执行文件，用 `SKILLMASTER_SERVER` 指向本地假服务端（60 条库、两页、owner 报 `access: admin`），逐条比对输出。发布后本机到 `dl.skillmasterai.com` 的连接被重置（HTTP 000），**线上产物未在本机复核**，以流水线自己的探针为准。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 · 默认只有地址 | **通过** | 真二进制：60 条非页脚行全部匹配 `^[^\s/]+/[^\s/]+$`，缩进行 0 条；`TestARowIsTheAddressAloneAndTheRestIsOptIn`、`TestListDefaultsToTheConsumableLibraryAndReadsEveryPage`（逐字节断言） |
| T2 · 默认列全 | **通过** | 真二进制：默认打 60 条地址（`main` 停在 50 条并说「还有更多」）；`TestListWalksEveryPage`、`TestListDefaultsToTheConsumableLibraryAndReadsEveryPage`（两页都被读） |
| T3 · `--all` 不改变输出 | **通过** | 真二进制 `cmp` 相同（865 B / 61 行）；`TestListAllChangesNothing` |
| T4 · `--verbose` 保留描述与小字段 | **通过** | 真二进制：本版 `--verbose` 与 `main skills list --all --verbose` **逐字节相同**（18,136 B / 181 行）；`TestListVerboseAddsOneLineAndKeepsTheCard`、`TestARowIsTheAddressAloneAndTheRestIsOptIn`（含「无描述时不打空的读法行」一例） |
| T5 · `search` 不变 | **通过** | 真二进制：`skills search pdf` 在 `main` 与本版上逐字节相同（8,231 B）；`skills search` 的既有用例全绿 |
| T6 · `show` 不变 | **通过**（回归） | `skill show` 的既有用例全绿；代码路径未改 |
| T7 · 帮助 | **通过** | help-eval 与 flag-attribution 用例全绿；真二进制 `--help` 逐条读过 |
| T8 · 空结果文案不变 | **通过** | 真二进制：两版都是 `没有匹配的 skill。`；`TestListUnknownFilterNamesAnswerWithTheVocabulary` 与空库用例 |

**指标（清单体积）**：本机 25 条的库 934 B / 39 行（目标 ≤ 1,500 B）；假库 60 条 865 B / 61 行。

**单元测试总览**：`go test ./...` 全绿；`gofmt` / `go vet` 干净；PR #93 的 `Test and vet`、`The update path on Windows`、`Validate documents` 三个 job 全绿。

**发布**：2026-10-30 合并 `bbe7aa4`，tag `cli/v1.3.0`，`CLI release` run `40948277867` success（暂存 → 逐字节校验 → 提升 → 桶与文档地址两处探针均通过）。

## 已知问题

本版范围内暂无记录。

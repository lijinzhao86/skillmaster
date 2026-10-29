# v30-search-exclude · 测试方案

> **最后更新**：2026-10-30
> **所属版本**：[`README.md`](README.md)

<!--
验收映射覆盖 prd 的每一条。结果一节在实现完成后填写，未执行就写未执行。
-->

## 验收映射

| prd 验收 | 用例 |
|---|---|
| A1 排除生效 | T1 |
| A2 两种写法等价 | T2 |
| A3 格式非法 → 退出 2 | T3 |
| A4 未知地址静默忽略 | T4 |
| A5 语义路径不缩水 | T5 |
| A6 字面路径不缩水、分页正确 | T6 |
| A7 前置提示 | T7 |
| A8 空结果 + 排除 | T8 |
| A9 帮助 | T9 |
| A10 上限 50 | T10 |
| A4（补充：空白与重复不算数量） | T11 |

## 用例

### T1 · 排除生效

- **前置**：本机服务端 + 有一个会命中查询的 skill `matrix-owner/fixture`（`qa/role-matrix` 夹具或自建）。
- **步骤**：`skmr skills search "<q>" --limit 10` 与 `skmr skills search "<q>" --limit 10 --exclude matrix-owner/fixture` 各跑一次。
- **期望**：第一次的地址集合包含它，第二次不包含；其余结果不变。
- **判定**：通过／失败

### T2 · 两种写法等价

- **步骤**：`--exclude a/b --exclude c/d` 与 `--exclude a/b,c/d` 各跑一次（a、c 都要会命中）。
- **期望**：stdout 逐字节相同。
- **判定**：通过／失败

### T3 · 格式非法

- **步骤**：分别跑 `--exclude a/b/c`、`--exclude ab`、`--exclude a/b@1.0.0`。
- **期望**：三次都退出 2；stderr 含「exclude 要写 <命名空间>/<名字>」或等价的形状说明，并以 `用法见：skmr skills search --help` 收尾。
- **判定**：通过／失败

### T4 · 未知地址静默忽略

- **前置**：库里有至少一条会命中的结果。
- **步骤**：`--exclude nosuch/name`。
- **期望**：退出 0；结果与不带 `--exclude` 时相同；stdout/stderr 都没有提到这个地址。
- **判定**：通过／失败

### T5 · 语义路径不缩水

- **前置**：查询命中 ≥ `limit + 2` 条语义候选。
- **步骤**：`skmr skills search "<q>" --limit 5 --exclude <头两条的地址>`。
- **期望**：仍返回 5 条；`access` / 描述正常；没有「还有更多结果」行（语义路径本来就没有游标）。
- **判定**：通过／失败

### T6 · 字面路径不缩水、分页正确

- **前置**：`--semantic=false` 下查询命中 > `limit` 条。
- **步骤**：`skmr skills search "<q>" --semantic=false --limit 5 --exclude <前两条地址>`。
- **期望**：返回 5 条；被排除的两条不在其中；「还有更多结果」出现与否与不排除时一致（总数足够时仍然出现）。
- **判定**：通过／失败

### T7 · 前置提示

- **步骤**：默认语义、`--limit 3`，分别跑不带 `--exclude` 与带 `--exclude`。
- **期望**：不带时 stdout **第一行**是「（要换说法再查：加 --exclude 带上这次给过的地址，重复的不再占上下文。）」，第二行起才是地址；带时不出现这一行。结果为空时也不出现。
- **判定**：通过／失败

### T8 · 空结果 + 排除

- **步骤**：用一个必然无结果的查询，分别跑不带与带 `--exclude`；再构造一次服务端带 note 的空结果（语义检索的常规空结果总是带「没有足够相关的 skill。」）。
- **期望**：不带时仍打印服务端的空结果句；带时在它之后多打印「（这种空结果也可能是 --exclude 排掉的：去掉 --exclude 再看。）」，且不出现「没有匹配的 skill」这类会把「看过」说成「没有」的句子。带 note 的空结果同样要有这句。
- **判定**：通过／失败

### T9 · 帮助

- **步骤**：`skmr skills search --help`。
- **期望**：Usage 含 `[--exclude <地址>]...`；参数表有 `--exclude` 与上限/形状说明；输出一节提到首行提示；例子至少一条带 `--exclude`。
- **判定**：通过／失败

### T10 · 上限 50

- **步骤**：CLI 传 51 个 `--exclude`；再直接 `curl` 一个带 51 个 `exclude` 参数的请求。
- **期望**：CLI 退出 2 并说明上限；API 返回 400。
- **判定**：通过／失败

### T11 · 空白与重复

- **步骤**：直接打 API：`?exclude=`（空值）、`?exclude=a/b&exclude=a/b`（重复）、以及同一条地址重复 51 次。
- **期望**：前两种都按「没写」处理（退出 200，结果不变）；第三种不因重复触发上限（去重后 1 个）。
- **判定**：通过／失败

## 环境与数据

- **栈**：本机 `skillmaster-server`（8080）+ 工作区构建的 CLI。
- **夹具**：`qa/role-matrix` 的账号与夹具（现有），或在本机库里造两条会命中同一查询的 skill。排除只读，不需要清理服务端数据。
- **不跑 `qa/role-matrix`**：本版不动权限矩阵；写代码与审计期间都不跑它（dev-sop §五）。

## 结果

**执行环境**：macOS arm64（本机）+ GitHub Actions。本机没有测试库凭据，服务端 IT 未在本机跑；**PR #91 的 `Test` job 用真 PostgreSQL 跑过全部 IT（2m35s，含本版新增的 7 个 `SkillSearchIT` 用例，全绿）**。语义检索在本机与 CI 都未启用，因此 T5 仍是未执行。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 · 排除生效 | **通过**（CI） | `SkillSearchIT.anExcludedAddressIsNotReturned` |
| T2 · 两种写法等价 | **通过**（CLI 层） | `TestSearchSendsEveryExcludedAddress`（逗号与重复合并、去重后发 2 个参数） |
| T3 · 格式非法 | **通过**（CLI 层） | `TestSearchRefusesAMalformedExclude`；服务端对应 `aMalformedExcludeIsABadRequest`（未运行） |
| T4 · 未知地址静默忽略 | **通过**（CI） | `SkillSearchIT.anExcludeThatNamesNothingIsIgnored`、`anEmptyExcludeIsIgnored`（未运行） |
| T5 · 语义路径不缩水 | **未执行** | 需要可用 embedding 的集成环境；代码路径见 td「语义路径」 |
| T6 · 字面路径分页 | **通过**（CI） | `SkillSearchIT.excludingDoesNotShrinkThePageOrRepeatRows` |
| T7 · 前置提示 | **通过** | `TestSearchPutsTheExcludeHintFirst`、`TestSearchKeepsTheHintAheadOfTheClampNote` |
| T8 · 空结果 + 排除 | **通过** | `TestSearchExplainsAnEmptyResultWithExcludes`、`TestSearchExplainsTheOrdinarySemanticEmptyWithExcludes`、`TestSearchKeepsTheServerExplanationWhenItOffersTheExcludeCheck` |
| T9 · 帮助 | **通过** | `go test ./cmd/skillmaster`（help-eval 与 flag-attribution 全绿） |
| T10 · 上限 50 | **通过**（CLI + CI） | `TestSearchCapsTheExcludesAtFifty`、`SkillSearchIT.moreThanFiftyExcludesIsABadRequest` |
| T11 · 空白与重复 | **通过**（CI） | `SkillSearchIT.anEmptyExcludeIsIgnored`、`oneAddressRepeatedFiftyOneTimesIsStillOneAddress` |

**单元测试总览**：`go test ./...` 全绿；服务端 `./mvnw test` 265 个用例，2 个 Spring 上下文加载失败（无测试库密码，环境问题）。

## 已知问题

本版范围内暂无记录。

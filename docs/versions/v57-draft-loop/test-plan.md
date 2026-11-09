# v57-draft-loop · 测试方案

> **最后更新**：2026-11-10
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| A1 一条命令 6 个 `--from` 全过 | T1 | 通过 |
| A2 写过 A 后 B 仍可覆盖 | T2 | 通过 |
| A3 B 在服务端改过 → 被拒 | T3、T3b | 通过 |
| A4 没读过仍被拒、无绕过路径 | T4 | 通过 |
| A5 草稿版本码可 run，且执行的是草稿里的脚本 | T5、T10 | 通过 |
| A6 同一 digest 不再问、发布后命中缓存 | T6、T12 | 部分：T6 通过，T12 未执行 |
| A7 不再出现 `X-Skill-Version` 原文 | T7、T11 | 通过 |
| A8 `--help` 与放行条件一致 | T8 | 通过 |
| A9 批量失败的已写入 / 失败 / 未开始清单，重跑能补齐 | T9 | 通过 |

## 用例

### T1 · 一条命令覆盖 3 个、新建 3 个

- **前置**：假服务端里有一个草稿，已有 `scripts/a.py`、`scripts/b.py`、`scripts/c.py`；本机对这三个各做过一次整份 `read`。
- **步骤**：**一条命令**带 6 个 `--from`（3 个覆盖 + 3 个新建），目标 `scripts/`，加 `--overwrite`。
- **期望**：全部成功；`PUT` 依次 6 次，每次的 `If-Match` 是上一次写入后的新整版摘要；中途不补读。
- **判定**：通过／失败。

### T2 · 写过 A 之后，B 的凭证仍有效

- **前置**：同 T1，读过 A、B、C 三个已存在文件。
- **步骤**：依次 `write --overwrite` A、B、C（三条命令，或一条命令带三个 `--from`）。
- **期望**：三次都放行；不存在「这一版变过」的报错。
- **判定**：通过／失败（失败时记录第几个被拒）。

### T3 · B 在服务端被改过 → 拒

- **前置**：读过 B；在假服务端把 B 的内容换掉（清单里 `sha256` 随之变化）。
- **步骤**：`write --overwrite` B。
- **期望**：被拒，报错指认 B 变了，并给 `skmr skill read …` 的下一步。
- **判定**：通过／失败。

### T3b · 批量中途别人改了兄弟文件 → 轮到它时被拒

- **前置**：读过 `scripts/a.py`、`scripts/b.py`；假服务端在第一个 `PUT` 成功后把 `b.py` 换成别人写的内容。
- **步骤**：一条命令覆盖 a、b 两个文件。
- **期望**：a 写入成功；b 被拒（「读过之后它被改过」），且 b 没有被 PUT。
- **判定**：通过／失败。

### T4 · 没读过仍被拒，且没有绕过路径

- **前置**：草稿里有 `SKILL.md`，本机没有任何读记录。
- **步骤**：`write --overwrite`；再试 `write --force`。
- **期望**：前者被拒并给出 `read` 的下一步；`--force` 是未知参数，退出码 2。
- **判定**：通过／失败。

### T5 · 草稿版本码的解析路径

- **前置**：假服务端模拟公开面（`Metadata` 对版本码答 400 `invalid_request`）、作者面（`GET /author/.../versions/{pin}` 答 digest，`archive` 按 digest 答含 `scripts/hello.sh` 的整版），租约端点答空租约。
- **步骤**：`skmr run <地址>@<草稿码> --yes -- sh hello.sh`。
- **期望**：公开面被问两次——带版本码一次（400）、裸地址探绑定一次（从未发布时 404，已发布时 200；这两支分别由 `TestRunProbesTheLeaseWhenTheSkillWasNeverPublished` 与 `TestRunUsesThePublicAnswerForAPublishedSkillsBindings` 覆盖，本用例的假服务端走 404 那支）；作者面解析出 digest；按 digest 取到 archive；**草稿里的 `scripts/hello.sh` 真的被执行**（stdout 出现它打印的内容）；stderr 的版本行指向草稿 digest；全程不出现服务端原文。
- **判定**：通过／失败。

### T6 · 同一 digest 第二次不问

- **前置**：T5 跑过第一次（已记下同意）。
- **步骤**：再跑一次同样的命令。
- **期望**：不再要求 `--yes`；缓存命中，不再下载。
- **判定**：通过／失败。

### T7 · 错误文案

- **前置**：同上，作者面也解析不到。
- **步骤**：`skmr run <地址>@<不存在的东西> -- <解释器>`。
- **期望**：stderr 没有 `X-Skill-Version`、没有 `invalid_request`；说的是「没有这一版」并给 `skmr skill versions <地址>`。
- **判定**：通过／失败。

### T8 · help 与放行条件一致

- **前置**：无。
- **步骤**：`skmr skill write --help`，对照 T1–T4 的实际行为。
- **期望**：`--overwrite` 的说明写的是「读过这个文件、读后它自己的内容没变」，不再出现「整版摘要」。
- **判定**：通过／失败。

### T9 · 批量失败的三组清单

- **前置**：一条命令带 6 个 `--from`；假服务端让第 3 个 `PUT` 答 500。
- **步骤**：跑这条命令；然后恢复服务端，重跑同一条命令。
- **期望**：第一次 stderr 里同时有 `已写入（2）`、`失败（1）`、`未开始（3）` 与「重跑同一条命令」；第二次 6 个全部落盘。
- **判定**：通过／失败。

### T10 · 真服务端：草稿上真跑一次

- **前置**：真实账号下有至少一个草稿；本机已 `login`。**不执行草稿里带副作用的脚本**——真的脚本执行由 T5 的假服务端覆盖。
- **步骤**：`skmr run <地址>@<草稿码> --yes -- python3 --version`。
- **期望**：版本解析走作者面、整版取回、缓存目录按草稿 digest、子进程执行；stderr 的版本行指向该草稿的 digest。它证明的是真服务端的解析与取回，不证明「脚本被执行」（那是 T5）。
- **判定**：通过／失败。

### T11 · 不存在的版本名

- **前置**：作者面也能访问。
- **步骤**：`skmr run <自己的地址>@1.999.999 -- python3 --version`。
- **期望**：报「没有这一版」，不是 400，也不是两次不同的错误叠在一起。
- **判定**：通过／失败。

### T12 · 发布前后是否命中同一缓存

- **前置**：T10 的草稿；所有者确认可以发布它。
- **步骤**：发布前 run 一次，记录缓存目录名与 `--yes` 记录；发布后按版本名 run 一次。
- **期望**：两次的 digest 相同、缓存目录相同、不再问。
- **判定**：通过／失败；不一致时按 TD 开放问题 1 处理（只影响是否再问一次，不影响正确性）。

## 环境与数据

- **单元与集成**：`cd skillmaster-cli && go test ./...`。命令级的用例跑在 `cmd/skillmaster` 的假服务端上（`read_write_contract_test.go` 里的 `fromFake`、`withTempHome`、`pointAtServer` 一类）。**假服务端的文件清单要补 `sha256` 字段**——这一版起，凭证比对的就是它。
- **真服务端**：按 [`.dev-sop/profile.md`](../../../.dev-sop/profile.md)「一库一栈」起本机服务端，或直接对生产用一个**自己的**草稿；不碰别人的 skill。
- **数据**：本机状态目录一律用测试自己的临时 HOME（`withTempHome`），不读也不写真实的 `reads/`。
- **清理**：测试结束删临时目录；真服务端用例只读不写，草稿内容不因测试而变（T12 的发布要得到所有者同意）。

## 结果

实现完成后填写；未执行就写未执行，不写「应该没问题」。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | `go test ./cmd/skillmaster -run TestWriteFromLocalFiles`：`one_command_covers_a_batch_of_six`（6 个 PUT；`If-Match` 序列由假服务端记录，未被单独断言） |
| T2 | 通过 | 同上：`a_sibling_write_does_not_invalidate_this_file's_proof`（写过 A 后 B 仍放行）；批量里 A、B、C 依次覆盖由 T1 的 6 文件用例覆盖 |
| T3 | 通过 | 同上：`the_file_changed_after_the_read_is_refused`（报错含「读过之后它被改过」，0 个 PUT） |
| T3b | 通过 | 同上：`a_sibling_changed_mid-batch_is_refused`（a 写入、b 被拒且未被 PUT） |
| T4 | 通过 | 同上：`a_partial_read_does_not_unlock_an_overwrite`、`a_default_read_stopped_at_the_line_cap_does_not_unlock_an_overwrite`、`TestAnOverwriteIsRefusedWhenTheListingHasNoHash`（空摘要 fail-closed）、`TestGetToStdoutDoesNotUnlockAnOverwrite`；`skmr skill write … --force` 退出码 2（`不认识的参数 "--force"`） |
| T5 | 通过 | `TestRunExecutesAScriptFromTheDraft`：公开面 400 → 作者面 digest → 取 archive → **`scripts/hello.sh` 的输出出现**；`TestRunResolvesADraftCodeOnTheAuthorPlane` 另证解析结果与 `Bound` 的取值；租约请求本身由端到端用例与 `TestRunKeepsRunningWhenTheLeaseIsRefused` 覆盖；已发布 + 有绑定的分支由 `TestRunInjectsTheLeaseForAPublishedSkillsDraft` 端到端覆盖（租约值真的进入子进程环境） |
| T6 | 通过 | 真服务端第二次运行不再要 `--yes`（2026-11-10，`lijinzhao/bilibili@JD0T518T`），命中 `41787c75f38dc122-r2` 缓存目录。注意它是真服务端取证，不是 T5 那个假服务端的续跑 |
| T7 | 通过 | `TestRunSaysTheVersionIsGoneWithoutProtocolDetails`、`TestRunTranslatesAMalformedDigest`（畸形 `sha256:` 也不漏原文）；真服务端 `@NOPE9999` 的输出只有中文句子与 `skmr skill versions` |
| T8 | 通过 | `skmr skill write --help` 逐字对照；`TestHelpSentencesFitTheirLimits` 通过（flag 摘要 ≤25 rune、失败下一步 ≤35） |
| T9 | 通过 | `a_batch_that_stops_partway_says_where_it_stands_and_the_rerun_finishes_it`：第一次 `已写入（2）` / `失败（1）：scripts/c.py` / `未开始（3）：scripts/d.py、scripts/e.py、scripts/f.md`，第二次 6 个全部 PUT；另 `TestABatchThatCannotReadItsBaselineSaysSo` 覆盖「已上传、基线未知」、`TestABatchWithoutOverwriteRefusesBeforeWriting` 覆盖未带 `--overwrite` 的预检、`TestABatchRefusesTwoFilesWithTheSameTargetName` 覆盖同一批重名目标 |
| T10 | 通过 | 2026-11-10 真服务端：`skmr run lijinzhao/bilibili@JD0T518T --yes -- python3 --version` → `Python 3.13.3`，缓存目录按草稿 digest `41787c75…`（草稿内容在两次取证之间变过一次，因此 digest 与首轮记录不同；两次运行都成立） |
| T11 | 通过 | 同日真服务端：`@NOPE9999` → 「在 lijinzhao/bilibili 下没有这一版…」，无 400 原文；`insufficient_scope` 不被当成这一版（`TestRunDoesNotCallAnInsufficientScopeAMissingVersion`） |
| T12 | 未执行 | 需要先取得所有者同意发布一份草稿；只影响 A6 的第二半 |

**未执行的一条**：T12 要发布一份真草稿再对比发布前后的缓存目录与确认记录。它验证的是 TD 开放问题 1（草稿与发布后是否同一 digest）。**T12 未执行只让 A6 的「发布后命中同一缓存」保持未验证；A1–A5、A7–A9 不受影响。**

**测试全量**：`cd skillmaster-cli && go test ./...` → 除三条与本版无关的既有失败外全部通过（见下）；`node scripts/check-file-size.mjs`、`check_docs.py`、`impact.py --check`、`generate.py` 后工作区干净。

**既有失败（不是本版引入）**：`TestTheCheckIsThrottledToOnceADay`、`TestANewerBuildIsInstalledAndTheCommandIsUntouched`、`TestATamperedPackageLeavesTheBinaryAlone`。在未改动的 `main` 工作区上同样失败（2026-11-10 复核），属本机环境的自动更新检查未发起请求，与本版改动的文件无关。

## 已知问题

### 1 · 记凭证时列的是「读完之后」的清单，存在一个窄竞态

`skillmaster-cli/cmd/skillmaster/read_proof.go`（`recordFullRead`） · **未验证**（机制清楚，未构造出复现）

现象：`read` 先取字节、再由 `recordFullRead` 列清单取哈希。同一路径若在这两步之间被另一个写者替换，凭证记成的是**新**内容的哈希，而人看到的是旧内容；之后 `write --overwrite` 会放行，覆盖掉人没看过的字节。
影响面：需要并发的另一个写者，窗口是两个请求之间——与 v57 之前按整版 ETag 的窗口同类，不是本版引入的。修法（下一步）：先列清单再读，或对实际读到的字节本地求哈希、与清单一致才记账。v57 不修，记在此处。

**同类的一个窄窗口在写侧**：`write --from` 在 PUT 成功后重列清单，若另一个写者在这两步之间改了同一路径，`recordWriteProof` 会把对方的内容记成「我刚写的」。本版已加一道判断——只有重列得到的版本摘要等于本次 PUT 返回的摘要时才记（`sameVersion`）；对方抢先时**不记**，重跑会在该文件处要求先 `read`。因此这一半是 fail-closed 的；读侧的窗口仍然存在。

### 2 · 从未发布过的 skill：`credentials_missing` 报不出

`skillmaster-cli/cmd/skillmaster/run.go`（`ProbeLease` 分支） · **未验证**

现象：草稿路径若 skill 从未发布，公开面没有 `credentials_bound` / `credentials_missing` 的答案，只能向租约探测。租约返回的是值，不返回「声明了哪些变量还没绑定」，所以文本模式不打印缺失提示、`--json` 的 `credentials_missing` 是空数组（含义从「没有缺失」变成「不知道」）。
影响面：只影响提示与 JSON 的一个字段，不影响注入本身；要修需要作者面响应带上声明（服务端改动），不在本版范围。另一处近似：**已发布** skill 的草稿走公开面答案时，`credentials_missing` 取自**已发布版本**的 frontmatter——草稿新声明的变量可能落在提示之外；`bound` 是 skill 级，不受影响。

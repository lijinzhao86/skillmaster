# v7-cli-file-io · 测试方案

> **最后更新**：2026-10-20
> **状态**：已执行（2026-10-20）；自动化部分通过，未执行项在 §结果 单列
> **所属版本**：[`README.md`](README.md)
> **权威**：[`prd.md`](prd.md) §验收与指标（#1–#31）与 [`technical-design.md`](technical-design.md)（接口、口径、已定决策）。
> **v9 修订（[ADR 0068](../../decisions/0068-export-is-a-one-shot-snapshot.md)）**：T-SEC-01 那句「没有整版 zip 路由」被 v9 的整包导出取代——作者面与网页各有一条 `…/archive`（门槛与「查看文件」同条件），**消费面仍然没有**，本版其余不变量不变。正文按「只增不改」保留原样。

## 测试策略与范围

- **三层验证**：服务端 IT（真 PostgreSQL + 真 HTTP，`AbstractIT`）→ CLI 端到端（真二进制 + 真服务端）→ 网页单元/组件 + 人工视觉验收（Claude 看不到渲染结果）。
- **验收优先**：`prd.md` 的 #1–#31 每一条至少一个用例；#1（写只认草稿）、#14（权限）与 #31（调用档不暴露结构）是成立条件，正反两面都要有。
- **技术不变量单列**：整版字节不出服务端、写路径无全局表锁、`edit` 与 Claude Code 对齐、撞码重试、GC grace、审计无空值——这些不对应某一条验收，但决定实现是否可交付。
- **反向覆盖**：除 #1–#31 的正向映射外，每个用例也要能回答「为什么存在」。没有落在验收行上的用例归到这里：T-ID-08（钉的兼容与 `Suffix()`）→ #23；T-WRITE-02（二进制写/读）→ #3；T-WRITE-05（`If-Match` 的取得与 428/409）→ 技术不变量「写的 If-Match 契约」（#15 的前置）；T-EDIT-06（二进制拒绝）→ #11；T-EDIT-07（并发旧 ETag）→ #15；T-READ-10（grep 只回命中）→ #31 / ADR 0062；T-SEC-02（不落内容副本）→ ADR 0035；T-SEC-03（审计不含内容）→ #19；T-CONC-02/03/04/06（并发与 GC）→ 技术不变量「写路径无全局表锁 / GC grace」；T-WEB-05（视觉验收）→ 本版交付条件；T-WEB-06（组件/函数依据）→ #22；T-PERF-01～04 → PRD §指标。新增用例时先补这一行，再加用例。
- **不覆盖**：全量回归（由用户指令触发，见根 `CLAUDE.md`）、生产部署、压测、浏览器视觉验收（由人做）。
- **口径**：性能数字是**本机开发环境**的 P95，不是生产 SLA；每条性能用例都记录环境（机器、JDK、PostgreSQL 版本、数据量）。

## 验收映射

> 「状态」列是计划时的填写；实现后的真实结果以 §结果 为准。

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #1 写只认草稿，跟着 `invoke` 走 | T-ID-04、T-ID-06、T-WRITE-01、T-PERM-03 | 未执行 |
| #2 草稿身份稳定（短码不变、digest/计数变化） | T-ID-01、T-ID-10、T-WRITE-01、T-CONC-05 | 未执行 |
| #3 `cat` 原样（CRLF / 无末尾换行 / 二进制） | T-READ-02 | 未执行 |
| #4 管道等价（`cat \| head` / `tail` / `wc` / `grep`） | T-READ-04 | 未执行 |
| #5 流式与提前退出 | T-READ-05 | 未执行 |
| #6 stdout / stderr 分离 | T-READ-03 | 未执行 |
| #7 退出码（`cat` / `grep` / `rm`） | T-READ-03、T-READ-08、T-RM-01 | 未执行 |
| #8 `wc` 与本地一致、不传输字节 | T-READ-06、T-READ-07 | 未执行 |
| #9 `grep` 只在草稿、输出与选项、截断 | T-READ-08、T-READ-09 | 未执行 |
| #10 `write` 覆盖 / 新增 / 空 stdin | T-WRITE-01 | 未执行 |
| #11 `edit` 一处 / 全部 / 0 处 / 多处 | T-EDIT-01～T-EDIT-05 | 未执行 |
| #12 `rm` 删除 / `SKILL.md` / 最后一个文件 | T-RM-01～T-RM-04 | 未执行 |
| #13 `mv` / `cp` 路径变化、内容不变、目标已存在 | T-MVCP-01～T-MVCP-04 | 未执行 |
| #14 权限（五个可授予档 + 所有者 + scope） | T-PERM-01～T-PERM-05、T-PERM-07、T-PERM-08 | 未执行 |
| #15 并发（同一摘要、第二个 409） | T-CONC-01 | 未执行 |
| #16 上限与校验 | T-WRITE-03、T-WRITE-04 | 未执行 |
| #17 状态语义（410 / 503 / 404 / 403） | T-PERM-05、T-PERM-06、T-ID-10、T-CONSUMER-01 | 未执行 |
| #18 不改变消费面 | T-CONSUMER-01 | 未执行 |
| #19 审计 | T-AUDIT-01 | 未执行 |
| #20 `read` 不变 | T-READ-11 | 未执行 |
| #21 内容命令不接受 `--json` | T-READ-12 | 未执行 |
| #22 网页与终端对得上（地址 / 版本码 / `?version=`） | T-WEB-01～T-WEB-04 | 未执行 |
| #23 短码就是行 id（新 / 旧 / 消费面 / 撞码重试） | T-ID-01～T-ID-03、T-ID-07 | 未执行 |
| #24 发现钉（`version list --query` / 翻页） | T-ID-09 | 未执行 |
| #25 invoke 三种钉 | T-ID-04、T-PERM-08 | 未执行 |
| #26 状态变了地址不变 | T-ID-10 | 未执行 |
| #27 缺省钉 | T-ID-05 | 未执行 |
| #28 `ls` 与多路径 `cat` | T-READ-01、T-READ-02 | 未执行 |
| #29 撞摘要（409 `content_already_exists`） | T-WRITE-06 | 未执行 |
| #30 写 `SKILL.md` 的元数据（版本快照更新、skill 行不动） | T-WRITE-03 | 未执行 |
| #31 调用档不暴露结构（任何接口都没有文件列表/全局检索） | T-PERM-01、T-PERM-09、T-SEC-01 | 未执行 |

**成立条件**：#1、#14 与 #31 的用例必须全绿；任一失败即不算交付。

## 用例

### 身份、地址与钉

| 用例 | 前置 | 步骤 | 期望 |
|---|---|---|---|
| **T-ID-01** 新行短码、旧行 ULID | 一个新提交的草稿；一个 v6 遗留的 26 位 ULID 行 | 提交后读 `version list --json`；再读旧行 | 新行 `code` 匹配 `^[0-9A-HJKMNP-TV-Z]{8}$`；旧行仍是 26 位 ULID；`version show` 的 `pin` 与 `code` 一致（草稿） |
| **T-ID-02** 钉的拼写与消费面拒绝 | 一份草稿（短码 X）、一份旧草稿（ULID U） | 作者面分别用 `@X`、`@U`、`@draft:U`、`@draft:X`；消费面用 `@X`、`@U` | 前三个 200；`@draft:X` 与其它无法解析的后缀同一条 **404**；消费面两者 404 |
| **T-ID-03** `code` / `pin` 的输出 | 同一 skill 的草稿、线上版本、已丢弃版本、无名旧版本 | 作者面 `GET versions` / `version show` | 每行都有 `code`；草稿/已丢弃的 `pin` 是裸行 id；线上是版本号；无名旧版本是 `sha256:…` |
| **T-ID-04** `invoke` 三种钉 | 草稿 X、草稿 Y、线上 `1.2.3`、无名旧版本摘要 D；`browse` 与 `invoke` 两档凭据 | 分别 `invoke @X`、`@1.2.3`、`@D`；再 `invoke @Y` 后 `write`；再用 `invoke` 档 `invoke @X` | 草稿钉进入作者面并记住；`browse` 用线上短码也能 `invoke`；`invoke` 档用短码 **403**，CLI 说明没有作者面 **API** 入口；命名/摘要钉走消费面、不回落；`invoke @X` 后写落 X，换成 `@Y` 后写落 Y（写命令跟着钉走） |
| **T-ID-05** 缺省钉 | 一份有上线版本、一份没有上线版本但有一份草稿的 skill | 不写钉跑 `invoke` / `cat` / `ls` / `wc` / `grep`；分别用 `write`+、`browse`、`invoke` 三档 | 有上线版本时钉当前版本；没有时 `write`+ 回落到最新可见版本并在 stderr 写明；`browse` 得到「还没有上线版本」；`invoke` 得到 404；不静默钉到别处；四条读命令的缺省钉同一口径；`grep` 的缺省钉解析到线上版时回 409 `grep_requires_draft`（这是正确结果，不是失败） |
| **T-ID-06** 写钉只接受行 id（五条命令） | 草稿 X、线上 `1.2.3`、摘要 D；`invoke` 分别钉在草稿和线上版本 | `write` / `edit` / `rm` / `mv` / `cp` 各跑一次 `@X`、`@1.2.3`、`@D`；`invoke @1.2.3` 后不带钉各跑一次；直接向作者面发带命名/摘要的写请求 | 五条命令只有 `@X`（及 invoke 钉为 X）写入；`@1.2.3` / `@D` 在 CLI 本地被拒（共享前缀「写只针对草稿」）；服务端直连也拒绝（不改内容）；即使 D 恰好等于草稿内容也不按摘要写 |
| **T-ID-07** 撞码重试 | 测试注入一个固定的 `ShortCode` 生成器：第一次返回已存在的 id，第二次返回新 id | 提交一次 | 第一次主键冲突使事务回滚；`DraftRecorder` 在新事务重试；最终只有一行、id 为新值、无 500、审计只有一条 |
| **T-ID-08** CLI 钉的兼容与 `Suffix()` | 一个旧版 pin 文件（只有 `name` / `digest`）；一个新的 code 钉；一个命名钉带 `code` | `read` / `cat` 各一次；检查发往服务端的路径 | 旧文件按 name/digest 兼容；`Kind=code` 发裸 id；命名钉发版本号（不因带 `code` 改发短码）；摘要钉原样发 `sha256:…`，不重复加前缀 |
| **T-ID-09** 发现钉 | 三个版本：两份草稿、一份线上；一份可按名称与版本号命中 | `version list --query <名称>`、`--query <版本号>`、`--status draft`、`--limit 1` 再 `--cursor` | 命中行第一段是完整地址；草稿行 `@<短码>` 可直接粘贴进 `cat`；翻页不重不漏 |
| **T-ID-10** 状态变了地址不变 | 钉住一份草稿 X；把它上线；再另建一份草稿并丢弃 | 上线后用 `@X` `cat` / `write`；丢弃后用 `@X` `cat` / `write`（`maintain`+、`write`、`browse`） | 短码仍指向同一行；上线后 `cat` 按作者面读到（`browse`+），`write` 409 `version_is_not_draft`，消息以「写只针对草稿」开头并指出它现在是 `@1.2.3`；丢弃后 `maintain`+ 写 409、`write` 写 404、`browse` 写 403（入口档不够）；不会静默改读另一版 |

### 读

| 用例 | 前置 | 步骤 | 期望 |
|---|---|---|---|
| **T-READ-01** `ls` | 一份含 `SKILL.md`、`references/a.md`、二进制 `blob.bin` 的草稿 | `ls`；`files`；`ls --json` | 每行 `path<TAB>bytes<TAB>text\|binary`；`files` 输出相同；`ls --json` 被明确拒绝（帮助里也没有） |
| **T-READ-02** `cat` 原样与多路径 | 本地副本：CRLF 文件、无末尾换行文件、二进制文件、两个普通文件 | `cat` 各文件并与本地 `cmp`；`cat a b`；`echo $?` | 字节逐字相同；多路径按参数顺序串接、无分隔、无提示；二进制原样；成功退出 0 |
| **T-READ-03** `cat` 失败语义 | 一份草稿 + 一个不存在的路径 | `cat 不存在`；`cat 存在 不存在 另一存在`；`echo $?` | 失败退出 1；stdout 只有成功文件的字节；stderr 一句错误；多个路径中一个失败仍继续读其余 |
| **T-READ-04** 管道等价 | 同一份文件在本地与服务端草稿 | 两边各跑 `cat \| head -n 20`、`cat \| tail -n 20`、`cat \| wc -l`、`cat \| grep -n <串>` | 输出与退出码一致；服务端输出没有行号或提示混入 |
| **T-READ-05** 流式与提前退出 | 1 MiB 文件；作者面与消费面各一份 | `cat 1MiB \| head -n 5`；在 IT 里用计数 `BlobStore` 替身/访问日志量服务端读出的字节 | 客户端只读前几行；服务端从 blob 读出的字节（或 `bytes_sent`）< 100 KiB；断开后不再读剩余块；消费面（`invoke` 档）同样 |
| **T-READ-06** `wc` | 一份含多行、多个 ASCII 空白、多个文件的草稿；本地副本 | `wc`、`wc -l`、`wc -w`、`wc -c`，带路径与不带路径 | 在 ASCII 空白夹具上，每文件与 `total` 与本地 `wc` 相同；非 ASCII 空白与二进制 `words` 的差异见 K2 / K4；响应只含计数与 `digest` / `pin`，不含文件字节（抓包/日志量） |
| **T-READ-07** `wc -c` 不读字节与二进制 | 一份 16 MiB 内的大文件；一个二进制文件；一个不存在的路径 | `wc -c`；`wc -l -w`；`wc 不存在` | `-c` 只读清单的 `size`（用 M6 替身断言没有 `open`/`get`）；总量是所选文件 `size` 之和；二进制也计入；不存在的路径报错并退出 1（与 `/count` 的 404 同一事实） |
| **T-READ-08** `grep` 语义 | 固定夹具：多处命中、大小写、路径前缀、二进制、超长行、**1001 处命中**（触发默认上限 1000） | `grep -F`、`-F -n`、`-l`、`-i`、`--path references/`、`--path references-old/`、无命中、坏正则；截断用 1001 命中的夹具（CLI 不暴露 `--limit`；服务端 `limit=1` 在 IT 里单测） | 输出分别为 `path:text`、`path:line:text`、只列路径；`-i` 大小写不敏感；`references/` 不命中 `references-old/`；截断时 `truncated: true` 且 stderr 说明；无命中退出 1；坏正则 400 `invalid_request`（detail `bad_pattern`）、退出 2；**反向引用 / lookaround 与坏语法同一条**；pattern > 1000 字符 → detail `too_long`；`(a+)+$` 对长行线性返回（ADR 0066）；**成本上界（ADR 0067）**：`a{999}b` 与 `a{10,101}b` → detail `too_expensive`（不读草稿）；`(((a{100}){100}){100}){100}`（乘积 10⁸）、`((a×955){100}){100}`（969 字符，曾在 512 MB 堆上编译期 OOM）与 `((((a{0}){100}){100}){100}){100}`（`{0}` 不归零上界）同样即时拒绝；`\Qa{999}\E` 照常搜索；`(a{100}){100}b` 在长行上、以及同一 pattern 在 10 万条 100 字符的短行上，都在截止时间内 400（后者证明截止是请求级、不是按行重置）；1000 字符的 `-F` / `-F -i` 字面量仍在毫秒级找到；二进制跳过 |
| **T-READ-09** `grep` 只认草稿 | 已上线版本、已下架版本、已丢弃草稿、草稿各一份；用行 id 与版本号各点名一次 | **`maintain`+** 对四个非草稿跑 `grep`；**`write`** 对已下架/已丢弃跑一次；**`browse`** 对已下架/已丢弃跑一次 | `maintain`+：行 id 与版本号/摘要钉都 409 `grep_requires_draft`（不是写路径的 `version_is_not_draft`）；`write` / `browse` 用**行 id** 点名已下架/已丢弃 → 404（门序 ②，状态对该档不可见），用**版本号/摘要**点名 → 409（钉形状先于门序，PRD #17）；CLI 提示改用 `cat \| grep` |
| **T-READ-10** `grep` 只回命中（安全） | 16 MiB 版本，pattern 只命中一行 | 跑 `grep`；量响应体 | 响应只含一行命中与 `digest`/`pin`/`truncated`；响应体远小于 16 MiB；不含未命中文件的字节 |
| **T-READ-11** `read` 不变 | 一份草稿 | `read` 一次 | 仍有行号、上限与续读提示；与 `cat` 并存 |
| **T-READ-12** 内容命令不接受 `--json` | 任意版本 | `cat --json`、`ls --json`、`wc --json`、`grep --json`；`version list --json` | 前四个被明确拒绝；`version list --json` 仍可用 |

### 写

| 用例 | 前置 | 步骤 | 期望 |
|---|---|---|---|
| **T-WRITE-01** 覆盖 / 新增 / 空 / no-op | 一份草稿；已有 `a.txt` | `write 地址 a.txt` 传入新内容；`write 地址 b.txt` 传入新内容；`write 地址 c.txt` 传空；再对 `a.txt` 写一次**相同内容** | `cat a.txt` 是新内容；`b.txt` 出现；`c.txt` 是 0 字节；每次返回新 digest/计数；**写前后 `code` / `pin` 不变**（验收 #2 的第一句）；**相同内容写是 no-op（200，digest/计数不变，不写审计），不 409 `content_already_exists`** |
| **T-WRITE-02** 二进制 | 一份草稿 | `write 地址 blob.bin` 传非 UTF-8 字节；`ls`；`cat`；`grep`；`wc` | `ls` 标为 binary；`cat` 字节原样；`grep` 跳过；`wc` 仍计数 |
| **T-WRITE-03** `SKILL.md` 与元数据 | 一份草稿；一个线上版本 | 改根 `SKILL.md` 的 title/description 后 `write`；读版本事实与 skill 行；再上线 | 该版本的 `title`/`description`/`frontmatter` 更新；skill 行不变；上线后 skill 行按 ADR 0031 §决定 9 的现有规则取该版本的值；缺 frontmatter / `name` 不符 → 拒绝且不改 |
| **T-WRITE-04** 上限与路径 | 一份草稿；一个 512 文件版本；一个近 16 MiB 版本 | 写第 513 个文件；写使总量超 16 MiB；写绝对路径 / `..` / `\` | 超限 400 `INVALID_UPLOAD`（`too_many_files` / `too_large`），不写入；非法路径 400 `invalid_path` |
| **T-WRITE-05** `If-Match` 的取得与比较 | 草稿 X；本机没有 X 的 digest | `write @X a.txt`；再直接发缺 `If-Match` 的写；再发一个过期 `If-Match` | CLI 先 `GET …/files` 拿 `ETag` 再写；缺头 428 `if_match_required`；过期 409 `digest_mismatch`（detail 带当前 digest）；内容不变 |
| **T-WRITE-06** 撞摘要 | 同一 skill 的线上版本 V 与草稿 D；D 的清单与 V 相同；另有一份已丢弃的 D2 与 D 同内容 | 在 D 上写最后一份内容，使 digest 等于 V；再用 `write` 档写 D 使 digest 等于 D2 | 409 `content_already_exists`：V 这一支 detail 带 `pin`/`state`，D2 这一支只带 `state`、不带 `pin`（该状态对 `write` 不可见）；D 不被删除、不被合并、不被改；CLI 说清已存在的那一版（可见时带 @X） |
| **T-EDIT-01** 唯一匹配 | 草稿含 `foo` 一次 | `edit --old foo --new bar` | 只改那一处；其余字节不变 |
| **T-EDIT-02** 全部替换 | 草稿含 `foo` 三次 | `edit --old foo --new bar --replace-all`；再用 `--all` 跑一次 | 两处都全部替换；`--all` 与 `--replace-all` 等价 |
| **T-EDIT-03** 0 处 | 草稿不含 `zzz` | `edit --old zzz --new y` | 退出 1；stderr 说明没有匹配；文件字节不变 |
| **T-EDIT-04** 多处未给替换全部 | 草稿含 `foo` 两次 | `edit --old foo --new bar` | 退出 1；说明有多处、需 `--replace-all`；文件不变 |
| **T-EDIT-05** 空 `--old` | 任意草稿 | `edit --old '' --new x` | 退出 1；不发写请求 |
| **T-EDIT-06** 二进制目标 | 草稿含二进制 `blob.bin` | `edit 地址 blob.bin --old a --new b` | CLI 先读清单，命中 `is_binary` → 本地拒绝（中文句子），退出 1，不读内容、不写 |
| **T-EDIT-07** 并发改动 | 两个会话在同一版上 `edit` | 第二个在第一个提交后仍用旧 `ETag` | 第二个 409 `digest_mismatch`；提示重新读一遍；不静默覆盖 |
| **T-RM-01** 删除与多路径 | 草稿含 `a`、`b`、`c` | `rm a`；`echo $?`；`rm b 不存在 c`；`echo $?` | `a` 消失且成功退出 0；不存在的一个失败但 `b` / `c` 仍被删；该次退出 1；清单与计数更新 |
| **T-RM-02** 根 `SKILL.md` | 草稿 | `rm SKILL.md`；`mv SKILL.md references/SKILL.md` | 两者都 409 `skill_md_cannot_be_deleted`；根文件仍在 |
| **T-RM-03** 删到空 | 一份只有根 `SKILL.md` 的草稿 | `rm SKILL.md` | 409 `skill_md_cannot_be_deleted`；文件仍在。`last_file_cannot_be_deleted` 只对「清单已没有根文件」的损坏数据可达，是护栏而不是正常路径 |
| **T-RM-04** 嵌套 `SKILL.md` | 草稿含 `references/SKILL.md` | `rm references/SKILL.md` | 200；该文件消失；根 `SKILL.md` 不受影响 |
| **T-MVCP-01** `mv` | 草稿含 `a.txt` | `mv a.txt b/a.txt` | 路径变为 `b/a.txt`；字节与本地副本逐字相同；digest 变化；`cat` 可读 |
| **T-MVCP-02** `cp` | 草稿含 `a.txt` | `cp a.txt copy.txt` | 两份内容相同；`version_file` 指向同一 blob（内容寻址）；digest 变化 |
| **T-MVCP-03** 目标已存在 | 草稿含 `a.txt`、`b.txt` | `mv a.txt b.txt`；`cp a.txt b.txt` | 409 `file_already_exists`；两者内容不变 |
| **T-MVCP-04** 根 `SKILL.md` 与 `If-Match` | 草稿 | `mv` 根 `SKILL.md`；缺 `If-Match` 的 `mv` | 前者 409 `skill_md_cannot_be_deleted`；后者 428 `if_match_required` |
| **T-AUDIT-01** 审计 | 一份草稿 | 依次 `write`、`rm`、`mv`、`cp`；再发一个被拒绝的写 | 每次成功写一条 `version_file_write`；事件字段 actor/targetType/targetId 齐全，detail 按 TD 的键（`version` / `code` / `op` / `path` / `from`·`to` / `digest_before` / `digest_after` / `files` / `bytes`，只出现本次真实存在的）；无 null；被拒绝的写不产生审计；审计与写同事务（写失败则无审计） |

### 权限、状态与面

| 用例 | 前置 | 步骤 | 期望 |
|---|---|---|---|
| **T-PERM-01** `invoke` 档 | 一把 `invoke` + `skills:read` 的凭据；**另有一把 `invoke` + `skills:read` + `skills:write` 的凭据**；一个线上版本 | `cat` 单文件；`ls`；`wc`；`grep`；`invoke @短码`；消费面 detail 与 `/metadata`；版本列表；网关索引 V1；**用带 `skills:write` 的那把跑五条写命令** | `cat` 200 且只取单文件；`ls` / `wc` / `grep` 403（没有清单/计数/扫描）；作者面全部 403；消费面 detail 403（现有 `browse` 门）；`/metadata` 200 且无 `files`；**消费面** `GET /api/v1/skills/{ns}/{name}/versions` 200（只列线上），网页版本栏可打开；**作者面**版本列表 / `version show` 403；网关索引 V1 仍 200（匿名公开面，只含公开 gateway skill，豁免）；**五条写命令全部 403**（档位不够；scope 里有 `skills:write` 也不放行），不落任何写 |
| **T-PERM-02** `browse` 档 | 一把 `browse` 凭据；**另有一把 `browse` + `skills:write` 的凭据**；一个线上版本与一份草稿 | 读线上；读草稿；**写线上钉**（`write @<线上短码>`，直发 `PUT`）；写草稿钉；用线上版本的短码 `invoke`；**用带 `skills:write` 的那把跑五条写命令（线上钉）** | 线上可读；读草稿 404（状态对该档不可见）；**写线上钉 403**（入口档不够，不是目标不存在；不带 `WWW-Authenticate`）；**写草稿钉时 CLI 的 `ETag` 预读先 404**（`browse` 看不见草稿），不是 403；短码 `invoke` 成功并读到内容；**五条写命令全部 403**（读档不进写路由，scope 不缺也不行），不落任何写 |
| **T-PERM-03** `write` / `maintain` / `admin` / 所有者 | `write` 凭据 A、`maintain` B、`admin` C、**所有者账号**的凭据 D（owner 不是可授予档，不在梯子上）；A 自己的草稿、别人的草稿（对 A 可见） | A 写自己的草稿；A 写 / 读别人的草稿；B、C、D 写 A 的草稿 | A 写自己的 200；A 写别人的 403（不是 404）；A 读别人的草稿 200（`write`+ 可读）；B / C / D 都 200（`maintain`+ 不受提交人限制） |
| **T-PERM-04** scope（读与写是两把独立的尺） | **同一账号**的三把 `write` 档 key（草稿的提交人就是这个账号）：A 带 `skills:read` + `skills:write`（基线）、B 带 `skills:read` 缺 `skills:write`、C 带 `skills:write` 缺 `skills:read`；A 自己的一份草稿 | A 读 + 写；B 读 + 写；C 读（`cat`、`edit` 的 GET）+ 按路径写（`PUT` 单文件，`If-Match` 从库里取）+ 走一次 CLI 的 `write`（本机钉不带 digest）、再试 `ls` / `grep` | A 全通；B 读 200、写 403（scope 不够；`WWW-Authenticate` 点名 `scope="skills:write"`）、不落任何写；C 写按路径 200、`cat` 与 `edit` 的 GET 403、`ls` / `grep` 也 403，**CLI 的 `write` 在 `ETag` 预读就 403**（读不了，整条命令失败）；三把的组合都不静默降级 |
| **T-PERM-05** 不可见 vs 可见但无权 | 一份在**未共享给该 `write` 用户**的 skill 里的草稿；一份同 skill 内可见的别人的草稿 | 读不可见；写不可见；写可见的别人的 | 前者 404（skill 不可见，不泄露存在性）；写可见的 403（存在但不是我提交的）；两者都不改内容 |
| **T-PERM-06** 已下架 / 离线 / 面 | 一个已下架版本；一个下线的 skill | 消费面读已下架；作者面 `maintain`+ 读；`browse` / `write` 读；`invoke` 读；skill 下线后消费面读；作者面读 | 消费面已下架 410；`cat` 的 `maintain`+ 回落作者面 200、其它档保留 410；`ls` / `wc` 对 `browse` / `write` 404（状态不可见）、对 `invoke` 403；下线后消费面 503，`cat` 回落作者面（`browse`+）、`invoke` 保留 503，作者面清单命令照常 |
| **T-PERM-07** 短码可见性 | 草稿、线上、已下架、已丢弃各一份；**五个可授予档 + 所有者账号**的凭据 | 各档读 `version list` / `version show` / 网页 | 草稿短码 `write`+；线上短码 `browse`+；已下架/已丢弃短码 `maintain`+；`invoke` 档任何短码都看不到 |
| **T-PERM-08** `invoke` 档用短码 | `invoke` 凭据 + 一份线上版本 | `invoke @短码` | 作者面 **403**；CLI 说清「调用档没有作者面入口；行 id 是作者面的地址（需要 browse 及以上），请改用版本号或摘要」；不回落消费面 |
| **T-PERM-09** `invoke` 档拿不到任何文件列表 | `invoke` 凭据；线上版本 | 逐个请求：作者面 `/files`、消费面 detail、`/metadata`、版本列表、网关索引 V1 / V2、CLI `ls` / `wc` / `grep`、网页 | 作者面 403；消费面 detail 403（现有 `browse` 门，不是「200 无 files」）；`/metadata` 200 且无 `files`；**消费面** `/versions` 200（只列线上）、网页版本栏可打开；**作者面**版本列表 / `version show` 403；网关 V1 200（匿名公开面，只含公开 gateway skill，**豁免**；V2 本来没有 `files`）；CLI 403；网页不画文件树；单文件 `GET` 仍 200；用户 skill 的目录结构无法从任何响应推断 |
| **T-CONSUMER-01** 消费面不变 | 一个线上版本 + 一份草稿 | 对草稿做全部写操作；再读线上版本与 `@版本号` | 线上版本与 `@版本号` 的字节、digest、清单完全不变；草稿写不影响消费面 |

### 并发、一致性与 GC

| 用例 | 前置 | 步骤 | 期望 |
|---|---|---|---|
| **T-CONC-01** 同一版并发写 | 两个会话；同一草稿同一路径；同一个起始 digest | 两边同时 `write` | 一个 200；另一个 409 `digest_mismatch`；最终字节是先到的；没有混合内容 |
| **T-CONC-02** 不同版 / 不同 skill 并发写 | 两个草稿（同 skill 不同版，或不同 skill） | 两边同时写 | 两者都 200；不因 `version_file` 表锁排队（记录墙钟时间；写路径不拿 `SHARE ROW EXCLUSIVE`） |
| **T-CONC-03** 写与 sweep 并发 | 一个正在写的草稿；一个触发 `BlobGc.sweep` 的 **submit / 软删除** | 写事务 `put` 后、提交前，让另一个事务跑 sweep | sweep 不删这个新 blob（`created_at` 在 grace 内）；写提交成功；无 FK 失败、无丢字节 |
| **T-CONC-04** GC grace 与孤儿 | 一个当前无引用的 blob（手工造，`created_at` = now）；一个 11 分钟前的无引用 blob（都用 TD 的 RFC3339 口径） | 跑 sweep | now 的保留；11 分钟前的删除；被引用的都保留 |
| **T-CONC-06** `rm` 后复用旧 blob | 草稿：写 X → `rm X`（blob 失去引用，`created_at` 仍是旧时间）→ 10 分钟后写回同一内容；同时让一个 sweep 跑 | 写一次；若撞 FK，客户端重跑 `write` | 第一次可能因 sweep 删掉旧 blob 而 FK 失败（可见、可重试）；重跑成功、不丢字节；**只有撞过 FK 的那一路**重跑后 blob 是新 `created_at` 且被引用；没撞 FK 时复用旧 blob、`created_at` 仍是旧值 |
| **T-CONC-05** 写后计数与摘要 | 一份草稿 | 每次写后读 `version show` | `digest` / `file_count` / `total_bytes` 与该版清单一致；`state` 仍是 draft；`version` 仍为 null；`number` / `submitted_at` / `submitted_by` 不变 |

### 安全与性能

| 用例 | 前置 | 步骤 | 期望 |
|---|---|---|---|
| **T-SEC-01** 没有整版出口 | 一个 16 MiB / 512 文件的版本 | 枚举作者面与消费面的文件/内容路由；尝试 `ls`、`grep`、`wc`、`cat`；用 `invoke` 凭据看消费面 detail / 网关索引 | 唯一内容出口是单文件 `GET …/files/{*relpath}`；没有整版 zip/全量文本路由；`grep`/`wc`/`ls` 的响应只含命中/计数/元数据；`invoke` 档没有任何文件列表（详见 T-PERM-09） |
| **T-SEC-02** 客户端不落内容副本 | 跑完 `cat` / `grep` / `wc` | 检查 CLI 的配置/缓存目录与临时目录 | 只有钉（name/digest/code）与凭据，没有可复用的内容副本（ADR 0035） |
| **T-SEC-03** 审计不含内容 | 一次 `write` | 读审计行 | detail 只有路径 / 操作 / digest / 计数等元数据（按 T-AUDIT-01 的键）；**不含文件字节或命中文本** |
| **T-PERF-01** 单写 P95 | 本机 CLI + 本地数据库；文件 ≤64 KiB | 连续 20 次 `write`，取 P95 | < 300 ms（PRD 指标）；记录机器/JDK/PG 版本 |
| **T-PERF-02** `cat \| head` 传输量 | 1 MiB 文件 | `cat \| head -n 5` | 服务端写出 < 100 KiB（PRD 指标）；与 T-READ-05 共用测量 |
| **T-PERF-03** 内部目标 | 512 文件 / 16 MiB 版本 | `ls`；`grep -F`；`wc -l -w` | `ls` < 50 ms；16 MiB 固定串 grep < 500 ms；16 MiB `wc` < 500 ms（目标，实测后回填 TD/test-plan） |
| **T-PERF-04** 不同版并发不排队 | 两个草稿 | 同时写；对比串行两次的墙钟 | 并发两次接近单次，不出现表的全局串行 |

### 网页与设计

| 用例 | 前置 | 步骤 | 期望 |
|---|---|---|---|
| **T-WEB-01** 草稿页地址与复制 | 一份草稿（短码 X）；**`write`+ 且为该草稿提交人**（或 `maintain`+）；写操作另需 `skills:write` | 打开草稿单版页；点复制；粘贴进 `skill cat` / `skill write` | 地址是 `namespace/name@X`；复制的是**完整地址**；CLI 能解析并读到 / 写到这一版；剪贴板不可用时按设计件的复制失败提示「复制失败，请手动选择」，不谎报成功 |
| **T-WEB-02** 线上页地址与版本码 | 一个线上版本；作者面与调用档各一个账号 | 打开线上页；看地址与「版本码」；复制 | 地址是 `@版本号`（或摘要），不是行 id；作者面另有版本码（行 id）可复制；调用档看不到版本码 |
| **T-WEB-03** 版本列表行 | ① `maintain`+ 看全量六行（含已下架、已丢弃）；② `write`+ 单独看草稿行；③ 调用档 | 看每行说明子行 | ① 每行显示版本码 + 复制；② 草稿行显示；③ 调用档不画。版本码在**作者与时间之前**、同一格**不截断**；复制按钮**压在整行链接之上**：点复制不导航、**复制的是完整版本码（8 / 26 位），不是 `ns/name@` 整串**；线上行仍显示版本号 |
| **T-WEB-04** URL（设计可判定的部分） | 草稿 X、**旧 ULID 草稿** U（设计件现成的 26 位 ULID 是线上版本，不能当草稿用）、线上 `1.2.3` | 打开 `?version=X`、`?version=U`、`?version=1.2.3`；再打开 `?version=draft:U` | 草稿 / 旧 ULID **草稿**落到同一行；线上用版本号 / 摘要；**旧 `draft:` 前缀不兼容**（设计稿只认裸 id），落「没有这一版」；`parseVersionRoute` 必须保留不认识的 `version` 值（否则会落版本列表，见 TD） |
| **T-WEB-05** 视觉验收 | 设计稿 `design/skillmaster-控制台演示.html` 与产品页面 | 对照 `design/shots/console/version-draft.png` 等；请人看 | 结构、类名、尺寸、文案与设计稿一致；「检查全绿」不算验收，必须有人看过 |
| **T-WEB-06** 组件（设计依据）与前端函数（TD 依据） | `VersionFacts` / `VersionRow`；`version.ts`；作者面与调用档 | 组件测试 + `version.ts` 单测 | **设计依据**（`design/README.md:1153`、`console-states.mjs:2791/3359/3658`）：`VersionFacts` **替换**草稿专用的「版本标识」行为「版本码」，所有作者面状态都画、位置第 3；调用档仍四项；`VersionRow` 顺序为 说明 → 版本码 + 复制 → 作者·时间，不截断，复制不导航。**TD 依据**：`version.ts` 无 `DRAFT_PIN` / `draft:`；`answersPin` 草稿/已丢弃只认裸 code，线上只认 name/digest；`useVersionActions` 不再拼 `draft:` |

## 环境与数据

- **服务端 IT**：真 PostgreSQL（`skillmaster-server/scripts/init-test-db.sh` 初始化测试库），`./mvnw -o verify -Dit.test=<IT>`；每个浏览器/作者面 IT 用 `@Sql("/sql/truncate-business-tables.sql")` 清业务表；`AbstractIT` 起真端口。`-o` 依赖本机 `.m2` 已缓存——冷环境第一次去掉 `-o` 或先联网拉依赖。
- **CLI 端到端**：本机服务端（`.env` + `./mvnw -o spring-boot:run`）+ `go build` 出的 `skillmaster` 二进制；`HOME` 指向临时目录以隔离 pin 文件；每个用例开始前清 pin 文件；**五个可授予档**（invoke / browse / write / maintain / admin）各一把 + 一把**所有者账号**的凭据（owner 不可授予，不在梯子上）+ 三把交叉 scope 的 key。**读档 + `skills:write` 的那把可以照常签发**（scope 与档位是两件事）；**写档 + `skills:write` 缺 `skills:read` 的那把不能靠 API key 签发拿到**——签发时会把 `skills:read` 补进带 `skills:write` 的行（`Scopes.withImpliedRead` / [ADR 0039](../../decisions/0039-api-key-defaults-and-their-edges.md) §决定 2），所以这条组合在 IT 里直接种 `api_key` 行（令牌要符合 `skm_` + 43 位 base64url 的形状（`ApiKeyFormat`），`token_hash` 存整串的 sha256；`note` / `expires_at` / `last_used_at` / `last_used_from` 可空），或用只注册 `skills:write` 的 OAuth 客户端换一把令牌（令牌签发不补 read）。
- **网页**：`npm run dev` + `npm test`（vitest）；视觉对照打开设计件（`design/` 下静态服务）与产品页；截图存 `design/shots/`（产品截图另存，不覆盖设计件的图）。
- **夹具**：一份含 `SKILL.md`、`references/`、二进制、CRLF、无末尾换行、1 MiB、1001 处命中文件的草稿；一个 512 文件 / 16 MiB 版本；一个线上版本；一个已下架版本；一个已丢弃草稿；一份旧 ULID 行；第二个 skill 用于跨 skill / 权限。
- **测量**：服务端访问日志或 `StreamingResponseBody` 的计数替身量 `bytes_sent`；`BlobStore` 替身断言 `-c` 不读字节；`ShortCode` 生成器注入撞码；`blob.created_at` 按 TD 的 RFC3339 口径改到 11 分钟前造 GC 夹具；`EXPLAIN (ANALYZE, BUFFERS)` 确认 `ls` / `grep` 走主键。
- **清理**：truncate 业务表、删临时 `HOME`、停服务端、恢复被改的时钟/夹具；不把测试数据留在开发库。

## 结果

**执行日**：2026-10-20。环境：本机 macOS / JDK（`mvnw` 默认）/ PostgreSQL（本地 `skillmaster` 库）；CLI 用 `go build` 的二进制连 `http://localhost:8080` 的真服务端；网页用 vitest。

**总量**：服务端单测 **229 个全过**（含新增 `SkillPathsTest`）；本次改动涉及的 12 个 IT 类全过（`AuthorSkillIT` 7、`WebSkillsIT` 25、`SkillVersionPinIT` 18、`SkillSubmitIT` 21、`GatewayIT` 14、`SkillContentIT` 9、`SkillDetailIT` 15、`InsufficientScopeIT` 2、`SkillSharingIT` 12、`ServerSmokeIT` 2、`WebSkillMetadataIT` 8、`DraftFileIT` **22**）；验收补课新增的 `SkillRungsIT` 8、`CliRungMatrixIT` 7 也全过；CLI `go test ./...` 全过；网页 **399 个用例全过**（新增 `VersionFacts` 与 `VersionRow` 的版本码/复制用例）。

| 用例组 | 结果 | 证据 / 未做项 |
|---|---|---|
| T-ID-01～T-ID-10 | **部分通过** | `SkillVersionPinIT`（18 个，含 8/26 位解析、`@draft:<短码>` 404、`code`/`pin` 输出）；`DraftFileIT.aLegacyUlidIsAcceptedBareAndPrefixedButAShortCodeIsNotPrefixed`；`DraftFileIT.aCollidingShortCodeIsDrawnAgainInANewTransaction`（撞码 → 新事务 → 新码）；CLI 单测覆盖 `Suffix()`、`isDraftPin` 与 `rowIDOfPin`（`draft:<短码>` 不再在本地剥前缀）；缺省钉的作者面回落三态各有单测：`TestCatFallsBackToTheAuthorPlaneForARevokedVersion`（410）、`TestCatDefaultPinFallsBackToTheNewestDraft`、`TestCatDefaultPinFallsBackToTheAuthorPlaneWhileTheSkillIsOffline`（503 时只取线上版本，不读草稿）；`version show` 输出「版本码」（e2e：`SYM5MKYJ`，8 位）。**新增**：`CliRungMatrixIT.aLegacyUlidDraftIsAddressableThroughTheCli`（复制一行成 26 位 ULID，`version show` / `list` 都接受） |
| T-READ-01～T-READ-12 | **部分通过** | 本机 CLI 连真服务端：`ls` / `files`、`cat`（单文件、多路径、二进制原样）、`wc`（默认 / `-c`）、`grep`（`-F`、`-F -i`、`-n`、`-l`、`-i`、无命中退出 1、已发布退出 2）；成本上界：`a{999}b`、`a{10,101}b`、`(((a{100}){100}){100}){100}`（乘积 10⁸）、`((a×955){100}){100}`（969 字符，曾在 512 MB 堆上编译期 OOM）与 `{0}` 嵌套形态都即时 400 `too_expensive`；`\Qa{999}\E` 照常搜索；`(a{100}){100}b` 在 20 万字符的长行上、以及 10 万条 100 字符的短行上（`manyShortLinesStillStopAtTheDeadline`）都在 1 秒量级内 400；1000 字符的 `-F` / `-F -i` 字面量毫秒级命中（`DraftFileIT.theRegexEngineIsLinearAndRefusesTheConstructsItDoesNotHave`、`aLongFixedLiteralIsFoundInEveryCaseMode`）；`cat`/`rm` 单路径失败只报一次、退出 1；`cat` 对已下架版本 410 / 缺省钉无上线版本时的作者面回落（`TestCatFallsBackToTheAuthorPlaneForARevokedVersion`、`TestCatDefaultPinFallsBackToTheNewestDraft`）；`grep --help` / `edit --help` 有内容；`@latest` / `@foo` 这类消费面没有名字的后缀走作者面（`TestANonConsumerSuffixIsResolvedOnTheAuthorPlane`，落到 404 而不是 400）；`grep` 的 pattern 校验先于权限解析（空 / 超长 → 400，不泄露版本可见性），`^$` 不对以换行结尾的文件多报一个空行（`DraftFileIT`）。`--json` 在四个内容命令上被明确拒绝（`TestContentCommandsRefuseJSON`）。**新增**：`DraftFileIT.crlfAndAMissingFinalNewlineComeBackByteForByte`（CRLF 与无末尾换行逐字节）；`grepStopsAtTheCapAndSaysTheResultIsTruncated`（1001 命中 → 1000 条 + `truncated`）。**仍未做**：`cat | head` 的服务端字节量（T-READ-05 / T-PERF-02）。**`read` 回归**由 `CliRungMatrixIT.anInvokeGrantInvokesAndReadsThroughTheCliButCannotList` 用真 CLI 走通 |
| T-WRITE-01～T-WRITE-06 | **部分通过** | `DraftFileIT`：覆盖 / 新增 / 空 stdin（0 字节文件，发现并修掉 Spring「空 body 当缺失」的 500）/ 相同内容 no-op / 缺 `If-Match` 428 / 过期 409 `digest_mismatch` / 根 `SKILL.md` 更新版本快照且 skill 行不动 / 撞摘要 409 `content_already_exists`（detail 指出 `1.0.0`）/ 二进制标记与 grep 跳过 / **超限 body 在读取前被拒**（16 MiB + 1 字节 → 400 `too_large`）；`re2j` 的线性返回、`bad_pattern`（反向引用）与 `too_long`（`DraftFileIT.theRegexEngineIsLinearAndRefusesTheConstructsItDoesNotHave`）。**新增**：`aVersionBeyondTheWholeSkillCeilingsIsRefusedBeforeItIsStored`（513 文件 → `too_many_files`）、`aPathThatEscapesOrBreaksTheRootsRulesIsRefusedBeforeTheRouteSeesIt` + `SkillPathsTest`（`..` / 空段 / 反斜杠；绝对路径的 issue 由同一单测钉住）、`aVersionAtTheFileCeilingEnumeratesEveryEntryWithoutSendingContent`（512 条清单、不带字节）。`aDraftBeyondTheWholeVersionByteCeilingIsRefused`（两个 9 MiB 写入，第二个越过整版 16 MiB → `too_large`）；单文件 16 MiB 边界由 `aBodyBeyondTheSkillCeilingIsRefusedBeforeItIsBuffered` 覆盖 |
| T-EDIT-01～T-EDIT-07 | **部分通过** | 本机 CLI：唯一匹配、多处未给 `--replace-all` 拒绝、`--replace-all`、0 处拒绝、二进制拒绝（代码路径）；`write` 复用同一条读-改-写，缺文件时会用清单 ETag 创建（e2e 验证）。**未做**：两个会话同一版并发 `edit` 的 CLI 实测（服务端 409 已由 T-WRITE-05 覆盖） |
| T-RM-01～T-RM-04 | **部分通过** | 本机 CLI：删除、多路径中不存在的继续删其余、退出码 1；根 `SKILL.md` 409 文案；`DraftFileIT` 移动/复制与根拒绝。`last_file_cannot_be_deleted` 已按 T-RM-03 改口径为护栏（只有根 `SKILL.md` 时先命中 `skill_md_cannot_be_deleted`）。 |
| T-MVCP-01～T-MVCP-04 | **通过** | `DraftFileIT.theRootSkillMdCannotBeRemovedAndAMoveOrCopyRefusesAnExistingTarget`；本机 CLI `cp` / `mv` 实测（目标已存在拒绝、缺 `If-Match` 由服务端 428） |
| T-AUDIT-01 | **通过** | `DraftFileIT.aSuccessfulWriteLeavesOneAuditRowAndARefusalLeavesNone`：成功写一条 `version_file_write`、字段含 `version`/`code`/`op`/`path`/`digest_before`/`digest_after`/`files`/`bytes`、不含内容；被拒绝的写零审计 |
| T-PERM-01～T-PERM-09 | **服务端通过，CLI 部分跑** | `DraftFileIT.anInvokeGrantCannotListFilesAndCanStillReadOne`（作者面 `/files`、`/count` 403；消费面单文件 200）；`InsufficientScopeIT`；`SkillSharingIT`；`SkillVersionPinIT` 的状态与可见性；CLI 对行 id 的 403 给出「调用档没有作者面入口」且不回落消费面（`TestInvokeWithARowIdSaysTheRungIsMissingAndDoesNotFallBack`）。**新增**：`CliRungMatrixIT` 用真二进制连真服务端，覆盖 invoke 档的 `skill invoke` / `read` / `cat` 与 files 403、browse 档的 files 200，以及 `writeAndRemoveNeedTheWriteRung`（browse 拒绝 `write` / `rm`；write 档能写、读回、删自己的草稿）。**仍未做**：maintain / admin 两档的 CLI 写命令矩阵与 `mv` / `cp` |
| T-CONSUMER-01 | **部分通过** | 发布后 `cat @<行 id>` 与 `cat @1.0.0` 读回同一份字节；写被 409 拒绝且线上版本未变。**未做**：写前后线上 digest / 清单的逐字段比对 |
| T-CONC-01～T-CONC-06 | **部分通过** | 撞码重试（上述）；GC grace：`SkillSubmitIT.aBlobIsReclaimedOnceNoVersionReferencesIt` 把孤儿 blob 的 `created_at` 提前后才被回收，新 blob 保留；T-CONC-04 由此覆盖。**新增**：`DraftFileIT.twoWritesWithTheSameBaselineLeaveOneWinnerAndOneConflict`（同一 baseline 的并发写 → 200 + 409 `digest_mismatch`）。**未做**：写与 sweep 并发、`rm` 后 10 分钟复用旧 blob 的 FK 竞态 |
| T-SEC-01～T-SEC-03 | **部分通过** | invoke 档拿不到清单 / 计数 / 检索（同上）；审计不含内容（T-AUDIT-01）。**新增**：`DraftFileIT.aVersionAtTheFileCeilingEnumeratesEveryEntryWithoutSendingContent`（512 条清单、不带字节）。**仍未做**：16 MiB 版本的出口枚举 |
| T-PERF-01～T-PERF-04 | **未执行** | 本机未做 P95、传输量、`ls`/`grep`/`wc` 延迟与并发墙钟测量 |
| T-WEB-01～T-WEB-06 | **部分通过** | 网页 399 个用例全过：`version.ts`（无 `DRAFT_PIN`、裸 code/name/digest、未知 `version` 保留）、`VersionFacts`（版本码第 3 位、调用档四项）、`VersionRow`（说明 → 版本码 + 复制 → 作者·时间）；`useVersionActions` 不再拼 `draft:`。**T-WEB-05 视觉验收未做**——Claude 看不到渲染结果，需人工对照设计稿 |

**未执行的部分不写成通过。** 上表里「未做」的项按根 `CLAUDE.md` 的口径留给下一次改动或用户指令触发的全量回归；视觉验收与五档权限矩阵是本版交付前最需要人看的两个缺口。

## 已知问题

**本版没有已知缺陷。** 以下是已接受的设计限制 / 代价（不是缺陷），状态按实测填模板四值之一。

| 编号 | 限制 / 代价 | 状态 | 说明 |
|---|---|---|---|
| K1 | 长期不上线的草稿会积累孤儿 blob | 已复现 | 已定：写路径不回收、不加锁；**只有 submit / 软删除**（不是上线 / 丢弃）会 sweep，10 分钟 grace。`SkillSubmitIT` 证明 grace 内的新 blob 不被回收；`rm` 后到下一次 submit 之间的孤儿没有自动回收点 |
| K2 | `wc -w` 的非 ASCII 空白与 GNU `wc` 不同 | 未验证 | 已定：文本用 `Character.isWhitespace`；夹具用 ASCII；差异写进帮助 |
| K3 | 正则语义不承诺与本地 `grep` 逐字相同 | 已复现 | 已定：RE2 线性时间（ADR 0066）；不支持反向引用与 lookaround，给 400 `invalid_request`（detail `bad_pattern`）；执行成本有硬上界（ADR 0067）：`{n}` / `{n,m}` ≤ 100、「计数乘积（每项至少按 1 计，`{0}` 不归零）× pattern 长度」≤ 1,000,000、单请求匹配 ≤ 1 秒，否则 400 `too_expensive`；`-F -i` 按 Unicode 小写映射折叠（与 RE2 在 `ς`/`İ` 上不同）；帮助里列边界 |
| K4 | 二进制文件的 `words` 按 ASCII 空白字节计 | 未验证 | 已定：文本与二进制都计数；二进制按 ASCII 空白；帮助里说明 |
| K5 | 旧 `?version=draft:<ULID>` **不兼容** | 已复现 | 已定：设计稿只认裸 id；v6 旧书签落「没有这一版」；CLI 的 `@draft:<ULID>` 输入兼容不受影响 |
| K6 | `rm` 后 10 分钟内复用同一内容的并发窗口 | 未验证 | 已定：写可能撞 FK 失败（可见、可重试）；重跑 `write` 成功；不丢字节；T-CONC-06 覆盖 |

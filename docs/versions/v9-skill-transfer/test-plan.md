# v9-skill-transfer · 测试方案

> **最后更新**：2026-10-21
> **所属版本**：[`README.md`](README.md)

<!--
验收映射覆盖 prd 的每一条，不多不少。结果一节在实现后如实填写：
通过写通过、没执行写未执行、失败附证据。视觉验收由人做。
本版尚未实现，结果表全部是「未执行」——这是如实状态，不是占位。
-->

## 验收映射

| prd # | 验收标准 | 用例 |
|---|---|---|
| 1 | CLI 下载整包：内容路径与文件数（不含 sidecar）与 `skill ls` 一致，二进制逐字节一致，ETag 等于 digest | T1 |
| 2 | 往返不变：原样提交答「内容未变」，版本数不变，digest 相同 | T2 |
| 3 | CLI 的地址与目标：最新 / 版本号 / 版本码 / ULID / `--into` / 省略 / 非空目录 / 空目录 / 已丢弃 | T3 |
| 4 | 网页下载：四种状态的入口、文件名、内容；已丢弃与 `invoke` 没有入口；按钉打开文件栏也看不到已丢弃 | T4 |
| 5 | 网页导入到已有 skill：菜单入口、落到新草稿、幂等不跳转、读不到不给链接 | T5 |
| 6 | 包内名字必须与地址一致，两个名字都写出来 | T6 |
| 7 | 网页导入到「我的 skill」：新名字、同一个包、换内容 | T7 |
| 8 | 名字被已删除的 skill 占着：`skill_deleted` + 去恢复；CLI 同样 | T8 |
| 9 | 坏包不静默、零写入（十一种） | T9 |
| 10 | 快照一致：下载中途改草稿，包仍是发起时那一份 manifest | T10 |
| 11 | 一次请求：整包一次生成，CLI 与网页都不逐文件拉 | T11 |
| 12 | 权限沿用梯子（下载 / 导入 / 新建） | T12 |
| 13 | 失败如实：两张表显示原因；直链失败由浏览器呈现 | T13 |
| 14 | 两张对话框 400px、Esc、焦点、进行中 | T14 |
| 15 | 文案与设计稿一致 | T15 |
| 16 | 元数据不是内容：不进清单 / digest / 上限、不覆盖名称与说明、保留路径边界 | T16 |
| 17 | 元数据往返（CLI）：显式值 / 空串 / 缺省 / 未知字段 / schema / 类型 / 缺文件 | T17 |
| 18 | 网页新建 skill：一次建出 skill 与带 `SKILL.md` 的草稿；64 / 65 个 emoji | T18 |
| 19 | 建 skill 的两种拒绝与事务失败：新增 0 行 | T19 |

## 用例

### T1 · CLI 下载整包

**前置**：一个已发布 `1.2.3` 的 skill，内容含文本、二进制、中文路径与非 ASCII 的 skill 名；本机已登录。

**步骤**：`skill download dev/pdf-tools@1.2.3 --into <空目录>`；再 `skill ls dev/pdf-tools@1.2.3`。

**期望**：退出码 0；目录里的**内容路径集合与文件数**（不含 `.skillmaster/metadata.json`）与 `skill ls` 一致；二进制用 `cmp` 逐字节相同；输出里的 digest 等于响应头 `ETag`（去引号）等于 `"sha256:" + manifest.digest()`；整包只有一次 HTTP 请求。

### T2 · 往返不变

**前置**：T1 的目录，含 sidecar。

**步骤**：`skill submit <目录> --to dev/pdf-tools`（不给 `--name`）。

**期望**：退出码 0，输出「内容未变」；响应 `created=false`；版本总数与 digest 都不变；`skill_version` 行数不变。

### T3 · CLI 的地址与目标

**前置**：同一个 skill 有线上 `1.2.3`、历史 `1.2.2`、已下架 `1.2.1`、草稿 `<8 位码>`、旧行 `<26 位 ULID>`、已丢弃 `<版本码>`。前四条用 API 造；**旧的 26 位 ULID 行用测试 SQL 直接插 `skill_version` / `version_file` 并造好它引用的 `blob` / `blob_content`**（短码是今天生成的，只有插行才能得到老形状的 id）。

**步骤**：依次下载：不带钉、`@1.2.3`、`@<8 位码>`、`@<ULID>`、`--into`、省略 `--into`；再对已存在非空目录、已存在空目录、`@<已丢弃码>`、断网各一次。

**期望**：不带钉得到线上；三种钉都得到对应的字节；省略 `--into` 落到 `./<名字>`；非空目录 → 非零退出、零写入；空目录 → 成功；已丢弃 → 非零退出、具名原因；断网 → 非零退出，`<目标>.partial` 与临时 zip 都被清掉，目标位置没有半个 skill；**空目录这一路在解压中途失败时，用户原有的空目录仍在**（rename 之前不删它）。

### T4 · 网页下载的入口与内容

**前置**：线上 / 历史 / 已下架 / 草稿 / 已丢弃五种状态；五个可授档位加所有者。

**步骤**：用对应身份打开版本页；点「下载」；解压后与 T1 比对。再用 `maintain` 按版本码直接打开已丢弃版本的文件栏。

**期望**：线上/历史（`browse`+）、草稿（`write`+）、已下架（`maintain`+）有「下载」；`write` / `browse` 打开已下架是 404；已丢弃与 `invoke` 没有「下载」；已丢弃在文件栏里也不出现文件——**不回落显示线上版本的文件**，地址钉住的那一版在选择器里是**禁用项**，文件区为空；文件名分别是 `<名字>@<版本号>.zip` / `<名字>@<版本码>.zip`；**没有版本号的已发布行是 `<名字>@<版本码>.zip`**；解压内容与 T1 一致。

### T5 · 网页导入到已有 skill

**前置**：`write` 账号；一个 `name: pdf-tools` 的 zip；一个与已丢弃版本内容相同的 zip。

**步骤**：从「新建版本 ▾」点「导入压缩包」，选文件、填名称与说明，导入；读版本列表与线上指针。再用同一个包导一次；再用 `write` 导入与已丢弃版本相同的包。

**期望**：新版本列表多出一条草稿，线上指针不动，页面落到新草稿的版本页；同一个包 → 弹窗不关、不多出一行，字段下多一句「这个 skill 已经有一版同样的内容（@1.2.3）；没有新建。」并跟 `查看 @1.2.3`（**整句照设计件**，不是片段）；与已丢弃版本相同 → 写「这个 skill 已经有一版同样的内容；那一版现在读不到。」且不给链接。

### T6 · 包内名字必须与地址一致

**前置**：`dev/pdf-tools` 的地址；zip 的 `SKILL.md` 声明 `name: split`。

**步骤**：从版本列表导入。

**期望**：400 `invalid_upload`；`details` 里同时有 `name_mismatch`、`address_name=pdf-tools`、`content_name=split`——**按 `field` 遍历 `details` 断言**（既有的 `field()` / `issue()` 只读 `details.get(0)`，三条读不全）；对话框写出「地址是 pdf-tools，SKILL.md 里写的是 split。把 SKILL.md 的 name 改成 pdf-tools。」；零写入。

### T7 · 网页导入到「我的 skill」

**前置**：任何登录账号；一个不存在的名字 `new-skill` 的 zip。

**步骤**：从「新建 skill ▾」点「导入本地 skill」，选包、填版本名称、说明可留空，导入；再导同一个包；换一份内容再导；打开列表。

**期望**：第一次出现新 skill（未上线）与一条草稿；第二次内容已存在、仍只有一个 skill 与一个版本；第三次这个 skill 多一个版本；始终不出现第二个同名 skill；空态里也能看到「新建 skill」。

### T8 · 名字被已删除的 skill 占着

**前置**：一个已删除的同名 skill——用网页的 `POST /web/skills/{ns}/{name}/delete` 造（会话 + CSRF），不用 SQL。

**步骤**：个人命名空间导入一次；CLI 不带 `--to` 提交一次（包里带 sidecar 的 `version.name`，不给 `--name`）；CLI 带 `--to` 提交一次（同上）。

**期望**：前两者 409 `skill_deleted`，网页有「去恢复」；带 `--to` 的是 404 `skill_not_found`（看不见已删除的 skill）；三次之后 `skill` / `skill_version` / `version_file` 的行数与之前相同（新增 0 行）。

### T9 · 坏包不静默、零写入

**前置**：一个目标 skill 或一个新名字。

**步骤**：依次构造十一种包：非 zip、空包、缺根 `SKILL.md`、顶层目录名与 `name` 不符、513 个内容文件、解压后 16 MiB + 1 字节、重复路径、符号链接、路径不规范（`../`、绝对、空段、反斜杠）、内容里出现保留路径且**不是元数据形状**（纯文本，或一个没有 `schema_version` 键的 JSON 对象）、请求体超过 32 MB。**每一种跑两轮**：一次对已有 skill（版本列表入口），一次对一个新名字（「我的 skill」入口，它可能先落 `skill` 行）。

**期望**：每一种都有具名 issue；网页在对话框里显示对应句子（**设计稿还没给句子的那几类（Q7 列的那一串，含反斜杠 `backslash_in_path`），本用例只验「有具名 issue、不显示通用句」**，句子本身等 Q7 补进设计稿后再验），CLI 打印；每一轮拒绝后按指标 #3 比对 `skill` / `skill_version` / `version_file` 三张表的行数差，新增 0 行（新名字那一轮 `skill` 表尤其要查）。

### T10 · 快照一致

**前置**：一份草稿；两个会话。

**步骤**：会话 A 发起下载；在响应生成期间，会话 B 修改这份草稿（写一个文件）；A 解压后比对 manifest 与 digest。**怎么卡住这个中间点**：用例里把 `BlobStore.open` 换成 `CountDownLatch` 包住的实现——读到 `manifest` 之后、开第一个 blob 之前停住，此时让 B 提交修改并等它返回，再放行 A。

**期望**：A 得到的包只包含发起时那一份 manifest 的文件；`ETag` 等于包内内容算出的 digest；不出现两个时刻的文件。**本用例的并发编辑是新增文件**；「并发删除 + 下载草稿」会让那条 blob 已被 GC 回收、以 500 结束（发字节之前），那是 TD §架构 写明的既有边界，不在本用例里判失败。

### T11 · 一次请求

**步骤**：CLI 下载时用 httptest 计数；网页点「下载」时看服务端访问日志。

**期望**：整包恰好一次请求；CLI 与网页都没有逐文件请求。

### T12 · 权限沿用梯子

**前置**：五个可授档位加所有者。

**步骤**：每人试一次下载、一次版本列表导入、一次新建 skill。

**期望**：下载门槛与 T4 一致；版本列表导入要 `write`；新建 skill 只在个人命名空间；`invoke` 既没有入口，也拿不到包。

### T13 · 失败如实

**步骤**：导入时断网、上传超时、服务端拒绝各一次；直链下载时断网一次。

**期望**：两张对话框都显示原因、不显示成功；CLI 非零退出；直链下载的失败就是浏览器拿到的那份响应（不要求页面中转）。

### T14 · 两张对话框

**步骤**：400px 视口打开导入与新建 skill（**新建 skill 没有窄屏截图，按共用的 `.confirm` 壳 CSS 验：`padding:24px`、`max-width:420px`、`width:100%`**）；用键盘走 Esc、Tab、方向键；提交中观察按钮。

**期望**：不横向溢出；Esc 关闭并把焦点还给打开它的控件；提交中按钮显示进行中、取消与提交都不可点。

### T15 · 文案与设计稿一致

**步骤**（逐条勾，不靠印象）：

1. **四处入口**：版本列表工具栏 `新建版本 ▾` → `在线编辑` / `导入压缩包`；我的 skill 页头 `新建 skill ▾` → `在线编辑` / `导入本地 skill`；两处在对应档位下可见、不可见时不画成禁用；**空态里「新建 skill ▾」也在**。
2. **空态句**：「还没有 skill。新建一个，或运行 `skillmaster skill submit`。」
3. **导入表**（两个标题各看一次）：标题「导入 skill」/「导入新版本」；引子两句照设计件；字段标签是 **`压缩包`**（不是「文件」）、`版本名称`、`说明（可选）`；**`压缩包` 下面那行说明逐字一致**：「只接受 .zip。SKILL.md 要在压缩包根部；所有文件都在同一个顶层文件夹里时，文件夹名要与 SKILL.md 的 name 相同。最多 512 个文件、解压后合计 16 MiB。」；没选文件写「未选择文件」；按钮 `选择文件` / `导入` / `取消`；没选文件或名称为空时 `导入` 是 `disabled`；提交中写「正在导入…」且两个按钮都不可用；Esc 与取消同答案、焦点回打开它的那颗。
4. **新建 skill 表**：标题「新建 skill」；字段 `名字` / `描述` / `版本名称`（默认「第一版」）；按钮 `创建` / `取消`；三项有空时 `创建` 是 `disabled`；提交中写「正在创建…」。
5. **已知句**（设计稿给了的）：`name_mismatch`、`skill_deleted`（+ `去恢复`）、`skill_already_exists`（「这个名字已经有了。」+ `查看 <名字>`）、元数据四句、32 MB 一句、网络一句；新建 skill 的五条字段错误句（是 `details[].issue`，与导入同一张表：`name_too_long` / `name_not_usable_as_a_directory` / `name_contains_version_separator` / `name_contains_unaddressable_char` / `required_field_missing`）。
6. **还没句子的三处**（Q6 / Q7 / Q8，设计稿补上之前**不验句子**，只确认实现没有自拟文案）：`editor-reserved-path`；那几类还没句子的导入拒绝；文件栏钉住已丢弃时的文件区。

**期望**：以上逐条一致；没有第二个名字，也没有 [`design/voice-spec.md`](../../../design/voice-spec.md) 的禁用叫法（行 id、短码、draft id…）。视觉验收由人做。

### T16 · 元数据不是内容

**前置**：一个内容里已经含 `.skillmaster/metadata.json` 的历史版本——用测试 SQL 直接插 `skill_version` / `version_file`，并先插它引用的 `blob` / `blob_content` 行（只有这一处绕过 validator，因为新管线已经不允许产生它）。

**步骤**：下载普通版本；读 `skill ls`；同一份内容带 sidecar / 不带 sidecar 各提交一次；512 内容 + sidecar、16 MiB 内容 + sidecar、513 内容（不带 sidecar）、513 内容 + sidecar（514 个条目，reader 先拒）、16 MiB + 1 字节各一次；下载历史版本，再把它导入；**从历史版本新建一版草稿**（`POST /web/skills/{ns}/{name}/versions`，体里带 `from=<它的码>`、`from_digest`、一个 `edit`、`name`——会话 + CSRF）；在编辑器与 CLI 里把 `write` / `mv` / `cp` 的目标设成保留路径各一次；删除与从它改名各一次。

**期望**：普通包里 **sidecar 是一个 zip 条目、路径为 `.skillmaster/metadata.json`**，但不进文件列表、`grep` / `cat` 读不到；两次提交 digest 相同、版本数不变、既有名称与说明不被覆盖；两个上限带 sidecar 仍接受，超一点拒绝；历史版本下载成功、**不覆盖也不追加** sidecar，那个文件原样在包里；再导入 → 具名拒绝（保留路径）；**从它新建草稿 → 成功，那个文件仍在草稿里**（历史数据不静默丢、旧 skill 不被锁死）；写 / 移 / 复制到保留路径 → 400 `reserved_path`，**删除与改名的来源不拦**（既有数据要能清理）。

### T17 · 元数据往返（CLI）

**步骤**：下载 → 改 sidecar 里的名称并改一个内容文件 → 不带 `--name` 提交；再逐次构造：带 `--name` 覆盖、`--name ''`、未知字段、`schema_version: 99`、类型不对、删掉整个文件；最后用**与既有版本相同的内容 + 坏 sidecar**提交一次。

**期望**：不带 `--name` 时新版本用文件里的名称与说明；显式值覆盖；空串等于没给；未知字段 / schema / 类型 → 具名拒绝、新增 0 行；缺文件 → 400 `draft_name_required`（若无 `--name`）或正常提交（若有）；**内容已存在 + 坏 sidecar → 仍具名拒绝，不走幂等**（校验先于幂等）。

### T18 · 网页新建 skill

**步骤**：从「新建 skill ▾」点「在线编辑」，填名字、描述、版本名称（默认「第一版」）→ 创建；读列表；读版本文件列表；在编辑页写一个文件并提交；再各造一个 64 / 65 个 emoji 的名字。

**期望**：一次 POST 建出 skill 与草稿，页面落在 `/skills/{ns}/{name}@<draft_id>/edit`（`draft_id` 就是这条草稿的短码）；草稿里有一份根 `SKILL.md`，frontmatter 的 `name` / `description` 等于表单；列表里是「草稿」且未上线；**在编辑页写一个文件再提交，草稿多出这个文件（证明新建出来的草稿是真能接着编辑的，不是只读的一行）**；64 个 emoji 接受，65 个拒绝。

### T19 · 建 skill 的两种拒绝与事务失败

**步骤**：名字已被自己的 live skill 占用一次；名字属于已删除的 skill 一次；注入一次草稿写入失败；再让短码生成器先返回一个已存在的码、第二次成功（模拟碰撞）；让一次「新建 skill」与一次同命名空间的 CLI 提交**并发**跑（两条线程同时进入）；最后让**两个同名的新建 skill** 并发跑。

**怎么注入**：草稿写入失败用 `@SpyBean` 换掉 `SkillVersionService`，让 `submit(...)` 抛一次异常（Boot 3 的 Mockito 是 inline mock maker，`final` 类也能替身）——发生在 `skill` 行已经插入之后，才证明两行一起回滚；不靠改积压数据制造时序。短码碰撞用同一个替身注入一个已存在的码、第二次返回真码。

**期望**：前两者 409（`skill_already_exists` / `skill_deleted`），零写入，并给对应的链接；事务失败后 `skill` / `skill_version` / `version_file` 都新增 0 行；碰撞重试后仍然只有**一整条** `skill` + 一条草稿 + 一行 `version_file`（重试没有留下半条）；并发那一对**两边都在超时内返回、没有死锁**（`skill` 行与 GC 锁的取用顺序相同）；两个同名并发创建**恰好一个 201、一个 409 `skill_already_exists`**，且 `skill` 只多一行。

## 环境与数据

- **服务端**：真实 PostgreSQL 测试库（`skillmaster-server/scripts/init-test-db.sh`，在 `skillmaster-server/` 下运行），`./mvnw -o verify -Dit.test=SkillTransferIT,ArchiveIT,CreateSkillIT`；每个 IT 继承 `AbstractAccountIT`（真 HTTP、真 CSRF、`token()`）并标 `@Sql("/sql/truncate-business-tables.sql")`。multipart 用 `support/Multipart`，二进制响应用 `AbstractIT.getBytes`；失败细节沿用 `field()` / `issue()`，但**它们只读 `details.get(0)`**——T6 这种要多条 detail 的断言按 `field` 遍历，或在 support 里加一个 `detail(response, field)`。
- **数据**：用 API 现造，不落固定夹具——注册账号 → `submit` 一个包 → 需要线上/历史/已下架/已丢弃时再走网页的 publish / revoke / discard；需要一个含保留路径的「历史版本」时，用测试 SQL 直接插 `skill_version` / `version_file`（只有这一处绕过 validator，因为新管线已经不允许产生它）。512 文件与 16 MiB 的包用 `ZipOutputStream` 在测试里现造；32 MB 的请求体沿用 `SkillSubmitIT` 的 33MB 造法。
- **CLI**：`go test ./...`；`httptest` + `pointAtServer`，zip 在测试里现造；断网用关闭 server 模拟。
- **网页**：`npm test`（vitest + jsdom）；`stubFetch` 需要支持 `FormData` 的记录分支。
- **设计**：v9 的截图要按名字选：`--only versions-import,versions-new-menu,skills-import,skills-new-menu,skills-new-skill,versions-import-narrow`；编辑器补的那一态另跑 `--only editor`。**Q7 / Q8 补进设计稿之后**，导入的 error 变体与文件栏那一态要一并重出图再跑同一条命令。`--only list,detail,layout` 只是断言分组，选不到截图。视觉验收由人做。
- **清理**：IT 的 `@Sql` 清业务表；CLI 用 `t.TempDir()`；网页测试不落盘。

## 结果

**实现前写定；截至 2026-10-21 全部未执行。**

| 用例 | 结果 | 证据 |
|---|---|---|
| T1–T19 | 未执行 | 版本尚未实现 |

**指标的口径与量法**：

| 指标（prd） | 怎么量 |
|---|---|
| 一次整包下载的服务端请求数 | CLI 侧 httptest 计数；网页侧一次 GET | 
| 下载包与清单的一致率 | 解压后的内容路径集合（不含 sidecar）与 `manifest` 比对 |
| 坏包拒绝后的写入 | 拒绝前后 `skill` / `skill_version` / `version_file` 的行数差 |
| 往返 digest 相等率 | 提交响应的 digest 与源版本的 digest |
| 带/不带元数据的 digest 相等率 | 同一份内容两次提交的 digest |
| 建 skill 的服务端请求数 | 网页测试里 `createSkill` 的 fetch 调用数 |

**失败或跳过的用例要单列说明**，不要埋在表格里。

## 已知问题

**本版范围内暂无**——实现尚未开始。实现中发现的缺陷按四态（已复现 / 部分复现 / 未验证 / 已修复）追加到本节。

**范围外的历史缺陷**（不修，留给 `known-issues.md` 或后续版本）：

- `skillmaster-web/src/composables/useVersionActions.ts:46`、`components/VersionPicker.vue:16`、`pages/SkillPage.vue:376` 的注释仍写「已丢弃没有文件快照」，与 `DiscardSkillVersionUseCase` 的「行与字节都留着」相反。v9 的下载实现会读到它们，建议随实现一并改掉。
- `SubmitResponse` / `SubmittedSkill` / `SubmitOutcome` 的 javadoc 只列 `draft / published / discarded` 三种状态，漏了 `revoked`；CLI 侧已经写对四种（`internal/api/submit.go:57-65`）。

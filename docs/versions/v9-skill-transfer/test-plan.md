# v9-skill-transfer · 测试方案

> **最后更新**：2026-10-21
> **所属版本**：[`README.md`](README.md)

<!--
验收映射覆盖 prd 的每一条，不多不少。结果一节在实现后如实填写：
通过写通过、没执行写未执行、失败附证据。视觉验收由人做。
结果一节在实现后按实际跑过的东西填写；没跑的不写成通过。
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
| 20 | 文件栏表头右端的两颗动作：位置与顺序、下载的同条件与直链、新建版本的同一菜单与门槛、窄屏折行 | T20 |

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

1. **三颗触发按钮、六个动作项**：版本列表工具栏 `新建版本 ▾` → `在线编辑` / `导入压缩包`；文件栏表头 `新建版本`（只有文字、居中）→ 同一张菜单；我的 skill 页头 `新建 skill ▾` → `在线编辑` / `导入本地 skill`；三处在对应档位下可见、不可见时不画成禁用；**空态里「新建 skill ▾」也在**。
2. **空态句**：「还没有 skill。新建一个，或运行 `skillmaster skill submit`。」
3. **导入表**（两个标题各看一次）：标题「导入 skill」/「导入新版本」；引子两句照设计件；字段标签是 **`压缩包`**（不是「文件」）、`版本名称`、`说明（可选）`；**`压缩包` 下面那行说明逐字一致**：「只接受 .zip。SKILL.md 要在压缩包根部；所有文件都在同一个顶层文件夹里时，文件夹名要与 SKILL.md 的 name 相同。最多 512 个文件、解压后合计 16 MiB。」；没选文件写「未选择文件」；按钮 `选择文件` / `导入` / `取消`；没选文件或名称为空时 `导入` 是 `disabled`；提交中写「正在导入…」且两个按钮都不可用；Esc 与取消同答案、焦点回打开它的那颗。
4. **新建 skill 表**：标题「新建 skill」；字段 `名字` / `描述` / `版本名称`（默认「第一版」）；按钮 `创建` / `取消`；三项有空时 `创建` 是 `disabled`；提交中写「正在创建…」。
5. **已知句**（设计稿给了的）：`name_mismatch`、`skill_deleted`（+ `去恢复`）、`skill_already_exists`（「这个名字已经有了。」+ `查看 <名字>`）、元数据四句、路径不规范一句（`absolute_path` / `path_traversal` / `empty_path_segment` 同一个句子）、32 MB 一句、网络一句（**按表分**：导入那句带「文件没有导入」，新建表用通用网络句）；新建 skill 的五条字段错误句（是 `details[].issue`，与导入同一张表：`name_too_long` / `name_not_usable_as_a_directory` / `name_contains_version_separator` / `name_contains_unaddressable_char` / `required_field_missing`）。
6. **还没句子的三处**（Q6 / Q7 / Q8，设计稿补上之前**不验句子**，只确认实现没有自拟文案）：`editor-reserved-path`；那几类还没句子的导入拒绝；文件栏钉住已丢弃时的文件区。

**期望**：以上逐条一致；没有第二个名字，也没有 [`design/voice-spec.md`](../../../design/voice-spec.md) 的禁用叫法（行 id、短码、draft id…）。视觉验收由人做。

### T16 · 元数据不是内容

**前置**：**两个**内容里已经含 `.skillmaster/metadata.json` 的历史版本——一个该路径上是普通文本，一个本身是 sidecar 形状（顶层带 `schema_version` 的 JSON 对象）。用测试 SQL 直接插 `skill_version` / `version_file`，并先插它引用的 `blob` / `blob_content` 行（只有这一处绕过 validator，因为新管线已经不允许产生它）。

**步骤**：下载普通版本；读 `skill ls`；同一份内容带 sidecar / 不带 sidecar 各提交一次；512 内容 + sidecar、16 MiB 内容 + sidecar、513 内容（不带 sidecar）、513 内容 + sidecar（514 个条目，reader 先拒）、16 MiB + 1 字节各一次；**两个历史版本各**下载一次、再把它导入一次；**从历史版本新建一版草稿**（`POST /web/skills/{ns}/{name}/versions`，体里带 `from=<它的码>`、`from_digest`、一个 `edit`、`name`——会话 + CSRF）；在编辑器与 CLI 里把 `write` / `mv` / `cp` 的目标设成保留路径各一次；删除与从它改名各一次。

**期望**：普通包里 **sidecar 是一个 zip 条目、路径为 `.skillmaster/metadata.json`**，但不进文件列表、`grep` / `cat` 读不到；两次提交 digest 相同、版本数不变、既有名称与说明不被覆盖；两个上限带 sidecar 仍接受，超一点拒绝；历史版本下载成功、**不覆盖也不追加** sidecar，那个文件原样在包里；普通文本的历史文件再导入 → 具名拒绝（保留路径）；**sidecar 形状的历史文件再导入 → 成功，那个文件不进新版本**（PRD #9 的例外）；**从它新建草稿 → 成功，那个文件仍在草稿里**（历史数据不静默丢、旧 skill 不被锁死）；写 / 移 / 复制到保留路径 → 400 `reserved_path`，**删除与改名的来源不拦**（既有数据要能清理）。

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

### T20 · 文件栏表头的两颗动作（2026-10-21 补）

**前置**：一个有线上版本、草稿、历史版本与**一条已丢弃版本**的 skill；能打开文件栏的五个档位（`browse` / `write` / `maintain` / `admin` / 所有者）各一份。

**步骤**：以五个档位各打开文件栏，并确认 `invoke` 档没有「文件」栏；`write` 档点开「新建版本」看菜单项，`browse` 档确认没有这一颗；从文件栏的「新建版本 → 导入压缩包」打开导入表再 Esc；把地址钉到那条已丢弃版本；在 400px 视口打开文件栏；点「下载」看请求地址与浏览器是否直接落盘。

**期望**：左端仍是版本选择器与版本数；右端是一组三样——地址、「下载」与「新建版本」（后者只有文字、居中，不画箭头）；**下载**在屏幕上有可读版本时出现、是 `<a>` 直链到 `/web/skills/{ns}/{name}@<pin>/archive`（取的是屏幕上的那一版），钉住已丢弃版本或一版都读不到时不画；**新建版本**只在 `write` 及以上出现，菜单项与版本列表那颗完全一致（在线编辑 / 导入压缩包），关闭导入表后焦点回到它；`invoke` 档没有文件栏；400px 下这一行折行、两颗动作靠右，不横向溢出、不隐藏任何东西。

## 环境与数据

- **服务端**：真实 PostgreSQL 测试库（`skillmaster-server/scripts/init-test-db.sh`，在 `skillmaster-server/` 下运行），`./mvnw -o verify -Dit.test=SkillTransferIT,ArchiveIT,CreateSkillIT`；每个 IT 继承 `AbstractAccountIT`（真 HTTP、真 CSRF、`token()`）并标 `@Sql("/sql/truncate-business-tables.sql")`。multipart 用 `support/Multipart`，二进制响应用 `AbstractIT.getBytes`；失败细节沿用 `field()` / `issue()`，但**它们只读 `details.get(0)`**——T6 这种要多条 detail 的断言按 `field` 遍历，或在 support 里加一个 `detail(response, field)`。
- **数据**：用 API 现造，不落固定夹具——注册账号 → `submit` 一个包 → 需要线上/历史/已下架/已丢弃时再走网页的 publish / revoke / discard；需要一个含保留路径的「历史版本」时，用测试 SQL 直接插 `skill_version` / `version_file`（只有这一处绕过 validator，因为新管线已经不允许产生它）。512 文件与 16 MiB 的包用 `ZipOutputStream` 在测试里现造；32 MB 的请求体沿用 `SkillSubmitIT` 的 33MB 造法。
- **CLI**：`go test ./...`；`httptest` + `pointAtServer`，zip 在测试里现造；断网用关闭 server 模拟。
- **网页**：`npm test`（vitest + jsdom）；`stubFetch` 需要支持 `FormData` 的记录分支。
- **设计**：v9 的截图要按名字选：`--only versions-import,versions-new-menu,skills-import,skills-new-menu,skills-new-skill,versions-import-narrow`；编辑器补的那一态另跑 `--only editor`。**Q7 / Q8 补进设计稿之后**，导入的 error 变体与文件栏那一态要一并重出图再跑同一条命令。`--only list,detail,layout` 只是断言分组，选不到截图。视觉验收由人做。
- **清理**：IT 的 `@Sql` 清业务表；CLI 用 `t.TempDir()`；网页测试不落盘。

## 结果

**2026-10-21 实现完成；自动化与本地接口实测已执行，浏览器里的点击与视觉未执行。** 下表只说「跑过
什么」，不把自动化测试当作验收本身：**未执行**的是 T10（下载中途改草稿的快照一致性）、T13（断网 /
超时的页面表现，需要人看）、T14 / T15 的视觉与窄屏部分；**部分**的每行都写清了缺哪一半。

**这一次跑过的命令与结果**（都是 2026-10-21）：

| 层 | 命令 | 结果 |
|---|---|---|
| 服务端单测 | `./mvnw -o test`（真 `.env`） | 238 通过（229 + 本次新增 `SkillSidecarTest` 8 与 validator 的 sidecar 余量用例 1） |
| 服务端 IT | `./mvnw -o verify -Dit.test=SkillTransferIT,ArchiveIT,CreateSkillIT` | 18 通过（`ArchiveIT` 4 / `SkillTransferIT` 9 / `CreateSkillIT` 5） |
| CLI | `go test ./...` | 通过（含 `cmd/skillmaster/download_test.go` 8 个、`internal/api` 新增的「无 `--name` 就不发 `name` 段」与「409 带码到命令层」） |
| 网页 | `npm test` / `npm run type-check` / `npm run build` | 468 通过（24 个文件；新增 `tests/skill-transfer.test.ts` 35 个）/ 类型检查通过 / 构建通过 |

**本地实测（2026-10-21，dev 库 + `localhost:8080`，Web 走 SPA 代理 `localhost:5173` 的同一组 `/web` 路由）**：
用两个新注册的账号（`v9test`、`v9other`）与一把 `skills:read skills:write` 的 API key 把两层都走了一遍。
**验的是接口与字节，不是「点起来对不对」**——浏览器里的点击与视觉仍由人做。

| 验的 | 结果 |
|---|---|
| CLI `submit` 一个四文件目录（中文路径 + 二进制） | 201，草稿 `TMZ2Q21M`，4 个文件 190 字节 |
| CLI `download @<版本码>` / `--into` 空目录 / 省略钉取线上 | 都成功；`ETag` = `sha256:d751…`（与提交 digest 相同）；与源目录 `diff -r` 一致、二进制 `cmp` 一致；不含 sidecar 的文件数 4（包内 5 个条目） |
| CLI 往返：下载目录**不带 `--name`** 原样 `submit --to` | 「内容未变」，digest 不变，名称与说明来自 sidecar |
| CLI `--name ''` | 等于没给（仍走 sidecar）；显式 `--name` 在新内容上胜出（新草稿 `PRYCC1BT`，名称 `覆盖后的名字`） |
| CLI 坏 sidecar + **同一份内容** | 400 `metadata_schema_unsupported`，**先于幂等**（不是「内容未变」） |
| CLI 目标规则 | 非空目录拒绝且不写入；未知钉 404；已丢弃版本 400 → 打印中文句「这一版目前的状态不能做这个操作。」（本轮补上，TD §接口 4 要求） |
| Web 下载（线上 `@1.0.0` / 草稿 `@版本码` / 不带钉） | 200；`Content-Disposition` 分别是 `pdf-tools@1.0.0.zip`、`web-made@XN38HQYS.zip`（`filename*` 带 UTF-8 百分号编码）；解压后与源一致；sidecar 名/说明正确 |
| Web 导入到已有 skill | 新草稿 201；同一包重放 200（同一条草稿）；名字与地址不符 400，`details` 三条（`name_mismatch` / `address_name` / `content_name`） |
| Web 导入到命名空间 | 新 skill 201（`skill_created`）；同一包重放 200，不建第二个 skill |
| Web 新建 skill | 201；草稿里有一份根 `SKILL.md`，`name`/`description` 等于表单；同名再来 409 `skill_already_exists` |
| Web 导入的坏包 | 非 zip → `not_a_zip`；空名字 → `draft_name_required`；顶层目录名不符 → `name_does_not_match_directory`；保留路径上是普通文本 → `reserved_path`；合法 sidecar 被剥离（文件数 1）且**说明取自包、名称取请求**；`schema_version: 99` → `metadata_schema_unsupported` |
| 编辑器写保留路径 | `write` 与 `rename` 到 `.skillmaster/metadata.json` 都是 400 `reserved_path`；同一草稿写普通文件成功 |
| 越权 | 另一个账号下载 / 导入别人的 skill 都是 404 `skill_not_found` |
| 线上指针 | 两次导入 + 一次 CLI 提交之后 `pdf-tools` 的 current 仍是 `1.0.0`；新建 / 导入出来的 skill `current = null` |
| 已丢弃 | 网页 `@<版本码>/archive` 400 `invalid_version_state` |

**本机工具的一个坑（不是产品问题）**：macOS 的 `/usr/bin/unzip` 会把 UTF-8 条目名显示成乱码（用 Python 的
`zipfile` 与 `/usr/bin/bsdtar` 解同一个包，名字都正确）。手工验收解包时用 `bsdtar -xf` 或 `ditto -x -k`。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 · CLI 下载整包 | 通过 | `download_test.go` 八个用例（含失败不留 `.partial`、保留路径上的普通文件算内容、反斜杠条目按不安全拒绝）；**sidecar 形状的历史内容文件这一角报 N−1**，TD §接口已写明；本地实测：下载四文件目录，与 `skill ls` 的路径/文件数一致、二进制 `cmp` 一致、`ETag` 等于提交的 digest |
| T2 · 往返不变 | 通过 | `SkillTransferIT.anExportedVersionComesBackAsTheSameDigest`（下载整包 → 原样导入 → `created=false`、digest 相同、名称不被覆盖）；本地实测：CLI 下载目录不带 `--name` 原样提交 → 「内容未变」、版本数不变；改了内容再提交 → 新草稿 |
| T3 · CLI 的地址与目标 | 部分 | `download_test.go` 的拒绝用例（当前目录、非空目录、失败零写入）；地址解析在 `reading_test.go`；本地实测 `@版本码` / 省略钉 / 已丢弃。**省略 `--into`（落到 `./<名字>`）未实测** |
| T4 · 网页下载的入口与内容 | 部分 | `skill-transfer.test.ts`：下载按钮在「查看文件 → 下载 → 编辑文件」之间、地址是 `/web/skills/{ns}/{name}@{pin}/archive`、与「查看文件」同条件（已丢弃没有）、文件栏钉住已丢弃三条（无文件、不回落、选择器禁用项）+ 可读版本仍画文件。五种身份实测与解压比对未执行 |
| T5 · 网页导入到已有 skill | 部分 | `SkillTransferIT` + `skill-transfer.test.ts`；本地实测：新草稿 201、同包重放 200（同一条草稿）、线上指针不动。**浏览器里的点击未做** |
| T6 · 包内名字必须与地址一致 | 通过 | `SkillTransferIT.aNameMismatchNamesBothNames` 按 `field` 取其 `issue`（`address_name=pdf-tools`、`content_name=split`）；`skill-transfer.test.ts` 断言整句 |
| T7 · 网页导入到「我的 skill」 | 部分 | `SkillTransferIT.aNewNameCreatesTheSkillOnTheNamespaceRouteAndReplaysWithoutASecond`（新名字 201 `skill_created`、同包重放 200、列表只有一个）；**「换一份内容再导」与浏览器里的点击未执行** |
| T8 · 名字被已删除的 skill 占着 | 通过 | `SkillTransferIT.aDeletedNameIsANamedConflict`、`CreateSkillIT.aDeletedNameIsANamedConflict`（409 `skill_deleted`，后者另比 `skill` / `skill_version` / `version_file` 三表行数）；`cmd/skillmaster` 的 `TestADeletedNameIsTranslatedOnSubmit`（409 → 中文句）与 `internal/api` 的 `TestAConflictCarriesItsCodeToTheCommandLayer`（409 信封把码带到命令层）；`skill-transfer.test.ts` 断言「去恢复」 |
| T9 · 坏包不静默、零写入 | 部分 | `SkillSidecarTest` + `SkillTransferIT`；本地实测：非 zip、空名字、顶层目录名不符、保留路径上是普通文本、坏 schema 各一次，都带具名 issue。**十一种坏包没有逐一走完，拒绝前后的行数差也没有逐项量** |
| T10 · 快照一致 | 未执行 | 需要真服务器 + 解压比对，自动化未覆盖 |
| T11 · 一次请求 | 通过 | CLI：`download_test.go` 的请求计数；网页：一条 `<a>` 直链，组件测试断言 href，页面不 fetch |
| T12 · 权限沿用梯子 | 部分 | IT 覆盖 404 与既有权限路径；本地实测：另一个账号下载与导入别人的 skill 都是 404。**五个可授档位加所有者逐档未跑** |
| T13 · 失败如实 | 未执行 | 人工观察项 |
| T14 · 两张对话框 | 部分 | `skill-transfer.test.ts` 覆盖：Esc 关闭（等待中按 Esc 不关）、取消、提交中两个按钮都不可用且写「正在导入…」、未选文件或名称为空时 `导入` / `创建` disabled、关闭后焦点回到「新建 skill ▾」。**400px 视口与真键盘的 Tab 顺序未人工执行** |
| T15 · 文案与设计稿一致 | 部分 | 组件测试逐条断言已知句（幂等整句、`name_mismatch`、`skill_deleted`、`skill_already_exists`、缺根 `SKILL.md`、路径不规范三种 issue、32 MB、网络按表分、导入侧不套「名字和描述都要填」、说明在 200 码点处停住）；Q6 / Q7 / Q8 三处按计划不验（设计稿尚未补），Q7 的那几类在补句子之前**回落服务端原句**（有用例钉住）；人看未做 |
| T16 · 元数据不是内容 | 部分 | `SkillTransferIT` + `SkillUploadValidatorTest`；本地实测：包里 5 个条目而文件数报 4、带/不带 sidecar 的两次提交 digest 相同、合法 sidecar 不进清单、保留路径写入 400、`schema_version: 99` 具名拒绝。**含保留路径的历史版本（下载不写 sidecar；普通文本的被拒、sidecar 形状的导入成功且文件不进新版本；复制成草稿保留）与 512 / 16 MiB 边界未在真库上跑** |
| T17 · 元数据往返（CLI） | 通过 | `SkillSidecarTest` 覆盖三种具名拒绝与渲染省略空字段；本地实测：下载（写 sidecar）→ 不带 `--name` 提交 → 名称/说明来自包；`--name ''` 等于没给；显式 `--name` 胜出；**坏 sidecar + 相同内容 → 具名拒绝、先于幂等** |
| T18 · 网页新建 skill | 部分 | `CreateSkillIT` + `skill-transfer.test.ts`；本地实测：201 建出 skill 与草稿、草稿里有一份根 `SKILL.md`（`name`/`description` 等于表单）、同名 409。**浏览器里点一遍、以及「在编辑页写一个文件再提交」未做** |
| T19 · 建 skill 的两种拒绝与事务失败 | 部分 | IT 覆盖两种拒绝，并比 `skill` / `skill_version` / `version_file` 三表行数；另有一个「JSON 体里没有 name」的用例（400 而不是 500）。**`@SpyBean` 注入失败、短码碰撞、两次并发未执行**——这一条仍是本版最大的测试缺口 |
| T20 · 文件栏表头的两颗动作 | 部分 | 设计稿已补（`design/README.md` §一个 skill；`console-states --only detail` 的六条新断言全绿）；`skill-transfer.test.ts` 的五条新用例覆盖顺序与直链、下载取屏幕上的那一版（钉住草稿时是 `@d1`）、钉住已丢弃不画下载、`browse` 没有新建版本、菜单项与关闭后焦点回到它。**五个档位逐一、400px 视口、以及「浏览器是否真的落盘」未人工跑** |

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

**一条窗口，本版明知不修**：并发删除 + 下载草稿——导出先取 manifest 快照，另一头的 `underExclusiveWrite` 清扫可能先删掉已无引用的 blob，请求随后在发字节前 500（客户端拿到的是错误信封，不是半个 zip）。冻结的已发布版本不受影响；**不拿 GC 锁去堵它**，理由见 TD §架构（那会让一次下载挡住所有写入）。状态：**已确认**（依据 TD §架构 的代码路径分析）；**未实测复现**，也没有对应用例——T10 测的是并发**新增**，不触发这一条。

**其余本版范围内暂无。**

实现时把原先记在这里的两条历史注释缺陷一并改掉了——两条都是注释与事实不符，行为一行未动：

- 「已丢弃没有文件快照」那句（`useVersionActions.ts`、`VersionPicker.vue`、`SkillPage.vue`）改成设计口径：不给已丢弃版本画文件是**决定**，不是没有字节——`DiscardSkillVersionUseCase` 明写行与字节都留着。
- `SubmitResponse` / `SubmittedSkill` / `SubmitOutcome` 的 javadoc 补上 `revoked` 这个状态（v9 的网页回放分支会读它）。

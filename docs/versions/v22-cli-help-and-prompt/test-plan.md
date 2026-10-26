# v22-cli-help-and-prompt · 测试方案

> **最后更新**：2026-10-27
> **所属版本**：[`README.md`](README.md)
> **依据**：PRD 已锁定（2026-10-27），技术设计见 [`technical-design.md`](technical-design.md)。

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #1 凭据两条路可发现 | T1 | 通过 |
| #2 无头环境快速失败（含六条读命令的帮助检查） | T2 | 未执行（darwin 造不出无头环境） |
| #3 凭据保存失败给下一步 | T3 | 未执行（darwin 的凭据在钥匙串，不在配置文件） |
| #4 401 下一步按凭据形态 | T4 | 通过（口径按 td Q4） |
| #5 在线编辑可发现并走通 | T5 | 通过 |
| #6 `@new` 与 `--from` 正确 | T6 | 通过（首轮漏了 404 的中文，已修） |
| #7 输出契约与帮助一致 | T7 | 通过 |
| #8 版本规则写清 | T8 | 通过（首轮漏了两句，已补） |
| #9 创作两条路径互指 | T9 | 通过 |
| #10 共享档位可判 | T10 | 通过（口径改为「一处写全、两处指路」，三种页面都合规） |
| #11 主题预算与单一权威 | T11 | 通过 |
| #12 v16 契约不回退 + 文档自检 | T12 | 通过 |
| #13 `grep` 对任意可读版本生效 | T13 | 通过 |
| #14 `skill check` 能抓断链并守住范围与快照 | T14 | 通过 |

## 用例

### T1 · 凭据两条路可发现

- **前置**：干净环境；清空 `SKILLMASTER_API_KEY`；本机无凭据；另备一把真实 API key 作夹具。
- **步骤**：`skillmaster login --help`；`skillmaster startup --help`；`skillmaster help env`；`skillmaster help auth`；再用那把 key 跑 `skillmaster auth status`。
- **期望**：四页都能看到「没有浏览器 / 容器 / 沙箱时用 `SKILLMASTER_API_KEY`」、控制台签发地址与 `skm_` 前缀；`help auth` 说清两种凭据、何时用、在哪撤销/续期，并指到 `help env`；`auth status` 的 key 输出含控制台续期/撤销地址。
- **判定**：通过／失败（失败时记录缺哪一句）

### T2 · 无头环境快速失败

- **前置**：无 `DISPLAY` 与 `WAYLAND_DISPLAY` 的 Linux（或容器）；无凭据；无 `SKILLMASTER_API_KEY`。
- **步骤**：计时跑 `skillmaster skill files <地址>`；再逐页读 `invoke/read/cat/files/grep/wc` 的帮助。
- **期望**：命令在 5 秒内返回，输出含 `login` 与 `SKILLMASTER_API_KEY`，不进入 5 分钟等待；六页帮助都写明「无凭据会尝试浏览器登录」，并分别写清哪些支持 `--no-login`、哪些会等用户登录。
- **判定**：通过／失败（记录耗时与缺哪一页）

### T3 · 凭据保存失败给下一步

- **前置**：把配置目录设成只读（`chmod 500`），或在沙箱里复现写入被拒。
- **步骤**：跑 `skillmaster login`。
- **期望**：保存失败的错误句含「改用 `SKILLMASTER_API_KEY`」或「给配置目录写权限」，不是裸的「保存凭据：<原始错误>」。
- **判定**：通过／失败

### T4 · 401 下一步按凭据形态

- **前置**：一把已撤销或已过期的 API key。
- **步骤**：用它跑 `auth status`、`skills list`、`skills my-tags`、`skill invoke`、`skill grep`、`skill show` 各一次；再读这六条命令的帮助。
- **期望**（2026-10-27 按 td Q4 改口径）：六页帮助的 `unauthenticated` 行**同时**写「重新登录」与「API key 到控制台续期或新签」——读帮助的人还没说自己拿的是哪一种；六处运行时文案**按手里那把凭据的形态给一条**（拿着 key 就指控制台，拿着登录就不是），因为运行时知道答案、不需要猜。仓库里的比对测试（声明文本 vs `refusalMessage` 关键词）通过。
- **判定**：通过／失败（逐条记录）

### T5 · 在线编辑可发现并走通

- **前置**：一个已发布 skill 的写权限；`help workflow` 存在。
- **步骤**：只用帮助里写的命令，走一遍 `read` → `write @new --name --from`（内容从 stdin）→ 同一草稿上 `edit` → `version diff` → `version open --sub actions`；记录每条命令与退出码。
- **期望**：全流程完成；序列里不出现 `download` + `submit`；`write`/`edit` 的成功信息与草稿码在 stderr 且帮助的 Output 段写明；`@new` 成功后打印的下一条命令可直接执行。
- **判定**：通过／失败（附完整命令序列）

### T6 · `@new` 与 `--from` 正确

- **前置**：同 T5。
- **步骤**：读 `write/edit/rm/mv/cp` 五页帮助；实跑 `--from <版本>` 与 `--from SKILL.md` 各一次。
- **期望**：`write/edit/rm/mv` 的用法行含 `@new`，`--name` 标为 `@new` 必填；`cp` 不再教 `@new`、不再列 `--from/--name/--note`；`--from` 显示为版本且例子传版本；`--from <版本>` 成功、`--from SKILL.md` 报「版本不存在」。
- **判定**：通过／失败

### T7 · 输出契约与帮助一致

- **步骤**：对一个有多个文件名和至少一个二进制文件的版本，跑 `read`、`cat`、`invoke`、`files`、`versions`、`search`。
- **期望**：`read` 带行号且末尾有版本页脚，帮助写明「不能直接写回」；`cat` 是原始字节，帮助写明二进制；`invoke` 正文末尾有一行说明（stdout），帮助写明；`files`/`versions`/`search` 的帮助写明哪些行是数据、哪些是说明；帮助里有「何时用哪个读命令」的一段。
- **判定**：通过／失败（逐条对照输出与帮助）

### T8 · 版本规则写清

- **前置**：同一 skill 有两个已发布版本，本机 `invoke` 钉住旧的那个。
- **步骤**：读 `read/invoke/cat/files` 四页帮助；实跑裸地址四个命令。
- **期望**：四页都有「裸地址 = 上次 `invoke` 钉住的；显式 `@版本` 才按它」；写明两组命令在没有 live 版本时的差异；写明版本注记的输出流（`read`/`files` 走 stdout，`cat`/`wc`/`grep` 走 stderr）；实跑结果的注记与帮助一致。
- **判定**：通过／失败

### T9 · 创作两条路径互指

- **步骤**：读 `submit`、`write`、`edit`、`download`、`skills pending`、`version publish` 的帮助。
- **期望**：`submit` 指到在线编辑与 `version open`，说明裸名字按 `SKILLMASTER_SKILLS_DIR` 解析，首例带 `--name`；`write`/`edit` 指到 `submit`、`version diff`、`version open`；`download` 注明是一次性快照；`skills pending` 说明只列可上线的，交接句是 `version open …@<草稿码> --sub actions`；`version publish --help` 写明退出 1 不是失败、上线用 `version open --sub actions`。
- **判定**：通过／失败

### T10 · 共享档位可判

- **步骤**：读 `share`、`request`、`shares` 三页帮助；用「让他管理」这句话判断该选哪档。
- **期望**（2026-10-27 按产品负责人选择改口径：一个事实一处写全）：`share` 写全五档含义与默认 `invoke`，并指到协作者管理页；`request` / `shares` 写「档位含义同 share」；「让他管理」能映射到 `admin`。
- **判定**：通过／失败

### T11 · 主题预算与单一权威

- **步骤**：`skillmaster help auth | wc -c`、`skillmaster help workflow | wc -c`；对全部帮助页与规则段 grep「在线编辑」相关句子。
- **期望**：两个 topic 各 ≤ 512 B；在线编辑的流程只在 `help workflow` 出现一次；规则段不含流程、只含三个问题与指路。
- **判定**：通过／失败（记录字节数与重复位置）

### T12 · v16 契约不回退 + 文档自检

- **步骤**：跑既有帮助测试（八项、≤ 2 KB、无 ANSI、退出码 0/1/2/4）；`python3 .claude/skills/docs-architecture/scripts/check_docs.py`。
- **期望**：测试全过；校验器退出 0。
- **判定**：通过／失败

### T13 · `grep` 对任意可读版本生效

- **前置**：同一 skill 备齐五种版本——线上、历史（旧 published）、草稿、已下架、已丢弃；三把凭据分别只有 `browse`、`write`、`maintain`；另有一把只有 `invoke`。
- **步骤**：按矩阵跑 `skill grep`——`browse` / `write` / `maintain` × 五种版本（线上走裸地址；历史 / 草稿 / 已下架 / 已丢弃带 `@<版本码>`）；再用 `invoke` 跑一次线上；最后读 `grep --help`。
- **期望**：矩阵内每一格都能搜到已知的那一行；`invoke` 回 403；行级不可见（如 `write` 问已下架）回 404；输出的路径 / 行号 / 截断语义与今天一致；`--help` 写明状态规则、示例用线上地址；`grepRefusal` 不再说「只在草稿里搜」。
- **判定**：通过／失败（附矩阵结果表）

### T14 · `skill check` 抓断链并守住范围与快照

- **前置**：两份内容夹具（干净 / 断链：markdown 链接、反引号路径、`skill read…`、`skill cat…` 各一条坏引用；命令写成**代码跨或围栏**，裸行不算命令）；同一 skill 备齐线上 / 历史 / 草稿 / 已下架 / 已丢弃五个版本；`browse`、`write`、`maintain`、`invoke` 四把凭据。
- **步骤**：
  1. 读 `skill check --help`。
  2. 两份夹具 × 四种版本状态（线上 / 历史 = `browse`，草稿 = `write`，已下架 = `maintain`；后三者带 `@<版本码>`）。
  3. 已丢弃：`maintain` 档 + `@<版本码>`。
  4. `invoke` 档跑线上。
  5. 摘要失效**分两种情况，只有第二种回「检查期间版本变过」**（td §CLI：新增 `skill check` 的「快照」一行；2026-10-27 改口径）：
     - **地址就是旧摘要**：`skill check <地址>@<旧摘要>`，第一次请求就 404 → 报「这个地址在你的 skill 里找不到」，与任何不存在的地址同一条答案。
     - **两次请求之间版本被动过**：第一次请求（按行 id / 版本名）成功、拿到的 ETag 摘要第二次归档时已不存在 → 报「检查期间版本变过，请重试」，且不退回用新内容。这一条由 `check_test.go` 的 `TestCheckSaysToRetryWhenTheDigestNoLongerResolves` 覆盖（构造方式：清单返回摘要，归档路由 404），线上无法稳定制造这个竞态，实跑记录见 §结果。
  6. **误报夹具**（干净且必须退出 0）：反引号后紧跟中文、同一行两条命令、无扩展名文件（`LICENSE`）、平衡括号与 `<带 空格>` 链接、围栏里的 `$ skill read …`、管道命令 `` `skill cat … | grep TODO` ``、围栏里展示的链接语法、`skill cat a.md b.md — 注释` 这类散文、四反引号围栏套三反引号。
- **期望**：帮助页写明「哪些算引用」与「不查什么」；断链夹具报 `path:line: 问题` 并退出 1，干净夹具与误报夹具退出 0；外部 URL 与二进制不误报；已丢弃时 `files` 成功而归档报 `invalid_version_state`（不是 404）；`invoke` 回 403；过期摘要报「检查期间版本变过，请重试」且不退回用新内容。
- **判定**：通过／失败（逐项记录）

## 环境与数据

- **栈**：本机 `skillmaster-server` + 控制台（发布 / 共享 / 上下架需要网页），CLI 用工作区构建并指向本机服务端（`SKILLMASTER_SERVER`）。
- **夹具**：一个专用 namespace；一个 skill 备齐线上 / 历史 / 草稿 / 已下架 / 已丢弃五个版本；四把凭据（`invoke` / `browse` / `write` / `maintain`）；两份 `skill check` 内容夹具；一个可 `submit` 的本地 skill 目录。
- **清理**：全部在专用 namespace 内，结束时丢弃草稿、下架历史版本；不动任何真实技能。

## 结果

**执行时间**：2026-10-27。**基线**：`7315918`（`main`，PR #67、#68 已合）。被验的两件东西都是这一提交构建的：CLI `/tmp/sm-main`（02:24），服务端是本机 8080 上由同一次构建产出的 jar（02:28，`/actuator/health` = UP）。

**夹具**：在 `matrix-owner` 下新建 `role-matrix-check`（1.0.0 断链 / 1.0.1 干净 / 1.0.2 断链 / 1.0.3 干净为线上、1.0.0 已下架、`40QB7RS9` 已丢弃、`N9B9GEPV` 误报夹具草稿、`59B29MY6` 断链草稿），凭据复用 `qa/role-matrix` 的六档。夹具 skill 只在本机 dev 库，未触碰任何真实技能。

| # | 用例 | 判定 | 证据（一句话 + 关键输出） |
|---|---|---|---|
| 1 | T1 | 通过 | `login --help` / `startup --help` 各命中 1 次 `SKILLMASTER_API_KEY`，`help env` 命中 2 次；`help auth` 385 B，含两种凭据与 `/account/keys`；`auth status` 拿 key 时打印「到控制台新签一把：http://127.0.0.1:8080/account/keys」 |
| 2 | T2 | **未执行** | 本机是 darwin，没有 `DISPLAY`/`WAYLAND_DISPLAY` 这种无头环境可造；该分支只存在于 `main.go` 的 `clientFor`（无浏览器时直接报错） |
| 3 | T3 | **未执行** | darwin 上凭据进系统钥匙串而不是配置文件，`chmod 500` 的配置目录不会触发保存失败；句子在 `main.go:852`（`保存凭据：…；改用 SKILLMASTER_API_KEY，或给配置目录写权限后重试`），只有 Linux/容器上能实跑 |
| 4 | T4 | 通过（按 td Q4 口径） | 用一把**已撤销**的 key（网页签出→`/web/keys/{id}/revoke` 204）跑六条命令：`auth status` / `skills list` / `skills my-tags` / `skill invoke` / `skill grep` / `skill show` 六处同为「这把 API key 已被撤销，续期不适用——到控制台新签一把：…/account/keys」，退出码都是 4；六页帮助的 `unauthenticated` 行两条路都写（`declarations_test.go`） |
| 5 | T5 | 通过 | `read`(0) → `write @new --name 1.0.7 --from 1.0.3`(0，stderr「已写入并建出草稿 …@7F3DMX3E。下一步：version diff …」) → `edit`(0，stderr「已修改 references/ok.md」) → `version diff`(0，`+2 −2、2 个文件`) → `version open --sub actions --print`(0，打印 `…?tab=versions&version=7F3DMX3E&sub=actions`)。序列里没有 `download` + `submit` |
| 6 | T6 | 通过 | 用法行：`write/edit/rm/mv` 都是 `<地址>@<版本码\|new>`；`cp` 仍是 `@<版本码>`，页面里 `--from/--name/--note/@new` 命中 0 次。`--from SKILL.md` 首轮报的是服务端英文原文，改后是「这个地址在你的 skill 里找不到。下一步：用 skillmaster version list 核对。」（修法见 §已知问题 1，已修） |
| 7 | T7 | 通过 | `read` 带行号 + stdout 页脚；`cat` 说明「原始字节，不带行号与页脚…要读文本优先用 read」；`invoke` 说明末尾一行说明也在 stdout；`files` 写明「正文与二进制那两行后面各有一条缩进说明，末尾还有整版的页脚（stdout）」；`versions`「一版一个地址行，后面跟 0–2 行缩进」；`search`「每条是地址一行 + 描述 2–3 行」 |
| 8 | T8 | 通过（首轮漏两句，已补） | 四页都有「裸地址用上次 invoke 钉住的版本，不是当前线上；要别的版本就写 `@<版本>`。」；补上后：`cat`/`wc`/`grep` 的 Output 各写完「版本注记走 stderr」，`read` 加一句「没有上线版本时按最近可见的版本读，注记会写明；invoke 报已下线。」；页大小 `read` 1113 B / `cat` 895 B / `wc` 911 B / `grep` 1394 B，都在 v16 的 2 KB 内 |
| 9 | T9 | 通过 | `submit` 指到 `version open` 与「在线」并写明 `SKILLMASTER_SKILLS_DIR`；`write` 指到 `submit`/`version diff`/`version open`；`download` 写明一次性快照；`skills pending` 的交接句是 `version open …@<草稿码> --sub actions`；`version publish` 写明退出 1 不是失败 |
| 10 | T10 | 通过（按 2026-10-27 定的口径） | `share` 写全五档与默认 `invoke`（「说『让他管理』要给 admin」）；`request` 写「档位含义同 skill share」、`shares` 写「档位含义同 share」，且两页的用法行都列着五个档名 |
| 11 | T11 | 通过 | `help auth` 385 B、`help workflow` 510 B（都 ≤ 512 B）；「在线」那条流程只在 `help workflow` 出现（`auth`/`env` 命中 0）；顶层 `help` 有「主流程：skillmaster help workflow」 |
| 12 | T12 | 通过 | `go test ./...`（`skillmaster-cli`）全过（`cmd/skillmaster` 0.627s）；`check_docs.py` 退出 0（2456 条相对链接 + 14 条 skill 自身链接全部有效） |
| 13 | T13 | 通过 | 见下表 |
| 14 | T14 | 通过 | 见下节逐项 |

### T13 的矩阵（本地 8080，`matrix-owner/role-matrix-fixture`，`grep role-matrix -F -n`）

HTTP 状态码（直接打 `/api/v1/author/skills/…/grep`，每档一把 key）：

| 档 | 线上 | 历史 1.0.0 | 已下架 1.0.1 | 已丢弃 95F376HE | 草稿 WNDC9VND |
|---|---|---|---|---|---|
| invoke | 403 | 403 | 403 | 403 | 403 |
| browse | 200 | 200 | 404 | 404 | 404 |
| write | 200 | 200 | 404 | 404 | 200 |
| maintain / admin / owner | 200 | 200 | 200 | 200 | 200 |

CLI 同一矩阵（`skillmaster skill grep <地址>[@版本] role-matrix -F -n`，记 `退出码/命中行数`）：

| 档 | 线上 | 历史 | 已下架 | 已丢弃 | 草稿 |
|---|---|---|---|---|---|
| invoke | 1/0（403 文案） | 1/0 | 1/0 | 1/0 | 1/0 |
| browse | 0/2 | 0/2 | 1/0 | 1/0 | 1/0 |
| write | 0/2 | 0/2 | 1/0 | 1/0 | 0/2 |
| maintain | 0/2 | 0/2 | 0/2 | 0/2 | 0/2 |

`invoke` 的文案是「你对这个 skill 没有这个权限。换一份凭据也一样——这是账号对这个资源的权限，不是凭据的权限。」；`browse`/`write` 问看不见的版本是服务端 404 原文（见 §已知问题 1）。`grep --help` 写明状态规则（「能搜读得到的版本：线上 / 历史要 browse，草稿要 write，已下架 / 已丢弃要 maintain」），例子用裸地址；`grepRefusal` 只在 `grep_requires_draft`（旧服务端）时说「这台服务端还只让 grep 搜草稿…升级 skillmaster」。

### T14 逐项

| 项 | 判定 | 证据 |
|---|---|---|
| 帮助页写明「哪些算引用」「不查什么」 | 通过 | `skill check --help`：「算引用的有三处：markdown 链接、反引号里的路径、`skill read/cat` 的路径；只看相对路径，不查语义。不查 frontmatter 与根 SKILL.md 的存在性」 |
| 断链夹具 → `path:line: 问题`、退出 1 | 通过 | `SKILL.md:9: references/missing.md 不在这一版里` / `:10: references/gone.md` / `:12: references/also-gone.md`，rc=1；`matrix-owner/role-matrix-check` 的这一版线上、1.0.2 历史、草稿 `59B29MY6`、已下架 1.0.0 四处都复现 |
| 干净夹具 → 退出 0 且不打印 | 通过 | 线上 1.0.3 rc=0、stdout 空；历史 1.0.1 rc=0 |
| 误报夹具 → 退出 0 | 通过 | 草稿 `N9B9GEPV`（反引号后紧跟中文、一行两条命令、无扩展名 `LICENSE`、平衡括号与 `<带 空格>` 链接、围栏里的 `$ skill read …`、`` `skill cat … \| grep TODO` ``、围栏里展示的链接语法、`skill cat a.md b.md — 注释` 散文、四反引号套三反引号、`AGENTS.override.md` / `.bak` / `Users/…` / `steps/03-prompt.md`）rc=0、stdout 空 |
| 外部 URL 与二进制不误报 | 通过 | `https://example.com/x.md` 与 `assets/logo.png`（二进制里嵌了一条指向 `nope.md` 的假 markdown 链接）都不出现在输出里 |
| 已丢弃：`files` 成功、归档 `invalid_version_state` 而不是 404 | 通过 | `40QB7RS9`：`files` rc=0；`/archive` 回 400 `invalid_version_state`（`this version has been discarded`）；`skill check` 报「这一版目前的状态不能做这个操作。」 |
| `invoke` 档 → 403 | 通过 | `skill check …@59B29MY6` 与线上都是「你对这个 skill 没有这个权限…」 |
| 摘要与快照 | 通过（口径已改） | 用摘要寻址正常：`@sha256:00eb2a01…` 检查 rc=1 并打出三条断链；把草稿改掉后再用**同一个旧摘要**跑，得到「这个地址在你的 skill 里找不到」（第一次请求就 404，与不存在的地址同一条答案）。「检查期间版本变过」这一条线上造不出竞态，由 `check_test.go` 的 `TestCheckSaysToRetryWhenTheDigestNoLongerResolves` 覆盖 |

**两处口径按 td 改写**（不是为了让结果变绿，而是原文与 td 冲突）：T4 的「六处运行时要同时给两条路」改为「声明两条路、运行按凭据形态给一条」（td Q4 就是这么定的）；T14 第 5 步把「过期摘要」拆成「地址就是旧摘要」（地址找不到）与「两次请求之间被动过」（检查期间版本变过），后者才是 td 说的「第二次 404」。

## 已知问题

本版内的缺口。三条文案缺口在验收当天就修了（2026-10-27），记在这里是因为它们是**测试发现产品问题**的证据，不是遗留：

1. **版本级 404 漏成服务端英文**——`skill check`/`files`/`cat`/`wc`/`grep`/`read` 与 `--from` 的解析在 404 时直接把服务端那句 `no skill at that address` 给用户看。**已修复**：新增 `addressRefusal`（`version.go`），把作者面所有 404 收成「这个地址在你的 skill 里找不到。下一步：用 skillmaster version list 核对。」；`checkRefusal`/`downloadRefusal` 与另外两处重复的三行判断合并成 `versionRefusal`。`expected-changes.json` 记了 5 条 golden 变化，`TestARevokedPinReportsTheAuthorPlanesNotFoundToALowRung` 的断言改成这句话（它防的仍是「低档看到 404 而不是 410」）。
2. **T8 的两条没写**——`cat` 没说注记走 stderr、「没有 live 时两组命令的差异」没写成对照。**已修复**：`cat`/`wc`/`grep` 的 Output 各加「版本注记走 stderr。」，`read` 加「没有上线版本时按最近可见的版本读，注记会写明；invoke 报已下线。」
3. **T10 的口径**——`request`/`shares` 不写五档含义与默认 `invoke`，与验收 #10 的字面要求和「一个事实一处」都冲突。**已定**：保留单一权威，改验收 #10（prd §验收、td §CLI 其余命令面、本文件 T10 同步改成「`share` 写全、另两页指路」）。
4. **`qa/role-matrix` 里没有 `grep` / `skill check` 的探针**：T13/T14 这次是手工按矩阵跑的，矩阵没有沉淀成用例。**已记录**。要不要补进 `expectations.py` / `CLI_READ_PROBES` 另定。

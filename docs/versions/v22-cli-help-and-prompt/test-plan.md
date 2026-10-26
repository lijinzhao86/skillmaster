# v22-cli-help-and-prompt · 测试方案

> **最后更新**：2026-10-27
> **所属版本**：[`README.md`](README.md)
> **依据**：PRD 已锁定（2026-10-27），技术设计见 [`technical-design.md`](technical-design.md)。

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| #1 凭据两条路可发现 | T1 | 未执行 |
| #2 无头环境快速失败（含六条读命令的帮助检查） | T2 | 未执行 |
| #3 凭据保存失败给下一步 | T3 | 未执行 |
| #4 401 下一步按凭据形态 | T4 | 未执行 |
| #5 在线编辑可发现并走通 | T5 | 未执行 |
| #6 `@new` 与 `--from` 正确 | T6 | 未执行 |
| #7 输出契约与帮助一致 | T7 | 未执行 |
| #8 版本规则写清 | T8 | 未执行 |
| #9 创作两条路径互指 | T9 | 未执行 |
| #10 共享档位可判 | T10 | 未执行 |
| #11 主题预算与单一权威 | T11 | 未执行 |
| #12 v16 契约不回退 + 文档自检 | T12 | 未执行 |
| #13 `grep` 对任意可读版本生效 | T13 | 未执行 |
| #14 `skill check` 能抓断链并守住范围与快照 | T14 | 未执行 |

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
- **期望**：六处运行时文案都同时给「重新登录」与「API key 到控制台续期或新签」；六页帮助的 `unauthenticated` 行与此一致；仓库里的比对测试（声明文本 vs `refusalMessage` 关键词）通过。
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
- **期望**：三页都能看到五档含义与默认 `invoke`；「让他管理」能映射到 `admin`；`share` 指到协作者管理页。
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
  5. 过期摘要：对一个草稿先 `files` 取摘要，写一次内容不同、且不与同 skill 其他版本撞摘要的改动，再用旧摘要跑。
  6. **误报夹具**（干净且必须退出 0）：反引号后紧跟中文、同一行两条命令、无扩展名文件（`LICENSE`）、平衡括号与 `<带 空格>` 链接、围栏里的 `$ skill read …`、管道命令 `` `skill cat … | grep TODO` ``、围栏里展示的链接语法、`skill cat a.md b.md — 注释` 这类散文、四反引号围栏套三反引号。
- **期望**：帮助页写明「哪些算引用」与「不查什么」；断链夹具报 `path:line: 问题` 并退出 1，干净夹具与误报夹具退出 0；外部 URL 与二进制不误报；已丢弃时 `files` 成功而归档报 `invalid_version_state`（不是 404）；`invoke` 回 403；过期摘要报「检查期间版本变过，请重试」且不退回用新内容。
- **判定**：通过／失败（逐项记录）

## 环境与数据

- **栈**：本机 `skillmaster-server` + 控制台（发布 / 共享 / 上下架需要网页），CLI 用工作区构建并指向本机服务端（`SKILLMASTER_SERVER`）。
- **夹具**：一个专用 namespace；一个 skill 备齐线上 / 历史 / 草稿 / 已下架 / 已丢弃五个版本；四把凭据（`invoke` / `browse` / `write` / `maintain`）；两份 `skill check` 内容夹具；一个可 `submit` 的本地 skill 目录。
- **清理**：全部在专用 namespace 内，结束时丢弃草稿、下架历史版本；不动任何真实技能。

## 结果

**待实现后填写。** 每条都要有证据（命令输出、日志片段），不要只写「通过」。

## 已知问题

本版范围内暂无记录。

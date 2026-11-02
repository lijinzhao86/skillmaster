# v42-skill-source-sync · 产品需求

> **最后更新**：2026-11-02
> **所属版本**：[`README.md`](README.md)

## 用户与场景

**谁在用**：从站点安装 SkillMaster 的人，以及替他执行安装的 agent。

**在什么场景下用**：安装说明的第一步是下载 [`skillmaster.tgz`](../../../skillmaster-site/public/install/skillmaster.md)，解开后 agent 读到的就是包里的 `SKILL.md` 与 `sop/`。这份正文决定了它之后每一次「先查 skill、再 `invoke`」的行为——它不是说明文档，是 agent 照着执行的操作规范。

**现在的替代做法是什么**：作者本机的 `~/.claude/skills/skillmaster` 是一份手工维护的版本，已经和仓库、线上包分叉。同一个人换一台机器、或把安装说明发给别人，拿到的就是另一套指令；而本机那份「好用」不会出现在任何地方的证据里。

**为什么还要做**：发布包应当是技能正文的唯一权威，本机副本只是它的一次拷贝。否则「我本机好用、别人装的不好用」只能靠对比两份文件才能定位。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | `skills/skillmaster/SKILL.md` 换成全局版：frontmatter 描述与正文结构都更新；表格补回 `00-model` 与 `07-files` 两行 | 仓库里的 Markdown 文件 |
| 2 | 新增 `skills/skillmaster/sop/00-model.md`（全局版）：版本、线上 / 草稿、上线只能在网页、删除例外 | 同上 |
| 3 | `skills/skillmaster/sop/05-manage.md` 换成全局版：删除例外与「为什么」指向 `00-model.md`，不再就地复述 | 同上 |
| 4 | 文件数从 8 改到 9：安装文档两处命令与两处说明、`site.yml` 三处断言与一处注释、`build-skill-tgz.py` 的注释 | 安装文档 + workflow + 脚本注释 |
| 5 | 本地校验：包为 9 个文件、`name: skillmaster` 仍在、`sop/*.md` 链接双向闭合、全仓无残留的 8 | `build-skill-tgz.py` + 链接与计数检查 |
| 6 | 文档与 [`TODO`](../../current/TODO.md) 同步 | 版本四件套 + TODO 一行 |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 采用全局的 `sop/03-edit-draft.md` | 它缺二进制文件的提示（`put` / `get` 不用 `write`）；仓库版本覆盖 v39 的能力，并指向 `07-files.md` | 全局版补上这段提示之后 |
| 2 | 删除 `sop/07-files.md` | 它是 v39 的二进制上传 / 读取说明；删掉等于把这项能力从指引里拿走 | 不适用（v39 已交付） |
| 3 | 在正文补 `invoke` / `read` 的分工，或做 CLI 拦截 | 用户 2026-11-02 决定先记入 [`TODO`](../../current/TODO.md)，不在本版做 | 单独开版；先定拦截方式（提示 vs 拒绝）与覆盖命令（`read` / `cat` / `head` / `tail`） |
| 4 | 改服务端、控制台、CLI 行为或设计稿 | 本版只改技能包内容、安装文档与 CI 闸 | 不适用 |
| 5 | 改接口或命令语法 | 没有新增命令；`invoke` / `read` 的分工另开版本 | 见不做 3 |

## 控制台操作与 CLI 对应

本版**没有新增或改动控制台操作**，也没有 CLI 行为变更。[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 的映射表为空，不申请例外。

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| A1 | `SKILL.md`、`sop/00-model.md`、`sop/05-manage.md` 与全局版一致；差异只允许是 `SKILL.md` 表格里补回的 `07-files` 行 | `diff` 三个文件；`SKILL.md` 只多一行，另两个无差异 |
| A2 | 技能包是 9 个文件 | `tar -tzf … \| grep -vc '/$'` 等于 9；两次构建 sha256 相同 |
| A3 | frontmatter 仍是 `name: skillmaster` | `scripts/build-skill-tgz.py` 通过（它强制检查这一条） |
| A4 | `SKILL.md` 里每个 `sop/*.md` 链接都指向包内存在的文件；`sop/` 下每个 `.md` 都被索引 | 正向逐个 `test -f`；反向检查每个文件名在 `SKILL.md` 里出现 |
| A5 | 文档校验与引用检查通过 | `check_docs.py` 退出 0；`scripts/docs/impact.py --check` 退出 0 |
| A6 | 没有残留的 8 文件断言 | 在安装文档、`site.yml`、`build-skill-tgz.py` 里搜 `-eq 8`、`-ne 8`、`数够 8`、`一共 8`、`exactly eight`，零命中 |
| A7 | 线上包与构建产物逐字节一致 | 站点部署 workflow 自带断言：下载桶里的 tgz 与 `public/skill/skillmaster.tgz` `cmp` 一致、文件数 9、`Cache-Control: no-cache` 在场。**本版本地不执行**（部署要合并后手动 dispatch） |

**成立条件**：A2 与 A6 不满足就不算交付。前者错了，安装说明的「数够 9 个文件」会让每一次安装失败；后者错了，安装说明与真实包对不上。

### 指标

没有单独的量化指标。判据是 A1–A6 在本地全过；A7 由部署 workflow 在发布时执行。

## 依赖与前置

- **外部依赖**：站点部署需要人工触发 `workflow_dispatch`（[`site.yml`](../../../.github/workflows/site.yml) 的 deploy 只在手动触发时跑）；合并本身只跑检查、不上线。
- **内部前提**：包形状由 [`site.yml`](../../../.github/workflows/site.yml) 的三处断言与安装文档共同钉住；本版把两边的数一起改成 9。
- **排除了什么前置**：不需要服务端、控制台、CLI 改动，也不需要设计稿；全局版来源是用户本机的 `~/.claude/skills/skillmaster`，不依赖任何远端服务。

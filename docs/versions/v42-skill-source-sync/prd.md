# v42-skill-source-sync · 产品需求

> **最后更新**：2026-11-02
> **所属版本**：[`README.md`](README.md)

## 用户与场景

**谁在用**：从站点安装 SkillMaster 的人，以及替他执行安装的 agent。

**在什么场景下用**：安装说明的第一步是下载 [`skillmaster.tgz`](../../../skillmaster-site/public/install/skillmaster.md)，解开后 agent 读到的就是包里的 `SKILL.md`。这份正文决定了它之后每一次「先查 skill、再 `invoke`」的行为——它不是说明文档，是 agent 照着执行的操作规范。

**现在的替代做法是什么**：作者本机的 `~/.claude/skills/skillmaster` 是一份手工维护的版本，已经和仓库、线上包分叉。同一个人换一台机器、或把安装说明发给别人，拿到的就是另一套指令；而本机那份「好用」不会出现在任何地方的证据里。

**为什么还要做**：发布包应当是技能正文的唯一权威，本机副本只是它的一次拷贝。否则「我本机好用、别人装的不好用」只能靠对比两份文件才能定位。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | 用全局版替换 `skills/skillmaster/SKILL.md`：frontmatter 的 `description` 与正文结构都随之更新 | 仓库里的 Markdown 文件 |
| 2 | 为保持包内自洽，表格做两处编辑：删掉指向不存在的 `sop/00-model.md` 的行；补回指向 `sop/07-files.md` 的行 | 同一个文件 |
| 3 | 本地校验：包仍为 8 个文件、`name: skillmaster` 仍在、`sop/*.md` 链接双向闭合 | `build-skill-tgz.py` + 链接检查 |
| 4 | 文档与 [`TODO`](../../current/TODO.md) 同步 | 版本四件套 + TODO 一行 |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 引入 `sop/00-model.md` | 全局版把版本模型同时写在 `SKILL.md` 与 `00-model.md` 两处（同一个事实两个家）；仓库选择把它内联在 `sop/05-manage.md`。加进来会把包变成 9 个文件，而安装文档与 `site.yml` 的三处闸按 8 个写死 | 决定把包形状从 8 改成 9 时；要同时改安装文档、`site.yml` 三处断言与测试 |
| 2 | 采用全局的 `sop/03-edit-draft.md` 与 `sop/05-manage.md` | 全局 03 缺二进制文件的提示、全局 05 指向不存在的 `00-model.md`；仓库版本覆盖 v39 的二进制上传与读取 | `00-model.md` 回归时一并评估 |
| 3 | 删除 `sop/07-files.md` | 它是 v39 的二进制上传 / 读取说明；照抄全局会让线上包少掉这一块 | 不适用（v39 已交付） |
| 4 | 在正文补 `invoke` / `read` 的分工，或做 CLI 拦截 | 用户 2026-11-02 决定先记入 [`TODO`](../../current/TODO.md)，不在本版做 | 单独开版；先定拦截方式（提示 vs 拒绝）与覆盖命令（`read` / `cat` / `head` / `tail`） |
| 5 | 改服务端、控制台、CLI 或设计稿 | 本版只改技能包里的一个 Markdown 文件 | 不适用 |

## 控制台操作与 CLI 对应

本版**没有新增或改动控制台操作**，也没有 CLI 行为变更。[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 的映射表为空，不申请例外。

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| A1 | 仓库里的 `SKILL.md` 与全局版一致，除两处表格编辑外没有差异 | `diff` 两份文件；差异只能是 `00-model` 行删除与 `07-files` 行新增 |
| A2 | 技能包仍是 8 个文件 | `tar -tzf … \| grep -vc '/$'` 等于 8 |
| A3 | frontmatter 仍是 `name: skillmaster` | `scripts/build-skill-tgz.py` 通过（它强制检查这一条） |
| A4 | `SKILL.md` 里每个 `sop/*.md` 链接都指向包内存在的文件；`sop/` 下每个 `.md` 都被索引 | 正向：解析链接后逐个 `test -f`；反向：每个文件名在 `SKILL.md` 里出现一次 |
| A5 | 文档校验与引用检查通过 | `check_docs.py` 退出 0；`scripts/docs/impact.py --check` 退出 0 |
| A6 | 线上包与构建产物逐字节一致 | 站点部署 workflow 自带断言：下载桶里的 tgz 与 `public/skill/skillmaster.tgz` `cmp` 一致、文件数 8、`Cache-Control: no-cache` 在场。**本版本地不执行**（部署要合并后手动 dispatch） |

**成立条件**：A2 与 A4 不满足就不算交付。前者错了，安装文档的「数够 8 个文件」会让每一次安装失败；后者错了，包里会有一页读不到（`07-files.md`）或一个死链（`00-model.md`）。

### 指标

没有单独的量化指标。判据是 A1–A5 在本地全过；A6 由部署 workflow 在发布时执行。

## 依赖与前置

- **外部依赖**：站点部署需要人工触发 `workflow_dispatch`（[`site.yml`](../../../.github/workflows/site.yml) 的 deploy 只在手动触发时跑）；合并本身只跑检查、不上线。
- **内部前提**：包形状「8 个文件」由 [`site.yml`](../../../.github/workflows/site.yml) 的三处断言与安装文档共同钉住；本版不动它。
- **排除了什么前置**：不需要服务端、控制台、CLI 改动，也不需要设计稿；全局版来源是用户本机的 `~/.claude/skills/skillmaster`，不依赖任何远端服务。

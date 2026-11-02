# v42-skill-source-sync · 测试方案

> **最后更新**：2026-11-02
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| A1 三个文件与全局版一致，差异只允许 `SKILL.md` 多一行 07 | T1、T2 | 通过 |
| A2 技能包是 9 个文件 | T3 | 通过 |
| A3 frontmatter 仍是 `name: skillmaster` | T3 | 通过 |
| A4 `sop/*.md` 链接双向闭合 | T4 | 通过 |
| A5 文档与引用检查通过 | T5 | 通过 |
| A6 没有残留的 8 文件断言 | T6 | 通过 |
| A7 线上包与构建产物逐字节一致 | T7 | 未执行（部署未触发） |

## 用例

### T1 · 仓库版与全局版逐字对比

- **前置**：本机存在 `~/.claude/skills/skillmaster/`；仓库里的三个文件已是本版内容。
- **步骤**：`diff -u` 三对文件——`SKILL.md`、`sop/00-model.md`、`sop/05-manage.md`。
- **期望**：`00-model.md` 与 `05-manage.md` 无差异；`SKILL.md` 只多出 `| 传 / 取单个文件… | sop/07-files.md |` 一行。
- **判定**：通过／失败

### T2 · 索引的必然性

- **前置**：同上。
- **步骤**：检查 `SKILL.md` 同时索引 `00-model.md` 与 `07-files.md`，且两个文件都在包内；`sop/` 下没有未被索引的文件。
- **期望**：8 个 SOP 全部被索引，无死链。
- **判定**：通过／失败

### T3 · 打包、文件数与 frontmatter

- **前置**：仓库根目录。
- **步骤**：`python3 scripts/build-skill-tgz.py` 跑两次；`tar -tzf skillmaster-site/public/skill/skillmaster.tgz | grep -vc '/$'`。
- **期望**：脚本不报错（它强制 `name: skillmaster`）；文件数 9；两次构建 sha256 相同。
- **判定**：通过／失败

### T4 · 链接双向闭合

- **前置**：`T3` 的 tgz 已生成。
- **步骤**：从 `SKILL.md` 抽出所有 `sop/*.md` 链接，逐个 `test -f`；再对 `sop/` 下每个 `.md`，检查文件名在 `SKILL.md` 里出现。
- **期望**：正向全部存在；反向全部被索引（00–07 共 8 个）。
- **判定**：通过／失败

### T5 · 文档与引用检查

- **前置**：本版四份文档已写。
- **步骤**：`python3 .claude/skills/docs-architecture/scripts/check_docs.py`；`python3 scripts/docs/impact.py --check`。
- **期望**：两条都退出 0。
- **判定**：通过／失败

### T6 · 没有残留的 8 文件断言

- **前置**：计数契约已改。
- **步骤**：在安装文档、`site.yml`、`build-skill-tgz.py` 里搜 `-eq 8`、`-ne 8`、`数够 8`、`一共 8`、`exactly eight`。
- **期望**：零命中。
- **判定**：通过／失败

### T7 · 部署后的线上字节一致

- **前置**：PR 已合并；有人手动 dispatch 了 `Site` workflow 的 deploy。
- **步骤**：workflow 内的断言执行——下载 `beta/skillmaster.tgz`，与 `public/skill/skillmaster.tgz` `cmp`；数文件 9；检查 `Cache-Control: no-cache`。
- **期望**：三条断言全过。
- **判定**：未执行（本版不触发部署）

## 环境与数据

- **环境怎么造**：仓库根目录，已 checkout 本版分支；`python3` 可用；不需要服务端、数据库或浏览器。
- **数据**：全局版文件（用户本机路径）只读对比；构建产物 `skillmaster-site/public/skill/skillmaster.tgz` 已被 `.gitignore` 忽略。
- **清理**：无需清理；tgz 是忽略文件，重新构建会覆盖。

## 结果

实现完成后填写。每条都要有证据（命令输出、sha256），不要只写「通过」。

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | `diff`：`00-model.md`、`05-manage.md` 无差异；`SKILL.md` 只多 `07-files` 一行（2026-11-02 22:09） |
| T2 | 通过 | `SKILL.md` 索引 00–07；`sop/` 下 8 个文件全部被索引，无孤立文件 |
| T3 | 通过 | 两次构建均 `sha256=494449b6618535bf0ac07d0f5ce627c8567f92a9b2507666feea77f2a8a6ab24`；`tar -tzf … \| grep -vc '/$'` = 9 |
| T4 | 通过 | 9 条 `sop/*.md` 链接全部存在（`missing: []`）；8 个 SOP 全部被索引（`unindexed: []`） |
| T5 | 通过 | `check_docs.py` 退出 0（3348 条相对链接、17 条 skill 自身链接全有效）；`impact.py --check` 退出 0（873 个对象） |
| T6 | 通过 | 搜 `-eq 8` / `-ne 8` / `数够 8` / `一共 8` / `exactly eight`：零命中（exit 1） |
| T7 | 未执行 | 部署未触发；合并后需手动 dispatch `Site` workflow |

**失败或跳过的用例要单列说明**，不要埋在表格里。

## 已知问题

本版没有范围内的未修缺陷。范围外的历史缺陷不在此登记。

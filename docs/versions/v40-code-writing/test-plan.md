# v40-code-writing · 测试

> **最后更新**：2026-11-02

## 验收映射

| 验收 | 用例 |
|---|---|
| A1 目录改名与链接 | T1 |
| A2 索引可重现 | T2 |
| A3 ADR 状态一致 | T3 |
| A4 版本归档 | T4 |
| A5 派生文档跟源变 | T5、T10 |
| A6 三个 skill 无重复规则 | T6 |
| A7 注释规则清零 | T7 |
| A8 引用可解析 | T9 |
| A9 代码零改动 | T8 |

## 用例

### T1 · 目录改名与链接

- **前置**：分支 `docs/code-writing-refactor`。
- **步骤**：`mv docs/architecture docs/current`，全仓替换引用；跑 `check_docs.py`。
- **期望**：退出码 0；`grep -rnE '\]\([^)]*/architecture/' --include='*.md' .` 无输出（`docs-architecture` 这个 skill 名不算）。

### T2 · 索引可重现

- **前置**：三份索引已生成并入库。
- **步骤**：删除三份索引 → 跑 `generate.py` → `git diff --exit-code`。
- **期望**：无差异；退出码 0。

### T3 · ADR 状态一致

- **步骤**：跑 `generate.py --check`，再逐条比对索引状态与 ADR 文件头。
- **期望**：不一致数 = 0；`0027`、`0028` 补上状态行。

### T4 · 版本归档

- **步骤**：`grep -rl '归档' docs/versions/v*/README.md | grep -v v40 | wc -l`。
- **期望**：输出 39；`docs/versions/README.md` 列出 40 个版本。标记词是「归档」，不是「已归档」。

### T5 · 派生文档跟源变

- **步骤**：对三份代码派生的视图各做一次：临时改 `ModuleMap` 里某模块的名字、`IngestLimits` 里一个常量、`ErrorCode` 里一个线上值 → 各跑一次 `generate.py` → 看对应的 `system.md` / `limits.md` / `errors.md` 是否跟着变 → 从备份还原。
- **期望**：三份都跟着变；还原后 `--check` 为 0、三个源文件无残留改动。

### T6 · 三个 skill 无重复规则

- **步骤**：逐条对三个 skill 的规则清单，标出每条规则的定义处。
- **期望**：每条只在一个 skill 里定义；另两处是指向链接。

### T7 · 注释规则清零

- **步骤**：跑 `check_comments.py --all`，再对 `skillmaster-console/src` 跑一次。
- **期望**：两处都输出「共 0 处」。**版本号不在检查范围**——它是指针，由 T9 保证可解析。

### T10 · 新增的三份生成物跟源变

- **步骤**：改一条迁移里的一个列名、一条 `help.Spec` 的 `Summary`、一个 `application.yml` 的值，各跑一次 `generate.py`。
- **期望**：`schema.md` / `cli.md` / `config.md` 各自跟着变；还原后 `--check` 为 0。

### T9 · 引用可解析

- **步骤**：`python3 scripts/docs/impact.py --check`。
- **期望**：退出码 0；活文档与代码注释里的符号、ADR、版本、测试类、配置键全部指得到；历史文档（ADR 正文、v40 之前的版本）按设计不复核。

### T8 · 代码零改动

- **步骤**：`git diff -U0 main -- '*.java' '*.go'`，检查每个被改的行。
- **期望**：全部是注释行（`//`、`/*`、`*` 开头，或纯缩进后接这些）。

## 环境与数据

- 本机 macOS，`python3` 标准库；不需要数据库、服务端、浏览器。
- 数据就是仓库本身，没有夹具。
- 清理：分支内改动，不落 `main`；临时改动在 T5 里显式还原。

## 结果

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | `check_docs.py` 全绿，3,284 条相对链接全部有效；A1 的 grep 0 命中 |
| T2 | 通过 | `generate.py --check` 报「生成物与源一致」（这比对全部生成物，比删了重建更严格） |
| T3 | 通过 | 134 条 ADR ↔ 134 行索引（含本版新增的 0134）；状态列由文件头生成，不一致数 0 |
| T4 | 通过 | 39 个旧版 README 带「归档」；`ls -d docs/versions/v*/` = 40 |
| T5 | 通过 | 三份各做一次：改 M1 的 name、`STANDARD_MAX_FILES`、一个错误码的线上值，`system.md` / `limits.md` / `errors.md` 各自跟着变；从备份还原后 `--check` 为 0 |
| T6 | 通过 | `docs-architecture` 2 处、`dev-sop` 1 处指向 `code-writing`；三处无同一条规则的副本 |
| T7 | 通过 | `check_comments.py --all` 输出「共 0 处」；控制台源码同一条命令也是 0（改造前服务端 19 处、前端 5 处；版本号按指针处理，由 T9 保证可解析） |
| T8 | 通过 | 全部改动行单独复核，非注释 0 行；`cd skillmaster-server && ./mvnw -DskipTests compile` 退出 0 |
| T10 | 通过 | 三份生成物都跟着源变：改迁移里的列名、`help.Spec` 的 Summary、`application.yml` 的值，各自重生成后都体现出来；还原后 `--check` 为 0、三个源文件无残留改动。规模：`schema.md` 33 张表（`browser_session` 按 `DROP TABLE` 剔除）、`cli.md` 77 条命令、`config.md` 61 个配置键 |
| T9 | 通过 | `impact.py --check` 退出 0：871 个被引用对象全部可解析，历史文档 41 处按设计跳过。锁的实验：把 `limits.md` 里的 `IngestLimits.STANDARD_MAX_FILE_BYTES` 改成少一个字母，`--check` 立刻退出 1；还原后 0 |

**T7 的口径**：`check_comments.py` 统计的是"命中的注释行数"，不是"问题数"；`/api/v1` 这类路径不算版本叙述。`--all` 恒退出 0，判据是 stdout 的「共 N 处」。

**T6 的判定**：三个 skill 里每条规则只出现一次——`docs-architecture` 只写轴与目录，`code-writing` 只写事实归属与注释规则，`dev-sop` 只写阶段顺序；另两处只链接。

## 已知问题

- **改名触到了冻结文本，但只换了链接目标**：3 条 ADR 正文（0031、0123、0132）与 20 个版本正文文件里，`../architecture/…` / `../../architecture/…` 改成了 `../current/…` / `../../current/…`，链接的显示文字与整句不动。这是 A1「没有链接指向旧路径」的必要代价；规则已写进 `docs-architecture` §2.2 与 §四，处数记在这里：共 59 个版本文件——39 个加了「归档」行（其中 36 个只加这一行），20 个正文文件改了链接，另有 3 个 README 两者都有。

- **`target/` 内部仍有旧版本号与行号引用**：`target/README.md` 用行号定位差异，改正文会让行号漂。本版不动 `target/` 正文，只把它纳入索引。
- **注释存量**：本版只清规则能判定的重复数值与历史措辞；`v\d+` 与 ADR 复述不清——前者是指针，后者靠评审。其余文件按"动到哪个文件清哪个"推进（P3）。

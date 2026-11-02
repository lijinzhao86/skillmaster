# v42-skill-source-sync · 技术设计

> **最后更新**：2026-11-02
> **状态**：实现中
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文只链接、不复述理由。

## 架构

本版不改系统架构，只改技能包内容与围绕它的两处计数。相关的既有管线：

```
skills/skillmaster/                                  源：SKILL.md + sop/*.md（本版 9 个）
        │
        │  scripts/build-skill-tgz.py（确定性打包；白名单只收 SKILL.md 与 sop/*.md）
        ▼
skillmaster-site/public/skill/skillmaster.tgz        构建产物（gitignore，不入库）
        │
        │  site.yml deploy（手动 workflow_dispatch）上传到下载桶
        ▼
skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/skillmaster.tgz
```

- **源在仓库里**：`skills/skillmaster/` 是唯一权威；本机全局目录是它的副本，不是源。
- **打包不改内容**：`build-skill-tgz.py` 只做确定性的 tar+gzip，并校验 `name: skillmaster` 与路径白名单。
- **文件数是一份契约**：安装文档写着「数够 N 个文件」，`site.yml` 三处断言同样写 N；本版把 N 从 8 改成 9，两边必须同时改。

本版动第一格的三个文件，以及契约里的 N。

## 数据模型

本版不新增实体或表。技能包的「数据模型」是它的形状，唯一约束由打包脚本与 workflow 强制：

| 约束 | 强制处 |
|---|---|
| 顶层只能是 `SKILL.md` | `scripts/build-skill-tgz.py` 的 `allowed()` |
| `sop/` 下只能是 `.md` | 同上 |
| frontmatter 的 `name` 必须是 `skillmaster` | 同上的 `frontmatter_name()` |
| 包内恰好 9 个文件 | [`site.yml`](../../../.github/workflows/site.yml) 三处断言 + [安装文档](../../../skillmaster-site/public/install/skillmaster.md) |

包内的 9 个文件：

| 文件 | 本版 |
|---|---|
| `SKILL.md` | 换成全局版；表格补回 `00-model`、`07-files` 两行 |
| `sop/00-model.md` | 新增（全局版） |
| `sop/05-manage.md` | 换成全局版（删除例外与「为什么」指向 `00-model.md`） |
| `sop/03-edit-draft.md` | 保留仓库版（含二进制提示） |
| `sop/07-files.md` | 保留 |
| `sop/01`、`02`、`04`、`06` | 不动 |

## 接口

本版不改任何 HTTP 接口、CLI 命令或控制台行为。对外契约的变化有三处：

| 契约 | 变化 |
|---|---|
| 技能包内容 | `SKILL.md`、`sop/00-model.md`、`sop/05-manage.md` 换成全局版；包内文件数 8 → 9 |
| 安装文档的文件数 | 8 → 9：两处命令、两处说明 |
| 构建与部署的闸 | `site.yml` 三处 `-eq 8` → `-eq 9`；`build-skill-tgz.py` 注释同步 |

错误码、鉴权、状态码：无变化。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 三个技能文件：`SKILL.md`、`sop/00-model.md`、`sop/05-manage.md` | A1 |
| P1 | 计数契约 8 → 9：安装文档、`site.yml`、`build-skill-tgz.py` 注释 | A6 |
| P2 | 本地校验：打包、文件数、frontmatter、链接双向闭合、文档检查 | A2–A5 |
| P3 | 合并后手动 dispatch 站点部署；workflow 断言线上字节与产物一致 | A7（本版未执行） |

**每期明确不做**：P0 不改 `sop/03`、`sop/07`；P1 不改安装话术（只改数字）；P2 不跑站点全量测试与设计检查（本版不触界面）；P3 不由本版触发——部署是发布动作，用户说了才做。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | `invoke` / `read` 的纠错落在哪？ | 优先写进被加载的 `SKILL.md` 正文；CLI 只在「裸地址 + 未 `invoke` + 读 `SKILL.md`」这一形状上加提示；不做服务端禁令 | 单独开版：先定提示还是拒绝、覆盖 `read` / `cat` / `head` / `tail` 中的哪些；记在 [`TODO`](../../current/TODO.md) |
| Q2 | 包形状以后是否稳定在 9？每加一个 SOP 都要同步安装文档与三处闸，这个代价是否可接受？ | 接受：计数能抓住「下载不完整」，比省几处同步更值 | 下一次需要加 SOP 时，重新评估是否把计数从安装文档里去掉 |

## 附录

### 附录 A · 与现有代码的关系

| 文件 | 关系 |
|---|---|
| `skills/skillmaster/SKILL.md` | 换成全局版 + 两行表格 |
| `skills/skillmaster/sop/00-model.md` | 新增 |
| `skills/skillmaster/sop/05-manage.md` | 换成全局版 |
| `skills/skillmaster/sop/03-edit-draft.md`、`07-files.md`、`01`、`02`、`04`、`06` | 保留不动 |
| `scripts/build-skill-tgz.py` | 只改注释里的文件数 |
| `skillmaster-site/public/install/skillmaster.md` | 文件数 8 → 9（两处命令、两处说明） |
| `site.yml` | 三处断言 8 → 9，一处注释同步 |

### 附录 B · 来源与出处

- 全局版正文：用户本机的 `~/.claude/skills/skillmaster/`（2026-11-02 21:32 版本），一手来源。
- 包形状与断言：仓库内的 [`scripts/build-skill-tgz.py`](../../../scripts/build-skill-tgz.py) 与 [`site.yml`](../../../.github/workflows/site.yml)，一手来源。
- 9 个文件的安装口径：本版同步改过的[安装文档](../../../skillmaster-site/public/install/skillmaster.md)。

### 附录 C · 已测量的数据

| 指标 | 口径（定义/样本/是否计入零值） | 数值 |
|---|---|---|
| 包内文件数 | `tar -tzf` 条目中去掉目录；本机 macOS 打包结果 | 见 [`test-plan.md`](test-plan.md) §结果 |
| 构建产物 sha256 | `build-skill-tgz.py` 打印；同一环境两次构建应相同 | 见 [`test-plan.md`](test-plan.md) §结果 |

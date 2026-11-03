# v42-skill-source-sync · 技术设计

> **最后更新**：2026-11-03
> **状态**：实现完成，待合并
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文只链接、不复述理由。

## 架构

本版不改系统架构，只改技能包内容，以及围绕包形状的两份文件数契约（安装文档与 `site.yml`）。相关的既有管线：

```
skills/skillmaster/                                  源：SKILL.md（本版 1 个）
        │
        │  scripts/build-skill-tgz.py（确定性打包；只收 SKILL.md）
        ▼
skillmaster-site/public/skill/skillmaster.tgz        构建产物（gitignore，不入库）
        │
        │  site.yml deploy（手动 workflow_dispatch）上传到下载桶
        ▼
skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/skillmaster.tgz
```

- **源在仓库里**：`skills/skillmaster/` 是唯一权威；本机全局目录是它的副本，不是源。
- **打包不改内容**：`build-skill-tgz.py` 只做确定性的 tar+gzip，按构造只写 `SKILL.md`，并校验 `name: skillmaster` 与目录名。
- **文件数是一份契约**：安装文档写着「数够 N 个文件」，`site.yml` 三处断言同样写 N；本版把 N 从 9 改成 1，两边必须同时改。**包只剩一个文件之后，这份契约最不容易再漂**。
- **命令语法只有一份权威**：`skmr <子命令> --help`，本版把这条写进 `SKILL.md`。删除前的 `sop/` 只给场景与形状；随该目录删除，`--name` 的修正一并作废。
- **SOP 已不在仓库**：`sop/` 于 2026-11-03 整目录删除，`03` 等文件的「只讲远程草稿」等约束随内容将迁往公开指南。
- **分发包只写用户路径**：内部 ADR 编号、维护者历史、API 实现细节、作者提示都不进包；`description` 保留 always-load（用户 2026-11-02 决定）。
- **判据的权威在 `description`**：六种情形、三种例外、反借口只写在技能描述里（默认先查这条判据出自 [ADR 0109](../../decisions/0109-the-rule-block-defaults-to-searching-first.md)）。常驻规则段（[ADR 0102](../../decisions/0102-the-rule-block-ships-with-the-cli.md)）只随 CLI 发布，不承载判据。`SKILL.md` 只写两条分支与四步流程。两处各写一套，迟早会漂——而漂了之后 agent 会拿更宽松的那套。
- **常驻层只做催促**：技能是必须安装的，判据、例外、流程、回复格式的权威全在技能与它的 `description` 里。常驻层只做一件技能做不到的事——**在 agent 还没加载技能、还没有规则可依时，先让它去调用**。因此它只写「任何任务都先调用 SkillMaster 这个 skill，照它的要求做；拿不准就调用」，外加三句反借口（这次很简单 / 手边有现成工具 / 刚才已经加载过）——那三句对应 issue #30387 里「与内置能力重叠的 skill 约 50% 被忽略」那条实测。**它不写判据、例外、回执、命令与格式。**
- **回执不在正文里**：它要求「每次都要发生」，属于常驻层；写在本地 `SKILL.md` 里会随着正文一起被压掉。把它移进常驻规则段的事记在 [`TODO`](../../current/TODO.md) 第 11 条。
- **正文不判断本次是显式还是自动加载**：`/skillmaster` 的确定性来自宿主，正文拿不到可靠的来源标记；所以「检索是第一动作」写成无条件，两种入口都覆盖。

本版只动 `SKILL.md`；`sop/` 整个删除（2026-11-03），分步说明将迁往公开指南。

## 数据模型

本版不新增实体或表。技能包的「数据模型」是它的形状，唯一约束由打包脚本与 workflow 强制：

| 约束 | 强制处 |
|---|---|
| 包里只有一个 `SKILL.md` | `scripts/build-skill-tgz.py` **按构造只写这一个条目**，不再遍历目录 |
| frontmatter 的 `name` 必须是 `skillmaster` | 同上 `build()` 里的 `frontmatter_name()` 校验 |
| 包内恰好 1 个文件 | [`site.yml`](../../../.github/workflows/site.yml) 三处断言 + [安装文档](../../../skillmaster-site/public/install/skillmaster.md) |
| 仓库里也没有 `sop/` | 2026-11-03 删除；分步说明将迁往公开指南，本版不发布它 |

包内只有 1 个文件：

| 文件 | 本版 |
|---|---|
| `SKILL.md` | 本地分发包的全部内容：两条并列分支（对 skill 的操作 / 找一个 skill 来用）+ 四步流程；description 保留 always-load |

`sop/` 已删除，本版不再携带分步说明；它们将迁往公开指南技能，由技能主人在平台上另发（本版不发）。删除前的版本保存在本机 `~/.claude/skillmaster-sop-20261103/`——v42 对它的改动当时尚未提交，git 历史里没有。

## 接口

本版不改任何 HTTP 接口、CLI 命令或控制台行为。对外契约的变化有四处：

| 契约 | 变化 |
|---|---|
| 技能包内容 | 本地包只留 `SKILL.md`，正文是两条并列分支 + 四步流程；包内文件数 9 → 1 |
| 安装文档的文件数 | 9 → 1：两处命令、三处说明 |
| 构建与部署的闸 | `site.yml` 三处 `-eq 9` → `-eq 1`；`build-skill-tgz.py` 从白名单改为按构造只打 `SKILL.md` |
| 常驻规则段 | `block.md` 的文本变了，但**命令面没变**；用户机器上的那份不会自动更新（[ADR 0110](../../decisions/0110-the-block-is-a-paragraph-the-cli-stops-managing.md)），要发版后重跑 `startup` 或手工替换 |

错误码、鉴权、状态码：无变化。命令语法不变；`sop/` 已删除，不再有第二份语法表；参数以 `skmr <子命令> --help` 为准。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 对齐：`SKILL.md`、文件数 9 → 1、删除 `sop/` | A1–A4、A6 |
| P1 | `SKILL.md` 的用户路径与派发结构；分步说明交给公开指南 | A10 |
| P1.5 | 触发契约：`SKILL.md` 写成两条并列分支 + 四步流程；判据留在 `description` | A14、A15、A16 |
| P2 | 本地校验：打包、文件数、frontmatter、文档检查 | A2–A6、A13、A14、A16–A20 |
| P3 | 合并后手动 dispatch 站点部署；workflow 断言线上字节与产物一致 | A7（本版未执行） |

**每期明确不做**：P0 不发布公开指南（本版不发它）；P1 不改安装文档的话术（只改数字）；P1.5 不碰 `sop/`（它已在 P0 删除），不让 `skmr` 记事件（记在 [`TODO`](../../current/TODO.md) 第 9 条），不做多 skill 串联（同文件第 10 条）；P2 不跑站点全量测试与设计检查（本版不触界面）；P3 不由本版触发——部署是发布动作，用户说了才做。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | `invoke` / `read` 的纠错落在哪？ | 优先写进被加载的 `SKILL.md` 正文；CLI 只在「裸地址 + 未 `invoke` + 读 `SKILL.md`」这一形状上加提示；不做服务端禁令 | 单独开版：先定提示还是拒绝、覆盖 `read` / `cat` / `head` / `tail` 中的哪些；记在 [`TODO`](../../current/TODO.md) |
| Q2 | 包形状以后是否稳定在 1？再加文件就要同步安装文档与三处闸，这个代价是否可接受？ | 接受：计数能抓住「下载不完整」，比省几处同步更值 | 下一次需要加 SOP 时，重新评估是否把计数从安装文档里去掉 |
| Q3 | 本机副本什么时候同步？ | 合并 / 安装时由仓库构建的包同步；repo 验收不依赖本机副本 | 安装步骤需要校验副本时，再加一条 diff 用例 |
| Q4 | 正文压到一段之后，还剩多少机会让 agent 想起要查？ | 本版答不了：正文没有回执，逐会话也自证不了 | [`TODO`](../../current/TODO.md) 第 9 条（事件）与第 11 条（回执进常驻层）之后，用固定评测集量「查了吗 / 查对了吗 / 用了吗」 |
| Q5 | 公开库查不到时，本地要不要留兜底？ | 本版不留：本地只有 `SKILL.md`，agent 退到 `--help` 与现场报错，并明说「官方说明没查到」 | 真机上统计「查不到」的比例；超出一个可接受值时，再议是否把最常用的 `sop` 放回本地包 |

## 附录

### 附录 A · 与现有代码的关系

| 文件 | 关系 |
|---|---|
| `skills/skillmaster/SKILL.md` | **两条并列分支 + 四步流程**：对 skill 的操作 → 加载指南；找一个 skill 来用 → 搜索 / 判断 / 规划 / 加载。不带判据、回执、SOP 索引；description 保留 always-load |
| `skills/skillmaster/sop/`（8 个文件） | **整目录删除**（2026-11-03）；内容将迁往公开指南，删除前的一版另存在本机 |
| `scripts/build-skill-tgz.py` | 从白名单遍历改成**按构造只写 `SKILL.md`**；注释同步 |
| `skillmaster-site/public/install/skillmaster.md` | 文件数 9 → 1（两处命令、**三处**说明） |
| `site.yml` | 三处断言 9 → 1，一处注释同步 |
| `skillmaster-cli/internal/prompt/block.md` | 压成一句催促 + 三句反借口；**去掉**判据、例外、回执、失败说明与 `skill write` / `put` / `create` 等逐条写法 |
| `skillmaster-cli/internal/prompt/block_test.go` | 断言同步：一句催促与三句反借口在场、`skill write` / `put` / `create` / `edit` / `skills search` / `invoke` / `read` 零命中、旧命令名仍不出现 |

### 附录 B · 来源与出处

- 全局版正文：用户本机的 `~/.claude/skills/skillmaster/`（2026-11-02 21:32 版本），一次性迁移来源；**从 v42 起，源在仓库**。
- 包形状与断言：仓库内的 [`scripts/build-skill-tgz.py`](../../../scripts/build-skill-tgz.py) 与 [`site.yml`](../../../.github/workflows/site.yml)，一手来源。
- 1 个文件的安装口径：本版同步改过的[安装文档](../../../skillmaster-site/public/install/skillmaster.md)。

### 附录 C · 已测量的数据

| 指标 | 口径（定义/样本/是否计入零值） | 数值 |
|---|---|---|
| 包内文件数 | `tar -tzf` 条目中去掉目录；本机 macOS 打包结果 | 见 [`test-plan.md`](test-plan.md) §结果 |
| 构建产物 sha256 | `build-skill-tgz.py` 打印；同一环境两次构建应相同 | 见 [`test-plan.md`](test-plan.md) §结果 |

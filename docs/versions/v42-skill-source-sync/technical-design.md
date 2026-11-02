# v42-skill-source-sync · 技术设计

> **最后更新**：2026-11-02
> **状态**：实现中
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/README.md)**——本文只链接、不复述理由。

## 架构

本版不改系统架构，只改技能包里的一个文件。相关的既有管线：

```
skills/skillmaster/                                  源：SKILL.md + sop/*.md
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
- **打包不改内容**：`build-skill-tgz.py` 只做确定性的 tar+gzip，并校验 `name: skillmaster` 与路径白名单。它不接受 `sop/` 之外的新文件，也不接受 `SKILL.md` 以外的顶层文件。
- **上线不走代码**：站点 workflow 的 deploy 手动触发；合并只跑检查。安装文档与包一起上传到下载桶。

本版只动第一格的 `SKILL.md`，管线其余部分不变。

## 数据模型

本版不新增实体、表或字段。技能包的「数据模型」就是它的形状，唯一约束由打包脚本强制：

| 约束 | 强制处 |
|---|---|
| 顶层只能是 `SKILL.md` | `scripts/build-skill-tgz.py` 的 `allowed()` |
| `sop/` 下只能是 `.md` | 同上 |
| frontmatter 的 `name` 必须是 `skillmaster` | 同上的 `frontmatter_name()` |
| 包内恰好 8 个文件 | [`site.yml`](../../../.github/workflows/site.yml) 三处断言 + [安装文档](../../../skillmaster-site/public/install/skillmaster.md) |

本版不改变这张表；`00-model.md` 不加入，正是为了保持最后一行。

## 接口

本版不改任何 HTTP 接口、CLI 命令或控制台行为。对外契约的变化只有一处，在**技能包内容**这一层：

| 契约 | 变化 |
|---|---|
| `SKILL.md` 的 frontmatter `description` | 换成全局版的短描述（「任何会话开始时默认强制加载本 skill…」） |
| `SKILL.md` 的正文 | 换成全局版的「什么时候查 / 查询到相关 skill 的调用 / 其它操作」结构 |
| `SKILL.md` 的 SOP 索引 | 删 `00-model` 行、加 `07-files` 行；其余 01–06 指向不变 |
| 包的字节 | 变化；文件名集合不变（8 个） |

错误码、鉴权、状态码：无变化。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 替换 `skills/skillmaster/SKILL.md`，做两处表格编辑 | A1 |
| P1 | 本地校验：打包、文件数、frontmatter、链接双向闭合、文档校验 | A2–A5 |
| P2 | 合并后手动 dispatch 站点部署；workflow 断言线上字节与产物一致 | A6（本版未执行） |

**每期明确不做**：P0 不改 `sop/` 下任何文件；P1 不跑站点全量测试与设计检查（本版不触界面）；P2 不由本版触发——部署是发布动作，用户说了才做。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | `sop/00-model.md` 要不要回归？ | 不回归：模型已内联在 `sop/05-manage.md`，`SKILL.md` 再加一份就是第二个家；加回来还会把包变成 9 个文件 | 若决定回归，同一个版本里改安装文档的文件数、`site.yml` 三处断言与测试；跑一遍部署再确认 |
| Q2 | `invoke` / `read` 的纠错落在哪？ | 优先写进被加载的 `SKILL.md` 正文；CLI 只在「裸地址 + 未 `invoke` + 读 `SKILL.md`」这一形状上加提示；不做服务端禁令 | 单独开版：先定提示还是拒绝、覆盖 `read` / `cat` / `head` / `tail` 中的哪些；记在 [`TODO`](../../current/TODO.md) |

## 附录

### 附录 A · 与现有代码的关系

| 文件 | 关系 |
|---|---|
| `skills/skillmaster/SKILL.md` | 本版唯一改动的产品文件 |
| `skills/skillmaster/sop/01-06.md`、`07-files.md` | 保留不动；07 重新进入 `SKILL.md` 的索引 |
| `scripts/build-skill-tgz.py` | 收紧实现，不改 |
| `skillmaster-site/public/install/skillmaster.md` | 文件数仍是 8，不因本版而改 |
| `site.yml` | 三处 8 文件断言不变 |

### 附录 B · 来源与出处

- 全局版正文：用户本机的 `~/.claude/skills/skillmaster/SKILL.md`（2026-11-02 21:32 版本），一手来源。
- 包形状与断言：仓库内的 [`scripts/build-skill-tgz.py`](../../../scripts/build-skill-tgz.py) 与 [`site.yml`](../../../.github/workflows/site.yml)，一手来源。
- 8 个文件的安装口径：[安装文档](../../../skillmaster-site/public/install/skillmaster.md)，一手来源。

### 附录 C · 已测量的数据

| 指标 | 口径（定义/样本/是否计入零值） | 数值 |
|---|---|---|
| 包内文件数 | `tar -tzf` 条目中去掉目录；本机 macOS 打包结果 | 8 |
| 构建产物 sha256 | `build-skill-tgz.py` 打印；同一环境两次构建应相同 | 见 [`test-plan.md`](test-plan.md) §结果 |

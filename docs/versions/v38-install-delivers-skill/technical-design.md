# v38-install-delivers-skill · 技术设计

> **最后更新**：2026-11-02
> **状态**：已实现（仓库侧）；平台上的 `skillmaster/skillmaster-startup` 尚未创建，T3 / T7 未执行
> **所属版本**：[`README.md`](README.md)
> **架构决策已独立成 [ADR](../../decisions/0129-onboarding-delivers-a-skill-not-a-memory-line.md)**——本文只链接、不复述理由。

## 架构

```
仓库
  skills/skillmaster/            ← 技能的唯一源（SKILL.md + sop/01…06）
        │
        │ scripts/build-skill-tgz.sh（确定性打包）
        ▼
  skillmaster-site/public/skill/skillmaster.tgz
        │
        │ vite build（public/ 原样进产物）
        ▼
  https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/skillmaster.tgz（部署时上传，文档指向这里）

用户机器
  Agent 读 https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/skillmaster.md（www 留同一份）
        │
        │ ① 下载并解包到「本 Agent 自己的技能目录」
        ▼
  <agent>/skills/skillmaster/{SKILL.md, sop/*.md}
        │
        │ ② 技能里的 sop/06-install.md 指引装 CLI
        ▼
  skmr 可用  →  skmr startup（登录 + 导入技能，不再写记忆）
```

**边界**：技能包是**静态文件**，没有新的服务端接口；安装命令仍由 `install.sh` / `install.ps1` 承担（它们由下载桶提供）；`skmr startup` 的 Go 代码本版只改一个常量——它加载并钉住的旅程技能地址（`skillmaster-cli/cmd/skillmaster/startup.go:23`，`const startupSkill = "skillmaster/skillmaster-startup"`，[ADR 0096](../../decisions/0096-guidance-lives-on-the-platform.md)），加载与钉住的逻辑不变；旅程内容在在线技能里，因此 startup 的行为改动**落在在线技能的新版本上**。

**为什么技能源要进仓库**：现在它只存在于草稿目录里，改一处没有 diff、没有评审。进仓库后它与站点产物之间才有「源 → 构建」的关系，可以和 `design/` 的产物校验用同一种纪律。

## 数据模型

本版不涉及表结构变更。技能包不是数据库实体——它是仓库里的源目录加上一个构建产物。

## 接口

### 对外地址

| 方法 | 路径 | 作用 | 鉴权 |
|---|---|---|---|
| `GET` | `https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/skillmaster.md` | 安装旅程与话术定稿（内容本版改写）；`www` 上留同一次构建的副本 | 公开 |
| `GET` | `https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/skillmaster.tgz` | 技能包（本版新增；站点部署时上传到这个桶） | 公开 |
| `GET` | `https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/install.sh`、`…/install.ps1` | 现有安装脚本，不变 | 公开 |

**技能包为什么不放 `www`**：那个名字会在 ClientHello 与 ServerHello 之间重置只用 TLS 1.2 的客户端（macOS 自带 curl、部分 Windows PowerShell，见 [`deploy/README.md`](../../../deploy/README.md) §TLS），而下载它的是用户自己的 `curl`。桶与安装脚本同一个（`beta/` 前缀），地址在附录 C 记着。站点构建仍产出 `public/skill/skillmaster.tgz`，部署时由 `site.yml` 上传并回读校验；`www` 上那份只是同一次构建的副本，文档不指向它。

### 技能包的形状（契约）

```
skillmaster/
├── SKILL.md          frontmatter: name=skillmaster, description=（查与调两步 + 「其它操作先加载正文」）
└── sop/
    ├── 01-create.md
    ├── 02-new-draft.md
    ├── 03-edit-draft.md
    ├── 04-upload.md
    ├── 05-manage.md
    └── 06-install.md
```

- 顶层必须是一个名为 `skillmaster/` 的目录（解包后直接落进技能目录，不套两层）。
- `name` 必须与目录名一致（Agent Skills 规范要求）。
- 包内所有相对链接必须可解析（`SKILL.md` → `sop/*.md`，`01`/`02` → `03`）。

### 安装文档的流程（契约）

| 步 | 做什么 | 关键约束 |
|---|---|---|
| 0 | 检查两个前提 | **静默判断，发生在任何输出之前**：① 这个 Agent 能装技能；② 这台电脑能装客户端（64 位；Windows 10 1803+，自带 `curl` 与 `tar`）。**两个都满足才往下走**；任一不满足只说对应话术（22 / 16）、给选项卡并结束，不走欢迎与确认 |
| 1 | 欢迎 + 请求确认 | **唯一一处问「要不要做」**；拒绝后可以再问一次（话术第 3 行），仍拒绝即结束，未做任何改动 |
| 2 | 装技能 | **用户级目录优先**；只有项目级时装到当前项目，并补第 24 行。目录由 Agent 自己判断，流程不给路径表。**按客户端分两支**：能直写 → 下到临时目录、数够 7 个文件再替换；只能界面导入 → 解到临时目录并请用户导入。判断不出就不装、说第 23 行、给选项卡 |
| 3 | 确认客户端安装状态 | 四种结果对应话术第 10–15 行；**修好 PATH 之后要重新判断「是不是最新」**，再决定说 12 还是 13 |
| 4 | 装客户端 | **只告知，不确认**；报错按附录 A 说对应那一行，并按那一行说的重试或停下；没列到的按第 20 行 |
| 5 | 交棒 `skmr startup` | 登录与迁移本机技能由它负责；安装文档不替它解释 |

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 技能源进仓库、构建脚本、tgz 上传到下载桶、安装文档改写、创建并发布 `skillmaster/skillmaster-startup` | prd §验收 全部 12 条 |

**P0 明确不做**：技能自动更新、Windows 真机验证、`skmr prompt` 的删除。

## 开放问题

| # | 问题 | 当前倾向 | 怎么回答 |
|---|---|---|---|
| Q1 | `skillmaster.tgz` 提交进仓库，还是部署时构建？ | **已定：构建时生成，不进仓库。** 站点 `npm run build` 先跑 `scripts/build-skill-tgz.sh` 写进 `public/skill/`，再由 vite 带进 `dist/`；`.gitignore` 拦着产物。二进制产物的 diff 没有意义，提交进去等于多一个会忘的副本 | 已落实在 `skillmaster-site/package.json`；两次构建同哈希见 test-plan T5 |
| Q2 | Windows 上怎么解包？ | 先 `curl.exe -fsSL -o` 下载成文件，再 `tar -xzf` 解包；**不要用管道**——PowerShell 5.1 在两个原生程序之间传二进制会损坏文件，且 `curl` 是 `Invoke-WebRequest` 的别名。文件数核对用 `Get-ChildItem`。Windows 10 1803 起自带 `curl.exe` 与 `tar`（bsdtar）。**未真机验证** | 有 Windows 机器时装一次，按 test-plan T12 记录 |
| Q3 | Agent 判断错技能目录怎么办？ | **不设兜底**：判断不出来就不装（话术第 23 行），不问用户——用户不知道路径。判**错**了（自信但错）没有护栏，用户看到的是技能不出现，重走安装即可；这一条如实记在 ADR 的后果里 | 在 Claude Code 上跑一遍，观察它报的路径与落到哪 |
| Q4 | `skmr prompt` 还有没有人用？ | 本版不删；先看 startup 新版上线后有没有调用方 | 全库搜 `skmr prompt`，零调用则下一版删 |
| Q5 | 技能包要不要带版本号？ | 暂不带；更新走「重走安装」，版本判断放在下一版 | 设计更新机制时一并定 |
| Q6 | 「所有客户端」里，实际验过哪几个？ | 本版只在 **Claude Code** 上验；其余客户端按文档的三支分支处理，未证伪也没证真 | 有其它客户端时各跑一次「装技能 → 装 CLI」；文档分支不改，只补验证记录 |
| Q7 | 平台上的 `skillmaster/skillmaster-startup` 什么时候创建？ | **本版先交仓库侧**，技能由产品负责人在平台上创建并发布旅程正文。**在它上线之前，`skmr startup` 会 not_found**——CLI 的常量已经指向它 | 创建后按 test-plan T3 / T7 补测 |
| Q8 | 安装文档本身还在 `www`，会不会也撞上 TLS 1.2 重置？ | **已处理（2026-11-02，ICP 未过期间的临时办法）**：文档与技能包一起上传到下载桶，首页那句安装指令指向 `beta/skillmaster.md`；`www` 上的仍是同一份。ICP 通过后可以指回 `www`，也可以一直用桶 | ICP 通过时重新评估 |

## 附录

### 附录 A · 与现有代码的关系

| 对象 | 本版动作 |
|---|---|
| `skillmaster-cli/cmd/skillmaster/startup.go` | **只改一处**——`startupSkill` 常量改为 `skillmaster/skillmaster-startup`；加载与钉住的逻辑不变 |
| `skillmaster-cli/install/install.sh` / `install.ps1` | **不改** |
| `skillmaster-site/public/install/skillmaster.md` | **重写**流程与话术定稿 |
| `skillmaster-site/public/skill/skillmaster.tgz` | **新增**（构建产物） |
| `skills/skillmaster/` | **新增**（技能源） |
| `scripts/build-skill-tgz.sh` | **新增**——确定性打包的入口（可由站点 `npm run skill` 或直接调用） |
| `scripts/build-skill-tgz.py` | **新增**——打包本身：排序、固定 mtime / 属主 / 权限、ustar、gzip 头 mtime 0 |
| 在线技能 `skillmaster/skillmaster-startup` | **创建并发布**（平台上还没有这个地址）：去掉写记忆的分支 |

### 附录 B · 来源与出处

- 技能加载机制与 frontmatter 字段：Claude Code 官方文档 `code.claude.com/docs/en/skills`（一手）。
- Agent Skills 规范（`name`/`description` 约束、渐进式披露）：`agentskills.io/specification.md`（一手）。
- 旅程技能的归属：[ADR 0096](../../decisions/0096-guidance-lives-on-the-platform.md)（仓库内一手）。
- 「全局记忆 vs 技能描述」的对照实验：本机无头会话实测（二手转一手：数据产物在实验目录，见附录 C 口径）。

### 附录 C · 已测量的数据

| 指标 | **口径**（定义/样本/是否计入零值） | 数值 |
|---|---|---|
| 记忆 vs 技能描述的触发率 | 本机 `deepseek-v4-flash`；每格 3–8 次；判据为「会话里是否出现 `skmr skills search`」；闲聊不计入 | 记忆 23/25、描述=全文 24/25、描述=门铃 23/25、都不放 3/15 |
| 任务中途换领域再查 | 两轮会话（第二轮 `--resume`）；轮 1 加载 coding 技能；轮 2 换审计 | 换领域 3/3 再查并调用 `lijinzhao/code-audit`；同领域对照 0/2 不查 |
| 补集操作加载正文 | 改写措辞前后各 3 场 | 改写前 1/3，改写后 2/3；两版任务答对都是 3/3 |

> 口径不写清的数字等于没测过。以上数据**只来自一个模型**，没有在 Claude 模型上验证。

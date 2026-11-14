# v71-onboarding · 技术设计

> **最后更新**：2026-11-14
> **所属版本**：[`README.md`](README.md)
> **状态**：已实现（源与安装文档），待发布与真机走查

## 架构

**一句话**：这一版是内容与安装话术的改动，不新增代码路径；引导正文住在平台上的 `skillmaster/skillmaster-startup`，安装文档只负责「问一次」和「交棒」。取舍理由见 [ADR 0206](../../decisions/0206-startup-is-the-onboarding-and-joining-is-the-default.md)，这里只写形状。

| 组件 | 拥有什么 | 这一版改什么 |
|---|---|---|
| 安装文档 [`skillmaster-site/public/install/skillmaster.md`](../../../skillmaster-site/public/install/skillmaster.md) | 安装步骤与话术定稿 | 新增「问引导」与「交棒」分支；话术表加三行 |
| 引导正文 `skills/skillmaster-startup/` → 平台 `skillmaster/skillmaster-startup` | 3 块引导的正文 | **改写**：① 找 + 加进库 + 用一次；② 做一条自己的 + 发布；③ 可选批量搬本机技能 |
| `skmr startup` [`skillmaster-cli/cmd/skillmaster/startup.go`](../../../skillmaster-cli/cmd/skillmaster/startup.go) | 加载并钉住正文；桌面上没有凭据会自动拉起登录，沙箱里没有浏览器才拒绝 | **不改**：常量 `startupSkill` 不变 |
| 主 skill 包 `skills/skillmaster/SKILL.md` → `skillmaster-site/public/skill/skillmaster.tgz` | 安装时交付的入口技能 | 加一行「第一次用怎么走」；重建 tgz |
| 服务端 / 控制台 / 其它 CLI 命令 | 搜索、库、导入、草稿、上线、加载 | **不改** |

**数据流**

```text
安装文档
  → 确认登录（现有第 5 步）
  → 问「要不要做新人引导」
      ├─ 以后再说 → 安装结束
      └─ 现在开始 → skmr startup
                      → CLI 取 skillmaster/skillmaster-startup 正文（钉住这一版）
                      → 第 1 块：读 onboarding/01–02；公开库找 → join → 用户原话搜自己的库 → invoke
                      → 第 2 块：读 onboarding/03–04；从头写或本机导入 → check → 网页确认上线一次 → 搜索
                      → 第 3 块（可选）：读 import/01–04；批量搬本机技能
```

**边界**

- **安装文档不写引导步骤**，引导正文不写安装步骤；两者唯一的交点是 `skmr startup`。
- **引导正文只写它用到的命令**；出错时的排查指向 `skillmaster/skillmaster-guide`，不在两处各写一份。开场和收尾都告诉用户「别的功能可以加载使用指南」。
- **服务端不感知「引导」**：它只看到 search / join / submit / create / write / check / publish / invoke / list / pending / versions。
- **不落进度**：不新建表、不写本地状态文件；已完成的事实是「库里能看到、线上能用」。

## 数据模型

无新表、无新实体、无新本地状态文件。引导的「进度」按实际状态重算（[ADR 0106](../../decisions/0106-the-checklist-is-recomputed-not-recorded.md)）：用户库里有没有那条 skill、线上有没有那一版，就是进度。

## 接口

**无新 HTTP 接口、无新 CLI 命令。** 引导只用现有命令；它们的契约不变，下面是正文必须依赖的部分：

| 块 | 引导里的动作 | 命令 | 正文必须处理的失败 |
|---|---|---|---|
| 1 | 搜公开库 | `skmr skills search "<原话>" --scope public --limit 5` | 搜不到 → 用「做 PPT」兜底；仍搜不到 → 如实说，不空手结束 |
| 1 | 加进自己的库 | `skmr skill join <地址> --yes` | 凭据 / 网络 / 已下线 → 说原因与下一步，不假装成功 |
| 1 | 自己库搜索（用户原话） | `skmr skills search "<原话>"` | 搜不到 → 换成 skill 名字再搜；仍没有用 `skills list` 找到它 |
| 1 | 加载刚加的 | `skmr skill invoke <地址> --yes` | 失败 → 用 `skill read` 读文件 |
| 2 | 从头建 | `skmr skill create <名字> --description <描述>` | 名字不合法 / 被占用 → 换名字 |
| 2 | 写正文（从头写） | `skmr skill read` → `skmr skill write --overwrite` | 先读再写；写失败 → 说清停在哪 |
| 2 | 从电脑上导入 | `skmr skill submit <目录> --name <名字>` | 目录里没有 `SKILL.md` / 名字被占用 → 换一条或换名字 |
| 2 | 检查 | `skmr skill check <地址>@<版本码>` | 断链 / 引用失败 → 先修；上线前退出码必须是 0 |
| 2 | 上线 | `skmr version publish <地址>@<版本码>` | 打不开网页 → 用 `--print` 打印地址，请用户在任何浏览器确认；不能替用户点 |
| 2 | 搜索验证 | `skmr skills search "<名字>"` | 搜不到 → 核对是否上线、是否在自己的库里 |
| 3 | 批量搬本机技能 | `skmr skill submit`（逐条，沿用 import 流程） | 见 `import/*.md` 的失败表 |

**为什么不在正文里做 `--json`**：这一版的消费者是 Agent 与人，正文里给的是人话与固定命令；诊断细节由命令自己的 stderr 承担。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | 仓库源、安装文档、平台 `skillmaster/skillmaster-startup@2.0.0`、guide patch、主 skill 包、ADR 与 TODO | 验收 1–6、8–10 |
| P1 | 5 位首次使用者走查；按结果改措辞、兜底与错误分支 | 验收 7、指标 |

P0 不做完不进入 P1：P1 的走查对象是 P0 发布后的正文，不是草案。

## 附一：引导正文的文件结构

```text
skills/skillmaster-startup/
├── SKILL.md              # 3 块总览、说话方式、路由、收尾、底线
├── onboarding/
│   ├── 01-find.md        # 第 1 块：搜公开库；兜底「做 PPT」
│   ├── 02-join.md        # 第 1 块：加进库；解释库；用户原话搜自己的库并加载
│   ├── 03-create.md      # 第 2 块：从头写 / 从电脑上导入
│   └── 04-publish.md     # 第 2 块：检查 → 上线一次 → 搜索验证
└── import/               # 第 3 块：从平台 1.0.0 搬入；流程不改，只改节引用
    ├── 01-find.md
    ├── 02-upload.md
    ├── 03-publish.md
    └── 04-cleanup.md
```

**SKILL.md 骨架**

1. frontmatter：`name: skillmaster-startup`；`description` 与 `when_to_use` 改成「新人引导」，不再是「迁移本机技能」。
2. 说话方式：沿用 1.0.0 的四条（称「你」、一句话一个意思、不出现技术词、给选项必带推荐），并加第五条：**能用卡片就用卡片**——凡是需要用户表态的地方都出 Agent 自己的选项卡，每张卡片带一个推荐项（放第一个、写「（推荐）」）；要他说需求时给 2–3 个例子当卡片，最后放「我自己说」。目标是用户一路点卡片走完主线。卡片问句要自带判断所需的信息（名字、数量、页面上该看到什么），选项写「点下去会发生什么」。
3. 三块总览：每块一句「用户得到什么」，并指向对应文件；主线（第 1–2 块）约 10 分钟，第 3 块时间另算。
4. 直接迁移的入口：用户说「我只想把电脑上的技能传上来」→ 跳到第 3 块；如果用户已经这么说了，第 3 块不再问一遍。
5. 收尾：走完主线 / 中途停下 / 只做了迁移，三种情况各有固定话术；都提一句「别的功能可以加载使用指南」。
6. 收尾前的一节：确认这个 Agent 实际加载的目录里有 `skillmaster`；没有就先问一次，同意才按安装说明第 0 步判定、第 2 节装。只在主线走完时做；拒绝或装不上，收尾不提 `/skillmaster`。
7. 底线：不替他决定、不播报过程、不删他的技能；另有一份「不要做的事」清单。

**每步文件的骨架**：`这一步做什么` / `什么时候做` / `做完的标志` / `步骤（含照说的话）` / `出问题怎么办` / `下一步`。与现有 `import/*.md` 同一形状，Agent 不需要学第二套读法。

**`import/*` 的来源**：从平台 `skillmaster/skillmaster-startup@1.0.0` 取（`skmr skill get skillmaster/skillmaster-startup@1.0.0 --all --into skills/skillmaster-startup`），导出时带的 `.skillmaster/` 不入库。`SKILL.md` 整体改写；四个 import 文件的流程不改，只把对旧 `SKILL.md` 的节引用改成「第三块」与「收尾」。

## 附二：安装文档的改动点

| 位置 | 改成 |
|---|---|
| 文件头「它按顺序做五件事」 | 「六件事」：加「**问一次要不要做新人引导**」 |
| 「话术定稿」表 | 新增 27–29 行（见下）；固定选项卡表加一组 |
| 「整个流程有两处安装决策」 | 改成三处：要不要安装、要不要现在配置沙箱、**要不要现在做新人引导** |
| 「5 · 交棒」 | 登录 `exit 0` 后先问第 27 行；选「现在开始」说第 28 行再运行 `skmr startup`；选「以后再说」说第 29 行并结束；`exit 1/4` 的分支拿到 0 后回到「退出码 0」 |
| 文末「到这一步安装就结束了」 | 保留总起句，改成按「是否运行 `startup`」分支收尾 |

**新增话术**

| # | 什么时候用 | 照说 | 选项卡 |
|---|---|---|---|
| 27 | 登录确认后，问引导 | 客户端装好了，也登录好了。接下来要不要花 10 分钟，我带你走一遍 SkillMaster 怎么用？ | `现在开始（推荐）` / `以后再说` |
| 28 | 选「现在开始」 | 好，我们开始。 | — |
| 29 | 选「以后再说」 | 好，这次就先不走一遍了。你已经能用了；以后想走一遍，跟我说「带我走一遍 SkillMaster 的新人引导」，或运行 `skmr startup`。 | — |

**为什么问在登录之后**：桌面上没有凭据时 `skmr startup` 会先拉起浏览器登录，沙箱里没有浏览器才拒绝；先登录，选「现在开始」的人可以直接进入引导，不需要再补一次登录。

## 附三：发布与仓库改动

| 交付物 | 怎么做 | 怎么验 |
|---|---|---|
| 引导正文源 | 新增 `skills/skillmaster-startup/`；`.gitattributes` 加 `skills/skillmaster-startup/** -text`；导出时带的 `.skillmaster/` 不入库 | `git diff --check`；行尾固定 |
| 平台 `skillmaster/skillmaster-startup` | `skmr skill submit skills/skillmaster-startup --to skillmaster/skillmaster-startup --name <版本名> --release major` → `skmr skill check` → 网页上线 | 平台版本 2.0.0 在线；`skmr startup` 取到新正文 |
| 指南 `skillmaster/skillmaster-guide` | 改 `skills/skillmaster-guide/start/agent.md`；`skmr skill submit skills/skillmaster-guide --to skillmaster/skillmaster-guide --release patch` → 网页上线 | guide 不再说 startup 是迁移；完成信号是三块主线 |
| 主 skill 包 | 改 `skills/skillmaster/SKILL.md`，跑 `scripts/build-skill-tgz.sh`；站点发布时上传 | `skillmaster-site/public/skill/skillmaster.tgz` 与源一致 |
| 安装文档 | 改 `skillmaster-site/public/install/skillmaster.md`；随站点部署到下载桶 | 安装说明页的文本与仓库一致 |
| TODO | 把「新人旅程」里被本版覆盖的部分搬进 PRD，条目改成只剩沙箱与 `secret set` | `docs/current/TODO.md` 不再声称搜索/上线/调用未做 |

**发布顺序（发布人照这个做）**：

1. 平台上 `skillmaster/skillmaster-startup@2.0.0` 先上线。
2. `skillmaster-guide` 的 patch 上线。
3. 重建主 skill 包、部署安装文档到下载桶。
4. 用一个干净账号跑 `skmr startup`，确认取到 2.0.0 正文；再开始 P1 走查。

**反过来做，用户会在安装后加载到旧的 1.0.0 正文。**

**不新增 `changed-paths.sh` 规则**：`skills/` 已经在 site 的匹配里；`skmr startup` 的 Go 代码没改，CLI 不需要因这份正文触发。主 skill 包由 site 构建，也在同一条匹配里。Guide 的改动会触发 CLI 的 help 测试（`go test ./cmd/skillmaster -run TestHelp`）。

## 开放问题

1. **沙箱里打不开上线确认页时，怎么判定「打不开」？** 倾向：先正常运行 `skmr version publish`；如果命令说「没能自动打开浏览器」（文本模式只有这句，`opened=false` 只在 `--json` 里），就改用 `--print` 打印地址，请用户在任何浏览器里确认。待在一个沙箱客户端里真机验一次。
2. **老用户跑 `skmr startup` 时，怎么不让他白走前两块？** 倾向：SKILL.md 开头写一句「如果你只想把电脑上的技能传上来，直接说，我跳到第三块」；不额外加一个问题。待用一个已有技能的老账号走一次。
3. **第 2 块从头写时的名字怎么定？** 倾向：Agent 提议一个 ASCII 名（服务端只收小写字母、数字和连字符，中文后缀会 `invalid_name`）；冲突就加数字；用户可改。从电脑上导入的版本名同理。待用一个已存在的名字测一次失败提示。
4. **10 分钟中位时长没有历史样本**：「大约 10 分钟」是设计目标，不是实测；P1 用 5 位首次使用者计时，记录起止与是否完成，超出就改文案。
5. **兜底 skill 是否一直可复制？** 2026-11-14 用 `skmr skill ls skillmaster/baoyu-slide-deck` 验证过有浏览档；上线前发布人再验一次。第 1 块只 `join`，不复制，所以即使档位收紧，join 仍可用；若它下线，换一条同为公开的官方 skill。

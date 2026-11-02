# 待办（跨版本）

> **最后更新**：2026-11-02
> **这一份是什么**：仓库里待办的**唯一权威**。项目记忆里的 `project_*_backlog` 是种子与背景，条目以本文件为准。
> **怎么维护**：开版时把相关条目搬进那一版的 `prd.md`（见 [`versions/`](../versions/README.md)）§范围；交付后从本文件删掉，历史留在那个版本的 `README.md` 与 `test-plan.md`。新加一条时写清四件事：**是什么、为什么现在不做、下一步卡在哪、记于何时**。

## 待开版

| # | 待办 | 是什么与下一步 | 相关 | 记于 |
|---|---|---|---|---|
| 1 | **目录成为一等对象** | `mkdir`、目录级 `mv/rm/cp`（Linux 语义），空目录进 digest。改造跨数据模型、zip 导入、`skill download`、`version diff` 与控制台文件树；需先一条新 ADR 推翻 v32「不做 2」。 | [v34 PRD 不做 3](../versions/v34-file-toolbox/prd.md) · [v39 PRD 不做 9](../versions/v39-media-files/prd.md) | 2026-10-30 |
| 2 | **`draft create` 新建草稿** | 建一版草稿（默认从线上版本起稿），不改 skill 内容；`write` / `edit` / `put` 不再以 `@new` 建草稿。阻塞在模型：一版至少要有一个文件，且 `UNIQUE (skill_id, digest)` 让「与底稿同内容的草稿」插不进去。下一步：先写新 ADR 取代 [ADR 0064](../decisions/0064-a-draft-may-change-a-published-version-may-not.md) §5 的半条。 | [v39 PRD 不做 16](../versions/v39-media-files/prd.md) · [v39 TD Q8](../versions/v39-media-files/technical-design.md) | 2026-11-02 |
| 3 | **命名空间成员可写** | 发布只落个人命名空间，官方技能要在两个账号间切换。服务端今天在 `CreateSkillUseCase.java:79`、`SubmitSkillVersionUseCase.java:111` 用 `personalNamespaceOf`；`namespace_member` 表存在但不参与授权。下一步：新 ADR（推翻「只落个人命名空间」）→ 服务端按成员角色授权 → 控制台命名空间切换与成员管理（先出设计稿）→ CLI 目标命名空间。 | [target/README.md §三 第 9–10 行](../target/README.md) · `skillmaster-server/src/main/java/com/skillmasterai/usecase/` | 2026-11-02 |
| 4 | **多账号切换** | 同一浏览器只有一份会话；授权页不显示当前账号；CLI 只有一份凭据。下一步：授权页写「以 `<handle>` 的身份授权」并给「换一个账号」；控制台多账号切换；CLI 多份凭据（profile），或与上一条「命名空间成员可写」合并解决。 | `skillmaster-console/src/pages/CliSignInPage.tsx` · `skillmaster-cli/internal/credentials/` | 2026-11-02 |
| 5 | **不重复造草稿** | 更新已有 skill 时，agent 每轮 `submit` 都新建草稿；应先查未上线草稿，有就用 `skill edit` 就地改。用户已有方案，等讨论；在那之前不改 CLI，也不写规则。 | 同 #2（草稿模型） | 2026-11-02 |
| 6 | **公共 skill 加载先经同意** | 公开 skill 调用前由 CLI 拦截、征得同意一次；同意后 `skill join` 收进库，自己的库不问。不改 [ADR 0048](../decisions/0048-visibility-hands-out-a-rung.md) 的权限模型；可能要在 detail 响应加一个只读字段（`SearchResponse.Card.source` 只出现在搜索结果里）。待定：`read` / `cat` / `grep` 是否一起拦。 | [ADR 0048](../decisions/0048-visibility-hands-out-a-rung.md) | 2026-10-27 |
| 7 | **CLI 自动更新** | 启动时查 `dl.skillmasterai.com/version.txt`，远端更新则后台下载、SHA-256 核对、原子替换；24h 节流、跳过 `--help` / `--version`、`SKILLMASTER_AUTO_UPDATE=0` 关闭；`skmr update [--check]`；`410 api_generation_retired` 立即提示重装。触发逻辑已对齐；还缺 CLI 的 `dl.` 编译期默认值。 | [ADR 0099](../decisions/0099-the-cli-has-a-beta-channel.md) | 2026-10-27 |
| 8 | **skill 加载与读取的区分** | `smkr skill read` 只读文件、不钉版本；`invoke` 才加载并钉版本。下一步：把这条写进 skillmaster 的 `SKILL.md` 正文；再定 CLI 是否在「裸地址 + 未 `invoke` + 读 `SKILL.md`」时提示或拒绝（`read` / `cat` / `head` / `tail` 共用文件路由）。服务端不做禁令。 | [v42 TD Q2](../versions/v42-skill-source-sync/technical-design.md) · `skillmaster-cli/cmd/skillmaster/read.go` | 2026-11-02 |

## 版本里的技术欠账

| 版本 | 欠账 | 记录处 |
|---|---|---|
| v39-media-files | P3：整包导入流式化（解除 128 MiB 临时上限）、CLI `download` 流式落盘（解除 17 MiB 限制）、blob 回收清扫器；P0–P2 的验收用例多数尚未执行。 | [README](../versions/v39-media-files/README.md) · [test-plan](../versions/v39-media-files/test-plan.md) |
| v38-install-delivers-skill | 真机验收 T3 / T7 / T4 / T8–T11 / T12（Windows）/ T13。平台技能 `skillmaster/skillmaster-startup@1.0.0` 已在线，T3 / T7 的阻塞已解除，但用例本身仍未跑。 | [test-plan §结果](../versions/v38-install-delivers-skill/test-plan.md) |
| v1-hosting | `PATCH` 元数据、`/inner/**` 独立端口、`/oauth/token` 与 `/oauth/revoke` 限流、RFC 9728 资源元数据。 | [README 状态行](../versions/v1-hosting/README.md) |

**分工**：`待开版` 只放还没进任何版本 `prd.md` 的条目；已经进了某一版的，从这一节删掉，在那版里维护。`版本里的技术欠账` 是已经开版、但还没收口的条目，收口后一并删掉。

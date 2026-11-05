# 待办（跨版本）

> **最后更新**：2026-11-05
> **这一份是什么**：仓库里待办的**唯一权威**。项目记忆里的 `project_*_backlog` 是种子与背景，条目以本文件为准。
> **怎么维护**：开版时把相关条目搬进那一版的 `prd.md`（见 [`versions/`](../versions/README.md)）§范围；交付后从本文件删掉，历史留在那个版本的 `README.md` 与 `test-plan.md`。新加一条时写清四件事：**是什么、为什么现在不做、下一步卡在哪、记于何时**。**别处引用这里的条目时写标题，不写序号**——删条目后编号会前移，序号引用会静默指错。

## 待开版

| # | 待办 | 是什么与下一步 | 相关 | 记于 |
|---|---|---|---|---|
| 1 | **目录成为一等对象** | `mkdir`、目录级 `mv/rm/cp`（Linux 语义），空目录进 digest。改造跨数据模型、zip 导入、`get --all`、`version diff` 与控制台文件树；需先一条新 ADR 推翻 v32「不做 2」。 | [v34 PRD 不做 3](../versions/v34-file-toolbox/prd.md) · [v39 PRD 不做 9](../versions/v39-media-files/prd.md) | 2026-10-30 |
| 2 | **命名空间成员可写** | 发布只落个人命名空间，官方技能要在两个账号间切换。服务端今天在 `CreateSkillUseCase.java:79`、`SubmitSkillVersionUseCase.java:111` 用 `personalNamespaceOf`；`namespace_member` 表存在但不参与授权。下一步：新 ADR（推翻「只落个人命名空间」）→ 服务端按成员角色授权 → 控制台命名空间切换与成员管理（先出设计稿）→ CLI 目标命名空间。 | [target/README.md §三 第 9–10 行](../target/README.md) · `skillmaster-server/src/main/java/com/skillmasterai/usecase/` | 2026-11-02 |
| 3 | **多账号切换与退出登录** | 网页和 CLI 各有一份互不相干的状态，谁也不告诉对方：网页 `/logout` 只清浏览器会话（`WebSession.clear`），CLI 的 `logout` 只清本机凭据、从不撤销服务端（[ADR 0038](../decisions/0038-default-credential-is-an-api-key.md) §决定 7）。于是换个账号实际要「网页退 + CLI 退」——其中 **CLI 那一次是白做的**（`login` 本来就会替换并撤销旧的），而**只退网页时 CLI 仍以老账号工作**、没有任何提示；只退 CLI 时，授权页不显示当前账号、也没有「换一个账号」，会静默地把凭据又发给同一个账号。下一步：①授权页写「以 `<handle>` 的身份授权」并给「换一个账号」（纯前端，先出设计稿）；②控制台的多账号切换；③CLI 多份凭据（profile），或与「命名空间成员可写」合并解决。①②③ 落地之前，更换账号仍须两边各退一次。 | `skillmaster-console/src/pages/CliSignInPage.tsx` · `skillmaster-cli/cmd/skillmaster/auth_logout.go` · `skillmaster-server/src/main/java/com/skillmasterai/api/WebAccountController.java` · `skillmaster-cli/internal/credentials/` | 2026-11-02（2026-11-03 补「退出登录」这一面） |
| 4 | **CLI 自动更新** | 启动时查 `dl.skillmasterai.com/version.txt`，远端更新则后台下载、SHA-256 核对、原子替换；24h 节流、跳过 `--help` / `--version`、`SKILLMASTER_AUTO_UPDATE=0` 关闭；`skmr update [--check]`；`410 api_generation_retired` 立即提示重装。触发逻辑与更新实现已在 v25 落地；`UpdateBase` 的 `dl.` 编译期默认值随 v43 落地。剩下的是 `410 api_generation_retired` 的重装提示（两侧都尚未实现）。 | [ADR 0099](../decisions/0099-the-cli-has-a-beta-channel.md) | 2026-10-27 |
| 5 | **会话内的检索 / 调用痕迹** | 回执现在靠 agent 引 `skmr skills search` 的打印原文，可核对但**不可聚合**——量不出「查了吗 / 查对了吗 / 用了吗」这三个数，于是每次改提示词都无法证伪。下一步：先定事件形状（query、命中数、invoke 地址、outcome）与落盘位置（会话级文件 vs 服务端上报）；涉及隐私，先问过用户。 | [research/agent-skill-triggering.md §五 杠杆 7](../research/agent-skill-triggering.md) · [v42 SKILL.md](../../skills/skillmaster/SKILL.md) | 2026-11-02 |
| 6 | **多 skill 的声明式串联** | 在 `SKILL.md` 里用散文要求「某一步调用另一个 skill」会断在三处：目标对调用者不可见、没有版本约束、没有失败语义；回执也从列表变成一棵树报不准。下一步：先在 frontmatter 定依赖字段（地址 + 可选版本），再定校验时机（发布闸校验存在 / 可见 / 无环，或 `invoke` 时提示缺哪个）。今天没有任何 skill 声明过依赖。 | [research/agent-skill-triggering.md §一](../research/agent-skill-triggering.md) · [ADR 0048](../decisions/0048-visibility-hands-out-a-rung.md) | 2026-11-02 |
| 7 | **「查完报一行」写进常驻规则段** | 本版把本地 `SKILL.md` 收成派发版之后，回执在正文里没有位置了；它要求「每次发生」，属于常驻层。下一步：在 `skillmaster-cli/internal/prompt/block.md` 加一句「查完回报一行：用了哪个 / 没匹配」，随 CLI 发版生效，并靠重跑 `startup` 或手工替换同步用户文件里已写下的那份。 | [v42 PRD A20](../versions/v42-skill-source-sync/prd.md) · `skillmaster-cli/internal/prompt/block.md` | 2026-11-03 |
| 8 | **「审计一个 skill」没有对应的 skill** | 每次审阅都要临时写提示词、临时决定查哪几面（事实对不对、结构合不合规则、有没有重复、有没有内部泄漏），结论无法复用，换个 agent 又从头来。2026-11-03 审 `skillmaster-guide` 时两轮共抓出 27 条，其中 7 条是「照着做会失败」的事实错误（回滚参数传反、`submit` 幂等被写成「永远新开」、设了 `SKILLMASTER_API_KEY` 时 `login` 换不掉账号……）——正是这套检查清单值得固定下来的证据。**下一次审阅的第一步本该是 `skmr skills search` 找它，而不是现场写提示词**。下一步：把 [`.skill-authoring/rules.md`](../../.skill-authoring/rules.md) 的判据、两类审阅视角（拿代码核事实 / 按规则查结构）与输出格式做成一个 skill。卡在两处：① 规则文件只覆盖「文档怎么写」，不含「怎么核事实」的流程；② 适用范围没定——只审我们自己写的 skill，还是也对用户开放。 | `.skill-authoring/rules.md` · `skillmaster/skillmaster-guide` | 2026-11-03 |
| 9 | **部署链路的两处欠账** | ① `server.yml` 的 main 分支保护里**一条必需检查都没配**，`Test` 红了也能合。要配成必需检查，但得先解决「`paths` 过滤 × 必需检查」的死锁：只改文档的 PR 不会触发 `Test`，检查会永远停在 Expected，谁都合不进去。解法二选一——加一个总会跑的占位 job，或改用 ruleset 的 required workflow。② `Deploy` **跳过 Test/Build、自己重新打 jar**，对同一 sha 的 CI 结论一无所知；要改成下载 `Build` 的 artifact，没有同 sha 的绿色 `Build` 就直接失败。console / site 的 `Deploy` 同样是自己重新构建。眼下的人肉纪律：**合并后等 `Build` 绿了再 dispatch**（2026-10-26 那次合并 13 秒就 dispatch，生产先于 CI 更新）。 | [`.dev-sop/release/server.md`](../../.dev-sop/release/server.md) · [`.dev-sop/release/README.md`](../../.dev-sop/release/README.md) | 2026-11-04（原记于 2026-10-26，从远端 `lijinzhao/skillmaster` 搬回仓库） |

## 版本里的技术欠账

| 版本 | 欠账 | 记录处 |
|---|---|---|
| v39-media-files | **v42 删除了 `sop/`，T27 要读的 `sop/07-files.md` 已不存在——跑之前先改写。** P3：整包导入流式化（解除 128 MiB 临时上限）、CLI 整版取回（`get --all`）流式落盘（解除 17 MiB 限制）、blob 回收清扫器；P0–P2 的验收用例多数尚未执行。 | [README](../versions/v39-media-files/README.md) · [test-plan](../versions/v39-media-files/test-plan.md) |
| v38-install-delivers-skill | **v42 删除了 `sop/`，v38 的 T4 等用例第一步就读 `sop/06-install.md`，前提不再成立——跑之前先改写。** 真机验收 T3 / T7 / T4 / T8–T11 / T12（Windows）/ T13。平台技能 `skillmaster/skillmaster-startup@1.0.0` 已在线，T3 / T7 的阻塞已解除，但用例本身仍未跑。 | [test-plan §结果](../versions/v38-install-delivers-skill/test-plan.md) |
| v1-hosting | `PATCH` 元数据、`/inner/**` 独立端口、`/oauth/token` 与 `/oauth/revoke` 限流、RFC 9728 资源元数据。 | [README 状态行](../versions/v1-hosting/README.md) |

**分工**：`待开版` 只放还没进任何版本 `prd.md` 的条目；已经进了某一版的，从这一节删掉，在那版里维护。`版本里的技术欠账` 是已经开版、但还没收口的条目，收口后一并删掉。

# 0001 · 可执行位改为由路径决定

## 改了什么

- **服务端**：新增 `ExecutableMode.of(relpath)`（`scripts/` 之下 `0755`，其余 `0644`），上传、同步、草稿首次改动、草稿编辑、**改名**（`renameFile` 连 relpath 一起改 mode）、复制六处都改成用它；`ManifestEntry` 的 4 参兼容构造也按路径算，它上面那个「默认位」常量与 `executable()` 查询随旧模型一起删掉。
- **读路径**：`VersionFileRepository.filesOf` 用 relpath 构造 `ManifestEntry`，不再读 `version_file.mode`——分发与作者面拿到的位永远按路径算；列只留给运维查询与迁移。
- **同步的安全闸**：`SecurityScanner` 的 `executable` / `executable_binary` 不再看 tar 位，改看「去掉 skill 目录前缀后的 relpath」；`security-rules.yml` 里那句「tar mode 是可执行位唯一存活的地方」随之改写。`FetchedFile.executable` 保留为上游事实，但没有任何东西再用它判权限。
- **迁移**：`V41__mode_follows_the_path.sql` 按路径重算全部存量行（含已发布版本）。digest 不变。
- **删除**：`ModeChangedOnReplayException`、`MODE_CHANGED_ON_REPLAY` 这个错误码、`ApiExceptionHandler` 里它的那条分支、`VersionSubmitter` 里 `reconcileModes` 的三种策略、仓库里改单条 mode 的那个方法。
- **测试**：`SkillSubmitIT.theExecutableBitFollowsThePath`（同一次提交里两个方向都问）、`SyncPipelineIT.aSyncedDraftTakesItsModesFromThePaths`、`VersionEditorIT.aMoveAcrossTheScriptsBoundaryChangesTheStoredMode` 取代原先的两条；`ArchiveIT` 的两条改成按路径断言，并新增 `theArchiveDoesNotTrustAStoredMode`；`SecurityScannerTest` 的两条改成按路径判可执行。CLI 的归档测试从 `client_test.go` 拆到 `archive_test.go`（新文件 155 行，原文件减 109 行）。
- **CLI**：`Archive()` 仍把本地 mode 写进提交的 zip（服务端不再读它，归档自描述没有坏处）；`run` 的缓存目录名加上规则版本（`<digest16>-r2`），旧目录一律当未命中；`run` 的状态行修掉了 `@1.0.0@sha256:…` 的双 `@`。

## 为什么

第一版把位当成**文件携带的属性**，于是每个入口都要正确产出它，平台新建的文件干脆产不出来（永远 0644），而「只改位」的提交在重放时只能二选一：改已发布版本，或拒绝——后者在同步链路上会变成永远失败的条目。端到端测试先用一棵普通 fixture 撞到了其中一个入口的漏洞（`skmr skill submit <目录>` 打包时不写 mode），说明这类规则不能靠每个入口各自实现。

改成路径的函数之后，同一份内容只可能算出一组位：没有可对账的东西，也没有要发明默认值的地方。见 [ADR 0166](../../../decisions/0166-the-executable-bit-follows-the-path.md)。

## 影响

- **已发布的版本也变了**：`scripts/` 下的文件在后续下载里是 `0755`（之前是 `0644`）。内容与 digest 不变，客户端已缓存的旧解包保持旧位——`run` 的缓存键因此带上规则版本，旧的 0644 解包不会被继续使用。
- **`scripts/` 里的数据文件也带 `+x`**（`scripts/data/input.csv`）。
- **`scripts/` 之外的脚本不可执行**；`run` 本来也只解析 `scripts/`。
- **同步能收的仓库有一点点变化**：`scripts/` 下有二进制（哪怕上游没标可执行）的条目会被安全闸阻断；`scripts/` 外标了可执行的二进制反而放行（交付时是 0644）。
- **`mode_changed_on_replay` 从错误码表里消失**：`docs/current/errors.md` 由生成器更新；PRD 的验收 12 与测试用例 T12 一并删除，替换为 T14。
- **内部 `docs/current/TODO.md` 的「存量回填」条目不再需要**：V41 已经做了。

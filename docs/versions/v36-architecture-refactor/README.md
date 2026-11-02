# v36-architecture-refactor · 结构拆分与模块边界加固

> **最后更新**：2026-11-01
> **状态**：已实现（P0–P4 已落地；三端测试全绿；**待人看图**）
> **归档**：v40 起为历史记录，正文不再修改。
> **含收费**：否

## 目标

在不改变对外能力边界的前提下，拆掉三端的长文件、把跨模块依赖收成显式契约、把「模块可独立改动」变成可执行检查。细节见 [`prd.md`](prd.md)。

## 验收标准

所有既有测试通过、对外契约不变，且 `main.go` < 300 行、控制台两个页面 < 600 行、服务端不再有 > 500 行的类（基线内除外）；新增的模块接口规则与行数检查在 CI 里生效。完整验收见 [`prd.md`](prd.md) §验收与指标。

**成立条件**：验收 1（行为不变）与验收 2（对外契约不变）任何一条不满足，这一版就不算交付。

## 这一版明确不做什么

- **不做战术 DDD 重写**——聚合根、领域事件、CQRS / Event Sourcing 不在本版；理由见 [ADR 0122](../../decisions/0122-architecture-refactor-is-structural.md)。
- **不改对外契约**——HTTP 路由与形状、CLI 输出与退出码、控制台 DOM 与文案都不动。
- **不换框架、不加依赖**——不引入 Spring Modulith、cobra、新状态库或 UI 库。

完整范围与每条的理由见 [`prd.md`](prd.md) §范围。

## 与目标态的关系

[`target/`](../../target/README.md) 描述的是完整形态；这一版不改变产品能力，只改工程结构，因此与目标态没有分叉。它让后续按目标态新增模块时，边界与行数不再成为阻力。

## 交付记录

| 期 | 结果 |
|---|---|
| **P0 基线与护栏** | [`rules.md`](../../current/rules.md) 已就位；`scripts/check-file-size.mjs` + 基线（47 → 35 条）；`.github/workflows/arch.yml`；ADR 0122–0125。 |
| **P1 CLI** | `main.go` 3,673 → **147**；`version.go` 1,741 → **182**（+ 7 个同包文件）；`files.go` 1,591 → **252**（+ 3 个）；`internal/api/client.go` 889 → **252**（+ 2 个）。CLI 全部测试通过。 |
| **P2 控制台** | `SkillPage.tsx` 1,914 → **415**（+ 8 行 re-export）；`VersionEditorPage.tsx` 1,381 → **267**（+ 8 行 re-export）。33 文件 / 614 条测试通过，`tsc` 与 `build` 通过。**视觉验收待人看**。 |
| **P3 服务端** | `SkillVersionService` 1,265 → **196** 门面 + 6 个协作者；`TokenStore` 853 → **221** + 3 个；`VersionRepository` 866 → **250** + 3 个；三个大控制器拆成读 / 写 / 生命周期 / 访问申请；535 条测试全绿。 |
| **P4 边界与收口** | `ModuleMap.publishedTypes` + ArchUnit「只依赖发布类型」规则（6 条架构测试全绿）；共享内核白名单；`VersionProjectionSink` 端口 + `config` 适配器；M03–M10、M12、M13 模块文档。`check_docs.py` 退出码 0。 |

**留下的开放项**：`api` 层「只调 usecase」的窄规则尚未加（P4 只做到「模块 ↔ 模块」；`api` 仍直接使用部分模块值类型与服务返回类型，见 [`technical-design.md`](technical-design.md) Q3）。控制台视觉验收仍待人看。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、验收与指标、依赖与前置 |
| [`technical-design.md`](technical-design.md) | 当前架构、目标架构、内部接口、分期、实施清单、开放问题 |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、环境与数据、结果 |

## 开放问题与当前结论

完整列表见 [`technical-design.md`](technical-design.md) §开放问题。按重要性排前三条：

1. **共享内核放哪**——已定：`Access` / `GrantRole` / `VersionPin` / `VersionState` 按白名单留在原处（[ADR 0123](../../decisions/0123-modules-publish-an-explicit-interface.md)）；只有共享类型继续增长时才考虑搬包。
2. **`api` 层是否禁止 import 模块**——本版只做到「模块 ↔ 模块」；`api` 窄规则按 Q3 推迟到下一版，届时先把 `FileLookup` / `StreamLookup` 收回 usecase 模型。
3. **文件行数阈值**——已按 600 / 400 / 500 / 500 / 600 / 300 / 测试 1,000 执行；基线 35 条只减不增，下一版再按实际拆分结果收紧。

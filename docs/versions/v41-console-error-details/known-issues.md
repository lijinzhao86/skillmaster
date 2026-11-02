# v41 · 本版范围外的已知问题

> 本文件记**本版范围外**、本版决定不改的缺陷。本版范围内的未修问题记在 [`test-plan.md`](test-plan.md) §已知问题。

## 1 · 密钥页剪贴板用例偶发失败

**状态**：已复现（2026-11-02，在 632 条的那一版全量里五次观察到一次 `1 failed | 631 passed`；单跑 `tests/keys.test.tsx` 稳定通过）。

**是什么。** `tests/keys.test.tsx` 的剪贴板用例只 `flush()` 一次就断言「退场按钮可用」，而复制处理里还 await 了 `navigator.clipboard.writeText`（`skillmaster-console/src/pages/AccountKeysPage.tsx:422`）。

**影响。** 全量偶发一条失败；后续 638 条的全量未再复现。本版没有碰这条路径。

**为什么本版不修。** 与本版改动无关，且不扩大 diff。

**建议修法。** 用 `waitFor` 等按钮状态，或把点击包在 `act` 里。

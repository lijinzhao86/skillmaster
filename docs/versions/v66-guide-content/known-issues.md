# 已知问题（本版不修）

本版只改 Guide 与它的评测；下面这些缺陷在 CLI 里，**本版按 PRD「不改 CLI」跳过**，记在这里等下一版。

## 1 · `skmr skill invoke --help` 的裸地址规则与行为相反

- **位置**：`skillmaster-cli/cmd/skillmaster/declarations_skill.go:20`（`skill invoke` 的 `Output` 拼进 `versionRuleLine`，定义在 `declarations.go:63`）
- **状态**：已复现
- **现象**：帮助写「裸地址用上次 invoke 钉住的版本，不是当前线上」。实际 `invoke` 故意不读本机记住的那一版：`invoke.go` 的 `fetchBody` 按 `/metadata` 解析当前线上，再写一条新钉子（`invoke.go:403-404` 的注释与 `fetchBody` 的 `namedInAddress` 分支）。
- **影响**：照帮助理解的人会以为重新 `invoke` 仍停在旧版；实际它会切到当前线上并重新钉住。
- **本版怎么处理**：Guide 的 [`use/read-files.md`](../../../skills/skillmaster-guide/use/read-files.md) 已按实际行为写——读取类命令用钉子，`invoke` 自己总按当前线上解析。CLI 帮助文本留给下一版，改法是让 `skill invoke` 用一句自己的规则，不拼 `versionRuleLine`。

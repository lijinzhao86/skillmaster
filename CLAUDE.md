# SkillMaster 仓库约定

## 开发流程：一律按 code-dev-sop 走

每个需求、每个 bugfix，先 `skmr skill invoke lijinzhao/code-dev-sop`，再读 [`.dev-sop/profile.md`](.dev-sop/profile.md) 与 [`.dev-sop/audit.md`](.dev-sop/audit.md)。**流程、关卡、判据都在 skill 里，本文件不重复**；这里只加两条项目约定：

- **默认检查点「中」档**（需求后一次、提 PR 前一次）。动共享面、跨模块、改架构时升「重」，由用户定。
- **`.dev-sop/` 那两份只有用户能改**；agent 只能提议，不能改完继续跑。

## 四条红线

违反了**不会报错**，所以必须在上下文里等着被撞上：

1. **界面的唯一权威是 `design/`**。结构、类名、数值、文案逐条照抄；不自创，不静默偏离；设计稿没覆盖的态**先问**。改界面之前先读 [`.dev-sop/design.md`](.dev-sop/design.md)。
2. **「测试全绿」不是视觉验收**。Claude 看不到渲染结果，改完要请人看，或先给截图 / 对照。
3. **设计检查的全量有闸**，Claude 在会话里跑不动整套——这是有意的，别绕。
4. **每一处控制台操作都要有 CLI 命令对应**（[ADR 0081](docs/decisions/0081-every-console-operation-has-a-cli-command.md)）。

## 想知道什么，去哪找

| 想知道 | 读 |
|---|---|
| 界面规格：尺寸、断点、类名、文案口径 | [`design/README.md`](design/README.md) · [`design/voice-spec.md`](design/voice-spec.md) |
| 界面与设计检查的过程规则 | [`.dev-sop/design.md`](.dev-sop/design.md) |
| 四端怎么发、怎么回滚 | [`.dev-sop/release/`](.dev-sop/release/README.md) |
| 前端结构、行数上限、`qa/` 的边界、控制台配 CLI 的细则 | [`.dev-sop/profile.md`](.dev-sop/profile.md) |
| 文档的五个轴与版本化 | [`.claude/skills/docs-architecture/SKILL.md`](.claude/skills/docs-architecture/SKILL.md) |
| 系统怎么切、模块边界、数据身份 | [`docs/current/README.md`](docs/current/README.md) |
| 写 skill 的文档规则 | [`.skill-authoring/rules.md`](.skill-authoring/rules.md) |

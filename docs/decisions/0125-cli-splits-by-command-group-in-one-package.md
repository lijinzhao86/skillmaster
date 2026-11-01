# ADR 0125 · CLI 按命令组拆文件，不拆成 internal 子包

- **状态**：已采纳
- **日期**：2026-11-01
- **依赖**：[ADR 0122](0122-architecture-refactor-is-structural.md)

## 背景

`skillmaster-cli/cmd/skillmaster` 是 `package main`，13,594 行源码（另有 14,721 行测试）。`main.go` 一个文件 3,673 行，装着入口、调度、登录 / 登出、搜索 / 列表、invoke、read、versions、版本钉、submit、startup 与一批输出辅助。Go 官方布局文档说：更大的程序可以把代码拆到多个文件、都声明 `package main`；更大的命令再把功能移进 `internal/` 支持包。

## 决定

1. 先**在同一个 `package main` 内按命令组拆文件**：`main.go` 只留入口与调度，其余按 auth / session / search / share / invoke / read / versions / pins / submit / startup 分组。
2. 不引入 cobra / urfave-cli；保留现有的 `run()` + switch 调度（仓库既有约定优先）。
3. 暂不把命令搬进 `internal/command/*` 子包；等 `package main` 仍然超限、或需要独立测试命令组时再评估。
4. `internal/api/client.go` 按关注点拆文件（核心 client / 资源读取 / 错误映射），同样先同包拆文件。

## 理由

**否掉 cobra / urfave-cli。** 现有 CLI 的 help 登记表、退出码、参数归属测试都建立在 stdlib flag 与手写调度上；换框架是一次行为重写，而本版的目标是行为不变。

**否掉「直接搬进 internal 子包」。** 命令处理函数共享大量包级辅助（输出、凭据、查询参数、错误改写），搬包会先制造一串循环依赖或导出一堆本不该导出的符号；同包拆文件是零行为变化的第一步。Go 官方布局也把「多个文件同包」列为合法且常见。

**为什么值得拆。** 3,673 行的单文件让「改一个命令」要先建立整个文件的地图；按命令组拆开后，每个文件对应一个语义单元，测试不用动（Go 测试只认包）。

## 后果

- 文件边界是唯一新增的结构；包边界不变，测试与调用方不变。
- `package main` 仍然是 13,594 行；如果拆完仍觉得大，下一版再评估 `internal/command` 或命令注册表。
- 按命令组拆文件后，新增命令要放进对应文件；`main.go` 的 switch 仍是唯一调度点。

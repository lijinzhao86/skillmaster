# v58 · 本版范围外的已知问题

> 这些缺陷在**本版之外**的历史代码里，v58 决定不修；每条标状态与复现方式。

## CLI 自动更新的三条用例在 `main` 上就失败

- **状态**：已复现（在 `main` 的 `b743f3d` 与 v58 工作区上输出一致）。
- **用例**：`TestTheCheckIsThrottledToOnceADay`、`TestANewerBuildIsInstalledAndTheCommandIsUntouched`、`TestATamperedPackageLeavesTheBinaryAlone`（`skillmaster-cli/cmd/skillmaster/v25_test.go`）。
- **现象**：三条命令发出 0 次更新请求；`skmr skill invoke` 无参数时只返回用法错误（退出码 2），更新提示不出现。
- **复现**：`cd skillmaster-cli && go test ./cmd/skillmaster`；或在 `main` 的干净检出上跑同样三条。
- **为什么不在这里修**：与 v58 的采集 / 读取路径无关，属于更新器与测试夹具的既有问题；改了会让本版的回归范围混进一个独立缺陷。

# v58 · 本版范围外的已知问题

本版没有留下未修的范围外缺陷。

## 记录：CLI 自动更新三条用例在本机失败的原因（已消除）

- **现象**：`TestTheCheckIsThrottledToOnceADay`、`TestANewerBuildIsInstalledAndTheCommandIsUntouched`、`TestATamperedPackageLeavesTheBinaryAlone` 在本机报「0 次更新请求」，看起来像更新器坏了。
- **实际原因**：本机 shell 导出了 `SKILLMASTER_AUTO_UPDATE=0`，`autoUpdateAllowed` 正确地关掉了自动检查；三条用例没有清这个变量，于是测到的是「开关关着」，不是更新逻辑。CI 上没有这个变量，所以一直是绿的。
- **处置**：三条用例开头显式 `t.Setenv(AutoUpdateEnv, "")`，开关本身仍由 `TestTheSwitchTurnsTheAutomaticCheckOff` 覆盖；`go test ./...` 现在全过。

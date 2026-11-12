# v62-cli-sandbox · 凭据默认存文件，沙箱配置一次放行

> **最后更新**：2026-11-12
> **状态**：进行中（已实现；真机 WorkBuddy `--fix`（终端与沙箱）/登录、Claude Code 沙箱失败与终端隔离配置成功、安装器已跑；Claude Code 真机写入（T10）与 T17–T19 未跑，见 [`test-plan.md`](test-plan.md) §结果；小版本迭代 1 条，见 [`iterations/0001-agent-install-chain.md`](iterations/0001-agent-install-chain.md)；已合并（2026-11-12））
> **含收费**：否

## 目标

让 CLI 在 agent 沙箱里不再被逐次审批卡住：凭据默认落 0600 文件、钥匙串改为可选；`skmr doctor --fix` 在用户终端跑一次，把状态目录写进本机**本版自动写、键名有官方出处或经本机实测的客户端**（名单见 [`technical-design.md`](technical-design.md) 的客户端表；Devin 的 `Write(glob)` 层级未核实），其中有域名键的再写 SkillMaster 的平台与更新域名。Codex 旧式与 Gemini 的网络侧只有总开关，会放开全部出站网络；Codex 旧式还会打开工作区可写。输出里都明说；没有可自动写的配置面的只打印指引。细节见 [`prd.md`](prd.md)。

## 验收标准

登录默认落文件、沙箱里不再出现钥匙串写审批、老凭据不掉登录、`doctor --fix` 幂等可撤销。完整验收见 [`prd.md`](prd.md) §验收与指标。

**成立条件**：与 [`prd.md` §验收与指标](prd.md#验收与指标) 的成立条件同一组——验收 #1、#2、#3、#9 任一条不满足就不算交付。

## 这一版明确不做什么

摘要三条最要紧的；完整范围与每条的理由在 [`prd.md`](prd.md) §范围。

- 不默认整体关沙箱，也不把 `skmr run` 放出去——只允许单条管理命令出沙箱，skill 代码仍在沙箱内
- 不给 Cline / Roo / Aider / Amp 做「放行」——它们没有隔离层，没有可写的权限键
- 不解决回环被挡导致的登录失败——Cursor / Zed / Claude Code Linux 是另一条独立问题

## 与目标态的关系

[`target/`](../../target/) 不描述客户端凭据的落点，这一版不改变目标态的产品范围。唯一相关的是平台不代管第三方身份密码（[`target/README.md`](../../target/README.md) 差异清单第 23 条），与本版无关。

- 保留的部分：目标态的产品范围不变。
- 已经分叉的部分：无。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、验收与指标、依赖与前置 |
| [`technical-design.md`](technical-design.md) | 架构、数据模型、接口、分期、开放问题 |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、环境与数据、结果、已知问题 |
| [`iterations/0001-agent-install-chain.md`](iterations/0001-agent-install-chain.md) | agent 安装链路的沙箱修复 |

调研依据：[`docs/research/agent-sandboxes.md`](../../research/agent-sandboxes.md) 的客户端矩阵与配置键。

## 影响最大的未决问题

完整列表见 [`technical-design.md`](technical-design.md) §开放问题。按重要性排：

1. **WorkBuddy 运行中改 `settings.json` 会不会被覆盖？** 当前做法是先改文件、要求重启后生效。
2. **Claude Code 的 `allowWrite` 是否对状态目录生效？** 文档有键，但 `~/.claude` 的受保护行为需要在真机上确认。
3. **真机 `--fix` 跑了 WorkBuddy（终端与沙箱各一次），Claude Code 只跑了沙箱内失败 + 终端隔离配置成功。** 沙箱那次还顺带写了 Codex 与 Copilot 的真实配置（已恢复/删除）；Claude Code 的真实配置（T10）与其余客户端的真机写入、逐家沙箱内行为仍未测，合并逻辑由单元测试覆盖。逐家复测是 P1，在那之前不宣称它们的沙箱内行为。

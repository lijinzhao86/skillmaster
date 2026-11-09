# v52-service-credentials · 服务凭据托管

> **最后更新**：2026-11-09
> **状态**：进行中。**P1 已实现**：M16 + 四张表 + 保险箱/绑定/租约/配额/审计 + `skmr secret` 的 set/list/rotate/rm/bind/unbind/shares/quota + `run` 注入 + 共享的 `/api/v1` 接口。**P2 已实现**：`metadata.requires.credentials` 的声明提示（`credentials_required` / `credentials_missing`）、控制台的 `/web/secrets*` 端点、「权限 › 服务凭据」页与技能设置面板、`skmr secret share`（页面动作）、`secret unshare` 与 `secret incoming`；共享的写接口从 `/api/v1` 下线。
> **含收费**：否

## 目标

让用户把 skill 需要的第三方服务凭据存在平台上，换机器不用重贴。**这一版的承诺是五项**：值不进对话、不随 skill 包下发、换机器不用重贴、作者可撤销、每次使用有审计——**「agent 看不到值」不在其中**：注入到环境变量后，agent 与脚本都读得到（[ADR 0169](../../decisions/0169-the-first-version-injects-without-proxying.md) §后果）。细节见 [`prd.md`](prd.md)。

## 验收标准

摘要：跨机器、跨 agent 可用；值不进对话、不随包下发；**没有读回出口**（唯一的明文出口是取租约那一次响应）；共享必须确认、撤销立即生效。完整验收见 [`prd.md`](prd.md) §验收与指标。

**成立条件**：验收 1（跨机器）与验收 5（共享确认与撤销）**不满足就不算交付**——它们就是这一版的两句承诺本身。

## 这一版明确不做什么

- **CLI 本地反代与平台代转** —— 本地反代覆盖面窄、沙箱矩阵贵；平台代转留到允许用户公开 skill 时做增值功能。两者都由 [ADR 0169](../../decisions/0169-the-first-version-injects-without-proxying.md) 定下。
- **托管身份密码** —— 与 API 凭据不是同一类数据：不能轮换、通常不可撤销，责任面完全不同（[ADR 0168](../../decisions/0168-hosted-credentials-are-api-keys-not-identity-passwords.md)）。
- **上传扫描与存量治理** —— 平台尚未上线，整体治理放进一个大版本；已记 [`TODO.md`](../../current/TODO.md)。

完整范围与每条的理由在 [`prd.md`](prd.md) §范围。

## 与目标态的关系

[`target/`](../../target/) 写的是完整形态。这一版与它的关系：

- **保留**：私有托管起步、服务端权威。
- **已经分叉**：`target/design.md` §2.1 的「不存储第三方密码，不代管组织外的身份」被收窄为「不代管身份密码；只托管用户明确授权、用于调用第三方服务的 API 凭据」。目标态正文不改，差异登记在 [`target/README.md`](../../target/README.md) §十（[ADR 0168](../../decisions/0168-hosted-credentials-are-api-keys-not-identity-passwords.md)）。

## 本版文档

| 文档 | 内容 |
|---|---|
| [`prd.md`](prd.md) | 用户与场景、范围、控制台与 CLI 对应、验收与指标、依赖与前置 |
| [`technical-design.md`](technical-design.md) | 架构、数据模型、接口、分期、开放问题（第 3 关已出） |
| [`test-plan.md`](test-plan.md) | 验收映射、用例、环境与数据、结果（自动化部分已填，T1–T13 待真栈执行） |
| `iterations/` | 小版本迭代记录（只增不改）；本版尚未开始 |

## 还没定的

只有 [`technical-design.md`](technical-design.md) §开放问题 一份清单，这里不再抄一遍。第 6 条已结案（共享的写收进会话平面，配额留在两个平面），第 1、2、4、7 条是仍未最终确认的取舍（各自已有当前倾向），第 8 条（阿里云实现何时做真机验证）也仍开放——它保留但生产不用，目前只有编译与配置校验。

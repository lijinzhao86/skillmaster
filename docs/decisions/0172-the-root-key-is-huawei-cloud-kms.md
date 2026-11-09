# 0172 · 根密钥改由华为云 KMS 托管

- **状态**：已采纳
- **日期**：2026-11-09
- **取代**：[ADR 0170](0170-the-root-key-lives-in-a-cloud-kms.md)
- **依赖**：[ADR 0168](0168-hosted-credentials-are-api-keys-not-identity-passwords.md)（托管边界）

## 背景

[ADR 0170](0170-the-root-key-lives-in-a-cloud-kms.md) 选的是**阿里云 KMS 共享版**：一个对称 CMK，按 0.3 元/天 + QPS 计费，估「每月约 100 元量级」（口径见 [调研 §四](../research/third-party-credentials.md)，官方按量付费页 2026-04-08）。2026-11-09 上线前核对，这个估算的两个前提都不成立了：

1. **共享版（KMS 1.0）2026-12-30 停止服务**——官方公告的 EOS 日期，距今约 7 周。
2. **官方对「自建应用 + 自建 CMK」只卖实例版**：中国内地的软件密钥管理实例 **2,499 元/月**（包年包月）；按量付费文档另有 30 元/天。默认密钥免费，但明确不覆盖用户自建应用的加解密。

同时核到本账号的部署身份没有 KMS 与 RAM 权限，开通与建密钥都需要主账号。

备选四条：

| | 做法 | 月成本 | 代价 |
|---|---|---|---|
| A | 阿里云 KMS 实例版 | 2,499 元 | 同云同 VPC，最稳；单项最贵 |
| B | **华为云 KMS（DEW）** | **约 11.3 元**（0.015458 元/小时/密钥；每密钥每月 2 万次调用免费，超出 0.6 元/万次） | 跨云调用（上海同城，公网 TLS）；要换 SDK |
| C | 自建（Vault 或一个 seal 服务） | 0 元 | 根密钥与业务同机；解封、备份、轮转、可用性全自己扛 |
| D | 阿里云共享版过渡 | 约 9 元（0.3 元/天/个，**不含 QPS**；加上 3 元/天/QPS 后，1 QPS 约 99 元/月、2 QPS 约 190 元/月） | 2026-12-30 停服，7 周后必须再迁一次 |

## 决定

- 用 **华为云 KMS（数据加密服务 DEW）**，地域 **cn-east-3（华东-上海一）**，一把对称 CMK（`AES_256`、平台托管），覆盖全部凭据。密钥 ID 记在部署配置里——它是标识符，不是密钥材料。
- 运行时用**独立 IAM 用户**，只授 `kms:cmk:encryptData` / `kms:cmk:decryptData`；不给建密钥、删密钥。
- **接口与存储形状不变**：`Sealer` 仍是 `encrypt` / `decrypt` 两个方法；表里仍然没有 `key_version` 列。
- **改为信封加密**（推翻 0170 的「第一版不做信封」）：值用进程内生成的 AES-256-GCM 数据密钥（DEK）加密，DEK 才交给 KMS 包。原因是两家 KMS 的单次明文上限都小于我们的值上限——华为 `EncryptData` 的 `plain_text` 只有 4096 个 Base64 字符（约 3 KB 原文），阿里云 `Encrypt` 是 6 KB，而 `CredentialValue.MAX_BYTES` 是 32 KiB，验收 3 明确要求 4 KB 的 cookie 能存。`Sealer` 接口不变，`lease` 仍是唯一解密点。
- **两个实现都保留，用 `sealer` 选一个**：`huawei`（生产用的）与 `aliyun`（备选）；`local` 只用于开发与测试。配置键名（`kms-key-id`、`region`、`access-key-id`、`access-key-secret`）两家同名同义，不新增第二套。
- 0170 的其余结论继续有效：一个 CMK、不缓存明文、`lease` 是唯一明文出口、不解决备份保留期。

## 理由

- **否掉 A**：为一把密钥每月付 2,499 元，是这个产品目前单项最贵的固定成本，而它买到的「同云同 VPC」只影响每次租约的几十毫秒。
- **否掉 C**：0170 否掉自管密钥的理由原样成立——根密钥与密文同处一台机器、同一份备份，一次主机失守两样一起到手；自建 Vault 只是把「配置文件里的密钥」换成「同一台机器上的另一个进程」，而且解封、备份、可用性全要自己做。
- **否掉 D**：共享版 2026-12-30 停服，等于把一次迁移硬塞进上线后的第 7 周。
- **为什么可以跨云**：KMS 在**每个凭据**的写入时调用一次、租约时调用一次，量级是每天几十到几百次；ECS 在阿里云 cn-shanghai、KMS 在华为云 cn-east-3，同城公网 TLS。**2026-11-09 本机实测**：一次加密 69–104 ms、一次解密 92–267 ms（4 次，含 SDK 开销与跨云公网，样本小，不足以称 P95）；一个凭据的租约仍在 PRD 的 500 ms 预算内，两个凭据接近上限——跨云链路的延迟要进告警（见后果）。KMS 不存用户数据，只做加解密；值本身仍然只落在我们自己的 Postgres。
- **为什么保留两个实现**（2026-11-09 所有者定为保留）：换厂商的成本主要在 SDK 适配，两条路径共用 `Sealer` 接口与同一套配置键，多出来的只有两个小类；代价是**只有一条会被生产流量验证**，另一条要在文档里标明这一点，改 `Sealer` 接口时两边一起改。
- **为什么信封成了必须**：0170 当时按「值只有几十到几百字节」判断直连加解密够用；实际上限是 32 KiB，而 KMS 单次明文上限是 KB 级。这个矛盾在 v52 未上线前被发现，改在 `Sealer` 内部，不涉及表结构（`ciphertext` 本来存的就是不透明字节）。

## 后果

- **新的外部依赖**：华为云账号、一把 CMK、一个 IAM 用户的 AK/SK（对 `sealer=huawei` 而言，这台 ECS 在阿里云上，华为的默认凭据链取不到东西，所以 AK/SK 是必须的，部署闸门会按名字拦；`aliyun` 可以留空走同机 RAM 角色，该角色需具备 KMS 加解密权限）。它们都在我们控制之外；华为云故障时 `lease` 跟着失败——与 0170 的「KMS 是可用性依赖」同一件事：调用有 2 s 连接 / 5 s 读超时，失败回答 503 `credential_vault_unavailable`，而不是挂住租约或报成 500。**不在租约事务里重试**：一次失败就返回，让脚本重跑一次，比让数据库连接多握 5–10 秒更安全（0170 写的「有限重试」因此改为「由调用方重跑」）。
- **成本**：密钥 11.3 元/月固定；调用量随「租约次数 × 每次涉及的凭据数」增长，每密钥每月前 2 万次免费，超出 0.6 元/万次。
- **配置键名不变**（`sealer`、`kms-key-id`、`region`、`access-key-id`、`access-key-secret`、`local-key`）；闸门机制不变，但合法值从 0170 的 `kms` 变成 `huawei|aliyun`，必填项见 `.github/workflows/server.yml` 与 `deploy/README.md`。
- **跨云链路要盯**：每次租约多一跳公网，KMS 的失败率与延迟要进告警。
- **切换 `sealer` 要重新封存**：blob 里没有记录是哪个 provider 包的 DEK，把部署从 `aliyun` 或 `local` 改成 `huawei`（或反向）之后，旧密文在新实现下解不开。v52 上线时库里没有旧值；上线之后切换要先迁移。
- **阿里云实现保留但不被生产使用**：它没有真实流量，属「未验证路径」；`Sealer` 接口改动时两个实现一起改、一起过编译，真要用它之前先对着阿里云实例跑一遍。
- **手机号加密不动**：`SKILLMASTER_PHONE_ENC_KEY` 这一套照旧。

## 出处

外部数字都取自官方页或本仓调研，口径随数字：

- 阿里云共享版 EOS 2026-12-30：[KMS 共享版停止服务公告](https://help.aliyun.com/zh/kms/key-management-service/support/billing-of-kms)
- 阿里云实例版 2,499 元/月、按量 30 元/天：[KMS 产品计费](https://help.aliyun.com/zh/kms/key-management-service/product-overview/kms-billing)、[按量付费](https://help.aliyun.com/zh/kms/key-management-service/pay-as-you-go)
- 阿里云 `Encrypt` 明文上限 6 KB：[Encrypt API](https://help.aliyun.com/zh/kms/key-management-service/developer-reference/api-encrypt)
- 华为云 0.015458 元/小时、每密钥每月 2 万次免费、超出 0.6 元/万次：[DEW 按需计费](https://support.huaweicloud.com/price-dew/dew_03_0005.html)（该页正文公式写作 `0.0015458`、示例算式按 `0.015458` 计，本文取示例口径；下单前以价格计算器为准）
- 阿里云共享版 0.3 元/天/个的口径：[调研 §四](../research/third-party-credentials.md)（官方按量付费页，2026-04-08）
- 华为 `EncryptData` 的 `plain_text` 限 4096 字符：SDK 模型 `EncryptDataRequestBody`（`huaweicloud-sdk-kms` 3.1.154）
- 实测延迟：本机 2026-11-09，32 KiB 值经 `EnvelopeSealer` + `HuaweiKeyWrapper` 对生产 CMK 跑 4 次

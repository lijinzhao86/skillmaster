# v52-service-credentials · 测试方案

> **最后更新**：2026-11-09
> **所属版本**：[`README.md`](README.md)

## 验收映射

> 用例编号与验收编号**不同号**（验收 11→T13、12→T11、13→T12）：T 编号是历史顺序，按下表逐行看，不要按数字顺读。

| 验收（[`prd.md`](prd.md) §验收与指标） | 用例 |
|---|---|
| 1 跨机器、跨 agent | T1 |
| 2 值不进对话、不随包下发 | T2 |
| 3 任意文本可用 | T3 |
| 4 没有明文出口 | T4 |
| 5 共享是高风险、只在页面上完成 | T5 |
| 6 声明只提示 | T6 |
| 7 未声明的 skill 仍可用 | T7 |
| 8 控制台与 CLI 一一对应 | T8 |
| 9 删除承诺 | T9 |
| 10 审计 | T10 |
| 11 一条值绑两处 | T13 |
| 12 无绑定不受影响 | T11 |
| 13 配额默认 500、可调、提高需用户确认 | T12 |
| 14 别人共享给我的凭据可见（技能设置页的来源列 / `skmr secret incoming`） | T5、T8 |
| 15 生产封存由华为云 KMS 完成（信封加密 + 三道闸门） | T14、T15 |

## 用例

### T1 跨机器（成立条件）

- **前置**：两台机器 A、B，同一账号登录；`dev/zhihu` 已在平台上有一个可运行版本；一把假的 `ZHIHU_COOKIE` 值（`test-cookie-…`）。
- **步骤**：A 机 `printf '%s' 'test-cookie-…' | skmr secret set --skill dev/zhihu --env ZHIHU_COOKIE`；B 机 `skmr run dev/zhihu -- python3 scripts/read.py`（脚本打印 `len(os.environ['ZHIHU_COOKIE'])`）。
- **期望**：B 机退出码 0，打印长度 13（例值 `test-cookie-…` 的长度），全程不需要在 B 机输入任何值。
- **跨 agent**：同一台 B 机上换一个 GUI agent 的 shell 再跑一次，结果相同。

### T2 值不进对话、不随包下发

- **前置**：同 T1。
- **步骤**：检查 `secret set` 只从 stdin 读（不带 `--value`，在终端里直接跑要被拒）；`secret list` / `shares` / 所有错误路径的输出；把 skill 包下载一次看有没有值。
- **期望**：任何命令与输出都不打印值；`skmr skill download` 的包里没有该值；错误提示只出现名称与 env 名。

### T3 任意文本

- **前置**：三类值——4 KB 的 cookie 串、多行 PEM、一段 JSON。
- **步骤**：分别 `secret set` 到三个 env，`skmr run` 后由脚本逐字比对并打印 SHA-256。
- **期望**：三次 SHA-256 与源文件一致（含换行与引号）。

### T4 没有明文出口

- **前置**：服务端日志级别默认；`actuator` 不暴露请求体。
- **步骤**：逐条调用 API（创建、列表、轮换、lease、共享），抓服务端日志与审计表；检查 `lease` 响应头。
- **期望**：日志与 `audit_event` 里没有值；`lease` 带 `Cache-Control: no-store`；列表响应没有值字段。

### T5 共享是高风险、只在页面上完成

- **前置**：两个账号（作者 / 协作者），作者持有 `dev/zhihu`；协作者已被接受为 `invoke`。
- **步骤**：控制台共享页出现常驻高风险横幅；确认共享；协作者跑通 `dev/zhihu`；协作者再跑同一条凭据绑定的另一个 skill → 不生效；协作者尝试给**作者的**凭据加绑定 → 被拒（绑自己的可以）；作者撤销 → 协作者下一次失败；`skmr secret share` 只打开页面（`--print` 时不发任何写请求，`POST /api/v1/secrets/{id}/shares` 返回 405）；`skmr secret unshare` 直连生效。
- **期望**：以上全部成立；撤销对下一次运行立即生效；绕过页面没有写共享的路。

### T6 声明只提示

- **前置**：skill 声明 `metadata.requires.credentials: [WEB_SEARCH_API_KEY]`，用户未绑定。
- **步骤**：`skmr run` 与 `skmr skill check`；再绑上这条凭据，两条命令各跑一次。
- **期望**：未绑定时输出点名 `WEB_SEARCH_API_KEY`，给出让用户执行的 `skmr secret set` 命令，含「不要把值贴进对话」；**不阻止运行**（退出码照脚本自己的），并明说「这次照常运行，脚本拿不到这些变量」；绑上之后一个字都不打印。`Metadata` 响应里 `credentials_required` 恒为声明值，`credentials_missing` 随绑定变化。

### T7 未声明的 skill 仍可用

- **步骤**：用一个没有任何 `metadata` 的 skill，手动 `secret set` 绑定后运行。
- **期望**：退出码 0，注入正常。

### T8 控制台与 CLI 一一对应

- **步骤**：逐行执行 `prd.md` 的映射表；确认第 5 行（共享）的 CLI 命令**只打开页面**、第 6 行（撤销）直连生效。
- **期望**：每一行都能执行；共享只能从页面完成。

### T9 删除承诺

- **步骤**：删除一条凭据；查库与 API；对照隐私政策里的「30 天」。
- **期望**：生产库立即查不到；绑定与共享同时失效；备份保留期文案一致。

### T10 审计

- **步骤**：创建、更新值、删除、绑定、解绑、共享、撤销共享各一次，再看一次使用计数。
- **期望**：上列**七类各一条事件**（`credential_create` / `rotate` / `delete` / `bind` / `unbind` / `share` / `unshare`）；使用按天聚合（`credential_use`），不逐次写审计表。

### T11 无绑定不受影响

- **步骤**：对同一个 skill 在有绑定 / 无绑定两种状态下各跑一次，抓 HTTP 请求。
- **期望**：无绑定时不发 `lease`，请求集合与今天一致（`credentials_bound=false`）。

### T12 配额

- **步骤**：默认 500；把上限调到 2（降低，不需确认），连续取用三次；再试着提高。
- **期望**：第三次失败，报错含当前上限、原因、提高命令与「不要自行提高」；在非终端环境提高被拒且未写入；控制台该行标「已达上限」，且「调整」仍在。

### T13 一条值绑两处

- **步骤**：同一把值绑到两个 skill；分别运行；轮换一次后再分别运行；删除后分别运行。
- **期望**：轮换后两边都拿到新值；删除后两边都取不到。

### T14 信封加密与 sealer 配置（单元级）

- **前置**：假 `KeyWrapper`（字节反转，不联网、不做 AES）；`application-test.yml` 的本地密钥。
- **步骤**：① 用信封 sealer 往返 32 KiB 的值与空值；② 同一个值加密两次，比较两段 blob；③ 翻转 blob 里密文的一个字节、数据密钥的一个字节、长度字段（置 0 / 置 0xFFFF），并把合法 blob 从 0 逐一截断到全长；④ 用 `huawei` / `aliyun` 构造 settings，各缺 `kms-key-id`、缺 `region`、只给一半 AK、两半都空；`local` 只给 32 字节 `local-key`（它不读 KMS 字段）。
- **期望**：① 原文逐字节相同；② 两段 blob 不同（DEK 与 IV 都是新的）；③ 每一种损坏都被拒（`IllegalArgumentException` 或 `IllegalStateException`），不返回错值；④ 三个取值都能构造；`huawei`/`aliyun` 缺键、缺地域、半对 AK 在启动时抛出带字段名的错误；两半都空时 `huawei` 被拒（这台机器没有华为凭据来源）、`aliyun` 放行；`local` 只要求 32 字节 `local-key`。

## 环境与数据

- **服务端**：本地栈；测试与单元测试都用 `sealer=local` + `application-test.yml` 里那把固定 32 字节密钥（不另开 CMK）。信封那一层用假 `KeyWrapper` 测；华为云真实链路用生产那把 CMK 做一次人工往返（与生产同一个密钥，不另开）。
- **凭据**：全部使用明显的假值（`test-cookie-…`），不使用真实第三方 key。
- **账号**：两个账号（作者 / 协作者）用于共享与撤销；协作者必须先接受邀请。
- **机器**：T1 需要两台真实机器；其余可以用同一台机器 + 两个 `SKILLMASTER_HOME` 验证状态隔离，但不能替代 T1。
- **清理**：用例结束删除测试凭据；本地密钥只在 `application-test.yml` 里，不进生产配置。

## 结果

> **最后更新**：2026-11-09（P1 与 P2 实现完成，进入循环审计）

### 自动化（已跑）

| 项 | 命令 | 结果 |
|---|---|---|
| 服务端编译 | `./mvnw -q -DskipTests compile` | 通过 |
| 服务端测试 | `./mvnw -o -Dtest=SecretsIT,SkillMetaResponseTest,SkillDetailIT test`（带 `SKILLMASTER_TEST_DB_*`） | 全绿：`SecretsIT` 5 · `SkillMetaResponseTest` 6 · `SkillDetailIT` 18 |
| 服务端全量 | `./mvnw -o verify`（带 `SKILLMASTER_TEST_DB_*`） | BUILD SUCCESS：单元 470 + 集成 656 = 1126 条，0 失败（`surefire` 与 `failsafe` 两段之和；656 只是集成那一段） |
| CLI 全部测试 | `go test ./...` | 除 3 条 v25 自动更新用例失败外全部通过（见 §已知问题） |
| 设计件类型检查 | `cd design && npm run type-check` | 通过 |
| 设计件状态检查 | `node scripts/console-states.mjs <演示件> /tmp/out --only secrets,skillsecrets` | 全过（断言含「值不出现在页面上」「共享高风险提示常驻」） |
| 设计产物与源一致 | `node scripts/build-demo.mjs` 两份 → `diff` | 无差异 |
| 控制台应用 | `npx tsc --noEmit` · `npm test` | 698 条通过（含 v52 P2 新增 `tests/secrets.test.tsx` 8 条：列表与到顶标记、列表失败不暗示运行失败、删除弹窗的 30 天文案、共享只走 `/web/secrets/{id}/shares`、地址里没有那条凭据时回列表、共享页深链预选、技能设置面板列出未绑定的凭据、面板列出「已共享给」并可撤销） |
| 声明解析 | `SkillMetaResponseTest` | 新增 3 条：`metadata.requires.credentials` 的读取、宽松解析（重复/非字符串/非数组）、`credentials_missing` 的差集 |
| 服务端两平面 | `SecretsIT` | 5 条：保险箱全流程；metadata 的 `credentials_bound`；**声明点名缺的变量**（`credentials_required` 恒为声明值，`credentials_missing` 随绑定变化）；**控制台平面走同一套生命周期**（含缺 CSRF 的写被 403 拒）；**共享只能在会话平面建**（`/api/v1` 405、协作者建不了、`incoming` 只给收到的人） |
| CLI 新命令 | `go test ./...` | 新增 4 条：`secret share` 只打开页面、不发请求、地址带 `state=share&secret=&share=&user=`；`secret unshare` 发 DELETE；`secret incoming` 点名所有者；声明提示点名变量与命令 |

### 用例（T1–T15）

**未执行**：T1–T13 需要真栈、两个账号或两台机器（见 §环境与数据）。它们在验收前必须逐条跑过，尤其是两条成立条件 T1（跨机器）与 T5（共享撤销）。

**通过**：T14、T15（2026-11-09，证据见下表）。

### 密封与 KMS（2026-11-09）

| 项 | 命令 / 做法 | 结果 |
|---|---|---|
| 信封加密（T14） | `EnvelopeSealerTest`（8 条） | 通过：32 KiB 与空值往返一致；同一值两次密文不同；翻转密文末字节、翻转数据密钥首字节、长度字段置 0 或 0xFFFF、从 0 逐一截断到全长，每一种损坏都被拒；未知格式被拒 |
| sealer 配置（T14） | `SecretSettingsTest`（8 条） | 通过：`local`/`huawei`/`aliyun` 三值；缺 key-id、缺 region、半对 AK（两家都测）、非 Base64 的 local-key、未知值（含旧写法 `kms`）都按字段名拒绝；`huawei` 缺 AK 被拒、`aliyun` 缺 AK 放行；首尾空格被 trim |
| 服务端装配（T15 ②） | `SecretConfigurationTest`（2 条） | 通过：`huawei`/`aliyun` 选中信封 sealer、`local` 选中本地实现；两个 provider 各自选中自己的 client（构造用假 factory，真 client 的构造会打 IAM，只在真实部署里走） |
| 真实 KMS 往返（T15 ①） | 一次性程序走 `EnvelopeSealer` + `HuaweiKeyWrapper`，对生产 CMK（`cn-east-3`）；**手工、不入库** | 通过：32 KiB 值往返一致；4 次加密 69–104ms、解密 92–267ms（含 SDK 开销与跨云公网） |
| prod 拒绝 `local`（T15 ③） | `SecretSettingsConfigTest`（3 条）+ 实跑一次 `SPRING_PROFILES_ACTIVE=prod` + `sealer=local`（带合法 `local-key`） | 通过：单测覆盖 prod 拒绝 / prod 接受云 sealer / 非 prod 放行；实跑启动失败，报错点名 `prod` 与 `sealer` |
| KMS 不可达的回答 | `SecretExceptionAdviceTest`（1 条） | 通过：映射成 503 + `credential_vault_unavailable`，不是 500 |
| 部署闸门（T15 ④） | 干跑 `server.yml` 里的两段校验 | 通过：`SECRET_SEALER=local`、缺 `SECRET_KMS_KEY_ID`、`sealer=huawei` 缺 `SECRET_ACCESS_KEY_ID` 都在触碰主机前失败并点名；`sealer=aliyun` + AK 两半都空放行（只对 huawei 强制） |

已由自动化覆盖到的部分：T2（值不进输出）→ `TestSecretSetSendsTheValueAndEchoesNone`；T11（无绑定不发租约）→ `TestResolveDigestCarriesTheBindingsFlag`；T12 的「非终端不能提高上限」→ `TestSecretQuotaRaiseNeedsATerminal`；T5 的两条服务端事实（共享覆盖后加的绑定、撤销协作者即失效）→ `SecretsIT.aShareIsCreatedOnTheWebPlaneAndFollowsTheSkill`。

### T15 生产闸门与华为真机往返

- **前置**：华为云 DEW 已开通；一把 `AES_256` CMK（`cn-east-3`）；运行时 IAM 用户只授 `kms:cmk:encryptData` / `decryptData`；AK/SK 在 `.env`。
- **步骤**：① 用 `EnvelopeSealer` + `HuaweiKeyWrapper` 对生产 CMK 往返一个 32 KiB 的值（一次性程序，不入库；blob 形状由 `EnvelopeSealerTest` 覆盖，`SecretsIT` 覆盖 `local` 下 create→lease→delete 的端到端往返，不直接断言 `ciphertext` 列）；② 断言 `sealer=huawei` 时 `SecretConfiguration` 选中的是信封 sealer、且 `huawei` 与 `aliyun` 各自选中自己的 client；③ `SPRING_PROFILES_ACTIVE=prod` + `sealer=local` **并给一个合法的 `local-key`** 起服务端；④ 干跑部署工作流：`SECRET_SEALER=local`（其余必填给假值）、缺 `SECRET_KMS_KEY_ID`、`sealer=huawei` 缺 `SECRET_ACCESS_KEY_ID`、`sealer=aliyun` + AK 两半都空（应放行）各一次（缺 region / 缺 AK secret 与它们走同一段检查，未单独跑）。
- **期望**：① 往返逐字节一致；② 返回 `EnvelopeSealer`，且两个 provider 不串；③ 启动失败，错误点名 `prod` 与 `sealer`（不给 `local-key` 会先报缺 key，那是另一条闸）；④ 三处都在触碰主机之前失败并点名缺的变量。

## 已知问题

本版范围内没有未修的缺陷。

本版范围**外**、只在自动化里可见的一条：CLI 的三条 v25 自动更新用例（`TestTheCheckIsThrottledToOnceADay`、`TestANewerBuildIsInstalledAndTheCommandIsUntouched`、`TestATamperedPackageLeavesTheBinaryAlone`）在本机失败；同一条在未改动的 `HEAD`（`331babe`）上一样失败，属环境所致，与本版无关。

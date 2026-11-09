# v52-service-credentials · 技术设计

> **最后更新**：2026-11-09
> **所属版本**：[`README.md`](README.md)
> **写法**：v40 起只写相对 [`docs/current/`](../../current/) 的 **delta**——新建了什么、改了什么、删了什么；不复述现状。

## 架构

**新增一个模块 M16 `secret`**（`com.skillmasterai.modules.secret`），没有改任何现有模块的内部：

```
CLI ──────────► /api/v1/secrets* ──► SecretService ──► Sealer(本地 AES-GCM 加密值 + KMS 包 DEK) ──► Postgres（密文）
控制台 ───────► /web/secrets*（会话 + CSRF，见 §接口）
CLI 的 `secret share` ──► 页面动作：不调接口，打开控制台那一页（ADR 0126）
                                                                                   │
skmr run ──► Metadata（带 credentials_bound）──► /skills/{地址}/credentials/lease ──┘
                                              └──► Sealer(KMS 解开 DEK → 本地解密) ──► 明文只在这一个响应里
```

- **依赖方向**：**M16 自己只依赖 `common`**；`account` / `skill` / `audit` 是由 usecase 层（`usecase/*Secret*`、`SecretSkills`）调用的。这样 M16 不认识「skill 能不能被调用」，也就不必随 M12 的规则一起改；不碰 `token`（那是访问我们的密钥）。
- **值只有一处解密点**：`lease`。没有「读明文」接口，列表、详情、审计都不带值。
- **注入在 CLI**：`skmr run` 解析版本时从 `Metadata` 响应拿到 `credentials_bound`（对当前调用者为真/假）；为真才发一次 `lease`，把返回的 `env → value` 放进子进程环境。
- **两个「不注入」的分支，行为与今天一致**：
  - 响应说没有绑定 → 不发 `lease`，不多一次请求（验收 A12）；
  - 没有服务端答案（摘要钉在地址里、或离线用缓存）→ 不注入。摘要钉住的运行是离线路径，CLI 刻意不为它联网；要用凭据就用版本名或不带钉的地址。
- **环境变量优先级**：注入值覆盖本机已有的同名变量（绑定是更明确的意图），在 `skmr run --help` 里写清。
- **值的输入规则（2026-11-08 定）**：只从 **stdin** 读（`< 文件` 或管道），**不接受命令行参数**（会进 shell 历史与 `ps`），**也不从终端读**（终端回显、留在滚动缓冲）；终端上直接跑会被拒并给出两种交法。字节**原样保存**——尾随换行也保存，因为 PEM 的最后一行换行属于值本身；唯一做的是在值以换行结尾时于 stderr 提醒一句（`echo` 会多带一个），不静默改了它。空值与超过 `CredentialValue.MAX_BYTES`（32 KiB；CLI 侧同名常量 `secretValueLimit`，以服务端为准）在本地先拒。
- **配额的确认口径（2026-11-08 定）**：`提高`要**交互终端确认**（不是 `--yes`——那是旗标，刚撞上限的 agent 也会打；要拦的正是它），非终端环境直接拒绝并给出「让用户来跑」的命令；`降低`/`不变`不要确认（只拿走自己的权限）。
- **密封形状（2026-11-09 定，[ADR 0172](../../decisions/0172-the-root-key-is-huawei-cloud-kms.md)）**：**信封加密**（为什么必须信封、KMS 单次明文上限与我们的值上限差多少，见 ADR 0172，这里不重复）。`Sealer.encrypt` 在进程内生成一把 32 字节 DEK，用 AES-256-GCM 加密值本身，再把 DEK 交给 KMS 包起来；`decrypt` 反向。blob 形状是 `0x01（格式版本）| u16 大端 wrapped-DEK 长度 | wrapped DEK | 12 字节 IV | GCM 密文+tag`，Base64 后存进 `ciphertext`。`Sealer` 接口与用例一层不变；KMS 调用次数也不变——**每个凭据**写一次 wrap、租约时一次 unwrap（一次租约涉及几个凭据就是几次，`PgSecretService.lease` 按凭据逐条解密）。
- **KMS 配置**：`secret` 下六组键（`sealer`、`kms-key-id`、`region`、`access-key-id`、`access-key-secret`、`local-key`）已在 `application.yml` 里，生成视图是 [`config.md`](../../current/config.md)。`sealer` 取 `local` / `huawei` / `aliyun`：生产用 `huawei`（华为云 DEW，独立 IAM 用户只授 `kms:cmk:encryptData` / `kms:cmk:decryptData`），`aliyun` 保留但生产不用，`local` 只给开发与测试（32 字节密钥）。**两个云取值都必填** `kms-key-id` 与 `region`；**AK/SK 只对 `huawei` 必填**（这台 ECS 在阿里云上，华为的默认凭据链取不到东西），`aliyun` 可留空走同机 RAM 角色；只给一半在启动时被拒。部署闸门按 `sealer` 判断：`huawei` 时缺 AK/SK 即失败，`aliyun` 不要求。**`local` 的拦法**：`prod` profile 激活时启动失败；部署单元里 `SPRING_PROFILES_ACTIVE=prod`，部署脚本也拒绝 `local`，两道都写在 [`deploy/README.md`](../../../deploy/README.md)。`sealer=local` 时 `local-key` 为空则**任何环境都起不来**——宁可起不来，也不用临时密钥封用户的值。**换 `sealer` 要重新封存**：blob 里不记 provider，旧密文在新实现下解不开（v52 上线时库里没有旧值；上线之后切换要先迁移）。

### 模块边界（这一版必须守住的）

- **M16 只发布显式接口**：`SecretService`（用例入口）、`CredentialRef` / `CredentialBinding` / `CredentialLease` / `CredentialValue` / `IncomingBinding`（值类型）、`SecretSettings`（装配用）与它的错误码进 `ModuleMap.publishedTypes`；表、KMS、配额算法都在 `internal` 包里。换掉内部实现不影响任何调用方。
- **`credentials_bound` 在 API / usecase 层组合**：先取 skill 的版本元数据，再调 `SecretService.boundEnvNames`（用户, skill, 是否仍是协作者），两件事在边界上拼起来。**不让 skill 模块去问 secret**——那会造出一条 `skill → secret` 的模块边，两个模块从此互相牵着走。
- **CLI 与控制台只认 HTTP 契约**：CLI 不 import 服务端类型；服务端对 CLI 的兼容承诺是「加字段可以，改字段要开版本」。
- **共享只在页面上完成**（[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 第一类例外）：写入共享的接口只存在于 `/web/secrets*`（会话 + CSRF），`POST /api/v1/secrets/{id}/shares` 下线。CLI 有 `secret share`，但它是**页面动作**（[ADR 0126](../../decisions/0126-page-only-actions-open-the-page.md)）——CLI 的 `internal/api` 里**没有**共享写入方法，撤销（`UnshareSecret`）与读取（`ReadSecretShares`）才有。

## 数据模型

四张表，都属于 M16。**值是密文，`lease` 是唯一解密点。**

```
service_credential
  id TEXT (ULID, pk)
  owner_user_id            -> app_user
  name                     给人看的标签；同一 owner 内唯一
  ciphertext TEXT          自描述 blob 的 Base64（格式版本 + KMS 包过的 DEK + IV + 本地密文，见 §架构「密封形状」）
  byte_len                 明文长度；只记录（上限由 CredentialValue 判）
  per_day_limit int        默认 500，上限 10,000
  created_at / rotated_at / last_used_at
  -- 没有 key_version：KMS 的密文自带 CMK 与版本；格式版本在 blob 头上
  -- 删除是硬删；审计里留一条 credential_delete

credential_binding
  id TEXT (ULID, pk)
  credential_id            -> service_credential
  owner_user_id            -> app_user   （从凭据冗余下来，见下）
  skill_id                 -> skill
  env_name                 text        （注入到子进程的变量名）
  created_at
  unique (owner_user_id, skill_id, env_name)
  -- 一个用户在一个 (skill, env) 上最多一条**生效**绑定：创建/绑定时在服务层校验
  -- （自己的绑定优先于共享来的；两条自己的同名绑定直接拒绝）

credential_use               -- 按天聚合，不逐次落审计
  credential_id, day, count, last_used_at
  pk (credential_id, day)

credential_share
  id TEXT (ULID, pk)
  credential_id            -> service_credential   **共享按「凭据 + skill」算**
  skill_id                 -> skill
  grantee_user_id          -> app_user
  granted_by               -> app_user（只能是凭据 owner）
  created_at, revoked_at
  unique 活跃行 (credential_id, skill_id, grantee_user_id)   -- 部分索引，revoked_at IS NULL
```

**约束落点**：`(owner, skill, env)` 既是服务层的检查（`credential_binding_conflict`，给出句子），也是**数据库唯一约束**——`owner_user_id` 冗余到绑定行上就是为了这个：两个并发请求不会都通过检查。共享对象必须是该 skill 的已接受协作者（`skill_grant` 存在），且不能是自己。

**共享行的键是 (凭据, skill)，不是绑定**：一行授权覆盖这把凭据在这个 skill 上的**每个**变量——现在绑的和以后才绑的都算；**读取按凭据解析**。P1 的初稿把它挂在绑定上（`credential_binding_id`），那样「共享之后才绑的新变量」会漏掉，「删掉一条绑定」又会连带撤销授权，两件都错。协作者身份在 lease 时由 M12 的 `Access.source`（`shared` 才认共享）复核，撤销协作者即等于撤销共享。

## 接口

**两个平面，一套用例。** CLI 用 API key 走 `/api/v1`；控制台用会话 + CSRF 走 `/web/secrets*`（照 `/web/keys` 的样子）。两边调用的是同一批 usecase，差别只在 `AuthenticatedSubject` 怎么来（`ofSession` 与 `ofBearer`），所以权限规则不会分成两套。

**共享的写只在一个平面**：`POST /api/v1/secrets/{id}/shares` 在 P2 下线（405），只有 `/web/secrets*` 能写。撤销（`DELETE`）与查看（`GET`）两个平面都留着——拿回授权不是高风险，而 `skmr secret shares` / `unshare` 要用。配额（`PATCH`）两个平面都留着：提额要人确认这条由 CLI 的 TTY 与界面的就地输入承载，关掉 API 一边挡不住真想取明文的人（他本来就能取租约）。

所有写操作都走现有权限校验，没有新角色。

| 方法 | 路径 | 入参 | 出参 / 语义 |
|---|---|---|---|
| POST | `/api/v1/secrets` | `{name, value, skill, env}` | 建一条值并绑第一个 skill；返回 id、name、binding。**值不回显** |
| GET | `/api/v1/secrets?skill=` | — | 列表：`id`、`name`、`bindings`、`per_day`、`used`、`last_used_at`。**没有值** |
| PUT | `/api/v1/secrets/{id}/value` | `{value}` | 换掉值；保留绑定与共享 |
| DELETE | `/api/v1/secrets/{id}` | — | 硬删 + 审计；绑定与共享随之失效 |
| PATCH | `/api/v1/secrets/{id}` | `{per_day}` | 调整上限；1–10,000 |
| POST | `/api/v1/secrets/{id}/bindings` | `{skill, env}` | 再绑一处；冲突返回 `credential_binding_conflict` |
| DELETE | `/api/v1/secrets/{id}/bindings?skill=&env=` | — | 删一条绑定，值还在 |
| ~~POST~~ | ~~`/api/v1/secrets/{id}/shares`~~ | — | **P2 下线**（405）：写共享只在 `/web/secrets*`。行留着是为了说明它曾经存在、以及为什么被搬走 |
| DELETE | `/api/v1/secrets/{id}/shares?skill=&user=` | — | 撤销，下一次运行立即生效 |
| GET | `/api/v1/secrets/{id}/shares?skill=` | — | 谁在用（只读） |
| GET | `/api/v1/secrets/incoming?skill=` | — | **别人共享给我的**变量：`env`、`skill`、凭据名字、主人 handle。只列仍然算数的共享（不再是协作者就为空） |
| POST | `/api/v1/skills/{ns}/{name}/credentials/lease` | — | 返回 `{values:[{env,value}], expires_at}`；`Cache-Control: no-store`；明文不进日志。按写权限算（它把明文交给调用方），所以只读 API key 取不到租约。**KMS 调用有 2 s 连接 / 5 s 读超时；失败回答 503 `credential_vault_unavailable`，不在事务里重试**，明文不因失败而返回 |

**控制台平面（P2）**，路径与形状**按需对应** `/api/v1`（两处例外：`lease` 没有控制台对应，它是运行时的；写共享只在这里，API 平面那条已下线），认证换成会话 + CSRF：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/web/secrets?skill=` | 列表。**两个页面都拉全集**：凭据页直接显示，技能设置页在页面内按 `binding.skill === skill` 过滤，因为它同时要「绑定下拉列出全部凭据」。`?skill=` 只给 `incoming` 用 |
| POST | `/web/secrets` | 新建值并绑第一个 skill；**值不回显** |
| PUT | `/web/secrets/{id}/value` | 更新值 |
| DELETE | `/web/secrets/{id}` | 硬删 |
| PATCH | `/web/secrets/{id}` | 调整上限（页面就地输入） |
| POST | `/web/secrets/{id}/bindings` | 再绑一处 |
| DELETE | `/web/secrets/{id}/bindings?skill=&env=` | 解绑 |
| GET | `/web/secrets/{id}/shares?skill=` | 谁在用 |
| POST | `/web/secrets/{id}/shares` | **共享的唯一入口**（会话 + CSRF） |
| DELETE | `/web/secrets/{id}/shares?skill=&user=` | 撤销 |
| GET | `/web/secrets/incoming?skill=` | 别人共享给我的变量（技能设置页的「共享自 X」） |

**版本解析响应**：`Metadata` 增加三个字段（wire 一律 snake_case，与 `when_to_use` 同一条约定）：

- `credentials_bound`（boolean，对当前调用者）——「这次运行要不要发 lease」的唯一依据，不加新请求；
- `credentials_required`（数组，作者声明的 env 名，所有人一样）——取自版本的 frontmatter `metadata.requires.credentials`；
- `credentials_missing`（数组，对当前调用者还缺的那几个）——服务端用「声明 − 这个调用者实际可用的绑定」算。**必须由服务端算**：只有它知道共享算不算数（M12 的 `Access.source`）。

三个字段都在同一次 metadata 读里给，`run` 不多发请求。旧 CLI 忽略新字段；新 CLI 对旧服务端读不到字段时，**它读取的两个字段逐字段取缺省**：`credentials_bound` 缺省 `false`（不发 `lease`）、`credentials_missing` 缺省空数组（不打印提示）。（CLI 目前不读 `credentials_required`——作者声明由服务端算完差集后以 `missing` 下发。）

**声明格式**（作者写在 `SKILL.md`）：

```yaml
metadata:
  requires:
    credentials:
      - WEB_SEARCH_API_KEY
```

只认 `metadata.requires.credentials` 这一处（不像 `when_to_use` 有顶层别名——这个字段是 v52 新加的，没有历史要兼容）。值不是数组、或元素不是字符串时，当作没有声明：一个写坏的声明不该让 skill 读不出来。声明的 env 名与绑定**按字符串精确比较**，大小写不同就是两个变量。

**错误码**（沿用现有 `ErrorCode` 风格，新增）：`credential_not_found`、`credential_binding_not_found`、`credential_binding_conflict`、`credential_name_conflict`、`credential_share_conflict`、`credential_not_shareable`（不是该 skill 的协作者）、`credential_share_not_found`（撤销时这个人在这把凭据上没有生效共享）、`credential_quota_exceeded`（消息里带当前上限、凭据 id、可执行的 `skmr secret quota <id> --per-day <N>`，以及「不要自行提高」）、`credential_value_too_large`（> `CredentialValue.MAX_BYTES`，即 32 KiB）、`credential_vault_unavailable`（云 KMS 不可达或拒绝，503；与 `internal_error` 分开，因为调用方应当稍后重试而不是报 bug）。

**CLI 命令面**（与 PRD 映射表一致）：

```
skmr secret set [--name <名字>] --skill <地址> --env <NAME>    # 值从 stdin；--name 省略时用 env 名
skmr secret list [--skill <地址>]                              # 永不输出值
skmr secret rotate <id>                                        # 值从 stdin
skmr secret rm <id> --yes
skmr secret bind <id> --skill <地址> --env <NAME>
skmr secret unbind <id> --skill <地址> --env <NAME>
skmr secret shares <id> [--skill <地址>]                       # 只读
skmr secret incoming --skill <地址>                            # 别人共享给我的，只读
skmr secret share <id> --skill <地址> [--user <用户名>]        # 页面动作：打开控制台，自己不写
skmr secret unshare <id> --skill <地址> --user <用户名>        # 直连 DELETE，拿回授权
skmr secret quota <id> --per-day <N>                           # 提高需 TTY 确认
```

**`share` 是页面动作，不是直连**（ADR 0081 第一类例外 + ADR 0126）：首句「还没执行：共享只能在网页上由人确认」，然后打开 `控制台/account/secrets?state=share&secret=<id>&share=<skill>&user=<handle>` 并说明「已在浏览器里打开」。**`state`、`secret`、`share` 必带**：`state` 才让页面落在共享那一屏，`share` 预选 skill；给了 `--user` 时再带 `user` 预选人，没给就在页面上选。少带 `state`，人就被丢在列表上自己找那一行。`--print` 只打印地址；`--json` 给 `{action, performed:false, url, opened}`——与 `skills publish` 的四个字段同形。**它不调任何接口**，所以服务端把 `POST /api/v1/secrets/{id}/shares` 关掉之后，CLI 这条路依然是通的。**这也是 ADR 0127 预检的一个例外**：那条预检读的是「作者页」上的档位，属于 skill 级动作；凭据共享是账号级动作，没有可读的档位，页面本身会按登录状态只显示自己的凭据——真跑错，人会在列表上看见「没有这一条」，而不是走到一个能按下去的按钮。

**`unshare` 直连**：撤销是拿回授予出去的访问，可重新共享，不进页面。控制台里它仍要弹窗（[ADR 0056](../../decisions/0056-dangerous-actions-are-confirmed-in-a-modal.md)：不染红，但要确认）。

**超限与缺凭据的文案**：`skmr run` 在 `credentials_bound` 为真但 `lease` 失败时明确失败，不静默降级；缺绑定的声明提示在 `skmr skill check` 与运行前给出「让用户执行 `skmr secret set` 命令」，并写明「不要把值贴进对话」。**提示只提示**：`credentials_missing` 非空时照常执行，脚本拿不到变量——这一点在提示的最后一行说出来，否则用户会把「运行成功」当成「凭据生效了」。**没有元数据就没有提示**（摘要钉住、离线缓存），与「不为离线路径联网」是同一条取舍（开放问题 1）。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| **P1** | M16 + 信封密封（华为云 KMS / 阿里云备选）+ 四张表 + 创建/列表/更新值/删除/绑定/解绑 + `lease` + 配额 + 审计 + **共享的 `/api/v1` 接口**（共享与撤销的写入暂无 CLI）+ `skmr secret`（set/list/rotate/rm/bind/unbind/quota/shares）+ `run` 注入 | A1–A4、A9–A13 里与保险箱和注入有关的部分；A15 |
| **P2** | 声明提示（`metadata.requires.credentials` → `credentials_required` / `credentials_missing`，`run` 与 `skill check` 打印）+ 控制台的 `/web/secrets*` 端点 + 「权限 › 服务凭据」页与技能设置面板 + `skmr secret share`（页面动作）/ `secret unshare` / `secret incoming` + 共享的写从 `/api/v1` 下线 | A5–A8、A14；A5 是成立条件之一 |

## 开放问题

1. **摘要钉住的运行要不要注入**——本设计选「不注入」（不为离线路径联网）。如果作者反馈「摘要钉住也要凭据」，再决定是否为它加一次显式请求。
2. **测试用 KMS**——`sealer=local` 在测试里就够（`application-test.yml` 给一把固定的 32 字节密钥）；信封那一层用**假 `KeyWrapper`** 测往返与篡改，**不给测试开 CMK**（那要一整套云账号权限）。华为云真实链路在实现时做一次人工往返并记进 [`test-plan.md`](test-plan.md) 的「结果」；生产就用同一个 CMK，不另开。
3. ~~`lease` 的租期~~——**已定**：`LeaseCredentialsUseCase.TTL` = 5 分钟；一次运行内够用，超时由脚本侧重跑，不做续租接口。
4. **版本解析响应加字段的兼容性**——旧 CLI 忽略新字段；新 CLI 对旧服务端读不到字段时按「没有绑定」处理，不发 `lease`。字段由 API 层组合（skill 元数据 + `SecretService.boundEnvNames`），不在 skill 模块里算。
5. ~~控制台实现拆几个单元~~——**已定（2026-11-08）**：一条 PR 交完（两处界面 + 端点 + 声明提示），因为它们是同一个验收面（A5–A8、A14），拆开会让「共享只在页面」半途成立。
6. ~~共享与配额的服务端闸~~——**已定（2026-11-08）**，两半结论相反：**共享的写**收进 `/web/secrets*`，`POST /api/v1/secrets/{id}/shares` 下线；**配额**留在两个平面——拿得到 API key 的人本来就能取租约拿明文，关掉提额挡不住他，只会把终端里的操作撵去网页。撤销共享也留在两个平面（拿回授权不是高风险）。
7. **非 UTF-8 的值**——这一版按**文本**承诺（API key / cookie / PEM / JSON）。服务端以 UTF-8 还原、CLI 走 JSON，二进制字节会被替换成 U+FFFD。要真字节就在 lease 里用 Base64，代价是每个消费方多一步解码。
8. **阿里云实现什么时候做真机验证**——它保留但不被生产使用（[ADR 0172](../../decisions/0172-the-root-key-is-huawei-cloud-kms.md)），目前只有编译与配置校验。倾向：**等到真要用它的那天再联调**，现在只保证它与华为实现共用同一个 `Sealer` 契约。

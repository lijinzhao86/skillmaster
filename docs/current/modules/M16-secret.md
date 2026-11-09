# M16 · 服务凭据

> **状态**：已实现（v52，P1–P2） · 拥有表与对外发布类型见 [`system.md`](../system.md) 的对应行（源是 `ModuleMap`）

## 职责

保管用户交给我们的一段**第三方服务值**（API key、cookie 之类的文本），并决定它**能到达哪个 skill 的哪个环境变量**。值以密文保存，只有一处解密出口；绑定是唯一的暴露面。

## 边界

- **不认识身份密码**：托管的是可以轮换、可以撤销的 API 凭据，不是登录密码（[ADR 0168](../../decisions/0168-hosted-credentials-are-api-keys-not-identity-passwords.md)）。
- **不代转第三方请求**：这一版只做注入，不做本地反代或平台代理（[ADR 0169](../../decisions/0169-the-first-version-injects-without-proxying.md)）。
- **不判断谁能用某个 skill**：可见性与协作者档位是 M12 的事实，由用例层问过再进来；M16 只认 `skillId`、`userId` 与一个「这份共享还算不算数」的布尔。M16 自己的包只 import `common`。
- **不认识版本**：绑定属于 skill，不属于某一版；因此 lease 不接受版本钉。
- **共享只在页面上完成**：写共享的接口只存在于会话平面（`/web/secrets*`），`POST /api/v1/secrets/{id}/shares` 是 405。CLI 有 `skmr secret share`，但它是**页面动作**——只打开控制台那一页，自己不写（[ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 第一类例外 + [ADR 0126](../../decisions/0126-page-only-actions-open-the-page.md)）。撤销不走页面（拿回授权不是高风险），`DELETE` 两个平面都有。

## 不变量

1. **值只在一个响应里离开平台**：`SecretService.lease`（`Cache-Control: no-store`）。列表、详情、审计都不带值，也没有「读明文」的方法。
2. **注入按绑定解析，从不按值**：`(credential, skill, env)` 一条绑定；一个 skill 没有绑定就永远拿不到。
3. **同一用户在同一 `(skill, env)` 上最多一条生效绑定**：`owner_user_id` 冗余到绑定行上，`UNIQUE (owner_user_id, skill_id, env_name)` 是硬约束（并发下也成立），服务层先查一次只为了给出句子（`credential_binding_conflict`）。自己的绑定优先于共享来的。
4. **共享按「凭据 + skill」算**：一行授权记 `(credential_id, skill_id, grantee_user_id)`，**读取按凭据解析**——之后在同一个 skill 上新增的变量同样属于这份授权，删掉一条绑定也不会连带撤销（反过来，把行挂在绑定上两件都会错）。对象必须是该 skill 的已接受协作者，且不能是自己（`credential_not_shareable`）；协作者身份在 lease 时由 M12 的 `Access.source == "shared"` 复核，**撤销协作者即等于撤销共享**。`incoming` 是它的反方向：**别人共享给这个人的那些变量**，带着凭据主人，给控制台技能设置页用。
5. **配额按凭据计、按天聚合**：一次 lease 里同一凭据服务多个变量只记一次；扣减是带条件的自增，超限整笔回滚（`credential_quota_exceeded`，消息里带当前上限、凭据 id、可执行的提高命令与「不要自行提高」）。
6. **删除是硬删**：绑定、使用记录、共享随外键一起消失；备份保留期过后不可恢复。
7. **根密钥在云端 KMS，值用信封封**：一把数据密钥（AES-256-GCM）在进程内加密值，只有这把 32 字节的密钥交给云 KMS 包起来；本模块只存这段自描述 blob 的 base64（TEXT），`lease` 是唯一解开它的地方。`sealer` 取 `huawei`（生产）、`aliyun`（保留的备选）或 `local`（本机与测试）。`prod` profile 激活时 `local` 起不来，部署脚本也拒绝它（[ADR 0172](../../decisions/0172-the-root-key-is-huawei-cloud-kms.md)）。

## 对外契约

`SecretService`（用例入口）、`CredentialRef`、`CredentialBinding`（含 `Share`）、`CredentialLease`、`CredentialValue`、`IncomingBinding`、`SecretSettings`（装配用），以及七个异常：`SecretNotFoundException`、`CredentialBindingNotFoundException`、`ShareNotFoundException`、`BindingConflictException`、`NotShareableException`、`QuotaExceededException`、`SecretValueTooLargeException`，外加 KMS 故障的 `CredentialVaultUnavailableException`（回答 503，与「值坏了」的 500 分开）。

`credentials_bound` **不在这里算**：它由 API 层把版本元数据与 `SecretService.boundEnvNames`（用户, skill, 是否仍是协作者）拼起来（`credentials_bound` 就是那个集合非空），否则会造出一条 `skill → secret` 的模块边。

## 为什么这样切

凭据会继续长——组织级共享、平台代转、上传扫描都属于这条线，而它们各自都会改表。把它做成一个只发布类型的模块，换 KMS、换配额算法、加一张共享表都不动调用方；反过来，如果让 skill 模块去问「有没有绑定」，两个模块从此互相牵着走，下一次改哪边都要先看另一边。

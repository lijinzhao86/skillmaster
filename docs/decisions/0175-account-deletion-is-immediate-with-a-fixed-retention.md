# 0175 · 账号注销立即生效；个人内容删除，协作与公开内容按归属保留

- **状态**：已采纳
- **日期**：2026-11-09
- **依赖**：[ADR 0081](0081-every-console-operation-has-a-cli-command.md)（注销是高风险例外）、[ADR 0176](0176-publishing-transfers-ownership-out-of-the-personal-namespace.md)（公开市场条目的归属）
- **修订单**：本条取代同日早先「账号行 30 天后物理删除」的草案——所有者 2026-11-09 在平台实践调研后改为按内容归属处理。

## 背景

平台要公开上线，手机号注册 + 公开内容，必须有一条用户可执行、平台可兑现的账号注销路径。今天没有任何注销入口；凭据页已经写了「备份保留 30 天」，但账号这条路径没有对应的承诺与实现。

难点不在「删不删」，在**内容归属**：一个人的 skill 可能被分享给别人、被收进别人的库，甚至（将来）发布到公开市场。注销时把这些一并删掉，会让依赖它的他人流程断掉；一律保留，又违背删除权。

## 决定

- **立即生效、不可撤销**。确认后：全部会话立即失效；全部 API 密钥立即失效；该账号个人命名空间下的 skill 对任何人立即不可调用；账号立即不能登录。
- **30 天保留期**。期满时删除该账号的个人数据（手机号、密码、会话、密钥、服务凭据、同意记录、通知）与**个人命名空间下的全部内容**（skill / 版本 / 文件 / 自己库里的引用）。
- **账号行不物理删除，改为去标识化保留**：
  - `phone_hash` / `phone_enc` / `email` 置空，`credential` 删除，`handle` 改成 `deleted-<id>`，`display_name` 写「已注销账号」，`purged_at` 记清理时刻；`id`、`status`、`created_at`、`deleted_at` 保留。
  - 保留这一行，是因为别人命名空间里的 `skill.created_by`、`skill_version.submitted_by`、`skill_grant.granted_by`、`skill_invitation.invited_by` 都指向它；删行会挡住外键或抹掉别人的历史。
  - 显示层按 `status='deleted'` 显示常量「已注销账号」，不把 `deleted-<id>` 当用户名展示。
- **按内容归属保留**（2026-11-09 调研后定）：
  - **个人命名空间**：删除，**包括已经分享给别人的**。分享不创造永久连续性；对方会失去访问。
  - **别人命名空间里的贡献**：保留，作者去标识化为「已注销账号」。
  - **公开 / 官方内容**：保留，作者去标识化为「已注销账号」；公开条目的归属见 [ADR 0176](0176-publishing-transfers-ownership-out-of-the-personal-namespace.md)。
  - **已下载到本机的副本**：平台收不回，文本里披露。
- **分享关系的通知**：注销时，对个人命名空间里仍有活跃 `skill_grant` 或 `skill_library_join` 的 skill，给接收者发一条通知：作者已注销，该 skill 不再可用。不提供迁移窗口，因为生效是立即的。
- **访问日志保留 6 个月**，注销不删除它，因为[《网络安全法》](https://www.gov.cn/xinwen/2016-11/07/content_5129723.htm)第二十一条要求网络日志留存不少于六个月；日志里不含密码、验证码或凭据值。
- **自动备份按平台现有周期轮转**（RDS 当前 7 天），旧备份随之消失；不为了注销单独导出或长期保存备份。
- **注销只在控制台**，是 [ADR 0081](0081-every-console-operation-has-a-cli-command.md) 的高风险例外（2026-11-09 产品负责人确认）。
- **30 天内同一手机号与用户名不可重新注册**；期满去标识化后释放。
- **注销尝试有按用户的频控**（2026-11-09 定）：密码比对前计数，规则见技术设计。
- 文本、控制台文案与实现里的三处保留期（个人数据与个人内容 30 天、日志 6 个月、备份 7 天）必须同一口径；内容归属的四条也要与条款一致。

## 理由

- **为什么账号行不删**：`skill.created_by` 与 `skill_version.submitted_by` 是 `NOT NULL` 且无级联；`skill_grant.granted_by` / `skill_invitation.invited_by` 也是。物理删行只有两条路——删掉别人命名空间里的内容，或改作者；两者都会破坏协作方的资产。去标识化保留一行是代价最小、最诚实的做法。
- **为什么个人空间里已分享的 skill 也删**：地址绑在个人命名空间上（`用户名/skill名`）。要保住地址就得永久保留用户名与命名空间，等于把一次临时分享升级成永久占位。GitHub 的做法也是「注销前把仓库转给组织，否则一起删」，而不是注销时自动转移。
- **为什么他人命名空间里的贡献保留**：内容属于那个命名空间；作者注销不是那个命名空间的注销。Gitee 用户协议 16.3 明确「不删除用户贡献到其他仓库的内容」；GitHub 把这类贡献改挂到 `ghost` 用户。
- **为什么公开/官方内容保留**：公开内容有依赖者。npm 明确公开包不随账号删除自动下架，撤包是单独政策；知乎把已发布内容改名为「知乎用户+随机字符」保留；Stack Overflow 保留帖子、去掉署名。这与 [ADR 0176](0176-publishing-transfers-ownership-out-of-the-personal-namespace.md) 的「发布即转出个人空间」同一方向。
- **为什么用「去标识化」而不是「匿名化」**：PIPL 第七十三条把两者分开；去标识化后仍是个人信息，必须披露，不能宣称已匿名。内容里若还含有姓名、手机号等，仍可能重新识别，靠举报/下架处理。
- **为什么日志 6 个月**：法定下限，不是产品选择；与个人数据的 30 天分开写。

## 后果

- 需要 `app_user.deleted_at`、`app_user.purged_at` 与一条清理任务：`deleted_at` 开窗，`purged_at` 记清理完成的时刻并让任务不再重复选中同一账号。`legal_consent` 新表。账号行永久保留但去标识化；`handlesOf` 对 `status='deleted'` 返回「已注销账号」常量（30 天窗口内 `display_name` 还是空的）。
- **清理顺序**：先删该账号拥有的 namespace（级联 skill / version / file / grant / library join / embedding），再删 `credential` / `api_key` / `namespace_member` / `notification_recipient` / `service_credential` / `credential_share(grantee)` / `legal_consent` / `identity`，删 `phone_verification` 里该号码的行；**再删别人命名空间里指向他的个人行**——`skill_library_join`、`skill_grant(grantee_id)`、`skill_invitation(invitee_id)`、`skill_access_request(requester_id)`、`my_tag_definition`、`skill_usage_day`；最后把 `app_user` 行去标识化（不删）：手机号与 email 清空、`handle` 改 `deleted-<id>`、写 `purged_at`。`skill_grant.granted_by` / `skill_invitation.invited_by` / `skill_version.submitted_by` 指向保留行，不需要改。
- **注销请求本身要幂等**：只有 `active → deleted` 的状态迁移真正发生，才下架 skill、吊销 key、发通知、写审计；第二个并发请求返回成功但不再重复副作用。
- **blob 回收**：`version_file` 删除后，内容寻址的 blob 可能仍被引用；用 `BlobStore.deleteUnreferenced`（已有按引用计数、宽限期与 `blob_deletion` 的语义）在删除后跑一次。
- **通知**：注销请求里给被分享者发通知，使用 M14 的通知表；skill 在 30 天后物理删除时，通知随 `skill_id` 级联消失，这是可接受的。
- **登录要能说清「已注销」**：只有密码已经比对通过、账号状态为 `deleted` 时才回答 `account_deleted`；密码不对仍回答「手机号或密码不对」。前者不构成账号存在性预言机。
- **日志留存要真的到 6 个月**：今天 nginx 只留 14 天（`rotate 14`），journald 没有显式上限；这一版要把两处都调到 ≥6 个月。
- **RDS 备份是 7 天**（2026-11-09 核 `DescribeBackupPolicy`：`BackupRetentionPeriod: 7`）；30 天是主数据与个人内容的保留期，不是备份保留期。
- **公开市场那一版要在发布时实现所有权转出**（[ADR 0176](0176-publishing-transfers-ownership-out-of-the-personal-namespace.md)）；这一版只写原则与条款，不实现。
- **执业律师审阅放在内测版上线之后**（2026-11-09 定）：先上，再请律师看责任上限、免费服务的免责边界与内容归属四条，以免审稿卡住内测。审后要改的，按新版本号重出文本并通知用户。

## 出处

- 平台实践（2026-11-09 调研）：[GitHub Personal account reference](https://docs.github.com/en/account-and-profile/reference/personal-account-reference)（自己名下仓库删除、别人仓库里的贡献改挂 `ghost`、用户名 90 天后释放）、[Gitee 用户协议 16.3](https://gitee.com/moark/docs/pulls/101.patch)（删除自己名下仓库，不删贡献到别人仓库的内容）、[知乎用户注销协议](https://www.zhihu.com/term/deactive)（已发布内容保留并改为匿名名称）、[npm: Deleting your user account](https://docs.npmjs.com/deleting-your-npm-user-account/)（公开包不随账号删除自动下架）。
- 法规：[《个人信息保护法》第四十七条、第七十三条](https://flk.npc.gov.cn/detail?title=%E4%B8%AD%E5%8D%8E%E4%BA%BA%E6%B0%91%E5%85%B1%E5%92%8C%E5%9B%BD%E4%B8%AA%E4%BA%BA%E4%BF%A1%E6%81%AF%E4%BF%9D%E6%8A%A4%E6%B3%95&id=ff8081817b6472a3017b656cc2040044)；[《网络安全法》第二十一条](https://www.gov.cn/xinwen/2016-11/07/content_5129723.htm)。

# 0009 · 四轮循环审计：修 2 个 blocker、6 个 high，以及一批「绿而无证」的测试

> **日期**：2026-10-02
> **所属版本**：[`../README.md`](../README.md)
> **类型**：小版本迭代——**未改变对外可验收的能力边界**

## 改了什么

范围是**当时全部未提交的改动**（M01 服务端 + `skillmaster-web/` 前端 + 文档，约 12k 行）。每轮换一批**全新的、互不共享上下文**的 agent，各自拿到一份定级程序（blocker/high 必须附可复现的失败场景，拿不准就往低报并标 `uncertain`），主 agent 逐条复现后才动手。**四轮里有两轮抓到的是上一轮修复本身引入的缺陷**，这是这类循环唯一值得存在的原因。

### 安全与正确性（服务端）

- `usecase/LoginUseCase.java` —— 按地址的登录预算**只在失败时**计（原先连成功登录也计、且永不清零：同一出口 IP 第 51 次登录就 429，公司 NAT 后面等于群体性登不进去）。第一版改法把该规则的判断放在鉴权之后，于是它的拒绝会**整笔回滚**、把手机号的计数一并抹掉——单个账号的猜测遂无上限。现为 `@Transactional(noRollbackFor = ThrottledException.class)`。
- `modules/account/AuthThrottle.java`、`internal/PgAuthThrottle.java` —— 接口拆成 `countLoginAttempt(phone)` 与 `recordLoginFailure(clientIp)` 两条。
- `modules/account/internal/{CaptchaRepository,PhoneVerificationRepository}.java`、`{CaptchaService,PhoneVerificationService}.java` —— 核销从 check-then-act 改成**条件 UPDATE**：先原子领取一次尝试（`attempts < :cap` 在 `WHERE` 里）再比对；`consume` 带 `consumed_at IS NULL` 并返回是否抢到。并发下同一个验证码不再能兑换两次，3 次上限也不再能被击穿。
- 同两个仓储的 `insert` —— 发新码时把同号同用途的未消费行一并置为已消费。原先「新的覆盖旧的」只写在注释里，没有任何覆盖：新码被用掉之后，旧码会重新被 `findLatestUnconsumed` 选中，在它剩余的有效期里仍然可用。
- `modules/account/AesGcmPhoneCipher.java` —— `decrypt` 的「太短」判据补上 16 字节认证标签（原先 13–27 字节的值会穿到 provider，抛出的是 `ProviderException` 而不是文档承诺的那个异常）。

### 安全与正确性（前端）

- `pages/LoginPage.vue` 的 `returnTo()` —— `return_to` 的 open redirect。第一版只比 origin，仍然可绕：**同源** URL 的 pathname 以 `//` 开头时（`https://本站//evil.example/`），交出去的字符串会被 `location.assign` 二次解析成新的 authority。现在把要交出去的那个字符串**再解析一次**比 origin，并把「解析回本页」的情形也算作没请求。两轮里这条改了三次。
- `api/client.ts` —— `await response.text()` 原先在 `try` 之外：响应头到了、body 传输中断（或超时正好落在读 body 阶段）会 reject 掉 `request()` 本身，`App.vue` 永远停在「加载中…」、登录按钮永久禁用。现在同guard；空 body 的成功分支限定为 204；15 秒超时对两个**等短信通道**的端点放宽到 45 秒，且超时单独回 `timeout` 码与「服务响应太慢」文案，不再冒充网络故障。
- `composables/useSession.ts` —— session 读取的 500/网络错误原先被显示成「还没有登录」（静默吞掉），现改为「无法确认登录状态」。
- `composables/useCodeRequest.ts` —— 发码**结果未知**（超时/网络错误）时不再当作「什么都没发生」：服务端是先消费验证码、再等短信通道，所以短信可能已经在路上。现在打开验证码那一步并换一张图，否则用户手里有码却用不了。

### 一批「绿而无证」的测试

审计的核心发现之一是：**若干声称被覆盖的行为没有任何一条能让它失败的用例**。补齐的是：按地址的短信与登录限流（原先六条规则里两条无人能测）、重置流程的猜错计数（改掉 `noRollbackFor` 则 6 位重置码可在 5 分钟内被枚举到账号接管，而全套测试仍绿）、短信码「用过即废」的成功路径、「新码作废旧码」、会话 id 轮换（原用例先登出，于是新 id 怎么都会变——绿得没有意义）、`X-Forwarded-For` 真的被读（这是唯一能让 `forward-headers-strategy` 被删掉时变红的用例）、密码策略的 8/72 两个数字、AES 短值判据的每一档长度、captcha 尝试数、CSRF 比对的是值而不只是「头在不在」。

## 为什么

不是 bug 报告，是**在提交前主动做一次独立审计**。选循环而不是一轮，是因为前几轮的经验说明：一次审计的修复本身会引入新缺陷，而只有让全新的 agent 去看修复后的代码才会暴露。这一轮里两个 blocker 都是**上一轮修复引入的**，另外三条的修复本身也有洞（`return_to` 改了三次、登录预算的两次语义调整、`WebLoginIT` 那条断言写错了自己的前提）。

被否掉的做法：只修 blocker/high（medium 里藏着 `AesGcmPhoneDecrypt` 的判据、核销的竞态、超时冒充网络故障——都不是印象分能筛掉的）；不写测试只改代码（「绿而无证」正是这次的主要产出之一）。

**没做的**：不给消息加可变模板、不引入 i18n、不动限流窗口的边界偶发性（要注入 `Clock`，是独立改动）、不动「短信外呼在数据库事务内」（改的是花钱那条路的事务边界，失败模式有界且可见）。这几条在最终报告里标为 ACCEPTED 并给了理由。

## 影响

- **对外可验收的能力边界未变**：没有新端点、没有新页面、没有改验收标准。改的是拒绝的**理由与时机**、限流的分桶、以及一批注释与文档。
- **一处配置是新的且是部署前提**：`server.forward-headers-strategy: framework` —— 取舍与「代理必须覆写该头」的理由另开 [ADR 0018](../../../decisions/0018-caller-address-behind-the-proxy.md)；ADR 0015 正文里那段 nginx 片段在该行上作废（已在 0015 文件头标出）。
- **计数**：服务端 **307 → 320**（170 单测 + 150 集成，`clean verify` 约 11 秒）；前端 **117 → 131**。
- **同步的文档**：本文件、`test-plan.md` §结果 与 §已知问题、[`M01-account-login.md`](../../../architecture/modules/M01-account-login.md) 的限流与登录两节、`decisions/README.md` 与 0015 的文件头。
- **是否需要新开 ADR**：需要，见 [ADR 0018](../../../decisions/0018-caller-address-behind-the-proxy.md)（跨版本：这个服务怎么知道调用方是谁，以及代理必须做什么）。其余修复不触及跨版本取舍。

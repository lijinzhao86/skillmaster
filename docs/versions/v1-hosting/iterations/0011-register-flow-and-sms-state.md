# 0011 · 注册流程重做：两页、首个发码免图形验证码、逐字段校验；以及短信比对暂时关掉

> **日期**：2026-10-02
> **所属版本**：[`../README.md`](../README.md)
> **类型**：小版本迭代——**未改变对外可验收的能力边界**

## 改了什么

### 服务端

- `PgAuthThrottle` 新增三条规则：`sms:free`（86 400 秒 / 1，按调用方地址，**认领**语义而不是计数语义）、`username:ip`（900 秒 / 60）、`register:policy`（900 秒 / 120）；`AuthThrottle` 相应新增 `claimFreeCodeSend` / `freeCodeSendAvailable` / `countCodePolicyRead` / `countUsernameLookup`。
- `SendVerificationCodeUseCase`：发码前先认领免费额度（**只给注册**，地址取不到就 `fail closed` 不认领），认领到手才跳过 `captchas.consume`；新增 `captchaNeeded(purpose, clientIp)`。
- `CheckUsernameUseCase`（新增）与 `GET /web/username/availability?username=`：先判格式，再问 `AccountRegistrar.handleTaken` **与** `NamespaceService.slugExists`——两个都要问，因为注册的守卫是 `UNIQUE(app_user.handle)` 与 `UNIQUE(namespace.slug)` 两条。保留名 `skillmaster` 不是这一路要挡的（V2 给系统账号起了同名 handle，靠约束挡住它）；这一路覆盖的是**没有同名 handle 的 slug**，v1 不产生、schema 允许。
- `GET /web/register/code/captcha-required`（新增，**只读**：不认领也不消耗）+ `CaptchaRequirement`。
- `UsernamePolicy`：下限 3 → **6**，形状 `^[a-z0-9][a-z0-9-]{5,29}$`；前端 `validation.ts` 同步。
- `CodeComparison`（新增枚举）与 `PhoneVerificationService` 接受它：`ANY` 时不比对，**其余全保留**（没请求过就拒、5 分钟过期、只能用一次、三次尝试）。`SmsConfig` 从 `skillmaster.sms.accept-any-code` 装配它（默认 `false`），并在「配了 AccessKey 又开着它」时**启动失败**。

### 前端（`skillmaster-web/`）

- 注册拆成**一个路由内的两步**：页 1（用户名 / 手机号 / 密码）→「下一步」发码 → 页 2（六位验证码）。状态零损失，没有引入路由。
- `useFieldChecks`（新增）：三态（检查中 / 通过 / 不通过）、失焦与实时两条来源、用户名走 400 毫秒防抖的异步查重、过期响应丢弃。**字段下面可能出现三句话，按产出者分开、生命周期各自写死**（规则判定每次重判都改写；服务器拒绝连同它当时针对哪个值一起存，值一换就不显示；问不到服务器那句只在没别的可说时显示），页面提交改为交给校验器登记——分工是「服务端提供能力、校验逻辑封装在前端」。
- `FieldFeedback`（新增，取代 `FieldError`）：消息优先于结论；图标用 Heroicons 的原路径。
- `CodeInput`（新增）：六格验证码——**一个真 input** 摊在六个方框下面（`autocomplete="one-time-code"` 是 iOS 肯把刚收到的码递进来的唯一理由），方框只负责显示与接点击。
- 注册**成功屏**：提交成功不再跳转，页 2 原地换成成功卡片（用户名、命名空间、`用户名/名称` 的地址形状、不可更改的提醒、一句诚实的「还没有下一步」）。
- 找回密码页：固定每次都过图形验证码（`captchaFromTheStart`）。
- 错误文案专业化、必填改放 placeholder、密码加显示/隐藏。

## 为什么

三件事挤在一轮里，因为它们是同一个问题的三面：

1. **摩擦落在最不该落的地方**：一页五个字段、图形验证码每次都要、用户名重名要等到最后提交才知道——而那时短信已经发出去了。
2. **首个发码免图形验证码是一次放宽**，取舍与被否掉的选项写在 [ADR 0020](../../../decisions/0020-first-code-send-without-a-captcha.md)，这里不重述。
3. **短信一时半会发不出去，流程却要先能走通**：签名来源已收窄到只剩「企事业单位名」与「已注册商标名」，而且审核通过后还要运营商报备 7–10 个工作日（核实到的原文、日期与出处记在 TD §8 问题 12）。所以用 `accept-any-code` 顶上——**它只摘掉比对这一步**，其余一律保留，这样真码接上时流程的形状一个字节都不用改。

## 影响

- 新增两个只读端点，`/web/*` 从 8 个变成 **10 个**（TD §4.4、架构 M01 与那张图已同步）。
- **`accept-any-code` 是上线前必须关掉的口子**：默认 `false`，只在本地 `.env` 里开着，而且配上凭据还开着会启动失败。它不构成「已经能发短信」的证据。
- 注册成功不再直接跳首页——这是有意的：用户名不可更改，那一刻是它唯一一次「新」，而首页那一屏对回访用户和新注册的人长得一模一样。
- 测试：服务端 170 单测 + 171 集成（新增 `WebAcceptAnyCodeIT` 5 例、`WebUsernameAvailabilityIT` 8 例，`WebCaptchaIT` 扩到 17 例）；前端 203 例（含注册两步流程与 `CodeInput` 6 例）。**其中十几例是循环审计补的**，最重的两条高危（环境变量盖过测试配置、发码失败却仍前进到验证码那一步）连同其余修复与复现，见 `test-plan.md` §结果第九轮。
- **注册这条已经在浏览器里对着真服务端走通过**（2026-10-02）——这是 `skillmaster-web/` 第一次以真服务端为准被走查，此前它只被类型检查、测试与构建验证过。

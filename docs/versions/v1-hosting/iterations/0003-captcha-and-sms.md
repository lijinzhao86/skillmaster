# 0003 · 图形验证码与阿里云短信

> **日期**：2026-10-01
> **所属版本**：[`../README.md`](../README.md)
> **类型**：小版本迭代——**未改变对外可验收的能力边界**

## 改了什么

### 1. 图形验证码（`V4__captcha.sql`）

`0002` 把图形验证码记成「明确没做」，这一轮做了。它是 [ADR 0013](../../../decisions/0013-phone-login-and-username-slug.md) 三条防刷里的第三条，也是唯一能挡住**多 IP** 攻击者的一条：冷却与日限都按手机号或 IP 计数，换个地址就绕开了。

- **新表 `captcha`**：`id`、`answer_hash`、`attempts`、`expires_at`、`consumed_at`、`created_at`。形状与 `phone_verification` 一致——哈希存答案、单次使用、短时有效、限尝试次数。
- **新迁移 `V4` 而不是并进 `V3`**：V3 已经在本机应用过，改它就是校验和失配，代价是每台已有库重建一次。多一个版本号不要钱。
- **新端点 `GET /web/captcha`** → `{captcha_id, image}`，图片是 base64 的 PNG。这一面上唯一的匿名 GET，存在的意义就是被别的端点依赖。
- **两个发短信的端点改为必须带验证码**：`/web/register/code` 与 `/web/reset/code` 的请求体加 `captcha_id` 与 `captcha_answer`。**登录没有加**——它不花钱，而且已经有「同手机号 15 分钟 10 次」的退避。
- **签发也限流**（`auth_throttle` 新增 `captcha:ip`，每地址每小时 120 次）。这个数比别的规则宽得多，因为画图不花钱，它管的是「匿名调用者能多快让这张表长行」。顺带在插入路径上清扫过期行——签发是这里最便宜的写，不扫就无界。

### 2. 阿里云短信（`AliyunSmsSender` + `SmsGateway`）

- **用官方 SDK**：`com.aliyun:dysmsapi20170525:4.6.0`。签名、时钟偏移、重试、endpoint 解析都是它已经做对的事，而做错的代价是「用户永远收不到码」。
- **SDK 只出现在 `config/SmsConfig` 一个文件里**。`modules/account` 定义 `SmsGateway`（一个方法，返回「收没收下」+ 提供方自己的说法），`AliyunSmsSender` 只做两件可测的事：把模板参数拼成模板期望的形状，以及**把「提供方拒了」变成异常而不是成功**——阿里云对一条被拒的短信答的是 HTTP 200，失败在响应体里，所以「调用返回了」和「短信发出去了」是两件事。
- 凭据为空时仍然是 `LoggingSmsSender`；**凭据有、签名或模板为空则启动失败**——那两个不是可选项，缺了每一条都会被拒，而「启动正常、每条都失败」是被收不到码的用户发现的。

### 3. 依赖

| 依赖 | 体积 | 为什么接受 |
|---|---|---|
| `cn.hutool:hutool-captcha:5.8.47` | 19 KB + `hutool-core` 1.47 MB | 三个候选里唯一活跃维护的（最后发版 2026-07-09，另两个停在 2025 上半年）。**只用它画图**，见下 |
| `com.aliyun:dysmsapi20170525:4.6.0` | ≈3.5 MB（okhttp 4.12 + gson 2.13 + kotlin-stdlib 2.3 + tea-*） | 官方 SDK，签名的正确性不由我们担保。代价是这一条把**第二套 JSON 栈**带上 classpath——已实测，不是猜的 |

## 为什么

两件事都是 [`prd.md`](../prd.md) 验收 #1 与 TD §7 的 P1 里剩下的，而且都是「不做就等于没做」的那类：没有验证码，两个发短信的端点就是一台匿名可用的短信费用发生器；没有真短信，注册与找回密码在真实环境里一步都走不通。

### 一个必须写在代码里的发现：Hutool 的默认答案是可预测的

`HutoolCaptchaRenderer` 的类注释记了细节，这里记结论：**它的默认 `RandomGenerator` 经由 `RandomUtil.getRandom()` 取随机数，而那个方法返回的是 `ThreadLocalRandom`**（对着 `hutool-core` 5.8.47 的字节码确认，不是推测）。`ThreadLocalRandom` 不是密码学安全的，看过几个答案就能推出下一个——那样的验证码不是「弱」，是**装饰**。

所以答案由我们自己给：Hutool 的 `CodeGenerator` 可以从构造函数注入，注入的是一个 `SecureRandom`。库留给真正值得复用的部分（字形渲染、逐字旋转、干扰线），安全的那一半自己拿着。

## 影响

**已交付行为**：`/web/register/code` 与 `/web/reset/code` 的请求体多两个字段，这是**对外契约的变化**，但那一面还没有任何外部客户端（CLI 还没做），所以没有兼容代价。其余五个端点、`/api/v1/**` 全部未动。

**一个覆盖不到的地方要如实说**：`AliyunSmsSender` 与阿里云的**真实调用没有被执行过**——没有凭据、签名与模板也还没过审，而这两件事都不在代码这一侧。被验证的是它这一侧的全部：模板参数形状、拒绝会被抛出来、接受则安静通过（`AliyunSmsSenderTest`，用假的 gateway）。**要跑真的，见 `application.yml` 里那几个 `SKILLMASTER_SMS_*`，以及 [TD §8 问题 12](../technical-design.md)。**

**文档同步**：

| 文档 | 改了什么 |
|---|---|
| [`technical-design.md`](../technical-design.md) §3.1 | 加 `captcha` 表 |
| 同 §4.4 | 端点表加 `GET /web/captcha`，并写明两个发码端点多两个字段 |
| 同 §7 P1 | 图形验证码从未做移到已完成；短信客户端从「不写」移到「已接、未验证」 |
| 同 §8 问题 12 | 加上「代码这一侧已完成，未验证的是审核与凭据」 |
| [`test-plan.md`](../test-plan.md) | 用例表加验证码一组；§结果与§已知问题补这一轮 |
| [`architecture/modules/M01-account-login.md`](../../../architecture/modules/M01-account-login.md) | 拥有的表、验证码不变量、实现状态里的两张表 |

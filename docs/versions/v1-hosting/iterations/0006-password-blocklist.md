# 0006 · 密码策略加黑名单（以及为什么没加字符类）

> **日期**：2026-10-02
> **所属版本**：[`../README.md`](../README.md)
> **类型**：小版本迭代——**未改变对外可验收的能力边界**

## 改了什么

### 1. 一份 vendored 的名单

- 新增 `skillmaster-server/src/main/resources/account/password-blocklist.txt`：SecLists 的 `Passwords/Common-Credentials/10k-most-common.txt` **逐字节复制**（10001 条、73026 字节、sha256 `68782d6a…`、MIT © 2018 Daniel Miessler）。
- 新增 `modules/account/PasswordBlocklist.java`：从类路径加载，两种失败都**响亮**（文件不在、条数低于 1000），查找大小写不敏感，解析对 CRLF 安全。来源、校验和与「这份名单覆盖不到什么」都写在类注释里。
- 接线在 `AccountConfiguration.passwordBlocklist()`，与 `captchaRenderer()` 同一处、同一个理由：**这是防御的形状，不是部署的事**；而且放在这里意味着文件缺失是**启动失败**，不是某个人第一次注册时的 500。它传给 `AccountService`（模块自己的守卫）与 `RegisterAccountUseCase`（先于短信码消耗的快速失败）。

### 2. 策略多两条派生规则、多一个码

- `PasswordPolicy.problemWith` 多一个参数（黑名单）。判序：空 → 太短 → 太长 → 与用户名相同 → 与手机号相同 → **常见**。
- 两条新规则：**以 handle 开头且其后只剩数字**（`demo-user123`、`demo.user.2026`）；**密码里出现手机号**（`a13800138000`、`13800138000!`）。黑名单判等，不判包含——`password-and-then-some-more` 必须通过，否则被拒的是这条策略想鼓励的那个长口令。
- 新 issue 码 **`too_common`**，一个码盖两种成因：对用户而言它们是同一件事——换一个。

### 3. 前端镜像两条派生规则，**不**镜像名单

`validation.ts` 加 `basedOnHandle` 与 `containsPhone`（同规则、同顺序），`ISSUE_MESSAGES` 加 `too_common`。**黑名单不下到浏览器**：几千条数据换一次**不花短信费**的往返，不划算。这是有意的缺口，写在文件里而不是省略掉。

### 4. 测试

| 位置 | 加了什么 | 条数 |
|---|---|---|
| `PasswordPolicyTest` | 黑名单命中、大小写不敏感、**包含但不等于必须通过**、handle + 数字（含**handle 自己以数字结尾**、大小写与标点）、手机号在任意位置、长口令不因握有 handle 被误伤 | 6 → 12 |
| `PasswordBlocklistTest`（新） | 真文件能加载且 >9000 条、大小写、**断言逐字节的 sha256**、CRLF/空行/前后空格解析、文件缺失与条数不足两种响亮失败、过短的条目由「太短」先拒 | 6 |
| `WebRegistrationIT` | **端点真的查了**：黑名单命中与 handle 派生各一条 | +2 |
| 前端 `validation.test.ts` / `errors.test.ts` | 两条派生规则 + 边界（长口令不误伤）+ 无用户名时的重置页；词表加 `too_common` | +4 |

**计数**：服务端 **285 → 299**（162 单测 + 137 集成，`clean verify` 整条 12.1 秒）；前端 **105 → 109**。

## 为什么

触发是一句反问：「密码八位，不止应该要求大小写特殊字符数字吧，可以参考 Apple 的注册逻辑。」

查证之后发现**两边都对**：Apple 的注册表单确实要求四类字符（现查，2026-10-02），而 NIST SP 800-63B 与 OWASP 明确反对组合规则、并要求黑名单。取舍、被否掉的那条为什么被否，全部记在 [ADR 0016](../../../decisions/0016-password-blocklist-not-composition.md)——本文不复述理由。

过程上有两点值得记：

1. **先查再答，没有凭印象。** 我原先的印象是「Apple 只要数字 + 大写」，查完发现那是旧版说法，注册表单是四类。既然拿它当参照物，就不能凭记忆描述它。
2. **没有自己编名单。** 「常见弱口令」是一个数据主张，手写一份等于把意见包装成数字的权威。名单是 vendored 的真实数据，带来源、许可证与校验和，并且有一条测试断言那个校验和。

## 影响

- **对外契约**：多一个 issue 码 `too_common`，密码多一条被拒的理由。**存量账号不受影响**——登录从不校验密码形态，策略只在注册与重置被调用，所以不需要任何人重置密码。
- **一条实现缺陷是集成测试抓出来的**：派生规则若写成「去掉密码尾部的数字再与 handle 比较」，当 **handle 自己以数字结尾**时会漏 —— 后缀剥离会吃掉 handle 的末位数字。`randomUsername()` 生成的正是 `u` + 16 位十六进制（天生于数字结尾），所以它一跑就红；用手挑的 `demo-user` 永远抓不到。已改成「以 handle 开头、其后只剩数字」，两侧同改。
- **名单的边界要写在明处**：10001 条里只有 **2087 条长到能被提交**（其余先被 8 字节下限拒掉，是死重量不是漏洞），且整份名单偏英文语料，中文场景的高频弱口令只覆盖一部分。产品侧不要对外承诺「挡住了所有弱密码」。
- **文档同步**：新增 [ADR 0016](../../../decisions/0016-password-blocklist-not-composition.md) 与索引行、[`M01-account-login.md`](../../../architecture/modules/M01-account-login.md) 新增 §密码、[`test-plan.md`](../test-plan.md) §结果记这一轮。
- **不需要新开大版本**：没有新增任何用户可以做的事，验收标准未变。

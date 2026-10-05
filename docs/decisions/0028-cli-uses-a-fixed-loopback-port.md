# 0028 · CLI 的回调用固定 loopback 端口

- 状态：已采纳
- 日期：2026-10-03
- 相关：[0011](0011-server-and-cli-stack.md)、[0023](0023-spring-authorization-server-with-our-own-storage.md)、[0026](0026-overriding-the-framework-for-public-clients.md)

## 背景

按 [RFC 8252](https://www.rfc-editor.org/rfc/rfc8252.html) §8.4，loopback 重定向的 `redirect_uri` **除端口外精确匹配**——原生应用的监听端口通常是操作系统给的临时端口，每次都不一样，逐字比对会让它永远对不上。§7.3 因此推荐用临时端口。

Spring Authorization Server 不实现这条例外。它的授权端点校验是 `registeredClient.getRedirectUris().contains(requestedRedirectUri)`——一个集合成员判断，没有任何端口相关的逻辑。

写 M2 时确认了这一点，并去找了挂钩。**唯一能插入这条规则的地方会连带毁掉别的东西**：`OAuth2AuthorizationCodeRequestAuthenticationValidator` 上只有 `setAuthenticationValidator(Consumer)`，而它**整体替换**框架的校验组合，那套组合里包含 grant_type、code_challenge 与 prompt 的校验。要放过端口，就得把整个授权端点的安全校验重写一遍。

这就撞上了 [ADR 0026](0026-overriding-the-framework-for-public-clients.md) 自己写下的那条规则：**覆盖只能认领「没人处理的那种形状」**。这里不是没人处理——是有人处理，而且处理得比我们要写的那版更可信。

## 决定

**CLI 用一个固定的 loopback 端口**，在客户端注册时逐字登记（`http://127.0.0.1:<固定端口>/callback`），授权请求里带的就是它。这段流程与逐字匹配的框架天然吻合，不需要任何覆盖。

端口由 CLI 内置，不是每次随机；**被占用时给一个说得清的报错，不静默换端口**——静默换端口会让回调打到没有监听的地方，表现成「登录卡住」，而不是「端口被占」。

## 理由

**被否掉的方案：加第四处覆盖，让随机端口能过。** 它符合规范推荐，也让端口彻底不成问题。否掉它有两个独立的理由，任一条都足够：

1. **代价是重写安全关键校验。** 不是加一条规则，是把框架那套替换掉，而替换品要自己保证 grant_type / code_challenge / prompt 三条都还在。这三条里任何一条写漏，都不会有测试变红——只会有一个窄一点的口子。
2. **它违反 [ADR 0026](0026-overriding-the-framework-for-public-clients.md) 刚立下的规则。** 那条规则是从一次真实的踩坑里总结出来的（覆盖写得太宽，把带密钥的刷新请求也认领了，破坏了本来能用的路径）。决定立下就绕过它，等于那条规则只在方便的时候成立。

**固定端口不是「退一步的将就」，是这条生态里的常规做法**：`gcloud`、`gh`、`az` 都用固定端口。规范推荐临时端口解决的是**端口冲突**与**被其它本地进程猜到**两个问题；前者表现为一个清楚的报错，后者在这个场景里不构成攻击（能连上那个端口的进程已经在用户的机器上了）。

**与 PKCE、state 的关系没有变化。** 临时端口带来的那点防护，本来就不在这条链的承重结构里：授权码一次一码且分钟级，绑定 PKCE 与 `state`，而 `state` 由 CLI 生成并逐字比对。端口是否随机不参与其中任何一条。

## 后果

- **注册行里写死一个端口**，所以换端口＝改部署种子（[ADR 0011](0011-server-and-cli-stack.md) 说 v1 的客户端就是手工种一行，这没有增加新的负担）。
- **同一台机器上多个 CLI 实例可能抢这个端口**。v1 的形态是一次 `skillmaster login`，不是并发多开；真出现时正确的答案是一个端口范围或按需重试，而不是回到随机端口。
- **`AuthorizationServerIT` 已经按这个形状写**（`http://127.0.0.1:51004/callback` 逐字注册），所以这个决定不是纸上的——它现在就是被测的形状。
- **「临时端口 + 第四处覆盖」没有被永久否掉**，只是被排到后面：等第三方客户端（CIMD）进来、授权流程本来就要再动一次时，它可以在那时连同一条新 ADR 一起做。届时要重写的是同一套校验，而现在没有第二个理由去碰它。

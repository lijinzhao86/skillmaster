# ADR 0018 · 反代后面的「调用方地址」：显式认转发头，且代理必须覆写它

- **状态**：已采纳
- **日期**：2026-10-02
- **依赖**：[ADR 0015](0015-web-frontend-stack.md)（生产同源反代）、[ADR 0013](0013-phone-login-and-username-slug.md)（按手机号与地址的限流）

## 背景

M01 有三条**按调用方地址**的限流规则：短信按地址（30/小时）、登录按地址（50 次失败/15 分钟）、图形验证码按地址（120/小时）。它们的键是「调用方地址」，而这个地址从哪来，在 ADR 0015 定下「生产由反向代理拼合同源」之后就成了一个必须回答的问题。

`WebAccountController.clientIp()` 读的是 `request.getRemoteAddr()`，也就是 socket 对端。直连时这是对的；按 ADR 0015 摆在 nginx 后面，它就是**代理的**地址——于是三条规则塌成**一个全站共享的桶**：第 31 条短信之后，注册与重置的「发码」对所有用户回 429，直到窗口结束。同一件事还有第二半：两个 cookie 的 `Secure` 由 Spring 依据 `request.isSecure()` 决定，而它要认 `X-Forwarded-Proto`。

Spring Boot 的行为是：`server.forward-headers-strategy` 不设时默认 `NONE`，但会**回落到云平台自动探测**——Kubernetes 上会自己打开。目标部署是 EKS，所以生产上「可能恰好没事」，而任何纯 VM / 非云的反代后面都不会。**这种「取决于平台探测」的静默差异本身就是问题**：同一份代码在两种环境下一个有防护一个没有。

选项：

1. **不认转发头**，把注释改成实话，等部署时再说。代价是那三条规则在最可能的部署形态下作废，且 cookie 不带 `Secure`。
2. **认转发头**，由服务端显式声明。
3. 用 `native` 策略（Tomcat 的 `RemoteIpValve`）+ nginx 的 `real_ip` 模块，信任跳数写在容器侧。

## 决定

**选 2。** 服务端在 `application.yml` 里显式写 `server.forward-headers-strategy: framework`，不吃 Boot 的平台自动探测。

**前提是代理必须覆写该头**，而不是追加：

```nginx
    # 覆写，不是 $proxy_add_x_forwarded_for：后者把调用方自己送来的值保留在最左、把真实地址
    # 追加在后面，而 Spring 取的是最左值 —— 那等于让调用方自选限流桶。
    proxy_set_header X-Forwarded-For   $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Forwarded         "";   # RFC 7239 头会被优先解析，同样要清掉
```

## 理由

**为什么不能让那句「代理是唯一入口」当前提就够。** 第一轮把这条写成「只有能直连端口的人才能伪造这个头」。查证 Spring 的实现后这是错的：`ForwardedHeaderUtils` 取 `X-Forwarded-For` 的**最左**值（`Forwarded` 头存在时还优先用它），而 ADR 0015 原本那段 nginx 用的是 `$proxy_add_x_forwarded_for`——它**保留**调用方的值并追加真实地址。最左值因此是调用方写的。所以「只能经代理访问」并不足够：**只经代理的调用方照样能自选桶**，三条按地址的规则可以靠换一个头的值逐条绕过，其中 `sms:ip` 正是那份注释里写的「唯一能注意到拿名单扫的人的规则」。

**为什么是显式声明而不是自动探测。** 自动探测只在云平台上生效，等于把「这个服务有没有按地址限流」交给运行环境决定，而且没有任何东西会让它在缺失时发声。显式一行，两种环境行为一致，且能被测试钉住。

**为什么不用 `native`（选项 3）。** 它能做同样的事，但信任配置落在容器/代理侧（`set_real_ip_from` 的跳数），与本仓库「服务端行为写在 `application.yml`」的习惯分家；而且它同样要求代理覆写或信任跳数正确，并不少一件事。

**为什么不是选项 1。** 那三条规则存在的理由（钱与暴力破解）在真实部署形态下正好最需要它们；放弃等于把「按地址」这半边的防护在唯一要用的地方关掉。

## 后果

- **部署时 nginx 必须覆写**：ADR 0015 正文里那段片段的 `X-Forwarded-For` 一行因此作废，见上面的正确写法。**若哪天把服务直接暴露到公网（不经代理），这个设置会让调用方自造该头自选桶**——所以它与「只经代理入」是绑定的，这一条写进了 `application.yml` 的注释里。
- **cookie 的 `Secure` 跟着解决**：`request.isSecure()` 认 `X-Forwarded-Proto`，代理发 `$scheme` 即可。ADR 0015 里那条「未核实」因此部分关闭，剩下的只有「部署是否真的发了这个头」。
- **测试**：`WebCaptchaIT.theCallersAddressIsTheOneTheProxyForwarded` 用两个不同的转发地址断言两个计数桶——这是唯一一条在这行配置被删掉时会变红的用例。它测不出「取最左还是最右」（一次请求只送一个值），所以**代理必须覆写**这条前提靠人守，不靠测试。

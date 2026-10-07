# 0027 · 框架的 attributes 里，principal 存框架自己的类型

- 状态：已采纳
- 日期：2026-10-03
- 相关：[0023](0023-spring-authorization-server-with-our-own-storage.md)、[0014](0014-browser-session-via-spring-session.md)、[0021](0021-opaque-tokens-not-jwt.md)

## 背景

授权服务器的 attributes 里有一项是本项目进不去的：`java.security.Principal` 键下的**授权主体对象**。框架把当前登录者写进去，而在这个部署里，那个对象的类型是 M1 的 `WebAuthentication`。

我们的存储要把 attributes 序列化成 JSON 再读回来（因为 PKCE 要读回 `OAuth2AuthorizationRequest`，见 [0023](0023-spring-authorization-server-with-our-own-storage.md)、V6）。读到 principal 时炸了：

```
InvalidTypeIdException: Could not resolve type id
'com.skillmasterai.modules.auth.WebAuthentication' as a subtype of `java.lang.Object`:
Configured `PolymorphicTypeValidator` ... denied resolution
```

框架带的多态类型校验器**只放行 Spring Security 自己的类型**，这是它在做本职工作而不是一个缺陷：按存储的 JSON 里写的类型名去实例化任意类是标准的反序列化攻击面。

有两条路：**放宽那个白名单**，或者**换掉存进去的那个值**。

## 决定

**换值。** attributes 落库时，principal 那一项被换成框架自己的规范类型 `UsernamePasswordAuthenticationToken`，名字（`getName()`，也就是 userId）与权限原样带过去。除此之外 attributes 逐字入库。

读的人要的只有名字 —— 授权主体的 userId 在行里还另有一份（`principal_name`），attributes 里这一份是给框架自己用的。框架恰好为这个类型带了专门的编解码器（form login 放进去的就是它），所以往返是框架的既有路径，不是我们发明的。

## 理由

**被否掉的方案：把 `com.skillmasterai.modules.auth.WebAuthentication` 加进多态白名单。** 它更小，但它用一层真实的防护换一个便利。那条防护挡的是「谁能写 attributes，谁就能让服务端实例化任意类」；把它开一个口子，就要论证「本项目的类里没有可用作 gadget 的」，而这个论证在以后每加一个类时都要重做一次。

**换值方案没有这个负担**，因为它不扩大可实例化的类型集合 —— 用的还是框架本来就允许的那个。代价是 attributes 里的一项不再逐字保存，这个代价写在 `TokenStore.attributesToStore` 的注释里：**不是裁剪** —— 没有丢掉任何键，也没有改变任何键的含义，换的只是同一个主体的一种表示。

**为什么不是「干脆把 principal 删掉」。** 那是真的裁剪，而 V6 的注释明说这个包不该被我们修剪。少一个键在今天的流程里可能没人读，但「今天没人读」与「这一项不该在」是两件事，而前者会随时间变成后者。

## 后果

- **attributes 的往返保真度不再是 100%，而是一个可以说清的例外**：一个键、一个已知的替换、原因与拒绝的替代方案都记在这里。以后新增 attributes 项时，这个例外不会扩散 —— 它只针对主体对象。
- **本项目的 `Authentication` 实现不进任何序列化白名单**，包括以后可能出现的其它存储。这条比这个决定本身更值得记住。
- **换进来的类型是 `UsernamePasswordAuthenticationToken` 而不是一个自造的中立类型**：框架对它有一等支持，而自造类型会立刻撞上同一个白名单问题。
- 如果将来某个流程开始读回 principal 的**其它**字段（比如权限），这个替换就会开始丢东西。今天没有任何流程这么做，这也是它至今成立的条件。

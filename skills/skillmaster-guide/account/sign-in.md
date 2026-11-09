# 登录

> 网页登录和命令行登录是**两件事**。网页登录只让浏览器认识你；命令行要单独 `skmr login`。**只做了前者，命令行仍然没有凭据。**

## 先判断用哪种登录

| 你的环境 | 怎么做 |
|---|---|
| 本机有浏览器 | `skmr login` |
| 容器、CI、服务器 | 创建一把 API key 放进 `SKILLMASTER_API_KEY` |

## 有浏览器

```bash
skmr login
skmr auth status
```

- **完成信号**：`auth status` 打印出账号名，而不是「本机没有凭据」。

## 没有浏览器

在 https://console.skillmasterai.com/account/keys 创建一把 `skm_…`，放到目标机器的环境变量里：

```bash
export SKILLMASTER_API_KEY=skm_...
skmr auth status
```

- **完成信号**：同上，打印出账号名。

## 登录时要注意的

- **授权页用的是浏览器当前的登录状态。** 想登录成另一个账号，先在浏览器里换，再跑 `skmr login`。见 [凭据：怎么换账号、怎么退出](credentials.md)。
- **换账号不用先 `logout`。** `login` 会替换本机凭据。
- **但如果设了 `SKILLMASTER_API_KEY`，`login` 换不掉它**——环境变量优先。先 `unset`，见 [凭据](credentials.md)。

## 登录出错怎么办

见 [登录不上](../trouble/sign-in.md)。

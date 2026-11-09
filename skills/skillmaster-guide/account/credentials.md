# 凭据：怎么换账号、怎么退出

> 网页登录和命令行登录是**两套**。换账号出问题，几乎都是只换了其中一边。

## 先看现在的状态

```bash
skmr auth status
```

`skmr auth status` 会告诉你：凭据来自哪里（系统钥匙串；钥匙串不可用时存文件；也可能是环境变量）、是哪个账号、什么时候过期、是 API key 还是一次 CLI 登录。

## 换账号

**正确顺序：先在浏览器里换成目标账号，再跑 `skmr login`。**

- **不用先 `logout`。** `login` 会替换本机凭据，并撤销被替换的那一次 CLI 登录。
- **如果上一份是 API key，它不会被撤销**，要去控制台的 API 密钥页自己撤。
- 为什么先换浏览器：授权页用的是浏览器当前的登录状态。浏览器还停在旧账号，`login` 会把凭据发回旧账号。

- **设了环境变量 `SKILLMASTER_API_KEY` 时换不了账号。** 环境变量优先于本机存储，`login` 写进去的凭据根本用不上。先 `unset SKILLMASTER_API_KEY`（或从容器、CI 的配置里删掉），再 `skmr login`，`logout` 也清不掉它。

- **完成信号**：`skmr auth status` 里的账号名变成目标账号。

## 退出

```bash
skmr logout
```

`skmr logout` **只清这台机器**，不让别的设备下线，也不撤销服务端记录。要彻底失效：

- CLI 登录记录在控制台「登录设备」里结束：https://console.skillmasterai.com/account/sessions
- API key 在 https://console.skillmasterai.com/account/keys 撤销

## 网页上退出

打开 https://console.skillmasterai.com/logout。

- **完成信号**：刷新控制台，回到登录页。

网页退出只影响浏览器这边——**网页退出了，命令行仍然是登录着的**；反过来，`skmr logout` 也不会让浏览器退出。

## 换账号与退出时要注意的

- **退出不会让别人的调用失效。** 它只是让这台机器上的命令行不再有凭据。
- **换完账号，之前用旧账号发的 skill 仍然属于旧账号。** 新账号看不到它们，除非被共享。

## 凭据出错怎么办

见 [登录不上](../trouble/sign-in.md)，以及 [登录设备与 API key](devices.md)。

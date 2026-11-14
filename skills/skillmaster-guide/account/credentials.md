# 凭据：怎么换账号、怎么退出

> 换账号先在浏览器里换成目标账号，再跑 `skmr login`；退出用 `skmr logout`。网页登录和命令行登录是**两套**，换账号出问题几乎都是只换了其中一边。

## 先看现在的状态

```bash
skmr auth status
```

`skmr auth status` 会告诉你：凭据来自哪里（默认是状态目录里的 0600 明文文件；`skmr login --keyring` 写系统钥匙串，写不进才回退明文；设了 `SKILLMASTER_API_KEY` 时以环境变量为准）、是哪个账号、什么时候过期、是 API key 还是一次 CLI 登录。

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

`skmr logout` 清掉本机副本。**CLI 登录还会结束服务端那一次登录**（别的设备与浏览器会话不受影响）；**API key 只清本机**，服务端那把要去撤销。服务端调用失败时，按输出去「登录设备」结束：

- CLI 登录记录：https://console.skillmasterai.com/account/sessions
- API key：https://console.skillmasterai.com/account/keys

## 网页上退出

打开 https://console.skillmasterai.com/logout。

- **完成信号**：刷新控制台，回到登录页。

网页退出只影响浏览器这边——**网页退出了，命令行仍然是登录着的**；反过来，`skmr logout` 也不会让浏览器退出。

## 换账号与退出时要注意的

- **退出不会让别人的调用失效。** 它只是让这台机器上的命令行不再有凭据。
- **换完账号，之前用旧账号发的 skill 仍然属于旧账号。** 新账号看不到它们，除非被共享。

## 凭据出错怎么办

见 [登录不上](../trouble/sign-in.md)，以及 [登录设备与 API key](devices.md)。

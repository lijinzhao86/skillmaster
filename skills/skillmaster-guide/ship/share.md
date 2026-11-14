# 共享给某个人

> 共享是**发邀请**，不是立即授权：对方在通知里接受之后才拿到档位。**默认给的是最低的「调用」**——说「让他管理」，就必须显式给管理。

## 先判断给哪一档

| 你要他 | 给 |
|---|---|
| 能用 | 调用 |
| 能看文件、能看到有哪些版本 | 浏览 |
| 能提新版本 | 写入 |
| 能上线、能丢弃别人的草稿 | 维护 |
| 能改可见性、能管别的协作者 | 管理 |

**删除与恢复仅限所有者**，共享给不了。

## 发邀请

```bash
skmr skill share <地址> --to <用户名> --role <invoke|browse|write|maintain|admin>
```

例：`skmr skill share <地址> --to <用户名> --role maintain`（能上线，不能改可见性）。

- **完成信号**：命令说「等待对方接受、30 天后过期」，并给出 `skmr skill shares <地址>`。对方接受后，`shares` 里才显示为协作者。
- 对方已有同级或更高的共享授权时，不发邀请，直接说明。

## 对方怎么接受

对方跑 `skmr notifications list`，按那行命令 `accept` 或 `decline`，见 [接受邀请、处理权限申请](notifications.md)。

## 改档、收回

```bash
skmr skill role    <地址> --user <用户名> --role <档位>
skmr skill unshare <地址> --user <用户名>
skmr skill shares  <地址>
```

`role` **只降不升**：降档直接生效；升档要用 `skmr skill share` 发邀请，对方接受后生效。还在等接受的邀请要对方先接受，或收回后重发。网页上在「设置」→「协作者」里改，升档同样是发邀请。

## 先看我有什么权限

```bash
skmr auth status <地址>
```

它回答「这份凭据在这个 skill 上能做什么」；换账号或不确定档位时先跑它。

## 我要申请更高的权限

被共享的人可以自己申请，不用等对方想起来：

```bash
skmr skill request <地址> --role write     # 申请某一档
skmr skill request <地址> --status         # 看进度
skmr skill request <地址> --cancel         # 撤回申请
```

申请会进对方的通知页，由所有者或「管理」批准，见 [接受邀请、处理权限申请](notifications.md)。

- **完成信号**：`skmr skill request <地址> --status` 显示你申请的那一档在等。

## 共享时要注意的

- **说的词要对上档位。** 「让他帮忙维护」「让他自己发版」都是 `maintain`；「让他管这个 skill」才是 `admin`。
- **共享和公开是两条路。** 只给一个人，用共享；给所有人，改可见性。两个都开，结果取并集。
- **拿不准就先给「调用」**，之后用 `skmr skill share` 发邀请往上调。

## 共享出错怎么办

- **提示你不是管理员**：共享只能由「管理」及以上发起，所有者一定有。
- **提示升档要发邀请**：`role` 只降不升，改用 `skmr skill share`。
- **用户或 skill 找不到**：核对用户名与地址。
- **对方说没收到**：邀请在对方的通知页，不在消息里；`skmr skill shares <地址>` 能看到谁还没接受。

# 接受邀请、处理权限申请

> 通知页放两类待办：别人共享给你的**邀请**（你接受才生效），和向你要更高档位的**申请**（你是所有者或「管理」，你批准才生效）。每条都自带下一步命令。

## 通知列表先看什么

```bash
skmr notifications list
```

默认 `attention`：待处理 + 未读。要全部用 `--status all`，只看未读加 `--unread`。

- **完成信号**：每条一行，下面给出可复制的下一步命令。

## 接受别人的共享

```bash
skmr notifications accept  <id>
skmr notifications decline <id>
```

- **接受之后才拿到档位**；还没拿到任何档位的私有 skill，在邀请被接受前对你仍然不存在。公开 skill 的公开档位在邀请之前就已生效。
- 邀请 30 天后过期。
- 接受要有写权限的凭据（`skills:write`）；只读 API key 不行，`skmr login` 换来的可以。
- **完成信号**：`skmr skills list --source shared` 里能看到它。私有 skill 还可以用 `skmr skill invoke <地址>` 验证；公开 skill 的公开档位本来就能 invoke，别用它判断邀请是否已接受。`skmr skill shares` 是所有者/管理的视图，普通受邀者跑不了。

## 处理别人要权限

```bash
skmr notifications approve <id> --yes
skmr notifications approve <id> --role write --yes
skmr notifications deny    <id> --yes
```

- 只有所有者或「管理」能批准。
- 不带 `--yes` 只打印后果与完整命令。
- 批准后对方收到结果；控制台仍可改档位。

## 看一条通知、标已读

```bash
skmr notifications show <id>
skmr notifications read <id>
skmr notifications read --all
```

## 网页上处理通知

https://console.skillmasterai.com/notifications

## 处理通知时要注意的

- **共享是邀请，不是立即授权。** 发出去后对方没接受，`skmr skill shares` 会显示等待中。
- **批准前核对档位。** 默认批对方申请的那一档；要少给就写 `--role`。
- **拒绝也要说一声**，别让邀请挂到过期。

## 通知出错怎么办

| 现象 | 下一步 |
|---|---|
| 邀请已过期或已处理 | `skmr notifications list --status all` 看留档 |
| 接受要可写密钥 | 换一把可写 API key，或 `skmr login` |
| 没有这条通知 | 核对 id；只有收件人能处理 |

# 给 skill 配服务凭据

> 脚本要用的第三方 API key、cookie 存成「服务凭据」，绑到某个 skill 的变量名上；之后 `skmr run` 自动注入。**值只进不出**：存进去就取不回来，也没有命令能读它。

## 先判断要不要存

| 脚本要的是 | 怎么处理 |
|---|---|
| 密钥、token、cookie | `skmr secret set` |
| 普通配置、路径 | 直接写在正文里，或让用户设本机环境变量 |

## 存一把并绑定

```bash
printf '%s' "$ZHIHU_COOKIE" | skmr secret set --skill <地址> --env ZHIHU_COOKIE
```

- 值**只从标准输入读**，不接受参数，也不从终端读。
- 一个 skill 的同一个变量只能绑一处；先 `unbind` 旧的，或换变量名。
- **完成信号**：回显 id、名字与绑定；值不回显。

## 看有哪些

```bash
skmr secret list
skmr secret list --skill <地址>
```

每行：id、名字、绑定的变量@skill、今日用量/上限、上次使用。**永不输出值**；没有绑定的会明说「等于没有」。

## 换值、再绑、解绑、删除

```bash
printf '%s' "$NEW_VALUE" | skmr secret rotate <id>
skmr secret bind   <id> --skill <地址2> --env NAME
skmr secret unbind <id> --skill <地址> --env NAME
skmr secret rm     <id> --yes
```

- `rotate` 绑定与共享都不动，下一次运行就用新值。
- `rm` 不带 `--yes` 只打印后果；删除立即生效，绑定与共享一起失效。

## 每日上限

```bash
skmr secret quota <id> --per-day 200
```

默认 500，上限 10000。**提高要人在终端里确认**——上限拦住的是失控的调用；降低不需要确认。

## 共享给别人

```bash
skmr secret shares   <id>              # 谁在用
skmr secret share    <id> --skill <地址> --user <用户名>
skmr secret unshare  <id> --skill <地址> --user <用户名>
skmr secret incoming --skill <地址>    # 别人共享给我的
```

`share` 只打开网页，**共享在网页上由人确认**；`unshare` 直连生效，撤销后对方下一次运行就用不了。

## 网页上管理

https://console.skillmasterai.com/account/secrets

- **完成信号**：`skmr secret list` 里能看到它，用量有变化。

## 配凭据时要注意的

- **别把值打在命令行里**：它会进 shell 历史和进程列表。
- **32 KiB 是单条上限**；更大的值拆小或换一种凭据。
- **提高每日上限交给用户**：agent 不要自行提高。

## 凭据出错怎么办

| 现象 | 下一步 |
|---|---|
| 这个变量已经绑过了 | `skmr secret unbind` 旧的，或换变量名 |
| 保管凭据的服务连不上 | 等一会儿重试；存着的值没有丢 |
| 运行时报变量缺失 | `skmr secret list --skill <地址>` 核对绑定 |

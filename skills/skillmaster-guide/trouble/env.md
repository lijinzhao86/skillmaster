# 连不上、跑不起来、被沙箱拦

> 先跑 `skmr doctor`。它打印六行状态，并给出按客户端区分的可复制配置；**只打印，不改你的设置**。

## 先跑 doctor 自检

```bash
skmr doctor
```

- **完成信号**：六行都正常；有异常时它会说下一步。

## 让 doctor 写白名单

```bash
skmr doctor --fix --dry-run   # 只看会改什么
skmr doctor --fix             # 真的写进去
skmr doctor --fix --undo      # 撤掉写入的条目
```

`--fix` 会把状态目录与 SkillMaster 的平台/更新域名写进检测到的客户端。**这是改用户的设置**——先给用户看 `--dry-run`，得到同意再跑。

## 连不上服务端

- 核对 `SKILLMASTER_SERVER`（本机默认 `localhost:8080`）。
- 代理只影响浏览器时，先确认命令行能不能直连。

## 跑脚本时缺依赖

`skmr run` 只负责取回版本并执行，**不装依赖**。缺 `python3`、Node 模块、浏览器时，按报错自己装。

## 更新 CLI

版本旧了先 `skmr update`；更新方式与自动检查的节流见 [装 skmr](../start/install.md) §更新客户端。

## 都查不出问题时

见 [提交一份反馈](feedback.md)。

## 环境问题里要注意的

- **`doctor --fix` 不还原开关**：`--undo` 只移除与写入值完全一致的条目。
- **凭据问题不在这篇**，见 [登录不上](sign-in.md)。

## 环境问题的常见现象

| 现象 | 下一步 |
|---|---|
| 连不上 SkillMaster | `skmr doctor` 看网络与沙箱 |
| 命令被沙箱拦 | `skmr doctor` 给出可复制配置 |
| 版本跑不了 | `skmr skill versions <地址>` 核对版本 |
| 绑定的凭据到上限 | 让用户跑 `skmr secret quota <id> --per-day <N>` |

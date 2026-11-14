# 装 skmr

> 照下面的安装文档装；装完 `skmr --version` 能打印版本号，就算装好了。

## 装 skmr 的方法

照安装文档做完：

https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/skillmaster.md

## 装完确认

```bash
skmr --version
```

- **完成信号**：打印出一行版本，形如 `skmr 1.1.0-beta (40ec1be) (API v1)`。

## 更新客户端

```bash
skmr update --check   # 只看有没有新版，不下载
skmr update           # 下载并安装
```

平时不用管：多数命令启动时最多每 24 小时自动检查一次（`help`、`--version`、`update`、`prompt` 与开发构建不检查）；设 `SKILLMASTER_AUTO_UPDATE=0` 可关掉。

## 装与更新时要注意的

- **提示 `command not found`**：多半是装完没有重开终端，`PATH` 还没刷新。
- **版本看着不对**：`which skmr` 能告诉你实际在跑哪一个。
- **装好了但连不上、被沙箱拦**：见 [连不上、跑不起来](../trouble/env.md)。

## 装完之后

1. [登录](../account/sign-in.md)。
2. 走 [第一次：从一个想法到一个上线的 skill](first-skill.md)。
3. 让 Agent 每次都先查库：见 [让 Agent 每次都记得用 skmr](agent.md)。

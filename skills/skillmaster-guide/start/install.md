# 装 skmr

> 装完之后 `skmr --version` 能打印版本号，就算装好了。

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

## 装与更新时要注意的

- **提示 `command not found`**：多半是装完没有重开终端，`PATH` 还没刷新。
- **版本看着不对**：`which skmr` 能告诉你实际在跑哪一个。

## 装完之后

[登录](../account/sign-in.md)，然后走 [第一次：从一个想法到一个上线的 skill](first-skill.md)。

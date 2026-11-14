# 跑 skill 里的脚本

> 正文让你执行一条命令时，用 `skmr run`，不要先把它取到本地再跑。它取回你钉住的那一版，把工作目录设到 `scripts/`，把绑定的服务凭据注入环境变量，再把命令原样交出去。

## 先判断用不用 run

| 正文说的是 | 怎么跑 |
|---|---|
| 「运行 `plan.py`」「执行 `scripts/xxx`」 | `skmr run` |
| 只是让你照着步骤做、没有脚本 | 不用 run，按正文做 |

## 跑一次脚本

```bash
skmr run <地址> -- python3 plan.py
```

- 默认在 `scripts/` 下执行，命令里的脚本路径也按 `scripts/` 解析。
- **第一次执行会先问你**，确认后记住；要跳过用 `--yes`。
- **完成信号**：命令自己的输出；`run` 只说一句在哪个目录执行（走 stderr）。

## 钉版本

`<地址>` 后面可以写版本名、sha256 摘要或草稿的版本码。不写就用上次 `invoke` 钉住的那一版；从没 `invoke` 过才用当前线上。要当前线上就先 `skmr skill versions <地址>` 看版本号，再写 `@<版本号>`。草稿在发布前也能这样跑一遍；版本码用 `skmr version list <地址> --status draft` 核对。

## 凭据怎么进来

绑过服务凭据的变量会注入子进程，并覆盖本机同名变量。没配就先配，见 [给 skill 配服务凭据](secrets.md)。

## 换目录、换缓存

需要时加 `--cwd`、`--cache-in`、`--no-cache`、`--json`；完整参数见 `skmr run --help`。

## 跑不起来怎么办

| 现象 | 下一步 |
|---|---|
| 缺 Python / Node 模块 | 自己装依赖；平台不代装 |
| 绑定的凭据今天到上限 | 让用户跑 `skmr secret quota <id> --per-day <N>`；别自行提高 |
| 网络或沙箱拦截 | 见 [连不上、跑不起来](../trouble/env.md) |
| 找不到这个版本 | 已发布的用 `skmr skill versions <地址>`；草稿用 `skmr version list <地址> --status draft` 核对版本码 |

## run 的退出码

`run` 把被执行的命令的退出码原样返回；`run` 自己失败时是 0 / 1 / 2 / 4。

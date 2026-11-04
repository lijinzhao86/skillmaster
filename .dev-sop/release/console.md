# 控制台（`skillmaster-console/`）

Workflow：`.github/workflows/console.yml`。产物是静态文件 `dist/`，由 nginx 直接发。

## 两段

| 现象 | 判据 |
|---|---|
| PR / push 到 main 且 `skillmaster-console/**` 变了 | `Check` job：装依赖 + `npm run build` |
| 人手动 `workflow_dispatch` | `Deploy` job（`if: github.event_name == 'workflow_dispatch'`） |

**合并不上线。** `Deploy` 只由人点。

## 部署做了什么

在自己的 runner 上构建（**不复用 `Check` 的产物**）→ `rsync -az --delete dist/` 到
`/srv/skillmaster/console-releases/<sha>/` → 切 `/srv/skillmaster/console` 软链 → 探针 → 保留最近 5 个。

**探针跑在主机上，不在 runner 上。** 它用 `--resolve <域名>:443:127.0.0.1` 去问本机的 nginx，所以
nginx、证书、文件三样都被覆盖到了。这不是洁癖：从 GitHub runner 用 curl 打这台机器的公网地址，每一个
TLS 连接都会在 ClientHello 与 ServerHello 之间被重置（`curl 35`，十一次重试全部如此），而同一时刻
SSH 与 rsync 到这台机器都正常——旧写法因此回滚过一次完全没问题的构建。控制台部署不可能弄坏公网可达性
（域名、反代、证书都不变），所以探针只问这个 job 能回答的问题。

探针不过 → 自动切回上一个 release。

## 回滚

软链切回上一个 `<sha>` 目录即可；目录还在，不需要重建。

## 手动兜底

Actions 不可用时的手工步骤在 `deploy/README.md` §Doing it by hand。

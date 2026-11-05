# 控制台（`skillmaster-console/`）

Workflow：`.github/workflows/console.yml`。产物是静态文件 `dist/`，由 nginx 直接发。

## 两段

| 现象 | 判据 |
|---|---|
| PR / push 到 main | `Console changes` 判断是否碰了控制台；碰了才跑 `Console check`：装依赖 + 测试 + `npm run build`，并在 push 上上传 `dist/` |
| 人手动 `workflow_dispatch` | `Deploy`：要求同一 commit 的 `Console check` 绿了，下载它的 artifact |

**合并不上线。** `Deploy` 只由人点。

## 部署做了什么

**不复用自己的构建**：先验同一 commit 的 `Console check` 结论是 `success`，再下载它上传的 `dist/`
（`actions/download-artifact`，跨 run 下载并校验 digest）→ `rsync -az --delete dist/` 到
`/srv/skillmaster/console-releases/<sha>/` → 切 `/srv/skillmaster/console` 软链 → 探针 → 保留最近 5 个。

`workflow_dispatch` 的 `sha` 可以填一个更早的、在 push run 里被 `Console check` 构建并上传过
`console-dist` 的完整 40 位 commit sha；留空就是 dispatch 的 ref 顶端。更早的 commit 的 run 里没有
artifact，`Deploy` 会在碰服务器之前失败。

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

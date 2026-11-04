# skillmaster · 发布与回滚

**一句话：门禁在 PR 上，上线是人按下去的一步。**

| 端 | 产物 | 谁触发上线 | 落到哪 | 回滚 |
|---|---|---|---|---|
| [server](server.md) | 一个 jar | 人手动 `workflow_dispatch` | `/opt/skillmaster/releases/<sha>/` + `current` 软链 | 切回上一个 release |
| [console](console.md) | 静态文件 `dist/` | 人手动 `workflow_dispatch` | `/srv/skillmaster/console-releases/<sha>/` + 软链 | 切回上一个 release |
| [site](site.md) | 静态文件 `dist/` | 人手动 `workflow_dispatch` | `/srv/skillmaster/site-releases/<sha>/` + 软链 | 切回上一个 release |
| [cli](cli.md) | 六个平台的包 | **打 tag** `cli/v*` | OSS 桶 `dl:` → `dl.skillmasterai.com` | 站点只往前走，见该篇 |

## 形状只有两类

- **机器上跑的东西**（server / console / site）：`releases/<sha>` + 一条软链 + 部署后探针 + 保留最近 5 个。探针不过就自动切回上一个，所以「部署失败」不会把线上留在半新半旧的样子。
- **发到桶里的东西**（cli）：打 tag → 构建 → 传进暂存区 → 逐字节校验 → 提升到根 → **最后**写 `version.txt`（它是「这一版正式上线」的标记）。

## 三条共同的规矩

1. **门禁在 PR 上。** 四端都有各自的 path 过滤：改控制台只跑控制台的检查，不会连累别的。
2. **上线是人按下去的一步。** 服务端和两个前端都要人手动 dispatch；CLI 要人打 tag。合并不等于上线。
3. **回滚是切回上一版，只有一样回不去：数据库迁移。** 服务端启动时跑 Flyway，回滚 jar 不会回滚 schema（见 [server](server.md)）。

## 还没做的地方

部署链路上有两处欠账——必需检查没配、`Deploy` 跳过测试自己重新构建。它们记在 [`docs/current/TODO.md`](../../docs/current/TODO.md)，现状与目标的对照在 [server.md](server.md) 的「现状 vs 要做到」。

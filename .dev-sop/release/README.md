# skillmaster · 发布与回滚

**一句话：门禁在 PR 上，上线是人按下去的一步。**

| 端 | 产物 | 谁触发上线 | 落到哪 | 回滚 |
|---|---|---|---|---|
| [server](server.md) | 一个 jar | 人手动 `workflow_dispatch` | `/opt/skillmaster/releases/<sha>/` + `current` 软链 | 切回上一个 release |
| [console](console.md) | 静态文件 `dist/` | 人手动 `workflow_dispatch` | `/srv/skillmaster/console-releases/<sha>/` + 软链 | 切回上一个 release |
| [site](site.md) | 静态文件 `dist/` | 人手动 `workflow_dispatch` | `/srv/skillmaster/site-releases/<sha>/` + 软链；`main` 顶端的构建还会把 skill 包与安装文档发到 `dl:beta/` | 切回上一个 release |
| [cli](cli.md) | 六个平台的包 | **打 tag** `cli/v*` | OSS 桶 `dl:` → `dl.skillmasterai.com` | 站点只往前走，见该篇 |

## 形状只有两类

- **机器上跑的东西**（server / console / site）：`releases/<sha>` + 一条软链 + 部署后探针 + 保留最近 5 个。探针不过就自动切回上一个，所以「部署失败」不会把线上留在半新半旧的样子。
- **发到桶里的东西**（cli）：打 tag → 构建 → 传进暂存区 → 逐字节校验 → 提升到根 → **最后**写 `version.txt`（它是「这一版正式上线」的标记）。
  site 的部署也把同一份构建里的 skill 包与安装文档发到这个桶的 `beta/`，但只在部署 `main` 顶端时这么做；用 `sha` 重发旧站点不会碰桶。

## 三条共同的规矩

1. **门禁在 PR 上。** 合并到 main 要过必需检查。改控制台只跑控制台的检查，不会连累别的——判断由每个 workflow 的 `changes` job 做，**不用 `paths:` 过滤**，理由见 [server.md](server.md)「合并闸」。
2. **上线是人按下去的一步。** 服务端和两个前端都要人手动 dispatch；CLI 要人打 tag。合并不等于上线。
3. **回滚是切回上一版，只有一样回不去：数据库迁移。** 服务端启动时跑 Flyway，回滚 jar 不会回滚 schema（见 [server](server.md)）。

这三条有钩子兜底：[`.claude/hooks/release-reminder.sh`](../../.claude/hooks/release-reminder.sh) 在命令里出现 `gh workflow run` / `gh pr merge` / `gh release` / `git tag` 时，把它们往上下文里放一次（每会话一次，只提醒不拦）。

## 一次性的仓库设置

main 的必需检查是仓库设置，不在代码里：清单与顺序（先合并 workflow 改动，再开必需检查）见
[server.md](server.md) 的「合并闸」。

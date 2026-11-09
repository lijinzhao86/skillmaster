# v57-draft-loop · 技术设计

> **最后更新**：2026-11-10
> **所属版本**：[`README.md`](README.md)

## 架构

两条数据流，都是现存路径上的一个点被换掉：

**读凭证**（决定 `write --overwrite` 放不放行）

```
skmr skill read|cat|get（整份）
        │  取到字节
        ▼
recordFullRead ── 列一次文件清单 ──► 记录该文件的 File.sha256
        │                              （本机状态目录，internal/pins/reads.go）
        ▼
skmr skill write --overwrite ── 再列一次清单 ──► 比较该文件当前的 File.sha256
```

与现状的差异有三处：凭证的值从「清单响应的 ETag（整版摘要）」换成「清单里这个文件自己的 `sha256`」；**被行数/字节上限截断的 `read` 不再记账**（ADR 0177 §决定 2）；**`write --from` 写成功一个文件也记一条凭证**（§决定 5），否则批量失败提示的「重跑同一条命令」必然在第一个已写文件处被拒。没有 `--force`、状态放在 `internal/pins` 不变。理由见 [ADR 0177](../../decisions/0177-read-proof-binds-to-file-content.md)。

**`run` 的版本解析**

```
skmr run <地址>@<pin>
        │
        ├─ pin 是合法 sha256: ──────────► 直接按 digest 取缓存（不变）
        ├─ 其他 pin（版本名、版本码、记住的码） ──► 公开面 Metadata
        │        └─ 400 invalid_request / 404 ──► 作者面 ReadAuthorVersion → version.digest
        │                           （作者面 404 或 forbidden 403 →「没有这一版」；
        │                            insufficient_scope → 凭据那句；网络/5xx 原样上抛）
        └─ 没有 pin（裸地址且本机没记住） ──► 公开面最新
                        │
                        ▼
              materialize（按 digest 取作者面 archive，缓存到 $TMPDIR）  ← 不变
```

与现状的差异：`resolveDigest` 多一条「公开面不认这个引用 → 问作者面」的边上路径，触发条件是 **pin 本身**（不是「调用者写了 `@`」）——`invoke` 给没有线上版本的 skill 记住的是草稿码，裸地址的 `run` 遇到的是同一个问题。取缓存、执行、按 digest 确认一次的规则都不变，理由见 [ADR 0178](../../decisions/0178-run-resolves-drafts-through-the-author-plane.md)。没有新增服务端路由：作者面的 `GET /author/skills/{ns}/{name}/versions/{pin}` 已经由 `api.Client.ReadAuthorVersion` 使用（`version_show.go` 等），`run` 复用它。

作者面解析出 digest 之后，绑定信息分两种情形：

- **skill 已发布**：用**公开面**对裸地址的答案——`credentials_bound` 是 skill 级事实；`credentials_missing` 取自公开面当前发布的版本，草稿新增的声明可能不在其中（见 test-plan §已知问题 2）。不多租约，只多一次空 pin 的探测；有绑定时再租一次。只读凭据因此不会被草稿逼着 POST 租约。
- **从未发布**：公开面没有答案，改为**探测一次租约**（租约是 skill 级的，不带 pin，草稿同样可租；没有绑定时返回空）。租约被拒（例如只读凭据没有 `skills:write`）**不中止**：说一句「没能确认」并照常运行。

组件与边界：

| 组件 | 位置 | 这一版改什么 |
|---|---|---|
| 本机读记录 | `skillmaster-cli/internal/pins/reads.go` | 记录字段 `etag` → `sha256`；空值按「没读过」 |
| 记账与放行 | `skillmaster-cli/cmd/skillmaster/read_proof.go` | 取 `File.sha256` 而不是 `listed.ETag`；`write --from` 写成功后记下新内容 |
| 覆盖入口 | `write_local.go` | 传文件的 `sha256`；每写一个就按最新清单刷新 `existing`；失败时打印三组清单（另有「已上传、基线未知」一种） |
| 覆盖入口（单文件） | `files_write.go`（`editRemote`） | 比对改用文件的 `sha256` |
| 版本解析 | `skillmaster-cli/cmd/skillmaster/run.go`（`resolveDigest`） | 公开面不认的 pin 增加作者面回退与错误分类 |

## 数据模型

没有服务端表变化。本机状态文件（`$state/pins/<server-hash>/reads/<address-hash>.json`，与版本钉同一套目录）的意图：

```
{
  "address": "lijinzhao/bilibili",
  "reads": {
    "<pin>": {
      "<relpath>": {"sha256": "<hex>", "at": "<RFC3339>"}
    }
  }
}
```

- **键仍是 `pin + relpath`**：读的是哪个版本的哪个文件，这一点不变。
- **值换成文件内容哈希**（服务端清单里 `File.sha256` 的原样字符串）：只有该文件的内容变了才失效。
- **写入成功也记一条**：`write --from` 写成功一个文件后，用紧接着的清单把这个文件的新 `sha256` 记为已读——「重跑同一条命令」才成立（[ADR 0177](../../decisions/0177-read-proof-binds-to-file-content.md) §决定 5）。
- **不迁移**：旧文件里的 `etag` 读出来没有 `sha256`；任一侧为空都按「没读过」处理（fail-closed，多读一次，不会写错内容）。
- 清理规则不变，上限见 `internal/pins/reads.go` 的 `maxReadProofs`，不在本文复述数值。

## 接口

### CLI 界面

| 面 | 变化 |
|---|---|
| `write --overwrite` 的放行条件 | 「本机完整读过这个文件，且读后**它自己的内容**没变」。`--help` 的说明同步改 |
| 批量写入的失败输出 | 写前先做两条校验：目标已存在而没带 `--overwrite`、或两个 `--from` 落到同一远端路径，都在任何 PUT 之前拒绝。中途失败时 stderr 打三组：`已写入（n）` / `失败（1）` / `未开始（n）`，最后一行给「修好后重跑同一条命令」。**上传成功但读不回新清单**时改打 `已写入（n）` / `已上传、基线未知（1）` / `未开始（n）`，并给出先读它一次的完整命令 |
| `run` 的版本引用 | 版本码（如 `@JD0T518T`）与版本名、`sha256:` 一样可用 |
| `run` 的错误 | 不再出现 `X-Skill-Version` 这类协议细节；作者面 404 或 `forbidden` 403 时说「没有这一版」，并给 `skmr skill versions <地址>`；`insufficient_scope` 交给凭据那句；网络与 5xx 原样上抛 |

批量失败输出（A9 的判定对象，逐字）：

```
错误：<失败那一个的原因>
本次批量写入 6 个文件：
  已写入（2）：scripts/a.py、scripts/b.py
  失败（1）：scripts/c.py —— <原因>
  未开始（3）：SKILL.md、references/x.md、references/y.md
修好后重跑同一条命令即可；已写入的文件会被覆盖成同样的内容。
```

三组都写**远端的相对路径**；重跑之所以能补齐，是因为写成功的文件已经记下了新内容的凭证。

### 服务端

不改。用到的既有路由：

| 路由 | 用途 | 已有客户端方法 |
|---|---|---|
| `GET /author/skills/{ns}/{name}/versions/{pin}` | 草稿版本码 → digest | `api.Client.ReadAuthorVersion` |
| `GET /author/skills/{ns}/{name}/versions/{pin}/files` | 文件清单（含每个文件的 `sha256`） | `api.Client.AuthorFileList` |
| `GET /author/skills/{ns}/{name}/versions/{pin}/archive` | 按 digest 取整版 | `api.Client.StreamArchive`（经 `fetchVersion`） |

错误码沿用 [ADR 0087](../../decisions/0087-cli-exit-codes-are-a-public-contract.md)：`run` 成功启动子进程时退出码是子进程的，其余仍是 0/1/2/4。

## 分期

一期交付全部，不分期。验收范围是 [`prd.md`](prd.md) 的 A1–A9；A1、A5 是成立条件。

## 开放问题

1. **草稿的 digest 与发布后的 digest 是不是同一个？** 决定「发布后能不能命中 run 的缓存、要不要再确认一次」。倾向：是（digest 由文件内容决定）。判定：同一个草稿在发布前后各 run 一次，看缓存目录名与 `--yes` 记录是否相同（T12，未执行）。若不一致，正确性不受影响，只是发布后再问一次。
2. **部分读（`--offset` / `--limit`）要不要也记凭证？** 已按 [ADR 0177](../../decisions/0177-read-proof-binds-to-file-content.md) §决定 2 定为不记（不接受「我只看了一眼就要整份覆盖」）；要放开需另开一条 ADR。
3. **草稿 run 多出的请求值不值？** 已按「值」实现，分两支：已发布的 skill 多一次空 pin 的公开面探测；从未发布的多一次探测失败 + 一次租约。若将来作者面能直接给出 `credentials_bound`，两支都能省掉。

**已关闭**：公开面答 404 时是否转问作者面——[ADR 0178](../../decisions/0178-run-resolves-drafts-through-the-author-plane.md) 已决定转（400 与 404 都转），实现与 T11 一致。

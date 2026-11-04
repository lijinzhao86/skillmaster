# v43-download-domain · 产品需求

> **最后更新**：2026-11-04
> **所属版本**：[`README.md`](README.md)

## 用户与场景

**谁在用**：按首页那句 prompt 把 SkillMaster 装到自己机器上的用户，以及替他们执行安装的 agent。

**在什么场景下用**：用户复制首页那句「根据 … 安装 SkillMaster。」交给 agent，agent 去取安装说明、安装脚本和 CLI 包；或者用户自己跑 `curl -fsSL …/install.sh | sh`。装好之后，CLI 还会按编译进去的地址自查更新。

**现在的替代做法是什么**：所有下载地址都直连 OSS 桶 `skillmaster-dl.oss-cn-hangzhou.aliyuncs.com`。这是备案通过之前的临时通道——桶自己的名字不经过备案设备，TLS 1.2 客户端（macOS 自带 curl、部分 Windows PowerShell）能装上。代价是用户和 agent 看到的是存储桶的名字，不是我们的域名；安装文档里还专门写着「不要改成 `dl.`」。

**现在为什么改**：`www.skillmasterai.com` 的 ICP 备案已于 2026-11-04 通过（`粤ICP备2026172880号`），阿里云备案设备不再按 SNI 重置 TLS 1.2，`dl.skillmasterai.com` 在 TLS 1.2 下实测 200。部署说明里预先写好的换回条件已经成立。

## 范围

### 做

| # | 做什么 | 交付形态 |
|---|---|---|
| 1 | 把**用户可见的**下载地址从桶直连改成 `https://dl.skillmasterai.com`：首页 prompt、安装说明页（含附录 B / C）、平台技能 `skills/skillmaster/SKILL.md`、CLI 安装脚本默认值、CLI 编译期 `UpdateBase`（`cli-beta.yml` / `cli-release.yml`） | 代码与文档 |
| 2 | 首页页脚加 ICP 备案号 `粤ICP备2026172880号`，链接到 `https://beian.miit.gov.cn/` | 设计件 + 官网实现 |
| 3 | 把说明「为什么用桶、什么时候换回」的运营文档与流水线注释改成当前事实：`deploy/README.md` §TLS 与备案、`deploy/acme/auth.sh` 的 DNS-01 理由、`skillmaster-cli/RELEASE.md`、`skillmaster-cli/install/README.md`、`skillmaster-site/README.md`、`version.go` 注释、`deploy/nginx/skillmaster.conf` 的端口 80 与 `dl.` 块注释、`site.yml` 与两个 CLI 工作流的注释 | 文档与注释 |
| 4 | 首页设计检查 `home-check.mjs` 增加页脚备案号的断言；官网测试同步更新 | 测试 |

### 不做

| # | 不做什么 | 为什么 | 什么时候可能做 |
|---|---|---|---|
| 1 | 不改工作流的 **OSS 上传端点**（`ossutil` / `rclone` 的 `endpoint`）和**从桶回读校验**（`base="https://${OSS_BUCKET}.oss-cn-hangzhou.aliyuncs.com/…"`） | 前者是写桶的控制面，必须直连 OSS；后者是刻意绕开 runner→ECS 的 TLS 链路。它们都不是用户链接 | 不适用 |
| 2 | 不把旧桶地址下线，也不改老版本 CLI 里已编译进去的 `UpdateBase` | 已装好的 CLI 还要能从桶自查更新；新发的包才用新地址。旧地址继续可用是这次切换的安全垫 | 旧 CLI 全部退役后 |
| 3 | 不改冻结的历史文档（`docs/versions/v1–v39`）与 ADR 正文 | 历史记录只增不改；ADR 记的是当时的取舍 | 不适用 |
| 4 | 不给控制台加备案号 | 本次只处理 `www` 首页；控制台是否要挂是另一个界面决定，设计稿没覆盖 | 产品负责人提出时 |
| 5 | 不替换服务端内部的 OSS endpoint（`BlobSettings`、`DashScopeEmbedder`、`SmsConfig` 里的地址） | 那是服务端访问存储与第三方服务的内部地址，不是用户下载链接 | 不适用 |
| 6 | 不改 nginx `dl.` 反代的上游（必须指向桶）与部署文档里的拓扑图 | `dl.` 是桶的前门，反代自己会成环；拓扑图画的正是这个事实 | 不适用 |

## 控制台操作与 CLI 对应

本版**没有新增或改动控制台操作**：只换下载地址和首页页脚文案。CLI 侧的安装入口本来就存在，本版不新增命令，也不申请 [ADR 0081](../../decisions/0081-every-console-operation-has-a-cli-command.md) 的例外。

## 验收与指标

### 验收标准

| # | 验收标准 | 怎么判定 |
|---|---|---|
| A1 | 用户可见的下载地址全部是 `https://dl.skillmasterai.com`：首页 prompt、安装说明页附录 B/C、平台技能 `SKILL.md`、`install.sh` / `install.ps1` 的默认 `BASE_URL`、两个工作流的 `UPDATE_BASE` | 在用户入口路径（`skillmaster-site/src/`、`skillmaster-site/public/`、`skills/`、`skillmaster-cli/install/`、`design/src/`、`design/scripts/`）与两个 CLI 工作流 grep `skillmaster-dl.oss-cn-hangzhou.aliyuncs.com`，命中为 0；再正面断言 `UPDATE_BASE: https://dl.skillmasterai.com` 出现两次。全仓其余命中只在允许类别里：工作流的上传/回读（含 `${OSS_BUCKET}` 插值）、nginx `dl.` 反代的上游、部署拓扑图、服务端内部的 OSS endpoint（不做 #5）、全部版本目录文档与 ADR 正文、本版文档自己的说明 |
| A2 | `curl -fsSL https://dl.skillmasterai.com/beta/install.sh` 能取到与桶里一致的内容 | 两处各取一次，`cmp` 相同；SHA-256 与 `checksums.txt` 对得上 |
| A3 | TLS 1.2 客户端能从 `dl.` 完成安装 | `curl --tlsv1.2 --tls-max 1.2` 取 `install.sh`、`skillmaster.md`、一个安装包，均 200 |
| A4 | 新发的 CLI 包编译进 `https://dl.skillmasterai.com`（beta 带 `/beta`） | 流水线从包里 `grep` 到该地址；本地对构建产物同样 grep 一次 |
| A5 | 首页页脚显示 `粤ICP备2026172880号`，链接指向 `https://beian.miit.gov.cn/` | 设计检查 `home-check.mjs` 断言文案与 `href`；官网组件测试同样断言 |
| A6 | 首页设计件与官网实现的页脚文案、`id`、`href` 一致 | 两处都由 T5 的断言钉住同一句文案与同一个 `href`；设计件能从源重出（T6） |
| A7 | 官网、CLI 的既有测试与类型检查全绿 | `skillmaster-site`: `npm test`、`npm run type-check`；`skillmaster-cli`: `go test ./...` |
| A8 | 文档与流水线注释不再说「备案没过 / 不要用 `dl.`」 | `deploy/README.md`、`skillmaster-cli/RELEASE.md`、`skillmaster-site/README.md`、安装说明页附录 C、`site.yml` / `cli-*.yml` 注释读完，没有与当前事实相反的句子 |

**成立条件**：A1 与 A5 不满足就不算交付——这一版的目的就是把用户入口换到域名，并在首页标出备案号。

### 指标

| 指标 | 定义 | 怎么量 | 目标 |
|---|---|---|---|
| 用户可见的桶地址数量 | 上述用户入口里仍指向 `skillmaster-dl.oss-cn-hangzhou.aliyuncs.com` 的处数 | `grep -rn` 后逐条归类 | 0 |

## 依赖与前置

- **外部依赖**：`www.skillmasterai.com` 的 ICP 备案已通过（`粤ICP备2026172880号`，2026-11-04）；`dl.skillmasterai.com` 的 nginx 反代与 Let's Encrypt 证书已在生产。
- **内部前提**：[`deploy/README.md`](../../../deploy/README.md) §TLS 与备案 已预先写好换回判据与三处改动；本版按它执行。
- **排除了什么前置**：不需要改 DNS、不需要重新签证书、不需要改 nginx 的 `dl.` 反代规则——它们都已经在跑。

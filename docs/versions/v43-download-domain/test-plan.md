# v43-download-domain · 测试方案

> **最后更新**：2026-11-04
> **所属版本**：[`README.md`](README.md)

## 验收映射

| prd 验收标准 | 对应用例 | 状态 |
|---|---|---|
| A1 用户可见地址全部是 `dl.` | T1 | 通过 |
| A2 `dl.` 与桶内容一致 | T2 | 通过 |
| A3 TLS 1.2 客户端能取到文件 | T3 | 通过 |
| A4 新构建编译进 `dl.` 基址 | T4 | 通过 |
| A5 页脚备案号与链接 | T5 | 通过 |
| A6 设计件与官网一致 | T6 | 通过 |
| A7 官网、CLI 测试与类型检查 | T7 | 通过 |
| A8 文档不再说旧结论 | T8 | 通过 |

## 用例

### T1 · 用户入口的地址审计

- **前置**：改动已落盘；工作区干净到只剩本版改动。
- **步骤**：先在用户入口路径与两个 CLI 工作流跑 `grep -rn "skillmaster-dl\.oss-cn-hangzhou\.aliyuncs\.com" skillmaster-site/src skillmaster-site/public skills skillmaster-cli/install design/src design/scripts .github/workflows/cli-beta.yml .github/workflows/cli-release.yml`；再正面断言 `grep -c "UPDATE_BASE: https://dl.skillmasterai.com"` 在两个工作流里各 1；最后在全仓跑 `grep -rn "oss-cn-hangzhou\.aliyuncs\.com" --exclude-dir=node_modules --exclude-dir=.git --exclude-dir=dist .`（把工作流里的 `${OSS_BUCKET}` 插值写法也带上），逐条归类。
- **期望**：用户入口路径与两个 CLI 工作流的旧桶字面量命中为 0；两个 `UPDATE_BASE` 都是 `dl.`（beta 带 `/beta`）；全仓其余命中只在允许类别里——工作流的上传/回读、nginx `dl.` 反代的上游、部署拓扑图、服务端内部的 OSS endpoint（不做 #5）、全部版本目录文档、ADR 正文、本版文档自己的说明。
- **判定**：通过／失败（失败时列出仍指向桶的用户入口）。

### T2 · `dl.` 与桶的字节一致

- **前置**：生产上 `dl.` 反代正常。
- **步骤**：分别取 `https://dl.skillmasterai.com/beta/install.sh` 与 `https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/install.sh`，`cmp`；再取 `beta/checksums.txt` 与一个安装包，核对 SHA-256。
- **期望**：`cmp` 无差异；包哈希与 `checksums.txt` 一致。
- **判定**：通过／失败（记录不一致的文件）。

### T3 · TLS 1.2 端到端

- **前置**：同 T2。
- **步骤**：`curl --tlsv1.2 --tls-max 1.2 -fsSL` 取 `beta/skillmaster.md`、`beta/install.sh`、`beta/skillmaster_darwin_arm64.tar.gz`（或当前机器对应包）。
- **期望**：三次都 200，文件完整；不出现 `Connection reset`。
- **判定**：通过／失败（记录退出码与错误）。

### T4 · 构建产物带 `dl.` 基址

- **前置**：能跑 `go build`。
- **步骤**：在 `skillmaster-cli/` 用与流水线相同的 `-ldflags` 构建一份，`grep -aF "https://dl.skillmasterai.com"`；再核两个工作流里的 `grep` 断言仍在。
- **期望**：二进制里能找到该地址；beta 构建带 `/beta`；流水线的两条 `grep` 断言随 `UPDATE_BASE` 一起更新。
- **判定**：通过／失败。

### T5 · 页脚备案号

- **前置**：`design/` 与 `skillmaster-site/` 依赖已装。
- **步骤**：跑 `node design/scripts/home-check.mjs "design/skillmaster-首页演示.html" artifacts/design/home`；跑官网 `npm test`。
- **期望**：`页脚底栏带版权与备案号`、`备案号链到工信部备案系统` 两条通过；官网组件测试对同一句与 `href` 的断言通过。
- **判定**：通过／失败。

### T6 · 设计件与官网一致

- **前置**：同 T5。
- **步骤**：在 `design/` 跑 `npm run build:home && npm run build:console`；跑 `npm run type-check`；`git status` 看两个演示件。
- **期望**：两个演示件与源一致（提交后 `git status` 对它们干净）；类型检查退出 0。两处页脚文案、`id`、`href` 的逐字一致由 T5 断言，本用例只证明设计件没有漂。
- **判定**：通过／失败。

### T7 · 官网与 CLI 的既有检查

- **前置**：依赖已装。
- **步骤**：`skillmaster-site/` 跑 `npm test`、`npm run type-check`；`skillmaster-cli/` 跑 `go test ./...`。
- **期望**：全绿。
- **判定**：通过／失败（附失败用例）。

### T8 · 文档事实审计

- **前置**：文档改动已落盘。
- **步骤**：读 `deploy/README.md` §TLS 与备案、`deploy/acme/auth.sh` 的 DNS-01 理由、`skillmaster-cli/RELEASE.md` §5–§9、`skillmaster-cli/install/README.md` 的失败处置、`skillmaster-site/README.md`、安装说明页附录 C、`deploy/nginx/skillmaster.conf` 的端口 80 与 `dl.` 块注释、`.github/workflows/site.yml` 与两个 `cli-*.yml` 的注释；对照当前事实（备案已通过、`dl.` 在 TLS 1.2 下正常、用户入口已换）。
- **期望**：没有「备案未通过」「不要用 `dl.`」「端口 80 在本机不生效」这类与现状相反的句子；检查命令指向 `dl.`。
- **判定**：通过／失败（列出仍与现状相反的句子）。

## 环境与数据

- **环境怎么造**：本机 macOS，工作区 `../skillmaster-dl-domain`；`design/` 与 `skillmaster-site/` 各跑一次 `npm ci`。不需要数据库、不需要起服务端。
- **数据**：生产上的真实文件（桶与 `dl.` 是同一批对象）；不造夹具。
- **清理**：无。`artifacts/design/` 是 gitignore 的截图目录；构建产物 `design/skillmaster-*.html` 入库，官网 `dist/` 不入库。

## 结果

| 用例 | 结果 | 证据 |
|---|---|---|
| T1 | 通过 | 用户入口路径与两个 CLI 工作流的旧桶字面量 0 命中；`UPDATE_BASE: https://dl.skillmasterai.com` 在两个工作流各 1（beta 带 `/beta`）；全仓（排除 `node_modules`、`.git`、`dist`）共 37 处，逐条归类后全部在允许类别：工作流上传/回读与 `${OSS_BUCKET}` 插值（14）、nginx 反代（3）、部署拓扑图（1）、服务端内部 OSS endpoint（1）、版本目录文档（v38 8 / v42 3 / v43 6）、ADR 0129（1） |
| T2 | 通过 | `dl.` 与桶的 `beta/install.sh` `cmp` 无差异；`skillmaster_darwin_arm64.tar.gz` 的 SHA-256 与 `checksums.txt` 一致 |
| T3 | 通过 | `--tlsv1.2 --tls-max 1.2` 取 `beta/install.sh`、`beta/checksums.txt`、`beta/skillmaster_darwin_arm64.tar.gz`、`beta/skillmaster.md` 均成功，无 reset |
| T4 | 通过 | 按流水线同一组 `-ldflags` 构建 `/tmp/skmr-v43`，`strings` 找到 `https://dl.skillmasterai.com`；另按 beta 的 `UPDATE_BASE` 构建 `/tmp/skmr-beta-v43`，`strings` 找到 `https://dl.skillmasterai.com/beta`；两条工作流的 `grep -qaF "$UPDATE_BASE"` 断言分别在 `cli-beta.yml` / `cli-release.yml` |
| T5 | 通过 | `home-check` `failed: 0`，含「页脚底栏带版权与备案号」「备案号链到工信部备案系统」；官网 20 条测试全过，含同一句与 `href` 断言 |
| T6 | 通过 | `design/` 的 `build:home`、`build:console`、`type-check` 均退出 0；两个演示件已重出 |
| T7 | 通过 | 官网 20 条测试、`type-check` 通过；CLI `go test ./...` 全绿（**需要先 `env -u SKILLMASTER_AUTO_UPDATE`**，见下） |
| T8 | 通过 | 全仓搜「还没走完备案 / 备案完成前 / 未备案 / ICP filing is pending / ICP not yet」在非冻结文档里 0 命中；`deploy/README.md`、`deploy/acme/auth.sh`、`RELEASE.md`、`install/README.md`、`site/README.md`、安装说明附录 C、nginx 端口 80 与 `dl.` 块注释、`site.yml` 与两个 `cli-*.yml` 注释已改到当前事实 |

**跳过 / 环境说明**：本机 shell 设了 `SKILLMASTER_AUTO_UPDATE=0`，它会让 `cmd/skillmaster` 里三条 updater 测试失败（请求数为 0、不更新）。`env -u SKILLMASTER_AUTO_UPDATE go test ./...` 后全绿；CI 的 CLI 工作流在 `main` 上也是绿。这不是本版引入的失败。

## 已知问题

本版范围内没有已复现的未修缺陷。

# CLI 发布规范

CLI 怎么编号、发哪些包、放在哪、旧版本怎么办——这张纸是这些问题的**唯一权威**。为什么这么定见
[ADR 0088](../docs/decisions/0088-cli-major-version-is-the-api-generation.md)；某一版到底交付了什么见
[`docs/versions/`](../docs/versions/)。

## 1 · 版本号

| | 含义 |
|---|---|
| 主版本 | **它说的 API 代次**：`1.x` ↔ `/api/v1`，`2.x` ↔ `/api/v2` |
| 次版本 | 同一代次内新增能力 |
| 修订号 | 修缺陷 |

- **首个正式版是 `1.0.0`，不是 `0.x`**——它说的是 v1。
- **同一主版本内，命令面只增不减**：改名用「新名 + 旧名留作别名 + 弃用提示」，删除与语义改变留到下一个主版本。
- **版本号只有一个来源：git tag。** 仓库里**不放** `VERSION` 文件——两个来源必然漂。
- `skillmaster --version` 打印两者：`skillmaster 1.0.0 (API v1)`。本地 `go run` 没有注入时显示 `dev`。

## 2 · 什么时候发

```
git tag cli/v1.0.0 && git push origin cli/v1.0.0
```

- **只有 `cli/v*` tag 触发构建**，且**只有不带 `-rc` 的正式版才上传**（**工作流尚未创建**：今天的 `cli.yml` 只跑 gofmt/vet/test）。合并进 main 只跑测试——发布节奏与合并节奏解耦。
- **tag 必须落在 main 的历史里**，流水线第一步校验，不是就拒绝。免得从某个分支的中间态发出一个版本。
- **预发布**用 `cli/v1.1.0-rc.1` 这种形式，**不上传**，只在 CI 里构建留产物供内部试。因此下载站上永远只有正式版。
- **不重打同一个版本**。坏了发下一个修订号；不删 tag、不覆盖资产——已经有人下过的东西，不能在同一版本号下变成另一样。

## 3 · 发哪些包

覆盖面要够宽，但架构矩阵不能靠猜。

| 系统 | 架构 | 产物 | 对应的机器 |
|---|---|---|---|
| macOS | arm64 | `skillmaster_darwin_arm64.tar.gz` | Apple Silicon（M1–M4） |
| macOS | amd64 | `skillmaster_darwin_amd64.tar.gz` | Intel Mac |
| Linux | amd64 | `skillmaster_linux_amd64.tar.gz` | 绝大多数服务器与桌面 |
| Linux | arm64 | `skillmaster_linux_arm64.tar.gz` | Graviton / Axion / 树莓派 64 位 |
| Windows | amd64 | `skillmaster_windows_amd64.zip` | 绝大多数 Windows |
| Windows | arm64 | `skillmaster_windows_arm64.zip` | Snapdragon 等 ARM 本 |

**凭什么**（2026-10 调研）：

| 结论 | 依据 |
|---|---|
| Windows 必须发 | 开发者里 Windows 的占比，取两个极端都不小：Stack Overflow 2025（4.9 万人、177 国）职业开发者主用系统 **49.5%**；The Pragmatic Engineer 面向创业公司/大厂的调查是 **12.8%**。无论信哪个都不是可以跳过的份额 |
| Linux 要发 arm64 | ARM 已占被统计 Kubernetes 集群 CPU 的约 **9%**，占 AWS 新增 EC2 实例 **20% 以上** |
| macOS 两个架构都要 | Apple Silicon 已占笔记本 CPU 出货约 **18–19%**，但 Intel Mac 存量仍可观，且 macOS 至今支持部分 Intel 机型 |

**不发 32 位 Windows**：Windows 11 根本没有 32 位版本，Windows 10 已于 2025-10-14 结束支持；Steam 硬件调查里 32 位 Windows 只占 **0.01%**。为它多发一个包，代价是安装说明永久多一条分支。

**同样不发**：`linux/386`（32 位服务器事实不存在）、`linux/arm`（32 位 ARM，树莓派 64 位系统已是主流）、FreeBSD / ppc64le / s390x（企业小众，且没有用户证据）。

**要加一个平台，先拿数据改这张表**，不要顺手编一个。

## 4 · 产物与命名

- **文件名不带版本号**。这样下载地址永久稳定，安装说明不用跟着每次发版改。版本号由二进制自己说。
- **unix 用 `.tar.gz`，Windows 用 `.zip`**——跟 Go 自己和绝大多数项目的做法一致，Windows 上双击就能解。
- 每次发布另有：`checksums.txt`（六个包的 SHA-256）、`install.sh`、`install.ps1`。

## 5 · 构建参数

```
CGO_ENABLED=0                      静态链接，Alpine 这类 musl 环境不需要额外依赖
-trimpath                          路径不进二进制
-s -w                              去掉调试信息；2026-10-25 实测：darwin/arm64 7.8 MB、
                                   linux/amd64 8.6 MB、windows/amd64 8.5 MB
                                   （不加这两条时 windows/amd64 约 12 MB）
-ldflags "-X …/internal/version.Version=<tag 去掉 cli/v>
          -X …/internal/config.DefaultServer=$SKILLMASTER_PUBLIC_BASE_URL"
```

**服务端地址必须注入**：`config.DefaultServer` 在本地的默认值是 `http://localhost:8080`（注释写明这是有意的——默认指向生产会让一条手滑的命令把凭据递给错的服务器）。发布构建把它覆盖成生产地址；本地构建保持 localhost，需要换地址就在 `.env` 里写 `SKILLMASTER_SERVER`。

**线上地址只在一个地方**：仓库变量 `SKILLMASTER_PUBLIC_BASE_URL`（`https://console.skillmasterai.com`）。流水线把它注入二进制，部署流水线把同一个变量渲染进服务端的 `server.env`，服务端再用它公布 `issuer`——CLI 拨的地址和服务端自称的地址因此是**同一个值**，不是两处要人保持同步的配置。变量为空时构建**直接失败**：一个连 localhost 的正式包比一个没发出来的版本更糟。构建完还有一条断言，从包里把 `linux/amd64` 解出来 `grep` 这个地址——`-X` 指向一个不存在的符号不会报错。

**代次编译进去**：`internal/api` 的 `Generation` 是代次的唯一来源，`APIPrefix` 由它拼出。**非测试代码里的 `"/api/v1…"` 字面量已全部收编**（2026-10-25，18 处），此后新增端点一律拼 `APIPrefix`——否则「改一行换代次」是句空话。

## 6 · 存放与访问

| 项 | 值 |
|---|---|
| 地址 | `https://dl.skillmasterai.com/` |
| 存储 | 阿里云 OSS bucket `skillmaster-dl`（华东1，**公共读、不开放写**） |
| 接入 | `dl.` 解析到 ECS，nginx 反代到 bucket 的外网 endpoint（[部署说明](../deploy/README.md)） |
| 证书 | ECS 上的 certbot，**DNS-01**：`dl.` 的 TXT 记录由 `/etc/skillmaster/acme/auth.sh` 写入 Aliyun DNS，续期由 `certbot.timer` 自动完成 |
| `Cache-Control` | **`no-cache`**——键名不带版本号，一缓存就会有人下到上一版；宁可每次回源 |

**不放 GitHub Releases**：目标用户在国内，那个域名对他们不可用。git tag 仍然标记版本历史，但**产物只有一个家**。

**上传顺序**：先六个包 → 再 `checksums.txt` → 最后两个安装脚本。读端拿到校验和时，它描述的东西已经在位；脚本放最后，因为它一旦可取就该能装成功。

**为什么不是 OSS 的自定义域名**：`PutCname` 对这个 bucket 一直回 `NoSuchCnameInRecord`，而 CNAME 记录、`_dnsauth` TXT 和 OSS 自己发的 token 全对得上（2026-10-26）。所以 `dl.` 指到那台 ECS，由 nginx 反代到 bucket——bucket 仍然是产物的唯一存放处，换个存放处只改 `deploy/nginx/skillmaster.conf` 里的一行。

**证书为什么走 DNS-01**：这台机器 80 端口对外的 ACME 请求会被阿里云的 ICP 检查先拦成 403，http-01 完不成（`www.` / `console.` 同样）。DNS-01 不碰端口，代价是 ECS 上留一份只够改 `skillmasterai.com` 解析的 AK，放在 `/etc/skillmaster/acme.env`（`0600 root`）。

## 7 · 安装脚本

**判断系统与架构是确定性工作，所以由脚本做，不由人或模型临时判断。**

| 系统 | 读什么 | 映射 |
|---|---|---|
| macOS / Linux | `uname -sm` | `Darwin arm64` → darwin_arm64；`Darwin x86_64` → darwin_amd64；`Linux x86_64` → linux_amd64；`Linux aarch64`\|`arm64` → linux_arm64 |
| Windows | `$env:PROCESSOR_ARCHITECTURE` | `AMD64` → windows_amd64；`ARM64` → windows_arm64 |

**认不出来就落到 `amd64`**：Apple Silicon 有 Rosetta 2、Windows ARM 有 x64 模拟，这两个兜底都能跑。在 Rosetta 下 `uname -m` 返回 `x86_64`，于是会装 amd64 版——同样能跑，只是走转译。

两个脚本做同一串事：

1. 判断平台 → 2. 取对应包与 `checksums.txt` → 3. **核对 SHA-256** → 4. 解包 →
5. 放进 PATH（unix 用 `~/.local/bin`，不要求 sudo；Windows 用 `%LOCALAPPDATA%\SkillMaster\bin`，用 `setx` 写 PATH）→
6. 打印 `skillmaster --version`

**用法**：

```
curl -fsSL https://dl.skillmasterai.com/install.sh | sh          # macOS / Linux
irm https://dl.skillmasterai.com/install.ps1 | iex               # Windows PowerShell
```

**信任边界**：脚本与包在同一个域名下，能改包的人也能改脚本——`curl | sh` 不会带来它本来就没有的安全。要更严一点，先下载、读一遍、再执行。

## 8 · 换代与退役

服务端换到新一代时，新旧两代**并行一段时间**；旧代退役后，旧路径返回 **410** 与 `{"code":"api_generation_retired","supported":["v2"]}`。CLI 认这个码，渲染成一句能照着做的事（**两侧都尚未实现，这是换代时的义务，不是今天的行为**）：「这个 CLI 说的是 API v1，服务器已经只支持 v2。按 https://www.skillmasterai.com/install/skillmaster.md 重装。」

**410 而不是 404**：那个地址存在过。404 会让人往「拼错了」或「被删了」上想。

**升级不在 v1 的范围内**：没有自动更新。CLI 唯一会主动叫人重装的场合，就是收到上面那个码。

## 9 · 未验证与已知限制

- **Windows 没有实测过。** `windows/amd64` 与 `windows/arm64` 能交叉编译出来（2026-10-25；不加 `-s -w` 时 12 MB / 11 MB，加了之后 amd64 是 8.5 MB），Windows 上的首次登录、`install.ps1` 的 PATH 写入、`.zip` 解包 —— **都没有在真实 Windows 上跑过**。在有 Windows 机器之前，这里写的是「未验证」，不是「支持」。
- **32 位 Windows / 32 位 Linux / FreeBSD / ppc64le / s390x 不发**，理由见 §3。
- **没有校验和签名**。只有 SHA-256，且它和包在同一台服务器上——这挡得住传输损坏，挡不住有人能写这个 bucket。

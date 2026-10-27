# 安装 SkillMaster CLI

这份说明写给**正在替人装东西的 agent**：照着做，最后一步是 `skillmaster startup`。

装的是命令行客户端，不是本机 skill。装完以后，skill 全部放在 SkillMaster 上，按需取用。

**现在装的是内测版（`1.1.0-beta`）。** 内测期里这条命令装的永远是最近一次构建；同一个版本串会有很多个构建，靠 `--version` 里的提交号分辨。内测版不保证兼容性：命令可能增删改，随时可能整个换掉。

## 0 · 先看一眼装没装

```bash
skillmaster --version
```

三种情况：

| 看到什么 | 做什么 |
|---|---|
| 命令找不到 | 从第 1 步装 |
| 打印出来了，但版本老了（`skillmaster update --check` 说有新构建） | `skillmaster update` |
| 已是最新 | 直接看第 4 步，先跑 `startup --plan` |

`startup --plan` 需要有小工具才能跑，所以「没装 / 旧了 / 已是最新」这三种情况的第一段由这一节负责；装好之后，剩下的清单都由 `--plan` 自己算。

## 1 · 跑安装脚本

**macOS / Linux**：

```bash
curl -fsSL https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/install.sh | SKILLMASTER_DL_URL=https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta sh
```

**Windows（PowerShell）**：

```powershell
$env:SKILLMASTER_DL_URL='https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta'; irm https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/install.ps1 | iex
```

（`SKILLMASTER_DL_URL` 是脚本本来就认的地址覆盖；内测版只是换一个前缀。两个名字指的是同一批文件。）

**为什么用上面这个地址，不是 `dl.skillmasterai.com`。** 那个域名还没走完备案，阿里云的合规设备会重置它的 TLS 1.2 握手，只放过 1.3：macOS 自带的 curl 与 Windows 上的 PowerShell 只会协商到 1.2，用 `dl.` 会得到 `curl: (35) Recv failure: Connection reset by peer`；浏览器和 CLI 自己走 1.3，所以看着是好的。备案通过后改回 `dl.`（见 `deploy/README.md` §TLS 与备案）。

**装好之后它会自己更新**（内测版每次构建都会跟进）：每次使用命令时最多每 24 小时看一眼有没有新构建（下载失败后一小时内重试），有就下载、核对校验和、换掉自己，下一条命令起生效。想立刻检查用 `skillmaster update --check`，想立刻装用 `skillmaster update`。装不进去（只读目录、沙箱）时它会说一次，那时重跑上面那条安装命令即可。

脚本自己判断系统和架构、下载对应产物、**核对 SHA-256**、装进 PATH（macOS / Linux 装到 `~/.local/bin`，Windows 装到 `%LOCALAPPDATA%\SkillMaster\bin`），最后打印 `skillmaster --version`。

- 校验和不符时脚本会停下，不安装。
- 认不出的架构会**拒绝安装**并说明原因：32 位系统跑不了 64 位程序，装上去只会得到一个起不来的命令。产物清单在下面，对得上就手工装。
- 脚本要能联网访问 `skillmaster-dl.oss-cn-hangzhou.aliyuncs.com`。

## 2 · 自己装（脚本跑不了时）

产物地址固定在 `https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/`，**文件名不带版本号**：

| 系统 | 架构 | 文件 |
|---|---|---|
| macOS | Apple Silicon | `skillmaster_darwin_arm64.tar.gz` |
| macOS | Intel | `skillmaster_darwin_amd64.tar.gz` |
| Linux | amd64 | `skillmaster_linux_amd64.tar.gz` |
| Linux | arm64 | `skillmaster_linux_arm64.tar.gz` |
| Windows | amd64 | `skillmaster_windows_amd64.zip` |
| Windows | arm64 | `skillmaster_windows_arm64.zip` |

**先核对再运行**：

```bash
curl -fsSLO https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/checksums.txt
curl -fsSLO https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/skillmaster_linux_amd64.tar.gz
sha256sum -c --ignore-missing checksums.txt     # macOS 用：shasum -a 256 -c --ignore-missing checksums.txt
tar -xzf skillmaster_linux_amd64.tar.gz
mkdir -p ~/.local/bin && mv skillmaster ~/.local/bin/
```

Windows 用 `Expand-Archive` 解开 zip，把 `skillmaster.exe` 放进 `%LOCALAPPDATA%\SkillMaster\bin`，并把这个目录加进用户 PATH。

## 3 · 验证

```bash
skillmaster --version
```

应当打印 `skillmaster 1.1.0-beta (<提交号>) (API v1)`，例如 `skillmaster 1.1.0-beta (77ef044) (API v1)`。

内测版的版本串是固定的，**提交号才是构建身份**：同一串号下的两次构建，靠括号里那七位分辨。报问题的时候把整行带上，对方才知道你装的是哪一次构建。

如果命令找不到，是 PATH 没生效：macOS / Linux 执行

```bash
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc    # 或 ~/.bashrc
```

然后重开一个终端；Windows 重开一个终端。

## 4 · 下一步：先看清单，再走旅程

```bash
skillmaster startup --plan --agent <这个 AI 的名字> --file <这个 AI 的用户级说明文件>
```

`--plan` 只读：不登录、不拉旅程、不写文件。它按这台机器的实际状态列出还差哪几件——小工具、登录、传技能（可选）、正式上线、让 AI 先来 SkillMaster 找技能、让 AI 用 SkillMaster 自己的文件夹（清理前才需要）、电脑上原来的那份（可选）；已经好了的收起来，没登录或断网的写成「登录后检查 / 暂时读不到」，不算成已完成。**传技能始终是可选的**：不传、只传一部分、传到一半停下，都不挡后面的步骤；想传的时候再跑一次 `--plan` 就能看到还剩几个。

写完说明后直接告诉用户「这段说明已经写进全局记忆，以后开新任务会用 SkillMaster 来读写技能」；不要求关掉再打开，也不做试用。之后再决定要不要清理电脑上原来的那份——清理是可选的。

看完清单，再走旅程：

```bash
skillmaster startup
```

它会先确认这台机器登录（没有凭据就打开浏览器，新用户在那页注册），然后加载入门技能，带你这台机器把 AI 配置成从 SkillMaster 取用。这台机器上已有的 skill 可以顺便搬上去；不想搬、只搬一部分，都不影响后面的步骤。

`--file` 写的是**这个 AI 的用户级说明文件**（整台机器、所有项目都会读到的那一份），位置由运行在客户端的 agent 自己确定；仍不确定就问「你用的是哪个 AI」，再直接写用户级记忆，不问路径。不知道文件在哪时可以先不带 `--file` 跑一次 `--plan`，清单会写「这台电脑上写过 N 份」或「还没写」。我们不给「AI → 文件」的对照表。

**换了一个 AI**，就在新 AI 里重跑第 4 步那条 `--plan`，用新名字和新文件；每个 AI 有自己的用户级说明文件，换 AI 不会自动带上。

遇到问题时 `skillmaster help` 列出所有命令，`skillmaster help <命令>` 给出参数与失败原因。

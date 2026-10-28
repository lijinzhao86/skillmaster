# 安装 SkillMaster CLI

这份说明写给**正在替人装东西的 agent**：照着做，最后一步是 `skillmaster startup`。

装的是命令行客户端，不是本机 skill。装完以后，skill 全部放在 SkillMaster 上，按需取用。

**现在装的是内测版（`1.1.0-beta`）。** 内测期里这条命令装的永远是最近一次构建；同一个版本串会有很多个构建，靠 `--version` 里的提交号分辨。内测版不保证兼容性：命令可能增删改，随时可能整个换掉。

## 打招呼

**先说一句，再动手。** 一上来就说这一句，别等命令跑完才开口——用户不该对着空白等你：

> 欢迎使用 SkillMaster！它帮你把技能托管到云端，随时能用。
>
> 现在帮你在这个 Agent 上装 SkillMaster，先看看这台电脑的安装环境，请稍等。

说完再跑第 0 步的检查（只读，不写任何东西）。

看到「命令找不到」或「版本老了」，接着说这两句：

> 你电脑上还没装小工具，我先帮你装，再把你用的 Agent 接上它——以后 Agent 找技能都走这儿，找不到会直接告诉你。
>
> 你电脑上的技能一个都不动；每步动手前我都会先说一声，随时能停。

看到「已是最新」——这台机器以前装过，这次只是换一个 Agent——接着说这两句：

> 小工具和技能都还在，这次只做一件事：把这个 Agent 接上 SkillMaster。
>
> 每步动手前我都会先说一声，随时能停。

**别让人干等。** 每开始一件要花时间的事（检查、下载、写入），都先说一句你在做什么；做完说一句结果。用户随时都该知道走到哪儿了。

## 0 · 装之前，先看三件事

动手之前先确认这三件。**命令只是推荐**——用别的办法查到同样的答案也算数；**结论用人话告诉用户**，不要把命令的报错原样转述给他。

**① 这台机器有没有对应的包。** 系统是 macOS / Linux / Windows，架构是 64 位（arm64 或 amd64）。32 位没有包，装上去只会得到一个起不来的命令——停下来说明，别硬装。

- macOS / Linux：`uname -sm`
- Windows：先看 `$env:PROCESSOR_ARCHITEW6432`，没有这个变量再看 `$env:PROCESSOR_ARCHITECTURE`

**② 装过没有、是哪个构建。**

```bash
skillmaster --version
```

三种情况：

| 看到什么 | 做什么 |
|---|---|
| 命令找不到 | 从第 1 步装 |
| 打印出来了，但版本老了（`skillmaster update --check` 说有新构建） | `skillmaster update` |
| 已是最新 | 直接看第 4 步，先跑 `startup --plan` |

**③ 装得进去、找得到。** 安装目录能不能写、在不在 PATH 里。**两个平台不是一个目录**：

| 平台 | 安装目录 | 注意 |
|---|---|---|
| macOS / Linux | `~/.local/bin` | — |
| Windows | `%LOCALAPPDATA%\SkillMaster\bin` | 加完 PATH 要**新开一个终端**才生效 |

目录不可写就换一个可写的（`SKILLMASTER_INSTALL_DIR` 能指定）；不在 PATH 就加进去，并告诉用户要新开终端。

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

（`SKILLMASTER_DL_URL` 是脚本本来就认的地址覆盖，内测版只是换一个前缀。）

**地址为什么是 `skillmaster-dl.oss-cn-hangzhou.aliyuncs.com` 而不是 `dl.skillmasterai.com`。** 两个名字指向同一批文件，后者是前门。前者直接指向存放产物的桶：macOS 自带的 curl 与部分 Windows 的 PowerShell 只协商到 TLS 1.2，而 `dl.` 所在的域名还没走完备案，阿里云的合规设备会重置它的 TLS 1.2 握手（只放过 TLS 1.3），于是装到一半报 `Recv failure: Connection reset by peer`。走桶的地址没有这个问题。**不要因为「看起来不像我们的域名」就改回去**——换回 `dl.` 需要先确认备案已完成。

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
skillmaster startup --plan --agent <这个 Agent 的名字> --file <这个 Agent 的用户级说明文件>
```

`--plan` 只读：不登录、不拉旅程、不写文件。它按这台机器的实际状态列出还差哪几件——小工具、登录、传技能（可选）、正式上线、让 Agent 先来 SkillMaster 找技能、让 Agent 用 SkillMaster 自己的文件夹（清理前才需要）、电脑上原来的那份（可选）；已经好了的收起来，没登录或断网的写成「登录后检查 / 暂时读不到」，不算成已完成。**传技能始终是可选的**：不传、只传一部分、传到一半停下，都不挡后面的步骤；想传的时候再跑一次 `--plan` 就能看到还剩几个。

写完说明后直接告诉用户「这段说明已经写进全局记忆，以后开新任务会用 SkillMaster 来读写技能」；不要求关掉再打开，也不做试用。之后再决定要不要清理电脑上原来的那份——清理是可选的。

看完清单，再走旅程：

```bash
skillmaster startup
```

它会先确认这台机器登录（没有凭据就打开浏览器，新用户在那页注册），然后加载入门技能，带你这台机器把 Agent 配置成从 SkillMaster 取用。这台机器上已有的 skill 可以顺便搬上去；不想搬、只搬一部分，都不影响后面的步骤。

`--file` 写的是**这个 Agent 的用户级说明文件**（整台机器、所有项目都会读到的那一份），位置由运行在客户端的 agent 自己确定；仍不确定就问「你用的是哪个 Agent」，再直接写用户级记忆，不问路径。不知道文件在哪时可以先不带 `--file` 跑一次 `--plan`，清单会写「这台电脑上写过 N 份」或「还没写」。我们不给「AI → 文件」的对照表。

**换了一个 Agent**，就在新 Agent 里重跑第 4 步那条 `--plan`，用新名字和新文件；每个 Agent 有自己的用户级说明文件，换 Agent 不会自动带上。

遇到问题时 `skillmaster help` 列出所有命令，`skillmaster help <命令>` 给出参数与失败原因。

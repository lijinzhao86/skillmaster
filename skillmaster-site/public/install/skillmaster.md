# 安装 SkillMaster CLI

这份说明写给**正在替人装东西的 agent**：照着做，最后一步是 `skillmaster startup`。

装的是命令行客户端，不是本机 skill。装完以后，skill 全部放在 SkillMaster 上，按需取用。

**现在装的是内测版（`1.1.0-beta`）。** 内测期里这条命令装的永远是最近一次构建；同一个版本串会有很多个构建，靠 `--version` 里的提交号分辨。内测版不保证兼容性：命令可能增删改，随时可能整个换掉。

## 1 · 跑安装脚本

**macOS / Linux**：

```bash
curl -fsSL https://dl.skillmasterai.com/beta/install.sh | SKILLMASTER_DL_URL=https://dl.skillmasterai.com/beta sh
```

**Windows（PowerShell）**：

```powershell
$env:SKILLMASTER_DL_URL='https://dl.skillmasterai.com/beta'; irm https://dl.skillmasterai.com/beta/install.ps1 | iex
```

（`SKILLMASTER_DL_URL` 是脚本本来就认的地址覆盖，内测版只是换一个前缀。更新就是重跑同一条命令。）

脚本自己判断系统和架构、下载对应产物、**核对 SHA-256**、装进 PATH（macOS / Linux 装到 `~/.local/bin`，Windows 装到 `%LOCALAPPDATA%\SkillMaster\bin`），最后打印 `skillmaster --version`。

- 校验和不符时脚本会停下，不安装。
- 认不出的架构会**拒绝安装**并说明原因：32 位系统跑不了 64 位程序，装上去只会得到一个起不来的命令。产物清单在下面，对得上就手工装。
- 脚本要能联网访问 `dl.skillmasterai.com`。

## 2 · 自己装（脚本跑不了时）

产物地址固定在 `https://dl.skillmasterai.com/beta/`，**文件名不带版本号**：

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
curl -fsSLO https://dl.skillmasterai.com/beta/checksums.txt
curl -fsSLO https://dl.skillmasterai.com/beta/skillmaster_linux_amd64.tar.gz
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

## 4 · 下一步

```bash
skillmaster startup
```

它会先确认这台机器登录（没有凭据就打开浏览器，新用户在那页注册），然后加载入门技能，按步骤带你把这台机器上已有的 skill 搬到平台上、配置 agent 从平台取用。

遇到问题时 `skillmaster help` 列出所有命令，`skillmaster help <命令>` 给出参数与失败原因。

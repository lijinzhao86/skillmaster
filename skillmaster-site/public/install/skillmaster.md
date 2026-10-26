# 安装 SkillMaster CLI

这份说明写给**正在替人装东西的 agent**：照着做，最后一步是 `skillmaster startup`。

装的是命令行客户端，不是本机 skill。装完以后，skill 全部放在 SkillMaster 上，按需取用。

## 1 · 跑安装脚本

**macOS / Linux**：

```bash
curl -fsSL https://dl.skillmasterai.com/install.sh | sh
```

**Windows（PowerShell）**：

```powershell
irm https://dl.skillmasterai.com/install.ps1 | iex
```

脚本自己判断系统和架构、下载对应产物、**核对 SHA-256**、装进 PATH（macOS / Linux 装到 `~/.local/bin`，Windows 装到 `%LOCALAPPDATA%\SkillMaster\bin`），最后打印 `skillmaster --version`。

- 校验和不符时脚本会停下，不安装。
- 认不出的架构按 `amd64` 装（Apple Silicon 走 Rosetta 2、Windows ARM 走 x64 模拟，都能跑）。
- 脚本要能联网访问 `dl.skillmasterai.com`。

## 2 · 自己装（脚本跑不了时）

产物地址固定在 `https://dl.skillmasterai.com/`，**文件名不带版本号**：

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
curl -fsSLO https://dl.skillmasterai.com/checksums.txt
curl -fsSLO https://dl.skillmasterai.com/skillmaster_linux_amd64.tar.gz
sha256sum -c --ignore-missing checksums.txt     # macOS 用：shasum -a 256 -c --ignore-missing checksums.txt
tar -xzf skillmaster_linux_amd64.tar.gz
mkdir -p ~/.local/bin && mv skillmaster ~/.local/bin/
```

Windows 用 `Expand-Archive` 解开 zip，把 `skillmaster.exe` 放进 `%LOCALAPPDATA%\SkillMaster\bin`，并把这个目录加进用户 PATH。

## 3 · 验证

```bash
skillmaster --version
```

应当打印 `skillmaster <版本> (API v1)`。版本号由二进制自己说，文件名里没有它。

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

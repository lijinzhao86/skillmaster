# 安装 SkillMaster CLI

这份说明写给**正在替人装东西的 agent**。它只管一件事：**把 SkillMaster CLI 装到这台电脑上，能跑起来就算完**——装的是一个命令行客户端，不是一个技能库；装好之后交给 `skmr startup`，那边负责配置。

**现在装的是内测版（`1.1.0-beta`）。** 内测期里这条命令装的永远是最近一次构建；同一个版本串会有很多个构建，靠 `--version` 里的提交号分辨。内测版不保证兼容性：命令可能增删改，随时可能整个换掉。

## 话术定稿（照说，不要改写）

**这张表是所有要跟用户说的话的唯一来源。** 第一列是行号；下面说到「第 N 行」就是这张表的第 N 行，不重复原话。改话术只改这张表。

| # | 什么时候用 | 照说 | 不要这样说 |
|---|---|---|---|
| 1 | 开场。还没跑任何命令 | 欢迎使用 SkillMaster！它帮你把技能托管到云端，随时能用。<br>现在帮你在这个 Agent 上装 SkillMaster CLI，先看看这台电脑的安装环境，请稍等。 | 「正在检测环境…」 |
| 2 | 机器没有对应的包（32 位或不认识的系统）。说完**停** | 这台电脑装不了 SkillMaster——它要 64 位的系统，这台不是。我先停在这里，不装了。<br>换一台 64 位的电脑再来；或者告诉我这是什么系统，我看看有没有别的办法。 | 「架构不受支持」 |
| 3 | 没装过 | 你电脑上还没装 SkillMaster CLI，一个装在这台电脑上的命令行工具。我先帮你装，装好以后你的 Agent 找技能会先来 SkillMaster——找不到会直接告诉你。 | 「未检测到 CLI」 |
| 4 | 装过，只是这个窗口找不到它 | SkillMaster CLI 其实已经在这台电脑上了，只是现在这个窗口还找不到它。我把它的位置告诉系统，你新开一个窗口就能用。 | 「PATH 未配置」 |
| 5 | 装了，但不是最新构建 | SkillMaster CLI 有个新版本，我先帮你更新，更新完接着来。已经做好的不会变。 | 「检测到新版本，运行 update」 |
| 6 | 装了，且是最新——交接的开头 | 不用装，SkillMaster CLI 已经是最新的了。 | 「已是最新版本」 |
| 7 | 刚装好——交接的开头 | 装好了。以后你的 Agent 就会通过它来查找、读写 SkillMaster 上的技能。 | 「安装成功」 |
| 8 | 改好 PATH、新开终端之后——交接的开头 | 现在能用了。 | 「PATH 已配置」 |
| 9 | 交接的下一步：**还没登录** | 现在只差一步：登录 SkillMaster。我打开一个网页——有账号直接登，没有就在那页注册一个。<br>登录完，我就把这个 Agent 接上；想看看 SkillMaster 里长什么样，我再带你转一圈。 | 「未检测到凭据，请先 auth login」 |
| 10 | 交接的下一步：**已经登录** | 你已经登录着，这一步跳过。接下来我把这个 Agent 接上，你就能开始用了。 | — |
| 11 | 下载失败、`Connection reset by peer`、连不上 | 下载没成功。我换个地址再试一次；还不行就是这台电脑连不上我们的下载点，先停在这里。 | 转述报错原文 |
| 12 | `建不了安装目录` / `复制失败` | 这个位置系统不让写。我换一个能写的位置装，装出来是一样的东西。 | 「mkdir 失败」 |
| 13 | `校验和不符` | 下载到的文件和清单对不上，我没有安装。这多半是网络中途出错，重试一次就好。 | — |
| 14 | 彻底停住（试过也不成） | 我先停在这里：这次什么都没装成，你电脑上原来的东西也没被动过。想再试的时候跟我说一声，我从这一步接着来。 | 「安装失败」 |

**两条规矩**：① 表里的话照说，不改写、不换说法；② 同一件事只说一遍。

## 步骤

### 1 · 开场

先说第 1 行（开场），**别等命令跑完再开口**——用户不该对着空白等你。说完才往下看。

### 2 · 看一眼这台电脑

这一步只读，不写任何东西；**也不用跟用户播报进度**——查完直接说结果。

**① 有没有对应的包。** 系统是 macOS / Linux / Windows，架构是 64 位（arm64 或 amd64）。32 位没有包，装上去只会得到一个起不来的命令——用第 2 行（机器不支持）说清，停，别硬装。

- macOS / Linux：`uname -sm`
- Windows：先看 `$env:PROCESSOR_ARCHITEW6432`，没有这个变量再看 `$env:PROCESSOR_ARCHITECTURE`

**② 装过没有、是哪个构建。** 四种结果，各有各的说法——**别把前两种混成一种**：

| 看到什么 | 意味着 | 用哪行 | 下一步 |
|---|---|---|---|
| `skmr --version` 找不到这条命令，默认目录里也没有 `skmr` | 没装过（或装的是改名前的版本） | 3 | 去「3 · 装」 |
| 命令找不到，但默认目录里**有** `skmr` | 装过，只是这个窗口找不到它 | 4 | 把目录加进 PATH，**新开一个终端**；改好用第 8 行交接，再去「4 · 结果」 |
| 命令能跑，但不是最新的构建 | 旧了 | 5 | `skmr update` |
| 命令能跑，且是最新 | 好了 | 6 | 第 6 行就是交接，说完直接去「4 · 结果」跑 `startup` |

默认目录：macOS / Linux 是 `~/.local/bin`，Windows 是 `%LOCALAPPDATA%\SkillMaster\bin`（Windows 加完 PATH 要新开终端）。

### 3 · 装

**直接装，不问、也不播报进度**——用户把「开始」那句发过来，就已经是同意了。命令在下面。

**macOS / Linux**：

```bash
curl -fsSL https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/install.sh | SKILLMASTER_DL_URL=https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta sh
```

**Windows（PowerShell）**：

```powershell
$env:SKILLMASTER_DL_URL='https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta'; irm https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/install.ps1 | iex
```

（`SKILLMASTER_DL_URL` 是脚本本来就认的地址覆盖，内测版只是换一个前缀。地址为什么是这个、什么时候换回 `dl.`，见附录 C。）

### 4 · 结果

**交接是两句，不是一句。** 先按结果说开头那一句，再看登录状态接后半句：

| 走的是哪条路 | 开头说 | 接着看登录 |
|---|---|---|
| 刚装好 / 刚更新完 | 第 7 行 | 没登录 → 第 9 行；已经登录 → 第 10 行 |
| 改好了 PATH | 第 8 行——不是「装好了」（没装东西），也不是「不用装」（用户刚被告知找不到它） | 同上 |
| 本来就最新 | 第 6 行 | 同上 |

**怎么知道登录没有**：`skmr auth status`。看不到凭据、或者命令报错，就当没登录，用第 9 行。**已经登录的机器不要说第 9 行**——让用户去登一个已经登着的账号，是这次对话里最没意义的一句话。

**登录与「装没装」是两件独立的事。** 用户完全可能在装之前就在网页上注册并登录过，也可能装完还没账号——所以上面三条路（第 6 / 7 / 8 行）都会遇到「已登录」和「未登录」两种，一律以 `auth status` 的结果为准，不靠猜。

**本来就最新那一路不要再补第 7 行**——这次什么都没装，说「装好了」是假话。

说完跑：

```bash
skmr startup
```

安装到这里就结束了——登录、把 Agent 接上、缺哪几步，都由 `startup` 管；它会先问你两件事，再一件一件做。**也不用在这里解释后面的步骤。**

**没装成**：按下表用第 11 / 12 / 13 行，仍不成用第 14 行（停住）。每次都先说清：**这次什么都没装成，你电脑上原来的东西没被动过。**

## 附录 A · 脚本失败时对照

| 脚本说什么 | 用哪行 | 接下来 |
|---|---|---|
| 下载失败、`Connection reset by peer`、连不上 | 11 | 换桶的地址重试一次；再失败用第 14 行（停住） |
| `建不了安装目录` / `复制失败` | 12 | 换一个可写目录再装；仍失败用第 14 行（停住） |
| `校验和不符` | 13 | 重试一次；仍失败用第 14 行（停住） |
| `不支持的系统` | 2 | 第 2 行的情形：这台机器装不了，停在这里 |
| `认不出架构` | 2 | 同上——脚本认不出的架构没有对应的包 |

**不许为了让它「成功」绕开校验和、换来源，或把失败说成成功。** 停下时说清停在哪一件事、什么都没变、以后怎么继续（重跑这份安装说明）。

## 附录 B · 手工安装（脚本跑不了时）

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
mkdir -p ~/.local/bin
mv skmr ~/.local/bin/
```

Windows 用 `Expand-Archive` 解开 zip，把 `skmr.exe` 放进 `%LOCALAPPDATA%\SkillMaster\bin`，并把这个目录加进用户 PATH。

**两个平台都要确认目录在 PATH 里**（安装脚本会替你检查；手工装没有这一步）：macOS / Linux 在 `~/.zshrc` 或 `~/.bashrc` 里加 `export PATH="$HOME/.local/bin:$PATH"`，然后重开终端——不加的话 `skmr` 会「找不到命令」。

## 附录 C · 下载地址

这里用的是存放产物的桶的直连地址，与 `dl.skillmasterai.com` 是同一批文件。**不要改成 `dl.`**——那个域名还没走完备案，部分客户端（macOS 自带 curl、部分 Windows PowerShell）会在装到一半被重置；原因与换回的判据见仓库里的 `deploy/README.md` §TLS 与备案。

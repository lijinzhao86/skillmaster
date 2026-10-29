# v29 · 技术设计

> **最后更新**：2026-10-29 · **所属版本**：[v29](README.md)

## 架构

命令名出现在四个面上，每个面的改法与风险差别很大：

| 面 | 今天 | 这一版 |
|---|---|---|
| 二进制与入口 | `~/.local/bin/skillmaster` 一个文件 | 实体文件 `skmr`；旧名作为第二个入口（Unix 符号链接，Windows 硬链接） |
| 面向人的输出 | 约 270 处命令示例（非测试 Go）+ 89 处（测试） | 全部改成 `skmr`，并用一条守卫测试防回潮 |
| 发布与自更新 | 资产 `skillmaster_<os>_<arch>`，包内 `skillmaster[.exe]` | 资产名不变；包内改成 `skmr`，安装与自更新都按这个名字走 |
| 文案面 | 站点安装说明、服务端提示、设计件、控制台 | 改成 `skmr` |

### 名字的单一来源

**不做 `const cmd = "skmr"`**：命令名嵌在绝大多数中文句子里（「用法见：skillmaster help」、「下一步：skillmaster startup」），抽成常量会把它们全部改成格式串，改动面从「逐行替换」变成「重写」，审计时再也对不上「哪一处该改、哪一处没改」。

**代替它的是守卫测试**：`skillmaster-cli/` 下的非测试 Go 源文件里，**任何一个字符串字面量只要含旧名**即失败（用 `go/ast` 取字面量，注释不算——注释里的历史说法不该被追着改）。

判据故意宽：第一版只匹配「裸名 / 带空格 / 带下划线」三种形状，结果放过了 `升级 skillmaster。`（名后是句号）这种句子。宽并不误伤，因为**同字母不同事实**的几处在 `isOldCommandName` 里逐条列了理由：`skillmaster-cli`（注册的 OAuth client id 与钥匙串服务名，也是 import 路径的一部分）、`skillmasterai.`（域名）、`skillmaster/`（发布者命名空间下的 skill 地址，也是 module 路径的一部分）、`.skillmaster`（版本里的附属目录）。**判法是先把这些从字面量里摘掉，再看还剩不剩旧名**——否则一个同时含 client id 与命令的长句会因为「命中了例外」被整句放过。`SKILLMASTER_*` 是大写，本来就不匹配。

口径：改名前 `rg -o '\bskillmaster [a-z-]+'` 在非测试 Go 里数得 **271** 次、测试 89 次；按 `go/ast` 的字符串字面量数是 261 个（一个字面量里可能出现两次）。两个数只用来表示量级，守卫只认「字面量里有没有这个词」。

### 旧名入口（一行，只在代码里）

`config.LegacyName` 一个常量，加 `updater.EnsureLegacyEntry(target)`：启动时若官方安装目录里只有新名字，就补一个指向它的旧名入口（Unix 符号链接，Windows 硬链接——symlink 在 Windows 上要一个本程序从不申请的特权）。**只在官方目录、只创建不删除、一次系统调用完成（因此不可能写穿已有文件、也不会留下半写的一份）、失败静默。**

为什么要有：`skillmaster …` 还写在别人机器里的 agent 记忆文件、以及已经发出去的 skill 正文里（v27 起 CLI 不再改写那些文件）。一个链接让它们继续能用，成本接近零。

为什么只有这一处：安装脚本不碰它，自更新也不碰它——**文档、界面、帮助里都不写这一层**（ADR 0113 决定 3）。它是给已经写在别处的旧字符串用的，不是第二个名字。

### 文案面

| 面 | 改哪里 | 注意 |
|---|---|---|
| 设计件 | `design/spec.md`、`design/src/console/pages/SkillsPage.tsx`、`design/src/shared/auth/views/ConsentIncompleteView.tsx` | 设计件是界面权威，先改它，再同步产品 |
| 控制台 | 上述两个组件的产品副本 + 相关测试 | 只换文案里的命令名，结构与样式不动 |
| 站点 | `skillmaster-site/public/install/skillmaster.md` 正文 | **路径不改**：它被 `skillmaster-site/src/App.tsx` 的 agent 提示与 [ADR 0088](../../decisions/0088-cli-major-version-is-the-api-generation.md) 决定 4 的 410 文案引用 |
| 服务端 | `Organize.java` 拼出的 `skillmaster skill join …` | 与 CLI 同时发，否则两边说的名字不一样 |
| 平台 skill 正文 | `skillmaster/startup` 等 | **人的动作**，代码里改不了 |

## 数据模型

没有表结构变化。**不动的身份**：

| 东西 | 值 | 为什么不动 |
|---|---|---|
| 配置目录 | `~/.config/skillmaster` | 凭据、pin、`prompt.json` 都在那里；改了等于所有人重新登录 |
| 环境变量前缀 | `SKILLMASTER_*` | 安装与更新的对外开关，改名会让脚本与镜像一起断 |
| 平台 skill 地址 | `skillmaster/startup` | 发布者命名空间，不是命令名 |
| Go module / 仓库名 | `github.com/lijinzhao86/skillmaster` | 不是命令名 |

记忆块的摘要（`prompt.Digest()`）会随正文改变，但今天没有任何代码拿它与磁盘上的记录比较——已装机器上记录的旧摘要不会触发任何提示。这一点在 v27 就是如此，不是本版引入的。

## 接口

### 命令面

61 个命令路径、参数、退出码、输出形状**一个不改**。变的只有程序名。

### `--version`

`skmr 1.2.0 (API v1)`，**从哪个入口调用都一样**——不按 `argv[0]` 变。旧名是兼容入口，不是第二个身份。

### 安装脚本

| 步骤 | 今天 | 这一版 |
|---|---|---|
| 下载的产物 | `skillmaster_<os>_<arch>.tar.gz` | 不变 |
| 包内文件 | `skillmaster` | `skmr` |
| 装到哪 | `$INSTALL_DIR/skillmaster` | `$INSTALL_DIR/skmr`（旧名入口由二进制启动时自己补） |
| 输出 | 「已安装到 …/skillmaster」 | 「已安装到 …/skmr」 |
| 冒烟 | `skillmaster --version` | `skmr --version` |
| 最后一句 | 「下一步：skillmaster startup」 | 「下一步：skmr startup」 |

Windows 的 `install.ps1` 同构：装 `skmr.exe`。

**同一个文件里还要修一个既有缺陷**（prd 做 #9，实现这一版时实测发现）：`$artifact（…` 这样的写法里，`sh` 会把后面的全角字符当成变量名的一部分，`set -u` 下直接报 `unbound variable`——错误分支本身说不出话。12 个 `die` 分支里有 4 个中招（第 30、37、54、71 行）。改法是把变量写成 `${…}`，没有别的动作：**分支说的话、退出码、触发条件都不变**。

### 自更新

协议不变：仍是 `version.txt` → `checksums.txt` → 资产 → 解包取 `BinaryName` → 替换。变的只有 `BinaryName` 的返回值（`skmr` / `skmr.exe`）。

## 分期

| 期 | 交付 | 验收 |
|---|---|---|
| P0 | CLI 本体：所有面向人的字符串、`block.md`、`--version`、守卫测试 | 验收 2、3、4；验收 6 的**代码那一半** |
| P1 | 安装脚本与更新器：`install.sh` / `install.ps1`、`Apply` / `RunHelper`、包内文件名 | 验收 1、8；自更新按新名取包与替换（T5） |
| P2 | 文案面：设计件 → 控制台；站点安装说明；服务端提示 | 验收 5；验收 6 的**文案那一半** |
| P3 | 人的动作：平台 skill 正文重发；重发 beta 通道（站点安装说明指向 `beta/`，先于页面更新跑一次 `cli-beta.yml`）；tag `cli/v1.2.0` | 验收 7；验收 2 里只能在发版后验的版本号 |

## 开放问题

1. **资产名什么时候能改回 `skmr_*`？** 条件是所有客户端都 ≥ 1.2.0，而今天没有任何遥测能回答这个问题。倾向：**不改**，把它当长期的兼容面；真要改，先加一个能观测的版本头，再另开 ADR。
2. **`cmd/skillmaster/` 目录要不要改名？** 它是仓库内部的路径，不是命令名，用户看不见。倾向：**本版不改**——这一版的价值在于「每一处用户可见的名字都改到」，顺带动目录会让 diff 变长、让审计失真；下一版可以单独做。
3. **Windows 上的旧名会不会与 `skmr` 分叉？** 会。自更新替换的是**被运行的那一份**：`installedExecutable()` 解析符号链接，所以 Unix 上从哪条路进来都落到 `skmr`；Windows 的硬链接没有可解析的链接，用旧名跑 `update` 更新的就是旧名那一份，`skmr` 停在旧版本。`EnsureLegacyEntry` 不刷新、也不覆盖已存在的文件——那是「一行兼容」自己的取舍（ADR 0113 决定 3），**不是** [ADR 0101](../../decisions/0101-the-cli-never-deletes-files.md) 的禁令（0101 只禁 `unlink`，明确允许 `rename` 覆盖）。要消掉分叉得让更新器在替换后重写旧名，需要 Windows 真机验证，本版没有（T9 未执行）。影响面见 KI-2。

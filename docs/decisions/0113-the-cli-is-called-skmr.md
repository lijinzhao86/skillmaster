# ADR 0113 · CLI 的命令名改为 `skmr`

- **状态**：已采纳
- **日期**：2026-10-29
- **依赖**：[`versions/v29-cli-rename-to-skmr/`](../versions/v29-cli-rename-to-skmr/)；[0088](0088-cli-major-version-is-the-api-generation.md) 决定 2（同一主版本内命令面只增不减）

## 背景

命令名 `skillmaster` 有 11 个字符，而它是这台电脑上出现最频繁的字符串之一：每一条命令、每一份 `--help`、以及写进每台机器 agent 记忆块的那段正文里都有它。产品负责人要求换一个更短的名字。

选名字时撞上的不是审美问题，是**名字已经被占**。2026-10-29 实测：

| 名字 | 谁在用 |
|---|---|
| `skm` | Homebrew core 的 `skm`（SSH 密钥管理器，0.9.3，`brew install skm` 直接装到它）；PyPI `skm-cli` 装的命令也叫 `skm`，自我描述为「Skill Manager」，0.4.0 于当日发布——**与我们同品类** |
| `skillm`、`skil` | npm / crates.io 上同品类的 agent skill 管理器 |
| `skmr` | npm 上同名同品类的包，2026-02-11 发布，此后停更，每周 1 次下载 |
| `skml`、`ksm`、`skmaster` | 五个注册中心（brew / npm / PyPI / RubyGems / crates）全空 |

出处（2026-10-29 逐个查的）：[Homebrew core 的 `skm`](https://github.com/Homebrew/homebrew-core/blob/421f3eb0631febf70dbba5c0c837130b0ddc7ee1/Formula/s/skm.rb)、[PyPI `skm-cli`](https://pypi.org/project/skm-cli/)、[npm `skmr`](https://www.npmjs.com/package/skmr)、[npm `skillm`](https://www.npmjs.com/package/skillm)、[crates.io `skil`](https://crates.io/crates/skil)；「全空」是按这五个注册中心的查询结果逐个确认的，不是凭印象。

## 决定

1. **命令名改为 `skmr`。** usage、help、提示、错误、记忆块、站点与控制台的文案一律用新名。
2. **发布产物的名字不变**：六个包仍是 `skillmaster_<os>_<arch>.{tar.gz,zip}`。它是用户从安装说明里复制的地址，不该因为命令改名而移动；包里面的可执行文件叫 `skmr`。
3. **旧名只留一处兼容，且只在代码里。** `config.LegacyName` 一个常量，进程启动时若官方安装目录里只有新名字，就补一个指向它的旧名入口（Unix 符号链接，Windows 硬链接）。文档、界面、帮助里都不写这一层——它是给「已经写在别处的旧字符串」用的：别人机器里的记忆文件、已经发出去的 skill 正文。
4. **数据与身份不变**：状态目录仍是 `…/skillmaster`，仓库名、Go module 路径、域名、平台上的 skill 地址（`skillmaster/startup`）都不动。

## 理由

- **被否的 `skm`**：它同时被一个活的 SSH 工具和一个**同品类**的 skill 管理器占着。同品类同名意味着用户搜索、论坛讨论、以及 `~/.local/bin` 里的那一个文件都会指向别人——安装脚本往那里放 `skm`，会直接盖掉 `uv tool install skm-cli` 装的那一个。
- **被否的 `skmaster`**：它干净、念得出、也和产品同源，但只把 11 个字符变成 8 个。这一版要买的就是「短」，而它只买了四分之一——`skmr` 是 4 个。
- **被否的 `skml` / `ksm`**：它们干净，但念不出，也看不出与 SkillMaster 的关系。每天要敲很多次的名字，可读性优先于「注册中心全空」这一条。
- **`skillm` / `skil` 是被占，不是被嫌**：npm 与 crates.io 上都有同品类的包在用这两个名字，理由与 `skm` 相同。
- **产物名不改**：那是安装说明里唯一需要人复制的东西。改它等于让所有书签、聊天记录、镜像里的链接一起失效，而收益只是「看起来一致」。
- **旧名只留一行、且不进文档**：改名不是一次性的删除——`skillmaster …` 还写在别人机器里的记忆文件和已经发出去的 skill 正文里。一个符号链接让它们继续能用，成本接近零；把它写进文档与界面，则会让人以为有两个名字。
- **为什么不是主版本**：改名不改 API。按 0088，主版本号是 API 代次，所以这次是次版本（1.2.0）。

## 后果

- 安装脚本、发布流水线、自更新、站点安装说明、服务端提示文案、设计件与控制台里的命令文案，都要跟着改。
- 仓库里的**历史版本文档与 ADR 保持原样**——它们是当时发生过什么的记录，其中的 `skillmaster` 不是错误。从 v29 起新写的文档用 `skmr`。
- `/install/skillmaster.md` 这个地址**不改**：它是安装说明页，被站点首页的 agent 提示与 0088 决定 4 的 410 文案引用；文档标题里的 SkillMaster 是产品名，不是命令名。
- 平台上的 skill 正文（`skillmaster/startup` 等）需要人工重发一次；在那之前，它们里面的 `skillmaster …` 仍能跑，靠的就是决定 3 那一行。

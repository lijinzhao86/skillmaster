# 安装 SkillMaster

这份文档是给 Agent 执行的安装步骤：读它，再照做。

它按顺序做六件事：**先确认两个前提**；**把 SkillMaster 的技能装进来**；**确认命令行客户端的状态，没有就装上**；**问一次要不要现在配置沙箱**；**确认登录**；**问一次要不要做新人引导，要就交给 `skmr startup`**——引导由它接手。

**现在装的是内测版（`1.1.0-beta`）。** 内测期的安装命令永远装最近一次构建：版本串相同、内容可能不同，用 `--version` 打印的提交号区分。内测版不承诺兼容，命令随时可能增删。

## 话术定稿（照说，不要改写）

**所有对用户说的话都以这张表为准。** 第一列是行号；下文说「第 N 行」，指的就是表里编号 N 的那句，不再重复原文。要改话术，只改这张表。

| # | 什么时候用 | 照说 | 不要这样说 |
|---|---|---|---|
| 1 | 开场。两个前提检查已通过 | 欢迎使用 SkillMaster！它帮你在交代任务时，先找到现成的做法。<br>接下来我给这个 Agent 装一个 SkillMaster 的技能。 | 「正在初始化…」 |
| 2 | 请求确认。**用选项卡** | 装好以后，很多事我不用一步步问你，你说想做什么，我就能直接接上。<br>选项卡：`现在安装（推荐）` / `先不安装` | 直接开装、不确认 |
| 3 | 用户选「先不安装」。**用选项卡** | 这个技能不装，我就没法替你管理 SkillMaster 上的技能——每一步该怎么做，我都不清楚。<br>选项卡：`现在安装（推荐）` / `先不安装` | 「你确定不装吗」 |
| 4 | 用户再次拒绝 | 好的，那这次先不装。你的电脑上什么都没动，想装的时候把安装说明再发给我，我接着来。 | 「安装失败」 |
| 5 | 用户提问。**先回答，不往下走** | 你问，我答，问完再往下走——不着急，我不会趁你问的时候继续装。 | 边回答边继续装 |
| 6 | 用户确认后，开始装技能 | 好，我现在把这个技能装到这个 Agent 里。你不用做什么，等我消息。 | — |
| 7 | 技能装好 | 技能装好了。<br>以后你交代任务，我会先去 SkillMaster 里找有没有现成的做法；想直接叫我，输入 `/skillmaster`。<br>这个技能只装在这台电脑的这个 Agent 里。换一台电脑，或者在同一台电脑上换一个 Agent，都要再装一次——到时候把安装说明发给新的 Agent 就行。 | 「技能已安装到 ~/.claude/skills」 |
| 8 | 告知要下载客户端。**只告知，不确认** | 接下来装一个命令行客户端（`skmr`），我通过它连到 SkillMaster。这一步你也不用动手，我来。 | 「即将执行 curl」 |
| 9 | 技能没装成。**用选项卡** | 这个技能没装成，你电脑上的其它东西都没动。<br>选项卡：`再试一次` / `先到这里` | 转述报错原文 |
| 10 | 没装过 CLI | 这台电脑上还没有这个客户端，我现在帮你装上，你不用管。 | 「未检测到 CLI」 |
| 11 | 装过，只是这个窗口找不到它 | 客户端已经装在这台电脑上了，只是当前这个窗口暂时找不到它。我把它的位置告诉系统，然后请你新开一个窗口，我接着往下走。 | 「PATH 未配置」 |
| 12 | 装了，但不是最新构建 | 客户端有个新版本，我先帮你更新一下，再接着装。已经装好的部分不受影响。 | 「检测到新版本，运行 update」 |
| 13 | 装了，且是最新 | 客户端已经是最新的了，这一步跳过。 | 「已是最新版本」 |
| 14 | 刚装好 / 刚更新完 | 客户端装好了。 | 「安装成功」 |
| 15 | 改好 PATH、新开终端之后 | 好了，现在能用了，我继续。 | 「PATH 已配置」 |
| 16 | 机器没有对应的包（32 位、Windows 10 1803 以下、不认识的系统、**这个架构没有对应的包**，或 `curl.exe` / `tar` 跑不了）。说完**停**。**用选项卡** | 这台电脑的系统、硬件或自带工具这次装不了——需要 64 位、Windows 10 1803 及以上，并且能运行下载和解包工具。你的电脑上什么都没动。<br>选项卡：`另想办法` / `先到这里` | 「架构不受支持」 |
| 17 | 下载失败、`Connection reset by peer`、连不上。**自动重试一次，不打扰用户** | 下载没成功。我再试一次；要是还不行，说明这台电脑连不上 SkillMaster 的下载服务器。 | 转述报错原文 |
| 18 | `建不了安装目录` / `复制失败` | 这个位置系统不让写。我换一个能写的位置，装出来是一模一样的。 | 「mkdir 失败」 |
| 19 | `校验和不符`。**自动重试一次，不打扰用户** | 下载到的文件不完整，我就没有安装。这通常是网络中途断了，我再下一次。 | — |
| 20 | 彻底停住（试过安装也不成）。**用选项卡** | 我先停在这里：这次什么都没装成，你电脑上的其它东西都没动。<br>选项卡：`再试一次` / `先到这里` | 「安装失败」 |
| 21 | 这个 Agent 只能通过界面导入技能 | 技能文件我已经放在 `<位置>` 了。请你在这个 Agent 的技能设置里导入一下，导入好了跟我说一声，我接着往下走。 | 「请手动导入」 |
| 22 | 这个 Agent 没有技能机制。说完**停**。**用选项卡** | 你现在用的这个 Agent 还不支持装技能，所以这次装不了。换一个支持技能机制的 Agent 可以再来，比如 Claude Code。<br>选项卡：`另想办法` / `先到这里` | 「你的 Agent 不支持 Skill」 |
| 23 | 判断不出自己的技能目录在哪。说完**停**。**用选项卡** | 我暂时判断不出自己该把技能放在哪里，这次先不装，你的电脑上什么都没动。接下来我自己查一下这个 Agent 怎么装技能，查到了再接着装。<br>选项卡：`继续` / `先到这里` | 「请告诉我技能路径」 |
| 24 | 技能装到了**项目级**目录。接在第 7 行之后说 | 这个 Agent 的技能是按项目分的，所以它只在这个项目里生效；换一个项目要再装一次。 | — |
| 25 | 第 16 / 22 / 23 行走完仍然装不了。说完**停**，**不给选项卡** | 这次没装成，你的电脑上什么都没动。先到这里；想再试的时候，把安装说明再发给我。 | 「安装失败」 |
| 26 | 装好了但跑不起来（`装好了但跑不起来`）。说完**停**，**不给选项卡** | 客户端装上了，但跑不起来。除了它，你的电脑上没有留下别的东西。先到这里；想再试的时候，把安装说明再发给我。 | 「安装失败」 |
| 27 | 登录确认后，问要不要做新人引导。**用选项卡** | 客户端装好了，也登录好了。接下来要不要花 10 分钟，我带你走一遍 SkillMaster 怎么用？<br>选项卡：`现在开始（推荐）` / `以后再说` | 直接开始引导、不确认 |
| 28 | 用户选「现在开始」 | 好，我们开始。 | — |
| 29 | 用户选「以后再说」。说完结束安装 | 好，这次就先不走一遍了。你已经能用了；以后想走一遍，跟我说「带我走一遍 SkillMaster 的新人引导」，或运行 `skmr startup`。 | 「安装失败」 |

**本页的三条要求**：① 表里的话照说，不改写；② 同一件事只说一遍；③ 要用户表态的地方，先用选项卡。

### 两个名字，不要混

- **这个 Agent**：你现在对话的这个程序（把它叫「客户端」会让用户以为要另装一个）。
- **客户端 / `skmr`**：第 8 行起要装的那个命令行程序。

### 确认点：先用选项卡

**能用这个 Agent 自己的选项卡组件（比如 Claude Code 的选项列表），就用它。** 没有这种组件，才把两个选项写进一句话：「再试一次，还是先到这里？」

- 最多两个选项，**第一个是推荐**，选项名短到一眼看完。固定标签就是下面这几组，不受 §四「按钮」的「动词开头、2–6 字」约束；「（推荐）」不计入字数。
- 句子说「为什么」，选项说「选哪个」——同一件事不在两处各说一遍。
- 用户选完直接往下走，不复述选项。
- 五组固定搭配，全流程复用：

| 什么时候 | 选项卡 |
|---|---|
| 装不装（第 2、3 行） | `现在安装（推荐）` / `先不安装` |
| 失败后（第 9、20 行） | `再试一次` / `先到这里` |
| 装不了（第 16、22 行） | `另想办法` / `先到这里` |
| 判断不了（第 23 行） | `继续` / `先到这里` |
| 引导做不做（第 27 行） | `现在开始（推荐）` / `以后再说` |

## 步骤

### 0 · 先做两个检查

这一步只做判断，**检查过程中不说话、不输出任何东西**；不通过时，第 16 行或第 22 行就是整段对话的第一句。下面两个条件**必须同时满足**才能往下走。

**① 这个 Agent 能装技能吗？**

| 情况 | 接下来 |
|---|---|
| 有技能机制（用户级目录、项目级目录，或只能通过界面导入，都算） | 继续看 ② |
| 没有技能机制 | **只说第 22 行，然后停**——不欢迎、不确认、不装任何东西 |

**② 这台电脑能装客户端吗？** 需要 64 位；Windows 还要是 Windows 10 1803 及以上（自带 `curl` 与 `tar`）：

- macOS / Linux：`uname -sm`
- Windows：先看 `$env:PROCESSOR_ARCHITEW6432`，没有这个变量再看 `$env:PROCESSOR_ARCHITECTURE`；再确认 `curl.exe --version` 与 `tar --version` 都能跑

32 位、Windows 10 1803 以下，或 `curl.exe` / `tar` 跑不了的机器 → **只说第 16 行，然后停**。

**第 16 行和第 22 行说完都给选项卡**：`另想办法` / `先到这里`。选「先到这里」就结束；选「另想办法」，你把自己查到的结果和能想到的替代做法说出来——第 16 行这一支是「换一台 64 位、系统受支持的电脑」，第 22 行这一支是「换一个支持技能机制的 Agent」。给不出就走第 25 行、停住。**不要再问用户用的是什么系统——这一步已经查过。**

两个都满足，去「1 · 欢迎，并请求确认」。

**为什么先做**：这两条任何一个不满足，后面的步骤都做不完。先挡掉，用户就不会先被请去确认、再被告知白忙一场。

### 1 · 欢迎，并请求确认

第 0 步的两个检查已经做完，现在直接说第 1 行。

然后用**选项卡**问第 2 行（`现在安装（推荐）` / `先不安装`）；这个 Agent 没有选项卡组件时，用一句话问：「现在安装，还是先不安装？」按用户的选择走：

- 选「现在安装」→ 说第 6 行，进入「2 · 装技能」。
- 选「先不安装」→ 说第 3 行，再用同一个选项卡问一次。
  - 这次选「现在安装」→ 说第 6 行，进入「2 · 装技能」。
  - 仍然选「先不安装」→ 说第 4 行，结束整个流程，不做任何改动。
- 用户提问 → 先回答（第 5 行）；答完等他表态，他说不想继续就按第 4 行结束。

**整个流程有三处安装决策：要不要安装（这一步）、装完客户端后要不要现在配置沙箱（4.5，可以拒绝），以及登录后要不要现在做新人引导（第 5 步，可以拒绝）。** 另外三处也要用户表态：第 2 步 shell 被拒时请求「只这一次出沙箱」，4.5 同意后请求「单条命令出沙箱」，第 5 步发现没登录时选择登录方式——它们不是安装决策，但同样用选项卡。其余顺利时只告知，出问题时按上面那五组固定搭配给选项卡；走第 25 / 26 行时只有文字，不给选项卡。

### 2 · 装技能

**优先装到用户级技能目录**——整台电脑所有项目都能用的那一份。这个 Agent 没有用户级目录、只有项目级，就装到**当前项目**的技能目录里；这种情况下，第 7 行说完再补第 24 行。

**属于哪一种，第 0 步已经判过。这里按那一支做：**

| 情况 | 怎么做 | 说什么 |
|---|---|---|
| 你能直接写技能目录（用户级或项目级） | 用下面的命令装进去 | 装好说第 7 行（项目级再加第 24 行） |
| 技能只能通过这个 Agent 的界面或设置导入 | 先解到临时目录，再请用户导入（见下） | 第 21 行；导入好再回到第 7 行 |

**判断不出该装进哪个技能目录时，不要装**：不要猜，也不要问用户——他不会知道。直接说第 23 行，给 `继续` / `先到这里` 的选项卡：选「继续」就先查清自己从哪里读技能，查到再接着装；查不到说第 25 行、结束。

目录由你判断——你知道自己从哪里读技能。这份文档不给路径表。

**能直接写的情况。** 先下到临时目录、解开、数够 1 个文件，**确认无误之后才动旧的那一份**。

**先试你自己的文件写入工具**（例如 Claude Code 的 Write）：下载并解到临时目录后，用 `cat` 读出 `$tmp/skillmaster/SKILL.md`，再用写入工具写到 `<技能目录>/skillmaster/SKILL.md`。读临时目录是允许的；写技能目录通常也不经过 shell 的沙箱（实测 Claude Code 的 Write 能写 `~/.claude/skills`，Bash 不能）。

**写文件工具失败，或这个 agent 只能用 shell**，再用下面这条。技能内容只有一个文件；命令会建目录、可能临时备份旧文件，但**不 rename、不删目录**：WorkBuddy 的沙箱允许写文件内容、拒绝 rename 覆盖已有文件。

macOS / Linux：

```bash
set -e
dir="<技能目录>"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/skillmaster.XXXXXX")"
curl -fsSL https://dl.skillmasterai.com/beta/skillmaster.tgz -o "$tmp/skillmaster.tgz" ||
  { echo "技能包获取失败" >&2; exit 1; }
tar -xzf "$tmp/skillmaster.tgz" -C "$tmp" ||
  { echo "技能包展开失败" >&2; exit 1; }
if ! test "$(find "$tmp/skillmaster" -type f | wc -l)" -eq 1; then
  echo "技能文件数不对" >&2
  exit 1
fi
mkdir -p "$dir/skillmaster"
target="$dir/skillmaster/SKILL.md"
# 备份用每次调用独有的名字；只有本次真的建成了，才把它当作回滚源。
backup=""
if [ -f "$target" ]; then
  backup="$(mktemp "$target.skillmaster-old.XXXXXX")" || { echo "技能备份失败" >&2; exit 1; }
  cp "$target" "$backup" || { rm -f "$backup" 2>/dev/null || true; echo "技能备份失败" >&2; exit 1; }
fi
if ! cp "$tmp/skillmaster/SKILL.md" "$target"; then
  if [ -n "$backup" ] && [ -f "$backup" ]; then
    if cp "$backup" "$target"; then
      rm -f "$backup" 2>/dev/null || true
    else
      echo "技能恢复失败：旧文件备份在 $backup" >&2
      exit 1
    fi
  else
    rm -f "$target" 2>/dev/null || true
    [ ! -f "$target" ] || echo "技能写入失败：$target 可能不完整，请删掉它或改用导入" >&2
  fi
  echo "技能写入失败" >&2
  exit 1
fi
rm -f "$backup" 2>/dev/null || true
rm -f "$tmp/skillmaster.tgz" 2>/dev/null || true
```

**如果这条 shell 也被沙箱拒绝**（`operation not permitted` / `Permission denied`，或脚本说 `技能备份失败`）：不要直接放弃。先请用户同意「只这一次把这条安装命令放到沙箱外跑」，重跑一次；用户不同意，或客户端没有这个机制时：这个客户端有导入界面，就走下面的界面导入；没有导入界面，就说第 9 行、停下，不要接着装客户端。**不要用 `rm -rf` 清技能目录，也不要用 `mv` 覆盖已有技能。**（这一段的任何失败——包括 `技能备份失败` / `技能写入失败` / `技能恢复失败` / `技能包获取失败` / `技能包展开失败` / `技能文件数不对`，以及 `mktemp` / `mkdir` 的原始报错——都不走附录 A；附录 A 是客户端安装器的报错。技能没装成按第 9 行处理。**例外**：看到 `技能恢复失败：旧文件备份在 <路径>` 或 `技能写入失败：<路径> 可能不完整` 时，把路径原文告诉用户——旧文件备份在哪、哪个技能文件可能不完整、请删掉它或改用导入；不要套第 9 行的「其它东西都没动」，也不要省掉这个路径。）

Windows（PowerShell）：**先下载成文件再解包**。PowerShell 5.1 里 `curl` 是 `Invoke-WebRequest` 的别名，而且两个原生程序之间直接接管道会损坏文件：

```powershell
$ErrorActionPreference = "Stop"
$dir = "<技能目录>"
$tmp = Join-Path $env:TEMP ("skillmaster-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force $tmp | Out-Null
curl.exe -fsSL https://dl.skillmasterai.com/beta/skillmaster.tgz -o "$tmp\skillmaster.tgz"
if ($LASTEXITCODE -ne 0) { throw "技能包获取失败" }
tar -xzf "$tmp\skillmaster.tgz" -C $tmp
if ($LASTEXITCODE -ne 0) { throw "技能包展开失败" }
if ((Get-ChildItem -Recurse -File "$tmp\skillmaster").Count -ne 1) { throw "技能文件数不对" }
$target = Join-Path $dir "skillmaster\SKILL.md"
New-Item -ItemType Directory -Force (Split-Path $target) | Out-Null
$backup = $null
if (Test-Path $target) {
  $backup = "$target.skillmaster-old." + [guid]::NewGuid().ToString("N")
  try { Copy-Item $target $backup -Force -ErrorAction Stop }
  catch { throw "技能备份失败" }
}
try { Copy-Item "$tmp\skillmaster\SKILL.md" $target -Force -ErrorAction Stop }
catch {
  if ($backup -and (Test-Path $backup)) {
    try { Copy-Item $backup $target -Force -ErrorAction Stop }
    catch { throw "技能恢复失败：旧文件备份在 $backup" }
    Remove-Item $backup -Force -ErrorAction SilentlyContinue
  } else {
    Remove-Item $target -Force -ErrorAction SilentlyContinue
    if (Test-Path $target) { Write-Error "技能写入失败：$target 可能不完整，请删掉它或改用导入" }
  }
  throw "技能写入失败：$($_.Exception.Message)"
}
Remove-Item $backup -Force -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
```

**这个地址为什么不是 `www`**：`www` 是营销页；下载走 `dl.skillmasterai.com`，它是 `skillmaster-dl` 桶的前门，安装脚本与自更新都指向它。见附录 C。

**只能通过界面导入的情况：** 先解到一个临时目录，把里面的 `skillmaster/` 路径告诉用户，由他在这个 Agent 的技能设置里导入。

macOS / Linux：

```bash
set -e
out="$(mktemp -d "${TMPDIR:-/tmp}/skillmaster-skill.XXXXXX")"
curl -fsSL https://dl.skillmasterai.com/beta/skillmaster.tgz -o "$out/skillmaster.tgz" ||
  { echo "技能包获取失败" >&2; exit 1; }
tar -xzf "$out/skillmaster.tgz" -C "$out" ||
  { echo "技能包展开失败" >&2; exit 1; }
rm -f "$out/skillmaster.tgz" 2>/dev/null || true
echo "$out/skillmaster"
```

Windows（PowerShell）：

```powershell
$out = Join-Path $env:TEMP ("skillmaster-skill-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force $out | Out-Null
curl.exe -fsSL https://dl.skillmasterai.com/beta/skillmaster.tgz -o "$out\skillmaster.tgz"
if ($LASTEXITCODE -ne 0) { throw "技能包获取失败" }
tar -xzf "$out\skillmaster.tgz" -C $out
if ($LASTEXITCODE -ne 0) { throw "技能包展开失败" }
Remove-Item "$out\skillmaster.tgz"
Write-Output (Join-Path $out "skillmaster")
```

**直接写的那一支，命令里已经数过文件；界面导入这一支，导入前再数一次**（包里只有一个 `skillmaster/SKILL.md`，一共 1 个）：

macOS / Linux：

```bash
find "<临时目录>/skillmaster" -type f | wc -l
```

Windows（PowerShell）：

```powershell
(Get-ChildItem -Recurse -File "<临时目录>\skillmaster").Count
```

两个都应当输出 `1`。

装好说第 7 行。按上面的顺序都试过（写文件工具、shell、出沙箱重跑、界面导入）仍没装成，说第 9 行，给 `再试一次` / `先到这里` 的选项卡；选「先到这里」就停下——**不要接着去装客户端**。

### 3 · 确认客户端的安装状态

系统和架构在第 0 步已经查过。这一步只查状态，不改安装状态与凭据；查完直接说结果，不用播报过程。

客户端装过没有、是不是最新，有四种结果，说法各不相同：

| 看到什么 | 结论 | 说哪行 | 下一步 |
|---|---|---|---|
| `skmr --version` 找不到，默认目录里也没有 `skmr` | 没装过 | 10 | 进入「4 · 装客户端」 |
| 命令找不到，但默认目录里**有** `skmr` | 装过，只是当前终端找不到 | 11 | 把默认目录加进 PATH（给用户以后用），说第 15 行；然后**用绝对路径重新跑一次下面的「是不是最新」判断**，按结果说第 12 或 13 行，进入「5 · 交棒」。不必等用户真的新开一个窗口才能继续 |
| 命令能跑，但不是最新的构建 | 旧了 | 12 | 跑 `skmr update`；如果它说沙箱里改不了名，请用户在自己的终端跑一次，或重跑安装命令；**不要跳过更新直接交棒**（旧版 `startup` 会写全局记忆）。再说第 14 行，进入「5 · 交棒」 |
| 命令能跑，且是最新的 | 不用装 | 13 | 说第 13 行，直接进入「5 · 交棒」 |

**为什么修好 PATH 之后还要再查一次**：默认目录里那个 `skmr` 可能是很久以前装的。旧版的 `startup` 还会往全局记忆里写说明，交棒给它就等于把这一版要拆的东西又装回去。

默认目录：macOS / Linux 是 `~/.local/bin`，Windows 是 `%LOCALAPPDATA%\SkillMaster\bin`（Windows 加完 PATH 要新开终端）。

「是不是最新」跑 `skmr update --check`：它只查不装，判据是版本串加提交号——内测期同一个版本串会有很多次构建。它说「已是最新」就是第 13 行；说「有新版本」就是第 12 行。

### 4 · 装客户端

先说第 8 行（只告知），然后直接装，不再问。

**先下载、再执行**，不要用管道——`curl … | sh` 在下载中途失败时，`sh` 读到空输入会退出 0，安装看起来成功了，直到第 5 步才发现命令不存在。

**macOS / Linux**：

```bash
script="$(mktemp "${TMPDIR:-/tmp}/skillmaster-install.XXXXXX")" \
  && curl -fsSL https://dl.skillmasterai.com/beta/install.sh -o "$script" \
  && SKILLMASTER_DL_URL=https://dl.skillmasterai.com/beta sh "$script" \
  && { rm -f "$script" 2>/dev/null || true; }
```

**Windows（PowerShell）**：

```powershell
$s = Join-Path $env:TEMP ("skillmaster-install-" + [guid]::NewGuid().ToString("N") + ".ps1")
curl.exe -fsSL https://dl.skillmasterai.com/beta/install.ps1 -o $s
if ($LASTEXITCODE -ne 0) { throw "下载失败" }
$env:SKILLMASTER_DL_URL='https://dl.skillmasterai.com/beta'
# **A child process, not `& $s` or `| Invoke-Expression`.** The execution policy would refuse a
# downloaded .ps1; and the script's `exit 1` would end the session it ran in — which here is the
# agent's own shell. `-File` gives it a process of its own and a plain exit code back.
& powershell -NoProfile -ExecutionPolicy Bypass -File $s
$code = $LASTEXITCODE
Remove-Item $s -ErrorAction SilentlyContinue
if ($code -ne 0) { throw "安装脚本失败（退出 $code）" }
```

（`SKILLMASTER_DL_URL` 是脚本本来就认的地址覆盖，内测版只是换一个前缀。为什么用这个地址，见附录 C。）

**装完先确认它真的能跑**——用**你实际装到的目录**（默认是 `~/.local/bin`；附录 A 第 18 行让你改过 `SKILLMASTER_INSTALL_DIR` 的话，就是那个目录）：macOS / Linux 跑 `"${SKILLMASTER_INSTALL_DIR:-$HOME/.local/bin}/skmr" --version`，Windows 跑 `& "$(if ($env:SKILLMASTER_INSTALL_DIR) { $env:SKILLMASTER_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'SkillMaster\bin' })\skmr.exe" --version`。看到版本串才说第 14 行。

**短名字 `skmr` 找不到不算装失败**——`install.sh` 只打印那个目录的 PATH 指引，不改当前窗口。这种情况按第 11 行处理：把**那个目录**加进 PATH、说第 15 行；**后续命令用绝对路径继续，不需要等用户新开窗口**（PATH 只是给用户以后用）。

下载安装脚本失败、或脚本报错时，按附录 A 说**对应那一行**，并按那一行说的重试或停下；附录 A 没列到的按第 20 行。

每次停下都要说清三件事：停在哪一步、这次装成了什么（没装成，或者装上了但没跑起来）、以后怎么继续（把安装说明再发给我）。

### 4.5 · 沙箱配置（问一次）

macOS / Linux 的 `install.sh` 检测到客户端时，最后会打印「还有一步：…」，以及 `--dry-run` / `--fix`，还有 `--undo` 撤回。Windows 的 `install.ps1` 只打印第 4 步那个目录里的绝对路径 `doctor --fix`（以及先 `auth status` 再 `startup`），没有 `--dry-run`，也不会说「没有检测到」——需要先看改动就自己加 `--dry-run`。**不要替用户决定，也不要跳过提问。**

如果 `install.sh` 说「没有检测到带沙箱配置的 AI 工具」，跳过这一步（也可以运行 `"<实际安装目录>/skmr" doctor --fix --dry-run` 看替代指引）。Windows 上先跑一次 `& "<实际安装目录>\skmr.exe" doctor --fix --dry-run`，看有没有可写的客户端；没有就跳过提问。

用这个 Agent 的选项卡/提问组件（Claude Code 是 `AskUserQuestion`）问一次：现在配置吗？

- **同意**：用第 4 步那个目录的绝对路径运行 `doctor --fix`：macOS / Linux 是 `"<实际安装目录>/skmr" doctor --fix`，Windows 是 `& "<实际安装目录>\skmr.exe" doctor --fix`；第 4 步改过 `SKILLMASTER_INSTALL_DIR` 就以那个目录为准（安装器打印的也是绝对路径）。多数客户端要在沙箱外；如果当前命令被沙箱拦，按这个客户端的「单条命令出沙箱」请求一次。WorkBuddy 可以在沙箱内写。请求被拒、客户端没有这个机制，或被企业策略禁用时，把 `doctor --fix` 的可粘贴命令给用户，让他在自己的终端跑一次；说明这次没有写入，然后继续第 5 步（PRD #9 的兜底）。
- **不同意**：跳过，并告诉用户以后可以自己运行 `doctor --fix`；不要因此停下安装。之后运行 `startup` 时，如果它要求跑 `doctor --fix`，先问用户——这一步的拒绝不能被绕过。

这一步写的是状态目录与 SkillMaster 的域名；它**不包括**技能目录与缓存目录。两个例外：Codex 旧式会另把工作区设为可写；Codex 旧式与 Gemini 的网络侧只有总开关——安装脚本的输出会说明。

### 5 · 交棒

先确认登录状态（不发起登录）。把 `<实际安装目录>` 换成第 4 步实际装到的目录（默认 `$HOME/.local/bin`；附录 A 第 18 行改过 `SKILLMASTER_INSTALL_DIR` 的话，就是那个目录）。

macOS / Linux：

```bash
dir="<实际安装目录>"
"$dir/skmr" auth status
```

Windows（PowerShell）：

```powershell
$dir = "<实际安装目录>"
& (Join-Path $dir 'skmr.exe') auth status
```

- **退出码 0（已登录）**：先说第 27 行，用选项卡问要不要现在做新人引导。选「现在开始」→ 说第 28 行，再运行 `"$dir/skmr" startup`（Windows：`& (Join-Path $dir 'skmr.exe') startup`）；选「以后再说」→ 说第 29 行，安装到此结束，不运行 `startup`。
- **退出码 1，且 stderr 里有「本机没有凭据」**：这就是没登录。**在沙箱里也能登录**：把 `skmr login` **放到后台**跑（输出写到文件），读它打印的那条 `https://…/cli-sign-in?ticket=…` 链接，**把链接原文交给用户**，请他在任意浏览器里打开并批准（手机、另一台电脑都行）。**不要用超时杀掉这条命令**——杀掉会取消这次登录，票根立即失效；也不要让它在后台无人看管地跑到超时。批准后它会自己结束。网络被沙箱挡住时，按提示放行服务端域名（`skmr doctor --fix`）。如果用户拿不到链接、或这台机器根本没人能批准，就给他一把 `SKILLMASTER_API_KEY`（控制台 `/account/keys`）。拿到凭据后重跑 `auth status`；退出码 0 时按下面的「退出码 0」分支（第 27 → 28 → 运行 `startup`）。
- **退出码 4**：凭据本身失效（`unauthenticated` / `token_idle` / `token_expired` / `token_revoked`），或者 `SKILLMASTER_API_KEY` 不是一把有效的 key。按输出里的句子处理：重新登录、续期或新签；环境变量不对就改对或去掉它。不要把旧凭据或坏变量交给 `startup`。重跑 `auth status`；退出码 0 时按「退出码 0」分支。账号被停用等其他 401 会落进「其他」，按输出里的句子说。
- **其他退出码，或 stderr 里没有「本机没有凭据」**：可能是凭据读不出来，也可能是服务器或网络的问题；按输出里的句子说，不要当「已登录」。**客户端已经装好了，不要说「什么都没装成」**；停在这里，把报错原文告诉用户。

（短名字 `skmr` 已经能用的，用短名字也行；第 4 步处理过 PATH 的情况，就用上面这条绝对路径。）

到这一步安装就结束了。**只有退出码 0（含登录后重跑得到 0）且用户选了「现在开始」才运行 `startup`**；退出码 1（且 stderr 有「本机没有凭据」）/ 4 按上面的分支拿到可用凭据、重跑得到 0 后，按「退出码 0」分支问第 27 行；其他退出码（包括网络或读取失败的 1）按上面的分支停下，不运行 `startup`。你不要在这里解释后面的步骤，也不要替它说。

## 附录 A · 安装脚本报错时怎么说

| 脚本报什么 | 说哪行 | 接下来怎么做 |
|---|---|---|
| 下载失败、`Connection reset by peer`、连不上 | 17 | 重试一次；再失败说第 20 行，停下 |
| `建不了临时目录` | 20 | 先请用户同意「只这一次把安装命令放到沙箱外跑」，重跑一次；仍失败说第 20 行，停下 |
| `建不了安装目录` / `解析不了安装目录` / `复制失败` | 18 | 先 `SKILLMASTER_INSTALL_DIR=<目录>` 放在**同一条命令**里（Windows：`$env:SKILLMASTER_INSTALL_DIR='<目录>'`），再跑一遍安装；如果提示沙箱，先按脚本提示放到沙箱外跑一次；仍失败说第 20 行，停下 |
| `改不了 … 的权限` / `写不进 … 也改不了名` | 18 | 同上：换 `SKILLMASTER_INSTALL_DIR` 重跑；如果提示沙箱，先放到沙箱外跑一次；仍失败说第 20 行，停下 |
| `校验和不符` | 19 | 重试一次；再失败说第 20 行，停下 |
| `解包失败` | 20 | 包坏了或下载不完整，重试一次；再失败停下 |
| `checksums.txt 里没有 …` | 16 | 清单里没有这台机器的包，按「这台电脑装不了」处理，给选项卡 |
| `需要 curl` / `需要 sha256sum` | 16 | 这台机器缺少安装工具，按「装不了」处理，给选项卡 |
| `安装被中断` | —（单独说，不要套第 20 行） | 客户端可能只写了一半，请重跑安装；重跑后 `--version` 仍失败按第 26 行。Windows 安装器没有这条提示，被杀后同样处理 |
| `装好了但跑不起来` | 26 | 客户端装上了、执行失败；不要说「什么都没装成」 |
| `不支持的系统` / `认不出架构` | 16 | 这台机器装不了，给选项卡，停下 |

**附录 A 没列到的报错，一律按第 20 行停住**，不要自己编一句。

**不要为了让安装看起来成功，而跳过校验、换一个来源，或者把失败说成成功。**

停下时要说清：停在哪一步、这次装成了什么（没装成，或者装上了但没跑起来）、以后怎么继续（把安装说明再发给我）。

## 附录 B · 手工安装（安装脚本跑不了时）

下面这些文件放在 `https://dl.skillmasterai.com/beta/`，文件名里不带版本号：

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
mkdir -p ~/.local/bin
cp skmr ~/.local/bin/skmr      # 不用 mv：沙箱里覆盖已有文件要 rename，会被拒
chmod 755 ~/.local/bin/skmr
# 覆盖正在运行的 skmr 会失败（Linux ETXTBSY）：先退出它，或换个目录再继续。
```

Windows 用 `Expand-Archive` 解开 zip，把 `skmr.exe` 放进 `%LOCALAPPDATA%\SkillMaster\bin`，并把这个目录加进用户 PATH。

**两个平台都要确认目录在 PATH 里**（安装脚本会替你检查；手工装没有这一步）：macOS / Linux 在 `~/.zshrc` 或 `~/.bashrc` 里加 `export PATH="$HOME/.local/bin:$PATH"`，然后重开终端——不加的话 `skmr` 会「找不到命令」。

## 附录 C · 下载地址

下载走 `https://dl.skillmasterai.com`——它是 `skillmaster-dl` 桶的前门（ECS 上的 nginx 反代到桶），安装脚本、技能包与自更新用的是同一批文件。

技能包（`beta/skillmaster.tgz`）也在同一个地址下。

客户端装好之后的自更新也指向 `dl.skillmasterai.com` 的 `beta/`——它是编译进去的地址。

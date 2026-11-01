# SOP · 装 CLI / 排障

技能在、`skmr` 命令不在时读这一份。**装完的标志是 `skmr --version` 打出版本串。**

## 只有一份权威：官方那份

https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/skillmaster.md

装客户端要做什么、对用户说的每一句话，都在它的「3 · 确认客户端的安装状态」「4 · 装客户端」「5 · 交棒」和「话术定稿」里。**照它做、照它说，不要改写。**

这份 SOP 不再复述那些步骤——两边各写一遍，改了一边另一边就旧。2026-11-02 已经因此打了两架：这里曾留着一个文档明说不要用的地址，引用的行号也停在文档的一半。

## 这一份只补文档里没有的（或文档读不到时补上的）

**一、文档读不到时**（网页打不开）。用安装包所在的桶直连地址，**先下载再执行**——不要用管道，`curl … | sh` 在下载失败时会读到空输入并退出 0；**也不要换成 `dl.`**，那个名字会重置只用 TLS 1.2 的客户端（macOS 自带 curl、部分 Windows PowerShell）：

```bash
script="$(mktemp)" \
  && curl -fsSL https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/install.sh -o "$script" \
  && SKILLMASTER_DL_URL=https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta sh "$script" \
  && rm -f "$script"
```

```powershell
$s = Join-Path $env:TEMP ("skillmaster-install-" + [guid]::NewGuid().ToString("N") + ".ps1")
curl.exe -fsSL https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta/install.ps1 -o $s
if ($LASTEXITCODE -ne 0) { throw "下载失败" }
$env:SKILLMASTER_DL_URL='https://skillmaster-dl.oss-cn-hangzhou.aliyuncs.com/beta'
& powershell -NoProfile -ExecutionPolicy Bypass -File $s
$code = $LASTEXITCODE
Remove-Item $s -ErrorAction SilentlyContinue
if ($code -ne 0) { throw "安装脚本失败（退出 $code）" }
```

装完先跑一次 `skmr --version` 再说话；命令找不到就用绝对路径验证：macOS / Linux `"${SKILLMASTER_INSTALL_DIR:-$HOME/.local/bin}/skmr" --version`，Windows `& (Join-Path $(if ($env:SKILLMASTER_INSTALL_DIR) { $env:SKILLMASTER_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'SkillMaster\bin' }) 'skmr.exe') --version`。

两条都不成，停下告诉用户「安装说明暂时读不到，请稍后再试。」——**不要到别处找安装包，也不要换架构硬装。**

**二、不要替 `startup` 解释后面的步骤。** 登录和迁移本机技能是它的活；只有它自己停下来、明确要你登录时，才用 `skmr login`，或让用户到控制台签一把 API 密钥放进 `SKILLMASTER_API_KEY`。

**三、装不了、也没别的办法时**，用安装文档第 25 行的原话收尾（这次装不了、电脑上什么都没动、先到这里），不要自己编。

## 其余一律以文档为准

每种失败说什么、什么时候停、停下要不要给选项卡——文档的话术定稿和附录 A 都写了，这里不抄。

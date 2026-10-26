# Installs the SkillMaster CLI on Windows.
#
# The artifact names, the checksum file and this script's behaviour are specified in
# skillmaster-cli/RELEASE.md §7; that document is the authority, this file is the implementation.
#
# **Picking the package is deterministic work, so it is done here rather than by a person or a
# model.** An architecture this script does not recognise is a refusal, not a guess: 32-bit Windows
# (`x86`) cannot run an amd64 binary, and Windows on ARM gets a real arm64 package, so there is
# nothing left for a fallback to rescue.
#
# Usage:  irm https://dl.skillmasterai.com/install.ps1 | iex
$ErrorActionPreference = 'Stop'

# Windows PowerShell 5.1 on .NET Framework 4.5/4.6 negotiates SSL3|TLS1.0 by default, and the
# download host speaks TLS 1.2 and 1.3 only — the first request would die as a generic
# "underlying connection was closed". Harmless on PowerShell 7, where this is already on.
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch { }

$BaseUrl = if ($env:SKILLMASTER_DL_URL) { $env:SKILLMASTER_DL_URL } else { 'https://dl.skillmasterai.com' }
$InstallDir = if ($env:SKILLMASTER_INSTALL_DIR) { $env:SKILLMASTER_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'SkillMaster\bin' }

function Say([string]$Message) { Write-Host $Message }
function Die([string]$Message) { Write-Error "install: $Message"; exit 1 }

# --- 1. Pick the package -----------------------------------------------------

$arch = switch ($env:PROCESSOR_ARCHITECTURE) {
    'ARM64' { 'arm64' }
    'AMD64' { 'amd64' }
    default {
        Die "认不出架构「$env:PROCESSOR_ARCHITECTURE」，没有为它构建的包（32 位 Windows 跑不了 64 位程序）。产物清单与手工安装步骤见 https://dl.skillmasterai.com/"
    }
}

$artifact = "skillmaster_windows_$arch.zip"

# --- 2. Fetch the package and the checksums ---------------------------------

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("skillmaster-install-" + [System.Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null

try {
    Say "下载 $BaseUrl/$artifact"
    try {
        Invoke-WebRequest -Uri "$BaseUrl/$artifact" -OutFile (Join-Path $tmp $artifact) -UseBasicParsing
    } catch {
        Die "下载失败：$BaseUrl/$artifact（网络，或这个平台暂时没有产物）"
    }
    # Its own message: a missing checksums.txt says nothing about whether this platform has a
    # package, and naming the package sends the reader after the wrong thing.
    try {
        Invoke-WebRequest -Uri "$BaseUrl/checksums.txt" -OutFile (Join-Path $tmp 'checksums.txt') -UseBasicParsing
    } catch {
        Die "下载失败：$BaseUrl/checksums.txt（包下载成功了，缺的是校验和文件）"
    }

    # --- 3. Check the checksum -----------------------------------------------

    $line = Select-String -Path (Join-Path $tmp 'checksums.txt') -Pattern "([0-9a-f]{64})\s+$([regex]::Escape($artifact))$" |
        Select-Object -First 1
    if (-not $line) { Die "checksums.txt 里没有 $artifact 这一行" }
    $want = $line.Matches[0].Groups[1].Value

    $got = (Get-FileHash -Algorithm SHA256 -Path (Join-Path $tmp $artifact)).Hash.ToLowerInvariant()
    if ($want -ne $got) { Die "校验和不符：$artifact（期望 $want，实际 $got）——已停止，没有安装" }
    Say "校验和正确"

    # --- 4. Unpack and install ------------------------------------------------

    Expand-Archive -Path (Join-Path $tmp $artifact) -DestinationPath $tmp -Force
    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null

    # Replace the executable in one rename, so a running copy is never half-written and a failed
    # update never leaves the machine without a binary: `Move-Item -Force` onto an existing file is
    # delete-then-move, which has exactly that window.
    $target = Join-Path $InstallDir 'skillmaster.exe'
    $staged = Join-Path $tmp 'skillmaster.exe'
    if (Test-Path $target) {
        [System.IO.File]::Replace($staged, $target, $null)
    } else {
        [System.IO.File]::Move($staged, $target)
    }

    # --- 5. Say what to do next -----------------------------------------------

    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if (($userPath -split ';') -notcontains $InstallDir) {
        $newPath = if ([string]::IsNullOrEmpty($userPath)) { $InstallDir } else { "$userPath;$InstallDir" }
        [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
        $env:Path = "$env:Path;$InstallDir"
        Say ""
        Say "已把 $InstallDir 加进用户 PATH——**新开**一个终端它才会生效。"
    }

    Say ""
    Say "已安装到 $target"
    Say ""

    & $target --version
    if ($LASTEXITCODE -ne 0) { Die "装好了但跑不起来：$target --version" }

    Say ""
    Say "下一步：skillmaster startup"
} finally {
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $tmp
}

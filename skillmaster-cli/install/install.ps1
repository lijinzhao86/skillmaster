# Installs the SkillMaster CLI on Windows.
#
# The artifact names, the checksum file and this script's behaviour are specified in
# skillmaster-cli/RELEASE.md §7; that document is the authority, this file is the implementation.
#
# **Picking the package is deterministic work, so it is done here rather than by a person or a
# model.** Windows ARM runs x64 under emulation, so an architecture this script does not recognise
# falls back to amd64 and says so, instead of stopping.
#
# Usage:  irm https://dl.skillmasterai.com/install.ps1 | iex
$ErrorActionPreference = 'Stop'

$BaseUrl = if ($env:SKILLMASTER_DL_URL) { $env:SKILLMASTER_DL_URL } else { 'https://dl.skillmasterai.com' }
$InstallDir = if ($env:SKILLMASTER_INSTALL_DIR) { $env:SKILLMASTER_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'SkillMaster\bin' }

function Say([string]$Message) { Write-Host $Message }
function Die([string]$Message) { Write-Error "install: $Message"; exit 1 }

# --- 1. Pick the package -----------------------------------------------------

$arch = switch ($env:PROCESSOR_ARCHITECTURE) {
    'ARM64' { 'arm64' }
    'AMD64' { 'amd64' }
    'x86'   { 'amd64' }
    default {
        Say "认不出架构「$env:PROCESSOR_ARCHITECTURE」，按 amd64 安装：Windows ARM 会用 x64 模拟跑它，功能一样。"
        'amd64'
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
        Invoke-WebRequest -Uri "$BaseUrl/checksums.txt" -OutFile (Join-Path $tmp 'checksums.txt') -UseBasicParsing
    } catch {
        Die "下载失败：$BaseUrl/$artifact（网络，或这个平台暂时没有产物）"
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

    # Replace the executable atomically, so a running copy is never half-written.
    $target = Join-Path $InstallDir 'skillmaster.exe'
    Copy-Item -Path (Join-Path $tmp 'skillmaster.exe') -Destination "$target.new" -Force
    Move-Item -Path "$target.new" -Destination $target -Force

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

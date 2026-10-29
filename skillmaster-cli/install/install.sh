#!/bin/sh
# Installs the SkillMaster CLI on macOS and Linux.
#
# The artifact names, the checksum file and this script's behaviour are specified in
# skillmaster-cli/RELEASE.md §7; that document is the authority, this file is the implementation.
#
# **Picking the package is deterministic work, so it is done here rather than by a person or a
# model.** An architecture this script does not recognise is a refusal, not a guess: it used to fall
# back to amd64 "because Apple Silicon runs x86_64 under Rosetta 2", but macOS only ever reports
# `arm64` or `x86_64`, so that branch was unreachable — and everything that does reach it (i686,
# armv7l, riscv64, s390x) cannot execute an amd64 binary at all. Installing one there replaced a
# working command with a file that cannot exec.
set -eu

BASE_URL="${SKILLMASTER_DL_URL:-https://dl.skillmasterai.com}"
# An override exists for testing and for a mirror; the default is the only address users see.
INSTALL_DIR="${SKILLMASTER_INSTALL_DIR:-$HOME/.local/bin}"

say() { printf '%s\n' "$*"; }
die() { printf 'install: %s\n' "$*" >&2; exit 1; }

# --- 1. Pick the package -----------------------------------------------------

system="$(uname -s)"
machine="$(uname -m)"

case "$system" in
  Darwin) os=darwin ;;
  Linux)  os=linux ;;
  *) die "不支持的系统：${system}（这个脚本只装 macOS 与 Linux；Windows 用 install.ps1）" ;;
esac

case "$machine" in
  arm64|aarch64) arch=arm64 ;;
  x86_64|amd64)  arch=amd64 ;;
  *)
    die "认不出架构「${machine}」，没有为它构建的包。产物清单与手工安装步骤见 https://dl.skillmasterai.com/ —— 如果那边也没有，这个平台暂时装不了。"
    ;;
esac

artifact="skillmaster_${os}_${arch}.tar.gz"

# --- 2. Fetch the package and the checksums ---------------------------------

command -v curl >/dev/null 2>&1 || die "需要 curl（没有它下不了包）"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT INT TERM

say "下载 $BASE_URL/$artifact"
# No `--retry-all-errors`: it needs curl 7.71, and the machines most likely to run this are the
# oldest ones. A 404 is not retried either way; `--retry` already covers the transient cases.
curl -fsSL --retry 3 --retry-delay 2 -o "$tmp/$artifact" "$BASE_URL/$artifact" ||
  die "下载失败：$BASE_URL/${artifact}（网络，或这个平台暂时没有产物）"
curl -fsSL --retry 3 --retry-delay 2 -o "$tmp/checksums.txt" "$BASE_URL/checksums.txt" ||
  die "下载失败：$BASE_URL/checksums.txt"

# --- 3. Check the checksum ---------------------------------------------------

want="$(awk -v name="$artifact" '$2 == name { print $1 }' "$tmp/checksums.txt")"
[ -n "$want" ] || die "checksums.txt 里没有 $artifact 这一行"

if command -v sha256sum >/dev/null 2>&1; then
  got="$(sha256sum "$tmp/$artifact" | awk '{ print $1 }')"
elif command -v shasum >/dev/null 2>&1; then
  got="$(shasum -a 256 "$tmp/$artifact" | awk '{ print $1 }')"
else
  die "需要 sha256sum 或 shasum 来核对校验和"
fi

[ "$want" = "$got" ] || die "校验和不符：${artifact}（期望 ${want}，实际 ${got}）——已停止，没有安装"
say "校验和正确"

# --- 4. Unpack and install ---------------------------------------------------

tar -xzf "$tmp/$artifact" -C "$tmp" || die "解包失败：$artifact"

mkdir -p "$INSTALL_DIR" || die "建不了安装目录：$INSTALL_DIR"
# Replace the executable atomically, so a running copy is never half-written.
# The archive's entry is `skmr` (ADR 0113 决定 2), so this is a plain copy: nothing is renamed here.
cp "$tmp/skmr" "$INSTALL_DIR/skmr.new" || die "复制失败：$INSTALL_DIR"
chmod 755 "$INSTALL_DIR/skmr.new"
mv -f "$INSTALL_DIR/skmr.new" "$INSTALL_DIR/skmr"

# --- 5. Say what to do next ---------------------------------------------------

say ""
say "已安装到 $INSTALL_DIR/skmr"

case ":$PATH:" in
  *":$INSTALL_DIR:"*) ;;
  *)
    say ""
    say "$INSTALL_DIR 不在 PATH 里。把它加进去（选你用的 shell）："
    say "  echo 'export PATH=\"$INSTALL_DIR:\$PATH\"' >> ~/.zshrc    # 或 ~/.bashrc"
    say "  然后重开一个终端。"
    ;;
esac

say ""
"$INSTALL_DIR/skmr" --version || die "装好了但跑不起来：$INSTALL_DIR/skmr --version"

say ""
say "下一步：skmr startup"

#!/usr/bin/env bash
# Fixed cases for `changed-paths.sh`.
#
# **Why this exists.** That script decides whether a module's tests run. A regex that
# quietly stops matching is not a red build — the heavy job is skipped, and a skipped
# required check counts as passing. These cases make the mapping itself fail loudly.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/changed-paths.sh"

check() {
  local key="$1" expected="$2" files="$3" got
  got="$(printf '%s\n' "$files" | bash "$script" "$key")"
  if [ "$got" != "$key=$expected" ]; then
    echo "FAIL $key: expected $key=$expected, got $got" >&2
    echo "  files: $files" >&2
    exit 1
  fi
  echo "ok   $got  <- $files"
}

check server  true  "skillmaster-server/src/A.java"
check server  false "docs/current/TODO.md"
check server  false "skillmaster-server/reference-python/a.py"
check server  true  $'skillmaster-server/reference-python/a.py\nskillmaster-server/src/A.java'
check server  true  ".dockerignore"
# The whitelist moved to ops in v69 (ADR 0199); the server stopped reading this file.
check server  false "deploy/sync-sources.yml"
# The Gateway step deploys these, so they are the server's business now (ADR 0207).
check server  true  "deploy/nginx/skillmaster.conf"
check server  false "deploy/ops/compose.yaml"
check console true  "skillmaster-console/src/a.tsx"
check console false "skillmaster-cli/cmd/a.go"
check site    true  "skills/skillmaster/SKILL.md"
check site    true  "scripts/build-skill-tgz.py"
check shell   true  ".github/scripts/changed-paths.sh"
check docs    true  "docs/current/TODO.md"
check docs    true  ".claude/skills/x/SKILL.md"
check cli     true  "skillmaster-cli/cmd/a.go"
check cli     false "skillmaster-console/src/a.tsx"
check cli     true  "skills/skillmaster-guide/SKILL.md"
check cli     true  "skills/skillmaster/SKILL.md"
check cli     false "skills/lijinzhao/other/SKILL.md"

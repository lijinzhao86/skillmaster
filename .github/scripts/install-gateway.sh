#!/usr/bin/env bash
# Ships the gateway (nginx) configuration as part of the server deployment.
#
# **Why this exists.** Until 2026-11-14 the nginx files reached the host by hand, and the only
# thing that noticed a difference was `ops-check.yml` — once a day. v68 added
# `cli-device-sign-ins` to the proxy prefixes; the server shipped, the prefix did not, and the
# first CLI device login got nginx's `405` instead of the consent page. A wrong answer rather
# than an error, which is why only walking the flow found it.
#
# **Before the jar, never after.** The caller runs this before the release is switched: a
# gateway this script cannot prove must stop the deployment while the running release is still
# untouched.
#
# **Validation is not skipped for unchanged files.** A previous run can leave the files
# installed but not loaded (the reload failed, or the script died between the install and the
# check). Treating "content matches" as "already applied" would then report success while nginx
# still served the old configuration — the very failure this script exists to prevent.
#
# Usage (from the Deploy job):
#   DEPLOY_HOST=… DEPLOY_USER=… DEPLOY_KEY=… bash .github/scripts/install-gateway.sh
set -euo pipefail

: "${DEPLOY_HOST:?DEPLOY_HOST is not set}"
: "${DEPLOY_USER:?DEPLOY_USER is not set}"
: "${DEPLOY_KEY:?DEPLOY_KEY is not set}"

key="$(mktemp)"
upload="$(mktemp -d)"
target="$DEPLOY_USER@$DEPLOY_HOST"

# Repository path → absolute path on the host. Both files are configuration this deploy owns:
# the proxy blocks, and the drop-in that lets a stopped nginx try again.
gateway=(
  "deploy/nginx/skillmaster.conf:/etc/nginx/conf.d/skillmaster.conf"
  "deploy/nginx/nginx.service.d/restart.conf:/etc/systemd/system/nginx.service.d/restart.conf"
)

changed=0
installed=""   # newline-separated destinations this run replaced
restored=0
committed=0    # 1 once the new files are validated *and* loaded by nginx

# Put back every file this run replaced. Both are attempted even if one fails: half a restored
# gateway is worse than knowing which half did not come back.
restore() {
  [ -n "$installed" ] || return 0
  restored=1
  local script="set -e; failed=0" dest
  while IFS= read -r dest; do
    [ -n "$dest" ] || continue
    script="$script
if [ -f '$dest.previous' ]; then
  mv -f '$dest.previous' '$dest' || failed=1
else
  rm -f '$dest' || failed=1
fi"
  done <<< "$installed"
  script="$script
systemctl daemon-reload || failed=1
exit \$failed"
  ssh "${opts[@]}" "$target" "$script"
}

# **Every exit path is covered, not just the validation one.** A `scp` or `mv` that fails after
# the first file was replaced would otherwise leave the host holding a configuration nothing
# ever tested — the running nginx keeps its in-memory copy, but the next restart loads the
# untested file.
#
# **`committed` is what stops the trap from undoing a success.** Nothing after the reload may
# fail the run into a restore: at that point the new configuration is what nginx is serving, and
# putting the old one back (or, if `.previous` was already cleaned, *removing* the file) would
# leave disk and memory disagreeing — the failure this script exists to prevent.
on_exit() {
  status=$?
  if [ "$status" -ne 0 ] && [ "$committed" -eq 0 ] && [ "$restored" -eq 0 ] && [ -n "$installed" ]; then
    echo "::error::the gateway install did not finish; restoring the files this run replaced" >&2
    restore || echo "::warning::restoring the previous gateway failed" >&2
  fi
  rm -f "$key"
  rm -rf "$upload"
}
trap on_exit EXIT

printf '%s\n' "$DEPLOY_KEY" > "$key"
chmod 600 "$key"
opts=(-i "$key" -o StrictHostKeyChecking=accept-new
      -o ConnectTimeout=15 -o ServerAliveInterval=15 -o ServerAliveCountMax=4)

# Fail here, with ssh's own message, rather than after a partial upload.
ssh "${opts[@]}" "$target" true

for entry in "${gateway[@]}"; do
  src="${entry%%:*}"
  dest="${entry#*:}"
  base="$(basename "$src")"
  cp "$src" "$upload/$base"

  # The parent directory is created **before** the upload: a host rebuilt without
  # `/etc/systemd/system/nginx.service.d/` would otherwise fail the second `scp` after the first
  # file was already installed.
  ssh "${opts[@]}" "$target" "mkdir -p \"\$(dirname '$dest')\""
  scp -q "${opts[@]}" "$upload/$base" "$target:$dest.incoming"

  # An unchanged file is not rewritten; it is still validated below.
  if ssh "${opts[@]}" "$target" "test -f '$dest' && cmp -s '$dest' '$dest.incoming'"; then
    ssh "${opts[@]}" "$target" "rm -f '$dest.incoming'"
    echo "gateway unchanged: $dest"
    continue
  fi

  # `.previous` is what `restore` puts back. `set -e` inside the remote script is load-bearing:
  # a failed `cp` must not leave an older `.previous` behind for the restore to pick up.
  ssh "${opts[@]}" "$target" "set -e
    if [ -f '$dest' ]; then cp -p '$dest' '$dest.previous'; fi
    mv '$dest.incoming' '$dest'
    chown root:root '$dest'
    chmod 644 '$dest'"
  echo "gateway installed: $dest"
  changed=$((changed + 1))
  installed="$installed$dest
"
done

# `daemon-reload` first: the drop-in is systemd's file, and a changed `Restart=` would not take
# effect until the next boot without it. `nginx -t` then reads the files as they sit on disk.
if ! ssh "${opts[@]}" "$target" 'systemctl daemon-reload'; then
  echo "::error::systemd rejected the nginx drop-in; restoring the previous gateway" >&2
  restore || echo "::warning::restoring the previous gateway failed" >&2
  exit 1
fi
if ! ssh "${opts[@]}" "$target" 'nginx -t'; then
  echo "::error::nginx rejected the gateway configuration from this commit; restoring the previous files" >&2
  restore || echo "::warning::restoring the previous gateway failed" >&2
  exit 1
fi

# Reloaded even when nothing changed, and that is deliberate: the files on disk are proven, but
# a reload that failed in an earlier run was never proven to have taken effect. A host whose
# nginx is down fails here — which is the honest answer, not a gateway reported as applied.
if ! ssh "${opts[@]}" "$target" 'systemctl reload nginx'; then
  echo "::error::nginx did not reload; the configuration on disk was validated but is not what nginx serves" >&2
  restore || echo "::warning::restoring the previous gateway failed" >&2
  exit 1
fi
committed=1

# Only now is `.previous` disposable, and only now may this fail without consequence: the new
# configuration is already loaded, so a cleanup that cannot run is a warning about disk hygiene,
# not a reason to undo a deploy.
for entry in "${gateway[@]}"; do
  dest="${entry#*:}"
  ssh "${opts[@]}" "$target" "rm -f '$dest.previous'" \
    || echo "::warning::could not remove $dest.previous" >&2
done

echo "gateway ok; $changed file(s) replaced"

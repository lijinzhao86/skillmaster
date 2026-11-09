#!/usr/bin/env bash
# Answers "did this change touch module X?" so a pull request can start the workflow
# that guards X and skip the other modules' tests.
#
# Usage: changed-paths.sh <server|cli|console|site|shell|docs>
#        (changed file paths, one per line, on stdin)
# Output: "<key>=true" or "<key>=false" on stdout, ready for $GITHUB_OUTPUT.
#
# **This file is the one home for the mapping.** The workflows used to declare
# `paths:` filters instead, and that is exactly what made a required check impossible
# to add: a pull request that changes only documents never starts server.yml, so the
# required `Test` check never reports and the merge waits forever. Removing `paths:`
# and deciding inside the workflow is what lets a required check report "skipped" on
# the pull requests that do not need it.
set -euo pipefail

key="${1:?usage: changed-paths.sh <server|cli|console|site|shell|docs>}"
files="$(cat)"

# The compare API stops at 300 files, and a rename contributes two lines (old and new
# path). A truncated list must not hide a change, so a list that large answers every
# question with yes.
count="$(grep -c . <<< "$files" || true)"
if [ "$count" -ge 300 ]; then
  echo "$key=true"
  exit 0
fi

matches() {
  # A here-string, not a pipe: under `pipefail`, `grep -q` exits early and the writer
  # takes SIGPIPE, which would turn a match into a failure on a large list.
  grep -qE "$1" <<< "$files"
}

case "$key" in
  server)
    # `reference-python/` is an archived design reference, not built code — the same
    # exclusion the workflow's `paths:` filter used to carry.
    without_archive="$(grep -vE '^skillmaster-server/reference-python/' <<< "$files" || true)"
    grep -qE '^(skillmaster-server/|\.dockerignore$|\.github/workflows/server\.yml$|deploy/sync-sources\.yml$)' <<< "$without_archive"
    ;;
  cli)
    # The Guide and the entry skill are read by the CLI's help tests, so a change to either is a
    # CLI change for the purpose of running them (ADR 0173).
    matches '^(skillmaster-cli/|skills/skillmaster-guide/|skills/skillmaster/|\.github/workflows/cli\.yml$)'
    ;;
  console)
    matches '^(skillmaster-console/|\.github/workflows/console\.yml$)'
    ;;
  site)
    matches '^(skillmaster-site/|skills/|scripts/build-skill-tgz\.(sh|py)$|\.github/workflows/site\.yml$)'
    ;;
  shell)
    matches '^(\.github/workflows/|\.github/scripts/|skillmaster-cli/install/install\.(sh|ps1)$|deploy/acme/)'
    ;;
  docs)
    matches '^(docs/|\.claude/|scripts/docs/|\.github/workflows/docs\.yml$)'
    ;;
  *)
    echo "unknown key: $key" >&2
    exit 2
    ;;
esac && echo "$key=true" || echo "$key=false"

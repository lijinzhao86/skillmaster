#!/usr/bin/env bash
# Build the skill package the site serves: skillmaster-site/public/skill/skillmaster.tgz (v38).
#
# The packing itself is in build-skill-tgz.py beside this file — one implementation, because the
# tar flags that make an archive reproducible differ between GNU tar and macOS's bsdtar, and two
# branches would be two archives that drift.
#
# Runs from any working directory. Optional arguments: [<source dir>] [<output tgz>].
set -euo pipefail
exec python3 "$(dirname "$0")/build-skill-tgz.py" "$@"

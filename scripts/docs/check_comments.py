#!/usr/bin/env python3
"""Report comments that carry a fact which belongs somewhere else.

The rule is `code-writing`'s: a comment owns only what is true of the line it
sits next to. A ceiling that already has a constant, and a "used to be" history,
are facts with a home elsewhere — and a copy of them in a comment has no
checker, so it drifts.

Two things are flagged:

  * a value that already has a constant (`100 MiB`, `512 files`) — the comment
    is a second constant, and the constant changing does not change it;
  * history narration (`原先`, `以前`) — the record belongs in an ADR.

A version number on its own is **not** flagged. `(v33)` is a pointer to the
version that explains the shape, and pointers are what make an audit cheap;
`impact.py --check` is what keeps them resolving. What is banned is restating
the fact, not naming where it is recorded.

Usage:
    python3 scripts/docs/check_comments.py [PATH ...] [--all]

With paths, the exit code is 1 when any of them has findings, 0 otherwise —
that is the per-change gate. `--all` prints the same default scope's total and
always exits 0: it is a count, not a verdict (the TD calls this check a
warning, not a blocker).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# A comment line that carries one of these. `/api/v1` is a path, not a version.
HISTORY = re.compile(r"原先|以前|曾经")
VALUE = re.compile(r"\b(100|512|128|16) ?MiB\b|\b512 files\b")
COMMENT = re.compile(r"^\s*(//|/\*|\*|\{/\*)")


def offending_lines(path: Path) -> list[tuple[int, str, str]]:
    found: list[tuple[int, str, str]] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not COMMENT.match(line):
            continue
        if "/api/v1" in line:
            continue
        reasons = []
        if HISTORY.search(line):
            reasons.append("历史叙述")
        if VALUE.search(line):
            reasons.append("重复数值")
        if reasons:
            found.append((number, line.strip(), "、".join(reasons)))
    return found


def targets(args: list[str]) -> list[Path]:
    if args:
        paths = [Path(a).resolve() for a in args]
    else:
        paths = [
            ROOT / "skillmaster-server/src/main",
            ROOT / "skillmaster-cli",
            ROOT / "skillmaster-console/src",
            ROOT / "skillmaster-site/src",
        ]
    files: list[Path] = []
    for path in paths:
        if path.is_dir():
            for suffix in ("*.java", "*.go", "*.ts", "*.tsx", "*.css", "*.scss"):
                files.extend(sorted(path.rglob(suffix)))
        elif path.is_file():
            files.append(path)
    return [f for f in files if "_test." not in f.name]


def main() -> int:
    args = [a for a in sys.argv[1:] if a != "--all"]
    show_all = "--all" in sys.argv[1:]

    total = 0
    for path in targets(args):
        found = offending_lines(path)
        total += len(found)
        if found and not show_all:
            print(f"{path.relative_to(ROOT)}")
            for number, line, reasons in found:
                print(f"  {number}: [{reasons}] {line[:100]}")
    print(f"共 {total} 处")
    if show_all:
        return 0
    return 1 if total else 0


if __name__ == "__main__":
    raise SystemExit(main())

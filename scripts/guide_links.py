#!/usr/bin/env python3
"""Structural checks for skills/skillmaster-guide that do not need a model.

What it proves, one line per check:
  - every page is reachable from SKILL.md in one hop (B1);
  - every relative link resolves (E3's local half);
  - every page is at most 100 lines; the index is at most 150 (A5 / B4);
  - the index is at most 3000 characters, the budget TestHelpBudgets enforces.

Run from the repository root: python3 scripts/guide_links.py
Exit code 0 means all four hold. The model eval lives in
skillmaster-cli/cmd/skillmaster/guide_eval_test.go; scripts/guide-regression.sh
runs both.
"""

from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GUIDE = os.path.join(ROOT, "skills", "skillmaster-guide")
INDEX = os.path.join(GUIDE, "SKILL.md")
LINK = re.compile(r'\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)')


def local_path(target: str) -> str:
    """A link may carry an anchor or a query; the file is what exists."""
    return re.split(r"[#?]", target, maxsplit=1)[0]


def strip_fences(text: str) -> str:
    """A link inside a code fence is an example, not an address."""
    out = []
    in_fence = False
    for line in text.splitlines():
        if line.strip().startswith("```"):
            in_fence = not in_fence
            out.append("")
            continue
        out.append("" if in_fence else line)
    return "\n".join(out)


def fail(message: str) -> None:
    print(f"✗ {message}")
    failures.append(message)


failures: list[str] = []


def main() -> int:
    if not os.path.isfile(INDEX):
        print(f"✗ the Guide index is missing: {INDEX}")
        return 1

    pages = []
    for base, _, names in os.walk(GUIDE):
        for name in names:
            if name.endswith(".md"):
                pages.append(os.path.join(base, name))
    pages.sort()

    index_text = open(INDEX, encoding="utf-8").read()
    index_links = strip_fences(index_text)
    linked = {
        os.path.normpath(os.path.join(GUIDE, local_path(match.group(1))))
        for match in LINK.finditer(index_links)
        if not match.group(1).startswith(("http://", "https://", "mailto:", "/")) and local_path(match.group(1))
    }

    leaves = [page for page in pages if os.path.normpath(page) != os.path.normpath(INDEX)]
    unreachable = [page for page in leaves if os.path.normpath(page) not in linked]
    if unreachable:
        for page in unreachable:
            fail(f"not linked from SKILL.md: {os.path.relpath(page, ROOT)}")
    else:
        print(f"✓ every page is reachable from SKILL.md ({len(leaves)} pages)")

    broken = []
    for page in pages:
        for match in LINK.finditer(strip_fences(open(page, encoding="utf-8").read())):
            target = match.group(1)
            if target.startswith(("http://", "https://", "mailto:", "/")):
                continue
            resolved = os.path.normpath(os.path.join(os.path.dirname(page), local_path(target)))
            if not os.path.exists(resolved):
                broken.append(f"{os.path.relpath(page, ROOT)} -> {target}")
    if broken:
        for item in broken:
            fail(f"broken link: {item}")
    else:
        print("✓ every relative link resolves")

    over = []
    for page in pages:
        count = len(open(page, encoding="utf-8").read().splitlines())
        limit = 150 if os.path.normpath(page) == os.path.normpath(INDEX) else 100
        if count > limit:
            over.append(f"{os.path.relpath(page, ROOT)} has {count} lines (limit {limit})")
    if over:
        for item in over:
            fail(item)
    else:
        print("✓ every page is within its line limit")

    if len(index_text) > 3000:
        fail(f"SKILL.md has {len(index_text)} characters (limit 3000)")
    else:
        print(f"✓ index budget: {len(index_text)}/3000 characters")

    if failures:
        print(f"\n{len(failures)} structural check(s) failed")
        return 1
    print("\nall structural checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())

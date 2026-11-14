#!/usr/bin/env python3
"""Who already claims version vNN / ADR NNNN / Flyway VNN — across worktrees and branches.

These three numbers are handed out by hand, and two sessions in two worktrees
have collided on the same version number more than once. The cause was the
same each time: the check looked at `main` (or only at its own worktree), while
the other claim sat uncommitted in another worktree or on a branch nobody had
merged yet. A number that exists only as an uncommitted directory is still
taken — the other session is writing in it.

So a claim is read from everywhere at once:

  - every worktree `git worktree list` knows, **including uncommitted**
    `docs/versions/vN-*` directories, `docs/decisions/NNNN-*` files and
    `db/migration/VN__*` files;
  - every local and `origin/*` branch's tree;
  - the branch name itself — `feat/v68-...` claims v68 before any doc exists.

Numbers on `main` are already used and are not reported; only allocations past
main's highest are claims worth seeing. That is what makes the listing short.

    python3 scripts/docs/next_number.py              # outstanding claims + next free
    python3 scripts/docs/next_number.py --claim v69  # is version v69 still free?
    python3 scripts/docs/next_number.py --claim 0199 # is ADR 0199 still free?
    python3 scripts/docs/next_number.py --claim V53  # is Flyway V53 still free?

`--claim` exits 1 when a number is held by anyone other than this worktree, so
it can gate naming a version, an ADR or a migration.

This is a local tool: CI checks out one branch and cannot see the others. Its
strength is the same as `git worktree list` — it sees what this clone sees;
another machine's unpushed branch is invisible to it.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

VERSION_DIR = re.compile(r"^v(\d+)-")
ADR_FILE = re.compile(r"^(\d{4})-")
MIGRATION_FILE = re.compile(r"^V(\d+)__")
BRANCH_VERSION = re.compile(r"(?:^|[/_-])v(\d+)(?:[-/_.]|$)")

MIGRATIONS = Path("skillmaster-server/src/main/resources/db/migration")


def git(*args: str, cwd: Path | None = None) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if result.returncode != 0:
        return ""
    return result.stdout


def worktrees() -> list[tuple[Path, str]]:
    """(path, branch) for every worktree; branch is empty when detached."""
    found: list[tuple[Path, str]] = []
    path: Path | None = None
    branch = ""
    for line in git("worktree", "list", "--porcelain").splitlines():
        if line.startswith("worktree "):
            if path is not None:
                found.append((path, branch))
            path = Path(line[len("worktree ") :])
            branch = ""
        elif line.startswith("branch "):
            branch = line[len("branch ") :].removeprefix("refs/heads/")
    if path is not None:
        found.append((path, branch))
    return found


class Numbers:
    def __init__(self) -> None:
        self.versions: set[int] = set()
        self.adrs: set[int] = set()
        self.migrations: set[int] = set()

    def add(self, path: str) -> None:
        """Read a repo-relative path such as `docs/versions/v69-x/prd.md`."""
        parts = path.split("/")
        if len(parts) >= 3 and parts[:2] == ["docs", "versions"]:
            match = VERSION_DIR.match(parts[2])
            if match:
                self.versions.add(int(match.group(1)))
        elif len(parts) == 3 and parts[:2] == ["docs", "decisions"]:
            match = ADR_FILE.match(parts[2])
            if match:
                self.adrs.add(int(match.group(1)))
        elif len(parts) == len(MIGRATIONS.parts) + 1 and path.startswith(
            str(MIGRATIONS) + "/"
        ):
            match = MIGRATION_FILE.match(parts[-1])
            if match:
                self.migrations.add(int(match.group(1)))

    def scan_tree(self, root: Path) -> None:
        """Files on disk under `root`, so uncommitted claims count."""
        for directory, pattern, bucket in (
            (root / "docs" / "versions", VERSION_DIR, self.versions),
            (root / "docs" / "decisions", ADR_FILE, self.adrs),
            (root / MIGRATIONS, MIGRATION_FILE, self.migrations),
        ):
            if not directory.is_dir():
                continue
            for entry in directory.iterdir():
                match = pattern.match(entry.name)
                if match:
                    bucket.add(int(match.group(1)))


def base_refs() -> list[str]:
    """Both mains: a local `main` is often behind, and a stale one underreports."""
    return [
        ref
        for ref in ("refs/heads/main", "refs/remotes/origin/main")
        if git("rev-parse", "--verify", "--quiet", ref)
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--claim",
        metavar="NUMBER",
        help="check one number: vNN version, four-digit NNNN ADR, VNN Flyway migration",
    )
    args = parser.parse_args()

    top = Path(git("rev-parse", "--show-toplevel").strip() or ".").resolve()
    refs = base_refs()
    if not refs:
        print("找不到 main：这个仓库还没有主干。", file=sys.stderr)
        return 2

    base = Numbers()
    for ref in refs:
        for path in git(
            "ls-tree",
            "-r",
            "--name-only",
            ref,
            "--",
            "docs/versions",
            "docs/decisions",
            str(MIGRATIONS),
        ).splitlines():
            base.add(path)
    floors = (
        max(base.versions, default=0),
        max(base.adrs, default=0),
        max(base.migrations, default=0),
    )

    claims: tuple[dict[int, set[str]], ...] = ({}, {}, {})
    buckets = ("versions", "adrs", "migrations")

    def claim(kind: str, number: int, source: str) -> None:
        index = buckets.index(kind)
        if number > floors[index]:
            claims[index].setdefault(number, set()).add(source)

    found = worktrees()
    checked_out = {branch for _, branch in found if branch}
    here = git("rev-parse", "--abbrev-ref", "HEAD").strip()

    for path, branch in found:
        label = f"工作区 {path.name}" + (f"（{branch}）" if branch else "（游离）")
        if path.resolve() == top:
            label += " ← 本次"
        tree = Numbers()
        tree.scan_tree(path)
        for kind in buckets:
            for number in sorted(getattr(tree, kind)):
                claim(kind, number, label)

    ref_names = [
        line
        for line in git(
            "for-each-ref",
            "--format=%(refname:short)",
            "refs/heads",
            "refs/remotes/origin",
        ).splitlines()
        if line
    ]
    for name in ref_names:
        match = BRANCH_VERSION.search(name)
        if match:
            claim(
                "versions",
                int(match.group(1)),
                f"分支名 {name}" + (" ← 本次" if name == here else ""),
            )
        if name in checked_out:
            continue  # the worktree listing above already read this branch's files
        for path in git(
            "ls-tree",
            "-r",
            "--name-only",
            name,
            "--",
            "docs/versions",
            "docs/decisions",
            str(MIGRATIONS),
        ).splitlines():
            parts = path.split("/")
            if len(parts) >= 3 and parts[:2] == ["docs", "versions"]:
                found_version = VERSION_DIR.match(parts[2])
                if found_version:
                    claim("versions", int(found_version.group(1)), f"分支 {name}")
            elif len(parts) == 3 and parts[:2] == ["docs", "decisions"]:
                found_adr = ADR_FILE.match(parts[2])
                if found_adr:
                    claim("adrs", int(found_adr.group(1)), f"分支 {name}")
            elif len(parts) == len(MIGRATIONS.parts) + 1 and path.startswith(
                str(MIGRATIONS) + "/"
            ):
                found_migration = MIGRATION_FILE.match(parts[-1])
                if found_migration:
                    claim("migrations", int(found_migration.group(1)), f"分支 {name}")

    if args.claim is not None:
        value = args.claim.strip()
        if re.fullmatch(r"v\d+", value):
            kind, number, label, floor = "version", int(value[1:]), value, floors[0]
        elif re.fullmatch(r"V\d+", value):
            kind, number, label, floor = "migration", int(value[1:]), value, floors[2]
        elif re.fullmatch(r"\d{4}", value):
            kind, number, label, floor = "adr", int(value), value, floors[1]
        else:
            print(f"认不出 {value!r}：vNN 版本、NNNN ADR、VNN 迁移。", file=sys.stderr)
            return 2
        held = claims[buckets.index(kind + "s")].get(number, set())
        names = {"version": "版本号", "adr": "ADR 号", "migration": "Flyway 迁移号"}
        if number <= floor and number > 0:
            print(f"{label} 已被主干占用（{names[kind]}不重号）。")
            return 1
        foreign = sorted(source for source in held if "← 本次" not in source)
        if foreign:
            print(f"{label} 已被占用：")
            for source in foreign:
                print(f"  {source}")
            return 1
        print(f"{label} 已在本工作区占用，正常。" if held else f"{label} 空闲。")
        return 0

    print(f"本次工作区：{top}（标「← 本次」的占用只属于这里）")
    print(
        f"主干已用到 v{floors[0]}、ADR {floors[1]:04d}、Flyway V{floors[2]}；"
        "下面是主干之外的占用。\n"
    )

    def listing(title: str, claims: dict[int, set[str]], prefix: str) -> None:
        print(title)
        if not claims:
            print("  （没有）")
        for number in sorted(claims):
            print(f"  {prefix.format(number)}  {' · '.join(sorted(claims[number]))}")
        print()

    listing("版本号", claims[0], "v{}")
    listing("ADR 号", claims[1], "{}")
    listing("Flyway 迁移号", claims[2], "V{}")
    nexts = [max([floors[index], *claims[index]]) + 1 for index in range(3)]
    print(f"下一个空号：v{nexts[0]}、ADR {nexts[1]:04d}、Flyway V{nexts[2]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

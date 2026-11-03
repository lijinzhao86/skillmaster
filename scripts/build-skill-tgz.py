#!/usr/bin/env python3
"""Pack skills/skillmaster/ into the archive the site publishes (v38).

**Byte-for-byte reproducible within one environment**, because the test plan compares the hash of
two builds (T3) and because a package whose bytes move on every build makes "which one did I
install?" unanswerable. Every field a filesystem or a clock could leak into is pinned:

- entries in sorted path order, so directory-listing order never reaches the archive;
- modification time 0, owner and group 0 with empty names — no user or machine shows through;
- regular files 0644 / 0755 by their executable bit, directories 0755;
- ustar only, so no pax headers whose key order could vary;
- gzip header with mtime 0 and no file name;
- written to a temporary file and moved into place, so a failed build never leaves a half archive
  where a good one used to be.

The package is **exactly one file, `SKILL.md`** (v42): the local skill is a dispatcher, and the
step-by-step procedures live in the public library instead. Packing is therefore by construction
rather than by whitelist — there is nothing to walk, so a stray `notes.txt` or a `.DS_Store`
cannot reach the archive, and the install document's file count of one cannot drift.

The step-by-step procedures will live in a **public guide skill** published separately from this
archive — **not published by this version**; the repository no longer carries a `sop/` tree beside
`SKILL.md` (2026-11-03).

The tar flags that would do this on Linux (`--sort`, `--mtime`, `--owner`) do not exist in macOS's
bsdtar, so the one implementation is here rather than two branches of a shell command.
"""

import gzip
import hashlib
import io
import os
import sys
import tarfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
NAME = "skillmaster"
SOURCE = REPO / "skills" / NAME
TARGET = REPO / "skillmaster-site" / "public" / "skill" / f"{NAME}.tgz"

def fail(message: str) -> None:
    sys.exit(f"build-skill-tgz: {message}")


def frontmatter_name(skill_md: Path) -> str:
    """The `name:` from SKILL.md's frontmatter, or "" when there is none."""
    text = skill_md.read_text(encoding="utf-8")
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return ""
    for line in lines[1:]:
        if line.strip() == "---":
            break
        if line.startswith("name:"):
            return line.split(":", 1)[1].strip().strip("'\"")
    return ""


def add(tar: tarfile.TarFile, path: Path, archive_name: str) -> None:
    if path.is_symlink():
        fail(f"{path} is a symlink — the archive must not carry files from outside the skill")
    info = tarfile.TarInfo(archive_name)
    info.mtime = 0
    info.uid = 0
    info.gid = 0
    info.uname = ""
    info.gname = ""
    if path.is_dir():
        info.type = tarfile.DIRTYPE
        info.mode = 0o755
        info.size = 0
        tar.addfile(info)
    elif path.is_file():
        info.type = tarfile.REGTYPE
        info.mode = 0o755 if path.stat().st_mode & 0o111 else 0o644
        data = path.read_bytes()
        info.size = len(data)
        tar.addfile(info, io.BytesIO(data))
    else:
        fail(f"{path} is not a regular file or a directory")


def build(source: Path, target: Path) -> str:
    if not (source / "SKILL.md").is_file():
        fail(f"{source} has no SKILL.md — is this a skill?")
    if source.name != NAME:
        fail(f"{source} must be named {NAME!r}; the package's top-level directory is its name")
    if frontmatter_name(source / "SKILL.md") != NAME:
        fail(f"{source}/SKILL.md must say `name: {NAME}` in its frontmatter — clients name a skill by it")

    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        try:
            add(tar, source / "SKILL.md", f"{NAME}/SKILL.md")
        except ValueError as exc:
            fail(f"SKILL.md: {exc}")

    target.parent.mkdir(parents=True, exist_ok=True)
    staged = target.with_name(target.name + ".tmp")
    with staged.open("wb") as handle:
        with gzip.GzipFile(filename="", mode="wb", fileobj=handle, mtime=0, compresslevel=9) as gz:
            gz.write(raw.getvalue())
    os.replace(staged, target)
    return hashlib.sha256(target.read_bytes()).hexdigest()


def main(argv: list) -> None:
    source = Path(argv[1]).resolve() if len(argv) > 1 else SOURCE
    target = Path(argv[2]).resolve() if len(argv) > 2 else TARGET
    digest = build(source, target)
    try:
        shown = target.relative_to(REPO)
    except ValueError:
        shown = target
    print(f"{shown}  sha256={digest}")


if __name__ == "__main__":
    main(sys.argv)

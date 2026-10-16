#!/usr/bin/env python3
"""Validate the docs/ tree against the docs-architecture skill's conventions.

Checks only what is objectively decidable:
  - every versions/vN-*/ folder has the four required documents
  - each document covers its required topics (as headings, or the lead block)
  - iteration records, when there are any, are named NNNN-<slug>.md
  - every relative markdown link resolves
  - decisions live in docs/decisions/, are named NNNN-<slug>.md, and have four sections

It does NOT judge prose quality, factual accuracy, or whether a measurement
states its basis. Those are governed by the skill's rules and by people.

Three deliberate limits:

  * Link targets are verified, **anchors are not**. ``[x](#some-section)`` and
    ``file.md#some-section`` confirm only that the file exists. Only inline
    links are found — reference-style definitions (``[ref]: path``) are not
    resolved.
  * Links inside fenced code blocks are ignored, for the same reason headings
    there are: a fenced block is an example, not structure. A destination
    containing a bare ``)`` (e.g. ``foo(1).md``) is not parsed — none exist in
    this repo, and the failure mode is a visible false positive, not a silent
    pass.
  * Heading extraction and the ADR section check both ignore fenced code blocks:
    a fenced block is an example, so headings inside it are content, not structure.

Usage:
    python3 check_docs.py [--docs PATH]

Exit code is 0 when compliant, 1 otherwise.
"""

from __future__ import annotations

import argparse
import os
import re
import sys

# Topics each document must cover. A topic passes when the literal keyword
# appears in a heading, or — for topics in LEAD_OK — anywhere before the first
# level-2 heading (this repo records version status in a lead block).
REQUIRED_TOPICS: dict[str, list[str]] = {
    "README.md": ["状态", "目标", "不做", "验收", "目标态"],
    "prd.md": ["用户", "范围", "验收"],
    "technical-design.md": ["架构", "模型", "接口", "分期", "开放问题"],
    "test-plan.md": ["验收", "用例", "结果"],
}
LEAD_OK = {"状态"}

REQUIRED_VERSION_FILES = ["README.md", "prd.md", "technical-design.md", "test-plan.md"]
OPTIONAL_VERSION_FILES = ["known-issues.md"]
VERSION_SUBDIRS = ["iterations"]

# `docs/architecture/` — the cross-version engineering architecture. Only the
# index is required: the rest migrates in as each module is discussed, and
# demanding a file before its content exists is how you get a second authority
# that drifts from the one it was copied out of.
ARCHITECTURE_DIR = "architecture"
ARCHITECTURE_REQUIRED_FILES = ["README.md"]
ARCHITECTURE_OPTIONAL_FILES = ["rules.md", "model.md"]
ARCHITECTURE_SUBDIRS = ["modules"]

# Figures that sit beside a document rather than being one. Admitted as a class
# rather than by filename: an image carries no prose, so — unlike a stray .md —
# it cannot become a second authority on a fact, which is the only thing the
# closed folder shapes exist to prevent. Naming a figure is therefore free, and
# every future diagram is covered without another edit here.
ASSET_SUFFIXES = (".svg", ".png")

ADR_SECTIONS = ["背景", "决定", "理由", "后果"]

ITERATION_RE = re.compile(r"^\d{4}-[^\s/\\]+\.md$")
VERSION_DIR_RE = re.compile(r"^v\d+-[a-z0-9][a-z0-9-]*$")
ADR_FILE_RE = re.compile(r"^\d{4}-[^\s/\\]+\.md$")
MODULE_DOC_RE = re.compile(r"^M\d{2}-[a-z0-9][a-z0-9-]*\.md$")
LINK_RE = re.compile(r"\[[^\]]*\]\(([^)]+)\)")

FENCE_OPEN_RE = re.compile(r"^\s*(`{3,}|~{3,})")
FENCE_CLOSE_RE = re.compile(r"^\s*(`{3,}|~{3,})\s*$")
H2_RE = re.compile(r"^#{2,6}\s+(.*)$")


def find_repo_root(start: str) -> str:
    """Walk upward from `start` until a directory containing docs/ is found."""
    cur = os.path.abspath(start)
    for _ in range(10):
        if os.path.isdir(os.path.join(cur, "docs")):
            return cur
        parent = os.path.dirname(cur)
        if parent == cur:
            break
        cur = parent
    raise SystemExit("could not locate a directory containing docs/")


def skill_root() -> str:
    """The skill's own directory — this script lives in `scripts/` beneath it.

    Computed from `__file__` rather than pinned to an absolute path, but it must
    stay exactly two levels up: the skill's own links are resolved relative to
    that directory, so pointing it at `scripts/` would check nothing at all and
    still report success.
    """
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read_text(path: str) -> str:
    """Read a file, surviving bytes that are not valid UTF-8."""
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read()


def analyse(text: str) -> tuple[str, list[str], str]:
    """Return (lead block, heading texts, text outside fenced code blocks).

    Fences are tracked by marker character and length so that a fenced example
    containing a shorter fence of the same character does not desync the scan.
    The lead block runs to the first level-2 heading, so a document's level-1
    title and the metadata bullets under it both count as lead.
    """
    kept: list[str] = []
    fence_char, fence_len = "", 0

    for line in text.splitlines():
        if not fence_char:
            opener = FENCE_OPEN_RE.match(line)
            if opener:
                fence_char, fence_len = opener.group(1)[0], len(opener.group(1))
                continue
            kept.append(line)
        else:
            closer = FENCE_CLOSE_RE.match(line)
            if (
                closer
                and closer.group(1)[0] == fence_char
                and len(closer.group(1)) >= fence_len
            ):
                fence_char, fence_len = "", 0

    headings: list[str] = []
    lead: list[str] = []
    seen_section = False
    for line in kept:
        match = H2_RE.match(line)
        if match:
            seen_section = True
            headings.append(match.group(1).strip())
        elif not seen_section:
            lead.append(line)

    return "\n".join(lead), headings, "\n".join(kept)


def is_asset(filename: str) -> bool:
    """True for an image that accompanies a document instead of being one."""
    return filename.lower().endswith(ASSET_SUFFIXES)


def check_topics(path: str, filename: str) -> list[str]:
    """Return the topics a document fails to cover."""
    lead, headings, _ = analyse(read_text(path))

    heading_text = "\n".join(headings)
    missing = []
    for topic in REQUIRED_TOPICS[filename]:
        if topic in heading_text:
            continue
        if topic in LEAD_OK and topic in lead:
            continue
        missing.append(topic)
    return missing


def iter_markdown(root: str, skip_dirs: frozenset[str] = frozenset()):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [
            d for d in dirnames if not d.startswith(".") and d not in skip_dirs
        ]
        for name in filenames:
            if name.endswith(".md"):
                yield os.path.join(dirpath, name)


def strip_link_title(target: str) -> str:
    """Drop an optional CommonMark link title and unwrap angle brackets.

    Handles `path "Title"`, `path 'Title'`, `path (Title)`, `<path>` and
    `<path> "Title"`. The title is removed first so an angle-wrapped path still
    gets unwrapped. A `(` counts as a title opener only when whitespace precedes
    it, so a path like `foo(1).md` survives.
    """
    target = re.sub(r"""\s+["'(].*$""", "", target, flags=re.S).strip()
    if target.startswith("<") and target.endswith(">"):
        target = target[1:-1].strip()
    return target


def check_links(
    root: str, skip_dirs: frozenset[str] = frozenset()
) -> tuple[int, list[str]]:
    """Return (links checked, broken links as 'file -> target')."""
    total = 0
    broken = []
    for path in iter_markdown(root, skip_dirs):
        # A fenced block is an example: a sample linking to a non-existent file
        # must not fail the build.
        _, _, unfenced = analyse(read_text(path))
        for match in LINK_RE.finditer(unfenced):
            target = strip_link_title(match.group(1)).split("#")[0].strip()
            if not target or "://" in target or target.startswith("mailto:"):
                continue
            total += 1
            resolved = os.path.normpath(os.path.join(os.path.dirname(path), target))
            if not os.path.exists(resolved):
                broken.append(f"{os.path.relpath(path, root)} -> {match.group(1)}")
    return total, broken


def check_decisions(decisions_dir: str) -> list[str]:
    problems: list[str] = []
    if not os.path.isdir(decisions_dir):
        return ["docs/decisions/ 不存在"]

    for name in sorted(os.listdir(decisions_dir)):
        if name == "README.md":
            continue
        path = os.path.join(decisions_dir, name)
        if os.path.isdir(path):
            problems.append(f"decisions/{name} 是目录——ADR 必须是文件")
            continue
        if not name.endswith(".md"):
            problems.append(f"decisions/{name} 不是 .md 文件")
            continue
        if not ADR_FILE_RE.match(name):
            problems.append(f"decisions/{name} 命名不合规（应为 NNNN-slug.md）")
            continue
        _, _, unfenced = analyse(read_text(path))
        for section in ADR_SECTIONS:
            if not re.search(rf"^##\s+{re.escape(section)}\s*$", unfenced, re.M):
                problems.append(f"decisions/{name} 缺小节 ## {section}")
    return problems


def check_version(version_dir: str, name: str) -> list[str]:
    problems: list[str] = []

    for filename in REQUIRED_VERSION_FILES:
        path = os.path.join(version_dir, filename)
        if not os.path.isfile(path):
            problems.append(f"{name}/{filename} 缺失")
            continue
        for topic in check_topics(path, filename):
            problems.append(f"{name}/{filename} 未覆盖必需主题「{topic}」")

    # `iterations/` is NOT required to exist. It is a directory with no tracked
    # content until the first record is written, and git does not track empty
    # directories — so requiring it made a fresh clone fail on a version that had
    # simply never made a small-version change. (Measured 2026-10-16: a clean
    # `git archive` of main reported v2-credentials/iterations/ 缺失 and
    # v3-sessions/iterations/ 缺失.) It is still an *allowed* directory — see the
    # shape check below — and its contents are checked when it is there.

    # The folder shape is fixed, so anything unexpected is reported whether it
    # is a file or a directory.
    known_files = set(REQUIRED_VERSION_FILES) | set(OPTIONAL_VERSION_FILES)
    for entry in sorted(os.listdir(version_dir)):
        if entry.startswith("."):
            continue
        path = os.path.join(version_dir, entry)
        if os.path.isdir(path):
            # `decisions` gets a better message from the explicit check below.
            if entry not in VERSION_SUBDIRS and entry != "decisions":
                problems.append(
                    f"{name}/{entry}/ 不在约定内（版本文件夹只放四份文档 + iterations/）"
                )
        elif is_asset(entry):
            continue
        elif entry not in known_files:
            problems.append(
                f"{name}/{entry} 不在约定内"
                f"（版本文件夹只放四份文档 + known-issues.md + 图片）"
            )

    iterations = os.path.join(version_dir, VERSION_SUBDIRS[0])
    if os.path.isdir(iterations):
        for filename in sorted(os.listdir(iterations)):
            if filename.startswith("."):
                continue
            if not os.path.isfile(os.path.join(iterations, filename)):
                problems.append(f"{name}/iterations/{filename} 不是文件")
            elif not is_asset(filename) and not ITERATION_RE.match(filename):
                problems.append(
                    f"{name}/iterations/{filename} 命名不合规（应为 NNNN-slug.md）"
                )

    # Decisions must never live inside a version folder.
    if os.path.isdir(os.path.join(version_dir, "decisions")):
        problems.append(f"{name}/decisions/ 不应存在——决策属于 docs/decisions/")

    return problems


def check_architecture(docs_dir: str) -> list[str]:
    """`docs/architecture/` — the architecture that outlives versions.

    Checks the shape, not the content: the folder is closed (anything beyond the
    names below — and images, see ASSET_SUFFIXES — is reported), and `modules/`
    filenames are pinned to `MNN-slug.md` so the module docs stay sorted and
    addressable by module id.

    `rules.md`, `model.md` and `modules/` are checked only when present, the same
    way `iterations/` is. That is deliberate: architecture content moves here as
    it is discussed, and requiring an empty file up front would invite a copy of
    whatever it is meant to replace.
    """
    arch = os.path.join(docs_dir, ARCHITECTURE_DIR)
    if not os.path.isdir(arch):
        return [f"docs/{ARCHITECTURE_DIR}/ 不存在"]

    problems: list[str] = []
    for filename in ARCHITECTURE_REQUIRED_FILES:
        if not os.path.isfile(os.path.join(arch, filename)):
            problems.append(f"{ARCHITECTURE_DIR}/{filename} 缺失")

    known_files = set(ARCHITECTURE_REQUIRED_FILES) | set(ARCHITECTURE_OPTIONAL_FILES)
    for entry in sorted(os.listdir(arch)):
        if entry.startswith("."):
            continue
        path = os.path.join(arch, entry)
        if os.path.isdir(path):
            if entry not in ARCHITECTURE_SUBDIRS:
                problems.append(
                    f"{ARCHITECTURE_DIR}/{entry}/ 不在约定内（只放 modules/）"
                )
        elif is_asset(entry):
            continue
        elif entry not in known_files:
            problems.append(
                f"{ARCHITECTURE_DIR}/{entry} 不在约定内"
                f"（只放 README.md / rules.md / model.md + modules/ + 图片）"
            )

    modules = os.path.join(arch, "modules")
    if os.path.isdir(modules):
        for filename in sorted(os.listdir(modules)):
            if filename.startswith("."):
                continue
            if not os.path.isfile(os.path.join(modules, filename)):
                problems.append(f"{ARCHITECTURE_DIR}/modules/{filename} 不是文件")
            elif not is_asset(filename) and not MODULE_DOC_RE.match(filename):
                problems.append(
                    f"{ARCHITECTURE_DIR}/modules/{filename} 命名不合规（应为 MNN-slug.md）"
                )

    return problems


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate docs/ against the docs-architecture conventions."
    )
    parser.add_argument("--docs", help="path to docs/ (default: <repo>/docs)")
    args = parser.parse_args()

    docs = (
        os.path.abspath(args.docs)
        if args.docs
        else os.path.join(find_repo_root(os.path.dirname(__file__)), "docs")
    )
    versions_dir = os.path.join(docs, "versions")

    print(f"docs = {docs}\n")

    problems: list[str] = []
    entries: list[str] = []

    if not os.path.isdir(versions_dir):
        problems.append("docs/versions/ 不存在")
    else:
        entries = sorted(
            e
            for e in os.listdir(versions_dir)
            if os.path.isdir(os.path.join(versions_dir, e))
        )
        for stray in sorted(os.listdir(versions_dir)):
            if stray.startswith("."):
                continue
            if not os.path.isdir(os.path.join(versions_dir, stray)):
                problems.append(
                    f"docs/versions/{stray} 不在约定内（versions/ 下只放版本文件夹）"
                )
        if not entries:
            problems.append("docs/versions/ 下没有任何版本文件夹")

    for name in entries:
        version_dir = os.path.join(versions_dir, name)
        found = [
            f
            for f in REQUIRED_VERSION_FILES
            if os.path.isfile(os.path.join(version_dir, f))
        ]
        missing = [f for f in REQUIRED_VERSION_FILES if f not in found]
        print(name)
        print(f"  ✓ {'  '.join(found)}" if found else "  （无必需文档）")
        if missing:
            print(f"  ✗ 缺: {'  '.join(missing)}")
        if not VERSION_DIR_RE.match(name):
            problems.append(f"{name}/ 命名不合规（应为 vN-主题）")
        problems.extend(check_version(version_dir, name))
        print()

    problems.extend(check_decisions(os.path.join(docs, "decisions")))

    arch = os.path.join(docs, ARCHITECTURE_DIR)
    print(f"{ARCHITECTURE_DIR}/")
    if os.path.isdir(arch):
        present = [
            f
            for f in ARCHITECTURE_REQUIRED_FILES + ARCHITECTURE_OPTIONAL_FILES
            if os.path.isfile(os.path.join(arch, f))
        ]
        print(f"  ✓ {'  '.join(present)}" if present else "  （无文档）")
        modules = os.path.join(arch, "modules")
        if os.path.isdir(modules):
            count = len([f for f in os.listdir(modules) if f.endswith(".md")])
            print(f"  modules/：{count} 篇")
    problems.extend(check_architecture(docs))
    print()

    total, broken = check_links(docs)
    print(f"  相对链接：检查 {total} 条")
    if broken:
        problems.append(f"{len(broken)} 条相对链接是死链")
        for item in broken:
            print(f"  ✗ {item}")
    else:
        print(f"  ✓ {total} 条相对链接全部有效")

    # The skill's own links point into docs/ as well. Templates are skipped: their
    # links are written relative to the version folder they will be copied into,
    # so they cannot resolve where they sit.
    skill_total, skill_broken = check_links(skill_root(), frozenset({"templates"}))
    print(f"\n  skill 自身链接：检查 {skill_total} 条（templates/ 按设计跳过）")
    if skill_broken:
        problems.append(f"skill 目录内 {len(skill_broken)} 条链接是死链")
        for item in skill_broken:
            print(f"  ✗ {item}")
    else:
        print(f"  ✓ {skill_total} 条全部有效")

    print()
    if problems:
        print(f"发现 {len(problems)} 个问题：")
        for item in problems:
            print(f"  - {item}")
        return 1
    print("全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

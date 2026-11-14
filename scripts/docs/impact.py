#!/usr/bin/env python3
"""Answer "what else references this?" without grepping the repository.

Every audit so far started with a search: change a ceiling, then grep for it;
rename a class, then grep for the old name; edit an ADR, then hope nothing else
said the same thing. That is O(repository) per question, and it is why the
audits kept re-reading the same files.

This builds one in-memory index and answers from it:

    python3 scripts/docs/impact.py IngestLimits.STANDARD_MAX_FILE_BYTES
    python3 scripts/docs/impact.py --adr 0130
    python3 scripts/docs/impact.py --version v39
    python3 scripts/docs/impact.py --file docs/current/limits.md
    python3 scripts/docs/impact.py --check      # do the references resolve?

What counts as a reference: a backticked `Class.MEMBER` (main or test code), an
`ADR NNNN`, a `vNN` version, a backticked test class name, and a backticked
configuration key (`skillmaster.*` is judged; `spring.*` / `server.*` are indexed
but not judged — a framework property may be real without appearing in our
application.yml), in live documents **and in code comments**. Those are the links; a fact is not allowed to travel any other
way.

**Bare method names and unknown class names are deliberately not judged.**
`RegisteredClientRepository` is Spring's, `MaterializePlan` is a future one, and
both read like a stale rename; the lock only covers the names we can resolve. `revokeAllFor` on its own
could be prose, a standard-library call, or another project's API; flagging the
ones we cannot resolve would cry wolf on every ADR. The enforceable form is
`Class.MEMBER` — that is the form the writing standard asks for, and the form
`--check` can hold.

Table names and column names are **not** indexed: a snake_case token in prose
may be a table, a column, a config leaf, or a word. The generated `schema.md`
is what a rename shows up in, and `generate.py --check` compares it.

`--check` is the part that makes this a lock rather than a convenience: rename a
constant, an ADR file, a version folder, or a config key, and every live document
that names the old one turns red — the same way code does when a symbol
disappears. ADR bodies and versions before v40 are history and are exempt.
"""

from __future__ import annotations

import argparse
import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
SERVER_MAIN = ROOT / "skillmaster-server/src/main/java"
SERVER_TEST = ROOT / "skillmaster-server/src/test/java"
# v69 split the import into its own service; its tests are tests of this repository
# too, and a document naming one must resolve the same way.
SERVER_TEST_DIRS = [SERVER_TEST, ROOT / "skillmaster-ops/src/test/java"]
CLI = ROOT / "skillmaster-cli"

ADR_RE = re.compile(r"\bADR\s+(\d{4})\b")
VERSION_RE = re.compile(r"\bv(\d+)\b(?!\.\d)")
# A branch is named after its version (`feat/v68-cloud-device-login`), and that
# branch's version folder may live in another worktree — so the number would look
# like a reference to a version this tree does not have. The token is a git ref,
# not a version reference; strip it before the version scan, the same way
# `/api/v1` is stripped below.
BRANCH_REF_RE = re.compile(r"\b(?:feat|fix|chore|docs|ops|release)/v\d+[-\w.]*")
# `Class.member`. A file name (`SkillPaths.java`) also matches; the `member in
# FILE_SUFFIXES` skip below is what excludes those — a lookahead after the
# closing backtick checked the wrong thing and never fired.
SYMBOL_RE = re.compile(r"`([A-Z][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)`")
TEST_RE = re.compile(r"`([A-Z][A-Za-z0-9_]*[a-z](?:Test|IT))`")
# A constant written without its class: `STANDARD_MAX_FILE_BYTES`. Only names
# with an underscore are treated as constants, so `README` is not one.
CONST_RE = re.compile(r"`([A-Z][A-Z0-9]*_[A-Z0-9_]+)`")
# A configuration key written whole: `skillmaster.sms.accept-any-code`. All four
# prefixes are indexed so a rename is visible; only our own namespace is judged,
# because a framework property (`spring.http…`) may be real without appearing in
# application.yml, and judging those would flag correct prose. A file name
# (`skillmaster.tgz`) is skipped by its suffix below.
KEY_RE = re.compile(r"`((?:skillmaster|spring|server|management)\.[a-z0-9_.-]+)`")
# A class written without a member: `IngestLimits`. The suffix list keeps
# `Bearer` or `GitHub` out. A bare class name is indexed when we know it, but an
# unknown one is **not** judged: framework classes (`RegisteredClientRepository`)
# and future ones (`MaterializePlan`) look exactly like a stale rename.
TYPE_RE = re.compile(
    r"`([A-Z][A-Za-z0-9]*(?:Service|Controller|Repository|UseCase|Validator|"
    r"Store|Config|Configuration|Properties|Limits|Policy|Settings|Map|Code|"
    r"Cache|Reader|Writer|Artifact|Recorder|Registry|Handler|Advice|Filter|"
    r"Resolver|Adapter|Exception|Row|Document|Upload|Plan|Gate|History|Counts|Log|"
    r"Tests?|IT))`"
)
COMMENT_RE = re.compile(r"^\s*(//|/\*|\*|\{/\*)")
FILE_SUFFIXES = (
    ".yml",
    ".yaml",
    ".md",
    ".go",
    ".java",
    ".ts",
    ".tsx",
    ".vue",
    ".js",
    ".jsx",
    ".mjs",
    ".cjs",
    ".json",
    ".exe",
    ".sh",
    ".tgz",
    ".gz",
    ".tar",
    ".zip",
    ".jar",
    ".properties",
    ".xml",
    ".html",
    ".css",
    ".scss",
    ".py",
    ".sql",
    ".toml",
    ".png",
    ".svg",
    ".txt",
    ".csv",
    ".cfg",
    ".ini",
    ".log",
    ".db",
)


# --------------------------------------------------------------------------
# The things a reference can point at
# --------------------------------------------------------------------------


def java_definitions() -> tuple[dict[str, Path], dict[str, set[str]]]:
    """class name -> file, and class name -> its members."""
    files: dict[str, Path] = {}
    members: dict[str, set[str]] = defaultdict(set)
    decl = re.compile(
        r"\b(?:class|record|interface|enum|@interface)\s+([A-Z][A-Za-z0-9_]*)"
    )
    # Members are collected loosely on purpose: a checker that only understands
    # `public` misses interface methods and package-private constants, and a
    # reference to those would be reported as stale. Missing a rename is the
    # cheaper failure than crying wolf on every ADR.
    member = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(")
    for path in [
        *SERVER_MAIN.rglob("*.java"),
        *(path for test in SERVER_TEST_DIRS for path in test.rglob("*.java")),
    ]:
        text = path.read_text(encoding="utf-8")
        found = decl.findall(text)
        if not found:
            continue
        name = found[0]
        files[name] = path
        members[name].update(member.findall(text))
        members[name].update(re.findall(r"\b([A-Z][A-Z0-9_]{2,})\b", text))
        members[name].update(found)
        # Record components are part of the public shape too.
        for params in re.findall(r"\brecord\s+\w+\s*\(([^)]*)\)", text):
            members[name].update(
                re.findall(r"\b([a-z][A-Za-z0-9_]*)\s*[,)]", params + ")")
            )
    return files, members


def test_classes() -> set[str]:
    return {path.stem for test in SERVER_TEST_DIRS for path in test.rglob("*.java")}


def config_keys() -> set[str]:
    """Every key path in application.yml, dotted the way the docs write it."""
    source = ROOT / "skillmaster-server/src/main/resources/application.yml"
    if not source.is_file():
        return set()
    keys: set[str] = set()
    stack: list[tuple[int, str]] = []
    for raw in source.read_text(encoding="utf-8").splitlines():
        if not raw.strip() or raw.lstrip().startswith("#") or ":" not in raw:
            continue
        indent = len(raw) - len(raw.lstrip())
        key = raw.strip().partition(":")[0].strip().strip('"')
        while stack and stack[-1][0] >= indent:
            stack.pop()
        keys.add(".".join([k for _, k in stack] + [key]))
        if not raw.strip().partition(":")[2].strip():
            stack.append((indent, key))
    return keys


def adr_files() -> set[str]:
    return {p.name[:4] for p in (DOCS / "decisions").glob("[0-9][0-9][0-9][0-9]-*.md")}


def version_dirs() -> set[str]:
    return {
        m.group(1)
        for p in (DOCS / "versions").iterdir()
        if p.is_dir() and (m := re.match(r"v(\d+)-", p.name))
    }


# --------------------------------------------------------------------------
# Where references live
# --------------------------------------------------------------------------


def sources() -> list[tuple[Path, bool]]:
    """(file, is_comment_only) — docs are read whole, code only in comments."""
    out: list[tuple[Path, bool]] = []
    # `iterations/` is history by the same rule as ADR bodies: a record of what was
    # tested then, written once and never edited. Judging it would turn "this test
    # existed in v50" into an error the moment the code moved.
    out.extend((p, False) for p in DOCS.rglob("*.md") if "iterations" not in p.parts)
    # Skills and CLAUDE.md are live documents too: they tell agents what to do,
    # and a stale ADR or symbol there is followed, not merely read.
    out.extend((p, False) for p in (ROOT / ".claude/skills").rglob("*.md"))
    out.append((ROOT / "CLAUDE.md", False))
    out.extend((p, True) for p in [*SERVER_MAIN.rglob("*.java"), *CLI.rglob("*.go")])
    for sub in ("skillmaster-console/src", "skillmaster-site/src"):
        for suffix in ("*.ts", "*.tsx", "*.css"):
            out.extend((p, True) for p in (ROOT / sub).rglob(suffix))
    return out


def lines_of(path: Path, comments_only: bool):
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if comments_only and not COMMENT_RE.match(line):
            continue
        yield number, line


class Index:
    def __init__(self) -> None:
        self.files, self.members = java_definitions()
        self.tests = test_classes()
        self.adrs = adr_files()
        self.versions = version_dirs()
        self.config_keys = config_keys()
        self.constants: set[str] = {
            member
            for members in self.members.values()
            for member in members
            if "_" in member and member.isupper()
        }
        self.refs: dict[str, list[tuple[Path, int, str]]] = defaultdict(list)
        self.unresolved: list[tuple[Path, int, str, str]] = []
        self._build()

    def _build(self) -> None:
        for path, comments_only in sources():
            for number, line in lines_of(path, comments_only):
                if "/api/v1" in line:
                    line_without_api = line.replace("/api/v1", "")
                else:
                    line_without_api = line
                line_without_api = BRANCH_REF_RE.sub("", line_without_api)

                for adr in ADR_RE.findall(line):
                    self._add(f"ADR {adr}", path, number, line)
                    if adr not in self.adrs:
                        self.unresolved.append((path, number, f"ADR {adr}", line))
                for version in VERSION_RE.findall(line_without_api):
                    self._add(f"v{version}", path, number, line)
                    if version not in self.versions:
                        self.unresolved.append((path, number, f"v{version}", line))
                for klass, member in SYMBOL_RE.findall(line):
                    if klass not in self.files:
                        continue  # unknown class: external, future, or prose
                    if member in {suffix.lstrip(".") for suffix in FILE_SUFFIXES}:
                        continue  # `Foo.java` is a path, not a member
                    self._add(f"{klass}.{member}", path, number, line)
                    if member not in self.members[klass]:
                        self.unresolved.append(
                            (path, number, f"{klass}.{member}", line)
                        )
                # Bare names are indexed but not judged: `SKILLMASTER_SERVER` is
                # an environment variable and `O_TRUNC` a syscall, so "this
                # uppercase name is not one of our constants" is not a defect.
                # The enforceable form is `Class.MEMBER`, and that is the form
                # the writing standard asks for.
                for constant in CONST_RE.findall(line):
                    if constant in self.constants:
                        self._add(constant, path, number, line)
                for klass in TYPE_RE.findall(line):
                    if klass in self.files or klass in self.tests:
                        self._add(klass, path, number, line)
                for key in KEY_RE.findall(line):
                    # `skillmaster.tgz` and `skillmaster.exe` are paths, not keys.
                    if key.endswith(FILE_SUFFIXES):
                        continue
                    self._add(key, path, number, line)
                    # Only our own namespace is judged: a framework property
                    # (`spring.http…`) may be real without appearing in our
                    # application.yml. Renaming one is still visible through
                    # `config.md`, but prose that names it is not a stale ref.
                    if key.startswith("skillmaster.") and key not in self.config_keys:
                        self.unresolved.append((path, number, key, line))
                for test in TEST_RE.findall(line):
                    self._add(test, path, number, line)
                    if test not in self.tests:
                        self.unresolved.append((path, number, test, line))

    def _add(self, term: str, path: Path, number: int, line: str) -> None:
        self.refs[term].append((path, number, line.strip()))

    def describe(self, term: str) -> str:
        where = ""
        if term.startswith("ADR "):
            where = f"docs/decisions/{term[4:]}"
        elif re.fullmatch(r"v\d+", term):
            where = f"docs/versions/v{term[1:]}-*"
        elif term in self.config_keys:
            where = "application.yml"
        elif "." in term:
            klass = term.split(".")[0]
            path = self.files.get(klass)
            where = str(path.relative_to(ROOT)) if path else "（未定义）"
        elif term in self.tests:
            where = "src/test 里的测试类"
        elif term in self.files:
            where = str(self.files[term].relative_to(ROOT))
        entries = self.refs.get(term, [])
        out = [f"{term}", f"  定义：{where}", f"  引用：{len(entries)} 处"]
        for path, number, line in entries:
            out.append(f"    {path.relative_to(ROOT)}:{number}  {line[:110]}")
        return "\n".join(out)


def is_frozen(path: Path) -> bool:
    """Historical documents are allowed to name things that no longer exist.

    An ADR says what the code looked like when the decision was made; a version
    before v40 is the same. Requiring them to stay resolvable would force a
    rewrite of history, which is the opposite of what the record is for.
    """
    try:
        rel = path.relative_to(DOCS).as_posix()
    except ValueError:
        return False  # code comments are always current
    if rel.startswith(("decisions/",)):
        return True
    m = re.match(r"versions/v(\d+)-", rel)
    return bool(m and int(m.group(1)) < 40)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("term", nargs="?", help="a symbol, `ADR 0130`, or `v39`")
    parser.add_argument("--adr", help="four-digit ADR number")
    parser.add_argument("--version", help="version id, e.g. v39")
    parser.add_argument("--file", help="references to a documentation file")
    parser.add_argument(
        "--check", action="store_true", help="verify references resolve"
    )
    args = parser.parse_args()

    index = Index()

    if args.check:
        live = [u for u in index.unresolved if not is_frozen(u[0])]
        frozen = len(index.unresolved) - len(live)
        if not live:
            print(
                f"引用检查通过：{len(index.refs)} 个被引用的对象；"
                f"历史文档里的 {frozen} 处按设计不复核"
            )
            return 0
        print(
            f"引用检查：{len(live)} 处指向不存在的对象（历史文档另有 {frozen} 处，不复核）"
        )
        for path, number, term, line in live:
            print(f"  {path.relative_to(ROOT)}:{number}  {term}  {line[:90]}")
        return 1

    if args.adr:
        print(index.describe(f"ADR {args.adr}"))
        return 0
    if args.version:
        print(index.describe(args.version))
        return 0
    if args.file:
        target = Path(args.file).resolve()
        hits = [
            (term, entries)
            for term, entries in index.refs.items()
            if any(p.resolve() == target for p, _, _ in entries)
        ]
        print(f"{args.file} 提到 {len(hits)} 个对象")
        for term, _ in sorted(hits):
            print(f"  {term}")
        return 0
    if args.term:
        print(index.describe(args.term))
        return 0

    print(f"被引用的对象：{len(index.refs)}")
    print(f"指向不存在的对象：{len(index.unresolved)}")
    print("用法见 --help")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

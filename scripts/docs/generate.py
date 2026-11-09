#!/usr/bin/env python3
"""Generate the documents that are views of a machine source.

The rule this script enforces is v40's: a fact has one home, and every other
mention is a pointer or a generated view. Nine files are generated:

    docs/current/system.md      <- ModuleMap.java
    docs/current/limits.md      <- IngestLimits.java, BlobSettings.java
    docs/current/errors.md      <- ErrorCode.java
    docs/current/schema.md      <- resources/db/migration/V*.sql
    docs/current/cli.md         <- cmd/skillmaster/declarations*.go
    docs/current/config.md      <- resources/application.yml
    docs/README.md              <- the docs/ tree
    docs/versions/README.md     <- versions/*/README.md
    docs/decisions/README.md    <- decisions/NNNN-*.md

Run it after changing any source, and commit the result. CI runs
`generate.py --check` and fails when the tree differs from what the sources say.

**A parse that finds nothing is a failure, not an empty document.** The worst
outcome here is a generator that silently writes a half-empty table, so every
extractor asserts a count and exits non-zero when the source shape changes.

Usage:
    python3 scripts/docs/generate.py            # write
    python3 scripts/docs/generate.py --check    # compare only; exit 1 on drift
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
SERVER = ROOT / "skillmaster-server/src/main/java/com/skillmasterai"

HEADER = "<!-- 生成物，勿手改。源：{source}；重新生成：python3 scripts/docs/generate.py -->\n"


def fail(message: str) -> NoReturn:
    print(f"generate: {message}", file=sys.stderr)
    raise SystemExit(1)


def read(path: Path) -> str:
    if not path.is_file():
        fail(f"找不到源文件 {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    print(f"写了 {path.relative_to(ROOT)}")


# --------------------------------------------------------------------------
# docs/current/system.md  <- ModuleMap.java
# --------------------------------------------------------------------------

MODULE_RE = re.compile(
    r'new Module\("(M\d+)"\s*,\s*"([^"]+)"\s*,\s*'
    r'MODULES_PACKAGE\s*\+\s*"\.([^"]+)"\s*,\s*'
    r"Set\.of\(([^)]*)\)\)",
    re.S,
)


def parse_modules() -> list[tuple[str, str, str, list[str]]]:
    text = read(SERVER / "common/ModuleMap.java")
    modules = []
    for module_id, name, package_suffix, tables in MODULE_RE.findall(text):
        owned = re.findall(r'"([^"]+)"', tables)
        modules.append((module_id, name, package_suffix, owned))
    if len(modules) < 11:
        fail(f"只解析到 {len(modules)} 个模块，源文件的形状可能变了")
    return modules


PUBLISHED_RE = re.compile(r'Map\.entry\("(M\d+)",\s*Set\.of\(([^)]*)\)\)', re.S)


def published_types() -> dict[str, list[str]]:
    text = read(SERVER / "common/ModuleMap.java")
    return {
        module_id: re.findall(r'"([^"]+)"', types)
        for module_id, types in PUBLISHED_RE.findall(text)
    }


def gen_system() -> str:
    modules = parse_modules()
    published = published_types()
    missing = {module[0] for module in modules} - set(published)
    if missing:
        fail(f"PUBLISHED_TYPES 缺少这些模块的条目：{', '.join(sorted(missing))}")
    ids = {m[0] for m in modules}
    for required in (
        "M1",
        "M2",
        "M3",
        "M4",
        "M5",
        "M6",
        "M7",
        "M8",
        "M9",
        "M10",
        "M12",
        "M13",
    ):
        if required not in ids:
            fail(f"缺少模块 {required}，解析可能漏了")

    lines = [
        HEADER.format(
            source="skillmaster-server/src/main/java/com/skillmasterai/common/ModuleMap.java"
        ),
        "# 模块表（生成）\n",
        "模块编号、包名、「拥有哪些表」与对外发布类型由 `ModuleMap.java` 定义，"
        "`ArchitectureTest` 与 `TableOwnershipTest` 强制。本文只是它的视图。\n",
        "| 模块 | 职责 | 包后缀 | 拥有的表 | 对外发布类型 |",
        "|---|---|---|---|---|",
    ]
    for module_id, name, package_suffix, tables in modules:
        owned = "、".join(f"`{t}`" for t in tables) if tables else "—"
        exposed = "、".join(f"`{t}`" for t in published.get(module_id, [])) or "—"
        lines.append(
            f"| {module_id} | {cell(name)} | `.{package_suffix}` | {cell(owned)} | {cell(exposed)} |"
        )
    lines.append("")
    lines.append(
        "包结构、依赖规则与它们的强制强度见 [`rules.md`](rules.md)；"
        "每个模块为什么这样切见 [`modules/`](modules/)。\n"
    )
    return "\n".join(lines)


# --------------------------------------------------------------------------
# docs/current/limits.md  <- IngestLimits.java / BlobSettings.java
# --------------------------------------------------------------------------

LIMIT_RE = re.compile(r"public static final (?:int|long) (\w+)\s*=\s*([^;]+);")


def evaluate(expression: str) -> int | None:
    """Evaluate the simple integer arithmetic these constants use."""
    cleaned = expression.replace("L", "").replace("_", "").strip()
    if not re.fullmatch(r"[\d\s*+()-]+", cleaned):
        return None
    try:
        return int(eval(cleaned, {"__builtins__": {}}, {}))  # noqa: S307
    except Exception:  # noqa: BLE001
        return None


def human(value: int | None) -> str:
    if value is None:
        return "—"
    if value >= 1024 * 1024 and value % (1024 * 1024) == 0:
        return f"{value // (1024 * 1024)} MiB"
    if value >= 1024 and value % 1024 == 0:
        return f"{value // 1024} KiB"
    return str(value)


def gen_limits() -> str:
    source = SERVER / "modules/ingest/IngestLimits.java"
    text = read(source)
    found = LIMIT_RE.findall(text)
    if len(found) < 4:
        fail(f"IngestLimits 只解析到 {len(found)} 个常量")

    lines = [
        HEADER.format(
            source="skillmaster-server/src/main/java/com/skillmasterai/modules/ingest/"
            "IngestLimits.java；skillmaster-server/src/main/java/com/skillmasterai/"
            "modules/blob/BlobSettings.java"
        ),
        "# 上传与对象存储的上限（生成）\n",
        "**服务端**以这些常量为准。CLI 与网页各自保留了一份同值或接近的常量（不由这里读取，"
        "控制台还有一句面向用户的文案）；改这里的值时，要一起检查客户端，否则它会先于服务端"
        "拦下或放过。其余常量与下面的分流阈值只有服务端有。\n",
        "| 常量 | 原始表达式 | 字节 | 说人话 |",
        "|---|---|---|---|",
    ]
    for name, expression in found:
        value = evaluate(expression)
        if value is None:
            # A row of `—` looks like a parsed fact. It is a parse failure.
            fail(f"无法求值 IngestLimits.{name} = {expression.strip()}")
        lines.append(
            f"| `IngestLimits.{name}` | `{expression.strip()}` | "
            f"{value} | {human(value)} |"
        )

    blob = read(SERVER / "modules/blob/BlobSettings.java")
    match = re.search(
        r"new BlobSettings\([^;]*?"
        r"(1024L\s*\*\s*1024L|\d+L\s*\*\s*1024L\s*\*\s*1024L)\s*,\s*"
        r"(\d+L\s*\*\s*1024L\s*\*\s*1024L)\s*,\s*\"\"\)",
        blob,
        re.S,
    )
    lines.append("")
    lines.append("## 对象存储的分流阈值\n")
    lines.append(
        "`BlobSettings.defaults()` 决定「大于多少字节进对象存储」与「单个 blob 的上限」。"
        "部署可以用配置覆盖它，默认值如下：\n"
    )
    if match:
        threshold = evaluate(match.group(1))
        ceiling = evaluate(match.group(2))
        lines.append("| 项 | 默认 |")
        lines.append("|---|---|")
        lines.append(f"| 进对象存储的阈值 | {threshold} 字节（{human(threshold)}） |")
        lines.append(f"| 单个 blob 上限 | {ceiling} 字节（{human(ceiling)}） |")
    else:
        fail("BlobSettings.defaults() 的形状变了，解析不到两个默认值")
    lines.append("")
    return "\n".join(lines)


# --------------------------------------------------------------------------
# docs/current/errors.md  <- ErrorCode.java
# --------------------------------------------------------------------------

# The last enumerator ends with `;`, not `,`. Missing it silently dropped
# INTERNAL_ERROR from errors.md (found in the v40 audit).
ERROR_RE = re.compile(r'^\s{4}([A-Z][A-Z0-9_]*)\("([^"]+)"\)\s*[,;]?\s*$', re.M)
ENUM_RE = re.compile(r'^\s{4}[A-Z][A-Z0-9_]*\("', re.M)


def gen_errors() -> str:
    source = SERVER / "common/ErrorCode.java"
    text = read(source)
    found = ERROR_RE.findall(text)
    # Exact, not "> 20": a parser that drops the last constant still produces a
    # plausible-looking table, and `--check` then blesses the same omission.
    expected = len(ENUM_RE.findall(text))
    if len(found) != expected:
        fail(f"ErrorCode 解析到 {len(found)} 条，源文件里有 {expected} 个枚举常量")

    lines = [
        HEADER.format(
            source="skillmaster-server/src/main/java/com/skillmasterai/common/ErrorCode.java"
        ),
        "# 错误码（生成）\n",
        "线上的稳定标识是**引号里那串**；Java 常量名不是契约，改名不会改线上值。"
        "HTTP 状态码由各接口的异常处理器决定（如 `ApiExceptionHandler`、`UploadExceptionAdvice`、"
        "`SecretExceptionAdvice`），本文只列线上名字。\n",
        "| 常量 | 线上值 |",
        "|---|---|",
    ]
    for constant, wire in found:
        lines.append(f"| `{constant}` | `{cell(wire)}` |")
    lines.append("")
    return "\n".join(lines)


# --------------------------------------------------------------------------
# docs/decisions/README.md  <- decisions/NNNN-*.md
# --------------------------------------------------------------------------

ADR_TITLE_RE = re.compile(r"^# (?:ADR )?(\d{4}) · (.+)$")
ADR_STATUS_RE = re.compile(r"^[-|>]\s*(?:\*\*)?状态(?:\*\*)?[：:]\s*(.+)$")
ADR_DATE_RE = re.compile(r"^[-|>]\s*(?:\*\*)?日期(?:\*\*)?[：:]\s*(\S+)")


def parse_adr(path: Path) -> tuple[str, str, str, str]:
    lines = path.read_text(encoding="utf-8").splitlines()[:12]
    number = title = ""
    status = date = "（未标注）"
    for line in lines:
        if m := ADR_TITLE_RE.match(line):
            number, title = m.group(1), m.group(2)
        elif m := ADR_STATUS_RE.match(line):
            status = m.group(1).strip()
        elif m := ADR_DATE_RE.match(line):
            date = m.group(1).strip()
    if not number or not title:
        fail(f"{path.name} 的第一行不是 `# NNNN · 标题`")
    if date == "（未标注）":
        # Some ADRs fold the date into the status line: `已采纳（2026-10-30）`.
        # Read it from there rather than reporting a date that is plainly present.
        if m := re.search(r"(\d{4}-\d{2}-\d{2})", status):
            date = m.group(1)
    return number, title, status, date


def gen_decisions() -> str:
    files = sorted((DOCS / "decisions").glob("[0-9][0-9][0-9][0-9]-*.md"))
    if len(files) < 100:
        fail(f"只找到 {len(files)} 条 ADR")

    rows = [parse_adr(p) for p in files]
    lines = [
        HEADER.format(source="docs/decisions/NNNN-*.md 的文件头"),
        "# 决策记录（ADR）\n",
        "决策跨版本存活。要推翻某条，新增一条「取代 000N」，不改旧文正文。"
        "**这份索引是生成物**：状态取自每个文件头，不在这里另写一份。\n",
        f"共 {len(rows)} 条。\n",
        "| 编号 | 决策 | 状态 | 日期 |",
        "|---|---|---|---|",
    ]
    for number, title, status, date in rows:
        lines.append(
            f"| [{number}]({number}-{_slug_of(number, files)}) | {cell(title)} | {cell(status)} | {cell(date)} |"
        )
    lines.append("")
    return "\n".join(lines)


def _slug_of(number: str, files: list[Path]) -> str:
    for path in files:
        if path.name.startswith(number + "-"):
            return path.name[len(number) + 1 :]
    fail(f"找不到 ADR {number}")


# --------------------------------------------------------------------------
# docs/versions/README.md  <- versions/*/README.md
# --------------------------------------------------------------------------

VERSION_STATUS_RE = re.compile(r"^\s*>\s*\*\*状态\*\*[：:]\s*(.+)$")


LINK_RE = re.compile(r"\[([^\]]+)\]\([^)]+\)")


def strip_links(text: str) -> str:
    """Keep the words of a link, drop the target.

    A status line is written in its own version folder, so a link inside it
    resolves from there; pasted into the index it would resolve from
    docs/versions/ and 404. The index carries the sentence, the folder keeps the
    link.
    """
    return LINK_RE.sub(r"\1", text)


def summarize(status: str, limit: int = 60) -> str:
    """One line for the index: the first clause, with its parenthesis closed.

    Cutting at the first `。` is right for a long status, but when that sentence
    opens a parenthesis the cut leaves `进行中…` — the one phrase that says
    nothing. Extend to the matching `）` first, then truncate.
    """
    text = re.split(r"[。；]", status)[0]
    if text.count("（") > text.count("）"):
        depth = 0
        for position, char in enumerate(status):
            if char == "（":
                depth += 1
            elif char == "）":
                depth -= 1
                if depth == 0:
                    text = status[: position + 1]
                    break
    if text.count("（") > text.count("）"):
        # An unclosed parenthesis (v23's status line is one) would reduce the
        # whole line to "进行中…". Cut at the last clause boundary instead.
        head = text[:limit]
        for boundary in "。；、，： ":
            cut = head.rfind(boundary)
            if cut > limit // 2:
                text = head[:cut].rstrip("。；、，： ") + "…"
                break
        else:
            text = text[: text.rindex("（")].rstrip() + "…"
    if len(text) > limit:
        head = text[:limit]
        for boundary in "。；、，： ":
            cut = head.rfind(boundary)
            if cut > limit // 2:
                head = head[:cut]
                break
        head = head.rstrip("。；、，： ")
        # Close an open parenthesis rather than leaving a dangling "（".
        if head.count("（") > head.count("）"):
            head += "）"
        text = head + "…"
    # A cut inside `code` leaves one backtick and breaks the cell — on every
    # path, including the short one and the unclosed-parenthesis fallback.
    if text.count("`") % 2:
        text = text[: text.rindex("`")].rstrip() + "…"
    return text


def gen_versions() -> str:
    dirs = sorted(
        (
            p
            for p in (DOCS / "versions").iterdir()
            if p.is_dir() and p.name.startswith("v")
        ),
        key=lambda p: int(re.match(r"v(\d+)", p.name).group(1)),
    )
    if len(dirs) < 30:
        fail(f"只找到 {len(dirs)} 个版本目录")

    lines = [
        HEADER.format(source="docs/versions/*/README.md"),
        "# 版本记录\n",
        "v1–v39 是**历史记录**：正文冻结，不再修改。"
        "v40 起只写「相对 [`current/`](../current/README.md) 的 delta」，"
        "不重述系统现状。\n",
        "| 版本 | 状态 |",
        "|---|---|",
    ]
    for directory in dirs:
        readme = directory / "README.md"
        status = "（未标注）"
        # The whole lead block, not the first ten lines: a longer header used to
        # degrade the index entry to 「未标注」.
        for line in (
            readme.read_text(encoding="utf-8").split("\n## ", 1)[0].splitlines()
        ):
            if m := VERSION_STATUS_RE.match(line):
                status = summarize(strip_links(m.group(1).strip()))
                break
        title = directory.name
        lines.append(f"| [`{title}`]({directory.name}/README.md) | {cell(status)} |")
    lines.append("")
    return "\n".join(lines)


# --------------------------------------------------------------------------
# docs/README.md  <- the tree
# --------------------------------------------------------------------------

AXES = [
    ("current", "现状", "系统现在长什么样：模块、边界、契约、身份"),
    ("versions", "变更记录", "每一版改了什么；v40 起写 delta"),
    ("decisions", "决策", "当时为什么这么选；正文只增不改"),
    ("research", "调研", "竞品与行业事实，长期参考"),
    ("target", "目标态", "远期完整形态，与现状的差异另有一份清单"),
]


def gen_root_readme() -> str:
    lines = [
        HEADER.format(source="docs/ 目录结构"),
        "# 文档索引\n",
        "**一个事实只有一个家。** 这份索引是生成物——手写索引会停在几个版本前，"
        "这里不重复那个错误。规则见 "
        "[`docs-architecture`](../.claude/skills/docs-architecture/SKILL.md) 与 "
        "[`code-writing`](https://console.skillmasterai.com/skills/lijinzhao/code-writing)。\n",
        "## 五个轴\n",
        "| 轴 | 目录 | 是什么 |",
        "|---|---|---|",
    ]
    for directory, name, description in AXES:
        lines.append(
            f"| {name} | [`{directory}/`]({directory}/) | {cell(description)} |"
        )

    versions = [
        p
        for p in (DOCS / "versions").iterdir()
        if p.is_dir() and p.name.startswith("v")
    ]
    adrs = list((DOCS / "decisions").glob("[0-9][0-9][0-9][0-9]-*.md"))
    lines.append("")
    lines.append("## 规模\n")
    lines.append(f"- 版本：{len(versions)} 个（索引见 [`versions/`](versions/)）")
    lines.append(f"- 决策：{len(adrs)} 条（索引见 [`decisions/`](decisions/)）")
    lines.append("")
    lines.append("## 阅读顺序\n")
    lines.append("1. [`current/`](current/) —— 系统现在是什么")
    lines.append("2. [`versions/`](versions/) —— 最近一版改了什么")
    lines.append("3. [`decisions/`](decisions/) —— 为什么这么定")
    lines.append("4. [`target/`](target/README.md) —— 先读那份差异清单")
    lines.append("")
    return "\n".join(lines)


# --------------------------------------------------------------------------


# --------------------------------------------------------------------------
# docs/current/schema.md  <- Flyway migrations
# --------------------------------------------------------------------------

MIGRATION_RE = re.compile(r"V(\d+)__")
CREATE_RE = re.compile(
    r"CREATE TABLE\s+(?:IF NOT EXISTS\s+)?([a-z_][a-z0-9_]*)\s*\((.*?)\);",
    re.S | re.I,
)
ADD_RE = re.compile(
    r"ALTER TABLE\s+([a-z_][a-z0-9_]*)\s+ADD COLUMN\s+(?:IF NOT EXISTS\s+)?"
    r"([a-z_][a-z0-9_]*)\s+([^;]+);",
    re.I,
)
DROP_RE = re.compile(r"DROP TABLE\s+(?:IF EXISTS\s+)?([a-z_][a-z0-9_]*)\s*;", re.I)
DROP_COLUMN_RE = re.compile(
    r"ALTER TABLE\s+([a-z_][a-z0-9_]*)\s+DROP COLUMN\s+(?:IF EXISTS\s+)?"
    r"([a-z_][a-z0-9_]*)\s*;",
    re.I,
)
RENAME_RE = re.compile(
    r"ALTER TABLE\s+([a-z_][a-z0-9_]*)\s+RENAME COLUMN\s+"
    r"([a-z_][a-z0-9_]*)\s+TO\s+([a-z_][a-z0-9_]*);",
    re.I,
)
RENAME_TABLE_RE = re.compile(
    r"ALTER TABLE\s+([a-z_][a-z0-9_]*)\s+RENAME TO\s+([a-z_][a-z0-9_]*);",
    re.I,
)
NON_COLUMN = ("CONSTRAINT", "PRIMARY KEY", "UNIQUE", "FOREIGN KEY", "CHECK", "LIKE")


def split_definitions(body: str) -> list[str]:
    """Split a CREATE TABLE body on commas that are not inside parentheses."""
    parts: list[str] = []
    depth = 0
    start = 0
    for index, char in enumerate(body):
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        elif char == "," and depth == 0:
            parts.append(body[start:index])
            start = index + 1
    parts.append(body[start:])
    return parts


def gen_schema() -> str:
    directory = ROOT / "skillmaster-server/src/main/resources/db/migration"
    files = sorted(
        directory.glob("V*__*.sql"),
        key=lambda p: int(MIGRATION_RE.match(p.name).group(1)),
    )
    if len(files) < 20:
        fail(f"只找到 {len(files)} 个迁移文件")

    tables: dict[str, list[tuple[str, str]]] = {}
    order: list[str] = []

    def ensure(name: str) -> None:
        if name not in tables:
            tables[name] = []
            order.append(name)

    for path in files:
        text = path.read_text(encoding="utf-8")
        # A commented-out DDL is not a fact. Strip `--` to end of line first.
        text = re.sub(r"--[^\n]*", "", text)
        # Each form has to be recognised exactly as often as it appears: a new
        # spelling (`IF NOT EXISTS`, `RENAME TO`) used to slip through silently.
        for pattern, regex, label in (
            (r"CREATE TABLE\b", CREATE_RE, "CREATE TABLE"),
            (r"ADD COLUMN\b", ADD_RE, "ADD COLUMN"),
            (r"DROP COLUMN\b", DROP_COLUMN_RE, "DROP COLUMN"),
            (r"DROP TABLE\b", DROP_RE, "DROP TABLE"),
            (r"RENAME COLUMN\b", RENAME_RE, "RENAME COLUMN"),
            (r"RENAME TO\b", RENAME_TABLE_RE, "RENAME TO"),
        ):
            expected = len(re.findall(pattern, text, re.I))
            found = len(regex.findall(text))
            if found != expected:
                fail(f"{path.name}：{label} 出现 {expected} 次，只解析到 {found} 次")
        for name, body in CREATE_RE.findall(text):
            ensure(name)
            for definition in split_definitions(body):
                # A CHECK constraint spans lines and contains commas of its own; splitting by line
                # used to turn its `OR ...` continuation into columns named `OR` and `AND`.
                line = " ".join(definition.split())
                if not line or line.upper().startswith(NON_COLUMN):
                    continue
                m = re.match(r"([a-z_][a-z0-9_]*)\s+(.+)", line, re.I)
                if m:
                    tables[name].append((m.group(1), m.group(2)))
        for name, column, rest in ADD_RE.findall(text):
            ensure(name)
            tables[name].append((column, " ".join(rest.split())))
        for name, old, new in RENAME_RE.findall(text):
            ensure(name)
            tables[name] = [(new if c == old else c, d) for c, d in tables[name]]
        for name, column in DROP_COLUMN_RE.findall(text):
            ensure(name)
            tables[name] = [(c, d) for c, d in tables[name] if c != column]
        for old_name, new_name in RENAME_TABLE_RE.findall(text):
            ensure(old_name)
            tables[new_name] = tables.pop(old_name)
            order[order.index(old_name)] = new_name
        # A table dropped by a later migration is not part of the current schema.
        for name in DROP_RE.findall(text):
            tables.pop(name, None)
            if name in order:
                order.remove(name)

    if len(tables) < 25:
        fail(f"只解析到 {len(tables)} 张表，迁移脚本的形状可能变了")

    owner = {
        table: module_id
        for module_id, _, _, owned in parse_modules()
        for table in owned
    }
    unknown = [t for t in order if t not in owner]

    lines = [
        HEADER.format(
            source="skillmaster-server/src/main/resources/db/migration/V*.sql"
        ),
        "# 表结构（生成）\n",
        "表与列来自迁移脚本的 `CREATE TABLE`、`ADD COLUMN`、`DROP COLUMN`、`RENAME COLUMN`、`RENAME TO`、`DROP TABLE`，按版本号顺序应用。"
        "**索引、约束、触发器不在本文**——它们在迁移脚本里，这里只回答「有哪些表、各有哪些列」。\n",
        "| 表 | 模块 |",
        "|---|---|",
    ]
    for name in order:
        lines.append(f"| `{name}` | {cell(owner.get(name, '—'))} |")
    lines.append("")
    for name in order:
        lines.append(f"## `{name}`\n")
        for column, definition in tables[name]:
            lines.append(f"- `{column}` — {definition}")
        lines.append("")
    if unknown:
        lines.append(
            "> **`ModuleMap` 没有认领的表**："
            + "、".join(f"`{t}`" for t in unknown)
            + "。要么补进 `ModuleMap`，要么它是迁移脚本的遗留。\n"
        )
    return "\n".join(lines)


# --------------------------------------------------------------------------
# docs/current/cli.md  <- help.Spec declarations
# --------------------------------------------------------------------------

PATH_RE = re.compile(r'Path:\s*"([^"]*)"')
SUMMARY_RE = re.compile(r'Summary:\s*"([^"]*)"')
USAGE_AT_RE = re.compile(r"Usage:\s*")
STRING_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')


def usage_of(chunk: str) -> str:
    """A Usage may be several adjacent Go string literals joined by `+`.

    Reading only the first one truncated three commands mid-flag
    (`skill grep`, `skill write`, `skill edit`) — found in the v40 audit.
    """
    at = USAGE_AT_RE.search(chunk)
    if not at:
        return ""
    parts: list[str] = []
    rest = chunk[at.end() :]
    pos = 0
    while literal := STRING_RE.match(rest, pos):
        parts.append(literal.group(1))
        pos = literal.end()
        plus = re.match(r"\s*\+\s*", rest[pos:])
        if not plus:
            break
        pos += plus.end()
    return "".join(parts)


def cell(text: str) -> str:
    """A literal `|` inside a table cell ends the cell in GFM."""
    return text.replace("|", r"\|")


def gen_cli() -> str:
    directory = ROOT / "skillmaster-cli/cmd/skillmaster"
    rows: dict[str, tuple[str, str]] = {}
    declared = 0
    for path in sorted(directory.glob("declarations*.go")):
        if path.name.endswith("_test.go"):
            continue  # a fixture's `Path:` is not a command
        text = path.read_text(encoding="utf-8")
        declared += len(PATH_RE.findall(text))
        # One spec runs from its `Path:` to the next one; Summary and Usage are
        # read from that slice so two neighbouring commands cannot be mixed up.
        for chunk in re.split(r'(?=Path:\s*")', text):
            m = PATH_RE.search(chunk)
            if not m:
                continue
            summary = SUMMARY_RE.search(chunk)
            rows[m.group(1)] = (
                summary.group(1) if summary else "",
                usage_of(chunk),
            )

    if len(rows) < 40:
        fail(f"只解析到 {len(rows)} 条命令，declarations 的形状可能变了")
    # A duplicate `Path:` overwrites silently; the count is what catches it.
    if len(rows) != declared:
        fail(
            f"有 {declared} 个 Path:，只留下 {len(rows)} 条命令（可能有重复的命令路径）"
        )

    lines = [
        HEADER.format(source="skillmaster-cli/cmd/skillmaster/declarations*.go"),
        "# CLI 语法（生成）\n",
        "命令路径、一句话与用法来自 `help.Spec`——与 `skmr help` 是同一份源，所以两者不可能不一致。"
        "退出码是公开契约（[ADR 0087](../decisions/0087-cli-exit-codes-are-a-public-contract.md)），"
        "参数细节用 `skmr <命令> --help`。\n",
        "| 命令 | 一句话 | 用法 |",
        "|---|---|---|",
    ]
    for command in sorted(rows):
        summary, usage = rows[command]
        label = f"`skmr {command}`" if command else "`skmr`"
        shown = f"`{usage}`" if usage else "—"
        lines.append(f"| {label} | {cell(summary)} | {cell(shown)} |")
    lines.append("")
    return "\n".join(lines)


# --------------------------------------------------------------------------
# docs/current/config.md  <- application.yml
# --------------------------------------------------------------------------

ENV_NAME_RE = re.compile(r"\$\{([A-Z][A-Z0-9_]*)")


def outer_env(value: str) -> list[str]:
    """Only the outermost `${NAME:...}` is a deployment knob.

    `${SKILLMASTER_DB_USER:${USER}}` has a nested OS variable in its default;
    listing `USER` as a configurable name (the first version did) is wrong.
    """
    names: list[str] = []
    depth = 0
    i = 0
    while i < len(value):
        if value.startswith("${", i):
            if depth == 0 and (m := ENV_NAME_RE.match(value, i)):
                names.append(m.group(1))
            depth += 1
            i += 2
        elif value.startswith("}", i) and depth:
            depth -= 1
            i += 1
        else:
            i += 1
    return names


def gen_config() -> str:
    source = ROOT / "skillmaster-server/src/main/resources/application.yml"
    entries: list[tuple[str, str]] = []
    stack: list[tuple[int, str]] = []
    env: set[str] = set()

    for raw in read(source).splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        line = raw.strip()
        if ":" not in line:
            continue
        key, _, value = line.partition(":")
        key = key.strip().strip('"')
        value = value.strip()
        while stack and stack[-1][0] >= indent:
            stack.pop()
        full = ".".join([k for _, k in stack] + [key])
        env.update(outer_env(value))
        if value:
            entries.append((full, value))
        else:
            stack.append((indent, key))

    if len(entries) < 10:
        fail(f"只解析到 {len(entries)} 个配置项")

    lines = [
        HEADER.format(source="skillmaster-server/src/main/resources/application.yml"),
        "# 配置项（生成）\n",
        "服务端的配置键与默认值（**仅 main profile**；测试 profile 的键不在这里）。"
        "`${NAME:default}` 表示可以用环境变量覆盖，冒号后面是没给时的值。"
        "**部署时真正要设哪些，见部署目录的说明**——这里只列代码认得哪些。\n",
        "| 键 | 默认值 |",
        "|---|---|",
    ]
    for key, value in entries:
        lines.append(f"| `{key}` | `{cell(value)}` |")
    if env:
        lines.append("")
        lines.append("## 可用的环境变量\n")
        lines.append("、".join(f"`{name}`" for name in sorted(env)))
        lines.append("")
    return "\n".join(lines)


GENERATORS = {
    DOCS / "current/system.md": gen_system,
    DOCS / "current/limits.md": gen_limits,
    DOCS / "current/errors.md": gen_errors,
    DOCS / "current/schema.md": gen_schema,
    DOCS / "current/cli.md": gen_cli,
    DOCS / "current/config.md": gen_config,
    DOCS / "decisions/README.md": gen_decisions,
    DOCS / "versions/README.md": gen_versions,
    DOCS / "README.md": gen_root_readme,
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check", action="store_true", help="compare only, write nothing"
    )
    args = parser.parse_args()

    drift = []
    for path, generate in GENERATORS.items():
        content = generate()
        if args.check:
            current = path.read_text(encoding="utf-8") if path.is_file() else ""
            if current != content:
                drift.append(path.relative_to(ROOT))
        else:
            write(path, content)

    if args.check and drift:
        for path in drift:
            print(f"generate: {path} 与源不一致", file=sys.stderr)
        return 1
    if args.check:
        print("generate: 生成物与源一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

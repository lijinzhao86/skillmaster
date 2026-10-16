"""Tests for the docs-architecture validator.

The validator is a standalone script rather than an importable module, so it is
loaded by path from the directory above. Each test builds a synthetic docs/ tree;
most of these cases come from defects found in the validator's first audit.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path
from types import ModuleType

import pytest


def _find_checker(start: Path) -> Path:
    """Walk up to the directory holding the validator, and return it.

    Deliberately not a fixed number of parents: this file's depth below the
    validator is a layout detail, and hardcoding it broke the moment the tests moved.
    """
    for candidate in (start, *start.parents):
        checker = candidate / "check_docs.py"
        if checker.is_file():
            return checker
    raise RuntimeError(f"cannot locate check_docs.py above {start}")


CHECKER_PATH = _find_checker(Path(__file__).resolve())

ADR_OK = "# 0001\n\n## 背景\n\n## 决定\n\n## 理由\n\n## 后果\n"

GOOD_README = """# v1-x

> **状态**：进行中

## 目标
见 [prd.md](prd.md)。
## 这一版明确不做什么
见 [prd.md](prd.md)。
## 验收标准
见 [prd.md](prd.md)。
## 与目标态的关系
见 [../../target/README.md](../../target/README.md)。
"""

GOOD_PRD = "# prd\n\n## 用户与场景\n\n## 范围\n\n## 验收与指标\n"
GOOD_TD = "# td\n\n## 架构\n\n## 数据模型\n\n## 接口\n\n## 分期\n\n## 开放问题\n"
GOOD_PLAN = "# plan\n\n## 验收映射\n\n## 用例\n\n## 结果\n"


def _load_checker() -> ModuleType:
    spec = importlib.util.spec_from_file_location("check_docs", CHECKER_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load validator from {CHECKER_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


check_docs = _load_checker()


def build_version(
    root: Path,
    name: str = "v1-x",
    *,
    readme: str = GOOD_README,
    prd: str | None = GOOD_PRD,
    td: str | None = GOOD_TD,
    plan: str | None = GOOD_PLAN,
    make_iterations: bool = True,
) -> Path:
    """Create a version folder; pass None for a document to omit it."""
    version_dir = root / "versions" / name
    version_dir.mkdir(parents=True)
    (version_dir / "README.md").write_text(readme, encoding="utf-8")
    for filename, content in (
        ("prd.md", prd),
        ("technical-design.md", td),
        ("test-plan.md", plan),
    ):
        if content is not None:
            (version_dir / filename).write_text(content, encoding="utf-8")
    if make_iterations:
        iterations = version_dir / "iterations"
        iterations.mkdir()
        (iterations / "0001-first.md").write_text("# 0001\n", encoding="utf-8")
    return version_dir


def build_docs(root: Path, **version_kwargs: object) -> Path:
    """Create a minimal but complete docs/ tree, so every fixture link resolves."""
    docs = root / "docs"
    build_version(docs, **version_kwargs)
    (docs / "target").mkdir(parents=True)
    (docs / "target" / "README.md").write_text("# target\n", encoding="utf-8")
    (docs / "decisions").mkdir()
    (docs / "decisions" / "0001-ok.md").write_text(ADR_OK, encoding="utf-8")
    arch = docs / "architecture"
    arch.mkdir()
    (arch / "README.md").write_text("# architecture\n", encoding="utf-8")
    return docs


def test_clean_tree_passes(tmp_path: Path) -> None:
    docs = build_docs(tmp_path)

    assert check_docs.check_version(docs / "versions" / "v1-x", "v1-x") == []
    assert check_docs.check_links(docs)[1] == []


def test_missing_documents_are_reported(tmp_path: Path) -> None:
    docs = tmp_path / "docs"
    version_dir = build_version(docs, prd=None, plan=None, make_iterations=False)

    problems = check_docs.check_version(version_dir, "v1-x")

    assert "v1-x/prd.md 缺失" in problems
    assert "v1-x/test-plan.md 缺失" in problems


def test_a_version_without_iteration_records_passes(tmp_path: Path) -> None:
    """`iterations/` is optional: a version that has never made a small-version
    change has no records, and git cannot carry the empty directory — so requiring
    it failed a fresh clone (2026-10-16)."""
    docs = tmp_path / "docs"
    version_dir = build_version(docs, make_iterations=False)

    assert check_docs.check_version(version_dir, "v1-x") == []


def test_link_with_commonmark_title_resolves(tmp_path: Path) -> None:
    """`[x](y.md "Title")` is valid CommonMark and must not read as a dead link."""
    docs = build_docs(tmp_path)
    (docs / "versions" / "v1-x" / "test-plan.md").write_text(
        GOOD_PLAN + '\n见 [技术设计](technical-design.md "设计稿")\n', encoding="utf-8"
    )

    assert check_docs.check_links(docs)[1] == []


def test_angle_bracket_link_resolves(tmp_path: Path) -> None:
    docs = build_docs(tmp_path)
    (docs / "versions" / "v1-x" / "test-plan.md").write_text(
        GOOD_PLAN + "\n[td](<technical-design.md>)\n", encoding="utf-8"
    )

    assert check_docs.check_links(docs)[1] == []


def test_angle_bracket_link_with_title_resolves(tmp_path: Path) -> None:
    """`[x](<y.md> "Title")` — the title comes off first, then the brackets."""
    docs = build_docs(tmp_path)
    (docs / "versions" / "v1-x" / "test-plan.md").write_text(
        GOOD_PLAN + '\n[td](<technical-design.md> "设计稿")\n', encoding="utf-8"
    )

    assert check_docs.check_links(docs)[1] == []


def test_paren_title_resolves(tmp_path: Path) -> None:
    docs = build_docs(tmp_path)
    (docs / "versions" / "v1-x" / "test-plan.md").write_text(
        GOOD_PLAN + "\n[td](technical-design.md (设计稿))\n", encoding="utf-8"
    )

    assert check_docs.check_links(docs)[1] == []


def test_link_inside_a_fence_is_ignored(tmp_path: Path) -> None:
    """A documentation sample may link anywhere without failing the build."""
    docs = build_docs(tmp_path)
    (docs / "versions" / "v1-x" / "notes.md").write_text(
        "# notes\n\n```markdown\n[example](does-not-exist.md)\n```\n", encoding="utf-8"
    )

    assert check_docs.check_links(docs)[1] == []


def test_dead_link_is_reported(tmp_path: Path) -> None:
    docs = build_docs(tmp_path)
    (docs / "versions" / "v1-x" / "test-plan.md").write_text(
        GOOD_PLAN + "\n[gone](nowhere.md)\n", encoding="utf-8"
    )

    broken = check_docs.check_links(docs)[1]

    assert len(broken) == 1
    assert "nowhere.md" in broken[0]


def test_directory_named_like_adr_is_reported_not_crash(tmp_path: Path) -> None:
    decisions = tmp_path / "docs" / "decisions"
    (decisions / "0001-impostor.md").mkdir(parents=True)

    problems = check_docs.check_decisions(str(decisions))

    assert any("0001-impostor.md" in problem for problem in problems)


def test_stray_directory_in_version_folder_is_reported(tmp_path: Path) -> None:
    docs = tmp_path / "docs"
    version_dir = build_version(docs)
    (version_dir / "drafts").mkdir()

    problems = check_docs.check_version(version_dir, "v1-x")

    assert any("drafts/" in problem for problem in problems)


def test_adr_sections_inside_a_fence_do_not_count(tmp_path: Path) -> None:
    """An ADR documenting the format in a fenced example must still define its own sections."""
    decisions = tmp_path / "docs" / "decisions"
    decisions.mkdir(parents=True)
    (decisions / "0001-fenced.md").write_text(
        "# 0001\n\n模板长这样：\n\n```markdown\n## 背景\n## 决定\n## 理由\n## 后果\n```\n",
        encoding="utf-8",
    )

    problems = check_docs.check_decisions(str(decisions))

    assert any("## 背景" in problem for problem in problems)


def test_heading_inside_unterminated_fence_does_not_satisfy_topic(
    tmp_path: Path,
) -> None:
    version_dir = tmp_path / "docs" / "versions" / "v1-x"
    version_dir.mkdir(parents=True)
    path = version_dir / "README.md"
    path.write_text(
        "# v1\n\n> **状态**：x\n\n## 目标\n\n```python\n## 验收标准\n", encoding="utf-8"
    )

    missing = check_docs.check_topics(str(path), "README.md")

    assert "验收" in missing


def test_longer_fence_contains_shorter_fence(tmp_path: Path) -> None:
    """A ```` block wrapping a ``` block must not end at the inner fence.

    Distinguishes fence char tracking from fence *length* tracking: a char-only
    implementation closes the outer fence at the inner ``` and then reads the
    following heading as real.
    """
    version_dir = tmp_path / "docs" / "versions" / "v1-x"
    version_dir.mkdir(parents=True)
    path = version_dir / "README.md"
    path.write_text(
        "# v1\n\n> **状态**：x\n\n## 目标\n\n````markdown\n```\n## 验收标准\n```\n````\n",
        encoding="utf-8",
    )

    missing = check_docs.check_topics(str(path), "README.md")

    assert "验收" in missing


def test_status_in_lead_block_satisfies_topic(tmp_path: Path) -> None:
    docs = tmp_path / "docs"
    version_dir = build_version(docs)

    missing = check_docs.check_topics(str(version_dir / "README.md"), "README.md")

    assert "状态" not in missing


def test_non_utf8_file_does_not_crash(tmp_path: Path) -> None:
    docs = build_docs(tmp_path)
    (docs / "versions" / "v1-x" / "notes.md").write_bytes(b"# caf\xe9 latin-1\n")

    assert check_docs.check_links(docs)[1] == []


def test_iteration_naming(tmp_path: Path) -> None:
    docs = tmp_path / "docs"
    version_dir = build_version(docs)
    (version_dir / "iterations" / "2-bad.md").write_text("# x\n", encoding="utf-8")

    problems = check_docs.check_version(version_dir, "v1-x")

    assert any("2-bad.md" in problem for problem in problems)


def test_non_ascii_iteration_slug_is_allowed(tmp_path: Path) -> None:
    docs = tmp_path / "docs"
    version_dir = build_version(docs)
    (version_dir / "iterations" / "0002-中文分词.md").write_text(
        "# x\n", encoding="utf-8"
    )

    problems = check_docs.check_version(version_dir, "v1-x")

    assert not any("0002" in problem for problem in problems)


def test_end_to_end_exit_code(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    docs = build_docs(tmp_path)

    monkeypatch.setattr(sys, "argv", ["check_docs.py", "--docs", str(docs)])

    assert check_docs.main() == 0


def test_end_to_end_reports_failure(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    docs = build_docs(tmp_path, td=None)

    monkeypatch.setattr(sys, "argv", ["check_docs.py", "--docs", str(docs)])

    assert check_docs.main() == 1


def test_stray_file_under_versions_is_reported(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """versions/ holds version folders only — a loose file there is a violation."""
    docs = build_docs(tmp_path)
    (docs / "versions" / "notes.md").write_text("# stray\n", encoding="utf-8")

    monkeypatch.setattr(sys, "argv", ["check_docs.py", "--docs", str(docs)])

    assert check_docs.main() == 1


def test_decisions_dir_inside_version_reported_once(tmp_path: Path) -> None:
    docs = tmp_path / "docs"
    version_dir = build_version(docs)
    (version_dir / "decisions").mkdir()

    problems = check_docs.check_version(version_dir, "v1-x")

    assert len([p for p in problems if "decisions" in p]) == 1


def test_architecture_index_alone_is_enough(tmp_path: Path) -> None:
    """The optional parts are checked only when present.

    Requiring `rules.md` / `model.md` / `modules/` up front would demand a file
    before its content exists, which is how a stale copy of the version TD gets
    committed next to the thing it was copied from.
    """
    docs = build_docs(tmp_path)

    assert check_docs.check_architecture(docs) == []


def test_architecture_optional_parts_are_accepted_when_present(tmp_path: Path) -> None:
    docs = build_docs(tmp_path)
    arch = docs / "architecture"
    (arch / "rules.md").write_text("# rules\n", encoding="utf-8")
    (arch / "model.md").write_text("# model\n", encoding="utf-8")
    modules = arch / "modules"
    modules.mkdir()
    (modules / "M03-auth.md").write_text("# auth\n", encoding="utf-8")

    assert check_docs.check_architecture(docs) == []


def test_missing_architecture_dir_is_reported(tmp_path: Path) -> None:
    docs = tmp_path / "docs"
    docs.mkdir()

    problems = check_docs.check_architecture(docs)

    assert any("architecture" in problem for problem in problems)


def test_missing_architecture_readme_is_reported(tmp_path: Path) -> None:
    docs = build_docs(tmp_path)
    (docs / "architecture" / "README.md").unlink()

    problems = check_docs.check_architecture(docs)

    assert "architecture/README.md 缺失" in problems


def test_stray_file_in_architecture_is_reported(tmp_path: Path) -> None:
    """The architecture axis is a closed shape, like a version folder."""
    docs = build_docs(tmp_path)
    (docs / "architecture" / "notes.md").write_text("# stray\n", encoding="utf-8")

    problems = check_docs.check_architecture(docs)

    assert any("notes.md" in problem for problem in problems)


def test_stray_dir_in_architecture_is_reported(tmp_path: Path) -> None:
    docs = build_docs(tmp_path)
    (docs / "architecture" / "drafts").mkdir()

    problems = check_docs.check_architecture(docs)

    assert any("drafts/" in problem for problem in problems)


def test_module_doc_naming(tmp_path: Path) -> None:
    docs = build_docs(tmp_path)
    modules = docs / "architecture" / "modules"
    modules.mkdir()
    (modules / "03-auth.md").write_text("# x\n", encoding="utf-8")

    problems = check_docs.check_architecture(docs)

    assert any("03-auth.md" in problem for problem in problems)


def test_architecture_run_fails_end_to_end(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Architecture problems must reach the exit code, not just the helper."""
    docs = build_docs(tmp_path)
    (docs / "architecture" / "README.md").unlink()

    monkeypatch.setattr(sys, "argv", ["check_docs.py", "--docs", str(docs)])

    assert check_docs.main() == 1


def test_figure_in_architecture_is_accepted(tmp_path: Path) -> None:
    """Images are admitted as a class, not by filename.

    A figure carries no prose, so unlike a stray .md it cannot become a second
    authority on a fact — which is the only thing the closed shape prevents.
    """
    docs = build_docs(tmp_path)
    (docs / "architecture" / "model-diagram.svg").write_text("<svg/>", encoding="utf-8")

    assert check_docs.check_architecture(docs) == []


def test_figure_in_version_folder_is_accepted(tmp_path: Path) -> None:
    docs = tmp_path / "docs"
    version_dir = build_version(docs)
    (version_dir / "flow.png").write_bytes(b"\x89PNG\r\n")

    problems = check_docs.check_version(version_dir, "v1-x")

    assert not any("flow" in problem for problem in problems)


def test_figure_in_modules_is_accepted(tmp_path: Path) -> None:
    docs = build_docs(tmp_path)
    modules = docs / "architecture" / "modules"
    modules.mkdir()
    (modules / "M03-auth.md").write_text("# auth\n", encoding="utf-8")
    (modules / "M03-auth.svg").write_text("<svg/>", encoding="utf-8")

    assert check_docs.check_architecture(docs) == []


def test_non_image_file_extensions_are_still_reported(tmp_path: Path) -> None:
    """Only real image suffixes pass — the exemption must not become a hole."""
    docs = build_docs(tmp_path)
    (docs / "architecture" / "notes.txt").write_text("x", encoding="utf-8")

    problems = check_docs.check_architecture(docs)

    assert any("notes.txt" in problem for problem in problems)


def test_figure_link_resolves(tmp_path: Path) -> None:
    """The point of allowing the figure is that a document can embed it."""
    docs = build_docs(tmp_path)
    (docs / "architecture" / "model-diagram.svg").write_text("<svg/>", encoding="utf-8")
    (docs / "architecture" / "README.md").write_text(
        "# architecture\n\n![总览](model-diagram.svg)\n", encoding="utf-8"
    )

    assert check_docs.check_links(docs)[1] == []


def test_skill_root_is_the_directory_holding_skill_md() -> None:
    """Guards a silent failure, not a crash.

    The script lives in `scripts/` beneath the skill root. If a future move makes
    `skill_root()` point anywhere else, the skill's own links stop being checked —
    and the run still prints a green "0 条全部有效", so nothing else would notice.
    """
    assert (Path(check_docs.skill_root()) / "SKILL.md").is_file()

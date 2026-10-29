"""The CLI surface, one rung at a time.

Reads run against the fixture skill. The only write is a draft the role creates for itself — on
the *scratch* skill, never the fixture, because a discarded draft stays visible to `maintain` and
above and would move the row counts the console suite asserts.
"""

from __future__ import annotations

import re
import time

import pytest

from role_matrix import config
from role_matrix.expectations import CLI_READ_PROBES, at_least

RUNGS = config.RUNGS
PROBE_IDS = [probe.name for probe in CLI_READ_PROBES]
DRAFT_CODE = re.compile(r"@([A-Z0-9]{8})")


@pytest.mark.parametrize("rung", RUNGS)
@pytest.mark.parametrize("probe", CLI_READ_PROBES, ids=PROBE_IDS)
def test_probe_matches_the_ladder(rung, probe, runner, fixture_state):
    result = runner.run(rung, *probe.args(fixture_state))
    if probe.rung is None:
        assert not result.ok, result.describe()
        return
    allowed = at_least(rung, probe.rung)
    outcome = "allowed" if allowed else "refused"
    assert result.ok == allowed, f"{probe.name} must be {outcome} at {rung}: {result.describe()}"


@pytest.mark.parametrize("rung", RUNGS)
def test_publish_hands_off_to_the_version_page(rung, runner, fixture_state):
    """Publishing is a browser action for every credential, the owner's included (ADR 0031/0061).

    The last line is the v11 handoff (ADR 0074): the copyable command that lands on 操作, not on
    the version's default pane.
    """
    skill, draft = fixture_state["skill"], fixture_state["draft"]
    result = runner.run(rung, "version", "publish", f"{skill}@{draft}")
    assert not result.ok, result.describe()
    assert result.lines[-1] == (
        f"到那一版自己的页面上去做：skmr version open {skill}@{draft} --sub actions"
    )


@pytest.mark.parametrize("rung", RUNGS)
def test_a_role_can_manage_its_own_draft_from_write(rung, cfg, runner):
    """`write` and above submit a draft and rename, re-note and discard it; below, the first call
    is refused and the rest have nothing to address."""
    skill = cfg.scratch
    written = runner.run(
        rung,
        "skill",
        "write",
        f"{skill}@new",
        f"notes/role-{rung}.md",
        "--name",
        f"{rung} 的草稿",
        "--note",
        "角色矩阵探针",
        # Content addressing: the same bytes as an earlier run are refused as "already submitted",
        # so each run writes something it has not written before.
        stdin=f"# {rung} 的探针 {time.time_ns()}\n",
    )
    if not at_least(rung, "write"):
        assert not written.ok, written.describe()
        return
    assert written.ok, written.describe()

    match = DRAFT_CODE.search(written.text)
    assert match, written.describe()
    code = match.group(1)

    assert runner.run(rung, "version", "rename", f"{skill}@{code}", "--name", "改过的名字").ok
    assert runner.run(rung, "version", "note", f"{skill}@{code}", "--note", "改过的说明").ok
    assert runner.run(rung, "version", "discard", f"{skill}@{code}", "--yes").ok

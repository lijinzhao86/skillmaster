"""The console itself, in system Chrome: the version list and the publish handoff per rung.

Two screens carry the rung differences a person actually sees: the version list (v6 T35 — which
states are listed, which filters exist, whether "new version" is offered) and the handoff the CLI
opens (v11 E1 — the pane, the preselected bump, the release field, the publish button).

Screenshots land in `output/`, one pair per rung, for the human visual check that no assertion
can stand in for.
"""

from __future__ import annotations

import pytest

from role_matrix import config, expectations

RUNGS = config.RUNGS

pytestmark = pytest.mark.ui


@pytest.mark.parametrize("rung", RUNGS)
def test_the_version_list_shows_what_this_rung_may_see(rung, browser, cfg, fixture_state):
    expected = expectations.list_expectation(rung)
    page = browser.open(cfg.account(rung))
    try:
        seen = page.version_list(fixture_state["skill"])
        assert seen["empty"] is None, seen
        assert seen["rows"] == expected.rows, seen
        assert tuple(seen["filters"]) == expected.filters, seen
        assert seen["create"] is expected.can_create, seen
        page.screenshot(f"{rung}-list")
    finally:
        page.close()


@pytest.mark.parametrize("rung", RUNGS)
def test_the_handoff_lands_where_this_rung_can_act(rung, browser, cfg, fixture_state):
    expected = expectations.handoff_expectation(rung, fixture_state["live"])
    page = browser.open(cfg.account(rung))
    try:
        seen = page.handoff(fixture_state["skill"], fixture_state["draft"], "minor")
        if expected.missing:
            assert seen["missing"] is True, seen
            assert "没有这一版" in seen["text"], seen
        else:
            assert seen["missing"] is False, seen
            assert seen["pane"] == expected.pane, seen
            checked = [value for value, is_checked in seen["radios"] if is_checked]
            assert checked == ([expected.bump] if expected.bump else []), seen
            if expected.release_name is None:
                assert seen["publishButton"] is None, seen
            else:
                assert seen["field"] == expected.release_name, seen
                assert seen["publishButton"] == f"上线 @{expected.release_name}", seen
        page.screenshot(f"{rung}-handoff")
    finally:
        page.close()

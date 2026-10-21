"""The browser plane from six sessions: what each rung reads, and every write gate in front of it.

Refusals are probed on the fixture — a 403 changes nothing. The allowed side of the two gates
that would change it (offline, revoke) runs on the scratch skill instead, and puts it back.
"""

from __future__ import annotations

import pytest

from role_matrix import config
from role_matrix.expectations import WEB_GATES, at_least
from role_matrix.web import session_for

RUNGS = config.RUNGS
GATE_IDS = [gate.name for gate in WEB_GATES]


@pytest.fixture(scope="module")
def sessions(cfg):
    return {rung: session_for(cfg.server, cfg.account(rung)) for rung in RUNGS}


@pytest.mark.parametrize("rung", RUNGS)
def test_the_view_names_the_callers_rung(rung, sessions, fixture_state):
    response = sessions[rung].get(f"/web/skills/{fixture_state['skill']}")
    assert response.status_code == 200, response.text[:300]
    assert response.json()["rung"] == rung


@pytest.mark.parametrize("rung", RUNGS)
@pytest.mark.parametrize("gate", WEB_GATES, ids=GATE_IDS)
def test_gate(rung, gate, sessions, fixture_state):
    allowed = at_least(rung, gate.rung)
    if allowed and gate.mutates:
        pytest.skip("the allowed side runs on the scratch skill, so no probe changes the fixture")

    body = gate.body(fixture_state) if gate.body else None
    response = sessions[rung].request(gate.method, gate.path.format(**fixture_state), json=body)

    if not allowed:
        assert response.status_code == 403, (
            f"{gate.name} must be refused at {rung} (needs {gate.rung}): "
            f"{response.status_code} {response.text[:300]}"
        )
        return

    assert response.status_code == 200, response.text[:300]
    if gate.name == "publish the current version":
        # Already live: the same state reached twice, and the server says so rather than
        # claiming a change that did not happen.
        assert response.json()["changed"] is False


def test_the_owner_takes_the_scratch_offline_and_back(cfg, sessions):
    web = sessions["owner"]
    web.post(f"/web/skills/{cfg.scratch}/online")  # heal a run that stopped halfway

    assert web.post(f"/web/skills/{cfg.scratch}/offline").status_code == 200
    assert web.post(f"/web/skills/{cfg.scratch}/online").status_code == 200


def test_the_owner_revokes_and_restores_the_older_version(sessions, fixture_state):
    """Revoking the current version is refused by design (callers would lose what they read);
    the scratch's history version is the one this gate is exercised on."""
    scratch = fixture_state["scratch"]
    web = sessions["owner"]
    at = f"/web/skills/{scratch['skill']}/versions/{scratch['history']}"
    web.post(at + "/restore")  # heal a run that stopped halfway

    assert web.post(at + "/revoke", {"reason": "角色矩阵"}).status_code == 200
    assert web.post(at + "/restore").status_code == 200

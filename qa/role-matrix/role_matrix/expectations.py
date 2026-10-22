"""The ladder and every expectation the matrix holds — the one place "who may do what" lives.

A suite asks this module what a rung should get and then asserts exactly that. Changing a gate in
the product means changing one row here, and every suite follows; that is the point of keeping the
table out of the tests.

The values are not guesses: each was read off the running stack (2026-10-21) and confirmed against
the code that enforces it (`SkillVersionService.publishVersion` → maintain,
`SkillVersionService.requireMaintain`, `SetSkillOfflineUseCase` → owner). When a gate moves, this
table is the first thing that goes red, which is what a matrix is for.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

from .config import RUNGS

LEVEL = {rung: index for index, rung in enumerate(RUNGS)}


def at_least(role: str, needed: str) -> bool:
    return LEVEL[role] >= LEVEL[needed]


# --------------------------------------------------------------------------------------------
# CLI


@dataclass(frozen=True)
class CliProbe:
    """One CLI call and the lowest rung that succeeds at it.

    `rung=None` means the call is refused at every rung — not because the credential is too
    small, but because the action only exists in the browser (ADR 0061).
    """

    name: str
    rung: str | None
    args: Callable[[dict], list[str]]


def _skill(state: dict) -> str:
    return state["skill"]


# Reads. Each is safe to run against the fixture; none writes.
CLI_READ_PROBES: tuple[CliProbe, ...] = (
    CliProbe("skill files", "browse", lambda s: ["skill", "files", _skill(s)]),
    CliProbe("skill cat", "invoke", lambda s: ["skill", "cat", _skill(s), "SKILL.md"]),
    CliProbe("skill versions", "invoke", lambda s: ["skill", "versions", _skill(s)]),
    CliProbe("version list", "browse", lambda s: ["version", "list", _skill(s)]),
    CliProbe("version show live", "browse", lambda s: ["version", "show", f"{_skill(s)}@{s['live']}"]),
    CliProbe("version show draft", "write", lambda s: ["version", "show", f"{_skill(s)}@{s['draft']}"]),
    CliProbe("version diff draft", "write", lambda s: ["version", "diff", f"{_skill(s)}@{s['draft']}"]),
    CliProbe("skill shares", "admin", lambda s: ["skill", "shares", _skill(s)]),
    CliProbe("skill open --print", "invoke", lambda s: ["skill", "open", _skill(s), "--print"]),
    CliProbe("skill request --status", "invoke", lambda s: ["skill", "request", _skill(s), "--status"]),
    # A user who exists and was never granted anything: taking them off is a no-op, which is what
    # makes this probe safe to repeat. The removed rung must own no access, or the "allowed" case
    # would be the destructive one.
    CliProbe(
        "skill unshare (no such grant)",
        "admin",
        lambda s: ["skill", "unshare", _skill(s), "--user", s["bystander"]],
    ),
    # Refused for everyone, including the owner: publish is a browser action (ADR 0031 / 0061).
    CliProbe("version publish", None, lambda s: ["version", "publish", f"{_skill(s)}@{s['draft']}"]),
)


# Writes. A role at `write` or above gets its own draft on the fixture and renames, re-notes and
# discards it — all of it removed before the test ends, so re-running changes nothing visible.
# Below `write` the very first call is refused and the rest have nothing to address.
CLI_OWN_DRAFT_RUNG = "write"


# --------------------------------------------------------------------------------------------
# Web plane (session + CSRF)


@dataclass(frozen=True)
class WebGate:
    """One browser-plane route and the lowest rung that is not refused.

    `body` is a callable over the fixture state, so a probe names the version it acts on instead
    of letting the server fall back to "latest" — a different request from the one written here.

    `legal` marks the gates whose allowed side would change the fixture (offline takes the whole
    skill down; revoke moves a version). Those are probed for refusal on the fixture and for
    effect on the scratch skill, so no run leaves the fixture different.
    """

    name: str
    method: str
    path: str
    rung: str
    body: Callable[[dict], dict] | None = None
    mutates: bool = False


WEB_GATES: tuple[WebGate, ...] = (
    WebGate("grants", "GET", "/web/skills/{skill}/grants", "admin"),
    WebGate("access-requests", "GET", "/web/skills/{skill}/access-requests", "admin"),
    # The current version is already live: publishing it is the same state reached twice and
    # writes nothing (`PromotionOutcome.changed == false`), which is what makes it safe here.
    WebGate(
        "publish the current version",
        "POST",
        "/web/skills/{skill}/publish",
        "maintain",
        body=lambda state: {"version": state["live"], "release_name": None},
    ),
    WebGate("offline", "POST", "/web/skills/{skill}/offline", "owner", mutates=True),
    WebGate(
        "revoke the history version",
        "POST",
        "/web/skills/{skill}/versions/{history}/revoke",
        "maintain",
        body=lambda state: {"reason": "角色矩阵：拒绝探针"},
        mutates=True,
    ),
)


# --------------------------------------------------------------------------------------------
# Console (the version list and the publish handoff)


@dataclass(frozen=True)
class ListExpectation:
    rows: int
    filters: tuple[str, ...]
    can_create: bool


# Filter order as the page draws it — not alphabetical, and not a dict's insertion order by luck.
FILTER_ORDER = ("全部", "线上", "草稿", "历史版本", "已下架", "已丢弃")


def list_expectation(rung: str) -> ListExpectation:
    """What the version list shows this rung on the six-state fixture.

    live + history are visible from `browse` (the list itself is a browse read);
    a draft is listed from `write`, whoever submitted it; revoked and discarded appear only from
    `maintain`, which is the first rung allowed to act on them.
    """
    counts = {"全部": 2, "线上": 1, "历史版本": 1}
    if at_least(rung, "write"):
        counts["全部"] += 1
        counts["草稿"] = 1
    if at_least(rung, "maintain"):
        counts["全部"] += 2
        counts["已下架"] = 1
        counts["已丢弃"] = 1
    filters = tuple(f"{name} {counts[name]}" for name in FILTER_ORDER if name in counts)
    return ListExpectation(rows=counts["全部"], filters=filters, can_create=at_least(rung, "write"))


@dataclass(frozen=True)
class HandoffExpectation:
    """What `?sub=actions&bump=minor` on the open draft shows this rung.

    `missing=True` is the pane that answers a version the caller may not open — a 404 in page
    form. `pane` is the sub-pane that ends up current: the address asks for 操作, but a rung that
    cannot act on the version is given 概览 instead, and the preselection is not shown at all.
    """

    missing: bool = False
    pane: str | None = None
    bump: str | None = None
    release_name: str | None = None


def handoff_expectation(rung: str, live: str) -> HandoffExpectation:
    if not at_least(rung, "write"):
        return HandoffExpectation(missing=True)
    if not at_least(rung, "maintain"):
        return HandoffExpectation(pane="概览")
    return HandoffExpectation(pane="操作", bump="minor", release_name=next_minor(live))


def next_minor(version: str) -> str:
    major, minor, _ = (int(part) for part in version.split("."))
    return f"{major}.{minor + 1}.0"

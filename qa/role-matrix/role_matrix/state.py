"""The seeded fixture's identity, written by `seed` and read by every suite.

The names come from `.env`; the version numbers and the draft's code are minted by the server
when the fixture is built, so they cannot be written down in advance. `.cache/` is this machine's
copy of that answer, and deleting it loses only the pointer — the skill itself is untouched.
"""

from __future__ import annotations

import json

from .config import CACHE

STATE_FILE = CACHE / "fixture.json"


class NotSeeded(RuntimeError):
    pass


def load() -> dict:
    try:
        return json.loads(STATE_FILE.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        raise NotSeeded(
            f"no fixture state at {STATE_FILE}; run `uv run python -m role_matrix.seed` first"
        ) from None


def save(state: dict) -> None:
    CACHE.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, indent=2, ensure_ascii=False) + "\n")

"""One API key per rung, cached between runs and re-minted when the server stops accepting it.

A key is minted through the browser plane (session + CSRF), which is the only place a key can be
minted — the CLI has no route for it, and that is the design. The cache lives in `.cache/` so the
console's key list does not grow by six rows on every run.
"""

from __future__ import annotations

import json

from . import config as config_module
from .config import Config, RUNGS
from .web import session_for

CACHE_FILE = config_module.CACHE / "tokens.json"

# Substrings the CLI uses for the three refusals a cached key can meet (main.go, `credentialHint`).
_STALE_MARKERS = ("服务端不认", "已被撤销", "已经过期")


def _load() -> dict[str, str]:
    try:
        data = json.loads(CACHE_FILE.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return {}
    return {k: v for k, v in data.items() if isinstance(v, str) and v}


def _save(tokens: dict[str, str]) -> None:
    config_module.CACHE.mkdir(parents=True, exist_ok=True)
    CACHE_FILE.write_text(json.dumps(tokens, indent=2) + "\n")


def mint(cfg: Config, rung: str) -> str:
    account = cfg.account(rung)
    return session_for(cfg.server, account).mint_key()


def ensure(cfg: Config) -> dict[str, str]:
    """Every rung's key, minting only the missing ones."""
    tokens = _load()
    minted = False
    for rung in RUNGS:
        if rung not in tokens:
            tokens[rung] = mint(cfg, rung)
            minted = True
    if minted:
        _save(tokens)
    return tokens


def renew(cfg: Config, tokens: dict[str, str], rung: str) -> str:
    """Mints a fresh key for one rung and updates the cache in place."""
    tokens[rung] = mint(cfg, rung)
    _save(tokens)
    return tokens[rung]


def looks_stale(text: str) -> bool:
    return any(marker in text for marker in _STALE_MARKERS)

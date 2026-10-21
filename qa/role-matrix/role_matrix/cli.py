"""Build the CLI once per run and call it with one rung's credential.

The binary is built from the checkout under test — not taken from PATH — so the matrix measures
the code on this branch. `go build` runs only when a `.go` file is newer than the binary.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import os
import subprocess

from . import config as config_module
from . import roles
from .config import Config


@dataclass(frozen=True)
class Result:
    argv: tuple[str, ...]
    code: int
    out: str
    err: str

    @property
    def text(self) -> str:
        return (self.out + self.err).strip()

    @property
    def ok(self) -> bool:
        return self.code == 0

    @property
    def lines(self) -> list[str]:
        return [line for line in self.text.splitlines() if line.strip()]

    def describe(self) -> str:
        body = " / ".join(self.lines[:4])
        return f"{' '.join(self.argv)} → {self.code}: {body}"


def build(cfg: Config) -> Path:
    source = config_module.repo_root() / "skillmaster-cli"
    target = cfg.cli
    if target.exists():
        built_at = target.stat().st_mtime
        newest_source = max(
            (path.stat().st_mtime for path in source.rglob("*.go")), default=0.0
        )
        if built_at > newest_source:
            return target
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["go", "build", "-o", str(target), "./cmd/skillmaster"],
        cwd=source,
        check=True,
        capture_output=True,
        text=True,
    )
    return target


class Runner:
    """Runs the CLI as one rung. The credential is an env var, never an argv — the process table
    is world-readable on most machines and a key in `ps` is the same leak as a key in a log."""

    def __init__(self, cfg: Config, cli: Path, tokens: dict[str, str]):
        self.cfg = cfg
        self.cli = cli
        self.tokens = tokens

    def run(self, rung: str, *args: str, stdin: str | None = None) -> Result:
        result = self._run(rung, args, stdin)
        if not result.ok and roles.looks_stale(result.text):
            # A cached key can go stale between runs (revoked in the console, account rotated).
            # Mint once and say nothing: the matrix measures rungs, not key lifetimes.
            self.tokens[rung] = roles.renew(self.cfg, self.tokens, rung)
            result = self._run(rung, args, stdin)
        return result

    def _run(self, rung: str, args: tuple[str, ...], stdin: str | None) -> Result:
        env = os.environ | {
            "SKILLMASTER_SERVER": self.cfg.server,
            "SKILLMASTER_WEB_URL": self.cfg.server,
            "SKILLMASTER_API_KEY": self.tokens[rung],
        }
        process = subprocess.run(
            [str(self.cli), *args], input=stdin, capture_output=True, text=True, env=env, timeout=60
        )
        return Result(argv=tuple(args), code=process.returncode, out=process.stdout, err=process.stderr)

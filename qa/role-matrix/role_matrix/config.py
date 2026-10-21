"""Settings and accounts, read from this folder's own `.env`.

`ROLE_MATRIX_*` in the process environment wins over the file, so a run can point one account or
the whole matrix at another stack without editing a checked-out `.env`.

Nothing here is secret to the product: these are six dev accounts on a local stack. They live in
`.env` (gitignored) rather than in a test file because a password in a test file gets copied into
the next repository.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import os

FOLDER = Path(__file__).resolve().parent.parent
CACHE = FOLDER / ".cache"

# The ladder, lowest first. One list, so a new rung is added here and the suites follow.
RUNGS = ("invoke", "browse", "write", "maintain", "admin", "owner")

# Not on the ladder: an account that exists and is granted nothing, so "unshare somebody who was
# never shared with" can be probed without taking access away from anyone.
BYSTANDER = "bystander"


class MissingSetting(KeyError):
    """A `.env` key the matrix needs is absent; the message says which and where."""


def _read_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


@dataclass(frozen=True)
class Account:
    username: str
    phone: str
    password: str


def _repo_root() -> Path:
    for parent in FOLDER.parents:
        if (parent / "skillmaster-cli").is_dir() and (parent / "skillmaster-server").is_dir():
            return parent
    raise RuntimeError(f"no repository root (skillmaster-cli + skillmaster-server) above {FOLDER}")


@dataclass(frozen=True)
class Config:
    server: str
    skill: str
    scratch: str
    db_url: str
    cli: Path
    accounts: dict[str, Account]

    def account(self, rung: str) -> Account:
        account = self.accounts.get(rung)
        if account is None:
            raise MissingSetting(
                f"no account for rung {rung!r}; set ROLE_MATRIX_{rung.upper()}_PHONE in {FOLDER / '.env'}"
            )
        return account


def load() -> Config:
    values = _read_env_file(FOLDER / ".env")
    values.update({k: v for k, v in os.environ.items() if k.startswith("ROLE_MATRIX_")})

    def need(key: str) -> str:
        value = values.get(key, "").strip()
        if not value:
            raise MissingSetting(
                f"{key} is missing; copy .env.example to .env in {FOLDER} and fill it in"
            )
        return value

    accounts: dict[str, Account] = {}
    for rung in (*RUNGS, BYSTANDER):
        prefix = f"ROLE_MATRIX_{rung.upper()}_"
        accounts[rung] = Account(
            username=need(prefix + "USERNAME"),
            phone=need(prefix + "PHONE"),
            password=need(prefix + "PASSWORD"),
        )

    root = _repo_root()
    cli = Path(values.get("ROLE_MATRIX_CLI") or CACHE / "skillmaster")

    return Config(
        server=need("ROLE_MATRIX_SERVER").rstrip("/"),
        skill=need("ROLE_MATRIX_SKILL"),
        scratch=need("ROLE_MATRIX_SCRATCH"),
        db_url=need("ROLE_MATRIX_DB_URL"),
        cli=cli,
        accounts=accounts,
    )


def repo_root() -> Path:
    return _repo_root()

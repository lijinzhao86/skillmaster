"""Solve a dev captcha from the database — seeding only, local stacks only.

The answer is four characters from a 32-symbol alphabet, stored as the SHA-256 of the upper-cased
string. That is about a million candidates, a second on a laptop. `CaptchaService` says as much in
its own comment: the image is a speed bump for a person writing a script, not a wall. This module
exists so the seed can register six accounts without a human in the loop.

It refuses a database that is not on this machine. Cracking a captcha on a shared server is a
different act from registering a fixture account on one's own dev stack, and the tests have no
reason to do the former.
"""

from __future__ import annotations

import hashlib
import itertools
import subprocess
from urllib.parse import urlsplit

ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
_LOCAL_HOSTS = ("localhost", "127.0.0.1", "::1")


class NotLocal(RuntimeError):
    pass


def require_local(db_url: str) -> None:
    if urlsplit(db_url).hostname not in _LOCAL_HOSTS:
        raise NotLocal(
            f"refusing to read captcha answers from a database that is not local: {db_url!r}"
        )


def solve(answer_hash: str) -> str:
    """The four characters whose upper-cased SHA-256 is `answer_hash`."""
    target = answer_hash.strip().lower()
    for letters in itertools.product(ALPHABET, repeat=4):
        candidate = "".join(letters)
        if hashlib.sha256(candidate.encode()).hexdigest() == target:
            return candidate
    raise LookupError(f"no four-symbol answer hashes to {answer_hash}")


def answer_for(db_url: str, captcha_id: str) -> str:
    """Reads one challenge's stored hash, then solves it."""
    require_local(db_url)
    query = "select answer_hash from captcha where id = :'id';"
    process = subprocess.run(
        ["psql", db_url, "-At", "-v", f"id={captcha_id}"],
        input=query,
        capture_output=True,
        text=True,
        check=True,
    )
    stored = process.stdout.strip()
    if not stored:
        raise LookupError(f"no captcha row with id {captcha_id!r}")
    return solve(stored)

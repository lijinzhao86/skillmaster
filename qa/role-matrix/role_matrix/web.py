"""The browser plane over plain HTTP: session cookie, double-submit CSRF, sign-in, key minting.

Every `/web/**` call carries `X-XSRF-TOKEN` with the `XSRF-TOKEN` cookie's value; the cookie
itself is set by any first GET, which is why a `Web` is opened (and not merely constructed) before
it is used.
"""

from __future__ import annotations

from dataclasses import dataclass

import requests

from .config import Account


class SignInFailed(RuntimeError):
    pass


@dataclass
class Web:
    base: str
    http: requests.Session

    @classmethod
    def open(cls, base: str) -> "Web":
        http = requests.Session()
        http.get(base.rstrip("/") + "/web/session", timeout=10)
        return cls(base=base.rstrip("/"), http=http)

    @property
    def csrf(self) -> str:
        return self.http.cookies.get("XSRF-TOKEN") or ""

    def request(self, method: str, path: str, json=None) -> requests.Response:
        return self.http.request(
            method, self.base + path, json=json, headers={"X-XSRF-TOKEN": self.csrf}, timeout=20
        )

    def get(self, path: str) -> requests.Response:
        return self.request("GET", path)

    def post(self, path: str, json=None) -> requests.Response:
        return self.request("POST", path, json=json)

    def sign_in(self, account: Account) -> "Web":
        response = self.post("/web/login", {"phone": account.phone, "password": account.password})
        if response.status_code >= 400:
            raise SignInFailed(
                f"{account.username}: login answered {response.status_code} {response.text[:200]}"
            )
        return self

    def mint_key(self) -> str:
        """A key with the scopes every rung's CLI needs. The rung is the grant's, not the key's."""
        response = self.post(
            "/web/keys",
            {
                "name": "role-matrix",
                "note": "issued by qa/role-matrix",
                "scopes": ["skills:read", "skills:write"],
                "expiresAt": None,
            },
        )
        if response.status_code >= 400:
            raise SignInFailed(f"minting a key answered {response.status_code} {response.text[:200]}")
        body = response.json()
        return body.get("token") or body.get("secret") or body


def session_for(base: str, account: Account) -> Web:
    return Web.open(base).sign_in(account)

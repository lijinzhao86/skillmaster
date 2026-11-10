#!/usr/bin/env python3
"""v59's library/personal retrieval evaluation against a real local server.

    DASHSCOPE_API_KEY=... python3 scripts/retrieval-eval-v59.py \\
        --base http://127.0.0.1:8082 --db skillmaster_v59_v19

What it does
------------
Seeds the 33 skills from ``src/test/resources/retrieval/skills.json`` into a throwaway
database, embeds them with the same text composition the server uses, registers one account,
then runs two query sets twice — once before any personal sentence exists, once after writing
the sentences in ``v59-personal.tsv``:

* ``labeled.tsv`` (v19, 99 queries): library Recall@10 before/after, to catch a regression;
* ``v59-personal.tsv`` (24 queries, two groups): Top-1, Recall@5 and MRR before/after.

The public shape is checked on one personal query: public cards must not gain
``my_when_to_use`` after the note is written.

Why a script and not a JUnit test
---------------------------------
It needs a running server, a throwaway database, a real embedding provider and money. The
checked-in data is the point; the run is deliberate, like v19's ``retrieval-eval.py``.

The server must be started against the same database with
``SKILLMASTER_SMS_ACCEPT_ANY_CODE=true`` — registration uses the captcha override below, and
the SMS code is not compared.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

DASHSCOPE = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings"
MODEL = "text-embedding-v4"
STORED_MODEL = "dashscope:text-embedding-v4:1024"
BATCH = 10


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client:
    def __init__(self, base: str):
        self.base = base
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(), NoRedirect())
        self.csrf = None
        self.token = None

    def call(self, method, path, body=None, bearer=None, csrf=False):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Accept", "application/json")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        if bearer is not None:
            req.add_header("Authorization", "Bearer " + bearer)
        if csrf and self.csrf:
            req.add_header("X-XSRF-TOKEN", self.csrf)
        try:
            with self.opener.open(req, timeout=90) as res:
                return res.status, res.read().decode()
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode()

    def read_csrf(self):
        self.call("GET", "/web/session")
        for handler in self.opener.handlers:
            cookiejar = getattr(handler, "cookiejar", None)
            if cookiejar is None:
                continue
            for cookie in cookiejar:
                if cookie.name == "XSRF-TOKEN":
                    self.csrf = urllib.parse.unquote(cookie.value)


def psql(container: str, db: str, sql: str) -> str:
    out = subprocess.run(
        ["docker", "exec", "-i", container, "psql", "-U", "skillmaster",
         "-d", db, "-v", "ON_ERROR_STOP=1", "-tAc", sql],
        capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr)
    return out.stdout.strip()


def embed(texts: list[str], key: str) -> list[list[float]]:
    out: list[list[float]] = []
    for start in range(0, len(texts), BATCH):
        chunk = texts[start:start + BATCH]
        payload = json.dumps({"model": MODEL, "input": chunk, "dimensions": 1024}).encode()
        req = urllib.request.Request(DASHSCOPE, data=payload, method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("Authorization", "Bearer " + key)
        with urllib.request.urlopen(req, timeout=120) as res:
            answer = json.loads(res.read().decode())
        out.extend(item["embedding"] for item in answer["data"])
    return out


def literal(vector: list[float]) -> str:
    return "[" + ",".join(f"{x:.8f}" for x in vector) + "]"


def compose(name: str, description: str) -> str:
    """The server's composition with title == name and no when_to_use."""
    return f"name: {name}\ntitle: {name}\ndescription: {description.strip()}"


def register(client: Client, container: str, db: str):
    stamp = str(int(time.time()))
    phone = "138" + stamp[-8:]
    username = "v59eval" + stamp[-8:]
    password = "eval-correct-horse"

    status, body = client.call("GET", "/web/captcha")
    assert status == 200, (status, body)
    captcha_id = json.loads(body)["captcha_id"]
    answer = "ABCD"
    digest = hashlib.sha256(answer.upper().encode()).hexdigest()
    psql(container, db, f"UPDATE captcha SET answer_hash = '{digest}' WHERE id = '{captcha_id}'")

    client.read_csrf()
    status, body = client.call("POST", "/web/register/code", {
        "phone": phone, "captcha_id": captcha_id, "captcha_answer": answer}, csrf=True)
    assert status in (200, 201, 204), (status, body)

    status, body = client.call("POST", "/web/register", {
        "phone": phone, "code": "111111", "password": password,
        "username": username, "consent": True}, csrf=True)
    assert status in (200, 201), (status, body)
    user_id = json.loads(body)["user_id"]

    # Registration signs in, which rotates the session and its CSRF token.
    client.read_csrf()
    status, body = client.call("POST", "/web/keys", {
        "name": "v59-eval", "scopes": ["skills:read", "skills:write"]}, csrf=True)
    assert status in (200, 201), (status, body)
    return user_id, username, json.loads(body)["token"]


def seed(container: str, db: str, user_id: str, skills: list[dict], key: str):
    namespace_id = psql(container, db,
                        f"SELECT id FROM namespace WHERE owner_user_id = '{user_id}'")
    now = "2026-11-11T00:00:00Z"
    vectors = embed([compose(s["name"], s["description"]) for s in skills], key)
    ids: dict[str, str] = {}
    suffix = str(int(time.time()))[-8:]
    for index, (skill, vector) in enumerate(zip(skills, vectors)):
        skill_id = f"01V59EVAL{index:02d}{suffix}".ljust(26, "0")[:26]
        ids[skill["name"]] = skill_id
        description = skill["description"].replace("'", "''")
        name = skill["name"].replace("'", "''")
        psql(container, db, f"""
            INSERT INTO skill (id, namespace_id, name, title, description, frontmatter,
                               visibility, public_rung, current_version_id,
                               current_version_name, current_version_submitted_at,
                               when_to_use, created_by, created_at, updated_at,
                               offline_at, deleted_at, category_key)
            VALUES ('{skill_id}', '{namespace_id}', '{name}', '{name}', '{description}',
                    '{{}}', 'public', 'browse', '{skill_id}V', '1.0.0', '{now}',
                    NULL, '{user_id}', '{now}', '{now}', NULL, NULL, NULL);
            INSERT INTO skill_embedding (skill_id, model, text_hash, embedding, updated_at)
            VALUES ('{skill_id}', '{STORED_MODEL}', 'h-{index}', '{literal(vector)}', '{now}');
        """)
    return ids


def search(client: Client, query: str, scope: str = "library", limit: int = 10):
    q = urllib.parse.quote(query)
    status, body = client.call(
        "GET", f"/api/v1/skills?scope={scope}&semantic=true&limit={limit}&q={q}",
        bearer=client.token)
    assert status == 200, (status, body)
    return json.loads(body)


def rank_of(answer: dict, expected: str) -> int:
    for position, card in enumerate(answer.get("skills", []), start=1):
        if card["name"] == expected:
            return position
    return 0


def metrics(rows: list[int]) -> tuple[float, float, float]:
    top1 = sum(1 for rank in rows if rank == 1) / len(rows)
    recall5 = sum(1 for rank in rows if 0 < rank <= 5) / len(rows)
    mrr = sum(1.0 / rank for rank in rows if rank > 0) / len(rows)
    return top1, recall5, mrr


def read_tsv(path: str, columns: int) -> list[list[str]]:
    rows = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            assert len(parts) == columns, (path, line)
            rows.append(parts)
    return rows


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8082")
    parser.add_argument("--db", required=True)
    parser.add_argument("--container", default="skillmaster-postgres")
    parser.add_argument("--fixtures", default=os.path.join(
        os.path.dirname(__file__), "..", "src", "test", "resources", "retrieval"))
    args = parser.parse_args()
    key = os.environ.get("DASHSCOPE_API_KEY", "")
    if not key:
        sys.exit("DASHSCOPE_API_KEY is required")

    fixtures = os.path.abspath(args.fixtures)
    skills = json.load(open(os.path.join(fixtures, "skills.json"), encoding="utf-8"))
    v19 = read_tsv(os.path.join(fixtures, "labeled.tsv"), 2)
    personal = read_tsv(os.path.join(fixtures, "v59-personal.tsv"), 4)

    client = Client(args.base)
    user_id, username, client.token = register(client, args.container, args.db)
    ids = seed(args.container, args.db, user_id, skills, key)
    print(f"seeded {len(skills)} skills for {username}")

    before_v19: dict[str, int] = {}
    for query, expected in v19:
        before_v19[query] = rank_of(search(client, query), expected)
    before_personal = []
    for group, query, expected, _note in personal:
        before_personal.append((group, query, expected, rank_of(search(client, query), expected)))

    notes: dict[str, str] = {}
    for _group, _query, expected, note in personal:
        notes.setdefault(expected, note)
    for name, note in notes.items():
        status, body = client.call(
            "PUT", f"/api/v1/skills/{username}/{name}/my-when-to-use",
            {"when_to_use": note}, bearer=client.token)
        assert status == 200, (status, body)
    print(f"wrote {len(notes)} personal sentences")

    after_v19: dict[str, int] = {}
    for query, expected in v19:
        after_v19[query] = rank_of(search(client, query), expected)
    after_personal = []
    for group, query, expected, _note in personal:
        after_personal.append((group, query, expected, rank_of(search(client, query), expected)))

    public = search(client, personal[0][1], scope="public")
    leaked = any("my_when_to_use" in card for card in public.get("skills", []))

    def recall10(before: dict[str, int], after: dict[str, int]) -> None:
        for label, ranks in (("v19 before", before), ("v19 after", after)):
            hits = sum(1 for rank in ranks.values() if rank > 0)
            print(f"Recall@10 ({label}, n={len(ranks)}): {hits / len(ranks):.3f}")

    recall10(before_v19, after_v19)
    print()
    for group in ("personal-exact", "personal-rewrite"):
        rows_before = [rank for g, _q, _e, rank in before_personal if g == group]
        rows_after = [rank for g, _q, _e, rank in after_personal if g == group]
        t1b, r5b, mb = metrics(rows_before)
        t1a, r5a, ma = metrics(rows_after)
        print(f"{group}: Top-1 {t1b:.0%} -> {t1a:.0%}; "
              f"Recall@5 {r5b:.0%} -> {r5a:.0%}; MRR {mb:.3f} -> {ma:.3f}")

    print()
    print("query\tgroup\tbefore\tafter")
    for (group, query, _expected, before), (_g, _q, _e, after) in zip(
            before_personal, after_personal):
        print(f"{query}\t{group}\t{before or 'miss'}\t{after or 'miss'}")
    print()
    print(f"public card gained my_when_to_use: {'YES — BUG' if leaked else 'no'}")


if __name__ == "__main__":
    main()

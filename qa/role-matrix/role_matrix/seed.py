"""Build the matrix's accounts and fixtures on a local stack. Run this before `pytest`.

    uv run python -m role_matrix.seed

What it leaves behind:

* six accounts (one per rung), registered through the real registration flow with the dev SMS
  code and a captcha solved from the dev database;
* one fixture skill with one version in each state the console draws — live, history, revoked,
  discarded, and one open draft — granted to the five non-owner accounts at their rung;
* one scratch skill the lifecycle probes may take offline, revoke and restore without touching
  the fixture.

It is a no-op when the recorded state still resolves; it never deletes or rewrites a skill.
`--recreate` is deliberately not offered: destroying a skill is a browser action, and a seed that
did it would be the one command in this repository able to erase something with no confirmation.
"""

from __future__ import annotations

import re
import sys

from . import captcha, cli, config, roles, state
from .web import Web, session_for, SignInFailed

OWNER = "owner"
DRAFT_CODE = re.compile(r"@([A-Z0-9]{8})")


def log(message: str) -> None:
    print(f"  {message}", file=sys.stderr)


def ensure_account(cfg: config.Config, rung: str) -> None:
    """Signs in; registers the account only when signing in says it does not exist.

    The dev stack accepts any SMS code (`SKILLMASTER_SMS_ACCEPT_ANY_CODE=true`), which is what
    lets the registration finish without a phone.
    """
    account = cfg.account(rung)
    try:
        session_for(cfg.server, account)
        return
    except SignInFailed as failure:
        log(f"{account.username}: not signing in ({failure}); registering")

    web = Web.open(cfg.server)
    challenge = web.get("/web/captcha").json()
    answer = captcha.answer_for(cfg.db_url, challenge["captcha_id"])
    code = web.post(
        "/web/register/code",
        {
            "phone": account.phone,
            "captcha_id": challenge["captcha_id"],
            "captcha_answer": answer,
        },
    )
    if code.status_code >= 400:
        raise SystemExit(
            f"{account.username}: register/code answered {code.status_code} {code.text[:300]}\n"
            "若这个手机号已注册但密码不同，请改 .env 里的密码或换一个手机号。"
        )
    registered = web.post(
        "/web/register",
        {
            "phone": account.phone,
            "code": "123456",
            "password": account.password,
            "username": account.username,
        },
    )
    if registered.status_code >= 400:
        raise SystemExit(
            f"{account.username}: register answered {registered.status_code} {registered.text[:300]}"
        )
    log(f"{account.username}: registered")


def draft_code(text: str, action: str) -> str:
    match = DRAFT_CODE.search(text)
    if not match:
        raise SystemExit(f"{action}: no draft code in the answer: {text[:400]}")
    return match.group(1)


def publish(cfg: config.Config, session, skill: str, code: str, release: str) -> None:
    response = session.post(
        f"/web/skills/{skill}/publish", {"version": code, "release_name": release}
    )
    if response.status_code >= 400:
        raise SystemExit(f"publish {release}: {response.status_code} {response.text[:300]}")
    log(f"published {release}")


def new_draft(runner: cli.Runner, skill: str, path: str, name: str, body: str, note: str) -> str:
    result = runner.run(
        OWNER,
        "skill",
        "write",
        f"{skill}@new",
        path,
        "--name",
        name,
        "--note",
        note,
        stdin=body,
    )
    if not result.ok:
        raise SystemExit(f"writing {path} failed: {result.describe()}")
    return draft_code(result.text, f"write {path}")


def grant(runner: cli.Runner, cfg: config.Config, skill: str) -> None:
    for rung in config.RUNGS:
        if rung == OWNER:
            continue
        account = cfg.account(rung)
        result = runner.run(
            OWNER, "skill", "share", skill, "--to", account.username, "--role", rung
        )
        # Re-sharing an existing grant answers as a change to the same state; both are fine.
        if not result.ok:
            raise SystemExit(f"sharing with {account.username} failed: {result.describe()}")


def build_fixture(cfg: config.Config, runner: cli.Runner, session) -> dict:
    created = runner.run(
        OWNER,
        "skill",
        "create",
        cfg.skill.split("/", 1)[1],
        "--description",
        "六档角色矩阵的夹具",
        "--name",
        "第一版",
    )
    if not created.ok:
        raise SystemExit(f"creating {cfg.skill} failed: {created.describe()}")
    first = draft_code(created.text, "skill create")
    publish(cfg, session, cfg.skill, first, "1.0.0")

    second = new_draft(runner, cfg.skill, "notes/two.md", "第二版", "# 第二版\n", "矩阵夹具：第二版")
    publish(cfg, session, cfg.skill, second, "1.0.1")

    third = new_draft(runner, cfg.skill, "notes/three.md", "第三版", "# 第三版\n", "矩阵夹具：第三版")
    publish(cfg, session, cfg.skill, third, "1.0.2")

    revoked = session.post(
        f"/web/skills/{cfg.skill}/versions/1.0.1/revoke", {"reason": "矩阵夹具：故意下架一版"}
    )
    if revoked.status_code >= 400:
        raise SystemExit(f"revoke 1.0.1: {revoked.status_code} {revoked.text[:300]}")
    log("revoked 1.0.1")

    dropped = new_draft(runner, cfg.skill, "notes/drop.md", "丢掉的一版", "# 丢掉\n", "矩阵夹具：会被丢弃")
    discarded = runner.run(OWNER, "version", "discard", f"{cfg.skill}@{dropped}", "--yes")
    if not discarded.ok:
        raise SystemExit(f"discarding @{dropped} failed: {discarded.describe()}")

    draft = new_draft(
        runner, cfg.skill, "notes/open.md", "矩阵草稿", "# 打开的草稿\n", "矩阵夹具：留着的草稿"
    )

    return {
        "skill": cfg.skill,
        "live": "1.0.2",
        "history": "1.0.0",
        "revoked": "1.0.1",
        "discarded": dropped,
        "draft": draft,
    }


def build_scratch(cfg: config.Config, runner: cli.Runner, session) -> dict:
    """Two published versions, so revoke has a non-current one to act on."""
    created = runner.run(
        OWNER,
        "skill",
        "create",
        cfg.scratch.split("/", 1)[1],
        "--description",
        "角色矩阵里可以随便改的那一个",
        "--name",
        "旧的一版",
    )
    if not created.ok:
        raise SystemExit(f"creating {cfg.scratch} failed: {created.describe()}")
    first = draft_code(created.text, "skill create")
    publish(cfg, session, cfg.scratch, first, "0.9.0")

    live = new_draft(
        runner, cfg.scratch, "notes/live.md", "线上的一版", "# 线上\n", "可随意改动的夹具：线上版"
    )
    publish(cfg, session, cfg.scratch, live, "1.0.0")

    return {"skill": cfg.scratch, "live": "1.0.0", "history": "0.9.0"}


def main() -> None:
    cfg = config.load()
    captcha.require_local(cfg.db_url)
    binary = cli.build(cfg)

    print("accounts", file=sys.stderr)
    for name in (*config.RUNGS, config.BYSTANDER):
        ensure_account(cfg, name)

    tokens = roles.ensure(cfg)
    runner = cli.Runner(cfg, binary, tokens)
    session = session_for(cfg.server, cfg.account(OWNER))

    if runner.run(OWNER, "skill", "ls", cfg.skill).ok:
        try:
            fixture = state.load()
        except state.NotSeeded:
            raise SystemExit(
                f"{cfg.skill} 已经存在，但 {state.STATE_FILE} 里没有它的身份（草稿码）。\n"
                "要么在控制台里把它删掉再跑一次，要么把 ROLE_MATRIX_SKILL 换成一个新名字。"
            ) from None
        if fixture.get("skill") != cfg.skill:
            raise SystemExit(
                f"{state.STATE_FILE} 记的是 {fixture.get('skill')!r}，.env 写的是 {cfg.skill!r}；"
                "改 .env 之后请先删掉那个文件。"
            )
        print(f"fixture: reusing {cfg.skill} (draft {fixture['draft']})", file=sys.stderr)
    else:
        print(f"fixture: building {cfg.skill}", file=sys.stderr)
        fixture = build_fixture(cfg, runner, session)
        fixture["scratch"] = None  # written once the scratch exists
        state.save(fixture)

    if not runner.run(OWNER, "skill", "ls", cfg.scratch).ok:
        print(f"scratch: building {cfg.scratch}", file=sys.stderr)
        fixture["scratch"] = build_scratch(cfg, runner, session)
        state.save(fixture)
    elif not fixture.get("scratch"):
        raise SystemExit(
            f"{cfg.scratch} 已经存在，但 {state.STATE_FILE} 里没有它的版本形状。\n"
            "把 ROLE_MATRIX_SCRATCH 换成一个新名字，再跑一次。"
        )

    grant(runner, cfg, fixture["skill"])
    grant(runner, cfg, cfg.scratch)

    # The bystander is named in the state so a probe can address them without importing settings.
    if fixture.get("bystander") != cfg.account(config.BYSTANDER).username:
        fixture["bystander"] = cfg.account(config.BYSTANDER).username
        state.save(fixture)

    print("ready", file=sys.stderr)
    print(
        f"skill={fixture['skill']} live={fixture['live']} draft={fixture['draft']} "
        f"scratch={cfg.scratch}",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()

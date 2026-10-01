# skillmaster

A hosting platform for AI-agent [skills](https://agentskills.io). The design keeps
skills on the server and exposes them over a remote API — agents search, read, and
use them on demand, so progressive disclosure is enforced server-side and no skill
copy is installed on the client.

**Status: designed, not yet implemented.** The product and technical documents under
[`docs/`](docs/) are the source of truth — start at [`docs/README.md`](docs/README.md).

## Layout

One repository, several independently built subprojects. Each subproject owns its
toolchain, its tests, and its workflow, and **nothing at the repository root assumes
a language** — so a subproject can be Python, Node, or anything else without
disturbing the others.

- `skillmaster-server/` — the API and authorization server, one process, **Java 25
  (LTS) + Spring Boot 4** with all state in **PostgreSQL** ([ADR 0011](docs/decisions/0011-server-and-cli-stack.md),
  [ADR 0010](docs/decisions/0010-storage-in-postgres.md)). Self-contained: Maven
  (`pom.xml`, with `mvnw` committed so every machine resolves the same Maven version),
  `Dockerfile`, `src/test/`. Its CI is `.github/workflows/server.yml` at the repository
  root — workflows can only live there. **P0a is implemented**: publishing a skill, the
  four read endpoints, search, and the gateway's discovery channel, over PostgreSQL.
  Authentication is a single static token until P1. `reference-python/` holds the
  pre-Java baseline as a design reference, outside the build
  ([technical design](docs/versions/v1-hosting/technical-design.md) §6).
- `skillmaster-cli/` — the client that holds credentials and fetches skills on
  demand. **Language decided: Go** ([ADR 0011](docs/decisions/0011-server-and-cli-stack.md));
  not implemented yet — see its README.
- `skillmaster-web/` — the browser pages: sign up, sign in, reset a password, over the
  server's `/web` plane. **Vue 3 + Vite + TypeScript** ([ADR 0015](docs/decisions/0015-web-frontend-stack.md)).
  A pure client — it adds no endpoint and no server capability. Its CI is
  `.github/workflows/web.yml`, which needs neither a database nor the server.
- `gateway/skillmaster/` — source of the gateway skill: the only skill installed
  locally, and the protocol agents follow to fetch the rest. Shared by the server
  (which serves it) and the CLI (which installs it).
- `.claude/skills/docs-architecture/` — the documentation convention: the rules, the
  templates, and (under `scripts/`) the validator that enforces them plus its tests.
  Repository-wide, and part of no subproject.
- `docs/` — product and technical documentation.
- `.github/workflows/` — one workflow serving each subproject, plus one for `docs/` and
  `.claude/` (which belong to no subproject). Each is gated by path filters, so a change
  to one does not run another's CI.

## Quick start

The server implements P0a: publish a skill, search it, read its manifest, its body and its
files, and serve the anonymous discovery channel. It needs a PostgreSQL database and a token
(the authorization server is P1, so there is no login yet).

```bash
cd skillmaster-server
scripts/init-test-db.sh  # creates the test database, once
./mvnw verify            # build and run the tests
./mvnw spring-boot:run   # then: curl localhost:8080/actuator/health
```

A walk-through of the whole loop, with the commands to run by hand, is in
[`skillmaster-server/README.md`](skillmaster-server/README.md).

Requires **JDK 25**. `mvnw` downloads its own Maven, so Maven itself needs no install.
Local setup beyond that is in
[`skillmaster-server/README.md`](skillmaster-server/README.md).

Nothing runs end to end yet — the server implements none of the API and the CLI does not
exist. What P0 delivers is in the
[v1-hosting technical design](docs/versions/v1-hosting/technical-design.md).

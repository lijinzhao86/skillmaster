# skillmaster

A hosting platform for AI-agent [skills](https://agentskills.io). The design keeps
skills on the server and exposes them over a remote API — agents search, read, and
use them on demand, so progressive disclosure is enforced server-side and no skill
copy is installed on the client.

**Status: in progress.** v1's first milestone is implemented — the server hosts skills and
serves them, and the browser pages sign up, sign in and reset a password. The CLI and the
OAuth token flow are not. The product and technical documents under [`docs/`](docs/) are the
source of truth — start at [`docs/README.md`](docs/README.md), or at the
[v1-hosting version record](docs/versions/v1-hosting/README.md) for what is built.

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

Three things to install: **JDK 25**, **Node 24** (see
[`skillmaster-web/.nvmrc`](skillmaster-web/.nvmrc)), and a container runtime —
`brew install colima docker docker-compose` on macOS, then `colima start`. PostgreSQL is
deliberately not on that list; it comes from the repository, so nobody has to install and
maintain an instance of it to work on this.

```bash
docker compose up -d            # PostgreSQL 16.15 on 127.0.0.1:5432, both databases created

cp .env.example .env            # local-only values; .env is gitignored
set -a && source .env && set +a

cd skillmaster-server
./mvnw verify                   # the whole suite, against that database
./mvnw spring-boot:run          # serves http://localhost:8080
```

The browser pages come up in another shell:

```bash
cd skillmaster-web
npm install
npm run dev                     # http://localhost:5173, proxying /web and /api/v1 to :8080
```

Then sign up at `http://localhost:5173/register`. With `SKILLMASTER_SMS_LOG_CODES=true`
from `.env`, the verification code is written to the server's log rather than sent — read it
from there. Nothing is texted or emailed locally, and the Aliyun sender is never reached.

`docker compose stop` keeps the data; `docker compose down -v` throws it away (the
initialisation scripts only run on an empty volume, so that is also how you rebuild it).

Requires **JDK 25**. `mvnw` downloads its own Maven, so Maven itself needs no install.
The hand-run walk-through of the API is in
[`skillmaster-server/README.md`](skillmaster-server/README.md); the frontend's toolchain is
in [`skillmaster-web/README.md`](skillmaster-web/README.md).

What v1 hosting delivers so far — and what it does not — is in the
[v1-hosting version record](docs/versions/v1-hosting/README.md).

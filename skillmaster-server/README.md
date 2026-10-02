# skillmaster server

The API and authorization server — one process serving the read API, the authorization
server, and blob storage ([technical design](../../docs/versions/v1-hosting/technical-design.md)
§2.2). **Java 25 (LTS) + Spring Boot 4 / Spring Security 7**
([ADR 0011](../../docs/decisions/0011-server-and-cli-stack.md)).

**P0a, P0c and M01 are implemented**: publishing a skill, the four read endpoints, search, the
gateway's discovery channel, and the browser plane — registration, login, logout and password reset
over `/web`, with a session cookie and CSRF over Spring Session JDBC
([ADR 0014](../../docs/decisions/0014-browser-session-via-spring-session.md)). A skill is addressed
as `namespace/name[@version]`
([ADR 0012](../../docs/decisions/0012-addressing-and-version-pinning.md)), so the detail response
hands out a URI per file with the version already written into it. **The `/api/v1` plane still
authenticates with a single static token, and all of `/oauth/*` is M2** — no tokens are issued yet.

The `/web` plane is an API: it answers with JSON. The pages a person actually types into are in
[`skillmaster-web/`](../skillmaster-web/README.md), which calls it from the browser. The phasing is in
[`technical-design.md`](../../docs/versions/v1-hosting/technical-design.md) §7.

## Toolchain

Needs **JDK 25**. `./mvnw` downloads its own Maven, so Maven itself needs no install:
CI, a laptop and the image all resolve the same Maven version from
`.mvn/wrapper/maven-wrapper.properties`. Its CI is
[`.github/workflows/server.yml`](../.github/workflows/server.yml), **currently disabled on
GitHub**.

## Build and run

The database comes from the repository's `compose.yaml` — see the
[root README](../README.md#quick-start) for the whole picture. From here:

```bash
docker compose -f ../compose.yaml up -d   # PostgreSQL 16.15, both databases
set -a && source ../.env && set +a        # after `cp .env.example .env`; see that file
./mvnw verify            # compile + tests
./mvnw spring-boot:run   # serves http://localhost:8080
curl localhost:8080/actuator/health
```

`.env` is what supplies the token and the two cipher keys; without them the application
refuses to start rather than falling back to a default, which is deliberate — see
`application.yml`. If you would rather run your own PostgreSQL than the container,
`scripts/init-test-db.sh` still creates the test database on it.

Maven picks its JDK from the environment, so on a machine with more than one installed,
point `JAVA_HOME` at 25 explicitly:

```bash
export JAVA_HOME=$(brew --prefix openjdk@25)   # Homebrew, macOS
```

## Walking the whole loop by hand

The sequence below is the same one [`ServerSmokeIT`](src/test/java/com/skillmasterai/api/ServerSmokeIT.java)
asserts, so if it works there it should work here. It needs a database the migrations can run
against, and a token:

```bash
export SKILLMASTER_AUTH_STATIC_TOKEN=$(openssl rand -hex 24)   # no default: see application.yml
# ...and the two cipher keys, which are also required; `source ../.env` supplies all of them.
./mvnw spring-boot:run
```

Then, in another shell, publish a skill and read it back at each level. `TOKEN` is the value
above, and the archive's directory must be named after the skill (§1.3):

```bash
TOKEN=...                                                   # same value as the export above
API=http://localhost:8080/api/v1
SKILL=demo/feishu-tasks                                     # namespace/name — the namespace is the owner's handle

mkdir -p /tmp/demo/feishu-tasks/references
printf -- '---\nname: feishu-tasks\ndescription: 飞书任务\n---\n# 飞书任务\n\n读 `references/fields.md`。\n' \
  > /tmp/demo/feishu-tasks/SKILL.md
printf '# 字段\n' > /tmp/demo/feishu-tasks/references/fields.md
(cd /tmp/demo && zip -qr /tmp/feishu-tasks.zip feishu-tasks)

# publish — 201 the first time, 200 with created:false if the content is unchanged
curl -sS -X POST "$API/skills" -H "Authorization: Bearer $TOKEN" \
  -F file=@/tmp/feishu-tasks.zip

# L1 — search, then the full manifest and no content. The detail resolves the version and writes
# it into every file's uri, which is what the two calls below follow.
curl -sS "$API/skills?q=飞书" -H "Authorization: Bearer $TOKEN"
curl -sS "$API/skills/$SKILL" -H "Authorization: Bearer $TOKEN"
VERSION=...                                                 # version.number from that response

# L2 and L3 — the bytes themselves. Drop the @VERSION and these resolve `latest` afresh instead,
# which is how a manifest and the bytes fetched from it come to disagree after someone publishes.
curl -sS "$API/skills/$SKILL@$VERSION/body" -H "Authorization: Bearer $TOKEN"
curl -sS "$API/skills/$SKILL@$VERSION/files/references/fields.md" -H "Authorization: Bearer $TOKEN"

# the anonymous discovery channel — no token, and a real 404 until the gateway is published
curl -sS "http://localhost:8080/.well-known/agent-skills/index.json"
curl -sS "http://localhost:8080/.well-known/skills/index.json"
curl -sS "http://localhost:8080/gateway/SKILL.md"
```

The last three have no `Authorization` header on purpose: that channel is how a machine that has
never logged in learns where to log in, so requiring a token would make it unreachable by the only
clients that need it (§1.5).

## Dockerfile

Multi-stage: `eclipse-temurin:25-jdk` compiles, `eclipse-temurin:25-jre` runs. The build
context is the repository root, because the image also carries `gateway/`.

**Unverified.** This image has never been built — the Docker daemon was not running and
Docker Hub was unreachable from the machine where the file was written, so even the
`eclipse-temurin:25` tags are unconfirmed.

## reference-python/

The pre-Java baseline, archived: a design reference for the Java rewrite, not built and
not tested. Its defects are catalogued in
[`known-issues.md`](../../docs/versions/v1-hosting/known-issues.md).

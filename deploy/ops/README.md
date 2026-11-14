# The operations service on the second ECS

The server's runbook is [`../README.md`](../README.md); this one covers only what is different
about `skillmaster-ops`. Why it is a separate service and database is
[ADR 0199](../../docs/decisions/0199-ops-runs-as-its-own-service-and-database.md).

## What runs

```
second ECS
  docker: skillmaster-ops
   127.0.0.1:8090  ← CI over SSH
   private address → RDS (skillmaster_ops) and → first ECS :8081 (/inner)
```

- One container, `restart: unless-stopped`, health check on `/actuator/health`.
- `mem_limit: 1g`. The machine is shared with MOSS (its container is capped at 2 GiB on a
  3.5 GiB host), and the image runs `-XX:MaxRAMPercentage=70`: without a cap the JVM would size
  its heap from the whole machine.
- **Nothing is published on a public interface.** The compose file maps the port to the host's
  loopback, and the security group opens 22 only.
- The image is built by CI. The host is a mainland instance and cannot reach Docker Hub, so the
  image arrives either from Aliyun ACR or as a file: `docker save | ssh docker load`.

## What lives where

| Path | What |
|---|---|
| `/etc/skillmaster-ops/ops.env` | environment, `0600 root`, written by the operator from the variables below (the `Ops` workflow only builds and publishes the image) |
| `/etc/skillmaster-ops/sync-sources.yml` | the import whitelist, `0644 root`, copied from this directory by hand |
| `/opt/skillmaster-ops/compose.yaml` | the compose file, copied from this directory |
| `/opt/skillmaster-ops/.env` | one line, `OPS_IMAGE=<tag>`: the image this host runs. Compose reads it from the project directory, so `docker compose up -d` alone is enough. It survives a `git` copy of the compose file, which is why the tag is not in that file |

## The four facts the service needs

| Variable | What it is |
|---|---|
| `OPS_DB_URL`, `OPS_DB_USER`, `OPS_DB_PASSWORD` | the `skillmaster_ops` database on the RDS instance; its whitelist holds this instance's **private** address |
| `OPS_SERVER_URL` | the first machine's **private** address and the inner port, e.g. `http://10.0.0.12:8081` |
| `OPS_INNER_TOKEN` | the same value the server holds as `SKILLMASTER_INNER_TOKEN`; the server compares it in constant time |
| `OPS_SOURCES_FILE` | default `/etc/skillmaster-ops/sync-sources.yml` |

`OPS_GITHUB_TOKEN` is required once a run has more than a handful of entries: unauthenticated
GitHub answers 60 requests an hour.

## Running one import

```bash
ssh <ops-host>
curl -fsS -X POST http://127.0.0.1:8090/inner/v1/jobs/import/runs \
  -H "X-Inner-Token: $OPS_INNER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"trigger":"manual"}'
curl -fsS http://127.0.0.1:8090/inner/v1/jobs/runs/<run_id> -H "X-Inner-Token: $OPS_INNER_TOKEN"
```

A second trigger while one is running answers `409 busy`; the mutex is a row lock, so a crashed
run does not leave a stuck flag.

## Rollback

Keep the previous image tag. `OPS_IMAGE=<previous> docker compose up -d` returns to it; the
database migrates forward only, and `V1` has nothing to undo.

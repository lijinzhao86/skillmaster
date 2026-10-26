# Deployment

What runs where, and how a new build reaches it. The decisions behind the shape are in
the ADRs this directory cites; this file is the runbook.

## The machine

One ECS instance (`ecs.u1-c1m2.large`, `cn-shanghai-b`), one public address, three names.

```
                     nginx (host, :80 -> :443)
  console.skillmasterai.com ─┬─ /srv/skillmaster/console   the console SPA's files
                             └─ /web, /api/v1, /oauth,
                                /.well-known, /gateway  ──► 127.0.0.1:8080
  www.skillmasterai.com     ─── /srv/skillmaster/site      the marketing page
  skillmasterai.com         ─── 301 to www

  127.0.0.1:8080 ── systemd: skillmaster.service ──► RDS PostgreSQL (VPC, non-public)
```

- The server binds `0.0.0.0:8080` but only the loopback is reachable, because the
  security group opens 80 and 443 and nothing else. The proxy is the only way in — the
  premise ADR 0018 attaches its forwarding rules to.
- **The server runs as a jar under systemd, not as a container.** The host is a mainland
  instance and cannot reach Docker Hub; Ubuntu 24.04 carries `openjdk-25-jre-headless`
  in its own repositories, so the jar runs on the host. `skillmaster-server/Dockerfile`
  is still built by CI on every merge so it stays correct for the day this needs a
  registry or more than one machine.
- The database is managed RDS, reachable only from inside the VPC. Its whitelist holds
  the ECS instance's **private** address, not the subnet.

## What lives where

| Path | What |
|---|---|
| `/opt/skillmaster/releases/<sha>/app.jar` | one release, kept so it can be returned to |
| `/opt/skillmaster/current` | a symlink to the release in service |
| `/etc/skillmaster/server.env` | the environment, `0600 root`, **rendered by the deploy workflow** |
| `/etc/skillmaster/tls/fullchain.pem`, `privkey.pem` | symlinks into `/etc/letsencrypt/live/` |
| `/etc/nginx/conf.d/skillmaster.conf` | the proxy, copied from `deploy/nginx/` |
| `/srv/skillmaster/console`, `/srv/skillmaster/site` | the two front-end builds |

Production configuration has exactly one source: the repository's Actions variables and
secrets, rendered into `server.env` by every deployment. Editing that file on the host
does not survive the next deploy — deliberately.

## Plugins the database needs

Migrations are applied by the application's account, which on RDS is an ordinary account.
Managed PostgreSQL does not let an ordinary account create extensions — `CREATE EXTENSION`
belongs to `rds_superuser` — so the first v19 deployment died inside the migration:

```
ERROR:  permission denied to create extension "vector"
HINT:   Must be superuser or user with all of rds_superuser to create this extension.
```

The deploy job caught it at the health probe and returned production to the previous
release on its own; nothing was left half-applied, because PostgreSQL runs a migration's
statements in one transaction.

**So installing an extension is bootstrap, not deployment** — once per database, in the
console, with the instance's privileged account (`dms_user_…` on this instance: the one
DMS created for its own secure access, whose password DMS manages — use it from DMS, never
from the application or CI):

```sql
-- connected to the `skillmaster` database, as the privileged account
CREATE EXTENSION IF NOT EXISTS vector;
SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';
-- vector | 0.8.0.2   (2026-10-25)
```

**Why this is not in the workflow.** Putting it there would mean carrying a standing
superuser credential in GitHub and sending it to the host on every deployment, so that an
operation that happens once can happen again every time. The failure it would prevent is
not silent: the migration stops the release, the job rolls back, and the log names the
statement — and the fix is this section.

**The migration keeps its own `CREATE EXTENSION IF NOT EXISTS vector;`, and once the
extension exists that line is a no-op for the application's account.** Verified against a
non-superuser role on a database where the extension was installed: PostgreSQL answers
`NOTICE: extension "vector" already exists, skipping` and never reaches the privilege
check. The control — the same statement for an extension that is *not* installed — fails
with `permission denied`, which is what makes the no-op a property of `IF NOT EXISTS` and
not of the environment.

The same rule covers any later extension: install it here first, then deploy.

## The CLI's client row

`skillmaster login` starts a sign-in request that the server resolves against `oauth_client`. With no
row for `skillmaster-cli` the request is a 500 —

```
java.lang.IllegalStateException: no oauth_client row for 'skillmaster-cli' —
this deployment cannot start a login. Seed it (see the server README).
```

— which the consent page can only report as 服务暂时不可用. Seeded by hand, once per deployment, and
the redirect URI has to match the CLI's fixed loopback port exactly (ADR 0028):

```sql
INSERT INTO oauth_client (client_id, name, registration, redirect_uris, grant_types,
    client_secret_hash, metadata, created_at) VALUES
  ('skillmaster-cli','SkillMaster CLI','preregistered',
   '["http://127.0.0.1:51004/callback"]','["authorization_code","refresh_token"]',
   NULL, '{"scopes":["skills:read","skills:write"]}', '2026-01-01T00:00:00Z');
```

`client_secret_hash` stays NULL: the CLI is a public client, and PKCE stands in for a secret. A row
that asks for the retired `client_credentials` grant stops the server from starting, so seed this one
and not an older one.

## Deploying

A merge to `main` runs the tests, packages the jar, asserts it carries **no** `gateway/` assets
(the startup journey lives in the platform's `skillmaster/startup` skill — ADR 0096), and builds
the image (not pushed). **It does not reach production.**

Production changes only from `Actions → Server → Run workflow`:

- `release`: `new` (default) builds the ref you picked and deploys it under its commit
  sha; naming an existing release points production back at it — that is the rollback.
- The deploy keeps the five most recent releases and deletes older ones.

The switch itself is one rename of the `current` symlink, so a running server is never
left pointing at half a directory. The job ends by asking the server for its discovery
document: a process that started is not the same thing as a service that answers.

## Rolling back by hand

If Actions is unavailable and a release must be reverted now:

```bash
ssh root@<host>
ln -sfn /opt/skillmaster/releases/<sha> /opt/skillmaster/current.new
mv -T /opt/skillmaster/current.new /opt/skillmaster/current
systemctl restart skillmaster
```

Migrations are forward-only (Flyway, applied at startup). **A rollback across a release
that added a migration is not a plain symlink swap** — check that the older jar tolerates
the newer schema before doing it.

## The front-end

Static, no process, and **deployed on its own**: `Console` and `Site` are separate
workflows with separate path filters, so a console change runs the console's checks and
nothing else. Each has its own manual `Run workflow`, and deploying one never touches the
other or the server.

The same releases-and-symlink arrangement as the server keeps a broken build from
sticking:

```
/srv/skillmaster/console          -> console-releases/<sha>/
/srv/skillmaster/site             -> site-releases/<sha>/
```

Each deploy lands in its own directory, moves one symlink, probes the name and switches
back if it does not answer. The five most recent are kept.

**The probe runs on the host, not on the runner** (2026-10-25): it asks nginx through
`--resolve …:127.0.0.1` for the real name, so nginx, the certificate and the files are all
covered. It used to ask the public URL from the runner, and every TLS attempt from a
GitHub runner to this host was reset between the ClientHello and the ServerHello while SSH
and rsync to the same host worked — the deploy then rolled back a build that was fine. A
console deploy cannot break public reachability (the names, the proxy and the certificate
do not change), so the probe asks the question this job can answer.

`VITE_CONSOLE_URL` comes from `skillmaster-site/.env.production`; `vite.config.ts` still
refuses to build without it, which is what keeps the page from shipping links to a machine
that is not there.

Doing it by hand, when Actions is unavailable:

```bash
(cd skillmaster-console && npm run build)
ssh root@<host> "mkdir -p /srv/skillmaster/console-releases/manual"
rsync -a --delete skillmaster-console/dist/ root@<host>:/srv/skillmaster/console-releases/manual/
ssh root@<host> 'ln -sfn /srv/skillmaster/console-releases/manual /srv/skillmaster/console.new &&
                 mv -T /srv/skillmaster/console.new /srv/skillmaster/console'
```

## Checking it is up

```bash
curl -fsS https://console.skillmasterai.com/.well-known/oauth-authorization-server | head
curl -fsS -o /dev/null -w '%{http_code}\n' https://www.skillmasterai.com/    # 200
curl -fsS -o /dev/null -w '%{http_code}\n' https://skillmasterai.com/        # 301
```

The three answer different questions: the first that the server started and its issuer
matches `SKILLMASTER_PUBLIC_BASE_URL`, the second that the site's files are in place,
the third that the apex redirect survived.

## Certificates

Let's Encrypt, one certificate covering the three names, renewed by certbot's timer. The
deploy hook `/etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh` reloads nginx after
a renewal, which is what keeps the running server from serving a stale certificate.

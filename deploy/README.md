# Deployment

What runs where, and how a new build reaches it. The decisions behind the shape are in
the ADRs this directory cites; this file is the runbook.

## The machine

One ECS instance (`ecs.u1-c1m2.large`, `cn-shanghai-b`), one public address, four names.

```
                     nginx (host, :80 -> :443)
  console.skillmasterai.com ─┬─ /srv/skillmaster/console   the console SPA's files
                             └─ /web, /api/v1, /oauth,
                                /.well-known, /gateway  ──► 127.0.0.1:8080
  www.skillmasterai.com     ─── /srv/skillmaster/site      the marketing page
  skillmasterai.com         ─── 301 to www
  dl.skillmasterai.com      ─── proxy   ──► skillmaster-dl.oss-cn-hangzhou.aliyuncs.com
                                            (the CLI packages, uploaded by cli-release.yml)

  127.0.0.1:8080 ── systemd: skillmaster.service ──► RDS PostgreSQL (VPC, non-public)
```

`dl.` is the only name whose content this machine does not hold. OSS would not bind it as
a custom domain, so nginx is the front door and the bucket is the origin — see
[Certificates](#certificates) for what that costs.

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
| `/etc/skillmaster/tls/fullchain.pem`, `privkey.pem` | symlinks into `/etc/letsencrypt/live/skillmasterai.com/` |
| `/etc/skillmaster/tls-dl/fullchain.pem`, `privkey.pem` | symlinks into `/etc/letsencrypt/live/dl.skillmasterai.com/` |
| `/etc/skillmaster/acme/{auth,cleanup}.sh` | the DNS-01 hooks, copied from [`acme/`](acme/) |
| `/etc/skillmaster/acme.env` | their credentials, `0600 root` |
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

`skmr login` starts a sign-in request that the server resolves against `oauth_client`. With no
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
curl -fsS -o /dev/null -w '%{http_code}\n' https://dl.skillmasterai.com/install.sh   # 200
```

The first three answer different questions: the server started and its issuer matches
`SKILLMASTER_PUBLIC_BASE_URL`, the site's files are in place, and the apex redirect
survived. The fourth that the download name reaches the bucket through the proxy.

## TLS 与备案

**Some clients cannot open TLS at all to the four names, and the cause is not nginx.** Measured
2026-10-28 from a European exit IP:

| Probe | Result |
|---|---|
| TLS 1.3 + SNI `www.skillmasterai.com` | handshake completes, `HTTP/1.1 200` |
| TLS 1.2 + the same SNI | **RST right after ClientHello** |
| TLS 1.2, **no** SNI | completes |
| TLS 1.2 + another SNI (`example.com`) to the same IP | completes |
| `http://www.skillmasterai.com/` | `403`, `Server: Beaver`, title `Non-compliance ICP Filing` |
| `http://47.117.36.14/` (no Host) | `301` — our nginx |

**The 1.2-only clients are the ones that break**: macOS's own curl (libcurl on
SecureTransport/LibreSSL 3.3.6, no TLS 1.3) and PowerShell on Windows (Schannel, 1.2). They
report `curl: (35) Recv failure: Connection reset by peer`. Browsers, the Go CLI and anything
else that negotiates 1.3 are fine — which is why the site "works" while its install command does
not.

**Why it is not `ssl_protocols`.** `deploy/nginx/skillmaster.conf` lists `TLSv1.2 TLSv1.3`, and
the no-SNI row above proves nginx does complete a 1.2 handshake (it falls back to the default
server). A configuration that refused 1.2 would answer with an alert, not a reset. The reset
arrives before nginx is reached, keyed on the SNI; the 403 on port 80 is Aliyun's filing
compliance appliance, and the same appliance reads plaintext SNI in a 1.2 ClientHello but does
not parse a 1.3 one. This is also what the 2026-10-25 note above describes ("every TLS attempt
was reset between the ClientHello and the ServerHello") — same appliance, narrower symptom.

**So the fix is the ICP filing, not a protocol list.** Adding 1.0/1.1 would be reset the same way.

**Meanwhile the install page points at the bucket's own name**
(`skillmaster-dl.oss-cn-hangzhou.aliyuncs.com`), which answers 1.2 fine — verified end to end
with the Mac's system curl: the script downloads, the SHA-256 matches, and it installs the same
build. **After the filing completes, switch
[`skillmaster-site/public/install/skillmaster.md`](../skillmaster-site/public/install/skillmaster.md)
back to `dl.skillmasterai.com`** and re-run the check:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' https://dl.skillmasterai.com/beta/checksums.txt   # want 200, not a reset
curl -sS -o /dev/null -w '%{http_code}\n' http://www.skillmasterai.com/                      # want 301, not the 403 block page
```

## Certificates

Let's Encrypt, one certificate covering the three names, plus a second one for `dl.`,
renewed by certbot's timer. The deploy hook
`/etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh` reloads nginx after a renewal,
which is what keeps the running server from serving a stale certificate.

**A renewal must use DNS-01, not HTTP-01** (2026-10-26). Every ACME request on port 80
is answered `403` by Aliyun's ICP check before it reaches nginx — `www.` and `console.`
included — so `http-01` cannot complete and `standalone` cannot either:

```
47.117.36.14: Invalid response from http://www.skillmasterai.com/.well-known/acme-challenge/…: 403
```

**Both certificates are issued with `--manual --preferred-challenges dns`**, and their
renewal configurations remember the hooks `/etc/skillmaster/acme/{auth,cleanup}.sh`
(sources in [`acme/`](acme/)). Those scripts write and remove a `_acme-challenge` TXT
record through the Aliyun CLI, using credentials in `/etc/skillmaster/acme.env`
(`0600 root`) that belong to a RAM user whose only rights are `alidns:DescribeDomainRecords`,
`AddDomainRecord` and `DeleteDomainRecord`.

**The bucket keeps every release under `releases/<version>/`.** Staging happens there and is never
deleted, because it is also where an older build can be fetched from by hand; it is public like the
rest of the bucket (a few tens of MB per release). Clearing old prefixes is a manual OSS operation.

**The repository is the source; the host runs its own copy.** `deploy/acme/*.sh` and
`deploy/nginx/skillmaster.conf` are copied to `/etc/skillmaster/acme/` and `/etc/nginx/conf.d/` by
hand, so `sh -n` passing in CI says nothing about the file that runs at renewal time. After an
edit, copy it and compare:

```bash
diff <(ssh root@<host> 'cd /etc/skillmaster/acme && sha256sum auth.sh cleanup.sh; sha256sum /etc/nginx/conf.d/skillmaster.conf' | cut -d' ' -f1) \
     <(sha256sum deploy/acme/*.sh deploy/nginx/skillmaster.conf | cut -d' ' -f1)
```

The hashes are compared, not the printed lines: the two sides print different paths, so a raw
`diff` always differs and tells you nothing.

**The hooks need three commands on the host**: `aliyun` (the CLI, `/usr/local/bin`), `python3`
(cleanup.sh parses the record list), and `dig` (auth.sh polls the zone's nameservers, from
`bind9-dnsutils`). A rebuilt host without them installs the hooks, passes `nginx -t`, and then
fails every renewal at the poll:

```bash
command -v aliyun python3 dig
certbot renew --cert-name skillmasterai.com --dry-run     # the only proof that they work
```

**A dry run waits before it works, and the wait is not DNS.** `certbot renew` sleeps
`random.uniform(1, 480)` seconds before doing anything — once **per invocation**, so that hosts
sharing a timer never hit Let's Encrypt at the same moment (certbot 2.9, `_internal/renewal.py`).
Two certificates rehearsed as two `--cert-name` calls therefore sleep twice: up to 16 minutes of
waiting for about 15 seconds of ACME work each. Watching a rehearsal, add
`--no-random-sleep-on-renew`. Leave the timer alone — there the randomisation is the point.

Re-issuing one by hand, when a name is added:

```bash
certbot certonly --manual --preferred-challenges dns \
  --manual-auth-hook /etc/skillmaster/acme/auth.sh \
  --manual-cleanup-hook /etc/skillmaster/acme/cleanup.sh \
  -d skillmasterai.com -d www.skillmasterai.com -d console.skillmasterai.com \
  --cert-name skillmasterai.com --force-renewal
certbot renew --cert-name skillmasterai.com --dry-run    # proves the hooks still work
```

The three-name certificate was converted this way on 2026-10-26; before that it renewed
with `standalone` and would have expired on 2027-01-22 without ever being able to renew.

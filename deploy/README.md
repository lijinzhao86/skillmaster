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

## Deploying

A merge to `main` runs the tests, packages the jar, asserts it carries the gateway skill,
and builds the image (not pushed). **It does not reach production.**

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

Static, no process. Built on a machine with the toolchain and copied up; `VITE_CONSOLE_URL`
must be set for the site build or `vite.config.ts` refuses.

```bash
(cd skillmaster-console && npm run build)
(cd skillmaster-site && VITE_CONSOLE_URL=https://console.skillmasterai.com npm run build)
rsync -a --delete skillmaster-console/dist/ root@<host>:/srv/skillmaster/console/
rsync -a --delete skillmaster-site/dist/    root@<host>:/srv/skillmaster/site/
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

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
  instance and cannot reach Docker Hub, so the base image in
  `skillmaster-server/Dockerfile` cannot be pulled there. Ubuntu 24.04 carries
  `openjdk-25-jre-headless` in its own repositories, so the jar runs on the host. The
  Dockerfile stays correct and stays what CI builds; it is simply not in this machine's
  path today.
- The database is managed RDS, reachable only from inside the VPC. Its whitelist holds
  the ECS instance's **private** address, not the subnet.

## What lives where

| Path | What |
|---|---|
| `/opt/skillmaster/app.jar` | the server, owned by the `skillmaster` user |
| `/etc/skillmaster/server.env` | the environment, `0600 root`; secrets never enter the image or git |
| `/etc/skillmaster/tls/fullchain.pem`, `privkey.pem` | symlinks into `/etc/letsencrypt/live/` |
| `/etc/nginx/conf.d/skillmaster.conf` | the proxy, copied from `deploy/nginx/` |
| `/srv/skillmaster/console`, `/srv/skillmaster/site` | the two front-end builds |

## Deploying a build

```bash
# 1. The jar (built on a machine with the toolchain: ./mvnw -B -DskipTests package)
scp skillmaster-server/target/skillmaster-server-*.jar root@<host>:/opt/skillmaster/app.jar
ssh root@<host> 'chown skillmaster:skillmaster /opt/skillmaster/app.jar'

# 2. The front-ends. VITE_CONSOLE_URL must be set for the site build or vite refuses.
(cd skillmaster-console && npm run build)
(cd skillmaster-site && VITE_CONSOLE_URL=https://console.skillmasterai.com npm run build)
rsync -a --delete skillmaster-console/dist/ root@<host>:/srv/skillmaster/console/
rsync -a --delete skillmaster-site/dist/    root@<host>:/srv/skillmaster/site/

# 3. Restart
ssh root@<host> 'systemctl restart skillmaster'
```

A restart applies the schema migrations (Flyway, forward-only). **Rolling back across a
release that added a migration is not a plain jar swap** — check that the previous
version tolerates the newer schema before replacing the jar.

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

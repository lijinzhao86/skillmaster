# skillmaster web

The browser client for the three account flows — sign up, sign in, reset a password — over the
server's `/web` plane ([technical design](../../docs/versions/v1-hosting/technical-design.md) §4.4).
**Vue 3 + Vite + TypeScript** ([ADR 0015](../../docs/decisions/0015-web-frontend-stack.md)).

It adds no server capability and calls no endpoint the server did not already have: every route it
uses is one the M01 iterations built. What was missing was a page a person could type into — the API
alone answers with JSON, which is fine for a script and useless for signing in.

**Implemented and tested, and its wire format has been exercised against a real server — but not in
a browser.** `npm run type-check`, the 124 tests and `npm run build` all pass.

The 124 tests stub `fetch`, so they only prove the client agrees with itself. What closed that gap was
a `curl` walkthrough through the Vite proxy — register, log out, log in, reset a password, plus every
refusal shape — checking field names, status codes, error codes and `Retry-After` against the real
server. **The API contract holds; the browser layer does not have that evidence**, because no browser
has loaded this yet. Both halves of that state, and what is left to do, are in
[`test-plan.md`](../../docs/versions/v1-hosting/test-plan.md) §结果 and §已知问题.

## Toolchain

Needs **Node 24**, from [`.nvmrc`](.nvmrc). TypeScript is pinned to **5.9.3** on purpose: `vue-tsc`
patches TypeScript's internals and only declares `typescript: >=5.0.0`, which is a range nobody has
tested against the 7.x Go port.

```bash
nvm use            # reads .nvmrc
npm ci             # exactly the lockfile
```

No global installs: `vite` and `vue-tsc` come from `devDependencies`, so `npm run` is the whole
interface. `.npmrc` sets `engine-strict=true`, so a wrong Node version fails at install rather than
at some later confusing moment.

## Build and run

```bash
npm run dev          # http://localhost:5173
npm run type-check   # vue-tsc --noEmit
npm run test         # vitest, once
npm run build        # writes dist/
```

Its CI is [`.github/workflows/web.yml`](../.github/workflows/web.yml). It needs no database and no
server, so it can run while the server workflow is stopped.

## Running it against a local server

Two processes. The server first, with the SMS codes going somewhere readable:

```bash
docker compose -f ../compose.yaml up -d                       # PostgreSQL, both databases — see the root README
cd ../skillmaster-server
# `.env.example` at the repository root holds this same set ready to source; the explicit
# exports below are the same thing written out, for when you would rather see each value.
export SKILLMASTER_AUTH_STATIC_TOKEN=$(openssl rand -hex 24)   # the API plane's token, not the browser's
export SKILLMASTER_SMS_LOG_CODES=true                         # prints each code; without it the fallback sender refuses
export SKILLMASTER_PHONE_HMAC_KEY=$(openssl rand -hex 16)      # neither key has a default, by design:
export SKILLMASTER_PHONE_ENC_KEY=$(openssl rand -hex 16)       # startup fails without them
./mvnw spring-boot:run                                        # http://localhost:8080
```

Those last two are what a phone number is stored under, so they have no default — a default key
would be a key everybody reading this repository knows, and `phone_hash` is indexed and unique.
Anything at least 32 bytes long will do locally; the encryption key must be exactly 32.

Then the client:

```bash
npm run dev                                                   # http://localhost:5173
```

**Open 5173, not 8080.** Vite proxies `/web` and `/api/v1` to 8080, so the browser sees one origin
for both — which is what the cookies require. Nothing needs cookie rewriting: neither the session
cookie nor the CSRF cookie carries a `Domain`, and neither is `Secure` outside production. Open 8080
instead and the paths exist but nothing serves the pages.

Read the SMS code out of the server's log, type the captcha from the picture, and the flows are the
ones described in [`test-plan.md`](../../docs/versions/v1-hosting/test-plan.md) §用例.

## What is deliberately not here

No router, no state library, no UI kit, no i18n, no linter. Each is a decision with a reason rather
than an omission, and the reasons are in ADR 0015. The shape that follows from them is worth knowing
before adding anything: `App.vue` picks a page by reading `window.location.pathname`, links are real
`<a href>` and navigation is a full page load.

**A deployment consequence.** Because those paths belong to the SPA (`/login`, `/register`, `/reset`)
rather than to the server, a production reverse proxy must not have `/web/**` swallow them, and it
needs a fallback so a refresh on `/register` is not a 404. The nginx snippet is in ADR 0015, marked
**unverified** — there is nowhere to deploy yet, so it has never been run.

# skillmaster CLI

The client that holds credentials and fetches skills on demand. It is the only
component that covers unattended use, and it is what installs the gateway skill.

**Implemented** (2026-10-07; the unattended login was removed in v3 — ADR 0038 §决定 9 — and the
paste-a-key login in v4, [ADR 0040](../docs/decisions/0040-api-key-in-the-cli-now-the-browser-path-later.md)
§决定 2 being superseded): §4.6's command set — `login` (a browser page and a one-time code),
`logout`, `setup`, the `skills` group: `skills list`, `skills search`; and the `skill` group for
one skill: `skill invoke`, `skill files`, `skill read`, `skill versions`, `skill submit`,
`skill share`, `skill download` (v9), and — since v10 — `skill create`, `skill shares`,
`skill unshare`, `skill request`, `skill open`. v6 added the
`version` group on the author plane: `version list`, `version show`, `version diff`,
`version rename`, `version note`, `version discard`, `version open`. `skill submit` takes `--name`
(the version's author-facing name; **optional since v9** — a package from `skill download` carries it,
see below) and an optional `--note`; the version number is
assigned in the browser at publish time, so a `version:` in `SKILL.md` is carried through as content
and does not name the release. **2026-10-22:** the collection verbs moved to `skills`
(`skills list` / `skills search`); because the product has not shipped yet, the old
`skill list` / `skill search` names do not enter the formal command surface (ADR 0077).

**v9 took the round trip** (ADR 0068, ADR 0069): `skill download <ns>/<name>[@pin] [--into <dir>]`
fetches one whole version as a directory in a single request — the server builds the zip, this side
places it where asked, and a failure leaves no half a skill behind. The package carries
`.skillmaster/metadata.json`, a sidecar holding the version's name and note; it is **not** content
(the manifest, the digest and the caps all exclude it), so re-submitting a package returns the same
digest. Which is why `submit`'s `--name` became optional: given none, the name comes from the
package's sidecar, and an explicit `--name` still wins. A package with a broken sidecar is refused by
name rather than submitted with a guessed one. `submit` also learned to read a `409 skill_deleted`:
a deleted skill's name is not available, and the message says so instead of the generic
`服务端答 409`.

**v14 added the library and the two tag layers** ([ADR 0079](../docs/decisions/0079-cli-manages-tags-and-library-references.md)–[0084](../docs/decisions/0084-l1-search-extension.md)).
`skills list` is now the caller's **library** — skills they created, were shared, or joined — with
`q` and the `--category` / `--my-tag` / `--public-tag` / `--source` / `--sort` / `--show` /
`--verbose` filters. It defaults to the consumable entries and 50 rows; `--show deleted` is the
author's own deleted rows and refuses the tag filters. `skills search` narrows to the **public
domain** and needs a keyword, a category or a public tag; it is one page by contract.
`skills categories`, `skills public-tags [--all]` and `skills my-tags [q]` print the three
vocabularies (Markdown tables, one word per row), and `skills my-tags add` / `rename` / `describe`
write tag definitions without attaching anything. On one skill, `skill tags` reads the three groups,
`skill tag add` / `remove [--public]` attach and detach, `skill category set` / `clear` move it
between shelves, `skill join` / `leave --yes` manage the library reference, `skill show` reads the
whole card and `skill restore` brings a soft-deleted skill back. **Deleting a tag definition is not
here**: it takes every attachment, including the sleeping ones, so it stays behind the console's red
dialog. The seven read commands take `--no-login`, which fails with the next step instead of opening
a browser; every new command refuses `--json`. `skill join` and `skill leave` print the consequence
and the full command unless `--yes` is given, and send nothing.

**v10 closed the lifecycle** (create: [ADR 0070](../docs/decisions/0070-creating-a-skill-creates-its-first-draft.md); requests: [ADR 0073](../docs/decisions/0073-asking-for-access-splits-by-plane.md); handover: [ADR 0072](../docs/decisions/0072-the-handoff-points-at-the-page-that-owns-the-action.md)): `skill create <name> --description <text> [--name <version>]`
starts a skill and its first draft without a local directory; any write command accepts `@new` as its
target and creates the draft with that first change (`--name` required, `--from` picks another base,
`cp` excluded because the server's create takes write / delete / rename only); `skill shares` and
`skill unshare --user` read and withdraw collaborations; `skill request --role | --status | --cancel`
asks for a rung (a write action: a `skills:read`-only key cannot ask — the server answers 403);
`skill open` prints and opens the skill's page. The page-only verbs now hand over to
the page that owns them: publish / rollback / revoke / restore keep `version open --sub actions`
(the pane those buttons live in), while offline / online / delete point at
`skill open <ns>/<name> --tab settings`, where their buttons are. Since v11 the publish handoff may
also carry `--bump patch|minor|major`, which the version page uses to preselect one of its three
publish choices; it is a preselection, never an execution (ADR 0074).

**The grammar is singular/plural.** `skill` takes one skill's address; `skills` is the collection
(`skills list`, `skills search`) and takes none. Before the split, `list` and `ls` both lived under
`skill` and only a letter apart answered different questions — one listed skills, the other a
version's files. Since this command surface has not shipped, those old names are removed rather than
kept as aliases; the help advertises only `skills ...` and `skill files`. The shape is
`claude mcp list` / `gh pr list`. `login`, `logout` and `setup` act on *this machine* — a credential,
a keychain entry, the skills directory — rather than on a skill in the registry.

**`invoke` and `read` are the host's `Skill` and `Read`**, and the split between them is not cosmetic:
`invoke` loads a body and *pins the version it loaded*, `read` uses that pin. A bare address means
"whatever is current", and the pointer moves, so a `read` that skipped `invoke` would answer about a
different version than the body in hand — which is why the pin exists and why it lives in the user's
config directory (`pins-<server>`, beside the credential — `~/Library/Application Support/skillmaster`
on macOS, `~/.config/skillmaster` on Linux) rather than in the caller's text
([ADR 0035](../docs/decisions/0035-the-pin-lives-on-the-client-machine.md)). The two renderings differ
the way the host's two do: **`read` numbers every line** (`<number>\t`, in the file's own numbering, so
the `--offset` it hands back can be checked), and **`invoke` adds nothing at all** — a body is
instructions, and it is the one output whose bytes a caller may want to hold against a digest.

**`files` is the enumeration the host gets from `ls`, and there is no local directory here to run one
in.** Locally a skill is a directory, so listing it is a shell command; the host's own skill load hands
over the body plus `Base directory for this skill: <dir>` and no file list (verified against the
installed CLI, 2.1.292), which works because those files were unpacked onto disk. Nothing is unpacked
here ([ADR 0001](../docs/decisions/0001-server-authoritative.md)), so the listing is a command of ours
([ADR 0036](../docs/decisions/0036-enumeration-is-a-read.md)) — printing the manifest the detail
endpoint was already answering with, and which `invoke` was already receiving and discarding. Every
relpath goes on a line of its own, because that line is what `read` takes next; the body and the binary
entries get a note, and nothing else does. It prints no `uri` and no digest — one is a whole version-pinned URL and the other is 71
characters of hex, and `read` needs neither of them (a relpath plus the pin is enough to locate bytes). It resolves the pin exactly as `read`
does, and it reaches nothing `read` cannot: one endpoint, one authorization, so a caller who may not
read the skill gets the same 404 from both.

**Run against a live server, end to end, on 2026-10-05 and 2026-10-06.** The login half was driven for
real in a browser: authorization → consent → loopback callback → PKCE exchange → Keychain. The read
half was `search` → `describe` → `show` → `get` against a running server, and the write half was
`submit` plus 上线 clicked on the page it opened. **That path was the old read surface**, and it is
gone twice over: `list` replaced `describe` and `show` (ADR 0034's round), and `invoke`/`read`/`versions`
replaced `get` (ADR 0035's). **Almost nothing on the current read surface has been through that kind of
run** — not `list`, not `share`, not `submit --to`. The exception is the read chain itself:
`invoke` → `files` → `read` was driven against a real server, a real database and a real credential on
2026-10-07, including a version pinned in the address, a path that does not exist, and a skill the
account may not read (the same 404 `read` gives). They have unit
and integration tests on both sides of the wire; see
[`test-plan.md`](../docs/versions/v1-hosting/test-plan.md) §结果 for exactly what is and is not
evidence.

**The handover to the browser has since been driven by hand** (2026-10-06): `submit` against a running
server and a real dev database, the draft line it printed, the page it opened, and 上线 clicked there —
after which the consumption plane answered with the new version, and `@1` still resolved with its own
description and its own file count rather than the new one's. Six steps and what each showed are in
[`test-plan.md`](../docs/versions/v1-hosting/test-plan.md) §结果. **What that run did not cover is the
rollback** — publishing an older version a second time; it is pinned by an integration test with
mutation verification, but the browser has not been clicked through it.

**`submit` cannot publish, and that is the design** ([ADR 0031](../docs/decisions/0031-submitting-and-publishing-are-two-actions.md)).
A submission lands a **draft**: the pointer does not move, nothing the consumption plane can read
changes, and the command's last act is to hand that person the address where they can look at it and
approve it — opening the browser on it, and printing it when the open fails or when there is nothing
to approve (the content is already live, or was discarded). The link carries no credential — a URL
copied out of a terminal is not a way in — and there is no flag that would let this command do the
approving itself. That is not a gap
to fill later: publishing is what every agent reading the API will get, and it is deliberately a
browser act with a session cookie and a CSRF token behind it.
**`skill versions` and `version list` are two questions.** The first is the consumer asking what it
may invoke (published versions only); the second is the author asking what exists (drafts, revoked
and discarded rows included, filtered by the caller's rung). They read different contracts, so the
same skill can legitimately give two different row counts.

**The `version` group manages drafts; it never changes what a caller reads.** `rename`, `note` and
`discard` touch only a draft; every write takes an explicit `<ns>/<name>@<pin>`, and a digest pin is
refused locally in favour of the draft's stable `draft:<id>`. `discard` prints the consequence and
the full command and exits non-zero unless `--yes` is given. Publishing, rollback, revoke, restore,
and whole-skill offline/online/delete have no command and no author-API route: they are confirmed by
a person in the browser. Pressing `publish` / `rollback` / `revoke` / `restore` here prints the
`version open <address> --sub actions` command for that version's page (and `publish` relays a
`--bump` you gave it); `offline` / `online` / `delete` print
`skill open <ns>/<name> --tab settings`, where their buttons are. `version open` itself takes
`--sub record|diff|actions` (record is the default and is not written to the address) and
`--bump patch|minor|major`, so the address can land on the right pane with a publish choice already
selected. `--json` is the machine contract on
every verb in this group (including `version open`), one stable object per command, with failures as
`{"ok":false,"error":{code,message,hint,server_code,issue}}` on stderr.

**Not written**: rollback — §4.6 does not list it. The rest of §4.3's author-plane endpoints
(metadata PATCH, soft delete, restore) are still absent; v6's version history is on
`/api/v1/author/...` and is what the `version` group reads. The page-only actions are absent **by
design**, not by omission — the CLI cannot publish (ADR 0031), delete a skill (ADR 0046), or change
any version's visibility (ADR 0061).

**Nor is searching inside a version's files** — the `grep` that would be the host's other enumeration
tool. Its shape is settled and it needs no schema change (walk the manifest, skip `is_binary`, read the
blobs, match literally in Java — `DiffService` is the precedent), but the caps and their visible
truncation, the line numbers having to agree with what `read --offset` prints, and the ADR that answers
[ADR 0006](../docs/decisions/0006-search-server-side.md)'s "never index the body or the files" are a
round of their own. `files` is the half that needed no new endpoint, so it went first.

**Five behaviours worth knowing before reading the code**, because none is obvious from the command
list:

- **`login` ends the authorization it replaces.** The second sign-in on a machine revokes the first
  one's, so a machine holds at most one live authorization. The order is *log in, then revoke* — a
  failed login must leave you exactly as signed in as you were — and a revocation that fails is a
  warning rather than an error, because the new credential in hand already works. Without it the
  first authorization's only copy of its refresh token is overwritten and it can never be revoked
  from here again.
- **Consent is remembered per (client, account)**, so it is asked once and never again: the second
  and later logins complete with nothing to click. That is the framework reading back the consent
  record, and it is why a `login` can look like it did nothing.
- **A 401 costs one renewal, not a re-login.** The expiry this CLI stores is its own arithmetic, and
  it can be wrong: a token is revocable while it still has fifty minutes to run (a password reset
  revokes every authorization the account had). So a refused token is replaced once — with
  `auth.Renew`, which ignores the stored expiry because the server has just contradicted it — and the
  command retried. A second refusal means the chain is genuinely gone: `auth` deletes the credential
  and the message names `skillmaster login`. **403 is deliberately not in this path**:
  `insufficient_scope` means the token is fine and the grant is too small, and signing in again would
  produce the same scopes.
- **Both stores are keyed by server, and the file had to be made to match.** The keychain entry's
  account name is the server URL, so a machine signed in to two — somebody's local stack and the real
  one — keeps two entries. The `0600` fallback file used to be a single shared path, which broke that
  in the direction that costs something: the load path would serve the *other* server's credential, so
  its bearer token went to an origin that never issued it, and the 401 that followed presented its
  refresh token there, read `invalid_grant`, and deleted it. Using one server signed the other out.
  The file is now `credentials-<hash of the server>` beside the refresh lock, which is hashed the same
  way for the same reason. **The pin file follows the same rule** (`pins-<hash>`), for a weaker reason
  that still holds: nothing in it is a secret, but a pin resolved against one server and sent to
  another resolves to nothing — or, if the names collide, to some other skill's version.
- **`setup` installs the gateway under the name the skill declares, not a name of its own.** §1.1
  makes `name` equal to the parent directory a MUST, and this repository obeys it wherever it controls
  a layout — that is why the source lives at `gateway/skillmaster/`. The install was the one place it
  could be broken, and it was: the file declares `name: skillmaster` and `setup` wrote it into
  `skillmaster-gateway`. The failure is silent where it costs most, because a client that checks the
  rule ignores the skill — so the service is never discovered while `setup` prints 「已安装」 and exits
  0. The name now comes from the content (and is validated: it decides a path, and it arrived over the
  network).

**Exit codes are a public contract** ([ADR 0087](../docs/decisions/0087-cli-exit-codes-are-a-public-contract.md)).
`skillmaster help` prints these four lines, `ExitClasses()` is where the text lives, and every
command's `--help` narrows them to the ones that command can return:

| 退出码 | 含义 |
|---|---|
| `0` | 成功 |
| `1` | 普通失败 |
| `2` | 用法错误 |
| `4` | 凭据不可用 |

`2` means the command or its arguments were written wrong. `4` means the credential itself has to be
replaced: `--no-login` was given and this machine holds nothing, `SKILLMASTER_API_KEY` is not a key
(`apikey.IsKey`, the server's own prefix rule), or the server answered 401 with `unauthenticated` /
`token_idle` / `token_expired` / `token_revoked`. Retrying as-is is pointless in each of those, though
the next step differs — sign in again, issue a new key, or fix the environment variable. Three things
that look similar stay `1`: `account_suspended` (a new credential would not help), a failed sign-in
(the browser may open on the next try), and a credential this machine cannot read (fix the keychain
or the file). A command that only refuses — `version publish`, `skills my-tags delete` — is also `1`:
the command was written correctly, the action simply is not the CLI's to take. When several apply,
help wins over usage and usage over authentication: `skill invoke` with a bad address and no
credential exits `2`.

Language: Go ([ADR 0011](../docs/decisions/0011-server-and-cli-stack.md)). §4.6 of
[`docs/versions/v1-hosting/technical-design.md`](../docs/versions/v1-hosting/technical-design.md)
says what it must do.

```
go test ./...                                  # unit tests, no server needed
CGO_ENABLED=0 go build -trimpath -o skillmaster ./cmd/skillmaster
```

The second line is checked, not hoped for: it produces a self-contained binary (11 MB) with no
runtime on the user's machine, which is the property ADR 0011 chose Go for. `file` calls it
`statically linked` on Linux (ELF); on macOS the same build is a Mach-O executable, where `file`
does not use that word — the check belongs to the Linux CI, not to a laptop. `go-keyring` is pure Go (`wincred` + `godbus`), which is what makes it true; a test
that keeps it true would be a good addition.

**On a network where `proxy.golang.org` is unreachable**, fetching modules needs a mirror:
`GOPROXY=https://goproxy.cn,direct go mod tidy`. The dependency set is three indirect modules and
nothing else.

A workflow of its own belongs in the **repository root's** `.github/workflows/` — workflows can only
live there. The rest of the repository does not need to change: that is the point of the layout.

## Two things to handle on the first day, not later

Both come from the keychain decision in
[ADR 0025](../docs/decisions/0025-cli-credential-storage.md) — keychain first, a `0600`
file as the fallback — and both fail in ways that look like something else. They were
researched on 2026-09-27 and are recorded here because nothing in the repository said
them until now.

**1. A headless Linux box has no Secret Service, and the naive code waits for one.**
The library reaches for D-Bus by shelling out, and with no session bus it sits there
until a timeout — around three seconds, on *every* invocation. For a CLI an agent calls
several times per task that is fatal, and the symptom is a slow command rather than an
error. So: probe `DBUS_SESSION_BUS_ADDRESS` **before** asking the keychain anything, and
go straight to the file fallback when it is unset. Do not let a timeout be the thing that
discovers it.

**2. On macOS the keychain is reached through `/usr/bin/security`, which changes who the
permission is attached to.** The ACL belongs to that binary, not to ours, so an unsigned
or ad-hoc-signed build re-prompts the user on every upgrade — and a modal prompt in front
of an agent is a hung task. Worse, macOS 26 and later kills a binary that is only
linker-signed, so an ad-hoc build needs a real signature (`codesign --force -s -`).
Budget for the signing step before shipping anything for macOS.

**2a. Which is why this package's tests are pointed away from the machine's keychain
(2026-10-15).** "Every upgrade" includes every `go test`: a freshly built, unsigned binary
is a new caller, so a test that reached the real store raised a real authorization dialog on
the developer's screen once per run, and the only symptom was the dialog. `TestMain` installs
a store that writes nowhere (`machineStore`, in `cmd/skillmaster/main.go`), and one test asserts
that the seam is still in place — deleting it is otherwise invisible.

**2b. A locked keychain hangs — and it cannot be probed, so it has a deadline instead (2026-10-04).**
`keychainUsable()` returns true unconditionally off Linux ("there is nothing to probe"), and that is
true right up until `security -i` blocks waiting for authorization nobody is there to give: `login`
sat in `keyring.Set` for as long as it was left, writing nothing and reporting nothing, hanging an
agent that called it.

**The fix is not a probe, and the reason is worth keeping**: availability and responsiveness are
different questions, and only the first can be answered before asking for real. A read of an item
that does not exist returns instantly even while the keychain is too locked to be *written* to — so
every cheap check says "fine" about exactly the case that hangs. `responsiveKeychain` therefore puts
a five-second deadline on the real call, and on expiry moves the run to the `0600` file, saying so
through `Where()`. Two consequences, both in that file's comment: the abandoned `security` is left
for the OS (measured: it does **not** land the write, so no surprise second copy), and a credential
can exist in both places — which is why `Load` serves the **later** of the two rather than the
keychain's, since the older copy holds a refresh token the server has already rotated and presenting
it is a replay (ADR 0024 revokes the whole chain for that).

**And one property to keep**: `zalando/go-keyring` is pure Go — wincred and godbus, no
CGO — so `CGO_ENABLED=0` static builds work. That is what makes a single binary
plausible; it is worth a test that keeps it true.


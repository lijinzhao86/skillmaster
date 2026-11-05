// Loading one skill and reading the one file the command asked for.

package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/pins"
	"os"
	"strings"
	"unicode/utf8"
)

// loaded is one resolved skill: the manifest that resolved it, and the bytes of the one thing the
// command asked for.
//
// Both halves travel together because the command needs both to say anything useful: the content is
// the answer and the manifest is where the version that answered it is written down. That version is
// not the caller's to know — it is what `invoke` records and what the closing line reports.
type loaded struct {
	// meta is the resolved version and the skill's identity — L1, which is all this command needs to
	// pin the bytes. The manifest is deliberately not read: it is a `browse` question, and `invoke`
	// belongs to the rung below it (ADR 0051).
	meta    api.Metadata
	content []byte
	binary  bool
}

// parseYes strips `--yes` from the line. It is the caller's statement that the person agreed to
// load a public skill this one time (ADR 0143): it writes nothing, and the skill stays out of the
// library — "never ask again" is `skill join`.
func parseYes(args []string) ([]string, bool) {
	yes := false
	rest := make([]string, 0, len(args))
	for _, arg := range args {
		if arg == "--yes" {
			yes = true
			continue
		}
		rest = append(rest, arg)
	}
	return rest, yes
}

// publicSkillNeedsConfirmation is the refusal a public skill gets when the caller has no library
// relation to it (ADR 0143). A plain failure, not a usage error: the command was written
// correctly, and what happens next is the person's to decide. Both ways forward are whole
// commands, because the caller here may be an agent with nobody to prompt — which is why the
// second line says who does the asking rather than assuming the reader is a person.
func publicSkillNeedsConfirmation(written, bare string) error {
	return fmt.Errorf("%s 是公开 skill，不在你的库里，这次没有加载。\n"+
		"代用户执行时，请用户二选一：\n"+
		"  只这一次（现在加载，下次仍会确认）：  skmr skill invoke %s --yes\n"+
		"  收进你的库（引用，不复制），以后不再确认：  skmr skill join %s --yes",
		written, written, bare)
}

// gateAuthorPlane is the row-id form's half of the confirmation (ADR 0143).
//
// **A row id is an author-plane address, and a `browse` caller can read one for somebody else's
// public skill** — the version list exposes it, so without this check the gate would be one
// address form away from bypassed. Two cases are left alone:
//
//   - a metadata failure is "no consumer answer" (the draft-only skill, whose caller is at `write`+
//     and therefore has a source), so the author-plane read proceeds;
//   - an `invoke` rung cannot open the author plane at all, so the author plane's own 403 is the
//     useful answer — asking for consent first would only lead back to that 403.
//
// A transient failure here fails open, on purpose: the author plane still authorizes the read, and
// a public skill that is offline is not loadable through the consumer plane anyway.
func gateAuthorPlane(ctx context.Context, client api.Client, address string, yes bool) error {
	if yes {
		return nil
	}
	meta, err := client.Metadata(ctx, bareAddress(address), "")
	if err != nil || !meta.InvokeRequiresConfirmation || meta.Access == "invoke" {
		return nil
	}
	return publicSkillNeedsConfirmation(address, bareAddress(address))
}

// invokeTakesNoPath is the answer to the two spellings of "load this file the way I loaded the
// skill" — the address with a path glued on, and the path given as a second argument.
//
// It stays a usage error like every other one (exit 2, ending at the command's help, within the
// 200-byte budget of v16 T10), and the sentence in front of that ending is the one thing the generic
// address error cannot say: the command that does take a path.
//
// **What the caller wrote is still echoed, and a directory is answered by a clause rather than by
// another guess.** The third segment of `ns/name/sub` is usually a directory — that is the shape a
// body links a sub-skill by, and the shape 2026-10-29's report used — so handing it back as a path to
// read costs the caller a second failure. But the CLI cannot tell a directory from an extensionless
// file (`LICENSE`, `Makefile`, `scripts/install` are real files in skill bundles) and must not decide
// the layout: which of these directories is a skill and which is an asset folder is the author's to
// say, in the body, which is the index a caller already has in hand. So both halves stay in the
// sentence — the path as written, and, for the directory case, the instruction to copy the link the
// body gives. Appending `/SKILL.md` would be a third possibility this line has no business inventing.
func invokeTakesNoPath(address, relpath string) error {
	return usagef("invoke 只加载正文，不接文件路径。要读：skmr skill read %s %s；若是目录，照抄正文里的链接。%s",
		address, relpath, usageAt("skill invoke"))
}

// addressWithPath splits `namespace/name/some/file.md` into the skill address and the file, and
// reports false unless both address segments are present.
//
// A version suffix stays on the name it was written on — `namespace/name@1.2.3/file.md` splits to
// `namespace/name@1.2.3` and `file.md` — because that is the address the answer has to hand back,
// and `read` understands a pin written into an address.
func addressWithPath(address string) (skill, relpath string, ok bool) {
	parts := strings.SplitN(address, "/", 3)
	if len(parts) != 3 || parts[0] == "" || parts[1] == "" || parts[2] == "" {
		return "", "", false
	}
	return parts[0] + "/" + parts[1], parts[2], true
}

// invoke loads a skill's body and remembers which version it came from (ADR 0035).
//
// **This is the host's `Skill`, doing the same job**: it loads the body, and like that tool it takes no
// path — reading a file of the skill is a different command in both systems. What it adds is the pin.
//
// **The pin is resolved here and written to this machine, and that is the design rather than
// bookkeeping.** `read` needs the same version later, in a different process, with no memory of this
// one. The alternative — a version written into every command by whoever is asking — is what this
// replaced: a name, or for a version its author left unnamed 64 hex characters, reproduced by hand on
// every line. The server is still stateless and the pin is still carried by the client (ADR 0012);
// what changed is that the client is now also a place, not only a context window.
//
// **A second invoke re-resolves, and says so when the answer moved.** "Whichever version is current"
// is what a caller normally wants, and naming one is how they ask for something else — so remembering
// what was loaded and returning it would make `invoke` unable to follow a publish. What must not
// happen is the move going unmentioned: the body in hand would be one version's while the files read
// next came from another, and nothing would report it.
func invoke(ctx context.Context, args []string) error {
	offset, rest, err := parseOffset(args)
	if err != nil {
		return err
	}
	rest, yes := parseYes(rest)
	// **A caller who just loaded a skill often wants a file the body names, and reaches for the
	// command that loaded it.** `invoke` is the host's `Skill` — the body, no path — and `read` is
	// its `Read` — one file, by path; the two are one concept apart, and a body that says
	// `[tdd/SKILL.md](tdd/SKILL.md)` reads like an address to load rather than a file to read. The
	// generic address error states the grammar and not the way out, and 2026-10-29 05:08 shows what
	// that costs: the caller gave up on the sub-skill and did the job without it. So both spellings
	// of the mistake — a path glued onto the address, or a path as a second argument — name `read`.
	//
	// **A token that starts with `-` is a flag, not a path** (audit of v1.3.1's advice): `--json` on
	// this command is an unknown flag, and answering it with a path to read would hide the word that
	// was actually wrong. It falls through to the usage line, which prints the flags.
	if len(rest) == 2 && checkAddressSyntax("skill invoke", rest[0]) == nil &&
		!strings.Contains(rest[1], "@") && !strings.HasPrefix(rest[1], "-") {
		return invokeTakesNoPath(rest[0], rest[1])
	}
	if len(rest) != 1 {
		return usagef("%s", usageAt("skill invoke"))
	}
	address := rest[0]
	if skill, relpath, ok := addressWithPath(address); ok {
		return invokeTakesNoPath(skill, relpath)
	}
	// **The address's syntax is checked first, and before a credential is asked for.** Otherwise a
	// mistyped address on a machine with no credential reports the credential instead of the typo
	// (v16 PRD #5's precedence: usage before authentication). It also has to run before the version
	// is read out, because a bare `@` is the syntax error and `versionInAddress` has no page to
	// point at.
	if err := checkAddressSyntax("skill invoke", address); err != nil {
		return err
	}
	// The version inside the address is read here too, not only in `pinFor`: `invoke` is the command
	// a caller uses to reach a *specific* version, so a malformed one has to be refused rather than
	// handed to the server. One parser, three readers — a second copy is how two commands come to
	// disagree about a spelling.
	namedVersion, named, err := versionInAddress(address)
	if err != nil {
		return usagef("%s。%s", err, usageAt("skill invoke"))
	}
	// **A draft is not invokeable, by design.** Drafts never reach the consumer plane, so sending one
	// to `invoke` would ask the server a question whose only answer is 404/400. The author-plane
	// commands already read it and its diff, and saying so is a better answer than a refusal about a
	// version that does not exist there.
	// **A row-id pin is the author plane, and it is the one that can name a draft** (ADR 0063).
	// The consumer plane has no draft to serve, so sending one there would be a 404 by construction.
	authorPin := named && isDraftPin(namedVersion)

	// The whole command is the unit that gets retried, not each request: it is two calls — the
	// manifest, then the bytes — and a credential replaced between them should carry through both.
	fetched, err := authenticated(ctx, func(client api.Client) (loaded, error) {
		if authorPin {
			if err := gateAuthorPlane(ctx, client, address, yes); err != nil {
				return loaded{}, err
			}
			return fetchAuthorBody(ctx, client, bareAddress(address), namedVersion)
		}
		return fetchBody(ctx, client, address, named, !yes)
	})
	// **Nothing is live.** A `write`+ caller is handed the newest version they can read instead of a
	// 404 (PRD #27); `browse` gets the sentence the PRD promises; `invoke` keeps the consumer's 404,
	// because `contentTarget` only speaks when the author plane is open to this credential.
	if err != nil && !named && !authorPin && errors.Is(err, api.ErrNotFound) {
		fallback, fallbackErr := authenticated(ctx, func(client api.Client) (fileTarget, error) {
			return contentTarget(ctx, client, bareAddress(address))
		})
		if fallbackErr != nil {
			return fallbackErr
		}
		if fallback.rowID {
			fetched, err = authenticated(ctx, func(client api.Client) (loaded, error) {
				if gateErr := gateAuthorPlane(ctx, client, address, yes); gateErr != nil {
					return loaded{}, gateErr
				}
				return fetchAuthorBody(ctx, client, bareAddress(address), fallback.pin)
			})
			if err == nil {
				authorPin = true
				namedVersion = fallback.pin
			}
		} else {
			fetched, err = authenticated(ctx, func(client api.Client) (loaded, error) {
				return fetchBody(ctx, client, address, false, !yes)
			})
		}
	}
	if err != nil {
		// A 404 on a named version gets the same next step `read` and `files` give it. Here there is no
		// local record to blame — this command never consults one — so the only other answer is the
		// server's own, unchanged.
		if errors.Is(err, api.ErrNotFound) && named {
			return versionGone(err, namedVersion, address)
		}
		// A row id is the author plane's address, and `invoke` is the rung that may not open it
		// (ADR 0047). The server's 403 says which rung is missing; the useful half for a caller is
		// what to use instead, and **not** falling back to the consumer plane — a row id names no
		// version there, so the fallback would turn "you may not" into "there is no such thing".
		if authorPin {
			var denied *api.Denial
			if errors.As(err, &denied) && denied.Code == api.Forbidden {
				return errors.New("调用档没有作者面入口；行 id 是作者面的地址" +
					"（需要 browse 及以上），请改用版本号或摘要（例如 " +
					bareAddress(address) + "@1.0.0）。")
			}
		}
		return err
	}

	key := bareAddress(address)
	version := fetched.meta.Version
	now := api.VersionSuffix(version.Name, version.Digest)
	var pin pins.Pin
	if authorPin {
		// The row id is what the address used, so it is what a later write sends. A legacy
		// `draft:<ULID>` is stored as the bare id — the canonical spelling has no prefix (ADR 0063).
		code := namedVersion
		if id, ok := rowIDOfPin(namedVersion); ok {
			code = id
		}
		now = code
		pin = pins.Pin{Kind: "code", Code: code,
			Digest: strings.TrimPrefix(version.Digest, "sha256:")}
	} else {
		pin = consumerPin(version.Name, version.Digest)
	}
	previous, had, err := rememberPin(key, pin)
	if err != nil {
		return err
	}

	// The header goes first, before the body: it is what the agent reads to decide what it may do
	// next, and a reader that stops after N lines should not lose it (v23).
	printLoadedHeader(fetched.meta, key, authorPin, now)
	// The continuation is the same load, so it carries the same consent: a public skill read in
	// chunks would otherwise stop at the first chunk's next line and ask again.
	resumeSuffix := ""
	if yes {
		resumeSuffix = " --yes"
	}
	printContent(bodyWithoutFrontmatter(fetched.content), offset, withoutLineNumbers, func(next int) string {
		return fmt.Sprintf("skmr skill invoke %s --offset %d%s", address, next, resumeSuffix)
	})

	if had {
		if before := previous.Suffix(); before != now {
			// The visible half of a design whose state is otherwise invisible: nothing in the request
			// says which version this was, so the only thing standing between a moved pin and a
			// confident answer about the wrong version is this line.
			//
			// **stderr, like `startup`'s copy of it** (v20). Since v23 stdout is one document —
			// the header and then the body — and a remark appended after it is a line a caller
			// redirecting the output would find inside what it believes are instructions. The
			// line is still printed; it stops being part of the document.
			fmt.Fprintf(os.Stderr, "（%s 上次钉在 @%s，现在是 @%s——中间有人发布过。）\n",
				key, before, now)
		}
	}
	return nil
}

// printLoadedHeader is the frontmatter `skill invoke` puts in front of the body (v23).
//
// **Assembled here, decided there.** Every value is the server's — the rung, the actions it
// unlocks, the version number — and this function only orders them. The CLI still parses no
// frontmatter: it prints fields it was handed, and the body below is byte for byte what the server
// served, minus the author's own block.
//
// What is deliberately absent: `description` and `when_to_use` (the search card already delivered
// them), the author's own `version:` label (it is not an address and it contradicts the number the
// platform assigned), and any repeat of a general rule such as how the pin works.
//
// `rowPin` says the caller addressed the version by its row id, not that this is a draft: a
// published version can be reached that way too, and it still has a number to print.
func printLoadedHeader(meta api.Metadata, key string, rowPin bool, pin string) {
	fmt.Println("---")
	// **The version line follows the version, not the plane that answered.** A published version
	// reached through its row id still has a number to print; only a row that has none — a draft,
	// or a discarded one, neither of which was ever assigned a number (ADR 0063) — is named by its
	// id, and then the address is where the caller got it.
	if meta.Version.Name == "" && rowPin {
		fmt.Printf("address: %s@%s\n", key, pin)
	} else {
		// A published version whose author declared no `version:` falls back to its digest: that is
		// what an address pins it by (ADR 0033), so it is what the header calls it.
		fmt.Printf("address: %s\n", key)
		fmt.Printf("version: %s\n", api.VersionSuffix(meta.Version.Name, meta.Version.Digest))
	}
	if meta.Access != "" {
		fmt.Printf("access: %s\n", meta.Access)
	}
	// **Capabilities name commands, so the agent can act on the header directly** (v34, ADR
	// 0119). One line per capability; the `skill ` prefix is dropped because the header is already
	// about one skill — a command from another group (`version discard`) keeps its prefix.
	// An older server sends only `permissions`: print that instead of an empty section.
	if len(meta.Capabilities) > 0 {
		fmt.Println("permissions:")
		for _, capability := range meta.Capabilities {
			fmt.Printf("  %s: %s\n", capability.Capability, commandsForDisplay(capability.Commands))
		}
	} else if len(meta.Permissions) > 0 {
		fmt.Printf("permissions: %s\n", strings.Join(meta.Permissions, ", "))
	}
	if compatibility := oneLine(meta.Compatibility); compatibility != "" {
		// Quoted, because this is the one value the author writes and the block reads as YAML: an
		// unquoted `macOS: 需要 X` is a mapping inside a mapping, and a value starting with `#` or
		// `[` is a comment or a list. `%q` produces a double-quoted scalar, which YAML reads as the
		// same text for the escapes Go emits (quote, backslash, control characters).
		fmt.Printf("compatibility: %q\n", compatibility)
	}
	fmt.Println("---")
}

// commandsForDisplay is the command list as the header shows it: one prefix for the group the
// header is about, the full spelling for anything from another group.
func commandsForDisplay(commands []string) string {
	shown := make([]string, len(commands))
	for i, command := range commands {
		shown[i] = strings.TrimPrefix(command, "skill ")
	}
	return strings.Join(shown, ", ")
}

// oneLine folds the author's free text onto one line.
//
// `compatibility` is the only value in the header that the author writes, and a block scalar or a
// stray newline would otherwise end the field: the next line of the value would sit among the
// platform facts, where a second `permissions:` line or an early `---` reads as if the server had
// said it. Collapsing every run of whitespace is enough to make the value one line and keeps the
// words.
func oneLine(value string) string {
	return strings.Join(strings.Fields(value), " ")
}

// fetchBody loads a skill's body without ever reading its manifest.
//
// **The manifest is a `browse` question, and `invoke` has to work one rung below it.** What resolves
// `latest` is `/metadata`; the bytes are then asked for at the version it named, so a publish landing
// in between cannot make the two disagree. No pin goes on the first request: a version named in the
// address is already there, and what this machine remembered is deliberately not consulted —
// invoking is the thing that decides.
// confirm is the caller's decision to enforce the public-skill gate (ADR 0143), not a fact about
// the request: `invoke` passes `!yes`, while `startup` passes false because its read is the
// installation journey, not a person loading a skill. The gate runs before the body is fetched, so
// a refusal never copies a public skill's content into a conversation.
func fetchBody(ctx context.Context, client api.Client, address string, namedInAddress,
	confirm bool) (loaded, error) {
	meta, err := client.Metadata(ctx, address, "")
	if err != nil {
		return loaded{}, err
	}
	if confirm && meta.InvokeRequiresConfirmation {
		return loaded{}, publicSkillNeedsConfirmation(address, bareAddress(address))
	}
	// A second pin would ask the server to reconcile two sources, which it refuses — so the header
	// carries the resolved version only when the address did not name one itself.
	pin := ""
	if !namedInAddress {
		pin = api.VersionSuffix(meta.Version.Name, meta.Version.Digest)
	}
	content, err := client.Body(ctx, address, pin)
	if err != nil {
		return loaded{}, err
	}
	return loaded{meta: meta, content: content}, nil
}

// fetchAuthorBody loads a row-id pin's body from the author plane.
//
// A draft lives nowhere else, and a published version's row id is still a row id — the author plane
// answers for every state the caller's rung may read, which is exactly what makes `invoke @<code>`
// on a published version work for `browse` and above.
func fetchAuthorBody(ctx context.Context, client api.Client, address, pin string) (loaded, error) {
	envelope, err := client.ReadAuthorVersion(ctx, address, pin)
	if err != nil {
		return loaded{}, err
	}
	content, _, err := client.AuthorFileBytes(ctx, address, pin, "SKILL.md")
	if err != nil {
		return loaded{}, err
	}
	return loaded{meta: api.AuthorSnapshot(envelope.Skill, envelope.Version, envelope.Compatibility), content: content}, nil
}

// fetchFile is the same path for one file: resolve the version from L1, then ask for the relpath at
// that version. `pin` is what `pinFor` decided (a remembered version, or empty for "whatever is
// current"); the second request always carries the **resolved** version, never the pin, so a publish
// between the two calls cannot hand back a file from a different snapshot.
func fetchFile(ctx context.Context, client api.Client, address, relpath, pin string) (loaded, error) {
	meta, err := client.Metadata(ctx, address, pin)
	if err != nil {
		return loaded{}, err
	}
	resolved := api.VersionSuffix(meta.Version.Name, meta.Version.Digest)
	content, err := client.File(ctx, bareAddress(address), relpath, resolved)
	if errors.Is(err, api.ErrNotFound) {
		// The metadata call above resolved, so this 404 is about the path and not the skill or the
		// version. Without the manifest there is nothing to look in, so the next step is the command
		// that does list one — and whether that needs 「浏览」 depends on the rung this same metadata
		// call reported, so the message is given that instead of assuming it.
		return loaded{}, fileMissing(relpath, address, meta.Access)
	}
	if err != nil {
		return loaded{}, err
	}
	// **The consumer plane has no manifest to ask**, so nothing on this path said whether these
	// bytes are text. `read` then printed a `.png` as mojibake, and `head`/`--raw` skipped the
	// binary refusal they promise. The bytes can answer it themselves: a NUL byte or invalid
	// UTF-8 is not a text file. Where the manifest does speak (the author plane), it still wins.
	return loaded{meta: meta, content: content, binary: looksBinary(content)}, nil
}

// looksBinary is the fallback for a read with no manifest: bytes that are not valid UTF-8, or that
// contain a NUL. Deliberately conservative — the manifest's `is_binary` is the authority, and this
// only fills the gap on the plane that does not carry one.
func looksBinary(content []byte) bool {
	if bytes.IndexByte(content, 0) >= 0 {
		return true
	}
	return !utf8.Valid(content)
}

// Command skillmaster is the CLI: it holds the credential, loads skills on demand, and submits them
// for approval.
//
// §4.6's command set is here except for rollback, which nothing needs yet. Everything else — login,
// logout, setup, the `skill` group's verbs for one skill (invoke, files, read, versions, submit,
// share, create, shares, unshare, request, open) and the `skills` group's verbs for the collection
// (list, search) — is in this file plus the packages under internal/.
//
// **The CLI's writing is submission and draft management; it cannot publish** (ADR 0031). Since v6
// `skill submit` carries the version's name and note, and the `version` group may rename, annotate
// and discard a draft. Publishing moves what every agent reading the API will get, and it is
// deliberately reachable only from the browser, by a person, with the page a command opens. The
// CLI's half of that handover is the deep link it prints and opens; it carries no credential of any
// kind, so a link copied out of a terminal is not a way in.
package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/apikey"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/auth"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/pins"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/version"
)

func main() {
	if err := run(context.Background(), os.Args[1:]); err != nil {
		os.Exit(reportError(err))
	}
}

func run(ctx context.Context, args []string) (err error) {
	if len(args) == 0 {
		writeCommandTable()
		return usagef("没有给出子命令。用法见：skillmaster help")
	}
	// **Answered before anything else looks at the arguments.** It is a question about the binary,
	// not about a command: it must work with no server reachable, no credential, and no parsing
	// (v17 acceptance 1). The second half is the API generation — the major number and this are the
	// same fact said twice (ADR 0088).
	if len(args) == 1 && args[0] == "--version" {
		fmt.Printf("skillmaster %s (API %s)\n", version.Version, api.Generation)
		return nil
	}
	if handled, err := helpRequest(args); handled {
		return err
	}
	// The command's own address is settled once, here: a parser deep inside has no way to know which
	// verb reached it (`--offset` serves both `read` and `invoke`), and every usage error has to end
	// at the help of the command that was actually typed (PRD 做 #4).
	defer func() { err = retargetUsage(err, args[0]) }()

	switch args[0] {
	case "login":
		return login(ctx, args[1:])
	case "auth":
		return authCmd(ctx, args[1:])
	case "logout":
		return logout(args[1:])
	case "setup":
		return setup(ctx, args[1:])
	case "skills":
		return skillsVerb(ctx, args[1:])
	case "skill":
		return skillVerb(ctx, args[1:])
	case "version":
		return versionVerb(ctx, args[1:])
	default:
		return unknownName("", args[0], childNames(""))
	}
}

// namedArgument is how a refusal points at an argument it will not repeat.
//
// **The argument that reaches one of these defaults is a credential about as often as it is a typo,
// and a refusal is the wrong place to find out which.** `--api-key=skm_…` typed one level too high,
// a key pasted where a subcommand goes: quoting it back puts the secret in the terminal and in
// whatever collects a CI job's output — one more place it lives, in the message that was supposed to
// protect it. What is left to name is the part the person has to change anyway: the flag, or the
// fact that what they handed over is a credential and where it goes instead.
//
// **The test is the server's own routing rule** (`apikey.IsKey`, the same prefix the API uses to
// decide a bearer token is a key), so this cannot drift from what a key is. An ordinary typo — by
// far the commonest way here — is quoted as before: it is the word to fix, and hiding it would make
// the message useless.
// flagValue reads the value that follows a flag, and refuses a token that is itself a flag.
//
// **Without the check the typo becomes data**: `--name --text` would rename a definition to
// "--text", and `--category --my-tag foo` would send a category named "--my-tag". Every v14 flag
// whose value is free text goes through here.
func flagValue(args []string, i int, flag, path string) (string, error) {
	if i+1 >= len(args) {
		return "", usagef("%s 后面要跟一个值。%s", flag, usageAt(path))
	}
	// Only a long flag (`--name`) is refused. A single dash stays a legal start for free text —
	// a description may begin with "- 交付期内" — while the typo that motivated the check
	// (`--name --text`) is still caught.
	if strings.HasPrefix(args[i+1], "--") && len(args[i+1]) > 2 {
		return "", usagef("%s 后面要跟一个值，但下一个参数看起来是旗标（%s）。%s",
			flag, namedArgument(args[i+1]), usageAt(path))
	}
	return args[i+1], nil
}

func namedArgument(arg string) string {
	switch {
	case apikey.IsKey(arg):
		return apikey.InAnArgument
	case strings.HasPrefix(arg, "-"):
		// The flag name alone: whatever follows `=` may be the secret, and it is not repeated.
		return strconv.Quote(strings.SplitN(arg, "=", 2)[0])
	default:
		return strconv.Quote(arg)
	}
}

// credentialSource names where the credential a command just used came from, for a refusal that has
// to say which of two possible credentials it is about.
func credentialSource() string {
	store, _ := openStore(config.Server())
	return store.Source()
}

// skillVerb is the `skill` group: the commands that act on one skill.
//
// **The grammar is singular/plural, and it is the rule rather than a convention.** Every verb here
// takes one skill's address; `skillsVerb` below is the collection (list, search) and takes none.
// Before this split `list` lived here next to `ls`/`files`, and two commands one letter apart
// answered different questions — the confusion the split exists to end.
//
// **A group rather than top-level verbs**, because this CLI is going to grow nouns that are not
// skills — a namespace, a version, a grant — and a bare `list` did not say what it listed. The
// shape is the one `claude mcp list`, `claude plugin eval` and `gh pr list` use, and it is what both
// of those do for a noun that has several verbs under it. Neither of them has a `noun-verb` command
// at all: a hyphen there appears *inside* a verb name (`claude setup-token`), never as a prefix.
//
// **`skill`/`skills` and `version` are groups, and `login`/`logout`/`setup` deliberately stay flat.**
// What decides is which noun a command acts on: those three act on this machine — a credential, a
// keychain entry, the skills directory — rather than on a skill in the registry. They are also not
// ambiguous the way a bare `list` was: there is one thing to log in to. The `version` group is the
// author-plane noun v6 added; it is top-level, not a verb under `skill`, because a version is an
// object in its own right and `skill version list` would read as a list of skills.
//
// **The two read commands are the host's two, and the split is the same one.** `invoke` is `Skill`:
// load the body, and it takes no path. `read` is `Read`: one file of it, by path. What `invoke` adds
// is that it resolves the version and writes it down, which is what lets `read` be given a bare
// address — see `invoke` for why that pin exists and why it lives here.
//
// **`files` is the one read command with no counterpart in the host** (ADR 0036): there, a skill is a
// directory and enumerating one is `ls`, and its skill load hands over a base directory rather than a
// listing. Neither of those exists here, so the enumeration is a command — see `files`.
func skillVerb(ctx context.Context, args []string) (err error) {
	if len(args) == 0 {
		return usagef("%s", usageAt("skill"))
	}
	// The command's own address is settled once, here: a parser deep inside has no way to know which
	// verb reached it (`--offset` serves both `read` and `invoke`), and every usage error has to end
	// at the help of the command that was actually typed (PRD 做 #4).
	defer func() { err = retargetUsage(err, "skill "+args[0]) }()
	switch args[0] {
	case "invoke":
		return invoke(ctx, args[1:])
	case "files":
		if err := rejectJSON("files", args[1:]); err != nil {
			return err
		}
		return files(ctx, args[1:])
	case "cat":
		if err := rejectJSON("cat", args[1:]); err != nil {
			return err
		}
		return cat(ctx, args[1:])
	case "wc":
		if err := rejectJSON("wc", args[1:]); err != nil {
			return err
		}
		return wc(ctx, args[1:])
	case "grep":
		if err := rejectJSON("grep", args[1:]); err != nil {
			return err
		}
		return grep(ctx, args[1:])
	case "write":
		return write(ctx, args[1:])
	case "edit":
		return edit(ctx, args[1:])
	case "rm":
		if err := rejectJSON("rm", args[1:]); err != nil {
			return err
		}
		return rm(ctx, args[1:])
	case "mv":
		return moveOrCopy(ctx, args[1:], "mv")
	case "cp":
		return moveOrCopy(ctx, args[1:], "cp")
	case "read":
		if err := rejectJSON("read", args[1:]); err != nil {
			return err
		}
		return read(ctx, args[1:])
	case "download":
		if err := rejectJSON("download", args[1:]); err != nil {
			return err
		}
		return download(ctx, args[1:])
	case "versions":
		if err := rejectJSON("versions", args[1:]); err != nil {
			return err
		}
		return versions(ctx, args[1:])
	case "submit":
		return submitSkill(ctx, args[1:])
	case "share":
		return shareSkill(ctx, args[1:])
	case "create":
		return createSkill(ctx, args[1:])
	case "shares":
		return shares(ctx, args[1:])
	case "unshare":
		return unshare(ctx, args[1:])
	case "request":
		return requestAccess(ctx, args[1:])
	case "open":
		return openSkill(ctx, args[1:])
	case "show":
		if err := rejectJSON("show", args[1:]); err != nil {
			return err
		}
		return showSkill(ctx, args[1:])
	case "tags":
		if err := rejectJSON("tags", args[1:]); err != nil {
			return err
		}
		return skillTags(ctx, args[1:])
	case "tag":
		if err := rejectJSON("tag", args[1:]); err != nil {
			return err
		}
		return tagVerb(ctx, args[1:])
	case "category":
		if err := rejectJSON("category", args[1:]); err != nil {
			return err
		}
		return categoryVerb(ctx, args[1:])
	case "join":
		if err := rejectJSON("join", args[1:]); err != nil {
			return err
		}
		return joinLibrary(ctx, args[1:], true)
	case "leave":
		if err := rejectJSON("leave", args[1:]); err != nil {
			return err
		}
		return joinLibrary(ctx, args[1:], false)
	case "restore":
		if err := rejectJSON("restore", args[1:]); err != nil {
			return err
		}
		return restoreSkill(ctx, args[1:])
	default:
		return unknownName("skill", args[0], childNames("skill"))
	}
}

// skillsVerb is the `skills` group: the commands that act on the collection.
//
// **It takes no address, and that is the half of the grammar `skillVerb` does not cover.** A verb
// that answers "which skills" belongs here; a verb that acts on one belongs there. The old
// `skill list` / `skill search` spellings are unknown commands, not aliases: they never shipped, so
// there is nothing to keep working (ADR 0077).
func skillsVerb(ctx context.Context, args []string) (err error) {
	if len(args) == 0 {
		return usagef("%s", usageAt("skills"))
	}
	// The command's own address is settled once, here: a parser deep inside has no way to know which
	// verb reached it (`--offset` serves both `read` and `invoke`), and every usage error has to end
	// at the help of the command that was actually typed (PRD 做 #4).
	defer func() { err = retargetUsage(err, "skills "+args[0]) }()
	switch args[0] {
	case "list":
		if err := rejectJSON("list", args[1:]); err != nil {
			return err
		}
		return listSkills(ctx, args[1:])
	case "search":
		if err := rejectJSON("search", args[1:]); err != nil {
			return err
		}
		return search(ctx, args[1:])
	case "categories":
		if err := rejectJSON("categories", args[1:]); err != nil {
			return err
		}
		return categoriesCmd(ctx, args[1:])
	case "public-tags":
		if err := rejectJSON("public-tags", args[1:]); err != nil {
			return err
		}
		return publicTagsCmd(ctx, args[1:])
	case "my-tags":
		if err := rejectJSON("my-tags", args[1:]); err != nil {
			return err
		}
		return myTagsCmd(ctx, args[1:])
	default:
		return unknownName("skills", args[0], childNames("skills"))
	}
}

// ---------------------------------------------------------------------------
// Credentials
// ---------------------------------------------------------------------------

// login has one shape, and it is the person's.
//
// **Two more have been removed, one per version, and this command is the story of them.** There was
// `--client-credentials` for a machine with nobody in front of it, and v3 removed it: a key handed
// over through `SKILLMASTER_API_KEY` needs no secret and no registration step (ADR 0038 §决定 9).
// There was a flag that read a key from standard input and pasted it into this machine's store — the
// shape v2 was built around — and **v4 removed it** (ADR 0040 §决定 2 is superseded here, and 决定 3
// with it): a key is a long-lived credential, and "copy a long-lived credential onto a machine by
// hand" is not what a login is for. A machine that wants one of those is given it through the
// environment; a machine whose person is sitting in front of it gets a login.
//
// **What is left is the login that was always the point**: a browser, a page, one click, and a
// credential that expires, appears on the console's 「登录设备」 screen, and can be ended there.
func login(ctx context.Context, args []string) error {
	server := config.Server()
	store, notice := openStore(server)

	previous, hadPrevious := previousCredential(store)

	// `--help` never reaches here: the process answers it before any handler runs (v16 做 #1), which
	// is also why `login --help` no longer opens a consent page.
	switch {
	case len(args) > 0:
		// **Anything else is a mistake rather than a login.** A *positional* argument is the quieter
		// spelling of the same mistake — the commands that took a credential from an argument were
		// removed a version ago, so a key pasted after `login` has lost its flag — and it is refused
		// without being repeated back (`namedArgument` says why).
		return unknownFlag("login", args[0])
	default:
		if err := loginWithBrowser(ctx, os.Stdout, server, store, notice); err != nil {
			return err
		}
	}

	revokeReplaced(ctx, server, previous, hadPrevious)
	return nil
}

// refusalMessage turns the server's own reason for a 401 into something a person can act on.
//
// **Both credentials answer the same vocabulary now, and the sentences are not the same** (v4 做
// #11, 验收 #11). A login has two rulers, so its four named reasons are four different next steps:
// idle says "you were away, come back"; expired says "come back, or get something that does not
// expire" — and it is the only one of the four that has a second answer, because a login has no
// renewal and a key does; ended says come back; a suspended account says the credential is not the
// problem. **The first two must not read alike**: they are indistinguishable in the stored date, and
// the whole reason the server names them is that they point somewhere different.
//
// For a key the three that exist send somebody to the console, and the design's own words for them
// are what this returns (ADR 0039 §决定 3) — the fourth, `token_idle`, is a ruler a key does not
// have and the server cannot produce for one.
//
// The second return value says whether this is one of them: a refusal with a code this version does
// not know keeps the message it always had, which at least quotes the server.
func refusalMessage(err error, key bool) (string, bool) {
	var refusal *api.Refusal
	if !errors.As(err, &refusal) {
		return "", false
	}
	console := config.WebURL() + "/account/keys"
	if key {
		switch refusal.Code {
		case "unauthenticated":
			// The server's answer for a token it cannot place at all: mistyped, revoked by a path
			// this build does not name, or from another service. The body is English and the envelope
			// is not the sentence a person needs.
			return fmt.Sprintf("这把 API key 服务端不认：可能填错了，也可能已经被撤销或过期。到控制台核对：%s", console), true
		case "token_expired":
			return fmt.Sprintf("这把 API key 已经过期。到控制台续期：%s", console), true
		case "token_revoked":
			return fmt.Sprintf("这把 API key 已被撤销，续期不适用——到控制台新签一把：%s", console), true
		case "account_suspended":
			return "这个账号已被停用，凭据暂时都不能用（不是这把 key 的问题）。", true
		}
		return "", false
	}
	switch refusal.Code {
	case "unauthenticated":
		return "服务端不认这份凭据：它可能已经被结束、过期，或者不是这个服务签发的。" +
			"重新运行 skillmaster login 点一下就能换一份新的。", true
	case "token_idle":
		return "这份凭据闲置太久了——这台机器有一阵子没用它。重新运行 skillmaster login，" +
			"在同一台机器上点一下就换回来。", true
	case "token_expired":
		// The one refusal with two answers, and both are said: coming back is what most people do,
		// and **a key is named because this is the moment the difference between the two shapes
		// matters** — a login does not renew, so wanting one that does means wanting the other shape.
		return fmt.Sprintf("这份凭据已经到期（它从签发那一刻起就有上限）。重新运行 skillmaster login 点一下，"+
			"或者到控制台签一把不会到期的 API key：%s", console), true
	case "token_revoked":
		return "这份凭据已经被结束（在控制台的「登录设备」里退出的就是它）。" +
			"重新运行 skillmaster login 点一下就换一份新的。", true
	case "account_suspended":
		return "这个账号已被停用，凭据暂时都不能用（不是这份凭据的问题）。", true
	}
	return "", false
}

// denialMessage turns a 403 into the answer the design gives for it — and **which answer it is comes
// from the server rather than from a guess about which command was run** (v4 §一次操作的结果落在谁
// 身上, its last two rows).
//
// **Merging the two is the mistake this exists to avoid.** `insufficient_scope` means *another
// credential would work*: a key's scopes are chosen when it is issued, so one carrying more goes
// through, and the challenge names the one that was missing. `forbidden` means *who is asking*,
// decided by the resource: the server deliberately sends no challenge, because a client that read
// one would re-authorize and get the same 403 for ever. One sentence for both tells somebody "just
// get another credential" and "nothing you hold will help" in the same words.
//
// **What the second answer must not say is 「重新授权」 or 「重新登录」**, and it is why the console is
// named only in the first: signing in again produces the same scopes this CLI asks for, so a person
// sent that way would come back to the same refusal.
func denialMessage(err error, key bool) (string, bool) {
	var denied *api.Denial
	if !errors.As(err, &denied) {
		return "", false
	}
	switch denied.Code {
	case api.InsufficientScope:
		missing := "这次操作需要的那个权限"
		if denied.Scope != "" {
			missing = "`" + denied.Scope + "`"
		}
		if !key {
			// A login's scopes are the two this CLI asks for and the server has registered, so another
			// login carries exactly this set — the loop the design warns about, named here rather than
			// invited. What could carry more is the **other** shape, which is what the console issues,
			// so that is where this points.
			return fmt.Sprintf("这份凭据没有 %s。它是这次 CLI 登录申请到的权限，重新登录也一样；"+
				"要一条带它的凭据就到控制台签一把 API key：%s/account/keys", missing, config.WebURL()), true
		}
		return fmt.Sprintf("这把 API key 没有 %s。到控制台签一把带它的：%s/account/keys，"+
			"或者换掉 %s 里的那一把。", missing, config.WebURL(), config.ApiKeyEnv), true
	case api.Forbidden:
		return "你对这个 skill 没有这个权限。换一份凭据也一样——这是账号对这个资源的权限，" +
			"不是凭据的权限。", true
	}
	return "", false
}

// authCmd is the small group of questions about the credential itself, which until v2 had no
// command at all — "which key am I using" was answerable only by remembering what was pasted.

func authCmd(ctx context.Context, args []string) (err error) {
	if len(args) == 0 {
		return usagef("%s", usageAt("auth"))
	}
	// The command's own address is settled once, here: a parser deep inside has no way to know which
	// verb reached it (`--offset` serves both `read` and `invoke`), and every usage error has to end
	// at the help of the command that was actually typed (PRD 做 #4).
	defer func() { err = retargetUsage(err, "auth "+args[0]) }()
	switch args[0] {
	case "status":
		// The optional address is 做 #9's second query: the same question — "what can *this*
		// credential do" — narrowed from the credential to one skill. Same command rather than a new
		// one because it is the same question; a second name would put one rule in two families.
		if len(args) > 2 {
			return usagef("auth status 最多接一个 skill 地址。%s", usageAt("auth status"))
		}
		return authStatus(ctx, args[1:])
	default:
		return unknownName("auth", args[0], childNames("auth"))
	}
}

// authStatus answers both halves of "what is in use here": what this machine holds, and what the
// server thinks that credential is.
//
// Two halves because either can be the one that is wrong — a stale file, an exported variable that
// overrides it, a key that was revoked in the console an hour ago — and each is read from the place
// that knows it rather than inferred from the other.
func authStatus(ctx context.Context, args []string) error {
	server := config.Server()
	store, notice := openStore(server)
	if notice != "" {
		fmt.Fprintln(os.Stderr, notice)
	}
	address := ""
	if len(args) == 1 {
		address = args[0]
	}
	return authStatusWith(ctx, server, store, address)
}

// authStatusWith is `auth status` against a store it was handed, the way `logoutWith` is: what this
// command says about a credential is the part worth testing, and reaching for this machine's keychain
// is the part that is not.
func authStatusWith(ctx context.Context, server string, store credentials.EnvStore, address string) error {
	// **The address is checked before anything is asked.** A typo costs one line here; checking it
	// where it is used instead would spend a request on `/me` and print a screenful of credential
	// facts with the complaint underneath them, which reads as though the credential were the
	// problem.
	if address != "" {
		// **The full grammar, not only `api.SkillPath`.** That check accepts any `a/b`, including a
		// bare `@` in the name, which every other command refuses as a usage error — and this one
		// would have sent it to the server as a skill called `b@`.
		if err := checkAddressSyntax("auth status", address); err != nil {
			return err
		}
	}

	// Before anything is asked: a variable that is not a key cannot be one, and the answer this
	// command owes is about the variable rather than about the credential it is not.
	if err := environmentKeyProblem(); err != nil {
		return err
	}

	stored, found, err := store.Load()
	if err != nil {
		return fmt.Errorf("读取本机凭据：%w", err)
	}
	if !found || stored.AccessToken == "" {
		return errors.New("本机没有凭据。跑 `skillmaster login` 在浏览器里登录，" +
			"或把一把控制台签发的 API key 放进 " + config.ApiKeyEnv + "。")
	}

	fmt.Printf("本机：凭据来自 %s\n", store.Source())
	if store.FromEnv() {
		fmt.Printf("      %s 优先于本机存储：命令用的都是它。\n", store.Var)
	}

	me, err := api.Client{BaseURL: server, Token: stored.AccessToken}.Me(ctx)
	if err != nil {
		if message, known := refusalMessage(err, apikey.IsKey(stored.AccessToken)); known {
			// **The raw code travels with the sentence.** Refusing quietly with a sentence lost the one
			// thing scripts can act on (v16 验收 #11), and it also lost the exit status: the four 401
			// codes mean "replace the credential", and without the code this looked like any failure.
			code := refusalCode(err)
			return &cliFailure{
				Code:       code,
				Message:    fmt.Sprintf("服务端：%s（%s）", message, code),
				ServerCode: code,
			}
		}
		return err
	}

	fmt.Printf("服务端：账号 %s\n", me.User.Username)

	// The scopes the credential carries, which is one of the two halves `reportSkillAccess` needs and
	// the only half a client can see. A key's come from the server's own description of it; a login's
	// from the copy this machine stored, which is the list the exchange handed back.
	scopes := stored.Scope
	if me.Key == nil {
		// A credential that is not a key, described by what it is. **v4 moved this sentence**, because
		// the version moved the fact: the login credential used to be an authorization with no entry
		// anywhere in the console, and it is now a 「登录设备」 — the row the sign-in page created,
		// ended from that screen like the browser sessions beside it (v4 Q6). The leftovers are still
		// what the old sentence was about, and they keep it.
		if auth.LeftoverOf(stored) == auth.NotALeftover {
			fmt.Printf("      这份凭据不是 API key，是一次 CLI 登录换来的：%s\n", loginFacts(stored))
		}
		if where := whereToRevoke(stored); where != "" {
			fmt.Printf("      %s\n", where)
		}
	} else {
		expiry := "不过期"
		if me.Key.ExpiresAt != "" {
			expiry = me.Key.ExpiresAt + " 到期"
		}
		used := "从未使用"
		if me.Key.LastUsedAt != "" {
			used = me.Key.LastUsedAt + " 最后使用"
		}
		fmt.Printf("      这把 key 叫「%s」，权限 %s，%s，%s\n", me.Key.Name, me.Key.Scope, expiry, used)
		scopes = me.Key.Scope
	}

	if address == "" {
		return nil
	}
	return reportSkillAccess(ctx, server, stored.AccessToken, scopes, address)
}

// reportSkillAccess answers 做 #9's second question: what **this** credential can do with one skill.
//
// **The whole reason the command exists is the word "this".** The account's answer and the
// credential's answer are not the same, and a person who is holding a `skills:read` key needs to hear
// the credential's — telling them what their account could do would send them to fix the wrong thing.
// So the account's half comes from the server (`can_write`, which is the same predicate the write
// path enforces) and the scope half is read here, from the credential this process is actually using.
//
// **Four endings, and three of them are not failures.** Readable and writable; readable and not, with
// the reason; not readable because the credential lacks `skills:read` — which another credential would
// fix; and not found, which is also what a skill the caller may not see answers, deliberately, so that
// the question cannot be used to probe for names.
func reportSkillAccess(ctx context.Context, server, token, scopes, address string) error {
	detail, err := api.Client{BaseURL: server, Token: token}.Detail(ctx, address, "")

	switch {
	case errors.Is(err, api.ErrNotFound):
		// One sentence for two situations, because that is what the server's single 404 means: saying
		// which of them it was would confirm that a skill exists to somebody who cannot see it.
		fmt.Printf("%s：读不到。它不存在，或者这份凭据看不到它。\n", address)
		return nil
	case isMissingScope(err, api.InsufficientScope):
		fmt.Printf("%s：不能读 —— 这份凭据没有 skills:read。\n", address)
		fmt.Println("      换一份带 skills:read 的凭据就行。")
		return nil
	case err != nil:
		return err
	}

	if detail.CanWrite {
		fmt.Printf("%s：能读，能写。\n", address)
		return nil
	}
	fmt.Printf("%s：能读，不能写。\n", address)
	// **Which of the two reasons it is decides what the person does next**, and they lead opposite
	// ways: a missing scope is answered by another credential, and the account's own standing is
	// answered by nothing the person can hold. Saying "没有权限" for both would send somebody to
	// re-issue a key that cannot help them.
	if !carriesScope(scopes, "skills:write") {
		fmt.Printf("      不能写的原因：这份凭据只带了 %s。\n", scopes)
		fmt.Println("      换一份带 skills:write 的凭据就行。")
		return nil
	}
	fmt.Println("      不能写的原因：你对这个 skill 没有这个权限（提交新版本需要你是它的所有者，或拿到「写入」及以上的档位）。")
	fmt.Println("      换一份凭据没有用。")
	return nil
}

// isMissingScope reports whether err is a 403 whose code is the one scope check.
func isMissingScope(err error, code string) bool {
	var denial *api.Denial
	return errors.As(err, &denial) && denial.Code == code
}

// carriesScope reports whether a space-separated scope list contains one scope.
//
// Split on whitespace rather than compared as a substring: "skills:read skills:write" contains
// "skills:read" as a prefix, so a substring test would answer yes for a list that never had it.
func carriesScope(scopes, want string) bool {
	for _, scope := range strings.Fields(scopes) {
		if scope == want {
			return true
		}
	}
	return false
}

// previousCredential is the credential this login is about to replace, read before anything
// overwrites it — so it can be revoked *after* the new one is safely stored.
//
// **A read that fails here does not fail the login**, and that is the whole reason it is a function
// rather than three lines in each shape. This is the one credential on the machine that may be
// unreadable — a keychain that timed out, a file somebody edited by hand — and `login` is the
// command that fixes exactly that. Refusing leaves the command that exists to replace a broken
// credential as the one command a broken credential stops, with a message pointing at the read
// instead of at what to delete. The cost of carrying on is one authorization nobody revokes, which
// is said out loud rather than hidden.
func previousCredential(store credentials.EnvStore) (credentials.Credentials, bool) {
	// **Read from this machine, not from the environment** (`Stored`). What a login replaces is the
	// stored credential; a variable is not something a login can replace, and handing it to the
	// revocation path below would send an API key to an OAuth endpoint that cannot revoke one.
	previous, hadPrevious, err := store.Stored()
	if err != nil {
		fmt.Fprintf(os.Stderr,
			"读取现有凭据失败——这次登录照常进行，但上一次那张授权不会被撤销：%v\n", err)
		return credentials.Credentials{}, false
	}
	return previous, hadPrevious
}

// browserAvailable is the machine's answer to "can anything here open a URL", and it is a variable
// only so a test can stand where a container stands. The fact itself has one home, in
// `internal/auth`, next to the command that would do the opening.
var browserAvailable = auth.BrowserAvailable

// loginWithBrowser is the whole of `login` now: a browser, PKCE, and a person.
//
// **It refuses up front on a machine with no browser**, and the refusal points at the one way that
// works there. Waiting instead would be the worst of the options: the flow would print the sign-in
// address, listen on the loopback port, and sit there until the callback timed out — a command that
// looks like it is working, for a reason the person cannot see (v4 验收 #7(a); ADR 0040 §决定 2).
//
// **The refusal names the variable and the console, and nothing else** — in particular not the
// paste command v2 had, which this version removed. That is v4 验收 #7(a)'s whole point, and v3 met
// the same trap from the other side: a message that sends somebody to a command that no longer exists
// is worse than no message, because they go and try it.
// @param progress where the narration goes. `login` answers on stdout; a command that logged in for
//
//	itself writes to stderr, because its stdout is the `--json` object and nothing else.
func loginWithBrowser(ctx context.Context, progress io.Writer, server string, store credentials.EnvStore,
	notice string) error {
	if !browserAvailable() {
		return fmt.Errorf("这台机器上没有可以打开链接的浏览器，浏览器登录走不通。"+
			"改用控制台签发的 API key：把它放进 %s（CI、容器用它，什么都不会写到本机），"+
			"或到控制台签一把：%s/account/keys", config.ApiKeyEnv, config.WebURL())
	}

	fmt.Fprintf(progress, "正在登录 %s …\n", server)
	credential, err := auth.Login(ctx, auth.LoginOptions{
		Server:   server,
		ClientID: config.ClientID,
		Port:     config.CallbackPort,
		OnURL: func(url string) {
			fmt.Fprintf(progress, "如果浏览器没有自动打开，请访问：\n%s\n", url)
		},
		OpenURL: auth.OpenBrowser,
	})
	if err != nil {
		return err
	}
	return saveAndReport(progress, store, notice, server, credential)
}

// revokeReplaced ends the authorization the login just replaced, which the server still has live.
//
// **The order is the point, and it is the reverse of `logout`'s.** There, revoking comes first
// because deleting would destroy the only copy of what has to be presented. Here the new credential
// is already stored and the old one is gone from this machine, so revoking can only come last — and
// doing it first would mean a failed login signed somebody out.
//
// Without this, signing in twice on one machine leaves two live authorizations: the second `login`
// overwrites the only copy of the first one's refresh token, so the first can never be revoked from
// here again and stays live until it idles out. Only one of them is visible to the person. Found by
// logging in twice while testing (2026-10-05).
//
// **Which credential was replaced decides what can be done, and v4 turned one branch into three.**
// A key has no authorization behind it; the old browser login's credential has the refresh token that
// ends its authorization, and ending it is what this function is for; and **this version's own
// credential has neither** — the server ends the login it replaces in the same browser action that
// created the new one, matched by the name somebody typed on the page (v4 Q7). So there is nothing to
// call on that last path, and nothing it may claim either: a name typed differently this time finds
// nothing and ends nothing.
//
// **Silence is not an option on the last two**, either. Saying nothing about a credential that was
// just replaced reads as "the previous one was dealt with", which is exactly how the version before
// this one came to say the opposite of the truth about the unattended leftover.
//
// A failure in the revocation branch is a warning rather than an error, and that is not laxness: the
// login itself succeeded and the credential in hand works, so failing the command would be a lie
// about the state the person is actually in. Saying what did not happen is the honest version.
func revokeReplaced(ctx context.Context, server string, previous credentials.Credentials, hadPrevious bool) {
	if !hadPrevious {
		return
	}
	if apikey.IsKey(previous.AccessToken) {
		// **A key has no authorization behind it to end**, and this is not the place that ends one in
		// any case: it is revoked in the console, where the list shows what is being changed
		// (ADR 0038 §决定 7). Without this, a machine still holding a key from an older version — the
		// paste login v2 had — would post it to `/oauth/revoke` with no client id: refused by the
		// server, reported here as an authorization that "stayed live", which is a sentence about
		// something that never existed.
		fmt.Printf("（上一次那份凭据是一把 API key，这里不撤它——key 只能在控制台撤销：%s/account/keys）\n",
			config.WebURL())
		return
	}
	switch auth.LeftoverOf(previous) {
	case auth.RevocableLeftover:
		err := auth.RevokeAuthorization(ctx, auth.RevokeOptions{
			Server: server, Credential: previous,
		})
		if err != nil {
			fmt.Fprintf(os.Stderr,
				"上一次那张授权没能撤销（这次登录是好的，但它在服务端仍然有效）：%v\n", err)
			return
		}
		fmt.Println("上一次那张授权已撤销，这台机器上只剩这一次。")
	case auth.UnrevocableLeftover:
		// **The one sentence that must not be 「上一份已撤销」**, which is a sentence about something
		// that never happened: nothing can be sent anywhere — its whole authentication was a client
		// secret read from two environment variables this CLI no longer has, so a request to the
		// revocation endpoint would be one the server must refuse. Saying what it is beats sending it,
		// and beats silence.
		fmt.Println("（上一次那份凭据来自无人值守登录，这个版本不再支持那条路，因此无法从这台机器撤销它；" +
			"它没有刷新令牌，过期即止，不会自动续期。）")
	default:
		// This version's own credential, whose ending was the server's doing and not this command's
		// (v4 Q7). Said out loud because the alternative — silence — reads as a claim, and said as
		// what it is because the ending depends on the name matching: the CLI cannot know that it did,
		// and a sentence that assumed it would be wrong on the one machine that typed a different one.
		fmt.Printf("（上一次那份凭据是本机存的登录凭据；服务端在点「同意并继续」那一下、按你这次填的名称"+
			"把它结束了。名称跟上次不一样就不会——可以在控制台的「登录设备」里看：%s/account/sessions）\n",
			config.WebURL())
	}
}

// noteIfTheEnvironmentWins is the sentence a login owes when a variable is exported: the login
// worked, and every command afterwards still presents the variable's key. It was written once for the
// command's two shapes, and one shape is left — the reason it is a function rather than three lines
// is the same as ever, and the day a third shape arrives it is already here.
func noteIfTheEnvironmentWins(progress io.Writer, store credentials.EnvStore) {
	if store.FromEnv() {
		fmt.Fprintf(progress, "注意：%s 仍然优先于本机凭据，这台机器上的命令用的还是环境里那一把。\n", store.Var)
	}
}

// saveAndReport stores what the login earned, and says what it is.
//
// **`ClientID` and `RefreshToken` are deliberately not written, and their absence is the shape.**
// Nothing renews this credential, so there is no refresh to present a client id with — the stored
// blob is the token, its date, and its scopes, and that shape is also what tells it apart from the
// leftovers of the logins that are gone (see `auth.LeftoverOf`).
func saveAndReport(progress io.Writer, store credentials.EnvStore, notice, server string,
	credential auth.Credential) error {
	stored := credentials.Credentials{
		Name:        credential.Name,
		AccessToken: credential.AccessToken,
		ExpiresAt:   credential.ExpiresAt,
		Scope:       strings.Join(credential.Scopes, " "),
	}
	if err := store.Save(stored); err != nil {
		return fmt.Errorf("保存凭据：%w", err)
	}
	if notice != "" {
		// On stderr, so a script reading stdout is not confused by it — and shown every time the
		// fallback is in use, because ADR 0025 asks for it to be announced rather than discovered.
		fmt.Fprintln(os.Stderr, notice)
	}
	fmt.Fprintf(progress, "已登录 %s，凭据存放于 %s\n", server, store.Where())
	// Its name, its permissions, and when it expires: the three the design asks a successful login to
	// print (验收 #1), in that order. See `loginFacts` for where the name comes from and for the
	// word 「至少」 in front of the date.
	fmt.Fprintln(progress, loginFacts(stored))
	noteIfTheEnvironmentWins(progress, store)
	return nil
}

// loginFacts is the one line that describes a login credential: which machine it is, what it may do,
// and when it could end.
//
// **The name is printed because the exchange is the only place it can be read.** The person typed it
// on the sign-in page, so the server holds it and this process never saw it; nothing else this CLI
// can ask carries it (`/me`'s key half is null for anything that is not an API key). It arrives
// beside the credential and is written down beside it, and it is the same string the console puts
// after `CLI ·` on the row where this login can be ended.
//
// **「至少」 is the honest word for the date**, and the reason this is a function rather than a format
// string at each of the two places that print it (`login`'s report and `auth status`): the value is
// the idle ruler as it stood when the browser handed the code over, and the server slides it on every
// use — so what it names is the earliest moment this could end, not the moment it will. A person who
// read it as a fixed end would conclude their credential died an hour ago while it was working.
func loginFacts(c credentials.Credentials) string {
	// Omitted rather than rendered empty. Every credential this CLI writes has a name — `auth.Login`
	// refuses an exchange answer without one — so the case is a blob written by something else, and
	// the honest answer to that is to say less rather than to print 「」.
	whose := ""
	if c.Name != "" {
		whose = fmt.Sprintf("这台机器叫「%s」；", c.Name)
	}
	return fmt.Sprintf("%s权限 %s；至少有效到 %s（每次使用服务端都会往后顺延）",
		whose, c.Scope, c.ExpiresAt.UTC().Format(time.RFC3339))
}

// logout ends this machine's use of a credential, and only this machine's.
//
// **It no longer revokes anything server-side, and that is the v2 decision rather than an omission**
// (ADR 0038 §决定 7). Two credentials now exist and only one of them has a revocation endpoint this
// CLI can reach: a key is revoked in the console, where the person can see the list they are
// changing. A `logout` that tried to do both would have to guess which kind it held, and would
// report a failure for the ordinary case of a machine that is simply being cleaned up.
func logout(args []string) error {
	if len(args) > 0 {
		return usagef("logout 不带参数。%s", usageAt("logout"))
	}
	server := config.Server()
	store, _ := openStore(server)
	return logoutWith(store)
}

// logoutWith is `logout` on a store it was handed, the way `login` is on one it opened: what this
// command does with a store is the part worth testing, and reaching for the platform's keychain from
// inside it is the part that is not.
//
// `server` is not a parameter: nothing here talks to a server, and the only address used is the one
// printed for a person to open.
// whereToRevoke says where the server's copy of one credential is ended, or nothing when there is no
// copy to speak of.
//
// Four sentences because the four kinds really differ, and **v4 added one of them**: a key is revoked
// in the console, where the list shows what is being changed (ADR 0038 §决定 7); the login credential
// this version issues is a 「登录设备」 on the console and is ended from that screen, with a 「退出」 —
// the same action a browser session there takes (v4 Q6); and the two leftovers have no entry at all
// (ADR 0040 §后果), which is why the old sentence is still here for them. Pointing at a screen for a
// row that is not on it sends somebody to a dead end dressed as help, and claiming there is no screen
// when there is now one is the same mistake with the opposite sign.
//
// It answers with a string rather than printing, because the two commands that ask it indent
// differently — `auth status` under a line about the credential, `logout` at the margin — and two
// copies of this decision are how the same credential comes to be pointed at two different places.
func whereToRevoke(c credentials.Credentials) string {
	switch {
	case c.AccessToken == "":
		// Nothing was here, so there is nothing on the server to point at.
		return ""
	case apikey.IsKey(c.AccessToken):
		return fmt.Sprintf("服务端的这把 key 在控制台撤销：%s/account/keys", config.WebURL())
	}
	switch auth.LeftoverOf(c) {
	case auth.NotALeftover:
		return fmt.Sprintf("服务端那份是本机的一次 CLI 登录，在控制台的「登录设备」里结束它：%s/account/sessions",
			config.WebURL())
	case auth.RevocableLeftover:
		return "服务端那份是旧版浏览器登录换来的授权：控制台没有它的入口；重置密码会一并撤销它。"
	default:
		// The unattended leftover, which is not an authorization anything can reach: it dies of its
		// own date, and either of the two sentences above would be about a screen that does not list
		// it.
		return "服务端那份来自无人值守登录：控制台没有它的入口，从这里也撤不掉；它会自己到点失效。"
	}
}

func logoutWith(store credentials.EnvStore) error {
	// **Read before deleting, because afterwards neither question can be answered.** Whether this
	// machine held anything decides the first sentence, and what it held decides the last one: a key
	// is revoked in the console, and the authorization an old browser login left behind has no entry
	// there at all (ADR 0040 §后果) — pointing at `/account/keys` for that one sends somebody to look
	// for something that is not on the list.
	stored, hadStored, err := store.Stored()
	if err != nil {
		// **A read that failed is not "there was nothing here", and this is the whole difference
		// between a command that works and a command that lies.** Folding it into `hadStored = false`
		// — which this did — printed「本机没有凭据，没什么可清的」and exited 0 on a machine whose
		// keychain was merely locked, while the credential sat there and every later command went on
		// authenticating with it. The delete below cannot be believed either: the responsive keychain
		// returns success on its own timeout *by design* (`responsiveKeychain.Delete`), so nothing
		// downstream reports the thing this one just concealed. Refusing is what the v1 command did
		// with the same failure, and it is the honest answer: this machine's state is unknown, so it
		// cannot be reported as clean.
		return fmt.Errorf("读不到本机凭据，所以没有清除任何东西（钥匙串可能锁着）：%w", err)
	}
	inUse := stored
	if fromVariable, found, loadErr := store.Load(); loadErr == nil && found {
		inUse = fromVariable
	}

	if err := store.Delete(); err != nil {
		return fmt.Errorf("清除本地凭据：%w", err)
	}
	if hadStored {
		fmt.Printf("已清除本地凭据（%s）。\n", store.Where())
	} else {
		fmt.Println("本机没有凭据，没什么可清的。")
	}

	if store.FromEnv() {
		// The honest half: this command cannot end a credential it does not own, and saying so is
		// better than a "logged out" that leaves the next command working.
		fmt.Printf("但是 %s 里还有一份，命令仍然会用它——它不在本机存储里，请从环境里去掉它。\n",
			store.Var)
	}
	// **About the credential that was actually deleted**, which is not always the one in use: with a
	// variable exported, `inUse` is the variable's key, and the authorization this command just
	// removed from the machine would have gone unmentioned — including the fact that nothing can
	// revoke it from the console.
	if where := whereToRevoke(stored); where != "" {
		fmt.Println(where)
	}
	if inUse.AccessToken != stored.AccessToken && apikey.IsKey(inUse.AccessToken) {
		// **The variable's copy gets only this sentence, and only when it is a key.** What a stored
		// credential is, is knowable — every login writes one of two shapes — while a variable holds
		// whatever was exported into it. Anything else there is not a credential this product put on a
		// server, so pointing at a place to revoke it would be a sentence about nothing (and the
		// sentence above already says the actionable half: take it out of the environment).
		fmt.Printf("服务端的这把 key 在控制台撤销：%s/account/keys\n", config.WebURL())
	}
	// Said plainly because it is the thing people get wrong: a logout here is not a browser logout.
	fmt.Println("（浏览器里的登录状态不受影响。）")
	return nil
}

// ---------------------------------------------------------------------------
// Reading skills
// ---------------------------------------------------------------------------

// session is the two lines every authenticated command needs: a credential this machine can present,
// and a client to present it with — or, when there is nothing to present, a login.
func session(ctx context.Context) (api.Client, error) {
	return sessionMode(ctx, false)
}

// sessionMode is `session` with v14's `--no-login` switch (PRD §CLI 命令面 共同点).
//
// **The switch is for a caller that must not be interrupted by a browser.** Seven read commands are
// the ones an agent runs at the start of a session, and a login page appearing there is not a prompt
// anybody is present to answer. With the switch, no credential means a failed command with the next
// step in it; without it, the v4 behaviour stands — the command runs the login and finishes.
func sessionMode(ctx context.Context, noLogin bool) (api.Client, error) {
	if err := environmentKeyProblem(); err != nil {
		return api.Client{}, err
	}
	return clientFor(ctx, noLogin)
}

// environmentKeyProblem refuses a `SKILLMASTER_API_KEY` that is not an API key, at the first command
// that would use it.
//
// **Because the alternative is a sentence about the wrong thing.** The environment's credential is
// loaded as a bare token with nothing beside it, so anything that is not a key walks into the
// sentence this CLI keeps for a leftover of a login that is gone — a message about a shape nobody
// used, for a variable somebody typed with the wrong prefix or filled from the wrong clipboard. The
// variable is documented as holding a key, so a value that is not one is worth naming as itself.
func environmentKeyProblem() error {
	value := strings.TrimSpace(os.Getenv(config.ApiKeyEnv))
	if value == "" || apikey.IsKey(value) {
		return nil
	}
	return &credentialError{message: fmt.Sprintf("%s 里装的不是一把 API key（应以 %s 开头），先改对或去掉它——"+
		"本机凭据与这个变量无关", config.ApiKeyEnv, apikey.Prefix)}
}

// startLogin is the login a command runs for itself when this machine has nothing to present.
//
// A variable only so that a test can stand where the browser stands: what is being pinned is that
// the command **carries on afterwards**, and that cannot be observed by opening a real browser
// (v4 做 #8). Same shape, and the same reason, as `browserAvailable` above.
// loginForACommand is the same login, narrating to stderr: the command's own answer is what goes on
// stdout, and in `--json` mode that is exactly one object.
func loginForACommand(ctx context.Context, server string, store credentials.EnvStore, notice string) error {
	return loginWithBrowser(ctx, os.Stderr, server, store, notice)
}

// A named function rather than a closure, so the suite can compare against it by pointer and know
// whether its own stub is still in place.
var startLogin = loginForACommand

// clientFor builds a client from the credential this machine presents — **starting the login itself
// when there is none**.
//
// **A command that needs a credential now gets one instead of failing** (v4 做 #8, 验收 #9). A person
// on a fresh machine typed `skills list` and was told to run `login`, which is an instruction to
// retype the command they just typed; the design's answer is that the command runs the login and
// then finishes. The sentence it prints first says what is happening, so a browser appearing is not a
// surprise but the thing that was announced.
//
// **One boundary, and it is knowable in advance**: a machine that cannot open a browser is not asked
// to. There the failure is what it always was, and it points at the variable — the same refusal
// `login` itself gives, and for the same reason: nobody there can click.
//
// The variable is checked before this: a `SKILLMASTER_API_KEY` that holds something is a credential
// this machine *has*, so a container with a key in it never reaches the login at all.
func clientFor(ctx context.Context, noLogin bool) (api.Client, error) {
	server := config.Server()
	store, notice := openStore(server)

	stored, err := auth.AccessToken(store)
	if errors.Is(err, auth.ErrNotSignedIn) && noLogin {
		// **No browser, and the sentence names both ways that do work on a machine nobody is sitting
		// at.** A login is the one the switch is protecting against; an API key in the environment is
		// the shape that needs no person and no store. Returning here also means no request is sent,
		// which is the first thing a caller in a loop can rely on.
		return api.Client{}, &credentialError{message: fmt.Sprintf("本机没有凭据，--no-login 不会发起登录。先运行 skillmaster login，"+
			"或把一把控制台签发的 API key 放进 %s。", config.ApiKeyEnv)}
	}
	if errors.Is(err, auth.ErrNotSignedIn) && browserAvailable() {
		// On stderr, because it is a notice and not the command's answer: `--json` promises that
		// stdout carries exactly one object, and a line printed while the browser opens would be a
		// second one.
		fmt.Fprintf(os.Stderr, "本机没有凭据，先登录 %s。\n", server)
		if loginErr := startLogin(ctx, server, store, notice); loginErr != nil {
			return api.Client{}, loginErr
		}
		stored, err = auth.AccessToken(store)
	}
	if err != nil {
		return api.Client{}, err
	}
	return api.Client{BaseURL: server, Token: stored.AccessToken}, nil
}

// answered is what a translated credential/denial failure travels as: the sentence a person reads,
// with the server error it was made from still reachable.
//
// **The cause is kept for the machine contract, not for the text.** `classifyFailure` reads the code
// through `Unwrap`, so a `--json` answer names the server's own reason (`token_expired`, `forbidden`)
// even though the text is ours — one error, not a copy whose two halves can disagree.
type answered struct {
	message string
	cause   error
}

func (a *answered) Error() string { return a.message }
func (a *answered) Unwrap() error { return a.cause }

// authenticated runs a command that needs a credential, and turns the server's refusal into an answer
// a person can act on.
//
// **Nothing is retried here any more, and that is the version rather than an omission.** This used to
// give a refused credential one replacement: refresh the chain and try once more, because a token can
// be revoked while the expiry this machine stored still says fifty minutes. There is no chain left to
// rotate (v4 做 #2) and no store to write, so a refusal is the server's final answer — and the useful
// half of it is the reason, which is what tells somebody whether to come back, to get a credential of
// another shape, or that their account is the problem.
//
// **A 403 is a different matter and is answered by its own reason** rather than by a status code: the
// design's rule is that "another credential would work" and "no credential will help" must not be
// said in the same words (v4 §一次操作的结果落在谁身上). See `denialMessage`.
func authenticated[T any](ctx context.Context, call func(api.Client) (T, error)) (T, error) {
	return authenticatedWith(ctx, false, call)
}

// authenticatedWith is `authenticated` with v14's `--no-login` switch. One implementation rather than
// two, because everything after the client is acquired — the two refusals and their sentences — is
// the same whichever way the credential arrived.
func authenticatedWith[T any](ctx context.Context, noLogin bool,
	call func(api.Client) (T, error)) (T, error) {
	var nothing T

	client, err := sessionMode(ctx, noLogin)
	if err != nil {
		return nothing, err
	}
	result, err := call(client)
	if err == nil {
		return result, nil
	}

	// **Which credential the server just refused is part of the sentence**, because there can be two
	// in play: the environment's wins over the local store (ADR 0025), and a person with one exported
	// and one stored has no way to tell from 「这把 API key」 which of them it is about — they would
	// renew the one they meant and watch the command fail the same way.
	key := apikey.IsKey(client.Token)
	if message, known := refusalMessage(err, key); known {
		return nothing, &answered{
			message: fmt.Sprintf("%s\n      这份凭据来自 %s。", message, credentialSource()),
			cause:   err,
		}
	}
	// **v14's private-tag 403 says where to go**, and its answer is not the v4 permission sentence:
	// the caller *can* read the skill and only needs to add it to their library first. Checked before
	// the generic 403 because the code is the same `forbidden` and only `details.reason` separates
	// them.
	if message, known := v14DenialMessage(err); known {
		return nothing, &answered{message: message, cause: err}
	}
	if message, known := denialMessage(err, key); known {
		return nothing, &answered{message: message, cause: err}
	}
	if errors.Is(err, api.ErrUnauthorized) {
		if key {
			// A key, refused for a reason this build does not know. Still not renewable here: a key
			// has no chain, and the console is where it is looked at (ADR 0040 §决定 4).
			return nothing, fmt.Errorf("%w；这是一把 API key，本机不能续期——到控制台看看：%s/account/keys"+
				"\n      这份凭据来自 %s。", err, config.WebURL(), credentialSource())
		}
		// A login credential whose refusal this build cannot read. The next step is the same one the
		// named reasons give, and the design names it there for the same reason: this path has no
		// renewal, so the answer is another login rather than waiting for one.
		return nothing, fmt.Errorf("%w；这份凭据是一次 CLI 登录换来的，"+
			"重新运行 skillmaster login 点一下就能换一份新的\n      这份凭据来自 %s。",
			err, credentialSource())
	}
	return result, err
}

func search(ctx context.Context, args []string) error {
	options, err := parseQueryArgs(args, true)
	if err != nil {
		return err
	}

	found, err := authenticatedWith(ctx, options.noLogin, func(client api.Client) (listed, error) {
		if options.note != "" {
			return listed{note: options.note}, nil
		}
		page, err := client.QuerySkills(ctx, queryOptionsFrom(options))
		if err != nil {
			return listed{}, err
		}
		return listed{cards: page.Skills, more: page.NextCursor != ""}, nil
	})
	if err != nil {
		return v14Error(err)
	}
	if len(found.cards) == 0 {
		if found.note != "" {
			fmt.Println(found.note)
			return nil
		}
		fmt.Println("没有匹配的 skill。")
		for _, hint := range filterHints(options) {
			fmt.Println(hint)
		}
		return nil
	}
	printCards(found.cards, options.verbose)
	if found.more {
		// Said rather than silently dropped: a listing that stops without saying so reads as
		// "that is everything", which is a claim the CLI would be making on the server's behalf.
		fmt.Printf("（还有更多结果；缩小关键词、加分类或公开标签再试。）\n")
	}
	return nil
}

// listSkills prints the skills in this caller's library, one addressable line each.
//
// **This is the read entry point, and it is the layer Claude Code loads at session start**: a name
// and a description, and nothing else (§1.2). **The address it prints carries no version** (ADR 0035).
//
// **Since v14 the question is the library, not "everything I may read"** (ADR 0082): a public skill
// the caller has not joined is found with `skills search`, and appears here only after `skill join`.
// The default state is the consumable one — published, online, not deleted — and the other states are
// asked for with `--show`.
//
// **One page by default, `--all` for the rest, and no cursor a caller has to carry.** The page token
// this command used to print was 191 characters of arbitrary alphanumeric, and asking the *model* to
// reproduce that is asking for the wrong thing. What crosses to the caller is a **switch**: the loop
// still exists, in `collect`, and `--all` is how a caller asks for it in one go.
func listSkills(ctx context.Context, args []string) error {
	options, err := parseQueryArgs(args, false)
	if err != nil {
		return err
	}

	found, err := authenticatedWith(ctx, options.noLogin, func(client api.Client) (listed, error) {
		if options.note != "" {
			return listed{note: options.note}, nil
		}
		return collect(ctx, client, queryOptionsFrom(options), options.all)
	})
	if err != nil {
		return v14Error(err)
	}

	if len(found.cards) == 0 {
		if found.note != "" {
			fmt.Println(found.note)
			return nil
		}
		fmt.Println(emptyLibraryLine(options))
		for _, hint := range filterHints(options) {
			fmt.Println(hint)
		}
		return nil
	}
	printCards(found.cards, options.verbose)

	// Both endings carry the same instruction, and it is the one that matters: the address above is
	// what a later command takes. It is worth saying because the next step is not obvious — the
	// address alone does not load anything, and `invoke` is the command that both loads the body and
	// decides which version the rest of the task will read.
	if found.more {
		// **Said out loud, and it is the most important line this command prints.** A listing that
		// stops without saying so reads as "this is everything", and a caller that believed it would
		// draw a conclusion from the first page and act on it — the failure the literature calls
		// coverage collapse, and the reason the count and the alternatives are both here.
		fmt.Printf("（这一页 %d 个，还有更多。加载某一个：skillmaster skill invoke <上面某一行的地址>。）\n",
			len(found.cards))
		fmt.Println("（要收窄就用关键词或 --category / --my-tag / --public-tag；要全部就用 skillmaster skills list --all。）")
		return nil
	}
	fmt.Printf("（共 %d 个。加载某一个：skillmaster skill invoke <上面某一行的地址>。）\n", len(found.cards))
	return nil
}

// emptyLibraryLine is what the library says when it has nothing to show.
//
// Two sentences rather than one, because the two situations are different: an empty library is a
// place to put something, and a filter that matched nothing is a word to change. The console draws
// the same distinction (PRD §展示与计数口径 空态); this is its command-line half.
func emptyLibraryLine(options queryArgs) string {
	if options.query != "" || len(options.categories) > 0 || len(options.myTags) > 0 ||
		len(options.publicTags) > 0 || len(options.sources) > 0 ||
		len(options.namespaces) > 0 {
		return "没有匹配的 skill。"
	}
	switch options.show {
	case "deleted":
		return "没有已删除的 skill。"
	case "draft":
		return "没有草稿。"
	case "offline":
		return "没有已下线的 skill。"
	default:
		return "你的库里还没有能显示的 skill。"
	}
}

// printCards is how an L1 row looks, in the two places that print a listing.
//
// **The address is alone on its own line**, and that is for the caller rather than for looks: it is
// the value a later command takes, whole, and a line with nothing else on it is a line that can be
// copied without deciding where it ends. The reading goes under it, indented.
//
// **`--verbose` adds one more indented line and changes nothing above it** (PRD #51). The default card
// is the gateway's stable contract; the extra line is for a person looking at their own library, and
// putting it below means a caller that parses the first two lines still can.
func printCards(cards []api.Card, verbose bool) {
	for _, card := range cards {
		fmt.Println(card.Address())
		if summary := card.Summary(); summary != "" {
			fmt.Printf("    %s\n", summary)
		}
		if verbose {
			if line := verboseLine(card); line != "" {
				fmt.Printf("    %s\n", line)
			}
		}
	}
}

// verboseLine is `--verbose`'s one line: where the skill came from, what state it is in, and the
// facts a person narrowing a list needs (PRD §CLI 命令面 `--verbose`).
//
// **A field with no value is left out rather than printed empty.** A draft has no version, a skill
// need not be on a shelf, and a list may have no tags; printing `版本：` for each would be three
// blanks pretending to be answers.
func verboseLine(card api.Card) string {
	var parts []string
	if card.Source != "" {
		parts = append(parts, "来源："+sourceLabel(card.Source))
	}
	if card.State != "" {
		parts = append(parts, "状态："+cardStateLabel(card.State))
	}
	if card.Version != "" {
		parts = append(parts, "版本："+card.Version)
	}
	when, whenLabel := card.UpdatedAt, "更新于"
	if card.State == "deleted" {
		// The deleted view answers "when did it go", not "when did it last change" — the row is in
		// this list because of the former.
		whenLabel = "删除于"
		if card.DeletedAt != nil {
			when = *card.DeletedAt
		}
	}
	if when != "" {
		parts = append(parts, whenLabel+"："+dayOf(when))
	}
	if card.Category != nil && card.Category.Name != "" {
		parts = append(parts, "分类："+card.Category.Name)
	}
	if len(card.PublicTags) > 0 {
		// No `#` here: the hash is the console's way of writing this layer (voice-spec §公开标签
		// 写成 #名字). The CLI and the documentation print the word itself.
		names := make([]string, 0, len(card.PublicTags))
		for _, tag := range card.PublicTags {
			names = append(names, tag.Name)
		}
		parts = append(parts, "公开标签："+strings.Join(names, "、"))
	}
	return strings.Join(parts, " · ")
}

// dayOf keeps the date half of an RFC 3339 timestamp. A listing is read to decide what to open, and
// the clock time has never decided that; the server's own spelling is kept rather than reformatted.
func dayOf(timestamp string) string {
	if len(timestamp) >= 10 {
		return timestamp[:10]
	}
	return timestamp
}

func sourceLabel(source string) string {
	switch source {
	case "created":
		return "我创建的"
	case "shared":
		return "共享给我的"
	case "joined":
		return "我加入的"
	default:
		return source
	}
}

func cardStateLabel(state string) string {
	switch state {
	case "live":
		return "在用"
	case "draft":
		return "草稿"
	case "offline":
		return "已下线"
	case "deleted":
		return "已删除"
	default:
		return state
	}
}

// listed is what a call to this command read, whether there was more it did not read, and — when a
// filter named something the vocabulary does not have — the sentence to print instead of a request.
type listed struct {
	cards []api.Card
	more  bool
	note  string
}

// collect reads one page, or every page when `--all` was asked for.
//
// The pages are gathered before anything is printed, so a request that fails in the middle prints no
// half list. A half list and a complete one look exactly alike, and this is a listing whose whole
// purpose is to be the caller's picture of what exists.
func collect(ctx context.Context, client api.Client, options api.QueryOptions,
	all bool) (listed, error) {
	var cards []api.Card
	cursor := ""
	seen := map[string]struct{}{}
	for {
		// **The filter goes on every page, and that is the one thing this loop has to get right.**
		// The server's cursor carries the ordering and not the predicate, so a page fetched without
		// the filter is accepted and quietly answers a different question — the silent skip the
		// whole cursor design exists to prevent, arriving from this side. Passing the same options
		// each turn is what makes it unrepresentable here; a test asserts the query carries it.
		options.Cursor = cursor
		page, err := client.QuerySkills(ctx, options)
		if err != nil {
			return listed{}, err
		}
		cards = append(cards, page.Skills...)
		if page.NextCursor == "" {
			return listed{cards: cards}, nil
		}
		if !all {
			return listed{cards: cards, more: true}, nil
		}
		// A server that answers with a cursor it has already given is not paging, and looping on it
		// would hang with no output. Any repeat counts, not just the one just sent: A→B→A walks
		// forever without ever sending the same cursor twice in a row. Stopped and said out loud
		// rather than repeated: the silence of an infinite loop reads as a slow network, and this
		// one is a bug on the far end.
		if _, repeated := seen[page.NextCursor]; repeated {
			return listed{}, fmt.Errorf("服务端反复给出同一个游标，列表走不下去了（已取到 %d 条）", len(cards))
		}
		seen[page.NextCursor] = struct{}{}
		cursor = page.NextCursor
	}
}

// queryArgs is the parsed form of either command's line, where `search` records that its args came
// from the public half.
//
// One type for both because they share seven flags and every rule about repetition; the differences
// are which flags are refused and which question the request asks.
type queryArgs struct {
	search     bool
	query      string
	all        bool
	verbose    bool
	noLogin    bool
	namespaces []string
	categories []string
	myTags     []string
	publicTags []string
	sources    []string
	sort       string
	show       string
	// note is a sentence to print instead of a request — used by `--show deleted` with a source that
	// cannot apply. It is an answer (exit 0), not a refusal.
	note string
}

func parseQueryArgs(args []string, search bool) (queryArgs, error) {
	path := queryPath(search)
	options := queryArgs{search: search, sort: "recent", show: "live"}
	if search {
		options.sort, options.show = "", ""
	}
	var words []string

	value := func(i int, flag string) (string, error) {
		return flagValue(args, i, flag, path)
	}

	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--all":
			if search {
				return queryArgs{}, usagef("skills search 不接受 --all；检索只取一页。%s", usageAt(path))
			}
			options.all = true
		case "--verbose":
			options.verbose = true
		case "--no-login":
			options.noLogin = true
		case "--namespace":
			if search {
				return queryArgs{}, usagef("skills search 不接受 --namespace；它只搜公域。%s", usageAt(path))
			}
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			options.namespaces = append(options.namespaces, item)
			i++
		case "--category":
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			options.categories = append(options.categories, item)
			i++
		case "--my-tag":
			if search {
				return queryArgs{}, usagef("skills search 不接受 --my-tag；我的标签只属于库。%s", usageAt(path))
			}
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			options.myTags = append(options.myTags, item)
			i++
		case "--public-tag":
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			options.publicTags = append(options.publicTags, item)
			i++
		case "--source":
			if search {
				return queryArgs{}, usagef("skills search 不接受 --source；公域没有库来源。%s", usageAt(path))
			}
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			if !slices.Contains([]string{"created", "shared", "joined"}, item) {
				return queryArgs{}, usagef("--source 只接受 created、shared、joined，收到 %q。%s", item, usageAt(path))
			}
			options.sources = append(options.sources, item)
			i++
		case "--sort":
			if search {
				return queryArgs{}, usagef("skills search 不接受 --sort；公域检索只有相关度。%s", usageAt(path))
			}
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			if item != "recent" && item != "name" {
				return queryArgs{}, usagef("--sort 只接受 recent 或 name，收到 %q。%s", item, usageAt(path))
			}
			options.sort = item
			i++
		case "--show":
			if search {
				return queryArgs{}, usagef("skills search 不接受 --show；公域只有可发现的 skill。%s", usageAt(path))
			}
			item, err := value(i, args[i])
			if err != nil {
				return queryArgs{}, err
			}
			if !slices.Contains([]string{"live", "draft", "offline", "deleted", "all"}, item) {
				return queryArgs{}, usagef("--show 只接受 live、draft、offline、deleted 或 all，收到 %q。%s",
					item, usageAt(path))
			}
			options.show = item
			i++
		default:
			if strings.HasPrefix(args[i], "-") {
				return queryArgs{}, unknownFlag(queryPath(search), args[i])
			}
			words = append(words, args[i])
		}
	}
	options.query = strings.TrimSpace(strings.Join(words, " "))

	if options.show == "deleted" {
		// The deleted view is the author's own rows: three tag filters cannot apply to it, and a
		// source other than created describes a row that is not there (PRD §CLI 命令面 列表视图).
		if len(options.categories) > 0 || len(options.myTags) > 0 || len(options.publicTags) > 0 {
			return queryArgs{}, usagef("--show deleted 不看标签：去掉 --category / --my-tag / --public-tag。%s", usageAt(path))
		}
		for _, source := range options.sources {
			if source != "created" {
				options.note = "「已删除」只含我创建的 skill，--source 只接受 created；这次没有结果。"
				break
			}
		}
	}
	if search && options.query == "" && len(options.categories) == 0 && len(options.publicTags) == 0 {
		return queryArgs{}, usagef("skills search 要给关键词、--category 或 --public-tag 至少一个。%s", usageAt(path))
	}
	return options, nil
}

// queryOptionsFrom is the request the command line describes.
//
// **The filter names travel as typed.** The API plane takes the words a person types and resolves
// them itself — categories, private-tag definitions and public-tag keys all have one server-side
// reader (TD §接口, `LibraryListUseCase.api` / `SearchPublicSkillsUseCase.api`). Resolving them here
// would be a second implementation of that rule, and a client that sent a key where a name was
// expected would ask a filter that selects nothing.
func queryOptionsFrom(options queryArgs) api.QueryOptions {
	query := api.QueryOptions{
		Scope:      api.ScopeLibrary,
		Query:      options.query,
		Namespaces: options.namespaces,
		Categories: options.categories,
		MyTags:     options.myTags,
		PublicTags: options.publicTags,
		Sources:    options.sources,
		Sort:       options.sort,
		Show:       options.show,
	}
	if options.search {
		query.Scope = api.ScopePublic
	}
	return query
}

// filterHints are what an empty result adds when a filter was in play: the name may simply have
// matched nothing, and the command that prints the vocabulary is where the next attempt starts.
//
// **Said even for a name that does exist**, because the CLI cannot tell the two apart: the server
// answers an unknown name with an empty page by design (a filter narrows; it does not fail), and a
// word that is in the vocabulary can also match nothing. The pointer is true in both cases.
func filterHints(options queryArgs) []string {
	var hints []string
	if len(options.categories) > 0 {
		hints = append(hints, "（分类的名字以 skillmaster skills categories 为准。）")
	}
	if len(options.myTags) > 0 {
		hints = append(hints, "（我的标签的名字以 skillmaster skills my-tags 为准。）")
	}
	if len(options.publicTags) > 0 {
		hints = append(hints, "（公开标签的名字以 skillmaster skills public-tags 为准。）")
	}
	return hints
}

// shareSkill hands one skill to one account, which is the first thing this CLI can do that changes
// what somebody else reads (ADR 0034).
//
// The boundary is worth stating where the person can see it, because the five rungs are not
// variations of one another: `write` adds versions and still cannot publish them, `maintain` can, and
// neither can change visibility or the collaborator list — that is `admin`. Deletion is the owner's
// alone and is not on the ladder at all (ADR 0047).
func shareSkill(ctx context.Context, args []string) error {
	address, handle, role, err := parseShare(args)
	if err != nil {
		return err
	}

	result, err := authenticated(ctx, func(client api.Client) (api.ShareResult, error) {
		return client.Share(ctx, address, handle, role)
	})
	if err != nil {
		return err
	}
	fmt.Printf("已把 %s 共享给 %s：%s。\n", address, result.Handle, roleLine(result.Role))
	return nil
}

// parseShare reads the arguments, and every rejection here is one the server would also make — this
// only makes it before a round trip and in a language the caller is reading. ADR 0011's line holds:
// the server is the authority, and these are courtesies.
func parseShare(args []string) (address, handle, role string, err error) {
	if len(args) == 0 {
		return "", "", "", usagef("要接地址和 --to。%s", usageAt("skill share"))
	}
	address = args[0]
	// The lowest rung: a share that grants nothing is not a share, and the default has to be one of
	// the five. It used to be `viewer`, whose meaning is `browse` now — see the refusal below.
	role = api.RoleInvoke

	rest := args[1:]
	for len(rest) > 0 {
		switch rest[0] {
		case "--to":
			if len(rest) < 2 {
				return "", "", "", usagef("--to 后面要跟一个用户名。%s", usageAt("skill share"))
			}
			handle, rest = rest[1], rest[2:]
		case "--role":
			if len(rest) < 2 {
				return "", "", "", usagef("--role 后面要跟 invoke、browse、write、maintain 或 admin。%s", usageAt("skill share"))
			}
			role, rest = rest[1], rest[2:]
		default:
			return "", "", "", unknownFlag("skill share", rest[0])
		}
	}

	// `--to` has no default value, and that is the point: there is no such thing as sharing with
	// somebody in general, and a default here would mean the command could share with a person nobody
	// named.
	if handle == "" {
		return "", "", "", usagef("--to 是必填的。%s", usageAt("skill share"))
	}
	// **The retired words are refused by name, not mapped.** `viewer` once meant reading and `editor`
	// once meant drafting, and a silent translation would answer "已授权" while granting something
	// other than what was asked (technical-design §一处要连带改的契约).
	switch role {
	case "viewer":
		return "", "", "", usagef("`viewer` 这个名字已经不再接受；只看文件与已上线的版本用 browse。%s", usageAt("skill share"))
	case "editor":
		return "", "", "", usagef("`editor` 这个名字已经不再接受；提交版本用 write。%s", usageAt("skill share"))
	}
	if !slices.Contains(api.Roles, role) {
		return "", "", "", usagef("角色只能是 %s，收到 %q。", strings.Join(api.Roles, "、"), role)
	}
	// The address's grammar first, so a malformed one is refused here rather than after a round trip.
	if _, err := api.SkillPath(address); err != nil {
		return "", "", "", usagef("%s", err)
	}
	// Then the pin, which parses and would be dropped on the floor: a share is a property of the
	// skill, not of a version of it. `@` cannot appear anywhere else in an address — it is the pin
	// separator, which is why a name containing one is not addressable at all — so this cannot
	// refuse a legitimate address.
	if strings.Contains(address, "@") {
		return "", "", "", usagef("共享是针对整个 skill 的，不是某一版——地址后面不要带 @版本。%s", usageAt("skill share"))
	}
	return address, handle, role, nil
}

// roleLine says what the role a skill was just shared with actually lets that person do.
//
// Not the constant's name repeated: `viewer` and `editor` are the wire's words, and someone who has
// just typed one of them is owed the answer to "so what can they do".
func roleLine(role string) string {
	switch role {
	case api.RoleInvoke:
		return "可以调用，但看不到文件清单"
	case api.RoleBrowse:
		return "可以调用并查看文件，但不能提交版本"
	case api.RoleWrite:
		return "可以提交新版本，但不能上线"
	case api.RoleMaintain:
		return "可以提交并上线版本，但不能修改可见性与协作者"
	case api.RoleAdmin:
		return "可以提交、上线，并修改可见性与协作者"
	default:
		// A role from a newer server than this build. Saying nothing about what it grants is the
		// honest answer; the name is above, and the server is the authority on what it means.
		return role
	}
}

// ---------------------------------------------------------------------------
// Loading a skill, and reading its files
// ---------------------------------------------------------------------------

const ()

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
	if len(rest) != 1 {
		return usagef("%s", usageAt("skill invoke"))
	}
	address := rest[0]
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
			return fetchAuthorBody(ctx, client, bareAddress(address), namedVersion)
		}
		return fetchBody(ctx, client, address, named)
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
				return fetchAuthorBody(ctx, client, bareAddress(address), fallback.pin)
			})
			if err == nil {
				authorPin = true
				namedVersion = fallback.pin
			}
		} else {
			fetched, err = authenticated(ctx, func(client api.Client) (loaded, error) {
				return fetchBody(ctx, client, address, false)
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
	} else if version.Name != "" {
		pin = pins.Pin{Kind: "name", Name: version.Name,
			Digest: strings.TrimPrefix(version.Digest, "sha256:")}
	} else {
		pin = pins.Pin{Kind: "digest", Digest: strings.TrimPrefix(version.Digest, "sha256:")}
	}
	previous, had, err := rememberPin(key, pin)
	if err != nil {
		return err
	}

	printContent(fetched.content, offset, withoutLineNumbers, func(next int) string {
		return fmt.Sprintf("skillmaster skill invoke %s --offset %d", address, next)
	})

	if had {
		if before := previous.Suffix(); before != now {
			// The visible half of a design whose state is otherwise invisible: nothing in the request
			// says which version this was, so the only thing standing between a moved pin and a
			// confident answer about the wrong version is this line.
			fmt.Printf("（%s 上次钉在 @%s，现在是 @%s——中间有人发布过。）\n", key, before, now)
		}
	}
	fmt.Printf("（已按 %s@%s 加载。它引用的文件用 skillmaster skill read %s <相对路径> 取。）\n",
		key, now, key)
	return nil
}

// fetchBody loads a skill's body without ever reading its manifest.
//
// **The manifest is a `browse` question, and `invoke` has to work one rung below it.** What resolves
// `latest` is `/metadata`; the bytes are then asked for at the version it named, so a publish landing
// in between cannot make the two disagree. No pin goes on the first request: a version named in the
// address is already there, and what this machine remembered is deliberately not consulted —
// invoking is the thing that decides.
func fetchBody(ctx context.Context, client api.Client, address string, namedInAddress bool) (loaded, error) {
	meta, err := client.Metadata(ctx, address, "")
	if err != nil {
		return loaded{}, err
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
	return loaded{meta: api.AuthorSnapshot(envelope.Skill, envelope.Version), content: content}, nil
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
		// that does list one — and it needs 「浏览」, which is said rather than left for the caller to
		// discover as a 403.
		return loaded{}, fileMissing(relpath, address)
	}
	if err != nil {
		return loaded{}, err
	}
	return loaded{meta: meta, content: content}, nil
}

// read prints one file of a skill, at the version `invoke` pinned.
//
// **This is the host's `Read`, and like it, it takes one path and returns the bytes** — with the same
// `--offset` for a long file, in the same unit. Which version those bytes come from is the one thing
// that cannot be the host's: locally a skill is a directory and its files are whatever is on disk,
// while here the same path means different bytes at different versions, so the version is decided and
// sent along (ADR 0035).
//
// A pin written into the address wins, and the remembered one is not consulted at all — sending both
// would ask the server to reconcile two sources, which it answers with a 400 (§4.2).
func read(ctx context.Context, args []string) error {
	offset, rest, err := parseOffset(args)
	if err != nil {
		return err
	}
	if len(rest) != 2 {
		return usagef("%s", usageAt("skill read"))
	}
	address, relpath := rest[0], rest[1]
	if err := checkAddressSyntax("skill read", address); err != nil {
		return err
	}
	pin, note, err := pinFor(address)
	if err != nil {
		return err
	}
	return readFile(ctx, address, bareAddress(address), relpath, pin, offset, note)
}

// pinFor decides which version a read is for, and what to say about that decision.
//
// Three answers, and the order between them is the design. A version named in the address wins and the
// store is not consulted at all — sending both would ask the server to reconcile two sources, which it
// answers with a 400 (§4.2). Otherwise it is what `invoke` remembered. And otherwise there is nothing,
// which is said out loud rather than passed over: the whole design rests on `invoke` having run, and a
// read that quietly used whatever is current would be exactly the drift this replaced — with nothing
// on screen to show it had happened.
//
// Split out of `read` because this is the part worth testing on its own: it touches a file and no
// network, so the decision can be pinned without a server or a credential.
func pinFor(address string) (pin, note string, err error) {
	key := bareAddress(address)
	version, named, err := versionInAddress(address)
	if err != nil {
		return "", "", err
	}
	if named {
		return version, "", nil
	}
	remembered, found, err := rememberedPin(key)
	if err != nil {
		return "", "", err
	}
	if !found {
		return "", fmt.Sprintf(
			"（%s 还没 invoke 过，按当前线上版本取。要先钉住就先 skillmaster skill invoke %s。）",
			key, key), nil
	}
	return remembered.Suffix(), "", nil
}

// versionInAddress is the version a caller wrote into the address, if they wrote one — and it refuses
// the one spelling that is neither: an `@` with nothing after it.
//
// **The one place that rule lives, because three things need it and they have to agree**: which
// version to send, whether the caller named one at all, and whether the address is malformed. The
// second is what tells two 404s apart — a version written in the command is a question the server
// answered "no", while a version this machine remembered is a local record that went stale — and each
// of those wants its own sentence and its own next step. The third is `invoke`'s to refuse as well as
// `read`'s, and a second copy of the parse is how the two would come to disagree.
func versionInAddress(address string) (version string, named bool, err error) {
	_, version, found := strings.Cut(address, "@")
	switch {
	case !found:
		return "", false, nil
	case version == "":
		// A bare `@` is not a version, and it is refused rather than read as "nothing named": the
		// quiet reading sends an address nobody meant and skips the line that says which version this
		// request is for — the one thing that must never be missing.
		return "", false, usagef("地址里的 @ 后面要跟一个版本名或 sha256:… 摘要。")
	}
	return version, true, nil
}

// versionGone is what a 404 says when the caller wrote the version into the command themselves.
//
// **It is deliberately not `pinFailure`, and the difference is the whole point of having two.** That
// one speaks about a record on this machine; here nothing local was consulted, so there is no record
// to blame. And its next step is `versions`, which lists what can be invoked — where `pinFailure`
// says to re-invoke, which for a caller whose explicit version just 404'd would resolve their problem
// by quietly handing them a different version, because a bare address means "whatever is current".
//
// **It names both things that may be missing, because the server's 404 does not.** One answer covers
// "no such skill", "not yours" and "no such version" (that is deliberate — §4.2), so a message that
// blamed only the version would send a caller with a typo'd skill name to a command that 404s too.
func versionGone(err error, version, address string) error {
	return fmt.Errorf("%w\n（地址里写的 @%s 取不到：可能没有这个版本，也可能没有这个 skill 或你看不到它。"+
		"看有哪些版本：skillmaster skill versions %s；要按名字找 skill：skillmaster skills list。）",
		err, version, bareAddress(address))
}

// notFoundFor picks the message for a 404 from a read command.
//
// **One function rather than the same three lines at three call sites**, because the branch is the
// whole decision and a copy of it is a place where `invoke`, `files` and `read` could come to explain
// the same failure three different ways. It is also the part worth testing on its own: no server and
// no credential are needed to check which of the two messages a given address produces.
func notFoundFor(err error, address, pin, key string) error {
	if version, named, _ := versionInAddress(address); named {
		return versionGone(err, version, address)
	}
	return pinFailure(err, pin, key)
}

// readFile is the one call `read` makes, whichever way the pin was decided.
//
// @param pin   what to send as the version header; empty means "whatever is current"
// @param note  what to say instead of naming the version, when there is no pin to name
func readFile(ctx context.Context, address, key, relpath, pin string, offset int,
	note string) error {
	// A draft's bytes live on the author plane, and the consumer plane refuses the pin outright.
	if pin != "" && isDraftPin(pin) {
		return readFromAuthor(ctx, key, pin, relpath, offset, note)
	}

	fetched, err := authenticated(ctx, func(client api.Client) (loaded, error) {
		return fetchFile(ctx, client, address, relpath, pin)
	})
	if err != nil {
		// A revoked version is readable to `maintain` and above through the author plane. The
		// original answer stands when that retry is refused — a `write` caller is not owed a
		// different explanation from the 410 the server already gave.
		if pin != "" && authorPlaneAnswers(err) {
			if authorErr := readFromAuthor(ctx, key, pin, relpath, offset, note); authorErr == nil {
				return nil
			} else if authorPlaneAnswer(authorErr) {
				return authorErr
			}
		}
		if errors.Is(err, api.ErrNotFound) && pin != "" {
			return notFoundFor(err, address, pin, key)
		}
		return err
	}

	// Which version this was, decided once for both paths. It is not a courtesy: the pin lives in a
	// file on this machine and nothing in the request shows it, so this line is the only thing that
	// makes it visible — and a binary file is still a file read at some version.
	which := note
	if which == "" {
		// The manifest's own answer to which version this was, rather than the pin that was sent: they
		// agree, and this one is the server's spelling of the address — which is what a caller would
		// copy into the next command.
		which = fmt.Sprintf("（按 %s 取。）", fetched.meta.Address())
	}

	if fetched.binary {
		return printBinary(fetched.content, relpath, offset, which)
	}
	printContent(fetched.content, offset, withLineNumbers, func(next int) string {
		return fmt.Sprintf("skillmaster skill read %s %s --offset %d", address, relpath, next)
	})
	fmt.Println(which)
	return nil
}

// readFromAuthor reads one file of a draft or revoked version from the author plane.
func readFromAuthor(ctx context.Context, address, pin, relpath string, offset int, note string) error {
	client, err := session(ctx)
	if err != nil {
		return err
	}
	// **The manifest first, the bytes second — the consumption path's order.** The bytes alone do
	// not say whether they are text; the manifest does. Fetching the other way round made a failed
	// manifest call leave `binary` false, so a `.png` was printed as mojibake — the exact defect
	// this branch exists to avoid — and the request order contradicted this function's own comment.
	files, err := client.AuthorFiles(ctx, address, pin)
	if err != nil {
		var server *api.ServerError
		if errors.As(err, &server) && server.Status == http.StatusNotFound {
			return fmt.Errorf("%w\n（%s@%s 在作者面取不到；它要么不存在，要么这一档看不见这一版。）",
				err, address, pin)
		}
		return err
	}
	binary := false
	for _, file := range files {
		if file.Relpath == relpath {
			binary = file.IsBinary
			break
		}
	}
	content, err := client.AuthorFile(ctx, address, pin, relpath)
	if err != nil {
		var server *api.ServerError
		if errors.As(err, &server) && server.Status == http.StatusNotFound {
			return fmt.Errorf("%w\n（%s@%s 在作者面也取不到这个路径；它要么不存在，要么这一档看不见这一版。）",
				err, address, pin)
		}
		return err
	}
	if binary {
		which := note
		if which == "" {
			which = fmt.Sprintf("（按 %s@%s 取。）", address, pin)
		}
		return printBinary(content, relpath, offset, which)
	}
	printContent(content, offset, withLineNumbers, func(next int) string {
		return fmt.Sprintf("skillmaster skill read %s@%s %s --offset %d", address, pin, relpath, next)
	})
	which := note
	if which == "" {
		which = fmt.Sprintf("（按 %s@%s 取。）", address, pin)
	}
	fmt.Println(which)
	return nil
}

// fileMissing is what a `read` of a relpath this version does not have says.
//
// The next step is part of the message, and it is the reason the command exists: the lookup is an
// exact match against the manifest, so nothing here can repair a near miss, and without the pointer
// the only recovery is guessing names until one works. Extracted for the same reason `pinFailure` is
// — a decision worth testing on its own, with no server and no credential.
func fileMissing(relpath, address string) error {
	return fmt.Errorf("这个路径在这个 skill 里取不到：%s\n"+
		"（列目录需要「浏览」档：skillmaster skill files %s。）", relpath, address)
}

// pinFailure rewrites a 404 that happened while a read was pinned from this machine's records.
//
// The server's 404 says it does not distinguish "no such skill" from "not yours" from "no such
// version", and it means it — but here the CLI knows something the server does not: it pinned this
// request itself. So it can say which pin failed, which turns a message that reads like a puzzle into
// one with a next step. **It does not fall back to the current version**: a silent substitution is the
// failure this whole design is arranged around, and it would hand back a confident answer about the
// wrong version.
func pinFailure(err error, pin, key string) error {
	return fmt.Errorf("%w\n（这个版本是按本地记住的 @%s 取的，现在取不到——可能是这个版本没了，"+
		"也可能是这个 skill 没了或你看不到。重新 skillmaster skill invoke %s 再取。）", err, pin, key)
}

// findFile looks a relpath up in the manifest, by exact match.
//
// A lookup and not a path join, which is §4.2's rule and also this side's: the manifest is the list of
// files the version actually has, and anything resembling path assembly here would be a second,
// differently-wrong answer to a question the server has already answered.
func findFile(detail api.Detail, relpath string) (api.File, bool) {
	for _, file := range detail.Files {
		if file.Relpath == relpath {
			return file, true
		}
	}
	return api.File{}, false
}

// bodyRelpath is the one file every version has, and the one `invoke` has already delivered.
//
// Spelled here rather than derived, because there is nothing to derive it from: the standard makes
// `SKILL.md` a MUST at the root of a skill, and the server names the same string on its own side when
// it serves the body. The manifest cannot settle it either — `resources.body` is a URI of its own
// shape (`/<address>/body`), not the file route the manifest lists.
const bodyRelpath = "SKILL.md"

// files lists what a version contains.
//
// **The host has nothing to mirror here, and that is what this command exists for.** Locally a skill
// is a directory, so enumerating one is `ls`; there is no local copy here (ADR 0001), and the host's
// own skill load hands over only the body — verified against the installed CLI, which prefixes one
// line naming a directory and lists nothing. So the equivalent has to be a command of ours rather
// than something a caller can do for itself. Everything it prints the server had already sent: the
// detail endpoint answers with the whole manifest and no content, `invoke` receives it and keeps only
// the body.
//
// **Enumeration is a read, not a second door** (ADR 0036): the manifest comes from the same endpoint
// as the body and is served under the same authorization, so this reaches nothing `read` cannot — a
// caller who may not read the skill gets the same 404 from both.
//
// The pin is `read`'s, decided the same way, for the same reason: a manifest belongs to one version,
// and a listing that resolved differently from the bytes it is a listing of is the drift this design
// is arranged around.
func files(ctx context.Context, args []string) error {
	if len(args) != 1 {
		return usagef("%s", usageAt("skill files"))
	}
	address := args[0]
	if err := checkAddressSyntax("skill files", address); err != nil {
		return err
	}
	target, err := authenticated(ctx, func(client api.Client) (fileTarget, error) {
		return resolve(ctx, client, address, true)
	})
	if err != nil {
		return err
	}
	listed, err := authenticated(ctx, func(client api.Client) (api.AuthorFileList, error) {
		return client.AuthorFileList(ctx, target.address, target.pin)
	})
	if err != nil {
		return err
	}
	display := filesAddressWithPin(address, target.pin)
	which := target.note
	if which == "" {
		which = fmt.Sprintf("（按 %s 列的。）", display)
	}
	printFiles(listed.Files, display, which)
	return nil
}

// filesAddressWithPin keeps the footer's `read` command on the version that was just listed. A
// caller who wrote a pin already has one; a bare address gets the pin `resolve` chose.
func filesAddressWithPin(address, pin string) string {
	if pin == "" || strings.Contains(address, "@") {
		return address
	}
	return address + "@" + pin
}

// authorPlaneAnswers says whether a failed consumer read is the one the author plane might answer:
// a revoked version (410) or a pin the consumer lookup cannot resolve (404). Anything else is a
// different failure and retrying it on the other plane would only add a second, less useful error.
func authorPlaneAnswers(err error) bool {
	var server *api.ServerError
	if errors.As(err, &server) && server.Code == "version_revoked" {
		return true
	}
	return errors.Is(err, api.ErrNotFound)
}

// authorPlaneAnswer says whether the author plane gave a "not here to you" answer. That is the one
// worth reporting instead of the consumer plane's 410: for `browse`/`write` a revoked pin is
// deliberately invisible (404), not a version they can see was taken down.
func authorPlaneAnswer(err error) bool {
	var server *api.ServerError
	if errors.As(err, &server) && (server.Status == 404 || server.Status == 403) {
		return true
	}
	var denial *api.Denial
	return errors.As(err, &denial)
}

// printFiles writes a version's manifest, one relpath per line, then says which version it listed.
//
// **A relpath alone on its line, and that is the whole shape.** That line is what the caller pastes
// into `read`, so nothing is appended to it — the same rule the skills listing follows. It is also why
// neither the file's `uri` nor its digest appears: those are 79 and 71 characters of arbitrary text,
// and `read` needs neither of them (that is what the pin is for).
//
// The two annotations are the two things a caller has to know before reading anything: which entry is
// the body, and which entries will not come back as text. A plain file gets no note — there is
// nothing to say about it, and the footer says how to fetch one.
//
// **The body's note names the command rather than claiming the caller already has it.** It did say
// "invoke 已给" once, and that is false on the one path where it matters: `files` works without a pin
// — it says so in its own last line — so on a machine that has never invoked this skill the note
// contradicted the line right below it, and a caller reading it literally would conclude the body was
// already in hand and skip the step this whole protocol calls a must.
//
// @param address the address exactly as the caller wrote it, so the footer's command carries the same pin
// @param which   the line naming the version this came from — never empty (ADR 0035)
func printFiles(files []api.File, address, which string) {
	for _, file := range files {
		fmt.Println(file.Relpath)
		switch {
		case file.Relpath == bodyRelpath:
			fmt.Println("    （正文，由 skillmaster skill invoke 加载）")
		case file.IsBinary:
			fmt.Println("    （二进制，read 只给路径不打字节）")
		}
	}
	fmt.Printf("（共 %d 个文件。取其中一个：skillmaster skill read %s <相对路径>。）\n",
		len(files), address)
	fmt.Println(which)
}

// printBinary leaves a file this command cannot show where it can be seen, and says where.
//
// This is not a fallback for something that failed. The host's `Read` renders images, so a path is the
// only route by which a caller can actually look at a binary — which is why the answer is a location
// and not a refusal. The bytes never go to stdout: a stream of them ruins whatever context it lands
// in, and nothing downstream could do anything with it.
//
// @param offset the line the caller asked to start at — meaningless here, and said so instead of dropped
// @param which the line naming the version this came from, the same one the text path ends with —
//
//	omitted here once, and the omission was a hole in the one thing this design claims:
//	that the local pin is never invisible
func printBinary(content []byte, relpath string, offset int, which string) error {
	dir, err := os.MkdirTemp("", "skillmaster-")
	if err != nil {
		return fmt.Errorf("创建临时目录：%w", err)
	}
	// On its own line, like every other value this CLI hands over for a later command to take: a
	// caller that has to find the path inside a sentence is a caller that will get it wrong.
	target := filepath.Join(dir, filepath.Base(relpath))
	if err := os.WriteFile(target, content, 0o600); err != nil {
		return fmt.Errorf("写入 %s：%w", target, err)
	}
	besideThePoint := ""
	if offset != 1 {
		besideThePoint = fmt.Sprintf("--offset %d 在这里没有意义。", offset)
	}
	fmt.Println(target)
	fmt.Printf("（%s 是二进制文件（%d 字节），内容没有打印——上面那一行是它在磁盘上的位置。%s）\n",
		relpath, len(content), besideThePoint)
	fmt.Println(which)
	return nil
}

// versions lists the versions of a skill that this caller may invoke, newest first.
//
// **The only command that prints a pin, and it exists for exactly that.** A bare address means
// "whatever is current", so without this a caller could never learn the name of a superseded version
// — and a superseded version is what somebody wants after a bad publish, because it is still published
// and still addressable. Everything else in this CLI works from names alone.
//
// The address carries the pin because choosing a version is the one thing that cannot be said without
// naming one. For the same reason the copy instruction is "whole": the line is what a caller pastes
// into `invoke`, and a line trimmed at the `@` would resolve to a different version.
func versions(ctx context.Context, args []string) error {
	if len(args) != 1 {
		return usagef("%s", usageAt("skill versions"))
	}
	address := args[0]
	if err := checkAddressSyntax("skill versions", address); err != nil {
		return err
	}
	if strings.Contains(address, "@") {
		return usagef("版本列表是针对整个 skill 的，地址后面不要带 @版本。%s", usageAt("skill versions"))
	}

	listed, err := authenticated(ctx, func(client api.Client) ([]api.VersionInfo, error) {
		return client.ReadVersions(ctx, address)
	})
	if err != nil {
		return err
	}

	// The same shape a listing uses: the value to copy alone on its line, what is known about it
	// indented underneath.
	for _, version := range listed {
		fmt.Println(address + "@" + api.VersionSuffix(version.Name, version.Digest))
		if line := versionLine(version); line != "" {
			fmt.Printf("    %s\n", line)
		}
		// v6's note is the caller's answer to "what changed"; it is version metadata on the
		// consumer plane, and the same two-space shape as a listing's reading.
		if version.Note != nil && *version.Note != "" {
			fmt.Printf("    %s\n", *version.Note)
		}
	}
	fmt.Printf("（共 %d 版。要钉住其中某一版：把上面某一行的地址整段抄进 skillmaster skill invoke。）\n",
		len(listed))
	return nil
}

// showSkill reads one skill's complete information: two reads composed into one card.
//
// **`metadata` and `tags` are two endpoints because they answer two questions**, and this command is
// the caller that wants both at once (TD §CLI 映射). The composition is here rather than a third
// server route because neither half changes shape for it — and because the two are the same
// visibility predicate, so a caller allowed to read one is allowed to read the other.
//
// **The address carries no version**, and a pin is refused rather than dropped: the fields below are
// facts about the skill, and the current version is one of them.
func showSkill(ctx context.Context, args []string) error {
	address := ""
	noLogin := false
	for _, arg := range args {
		switch arg {
		case "--no-login":
			noLogin = true
		default:
			if strings.HasPrefix(arg, "-") {
				return unknownFlag("skill show", arg)
			}
			if address != "" {
				return usagef("%s", usageAt("skill show"))
			}
			address = arg
		}
	}
	if address == "" {
		return usagef("%s", usageAt("skill show"))
	}
	if err := checkBareAddress(address); err != nil {
		return err
	}

	var meta api.Metadata
	var tags api.SkillTags
	_, err := authenticatedWith(ctx, noLogin, func(client api.Client) (struct{}, error) {
		var err error
		meta, err = client.Metadata(ctx, address, "")
		if err != nil {
			return struct{}{}, err
		}
		tags, err = client.Tags(ctx, address)
		if err != nil {
			return struct{}{}, err
		}
		return struct{}{}, nil
	})
	if err != nil {
		return v14Error(err)
	}
	printSkillShow(address, meta, tags)
	return nil
}

// printSkillShow writes the card, in the order the PRD fixes: address, description, when to use,
// compatibility, version, category, public tags, my tags. **An empty field is not printed** — a blank
// label says nothing and makes the ones that do carry a value harder to find.
func printSkillShow(address string, meta api.Metadata, tags api.SkillTags) {
	fmt.Println(address)
	if meta.Description != "" {
		fmt.Printf("  %s\n", meta.Description)
	}
	if meta.WhenToUse != "" {
		fmt.Printf("  适用场景：%s\n", meta.WhenToUse)
	}
	if meta.Compatibility != "" {
		fmt.Printf("  运行环境：%s\n", meta.Compatibility)
	}
	if version := api.VersionSuffix(meta.Version.Name, meta.Version.Digest); version != "" {
		fmt.Printf("  版本：%s\n", version)
	}
	if tags.Category != nil && tags.Category.Name != "" {
		fmt.Printf("  分类：%s\n", tags.Category.Name)
	}
	if names := tagNames(tags.PublicTags); len(names) > 0 {
		fmt.Printf("  公开标签：%s\n", strings.Join(names, "、"))
	}
	if !tags.MyTags.Readable {
		// A reason line rather than silence: the group exists, and a reader who expected it needs to
		// know why it is not there (PRD #53).
		fmt.Printf("  我的标签：%s\n", myTagsUnreadable(tags.MyTags.Reason))
	} else if names := myTagNames(tags.MyTags.Tags); len(names) > 0 {
		fmt.Printf("  我的标签：%s\n", strings.Join(names, "、"))
	}
}

// versionLine is what sits under a version's address: whether a bare address would resolve to it, and
// when it first went live.
func versionLine(version api.VersionInfo) string {
	when := version.PublishedAt
	if len(when) >= 10 {
		// RFC3339 as the server writes it, and the date alone. Somebody choosing between versions is
		// answering "which one", and the clock time has never decided that.
		when = when[:10]
	}
	switch {
	case version.IsCurrent && when != "":
		return "当前 · " + when
	case version.IsCurrent:
		return "当前"
	default:
		return when
	}
}

// bareAddress is an address with its version suffix removed — the key a pin is remembered under.
//
// The suffix comes off because a pin is *about* the version: remembering `alice/pdf-tools@1.2.3 →
// 1.2.3` would work right up until the same skill is invoked at a different version, which is the one
// thing the key has to survive. What is left is what a person would have typed anyway.
func bareAddress(address string) string {
	held, _, _ := strings.Cut(address, "@")
	return held
}

// pinsFor opens this machine's pin file for one server.
func pinsFor(server string) (*pins.Store, error) {
	path, err := config.PinPath(server)
	if err != nil {
		return nil, fmt.Errorf("找不到用户配置目录：%w", err)
	}
	return pins.Open(path), nil
}

// pinsUnreadable rewrites a failure to read or write this machine's pin file.
//
// **What makes it worth its own message is what the file holds**: version names and digests, nothing
// else, so deleting it costs nothing a later `invoke` cannot rebuild — and saying so is the difference
// between a command that fails with a next step and one that fails forever with a path in it.
//
// **What must not happen is the other repair.** Treating an unreadable file as "no pin" would answer
// about whatever is current while the caller believes they are reading the version they invoked,
// which is the silent substitution this whole design is arranged around (ADR 0035 §后果).
func pinsUnreadable(err error) error {
	var corrupt *pins.CorruptError
	if !errors.As(err, &corrupt) {
		// Every other way this store fails is I/O — a directory that belongs to somebody else, a full
		// disk — and there the file is fine. "Delete it" would send the operator to repair the wrong
		// thing, so this one goes through as it came, with its own path in it.
		return err
	}
	return fmt.Errorf("%w\n（这个文件里只有版本名与摘要，删掉它是安全的——下一次 invoke 会重建它。"+
		"在它读得出来之前，read 与 files 不会替你猜一个版本。）", err)
}

// rememberPin writes a resolved version down, returning whatever was there before.
func rememberPin(address string, pin pins.Pin) (pins.Pin, bool, error) {
	store, err := pinsFor(config.Server())
	if err != nil {
		return pins.Pin{}, false, err
	}
	previous, had, err := store.Remember(address, pin)
	if err != nil {
		return pins.Pin{}, false, pinsUnreadable(err)
	}
	return previous, had, nil
}

// rememberedPin is the version this address was last invoked at, if any.
func rememberedPin(address string) (pins.Pin, bool, error) {
	store, err := pinsFor(config.Server())
	if err != nil {
		return pins.Pin{}, false, err
	}
	pin, found, err := store.Lookup(address)
	if err != nil {
		return pins.Pin{}, false, pinsUnreadable(err)
	}
	return pin, found, nil
}

// ---------------------------------------------------------------------------
// Writing skills
// ---------------------------------------------------------------------------

// submitSkill uploads one skill of this machine's, and hands the person the page that can approve it.
//
// The two halves of ADR 0031 meet here. This command makes nothing live — the version lands as a
// draft and the consumption plane does not change — so what it does at the end is open the browser at
// the address where that draft can be looked at and published. Without that step the command would be
// a success that left the work invisible, which is what it was before the split.
//
// **That last step happens only when there is something there to approve.** A submission of content
// the server already has answers with whichever version holds it, and that version may be live or
// discarded — in neither case is there an approval to make, so the address is printed and no tab is
// opened. See stateLine and the branch below it.
//
// **`--to` names somebody else's skill, and it is the `editor` grant's one write path** (ADR 0034).
// It routes to the endpoint that adds a version to an existing skill rather than to the one that
// creates a skill, and what keeps naming a target from being a cross-namespace write primitive is
// the server's rule: that route can only add to a skill the caller has been given write access to.
// The one thing it does not change is publishing — an editor can draft and cannot publish, so the
// draft line below says who can.
func submitSkill(ctx context.Context, args []string) error {
	dirArgs, target, name, note, err := splitSubmitArgs(args)
	if err != nil {
		return err
	}
	dir, err := resolveSkill(dirArgs)
	if err != nil {
		return err
	}

	// Archived first, so a missing SKILL.md or a symlink is a message rather than an upload that
	// fails at the far end. The server checks all of it too and is the authority (ADR 0011); this
	// only saves the round trip. Also what makes the retry below cheap — the archive is built once,
	// not once per attempt.
	archive, err := api.Archive(dir)
	if err != nil {
		return err
	}

	// **`version:` is still legal content and no longer a release name.** v6 assigns the number at
	// publish time, so the field is carried through untouched; saying so once keeps an author from
	// believing the file decides the address. It never blocks the submission.
	if content, readErr := os.ReadFile(filepath.Join(dir, "SKILL.md")); readErr == nil {
		if declared, ok := frontmatterScalar(content, "version"); ok {
			fmt.Printf("（SKILL.md 里的 version: %s 不参与发布命名；发布时在网页上再定版本号。）\n", declared)
		}
	}

	// Retrying a submission is safe, and for a reason worth naming: a rejected token is refused at
	// the security filter, so the first attempt wrote nothing, and submitting the same bytes twice is
	// idempotent anyway (ADR 0005) — it produces no second version, draft or otherwise.
	result, err := authenticated(ctx, func(client api.Client) (api.SubmitResult, error) {
		if target == "" {
			return client.Submit(ctx, archive, name, note)
		}
		return client.SubmitVersion(ctx, target, archive, name, note)
	})
	if err != nil {
		// The v6 field refusals (`draft_name_required` and friends) arrive as codes; the one map in
		// `versionMessages` is what turns them into the interface's sentences.
		return versionErr(false, err, "")
	}

	fmt.Printf("%s %s/%s@%s　%s\n", submitVerb(result), result.Namespace, result.Name,
		submitPin(result), result.Version.Digest)
	fmt.Printf("  %d 个文件　%d 字节\n", result.Version.FileCount, result.Version.TotalBytes)
	if result.Version.DraftName != nil && *result.Version.DraftName != "" {
		fmt.Printf("  名称：%s\n", *result.Version.DraftName)
	}
	if result.Version.Note != nil && *result.Version.Note != "" {
		fmt.Printf("  说明：%s\n", *result.Version.Note)
	}

	// Said before the link, and said plainly, because this is the fact the whole split turns on: a
	// successful `submit` has changed nothing anybody can read yet — when what it left was a draft.
	state := result.Version.State
	fmt.Println(stateLine(state))
	// Said only on the `--to` path, and only about the draft case, because that is where the general
	// sentence above can be read as an instruction the caller cannot carry out: an `editor` grant
	// lets them draft and explicitly does not let them publish (ADR 0034). Worded as "the skill's
	// owner" rather than as "not you", because `--to` naming your own skill is legal and a sentence
	// that called you somebody else would be wrong there.
	if target != "" && state == api.StateDraft {
		fmt.Println("（这个 skill 的上线只能由它的主人在网页上点。）")
	}

	page := skillPageURL(result)
	// The label and the browser follow the same branch the line above took. Calling it an approval
	// address when there is nothing to approve contradicts the sentence printed directly above it,
	// and opening a tab for a submission that changed nothing is a side effect nobody asked for.
	if state != api.StateDraft {
		fmt.Printf("这一版在网页上（不含凭据）：\n%s\n", page)
		return nil
	}
	fmt.Printf("审批地址（不含凭据，在浏览器里用你自己的账号登录）：\n%s\n", page)
	// A failure here is a warning rather than an error: the submission has happened, and reporting it
	// as failed would be a lie about the state the person is in. Same rule as `login`'s.
	if err := auth.OpenBrowser(page); err != nil {
		fmt.Fprintf(os.Stderr, "没能自动打开浏览器（地址在上面，也可以自己打开）：%v\n", err)
	}
	return nil
}

// submitVerb says what actually happened, which is three outcomes and not two.
//
// **`created` is not "the skill was created".** The server sets it when *this call created a
// version*, so it is true both for a brand-new skill and for a new version of an old one — reading
// it as the former prints "已创建" at somebody who just updated a skill that has existed for months.
// `skill_created` is what separates the two, and it is exactly the fact the version number used to
// carry: it is true only for the call that gave the skill its first version of any state (ADR 0033).
//
// The third outcome is a replay of identical content (ADR 0005's idempotence): nothing was written,
// and the version printed is the one that was already there. Any verb with 更新 in it would claim a
// change that did not happen, so this one says only that the content did not move — and what the
// version's state actually is comes from stateLine, printed on the line below.
func submitVerb(result api.SubmitResult) string {
	switch {
	case !result.Created:
		return "内容未变"
	case result.SkillCreated:
		return "已创建"
	default:
		return "已提交"
	}
}

// submitPin is the address suffix a submission's result should be printed with.
//
// **A draft has no version number in v6**, so the digest is not the right suffix for it any more:
// the stable author-plane identity is `draft:<id>`, and that is what a later `version show` or
// `version diff` takes. A replay of already-published content answers with no draft id and keeps the
// number-or-digest spelling the consumer plane uses.
func submitPin(result api.SubmitResult) string {
	// v7: a draft's address is its bare row id (ADR 0063). `draft:` is accepted on input for
	// compatibility but is never written back — the canonical spelling has no status in it.
	if result.Version.DraftID != nil && *result.Version.DraftID != "" {
		return *result.Version.DraftID
	}
	return api.VersionSuffix(result.Version.Name, result.Version.Digest)
}

// stateLine says what the version this call names actually is, which is not always a fresh draft.
//
// **`created` cannot answer this.** Submitting content the server already holds answers 200 with the
// row that holds that digest, and the unique constraint is on the content rather than on the state —
// so that row may be a draft, may already be published, or may have been discarded. Telling somebody
// to go and publish a version that is already live sends them to a page with nothing to do, and the
// discarded case is worse: the server refuses it, so the instruction can never be carried out.
//
// The default is for a state from a newer server than this build. Saying nothing about publishing is
// the honest answer there; the address and digest above still name the version.
func stateLine(state string) string {
	switch state {
	case api.StateDraft:
		return "这一版还是草稿，线上没有任何变化。要生效得去网页上点「上线」。"
	case api.StatePublished:
		return "这一版已经在线上，没有需要审批的东西。"
	case api.StateRevoked:
		return "这一版已被下架，消费面不再提供它；作者面仍可由维护者读到。"
	case api.StateDiscarded:
		return "这一版曾被丢弃，不会被上线；提交相同内容也不会把它变回草稿。"
	default:
		return "这一版已提交。"
	}
}

// skillPageURL is where a person approves what was just submitted.
//
// Built from `WebURL` rather than from the API base: in a deployment they are one origin, and in
// development they are not — the pages are Vite's on 5173 and the API is on 8080 — so a link built
// from the server would open a 404 that reads as a failed submission.
//
// **Both segments are escaped, and nothing else is.** A skill's name and namespace may be non-ASCII
// (M5 permits either), and this string goes to a browser: a space or a `#` left raw would truncate
// the address. The `@version` suffix is deliberately not added — the page is the version list, and a
// person who wants a particular version is about to choose it there.
func skillPageURL(result api.SubmitResult) string {
	return fmt.Sprintf("%s/skills/%s/%s", config.WebURL(),
		url.PathEscape(result.Namespace), url.PathEscape(result.Name))
}

// splitSubmitArgs separates the flags from the argument that names what to upload.
//
// The flags come out first so that `resolveSkill` keeps its one job and its one argument. Its reading
// of that argument is deliberately by the argument's own characters and never by asking the
// filesystem, and a flag left in the list would be read as a skill named `--name`.
//
// **`--name` is optional since v9.** A v9 download writes the version's name into the package
// (ADR 0069), and the server uses it when the request does not give one; a package without it,
// submitted without the flag, is refused with `draft_name_required` — the server's answer, quoted
// rather than pre-empted, because only the server can read the package.
//
// The address is checked here rather than left to the call that uses it, so a malformed one is a
// message before an archive of the whole directory is built and uploaded. `api.SkillPath` is the
// check because it is the one spelling of the address grammar this program has.
// A version pin is refused for the same reason `share` refuses one: the version is what this call
// produces, so a target that named one would be naming something this call does not use.
func splitSubmitArgs(args []string) (dirArgs []string, target, name, note string, err error) {
	var named, noted bool

	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--to", "--name", "--note":
		default:
			// A token that starts like a flag is a flag somebody mistyped, not a directory: read as
			// one it would become `读取 --name` or, worse, upload whatever that path happens to be.
			if strings.HasPrefix(args[i], "-") && len(args[i]) > 1 {
				return nil, "", "", "", unknownFlag("skill submit", args[i])
			}
			dirArgs = append(dirArgs, args[i])
			continue
		}
		if i+1 >= len(args) {
			return nil, "", "", "", usagef("%s 后面要跟一个值。%s", args[i], usageAt("skill submit"))
		}
		value := args[i+1]
		switch args[i] {
		case "--to":
			if target != "" {
				return nil, "", "", "", usagef("--to 只能给一次。%s", usageAt("skill submit"))
			}
			target = value
			if _, pathErr := api.SkillPath(target); pathErr != nil {
				return nil, "", "", "", usagef("%s", pathErr)
			}
			if strings.Contains(target, "@") {
				return nil, "", "", "", usagef("--to 指的是一个 skill，不是某一版——地址后面不要带 @版本。%s", usageAt("skill submit"))
			}
		case "--name":
			if named {
				return nil, "", "", "", usagef("--name 只能给一次。%s", usageAt("skill submit"))
			}
			named = true
			name = value
		case "--note":
			if noted {
				return nil, "", "", "", usagef("--note 只能给一次。%s", usageAt("skill submit"))
			}
			noted = true
			note = value
		}
		i++
	}

	return dirArgs, target, name, note, nil
}

// resolveSkill turns the command's arguments into a directory to upload.
//
// Three shapes, and the point of them is that none requires the person to know where their skills
// live: a **bare name** is looked up under the skills directory, anything with a separator or a
// leading `.` or `~` is a path taken as given, and **no argument at all lists what is there** rather
// than guessing at one. Guessing would be wrong the moment somebody has two.
//
// The classification is by the argument's own characters and never by asking the filesystem, which
// is what makes the three cases deterministic: `submit pdf-tools` means the same thing whether or not
// a directory called `pdf-tools` happens to be in the current one.
func resolveSkill(args []string) (string, error) {
	if len(args) > 1 {
		return "", usagef("%s", usageAt("skill submit"))
	}
	if len(args) == 0 {
		return "", noArgumentGiven()
	}

	arg := args[0]
	// `submit ""` is what an unset shell variable looks like, and it is not a name: it would take the
	// bare-name branch, `filepath.Join(dir, "")` is `dir`, and the whole skills directory would go off
	// as one skill's archive. Refused here rather than left to the server's validator, which would
	// answer about a skill's *contents* after the upload had already happened.
	if arg == "" {
		return "", usagef("名字不能是空的。%s", usageAt("skill submit"))
	}
	if isPathLike(arg) {
		return expandHome(arg)
	}

	dir, err := config.SkillsDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, arg), nil
}

// noArgumentGiven says what could have been named instead, which is the whole value of the empty
// form: a person who does not remember the skill's directory name is who it is for.
func noArgumentGiven() error {
	dir, err := config.SkillsDir()
	if err != nil {
		return err
	}
	names, err := skillNamesIn(dir)
	if err != nil {
		return usagef("读 %s 失败：%v。%s", dir, err, usageAt("skill submit"))
	}
	if len(names) == 0 {
		return usagef("%s 里没有找到 skill。%s", dir, usageAt("skill submit"))
	}
	return usagef("%s 里有：%s。%s", dir, strings.Join(names, "、"), usageAt("skill submit"))
}

// skillNamesIn lists the directories under dir that hold a SKILL.md — which is what makes something
// a skill, and the same check the server's validator starts with.
//
// **A regular file, not merely something that resolves to one.** `os.Stat` follows a symlink, and
// `Archive` refuses one — so a directory whose `SKILL.md` is a link would be offered here and then
// fail on submit with 「是符号链接」, which reads as a broken skill rather than as a name this
// command should not have suggested.
//
// A directory that cannot be read is not an empty one, so it is reported: on most machines the
// skills directory is absent until something installs one, and "there are no skills" would send
// somebody looking for a mistake they did not make.
func skillNamesIn(dir string) ([]string, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	var names []string
	for _, entry := range entries {
		// `IsDir` is Lstat's answer, so a directory reached through a link is not listed — which is
		// now the same answer `Archive` gives, deliberately: offering it would name something that
		// cannot be submitted by that name.
		if !entry.IsDir() {
			continue
		}
		info, err := os.Lstat(filepath.Join(dir, entry.Name(), "SKILL.md"))
		if err != nil || !info.Mode().IsRegular() {
			continue
		}
		names = append(names, entry.Name())
	}
	// `os.ReadDir` already answers in filename order, so this is redundant against that promise —
	// kept because the order is what a person reads, and a caller that swapped the read for one that
	// does not sort should not be able to change the output.
	sort.Strings(names)
	return names, nil
}

// isPathLike reports whether the argument names a location rather than a skill.
//
// A separator anywhere, or a leading `.` or `~`. Nothing else — and the readings are still
// unambiguous, because the classification reads only the argument's own characters.
//
// **The cost is one narrow case, and it is worth stating rather than denying.** The server accepts a
// name that starts with `.` or `~` (it refuses whitespace, separators, `.`, `..`, `@`, `%` and `;` —
// nothing else), so a skill really named `.hidden` cannot be submitted by that bare word: the word
// reads as a path and is resolved against the current directory. It has to be given as a path
// (`submit ~/.claude/skills/.hidden`). The alternative — asking the filesystem which reading was
// meant — would make the same command mean different things on different machines.
func isPathLike(arg string) bool {
	return strings.ContainsRune(arg, '/') ||
		strings.ContainsRune(arg, filepath.Separator) ||
		strings.HasPrefix(arg, ".") ||
		strings.HasPrefix(arg, "~")
}

// expandHome resolves the leading `~`, which a shell would normally do before this sees it — but
// only when the argument arrives unquoted and unexpanded, and `submit ~/my-skills/pdf-tools` is
// exactly the shape somebody types.
//
// **Only a bare `~` or `~/…`.** `~bob/x` is a shell's own expansion and this program is not a shell;
// stripping the tilde from it would resolve to `$HOME/bob/x`, a path nobody named. Left alone it is
// a path that does not exist, which fails where it is used and says so.
func expandHome(path string) (string, error) {
	if path != "~" && !strings.HasPrefix(path, "~/") {
		return path, nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("找不到主目录（%s 要用它展开）：%w", path, err)
	}
	rest := strings.TrimPrefix(path, "~")
	rest = strings.TrimPrefix(rest, "/")
	return filepath.Join(home, rest), nil
}

// ---------------------------------------------------------------------------
// The gateway skill
// ---------------------------------------------------------------------------

// setup installs the gateway skill, which is what tells an agent that this service exists.
//
// **Only one agent's location is known here**, and that is stated rather than papered over: the
// design's "detect the local agent" wants a table of them, and there is one entry in it. A machine
// with a different agent gets a clear message and an environment variable to point at the right
// directory, which is honest; a guess at another agent's layout would not be.
func setup(ctx context.Context, args []string) error {
	dir, err := skillsDir(args)
	if err != nil {
		return err
	}

	// Anonymous on purpose: the discovery channel is how a machine that has never authenticated
	// learns where the API is (§1.5), so requiring a token would make it unreachable by exactly the
	// clients it exists for.
	//
	// Bounded, like every other call this CLI makes. This was the one exception — a bare `http.Get`,
	// which takes no context and has no deadline — so an endpoint that accepted the connection and
	// then said nothing hung `setup` with nothing on screen and no way out but Ctrl-C.
	gatewayURL := config.Server() + "/gateway/SKILL.md"
	fetchCtx, cancel := context.WithTimeout(ctx, gatewayTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(fetchCtx, http.MethodGet, gatewayURL, nil)
	if err != nil {
		return fmt.Errorf("构造取网关 skill 的请求：%w", err)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("取网关 skill：%w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("取网关 skill 时服务端答 %d", resp.StatusCode)
	}
	content, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("读取网关 skill：%w", err)
	}

	// **A 200 is not enough, and this is not hypothetical.** Anything in front of the server that
	// falls back to an SPA index for unknown paths answers 200 with a web page — the dev proxy did
	// exactly that on 2026-10-05, and the first version of this command wrote the page into
	// `SKILL.md` and reported success. An agent loading that file as a skill is a worse outcome than
	// a failed install, and the cause (a missing proxy route, a reverse proxy's `try_files`) is
	// invisible from here unless the message says what arrived.
	if !isSkillMarkdown(content) {
		return fmt.Errorf("%s 返回的不是 SKILL.md（%d 字节，%s）——"+
			"多半是服务端前面那层把 /gateway/SKILL.md 当成了未知路径，"+
			"用 SPA 回退或首页把它吃掉了；检查反代的 /gateway 路由",
			gatewayURL, len(content), firstLine(content))
	}

	// **The directory is the skill's own `name`, not a name this command invents.** §1.1's MUST is
	// that they are equal, and the repository obeys it everywhere it controls a layout — which is
	// why the source lives at `gateway/skillmaster/`. Installing as `skillmaster-gateway`, as this
	// did, is the one place the rule could be broken, and breaking it is silent in the worst way:
	// an agent that checks the rule ignores the skill, so the service is never discovered while
	// `setup` prints 「已安装」 and exits 0. Taking the name from the content makes the invariant
	// hold by construction rather than by a constant agreeing with a file in another repository.
	name, ok := frontmatterScalar(content, "name")
	if !ok || !isSkillName(name) {
		return fmt.Errorf("%s 的 frontmatter 里没有可用的 name（%q）——"+
			"装到哪里由这个字段决定（§1.1 要求目录名等于 name），猜一个名字会让 agent 忽略它",
			gatewayURL, name)
	}

	target := filepath.Join(dir, name)
	path := filepath.Join(target, "SKILL.md")
	// Said before the overwrite, because it is the difference §5.2 item 3 asks to be visible: the
	// gateway body is the protocol, so a copy that is about to change version is worth a line.
	if previous, err := os.ReadFile(path); err == nil {
		if was, _ := frontmatterScalar(previous, "platform_api_version"); was != "" {
			if now, _ := frontmatterScalar(content, "platform_api_version"); now != was {
				fmt.Printf("本地网关 skill 的 platform_api_version 是 %s，这次装的是 %s。\n", was, now)
			}
		}
	}
	if err := os.MkdirAll(target, 0o755); err != nil {
		return fmt.Errorf("创建 %s：%w", target, err)
	}
	if err := os.WriteFile(path, content, 0o644); err != nil {
		return fmt.Errorf("写入 %s：%w", path, err)
	}
	fmt.Printf("已安装网关 skill：%s\n", path)
	return nil
}

// gatewayTimeout bounds the one anonymous fetch this CLI makes.
const gatewayTimeout = 30 * time.Second

// frontmatterScalar is the value of a key in the frontmatter block, for the two keys this file
// reads out of one.
//
// Deliberately not a YAML parser: the block's shape is already pinned by isSkillMarkdown, both keys
// are plain scalars on their own line, and the CLI has no other use for YAML. A real parser would be
// a dependency, and this is a dozen lines — but it is also why it refuses anything it is not sure
// about rather than guessing: an absent or empty value has to read as "not there".
//
// **Indentation is allowed through, because one of the two keys is nested.** `name` is top level and
// `platform_api_version` sits under `metadata:`, so anchoring at column 0 would silently never find
// the second one — a failure the caller cannot see, since a missing version just means no notice is
// printed. First match wins, and the two keys do not collide in a real frontmatter.
func frontmatterScalar(content []byte, key string) (string, bool) {
	text := strings.TrimPrefix(string(content), "\ufeff")
	if !strings.HasPrefix(text, "---") {
		return "", false
	}
	rest := text[3:]
	end := strings.Index(rest, "\n---")
	if end < 0 {
		return "", false
	}
	for _, line := range strings.Split(rest[:end], "\n") {
		trimmed := strings.TrimSpace(line)
		if !strings.HasPrefix(trimmed, key+":") {
			continue
		}
		value := strings.TrimSpace(strings.TrimPrefix(trimmed, key+":"))
		if value == "" {
			return "", false
		}
		return strings.Trim(value, `"'`), true
	}
	return "", false
}

// isSkillName is §1.1's rule for the characters a `name` may contain, and it is load-bearing here
// rather than pedantic: the value decides a path, so anything with a separator or a `..` in it must
// not get that far. The content came over the network from a server this CLI does not control.
func isSkillName(name string) bool {
	if name == "" || name == "." || name == ".." || len(name) > 64 {
		return false
	}
	if strings.HasPrefix(name, "-") || strings.HasSuffix(name, "-") || strings.Contains(name, "--") {
		return false
	}
	for _, r := range name {
		if (r < 'a' || r > 'z') && (r < '0' || r > '9') && r != '-' {
			return false
		}
	}
	return true
}

// isSkillMarkdown reports whether what came back looks like a SKILL.md rather than a web page.
//
// Deliberately shallow — it is a courtesy that turns a silent wrong install into a message, not a
// validator (the server's parser is the authority, ADR 0011). The shape it checks is the one §1.1
// requires of every skill: a YAML frontmatter block first, with a `name` in it. A page of HTML has
// neither, which is the case this exists for.
func isSkillMarkdown(content []byte) bool {
	text := strings.TrimPrefix(string(content), "\ufeff")
	if !strings.HasPrefix(text, "---") {
		return false
	}
	// The block ends at the next line that is exactly `---`.
	rest := text[3:]
	end := strings.Index(rest, "\n---")
	if end < 0 {
		return false
	}
	return strings.Contains(rest[:end], "name:")
}

// firstLine is what to show a person about content that was not a skill: the first line is enough to
// recognise it (`<!doctype html>` says everything) and printing more would bury that.
func firstLine(content []byte) string {
	line := string(content)
	if newline := strings.IndexByte(line, '\n'); newline >= 0 {
		line = line[:newline]
	}
	if len(line) > 80 {
		line = line[:80] + "…"
	}
	return strings.TrimSpace(line)
}

// skillsDir is where the gateway goes, and what `setup` will do with no argument.
//
// `--dir` is this command's own flag and overrides everything; without it the answer is
// `config.SkillsDir`, which is the environment variable or Claude Code's location. That is the same
// value `submit` resolves a bare skill name against, and it is one function rather than two so that
// installing a skill and submitting one cannot disagree about where they are.
func skillsDir(args []string) (string, error) {
	if len(args) > 0 {
		if args[0] != "--dir" || len(args) != 2 {
			return "", usagef("%s", usageAt("setup"))
		}
		return args[1], nil
	}
	return config.SkillsDir()
}

// ---------------------------------------------------------------------------

// openStore picks where the credential lives and reports the fallback, if any.
//
// The path is only computed when it will be used: a keychain store never touches the file, and
// creating the directory for a store that does not need it would leave a config directory behind on
// a machine that never falls back.
// openStore picks both the place a credential lives and what the next command will present.
//
// **The environment is wrapped around the store here, once**, rather than checked in each command:
// `SKILLMASTER_API_KEY` wins over anything stored on this machine (ADR 0038 §决定 6), and a rule
// applied per command is a rule one command will not have. The concrete type is returned so that the
// commands which have to explain the difference — `auth status` and `logout` — can ask which of the
// two answered.
// machineStore is the seam that keeps the test suite off the machine it runs on.
//
// **On macOS this call is the real login keychain**, and a test that runs `login` or `auth status`
// rather than a shape function reaches it. Reading an entry there needs the item's ACL to trust the
// caller, and `go test` builds a fresh, unsigned binary on every run, so an approval cannot stick:
// the suite raised a keychain authorization dialog on the developer's screen once per run, and this
// machine's own credential sat in the path of tests that only wanted to know which flag reached
// which shape. Replaced once in TestMain rather than injected per test, because the failure mode of
// forgetting is a dialog on somebody's screen rather than a red test.
var machineStore = credentials.Open

// openStore picks where the credential lives and reports the fallback, if any.
func openStore(server string) (credentials.EnvStore, string) {
	path, err := config.CredentialPath(server)
	if err != nil {
		fmt.Fprintf(os.Stderr, "找不到用户配置目录（%v），将只使用钥匙串。\n", err)
		path = ""
	}
	fallback, notice := machineStore(path, server)
	return credentials.EnvStore{
		Var:      config.ApiKeyEnv,
		Value:    os.Getenv(config.ApiKeyEnv),
		Fallback: fallback,
	}, notice
}

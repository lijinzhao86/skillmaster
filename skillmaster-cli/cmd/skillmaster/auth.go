// Credential lifecycle: login, logout, auth status, and the browser hand-off.

package main

import (
	"context"
	"errors"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/apikey"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/auth"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/sandbox"
	"io"
	"os"
	"strings"
	"time"
)

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
	keyring, err := parseLoginArgs(args)
	if err != nil {
		return err
	}
	server := config.Server()
	var store credentials.EnvStore
	var notice string
	if keyring {
		store, notice = openStoreKeyring(server)
	} else {
		store, notice = openStore(server)
		// **The plaintext-file notice is printed by `saveAndReport`, after the write.** Computing
		// it here would call `Where()` before the write path exists, and in a sandbox the file
		// store would then name the ordinary directory's older copy — a different file from the
		// one the login just wrote. A machine with no keychain already has a sentence of its own.
	}

	previous, hadPrevious := previousCredential(store)

	// `--help` never reaches here: the process answers it before any handler runs (v16 做 #1), which
	// is also why `login --help` no longer opens a consent page.
	if err := loginWithBrowser(ctx, os.Stdout, server, store, notice); err != nil {
		return err
	}

	revokeReplaced(ctx, server, previous, hadPrevious)
	return nil
}

// parseLoginArgs reads the one flag login has (ADR 0185). **Anything else is a mistake rather than a
// login**: a positional argument is the quieter spelling of the same mistake — the commands that took
// a credential from an argument were removed a version ago — and it is refused without being repeated
// back (`namedArgument` says why).
func parseLoginArgs(args []string) (keyring bool, err error) {
	for _, arg := range args {
		if arg == "--keyring" {
			keyring = true
			continue
		}
		return false, unknownFlag("login", arg)
	}
	return keyring, nil
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
			"重新运行 skmr login 点一下就能换一份新的。", true
	case "token_idle":
		return "这份凭据闲置太久了——这台机器有一阵子没用它。重新运行 skmr login，" +
			"在同一台机器上点一下就换回来。", true
	case "token_expired":
		// The one refusal with two answers, and both are said: coming back is what most people do,
		// and **a key is named because this is the moment the difference between the two shapes
		// matters** — a login does not renew, so wanting one that does means wanting the other shape.
		return fmt.Sprintf("这份凭据已经到期（它从签发那一刻起就有上限）。重新运行 skmr login 点一下，"+
			"或者到控制台签一把不会到期的 API key：%s", console), true
	case "token_revoked":
		return "这份凭据已经被结束（在控制台的「登录设备」里退出的就是它）。" +
			"重新运行 skmr login 点一下就换一份新的。", true
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
	store, _ := openStore(server)
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
		return errors.New("本机没有凭据。跑 `skmr login` 拿一条登录链接，" +
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
		if where := whereToRevoke(stored); where != "" {
			// The key branch used to stop at the facts, so a key holder had no address for renewing
			// or revoking it (v22 A6).
			fmt.Printf("      %s\n", where)
		}
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

// loginWithBrowser is the whole of `login`: a link, a browser somewhere, and a person.
//
// **It works on a machine with no browser, and that is v68's point.** What this machine needs is a
// network connection: the link is printed, and it can be opened on the phone in the person's hand.
// So the command no longer refuses up front — the one case it still cannot serve is a machine with
// **no network at all**, and that failure names itself when it happens rather than being predicted
// here from a platform switch.
//
// **Ctrl-C is a supported way out.** The waiting process is the only one that knows the person gave
// up, so it tells the server (best effort) that this question is over; the ticket expiring on its
// own is the backstop. The command says what happened rather than printing a timeout.
//
// Inside a sandbox the browser is not even attempted: there is a launcher, and calling it produces a
// refusal that reads as a broken login. The link is the mechanism there.
//
// @param progress where the narration goes. `login` answers on stdout; a command that logged in for
//
//	itself writes to stderr, because its stdout is the `--json` object and nothing else.
func loginWithBrowser(ctx context.Context, progress io.Writer, server string, store credentials.EnvStore,
	notice string) error {
	fmt.Fprintf(progress, "正在登录 %s …\n", server)

	// **Only a machine that could actually open something is asked to.** On Linux that means a
	// display server, not merely an `xdg-open` on PATH; a container has the binary and nowhere to
	// draw. The link is the mechanism either way, and attempting here would print a failure at the
	// one moment the person is reading their instructions.
	var open func(string) error
	if !sandbox.Detect().Sandboxed && browserAvailable() {
		open = openBrowser
	}
	credential, err := auth.Login(ctx, auth.LoginOptions{
		Server:   server,
		ClientID: config.ClientID,
		OnURL: func(url string) {
			fmt.Fprintf(progress, "在任何浏览器里打开下面的链接完成批准（手机也行）：\n%s\n"+
				"批准后这条命令会自动继续。链接只能批准一次，不要转发给别人。\n", url)
		},
		OpenURL: open,
	})
	if err != nil {
		if errors.Is(err, context.Canceled) {
			return errors.New("已取消登录，没有拿到凭据。")
		}
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
		// **The keychain half-success is not a failed save.** The credential is stored; only the
		// file tombstone failed, so "保存凭据失败" and "改用 API key" would both point at the wrong
		// problem.
		var halfSaved *credentials.KeychainTombstoneError
		if errors.As(err, &halfSaved) {
			return fmt.Errorf("%w；让凭据文件可写（必要时先删掉它）后重试（help auth）", err)
		}
		// The store refused (a sandbox, a read-only home). Both ways out are named here because this
		// is the sentence a locked-down machine dies on, and there is no later page that says them
		// (v22 A3).
		return fmt.Errorf("保存凭据：%w；改用 %s，或给配置目录写权限后重试（help auth）",
			err, config.ApiKeyEnv)
	}
	if notice != "" {
		// On stderr, so a script reading stdout is not confused by it — and shown every time the
		// fallback is in use, because ADR 0025 asks for it to be announced rather than discovered.
		fmt.Fprintln(os.Stderr, notice)
	} else if where := store.Source(); isFile(where) {
		// **The paths that do not go through `login()` still have to say it.** A command that starts
		// a login for itself (`clientFor`) has no `login` notice to print, and ADR 0185 owes the
		// person the same sentence there: this is a plaintext file, and `--keyring` is the way back.
		// **Unless the keychain was the thing that just failed**: then recommending `--keyring`
		// would point at the flag the person already used.
		if fellBack, ok := store.Fallback.(interface {
			FellBackToFile() bool
			FallbackReason() string
		}); ok && fellBack.FellBackToFile() {
			fmt.Fprintln(os.Stderr, credentials.KeyringFallbackNotice(where, fellBack.FallbackReason()))
		} else {
			fmt.Fprintln(os.Stderr, credentials.FileDefaultNotice(where))
		}
	}
	fmt.Fprintf(progress, "已登录 %s，凭据存放于 %s\n", server, store.Where())
	// Whose login it is (v46). The browser screen says which account the credential will go to
	// before consent; this is the same fact arriving on the machine that now holds it. Omitted when
	// the server is older than the field — see `Credential.Username`.
	if credential.Username != "" {
		fmt.Fprintf(progress, "已登录为 %s\n", credential.Username)
	}
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

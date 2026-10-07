// Command skillmaster is the CLI: it holds the credential, loads skills on demand, and submits them
// for approval.
//
// §4.6's command set is here except for rollback, which nothing needs yet. Everything else — login,
// logout, setup, and the `skill` group's list, search, invoke, files, read, versions, submit and
// share — is in this file plus the packages under internal/.
//
// **`submit` is the whole of this CLI's writing, and it cannot publish** (ADR 0031). Publishing
// moves what every agent reading the API will get, and it is deliberately reachable only from the
// browser, by a person, with the page this command opens. The CLI's half of that handover is the
// deep link it prints and opens; it carries no credential of any kind, so a link copied out of a
// terminal is not a way in.
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
	"sort"
	"strings"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/auth"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/pins"
)

func main() {
	if err := run(context.Background(), os.Args[1:]); err != nil {
		fmt.Fprintf(os.Stderr, "错误：%v\n", err)
		os.Exit(1)
	}
}

func run(ctx context.Context, args []string) error {
	if len(args) == 0 {
		usage()
		return errors.New("没有给出子命令")
	}

	switch args[0] {
	case "login":
		return login(ctx, args[1:])
	case "logout":
		return logout(ctx)
	case "setup":
		return setup(ctx, args[1:])
	case "skill":
		return skillVerb(ctx, args[1:])
	case "help", "-h", "--help":
		usage()
		return nil
	default:
		usage()
		return fmt.Errorf("未知的子命令 %q", args[0])
	}
}

// skillUsage is the `skill` group's own listing, kept beside the group rather than in the global
// usage text: a person who typed a wrong verb under it wants that group's own commands, not the
// whole CLI.
const skillUsage = `用法：skillmaster skill <子命令>

  list [--all] [--namespace <命名空间>]...
                                          列出你读得到的 skill（含别人共享给你的）
  search <关键词>                          检索 skill
  invoke <命名空间>/<名字>[@版本]          加载正文，并把这一版钉在这台机器上
  files <命名空间>/<名字>[@版本]           列出这一版有哪些文件（相对路径）
  read <命名空间>/<名字>[@版本] <相对路径>    读它引用的一个文件（默认用 invoke 钉住的那一版）
  versions <命名空间>/<名字>               列出可以 invoke 的版本
  submit [名字 | 目录] [--to <命名空间>/<名字>]
                                          提交一版（落成草稿，不改线上），并打开审批页
  share <命名空间>/<名字> --to <用户名> [--role viewer|editor]
                                          把这一个 skill 共享给某个人，默认只读`

// skillVerb is the `skill` group: the commands that act on a skill.
//
// **A group rather than top-level verbs**, because this CLI is going to grow nouns that are not
// skills — a namespace, a version, a grant — and `list` on its own does not say what it lists. The
// shape is the one `claude mcp list`, `claude plugin eval` and `gh pr list` use, and it is what both
// of those do for a noun that has several verbs under it. Neither of them has a `noun-verb` command
// at all: a hyphen there appears *inside* a verb name (`claude setup-token`), never as a prefix.
//
// **`skill` is the only group so far, and `login`/`logout`/`setup` deliberately stay flat.** What
// decides is which noun a command acts on, and those three act on this machine — a credential, a
// keychain entry, the skills directory — rather than on a skill in the registry. They are also not
// ambiguous the way a bare `list` was: there is one thing to log in to.
//
// **The two read commands are the host's two, and the split is the same one.** `invoke` is `Skill`:
// load the body, and it takes no path. `read` is `Read`: one file of it, by path. What `invoke` adds
// is that it resolves the version and writes it down, which is what lets `read` be given a bare
// address — see `invoke` for why that pin exists and why it lives here.
//
// **`files` is the one read command with no counterpart in the host** (ADR 0036): there, a skill is a
// directory and enumerating one is `ls`, and its skill load hands over a base directory rather than a
// listing. Neither of those exists here, so the enumeration is a command — see `files`.
func skillVerb(ctx context.Context, args []string) error {
	if len(args) == 0 {
		return errors.New(skillUsage)
	}
	switch args[0] {
	case "list":
		return listSkills(ctx, args[1:])
	case "search":
		return search(ctx, args[1:])
	case "invoke":
		return invoke(ctx, args[1:])
	case "files":
		return files(ctx, args[1:])
	case "read":
		return read(ctx, args[1:])
	case "versions":
		return versions(ctx, args[1:])
	case "submit":
		return submitSkill(ctx, args[1:])
	case "share":
		return shareSkill(ctx, args[1:])
	case "help", "-h", "--help":
		fmt.Fprintln(os.Stderr, skillUsage)
		return nil
	default:
		return fmt.Errorf("未知的 skill 子命令 %q\n%s", args[0], skillUsage)
	}
}

// ---------------------------------------------------------------------------
// Credentials
// ---------------------------------------------------------------------------

// login has two shapes. The ordinary one opens a browser and needs a person; the other is for CI,
// where the machine is the deployment and there is nobody to consent — which is exactly why the
// credential it gets is bound to a user at registration instead (ADR 0022).
func login(ctx context.Context, args []string) error {
	server := config.Server()
	store, notice := openStore(server)

	previous, hadPrevious := previousCredential(store)

	if len(args) > 0 && args[0] == "--client-credentials" {
		if err := loginUnattended(ctx, server, store, notice); err != nil {
			return err
		}
	} else if err := loginWithBrowser(ctx, server, store, notice); err != nil {
		return err
	}

	revokeReplaced(ctx, server, previous, hadPrevious)
	return nil
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
func previousCredential(store credentials.Store) (credentials.Credentials, bool) {
	previous, hadPrevious, err := store.Load()
	if err != nil {
		fmt.Fprintf(os.Stderr,
			"读取现有凭据失败——这次登录照常进行，但上一次那张授权不会被撤销：%v\n", err)
		return credentials.Credentials{}, false
	}
	return previous, hadPrevious
}

// loginWithBrowser is the ordinary shape: a browser, PKCE, and a person.
func loginWithBrowser(ctx context.Context, server string, store credentials.Store, notice string) error {
	fmt.Printf("正在登录 %s …\n", server)
	tokens, err := auth.Login(ctx, auth.LoginOptions{
		Server:   server,
		ClientID: config.ClientID,
		Port:     config.CallbackPort,
		OnURL: func(url string) {
			fmt.Printf("如果浏览器没有自动打开，请访问：\n%s\n", url)
		},
		OpenURL: auth.OpenBrowser,
	})
	if err != nil {
		return err
	}
	return saveAndReport(store, notice, config.ClientID, tokens, server)
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
// here again and stays live until it idles out (30 days). Only one of them is visible to the person.
// Found by logging in twice while testing (2026-10-05).
//
// **Both shapes go through here, and the unattended one is not an exception.** `login
// --client-credentials` writes to the same store key, so it replaces a browser credential just as
// silently — and an unattended login has no reason to be the one shape that leaves a live
// authorization behind. The secret is what the revocation of an *unattended* credential needs; a
// browser credential is revoked with its own refresh token and ignores it.
//
// A failure here is a warning rather than an error, and that is not laxness: the login itself
// succeeded and the credential in hand works, so failing the command would be a lie about the state
// the person is actually in. Saying what did not happen is the honest version.
func revokeReplaced(ctx context.Context, server string, previous credentials.Credentials, hadPrevious bool) {
	if !hadPrevious {
		return
	}
	err := auth.RevokeAuthorization(ctx, auth.RevokeOptions{
		Server: server, Credential: previous,
		Secret: os.Getenv(config.ClientCredentialsSecretEnv),
	})
	if err != nil {
		fmt.Fprintf(os.Stderr,
			"上一次那张授权没能撤销（这次登录是好的，但它在服务端仍然有效）：%v\n", err)
		return
	}
	fmt.Println("上一次那张授权已撤销，这台机器上只剩这一次。")
}

func loginUnattended(ctx context.Context, server string, store credentials.Store, notice string) error {
	clientID := os.Getenv(config.ClientCredentialsIDEnv)
	secret := os.Getenv(config.ClientCredentialsSecretEnv)
	if clientID == "" || secret == "" {
		return fmt.Errorf("需要 %s 与 %s 两个环境变量",
			config.ClientCredentialsIDEnv, config.ClientCredentialsSecretEnv)
	}

	metadata, err := auth.Discover(ctx, http.DefaultClient, server)
	if err != nil {
		return err
	}
	tokens, err := auth.ClientCredentials(ctx, http.DefaultClient, metadata.TokenEndpoint,
		clientID, secret, auth.DefaultScopes)
	if err != nil {
		return err
	}
	if err := saveAndReport(store, notice, clientID, tokens, server); err != nil {
		return err
	}
	// Said plainly because it is the difference that matters for an unattended client: it has no
	// refresh token (the grant issues none), so when this token expires the client authenticates
	// again with its secret rather than renewing. Nothing renews in the background.
	fmt.Println("（无人值守：没有刷新令牌，过期后用同一份密钥重新登录。）")
	return nil
}

func saveAndReport(store credentials.Store, notice, clientID string, tokens auth.Tokens,
	server string) error {
	stored := credentials.Credentials{
		ClientID:     clientID,
		AccessToken:  tokens.AccessToken,
		RefreshToken: tokens.RefreshToken,
		ExpiresAt:    tokens.ExpiresAt,
	}
	if err := store.Save(stored); err != nil {
		return fmt.Errorf("保存凭据：%w", err)
	}
	if notice != "" {
		// On stderr, so a script reading stdout is not confused by it — and shown every time the
		// fallback is in use, because ADR 0025 asks for it to be announced rather than discovered.
		fmt.Fprintln(os.Stderr, notice)
	}
	fmt.Printf("已登录 %s，凭据存放于 %s\n", server, store.Where())
	return nil
}

func logout(ctx context.Context) error {
	server := config.Server()
	store, _ := openStore(server)

	// The secret is read here and handed over only if it turns out to be an unattended credential
	// that needs it — see auth.LogoutOptions.Secret. Taking it from the environment is the same
	// place `login --client-credentials` takes it, which is the only place it exists: it is
	// deliberately not stored beside the credential it minted.
	err := auth.Logout(ctx, auth.LogoutOptions{
		Server: server, Store: store,
		Secret: os.Getenv(config.ClientCredentialsSecretEnv),
	})
	if errors.Is(err, auth.ErrRevocationNeedsSecret) {
		return fmt.Errorf("无人值守登录的凭据要用客户端密钥才能撤销，"+
			"请像登录时那样设上 %s（本地凭据保留）", config.ClientCredentialsSecretEnv)
	}
	if err != nil {
		return err
	}
	fmt.Println("已撤销授权并清除本地凭据。")
	// Said plainly because it is the thing people get wrong: a logout here is not a browser logout.
	fmt.Println("（浏览器里的登录状态不受影响。）")
	return nil
}

// ---------------------------------------------------------------------------
// Reading skills
// ---------------------------------------------------------------------------

// session is the three lines every authenticated command needs: where the credential is, a token
// that is still good, and a client to use it with.
func session(ctx context.Context) (api.Client, error) {
	return clientFor(ctx, auth.AccessToken)
}

// clientFor runs one of the two ways of getting a token and builds a client from what comes back.
//
// The token half is a parameter rather than a flag because the two differ in more than eagerness:
// `auth.Renew` has to be told which token was refused, and only the caller of the failed request
// knows that. Returns only the token, since `credentials.Credentials` is not this layer's business.
func clientFor(ctx context.Context,
	token func(context.Context, auth.SessionOptions) (credentials.Credentials, error)) (api.Client, error) {
	server := config.Server()
	store, _ := openStore(server)
	dir, err := config.Dir()
	if err != nil {
		return api.Client{}, err
	}

	// That is where the lock and the server's grace window live, and it is why this is one call
	// rather than a "is it expired?" check here. The client id is not passed: a refresh goes out as
	// the client the stored credential was issued to, which the credential itself names.
	stored, err := token(ctx, auth.SessionOptions{
		Server: server, Dir: dir, Store: store,
		Warn: func(message string) { fmt.Fprintln(os.Stderr, message) },
	})
	if err != nil {
		return api.Client{}, err
	}
	return api.Client{BaseURL: server, Token: stored.AccessToken}, nil
}

// authenticated runs a command that needs a token, and gives a credential the server has refused one
// chance to be replaced.
//
// **This machine's idea of "still valid" is not the server's, and that gap used to be a dead end.**
// A token can be revoked while its stored expiry still says fifty minutes — a password reset on the
// account revokes every authorization it had — and every command afterwards reported the raw refusal
// with no way forward but to guess that logging out and in again would help. So the first 401 asks
// for a new token and tries once more.
//
// Once, and never a sign-in: `auth.Renew` either rotates the chain or reports that the authorization
// is gone, and in that second case it has already deleted the credential and said to log in again.
// Opening a browser from inside a `search` would be a surprise rather than a service, so that stays
// the person's move.
func authenticated[T any](ctx context.Context, call func(api.Client) (T, error)) (T, error) {
	var nothing T

	client, err := session(ctx)
	if err != nil {
		return nothing, err
	}
	result, err := call(client)
	if !errors.Is(err, api.ErrUnauthorized) {
		return result, err
	}

	renewed, err := clientFor(ctx, func(ctx context.Context, options auth.SessionOptions) (credentials.Credentials, error) {
		return auth.Renew(ctx, options, client.Token)
	})
	if err != nil {
		return nothing, err
	}
	return call(renewed)
}

func search(ctx context.Context, args []string) error {
	query := strings.TrimSpace(strings.Join(args, " "))
	if query == "" {
		return errors.New("用法：skillmaster skill search <关键词>")
	}

	page, err := authenticated(ctx, func(client api.Client) (api.SearchPage, error) {
		return client.Search(ctx, query)
	})
	if err != nil {
		return err
	}
	if len(page.Skills) == 0 {
		fmt.Println("没有匹配的 skill。")
		return nil
	}
	printCards(page.Skills)
	if page.NextCursor != "" {
		// Said rather than silently dropped: a listing that stops without saying so reads as
		// "that is everything", which is a claim the CLI would be making on the server's behalf.
		fmt.Printf("（还有更多结果；这一版不支持翻页，缩小关键词即可。）\n")
	}
	return nil
}

// listSkills prints the skills this caller may read, one addressable line each.
//
// **This is the read entry point, and it is the layer Claude Code loads at session start**: a name
// and a description, and nothing else (§1.2). **The address it prints carries no version** (ADR 0035).
// It used to: the pin was written into the line so that copying the line whole was what kept a caller
// on the version the listing was about. That job moved to `invoke`, which resolves the pin and
// remembers it — so the only thing this line has to be is a name somebody can read and type, and for a
// version its author left unnamed the old line ended in 64 hex characters.
//
// **One page by default, `--all` for the rest, and no cursor a caller has to carry.** The page token
// this command used to print — base64 of the server's cursor — was 191 characters of arbitrary
// alphanumeric, and asking the *model* to reproduce that is asking for the wrong thing. Three
// findings, all pointing the same way:
//
//   - Anthropic's own tool-writing guidance says to eschew low-level technical identifiers and that
//     resolving arbitrary alphanumeric ones into something meaningful improves precision. A 191-
//     character base64 blob is the worst case of the thing to avoid.
//   - Producing that value has no exact-copy path. A person pastes it; a model regenerates it from
//     context, and a long arbitrary string is where that goes wrong.
//   - And the value is mostly not even used: a study of production MCP logs found no agent-initiated
//     request for a second chunk with a cursor, and the failure mode of reading one page and reporting
//     as though it were all of them has a name — coverage collapse.
//
// So what crosses to the caller is a **switch**, not a string. The loop still exists, in `collect`,
// and `--all` is how a caller asks for it in one go — which is the part that must not be left to a
// caller to iterate, one round trip at a time, holding a value it cannot reliably write down.
//
// The same reasoning is why the version sitting in this address went away: it was the other long
// arbitrary string this command handed to a model.
func listSkills(ctx context.Context, args []string) error {
	all, namespaces, err := parseListArgs(args)
	if err != nil {
		return err
	}

	found, err := authenticated(ctx, func(client api.Client) (listed, error) {
		return collect(ctx, client, all, namespaces)
	})
	if err != nil {
		return err
	}

	if len(found.cards) == 0 {
		fmt.Println("没有读得到的 skill。")
		return nil
	}
	printCards(found.cards)

	// Both endings carry the same instruction, and it is the one that matters: the address above is
	// what a later command takes. It is worth saying because the next step is not obvious — the
	// address alone does not load anything, and `invoke` is the command that both loads the body and
	// decides which version the rest of the task will read.
	if found.more {
		// **Said out loud, and it is the most important line this command prints.** A listing that
		// stops without saying so reads as "this is everything", and a caller that believed it would
		// draw a conclusion from the first hundred rows and act on it — the failure the literature
		// calls coverage collapse, and the reason the count and the alternatives are both here.
		fmt.Printf("（这一页 %d 个，还有更多。加载某一个：skillmaster skill invoke <上面某一行的地址>。）\n",
			len(found.cards))
		fmt.Println("（要收窄用 skillmaster skill search \"<关键词>\"；要全部就用 skillmaster skill list --all。）")
		return nil
	}
	fmt.Printf("（共 %d 个。加载某一个：skillmaster skill invoke <上面某一行的地址>。）\n", len(found.cards))
	return nil
}

// printCards is how an L1 row looks, in the two places that print a listing.
//
// **The address is alone on its own line**, and that is for the caller rather than for looks: it is
// the value a later command takes, whole, and a line with nothing else on it is a line that can be
// copied without deciding where it ends. The reading goes under it, indented.
//
// One function because there is one shape: `list` and `search` are the same layer answering two
// questions, and two copies of this would be two things to change when the layer changes — which is
// exactly what just happened to it.
func printCards(cards []api.Card) {
	for _, card := range cards {
		fmt.Println(card.Address())
		if summary := card.Summary(); summary != "" {
			fmt.Printf("    %s\n", summary)
		}
	}
}

// listed is what a call to this command read, and whether there was more it did not read.
type listed struct {
	cards []api.Card
	more  bool
}

// collect reads one page, or every page when `--all` was asked for.
//
// The pages are gathered before anything is printed, so a request that fails in the middle prints no
// half list. A half list and a complete one look exactly alike, and this is a listing whose whole
// purpose is to be the caller's picture of what exists.
func collect(ctx context.Context, client api.Client, all bool, namespaces []string) (listed, error) {
	var cards []api.Card
	cursor := ""
	for {
		// **The filter goes on every page, and that is the one thing this loop has to get right.**
		// The server's cursor carries the ordering and not the predicate, so a page fetched without
		// the filter is accepted and quietly answers a different question — the silent skip the
		// whole cursor design exists to prevent, arriving from this side. Passing the same slice
		// each turn is what makes it unrepresentable here; a test asserts the query carries it.
		page, err := client.List(ctx, namespaces, cursor)
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
		// A server that answers with the cursor it was just given is not paging, and looping on it
		// would hang with no output. Stopped and said out loud rather than repeated: the silence of
		// an infinite loop reads as a slow network, and this one is a bug on the far end.
		if page.NextCursor == cursor {
			return listed{}, fmt.Errorf("服务端反复给出同一个游标，列表走不下去了（已取到 %d 条）", len(cards))
		}
		cursor = page.NextCursor
	}
}

// parseListArgs reads `[--all] [--namespace <slug>]...`.
//
// **`--namespace` repeats**, because a caller reads in more than one namespace — their own, plus
// every namespace a skill was shared with them from — and "mine together with what somebody shared"
// is one question rather than two listings to be stitched together on this side. Order is not
// significant, and passing none is the whole set.
//
// **The slug is not checked here.** A name that resolves to nothing is the endpoint's business (it
// selects nothing, and it is not a way to widen), and a check on this side would need a list of
// namespaces the CLI does not have — a second authority for a rule the server already states.
func parseListArgs(args []string) (bool, []string, error) {
	const usage = "用法：skillmaster skill list [--all] [--namespace <命名空间>]..."
	all := false
	var namespaces []string
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--all":
			all = true
		case "--namespace":
			if i+1 >= len(args) {
				return false, nil, errors.New("--namespace 后面要跟一个命名空间。" + usage)
			}
			namespaces = append(namespaces, args[i+1])
			i++
		default:
			return false, nil, fmt.Errorf("不认识的参数 %q。%s", args[i], usage)
		}
	}
	return all, namespaces, nil
}

// shareSkill hands one skill to one account, which is the first thing this CLI can do that changes
// what somebody else reads (ADR 0034).
//
// The boundary is worth stating where the person can see it, because the two roles are not
// variations of one another: an `editor` can add versions and still cannot publish them, so what a
// share hands out is reading, or reading plus drafting — never a change to what every reader of this
// server gets. Publishing stays in the browser and stays the owner's (ADR 0031).
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
	const usage = "用法：skillmaster skill share <命名空间>/<名字> --to <用户名> [--role viewer|editor]"
	if len(args) == 0 {
		return "", "", "", errors.New(usage)
	}
	address = args[0]
	role = api.RoleViewer

	rest := args[1:]
	for len(rest) > 0 {
		switch rest[0] {
		case "--to":
			if len(rest) < 2 {
				return "", "", "", errors.New("--to 后面要跟一个用户名。" + usage)
			}
			handle, rest = rest[1], rest[2:]
		case "--role":
			if len(rest) < 2 {
				return "", "", "", errors.New("--role 后面要跟 viewer 或 editor。" + usage)
			}
			role, rest = rest[1], rest[2:]
		default:
			return "", "", "", fmt.Errorf("不认识的参数 %q。%s", rest[0], usage)
		}
	}

	// `--to` has no default value, and that is the point: there is no such thing as sharing with
	// somebody in general, and a default here would mean the command could share with a person nobody
	// named.
	if handle == "" {
		return "", "", "", errors.New("--to 是必填的。" + usage)
	}
	if role != api.RoleViewer && role != api.RoleEditor {
		return "", "", "", fmt.Errorf("角色只能是 %s 或 %s，收到 %q", api.RoleViewer, api.RoleEditor, role)
	}
	// The address's grammar first, so a malformed one is refused here rather than after a round trip.
	if _, err := api.SkillPath(address); err != nil {
		return "", "", "", err
	}
	// Then the pin, which parses and would be dropped on the floor: a share is a property of the
	// skill, not of a version of it. `@` cannot appear anywhere else in an address — it is the pin
	// separator, which is why a name containing one is not addressable at all — so this cannot
	// refuse a legitimate address.
	if strings.Contains(address, "@") {
		return "", "", "", errors.New("共享是针对整个 skill 的，不是某一版——地址后面不要带 @版本")
	}
	return address, handle, role, nil
}

// roleLine says what the role a skill was just shared with actually lets that person do.
//
// Not the constant's name repeated: `viewer` and `editor` are the wire's words, and someone who has
// just typed one of them is owed the answer to "so what can they do".
func roleLine(role string) string {
	switch role {
	case api.RoleViewer:
		return "只读"
	case api.RoleEditor:
		return "可以提交新版本，但不能上线"
	default:
		// A role from a newer server than this build. Saying nothing about what it grants is the
		// honest answer; the name is above, and the server is the authority on what it means.
		return role
	}
}

// ---------------------------------------------------------------------------
// Loading a skill, and reading its files
// ---------------------------------------------------------------------------

const (
	invokeUsage   = "用法：skillmaster skill invoke <命名空间>/<名字>[@版本] [--offset <行号>]"
	filesUsage    = "用法：skillmaster skill files <命名空间>/<名字>[@版本]"
	readUsage     = "用法：skillmaster skill read <命名空间>/<名字>[@版本] <相对路径> [--offset <行号>]"
	versionsUsage = "用法：skillmaster skill versions <命名空间>/<名字>"
)

// loaded is one resolved skill: the manifest that resolved it, and the bytes of the one thing the
// command asked for.
//
// Both halves travel together because the command needs both to say anything useful: the content is
// the answer and the manifest is where the version that answered it is written down. That version is
// not the caller's to know — it is what `invoke` records and what the closing line reports.
type loaded struct {
	detail  api.Detail
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
		return errors.New(invokeUsage)
	}
	address := rest[0]
	// The address is validated here too, not only in `pinFor`: `invoke` is the command a caller uses to
	// reach a *specific* version, so a malformed one has to be refused rather than handed to the server.
	// One parser, three readers — a second copy is how two commands come to disagree about a spelling.
	namedVersion, named, err := versionInAddress(address)
	if err != nil {
		return err
	}

	// The whole command is the unit that gets retried, not each request: it is two calls — the
	// manifest, then the bytes — and a credential replaced between them should carry through both.
	fetched, err := authenticated(ctx, func(client api.Client) (loaded, error) {
		// No pin on the request: when the caller named a version it is already in the address, and the
		// server is what resolves it. What this machine remembered is deliberately not consulted —
		// invoking is the thing that decides.
		detail, err := client.Detail(ctx, address, "")
		if err != nil {
			return loaded{}, err
		}
		content, err := client.Fetch(ctx, detail.Resources.Body)
		if err != nil {
			return loaded{}, err
		}
		return loaded{detail: detail, content: content}, nil
	})
	if err != nil {
		// A 404 on a named version gets the same next step `read` and `files` give it. Here there is no
		// local record to blame — this command never consults one — so the only other answer is the
		// server's own, unchanged.
		if errors.Is(err, api.ErrNotFound) && named {
			return versionGone(err, namedVersion, address)
		}
		return err
	}

	key := bareAddress(address)
	version := fetched.detail.Version
	now := api.VersionSuffix(version.Name, version.Digest)
	previous, had, err := rememberPin(key, version.Name, version.Digest)
	if err != nil {
		return err
	}

	printContent(fetched.content, offset, withoutLineNumbers, func(next int) string {
		return fmt.Sprintf("skillmaster skill invoke %s --offset %d", address, next)
	})

	if had {
		if before := api.VersionSuffix(previous.Name, previous.Digest); before != now {
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
		return errors.New(readUsage)
	}
	address, relpath := rest[0], rest[1]
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
	return api.VersionSuffix(remembered.Name, remembered.Digest), "", nil
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
		return "", false, errors.New("地址里的 @ 后面要跟一个版本名或 sha256:… 摘要")
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
		"看有哪些版本：skillmaster skill versions %s；要按名字找 skill：skillmaster skill list。）",
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
	fetched, err := authenticated(ctx, func(client api.Client) (loaded, error) {
		detail, err := client.Detail(ctx, address, pin)
		if err != nil {
			return loaded{}, err
		}
		file, found := findFile(detail, relpath)
		if !found {
			return loaded{}, fileMissing(relpath, address)
		}
		// The URI comes from the manifest and is followed verbatim. It already has a version written
		// into it, so a publish landing in between cannot change what arrives.
		content, err := client.Fetch(ctx, file.URI)
		if err != nil {
			return loaded{}, err
		}
		return loaded{detail: detail, content: content, binary: file.IsBinary}, nil
	})
	if err != nil {
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
		which = fmt.Sprintf("（按 %s 取。）", fetched.detail.Address())
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

// fileMissing is what a `read` of a relpath this version does not have says.
//
// The next step is part of the message, and it is the reason the command exists: the lookup is an
// exact match against the manifest, so nothing here can repair a near miss, and without the pointer
// the only recovery is guessing names until one works. Extracted for the same reason `pinFailure` is
// — a decision worth testing on its own, with no server and no credential.
func fileMissing(relpath, address string) error {
	return fmt.Errorf("这个版本里没有 %s\n（看看它有哪几个文件：skillmaster skill files %s。）",
		relpath, address)
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
		return errors.New(filesUsage)
	}
	address := args[0]
	pin, note, err := pinFor(address)
	if err != nil {
		return err
	}
	key := bareAddress(address)

	detail, err := authenticated(ctx, func(client api.Client) (api.Detail, error) {
		return client.Detail(ctx, address, pin)
	})
	if err != nil {
		if errors.Is(err, api.ErrNotFound) && pin != "" {
			return notFoundFor(err, address, pin, key)
		}
		return err
	}

	which := note
	if which == "" {
		which = fmt.Sprintf("（按 %s 列的。）", detail.Address())
	}
	printFiles(detail.Files, address, which)
	return nil
}

// printFiles writes a version's manifest, one relpath per line, then says which version it listed.
//
// **A relpath alone on its line, and that is the whole shape.** That line is what the caller pastes
// into `read`, so nothing is appended to it — the same rule the skill list follows. It is also why
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
		return errors.New(versionsUsage)
	}
	address := args[0]
	if strings.Contains(address, "@") {
		return errors.New("版本列表是针对整个 skill 的，地址后面不要带 @版本")
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
	}
	fmt.Printf("（共 %d 版。要钉住其中某一版：把上面某一行的地址整段抄进 skillmaster skill invoke。）\n",
		len(listed))
	return nil
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
func rememberPin(address, name, digest string) (pins.Pin, bool, error) {
	store, err := pinsFor(config.Server())
	if err != nil {
		return pins.Pin{}, false, err
	}
	pin, had, err := store.Remember(address, pins.Pin{Name: name, Digest: digest})
	if err != nil {
		return pins.Pin{}, false, pinsUnreadable(err)
	}
	return pin, had, nil
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
	dirArgs, target, err := splitSubmitArgs(args)
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

	// Retrying a submission is safe, and for a reason worth naming: a rejected token is refused at
	// the security filter, so the first attempt wrote nothing, and submitting the same bytes twice is
	// idempotent anyway (ADR 0005) — it produces no second version, draft or otherwise.
	result, err := authenticated(ctx, func(client api.Client) (api.SubmitResult, error) {
		if target == "" {
			return client.Submit(ctx, archive)
		}
		return client.SubmitVersion(ctx, target, archive)
	})
	if err != nil {
		return err
	}

	fmt.Printf("%s %s/%s@%s　%s\n", submitVerb(result), result.Namespace, result.Name,
		api.VersionSuffix(result.Version.Name, result.Version.Digest), result.Version.Digest)
	fmt.Printf("  %d 个文件　%d 字节\n", result.Version.FileCount, result.Version.TotalBytes)

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

// submitUsage is the command's syntax, written once because six places refuse an argument and all
// six should name the same thing. `--to` is in it because a person who gets a usage line without it
// does not learn the flag exists.
const submitUsage = "用法：skillmaster skill submit [名字 | 目录] [--to <命名空间>/<名字>]"

// splitSubmitArgs separates the `--to` target from the argument that names what to upload.
//
// The flag comes out first so that `resolveSkill` keeps its one job and its one argument. Its reading
// of that argument is deliberately by the argument's own characters and never by asking the
// filesystem, and a `--to <address>` left in the list would be read as a skill named `--to`.
//
// The address is checked here rather than left to the call that uses it, so a malformed one is a
// message before an archive of the whole directory is built and uploaded. `api.SkillPath` is the
// check because it is the one spelling of the address grammar this program has.
// A version pin is refused for the same reason `share` refuses one: the version is what this call
// produces, so a target that named one would be naming something this call does not use.
func splitSubmitArgs(args []string) (dirArgs []string, target string, err error) {
	const usage = submitUsage

	for i := 0; i < len(args); i++ {
		if args[i] != "--to" {
			dirArgs = append(dirArgs, args[i])
			continue
		}
		if i+1 >= len(args) {
			return nil, "", errors.New("--to 后面要跟一个 <命名空间>/<名字>。" + usage)
		}
		if target != "" {
			return nil, "", errors.New("--to 只能给一次。" + usage)
		}

		target = args[i+1]
		if _, err := api.SkillPath(target); err != nil {
			return nil, "", err
		}
		if strings.Contains(target, "@") {
			return nil, "", errors.New("--to 指的是一个 skill，不是某一版——地址后面不要带 @版本")
		}
		i++
	}
	return dirArgs, target, nil
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
		return "", errors.New(submitUsage)
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
		return "", errors.New(submitUsage + "（名字不能是空的）")
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
		return fmt.Errorf(submitUsage+"（读 %s 失败：%v）", dir, err)
	}
	if len(names) == 0 {
		return fmt.Errorf(submitUsage+"（%s 里没有找到 skill）", dir)
	}
	return fmt.Errorf(submitUsage+"，%s 里有：%s",
		dir, strings.Join(names, "、"))
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
			return "", errors.New("用法：skillmaster setup [--dir <技能目录>]")
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
func openStore(server string) (credentials.Store, string) {
	path, err := config.CredentialPath(server)
	if err != nil {
		fmt.Fprintf(os.Stderr, "找不到用户配置目录（%v），将只使用钥匙串。\n", err)
		path = ""
	}
	return credentials.Open(path, server)
}

func usage() {
	fmt.Fprint(os.Stderr, `skillmaster — SkillMaster 的命令行客户端

用法：
  skillmaster login                       浏览器登录（PKCE），凭据存进钥匙串
  skillmaster login --client-credentials  无人值守登录（CI），读环境变量
  skillmaster logout                      撤销授权并清除本地凭据
  skillmaster setup [--dir <技能目录>]     安装网关 skill

  skillmaster skill <子命令>               对 skill 的操作，见 skillmaster skill help
    list [--all] [--namespace <命名空间>]...
                                          列出你读得到的 skill（含别人共享给你的）
                                          每条第一行是地址本身（不带版本），缩进那行是描述与
                                          「什么时候该用」
                                          默认只列前 100 条并告诉你还有更多；--all 一次列完全部
                                          --namespace 可重复，只列这几个命名空间的（默认全部）
    search <关键词>                        检索 skill
    invoke <命名空间>/<名字>[@版本]          加载正文，并把这一版钉在这台机器上
                                          默认是当前线上版本；不带 @版本 时它才跟着指针走
    files <命名空间>/<名字>[@版本]           列出这一版有哪些文件（相对路径）
                                          本机没有 skill 的目录可以 ls，所以清单由它来给
    read <命名空间>/<名字>[@版本] <相对路径>    读它引用的一个文件，默认用的是 invoke 钉住的那一版
                                          地址里写了 @版本 就用它，并压过本机记录
                                          结果打到标准输出；--offset <行号> 从某一行接着看
    versions <命名空间>/<名字>               列出可以 invoke 的版本（含被顶替的旧版本）
    submit [名字 | 目录] [--to <命名空间>/<名字>]
                                          提交一版（落成草稿，不改线上），并打开审批页
                                          （内容已在线上或已被丢弃时不打开，只打印地址）
                                          不带参数会列出本机有哪些 skill
                                          --to 是把这一版提到别人的 skill 上（要对方给编辑权限）
    share <命名空间>/<名字> --to <用户名> [--role viewer|editor]
                                          把这一个 skill 共享给某个人，默认只读

**为什么 skill 是一个组**：这个 CLI 以后还会有别的名词（命名空间、版本、授权），而光杆的
list 没说清它列的是什么。形状跟 claude mcp list / gh pr list 一样——名词带一组动词。
login / logout / setup 留在顶层，因为它们作用在这台机器上，而不是仓库里的某个 skill。

**invoke 必须先跑，read 才有那一版可读。** invoke 解析版本并把钉写进用户配置目录下的
skillmaster/（macOS 是 ~/Library/Application Support/skillmaster/，Linux 是
~/.config/skillmaster/）里一个按服务端分开的文件，read 从那里取出来、用请求头带过去，所以地址和
命令里永远不用写版本。跳过 invoke 的话 read 会退回「当前线上版本」，而任务进行到一半有人发布时
它就会换版本——所以它会把这件事说出来，而不是默默换掉。

**枚举由 files 给，因为本机没有目录可以 ls。** 宿主加载 skill 时会给一行本地目录，模型自己
ls / glob 就够了；这里没有副本（服务端权威），所以「这一版有哪些文件」只能问服务端。它和 read
用同一把钥匙：读不到这个 skill 的人，两个命令一样是 404。

**submit 只能提交，不能上线。** 上线要把内容推给所有读这个服务的人，只能在浏览器里由人点，
cli 交给你的是一条不含任何凭据的深链。

**share 会改变别人读得到什么**，而 --role editor 给出的是「能提草稿，但不能上线」——上线仍然
只能在浏览器里由 skill 的主人点。这个命令不在网关正文里，agent 没有共享的理由。

环境变量：
  `+config.ServerEnv+`                服务端地址（默认 `+config.DefaultServer+`）
  `+config.ClientCredentialsIDEnv+` / `+config.ClientCredentialsSecretEnv+`
                                   仅 --client-credentials 需要
  `+config.SkillsDirEnv+`             本机 skill 在哪（默认 ~/.claude/skills）
  `+config.WebURLEnv+`                审批页的地址（默认与服务端相同）
`)
}

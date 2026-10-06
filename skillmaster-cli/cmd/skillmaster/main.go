// Command skillmaster is the CLI: it holds the credential, fetches skills on demand, and submits
// them for approval.
//
// §4.6's command set is here except for the two the design lists but nothing needs yet: there is no
// `versions` listing and no rollback. Everything else — login, logout, setup, search, show, get,
// submit — is in this file plus the three packages under internal/.
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
	case "search":
		return search(ctx, args[1:])
	case "show":
		return show(ctx, args[1:])
	case "get":
		return get(ctx, args[1:])
	case "submit":
		return submitSkill(ctx, args[1:])
	case "help", "-h", "--help":
		usage()
		return nil
	default:
		usage()
		return fmt.Errorf("未知的子命令 %q", args[0])
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
		return errors.New("用法：skillmaster search <关键词>")
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
	for _, card := range page.Skills {
		fmt.Printf("%s  %s\n", card.Address(), card.Title)
		if card.Description != "" {
			fmt.Printf("    %s\n", card.Description)
		}
	}
	if page.NextCursor != "" {
		// Said rather than silently dropped: a listing that stops without saying so reads as
		// "that is everything", which is a claim the CLI would be making on the server's behalf.
		fmt.Printf("（还有更多结果；这一版不支持翻页，缩小关键词即可。）\n")
	}
	return nil
}

func show(ctx context.Context, args []string) error {
	if len(args) != 1 {
		return errors.New("用法：skillmaster show <命名空间>/<名字>[@版本]")
	}

	detail, err := authenticated(ctx, func(client api.Client) (api.Detail, error) {
		return client.Detail(ctx, args[0])
	})
	if err != nil {
		return err
	}
	fmt.Printf("%s  %s\n", detail.Address(), detail.Title)
	if detail.Description != "" {
		fmt.Printf("  %s\n", detail.Description)
	}
	fmt.Printf("  可见性 %s　版本 %d　%s　%d 个文件　%d 字节%s\n",
		detail.Visibility, detail.Version.Number, detail.Version.Digest,
		detail.Version.FileCount, detail.Version.TotalBytes,
		map[bool]string{true: "　（最新）", false: ""}[detail.Version.IsLatest])
	for _, file := range detail.Files {
		fmt.Printf("    %s  %d 字节\n", file.Relpath, file.Size)
	}
	// The manifest is a list of addresses, not the content. Saying so is the difference between a
	// person running `get` and a person wondering why the body did not print.
	fmt.Println("（这是清单；要正文用 skillmaster get。)")
	return nil
}

func get(ctx context.Context, args []string) error {
	if len(args) < 1 || len(args) > 2 {
		return errors.New("用法：skillmaster get <命名空间>/<名字>[@版本] [相对路径]")
	}
	relpath := ""
	if len(args) == 2 {
		relpath = args[1]
	}

	// The whole of `get` is the unit that gets retried, not each request: it is two calls — the
	// manifest, then the bytes — and a credential replaced between them should carry through both.
	target, err := authenticated(ctx, func(client api.Client) (string, error) {
		return fetchInto(ctx, client, args[0], relpath)
	})
	if err != nil {
		return err
	}
	fmt.Println(target)
	return nil
}

// fetchInto reads one file of one version and leaves it in a temporary directory, returning the path.
func fetchInto(ctx context.Context, client api.Client, address, relpath string) (string, error) {
	detail, err := client.Detail(ctx, address)
	if err != nil {
		return "", err
	}

	// The URI comes from the manifest and is followed verbatim, which is what pins the version: it
	// already has one written into it, so a publish landing in between cannot change what arrives.
	uri := detail.Resources.Body
	if relpath != "" {
		uri = ""
		for _, file := range detail.Files {
			if file.Relpath == relpath {
				uri = file.URI
				break
			}
		}
		if uri == "" {
			return "", fmt.Errorf("这个版本里没有 %s", relpath)
		}
	}
	if uri == "" {
		return "", fmt.Errorf("清单里没有正文地址")
	}

	content, err := client.Fetch(ctx, uri)
	if err != nil {
		return "", err
	}

	// A temporary directory, and its path is what gets printed: this is an intermediate artefact for
	// whoever asked, not a copy of the skill installed anywhere (§4.6 says so explicitly).
	dir, err := os.MkdirTemp("", "skillmaster-")
	if err != nil {
		return "", fmt.Errorf("创建临时目录：%w", err)
	}
	name := filepath.Base(relpath)
	if relpath == "" {
		name = "SKILL.md"
	}
	target := filepath.Join(dir, name)
	if err := os.WriteFile(target, content, 0o600); err != nil {
		return "", fmt.Errorf("写入 %s：%w", target, err)
	}
	return target, nil
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
func submitSkill(ctx context.Context, args []string) error {
	dir, err := resolveSkill(args)
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
		return client.Submit(ctx, archive)
	})
	if err != nil {
		return err
	}

	fmt.Printf("%s %s/%s@%d　%s\n", submitVerb(result), result.Namespace, result.Name,
		result.Version.Number, result.Version.Digest)
	fmt.Printf("  %d 个文件　%d 字节\n", result.Version.FileCount, result.Version.TotalBytes)

	// Said before the link, and said plainly, because this is the fact the whole split turns on: a
	// successful `submit` has changed nothing anybody can read yet — when what it left was a draft.
	state := result.Version.State
	fmt.Println(stateLine(state))

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
// The version number is what separates the two, and ADR 0012 makes it reliable: version 1 is the
// first content a skill ever holds, so a submission answering with 1 is the one that created it.
//
// The third outcome is a replay of identical content (ADR 0005's idempotence): nothing was written,
// and the version printed is the one that was already there. Any verb with 更新 in it would claim a
// change that did not happen, so this one says only that the content did not move — and what the
// version's state actually is comes from stateLine, printed on the line below.
func submitVerb(result api.SubmitResult) string {
	switch {
	case !result.Created:
		return "内容未变"
	case result.Version.Number == 1:
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
// the honest answer there; the number and digest above still name the version.
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
		return "", errors.New("用法：skillmaster submit [名字 | 目录]")
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
		return "", errors.New("用法：skillmaster submit [名字 | 目录]（名字不能是空的）")
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
		return fmt.Errorf("用法：skillmaster submit <名字 | 目录>（读 %s 失败：%v）", dir, err)
	}
	if len(names) == 0 {
		return fmt.Errorf("用法：skillmaster submit <名字 | 目录>（%s 里没有找到 skill）", dir)
	}
	return fmt.Errorf("用法：skillmaster submit <名字 | 目录>，%s 里有：%s",
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
// creating the directory for a store that does not need it would leave a `~/.config/skillmaster`
// behind on a machine that never falls back.
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
  skillmaster search <关键词>              检索 skill
  skillmaster show <命名空间>/<名字>[@版本] 看清单与文件列表
  skillmaster get <命名空间>/<名字>[@版本] [相对路径]
                                          取正文或单个文件，落到临时目录并打印路径
  skillmaster submit [名字 | 目录]         提交一版（落成草稿，不改线上），并打开审批页
                                          （提交的内容已经在线上或已被丢弃时不打开，只打印地址）
                                          不带参数会列出本机有哪些 skill

**submit 只能提交，不能上线。** 上线要把内容推给所有读这个服务的人，只能在浏览器里由人点，
cli 交给你的是一条不含任何凭据的深链。

环境变量：
  `+config.ServerEnv+`                服务端地址（默认 `+config.DefaultServer+`）
  `+config.ClientCredentialsIDEnv+` / `+config.ClientCredentialsSecretEnv+`
                                   仅 --client-credentials 需要
  `+config.SkillsDirEnv+`             本机 skill 在哪（默认 ~/.claude/skills）
  `+config.WebURLEnv+`                审批页的地址（默认与服务端相同）
`)
}

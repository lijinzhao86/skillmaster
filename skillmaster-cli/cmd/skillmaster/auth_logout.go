// Logging out, and where to revoke the credential.

package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/apikey"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/auth"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// logout ends this machine's use of a credential, and — for a CLI login — the server's half of it.
//
// **The two credential kinds end differently, and v46 makes that split sharper rather than blurring
// it** (ADR 0038 §决定 7, ADR 0142). A CLI login is a 「登录设备」 with an authorization behind it, and
// this command now ends that authorization too, so the console's list stops showing a machine that
// thinks it is signed out. An API key has no login to end: it is revoked in the console, where the
// list shows what is being changed.
//
// The server call is not allowed to make the command fail. Clearing this machine is the contract
// that the next command depends on; a server that is down changes what is said, not what is done.
func logout(args []string) error {
	if len(args) > 0 {
		return usagef("logout 不带参数。%s", usageAt("logout"))
	}
	server := config.Server()
	store, _ := openStore(server)
	return logoutWithServer(store, server, nil)
}

// logoutWith is `logout` on a store it was handed and the server the configuration names.
func logoutWith(store credentials.EnvStore) error {
	return logoutWithServer(store, config.Server(), nil)
}

// alreadyEnded says whether the server answered "this login is already over".
//
// The one 401 that is not a failure for this command: the credential reached the server and the
// server said its authorization is ended, which is exactly the state `logout` wanted. Treated as
// success so a second run of the command — or a login the console already ended — does not tell the
// person something went wrong.
func alreadyEnded(err error) bool {
	var refusal *api.Refusal
	if !errors.As(err, &refusal) {
		return false
	}
	switch refusal.Code {
	case "token_revoked", "token_expired", "token_idle":
		// **All three mean the server has no usable login left to end.** The console's list filters
		// by the credential's own expiry, so an expired or idle login is not a row there either —
		// printing "服务端那份还在" for one would send the person to a screen that does not list it.
		// `unauthenticated` is deliberately absent: it can also be a different server answering,
		// and claiming an ending that may not have happened is worse than saying the call failed.
		return true
	}
	return false
}

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
// **v46 narrows the first case's use, not its wording**: a live CLI login is now ended by this
// command itself, so the console sentence is what a *failed* ending falls back to.
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

// logoutWithServer is the whole command, with the two things a test has to replace passed in: the
// server it may call and the client that call goes through. A store is the third, the way `login`
// takes one — reaching for the platform's keychain from inside the tested part is the part that is
// not worth testing here.
//
// The 5-second budget is this command's own, not the API package's two minutes: a person who typed
// `skmr logout` is leaving, and a server that cannot answer quickly is the "still there" case A13
// describes, not a reason to hold the terminal.
func logoutWithServer(store credentials.EnvStore, server string, httpClient *http.Client) error {
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
		// authenticating with it. The clear below cannot be believed either: the responsive keychain
		// returns success on its own timeout *by design* (`responsiveKeychain.Clear`), so nothing
		// downstream reports the thing this one just concealed. Refusing is what the v1 command did
		// with the same failure, and it is the honest answer: this machine's state is unknown, so it
		// cannot be reported as clean.
		return fmt.Errorf("读不到本机凭据，所以没有清除任何东西（钥匙串可能锁着）：%w", err)
	}
	inUse := stored
	if fromVariable, found, loadErr := store.Load(); loadErr == nil && found {
		inUse = fromVariable
	}

	// **The server's half, while the credential is still in hand** (v46, ADR 0142). Only a login has
	// an authorization this command may end: a key is the console's, and a leftover from an older
	// login has no route either (ADR 0040 §后果). The shape test is the one the rest of the CLI
	// already uses to tell a v4 login from those leftovers.
	//
	// **The order is revoke, then clear.** If this process dies between them, the local file still
	// holds the credential and the person can run the command again; the other order would leave a
	// live login nobody on this machine can name any more.
	// **A key is excluded by its prefix, not only by the leftover shape.** `Store.Save` stamps
	// `SavedAt` on everything it writes (`Credentials.Blob`), so an API key saved by an older version
	// (`login --with-key`, removed in v3) also answers `NotALeftover` — and would be sent to a route
	// that only ends logins. The shape test alone is not enough here; the key's own prefix is.
	isLogin := hadStored && stored.AccessToken != "" && !apikey.IsKey(stored.AccessToken) &&
		auth.LeftoverOf(stored) == auth.NotALeftover

	serverEnded := false
	var serverErr error
	if isLogin {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		client := api.Client{BaseURL: server, Token: stored.AccessToken, HTTP: httpClient}
		serverErr = client.Logout(ctx)
		cancel()
		serverEnded = serverErr == nil || alreadyEnded(serverErr)
	}

	if err := store.Clear(); err != nil {
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
	switch {
	case isLogin:
		// v46: this command ended the server's half itself, so the console sentence is the fallback
		// rather than the answer. Saying "ended" on the strength of `alreadyEnded` is honest: the
		// server's answer was that the login is already over, which is the state the person wanted.
		if serverEnded {
			fmt.Println("服务端那份也已结束。")
		} else {
			fmt.Printf("服务端那份还在：%v\n", serverErr)
			fmt.Println(whereToRevoke(stored))
		}
	default:
		if where := whereToRevoke(stored); where != "" {
			fmt.Println(where)
		}
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

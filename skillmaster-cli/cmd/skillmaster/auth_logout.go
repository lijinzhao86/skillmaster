// Logging out, and where to revoke the credential.

package main

import (
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/apikey"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/auth"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

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

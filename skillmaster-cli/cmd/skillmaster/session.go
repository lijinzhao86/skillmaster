// The session a command runs under: credential lookup, client construction, and the answered-error wrapper.

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
	"os"
	"strings"
)

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
// errNoBrowserNoCredential is the headless-and-empty case, told apart from any other missing
// credential so `startup` can answer it in its own words and its own exit code.
var errNoBrowserNoCredential = errors.New("本机没有凭据，这里也打不开浏览器")

func clientFor(ctx context.Context, noLogin bool) (api.Client, error) {
	server := config.Server()
	store, notice := openStore(server)

	stored, err := auth.AccessToken(store)
	if errors.Is(err, auth.ErrNotSignedIn) && noLogin {
		// **No browser, and the sentence names both ways that do work on a machine nobody is sitting
		// at.** A login is the one the switch is protecting against; an API key in the environment is
		// the shape that needs no person and no store. Returning here also means no request is sent,
		// which is the first thing a caller in a loop can rely on.
		return api.Client{}, &credentialError{message: fmt.Sprintf("本机没有凭据，--no-login 不会发起登录。先运行 skmr login，"+
			"或把一把控制台签发的 API key 放进 %s。", config.ApiKeyEnv)}
	}
	if errors.Is(err, auth.ErrNotSignedIn) && browserAvailable() && !sandbox.Detect().Sandboxed {
		// **A sandbox can have `open` and still not be able to open a browser.** The command that
		// waits for a callback nobody can produce sits there for the full five minutes; an agent
		// cannot relay the URL while it is blocked. So a detectable sandbox takes the no-browser
		// path below and names the API key instead. An explicit `skmr login` still tries, because
		// the person may be able to open the printed URL themselves.
		// On stderr, because it is a notice and not the command's answer: `--json` promises that
		// stdout carries exactly one object, and a line printed while the browser opens would be a
		// second one.
		fmt.Fprintf(os.Stderr, "本机没有凭据，先登录 %s。\n", server)
		if loginErr := startLogin(ctx, server, store, notice); loginErr != nil {
			return api.Client{}, loginErr
		}
		stored, err = auth.AccessToken(store)
	}
	if errors.Is(err, auth.ErrNotSignedIn) {
		// No credential and no way to open a browser here. The bare "先运行 skmr login" that
		// used to fall out of this path sends a headless machine to a command that cannot finish
		// (v22 A2). **An ordinary failure, not exit 4**: ADR 0087's four-credential class is a closed
		// list, and a missing credential on a machine that could log in is not that. `startup` is the
		// one caller that deliberately turns this into the credential class, because for it the
		// refusal *is* the answer.
		//
		// **A detectable sandbox names the terminal it has to be run in.** `skmr login` in the same
		// sandbox hits the blocked `open` and waits out the callback timeout — the sentence must not
		// send a person back into the wait this branch exists to avoid.
		if sandbox.Detect().Sandboxed {
			return api.Client{}, fmt.Errorf("%w。请在沙箱外的终端运行 skmr login；"+
				"无人值守把一把控制台签发的 API key 放进 %s（help auth）。",
				errNoBrowserNoCredential, config.ApiKeyEnv)
		}
		return api.Client{}, fmt.Errorf("%w。有浏览器就运行 skmr login；"+
			"无人值守把一把控制台签发的 API key 放进 %s（help auth）。",
			errNoBrowserNoCredential, config.ApiKeyEnv)
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
			"重新运行 skmr login 点一下就能换一份新的\n      这份凭据来自 %s。",
			err, credentialSource())
	}
	return result, err
}

package auth

import (
	"context"
	"errors"
	"fmt"
	"net/http"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/apikey"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// ErrNotSignedIn is what a command gets when there is nothing stored.
var ErrNotSignedIn = errors.New("本机没有可用的凭据")

// ErrOldBrowserLogin is what a credential from the browser login this version replaced gets.
//
// **The message is what the person reads, so it says what the thing is, why it stopped working, and
// what to do about it.** It used to be the English phrase "the authorization was revoked", which was
// this CLI translating a server code; that translation died with the refresh chain, because nothing
// on this path asks a server about a credential like this any more.
//
// **It is not deleted when this is returned, and that is deliberate rather than an oversight.** The
// only thing that can end this authorization is the refresh token inside it, and the moment that
// happens is the next `login` — so a command that threw the credential away would be throwing away
// the one chance to tidy up a live authorization (v4 Q8(i); `revokeReplaced` is where it happens).
var ErrOldBrowserLogin = errors.New(
	"本机存的凭据来自旧版的浏览器登录（它带着刷新令牌）。这个版本起这条路不再续期，" +
		"所以它在这里已经不能用了。重新运行 skmr login 换一份——" +
		"那一次登录会顺手把这一份结束掉")

// ErrNotRenewable is what the unattended login v3 removed left behind: a credential with no refresh
// token, written before this CLI's stored format knew what it was storing.
//
// **A key never reaches this, and neither does the credential this version's login writes** — both
// are recognised before it (see `LeftoverOf`). What is left is a blob `Blob` never wrote: that
// login's whole authentication was a client secret read from two environment variables this CLI no
// longer has, so there is nothing to present it with and nothing to renew it with.
//
// **It says the three things the design asks for** (v4 Q8(ii)) — what it is, why it cannot be ended
// from this machine, and that it expires on its own — and it must never say 「上一份已撤销」: nothing
// revoked it, and nothing here can. The way forward it names is the one shape that does work on a
// machine with nobody in front of it.
var ErrNotRenewable = errors.New(
	"本机存的凭据来自无人值守登录——这个版本已经没有那条路了（它的认证是一对已经不在的环境变量），" +
		"所以从这台机器撤不掉它。它没有刷新令牌，到点就自己失效，不会自动续期。" +
		"要接着用就重新运行 skmr login（浏览器登录），" +
		"或在无人值守的机器上把一把 API key 放进环境变量")

// Leftover is what a stored credential is when this version's login did not write it and it is not
// a key: something one of the two logins that are gone left behind.
//
// **Two of them are in the wild and they need different sentences** (v4 Q8), because what can be
// done about them is not the same: one carries the refresh token that ends the authorization it
// belongs to, and the other has no way back to the client secret that could ask for one. Folding
// them into one shape is how a message comes to say 「上一份已撤销」 about something nothing revoked.
type Leftover int

const (
	// NotALeftover is what this version's `login` writes — a login credential with no refresh token —
	// and what a key is, which the caller checks before this.
	NotALeftover Leftover = iota
	// RevocableLeftover is the old browser login's credential: it has the refresh token that ends its
	// authorization, which is what the next `login` presents.
	RevocableLeftover
	// UnrevocableLeftover is the unattended login's: no refresh token, and no road back to the secret
	// that could ask for one.
	UnrevocableLeftover
)

// LeftoverOf classifies a stored credential that is not a key.
//
// **The stamp is the whole test, and it is exact rather than a guess.** Every blob this CLI's stores
// write carries `SavedAt` — `Blob` stamps whatever is saved — so a blob without one was written
// before the stamp existed, and the only shape that is both unstamped and refresh-token-less is the
// unattended login's: the old browser login's blob has the refresh token, which settles it whichever
// version wrote it. This is the same construction the server uses to recognise its own login
// credentials (the `label` column, written by the new sign-in and nothing else): the marker is a fact
// only one writer can produce, rather than a value two writers could come to share.
func LeftoverOf(c credentials.Credentials) Leftover {
	switch {
	case c.RefreshToken != "":
		return RevocableLeftover
	case !c.SavedAt.IsZero():
		return NotALeftover
	default:
		return UnrevocableLeftover
	}
}

// AccessToken is what a command presents: the credential this machine holds, or the reason there is
// nothing it can present.
//
// **It renews nothing, and after v4 there is nothing on this path that could.** The chain, the lock
// and the race window the lock was for are all gone together (v4 做 #2): this login's credential has
// no second life to rotate, the server slides its expiry on a use, and a person ends it in the
// console. So what is left for this function is one decision — is this something the server should
// judge, or something this machine already knows is finished?
//
// **It is also why the stored expiry is not consulted for anything that may still work.** A key's
// date is not a judgement this machine makes (the server is the authority, ADR 0040 §决定 4), and a
// login credential's is not either: `expires_at` from the exchange is the earliest moment it *could*
// end, so a date that has gone by here says nothing about a credential the server has been sliding
// forward all along. The date this machine holds is for showing, not for deciding.
//
// **A leftover is refused whether or not its token has run out yet, and that is a judgement rather
// than an oversight.** The old browser login's access token may have some of its hour left, so
// presenting it would work — for a few minutes, and then the refusal would arrive carrying a reason
// written for a shape the person is not holding, because the two rulers belong to this version's
// login. v4 Q8 asks for the leftover's own sentence at the command, and this is what makes that
// sentence true: **the cost is at most one hour of use**, bought for one sentence that says what the
// thing is and one command that replaces it. The credential is left where it is for the next reason
// over — the next `login` is what ends it.
func AccessToken(store credentials.Store) (credentials.Credentials, error) {
	current, err := signedIn(store)
	if err != nil {
		return credentials.Credentials{}, err
	}
	// A key is handed back as it is, every time, and the server answers. Anything this machine
	// concluded about it would be a guess whose cost is a wrong message: a key that expired a minute
	// ago would be judged "spent" here and reported as a credential that cannot be renewed — a
	// sentence about a login that never happened, and not what to do.
	if apikey.IsKey(current.AccessToken) {
		return current, nil
	}

	switch LeftoverOf(current) {
	case NotALeftover:
		// This version's own login credential, handed over for the key's reason and one more: the
		// server is the only party that can say whether it still works, and it is also the only one
		// that can say *which* of the two rulers ran out — idle and expiry point at different next
		// steps, so that answer has to come from where both are known (v4 做 #11).
		return current, nil
	case RevocableLeftover:
		return credentials.Credentials{}, ErrOldBrowserLogin
	default:
		return credentials.Credentials{}, ErrNotRenewable
	}
}

// RevokeOptions is what ending one authorization needs, wherever the credential came from.
type RevokeOptions struct {
	Server string

	// Credential is the one whose authorization to end. **Only its refresh token is read** — the
	// server takes the whole authorization down with that one, and the second input this used to
	// accept (an access token, which is what an unattended credential had instead of a refresh
	// token) went with the unattended login v3 removed.
	Credential credentials.Credentials

	HTTPClient *http.Client
}

// RevokeAuthorization ends the authorization a credential belongs to, and leaves the store alone.
//
// **Leaving the store alone is the whole of its contract**, and it is why the delete is not here:
// its one caller is `login` ending the authorization it is *replacing*, by which time the credential
// in the store is already the new one. Clearing the local copy is `logout`'s job and only its job —
// and since v2 that command does nothing else (ADR 0038 §决定 7).
//
// **The refresh token is the only thing ever revoked here, and v4 narrowed who can be.** Of the four
// shapes this CLI can find on a machine — a key, this version's login credential, and the two
// leftovers — exactly one has an authorization and a token to present it with: the old browser
// login's. The caller checks before coming here, and a credential with nothing to present is not an
// error, because "already gone" is the state a caller asks for.
func RevokeAuthorization(ctx context.Context, options RevokeOptions) error {
	toRevoke := options.Credential.RefreshToken
	if toRevoke == "" {
		return nil
	}

	client := options.HTTPClient
	if client == nil {
		client = http.DefaultClient
	}
	metadata, err := Discover(ctx, client, options.Server)
	if err != nil {
		return fmt.Errorf("撤销需要连上服务端：%w", err)
	}
	if metadata.RevocationEndpoint == "" {
		return errors.New("服务端没有公布撤销端点，无法撤销")
	}
	return Revoke(ctx, client, metadata.RevocationEndpoint, options.Credential.ClientID, toRevoke)
}

func signedIn(store credentials.Store) (credentials.Credentials, error) {
	stored, found, err := store.Load()
	if err != nil {
		return credentials.Credentials{}, err
	}
	if !found {
		return credentials.Credentials{}, fmt.Errorf("%w：先运行 skmr login", ErrNotSignedIn)
	}
	return stored, nil
}

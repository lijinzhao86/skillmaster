package auth

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/apikey"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
)

// ErrNotSignedIn is what a command gets when there is nothing stored.
var ErrNotSignedIn = errors.New("本机没有可用的凭据")

// ErrNotRenewable is what a spent credential with no refresh token gets.
//
// **A key never reaches this**, because a key is handed back untouched and the server decides whether
// it still works (see the note in `accessToken`). So what is left is a **leftover**: a credential
// written by the unattended login (`--client-credentials`) that v3 removed. Its whole authentication
// was a client secret read from two environment variables this CLI no longer has, so there is nothing
// here to renew with and the way forward is to sign in again — in either of the two shapes that
// remain.
//
// Said here rather than discovered from the server, which answers an empty `refresh_token` with a
// bare `invalid_client` and no hint that the credential was never renewable in the first place.
var ErrNotRenewable = errors.New(
	"这份凭据没有刷新令牌，不能续期。请重新运行 skillmaster login（浏览器授权），" +
		"或 skillmaster login --with-key 换成一把 API key")

// expirySkew is how long before its stated expiry an access token is treated as spent.
//
// Not zero, because the check and the use are not the same instant: a token that is valid when this
// function returns can expire while a request is in flight, and the answer to that is a 401 whose
// cause is two lines away from where it is seen. Two minutes is longer than any request this CLI
// makes and much shorter than the token's hour.
const expirySkew = 2 * time.Minute

// lockWait is how long to wait for the refresh lock before going ahead anyway.
const lockWait = 10 * time.Second

// SessionOptions is what this half needs to talk to the server.
//
// **There is no `ClientID` here, and its absence is deliberate.** A refresh has to be presented as
// the client the credential was issued to, and the credential says which that is — so the one thing
// this struct could get wrong is a caller passing *its* idea of the client id instead. It used to
// have two (`skillmaster-cli`, and whatever an unattended login was seeded as — the shape v3
// removed); with one left, what the field's absence still buys is that the compiled-in id cannot be
// sent for a credential that was minted for something else, which the server answers with
// `invalid_client` at the one moment the person is already being told something went wrong.
type SessionOptions struct {
	Server     string
	Dir        string // where the refresh lock lives, beside the credential
	Store      credentials.Store
	HTTPClient *http.Client

	// Warn is shown to the person when something was survived rather than avoided — a lock that
	// could not be taken, so a race was risked. Optional; a nil Warn drops the message.
	Warn func(string)
}

// AccessToken returns a usable access token, refreshing first if the stored one is spent.
//
// **It does not refresh on every call, and that is the point of storing the access token at all.**
// Refreshing rotates the refresh token (ADR 0024), so a CLI that refreshed every time would rotate
// on every command an agent runs — wearing through the chain, spending a round trip per command,
// and moving the "keep me signed in" clock for somebody who never opened the tool.
func AccessToken(ctx context.Context, options SessionOptions) (credentials.Credentials, error) {
	return accessToken(ctx, options, "")
}

// Renew replaces an access token **the server has already refused**, whatever the stored expiry says
// about it.
//
// That is not a redundancy with AccessToken but the whole reason it exists. A token can be revoked
// while its expiry is still in the future — a password reset on the account revokes every
// authorization it had, and so does anything else that reaches the server's copy — and the expiry
// this CLI stored cannot know that. Asking the server again is the only way to find out, and what
// comes back is authoritative either way: a rotated token, or `ErrAuthorizationRevoked` (the chain is
// gone, and the credential has been deleted) or `ErrNotRenewable` (a leftover from the unattended
// login v3 removed; sign in again).
//
// @param refused the token the server refused. **It is what makes this safe to call twice**, which
//
//	matters because the 401 and this call are not one instant: if the store no longer
//	holds that value, somebody else has already replaced it and there is nothing left to
//	distrust — rotating again would spend a rotation to learn what the store says.
//
// It still takes the lock and still re-reads the store inside it, because two processes told 401 at
// the same moment is exactly the race the lock is for.
func Renew(ctx context.Context, options SessionOptions, refused string) (credentials.Credentials, error) {
	return accessToken(ctx, options, refused)
}

// accessToken is both of the above. An empty `refused` means the ordinary path, where the stored
// expiry is worth believing.
func accessToken(ctx context.Context, options SessionOptions, refused string) (credentials.Credentials, error) {
	current, err := signedIn(options)
	if err != nil {
		return credentials.Credentials{}, err
	}
	// **A key is never renewed, and the date stored beside it is never believed.**
	//
	// Two rules with one reason: the server is the authority on whether a key still works, and
	// anything this machine concludes about it is a guess whose cost is a wrong message. A key that
	// expired a minute ago would be judged "spent" here and reported as a credential that cannot be
	// renewed — a sentence about a login that never happened, and not what to do. So a key is handed
	// back as it is, every time, and the server answers (ADR 0040 §决定 4,
	// and the note under it about why the local expiry is not a judgement).
	if apikey.IsKey(current.AccessToken) {
		return current, nil
	}

	// Already replaced before we looked, or simply still good: either way nothing to do.
	if current.AccessToken != refused && usable(current, time.Now()) {
		return current, nil
	}

	// The lock is an optimisation around a race the server already survives (ADR 0024's grace
	// window), so failing to take it is a warning rather than a stop. Waiting forever would be
	// worse: a lock left behind by a killed process would make every later command hang.
	release, lockErr := credentials.NewLock(options.Dir, options.Server).Acquire(lockWait)
	if lockErr != nil && options.Warn != nil {
		options.Warn(fmt.Sprintf("%v — 继续刷新，可能与该锁的持有者同时进行", lockErr))
	}
	defer release()

	// Read again now that we hold the lock: whoever had it was most likely refreshing this same
	// credential, and their result is in the store already.
	again, err := signedIn(options)
	if err != nil {
		return credentials.Credentials{}, err
	}
	if again.AccessToken != refused && usable(again, time.Now()) {
		return again, nil
	}

	// Checked before anything is asked of the server, because there is nothing to ask with. What
	// reaches here is a **leftover**: the unattended login v3 removed wrote credentials with no
	// refresh token, and one of those can still be sitting in a keychain. (A key never gets this far
	// — it is handed back untouched, and the server decides whether it still works.) Failing here
	// names the reason and the command that fixes it, rather than sending an empty `refresh_token`
	// and translating whatever the server says about it.
	if again.RefreshToken == "" {
		return credentials.Credentials{}, ErrNotRenewable
	}

	client := options.HTTPClient
	if client == nil {
		client = http.DefaultClient
	}
	metadata, err := Discover(ctx, client, options.Server)
	if err != nil {
		return credentials.Credentials{}, err
	}

	tokens, err := Refresh(ctx, client, metadata.TokenEndpoint, again.ClientID, again.RefreshToken)
	if errors.Is(err, ErrAuthorizationRevoked) {
		// The design asks for this to be handled rather than reported: the machine that was signed
		// out from elsewhere has a file on disk that will keep producing the same refusal, so it is
		// deleted here and the message the person sees is about signing in again.
		if deleteErr := options.Store.Delete(); deleteErr != nil && options.Warn != nil {
			options.Warn(fmt.Sprintf("删除本地凭据失败：%v", deleteErr))
		}
		return credentials.Credentials{}, ErrAuthorizationRevoked
	}
	if err != nil {
		return credentials.Credentials{}, err
	}

	updated := credentials.Credentials{
		ClientID:     again.ClientID,
		AccessToken:  tokens.AccessToken,
		RefreshToken: tokens.RefreshToken,
		ExpiresAt:    tokens.ExpiresAt,
	}
	// A refresh that came back without a new refresh token would leave this one rotating on
	// nothing. Kept rather than blanked, because the old value is at least a working token until
	// the server decides otherwise — but the server rotates, so this should not happen.
	if updated.RefreshToken == "" {
		updated.RefreshToken = again.RefreshToken
	}
	if err := options.Store.Save(updated); err != nil {
		return credentials.Credentials{}, fmt.Errorf("storing the refreshed credential: %w", err)
	}
	return updated, nil
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
// **The refresh token is the only thing ever revoked here.** It is the long-lived half, and the
// server takes the whole authorization down with it — including the access token, which would
// otherwise keep working for up to an hour. There used to be a second branch, relapsing to the access
// token for an unattended credential; that shape is gone and so is the branch — nothing this CLI can
// store today lacks a refresh token except a key, and a key has no authorization behind it to end
// (its caller reports that instead of calling here).
func RevokeAuthorization(ctx context.Context, options RevokeOptions) error {
	toRevoke := options.Credential.RefreshToken
	if toRevoke == "" {
		// Nothing to present. Not an error: a credential that is already gone is the state a caller
		// asks for.
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

func signedIn(options SessionOptions) (credentials.Credentials, error) {
	stored, found, err := options.Store.Load()
	if err != nil {
		return credentials.Credentials{}, err
	}
	if !found {
		return credentials.Credentials{}, fmt.Errorf("%w：先运行 skillmaster login", ErrNotSignedIn)
	}
	return stored, nil
}

func usable(c credentials.Credentials, now time.Time) bool {
	return c.AccessToken != "" && now.Add(expirySkew).Before(c.ExpiresAt)
}

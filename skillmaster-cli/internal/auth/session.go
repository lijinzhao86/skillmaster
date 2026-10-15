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
// Not a fault of the credential: an unattended login (`--client-credentials`) is issued **no
// refresh token at all**, because the grant has none to give (ADR 0022). So when its hour runs out
// there is nothing here to renew with, and the only way forward is to sign in again — with the same
// secret, from the same environment variables.
//
// Said here rather than discovered from the server, which answers an empty `refresh_token` with a
// bare `invalid_client` and no hint that the credential was never renewable in the first place.
var ErrNotRenewable = errors.New(
	"这份凭据没有刷新令牌（无人值守登录就是这样），不能续期；请重新运行 skillmaster login --client-credentials")

// ErrRevocationNeedsSecret is what revoking gets when the credential is an unattended one and no
// client secret came with it.
//
// Returned without a message of its own because naming the environment variable is the caller's
// job — the variable is a configuration fact, and this package deliberately does not know how the
// CLI is configured (it is handed a store and two strings, not a config).
var ErrRevocationNeedsSecret = errors.New("revoking an unattended credential needs its client secret")

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
// this struct could get wrong is a caller passing *its* idea of the client id instead. The CLI has
// two: the browser login's compiled-in `skillmaster-cli`, and whatever an unattended login was
// seeded as. Sending the first for a credential minted for the second is an `invalid_client` from
// the server, at the one moment the person is already being told something went wrong.
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
// gone, and the credential has been deleted) or `ErrNotRenewable` (unattended, sign in again).
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
	// renewed — a sentence about `--client-credentials`, which is not what happened and not what to
	// do. So a key is handed back as it is, every time, and the server answers (ADR 0040 §决定 4,
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

	// Checked before anything is asked of the server, because there is nothing to ask with: an
	// unattended credential is issued no refresh token. Failing here names the reason and the
	// command that fixes it, rather than sending an empty `refresh_token` and translating whatever
	// the server says about it.
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

	// Credential is the one whose authorization to end. Either half may carry it: the refresh token
	// is preferred, because the server takes the whole authorization down with it, and an access
	// token is what an unattended credential has instead of one.
	Credential credentials.Credentials

	// Secret is the client secret, and only an unattended credential has a use for it.
	//
	// **Only that half gets it.** A browser login is a public client: `client_id` in the form is the
	// whole of its authentication, and sending a secret would turn the request into one the framework
	// reads as a confidential client's and refuses. An unattended credential is the reverse — minted
	// by `client_credentials`, which cannot happen without a secret — so revoking it needs the same
	// secret, presented the same way, or the server answers `invalid_client` and rightly so.
	Secret string

	HTTPClient *http.Client
}

// RevokeAuthorization ends the authorization a credential belongs to, and leaves the store alone.
//
// **Leaving the store alone is the whole of its contract**, and it is why the delete is not here:
// its one caller is `login` ending the authorization it is *replacing*, by which time the credential
// in the store is already the new one. Clearing the local copy is `logout`'s job and only its job —
// and since v2 that command does nothing else (ADR 0038 §决定 7).
//
// The refresh token is what is revoked when there is one: it is the long-lived half, and the server
// takes the whole authorization down with it — including the access token, which would otherwise keep
// working for up to an hour. **When there is not one, the access token is revoked instead, and that
// is not a consolation prize**: an unattended credential has no refresh token to give, and skipping
// the revocation would leave a token working until it expired. The two branches differ in how the
// client proves itself as well as in what is revoked, which is the part that had to be found by
// trying it: revoking an access token with nothing but a `client_id` is refused, because the client
// that grant requires is a confidential one.
func RevokeAuthorization(ctx context.Context, options RevokeOptions) error {
	toRevoke, secret := options.Credential.RefreshToken, ""
	if toRevoke == "" {
		toRevoke, secret = options.Credential.AccessToken, options.Secret
		if toRevoke != "" && secret == "" {
			return ErrRevocationNeedsSecret
		}
	}
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
	return Revoke(ctx, client, metadata.RevocationEndpoint, options.Credential.ClientID, toRevoke, secret)
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

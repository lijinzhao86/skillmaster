package auth

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Tokens is what a token-endpoint call hands back, reduced to what this CLI stores.
type Tokens struct {
	AccessToken  string
	RefreshToken string
	ExpiresAt    time.Time
}

// ErrAuthorizationRevoked is a refresh the server refused because the authorization is gone.
//
// Its own error because the design requires it to be *translated* rather than shown: a stale CLI on
// another machine that gets `invalid_grant` has to say "your authorization was revoked, sign in
// again" and delete its own stored credential. A bare error code leaves somebody staring at
// `invalid_grant` with a file on disk that will keep producing it.
//
// **The message is what the person reads, so it says what to do.** It used to be the English phrase
// "the authorization was revoked", which is a translation of the code and no help at all: every other
// message this CLI prints is Chinese and names the next command. Three things end up here — a
// password reset, a logout that revoked this chain, and a refresh token replayed outside the grace
// window (ADR 0024) — and all three have the same answer.
var ErrAuthorizationRevoked = errors.New("授权已被撤销，需要重新登录：skillmaster login")

// tokenTimeout bounds every token-endpoint call. Generous enough for a cold server, short enough
// that a black-holed connection does not hang a command indefinitely.
const tokenTimeout = 30 * time.Second

// ExchangeCode redeems an authorization code, proving the proof key.
//
// No client secret is sent and none exists: this is a public client (RFC 8252 §8.5), and the
// `code_verifier` is what stands in for one.
func ExchangeCode(ctx context.Context, client *http.Client, endpoint, clientID, code, verifier,
	redirectURI string) (Tokens, error) {
	return postToken(ctx, client, endpoint, url.Values{
		"grant_type":    {"authorization_code"},
		"code":          {code},
		"redirect_uri":  {redirectURI},
		"client_id":     {clientID},
		"code_verifier": {verifier},
	})
}

// Refresh rotates the refresh token.
//
// The response carries a **new** refresh token and the old one stops working (ADR 0024), so the
// caller must store what comes back rather than keeping what it sent. Discarding it is the one
// mistake here that has a delayed symptom: the next refresh presents a spent token, which the
// server reads as a replay and answers by revoking the whole chain.
func Refresh(ctx context.Context, client *http.Client, endpoint, clientID, refreshToken string) (Tokens, error) {
	tokens, err := postToken(ctx, client, endpoint, url.Values{
		"grant_type":    {"refresh_token"},
		"refresh_token": {refreshToken},
		"client_id":     {clientID},
	})
	if errors.Is(err, errInvalidGrant) {
		return Tokens{}, ErrAuthorizationRevoked
	}
	return tokens, err
}

// Revoke gives up a token, per RFC 7009.
//
// **Reached only from `RevokeAuthorization`, whose one caller is `login` ending the authorization it
// is replacing** — so this is not how a person gives up a credential. `logout` does not come through
// here at all: since v2 it deletes the local copy and nothing else, and then says where the server's
// copy actually ends (ADR 0038 §决定 7, `whereToRevoke`).
//
// A token that is already invalid answers 200, so this is idempotent — revoking twice is not an
// error. So is revoking a token the server has never seen: RFC 7009 asks for the same answer either
// way, because distinguishing them tells a caller whether a token it holds is real.
//
// **No client authentication, and there is none left to send.** This CLI holds only public clients
// now: a browser login authenticates with `client_id` plus PKCE, and a key is not revoked here at all
// (ADR 0038 §决定 7). The confidential shape — a secret in an `Authorization: Basic` header — existed
// for `--client-credentials`, and it was deleted with it rather than kept as a parameter nothing can
// fill.
func Revoke(ctx context.Context, client *http.Client, endpoint, clientID, token string) error {
	ctx, cancel := context.WithTimeout(ctx, tokenTimeout)
	defer cancel()

	// `client_id` is always in the form, and it is the whole of the client's authentication: every
	// client this CLI holds is public and proves itself with PKCE, so there is no secret and nothing
	// else for the server to learn who is asking by. The Basic header went with the confidential
	// shape `--client-credentials` needed.
	form := url.Values{"token": {token}, "client_id": {clientID}}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint,
		strings.NewReader(form.Encode()))
	if err != nil {
		return fmt.Errorf("building the revocation request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("revoking at %s: %w", endpoint, err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<16))

	if resp.StatusCode == http.StatusOK {
		return nil
	}
	var failure struct {
		Error       string `json:"error"`
		Description string `json:"error_description"`
	}
	_ = json.Unmarshal(body, &failure)
	if failure.Error == "" {
		failure.Error = fmt.Sprintf("HTTP %d", resp.StatusCode)
	}
	return fmt.Errorf("the server refused to revoke the token: %s %s",
		failure.Error, failure.Description)
}

// errInvalidGrant is internal: callers get ErrAuthorizationRevoked, which says what to do about it.
var errInvalidGrant = errors.New("invalid_grant")

// postToken makes the call. **Every grant this client uses belongs to a public client**, so there is
// no `Authorization` header to set: `client_id` in the form is the whole of its authentication
// (RFC 8252 §8.5). The confidential path — a secret in a Basic header — belonged to
// `--client-credentials` and went with it in v3.
func postToken(ctx context.Context, client *http.Client, endpoint string, form url.Values) (Tokens, error) {
	ctx, cancel := context.WithTimeout(ctx, tokenTimeout)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint,
		strings.NewReader(form.Encode()))
	if err != nil {
		return Tokens{}, fmt.Errorf("building the token request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return Tokens{}, fmt.Errorf("calling %s: %w", endpoint, err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<16))
	if err != nil {
		return Tokens{}, fmt.Errorf("reading the response from %s: %w", endpoint, err)
	}

	var payload struct {
		AccessToken      string `json:"access_token"`
		RefreshToken     string `json:"refresh_token"`
		ExpiresIn        int    `json:"expires_in"`
		Error            string `json:"error"`
		ErrorDescription string `json:"error_description"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		return Tokens{}, fmt.Errorf("%s answered %d and not a token response: %s",
			endpoint, resp.StatusCode, summarise(body))
	}

	if payload.Error != "" {
		if payload.Error == "invalid_grant" {
			return Tokens{}, fmt.Errorf("%w: %s", errInvalidGrant, payload.ErrorDescription)
		}
		return Tokens{}, fmt.Errorf("the server refused the request: %s %s",
			payload.Error, payload.ErrorDescription)
	}
	if resp.StatusCode != http.StatusOK {
		return Tokens{}, fmt.Errorf("%s answered %d: %s", endpoint, resp.StatusCode, summarise(body))
	}
	if payload.AccessToken == "" {
		return Tokens{}, fmt.Errorf("%s answered without an access token", endpoint)
	}
	// Refused rather than defaulted. A token whose lifetime is unknown is a token this CLI would
	// have to guess about, and the guess that fails is the one that keeps a dead token in a file
	// until some other command reports a 401 with no explanation.
	if payload.ExpiresIn <= 0 {
		return Tokens{}, fmt.Errorf("%s did not say how long the access token lasts", endpoint)
	}

	return Tokens{
		AccessToken:  payload.AccessToken,
		RefreshToken: payload.RefreshToken,
		ExpiresAt:    time.Now().Add(time.Duration(payload.ExpiresIn) * time.Second),
	}, nil
}

package auth

import (
	"context"
	"encoding/base64"
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
	}, "")
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
	}, "")
	if errors.Is(err, errInvalidGrant) {
		return Tokens{}, ErrAuthorizationRevoked
	}
	return tokens, err
}

// ClientCredentials gets a token with no person present (ADR 0022).
//
// A confidential client, so it does have a secret — which is why this is not the path a workstation
// takes. It exists for CI, where the machine is the deployment and the secret is a pipeline secret.
//
// **The secret goes in an `Authorization: Basic` header, never in the form.** Those are two
// different registered authentication methods — `client_secret_basic` and `client_secret_post` — and
// the server registers the first one; a form-encoded secret is refused with `invalid_client`, which
// reads like a wrong secret rather than a secret in the wrong place. Basic also keeps the secret out
// of the request body, where it would be the thing a proxy logs.
func ClientCredentials(ctx context.Context, client *http.Client, endpoint, clientID, secret string,
	scopes []string) (Tokens, error) {
	form := url.Values{"grant_type": {"client_credentials"}}
	if len(scopes) > 0 {
		form.Set("scope", strings.Join(scopes, " "))
	}
	return postToken(ctx, client, endpoint, form, basicAuth(clientID, secret))
}

// basicAuth is the `Authorization` value for `client_secret_basic`.
//
// One function because there are now two callers and the mistake it prevents is invisible: a secret
// in the form instead of the header is `client_secret_post`, a *different* registered method, and
// this server registers only the first — so it answers `invalid_client`, which reads like a wrong
// secret rather than a secret in the wrong place.
func basicAuth(clientID, secret string) string {
	return "Basic " + base64.StdEncoding.EncodeToString([]byte(clientID+":"+secret))
}

// Revoke gives up a token, per RFC 7009.
//
// Called by `logout` **before** the local credential is deleted, and the order is the whole point
// of doing it here: once the local copy is gone there is nothing left to present, and the server's
// copy stays live until it expires. `logout` that only deleted the file would look identical and
// leave a working credential behind.
//
// A token that is already invalid answers 200, so this is idempotent — logging out twice is not an
// error. So is revoking a token the server has never seen: RFC 7009 asks for the same answer either
// way, because distinguishing them tells a caller whether a token it holds is real.
//
// **A secret, when there is one, goes in `Authorization: Basic`** — see {@link basicAuth}. Which of
// the two shapes this call takes is decided by the caller and not here: a browser credential has no
// secret at all, and an unattended one cannot be revoked without the secret it was minted with.
func Revoke(ctx context.Context, client *http.Client, endpoint, clientID, token string, secret string) error {
	ctx, cancel := context.WithTimeout(ctx, tokenTimeout)
	defer cancel()

	// `client_id` stays in the form even when Basic carries it: on the public path it is the whole
	// of the client's authentication, and there is no other way for the server to know who is asking.
	form := url.Values{"token": {token}, "client_id": {clientID}}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint,
		strings.NewReader(form.Encode()))
	if err != nil {
		return fmt.Errorf("building the revocation request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	if secret != "" {
		req.Header.Set("Authorization", basicAuth(clientID, secret))
	}

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

// postToken makes the call. `authorization` is empty for the two grants a public client uses — it
// has no credentials to present — and carries the Basic header for the one that does.
func postToken(ctx context.Context, client *http.Client, endpoint string, form url.Values,
	authorization string) (Tokens, error) {
	ctx, cancel := context.WithTimeout(ctx, tokenTimeout)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint,
		strings.NewReader(form.Encode()))
	if err != nil {
		return Tokens{}, fmt.Errorf("building the token request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	if authorization != "" {
		req.Header.Set("Authorization", authorization)
	}

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

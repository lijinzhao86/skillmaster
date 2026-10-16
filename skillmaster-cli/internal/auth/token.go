package auth

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// tokenTimeout bounds every one-shot call this package makes to the server's own endpoints: the
// exchange that hands back a credential, and the revocation that gives one up. Generous enough for a
// cold server, short enough that a black-holed connection does not hang a command indefinitely.
const tokenTimeout = 30 * time.Second

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
// now: a login authenticates with `client_id` plus PKCE, and a key is not revoked here at all
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

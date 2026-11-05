package api

import "context"

// Logout ends the CLI login this token belongs to (v46, ADR 0142).
//
// **The request carries no id, and that is the contract rather than a shortcut.** The server reads
// the authorization off the presented token's own row, so this credential can only end itself; one
// machine cannot name another's login even if it tried. The command is idempotent in the only sense
// that matters: reaching it again with a credential whose login is already over is answered as
// already over (`token_revoked`), which the caller reads as the state it wanted rather than as a
// failure.
//
// **An API key is not sent here.** It has no login behind it, and the server answers 400; the command
// checks the credential's shape first and leaves keys to the console (ADR 0038 §决定 7).
func (c Client) Logout(ctx context.Context) error {
	// An empty object rather than an empty body: this plane's writes are JSON, and a body of nothing
	// is a request some proxies rewrite. The server reads no field from it.
	_, err := c.postJSON(ctx, APIPrefix+"/logout", []byte("{}"))
	return err
}

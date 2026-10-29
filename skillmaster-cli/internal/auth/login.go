package auth

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/pkce"
)

// DefaultScopes is what `login` asks for.
//
// Both of them, and asked for together on purpose: a consent screen that appears again the first
// time somebody publishes would be a second interruption for one decision. The person agrees once
// to what the tool may do in their name, and the page says what that is.
var DefaultScopes = []string{"skills:read", "skills:write"}

// SignInPath is the page the browser is sent to, under the server's issuer.
//
// **The address is the issuer and this path, and no new metadata field was added for it.** The
// issuer already names the site the server serves its own pages at — it is the same value the server
// builds its `/login` page from — so a second field meaning "where the pages are" would be a second
// thing to keep in step with the first, and the day they disagreed a login would open a 404.
const SignInPath = "/cli-sign-in"

// ExchangePath redeems the one-time code for the credential itself.
//
// It is the one route on this server that a caller with no session writes to, which is why its
// address is not in the discovery document either: the document describes the authorization server's
// surface, and this is the first party's own door (v4 Q4, Q5).
const ExchangePath = "/cli-sign-ins/exchange"

// callbackTimeout bounds the wait for the browser. Long enough to find a password manager, short
// enough that a person who closed the tab gets the terminal back.
const callbackTimeout = 5 * time.Minute

// Credential is what a login earns: the credential itself, once.
//
// **There is no refresh token in it, and the absence is the version** (v4 做 #2). What this login
// produces is a login: the server slides its expiry on every use, it shows up on the console's
// 「登录设备」 screen, and a person ends it there. Nothing renews it — not this machine, and not the
// server — so the way to a longer-lived credential is a different shape rather than a longer life.
type Credential struct {
	// Name is what the person called this machine on the sign-in page, and it is the console's name
	// for the row they will see in 「登录设备」 (`CLI · <name>`).
	//
	// **It travels with the credential because this is the only place a client can learn it.** The
	// person typed it, so the server holds it; the process that redeems the code has never seen it,
	// and nothing else it can ask carries it (`/me`'s key half is null for anything that is not an API
	// key). A client not told here would have to invent one, which is why a login with no name beside
	// it is refused rather than printed as something made up.
	Name        string
	AccessToken string
	// ExpiresAt is the **earliest** moment this could end, not the moment it will.
	//
	// The server sends the idle ruler as it stood at issuance, and every use pushes it forward (up to
	// the absolute ceiling). So the honest reading is "at least until here" — and the CLI says it that
	// way wherever it prints it, because a date presented as a fixed end would be the false half of a
	// true number.
	ExpiresAt time.Time
	// Scopes is what this sign-in was actually granted, which is not always what was asked for: the
	// server intersects the request with what the client is registered for. It is where the answer to
	// "what may this credential do" lives — a token's scopes are not in `/me`, whose key half is null
	// for anything that is not an API key.
	Scopes []string
}

// LoginOptions is everything `login` needs that is not the machine it is running on.
type LoginOptions struct {
	Server     string
	ClientID   string
	Port       int
	Scopes     []string
	HTTPClient *http.Client

	// MachineName is what this machine will be called on 「登录设备」, and how the page's 名称 field
	// starts. Empty means "read it off this machine", which is what a real run does (`MachineName`);
	// a test sets it, because the answer otherwise depends on the computer the tests are running on.
	MachineName string

	// OnURL is handed the sign-in URL before anything is opened. It is how a headless run tells
	// somebody where to go, and how a test drives the flow without a browser.
	OnURL func(string)

	// OpenURL launches the browser. A failure is reported and survived: the URL has already been
	// printed, so somebody on a machine with no browser can paste it themselves.
	OpenURL func(string) error
}

// Login runs the sign-in this version has, and returns the credential it earned.
//
// The order of the four steps is the part worth reading: discover, then **listen**, then open the
// browser, then wait. Listening before opening is not tidiness — a redirect that arrives before
// anything is bound is lost for good, and the symptom is a timeout with no explanation.
//
// **What the browser is given is the sign-in page, not an authorization request**, and the parameters
// it carries are the whole of what that page reads: the client, the state, the scopes, the PKCE
// challenge, and the name of this machine. Two things are deliberately absent:
//
//   - **No `redirect_uri`.** The server reads it from the client's own registered row, and letting a
//     request choose where a one-time code is delivered would turn that row into a suggestion. The
//     loopback address is fixed (ADR 0028), so the CLI only has to listen where the registration says
//     it will.
//   - **No `code_challenge_method`.** S256 is the only method this path has, and the challenge has to
//     travel with the address because the code is created with it — the page posts it back, and by
//     then the code exists.
//
// The challenge is what stands in for a client secret at the exchange: this is a public client
// (RFC 8252 §8.5), and the code comes back through a loopback redirect that anything on the machine
// could watch.
func Login(ctx context.Context, options LoginOptions) (Credential, error) {
	client := options.HTTPClient
	if client == nil {
		client = http.DefaultClient
	}
	scopes := options.Scopes
	if len(scopes) == 0 {
		scopes = DefaultScopes
	}

	metadata, err := Discover(ctx, client, options.Server)
	if err != nil {
		return Credential{}, err
	}

	verifier, err := pkce.NewVerifier()
	if err != nil {
		return Credential{}, fmt.Errorf("generating the proof key: %w", err)
	}
	state, err := newState()
	if err != nil {
		return Credential{}, err
	}

	callback, err := Listen(options.Port, state)
	if err != nil {
		return Credential{}, err
	}
	defer callback.Close()

	// **The machine's own name rides along on the address.** Of the five parameters this is the only
	// one the page could not have been given any other way, and the only one that is not the server's
	// business: `name=` is the initial value of a text field the person can still correct, and what
	// ends up written down is whatever they submit (`v4 §接口 ①`, `design/README.md`
	// §怎么打开「授权请求」). It is set only when there is one — an address with no `name` is the empty
	// box, which is a state the page already draws.
	parameters := url.Values{
		"client_id":      {options.ClientID},
		"state":          {state},
		"scope":          {strings.Join(scopes, " ")},
		"code_challenge": {pkce.Challenge(verifier)},
	}
	name := options.MachineName
	if name == "" {
		name = MachineName()
	}
	if name != "" {
		parameters.Set("name", name)
	}

	// The trailing slash comes off before the path goes on: the issuer is a base URL and a deployment
	// may well spell it either way, and `//cli-sign-in` is a different address on the wire.
	signInURL := strings.TrimRight(metadata.Issuer, "/") + SignInPath + "?" + parameters.Encode()

	if options.OnURL != nil {
		options.OnURL(signInURL)
	}
	if options.OpenURL != nil {
		// Reported, not fatal. Somewhere without a browser — a container, an SSH session — the
		// printed URL is the whole mechanism, and refusing to continue would take that away.
		if openErr := options.OpenURL(signInURL); openErr != nil {
			// A warning, not the command's answer: stdout may be a machine contract.
			fmt.Fprintf(os.Stderr, "不能自动打开浏览器（%v）。请手动打开上面的链接。\n", openErr)
		}
	}

	code, err := callback.Wait(callbackTimeout)
	if err != nil {
		return Credential{}, err
	}
	return exchange(ctx, client, options.Server, code, verifier)
}

// exchange redeems the code for the credential itself, proving the proof key.
//
// **No client authentication is sent, and none exists**: the verifier is what stands in for a secret
// here (RFC 8252 §8.5), and it is the only thing that ties the caller to the process that asked for
// the code. The body's two names are snake_case because that is this server's global wire convention
// (`/web/sessions` sends `device_id` the same way).
func exchange(ctx context.Context, client *http.Client, server, code, verifier string) (Credential, error) {
	ctx, cancel := context.WithTimeout(ctx, tokenTimeout)
	defer cancel()

	payload, err := json.Marshal(map[string]string{"code": code, "code_verifier": verifier})
	if err != nil {
		return Credential{}, fmt.Errorf("building the exchange request: %w", err)
	}
	endpoint := server + ExchangePath
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(payload))
	if err != nil {
		return Credential{}, fmt.Errorf("building the exchange request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return Credential{}, fmt.Errorf("calling %s: %w", endpoint, err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<16))
	if err != nil {
		return Credential{}, fmt.Errorf("reading the response from %s: %w", endpoint, err)
	}

	switch resp.StatusCode {
	case http.StatusNotFound:
		// **One answer for four ways a code can be unusable** — no such code, an expired one, a spent
		// one, and a verifier that did not match — and the design asks for exactly that: telling them
		// apart would also tell somebody holding a random code whether it ever existed. The CLI's
		// sentence therefore names all four rather than guessing which one this was.
		return Credential{}, errors.New("这个登录码不能用：它可能已经过期、已经用过，或者不是这次登录的。" +
			"重新运行 skmr login，再点一次。")
	case http.StatusTooManyRequests:
		// The one public write this server has carries a limit of its own, keyed on the address rather
		// than on the code (a limit that answered differently for a code that exists would be a better
		// existence probe than the status code is willing to be). Said as itself: 「兑换失败」 would
		// send somebody looking at their own machine for a problem that is not there.
		return Credential{}, fmt.Errorf("服务端限流了这次兑换，%s后再试一次——这不是凭据的问题",
			retryAfter(resp.Header.Get("Retry-After")))
	}
	if resp.StatusCode != http.StatusOK {
		return Credential{}, fmt.Errorf("%s answered %d: %s", endpoint, resp.StatusCode, summarise(body))
	}

	var answer struct {
		AccessToken string `json:"access_token"`
		ExpiresAt   string `json:"expires_at"`
		Scope       string `json:"scope"`
		Name        string `json:"name"`
	}
	if err := json.Unmarshal(body, &answer); err != nil {
		return Credential{}, fmt.Errorf("%s answered %d and not a credential: %s",
			endpoint, resp.StatusCode, summarise(body))
	}
	// **Three fields are refused rather than defaulted, the way the token endpoint's `expires_in`
	// was**: they are what the CLI stores and prints, and a guessed one is a store that keeps a dead
	// credential with a date beside it that is not true. The name is the third for one more reason —
	// **a client that was not told it cannot work one out**, so the alternatives are an invented name
	// or a silent blank, and both are worse than saying the answer was not a credential.
	if answer.AccessToken == "" {
		return Credential{}, fmt.Errorf("%s answered without an access token", endpoint)
	}
	if answer.Name == "" {
		return Credential{}, fmt.Errorf("%s answered without a name for the machine —"+
			" a credential this CLI did not ask for, or a server from a version that does not say it",
			endpoint)
	}
	if answer.ExpiresAt == "" {
		return Credential{}, fmt.Errorf("%s did not say when the credential ends", endpoint)
	}
	expiresAt, err := time.Parse(time.RFC3339, answer.ExpiresAt)
	if err != nil {
		return Credential{}, fmt.Errorf("%s sent an expiry this CLI cannot read (%q): %w",
			endpoint, answer.ExpiresAt, err)
	}

	return Credential{
		Name:        answer.Name,
		AccessToken: answer.AccessToken,
		ExpiresAt:   expiresAt,
		Scopes:      strings.Fields(answer.Scope),
	}, nil
}

// retryAfter renders the header the server sends with its 429, and says "稍后" when it is not there.
//
// Parsed rather than printed raw because it is a number of seconds, and the sentence that carries it
// is about waiting: 「Retry-After: 30」 in the middle of a Chinese sentence is a header quoted at
// somebody. A value that is not a whole number of seconds — an HTTP-date, which RFC 9110 also allows
// — is not worth assembling a clock out of, and the sentence still stands without it.
func retryAfter(header string) string {
	if seconds, err := strconv.Atoi(strings.TrimSpace(header)); err == nil && seconds > 0 {
		return fmt.Sprintf("%d 秒", seconds)
	}
	return "稍后"
}

// newState is the value the callback is checked against, and it is a credential for that one
// round-trip: the check is what stops somebody handing this CLI their own authorization code.
func newState() (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", fmt.Errorf("generating the state: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(buf), nil
}

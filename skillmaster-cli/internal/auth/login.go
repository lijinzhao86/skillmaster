package auth

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"net/http"
	"net/url"
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

// callbackTimeout bounds the wait for the browser. Long enough to find a password manager, short
// enough that a person who closed the tab gets the terminal back.
const callbackTimeout = 5 * time.Minute

// LoginOptions is everything `login` needs that is not the machine it is running on.
type LoginOptions struct {
	Server     string
	ClientID   string
	Port       int
	Scopes     []string
	HTTPClient *http.Client

	// OnURL is handed the authorization URL before anything is opened. It is how a headless run
	// tells somebody where to go, and how a test drives the flow without a browser.
	OnURL func(string)

	// OpenURL launches the browser. A failure is reported and survived: the URL has already been
	// printed, so somebody on a machine with no browser can paste it themselves.
	OpenURL func(string) error
}

// Login runs the authorization code flow with PKCE and returns the credential it earned.
//
// The order of the four steps is the part worth reading: discover, then **listen**, then open the
// browser, then wait. Listening before opening is not tidiness — a redirect that arrives before
// anything is bound is lost for good, and the symptom is a timeout with no explanation.
func Login(ctx context.Context, options LoginOptions) (Tokens, error) {
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
		return Tokens{}, err
	}

	verifier, err := pkce.NewVerifier()
	if err != nil {
		return Tokens{}, fmt.Errorf("generating the proof key: %w", err)
	}
	state, err := newState()
	if err != nil {
		return Tokens{}, err
	}

	redirectURI := fmt.Sprintf("http://127.0.0.1:%d/callback", options.Port)
	callback, err := Listen(options.Port, state)
	if err != nil {
		return Tokens{}, err
	}
	defer callback.Close()

	authorizationURL := metadata.AuthorizationEndpoint + "?" + url.Values{
		"response_type":         {"code"},
		"client_id":             {options.ClientID},
		"redirect_uri":          {redirectURI},
		"scope":                 {strings.Join(scopes, " ")},
		"state":                 {state},
		"code_challenge":        {pkce.Challenge(verifier)},
		"code_challenge_method": {"S256"},
	}.Encode()

	if options.OnURL != nil {
		options.OnURL(authorizationURL)
	}
	if options.OpenURL != nil {
		// Reported, not fatal. Somewhere without a browser — a container, an SSH session — the
		// printed URL is the whole mechanism, and refusing to continue would take that away.
		if openErr := options.OpenURL(authorizationURL); openErr != nil {
			fmt.Printf("不能自动打开浏览器（%v）。请手动打开上面的链接。\n", openErr)
		}
	}

	code, err := callback.Wait(callbackTimeout)
	if err != nil {
		return Tokens{}, err
	}
	return ExchangeCode(ctx, client, metadata.TokenEndpoint, options.ClientID, code, verifier, redirectURI)
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

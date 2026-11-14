package auth

import (
	"bytes"
	"context"
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

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/pkce"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/sandbox"
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

// DeviceSignInsPath is where a question is asked, and where its answer is collected (v68).
//
// **It is on the platform's own surface, not the authorization server's**, which is why it is not in
// the discovery document either: the document describes the OAuth endpoints, and this is the first
// party's own door. The issuer tells the CLI where that door is.
const DeviceSignInsPath = "/cli-device-sign-ins"

// The default lifetime and poll interval, used when a server does not say.
//
// The server always sends both; these are the numbers the person would get anyway, kept so that a
// missing field is a working login rather than an immediate expiry.
const (
	defaultLifetime = 10 * time.Minute
	defaultInterval = time.Second
)

// LoginFailedError marks a failure that belongs to the login itself — a link that ran out or was
// already answered, an answer that was not a credential — as opposed to the connection failing.
//
// **The distinction is for scripts.** `login_failed` and `network` ask for different next steps, and
// a machine that reads only the code should not retry a link that is gone.
type LoginFailedError struct{ Err error }

func (e *LoginFailedError) Error() string { return e.Err.Error() }

func (e *LoginFailedError) Unwrap() error { return e.Err }

func loginFailed(err error) error { return &LoginFailedError{Err: err} }

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
	// person typed it, so the server holds it; the process that collects the credential has never
	// seen it, and nothing else it can ask carries it (`/me`'s key half is null for anything that is
	// not an API key). A client not told here would have to invent one, which is why a login with no
	// name beside it is refused rather than printed as something made up.
	Name string
	// Username is the account the credential belongs to (v46). The browser screen writes the account
	// out before consent; this is the terminal's half of the same answer, so a person who authorized
	// as the wrong account finds out here rather than in the console later.
	//
	// **Empty is allowed and is not an error.** A server from before this field simply does not send
	// it, and the login is still perfectly good; the report omits the sentence rather than refusing a
	// credential it can use.
	Username    string
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
	Scopes     []string
	HTTPClient *http.Client

	// MachineName is what this machine will be called on 「登录设备」, and how the page's 名称 field
	// starts. Empty means "read it off this machine", which is what a real run does (`MachineName`);
	// a test sets it, because the answer otherwise depends on the computer the tests are running on.
	MachineName string

	// PollInterval overrides the wait the server asks for between polls. Zero means "use what the
	// server said", which is what a real run does; a test sets it so a pending answer does not cost a
	// second of wall-clock.
	PollInterval time.Duration

	// OnURL is handed the sign-in URL before anything is opened. It is how a headless run tells
	// somebody where to go, and how a test drives the flow without a browser.
	OnURL func(string)

	// OpenURL launches the browser. A failure is reported and survived: the URL has already been
	// printed, so somebody on a machine with no browser can open it elsewhere — including on their
	// phone, which is the whole point of v68. The caller leaves this nil inside a sandbox, where
	// opening would only produce an error and a wait nobody can end.
	OpenURL func(string) error
}

// Login asks for a question, shows the link, and collects the credential once somebody approves it.
//
// **The three steps are separate on purpose.**
//
//   - **Ask.** An anonymous POST creates one short-lived row. It carries the PKCE challenge and the
//     name this machine suggests; the answer carries the ticket that goes in the link and the device
//     secret that never leaves this process.
//   - **Show.** The link is printed, and the browser is opened as a best effort. **Nothing waits on
//     this machine's browser any more** (v68): the link can be opened on a phone, in another
//     machine's browser, anywhere — which is what makes a container or a cloud box a place a person
//     can log in from at all.
//   - **Collect.** The CLI asks, once a second, with the device secret and the PKCE verifier. The
//     first answer that is not "pending" is the end: a credential, a refusal, or a link that ran out.
//
// **The link is worth nothing on its own.** The ticket in it is public by construction — it sits in
// a URL — so the credential is released only to a caller that can present the secret handed out at
// creation plus the verifier that produced the challenge. That is also why a ticket cannot be
// replayed into a credential by whoever forwards the link: approving it only writes the
// authorization row, and the row's token is handed to this process on the next poll.
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

	// **The machine's own name goes with the request, not on the link.** The page starts its 名称 box
	// with this value and the person can still correct it; what ends up on 「登录设备」 is whatever
	// they submit. Sending it here rather than in the address keeps the link to the one thing it
	// needs — the ticket.
	name := options.MachineName
	if name == "" {
		name = MachineName()
	}

	started, err := start(ctx, client, options.Server, deviceStart{
		clientID:      options.ClientID,
		name:          name,
		scopes:        scopes,
		codeChallenge: pkce.Challenge(verifier),
	})
	if err != nil {
		return Credential{}, err
	}

	// The trailing slash comes off before the path goes on: the issuer is a base URL and a deployment
	// may well spell it either way, and `//cli-sign-in` is a different address on the wire.
	signInURL := strings.TrimRight(metadata.Issuer, "/") + SignInPath +
		"?ticket=" + url.QueryEscape(started.ticket)

	if options.OnURL != nil {
		options.OnURL(signInURL)
	}
	if options.OpenURL != nil {
		// Reported, not fatal. Somewhere without a browser — a container, an SSH session, a cloud
		// sandbox — the printed link is the whole mechanism, and refusing to continue would take the
		// only way through away.
		if openErr := options.OpenURL(signInURL); openErr != nil {
			// A warning, not the command's answer: stdout may be a machine contract.
			fmt.Fprintf(os.Stderr, "不能自动打开浏览器（%v）。链接仍可用，在任意浏览器打开它就能继续。\n",
				openErr)
		}
	}

	return wait(ctx, client, options.Server, started, verifier, options.PollInterval)
}

// deviceStart is what this machine tells the server about the login it is asking for.
type deviceStart struct {
	clientID      string
	name          string
	scopes        []string
	codeChallenge string
}

// started is the server's answer: two secrets that go to two different places, and two clocks.
type started struct {
	// ticket goes in the link. Public by construction.
	ticket string
	// deviceSecret never leaves this process, and is half of what makes a poll the asking process's.
	deviceSecret string
	lifetime     time.Duration
	interval     time.Duration
}

// start creates the question.
//
// The body's names are snake_case because that is this server's global wire convention
// (`/web/sessions` sends `device_id` the same way).
func start(ctx context.Context, client *http.Client, server string,
	request deviceStart) (started, error) {
	ctx, cancel := context.WithTimeout(ctx, tokenTimeout)
	defer cancel()

	payload, err := json.Marshal(map[string]string{
		"client_id":      request.clientID,
		"name":           request.name,
		"scope":          strings.Join(request.scopes, " "),
		"code_challenge": request.codeChallenge,
	})
	if err != nil {
		return started{}, fmt.Errorf("building the sign-in request: %w", err)
	}
	endpoint := server + DeviceSignInsPath
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(payload))
	if err != nil {
		return started{}, fmt.Errorf("building the sign-in request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return started{}, sandbox.Wrap(fmt.Errorf("calling %s: %w", endpoint, err))
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<16))
	if err != nil {
		return started{}, fmt.Errorf("reading the response from %s: %w", endpoint, err)
	}

	if resp.StatusCode == http.StatusTooManyRequests {
		// Typed rather than a bare sentence, so `--json` names the code the help document promises:
		// `too_many_requests`, with the wait as the next step. Nothing about the person's machine is
		// wrong, and a generic `network` would send them looking for one.
		return started{}, &api.ServerError{
			Status:  http.StatusTooManyRequests,
			Code:    "too_many_requests",
			Message: fmt.Sprintf("服务端限流了这次登录，%s后再试一次", retryAfter(resp.Header.Get("Retry-After"))),
		}
	}
	if resp.StatusCode != http.StatusCreated {
		return started{}, fmt.Errorf("服务端没能发起这次登录（HTTP %d）：%s",
			resp.StatusCode, summarise(body))
	}

	var answer struct {
		Ticket       string `json:"ticket"`
		DeviceSecret string `json:"device_secret"`
		ExpiresIn    int64  `json:"expires_in"`
		Interval     int64  `json:"interval"`
	}
	if err := json.Unmarshal(body, &answer); err != nil {
		return started{}, fmt.Errorf("服务端的回答不是一次登录（HTTP %d）：%s",
			resp.StatusCode, summarise(body))
	}
	// **Both secrets are refused rather than defaulted.** A ticket with no secret is a question this
	// process could never collect, and the failure would arrive ten minutes later as a timeout with
	// no explanation of what actually went wrong.
	if answer.Ticket == "" {
		return started{}, errors.New("服务端没有发来 ticket——服务端可能比这个 CLI 旧")
	}
	if answer.DeviceSecret == "" {
		return started{}, errors.New("服务端没有发来 device_secret——这次登录收回不了，服务端可能比这个 CLI 旧")
	}

	result := started{
		ticket:       answer.Ticket,
		deviceSecret: answer.DeviceSecret,
		lifetime:     defaultLifetime,
		interval:     defaultInterval,
	}
	if answer.ExpiresIn > 0 {
		result.lifetime = time.Duration(answer.ExpiresIn) * time.Second
	}
	if answer.Interval > 0 {
		result.interval = time.Duration(answer.Interval) * time.Second
	}
	return result, nil
}

// wait asks until the question is answered, and returns what the answer earned.
//
// **It stops at the first answer that is not "pending"**, which is the whole of the protocol on this
// side: the server owns every state, and this loop owns only the asking. A 429 is not a failure —
// the server is saying "not this often", so it waits as told and asks again; the app is the
// authority on its own limits (ADR 0040 §决定 4).
func wait(ctx context.Context, client *http.Client, server string, question started,
	verifier string, override time.Duration) (Credential, error) {
	interval := question.interval
	if override > 0 {
		interval = override
	}
	deadline := time.Now().Add(question.lifetime)

	for {
		status, credential, retry, err := poll(ctx, client, server, question, verifier)
		if err != nil {
			// **Ctrl-C can land while a poll is in flight**, and then the error is the cancellation
			// rather than a timeout from `sleep`. The question still has to be ended: this process is
			// the only one that knows the person gave up.
			if ctx.Err() != nil {
				cancel(client, server, question)
			}
			return Credential{}, err
		}
		switch status {
		case pollApproved:
			return credential, nil
		case pollPending:
			// Ask again below.
		case pollSlowDown:
			if retry > interval {
				interval = retry
			}
		case pollDenied, pollExpired, pollUsed:
			// **One sentence for three ends**, because from here they are the same fact: this link
			// is not a question any more, and the way forward is a new one. Which of the three it
			// was is the server's business, and telling a machine apart from a URL would say more
			// about a login than the link's holder should learn.
			return Credential{}, loginFailed(fmt.Errorf(
				"这次登录没有完成：链接被拒绝、已经用过，或者超过了 %s。"+
					"重新运行 skmr login，再点一次新链接。", humanDuration(question.lifetime)))
		}

		// **The last poll always happens.** A `Retry-After` from the server can be longer than what is
		// left of the question, and giving up on that basis would throw away a link that is still
		// valid — the deadline is when the asking stops, not a reason to stop asking early.
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return Credential{}, loginFailed(fmt.Errorf(
				"等了 %s，这个链接没有被批准。重新运行 skmr login 拿一个新链接。",
				humanDuration(question.lifetime)))
		}
		wait := interval
		if wait > remaining {
			wait = remaining
		}
		if err := sleep(ctx, wait); err != nil {
			// **Ctrl-C ends the question, not just the wait.** Leaving it pending would let somebody
			// who opens the link afterwards approve a login the person already gave up on — and
			// acceptance #3 asks for the opposite. Best effort: the ticket expires on its own in ten
			// minutes anyway, so a server that cannot be reached here costs nothing.
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				cancel(client, server, question)
			}
			return Credential{}, err
		}
	}
}

// The three answers a poll can carry that this side acts on. Everything else is a refusal.
const (
	pollPending  = "pending"
	pollApproved = "approved"
	pollDenied   = "denied"
	pollExpired  = "expired"
	pollUsed     = "used"
	pollSlowDown = "slow_down"
)

// poll asks once, and translates the answer into a status and, when there is one, a credential.
func poll(ctx context.Context, client *http.Client, server string, question started,
	verifier string) (string, Credential, time.Duration, error) {
	ctx, cancel := context.WithTimeout(ctx, tokenTimeout)
	defer cancel()

	payload, err := json.Marshal(map[string]string{
		"device_secret": question.deviceSecret,
		"code_verifier": verifier,
	})
	if err != nil {
		return "", Credential{}, 0, fmt.Errorf("building the poll request: %w", err)
	}
	endpoint := server + DeviceSignInsPath + "/" + url.PathEscape(question.ticket) + "/token"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(payload))
	if err != nil {
		return "", Credential{}, 0, fmt.Errorf("building the poll request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return "", Credential{}, 0, sandbox.Wrap(fmt.Errorf("calling %s: %w", endpoint, err))
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<16))
	if err != nil {
		return "", Credential{}, 0, fmt.Errorf("reading the response from %s: %w", endpoint, err)
	}

	switch resp.StatusCode {
	case http.StatusNotFound:
		// **One answer for every way this can fail to match** — no such ticket, a secret that is not
		// the one it was issued with, a verifier that does not hash to the challenge. Telling them
		// apart would also tell somebody holding a link whether it is a real question, and which half
		// they are missing. The sentence names the way forward rather than the cause.
		return "", Credential{}, 0, loginFailed(errors.New("这个链接已经失效了（或者不是这次登录的）。" +
			"重新运行 skmr login 拿一个新链接。"))
	case http.StatusTooManyRequests:
		// The header is seconds; an HTTP-date is legal but not worth assembling a clock out of.
		// Without it, the loop waits its normal interval — the server's next answer will say the same
		// thing again if that is still too soon.
		retry, _ := strconv.Atoi(strings.TrimSpace(resp.Header.Get("Retry-After")))
		return pollSlowDown, Credential{}, time.Duration(retry) * time.Second, nil
	}

	var answer struct {
		Status      string `json:"status"`
		AccessToken string `json:"access_token"`
		ExpiresAt   string `json:"expires_at"`
		Scope       string `json:"scope"`
		Name        string `json:"name"`
		Username    string `json:"username"`
	}
	if err := json.Unmarshal(body, &answer); err != nil {
		return "", Credential{}, 0, fmt.Errorf("服务端的回答不是一个状态（HTTP %d）：%s",
			resp.StatusCode, summarise(body))
	}

	switch resp.StatusCode {
	case http.StatusAccepted:
		return pollPending, Credential{}, 0, nil
	case http.StatusGone:
		switch answer.Status {
		case pollDenied, pollExpired, pollUsed:
			return answer.Status, Credential{}, 0, nil
		}
		return "", Credential{}, 0, fmt.Errorf("服务端说这次登录结束了（HTTP 410），但状态是 %q",
			answer.Status)
	}
	if resp.StatusCode != http.StatusOK {
		return "", Credential{}, 0, fmt.Errorf("服务端回答轮询时出错（HTTP %d）：%s",
			resp.StatusCode, summarise(body))
	}

	credential, err := credentialOf(answer.AccessToken, answer.Name, answer.Username,
		answer.ExpiresAt, answer.Scope)
	if err != nil {
		return "", Credential{}, 0, loginFailed(err)
	}
	return pollApproved, credential, 0, nil
}

// credentialOf refuses an answer that is not one, rather than filling in the holes.
//
// **Three fields are refused rather than defaulted**, the same way the token endpoint's `expires_in`
// was: they are what the CLI stores and prints, and a guessed one is a store that keeps a dead
// credential with a date beside it that is not true. The name is the third for one more reason —
// **a client that was not told it cannot work one out**, so the alternatives are an invented name or
// a silent blank, and both are worse than saying the answer was not a credential.
func credentialOf(token, name, username, expiresAt, scope string) (Credential, error) {
	if token == "" {
		return Credential{}, errors.New("服务端说批准了，却没有给凭据")
	}
	if name == "" {
		return Credential{}, errors.New("服务端没有说这台机器的名称" +
			"——这不是这个 CLI 发起的登录，或者服务端是还不说这件事的版本")
	}
	if expiresAt == "" {
		return Credential{}, errors.New("服务端没有说这份凭据什么时候到期")
	}
	when, err := time.Parse(time.RFC3339, expiresAt)
	if err != nil {
		return Credential{}, fmt.Errorf("服务端给的到期时间 CLI 读不懂（%q）：%w", expiresAt, err)
	}
	return Credential{
		Name:        name,
		Username:    username,
		AccessToken: token,
		ExpiresAt:   when,
		Scopes:      strings.Fields(scope),
	}, nil
}

// cancel tells the server the waiting process gave up.
//
// The body carries the same device secret the poll does, so a person holding the link cannot cancel
// somebody else's question. Its answer is ignored on purpose: the person has already cancelled, and
// a failure here must not replace the cancellation with a network error.
func cancel(client *http.Client, server string, question started) {
	// **Its own context, not the caller's**: the caller's is cancelled by definition here, and a
	// request built on it would fail before leaving. Five seconds is this one call's whole budget.
	requestCtx, done := context.WithTimeout(context.Background(), 5*time.Second)
	defer done()

	payload, err := json.Marshal(map[string]string{"device_secret": question.deviceSecret})
	if err != nil {
		return
	}
	endpoint := server + DeviceSignInsPath + "/" + url.PathEscape(question.ticket) + "/cancel"
	req, err := http.NewRequestWithContext(requestCtx, http.MethodPost, endpoint,
		bytes.NewReader(payload))
	if err != nil {
		return
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<12))
	_ = resp.Body.Close()
}

// sleep waits, unless the person has already pressed Ctrl-C.
//
// **A cancelled context is the Ctrl-C case, and it is answered as itself rather than as a failed
// poll.** The question is ended best-effort before returning — see `cancel` — and the ticket expiring
// on its own in ten minutes is the backstop for a server that could not be reached.
func sleep(ctx context.Context, d time.Duration) error {
	if d <= 0 {
		return ctx.Err()
	}
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

// humanDuration renders a wait in the unit a person would say it in.
func humanDuration(d time.Duration) string {
	if d%time.Minute == 0 && d >= time.Minute {
		return fmt.Sprintf("%d 分钟", int(d/time.Minute))
	}
	if d >= time.Minute {
		return fmt.Sprintf("%d 分钟", int(d/time.Minute))
	}
	return fmt.Sprintf("%d 秒", int(d/time.Second))
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

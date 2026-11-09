// Package api is the CLI's side of the read and write endpoints of §4.2 and §4.3.
//
// Every call takes a bearer token and nothing here obtains one: which credential this machine
// presents is `internal/auth`'s business, and a client that could also go and get one would be a
// second place where the rules about that live.
//
// What this package does do about credentials is **report the one refusal that has an action
// attached to it** — {@link ErrUnauthorized}, so that the layer holding the store can replace the
// token and try again. It does not know how, deliberately; it only says which failure this is.
package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/apikey"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/sandbox"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Generation is the API generation this binary speaks (ADR 0088).
//
// **It is also the CLI's major version**, which is why the number is here rather than in a document:
// `1.x` speaks `/api/v1`, `2.x` speaks `/api/v2`, and `--version` prints both halves so nobody has to
// count digits to see which one they are holding. Bumping it is a coordinated act, not a release
// detail — the old generation keeps answering while the new one is rolled out (ADR 0088 §决定 4).
const Generation = "v1"

// APIPrefix is where every endpoint in this package lives.
//
// One constant rather than the twenty-nine string literals this used to be: the generation is a
// property of the binary, so a build that speaks another one should be a one-line change here, not a
// search-and-replace across nine files that a reviewer has to trust.
const APIPrefix = "/api/" + Generation

// Client talks to one server with one token.
type Client struct {
	BaseURL string
	Token   string
	HTTP    *http.Client
}

// callTimeout bounds one request. Longer than the token endpoints' budget because these carry
// payloads: a publish uploads an archive.
const callTimeout = 2 * time.Minute

func (c Client) http() *http.Client {
	if c.HTTP != nil {
		return c.HTTP
	}
	return http.DefaultClient
}

// pathForError is the path an error sentence may show: everything before the query string.
//
// **Cursors live in the query and must not reach the user** (TD §CLI 映射: 游标不出现在 stdout、
// 帮助或错误里). A transport failure would otherwise print the whole URL, cursor and all, and
// `skills list --all` / `skills public-tags --all` carry one on every continuation request.
func pathForError(path string) string {
	if i := strings.IndexByte(path, '?'); i >= 0 {
		return path[:i]
	}
	return path
}

// withHint appends the sandbox package's explanation to a transport failure when it has one. The
// original error stays wrapped, so a caller's errors.Is checks and refusal handling are unchanged.
//
// **The host comes from BaseURL, not from config.Server().** They are the same value at every
// production construction site today, and a hint that named the configured server while the request
// went somewhere else is exactly the fault `WrapFor` exists to avoid.
func (c Client) withHint(err error) error {
	return sandbox.WrapFor(sandbox.HostOf(c.BaseURL), err)
}

func (c Client) get(ctx context.Context, path string) ([]byte, error) {
	return c.getPinned(ctx, path, "")
}

// getPinned is get with an optional version pin on the request — the only difference between the two,
// which is why there is one implementation and not two.
//
// An empty pin sends no header at all rather than an empty one: §4.2 reads a blank header as absent,
// but sending it would still be this client stating something it does not mean.
func (c Client) getPinned(ctx context.Context, path, pin string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, callTimeout)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.BaseURL+path, nil)
	if err != nil {
		return nil, fmt.Errorf("构造请求：%w", err)
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.Token)
	if pin != "" {
		req.Header.Set(VersionHeader, pin)
	}

	shown := pathForError(path)
	resp, err := c.http().Do(req)
	if err != nil {
		return nil, c.withHint(fmt.Errorf("请求 %s 失败：%w", shown, err))
	}
	defer resp.Body.Close()
	body, err := readAll(resp)
	if err != nil {
		return nil, fmt.Errorf("读取 %s 的响应：%w", shown, err)
	}
	if resp.StatusCode == http.StatusNotFound {
		// The server answers 404 for "no such skill", "not visible to you" and "that version does
		// not exist" alike, deliberately (§4.2). A CLI that guessed which one it was would be
		// undoing that, so the message repeats the one thing the server did say.
		return nil, fmt.Errorf("%w %s（服务端不区分「不存在」与「你看不到」）", ErrNotFound, shown)
	}
	if resp.StatusCode == http.StatusUnauthorized {
		return nil, refusal(body)
	}
	if resp.StatusCode == http.StatusForbidden {
		// A read can be refused this way too — a credential that lacks `skills:read` altogether — and
		// it is the same two reasons as on a write, so it is the same error with the same two
		// answers. See Denial.
		return nil, denial(resp, body)
	}
	if resp.StatusCode == http.StatusGone || resp.StatusCode == http.StatusServiceUnavailable {
		// 410 and 503 are about a version or a skill's service state, not a generic failure, and the
		// two sentences `ServerError` gives them are the ones the design asks for.
		return nil, serverError(resp.StatusCode, body)
	}
	if resp.StatusCode != http.StatusOK {
		return nil, &StatusRefusal{Status: resp.StatusCode, Code: errorEnvelopeCode(body), Body: string(body)}
	}
	return body, nil
}

// postJSON sends one small JSON body and reads the answer.
//
// The statuses it names are the ones with something to say beyond their number, and a 403 is one of
// them rather than a single fact: it is either "your credential is too narrow" or "this is not yours
// to do", and those are two different answers for the person. `Denial` carries which, and the
// server's sentence with it.
func (c Client) postJSON(ctx context.Context, path string, payload []byte) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, callTimeout)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.BaseURL+path, bytes.NewReader(payload))
	if err != nil {
		return nil, fmt.Errorf("构造请求：%w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.Token)

	shown := pathForError(path)
	resp, err := c.http().Do(req)
	if err != nil {
		return nil, c.withHint(fmt.Errorf("请求 %s 失败：%w", shown, err))
	}
	defer resp.Body.Close()
	body, err := readAll(resp)
	if err != nil {
		return nil, fmt.Errorf("读取 %s 的响应：%w", shown, err)
	}
	switch resp.StatusCode {
	case http.StatusOK, http.StatusCreated, http.StatusNoContent:
		return body, nil
	case http.StatusNotFound:
		return nil, fmt.Errorf("找不到 %s（服务端不区分「不存在」、「你看不到」与「不是你的」）", path)
	case http.StatusUnauthorized:
		return nil, refusal(body)
	case http.StatusForbidden:
		return nil, denial(resp, body)
	case http.StatusGone, http.StatusServiceUnavailable:
		return nil, serverError(resp.StatusCode, body)
	default:
		return nil, &StatusRefusal{Status: resp.StatusCode, Code: errorEnvelopeCode(body), Body: string(body)}
	}
}

// serverMessage pulls §4.1's `error.message` out of a body, and falls back to the body itself.
//
// The fallback is the honest half: a refusal from something in front of the application — a proxy, a
// firewall — has no envelope to read, and showing nothing would leave a person with a status code
// and no sentence.
func serverMessage(body []byte) string {
	var envelope errorEnvelope
	if json.Unmarshal(body, &envelope) == nil && envelope.Error.Message != "" {
		return envelope.Error.Message
	}
	return strings.TrimSpace(string(body))
}

// decodeErrorEnvelope reads the envelope, and answers an empty one when the body is not JSON.
//
// Empty is a real answer: a refusal from in front of the application has no envelope, and the
// callers treat "no code" as "say what arrived" rather than as a parse failure.
func decodeErrorEnvelope(body []byte) errorEnvelope {
	var envelope errorEnvelope
	_ = json.Unmarshal(body, &envelope)
	return envelope
}

// errorEnvelopeCode pulls the same envelope's `code` out, and says nothing when it cannot.
//
// An empty answer is not a failure: a 401 from something in front of the application has no
// envelope, and the caller's wording for "I do not know why" is the generic one it always had.
func errorEnvelopeCode(body []byte) string {
	var envelope errorEnvelope
	if json.Unmarshal(body, &envelope) == nil {
		return envelope.Error.Code
	}
	return ""
}

// readAll reads a response body whole.
//
// Unbounded on purpose for the two that need it: `get` writes a skill's bytes to a file, and a
// truncated skill is worse than a failed one — the digest in the manifest is what a caller would
// check it against, and there is nothing here that can check it. The JSON responses are small
// because the server's own limits make them so.
func readAll(resp *http.Response) ([]byte, error) {
	return io.ReadAll(resp.Body)
}

// SkillPath turns `namespace/name[@version]` into the request path.
//
// Both segments are escaped, because a name may be non-ASCII (M5 permits it) and a raw space would
// make the URL invalid. `@` and `:` survive the escaping — they are legal in a path segment — which
// is what lets the pin travel inside the name exactly as the server's own addresses do.
func SkillPath(address string) (string, error) {
	namespace, name, found := strings.Cut(address, "/")
	if !found || namespace == "" || name == "" || strings.Contains(name, "/") {
		// **Guarded here rather than at each caller**, because this is the one writer of this message
		// and four commands reach it through this package (`invoke`, `read`, `files`, `versions`): the
		// first version of this guard sat in the command layer beside `share` and `submit --to`, and a
		// key pasted into the other four was quoted back verbatim — into the terminal and into whatever
		// collects a CI job's output. A plain typo is still quoted, because the word to fix is the word
		// to show.
		if apikey.IsKey(address) {
			return "", fmt.Errorf("地址要写成 <命名空间>/<名字>[@版本]，%s", apikey.InAnArgument)
		}
		return "", fmt.Errorf("地址要写成 <命名空间>/<名字>[@版本]，收到 %q", address)
	}
	return APIPrefix + "/skills/" + url.PathEscape(namespace) + "/" + url.PathEscape(name), nil
}

// VersionSuffix is what follows the `@` in an address: the author's semver when they declared one,
// and the digest when they did not (ADR 0033).
//
// **The digest arrives already prefixed** — the API presents it as `sha256:…` — so the no-name case
// is the digest verbatim and nothing is assembled here. One function rather than one expression per
// caller, because this is the address grammar: a second spelling of it would eventually disagree
// with this one.
func VersionSuffix(name, digest string) string {
	if name != "" {
		return name
	}
	return digest
}

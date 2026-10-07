// Package api is the CLI's side of the read and write endpoints of §4.2 and §4.3.
//
// Every call takes a bearer token and nothing here refreshes one: obtaining and renewing a
// credential is `internal/auth`'s business, and a client that could also refresh would be a second
// place where the rotation rule lives.
//
// What this package does do about credentials is **report the one refusal that has an action
// attached to it** — {@link ErrUnauthorized}, so that the layer holding the store can replace the
// token and try again. It does not know how, deliberately; it only says which failure this is.
package api

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// ErrUnauthorized is what a request gets when the server will not accept the token at all.
//
// **Its own error because its remedy is unlike every other failure here.** A 404 is an answer about
// the skill; a timeout is a network; a 401 is about *the credential*, and the only layer that can do
// anything is the one holding the store. The CLI believed this token was good for another fifty
// minutes — that belief comes from the expiry it stored, and the server is the authority on it — so
// this is the signal to obtain a new one and try once more, not an error to show a person.
//
// Distinct from 403, which stays generic on purpose: `insufficient_scope` means the token is fine and
// the grant is too small. Signing in again would produce the same scopes, since consent is
// remembered — so treating it as an authentication problem would send somebody round a loop that
// cannot help.
var ErrUnauthorized = errors.New("服务端不认这份凭据（可能已被撤销或已过期）")

// ErrNotFound is the server's one 404, which covers every address that resolves to nothing.
//
// It is a value rather than only a message because a caller can know something the server cannot: a
// read that pinned a version from this machine's own records and then got a 404 knows that *its* pin
// is what stopped resolving, which is a different thing to say from "there is no such skill" — see
// `read`'s handler. The message stays exactly what the server's answer amounts to; only the ability to
// tell it apart is added here.
var ErrNotFound = errors.New("找不到")

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

// Search asks §4.2's list endpoint.
func (c Client) Search(ctx context.Context, query string) (SearchPage, error) {
	path := "/api/v1/skills?q=" + url.QueryEscape(query)
	body, err := c.get(ctx, path)
	if err != nil {
		return SearchPage{}, err
	}
	var page SearchPage
	if err := json.Unmarshal(body, &page); err != nil {
		return SearchPage{}, fmt.Errorf("检索响应不是 JSON：%w", err)
	}
	return page, nil
}

// ListPageSize is how many skills one request for a page asks for.
//
// 100 is the server's own maximum (`SearchRequest.MAX_LIMIT`), so this is the fewest requests a
// complete listing can take. Asking for less would not make anything cheaper: the bound the server
// is protecting is on one response, and a caller that wants fewer rows narrows by query instead.
const ListPageSize = 100

// List reads one page of §4.2's listing: every skill the caller may read, across namespaces.
//
// **The filter narrows and never widens.** The endpoint's scope is "the namespaces I own, plus the
// skills shared with me" (ADR 0034); these slugs select within that, and a slug the caller can read
// nothing in selects nothing rather than somebody else's skills. Empty means all of it — so a caller
// that passes no slug gets the whole set, which is what makes the ordinary `list` an entry point
// rather than a scoped query.
//
// The cursor goes back to the server exactly as it came, and it is not the caller's to hold: see
// `listSkills` in the command layer for why no command-line surface carries one. **The filter and
// the cursor are independent** — the server's cursor carries the ordering and not the predicate — so
// `collect` is what keeps them together on every page of a walk.
func (c Client) List(ctx context.Context, namespaces []string, cursor string) (SearchPage, error) {
	query := url.Values{}
	query.Set("limit", strconv.Itoa(ListPageSize))
	// Repeated rather than comma-joined: that is the shape the endpoint reads (`@RequestParam
	// List<String>`), and a joining convention here would be a second grammar for the same thing,
	// free to disagree with the first.
	for _, slug := range namespaces {
		query.Add("namespace", slug)
	}
	if cursor != "" {
		query.Set("cursor", cursor)
	}
	body, err := c.get(ctx, "/api/v1/skills?"+query.Encode())
	if err != nil {
		return SearchPage{}, err
	}
	var page SearchPage
	if err := json.Unmarshal(body, &page); err != nil {
		return SearchPage{}, fmt.Errorf("列表响应不是 JSON：%w", err)
	}
	return page, nil
}

// The two roles a skill can be shared with (ADR 0034). Wire values, so they are spelled here rather
// than derived from a constant name the way `SearchRequest.SortOrder` argues for.
//
// `owner` is deliberately absent: it is not a grant that can be given, it is what the namespace's
// owner has by virtue of owning the namespace.
const (
	RoleViewer = "viewer"
	RoleEditor = "editor"
)

// Share grants one skill to one account, or changes the role it was already granted (§4.3,
// ADR 0034).
//
// The address is `namespace/name` and carries no version: a share is a property of the skill, not of
// a version of it, so a pin here would be ignored — which is why SkillPath's caller in the command
// layer refuses one rather than letting it through to be dropped.
//
// This is the CLI's first verb that changes what somebody *else* can read. `--to` has no default and
// the command is absent from the gateway's text on purpose: an agent has no reason to hand somebody
// access to a skill, and a protocol that never mentions it is one it cannot do by accident.
func (c Client) Share(ctx context.Context, address, handle, role string) (ShareResult, error) {
	path, err := SkillPath(address)
	if err != nil {
		return ShareResult{}, err
	}
	payload, err := json.Marshal(map[string]string{"handle": handle, "role": role})
	if err != nil {
		return ShareResult{}, fmt.Errorf("构造请求体：%w", err)
	}
	body, err := c.postJSON(ctx, path+"/grants", payload)
	if err != nil {
		return ShareResult{}, err
	}
	var result ShareResult
	if err := json.Unmarshal(body, &result); err != nil {
		return ShareResult{}, fmt.Errorf("共享响应不是 JSON：%w", err)
	}
	return result, nil
}

// ShareResult is what the server says a share came to.
type ShareResult struct {
	// The handle as typed, echoed back: the server resolved it to an account, and this is what it
	// resolved, which is worth printing beside what was asked for.
	Handle string `json:"handle"`
	Role   string `json:"role"`
}

// VersionHeader is how this client pins a version on a request (§4.2).
//
// It is the same grammar as an address's `@` suffix, and it exists because the two need not be spelled
// in the same place: the address says *which skill*, this says *which version of it*. The CLI sends it
// from what `invoke` remembered, so the pin never has to be written into a command by whoever is
// asking (ADR 0035) — and the address a person or a model types is always the bare name.
const VersionHeader = "X-Skill-Version"

// Detail reads the manifest of one address — §4.2's L1.
//
// The address is what the person typed: `namespace/name`, and the pin travels separately in `pin`,
// which may be empty. Both go to the server unresolved: it is what owns the address grammar and the
// resolution (ADR 0012), and a CLI that re-implemented either would be a second, differently-wrong
// answer.
func (c Client) Detail(ctx context.Context, address, pin string) (Detail, error) {
	path, err := SkillPath(address)
	if err != nil {
		return Detail{}, err
	}
	body, err := c.getPinned(ctx, path, pin)
	if err != nil {
		return Detail{}, err
	}
	var detail Detail
	if err := json.Unmarshal(body, &detail); err != nil {
		return Detail{}, fmt.Errorf("详情响应不是 JSON：%w", err)
	}
	return detail, nil
}

// VersionInfo is one row of §4.2's version list.
type VersionInfo struct {
	// Null when the author declared no `version:` — for such a version the digest is the only thing it
	// can be addressed by, and it is always there (ADR 0033).
	Name        string `json:"name"`
	Digest      string `json:"digest"`
	PublishedAt string `json:"published_at"`
	IsCurrent   bool   `json:"is_current"`
}

// ReadVersions lists the versions of one skill that this caller may invoke, newest submission first.
//
// No pin is sent: no version is being selected, and the server refuses the header outright on this
// endpoint rather than ignoring it (§4.2). That refusal is why this does not take a pin parameter it
// would then have to suppress.
func (c Client) ReadVersions(ctx context.Context, address string) ([]VersionInfo, error) {
	path, err := SkillPath(address)
	if err != nil {
		return nil, err
	}
	body, err := c.get(ctx, path+"/versions")
	if err != nil {
		return nil, err
	}
	var listed struct {
		Versions []VersionInfo `json:"versions"`
	}
	if err := json.Unmarshal(body, &listed); err != nil {
		return nil, fmt.Errorf("版本列表响应不是 JSON：%w", err)
	}
	return listed.Versions, nil
}

// Fetch follows a URI the manifest handed back — §4.2's L2 and L3.
//
// The URI is used verbatim, which is the point of the manifest carrying one: it has the version
// already written into it, so a publish that lands in between cannot swap the content out from under
// a client that has already entered at a stable address.
func (c Client) Fetch(ctx context.Context, uri string) ([]byte, error) {
	return c.get(ctx, uri)
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

	resp, err := c.http().Do(req)
	if err != nil {
		return nil, fmt.Errorf("请求 %s 失败：%w", path, err)
	}
	defer resp.Body.Close()
	body, err := readAll(resp)
	if err != nil {
		return nil, fmt.Errorf("读取 %s 的响应：%w", path, err)
	}
	if resp.StatusCode == http.StatusNotFound {
		// The server answers 404 for "no such skill", "not visible to you" and "that version does
		// not exist" alike, deliberately (§4.2). A CLI that guessed which one it was would be
		// undoing that, so the message repeats the one thing the server did say.
		return nil, fmt.Errorf("%w %s（服务端不区分「不存在」与「你看不到」）", ErrNotFound, path)
	}
	if resp.StatusCode == http.StatusUnauthorized {
		return nil, fmt.Errorf("%w：%s", ErrUnauthorized, strings.TrimSpace(string(body)))
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("服务端答 %d：%s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	return body, nil
}

// postJSON sends one small JSON body and reads the answer.
//
// The statuses it names are the ones with something to say beyond their number. A 403 is the
// system's only one and means something narrower than the word usually does — the caller can read
// this skill and may not administer it — so the server's own sentence is quoted rather than replaced
// by a guess at which level they are missing. `errorEnvelope` says where that sentence lives.
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

	resp, err := c.http().Do(req)
	if err != nil {
		return nil, fmt.Errorf("请求 %s 失败：%w", path, err)
	}
	defer resp.Body.Close()
	body, err := readAll(resp)
	if err != nil {
		return nil, fmt.Errorf("读取 %s 的响应：%w", path, err)
	}
	switch resp.StatusCode {
	case http.StatusOK, http.StatusCreated, http.StatusNoContent:
		return body, nil
	case http.StatusNotFound:
		return nil, fmt.Errorf("找不到 %s（服务端不区分「不存在」、「你看不到」与「不是你的」）", path)
	case http.StatusUnauthorized:
		return nil, fmt.Errorf("%w：%s", ErrUnauthorized, strings.TrimSpace(string(body)))
	case http.StatusForbidden:
		return nil, fmt.Errorf("服务端拒绝了这次操作：%s", serverMessage(body))
	default:
		return nil, fmt.Errorf("服务端答 %d：%s", resp.StatusCode, strings.TrimSpace(string(body)))
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
		return "", fmt.Errorf("地址要写成 <命名空间>/<名字>[@版本]，收到 %q", address)
	}
	return "/api/v1/skills/" + url.PathEscape(namespace) + "/" + url.PathEscape(name), nil
}

// SearchPage is §4.2's list response, reduced to what the CLI prints.
//
// The fields are snake_case because that is the wire format; a struct that only names the fields it
// needs is deliberate, so a field the server adds is not a change here.
type SearchPage struct {
	Skills     []Card `json:"skills"`
	NextCursor string `json:"next_cursor"`
}

type Card struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	// The author's own answer to "when should this be used", or empty. **The half of the L1 that is a
	// judgment rather than a summary**, which is why it is printed beside the description rather than
	// left out: a listing exists to answer "is this the skill I want", and Claude Code's own listing
	// joins exactly these two (`description - when_to_use`).
	WhenToUse string `json:"when_to_use"`
	Namespace string `json:"namespace"`
}

// Summary is what a row says about the skill, below its address.
//
// One function rather than a format string at each call site, because the join is the L1's *reading
// rule* and not a detail of one printer: the same two fields make the same sentence wherever a
// listing is rendered. `when_to_use` is the author's `when_to_use`, and a skill whose author declared
// none reads exactly as its description — no dangling separator.
func (c Card) Summary() string {
	if c.WhenToUse == "" {
		return c.Description
	}
	if c.Description == "" {
		return c.WhenToUse
	}
	return c.Description + " - " + c.WhenToUse
}

// Address is how a card is named back to the server — the two segments joined, which is also what a
// person types.
//
// **No version, and that is now the design rather than an omission.** A card used to carry one so a
// listing was enough to pin; since ADR 0035 the pin is resolved by `invoke` and kept on this machine,
// so an address is always the bare name and the model never writes a version at all. The pin did not
// stop mattering — it stopped travelling through the caller's text.
func (c Card) Address() string {
	return c.Namespace + "/" + c.Name
}

// Detail is §4.2's manifest. The two URIs are what `get` follows.
type Detail struct {
	Name        string `json:"name"`
	Title       string `json:"title"`
	Description string `json:"description"`
	Namespace   struct {
		Slug  string `json:"slug"`
		Title string `json:"title"`
	} `json:"namespace"`
	Visibility string `json:"visibility"`
	Version    struct {
		// Null when the author declared no `version:` in their SKILL.md, which is a legal submission
		// and not a missing field (ADR 0033): such a version has no name and is addressed by digest.
		Name        string `json:"name"`
		Digest      string `json:"digest"`
		PublishedAt string `json:"published_at"`
		FileCount   int    `json:"file_count"`
		TotalBytes  int64  `json:"total_bytes"`
		IsLatest    bool   `json:"is_latest"`
	} `json:"version"`
	Files     []File `json:"files"`
	Resources struct {
		Body string `json:"body"`
	} `json:"resources"`
}

// File is one row of a manifest: where a file is, and what it is.
//
// **`URI` is the pinned address and not a path**, which is the whole of ADR 0012's mechanism: it has
// the resolved version written into it, so a client that follows it never asks for `latest` again and
// a publish landing in between cannot change what arrives. `IsBinary` is carried so that a client can
// decide not to print bytes it cannot show — the manifest knows, and guessing at the bytes does not.
type File struct {
	Relpath  string `json:"relpath"`
	URI      string `json:"uri"`
	SHA256   string `json:"sha256"`
	Size     int64  `json:"size"`
	IsBinary bool   `json:"is_binary"`
}

// Address is where this manifest lives, for printing.
func (d Detail) Address() string {
	return d.Namespace.Slug + "/" + d.Name + "@" + VersionSuffix(d.Version.Name, d.Version.Digest)
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

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
	"errors"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/apikey"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// ErrUnauthorized is what a request gets when the server will not accept the token at all.
//
// **Its own error because it is about the credential and nothing else here is.** A 404 is an answer
// about the skill; a timeout is a network; a 401 is the credential, and the server names which of the
// ways it failed, because those send a person to different places (ADR 0039 §决定 3).
//
// **It no longer means "get a new one and try again", and v4 is why.** This used to be the signal to
// refresh the chain and retry once; there is no chain any more (v4 做 #2), so a refusal is the
// server's final answer and what the CLI does with it is say it. See `Refusal` for the reason.
//
// Distinct from 403, which is never about the credential being invalid — see `Denial`, where the two
// 403s are told apart rather than merged into one sentence.
var ErrUnauthorized = errors.New("服务端不认这份凭据（可能已被撤销或已过期）")

// Refusal is a 401 with the server's own reason for it.
//
// **It wraps ErrUnauthorized rather than replacing it**, so every existing `errors.Is` keeps
// working and the callers that only know "the credential was refused" are unaffected. What it adds
// is the part the server sent on purpose: since v2 a 401 says *which* kind of no it is — expired,
// revoked, suspended — and those send somebody to different places (ADR 0039 §决定 3). **v4 added a
// fourth name, `token_idle`**, which only a login can produce: a credential with two rulers has two
// ways to run out, and "you were away" is a different thing to be told than "this is old", because
// the one points at coming back and the other at a credential that lasts (v4 做 #11).
//
// Reporting them as one sentence would throw away the only thing that tells a person what to do.
// A code this build does not know keeps the generic sentence, which at least quotes the server.
type Refusal struct {
	// Code is the server's own name for the reason. Empty when the body was not the error envelope
	// — a 401 raised in front of the application, where the status is the whole of the fact.
	Code string
	// Body is the envelope exactly as it arrived. Kept because `Error` still says it: this type is
	// read by two kinds of caller, one branching on the code and one printing the message, and the
	// second must not lose what it had before this type existed.
	Body string
}

// Error is the sentence a 401 produced before this type existed — the generic one, then whatever the
// server sent — so nothing that only prints it changed. The code is what the callers that can act on
// a refusal read instead.
func (r *Refusal) Error() string {
	return ErrUnauthorized.Error() + "：" + r.Body
}

// Unwrap is what keeps ErrUnauthorized meaningful for every caller that predates this type.
func (r *Refusal) Unwrap() error { return ErrUnauthorized }

// refusal builds the error for a 401, reading the reason out of the envelope.
//
// A body that is not the envelope is not an error here: the status is the fact, and a code this
// cannot read leaves the message exactly what it was before this type existed.
func refusal(body []byte) error {
	return &Refusal{
		Code: errorEnvelopeCode(body),
		Body: strings.TrimSpace(string(body)),
	}
}

// Denial is a 403, with the reason the server gave for it.
//
// **Two refusals arrive as 403 and they are not the same thing, and telling them apart is v4's work**
// (§一次操作的结果落在谁身上). They differ in exactly one respect, and it is the one a person acts on:
//
//   - `insufficient_scope` — another credential *would* work. The token is fine and grants too
//     little, and a key's scopes are chosen when it is issued, so one carrying more goes through.
//     The server sends a `WWW-Authenticate` challenge naming the scope that was missing.
//   - `forbidden` — nothing the caller holds would work. The resource is refusing, not the security
//     layer, and the server deliberately sends **no** challenge: a client that read one would
//     re-authorize and get the same 403 for ever (`NotPermittedException` says exactly that).
//
// A single sentence covering both says "get another credential" and "no credential helps" in the same
// words. **The code is what separates them, and it comes from the server rather than from a guess
// about which call this was** — guessing would be right until the day an authorization decision moved,
// which is the kind of thing a CLI is not in a position to notice.
//
// The body is kept whole, the way `Refusal` keeps it: it is what an operator greps for, and for a code
// this build does not know it is the only thing that can name which level the caller is missing.
type Denial struct {
	// Code is the server's own name for the reason: `insufficient_scope`, `forbidden`, or empty when
	// the body was not the error envelope — a 403 raised in front of the application, where the status
	// is the whole of the fact.
	Code string
	// Scope is the scope the challenge named, and empty when there was no challenge. It is the one
	// thing in the response a person can act on: the name of the permission they do not have.
	Scope string
	// Body is the envelope exactly as it arrived. Kept because `Error` still says it: this type is
	// read by two kinds of caller, one branching on the code and one printing the message, and the
	// second must not lose what it had before this type existed.
	Body string
	// Message is the envelope's own sentence. v14's private-tag refusals put the next step there
	// (「先把 … 加入你的库」), and a client that replaced it with a generic permission sentence would
	// be telling somebody to get another credential when what they need is `skill join`.
	Message string
	// Details is the envelope's `details`, keyed by field — the same reading `ServerError` does. v14
	// uses one entry, `reason`, to separate "not in the library" from the other forbidden cases.
	Details map[string]string
}

// The two reasons a 403 carries, spelled as the server spells them. Wire values, so they are written
// here rather than derived from a constant name on the other side of a network.
const (
	InsufficientScope = "insufficient_scope"
	Forbidden         = "forbidden"
)

// Error is the sentence a 403 produced before this type existed — the same prefix, then whatever the
// server sent — so nothing that only prints it changed. The code is what the callers that can act on
// a refusal read instead.
func (d *Denial) Error() string {
	return "服务端拒绝了这次操作：" + d.Body
}

// denial builds the error for a 403, reading the reason, the challenge and the envelope's own
// details out of the response.
func denial(resp *http.Response, body []byte) error {
	envelope := decodeErrorEnvelope(body)
	details := make(map[string]string, len(envelope.Error.Details))
	for _, detail := range envelope.Error.Details {
		if detail.Field != "" && detail.Issue != "" {
			details[detail.Field] = detail.Issue
		}
	}
	return &Denial{
		Code:    envelope.Error.Code,
		Scope:   challengedScope(resp.Header.Get("WWW-Authenticate")),
		Body:    strings.TrimSpace(string(body)),
		Message: envelope.Error.Message,
		Details: details,
	}
}

// challengedScope pulls `scope="…"` out of a Bearer challenge.
//
// **Read loosely on purpose.** The header the server sends is `Bearer, error="insufficient_scope",
// scope="skills:write", resource_metadata="…"`, and this is not going to become an RFC 6750 parser to
// get one value out of it: what is wanted is the name of the permission a person does not have. A
// header that does not carry one leaves this empty, and every caller handles that by saying less
// rather than by saying something wrong.
func challengedScope(header string) string {
	for _, part := range strings.Split(header, ",") {
		key, value, found := strings.Cut(strings.TrimSpace(part), "=")
		if !found || !strings.EqualFold(strings.TrimSpace(key), "scope") {
			continue
		}
		return strings.Trim(strings.TrimSpace(value), `"`)
	}
	return ""
}

// ServerError is any refusal that is not a credential problem and not one of the two 403s: a 400
// with a named code, a 404, a 410, a 503. It keeps the envelope so a `--json` caller can report the
// server's own code, and gives the two lifecycle refusals a sentence each.
type ServerError struct {
	Status  int
	Code    string
	Message string
	Body    string
	// Details is the envelope's `details`, keyed by field. v7's refusals put the facts a caller can
	// act on there — the state a row is in, the pin it now answers to — and a client that read only
	// `message` would have to guess which row the refusal was about.
	Details map[string]string
}

// Error is the sentence a person reads. The two lifecycle cases are spelled out because they are
// answers about a version that used to be readable, not generic failures: `version_revoked` says the
// version exists and was taken off the consumer plane, and `skill_offline` says the whole skill is
// temporarily out of service rather than gone.
func (e *ServerError) Error() string {
	switch e.Code {
	case "version_revoked":
		return "这一版已被下架：它曾经可读，现在消费面不再服务它。"
	case "skill_offline":
		return "这个 skill 已下线，暂时不提供服务。"
	}
	if e.Message != "" {
		return e.Message
	}
	if e.Body != "" {
		return e.Body
	}
	return fmt.Sprintf("服务端答 %d", e.Status)
}

// Is lets the v14 routes' 404 — which keeps its envelope so the caller can read the code — still
// answer `errors.Is(err, ErrNotFound)`. The older read routes predate this type and return
// ErrNotFound directly; a caller should not have to know which shape its endpoint chose.
func (e *ServerError) Is(target error) bool {
	return target == ErrNotFound && e.Status == http.StatusNotFound
}

// serverError reads the envelope and keeps it.
func serverError(status int, body []byte) error {
	envelope := decodeErrorEnvelope(body)
	details := make(map[string]string, len(envelope.Error.Details))
	for _, detail := range envelope.Error.Details {
		if detail.Field != "" && detail.Issue != "" {
			details[detail.Field] = detail.Issue
		}
	}
	return &ServerError{
		Status:  status,
		Code:    envelope.Error.Code,
		Message: envelope.Error.Message,
		Body:    strings.TrimSpace(string(body)),
		Details: details,
	}
}

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

// Search asks the public half of §4.2's list endpoint.
//
// **Public only, since v14** (ADR 0082): a search is what somebody does when their own library does
// not have what they need, so it reaches the public domain and never the caller's private rows. The
// bare-query form remains for callers that have no filters; `QuerySkills` is the full question.
func (c Client) Search(ctx context.Context, query string) (SearchPage, error) {
	return c.QuerySkills(ctx, QueryOptions{Scope: ScopePublic, Query: query})
}

// The two scopes of the listing endpoint (v14, ADR 0082).
//
// **One route and two questions**, because the response shape is the same card; the `scope`
// parameter is what decides which predicate and which result set answer it. A request that names
// neither gets the library, which is the reading `skills list` has always had.
const (
	ScopeLibrary = "library"
	ScopePublic  = "public"
)

// ListPageSize is how many skills one request for a page asks for.
//
// **50 since v14** (PRD #27, down from v13's 100): the library listing is meant to be read, and a page
// that a person can hold in view is worth more than the extra round trip `--all` does not hide.
// `skills public-tags` uses the same page size; `skills my-tags` and `skills categories` are complete
// in one answer and do not go through it.
const ListPageSize = 50

// List reads one page of the library: the skills the caller created, was granted, or joined.
//
// **The filter narrows and never widens.** These slugs select within the library; a slug the caller
// can read nothing in selects nothing rather than somebody else's skills. Empty means all of it.
//
// The cursor goes back to the server exactly as it came, and it is not the caller's to hold: see
// `listSkills` in the command layer for why no command-line surface carries one. **The filter and
// the cursor are independent** — the server's cursor carries the ordering and not the predicate — so
// `collect` is what keeps them together on every page of a walk.
func (c Client) List(ctx context.Context, namespaces []string, cursor string) (SearchPage, error) {
	return c.QuerySkills(ctx, QueryOptions{
		Scope: ScopeLibrary, Namespaces: namespaces, Cursor: cursor,
	})
}

// The five rungs a skill can be shared at, low to high (ADR 0047). Wire values, so they are spelled
// here rather than derived from a constant name the way `SearchRequest.SortOrder` argues for.
//
// Every rung includes the ones below it. `owner` is deliberately absent: it is not a grant that can
// be given, it is what the namespace's owner has by virtue of owning the namespace.
const (
	RoleInvoke   = "invoke"
	RoleBrowse   = "browse"
	RoleWrite    = "write"
	RoleMaintain = "maintain"
	RoleAdmin    = "admin"
)

// Roles is the ladder, low to high — the list `share --role` offers and validates against.
var Roles = []string{RoleInvoke, RoleBrowse, RoleWrite, RoleMaintain, RoleAdmin}

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

// Metadata reads L1 alone — what says what a skill is, and which version `latest` resolved to
// (§4.2's `/metadata`, ADR 0051).
//
// **This is the read `invoke` starts from since v5, and the change is about the rung it needs.**
// The detail endpoint carries the manifest, which is a `browse` question; `invoke` needs only the
// resolved version, which is `invoke`'s own layer. The bytes are then fetched from `/body` and
// `/files/…` with the version in the header — the same address-plus-header pair that made following
// a URI from the manifest equivalent (ADR 0035), without needing to see the manifest at all.
func (c Client) Metadata(ctx context.Context, address, pin string) (Metadata, error) {
	path, err := SkillPath(address)
	if err != nil {
		return Metadata{}, err
	}
	body, err := c.getPinned(ctx, path+"/metadata", pin)
	if err != nil {
		return Metadata{}, err
	}
	var metadata Metadata
	if err := json.Unmarshal(body, &metadata); err != nil {
		return Metadata{}, fmt.Errorf("元数据响应不是 JSON：%w", err)
	}
	return metadata, nil
}

// Body fetches the original SKILL.md bytes of a resolved version (L2).
func (c Client) Body(ctx context.Context, address, pin string) ([]byte, error) {
	path, err := SkillPath(address)
	if err != nil {
		return nil, err
	}
	return c.getPinned(ctx, path+"/body", pin)
}

// File fetches one file of a resolved version by exact relpath (L3).
//
// The relpath is encoded as a path rather than as one segment — `references/x.md` has to stay two
// segments, because an encoded slash is refused by the servlet firewall before routing ever sees it.
// The server applies the same rule when it advertises one, so this is the inverse of it.
func (c Client) File(ctx context.Context, address, relpath, pin string) ([]byte, error) {
	path, err := SkillPath(address)
	if err != nil {
		return nil, err
	}
	segments := strings.Split(relpath, "/")
	for index, segment := range segments {
		segments[index] = url.PathEscape(segment)
	}
	return c.getPinned(ctx, path+"/files/"+strings.Join(segments, "/"), pin)
}

// Metadata is §4.2's L1: the fields that say what the skill is, plus the version `latest` resolved to.
type Metadata struct {
	Name      string `json:"name"`
	Title     string `json:"title"`
	Namespace struct {
		Slug string `json:"slug"`
	} `json:"namespace"`
	// Description is the L1 summary. It was already on the wire; v14 gives `skill show` a reader for
	// it rather than making that command fetch the manifest for one field.
	Description string `json:"description"`
	// WhenToUse is the author's answer to "when should this be used". **The server extracts it** from
	// the frontmatter (`metadata.when_to_use` first, the top level as a fallback) and sends it as a
	// top-level field (v14 TD §附录 A). The CLI deliberately does not parse frontmatter itself: two
	// readers of the same block is how they come to disagree.
	WhenToUse string `json:"when_to_use"`
	// Compatibility is the runtime requirements from the frontmatter's top level, or empty.
	Compatibility string `json:"compatibility"`
	Version       struct {
		// Null when the author declared no `version:` — such a version is addressed by its digest.
		Name   string `json:"name"`
		Digest string `json:"digest"`
	} `json:"version"`
}

// Address is where this metadata lives, for printing — the same spelling `Detail.Address` uses.
func (m Metadata) Address() string {
	return m.Namespace.Slug + "/" + m.Name + "@" + VersionSuffix(m.Version.Name, m.Version.Digest)
}

// VersionInfo is one row of §4.2's version list.
type VersionInfo struct {
	// Null when the author declared no `version:` — for such a version the digest is the only thing it
	// can be addressed by, and it is always there (ADR 0033).
	Name        string `json:"name"`
	Digest      string `json:"digest"`
	PublishedAt string `json:"published_at"`
	IsCurrent   bool   `json:"is_current"`
	// v6 adds the note and the derived status. `draft_name` deliberately does not appear on the
	// consumer plane: a name is the author's label, and callers address a version by its number.
	Note   *string `json:"note"`
	State  string  `json:"state"`
	Status string  `json:"status"`
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
		return nil, fmt.Errorf("请求 %s 失败：%w", shown, err)
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
		return nil, fmt.Errorf("服务端答 %d：%s", resp.StatusCode, strings.TrimSpace(string(body)))
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
		return nil, fmt.Errorf("请求 %s 失败：%w", shown, err)
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
	ID          string `json:"id"`
	Name        string `json:"name"`
	Description string `json:"description"`
	// The author's own answer to "when should this be used", or empty. **The half of the L1 that is a
	// judgment rather than a summary**, which is why it is printed beside the description rather than
	// left out: a listing exists to answer "is this the skill I want", and Claude Code's own listing
	// joins exactly these two (`description - when_to_use`).
	WhenToUse string `json:"when_to_use"`
	Namespace string `json:"namespace"`

	// Everything below is v14's half of the card (PRD §展示与计数口径, TD §响应形状). They are
	// optional on the wire: the default card prints only the address and the reading, and `--verbose`
	// is what asks these fields to be shown. A field the server has not filled in is omitted rather
	// than printed as a blank.
	//
	// Source is the caller's relation to this row — created / shared / joined — and is absent on a
	// public result the caller has no relation to. State is the display state (live / draft / offline
	// / deleted), derived by the server rather than stored. Version is the current version's author
	// given name; DeletedAt is the soft-delete time and only appears on the deleted view.
	Source     string    `json:"source"`
	State      string    `json:"state"`
	Version    string    `json:"version"`
	UpdatedAt  string    `json:"updated_at"`
	DeletedAt  *string   `json:"deleted_at"`
	Category   *Category `json:"category"`
	PublicTags []Tag     `json:"public_tags"`
	MyTags     []Tag     `json:"my_tags"`
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
	// CanWrite is whether **the credential that made this request** may submit a new version of this
	// skill and manage its sharing (v4 做 #9).
	//
	// **The server computes it, and that is the point of it being a field.** What a person can do is
	// their account's standing on the skill intersected with the scopes the credential carries, and a
	// client holds only the second half — so a client that worked this out itself would be deriving
	// the rule a second time, and the day the two derivations disagreed, "it says I can" and "it let
	// me" would be two answers to one question. What a client *can* do is say why the answer is no,
	// which is what `auth status <address>` does with its own scopes.
	CanWrite  bool   `json:"can_write"`
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

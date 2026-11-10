// The error shapes the API returns, and the helpers that decode them.

package api

import (
	"errors"
	"fmt"
	"net/http"
	"strings"
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
	// DetailList is the same facts in the order the server sent them. A map has no order, and v33's
	// collision refusal is deliberately two adjacent entries — one per path — that a client renders
	// as one line (`A.md 与 a.md：…`). Pairing them by field name would be a guess.
	DetailList []IssueDetail
}

// IssueDetail is one `details[]` entry, in order.
type IssueDetail struct {
	Field string
	Issue string
	// v39's size refusals carry the actual and allowed counts; zero means the server did not say,
	// which is every detail that is not about a size.
	ActualBytes int64
	LimitBytes  int64
	// ADR 0179's text limits, in code points; zero means the server did not say.
	ActualCodePoints int64
	LimitCodePoints  int64
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
	ordered := make([]IssueDetail, 0, len(envelope.Error.Details))
	for _, detail := range envelope.Error.Details {
		if detail.Field != "" && detail.Issue != "" {
			details[detail.Field] = detail.Issue
		}
		if detail.Issue != "" {
			entry := IssueDetail{Field: detail.Field, Issue: detail.Issue}
			if detail.ActualBytes != nil {
				entry.ActualBytes = *detail.ActualBytes
			}
			if detail.LimitBytes != nil {
				entry.LimitBytes = *detail.LimitBytes
			}
			if detail.ActualCodePoints != nil {
				entry.ActualCodePoints = *detail.ActualCodePoints
			}
			if detail.LimitCodePoints != nil {
				entry.LimitCodePoints = *detail.LimitCodePoints
			}
			ordered = append(ordered, entry)
		}
	}
	return &ServerError{
		Status:     status,
		Code:       envelope.Error.Code,
		Message:    envelope.Error.Message,
		Body:       strings.TrimSpace(string(body)),
		Details:    details,
		DetailList: ordered,
	}
}

// StatusRefusal is an answer whose status this client has no sentence of its own for. The wire's
// words are kept as they arrived; what is added is the number, so a caller can decide whether
// another plane might know better (ADR 0178: `run` asks the author plane when the public plane
// refuses a version reference's spelling).
type StatusRefusal struct {
	Status int
	// Code is the envelope's own name for the refusal, or empty when the body was not one — the
	// case a caller needs when two statuses with the same number mean different things.
	Code string
	Body string
}

func (e *StatusRefusal) Error() string {
	return fmt.Sprintf("服务端答 %d：%s", e.Status, strings.TrimSpace(e.Body))
}

// StatusOf reports the HTTP status behind an error, when one is known.
//
// **Two shapes answer it**: the parsed envelope (`ServerError`) and the pass-through above. The older
// read routes return `ErrNotFound` for a 404; that is not a status they expose, so callers that need
// one ask `errors.Is(err, ErrNotFound)` for that case.
func StatusOf(err error) (int, bool) {
	var server *ServerError
	if errors.As(err, &server) {
		return server.Status, true
	}
	var refusal *StatusRefusal
	if errors.As(err, &refusal) {
		return refusal.Status, true
	}
	return 0, false
}

// ErrNotFound is the server's one 404, which covers every address that resolves to nothing.
//
// It is a value rather than only a message because a caller can know something the server cannot: a
// read that pinned a version from this machine's own records and then got a 404 knows that *its* pin
// is what stopped resolving, which is a different thing to say from "there is no such skill" — see
// `read`'s handler. The message stays exactly what the server's answer amounts to; only the ability to
// tell it apart is added here.
var ErrNotFound = errors.New("找不到")

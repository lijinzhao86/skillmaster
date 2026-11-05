// The resource methods and their response types.

package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strings"
)

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
	// ScopeAll is "anywhere I could find it" (v19): the library and the public plane at once. Only
	// a semantic search can answer it — the literal path has no union — so it is refused with
	// --semantic=false rather than silently narrowed.
	ScopeAll = "all"
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
	body, err := c.postJSON(ctx, path+"/invitations", payload)
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
	// ExpiresAt is set when an invitation was written; empty when AlreadyGranted is true.
	ExpiresAt string `json:"expires_at"`
	// AlreadyGranted means the invitee held this rung or higher, so nothing was written. It is a
	// 200, not a failure: the state the caller asked for already holds.
	AlreadyGranted bool `json:"already_granted"`
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
	// Access is the caller's rung on this skill, and Permissions the actions it unlocks — the two
	// facts `skill invoke` prints as its header (v23). The server computes both from the same
	// `Access` that resolved the read, which is why the CLI never derives them: the shown answer and
	// the enforced one have to come from one predicate.
	//
	// Access is `admin` for the owner: ownership sits above the ladder, and every action with a CLI
	// command is the same set at `admin`.
	Access string `json:"access"`
	// Permissions is the pre-v34 action list. It stays so an older server still renders, and it is
	// printed only when Capabilities is empty (v34, ADR 0119).
	Permissions []string `json:"permissions"`
	// Capabilities is what this credential may do on this skill, each entry naming the commands
	// that do it. The header prints one line per capability; the commands are what the agent types
	// after `skmr` (v34, ADR 0119).
	Capabilities []Capability `json:"capabilities"`
	// InvokeRequiresConfirmation is the server's answer to "must the caller confirm before loading
	// this skill" (v47, ADR 0143): true only when the skill is public and this caller has no
	// library relation to it. **Read, not derived** — the caller cannot see the relation, and an
	// older server omits the field, which stays false and keeps the old behaviour.
	InvokeRequiresConfirmation bool `json:"invoke_requires_confirmation"`
}

// Capability is one entry of `capabilities`: a plain-language statement and its commands.
type Capability struct {
	Capability string   `json:"capability"`
	Commands   []string `json:"commands"`
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

// SearchPage is §4.2's list response, reduced to what the CLI prints.
//
// The fields are snake_case because that is the wire format; a struct that only names the fields it
// needs is deliberate, so a field the server adds is not a change here.
type SearchPage struct {
	Skills     []Card `json:"skills"`
	NextCursor string `json:"next_cursor"`
	// Note is why the answer is not the one that was asked for — today only "semantic search fell
	// back to keyword" (v19). Empty on an ordinary answer; when it is set the command prints it, so
	// a keyword result is never mistaken for a semantic one.
	Note string `json:"note"`
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
	// Score is the cosine similarity a semantic search ranked this row by (v19), or nil on the
	// keyword path. A pointer because zero is a real similarity and "no score" is a different fact.
	Score *float64 `json:"score"`
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

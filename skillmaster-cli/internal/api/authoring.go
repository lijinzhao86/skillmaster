package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
)

// The CLI's authoring half (v10): creating a skill from nothing, reading the base a first write
// would start from, creating a draft with that first change, and the person-level access verbs.
//
// They live beside the rest of the API client rather than in their own package because they are the
// same client, the same token and the same envelope; a second package would only be a second place
// to change when the wire moves.

// CreatedSkill is what creating a skill answers.
type CreatedSkill struct {
	Namespace   string `json:"namespace"`
	Name        string `json:"name"`
	VersionCode string `json:"version_code"`
	VersionName string `json:"version_name"`
	State       string `json:"state"`
}

// DraftBase is the reference a first write starts from; Base is nil when the base is blank.
type DraftBase struct {
	Base *DraftBaseRef `json:"base"`
}

type DraftBaseRef struct {
	Pin    string `json:"pin"`
	Digest string `json:"digest"`
}

// DraftEdit is the one change the create-draft request carries. The server accepts write, delete
// and rename; anything else is refused there rather than guessed at here.
type DraftEdit struct {
	Op   string `json:"op"`
	Path string `json:"path"`
	Text string `json:"text"`
	From string `json:"from,omitempty"`
	To   string `json:"to,omitempty"`
}

// CreateDraftRequest is the wire body: the labels, the base the caller read, and the edit.
type CreateDraftRequest struct {
	Name       string    `json:"name"`
	Note       string    `json:"note,omitempty"`
	From       string    `json:"from,omitempty"`
	FromDigest string    `json:"from_digest,omitempty"`
	Edit       DraftEdit `json:"edit"`
}

// CreatedDraft is the row the create answered with.
type CreatedDraft struct {
	Version struct {
		Code      string `json:"code"`
		DraftName string `json:"draft_name"`
		Digest    string `json:"digest"`
		State     string `json:"state"`
	} `json:"version"`
}

// Grant is one row of the collaborator list.
type Grant struct {
	Handle    string `json:"handle"`
	Role      string `json:"role"`
	CreatedAt string `json:"created_at"`
}

type GrantsPage struct {
	Grants   []Grant `json:"grants"`
	Total    int     `json:"total"`
	Page     int     `json:"page"`
	PageSize int     `json:"page_size"`
}

// CreateSkill creates a skill and its first draft in one request (v10).
func (c Client) CreateSkill(ctx context.Context, name, description, versionName string) (CreatedSkill, error) {
	payload, err := json.Marshal(map[string]string{
		"name": name, "description": description, "version_name": versionName,
	})
	if err != nil {
		return CreatedSkill{}, fmt.Errorf("编码请求：%w", err)
	}
	raw, err := c.authorSend(ctx, http.MethodPost, "/api/v1/author/skills", payload, "",
		"application/json")
	if err != nil {
		return CreatedSkill{}, err
	}
	var created CreatedSkill
	if err := json.Unmarshal(raw, &created); err != nil {
		return CreatedSkill{}, fmt.Errorf("服务端没有返回一个建好的 skill：%w", err)
	}
	return created, nil
}

// DraftBaseOf reads the base a first write would start from.
func (c Client) DraftBaseOf(ctx context.Context, address string) (DraftBase, error) {
	base, err := SkillPath(address)
	if err != nil {
		return DraftBase{}, err
	}
	raw, err := c.authorSend(ctx, http.MethodGet,
		"/api/v1/author"+trimV1(base)+"/draft-base", nil, "", "")
	if err != nil {
		return DraftBase{}, err
	}
	var answer DraftBase
	if err := json.Unmarshal(raw, &answer); err != nil {
		return DraftBase{}, fmt.Errorf("服务端没有返回一个底稿：%w", err)
	}
	return answer, nil
}

// CreateDraft creates a draft from a base plus one change.
func (c Client) CreateDraft(ctx context.Context, address string,
	request CreateDraftRequest) (CreatedDraft, error) {
	base, err := SkillPath(address)
	if err != nil {
		return CreatedDraft{}, err
	}
	payload, err := json.Marshal(request)
	if err != nil {
		return CreatedDraft{}, fmt.Errorf("编码请求：%w", err)
	}
	raw, err := c.authorSend(ctx, http.MethodPost,
		"/api/v1/author"+trimV1(base)+"/versions", payload, "", "application/json")
	if err != nil {
		return CreatedDraft{}, err
	}
	var created CreatedDraft
	if err := json.Unmarshal(raw, &created); err != nil {
		return CreatedDraft{}, fmt.Errorf("服务端没有返回一份新草稿：%w", err)
	}
	return created, nil
}

// ListGrants reads one page of the collaborator list.
func (c Client) ListGrants(ctx context.Context, address, query string,
	page int) (GrantsPage, error) {
	base, err := SkillPath(address)
	if err != nil {
		return GrantsPage{}, err
	}
	path := base + "/grants"
	params := url.Values{}
	if query != "" {
		params.Set("q", query)
	}
	if page > 0 {
		params.Set("p", strconv.Itoa(page))
	}
	if encoded := params.Encode(); encoded != "" {
		path += "?" + encoded
	}
	raw, err := c.authorSend(ctx, http.MethodGet, path, nil, "", "")
	if err != nil {
		return GrantsPage{}, err
	}
	var result GrantsPage
	if err := json.Unmarshal(raw, &result); err != nil {
		return GrantsPage{}, fmt.Errorf("服务端没有返回一份共享列表：%w", err)
	}
	return result, nil
}

// RevokeShare withdraws one grant. Idempotent on the server: no grant is still a success.
func (c Client) RevokeShare(ctx context.Context, address, handle string) error {
	base, err := SkillPath(address)
	if err != nil {
		return err
	}
	_, err = c.authorSend(ctx, http.MethodDelete,
		base+"/grants/"+url.PathEscape(handle), nil, "", "")
	return err
}

// RequestAccess asks for a rung on a skill the caller can see.
func (c Client) RequestAccess(ctx context.Context, address, rung string) (string, error) {
	base, err := SkillPath(address)
	if err != nil {
		return "", err
	}
	payload, err := json.Marshal(map[string]string{"rung": rung})
	if err != nil {
		return "", fmt.Errorf("编码请求：%w", err)
	}
	raw, err := c.authorSend(ctx, http.MethodPost, base+"/access-requests", payload, "",
		"application/json")
	if err != nil {
		return "", err
	}
	var answer struct {
		Rung string `json:"rung"`
	}
	if err := json.Unmarshal(raw, &answer); err != nil {
		return "", fmt.Errorf("服务端没有返回申请结果：%w", err)
	}
	return answer.Rung, nil
}

// PendingAccess reads what the caller is waiting for; nil means nothing is pending.
func (c Client) PendingAccess(ctx context.Context, address string) (*string, error) {
	base, err := SkillPath(address)
	if err != nil {
		return nil, err
	}
	raw, err := c.authorSend(ctx, http.MethodGet, base+"/access-requests/me", nil, "", "")
	if err != nil {
		return nil, err
	}
	var answer struct {
		PendingRung *string `json:"pending_rung"`
	}
	if err := json.Unmarshal(raw, &answer); err != nil {
		return nil, fmt.Errorf("服务端没有返回申请状态：%w", err)
	}
	return answer.PendingRung, nil
}

// WithdrawAccess cancels the caller's own request. The server answers 204 whether or not one was
// there, which is what makes retrying safe.
func (c Client) WithdrawAccess(ctx context.Context, address string) error {
	base, err := SkillPath(address)
	if err != nil {
		return err
	}
	_, err = c.authorSend(ctx, http.MethodDelete, base+"/access-requests/me", nil, "", "")
	return err
}

// trimV1 turns `/api/v1/skills/ns/name` into `/skills/ns/name`, the tail the author prefix extends.
func trimV1(path string) string {
	const prefix = "/api/v1"
	if len(path) >= len(prefix) && path[:len(prefix)] == prefix {
		return path[len(prefix):]
	}
	return path
}

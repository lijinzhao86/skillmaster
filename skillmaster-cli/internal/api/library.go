package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
)

// This file is v14's half of the API client: the library, the two tag layers, the category
// vocabulary, and restoring a soft-deleted skill (PRD §CLI 命令面, TD §主契约).
//
// **Every one of these requests goes through `request`, and that is a decision rather than
// plumbing.** The older read and write helpers grew their own answers for 404 (a bare ErrNotFound, a
// sentence, or a body with the status in front of it); the v14 refusals are read by their **code**
// (`SKILL_NOT_JOINABLE`, `LIBRARY_ENTRY_DORMANT`, `TAG_NOT_ATTACHED`), and a code is only readable
// where the envelope is kept. `statusError` already keeps it, so these calls hand it every status
// with something to say and let the command layer translate the code into a sentence.

// Tag is one public or free-form tag: the normalised key the server stores and the spelling its
// author used. The command layer prints Name; the key never reaches a person.
type Tag struct {
	Key  string `json:"key"`
	Name string `json:"name"`
}

// Category is the one shelf a skill is on (ADR 0078).
type Category struct {
	Key  string `json:"key"`
	Name string `json:"name"`
}

// CategoryRow is one row of the platform vocabulary: a category, whether it may be used, and how
// many discoverable skills carry it.
type CategoryRow struct {
	Key         string `json:"key"`
	Name        string `json:"name"`
	State       string `json:"state"`
	PublicTotal int    `json:"public_total"`
}

// TagCount is one row of the public-tag vocabulary.
type TagCount struct {
	Key         string `json:"key"`
	Name        string `json:"name"`
	PublicTotal int    `json:"public_total"`
}

// MyTag is a definition from the caller's own tag library, with the description that tells a person
// (or an agent) when to use it and with how many effective attachments it currently has.
type MyTag struct {
	ID          string  `json:"id"`
	Name        string  `json:"name"`
	Description *string `json:"description"`
	UsedIn      int     `json:"used_in"`
}

// MyTagSection is the private half of a skill's tags. Readable is false when the caller's library
// entry is not effective, and Reason says which way — `not_in_library` or `dormant`.
type MyTagSection struct {
	Readable bool    `json:"readable"`
	Reason   *string `json:"reason"`
	Tags     []MyTag `json:"tags"`
}

// SkillTags is the three groups `skill tags` reads.
type SkillTags struct {
	Category   *Category    `json:"category"`
	PublicTags []Tag        `json:"public_tags"`
	MyTags     MyTagSection `json:"my_tags"`
}

// PublicTagPage is one page of the public-tag vocabulary.
type PublicTagPage struct {
	Tags       []TagCount `json:"tags"`
	NextCursor string     `json:"next_cursor"`
}

// Change is what the small v14 writes answer with. The contract does not require a body — several of
// these answers are 204 — so `changed` is read when it is there and assumed otherwise. It exists
// because the PRD asks a no-op to say so (「已经贴过」/「已经是这个分类」/「已经在你的库里」) and a
// status code cannot say that on its own.
type Change struct {
	Changed bool
}

func changeOf(body []byte) Change {
	var answer struct {
		Changed *bool `json:"changed"`
	}
	if len(body) > 0 && json.Unmarshal(body, &answer) == nil && answer.Changed != nil {
		return Change{Changed: *answer.Changed}
	}
	return Change{Changed: true}
}

// QueryOptions is one question to `GET /api/v1/skills`.
//
// **The filter values are the names a person typed.** The API plane resolves them server-side —
// categories, private-tag definitions and public-tag keys all have one reader there — so a client
// that sent a key or a definition id would be addressing a name that does not exist (TD §接口).
type QueryOptions struct {
	Scope      string
	Query      string
	Namespaces []string

	Categories []string
	MyTags     []string
	PublicTags []string
	Sources    []string

	Sort   string
	Show   string
	Cursor string
	Limit  int
}

// QuerySkills reads one page of either the library or the public domain.
func (c Client) QuerySkills(ctx context.Context, options QueryOptions) (SearchPage, error) {
	query := url.Values{}
	if options.Scope != "" {
		query.Set("scope", options.Scope)
	}
	if options.Query != "" {
		query.Set("q", options.Query)
	}
	// Repeated rather than comma-joined: that is the shape the endpoint reads (`@RequestParam
	// List<String>`), and a joining convention here would be a second grammar for the same thing.
	for _, slug := range options.Namespaces {
		query.Add("namespace", slug)
	}
	for _, key := range options.Categories {
		query.Add("category", key)
	}
	for _, name := range options.MyTags {
		query.Add("my_tag", name)
	}
	for _, name := range options.PublicTags {
		query.Add("public_tag", name)
	}
	for _, source := range options.Sources {
		query.Add("source", source)
	}
	if options.Sort != "" {
		query.Set("sort", options.Sort)
	}
	if options.Show != "" {
		query.Set("show", options.Show)
	}
	if options.Cursor != "" {
		query.Set("cursor", options.Cursor)
	}
	limit := options.Limit
	if limit == 0 {
		limit = ListPageSize
	}
	query.Set("limit", strconv.Itoa(limit))

	body, err := c.request(ctx, http.MethodGet, "/api/v1/skills?"+query.Encode(), nil)
	if err != nil {
		return SearchPage{}, err
	}
	var page SearchPage
	if err := json.Unmarshal(body, &page); err != nil {
		return SearchPage{}, fmt.Errorf("列表响应不是 JSON：%w", err)
	}
	return page, nil
}

// Categories reads the platform vocabulary: enabled and stopped seed words, retired ones absent.
func (c Client) Categories(ctx context.Context) ([]CategoryRow, error) {
	body, err := c.request(ctx, http.MethodGet, "/api/v1/categories", nil)
	if err != nil {
		return nil, err
	}
	var answer struct {
		Categories []CategoryRow `json:"categories"`
	}
	if err := json.Unmarshal(body, &answer); err != nil {
		return nil, fmt.Errorf("分类响应不是 JSON：%w", err)
	}
	return answer.Categories, nil
}

// PublicTags reads one page of the public-tag vocabulary. `--all` is the command layer's loop; this
// method carries the cursor exactly as it came.
func (c Client) PublicTags(ctx context.Context, query, cursor string) (PublicTagPage, error) {
	params := url.Values{}
	if query != "" {
		params.Set("q", query)
	}
	params.Set("limit", strconv.Itoa(ListPageSize))
	if cursor != "" {
		params.Set("cursor", cursor)
	}
	body, err := c.request(ctx, http.MethodGet, "/api/v1/public-tags?"+params.Encode(), nil)
	if err != nil {
		return PublicTagPage{}, err
	}
	var page PublicTagPage
	if err := json.Unmarshal(body, &page); err != nil {
		return PublicTagPage{}, fmt.Errorf("公开标签响应不是 JSON：%w", err)
	}
	return page, nil
}

// MyTags reads every definition the caller has (at most 100, so one answer).
func (c Client) MyTags(ctx context.Context, query string) ([]MyTag, error) {
	path := "/api/v1/me/tags"
	if query != "" {
		path += "?q=" + url.QueryEscape(query)
	}
	body, err := c.request(ctx, http.MethodGet, path, nil)
	if err != nil {
		return nil, err
	}
	var answer struct {
		Tags []MyTag `json:"tags"`
	}
	if err := json.Unmarshal(body, &answer); err != nil {
		return nil, fmt.Errorf("我的标签响应不是 JSON：%w", err)
	}
	return answer.Tags, nil
}

// ResolveMyTag asks the server for the definition a typed word names.
//
// **The server decides what "the same word" means** (NFKC, trim, collapse, lower-case). Comparing
// names here would be a second copy of that rule, and a person who types `pdf` after creating
// `PDF` would be told their tag does not exist.
func (c Client) ResolveMyTag(ctx context.Context, name string) (MyTag, bool, error) {
	path := "/api/v1/me/tags?name=" + url.QueryEscape(name)
	body, err := c.request(ctx, http.MethodGet, path, nil)
	if err != nil {
		return MyTag{}, false, err
	}
	var answer struct {
		Tags []MyTag `json:"tags"`
	}
	if err := json.Unmarshal(body, &answer); err != nil {
		return MyTag{}, false, fmt.Errorf("我的标签响应不是 JSON：%w", err)
	}
	if len(answer.Tags) == 0 {
		return MyTag{}, false, nil
	}
	return answer.Tags[0], true, nil
}

// CreateMyTag makes a definition without attaching it to any skill.
func (c Client) CreateMyTag(ctx context.Context, name string, description *string) (Change, error) {
	payload := map[string]any{"name": name}
	if description != nil {
		payload["description"] = *description
	}
	body, err := c.sendJSON(ctx, http.MethodPost, "/api/v1/me/tags", payload)
	if err != nil {
		return Change{}, err
	}
	return changeOf(body), nil
}

// UpdateMyTag renames a definition or writes its description; either half may be omitted. An empty
// description is sent as an empty string and means "clear it" (TD Q6).
func (c Client) UpdateMyTag(ctx context.Context, id string, name, description *string) (Change, error) {
	payload := map[string]any{}
	if name != nil {
		payload["name"] = *name
	}
	if description != nil {
		payload["description"] = *description
	}
	body, err := c.sendJSON(ctx, http.MethodPatch, "/api/v1/me/tags/"+url.PathEscape(id), payload)
	if err != nil {
		return Change{}, err
	}
	return changeOf(body), nil
}

// Tags reads the three groups of one skill.
func (c Client) Tags(ctx context.Context, address string) (SkillTags, error) {
	path, err := SkillPath(address)
	if err != nil {
		return SkillTags{}, err
	}
	body, err := c.request(ctx, http.MethodGet, path+"/tags", nil)
	if err != nil {
		return SkillTags{}, err
	}
	var tags SkillTags
	if err := json.Unmarshal(body, &tags); err != nil {
		return SkillTags{}, fmt.Errorf("标签响应不是 JSON：%w", err)
	}
	return tags, nil
}

// AttachTag ties one tag to one skill. `public` selects the author's layer; the default is the
// caller's private layer (PRD §CLI 命令面 §`skill`).
func (c Client) AttachTag(ctx context.Context, address, name string, public bool) (Change, error) {
	path, err := tagPath(address, public)
	if err != nil {
		return Change{}, err
	}
	body, err := c.sendJSON(ctx, http.MethodPost, path, map[string]any{"name": name})
	if err != nil {
		return Change{}, err
	}
	return changeOf(body), nil
}

// DetachTag removes one tag from one skill. The name travels in the query, as the route spells it.
func (c Client) DetachTag(ctx context.Context, address, name string, public bool) (Change, error) {
	path, err := tagPath(address, public)
	if err != nil {
		return Change{}, err
	}
	body, err := c.request(ctx, http.MethodDelete, path+"?name="+url.QueryEscape(name), nil)
	if err != nil {
		return Change{}, err
	}
	return changeOf(body), nil
}

func tagPath(address string, public bool) (string, error) {
	path, err := SkillPath(address)
	if err != nil {
		return "", err
	}
	if public {
		return path + "/tags/public", nil
	}
	return path + "/tags/my", nil
}

// SetCategory puts a skill on one shelf, replacing whatever it was on.
func (c Client) SetCategory(ctx context.Context, address, key string) (Change, error) {
	path, err := SkillPath(address)
	if err != nil {
		return Change{}, err
	}
	body, err := c.sendJSON(ctx, http.MethodPut, path+"/category", map[string]any{"key": key})
	if err != nil {
		return Change{}, err
	}
	return changeOf(body), nil
}

// ClearCategory removes a skill's category.
func (c Client) ClearCategory(ctx context.Context, address string) (Change, error) {
	path, err := SkillPath(address)
	if err != nil {
		return Change{}, err
	}
	body, err := c.request(ctx, http.MethodDelete, path+"/category", nil)
	if err != nil {
		return Change{}, err
	}
	return changeOf(body), nil
}

// JoinLibrary adds a reference from the caller's library to a public skill. It is a reference: no
// content is copied, no version is created and no access changes (ADR 0082).
func (c Client) JoinLibrary(ctx context.Context, address string) (Change, error) {
	return c.library(ctx, http.MethodPost, address)
}

// LeaveLibrary deletes that reference. The private tags on the skill stay where they are and sleep.
func (c Client) LeaveLibrary(ctx context.Context, address string) (Change, error) {
	return c.library(ctx, http.MethodDelete, address)
}

func (c Client) library(ctx context.Context, method, address string) (Change, error) {
	path, err := SkillPath(address)
	if err != nil {
		return Change{}, err
	}
	body, err := c.request(ctx, method, path+"/library", nil)
	if err != nil {
		return Change{}, err
	}
	return changeOf(body), nil
}

// RestoreSkill brings a soft-deleted skill back. The owner's action; no body.
func (c Client) RestoreSkill(ctx context.Context, address string) (Change, error) {
	path, err := SkillPath(address)
	if err != nil {
		return Change{}, err
	}
	body, err := c.request(ctx, http.MethodPost, path+"/restore", nil)
	if err != nil {
		return Change{}, err
	}
	return changeOf(body), nil
}

// sendJSON marshals one small object and sends it.
func (c Client) sendJSON(ctx context.Context, method, path string, payload map[string]any) ([]byte, error) {
	encoded, err := json.Marshal(payload)
	if err != nil {
		return nil, fmt.Errorf("构造请求体：%w", err)
	}
	return c.request(ctx, method, path, encoded)
}

// request is this file's one request shape.
//
// **Every refusal keeps its envelope**, unlike the older read helpers: a 404 here is an answer the
// command layer distinguishes by code (`TAG_NOT_ATTACHED` versus an invisible skill), and only the
// envelope carries that distinction.
func (c Client) request(ctx context.Context, method, path string, payload []byte) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, callTimeout)
	defer cancel()

	var body io.Reader
	if payload != nil {
		body = bytes.NewReader(payload)
	}
	request, err := http.NewRequestWithContext(ctx, method, c.BaseURL+path, body)
	if err != nil {
		return nil, fmt.Errorf("构造请求：%w", err)
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Authorization", "Bearer "+c.Token)
	if payload != nil {
		request.Header.Set("Content-Type", "application/json")
	}

	shown := pathForError(path)
	resp, err := c.http().Do(request)
	if err != nil {
		return nil, fmt.Errorf("请求 %s 失败：%w", shown, err)
	}
	defer resp.Body.Close()
	answer, err := readAll(resp)
	if err != nil {
		return nil, fmt.Errorf("读取 %s 的响应：%w", shown, err)
	}
	if err := statusError(resp, answer); err != nil {
		return nil, err
	}
	return answer, nil
}

// The v14 refusal codes the command layer translates into a sentence (TD §错误形状).
//
// **Upper case here, lower case on the wire**, which is why the comparison folds case. The design
// names them in upper case; the envelope, like every code before v14, spells them lower case. The
// CLI is right against either without having to know which document a server was built from.
const (
	CodeSkillNotJoinable        = "SKILL_NOT_JOINABLE"
	CodeLibraryReferenceMissing = "LIBRARY_REFERENCE_MISSING"
	CodeLibraryEntryDormant     = "LIBRARY_ENTRY_DORMANT"
	CodeTagNotAttached          = "TAG_NOT_ATTACHED"
	CodeTagDefinitionNotFound   = "TAG_DEFINITION_NOT_FOUND"
)

// A caller's tag spelling travels as typed, and the server compares normalised keys (TD
// §规范化与计数). Nothing here normalises: a second implementation is how the two come to disagree
// about whether `PDF` and `pdf` are one word.

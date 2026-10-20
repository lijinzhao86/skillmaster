// The author plane: the version commands an agent may run with its own credential.
//
// **This is a second plane, not a second spelling of the consumer one.** The consumer endpoints
// (`/api/v1/skills/...`) answer what a caller may invoke; these answer what an author may see and
// change about the versions of a skill — drafts, revoked and discarded rows included, filtered by
// the caller's rung. The server keeps the two apart (Bearer + scope on both, but the rung is checked
// here), and so does this file: its shapes are the author API's own, and nothing from `/web` leaks
// in.
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
	"strings"
)

// AuthorSkill names the skill a version belongs to.
type AuthorSkill struct {
	Namespace string `json:"namespace"`
	Name      string `json:"name"`
	Address   string `json:"address"`
}

// AuthorVersion is one version record on the author plane.
//
// The nullable fields are pointers because the wire distinguishes "absent" from "empty": a draft
// has no version number, a version with no note has `null`, and the CLI's `--json` has to echo the
// distinction rather than turn it into an empty string.
type AuthorVersion struct {
	Address          string  `json:"address"`
	Pin              string  `json:"pin"`
	Name             *string `json:"name"`
	Version          *string `json:"version"`
	Status           string  `json:"status"`
	Note             *string `json:"note"`
	SubmittedBy      *string `json:"submitted_by"`
	SubmittedAt      *string `json:"submitted_at"`
	FirstPublishedAt *string `json:"first_published_at"`
	StateAt          *string `json:"state_at"`
	RevokedReason    *string `json:"revoked_reason"`
	Digest           string  `json:"digest"`
	Files            int     `json:"files"`
	Bytes            int64   `json:"bytes"`
}

// AuthorCounts is the list screen's unfiltered tally, in the same reading as the web's pills.
type AuthorCounts struct {
	All       int `json:"all"`
	Live      int `json:"live"`
	Draft     int `json:"draft"`
	History   int `json:"history"`
	Revoked   int `json:"revoked"`
	Discarded int `json:"discarded"`
}

// AuthorVersionList is the answer to `version list`.
type AuthorVersionList struct {
	Skill    AuthorSkill     `json:"skill"`
	Versions []AuthorVersion `json:"versions"`
	Counts   AuthorCounts    `json:"counts"`
	Next     *string         `json:"next"`
}

// AuthorVersionEnvelope is the answer to `version show`.
type AuthorVersionEnvelope struct {
	Skill   AuthorSkill   `json:"skill"`
	Version AuthorVersion `json:"version"`
}

// AuthorWriteResult is the answer to rename, note and discard.
type AuthorWriteResult struct {
	Ok      bool          `json:"ok"`
	Version AuthorVersion `json:"version"`
}

// AuthorVersionQuery is what `version list` may narrow by.
//
// Statuses are the derived five-value names, not the stored state: the server filters on what the
// screen shows, so the CLI passes through what the person typed.
type AuthorVersionQuery struct {
	Statuses []string
	Query    string
	Limit    int
	Cursor   string
}

// AuthorDiff is the comparison between two versions.
//
// The v6 response adds `next` and, with `file=`, the hunks of one file only. Content is nullable
// per file: `hunks == nil` means nothing was rendered (binary, over the size cap, or not in this
// batch), and `added` / `removed` are null for exactly the same files.
type AuthorDiff struct {
	From      string     `json:"from"`
	To        string     `json:"to"`
	Truncated bool       `json:"truncated"`
	Next      *string    `json:"next"`
	Files     []DiffFile `json:"files"`
}

type DiffFile struct {
	Relpath string     `json:"relpath"`
	Status  string     `json:"status"`
	Binary  bool       `json:"binary"`
	Size    *int64     `json:"size"`
	Added   *int       `json:"added"`
	Removed *int       `json:"removed"`
	Hunks   []DiffHunk `json:"hunks"`
}

type DiffHunk struct {
	Header string   `json:"header"`
	Lines  []string `json:"lines"`
}

// AuthorVersions lists the versions this credential may see, newest first.
func (c Client) AuthorVersions(ctx context.Context, address string, query AuthorVersionQuery) (AuthorVersionList, error) {
	base, err := authorPath(address)
	if err != nil {
		return AuthorVersionList{}, err
	}
	values := url.Values{}
	for _, status := range query.Statuses {
		values.Add("status", status)
	}
	if query.Query != "" {
		values.Set("q", query.Query)
	}
	limit := query.Limit
	if limit == 0 {
		limit = 50
	}
	values.Set("limit", strconv.Itoa(limit))
	if query.Cursor != "" {
		values.Set("cursor", query.Cursor)
	}

	body, err := c.authorRequest(ctx, http.MethodGet, base+"/versions?"+values.Encode(), nil)
	if err != nil {
		return AuthorVersionList{}, err
	}
	var listed AuthorVersionList
	if err := json.Unmarshal(body, &listed); err != nil {
		return AuthorVersionList{}, fmt.Errorf("版本列表响应不是 JSON：%w", err)
	}
	return listed, nil
}

// ReadAuthorVersion reads one version's facts.
func (c Client) ReadAuthorVersion(ctx context.Context, address, pin string) (AuthorVersionEnvelope, error) {
	path, err := authorVersionPath(address, pin)
	if err != nil {
		return AuthorVersionEnvelope{}, err
	}
	body, err := c.authorRequest(ctx, http.MethodGet, path, nil)
	if err != nil {
		return AuthorVersionEnvelope{}, err
	}
	var envelope AuthorVersionEnvelope
	if err := json.Unmarshal(body, &envelope); err != nil {
		return AuthorVersionEnvelope{}, fmt.Errorf("版本响应不是 JSON：%w", err)
	}
	return envelope, nil
}

// AuthorDiff compares one version with a baseline.
//
// `from` empty leaves the baseline to the server, which defaults to the previous version — the v6
// default. `file` restricts the answer to one file's hunks; the server returns that file only, so
// the caller never has to walk `next` for a single file.
func (c Client) AuthorDiff(ctx context.Context, address, pin, from, file, cursor string) (AuthorDiff, error) {
	path, err := authorVersionPath(address, pin)
	if err != nil {
		return AuthorDiff{}, err
	}
	values := url.Values{}
	if from != "" {
		values.Set("from", from)
	}
	if file != "" {
		values.Set("file", file)
	}
	if cursor != "" {
		values.Set("cursor", cursor)
	}
	if encoded := values.Encode(); encoded != "" {
		path += "/diff?" + encoded
	} else {
		path += "/diff"
	}
	body, err := c.authorRequest(ctx, http.MethodGet, path, nil)
	if err != nil {
		return AuthorDiff{}, err
	}
	var diff AuthorDiff
	if err := json.Unmarshal(body, &diff); err != nil {
		return AuthorDiff{}, fmt.Errorf("改动响应不是 JSON：%w", err)
	}
	return diff, nil
}

// AuthorRename replaces a draft's name.
func (c Client) AuthorRename(ctx context.Context, address, pin, name string) (AuthorWriteResult, error) {
	return c.authorWrite(ctx, address, pin, "name", map[string]string{"name": name})
}

// AuthorNote replaces a draft's note. An empty note clears it.
func (c Client) AuthorNote(ctx context.Context, address, pin, note string) (AuthorWriteResult, error) {
	return c.authorWrite(ctx, address, pin, "note", map[string]string{"note": note})
}

func (c Client) authorWrite(ctx context.Context, address, pin, field string, payload map[string]string) (AuthorWriteResult, error) {
	path, err := authorVersionPath(address, pin)
	if err != nil {
		return AuthorWriteResult{}, err
	}
	encoded, err := json.Marshal(payload)
	if err != nil {
		return AuthorWriteResult{}, fmt.Errorf("构造请求体：%w", err)
	}
	body, err := c.authorRequest(ctx, http.MethodPatch, path+"/"+field, encoded)
	if err != nil {
		return AuthorWriteResult{}, err
	}
	var result AuthorWriteResult
	if err := json.Unmarshal(body, &result); err != nil {
		return AuthorWriteResult{}, fmt.Errorf("写操作响应不是 JSON：%w", err)
	}
	return result, nil
}

// AuthorDiscard ends a draft. The pin travels in the body, the way every POST action does.
func (c Client) AuthorDiscard(ctx context.Context, address, pin string) (AuthorWriteResult, error) {
	base, err := authorPath(address)
	if err != nil {
		return AuthorWriteResult{}, err
	}
	payload, err := json.Marshal(map[string]string{"version": pin})
	if err != nil {
		return AuthorWriteResult{}, fmt.Errorf("构造请求体：%w", err)
	}
	body, err := c.authorRequest(ctx, http.MethodPost, base+"/versions/discard", payload)
	if err != nil {
		return AuthorWriteResult{}, err
	}
	var result AuthorWriteResult
	if err := json.Unmarshal(body, &result); err != nil {
		return AuthorWriteResult{}, fmt.Errorf("丢弃响应不是 JSON：%w", err)
	}
	return result, nil
}

// AuthorFiles lists one version's files, including a draft's or a revoked version's.
//
// The response shape is the author API's own; the two spellings accepted here are the one the
// server writes (`{"files": [...]}`) and a bare array, so a client of either shape reads it.
func (c Client) AuthorFiles(ctx context.Context, address, pin string) ([]File, error) {
	path, err := authorVersionPath(address, pin)
	if err != nil {
		return nil, err
	}
	body, err := c.authorRequest(ctx, http.MethodGet, path+"/files", nil)
	if err != nil {
		return nil, err
	}
	var wrapped struct {
		Files []File `json:"files"`
	}
	if json.Unmarshal(body, &wrapped) == nil && wrapped.Files != nil {
		return wrapped.Files, nil
	}
	var files []File
	if json.Unmarshal(body, &files) == nil {
		return files, nil
	}
	return nil, fmt.Errorf("文件清单响应不是 JSON：%s", strings.TrimSpace(string(body)))
}

// AuthorFile reads one file's bytes at a named version.
func (c Client) AuthorFile(ctx context.Context, address, pin, relpath string) ([]byte, error) {
	path, err := authorVersionPath(address, pin)
	if err != nil {
		return nil, err
	}
	segments := strings.Split(relpath, "/")
	for index, segment := range segments {
		segments[index] = url.PathEscape(segment)
	}
	return c.authorRequest(ctx, http.MethodGet, path+"/files/"+strings.Join(segments, "/"), nil)
}

func authorPath(address string) (string, error) {
	path, err := SkillPath(address)
	if err != nil {
		return "", err
	}
	// SkillPath has already validated and escaped both segments; only the plane differs.
	return "/api/v1/author" + strings.TrimPrefix(path, "/api/v1"), nil
}

func authorVersionPath(address, pin string) (string, error) {
	base, err := authorPath(address)
	if err != nil {
		return "", err
	}
	if pin == "" {
		return "", fmt.Errorf("要写明是哪一版：地址后面加 @<版本>")
	}
	return base + "/versions/" + url.PathEscape(pin), nil
}

// authorRequest is the author plane's one request shape.
//
// A 403 is a `Denial` rather than a status code because the two reasons a 403 carries send a person
// to different places. Every other refusal keeps its envelope in a `ServerError`, which is what lets
// `--json` report the server's own code instead of a number.
func (c Client) authorRequest(ctx context.Context, method, path string, payload []byte) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, callTimeout)
	defer cancel()

	var body io.Reader
	if payload != nil {
		body = bytes.NewReader(payload)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.BaseURL+path, body)
	if err != nil {
		return nil, fmt.Errorf("构造请求：%w", err)
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.Token)
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
	}

	resp, err := c.http().Do(req)
	if err != nil {
		return nil, fmt.Errorf("请求 %s 失败：%w", path, err)
	}
	defer resp.Body.Close()
	answer, err := readAll(resp)
	if err != nil {
		return nil, fmt.Errorf("读取 %s 的响应：%w", path, err)
	}

	switch resp.StatusCode {
	case http.StatusOK, http.StatusCreated, http.StatusNoContent:
		return answer, nil
	case http.StatusUnauthorized:
		return nil, refusal(answer)
	case http.StatusForbidden:
		return nil, denial(resp, answer)
	default:
		return nil, serverError(resp.StatusCode, answer)
	}
}

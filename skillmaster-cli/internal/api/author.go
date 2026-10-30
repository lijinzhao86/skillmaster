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
	// Access and Permissions mirror the consumer plane's fields (v23): the same rung, computed from
	// the same `Access`, so `skill invoke` prints one header whichever plane answered.
	Access      string   `json:"access"`
	Permissions []string `json:"permissions"`
	// Capabilities mirrors the consumer plane's field (v34, ADR 0119): the same rung, the same
	// commands, so the header looks the same whichever plane answered.
	Capabilities []Capability `json:"capabilities"`
}

// AuthorVersion is one version record on the author plane.
//
// The nullable fields are pointers because the wire distinguishes "absent" from "empty": a draft
// has no version number, a version with no note has `null`, and the CLI's `--json` has to echo the
// distinction rather than turn it into an empty string.
type AuthorVersion struct {
	Address          string  `json:"address"`
	Pin              string  `json:"pin"`
	Code             string  `json:"code"`
	Name             *string `json:"name"`
	Version          *string `json:"version"`
	Status           string  `json:"status"`
	Note             *string `json:"note"`
	SubmittedBy      *string `json:"submitted_by"`
	SubmittedAt      *string `json:"submitted_at"`
	FirstPublishedAt *string `json:"first_published_at"`
	StateAt          *string `json:"state_at"`
	RevokedReason    *string `json:"revoked_reason"`
	// The kind a draft declares (v18, ADR 0091) — "major" / "minor" / "patch", or null when none
	// was declared. **Not a version number**: the number is computed at publish time from this and
	// whatever is live then.
	ReleaseType *string `json:"release_type"`
	Digest      string  `json:"digest"`
	Files       int     `json:"files"`
	Bytes       int64   `json:"bytes"`
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

// PublishQueue is what `skills pending` reads: every draft waiting on a skill this caller may
// publish from (v18).
//
// **The same shape the console gets.** One question, one spelling — the CLI cannot use the web
// plane's session, so it reaches the author plane's twin route and the server answers with this one
// response record.
//
// The order is the server's (skills by their newest draft, drafts newest first) and the CLI does not
// re-sort it: two orders would eventually disagree.
type PublishQueue struct {
	Skills    []QueueSkill `json:"skills"`
	Truncated bool         `json:"truncated"`
}

// QueueSkill is one skill on the queue, with the drafts of it that are still waiting.
//
// Current is null when nothing has ever been published. When the skill has been published but that
// version carries no number (a pre-v11 row), Current.Version is empty — a different fact, and the
// command prints it differently.
type QueueSkill struct {
	Namespace string       `json:"namespace"`
	Name      string       `json:"name"`
	Current   *QueueNow    `json:"current"`
	Drafts    []QueueDraft `json:"drafts"`
}

// QueueNow is what is live for a skill.
type QueueNow struct {
	Version     *string `json:"version"`
	SubmittedAt *string `json:"submitted_at"`
}

// QueueDraft is one waiting draft.
//
// Code is the pin, bare: it is what a publish request takes, and no `draft:` prefix is ever added
// (ADR 0063). Name is null on a draft submitted before drafts had names.
type QueueDraft struct {
	Code        string  `json:"code"`
	Name        *string `json:"name"`
	ReleaseType *string `json:"release_type"`
	SubmittedAt string  `json:"submitted_at"`
}

// AuthorVersionList is the answer to `version list`.
type AuthorVersionList struct {
	Skill    AuthorSkill     `json:"skill"`
	Versions []AuthorVersion `json:"versions"`
	Counts   AuthorCounts    `json:"counts"`
	Next     *string         `json:"next,omitempty"`
}

// AuthorVersionEnvelope is the answer to `version show`.
type AuthorVersionEnvelope struct {
	Skill   AuthorSkill   `json:"skill"`
	Version AuthorVersion `json:"version"`
	// Compatibility is the author's `compatibility` for this version, lifted by the server, or empty.
	// It is beside `version` rather than inside `skill` because it describes the version, and it is
	// what lets a draft's header carry the same fifth line a published version's does (v23).
	Compatibility string `json:"compatibility"`
}

// AuthorWriteResult is the answer to rename, note and discard.
type AuthorWriteResult struct {
	Ok      bool          `json:"ok"`
	Version AuthorVersion `json:"version"`
}

// AuthorSnapshot is an author version read as the L1 shape `invoke` needs, so the command has one
// kind of value whether the pin went to the author plane or the consumer one.
func AuthorSnapshot(skill AuthorSkill, version AuthorVersion, compatibility string) Metadata {
	metadata := Metadata{Name: skill.Name, Compatibility: compatibility}
	metadata.Namespace.Slug = skill.Namespace
	if version.Version != nil {
		metadata.Version.Name = *version.Version
	}
	metadata.Version.Digest = version.Digest
	metadata.Access = skill.Access
	metadata.Permissions = skill.Permissions
	metadata.Capabilities = skill.Capabilities
	return metadata
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
// PublishQueue reads the publish queue from the author plane.
//
// **Its own route, not a filter on a listing.** "What is waiting" is a question about drafts, and
// the skill listing answers questions about skills — an already-published skill with a new draft is
// in the queue and is not in `skills list --show draft`, which means "never published".
func (c Client) PublishQueue(ctx context.Context) (PublishQueue, error) {
	body, err := c.authorRequest(ctx, http.MethodGet, APIPrefix+"/author/publish-queue", nil)
	if err != nil {
		return PublishQueue{}, err
	}
	var queue PublishQueue
	if err := json.Unmarshal(body, &queue); err != nil {
		return PublishQueue{}, fmt.Errorf("待上线队列不是 JSON：%w", err)
	}
	return queue, nil
}

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

// AuthorReleaseType declares how big a draft's change is (ADR 0091): major, minor or patch, or
// "none" to clear it. It is not a version number — the number is computed at publish time.
func (c Client) AuthorReleaseType(ctx context.Context, address, pin, releaseType string) (AuthorWriteResult, error) {
	return c.authorWrite(ctx, address, pin, "release-type", map[string]string{"release_type": releaseType})
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

// AuthorFileList is a version's manifest and the digest it belongs to.
//
// The ETag is the version digest — the same value a write must send back as If-Match — so one
// listing gives both the file names and the baseline a write needs.
type AuthorFileList struct {
	Files []File
	ETag  string
}

// AuthorFiles lists one version's files, including a draft's or a revoked version's.
//
// The response shape is the author API's own; the two spellings accepted here are the one the
// server writes (`{"files": [...]}`) and a bare array, so a client of either shape reads it.
func (c Client) AuthorFiles(ctx context.Context, address, pin string) ([]File, error) {
	listed, err := c.AuthorFileList(ctx, address, pin)
	if err != nil {
		return nil, err
	}
	return listed.Files, nil
}

// AuthorFileList is AuthorFiles with the response's ETag: the version digest a write's If-Match
// carries, so `files` followed by `write` needs no second request for the baseline.
func (c Client) AuthorFileList(ctx context.Context, address, pin string) (AuthorFileList, error) {
	path, err := authorVersionPath(address, pin)
	if err != nil {
		return AuthorFileList{}, err
	}
	resp, body, err := c.authorGet(ctx, path+"/files")
	if err != nil {
		return AuthorFileList{}, err
	}
	var wrapped struct {
		Files []File `json:"files"`
	}
	listed := AuthorFileList{ETag: resp.Header.Get("ETag")}
	if json.Unmarshal(body, &wrapped) == nil && wrapped.Files != nil {
		listed.Files = wrapped.Files
		return listed, nil
	}
	var files []File
	if json.Unmarshal(body, &files) == nil {
		listed.Files = files
		return listed, nil
	}
	return AuthorFileList{}, fmt.Errorf("文件清单响应不是 JSON：%s", strings.TrimSpace(string(body)))
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

// AuthorFileBytes reads one file and returns the response's ETag with it.
//
// The ETag is the version digest at the moment of the read: an `edit` sends it back as If-Match, so
// a concurrent change between the read and the write is a 409 rather than a silent overwrite.
func (c Client) AuthorFileBytes(ctx context.Context, address, pin, relpath string) ([]byte, string, error) {
	path, err := authorVersionPath(address, pin)
	if err != nil {
		return nil, "", err
	}
	segments := strings.Split(relpath, "/")
	for index, segment := range segments {
		segments[index] = url.PathEscape(segment)
	}
	resp, body, err := c.authorGet(ctx, path+"/files/"+strings.Join(segments, "/"))
	if err != nil {
		return nil, "", err
	}
	return body, resp.Header.Get("ETag"), nil
}

// AuthorWriteFile replaces or creates one file. content is the file's exact bytes; ifMatch is the
// digest the caller read (the server accepts a bare hex or its quoted ETag spelling).
func (c Client) AuthorWriteFile(ctx context.Context, address, pin, relpath string, content []byte,
	ifMatch string) (AuthorWriteResult, error) {
	path, err := authorVersionPath(address, pin)
	if err != nil {
		return AuthorWriteResult{}, err
	}
	segments := strings.Split(relpath, "/")
	for index, segment := range segments {
		segments[index] = url.PathEscape(segment)
	}
	answer, err := c.authorSend(ctx, http.MethodPut, path+"/files/"+strings.Join(segments, "/"),
		content, ifMatch, "application/octet-stream")
	if err != nil {
		return AuthorWriteResult{}, err
	}
	return decodeWriteResult(answer)
}

// AuthorDeleteFile removes one file of a draft.
func (c Client) AuthorDeleteFile(ctx context.Context, address, pin, relpath, ifMatch string) (AuthorWriteResult, error) {
	path, err := authorVersionPath(address, pin)
	if err != nil {
		return AuthorWriteResult{}, err
	}
	segments := strings.Split(relpath, "/")
	for index, segment := range segments {
		segments[index] = url.PathEscape(segment)
	}
	answer, err := c.authorSend(ctx, http.MethodDelete, path+"/files/"+strings.Join(segments, "/"),
		nil, ifMatch, "")
	if err != nil {
		return AuthorWriteResult{}, err
	}
	return decodeWriteResult(answer)
}

// AuthorMoveOrCopy renames or duplicates one path inside a draft. op is "mv" or "cp".
func (c Client) AuthorMoveOrCopy(ctx context.Context, address, pin, op, from, to, ifMatch string) (AuthorWriteResult, error) {
	base, err := authorVersionPath(address, pin)
	if err != nil {
		return AuthorWriteResult{}, err
	}
	payload, err := json.Marshal(map[string]string{"op": op, "from": from, "to": to})
	if err != nil {
		return AuthorWriteResult{}, fmt.Errorf("构造请求体：%w", err)
	}
	answer, err := c.authorSend(ctx, http.MethodPost, base+"/files", payload, ifMatch,
		"application/json")
	if err != nil {
		return AuthorWriteResult{}, err
	}
	return decodeWriteResult(answer)
}

func decodeWriteResult(answer []byte) (AuthorWriteResult, error) {
	var result AuthorWriteResult
	if err := json.Unmarshal(answer, &result); err != nil {
		return AuthorWriteResult{}, fmt.Errorf("写操作响应不是 JSON：%w", err)
	}
	return result, nil
}

// AuthorGrepResult is the server's answer: only the matching lines, never the files.
type AuthorGrepResult struct {
	Digest    string    `json:"digest"`
	Pin       string    `json:"pin"`
	Hits      []GrepHit `json:"hits"`
	Truncated bool      `json:"truncated"`
}

type GrepHit struct {
	Path string `json:"path"`
	Line int    `json:"line"`
	Text string `json:"text"`
	// Match is false for the context lines a request asked for with -A/-B/-C (v34).
	Match bool `json:"match"`
}

// GrepQuery is what one search asks for (v34). Everything here is computed on the server: the
// client renders the answer and nothing else (ADR 0118).
type GrepQuery struct {
	Patterns   []string
	Fixed      bool
	IgnoreCase bool
	PathPrefix string
	Before     int
	After      int
	Exclude    []string
	Limit      int
	Max        int
}

// AuthorGrep runs a scoped search over one draft.
func (c Client) AuthorGrep(ctx context.Context, address, pin string,
	query GrepQuery) (AuthorGrepResult, error) {
	base, err := authorVersionPath(address, pin)
	if err != nil {
		return AuthorGrepResult{}, err
	}
	values := url.Values{}
	for _, pattern := range query.Patterns {
		values.Add("pattern", pattern)
	}
	values.Set("fixed", strconv.FormatBool(query.Fixed))
	values.Set("ignore_case", strconv.FormatBool(query.IgnoreCase))
	if query.PathPrefix != "" {
		values.Set("path", query.PathPrefix)
	}
	for _, exclusion := range query.Exclude {
		values.Add("exclude", exclusion)
	}
	if query.Before > 0 {
		values.Set("before", strconv.Itoa(query.Before))
	}
	if query.After > 0 {
		values.Set("after", strconv.Itoa(query.After))
	}
	if query.Limit > 0 {
		values.Set("limit", strconv.Itoa(query.Limit))
	}
	if query.Max > 0 {
		values.Set("max", strconv.Itoa(query.Max))
	}
	body, err := c.authorRequest(ctx, http.MethodGet, base+"/grep?"+values.Encode(), nil)
	if err != nil {
		return AuthorGrepResult{}, err
	}
	var result AuthorGrepResult
	if err := json.Unmarshal(body, &result); err != nil {
		return AuthorGrepResult{}, fmt.Errorf("grep 响应不是 JSON：%w", err)
	}
	return result, nil
}

// AuthorCount is a version's counts, computed server-side.
type AuthorCount struct {
	Digest string `json:"digest"`
	Pin    string `json:"pin"`
	Files  []struct {
		Path  string `json:"path"`
		Lines int64  `json:"lines"`
		Words int64  `json:"words"`
		Bytes int64  `json:"bytes"`
	} `json:"files"`
	Total struct {
		Lines int64 `json:"lines"`
		Words int64 `json:"words"`
		Bytes int64 `json:"bytes"`
	} `json:"total"`
}

// AuthorCount counts one version, or the named paths of it.
func (c Client) AuthorCount(ctx context.Context, address, pin string, paths []string) (AuthorCount, error) {
	base, err := authorVersionPath(address, pin)
	if err != nil {
		return AuthorCount{}, err
	}
	values := url.Values{}
	for _, path := range paths {
		values.Add("path", path)
	}
	encoded := ""
	if len(paths) > 0 {
		encoded = "?" + values.Encode()
	}
	body, err := c.authorRequest(ctx, http.MethodGet, base+"/count"+encoded, nil)
	if err != nil {
		return AuthorCount{}, err
	}
	var result AuthorCount
	if err := json.Unmarshal(body, &result); err != nil {
		return AuthorCount{}, fmt.Errorf("count 响应不是 JSON：%w", err)
	}
	return result, nil
}

// StreamFile opens one file for a response written to stdout as it arrives.
//
// The bytes never go through readAll: `cat | head` must stop the transfer when the reader stops,
// and buffering the whole file first would make that impossible. The path is already built by the
// caller; the header names which version it resolves to.
func (c Client) StreamFile(ctx context.Context, path, header, value string) (io.ReadCloser, error) {
	ctx, cancel := context.WithCancel(ctx)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.BaseURL+path, nil)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("构造请求：%w", err)
	}
	req.Header.Set("Authorization", "Bearer "+c.Token)
	if header != "" {
		req.Header.Set(header, value)
	}
	shown := pathForError(path)
	resp, err := c.http().Do(req)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("请求 %s 失败：%w", shown, err)
	}
	if resp.StatusCode != http.StatusOK {
		body, readErr := readAll(resp)
		resp.Body.Close()
		cancel()
		if readErr != nil {
			return nil, fmt.Errorf("读取 %s 的响应：%w", shown, readErr)
		}
		return nil, statusError(resp, body)
	}
	return &streamBody{ReadCloser: resp.Body, cancel: cancel}, nil
}

type streamBody struct {
	io.ReadCloser
	cancel context.CancelFunc
}

func (s *streamBody) Close() error {
	err := s.ReadCloser.Close()
	s.cancel()
	return err
}

func authorPath(address string) (string, error) {
	path, err := SkillPath(address)
	if err != nil {
		return "", err
	}
	// SkillPath has already validated and escaped both segments; only the plane differs.
	return APIPrefix + "/author" + strings.TrimPrefix(path, APIPrefix), nil
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
	return c.authorSend(ctx, method, path, payload, "", "application/json")
}

// authorSend is the author plane's request shape with the two headers a write needs: If-Match,
// and a content type that is the file's own for a PUT.
func (c Client) authorSend(ctx context.Context, method, path string, payload []byte, ifMatch,
	contentType string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, callTimeout)
	defer cancel()

	resp, err := c.doAuthor(ctx, method, path, payload, ifMatch, contentType)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	answer, err := readAll(resp)
	if err != nil {
		return nil, fmt.Errorf("读取 %s 的响应：%w", pathForError(path), err)
	}
	if err := statusError(resp, answer); err != nil {
		return nil, err
	}
	return answer, nil
}

// authorGet is a GET whose headers the caller wants — the ETag a write sends back.
func (c Client) authorGet(ctx context.Context, path string) (*http.Response, []byte, error) {
	ctx, cancel := context.WithTimeout(ctx, callTimeout)
	defer cancel()

	resp, err := c.doAuthor(ctx, http.MethodGet, path, nil, "", "")
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()
	body, err := readAll(resp)
	if err != nil {
		return nil, nil, fmt.Errorf("读取 %s 的响应：%w", pathForError(path), err)
	}
	if err := statusError(resp, body); err != nil {
		return nil, nil, err
	}
	return resp, body, nil
}

func (c Client) doAuthor(ctx context.Context, method, path string, payload []byte, ifMatch,
	contentType string) (*http.Response, error) {
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
	if ifMatch != "" {
		req.Header.Set("If-Match", ifMatch)
	}
	if payload != nil && contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	resp, err := c.http().Do(req)
	if err != nil {
		return nil, fmt.Errorf("请求 %s 失败：%w", pathForError(path), err)
	}
	return resp, nil
}

// statusError maps the responses with something to say beyond their number.
func statusError(resp *http.Response, body []byte) error {
	switch resp.StatusCode {
	case http.StatusOK, http.StatusCreated, http.StatusNoContent:
		return nil
	case http.StatusUnauthorized:
		return refusal(body)
	case http.StatusForbidden:
		return denial(resp, body)
	default:
		return serverError(resp.StatusCode, body)
	}
}

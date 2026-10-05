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
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
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

// Detail reads the manifest of one address — §4.2's L1.
//
// The address is what the person typed: `namespace/name`, optionally with `@3` or `@sha256:…`. It
// goes into the path as it stands, because the server is what resolves the pin (ADR 0012) and a CLI
// that re-implemented that would be a second, differently-wrong answer.
func (c Client) Detail(ctx context.Context, address string) (Detail, error) {
	path, err := SkillPath(address)
	if err != nil {
		return Detail{}, err
	}
	body, err := c.get(ctx, path)
	if err != nil {
		return Detail{}, err
	}
	var detail Detail
	if err := json.Unmarshal(body, &detail); err != nil {
		return Detail{}, fmt.Errorf("详情响应不是 JSON：%w", err)
	}
	return detail, nil
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
	ctx, cancel := context.WithTimeout(ctx, callTimeout)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.BaseURL+path, nil)
	if err != nil {
		return nil, fmt.Errorf("构造请求：%w", err)
	}
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
	if resp.StatusCode == http.StatusNotFound {
		// The server answers 404 for "no such skill", "not visible to you" and "that version does
		// not exist" alike, deliberately (§4.2). A CLI that guessed which one it was would be
		// undoing that, so the message repeats the one thing the server did say.
		return nil, fmt.Errorf("找不到 %s（服务端不区分「不存在」与「你看不到」）", path)
	}
	if resp.StatusCode == http.StatusUnauthorized {
		return nil, fmt.Errorf("%w：%s", ErrUnauthorized, strings.TrimSpace(string(body)))
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("服务端答 %d：%s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	return body, nil
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
	Title       string `json:"title"`
	Description string `json:"description"`
	Namespace   string `json:"namespace"`
	Visibility  string `json:"visibility"`
	UpdatedAt   string `json:"updated_at"`
}

// Address is how a card is named back to the server — the two segments joined, which is also what a
// person types.
func (c Card) Address() string { return c.Namespace + "/" + c.Name }

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
		Number      int    `json:"number"`
		Digest      string `json:"digest"`
		PublishedAt string `json:"published_at"`
		FileCount   int    `json:"file_count"`
		TotalBytes  int64  `json:"total_bytes"`
		IsLatest    bool   `json:"is_latest"`
	} `json:"version"`
	Files []struct {
		Relpath  string `json:"relpath"`
		URI      string `json:"uri"`
		SHA256   string `json:"sha256"`
		Size     int64  `json:"size"`
		IsBinary bool   `json:"is_binary"`
	} `json:"files"`
	Resources struct {
		Body string `json:"body"`
	} `json:"resources"`
}

// Address is where this manifest lives, for printing.
func (d Detail) Address() string {
	return d.Namespace.Slug + "/" + d.Name + "@" + fmt.Sprint(d.Version.Number)
}

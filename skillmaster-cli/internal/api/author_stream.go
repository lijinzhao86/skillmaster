package api

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
)

// AuthorWriteFileStream replaces or creates one file from a reader (v39).
//
// The reader is sent as the request body with a declared length, so an upload at the ceiling never becomes
// a byte array in this process. It uses the archive budget rather than callTimeout: a large file on
// a slow link is unusual, not broken, and a two-minute deadline would call it a failure.
func (c Client) AuthorWriteFileStream(ctx context.Context, address, pin, relpath string,
	body io.Reader, size int64, ifMatch string) (AuthorWriteResult, error) {
	path, err := authorVersionPath(address, pin)
	if err != nil {
		return AuthorWriteResult{}, err
	}
	segments := strings.Split(relpath, "/")
	for index, segment := range segments {
		segments[index] = url.PathEscape(segment)
	}
	escaped := strings.Join(segments, "/")

	ctx, cancel := context.WithTimeout(ctx, archiveTimeout)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodPut,
		c.BaseURL+path+"/files/"+escaped, body)
	if err != nil {
		return AuthorWriteResult{}, fmt.Errorf("构造请求：%w", err)
	}
	req.ContentLength = size
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.Token)
	req.Header.Set("Content-Type", "application/octet-stream")
	if ifMatch != "" {
		req.Header.Set("If-Match", ifMatch)
	}
	resp, err := c.http().Do(req)
	if err != nil {
		return AuthorWriteResult{}, c.withHint(fmt.Errorf("请求 %s 失败：%w",
			pathForError(path+"/files/"+escaped), err))
	}
	answer, readErr := readAll(resp)
	resp.Body.Close()
	if readErr != nil {
		return AuthorWriteResult{}, fmt.Errorf("读取响应：%w", readErr)
	}
	if err := statusError(resp, answer); err != nil {
		return AuthorWriteResult{}, err
	}
	return decodeWriteResult(answer)
}

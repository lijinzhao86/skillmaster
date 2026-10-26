package api

import (
	"archive/zip"
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"time"
)

// archiveTimeout bounds the whole exchange — headers **and** the body — of an archive download.
//
// Longer than {@link callTimeout}, and deliberately so: the response is up to 16 MiB of content, and
// a link slow enough to take more than two minutes for that is unusual but not broken. What it is
// not is unbounded: without a deadline a proxy that sends 200 and then stalls leaves the command
// hanging with no error and no exit, which is the one failure a caller cannot see.
const archiveTimeout = 10 * time.Minute

// ArchiveStream is one version's zip, still arriving.
//
// The body is a stream rather than bytes: the CLI writes it straight to a temporary file, and a
// buffered read would double the memory for no benefit. The caller owns the body and must close it.
type ArchiveStream struct {
	Body   io.ReadCloser
	ETag   string // the version digest, quoted, exactly as the server sent it
	Length int64  // -1 when the server did not say
}

// StreamArchive opens `…/archive` on the author plane.
//
// A single request, no pre-resolution: the route itself answers "no pin means what is live", which
// is why download does not reuse the read commands' three-request resolver (v9 TD §接口 4).
func (c Client) StreamArchive(ctx context.Context, path string) (ArchiveStream, error) {
	// The body is read after this function returns, so the deadline has to live in the context the
	// body inherits — the cancellation below is what ends it early when the caller closes the body.
	ctx, cancel := context.WithTimeout(ctx, archiveTimeout)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.BaseURL+path, nil)
	if err != nil {
		cancel()
		return ArchiveStream{}, fmt.Errorf("构造请求：%w", err)
	}
	req.Header.Set("Authorization", "Bearer "+c.Token)

	resp, err := c.http().Do(req)
	if err != nil {
		cancel()
		return ArchiveStream{}, fmt.Errorf("请求 %s 失败：%w", path, err)
	}
	if resp.StatusCode != http.StatusOK {
		body, readErr := readAll(resp)
		resp.Body.Close()
		cancel()
		if readErr != nil {
			return ArchiveStream{}, fmt.Errorf("读取 %s 的响应：%w", path, readErr)
		}
		return ArchiveStream{}, statusError(resp, body)
	}
	return ArchiveStream{
		Body:   &streamBody{ReadCloser: resp.Body, cancel: cancel},
		ETag:   resp.Header.Get("ETag"),
		Length: resp.ContentLength,
	}, nil
}

// ReadArchiveInMemory downloads one version's zip and returns its entries by relative path.
//
// **In memory, not on disk.** `skill check` reads the whole tree once and writes nothing; the
// stream-to-a-temp-file shape `download` uses would put a copy of the skill in /tmp to answer a
// question that needs no copy. `limit` is the same ceiling download enforces — a response larger
// than the server's own content ceiling is not the answer that was asked for.
func (c Client) ReadArchiveInMemory(ctx context.Context, path string, limit int64) (map[string][]byte, error) {
	stream, err := c.StreamArchive(ctx, path)
	if err != nil {
		return nil, err
	}
	defer stream.Body.Close()

	buf, err := io.ReadAll(io.LimitReader(stream.Body, limit+1))
	if err != nil {
		return nil, fmt.Errorf("读取归档：%w", err)
	}
	if int64(len(buf)) > limit {
		return nil, fmt.Errorf("归档超过 %d 字节，不像这一版的整版内容", limit)
	}

	reader, err := zip.NewReader(bytes.NewReader(buf), int64(len(buf)))
	if err != nil {
		return nil, fmt.Errorf("归档不是有效的 zip：%w", err)
	}
	files := make(map[string][]byte, len(reader.File))
	for _, entry := range reader.File {
		if entry.FileInfo().IsDir() {
			continue
		}
		rc, openErr := entry.Open()
		if openErr != nil {
			return nil, fmt.Errorf("解包 %s：%w", entry.Name, openErr)
		}
		data, readErr := io.ReadAll(rc)
		rc.Close()
		if readErr != nil {
			return nil, fmt.Errorf("解包 %s：%w", entry.Name, readErr)
		}
		files[entry.Name] = data
	}
	return files, nil
}

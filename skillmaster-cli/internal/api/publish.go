package api

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io/fs"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// PublishResult is §4.3's answer, reduced to what the CLI reports.
type PublishResult struct {
	Name      string `json:"name"`
	Namespace string `json:"namespace"`
	Created   bool   `json:"created"`
	Version   struct {
		Number     int    `json:"number"`
		Digest     string `json:"digest"`
		FileCount  int    `json:"file_count"`
		TotalBytes int64  `json:"total_bytes"`
	} `json:"version"`
}

// Publish uploads an archive as one multipart part named `file`.
//
// Multipart rather than a raw body because that is what the endpoint consumes
// (`MULTIPART_FORM_DATA_VALUE`, one part called `file`) — and the part is a **zip**, not a tar and
// not the files one by one, so one request carries a whole directory and the server sees the same
// file set it will store.
func (c Client) Publish(ctx context.Context, archive []byte) (PublishResult, error) {
	ctx, cancel := context.WithTimeout(ctx, callTimeout)
	defer cancel()

	var body bytes.Buffer
	form := multipart.NewWriter(&body)
	part, err := form.CreateFormFile("file", "skill.zip")
	if err != nil {
		return PublishResult{}, fmt.Errorf("构造上传：%w", err)
	}
	if _, err := part.Write(archive); err != nil {
		return PublishResult{}, fmt.Errorf("构造上传：%w", err)
	}
	if err := form.Close(); err != nil {
		return PublishResult{}, fmt.Errorf("构造上传：%w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.BaseURL+"/api/v1/skills", &body)
	if err != nil {
		return PublishResult{}, fmt.Errorf("构造请求：%w", err)
	}
	req.Header.Set("Content-Type", form.FormDataContentType())
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.Token)

	resp, err := c.http().Do(req)
	if err != nil {
		return PublishResult{}, fmt.Errorf("上传失败：%w", err)
	}
	defer resp.Body.Close()
	answer, err := readAll(resp)
	if err != nil {
		return PublishResult{}, err
	}
	if resp.StatusCode == http.StatusUnauthorized {
		// Same signal as every other call's, and safe to act on and retry: a 401 is refused at the
		// security filter, so nothing was written. See ErrUnauthorized.
		return PublishResult{}, fmt.Errorf("%w：%s", ErrUnauthorized, strings.TrimSpace(string(answer)))
	}
	if resp.StatusCode != http.StatusCreated && resp.StatusCode != http.StatusOK {
		return PublishResult{}, fmt.Errorf("服务端答 %d：%s", resp.StatusCode, strings.TrimSpace(string(answer)))
	}

	var result PublishResult
	if err := json.Unmarshal(answer, &result); err != nil {
		return PublishResult{}, fmt.Errorf("发布响应不是 JSON：%w", err)
	}
	return result, nil
}

// Archive packs a directory the way the endpoint expects: a zip whose entries are paths relative to
// the directory, with `/` separators.
//
// **The checks here are courtesy, not authority.** ADR 0011 draws that line explicitly: the server
// validates the same things and is the only authority, so a client-side check exists to say
// something useful early, never to be the thing that keeps bad input out. When they disagree the
// server wins, and this file must not be read as a reason to relax anything over there.
//
// Two of those courtesies are worth having anyway, because the server's answer for them arrives
// after an upload that could be megabytes: a missing `SKILL.md`, which every skill must have, and a
// symlink, which the format does not carry and which would otherwise be uploaded as its target's
// contents or as nothing at all.
func Archive(dir string) ([]byte, error) {
	info, err := os.Stat(dir)
	if err != nil {
		return nil, fmt.Errorf("读取 %s：%w", dir, err)
	}
	if !info.IsDir() {
		return nil, fmt.Errorf("%s 不是一个目录", dir)
	}
	if _, err := os.Stat(filepath.Join(dir, "SKILL.md")); err != nil {
		return nil, fmt.Errorf("%s 里没有 SKILL.md，发布前先写上它", dir)
	}

	var paths []string
	err = filepath.WalkDir(dir, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if path == dir {
			return nil
		}
		if entry.Type()&fs.ModeSymlink != 0 {
			return fmt.Errorf("%s 是符号链接，技能包里不带符号链接", path)
		}
		if entry.IsDir() {
			return nil
		}
		paths = append(paths, path)
		return nil
	})
	if err != nil {
		return nil, err
	}
	// Sorted so that the same tree produces the same archive twice. The server's digest is over the
	// file *set* rather than the zip, so this is not what makes publishing idempotent — it just means
	// a diff of two archives is readable when something does look wrong.
	sort.Strings(paths)

	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	for _, path := range paths {
		relpath, err := filepath.Rel(dir, path)
		if err != nil {
			return nil, fmt.Errorf("计算 %s 的相对路径：%w", path, err)
		}
		// Forward slashes regardless of platform: the archive's entry names are what the server
		// stores as `version_file.relpath`, and a backslash from Windows would be a segment
		// separator on one side and a character in a filename on the other.
		entry, err := writer.Create(filepath.ToSlash(relpath))
		if err != nil {
			return nil, fmt.Errorf("打包 %s：%w", relpath, err)
		}
		content, err := os.ReadFile(path)
		if err != nil {
			return nil, fmt.Errorf("读取 %s：%w", path, err)
		}
		if _, err := entry.Write(content); err != nil {
			return nil, fmt.Errorf("打包 %s：%w", relpath, err)
		}
	}
	if err := writer.Close(); err != nil {
		return nil, fmt.Errorf("收尾打包：%w", err)
	}
	return buffer.Bytes(), nil
}

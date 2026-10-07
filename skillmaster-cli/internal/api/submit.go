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

// SubmitResult is §4.3's answer, reduced to what the CLI reports.
type SubmitResult struct {
	Name      string `json:"name"`
	Namespace string `json:"namespace"`
	// True when *this call wrote a version*, which is not the same fact as the skill being new: a
	// first submission after months of edits sets it too. skill_created is what separates the two.
	Created bool `json:"created"`
	// True only for the call that created the *skill* — the skill had no version of any state before
	// it. This is the distinction the version number used to carry (ADR 0033), and `created` cannot
	// stand in for it: it is true for a new version of an old skill as well.
	SkillCreated bool `json:"skill_created"`
	Version      struct {
		// Null when the author declared no `version:` in their SKILL.md (ADR 0033): such a version
		// has no name and is addressed by digest.
		Name        string `json:"name"`
		Digest      string `json:"digest"`
		FileCount   int    `json:"file_count"`
		TotalBytes  int64  `json:"total_bytes"`
		SubmittedAt string `json:"submitted_at"`
		// `draft`, `published` or `discarded`. Read rather than assumed: a replay answers with
		// whichever row already holds that digest, and the unique constraint is on the content
		// rather than on the state, so the version this call names may be any of the three.
		State string `json:"state"`
	} `json:"version"`
}

// The three states a version can be in (ADR 0031), spelled as the server spells them.
const (
	StateDraft     = "draft"
	StatePublished = "published"
	StateDiscarded = "discarded"
)

// versionAlreadyExists is §4.1's error code for a version name this skill already has with different
// content (ADR 0033) — the one code a submit needs to recognise, because it is the only rejection
// whose fix is in the author's SKILL.md rather than in the command.
const versionAlreadyExists = "version_already_exists"

// errorEnvelope is §4.1's error body. Only the two halves this package reads are declared; the rest
// is deliberately absent rather than modelled, because nothing here acts on it.
type errorEnvelope struct {
	Error struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

// Submit uploads an archive as one multipart part named `file`, and records it as a draft.
//
// **A submission is not a publication** (ADR 0031). This call makes nothing live: the version it
// returns is a draft, the pointer does not move, and the consumption plane sees exactly what it saw
// before. Somebody has to open the page this command prints and approve it, which is why `submit`
// is the verb here and `publish` belongs to the browser.
//
// Multipart rather than a raw body because that is what the endpoint consumes
// (`MULTIPART_FORM_DATA_VALUE`, one part called `file`) — and the part is a **zip**, not a tar and
// not the files one by one, so one request carries a whole directory and the server sees the same
// file set it will store.
func (c Client) Submit(ctx context.Context, archive []byte) (SubmitResult, error) {
	return c.upload(ctx, "/api/v1/skills", archive)
}

// SubmitVersion adds a version to a skill that already exists — §4.3's editor path (ADR 0034).
//
// The address is `namespace/name` and names *the skill*: the version is what this call produces, so
// a pin would be a second, ignored answer to a question this call does not ask. The command layer
// refuses one rather than letting it through to be dropped.
//
// **This names its target, and `Submit` does not** — which is not a looseness. The create route takes
// its target from the token's subject and never from the request, so a submission cannot be a
// cross-namespace write primitive; the thing that makes naming one safe here is that this route can
// only add to a skill the caller has been given write access to, and can never bring one into being.
func (c Client) SubmitVersion(ctx context.Context, address string, archive []byte) (SubmitResult, error) {
	path, err := SkillPath(address)
	if err != nil {
		return SubmitResult{}, err
	}
	return c.upload(ctx, path+"/versions", archive)
}

// upload is the one multipart POST; the two verbs differ in their path and in nothing else.
func (c Client) upload(ctx context.Context, path string, archive []byte) (SubmitResult, error) {
	ctx, cancel := context.WithTimeout(ctx, callTimeout)
	defer cancel()

	var body bytes.Buffer
	form := multipart.NewWriter(&body)
	part, err := form.CreateFormFile("file", "skill.zip")
	if err != nil {
		return SubmitResult{}, fmt.Errorf("构造上传：%w", err)
	}
	if _, err := part.Write(archive); err != nil {
		return SubmitResult{}, fmt.Errorf("构造上传：%w", err)
	}
	if err := form.Close(); err != nil {
		return SubmitResult{}, fmt.Errorf("构造上传：%w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.BaseURL+path, &body)
	if err != nil {
		return SubmitResult{}, fmt.Errorf("构造请求：%w", err)
	}
	req.Header.Set("Content-Type", form.FormDataContentType())
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.Token)

	resp, err := c.http().Do(req)
	if err != nil {
		return SubmitResult{}, fmt.Errorf("上传失败：%w", err)
	}
	defer resp.Body.Close()
	answer, err := readAll(resp)
	if err != nil {
		return SubmitResult{}, err
	}
	if resp.StatusCode == http.StatusUnauthorized {
		// Same signal as every other call's, and safe to act on and retry: a 401 is refused at the
		// security filter, so nothing was written. See ErrUnauthorized.
		return SubmitResult{}, fmt.Errorf("%w：%s", ErrUnauthorized, strings.TrimSpace(string(answer)))
	}
	if resp.StatusCode == http.StatusNotFound {
		// Only the `--to` path can reach this: `Submit` creates, so there is nothing for it to miss.
		// The server answers one 404 for all three of these on purpose (§4.2), and a client that
		// guessed which one it was would be undoing that.
		return SubmitResult{}, fmt.Errorf(
			"找不到 %s（服务端不区分「不存在」、「你看不到」与「不是你写得了的」）", path)
	}
	if resp.StatusCode == http.StatusForbidden {
		// A skill that was shared with this caller as a viewer: they can read it and cannot add to
		// it. The server names the level they would need, so its sentence is shown as it stands.
		return SubmitResult{}, fmt.Errorf("服务端拒绝了这次提交：%s", serverMessage(answer))
	}
	if resp.StatusCode == http.StatusBadRequest {
		// The one rejection whose fix is in the author's SKILL.md rather than on the command line, and
		// the one most easily misread as success: nothing was uploaded, and the version they have been
		// editing already exists on the server holding *different* content (ADR 0033). A bare
		// `服务端答 400` would leave them looking for a network problem. The server's own message says
		// which version and what to do, and is quoted rather than paraphrased, so the two cannot drift.
		var envelope errorEnvelope
		if json.Unmarshal(answer, &envelope) == nil &&
			envelope.Error.Code == versionAlreadyExists {
			return SubmitResult{}, fmt.Errorf(
				"这个版本号服务端已经有了，而且内容不一样——把 SKILL.md 里的 version 改成新的再提交。"+
					"\n服务端说：%s", envelope.Error.Message)
		}
	}
	if resp.StatusCode != http.StatusCreated && resp.StatusCode != http.StatusOK {
		return SubmitResult{}, fmt.Errorf("服务端答 %d：%s", resp.StatusCode, strings.TrimSpace(string(answer)))
	}

	var result SubmitResult
	if err := json.Unmarshal(answer, &result); err != nil {
		return SubmitResult{}, fmt.Errorf("提交响应不是 JSON：%w", err)
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
	// **Clean first**, because a trailing separator makes `lstat(2)` resolve the final component:
	// `Lstat("link/")` reports the *target*, so the refusal below would not fire for `submit link/`
	// while it does for `submit link`. The per-file rule inside the walk has no such hole — it sees
	// entries, not paths — and this is the one place a caller's own spelling could open one.
	dir = filepath.Clean(dir)
	// **Lstat, not Stat.** `Stat` follows a link, so a symlinked root passed this check and then
	// vanished: `WalkDir` Lstats its own root, a link is not a directory, so it does not descend — the
	// callback fires once and returns early on `path == dir`, which is *before* the symlink check
	// below. The archive came out valid and empty, and the server answered that the upload contained
	// no files, blaming the skill for a link. A linked directory is refused here for the same reason
	// a linked file is: the format does not carry links.
	info, err := os.Lstat(dir)
	if err != nil {
		return nil, fmt.Errorf("读取 %s：%w", dir, err)
	}
	if info.Mode()&fs.ModeSymlink != 0 {
		return nil, fmt.Errorf("%s 是符号链接，技能包里不带符号链接（用它指向的真目录）", dir)
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

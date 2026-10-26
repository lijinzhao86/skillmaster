package main

import (
	"archive/zip"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// The sidecar is written into the download but is not one of the skill's files: it must not be
// counted in the "N 个文件" line, which is compared with `skill files` (v9 TD §接口 4).
const metadataRelpath = ".skillmaster/metadata.json"

// skillSidecarMaxBytes mirrors the server's internal allowance for the sidecar: a file larger than
// this at the reserved path is content, because a sidecar never is.
const skillSidecarMaxBytes = 64 * 1024

// maxArchiveBytes bounds what we are willing to write to disk. The server's content ceiling is
// 16 MiB; this leaves room for the zip's own headers and one sidecar, and refuses anything that
// suggests the response is not the archive we asked for.
const maxArchiveBytes = 16*1024*1024 + 1024*1024

// download writes one version of a skill to a directory (ADR 0068).
//
// **One request, then the filesystem.** The archive is streamed to a temporary file, checked entry
// by entry, and unpacked into `<target>.partial`; only when that whole step succeeds does the name
// become `<target>`. A failure therefore leaves the user with either their old directory or
// nothing — never half a skill.
//
// **The target rule is the CLI's, not the server's.** An existing directory is accepted only when
// it is empty: replacing a directory with content in it would be a deletion nobody asked for, and
// merging into one would silently mix two versions. `.`, `..` and the root are refused outright —
// they are the directories the person is standing in, not a place to swap out.
func download(ctx context.Context, args []string) error {
	address, into, err := parseDownloadArgs(args)
	if err != nil {
		return err
	}

	base, pin, named, err := splitDownloadAddress(address)
	if err != nil {
		return err
	}
	if _, err := api.SkillPath(base); err != nil {
		return &usageError{message: err.Error()}
	}
	name := base[strings.Index(base, "/")+1:]

	target := into
	if target == "" {
		target = name
	}
	target = filepath.Clean(target)
	if target == "." || target == ".." || target == string(filepath.Separator) || target == "/" {
		return usagef("下载目标不能是当前目录、上级目录或根目录；用 --into 指定一个目录。%s", usageAt("skill download"))
	}

	emptyExisting, err := checkTarget(target)
	if err != nil {
		return err
	}

	client, err := session(ctx)
	if err != nil {
		return err
	}
	archive, err := client.StreamArchive(ctx, archivePath(base, pin, named))
	if err != nil {
		return downloadRefusal(err)
	}
	defer archive.Body.Close()

	tmp, err := os.CreateTemp("", "skillmaster-archive-*.zip")
	if err != nil {
		return fmt.Errorf("创建临时文件：%w", err)
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)

	written, err := io.Copy(tmp, io.LimitReader(archive.Body, maxArchiveBytes+1))
	if closeErr := tmp.Close(); closeErr != nil && err == nil {
		err = fmt.Errorf("写临时文件：%w", closeErr)
	}
	if err != nil {
		return fmt.Errorf("下载失败，没有写入任何文件：%w", err)
	}
	if written > maxArchiveBytes {
		return errors.New("服务端返回的压缩包比上限还大，已停止；没有写入任何文件。")
	}

	partial := target + ".partial"
	// **Nothing half a skill is left behind** (PRD 做 #1 / T3): the unpacked copy is this run's own
	// creation, so every failure below removes it before returning — the target itself was never
	// touched.
	files, bytes, err := unpack(tmpName, partial)
	if err != nil {
		_ = os.RemoveAll(partial)
		return err
	}

	if err := place(partial, target, emptyExisting); err != nil {
		_ = os.RemoveAll(partial)
		return err
	}

	pinLabel := pin
	if !named {
		pinLabel = "最新线上"
	}
	fmt.Printf("下载 %s@%s  %s\n", base, pinLabel, strings.Trim(archive.ETag, `"`))
	fmt.Printf("%d 个文件  %d 字节\n", files, bytes)
	fmt.Printf("已写到 %s\n", target)
	return nil
}

// downloadRefusal answers in the interface's own words for the refusals this CLI has a sentence for
// — a discarded version (400 `invalid_version_state`) is the one this command meets in practice —
// and keeps the transport's wording for everything else. v9 TD §接口 4 asks for exactly that: the
// mapping 401/403/404 already get, plus a Chinese sentence for a state the product refuses to serve.
func downloadRefusal(err error) error {
	return versionRefusal(err)
}

func parseDownloadArgs(args []string) (address, into string, err error) {
	if len(args) == 0 {
		return "", "", usagef("%s", usageAt("skill download"))
	}
	address = args[0]
	rest := args[1:]
	for i := 0; i < len(rest); i++ {
		switch rest[i] {
		case "--into":
			if i+1 >= len(rest) {
				return "", "", usagef("--into 后面要跟一个目录。%s", usageAt("skill download"))
			}
			into = rest[i+1]
			i++
		default:
			return "", "", unknownFlag("skill download", rest[i])
		}
	}
	return address, into, nil
}

// splitDownloadAddress separates the skill from the pin, validating neither pin's grammar nor its
// existence: the server accepts version numbers, digests, short codes and ULIDs, and duplicating
// that parser here would be a second answer that can disagree (v9 TD §接口 4).
func splitDownloadAddress(address string) (base, pin string, named bool, err error) {
	if err := checkAddressSyntax("skill download", address); err != nil {
		return "", "", false, err
	}
	version, named, err := versionInAddress(address)
	if err != nil {
		return "", "", false, err
	}
	base = bareAddress(address)
	if named && version == "" {
		return "", "", false, usagef("%s", usageAt("skill download"))
	}
	return base, version, named, nil
}

func archivePath(base, pin string, named bool) string {
	namespace, name, _ := strings.Cut(base, "/")
	prefix := api.APIPrefix + "/author/skills/" + url.PathEscape(namespace) + "/" + url.PathEscape(name)
	if !named {
		return prefix + "/archive"
	}
	return prefix + "/versions/" + url.PathEscape(pin) + "/archive"
}

// checkTarget reports whether the target is an existing empty directory that may be replaced.
//
// A missing target is the ordinary case. An existing file is refused for the same reason a
// non-empty directory is: this command owns neither of them.
func checkTarget(target string) (emptyExisting bool, err error) {
	info, statErr := os.Stat(target)
	if errors.Is(statErr, os.ErrNotExist) {
		return false, nil
	}
	if statErr != nil {
		return false, fmt.Errorf("查看 %s：%w", target, statErr)
	}
	if !info.IsDir() {
		return false, fmt.Errorf("%s 已经是一个文件；下载目标必须是一个不存在的目录或空目录。", target)
	}
	entries, readErr := os.ReadDir(target)
	if readErr != nil {
		return false, fmt.Errorf("读取 %s：%w", target, readErr)
	}
	if len(entries) > 0 {
		return false, fmt.Errorf("%s 不是空目录；为了不删掉你的东西，下载不会覆盖它。换一个 --into。",
			target)
	}
	return true, nil
}

// unpack reads the archive into dir, refusing anything that would escape it.
//
// The server already validates what it stores, but these bytes arrive over a socket and the check
// costs one Clean per entry: an absolute path or a `..` segment here would write outside the
// directory the person named.
func unpack(zipPath, dir string) (files int, bytes int64, err error) {
	reader, err := zip.OpenReader(zipPath)
	if err != nil {
		return 0, 0, fmt.Errorf("解压失败，没有写入任何文件：%w", err)
	}
	defer reader.Close()

	if err := os.RemoveAll(dir); err != nil {
		return 0, 0, fmt.Errorf("清理 %s：%w", dir, err)
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return 0, 0, fmt.Errorf("创建 %s：%w", dir, err)
	}

	for _, entry := range reader.File {
		if entry.FileInfo().IsDir() {
			continue
		}
		// `path` only knows `/`, and these bytes came over a socket: on Windows a `..\evil` entry
		// would pass the checks below and `filepath.Join` would then resolve it outside the target.
		// The server refuses a backslash on upload (`backslash_in_path`), so refusing it here keeps
		// the two ends on one rule rather than trusting the package to have come from us.
		clean := path.Clean(entry.Name)
		if strings.ContainsRune(entry.Name, '\\') ||
			path.IsAbs(clean) || clean == ".." || strings.HasPrefix(clean, "../") {
			return 0, 0, fmt.Errorf("压缩包里的路径 %q 不安全，已停止；没有留下任何东西。", entry.Name)
		}
		dest := filepath.Join(dir, filepath.FromSlash(clean))
		if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
			return 0, 0, fmt.Errorf("创建目录：%w", err)
		}
		if err := writeEntry(entry, dest); err != nil {
			return 0, 0, err
		}
		if countsAsContent(clean, dest) {
			files++
			bytes += int64(entry.UncompressedSize64)
		}
	}
	return files, bytes, nil
}

/**
 * Whether an entry at the reserved path is content or the platform's sidecar.
 *
 * A downloaded version normally carries the sidecar the server rendered, and that file is not part
 * of the version — `skill files` does not list it, so the count here must not include it. But a
 * **historical** version may already hold a file at that path as ordinary content (ADR 0069), and
 * the server then writes no sidecar: counting by name alone would report N-1 files for a directory
 * that has N. The discriminator is the one the server uses on an upload — a JSON object with a
 * top-level `schema_version` is metadata, anything else is content.
 */
func countsAsContent(clean, dest string) bool {
	if clean != metadataRelpath {
		return true
	}
	info, err := os.Stat(dest)
	if err != nil || info.Size() > skillSidecarMaxBytes {
		return true
	}
	data, err := os.ReadFile(dest)
	if err != nil {
		return true
	}
	var object map[string]any
	if json.Unmarshal(data, &object) != nil {
		return true
	}
	_, isSidecar := object["schema_version"]
	return !isSidecar
}

func writeEntry(entry *zip.File, dest string) error {
	source, err := entry.Open()
	if err != nil {
		return fmt.Errorf("读取压缩包里的 %s：%w", entry.Name, err)
	}
	defer source.Close()
	file, err := os.OpenFile(dest, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return fmt.Errorf("写入 %s：%w", dest, err)
	}
	if _, err := io.Copy(file, source); err != nil {
		file.Close()
		return fmt.Errorf("写入 %s：%w", dest, err)
	}
	return file.Close()
}

// place gives the unpacked directory its final name.
//
// An existing empty directory is the only thing that may be replaced, and even then it is moved
// aside first on platforms whose rename refuses a non-empty target: if the rename then fails, the
// person's directory is put back. The copy is never deleted before the swap succeeds.
func place(partial, target string, emptyExisting bool) error {
	if !emptyExisting {
		if err := os.Rename(partial, target); err != nil {
			return fmt.Errorf("把下载结果放到 %s：%w", target, err)
		}
		return nil
	}
	if err := os.Rename(partial, target); err == nil {
		return nil
	}
	backup, err := os.MkdirTemp(filepath.Dir(target), ".skillmaster-replaced-")
	if err != nil {
		return fmt.Errorf("准备替换 %s：%w", target, err)
	}
	os.Remove(backup)
	if err := os.Rename(target, backup); err != nil {
		return fmt.Errorf("替换 %s：%w", target, err)
	}
	if err := os.Rename(partial, target); err != nil {
		_ = os.Rename(backup, target)
		return fmt.Errorf("把下载结果放到 %s：%w", target, err)
	}
	_ = os.Remove(backup)
	return nil
}

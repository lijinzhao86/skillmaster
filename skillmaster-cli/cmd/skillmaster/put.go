// put: upload local files, including binary ones, into a draft.

package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

type putOptions struct {
	overwrite bool
}

// The server's ceilings, in the client's words (v39). They are not configurable; the server remains
// the authority, but a 120 MiB file must not cross the network to be refused.
const (
	maxFileBytes  = int64(100 * 1024 * 1024)
	maxSkillBytes = int64(512 * 1024 * 1024)
	maxSkillFiles = 512
)

func mib(bytes int64) string {
	if bytes%(1024*1024) == 0 {
		return fmt.Sprintf("%d MiB", bytes/(1024*1024))
	}
	return fmt.Sprintf("%.1f MiB", float64(bytes)/(1024*1024))
}

func parsePutFlags(args []string) (putOptions, []string, error) {
	var opts putOptions
	var rest []string
	for _, arg := range args {
		switch arg {
		case "--overwrite":
			opts.overwrite = true
		default:
			if strings.HasPrefix(arg, "-") && arg != "-" {
				return opts, nil, unknownFlag("skill put", arg)
			}
			rest = append(rest, arg)
		}
	}
	return opts, rest, nil
}

// put uploads one or more local files.
//
// **The destination is the last argument**, like `cp` and `scp`: one local file may name its target
// exactly; several, or a target ending in `/`, mean "into this directory, under each file's own
// name". The bytes are streamed from the file — this never reads a 100 MiB file into memory.
//
// **Only a draft.** A published version is frozen, and creating a version is the browser's action
// (ADR 0031); a draft must already exist. `skill write` creates one from text, and the console can
// create one from a binary.
func put(ctx context.Context, args []string) error {
	opts, rest, err := parsePutFlags(args)
	if err != nil {
		return err
	}
	if len(rest) < 3 {
		return usagef("要接地址、至少一个本地文件、和一个远端路径。%s", usageAt("skill put"))
	}
	address := rest[0]
	localPaths := rest[1 : len(rest)-1]
	remote := rest[len(rest)-1]
	if isNewDraft(address) {
		return errors.New("put 只写已经存在的草稿；先用 skill write 建出草稿（写一个文本文件），再 put 二进制。")
	}
	if len(localPaths) > 1 && !strings.HasSuffix(remote, "/") {
		return usagef("传多个文件时，远端路径要以 / 结尾，表示一个目录。%s", usageAt("skill put"))
	}
	for _, local := range localPaths {
		info, statErr := os.Stat(local)
		if statErr != nil {
			return fmt.Errorf("读不到本地文件 %s：%w", local, statErr)
		}
		if info.IsDir() {
			return fmt.Errorf("%s 是一个目录；put 一次只传一个文件，目录用 skill submit", local)
		}
	}

	target, err := writeTarget(address)
	if err != nil {
		return err
	}
	client, err := session(ctx)
	if err != nil {
		return err
	}
	listed, err := client.AuthorFileList(ctx, target.address, target.pin)
	if err != nil {
		return err
	}
	existing := make(map[string]api.File, len(listed.Files))
	for _, file := range listed.Files {
		existing[file.Relpath] = file
	}
	etag := listed.ETag

	if refusal := putRefusal(localPaths, remote, existing, opts.overwrite); refusal != "" {
		return &exitError{code: 1, err: errors.New(refusal)}
	}

	failed := 0
	for _, local := range localPaths {
		targetPath := remote
		if strings.HasSuffix(remote, "/") {
			targetPath = remote + filepath.Base(local)
		}
		if _, found := existing[targetPath]; found && !opts.overwrite {
			fmt.Fprintf(os.Stderr, "这个版本已经有 %s；整份覆盖加 --overwrite。文件没有上传。\n",
				targetPath)
			failed++
			continue
		}
		if err := putOne(ctx, client, target, local, targetPath, etag); err != nil {
			fmt.Fprintf(os.Stderr, "%s：%v\n", local, err)
			failed++
			continue
		}
		// A later file may name the same target (two basenames from different directories); the
		// snapshot has to know the path exists now.
		if info, statErr := os.Stat(local); statErr == nil {
			existing[targetPath] = api.File{Relpath: targetPath, Size: info.Size()}
		}
		// The next write in this command is based on the version the previous one produced.
		listed, err = client.AuthorFileList(ctx, target.address, target.pin)
		if err != nil {
			return &exitError{code: 1, err: fmt.Errorf(
				"%s 已上传，但读不回新基线：%w", targetPath, err)}
		}
		etag = listed.ETag
	}
	if failed > 0 {
		return &exitError{code: 1, err: fmt.Errorf("%d 个文件没有上传", failed)}
	}
	return nil
}

// putRefusal applies the server's ceilings to the batch before the first write request. It counts
// only files that will actually be uploaded; a skipped existing path is not a new byte.
func putRefusal(localPaths []string, remote string, existing map[string]api.File,
	overwrite bool) string {
	var total int64
	for _, file := range existing {
		total += file.Size
	}
	added := 0
	for _, local := range localPaths {
		info, err := os.Stat(local)
		if err != nil {
			continue
		}
		size := info.Size()
		if size > maxFileBytes {
			return fmt.Sprintf("文件 %s 有 %s，超过单文件上限 %s。",
				local, mib(size), mib(maxFileBytes))
		}
		targetPath := remote
		if strings.HasSuffix(remote, "/") {
			targetPath = remote + filepath.Base(local)
		}
		if old, found := existing[targetPath]; found {
			if !overwrite {
				continue
			}
			total -= old.Size
		} else {
			added++
		}
		total += size
	}
	if len(existing)+added > maxSkillFiles {
		return fmt.Sprintf("这一版最多 %d 个文件。", maxSkillFiles)
	}
	if total > maxSkillBytes {
		return fmt.Sprintf("这一版加上这次上传会超过 %s 的上限。", mib(maxSkillBytes))
	}
	return ""
}

func putOne(ctx context.Context, client api.Client, target fileTarget, local, remote,
	etag string) error {
	file, err := os.Open(local)
	if err != nil {
		return fmt.Errorf("打开本地文件：%w", err)
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return fmt.Errorf("读取本地文件信息：%w", err)
	}
	if info.IsDir() {
		return errors.New("这是一个目录；put 一次只传一个文件，目录用 skill submit")
	}
	result, err := client.AuthorWriteFileStream(ctx, target.address, target.pin, remote, file,
		info.Size(), etag)
	if err != nil {
		return writeRefusal(err, target.address)
	}
	fmt.Fprintf(os.Stderr, "已上传 %s → %s（%s）。\n", local, remote,
		shortDigest(result.Version.Digest))
	return nil
}

// Streaming uploads shared by `write --from`: the client-side ceilings and the one PUT.

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
	etag string) (api.AuthorWriteResult, error) {
	file, err := os.Open(local)
	if err != nil {
		return api.AuthorWriteResult{}, fmt.Errorf("打开本地文件：%w", err)
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return api.AuthorWriteResult{}, fmt.Errorf("读取本地文件信息：%w", err)
	}
	if info.IsDir() {
		return api.AuthorWriteResult{}, errors.New("这是一个目录；write 一次只传一个文件，目录用 skill submit")
	}
	result, err := client.AuthorWriteFileStream(ctx, target.address, target.pin, remote, file,
		info.Size(), etag)
	if err != nil {
		return api.AuthorWriteResult{}, writeRefusal(err, target.address)
	}
	fmt.Fprintf(os.Stderr, "已上传 %s → %s（%s）。\n", local, remote,
		shortDigest(result.Version.Digest))
	return result, nil
}

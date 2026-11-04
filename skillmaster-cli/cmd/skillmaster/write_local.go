// `write --from`: whole-file replacement from local files (v44, ADR 0136).

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

// writeLocalFiles uploads one or more local files into an existing draft.
//
// The destination rule is `put`'s: one file may name its target exactly; several, or a target
// ending in `/`, mean "into this directory, under each file's own name". Every existing target
// needs `--overwrite` and a read proof of the version being replaced (ADR 0136).
func writeLocalFiles(ctx context.Context, address, remote string, locals []string,
	overwrite bool) error {
	if len(locals) > 1 && !strings.HasSuffix(remote, "/") {
		return usagef("传多个文件时，远端路径要以 / 结尾，表示一个目录。%s", usageAt("skill write"))
	}
	for _, local := range locals {
		info, err := os.Stat(local)
		if err != nil {
			return fmt.Errorf("读不到本地文件 %s：%w", local, err)
		}
		if info.IsDir() {
			return fmt.Errorf("%s 是一个目录；write 一次只传文件，目录用 skill submit", local)
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
		return writeBaselineRefusal(ctx, err, address)
	}
	existing := make(map[string]api.File, len(listed.Files))
	for _, file := range listed.Files {
		existing[file.Relpath] = file
	}
	etag := listed.ETag

	// The server's ceilings, checked before the first byte crosses the network (v39).
	if refusal := putRefusal(locals, remote, existing, overwrite); refusal != "" {
		return &exitError{code: 1, err: errors.New(refusal)}
	}

	for _, local := range locals {
		targetPath := remote
		if strings.HasSuffix(remote, "/") {
			targetPath = remote + filepath.Base(local)
		}
		old, found := existing[targetPath]
		if found {
			if !overwrite {
				return &exitError{code: 1, err: fmt.Errorf(
					"这个版本已经有 %s；整份覆盖加 --overwrite。文件没有改动。", targetPath)}
			}
			if refusal := readProofRefusal(target.address, target.pin, targetPath, etag); refusal != "" {
				return &exitError{code: 1, err: errors.New(refusal)}
			}
			before, currentETag, readErr := client.AuthorFileBytes(ctx, target.address, target.pin,
				targetPath)
			if readErr != nil {
				return readErr
			}
			etag = currentETag
			after, readErr := os.ReadFile(local)
			if readErr != nil {
				return fmt.Errorf("读本地文件 %s：%w", local, readErr)
			}
			if old.IsBinary {
				fmt.Fprintf(os.Stderr, "将替换 %s（%d 字节 → %d 字节）。\n", targetPath,
					len(before), len(after))
			} else {
				added, removed := diffLineCounts(before, after)
				fmt.Fprintf(os.Stderr, "将改动 %d 行（+%d / -%d）。\n", added+removed, added,
					removed)
			}
		}
		if err := putOne(ctx, client, target, local, targetPath, etag); err != nil {
			return err
		}
		// The next file in this command is based on the version the previous one produced.
		listed, err = client.AuthorFileList(ctx, target.address, target.pin)
		if err != nil {
			return &exitError{code: 1, err: fmt.Errorf("%s 已上传，但读不回新基线：%w",
				targetPath, err)}
		}
		etag = listed.ETag
		existing[targetPath] = api.File{Relpath: targetPath}
	}
	return nil
}

// diffLineCounts reports how many lines a whole-file replacement adds and removes, by the same
// diff the edit echo uses — one definition, so the number a write prints and the hunks an edit
// prints cannot disagree.
func diffLineCounts(before, after []byte) (added, removed int) {
	oldLines := editContentLines(before)
	newLines := editContentLines(after)
	prefix := 0
	for prefix < len(oldLines) && prefix < len(newLines) && oldLines[prefix] == newLines[prefix] {
		prefix++
	}
	suffix := 0
	for suffix < len(oldLines)-prefix && suffix < len(newLines)-prefix &&
		oldLines[len(oldLines)-1-suffix] == newLines[len(newLines)-1-suffix] {
		suffix++
	}
	for _, op := range diffMiddle(oldLines[prefix:len(oldLines)-suffix],
		newLines[prefix:len(newLines)-suffix]) {
		switch op.kind {
		case '+':
			added++
		case '-':
			removed++
		}
	}
	return added, removed
}

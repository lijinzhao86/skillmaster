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
	existing := fileMap(listed.Files)
	etag := listed.ETag

	// The server's ceilings, checked before the first byte crosses the network (v39).
	if refusal := putRefusal(locals, remote, existing, overwrite); refusal != "" {
		return &exitError{code: 1, err: errors.New(refusal)}
	}

	// **"Create only" is decided before anything is written.** The per-file check below would refuse
	// the clashing file after the new ones had landed, leaving a half-done batch whose own advice
	// ("rerun the same command") then fails on the files it just wrote.
	// **Two local files landing on one remote path is a mistake, not a batch.** The second would be
	// refused after the first landed, and the advice to rerun could never succeed — the pre-check in
	// the next block would refuse it before any byte moved.
	seen := make(map[string]string, len(locals))
	for _, local := range locals {
		targetPath := remotePathFor(remote, local)
		if previous, duplicate := seen[targetPath]; duplicate {
			return usagef("两个 --from（%s 与 %s）会写到同一个远端路径 %s；请只留一个。",
				previous, local, targetPath)
		}
		seen[targetPath] = local
	}
	if !overwrite {
		var clashes []string
		for _, local := range locals {
			if _, taken := existing[remotePathFor(remote, local)]; taken {
				clashes = append(clashes, remotePathFor(remote, local))
			}
		}
		if len(clashes) > 0 {
			return &exitError{code: 1, err: fmt.Errorf(
				"这个版本已经有 %s；整份覆盖加 --overwrite。文件没有改动。",
				strings.Join(clashes, "、"))}
		}
	}

	written := make([]string, 0, len(locals))
	for index, local := range locals {
		targetPath := remotePathFor(remote, local)
		fail := func(err error) error {
			remaining := make([]string, 0, len(locals)-index-1)
			for _, later := range locals[index+1:] {
				remaining = append(remaining, remotePathFor(remote, later))
			}
			return batchFailure(err, written, targetPath, remaining, len(locals))
		}
		old, found := existing[targetPath]
		if found {
			if !overwrite {
				return fail(fmt.Errorf(
					"这个版本已经有 %s；整份覆盖加 --overwrite。", targetPath))
			}
			if refusal := readProofRefusal(target.address, target.pin, targetPath, old.SHA256); refusal != "" {
				return fail(errors.New(refusal))
			}
			before, currentETag, readErr := client.AuthorFileBytes(ctx, target.address, target.pin,
				targetPath)
			if readErr != nil {
				return fail(readErr)
			}
			etag = currentETag
			after, readErr := os.ReadFile(local)
			if readErr != nil {
				return fail(fmt.Errorf("读本地文件 %s：%w", local, readErr))
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
		writtenResult, err := putOne(ctx, client, target, local, targetPath, etag)
		if err != nil {
			return fail(err)
		}
		// **The next file is judged against the version this one just produced.** The listing is
		// re-taken for two reasons: the If-Match baseline moved, and every other file's hash may have
		// moved with it — a sibling changed outside this command must not be overwritten on the
		// strength of a hash this run read before that change.
		listed, err = client.AuthorFileList(ctx, target.address, target.pin)
		if err != nil {
			// The bytes landed; only the baseline did not. Reporting it as "failed" would put the same
			// path in two groups and would leave the rerun without a proof for it — say which file is
			// in that state and what to do before rerunning.
			return baselineLost(targetPath, err, written, locals[index+1:], remote, target, len(locals))
		}
		etag = listed.ETag
		existing = fileMap(listed.Files)
		justWritten, present := existing[targetPath]
		if !present {
			// The write was accepted but the fresh listing does not show it. Counting it as written
			// would make the report disagree with the manifest, and a rerun would be refused by the
			// pre-check — so it goes where "uploaded, baseline unknown" goes.
			return baselineLost(targetPath, errors.New("写完重列的清单里没有它"),
				written, locals[index+1:], remote, target, len(locals))
		}
		written = append(written, targetPath)
		if sameVersion(listed.ETag, writtenResult.Version.Digest) {
			// The caller supplied these bytes; the next attempt (a rerun) is allowed to replace them.
			// **Only when the listing is the version this write produced** — if somebody else wrote
			// between the PUT and this list, the hash describes their content, not the caller's.
			recordWriteProof(target.address, target.pin, targetPath, justWritten.SHA256)
		}
	}
	return nil
}

// remotePathFor is where one local file lands: the target itself, or under it when it names a
// directory. The batch report uses it too, so all three groups speak about the same paths.
func remotePathFor(remote, local string) string {
	if strings.HasSuffix(remote, "/") {
		return remote + filepath.Base(local)
	}
	return remote
}

func fileMap(files []api.File) map[string]api.File {
	existing := make(map[string]api.File, len(files))
	for _, file := range files {
		existing[file.Relpath] = file
	}
	return existing
}

// batchFailure is what a several-file `write` says when it stops partway.
//
// **Not atomic, and it does not pretend to be.** The server takes one PUT per file with no
// transaction across them, and undoing the earlier ones would mean rewriting or deleting content the
// caller did not ask to touch (ADR 0101, ADR 0177). What it can do is say exactly where the batch
// stands: which files landed, which one failed and why, which never started — then point at the one
// command that finishes the job. Rerunning is cheap precisely because the read proofs are per file.
//
// A single-file write keeps its own sentence: there is no batch to report.
func batchFailure(err error, written []string, failed string, remaining []string,
	total int) error {
	if total <= 1 {
		return err
	}
	var b strings.Builder
	fmt.Fprintf(&b, "%v\n本次批量写入 %d 个文件：\n", err, total)
	fmt.Fprintf(&b, "  已写入（%d）：%s\n", len(written), joinedOrNone(written))
	fmt.Fprintf(&b, "  失败（1）：%s —— %v\n", failed, err)
	fmt.Fprintf(&b, "  未开始（%d）：%s\n", len(remaining), joinedOrNone(remaining))
	b.WriteString("修好后重跑同一条命令即可；已写入的文件会被覆盖成同样的内容。")
	// **The cause stays reachable.** An expired credential is a 401, and `reportError` maps that to
	// its own exit code and next step only when the typed error survives; flattening the message into
	// `errors.New` would turn "log in again" into "check the network".
	return &batchError{summary: b.String(), cause: err}
}

// baselineLost is the report for "the bytes landed, the baseline did not".
//
// It is not the failed group: the file was written. It is also not a finished file — without the
// listing there is no hash to record, so a rerun will be asked to read it first. Saying both of
// those is the only honest answer, and it keeps the three-group promise intact for everything else.
func baselineLost(targetPath string, err error, written []string, remaining []string, remote string,
	target fileTarget, total int) error {
	var b strings.Builder
	fmt.Fprintf(&b, "%s 已上传，但读不回新基线：%v", targetPath, err)
	if total > 1 {
		remainingPaths := make([]string, 0, len(remaining))
		for _, local := range remaining {
			remainingPaths = append(remainingPaths, remotePathFor(remote, local))
		}
		fmt.Fprintf(&b, "\n本次批量写入 %d 个文件：\n", total)
		fmt.Fprintf(&b, "  已写入（%d）：%s\n", len(written), joinedOrNone(written))
		fmt.Fprintf(&b, "  已上传、基线未知（1）：%s —— 重跑前先读它一次：skmr skill read %s@%s %s\n",
			targetPath, target.address, target.pin, targetPath)
		fmt.Fprintf(&b, "  未开始（%d）：%s", len(remainingPaths), joinedOrNone(remainingPaths))
	}
	return &batchError{summary: b.String(), cause: err}
}

// sameVersion reports whether the listing this run got back is the version its own PUT produced.
//
// **A proof recorded from somebody else's write is worse than no proof.** The write result carries
// the digest the server assigned; if the listing's ETag is a different one, another writer landed in
// between and the hash in this listing describes their content, not the bytes this caller supplied.
func sameVersion(etag, digest string) bool {
	etag = strings.Trim(etag, `"`)
	digest = strings.Trim(digest, `"`)
	if etag == "" || digest == "" {
		return false
	}
	return etag == digest || "sha256:"+etag == digest || etag == "sha256:"+digest
}

// batchError is a batch report with the failure that stopped it still unwrappable.
type batchError struct {
	summary string
	cause   error
}

func (e *batchError) Error() string { return e.summary }

func (e *batchError) Unwrap() error { return e.cause }

func joinedOrNone(paths []string) string {
	if len(paths) == 0 {
		return "无"
	}
	return strings.Join(paths, "、")
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

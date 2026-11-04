// Moving files: rm, cp and mv, plus the content-write flags shared with `write`.

package main

import (
	"context"
	"errors"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"net/url"
	"os"
	"strings"
)

// rm removes one or more files, continuing past a failure and reporting it at the end.
func rm(ctx context.Context, args []string) error {
	for _, arg := range args {
		if strings.HasPrefix(arg, "-") {
			return usagef("rm 不接受 flag。%s", usageAt("skill rm"))
		}
	}
	rest := args
	if len(rest) < 2 {
		return usagef("要接地址和至少一个相对路径。%s", usageAt("skill rm"))
	}
	if isNewDraft(rest[0]) {
		return errors.New("rm 不再用 @new 建草稿。先 skmr draft create <地址> --name <名称>，" +
			"再用版本码删：skmr skill rm <地址>@<版本码> <路径>。")
	}
	args = rest
	target, err := writeTarget(args[0])
	if err != nil {
		return err
	}
	client, err := session(ctx)
	if err != nil {
		return err
	}
	ifMatch := ""
	failed := false
	for _, relpath := range args[1:] {
		if ifMatch == "" {
			listed, err := client.AuthorFileList(ctx, target.address, target.pin)
			if err != nil {
				return writeBaselineRefusal(ctx, err, args[0])
			}
			ifMatch = listed.ETag
		}
		result, err := client.AuthorDeleteFile(ctx, target.address, target.pin, relpath, ifMatch)
		if err != nil {
			fmt.Fprintf(os.Stderr, "错误：删除 %s：%v\n", relpath, writeRefusal(err, args[0]))
			failed = true
			continue
		}
		ifMatch = result.Version.Digest
		rememberWrite(args[0], target, result)
		fmt.Fprintf(os.Stderr, "已删除 %s。\n", relpath)
	}
	if failed {
		if len(args[1:]) == 1 {
			return &exitError{code: 1}
		}
		return &exitError{code: 1, err: errors.New("有一个或多个文件没有删掉")}
	}
	return nil
}

// copyFiles is `cp`, without `mv`'s new-draft flags: a copy can never be the first change to a
// version (the server creates a draft from a write, a delete or a rename), so `--from`, `--name` and
// `--note` have nothing to do here. A shared entry point would have kept them parsed and would have
// kept the help listing flags that always fail (v22 D2).
func copyFiles(ctx context.Context, args []string) error {
	for _, arg := range args {
		if strings.HasPrefix(arg, "--") {
			return usagef("cp 不接受 flag。%s", usageAt("skill cp"))
		}
	}
	if len(args) != 3 {
		return usagef("要接地址、来源路径与目标路径。%s", usageAt("skill cp"))
	}
	if isNewDraft(args[0]) {
		return errors.New("第一次改动不能是复制：服务端建草稿只接受写、删、改名。" +
			"先把草稿建出来（写一份或删一个文件），再 cp。")
	}
	return moveOrCopyFiles(ctx, args, "cp")
}

// moveOrCopy is `mv`: one request, because the server moves a reference rather than bytes.
func moveOrCopy(ctx context.Context, args []string, op string) error {
	for _, arg := range args {
		if strings.HasPrefix(arg, "-") {
			return usagef("%s 不接受 flag。%s", op, usageAt("skill "+op))
		}
	}
	if len(args) != 3 {
		return usagef("要接地址、来源路径与目标路径。%s", usageAt("skill "+op))
	}
	if isNewDraft(args[0]) {
		return errors.New("mv 不再用 @new 建草稿。先 skmr draft create <地址> --name <名称>，" +
			"再用版本码移动：skmr skill mv <地址>@<版本码> <from> <to>。")
	}
	return moveOrCopyFiles(ctx, args, op)
}

// moveOrCopyFiles is the request both verbs end in: the server moves or copies a reference.
func moveOrCopyFiles(ctx context.Context, args []string, op string) error {
	target, err := writeTarget(args[0])
	if err != nil {
		return err
	}
	client, err := session(ctx)
	if err != nil {
		return err
	}
	listed, err := client.AuthorFileList(ctx, target.address, target.pin)
	if err != nil {
		return writeBaselineRefusal(ctx, err, args[0])
	}
	result, err := client.AuthorMoveOrCopy(ctx, target.address, target.pin, op, args[1], args[2],
		listed.ETag)
	if err != nil {
		return writeRefusal(err, args[0])
	}
	rememberWrite(args[0], target, result)
	verb := "复制"
	if op == "mv" {
		verb = "移动"
	}
	fmt.Fprintf(os.Stderr, "已%s %s → %s（%s）。\n", verb, args[1], args[2],
		shortDigest(result.Version.Digest))
	return nil
}

// authorFilePath is the author plane's address for one file.
func authorFilePath(address, pin, relpath string) string {
	base, err := api.SkillPath(address)
	if err != nil {
		return ""
	}
	return api.APIPrefix + "/author" + strings.TrimPrefix(base, api.APIPrefix) + "/versions/" +
		url.PathEscape(pin) + "/files/" + escapeRelpath(relpath)
}

// consumerFilePath is the consumption plane's address for one file. The version travels in the
// header, never in the path — the path grammar there is a version suffix on the name.
func consumerFilePath(address, relpath string) string {
	base, err := api.SkillPath(address)
	if err != nil {
		return ""
	}
	return base + "/files/" + escapeRelpath(relpath)
}

func escapeRelpath(relpath string) string {
	segments := strings.Split(relpath, "/")
	for index, segment := range segments {
		segments[index] = url.PathEscape(segment)
	}
	return strings.Join(segments, "/")
}

func containsPath(paths []string, relpath string) bool {
	if len(paths) == 0 {
		return true
	}
	for _, wanted := range paths {
		if wanted == relpath {
			return true
		}
	}
	return false
}

func shortDigest(digest string) string {
	digest = strings.TrimPrefix(digest, "sha256:")
	if len(digest) > 8 {
		return digest[:8]
	}
	return digest
}

func printNote(target fileTarget) {
	if target.note != "" {
		fmt.Fprintln(os.Stderr, target.note)
	}
}

// isBrokenPipe is the downstream having gone away — `cat big | head -5` — which is not a failure of
// this command and must not print an error or change the exit status.
func isBrokenPipe(err error) bool {
	return errors.Is(err, os.ErrClosed) || strings.Contains(err.Error(), "broken pipe") ||
		strings.Contains(err.Error(), "EPIPE")
}

// writeFlags are the flags that say something about the content rather than about the draft.
//
// **There is deliberately no `--content-file`.** Its only effect would be to make "author a file
// locally, push the whole thing" convenient, and that is the round trip v32 exists to replace: an
// agent that starts from a local file stops using `edit`, and with it the hunk, the uniqueness check
// and the miss diagnosis. `--from` names local files directly (v44), so a separate content flag
// would only be a second spelling of the same request.
type writeFlags struct {
	overwrite  bool
	allowEmpty bool
	from       []string
}

func splitWriteFlags(args []string) (writeFlags, []string, error) {
	var flags writeFlags
	var rest []string
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--overwrite":
			flags.overwrite = true
		case "--allow-empty":
			flags.allowEmpty = true
		case "--from":
			if i+1 >= len(args) {
				return flags, nil, usagef("--from 后面要跟一个本地文件。%s", usageAt("skill write"))
			}
			flags.from = append(flags.from, args[i+1])
			i++
		default:
			if strings.HasPrefix(args[i], "-") {
				return flags, nil, unknownFlag("skill write", args[i])
			}
			rest = append(rest, args[i])
		}
	}
	return flags, rest, nil
}

// readRenderingRefusal says what the content is and where the real text is. The command is spelled
// out with the caller's own address and path, since writing only names an existing draft.
func readRenderingRefusal(address, relpath string) error {
	return fmt.Errorf("这份内容像是 skmr skill read 的输出：每行带行号，末尾是它的注记。"+
		"写进去这些行号会变成正文。取原文：skmr skill cat %s %s；改一处用 skmr skill edit。"+
		"文件没有改动。", address, relpath)
}

// isNewDraft reports whether the address names the draft that does not exist yet.
func isNewDraft(address string) bool {
	version, named, err := versionInAddress(address)
	return err == nil && named && version == "new"
}

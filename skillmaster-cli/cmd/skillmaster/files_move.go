// Moving files and creating drafts: rm, cp, mv, and the new-draft paths.

package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"net/url"
	"os"
	"strings"
	"unicode/utf8"
)

// rm removes one or more files, continuing past a failure and reporting it at the end.
func rm(ctx context.Context, args []string) error {
	opts, rest, err := splitNewDraftFlags(args)
	if err != nil {
		return err
	}
	if len(rest) < 2 {
		return usagef("要接地址和至少一个相对路径。%s", usageAt("skill rm"))
	}
	if isNewDraft(rest[0]) {
		if len(rest) != 2 {
			return usagef("第一次改动只能删一个文件；草稿建出来后可以继续 rm。%s", usageAt("skill rm"))
		}
		return createDraftWithEdit(ctx, rest[0], opts,
			api.DraftEdit{Op: "delete", Path: rest[1]}, "删除")
	}
	if opts != (newDraftOptions{}) {
		return usagef("--from / --name / --note 只在 @new 上有意义。%s", usageAt("skill rm"))
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
				return err
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
	opts, rest, err := splitNewDraftFlags(args)
	if err != nil {
		return err
	}
	if len(rest) != 3 {
		return usagef("要接地址、来源路径与目标路径。%s", usageAt("skill "+op))
	}
	if isNewDraft(rest[0]) {
		return createDraftWithEdit(ctx, rest[0], opts,
			api.DraftEdit{Op: "rename", From: rest[1], To: rest[2]}, "移动")
	}
	if opts != (newDraftOptions{}) {
		return usagef("--from / --name / --note 只在 @new 上有意义。%s", usageAt("skill "+op))
	}
	return moveOrCopyFiles(ctx, rest, op)
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
		return err
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

// newDraftOptions are the three flags that only mean something when the target is @new.
type newDraftOptions struct {
	from string
	name string
	note string
}

// splitNewDraftFlags pulls them out, leaving the positional arguments alone.
func splitNewDraftFlags(args []string) (newDraftOptions, []string, error) {
	var opts newDraftOptions
	var rest []string
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--from", "--name", "--note":
			if i+1 >= len(args) {
				return opts, nil, usagef("%s 后面要跟一个值。", args[i])
			}
			switch args[i] {
			case "--from":
				opts.from = args[i+1]
			case "--name":
				opts.name = args[i+1]
			case "--note":
				opts.note = args[i+1]
			}
			i++
		default:
			rest = append(rest, args[i])
		}
	}
	return opts, rest, nil
}

// writeFlags are the flags that say something about the content rather than about the draft.
//
// **There is deliberately no `--content-file`.** Its only effect would be to make "author a file
// locally, push the whole thing" convenient, and that is the round trip v32 exists to replace: an
// agent that starts from a local file stops using `edit`, and with it the hunk, the uniqueness check
// and the miss diagnosis. The standard input can already carry any bytes a file could; the door we
// would be opening is the habit, not the capability.
type writeFlags struct {
	overwrite  bool
	allowEmpty bool
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
		default:
			rest = append(rest, args[i])
		}
	}
	return flags, rest, nil
}

// readRenderingRefusal says what the content is and where the real text is. The command is spelled
// out with the caller's own address and path — except on `@new`, where `cat` at that address would
// 404 and a placeholder is the honest answer.
func readRenderingRefusal(address, relpath string) error {
	source := "取原文：skmr skill cat <地址> <路径>"
	if !isNewDraft(address) {
		source = fmt.Sprintf("取原文：skmr skill cat %s %s", address, relpath)
	}
	return fmt.Errorf("这份内容像是 skmr skill read 的输出：每行带行号，末尾是它的注记。"+
		"写进去这些行号会变成正文。%s；改一处用 skmr skill edit。文件没有改动。", source)
}

// isNewDraft reports whether the address names the draft that does not exist yet.
func isNewDraft(address string) bool {
	version, named, err := versionInAddress(address)
	return err == nil && named && version == "new"
}

// createDraftWithEdit reads the base the server would use and creates the draft with this change.
func createDraftWithEdit(ctx context.Context, address string, opts newDraftOptions,
	edit api.DraftEdit, summary string) error {
	if opts.name == "" {
		return usagef("第一次写入要同时给这一版起个名字（--name）。" +
			"建出来的草稿之后可以用 version rename 改名。")
	}
	base := bareAddress(address)
	client, err := session(ctx)
	if err != nil {
		return err
	}
	pin, digest, err := resolveNewDraftBase(ctx, client, base, opts.from)
	if err != nil {
		return err
	}
	_, err = createDraftWithBase(ctx, client, base, opts, pin, digest, edit, summary)
	return err
}

// createDraftWithBase sends the create with a base that has already been resolved.
//
// **Split out because `edit @new` must not resolve twice.** It reads the file from the base, and a
// second resolution would let a base that moved in between produce a draft whose content came from
// the old one while its digest claims the new one — the 409 the whole guard exists for would never
// fire.
func createDraftWithBase(ctx context.Context, client api.Client, base string, opts newDraftOptions,
	pin, digest string, edit api.DraftEdit, summary string) (api.CreatedDraft, error) {
	created, err := client.CreateDraft(ctx, base, api.CreateDraftRequest{
		Name:       opts.name,
		Note:       opts.note,
		From:       pin,
		FromDigest: digest,
		Edit:       edit,
	})
	if err != nil {
		return api.CreatedDraft{}, writeRefusal(err, base)
	}
	fmt.Fprintf(os.Stderr, "已%s并建出草稿 %s@%s。\n下一步：skmr version diff %s@%s，"+
		"或继续在同一草稿上改。\n", summary, base, created.Version.Code, base, created.Version.Code)
	return created, nil
}

// resolveNewDraftBase is the read half: an explicit --from is read for its digest; otherwise the
// server resolves the base and its digest is passed back unchanged.
func resolveNewDraftBase(ctx context.Context, client api.Client, base, from string) (string, string, error) {
	if from != "" {
		envelope, err := client.ReadAuthorVersion(ctx, base, from)
		if err != nil {
			return "", "", addressRefusal(err)
		}
		return from, envelope.Version.Digest, nil
	}
	resolved, err := client.DraftBaseOf(ctx, base)
	if err != nil {
		return "", "", addressRefusal(err)
	}
	if resolved.Base == nil {
		return "", "", nil
	}
	return resolved.Base.Pin, resolved.Base.Digest, nil
}

// editOnNewDraft reads the base file, applies the replacement locally, and creates the draft with
// the resulting text. A blank base has no files to read, and the sentence says so rather than
// sending a request that would 404.
func editOnNewDraft(ctx context.Context, parsed editArgs) error {
	if parsed.name == "" {
		// A usage error, like the same check in `skill write`: the command is incomplete, not the
		// request that failed.
		return usagef("第一次写入要同时给这一版起个名字（--name）。"+
			"建出来的草稿之后可以用 version rename 改名。%s", usageAt("skill edit"))
	}
	base := bareAddress(parsed.address)
	client, err := session(ctx)
	if err != nil {
		return err
	}
	pin, digest, err := resolveNewDraftBase(ctx, client, base, parsed.from)
	if err != nil {
		return err
	}
	if pin == "" {
		return errors.New("这一版还没有可改的文件（空白底稿）；先用 write 建一份。")
	}
	content, err := client.AuthorFile(ctx, base, pin, parsed.relpath)
	if err != nil {
		return err
	}
	// The diagnosis names the base the caller can actually read: `@new` is not a version, and a
	// `skill cat` at that address would 404.
	changed, err := replaceExactly(content, parsed.old, parsed.new, base+"@"+pin, parsed.relpath,
		parsed.replaceAll, parsed.regex)
	if err != nil {
		return err
	}
	if !utf8.Valid(changed) {
		return errors.New("第一次写入只接受文本；二进制文件请先建出草稿，再用 skill put 传上来。")
	}
	hunks, truncated := renderHunks(content, changed)
	terminatorsOnly := len(hunks) == 0 && !bytes.Equal(content, changed)
	created, err := createDraftWithBase(ctx, client, base,
		newDraftOptions{from: parsed.from, name: parsed.name, note: parsed.note},
		pin, digest,
		api.DraftEdit{Op: "write", Path: parsed.relpath, Text: string(changed)}, "修改")
	if err != nil {
		return err
	}
	printEditEcho(hunks, truncated, base, created.Version.Code, terminatorsOnly)
	return nil
}

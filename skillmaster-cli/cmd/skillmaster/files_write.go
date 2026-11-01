// Writing files: write, edit, and the refusal text they share.

package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/pins"
	"io"
	"os"
	"strings"
	"unicode/utf8"
)

// write replaces or creates one file from standard input.
//
// **`@new` is the first-write form** (v10): the draft does not exist yet, so this call reads the
// base the server would use, sends `--name` / `--note` with it, and the server creates the draft and
// applies the write in one transaction. `--from` picks another base.
// stdin is a seam for the same reason openBrowser is: a test must be able to feed a write without
// touching the process's own input.
var stdin io.Reader = os.Stdin

func write(ctx context.Context, args []string) error {
	flags, args, err := splitWriteFlags(args)
	if err != nil {
		return err
	}
	opts, rest, err := splitNewDraftFlags(args)
	if err != nil {
		return err
	}
	if len(rest) != 2 {
		return usagef("要接地址和相对路径。%s", usageAt("skill write"))
	}
	if isNewDraft(rest[0]) {
		if opts.name == "" {
			return usagef("第一次写入要同时给这一版起个名字（--name）；草稿建出来之后可以用 "+
				"version rename 改名。%s", usageAt("skill write"))
		}
		if flags.overwrite {
			// There is no draft yet, so there is no file being replaced: the base's copy being
			// superseded is what creating this version means.
			return usagef("--overwrite 只在整份替换已有文件时有意义；@new 建的是新草稿。%s",
				usageAt("skill write"))
		}
	} else if _, err := writeTarget(rest[0]); err != nil {
		// The address before the content: "this is not a draft" is the more fundamental mistake,
		// and a caller who typed the wrong address should not be told their stdin was empty.
		return err
	}
	content, err := io.ReadAll(stdin)
	if err != nil {
		return fmt.Errorf("读标准输入：%w", err)
	}
	// Empty is legal content (v8 TD), so it is not refused — but it must be meant. Without this,
	// a heredoc that never arrived and a deliberate empty file are the same request.
	if len(content) == 0 && !flags.allowEmpty {
		return usagef("要写的内容是空的；真要写空内容就加 --allow-empty。%s", usageAt("skill write"))
	}
	if looksLikeReadOutput(content) {
		return readRenderingRefusal(rest[0], rest[1])
	}
	if isNewDraft(rest[0]) {
		// The create-draft body carries text, not bytes: invalid UTF-8 would be replaced silently
		// on the way through JSON. Refused here rather than corrupting a file nobody would notice.
		if !utf8.Valid(content) {
			return errors.New("第一次写入只接受文本；二进制文件请先建出草稿（写一个文本文件），" +
				"再用 skill write 写它。")
		}
		return createDraftWithEdit(ctx, rest[0], opts,
			api.DraftEdit{Op: "write", Path: rest[1], Text: string(content)}, "写入")
	}
	if opts != (newDraftOptions{}) {
		return usagef("--from / --name / --note 只在 @new 上有意义。%s", usageAt("skill write"))
	}
	_, _, err = editRemote(ctx, rest[0], rest[1], func([]byte) ([]byte, error) { return content, nil },
		"写入", true, flags.overwrite)
	return err
}

// edit is Claude Code's `Edit`, on the server: one exact substring, exactly one occurrence unless
// asked to replace them all.
//
// **What it adds to that contract is what the host would otherwise provide.** The success message
// carries the applied hunk, because nothing here tracks the file the way Claude Code's harness does;
// and a miss is diagnosed rather than merely reported, because "not found" leaves a caller with no
// next move.
func edit(ctx context.Context, args []string) error {
	parsed, err := parseEditArgs(args)
	if err != nil {
		return err
	}
	if err := loadEditFiles(&parsed); err != nil {
		return err
	}
	if parsed.old == "" {
		if parsed.oldFileSet {
			// **No path in the sentence.** Usage errors carry a 200-byte budget (v16 T10), and a
			// deep path would spend it on something the caller already knows.
			return usagef("读到的文件是空的，没有要替换的文本。%s", usageAt("skill edit"))
		}
		return usagef("要替换的文本是空的：用 --old 或 --old-file 给出它。%s", usageAt("skill edit"))
	}
	// Checked before the address is resolved: the answer does not depend on the file, so it costs no
	// request — and it is the same refusal local `Edit` gives (`No changes to make`).
	if !parsed.regex && parsed.old == parsed.new {
		return errors.New("--old 与 --new 相同，没有可改的内容；文件没有改动。")
	}
	// The pattern is checked here for the same reason the equality check above is: it does not
	// depend on the file. Compiling it late meant an unreachable server answered first, so a plain
	// typo in `--old` cost a request and sometimes a login prompt.
	if parsed.regex {
		if _, err := compilePattern(parsed.old); err != nil {
			return err
		}
	}
	if isNewDraft(parsed.address) {
		return editOnNewDraft(ctx, parsed)
	}
	if parsed.from != "" || parsed.name != "" || parsed.note != "" {
		return usagef("--from / --name / --note 只在 @new 上有意义。%s", usageAt("skill edit"))
	}
	var hunks []string
	truncated := false
	terminatorsOnly := false
	result, stored, err := editRemote(ctx, parsed.address, parsed.relpath, func(content []byte) ([]byte, error) {
		updated, err := replaceExactly(content, parsed.old, parsed.new, parsed.address, parsed.relpath,
			parsed.replaceAll, parsed.regex)
		if err != nil {
			return nil, err
		}
		hunks, truncated = renderHunks(content, updated)
		// A change that lives only in line terminators produces no line diff; saying nothing would
		// leave the caller with a success line and no evidence anything moved.
		terminatorsOnly = len(hunks) == 0 && !bytes.Equal(content, updated)
		return updated, nil
	}, "修改", false, true)
	if err != nil {
		return err
	}
	if !stored {
		// editRemote already said so. Printing the hunk here would show a change the server did not
		// keep — the local edit was real, the stored version is the one that did not move.
		return nil
	}
	printEditEcho(hunks, truncated, parsed.address, result.Version.Code, terminatorsOnly)
	return nil
}

type editArgs struct {
	address    string
	relpath    string
	old        string
	new        string
	oldFile    string
	newFile    string
	oldSet     bool
	newSet     bool
	oldFileSet bool
	newFileSet bool
	replaceAll bool
	regex      bool
	from       string
	name       string
	note       string
}

// loadEditFiles fills old and new from the files the caller named. The bytes are used as they are:
// a trailing newline somebody did not mean is theirs to keep or fix, and normalising it here would
// change content the caller never asked to change.
func loadEditFiles(parsed *editArgs) error {
	if parsed.oldFile != "" {
		content, err := readEditTextFile(parsed.oldFile)
		if err != nil {
			return err
		}
		parsed.old = content
	}
	if parsed.newFile != "" {
		content, err := readEditTextFile(parsed.newFile)
		if err != nil {
			return err
		}
		parsed.new = content
	}
	return nil
}

func readEditTextFile(path string) (string, error) {
	content, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("读 %s 失败：%v。", path, err)
	}
	if !utf8.Valid(content) {
		return "", fmt.Errorf("%s 不是 UTF-8 文本；edit 只改文本。", path)
	}
	return string(content), nil
}

// printEditEcho shows what the edit actually did. It goes to stderr beside the success line, so the
// command's stdout contract (empty on success) is unchanged.
func printEditEcho(hunks []string, truncated bool, address, code string, terminatorsOnly bool) {
	for _, line := range hunks {
		fmt.Fprintln(os.Stderr, line)
	}
	if terminatorsOnly {
		fmt.Fprintln(os.Stderr, "（这次只改了行尾：换行符或末尾的换行，内容行没有变。）")
	}
	if truncated {
		fmt.Fprintf(os.Stderr, "…（只显示前 %d 行；完整改动：skmr version diff %s@%s。）\n",
			len(hunks), bareAddress(address), code)
	}
	if code != "" {
		// The sentence Claude Code's `Edit` gets for free from its host: the caller's picture of the
		// file is now the stored one, so re-reading is not how to check the result.
		fmt.Fprintf(os.Stderr, "（这一版现在是 %s@%s；再读拿到的就是这份内容。）\n",
			bareAddress(address), code)
	}
}

func parseEditArgs(args []string) (editArgs, error) {
	var parsed editArgs
	var positional []string
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--old":
			if i+1 >= len(args) {
				return editArgs{}, usagef("--old 后面要跟被替换的文本。%s", usageAt("skill edit"))
			}
			parsed.old = args[i+1]
			parsed.oldSet = true
			i++
		case "--old-file":
			if i+1 >= len(args) {
				return editArgs{}, usagef("--old-file 后面要跟文件路径。%s", usageAt("skill edit"))
			}
			parsed.oldFile = args[i+1]
			parsed.oldFileSet = true
			i++
		case "--new":
			if i+1 >= len(args) {
				return editArgs{}, usagef("--new 后面要跟新文本。%s", usageAt("skill edit"))
			}
			parsed.new = args[i+1]
			parsed.newSet = true
			i++
		case "--new-file":
			if i+1 >= len(args) {
				return editArgs{}, usagef("--new-file 后面要跟文件路径。%s", usageAt("skill edit"))
			}
			parsed.newFile = args[i+1]
			parsed.newFileSet = true
			i++
		case "--replace-all", "--all":
			parsed.replaceAll = true
		case "--regex":
			parsed.regex = true
		case "--from":
			if i+1 >= len(args) {
				return editArgs{}, usagef("--from 后面要跟一个版本。%s", usageAt("skill edit"))
			}
			parsed.from = args[i+1]
			i++
		case "--name":
			if i+1 >= len(args) {
				return editArgs{}, usagef("--name 后面要跟版本名称。%s", usageAt("skill edit"))
			}
			parsed.name = args[i+1]
			i++
		case "--note":
			if i+1 >= len(args) {
				return editArgs{}, usagef("--note 后面要跟说明。%s", usageAt("skill edit"))
			}
			parsed.note = args[i+1]
			i++
		default:
			if strings.HasPrefix(args[i], "-") {
				return editArgs{}, unknownFlag("skill edit", args[i])
			}
			positional = append(positional, args[i])
		}
	}
	if len(positional) != 2 {
		return editArgs{}, usagef("要接地址和相对路径。%s", usageAt("skill edit"))
	}
	// **"Given" means the flag appeared, not that its value was non-empty.** `--new ""` is a
	// deliberate "replace with nothing", and pairing it with `--new-file` must be the same
	// contradiction as any other pair.
	if parsed.oldSet && parsed.oldFileSet {
		return editArgs{}, usagef("--old 与 --old-file 只给一个。%s", usageAt("skill edit"))
	}
	if parsed.newSet && parsed.newFileSet {
		return editArgs{}, usagef("--new 与 --new-file 只给一个。%s", usageAt("skill edit"))
	}
	parsed.address, parsed.relpath = positional[0], positional[1]
	return parsed, nil
}

// replaceExactly applies the edit, or refuses without writing.
//
// Zero matches is a refusal because the caller's picture of the file is stale; several matches
// without `--replace-all` is a refusal because "which one" is not something the command can know.
// This is Claude Code's own contract, and it is why the count is taken before anything is written.
//
// **Matching runs on the line-ending view, not on the raw bytes** (v32 TD §接口「匹配与写回」): an LF
// `--old` finds a CRLF file, the way local `Edit` does. Everything but the terminators is still
// compared exactly — case, spaces, Unicode composition. The replacement is spliced back into the
// original bytes at the served offsets, encoded in the file's own style, so nothing outside the
// match moves.
//
// **Both refusals say where to look.** A bare "not found" is the one answer that leaves an agent
// re-reading the file — and re-reading is the numbered rendering that put line numbers into content
// in the first place. `diagnoseMiss` names the likely mistake; `multipleMatchesError` lists the
// lines so `--replace-all` versus "more context" is a decision the caller can make from the refusal.
// Both work on the view too: a line ending that already matched must not be blamed as whitespace.
func replaceExactly(content []byte, old, replacement, address, relpath string,
	replaceAll, regexMode bool) ([]byte, error) {
	view := lineEndingView(content)
	if regexMode {
		return replaceRegex(content, view, old, replacement, address, relpath, replaceAll)
	}
	wanted := normalizeLineEndings(old)
	count := strings.Count(view, wanted)
	switch {
	case count == 0:
		return nil, diagnoseMiss([]byte(view), wanted, address, relpath)
	case count > 1 && !replaceAll:
		return nil, multipleMatchesError([]byte(view), wanted, count)
	}
	updated := spliceReplacement(content, view, wanted, replacement, replaceAll)
	if bytes.Equal(updated, content) {
		// Reachable when old and new differ only in line endings: the replacement is re-encoded to
		// the file's own style, so the bytes come back the same. Local `Edit` re-encodes the whole
		// file and never reports this case; here it would otherwise be a successful PUT of the
		// content that is already there.
		return nil, errors.New("替换后与原文相同，没有可改的内容；文件没有改动。")
	}
	return updated, nil
}

// editRemote is the read–change–write every content write shares: get the file and its ETag, apply
// the change locally, and PUT it back with the ETag as If-Match.
//
// **`write` may create a file; `edit` may not.** When the path is absent, `write` uses the listing's
// own ETag as its baseline and sends the new content without reading anything; `edit` refuses,
// because there is no text to replace and the caller's picture is stale. A binary target is refused
// for `edit` only — `write` replaces bytes whatever they are.
//
// **The bool says whether the server stored something new.** v33 canonicalises text on the way in
// (ADR 0117), so an edit can arrive already equal to what is stored — NFC typed as NFD, or a line
// ending that the server rewrites anyway. The server answers with the version it already had; saying
// "已修改" and printing a hunk for that would claim a change that never landed.
func editRemote(ctx context.Context, address, relpath string,
	change func([]byte) ([]byte, error), summary string,
	allowCreate, allowReplace bool) (api.AuthorWriteResult, bool, error) {
	target, err := writeTarget(address)
	if err != nil {
		return api.AuthorWriteResult{}, false, err
	}
	client, err := session(ctx)
	if err != nil {
		return api.AuthorWriteResult{}, false, err
	}
	listed, err := client.AuthorFileList(ctx, target.address, target.pin)
	if err != nil {
		return api.AuthorWriteResult{}, false, err
	}
	binary := false
	found := false
	for _, file := range listed.Files {
		if file.Relpath == relpath {
			binary = file.IsBinary
			found = true
			break
		}
	}
	if !found && !allowCreate {
		return api.AuthorWriteResult{}, false, fmt.Errorf("这个版本没有 %s；文件没有改动。", relpath)
	}
	if found && !allowReplace {
		// Wholesale replacement has to be asked for (v32 PRD §范围 9). The default is "put a new
		// file here", and the refusal names both ways forward: change one part with `edit`, or say
		// `--overwrite`. A speed bump rather than a wall — stdin can still carry anything — but it
		// makes every whole-file overwrite a decision the caller took.
		return api.AuthorWriteResult{}, false, fmt.Errorf(
			"这个版本已经有 %s；改一处用 skmr skill edit %s %s --old … --new …；"+
				"整份覆盖加 --overwrite。文件没有改动。", relpath, address, relpath)
	}
	if found && binary && !allowCreate {
		return api.AuthorWriteResult{}, false,
			fmt.Errorf("%s 是二进制文件，edit 只改文本；文件没有改动。", relpath)
	}
	var content []byte
	var etag string
	if found {
		content, etag, err = client.AuthorFileBytes(ctx, target.address, target.pin, relpath)
		if err != nil {
			return api.AuthorWriteResult{}, false, err
		}
	} else {
		etag = listed.ETag
	}
	updated, err := change(content)
	if err != nil {
		return api.AuthorWriteResult{}, false, err
	}
	result, err := client.AuthorWriteFile(ctx, target.address, target.pin, relpath, updated, etag)
	if err != nil {
		return api.AuthorWriteResult{}, false, writeRefusal(err, address)
	}
	rememberWrite(address, target, result)
	if etag != "" && strings.Trim(etag, `"`) == result.Version.Digest {
		fmt.Fprintf(os.Stderr, "没有改动：服务端归一之后与已存的内容相同，这一版没有变。\n")
		return result, false, nil
	}
	fmt.Fprintf(os.Stderr, "已%s %s（%s）。\n", summary, relpath, shortDigest(result.Version.Digest))
	return result, true, nil
}

// writeRefusal turns the server's content conflicts into a sentence about what to do next.
// stateLabel is the console's word for a stored state value.
func stateLabel(state string) string {
	switch state {
	case "draft":
		return "草稿"
	case "published":
		return "线上"
	case "revoked":
		return "已下架"
	case "discarded":
		return "已丢弃"
	default:
		return "未知状态"
	}
}

func writeRefusal(err error, address string) error {
	var server *api.ServerError
	if errors.As(err, &server) {
		switch server.Code {
		case "digest_mismatch":
			return fmt.Errorf("这一版在读过之后被改动过；重新读一遍再改。%w", err)
		case "version_is_not_draft":
			// The server names the row's state, and its pin when that pin is an address the caller
			// can use. No `%w`: the server's sentence is English, and pasting it after a Chinese one
			// reads as two answers.
			state := stateLabel(server.Details["state"])
			next := "要改内容，先找一份草稿：skmr version list --status draft。"
			if server.Details["state"] == "published" && server.Details["pin"] != "" {
				return fmt.Errorf("写只针对草稿；这一版现在是「%s」（@%s）。%s",
					state, server.Details["pin"], next)
			}
			return fmt.Errorf("写只针对草稿；这一版现在是「%s」。%s", state, next)
		case "content_already_exists":
			state := stateLabel(server.Details["state"])
			where := ""
			if pin := server.Details["pin"]; pin != "" {
				where = "在 @" + pin + "（" + state + "）"
			} else if server.Details["state"] != "" {
				where = "在同一 skill 的一版「" + state + "」里"
			}
			return fmt.Errorf("这段内容已经存在%s；草稿没有被改动。", where)
		case "if_match_required":
			return fmt.Errorf("这次写没有带上基线摘要；重新运行命令。%w", err)
		case "note_too_long":
			return fmt.Errorf("版本说明最多 200 个码点。")
		case "draft_name_required":
			return fmt.Errorf("这一版要有名称（--name）。")
		case "draft_name_too_long":
			return fmt.Errorf("版本名称最多 40 个码点。")
		case "file_already_exists":
			return fmt.Errorf("这一版已经有同名的文件：%s", server.Details["to"])
		case "skill_md_cannot_be_deleted":
			return fmt.Errorf("SKILL.md 是 skill 的正文，不能删掉或移走。")
		case "last_file_cannot_be_deleted":
			return fmt.Errorf("一版至少要留一个文件。")
		case "invalid_path":
			// v33: a name that cannot be restored on macOS or Windows. Only the cross-system issues
			// have a sentence here; the structural ones keep the server's own line below.
			if refused, ok := writeNameRefusal(server); ok {
				return errors.New(refused)
			}
		case "invalid_request":
			for _, issue := range issueValues(server.Details) {
				switch issue {
				case "not_a_base":
					return fmt.Errorf("这一版不能当底稿：底稿必须是线上版本或草稿。")
				case "invalid_format":
					return fmt.Errorf("底稿摘要的格式不对；重新读一遍底稿再改。")
				case "unexpected":
					return fmt.Errorf("空白底稿不带 digest；重读一遍底稿再改。")
				}
			}
		case "invalid_upload":
			if issues := issueValues(server.Details); len(issues) > 0 {
				if message, known := issueMessages[issues[0]]; known {
					return fmt.Errorf("%s", message)
				}
			}
		}
	}
	return err
}

// rememberWrite updates the remembered pin's digest when the write was against it.
func rememberWrite(address string, target fileTarget, result api.AuthorWriteResult) {
	if strings.Contains(address, "@") {
		// An explicit pin does not move the machine's pin — the same rule `invoke`'s explicit form
		// follows.
		return
	}
	remembered, found, err := rememberedPin(target.address)
	if err != nil || !found || remembered.Suffix() != target.pin {
		return
	}
	remembered.Digest = strings.TrimPrefix(result.Version.Digest, "sha256:")
	_, _, _ = pinsForAndRemember(target.address, remembered)
}

func pinsForAndRemember(address string, pin pins.Pin) (pins.Pin, bool, error) {
	return rememberPin(address, pin)
}

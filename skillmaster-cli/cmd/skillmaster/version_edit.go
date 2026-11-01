// `version rename/note/type/discard`: editing a draft's labels or throwing it away.

package main

import (
	"context"
	"errors"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"io"
	"os"
	"strings"
)

func versionRename(ctx context.Context, args []string) error {
	jsonMode := hasJSONFlag(args)
	target, name, err := parseRenameArgs(args)
	if err != nil {
		return versionErr(jsonMode, err, "")
	}

	result, err := authenticated(ctx, func(client api.Client) (api.AuthorWriteResult, error) {
		return client.AuthorRename(ctx, target.skill, target.pin, name)
	})
	if err != nil {
		return versionErr(jsonMode, err, "改草稿需要你是它的提交人（写入档），或者有「维护」及以上。")
	}
	if jsonMode {
		return writeJSON(result)
	}
	fmt.Printf("已改名：%s@%s → 「%s」\n", target.skill, target.pin, name)
	return nil
}

// versionReleaseType declares how big a draft's change is (ADR 0091).
//
// **The help says the thing an agent will otherwise get wrong**: the type is not a version number.
// `--release minor` does not produce `1.3.0`; it declares that the next publish should move the
// minor digit, and the number is computed at that moment from whatever is live then.
func versionReleaseType(ctx context.Context, args []string) error {
	jsonMode := hasJSONFlag(args)
	target, releaseType, err := parseReleaseTypeArgs(args)
	if err != nil {
		return versionErr(jsonMode, err, "")
	}

	result, err := authenticated(ctx, func(client api.Client) (api.AuthorWriteResult, error) {
		return client.AuthorReleaseType(ctx, target.skill, target.pin, releaseType)
	})
	if err != nil {
		return versionErr(jsonMode, err, "改版本类型需要写入档（write）；发布之后类型就冻结了。")
	}
	if jsonMode {
		return writeJSON(result)
	}
	if releaseType == "none" {
		fmt.Printf("已清空版本类型：%s@%s\n", target.skill, target.pin)
		return nil
	}
	fmt.Printf("已记为%s：%s@%s（发布时按这个和当时的线上版本算号）\n",
		releaseTypeLabel(releaseType), target.skill, target.pin)
	return nil
}

func releaseTypeLabel(releaseType string) string {
	switch releaseType {
	case "major":
		return "主版本"
	case "minor":
		return "次版本"
	default:
		return "修订"
	}
}

func parseReleaseTypeArgs(args []string) (versionTarget, string, error) {
	address, releaseType := "", ""
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--json":
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionTarget{}, "", usagef("不认识的参数 %s。%s", namedArgument(args[i]), usageAt("version"))
			}
			if address == "" {
				address = args[i]
				continue
			}
			if releaseType != "" {
				return versionTarget{}, "", usagef("version release-type 只接一个地址和一个档位。")
			}
			releaseType = args[i]
		}
	}
	if address == "" || releaseType == "" {
		return versionTarget{}, "", usagef("%s", usageAt("version release-type"))
	}
	switch releaseType {
	case "major", "minor", "patch", "none":
	default:
		return versionTarget{}, "", usagef("档位只接 major / minor / patch / none。%s", usageAt("version release-type"))
	}
	target, err := parseVersionTarget(address)
	if err != nil {
		return versionTarget{}, "", err
	}
	return target, releaseType, nil
}

func parseRenameArgs(args []string) (versionTarget, string, error) {
	address, name := "", ""
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--json":
		case "--name":
			if i+1 >= len(args) {
				return versionTarget{}, "", usagef("--name 后面要跟版本名称。")
			}
			name = args[i+1]
			i++
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionTarget{}, "", usagef("不认识的参数 %s。%s", namedArgument(args[i]), usageAt("version"))
			}
			if address != "" {
				return versionTarget{}, "", usagef("version rename 只接一个地址。")
			}
			address = args[i]
		}
	}
	if address == "" {
		return versionTarget{}, "", usagef("%s", usageAt("version"))
	}
	if strings.TrimSpace(name) == "" {
		return versionTarget{}, "", usagef("要写上新名称：--name <名称>。%s", usageAt("version rename"))
	}
	target, err := writablePin(address)
	return target, name, err
}

func versionNote(ctx context.Context, args []string) error {
	jsonMode := hasJSONFlag(args)
	target, note, err := parseNoteArgs(args)
	if err != nil {
		return versionErr(jsonMode, err, "")
	}

	result, err := authenticated(ctx, func(client api.Client) (api.AuthorWriteResult, error) {
		return client.AuthorNote(ctx, target.skill, target.pin, note)
	})
	if err != nil {
		return versionErr(jsonMode, err, "改草稿需要你是它的提交人（写入档），或者有「维护」及以上。")
	}
	if jsonMode {
		return writeJSON(result)
	}
	if note == "" {
		fmt.Printf("已清空说明：%s@%s\n", target.skill, target.pin)
		return nil
	}
	fmt.Printf("已更新说明：%s@%s → 「%s」\n", target.skill, target.pin, note)
	return nil
}

func parseNoteArgs(args []string) (versionTarget, string, error) {
	address := ""
	note := ""
	mode := ""
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--json":
		case "--clear":
			if mode != "" {
				return versionTarget{}, "", usagef("--note、--note-file 与 --clear 只能给一个。")
			}
			mode = "clear"
		case "--note", "--note-file":
			if mode != "" {
				return versionTarget{}, "", usagef("--note、--note-file 与 --clear 只能给一个。")
			}
			if i+1 >= len(args) {
				return versionTarget{}, "", usagef("%s 后面要跟一个值。", args[i])
			}
			mode = args[i]
			value := args[i+1]
			i++
			if mode == "--note-file" {
				content, err := readNoteSource(value)
				if err != nil {
					return versionTarget{}, "", &usageError{message: err.Error()}
				}
				note = content
			} else if value == "-" {
				content, err := io.ReadAll(os.Stdin)
				if err != nil {
					return versionTarget{}, "", usagef("读标准输入：%v", err)
				}
				note = string(content)
			} else {
				note = value
			}
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionTarget{}, "", usagef("不认识的参数 %s。%s", namedArgument(args[i]), usageAt("version"))
			}
			if address != "" {
				return versionTarget{}, "", usagef("version note 只接一个地址。")
			}
			address = args[i]
		}
	}
	if address == "" {
		return versionTarget{}, "", usagef("%s", usageAt("version"))
	}
	if mode == "" {
		return versionTarget{}, "", usagef("要给一句说明或清空它：--note <文字>、--note-file <文件> 或 --clear。%s", usageAt("version"))
	}
	target, err := writablePin(address)
	return target, note, err
}

// readNoteSource reads `--note-file`, with `-` meaning standard input.
func readNoteSource(path string) (string, error) {
	if path == "-" {
		content, err := io.ReadAll(os.Stdin)
		if err != nil {
			return "", fmt.Errorf("读标准输入：%w", err)
		}
		return string(content), nil
	}
	content, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("读 %s：%w", path, err)
	}
	return string(content), nil
}

func versionDiscard(ctx context.Context, args []string) error {
	jsonMode := hasJSONFlag(args)
	target, yes, err := parseDiscardArgs(args)
	if err != nil {
		return versionErr(jsonMode, err, "")
	}

	command := fmt.Sprintf("skmr version discard %s@%s --yes", target.skill, target.pin)
	if !yes {
		// **Nothing is sent, and the exit code is non-zero.** The consequence is the first sentence
		// because it is the one that cannot be undone: the draft is not recoverable.
		if jsonMode {
			// The consequence and the command are in the message rather than the hint: a hint is
			// attached only to a permission refusal, and this one is a usage answer a machine
			// contract still has to carry in full.
			return versionErr(true, usagef("丢弃草稿要确认。这一版无法找回。确认后执行：%s", command), "")
		}
		fmt.Println("丢弃这一版会结束这个草稿：这一版无法找回，也不会占用任何版本号；线上不受影响。")
		fmt.Printf("确认后执行：\n%s\n", command)
		return errors.New("没有执行：丢弃草稿要加 --yes。")
	}

	// **A second discard is an answer, not a failure.** The server treats it as idempotent, so the
	// only way to say "already gone" rather than "just discarded" is to look before acting — and
	// only for a draft pin, where both answers are possible and neither is a different version.
	//
	// **One `authenticated`, not two.** That call reads the credential (and may start a login), so
	// doing the pre-read and the write through it separately read the store twice — two keychain
	// waits on a locked machine, and two browser windows when nobody had logged in yet.
	alreadyDiscarded := false
	preReadInvisible := false
	var before api.AuthorVersionEnvelope
	var result api.AuthorWriteResult
	result, err = authenticated(ctx, func(client api.Client) (api.AuthorWriteResult, error) {
		if !isDraftPin(target.pin) {
			return client.AuthorDiscard(ctx, target.skill, target.pin)
		}
		var readErr error
		before, readErr = client.ReadAuthorVersion(ctx, target.skill, target.pin)
		switch {
		case readErr == nil && before.Version.Status == "discarded":
			alreadyDiscarded = true
			return api.AuthorWriteResult{Ok: true, Version: before.Version}, nil
		case readErr != nil && authorNotFound(readErr):
			// A discarded row is `maintain`+ only: to a `write` submitter running the command a
			// second time, the pre-read is a 404. The discard itself is still idempotent for them;
			// this flag is what lets the success below say "already" rather than "just did".
			preReadInvisible = true
		}
		return client.AuthorDiscard(ctx, target.skill, target.pin)
	})
	if err != nil {
		return versionErr(jsonMode, err, "丢弃草稿需要你是它的提交人（写入档），或者有「维护」及以上。")
	}
	if alreadyDiscarded {
		if jsonMode {
			return writeJSON(result)
		}
		fmt.Printf("这一版已经是丢弃状态，没有需要再做的：%s@%s\n", target.skill, target.pin)
		return nil
	}
	if jsonMode {
		return writeJSON(result)
	}
	if preReadInvisible && result.Version.Status == "discarded" {
		fmt.Printf("这一版已经是丢弃状态，没有需要再做的：%s@%s\n", target.skill, target.pin)
		return nil
	}
	fmt.Printf("已丢弃 %s@%s（这一版无法找回）。\n", target.skill, target.pin)
	return nil
}

// authorNotFound is the author plane's "this version is not visible to you" answer.
func authorNotFound(err error) bool {
	var server *api.ServerError
	if errors.As(err, &server) && server.Status == 404 {
		return true
	}
	return errors.Is(err, api.ErrNotFound)
}

func parseDiscardArgs(args []string) (versionTarget, bool, error) {
	address := ""
	yes := false
	for _, arg := range args {
		switch arg {
		case "--json":
		case "--yes":
			yes = true
		default:
			if strings.HasPrefix(arg, "-") {
				return versionTarget{}, false, usagef("不认识的参数 %s。%s", namedArgument(arg), usageAt("version"))
			}
			if address != "" {
				return versionTarget{}, false, usagef("version discard 只接一个地址。")
			}
			address = arg
		}
	}
	if address == "" {
		return versionTarget{}, false, usagef("%s", usageAt("version"))
	}
	target, err := writablePin(address)
	return target, yes, err
}

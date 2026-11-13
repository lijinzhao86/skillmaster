// `feedback`: the document template, the preview-and-send pair, and the withdrawal (v64).

package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"runtime"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/feedback"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/version"
)

// feedbackMaxCodePoints repeats the server's ceiling so an oversized document fails before it
// travels. The server is still the authority; this copy is the one that saves a round trip.
const feedbackMaxCodePoints = 20_000

func feedbackVerb(ctx context.Context, args []string) error {
	// **Bare `skmr feedback` is the group's help**, not a send. There are three different
	// questions here (what do I write, do I want to send this, take it back), and guessing the
	// first would put a preview where a person expected a template.
	if len(args) == 0 || strings.HasPrefix(args[0], "-") {
		return usagef("%s", usageAt("feedback"))
	}
	switch args[0] {
	case "template":
		if len(args) > 1 {
			return usagef("%s", usageAt("feedback template"))
		}
		fmt.Println(feedback.Template)
		return nil
	case "attach":
		return feedbackAttach(ctx, args[1:])
	case "send":
		return feedbackSend(ctx, args[1:])
	case "delete":
		return feedbackDelete(ctx, args[1:])
	default:
		return unknownName("feedback", args[0], []string{"template", "attach", "send", "delete"})
	}
}

type feedbackSendOptions struct {
	file           string
	skill          string
	attach         bool
	attachments    []string
	command        string
	errorCode      string
	consent        bool
	allowSensitive bool
	json           bool
}

func parseFeedbackSend(args []string) (feedbackSendOptions, error) {
	var opts feedbackSendOptions
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--file", "--skill", "--attach", "--command", "--error-code":
			if i+1 >= len(args) {
				return opts, usagef("%s 后面要跟一个值。%s", args[i], usageAt("feedback send"))
			}
			flag, value := args[i], args[i+1]
			i++
			switch flag {
			case "--file":
				opts.file = value
			case "--skill":
				opts.skill = value
			case "--attach":
				opts.attachments = append(opts.attachments, value)
			case "--command":
				opts.command = value
			case "--error-code":
				opts.errorCode = value
			}
		case "--attach-diagnostics":
			opts.attach = true
		case "--consent":
			opts.consent = true
		case "--allow-sensitive":
			opts.allowSensitive = true
		case "--json":
			opts.json = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return opts, unknownFlag("feedback send", args[i])
			}
			return opts, usagef("%s", usageAt("feedback send"))
		}
	}
	return opts, nil
}

func feedbackSend(ctx context.Context, args []string) error {
	// **Read `--json` off the raw arguments**, like every other command: a usage failure raised
	// before the flag is reached must still print the JSON object when the caller asked for one.
	jsonMode := jsonModeIn(args, "--file", "--skill", "--attach", "--command", "--error-code")
	opts, err := parseFeedbackSend(args)
	if err != nil {
		return versionErr(jsonMode, err, "")
	}
	opts.json = jsonMode
	content, err := readFeedbackContent(opts.file)
	if err != nil {
		return versionErr(opts.json, err, "")
	}
	// **The document travels as written.** A trailing newline is part of what a person typed, and
	// the stored copy is supposed to be the file; only blankness is judged without it.
	if strings.TrimSpace(content) == "" {
		return versionErr(opts.json, usagef("反馈内容为空。%s", usageAt("feedback send")), "")
	}
	if strings.ContainsRune(content, '\x00') {
		return versionErr(opts.json, usagef(
			"反馈内容含有无法保存的空字符（U+0000）；请用文本编辑器打开后重新提交。%s",
			usageAt("feedback send")), "")
	}
	if points := utf8.RuneCountInString(content); points > feedbackMaxCodePoints {
		return versionErr(opts.json, usagef("反馈内容超过 %d 个字符（实际 %d）。%s",
			feedbackMaxCodePoints, points, usageAt("feedback send")), "")
	}

	// **Secrets are removed before the document is sent**, not after the server sees them. The
	// server repeats the replacement; a key that never travelled cannot leak on the way. The scan
	// then looks at what is left: a phone number may be the report, a key never is.
	secrets := feedback.ScanText(content).Counts["secret_prefix"]
	content = feedback.RedactSecrets(content)
	if secrets > 0 {
		fmt.Fprintf(os.Stderr, "已将 %d 处密钥前缀替换为 [已隐藏]；原值不会发送。\n", secrets)
	}
	scan := feedback.ScanText(content)

	// **Attachments are checked before the network.** A missing file, a wrong header or an
	// oversized video is the caller's mistake, and finding it after a consent round trip would
	// spend the person's time on a request that was never going to be accepted.
	attachments, err := readFeedbackAttachments(opts.attachments)
	if err != nil {
		return versionErr(opts.json, err, "")
	}

	// **The notice is read before a preview, not after the scan.** A document that would need
	// `--allow-sensitive` still deserves a preview: it is local, it sends nothing, and looking at
	// the document is the step this whole command exists for.
	consent, err := feedbackAuthenticated(ctx, func(client api.Client) (api.FeedbackConsent, error) {
		return client.GetFeedbackConsent(ctx)
	})
	if err != nil {
		return versionErr(opts.json, err, "")
	}
	if !opts.consent {
		// **The preview goes to stderr under `--json`**, so the caller's stdout stays one JSON
		// object; in text mode it is the output the person is meant to read.
		out := io.Writer(os.Stdout)
		if opts.json {
			out = os.Stderr
		}
		printFeedbackPreview(out, content, consent, opts, attachments)
		if scan.Any() {
			fmt.Fprintf(out, "注意：内容里可能包含%s；发送时要加 --allow-sensitive。\n", scan.Summary())
		}
		return versionErr(opts.json, &refusalError{
			message: "没有发送：确认上面的内容后加 --consent 再运行一次。"}, "")
	}
	if scan.Any() && !opts.allowSensitive {
		return versionErr(opts.json, &refusalError{message: fmt.Sprintf(
			"内容里可能包含%s。如果这正是要报告的问题，加 --allow-sensitive 再发一次。",
			scan.Summary())}, "")
	}

	uploads := make([]api.FeedbackAttachmentUpload, 0, len(attachments))
	for _, attachment := range attachments {
		uploads = append(uploads, api.FeedbackAttachmentUpload{
			Path:     attachment.Path,
			Filename: attachment.Filename,
		})
	}
	receipt, err := feedbackAuthenticated(ctx, func(client api.Client) (api.FeedbackReceipt, error) {
		if len(uploads) > 0 {
			return client.SubmitFeedbackWithAttachments(ctx, content, opts.skill,
				feedbackDiagnostics(opts), consent.Version, opts.allowSensitive, uploads)
		}
		return client.SubmitFeedback(ctx, content, opts.skill, feedbackDiagnostics(opts),
			consent.Version, opts.allowSensitive)
	})
	if err != nil {
		return versionErr(opts.json, err, "")
	}
	// The stable copies are this CLI's; after the server has them, their bytes are not needed. They
	// are truncated rather than deleted (ADR 0101); a path the person gave is never touched.
	for _, attachment := range attachments {
		feedback.ClearIfManaged(attachment.Path)
	}
	if opts.json {
		return writeJSON(receipt)
	}
	if len(receipt.Attachments) > 0 {
		fmt.Printf("已提交，回执 %s（%d 个附件）。\n", receipt.Receipt, len(receipt.Attachments))
	} else {
		fmt.Printf("已提交，回执 %s。\n", receipt.Receipt)
	}
	fmt.Printf("要撤回并删除（附件一并删除）：skmr feedback delete %s --yes\n", receipt.Receipt)
	return nil
}

func feedbackDelete(ctx context.Context, args []string) error {
	yes := false
	jsonMode := jsonModeIn(args)
	receipt := ""
	for _, arg := range args {
		switch {
		case arg == "--yes":
			yes = true
		case arg == "--json":
		case strings.HasPrefix(arg, "-"):
			return versionErr(jsonMode, unknownFlag("feedback delete", arg), "")
		default:
			if receipt != "" {
				return versionErr(jsonMode, usagef("%s", usageAt("feedback delete")), "")
			}
			receipt = arg
		}
	}
	if receipt == "" {
		return versionErr(jsonMode, usagef("%s", usageAt("feedback delete")), "")
	}
	if !yes {
		return versionErr(jsonMode, &refusalError{message: fmt.Sprintf(
			"将删除反馈 %s、它的附件与归档副本。确认请加 --yes：skmr feedback delete %s --yes",
			receipt, receipt)}, "")
	}
	if _, err := feedbackAuthenticated(ctx, func(client api.Client) (struct{}, error) {
		return struct{}{}, client.DeleteFeedback(ctx, receipt)
	}); err != nil {
		return versionErr(jsonMode, err, "")
	}
	if jsonMode {
		return writeJSON(map[string]any{"receipt": receipt, "deleted": true})
	}
	fmt.Printf("已删除反馈 %s、附件与归档副本。\n", receipt)
	return nil
}

// feedbackMaxReadBytes bounds what is read before the code-point check can run: the ceiling is
// 20000 code points (at most 80 KB of UTF-8), and a file or pipe must not be loaded whole first.
const feedbackMaxReadBytes = 1 << 20

func readFeedbackContent(path string) (string, error) {
	var source io.Reader
	label := "标准输入"
	if path != "" {
		file, err := os.Open(path)
		if err != nil {
			return "", fmt.Errorf("读反馈文件：%w", err)
		}
		defer file.Close()
		source = file
		label = "反馈文件"
	} else {
		if stdinIsTerminal() {
			return "", usagef("没有 --file，标准输入也不是文件。"+
				"用法：skmr feedback send --file <文件>，或 cat <文件> | skmr feedback send。%s",
				usageAt("feedback send"))
		}
		source = stdin
	}
	raw, err := io.ReadAll(io.LimitReader(source, feedbackMaxReadBytes+1))
	if err != nil {
		return "", fmt.Errorf("读%s：%w", label, err)
	}
	if len(raw) > feedbackMaxReadBytes {
		return "", fmt.Errorf("%s超过 %d 字节；反馈文档请控制在 %d 个字符以内。",
			label, feedbackMaxReadBytes, feedbackMaxCodePoints)
	}
	// **Refused, not repaired.** JSON would carry invalid bytes as U+FFFD, so the stored copy
	// would differ from the file while the command still said it was submitted.
	if !utf8.Valid(raw) {
		return "", fmt.Errorf("%s不是 UTF-8 文本；请先转成 UTF-8 再提交。", label)
	}
	return string(raw), nil
}

// feedbackAuthenticated is `authenticated` plus the second path acceptance #11 asks for: when the
// credential itself is the problem, login may be exactly what is broken, so the mailbox is named.
func feedbackAuthenticated[T any](ctx context.Context, call func(api.Client) (T, error)) (T, error) {
	value, err := authenticated(ctx, call)
	if err == nil || !feedbackCredentialProblem(err) {
		return value, err
	}
	return value, fmt.Errorf("%w\n      登录本身不可用时，发邮件到 contact@skillmasterai.com。", err)
}

func feedbackCredentialProblem(err error) bool {
	var login *loginFailure
	return credentialUnavailable(err) ||
		errors.As(err, &login) ||
		errors.Is(err, errNoBrowserNoCredential) ||
		errors.Is(err, api.ErrUnauthorized)
}

// feedbackAttachment is one locally validated file, before it becomes a multipart part.
type feedbackAttachment struct {
	Path      string
	Filename  string
	MediaType string
	SizeBytes int64
	SHA256    string
}

// readFeedbackAttachments applies the same rules the server will: count, type by header, per-file
// size, total size, non-empty. The server repeats every one of them; a client that skipped them
// would only learn the same answer one round trip later.
func readFeedbackAttachments(paths []string) ([]feedbackAttachment, error) {
	if len(paths) > feedback.MaxAttachments {
		return nil, &refusalError{message: fmt.Sprintf(
			"一条反馈最多带 %d 个附件（给了 %d 个）。", feedback.MaxAttachments, len(paths))}
	}
	attachments := make([]feedbackAttachment, 0, len(paths))
	var total int64
	for _, path := range paths {
		info, err := os.Stat(path)
		if err != nil {
			return nil, fmt.Errorf("读附件 %s：%w", path, err)
		}
		if !info.Mode().IsRegular() {
			return nil, &refusalError{message: fmt.Sprintf("附件「%s」不是普通文件。", path)}
		}
		if info.Size() <= 0 {
			return nil, &refusalError{message: fmt.Sprintf("附件「%s」是空文件。", path)}
		}
		media, err := feedback.DetectFile(path)
		if errors.Is(err, feedback.ErrUnsupportedType) {
			return nil, &refusalError{message: fmt.Sprintf("附件「%s」不是允许的类型；允许：%s。",
				path, strings.Join(feedback.AllowedTypes(), "、"))}
		}
		if err != nil {
			return nil, err
		}
		if info.Size() > media.Limit {
			return nil, &refusalError{message: fmt.Sprintf("附件「%s」超过单文件上限 %s（实际 %s）。",
				path, feedback.HumanBytes(media.Limit), feedback.HumanBytes(info.Size()))}
		}
		total += info.Size()
		if total > feedback.MaxTotalBytes {
			return nil, &refusalError{message: fmt.Sprintf("附件合计超过 %s（实际 %s）。",
				feedback.HumanBytes(feedback.MaxTotalBytes), feedback.HumanBytes(total))}
		}
		digest, err := feedback.SHA256File(path)
		if err != nil {
			return nil, err
		}
		attachments = append(attachments, feedbackAttachment{
			Path:      path,
			Filename:  feedback.DisplayName(path),
			MediaType: media.Type,
			SizeBytes: info.Size(),
			SHA256:    digest,
		})
	}
	return attachments, nil
}

func printFeedbackPreview(out io.Writer, content string, consent api.FeedbackConsent,
	opts feedbackSendOptions, attachments []feedbackAttachment) {
	fmt.Fprintln(out, "将发送的反馈文档：")
	fmt.Fprintln(out, "────────────────────────────────")
	fmt.Fprintln(out, content)
	fmt.Fprintln(out, "────────────────────────────────")
	fmt.Fprintln(out, "同意说明：")
	fmt.Fprintln(out, consent.Notice)
	fmt.Fprintln(out, "将发送的字段：文档全文、账号标识、提交时间")
	if opts.skill != "" {
		fmt.Fprintf(out, "关联 skill：%s\n", opts.skill)
	}
	if opts.attach {
		fmt.Fprintf(out, "诊断：%s\n", strings.Join(feedbackDiagnosticKeys(opts), "、"))
	}
	if len(attachments) > 0 {
		var total int64
		fmt.Fprintf(out, "附件（%d 个）：\n", len(attachments))
		for index, attachment := range attachments {
			total += attachment.SizeBytes
			fmt.Fprintf(out, "  %d. %s  %s  %s  sha256 %s\n",
				index+1, attachment.Filename, attachment.MediaType,
				feedback.HumanBytes(attachment.SizeBytes), attachment.SHA256)
		}
		fmt.Fprintf(out, "合计：%s\n", feedback.HumanBytes(total))
		// **Said at the moment of the decision, not only in the policy.** The text scan cannot see
		// inside an image, and the bytes keep their metadata; the person is the last check.
		fmt.Fprintln(out, "图片或视频不会被自动扫描；请确认画面里没有密钥、个人信息或他人隐私；"+
			"图片或视频可能含位置等元数据。")
	}
	fmt.Fprintln(out, "确认后运行同一条命令并加 --consent。")
}

func feedbackDiagnostics(opts feedbackSendOptions) map[string]string {
	if !opts.attach {
		return nil
	}
	diagnostics := map[string]string{
		"cli_version": version.String(),
		"os":          runtime.GOOS,
		"arch":        runtime.GOARCH,
		"at":          time.Now().UTC().Format(time.RFC3339),
	}
	if opts.command != "" {
		diagnostics["command"] = firstWord(opts.command)
	}
	if opts.errorCode != "" {
		diagnostics["error_code"] = opts.errorCode
	}
	return diagnostics
}

func feedbackDiagnosticKeys(opts feedbackSendOptions) []string {
	keys := []string{"cli_version", "os", "arch", "at"}
	if opts.command != "" {
		keys = append(keys, "command")
	}
	if opts.errorCode != "" {
		keys = append(keys, "error_code")
	}
	return keys
}

func firstWord(text string) string {
	fields := strings.Fields(text)
	if len(fields) == 0 {
		return ""
	}
	return fields[0]
}

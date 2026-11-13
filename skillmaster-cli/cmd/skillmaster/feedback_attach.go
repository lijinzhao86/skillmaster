// `feedback attach`: turn the media a person just pasted into a stable local file the send command
// can name (v65).

package main

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/feedback"
)

type feedbackAttachOptions struct {
	copyPath  string
	lastPaste bool
	clipboard bool
	maxAge    time.Duration
	maxAgeSet bool
	json      bool
}

func feedbackAttach(_ context.Context, args []string) error {
	jsonMode := jsonModeIn(args, "--copy", "--max-age")
	opts, err := parseFeedbackAttach(args)
	if err != nil {
		return versionErr(jsonMode, err, "")
	}
	opts.json = jsonMode

	sources := 0
	if opts.copyPath != "" {
		sources++
	}
	if opts.lastPaste {
		sources++
	}
	if opts.clipboard {
		sources++
	}
	if sources != 1 {
		return versionErr(opts.json, usagef(
			"要选一个附件来源：--copy <路径>、--last-paste 或 --clipboard。%s",
			usageAt("feedback attach")), "")
	}
	if opts.maxAgeSet && !opts.lastPaste {
		return versionErr(opts.json, usagef(
			"--max-age 只对 --last-paste 的会话缓存有效。%s", usageAt("feedback attach")), "")
	}

	media, err := feedback.Attach(feedback.AttachRequest{
		CopyPath:  opts.copyPath,
		LastPaste: opts.lastPaste,
		Clipboard: opts.clipboard,
		MaxAge:    opts.maxAge,
	})
	if err != nil {
		return versionErr(opts.json, attachError(err), "")
	}
	if opts.json {
		return writeJSON(feedbackAttachResult{
			Path:         media.Path,
			Source:       media.Source,
			MediaType:    media.Media.Type,
			SizeBytes:    media.Size,
			SHA256:       media.SHA256,
			OriginalName: media.OriginalName,
		})
	}
	fmt.Printf("已取到：%s\n", media.Path)
	fmt.Printf("来源：%s\n", attachSourceLabel(media.Source))
	fmt.Printf("类型：%s\n", media.Media.Type)
	fmt.Printf("大小：%s\n", feedback.HumanBytes(media.Size))
	fmt.Printf("sha256：%s\n", media.SHA256)
	fmt.Printf("下一步：skmr feedback send --file <文档> --attach %s\n", media.Path)
	return nil
}

func parseFeedbackAttach(args []string) (feedbackAttachOptions, error) {
	opts := feedbackAttachOptions{maxAge: 30 * time.Minute}
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--copy", "--max-age":
			if i+1 >= len(args) {
				return opts, usagef("%s 后面要跟一个值。%s", args[i], usageAt("feedback attach"))
			}
			flag, value := args[i], args[i+1]
			i++
			switch flag {
			case "--copy":
				opts.copyPath = value
			case "--max-age":
				duration, err := time.ParseDuration(value)
				if err != nil || duration <= 0 {
					return opts, usagef("--max-age 要写成 30m、2h 这样的正数时长。%s",
						usageAt("feedback attach"))
				}
				opts.maxAge = duration
				opts.maxAgeSet = true
			}
		case "--last-paste":
			opts.lastPaste = true
		case "--clipboard":
			opts.clipboard = true
		case "--json":
			opts.json = true
		default:
			if len(args[i]) > 0 && args[i][0] == '-' {
				return opts, unknownFlag("feedback attach", args[i])
			}
			return opts, usagef("%s", usageAt("feedback attach"))
		}
	}
	return opts, nil
}

// attachError turns the package's sentinel values into the sentence the agent will act on. The
// last one is the important one: when every source is empty, the next step is to ask the person
// to drag the file in, not to retry the same command.
func attachError(err error) error {
	switch {
	case errors.Is(err, feedback.ErrNoPaste):
		return &refusalError{message: "没有找到最近粘贴的图片或视频。请把文件拖进对话（会带出路径），" +
			"或先保存成文件再用 --copy <路径>。"}
	case errors.Is(err, feedback.ErrClipboardUnsupported):
		return &refusalError{message: "当前平台不支持读取剪贴板；请用 --copy <路径>，" +
			"或把文件拖进对话。"}
	case errors.Is(err, feedback.ErrUnsupportedType):
		return &refusalError{message: "取到的文件不是允许的附件类型；允许：" +
			feedbackTypeSentence() + "。可以先把图片存成 PNG/JPEG，视频存成 MP4/MOV/WebM。"}
	default:
		return err
	}
}

func feedbackTypeSentence() string {
	types := feedback.AllowedTypes()
	sentence := ""
	for index, mediaType := range types {
		if index > 0 {
			sentence += "、"
		}
		sentence += mediaType
	}
	return sentence
}

func attachSourceLabel(source string) string {
	switch source {
	case "message_path":
		return "消息里的路径"
	case "session_cache":
		return "当前会话的粘贴缓存"
	case "clipboard":
		return "系统剪贴板"
	default:
		return source
	}
}

type feedbackAttachResult struct {
	Path         string `json:"path"`
	Source       string `json:"source"`
	MediaType    string `json:"media_type"`
	SizeBytes    int64  `json:"size_bytes"`
	SHA256       string `json:"sha256"`
	OriginalName string `json:"original_name"`
}

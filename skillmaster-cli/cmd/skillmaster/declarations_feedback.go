package main

import "github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"

// feedbackSpecs is the `feedback` group: the group and four command paths (v64; attach in v65).
var feedbackSpecs = []*help.Spec{
	{
		Path:      "feedback",
		Summary:   "把解决不了的问题与现场交给平台",
		Children:  []string{"template", "attach", "send", "delete"},
		PureGroup: true,
	},
	{
		Path: "feedback template", Summary: "打印反馈文档模板",
		Usage: "skmr feedback template",
		Args:  []help.Arg{},
		Output: "打印六节模板：我想做什么、发生了什么、怎么复现、环境、已经试过什么、不要写；" +
			"另有一段说明附件怎么带。不需要登录，也不发网络请求。",
		Failures: []help.Failure{
			{Code: "usage", Summary: "多给了参数", Next: "去掉参数：template 不带参数"},
		},
		Examples: []string{"skmr feedback template"},
		Parent:   "feedback", Next: "skmr feedback attach --last-paste",
		Exits: exitsLocal,
	},
	{
		Path: "feedback attach", Summary: "把贴的图片或视频存下来",
		Usage: "skmr feedback attach (--copy <路径> | --last-paste | --clipboard) " +
			"[--max-age <时长>] [--json]",
		Flags: []help.Flag{
			{Name: "--copy", Value: "<路径>", Summary: "把消息里已有的路径复制到稳定目录"},
			{Name: "--last-paste", Summary: "先取当前会话缓存，再取剪贴板"},
			{Name: "--clipboard", Summary: "只取系统剪贴板；仅 macOS 支持"},
			{Name: "--max-age", Value: "<时长>", Summary: "会话缓存回看范围，默认 30m"},
		},
		Args: []help.Arg{},
		Output: "打印稳定文件路径、来源、类型、大小与 sha256；把这个路径交给 " +
			"send --attach。文件放在 $TMPDIR/skmr-feedback/，发送成功后会清空内容，文件本身留着。",
		JSONFields: []string{"path", "source", "media_type", "size_bytes", "sha256",
			"original_name"},
		Failures: []help.Failure{
			{Code: "usage", Summary: "参数或来源不对", Next: "看用法行，选一个来源"},
		},
		Examples: []string{
			"skmr feedback attach --copy /tmp/shot.png",
			"skmr feedback attach --last-paste --json",
		},
		Parent: "feedback", Next: "skmr feedback send --file <文件> --attach <路径>",
		Exits: exitsLocal,
	},
	{
		Path: "feedback send", Summary: "预览或提交一份反馈",
		Usage: "skmr feedback send [--file <路径>] [--skill <地址>] [--attach <路径>]... " +
			"[--attach-diagnostics] [--command <命令>] [--error-code <码>] [--consent] " +
			"[--allow-sensitive] [--json]",
		Flags: []help.Flag{
			{Name: "--file", Value: "<路径>", Summary: "从文件读；不给就读标准输入"},
			{Name: "--skill", Value: "<地址>", Summary: "关联的 skill，只作上下文"},
			{Name: "--attach", Value: "<路径>", Summary: "图片或视频；可重复，最多 5 个"},
			{Name: "--attach-diagnostics", Summary: "附带白名单诊断字段"},
			{Name: "--command", Value: "<命令>", Summary: "出问题的命令名"},
			{Name: "--error-code", Value: "<码>", Summary: "服务端或 CLI 的错误码"},
			{Name: "--consent", Summary: "确认内容后发送"},
			{Name: "--allow-sensitive", Summary: "确认敏感信息是问题本身"},
		},
		Args: []help.Arg{},
		Output: "不带 --consent 只预览，不发送，退出码 1；带 --consent 才提交并打印回执。" +
			"附件不会被自动扫描。上限 5 个；图片≤10 MiB、视频≤50 MiB、合计≤100 MiB。",
		JSONFields: []string{"receipt", "created_at", "attachments"},
		Failures: []help.Failure{
			{Code: "feedback_sensitive", Summary: "内容可能有个人信息",
				Next: "确认后加 --allow-sensitive"},
			{Code: "feedback_attachment_type", Summary: "附件类型不允许",
				Next: "换成允许的格式"},
			{Code: "feedback_attachment_too_large", Summary: "附件超过单文件上限",
				Next: "压缩后再试"},
			{Code: "feedback_attachment_total_too_large", Summary: "附件合计超过上限",
				Next: "减少数量或大小"},
			{Code: "feedback_attachment_too_many", Summary: "附件数量超过上限",
				Next: "最多 5 个附件"},
			{Code: "feedback_attachment_empty", Summary: "附件是空文件",
				Next: "换一个非空文件"},
			{Code: "feedback_attachment_unreadable", Summary: "附件读不出来",
				Next: "确认文件可读"},
			{Code: "feedback_attachment_unavailable", Summary: "附件存储不可用",
				Next: "重试一次；文本仍可提交"},
			{Code: "feedback_throttled", Summary: "反馈太频繁",
				Next: "等一小时"},
			{Code: "feedback_consent_stale", Summary: "同意说明已更新",
				Next: "重新预览"},
			{Code: "unauthenticated", Summary: help.UnauthenticatedSummary,
				Next: help.UnauthenticatedNext},
		},
		Examples: []string{
			"skmr feedback send --file issue.md",
			"skmr feedback send --file issue.md --attach /tmp/shot.png --consent",
		},
		Parent: "feedback", Next: "skmr feedback delete <回执> --yes",
		Exits: exitsWithCredential,
	},
	{
		Path: "feedback delete", Summary: "撤回并删除一条反馈",
		Usage: "skmr feedback delete <回执> [--yes] [--json]",
		Flags: []help.Flag{
			{Name: "--yes", Summary: "确认删除"},
		},
		Args:       []help.Arg{{Name: "<回执>", Key: "receipt"}},
		Output:     "不带 --yes 时只打印将要删除的回执，不发请求，退出码 1；带 --yes 时删除数据库行、附件对象与归档副本。",
		JSONFields: []string{"receipt", "deleted"},
		Failures: []help.Failure{
			{Code: "feedback_not_found", Summary: "没有这条反馈，或它不属于你",
				Next: "核对回执号"},
			{Code: "unauthenticated", Summary: help.UnauthenticatedSummary,
				Next: help.UnauthenticatedNext},
		},
		Examples: []string{"skmr feedback delete FB-20261113-ABC123 --yes"},
		Parent:   "feedback", Next: "skmr feedback template",
		Exits: exitsWithCredential,
	},
}

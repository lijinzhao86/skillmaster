package main

import "github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"

// feedbackSpecs is the `feedback` group: the group and three command paths (v63).
var feedbackSpecs = []*help.Spec{
	{
		Path:      "feedback",
		Summary:   "把解决不了的问题交给平台",
		Children:  []string{"template", "send", "delete"},
		PureGroup: true,
	},
	{
		Path: "feedback template", Summary: "打印反馈文档模板",
		Usage:  "skmr feedback template",
		Args:   []help.Arg{},
		Output: "打印六节模板：我想做什么、发生了什么、怎么复现、环境、已经试过什么、不要写。不需要登录，也不发网络请求。",
		Failures: []help.Failure{
			{Code: "usage", Summary: "多给了参数", Next: "去掉参数：template 不带参数"},
		},
		Examples: []string{"skmr feedback template"},
		Parent:   "feedback", Next: "skmr feedback send --file <文件>",
		Exits: exitsLocal,
	},
	{
		Path: "feedback send", Summary: "预览或提交一份反馈",
		Usage: "skmr feedback send [--file <路径>] [--skill <地址>] [--attach-diagnostics] " +
			"[--command <命令>] [--error-code <码>] [--consent] [--allow-sensitive] [--json]",
		Flags: []help.Flag{
			{Name: "--file", Value: "<路径>", Summary: "从文件读；不给就读标准输入"},
			{Name: "--skill", Value: "<地址>", Summary: "关联的 skill，只作上下文"},
			{Name: "--attach-diagnostics", Summary: "附带白名单诊断字段"},
			{Name: "--command", Value: "<命令>", Summary: "出问题的命令名"},
			{Name: "--error-code", Value: "<码>", Summary: "服务端或 CLI 的错误码"},
			{Name: "--consent", Summary: "确认内容后发送"},
			{Name: "--allow-sensitive", Summary: "确认敏感信息是问题本身"},
		},
		Args: []help.Arg{},
		Output: "不带 --consent 时只打印完整文档、同意说明与将发送的字段，不发送，退出码 1；" +
			"带 --consent 时提交并打印回执号，另给一条站内通知。",
		JSONFields: []string{"receipt", "created_at"},
		Failures: []help.Failure{
			{Code: "feedback_sensitive", Summary: "内容里可能有个人信息",
				Next: "确认是问题本身后加 --allow-sensitive"},
			{Code: "feedback_throttled", Summary: "一小时内的反馈条数用完了",
				Next: "等消息里的时间再试"},
			{Code: "feedback_consent_stale", Summary: "同意说明已更新",
				Next: "重新预览一次"},
			{Code: "unauthenticated", Summary: help.UnauthenticatedSummary,
				Next: help.UnauthenticatedNext},
		},
		Examples: []string{
			"skmr feedback send --file issue.md",
			"skmr feedback send --file issue.md --consent",
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
		Output:     "不带 --yes 时只打印将要删除的回执，不发请求，退出码 1；带 --yes 时删除数据库行与归档副本。",
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

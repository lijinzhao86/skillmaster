package main

import "github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"

// usageSpecs is `usage` (v58): the personal interaction log, its stats, and a skill's owner view.
var usageSpecs = []*help.Spec{
	{
		Path:    "usage",
		Summary: "使用记录",
		Usage: "skmr usage [<地址>] [--stats] [--expand] [--users] [--mine] [--page <页码>] [--limit <条数>] [--json]",
		Flags: []help.Flag{
			{Name: "--expand", Summary: "展开搜索结果（截断时说明）"},
			{Name: "--page", Value: "<页码>", Summary: "明细第几页，默认 1"},
			{Name: "--limit", Value: "<条数>", Summary: "每页条数，默认 50、最大 100"},
			{Name: "--stats", Summary: "我的统计"},
			{Name: "--users", Summary: "只要这个 skill 的使用者表（所有者）"},
			{Name: "--mine", Summary: "我在这条 skill 上的使用，按版本"},
		},
		Args: []help.Arg{{Name: "<地址>", Key: "address"}},
		Output: "一条交互一行：搜索词、总结果数、随后调用了哪条与原名次；一次搜索调了多条就分行列出。" +
			"`--expand` 再铺开当次返回的结果（最多 50 条）。`--stats` 与网页统计同一组数；" +
			"`<地址>` 是所有者视角（聚合 + 使用者表），`--users` 只要表，`--mine` 是本人在这条 skill 上的使用。",
		JSONFields: []string{"items", "total", "page", "page_size", "window_days",
			"calls", "searches", "converted", "missed", "skills", "latest", "top",
			"skill", "public", "totals", "users", "versions", "by_version"},
		Failures: []help.Failure{
			{Code: "unauthenticated", Summary: help.UnauthenticatedSummary, Next: help.UnauthenticatedNext},
			{Code: "not_found", Summary: "没有这个 skill，或使用者表只给所有者", Next: "核对地址；使用者表与聚合只对所有者返回"},
			{Code: "invalid_request", Summary: "页码或每页条数超出范围", Next: "页码从 1 开始；每页 1–100 条"},
		},
		Examples: []string{
			"skmr usage --expand",
			"skmr usage demo/pdf-tools --mine",
		},
		Next:  "找回一条 skill 后用 skmr skill invoke <地址> 再调用",
		Exits: exitsWithCredential,
	},
}

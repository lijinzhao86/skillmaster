package main

import "github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"

// usageSpecs is `usage` (v58): the personal interaction log, its stats, and a skill's owner view.
var usageSpecs = []*help.Spec{
	{
		Path:    "usage",
		Summary: "使用记录",
		Usage:   "skmr usage [<地址>] [--stats] [--expand] [--users] [--mine] [--days <1–90> | --since <YYYY-MM-DD> --until <YYYY-MM-DD>] [--skill <关键词或地址>] [--page <页码>] [--limit <条数>] [--json]",
		Flags: []help.Flag{
			{Name: "--days", Value: "<1–90>", Summary: "近 N 天；默认 30，与自定义二选一"},
			{Name: "--since", Value: "<YYYY-MM-DD>", Summary: "自定义开始日，含当天"},
			{Name: "--until", Value: "<YYYY-MM-DD>", Summary: "自定义结束日，含当天"},
			{Name: "--skill", Value: "<关键词或地址>", Summary: "只看这条 skill 的交互与统计"},
			{Name: "--expand", Summary: "展开搜索结果（截断时说明）"},
			{Name: "--page", Value: "<页码>", Summary: "明细第几页，默认 1"},
			{Name: "--limit", Value: "<条数>", Summary: "每页条数，默认 50、最大 100"},
			{Name: "--stats", Summary: "我的统计"},
			{Name: "--users", Summary: "只要这个 skill 的使用者表（所有者）"},
			{Name: "--mine", Summary: "我在这条 skill 上的使用，按版本"},
		},
		Args: []help.Arg{{Name: "<地址>", Key: "address"}},
		Output: "一条交互一行：搜索词、结果数、随后调用的地址与名次；`--expand` 铺开当次结果。" +
			"尾行与统计首行写明区间，`--skill` 再挂过滤词。`<地址>` 是所有者视角（聚合 + 使用者表）；" +
			"`--users` 只要表，`--mine` 是本人在它上面的使用。",
		JSONFields: []string{"items", "total", "page", "page_size", "window_days",
			"since", "until", "skill", "has_any",
			"calls", "searches", "converted", "missed", "skills", "latest", "top",
			"public", "totals", "users", "versions", "by_version"},
		Failures: []help.Failure{
			{Code: "unauthenticated", Summary: help.UnauthenticatedSummary, Next: help.UnauthenticatedNext},
			{Code: "not_found", Summary: "没有这个 skill，或使用者表只给所有者", Next: "核对地址；使用者表与聚合只对所有者返回"},
			{Code: "invalid_request", Summary: "页码、条数或时间区间不合法", Next: "核对页码、条数与时间区间"},
		},
		Examples: []string{
			"skmr usage --days 7 --skill pdf",
			"skmr usage demo/pdf-tools --mine",
		},
		Next:  "找回一条 skill 后用 skmr skill invoke <地址> 再调用",
		Exits: exitsWithCredential,
	},
}

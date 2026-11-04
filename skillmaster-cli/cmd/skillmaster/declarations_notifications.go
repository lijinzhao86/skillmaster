package main

import "github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"

// notificationSpecs is the `notifications` group (v45): the feed, the two invitation answers and
// the two request decisions.
var notificationSpecs = []*help.Spec{
	{
		Path:      "notifications",
		Summary:   "通知：待处理与全部",
		Children:  []string{"list", "show", "read", "accept", "decline", "approve", "deny"},
		PureGroup: true,
	},
	{
		Path: "notifications list", Summary: "看通知与下一步命令",
		// Bare `skmr notifications` is this command (see `notificationsVerb`); the help still names
		// the verb so the group's children and the dispatch switch agree.
		Usage: "skmr notifications list [--status attention|pending|all] [--unread] [--page <页码>] [--json]",
		Flags: []help.Flag{
			{Name: "--status", Value: "attention|pending|all", Summary: "默认 attention：待处理 + 未读"},
			{Name: "--unread", Summary: "只列未读"},
			{Name: "--page", Value: "<页码>", Summary: "翻页"},
		},
		Args:       []help.Arg{},
		Output:     "每条一行，下面给出可复制的下一步命令：待接受邀请给 accept / decline；待处理申请给 approve / deny（带 --yes）与网页处理地址；已接受 / 已批准给 skill invoke / open。处理完的留档在 --status all 里。",
		JSONFields: []string{"items", "total", "unread"},
		Failures: []help.Failure{
			{Code: "unauthenticated", Summary: "凭据不被接受", Next: credentialNext},
		},
		Examples: []string{"skmr notifications list", "skmr notifications list --status all --unread"},
		Parent:   "notifications", Next: "对某条按它下面那行命令做；完整内容用 skmr notifications show <id>",
		Exits: exitsWithCredential,
	},
	{
		Path: "notifications show", Summary: "看一条通知的全文",
		Usage:      "skmr notifications show <id> [--json]",
		Args:       []help.Arg{{Name: "<id>", Key: "id", Required: true}},
		Output:     "完整正文、指向的 skill 与下一步命令；只读，不自动标已读（要标用 notifications read）。",
		JSONFields: []string{"id", "kind", "state", "actor", "skill", "role", "title", "body", "created_at", "resolved_at", "expires_at", "read", "viewable"},
		Failures: []help.Failure{
			{Code: "notification_not_found", Summary: "没有这条通知，或不是你的", Next: "用 skmr notifications list 核对 id"},
		},
		Examples: []string{"skmr notifications show 01J..."},
		Parent:   "notifications", Exits: exitsWithCredential,
	},
	{
		Path: "notifications read", Summary: "标为已读",
		Usage:      "skmr notifications read <id> | --all [--json]",
		Flags:      []help.Flag{{Name: "--all", Summary: "全部标为已读"}},
		Args:       []help.Arg{{Name: "<id>", Key: "id"}},
		Output:     "幂等。只影响未读数与那枚点，不改变邀请或申请能不能处理——已读不等于已处理。",
		JSONFields: []string{"id", "read", "marked"},
		Failures: []help.Failure{
			{Code: "insufficient_permission", Summary: "标已读要可写密钥（skills:write）", Next: "换一把可写密钥；只读密钥能 list / show，不能标已读"},
			{Code: "notification_not_found", Summary: "没有这条通知，或不是你的", Next: "用 skmr notifications list 核对 id"},
		},
		Examples: []string{"skmr notifications read --all", "skmr notifications read 01J..."},
		Parent:   "notifications", Exits: exitsWithCredential,
	},
	{
		Path: "notifications accept", Summary: "接受共享邀请",
		Usage:      "skmr notifications accept <id> [--json]",
		Args:       []help.Arg{{Name: "<id>", Key: "id", Required: true}},
		Output:     "回显拿到的 skill 与档位。接受后才落授权；之前那个 skill 对你仍然不存在（私有档）。",
		JSONFields: []string{"skill", "role"},
		Failures: []help.Failure{
			{Code: "insufficient_permission", Summary: "接受要可写密钥（skills:write）", Next: "换一把可写密钥；只读密钥不能接受邀请"},
			{Code: "invalid_notification_state", Summary: "邀请已过期或已处理", Next: "看 skmr notifications list"},
			{Code: "notification_not_found", Summary: "没有这条通知，或不是你的", Next: "用 skmr notifications list 核对 id"},
		},
		Examples: []string{"skmr notifications accept 01J..."},
		Parent:   "notifications", Next: "接着用 skmr skill invoke <地址>", Exits: exitsWithCredential,
	},
	{
		Path: "notifications decline", Summary: "拒绝共享邀请",
		Usage:      "skmr notifications decline <id> [--json]",
		Args:       []help.Arg{{Name: "<id>", Key: "id", Required: true}},
		Output:     "邀请行消失，不产生授权；通知留档为「已拒绝」。",
		JSONFields: []string{"id", "declined"},
		Failures: []help.Failure{
			{Code: "insufficient_permission", Summary: "拒绝要可写密钥（skills:write）", Next: "换一把可写密钥；只读密钥不能拒绝邀请"},
			{Code: "invalid_notification_state", Summary: "邀请已过期或已处理", Next: "看 skmr notifications list"},
			{Code: "notification_not_found", Summary: "没有这条通知，或不是你的", Next: "用 skmr notifications list 核对 id"},
		},
		Examples: []string{"skmr notifications decline 01J..."},
		Parent:   "notifications", Exits: exitsWithCredential,
	},
	{
		Path: "notifications approve", Summary: "批准权限申请",
		Usage: "skmr notifications approve <id> [--role <档位>] --yes [--json]",
		Flags: []help.Flag{
			{Name: "--role", Value: "<档位>", Summary: "批准的档位，默认对方申请的那一档"},
			{Name: "--yes", Summary: "确认执行"},
		},
		Args:       []help.Arg{{Name: "<id>", Key: "id", Required: true}},
		Output:     "不带 --yes 只打印后果与完整命令，不执行（退出码非 0）。批准后写入授权、申请行消失、对方收到结果；控制台仍保留可改档位的决定界面，两边调用同一条用例。",
		JSONFields: []string{"skill", "role"},
		Failures: []help.Failure{
			{Code: "insufficient_permission", Summary: "你不是所有者或管理档", Next: "请所有者或 admin 处理"},
			{Code: "invalid_notification_state", Summary: "申请已被处理或撤回", Next: "看 skmr notifications list"},
			{Code: "notification_not_found", Summary: "没有这条通知，或不是你的", Next: "用 skmr notifications list 核对 id"},
		},
		Examples: []string{"skmr notifications approve 01J... --role write --yes"},
		Parent:   "notifications", Exits: exitsWithCredential,
	},
	{
		Path: "notifications deny", Summary: "拒绝权限申请",
		Usage:      "skmr notifications deny <id> --yes [--json]",
		Flags:      []help.Flag{{Name: "--yes", Summary: "确认执行"}},
		Args:       []help.Arg{{Name: "<id>", Key: "id", Required: true}},
		Output:     "不带 --yes 只打印后果与完整命令，不执行（退出码非 0）。拒绝后申请行消失、不产生授权，对方收到「未通过」的结果。",
		JSONFields: []string{"id", "approved"},
		Failures: []help.Failure{
			{Code: "insufficient_permission", Summary: "你不是所有者或管理档", Next: "请所有者或 admin 处理"},
			{Code: "invalid_notification_state", Summary: "申请已被处理或撤回", Next: "看 skmr notifications list"},
			{Code: "notification_not_found", Summary: "没有这条通知，或不是你的", Next: "用 skmr notifications list 核对 id"},
		},
		Examples: []string{"skmr notifications deny 01J... --yes"},
		Parent:   "notifications", Exits: exitsWithCredential,
	},
}

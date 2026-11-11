package main

import "github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"

// The four skill-level page actions (v37, ADR 0126): each opens the settings page after one read of
// the author plane decides whether this credential could act. Split from `skillSpecs` so neither
// file outgrows its limit.
var skillPageSpecs = []*help.Spec{
	{
		Path: "skill delete", Summary: "删除 skill（网页）",
		// **The confirmation switch is deliberately not in this help** (ADR 0128). Help must
		// offer the safe command: running it prints the consequence and, only when the server would
		// allow the delete, the full command to confirm with. Advertising it here would make a
		// one-step delete the first thing somebody copies.
		Usage:      "skmr skill delete <命名空间>/<名字> [--json]",
		Args:       []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		Output:     "先说明后果与下一步，不删除。确认后按输出重跑：能删时给出带确认的完整命令，由服务端判定是否允许；上线过的 skill 到设置页「常规」由人在网页上确认删除（退出 1）。删除后名字立即释放。",
		JSONFields: []string{"action", "performed", "address", "deleted"},
		Failures: []help.Failure{
			{Code: "confirmation_required", Summary: "删除还没有执行", Next: "按输出里给出的完整命令重跑"},
			{Code: "precheck_unavailable", Summary: "没能确认它是否上线过", Next: "在能读到作者页的环境里重跑，或到网页上确认"},
			{Code: "skill_published", Summary: "上线过的 skill 不能由凭据删", Next: "到网页上由人确认"},
			{Code: "insufficient_permission", Summary: "你不是所有者", Next: "请所有者来删"},
			{Code: "skill_not_found", Summary: "看不到这个 skill", Next: "用 skmr skills list 核对"},
		},
		Examples: []string{"skmr skill delete dev/typo-skill"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill offline", Summary: "下线（打开网页确认）",
		Usage: "skmr skill offline <命名空间>/<名字> [--print] [--json]",
		Flags: []help.Flag{
			{Name: "--print", Summary: "只打印地址，不打开浏览器"},
		},
		Args:       []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		JSONFields: []string{"action", "performed", "url", "opened"},
		Output:     "确认你是所有者、且它在线（不在下线中），然后打开设置页的「常规」；命令不执行下线，确认在网页上由人完成，首句说明它还没发生。下线中时命令拒绝，取消下线用 skmr skill online。",
		Failures: []help.Failure{
			{Code: "insufficient_permission", Summary: "只有所有者能下线", Next: "把地址给所有者"},
			{Code: "wrong_version_state", Summary: "它已下线或在离线前的过渡期", Next: "取消下线中或恢复服务用 skmr skill online"},
		},
		Examples: []string{"skmr skill offline dev/pdf-tools"},
		Parent:   "skill", Exits: exitsLocal,
	},
	{
		Path: "skill online", Summary: "恢复服务（打开网页确认）",
		Usage: "skmr skill online <命名空间>/<名字> [--print] [--json]",
		Flags: []help.Flag{
			{Name: "--print", Summary: "只打印地址，不打开浏览器"},
		},
		Args:       []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		JSONFields: []string{"action", "performed", "url", "opened"},
		Output:     "确认你是所有者、且它已下线或在下线中，然后打开设置页的「常规」；命令不执行恢复服务，确认在网页上由人完成，首句说明它还没发生。下线中时这一页就是取消下线。",
		Failures: []help.Failure{
			{Code: "insufficient_permission", Summary: "只有所有者能恢复服务", Next: "把地址给所有者"},
			{Code: "wrong_version_state", Summary: "它既没下线，也不在下线中", Next: "要下线用 skmr skill offline"},
		},
		Examples: []string{"skmr skill online dev/pdf-tools"},
		Parent:   "skill", Exits: exitsLocal,
	},
	{
		Path: "skill visibility", Summary: "改可见性（打开网页确认）",
		Usage: "skmr skill visibility <命名空间>/<名字> [--print] [--json]",
		Flags: []help.Flag{
			{Name: "--print", Summary: "只打印地址，不打开浏览器"},
		},
		Args:       []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		JSONFields: []string{"action", "performed", "url", "opened"},
		Output:     "确认你有 admin 及以上，然后打开设置页的「常规」；命令不执行改可见性，确认在网页上由人完成，首句说明它还没改。命令不带目标值：网页没有按参数预选的能力。",
		Failures: []help.Failure{
			{Code: "insufficient_permission", Summary: "要 admin 及以上", Next: "把地址给有这个档位的人"},
		},
		Examples: []string{"skmr skill visibility dev/pdf-tools"},
		Parent:   "skill", Exits: exitsLocal,
	},
}

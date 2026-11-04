package main

import "github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"

// skillRoleSpecs holds the one collaborator verb added in v45. It lives apart so the large
// `skill` declaration file stays inside its size limit.
var skillRoleSpecs = []*help.Spec{
	{
		Path: "skill role", Summary: "改已生效协作者的档位",
		Usage: "skmr skill role <命名空间>/<名字> --user <用户名> --role <档位> [--json]",
		Flags: []help.Flag{
			{Name: "--user", Value: "<用户名>", Summary: "改谁"},
			{Name: "--role", Value: "invoke|browse|write|maintain|admin", Summary: "改成哪一档"},
		},
		Args:       []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		Output:     "只降不升：降档直接生效并记审计；升档要用 skmr skill share 发邀请，对方接受后生效。",
		JSONFields: []string{"handle", "role", "changed"},
		Failures: []help.Failure{
			{Code: "invalid_request", Summary: "升档不能直接改", Next: "用 skmr skill share 发邀请（不要直接升档）"},
			{Code: "insufficient_permission", Summary: "你不是所有者或管理档", Next: "请所有者或 admin 改"},
			{Code: "skill_not_found", Summary: "这个人没有已生效的授权", Next: "用 skmr skill shares 核对"},
		},
		Examples: []string{"skmr skill role dev/pdf-tools --user alice --role write"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
}

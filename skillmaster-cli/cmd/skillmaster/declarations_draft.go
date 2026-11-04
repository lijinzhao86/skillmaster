package main

import "github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"

// The `draft` group: creating a draft is its own action (v44, ADR 0135).
//
// **`--new` is deliberately absent from the flags below.** The command refuses when the caller
// already has an open draft, and the override is named only in that refusal — the error is where a
// caller learns it is a decision to ask the user about, not a flag to reach for.
var draftSpecs = []*help.Spec{
	{
		Path:      "draft",
		Summary:   "草稿的动词",
		Children:  []string{"create"},
		PureGroup: true,
	},
	{
		Path: "draft create", Summary: "新建一份草稿",
		Usage: "skmr draft create <命名空间>/<名字> [--from <版本|版本码>] --name <名称> [--note <说明>]",
		Flags: []help.Flag{
			{Name: "--from", Value: "<版本|版本码>", Summary: "从哪一版起稿；省略时用线上版本"},
			{Name: "--name", Value: "<名称>", Summary: "这一版的名字（1–40 字符）"},
			{Name: "--note", Value: "<说明>", Summary: "这一版的说明"},
		},
		Args: []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		Output: "版本码与一条可直接复制的下一步写命令，走 stderr。默认从线上版本整版复制、内容不变。" +
			"你在这个 skill 下已有未上线的草稿时会拒绝并列出草稿；确实要另开一份，要先问用户。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址或底稿找不到", Next: "核对命名空间、名字与 --from"},
			{Code: "draft_name_required", Summary: "没给版本名", Next: "加 --name"},
			{Code: "insufficient_permission", Summary: "你没有这个 skill 的写权限", Next: "请所有者给你 write 及以上"},
		},
		Examples: []string{
			"skmr draft create dev/pdf-tools --name 第二版",
			"skmr draft create dev/pdf-tools --from 1.2.0 --name 从旧版起稿",
		},
		Parent: "draft",
		Next:   "skmr skill write <地址>@<版本码> <路径> --from <本地文件>",
		Exits:  exitsWithCredential,
	},
}

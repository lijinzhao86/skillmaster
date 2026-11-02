package main

import "github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"

// 文件传输的三条命令：put / get / download（v39 扩了 put 与 get 的说明）。从 `skillSpecs` 拆出来，
// 两边都不越行数上限。
var skillFileSpecs = []*help.Spec{
	{
		Path: "skill put", Summary: "传本地文件",
		Usage: "skmr skill put <命名空间>/<名字>[@<版本码>] <本地路径>... <远端路径|目录/> [--overwrite]",
		Flags: []help.Flag{
			{Name: "--overwrite", Summary: "目标已存在时覆盖：默认拒绝"},
		},
		Args: []help.Arg{
			{Name: "<命名空间>/<名字>[@<版本码>]", Key: "address", Required: true},
			{Name: "<本地路径>", Key: "local", Required: true},
			{Name: "<远端路径|目录/>", Key: "path", Required: true},
		},
		Output: "逐个文件把成功信息打在 stderr，stdout 为空；远端路径以 / 结尾或传多个文件时，" +
			"按每个文件的 basename 放进那个目录。只写已经存在的草稿；二进制按原字节保存，" +
			"文本（不超过 16 MiB）与 write 一样做换行 / BOM / NFC 归一。默认拒绝覆盖已有路径，" +
			"--overwrite 才覆盖。写草稿请直接给 @<版本码>（version list --status draft 能找到它）；裸地址要先 " +
			"invoke <地址>@<版本码> 钉过这份草稿才可用。新版本的第一份内容就是二进制时：printf '占位\\n' " +
			"| skmr skill write <地址>@new _placeholder.txt --name <名称> 建出草稿（占位路径不要用 SKILL.md、" +
			"不要与目标文件同路径、不要用底稿已有的路径——那会覆盖底稿里的那份），上传之后再 skmr skill rm 删掉它。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "草稿或目标路径找不到", Next: "用 version list --status draft 核对版本码"},
			{Code: "digest_mismatch", Summary: "这一版在读过之后被改动过", Next: "重新跑一次命令"},
		},
		Examples: []string{
			"skmr skill put dev/my-skill@a1b2c3 ./design.png references/design.png",
			"skmr skill put dev/my-skill@a1b2c3 ./a.png ./b.pdf references/ --overwrite",
		},
		Parent: "skill", Next: "取回来用 skmr skill get；整版打包用 skmr skill download", Exits: exitsWithCredential,
	},
	{
		Path: "skill get", Summary: "取一个文件到本地",
		Usage: "skmr skill get <命名空间>/<名字>[@版本] <相对路径> [-o <本地路径>] [--overwrite]",
		Flags: []help.Flag{
			{Name: "-o", Value: "<本地路径>", Summary: "写到哪；省略用当前目录同名文件；- 为标准输出"},
			{Name: "--overwrite", Summary: "本地已有同名文件时覆盖：默认拒绝"},
		},
		Args: []help.Arg{
			{Name: "<命名空间>/<名字>[@版本]", Key: "address", Required: true},
			{Name: "<相对路径>", Key: "path", Required: true},
		},
		Output: "文件写到 -o 指定的路径（省略时是当前目录的 basename），成功信息与 sha256 打在 stderr。" +
			"先写临时文件、成功后再改名，中断不会留下半份。任意类型；只想在终端看一眼用 skill cat，" +
			"整版打包用 skill download（它把整包读进内存，上限约 17 MiB；更大的版本用本命令逐个取）。\n" +
			versionRuleLine,
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址或这一版找不到", Next: "用 skmr version list 核对"},
			{Code: "insufficient_permission", Summary: "这一档读不了这个文件", Next: "请所有者给你 browse 及以上"},
		},
		Examples: []string{
			"skmr skill get dev/my-skill@a1b2c3 references/design.png",
			"skmr skill get dev/my-skill@1.2.3 references/manual.pdf -o ./manual.pdf",
		},
		Parent: "skill", Next: "改文件用 skmr skill put / write；整版打包用 skmr skill download", Exits: exitsWithCredential,
	},
	{
		Path: "skill download", Summary: "整版取成一个目录",
		Usage:  "skmr skill download <命名空间>/<名字>[@版本 | @版本码] [--into <目录>]",
		Flags:  []help.Flag{{Name: "--into", Value: "<目录>", Summary: "写到哪个目录"}},
		Args:   []help.Arg{{Name: "<命名空间>/<名字>[@版本 | @版本码]", Key: "address", Required: true}},
		Output: "把这一版写到目标目录并打印落点。这是一次性快照：往返一次摘要相同，适合分发；要改这一版用 skill write / edit，要导入一个本地目录用 skill submit <目录> --name <名>。客户端把整包读进内存，上限约 17 MiB；更大的版本用 skill get 逐个取文件。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址找不到", Next: "核对命名空间与名字"},
			{Code: "usage", Summary: "目标目录不为空", Next: "换一个空目录或删掉目标"},
		},
		Examples: []string{"skmr skill download dev/pdf-tools", "skmr skill download dev/pdf-tools --into ./pdf"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
}

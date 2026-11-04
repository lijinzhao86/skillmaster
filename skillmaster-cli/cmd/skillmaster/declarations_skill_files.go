package main

import "github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"

// 取文件的两条形态：一个文件，或整版（v44 把 download 并进 --all）。
var skillFileSpecs = []*help.Spec{
	{
		Path: "skill get", Summary: "取文件或整版到本地",
		Usage: "skmr skill get <地址>@<版本> (<相对路径> | --all) [-o <本地路径>] " +
			"[--into <目录>] [--overwrite]",
		Flags: []help.Flag{
			{Name: "-o", Value: "<本地路径>", Summary: "写到哪；省略用当前目录同名文件；- 为标准输出"},
			{Name: "--overwrite", Summary: "本地已有同名文件时覆盖：默认拒绝"},
			{Name: "--all", Summary: "取整版到一个目录，而不是一个文件"},
			{Name: "--into", Value: "<目录>", Summary: "整版写到哪个目录（只与 --all 同用）"},
		},
		Args: []help.Arg{
			{Name: "<地址>@<版本>", Key: "address", Required: true},
			{Name: "<相对路径>", Key: "path", Required: false},
		},
		Output: "单文件：写到 -o 指定的路径（省略时是当前目录的 basename），成功信息与 sha256 打在 stderr；" +
			"先写临时文件、成功后再改名，中断不会留下半份。整版（--all）：写进一个不存在或空的目录，" +
			"连同 .skillmaster/metadata.json 旁车；目录非空时拒绝。整版把包读进内存，上限约 17 MiB；" +
			"更大的版本用单文件形态逐个取。任意类型；只想在终端看一眼用 skill cat。\n" +
			versionRuleLine,
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址或这一版找不到", Next: "用 skmr version list 核对"},
			{Code: "insufficient_permission", Summary: "这一档读不了这个文件", Next: "请所有者给你 browse 及以上"},
			{Code: "usage", Summary: "整版的目标目录不为空", Next: "换一个空目录"},
		},
		Examples: []string{
			"skmr skill get dev/my-skill@a1b2c3 references/design.png",
			"skmr skill get dev/my-skill@1.2.3 --all --into ./pdf",
		},
		Parent: "skill",
		Next:   "改文件用 skmr skill write / edit；只想看一段用 skill read",
		Exits:  exitsWithCredential,
	},
}

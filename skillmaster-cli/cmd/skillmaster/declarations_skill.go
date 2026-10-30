package main

import "github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"

// skillSpecs is the `skill` subtree: 26 leaves, two nested groups, and their four verbs.
var skillSpecs = []*help.Spec{
	{
		Path: "skill invoke", Summary: "加载正文并钉住这一版",
		Usage: "skmr skill invoke <命名空间>/<名字>[@版本] [--offset <行号>]",
		Flags: []help.Flag{{Name: "--offset", Value: "<行号>", Summary: "从正文第几行开始输出（头部照常打印）"}},
		Args:  []help.Arg{{Name: "<命名空间>/<名字>[@版本]", Key: "address", Required: true}},
		Output: "先输出头部：`---` 之间的 address / version / access / permissions，作者声明了 compatibility 时多一行；草稿没有 version 行，address 带版本码。" +
			"随后是正文，作者的 frontmatter 不出现。" +
			"access 是账号在这一份上的档位，每一档包含它之下所有档；permissions 是这份凭据在这一档上能做的动作，列出的动作一定能做。" +
			"头部与正文都在 stdout（正文被截断、或 --offset 越界时，说明跟在正文之后，也在 stdout），其余提示走 stderr。" +
			"这一版同时钉在这台机器上。要这一版的原始字节（含作者 frontmatter）用 skmr skill cat。\n" +
			versionRuleLine + "\n" + autoLoginLine,
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址在服务端找不到", Next: "核对命名空间与名字"},
			{Code: "skill_offline", Summary: "这个 skill 已下线", Next: "问作者什么时候再上线"},
			{Code: "unauthenticated", Summary: "凭据不被接受", Next: credentialNext},
		},
		Examples: []string{"skmr skill invoke dev/pdf-tools", "skmr skill invoke dev/pdf-tools@1.2.3 --offset 40"},
		Parent:   "skill", Next: "skmr skill read <地址> <相对路径>", Exits: exitsWithCredential,
	},
	{
		Path: "skill files", Summary: "列出这一版的文件",
		Usage: "skmr skill files <命名空间>/<名字>[@版本]",
		Args:  []help.Arg{{Name: "<命名空间>/<名字>[@版本]", Key: "address", Required: true}},
		Output: "一行一个相对路径。正文与二进制那两行后面各有一条缩进说明，末尾还有整版的页脚（stdout）。\n" +
			versionRuleLine + "\n" + autoLoginLine,
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址在服务端找不到", Next: "核对命名空间与名字"},
		},
		Examples: []string{"skmr skill files dev/pdf-tools"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill read", Summary: "读一个文件",
		Usage: "skmr skill read <命名空间>/<名字>[@版本] <相对路径> [--offset <行号>]",
		Flags: []help.Flag{{Name: "--offset", Value: "<行号>", Summary: "从第几行开始读"}},
		Args: []help.Arg{
			{Name: "<命名空间>/<名字>[@版本]", Key: "address", Required: true},
			{Name: "<相对路径>", Key: "path", Required: true},
		},
		Output: "带行号（`1\\t…`），末尾追加一行版本页脚——两者都在 stdout，不能直接写回；要原始字节用 cat。\n" +
			versionRuleLine + "\n" +
			"没有上线版本时按最近可见的版本读，注记会写明；invoke 报已下线。\n" + autoLoginLine,
		Failures: []help.Failure{
			{Code: "not_found", Summary: "文件或地址找不到", Next: "用 skmr skill files 看清单"},
		},
		Examples: []string{"skmr skill read dev/pdf-tools SKILL.md", "skmr skill read dev/pdf-tools SKILL.md --offset 40"},
		Parent:   "skill", Next: "skmr skill files <地址>", Exits: exitsWithCredential,
	},
	{
		Path: "skill cat", Summary: "一次读多个文件",
		Usage: "skmr skill cat <命名空间>/<名字>[@版本] <相对路径>...",
		Args: []help.Arg{
			{Name: "<命名空间>/<名字>[@版本]", Key: "address", Required: true},
			{Name: "<相对路径>...", Key: "paths", Required: true},
		},
		Output: "原始字节，不带行号与页脚；多个文件直接相接、没有分隔。二进制没有保护——要读文本优先用 read。版本注记走 stderr。\n" +
			versionRuleLine + "\n" + autoLoginLine,
		Failures: []help.Failure{
			{Code: "not_found", Summary: "某个文件找不到", Next: "用 skmr skill files 看清单"},
		},
		Examples: []string{"skmr skill cat dev/pdf-tools SKILL.md README.md"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill wc", Summary: "数行、词、字节",
		Usage: "skmr skill wc <命名空间>/<名字>[@版本] [相对路径...] [-l] [-w] [-c]",
		Flags: []help.Flag{
			{Name: "-l", Summary: "只数行"},
			{Name: "-w", Summary: "只数词"},
			{Name: "-c", Summary: "只数字节"},
		},
		Args: []help.Arg{{Name: "<命名空间>/<名字>[@版本]", Key: "address", Required: true}, {Name: "[相对路径...]", Key: "paths"}},
		Output: "一个文件一行，走 stdout；不传路径就是整版合计。版本注记走 stderr。\n" +
			versionRuleLine + "\n" + autoLoginLine,
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址找不到", Next: "核对命名空间与名字"},
		},
		Examples: []string{"skmr skill wc dev/pdf-tools SKILL.md -l"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill grep", Summary: "在某一版里搜文本",
		Usage: "skmr skill grep <命名空间>/<名字>[@版本] <pattern> [-F] [-i] [-n] [-l] [--path <前缀>]",
		Flags: []help.Flag{
			{Name: "-F", Summary: "按字面匹配（线性，最省事）"},
			{Name: "-i", Summary: "忽略大小写"},
			{Name: "-n", Summary: "带上行号"},
			{Name: "-l", Summary: "只列文件名"},
			{Name: "--path", Value: "<前缀>", Summary: "只在这个前缀下搜"},
		},
		Args: []help.Arg{
			{Name: "<命名空间>/<名字>[@版本]", Key: "address", Required: true},
			{Name: "<pattern>", Key: "pattern", Required: true},
		},
		Output: "一个命中一行，走 stdout；没有命中时退出 1 并说明。服务端用 RE2 逐行扫，只回命中行。版本注记走 stderr。\n" +
			"能搜读得到的版本：线上 / 历史要 browse，草稿要 write，已下架 / 已丢弃要 maintain。\n" +
			versionRuleLine + "\n" + autoLoginLine,
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址找不到", Next: "核对命名空间与名字"},
			{Code: "unauthenticated", Summary: "凭据不被接受", Next: credentialNext},
			{Code: "bad_pattern", Summary: "服务端不支持这个正则", Next: "去掉反向引用与 lookaround，或改用 -F"},
			{Code: "too_expensive", Summary: "这个正则太费时", Next: "收窄 pattern，或改用 -F"},
		},
		Examples: []string{"skmr skill grep dev/pdf-tools TODO --path src/"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill check", Summary: "查这一版的相对引用",
		Usage: "skmr skill check <命名空间>/<名字>[@版本]",
		Args:  []help.Arg{{Name: "<命名空间>/<名字>[@版本]", Key: "address", Required: true}},
		Output: "每个断链一行：`路径:行号: 目标 不在这一版里`，走 stdout；全部成立时不打印任何东西。退出 1 表示有断链。\n" +
			"算引用的有三处：markdown 链接、反引号里的路径、`skill read/cat` 的路径；只看相对路径，不查语义。不查 frontmatter 与根 SKILL.md 的存在性——写入时服务端已经校验过。\n" +
			versionRuleLine + "\n" + autoLoginLine,
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址或这一版找不到", Next: "用 skmr version list 核对"},
			{Code: "invalid_version_state", Summary: "这一版不能导出（已丢弃）", Next: "用 skmr version list 换一版"},
			{Code: "insufficient_permission", Summary: "需要 browse 档", Next: "请所有者给你 browse 及以上"},
		},
		Examples: []string{"skmr skill check dev/pdf-tools", "skmr skill check dev/pdf-tools@a1b2c3"},
		Parent:   "skill", Next: "断链：skmr skill edit <地址>@<草稿码|new> <路径> --old <目标> --new <新>（@new 再加 --name、--from）", Exits: exitsWithCredential,
	},
	{
		Path: "skill write", Summary: "写一个文件",
		Usage: "skmr skill write <命名空间>/<名字>@<版本码|new> <相对路径>（内容从标准输入读）",
		Flags: []help.Flag{
			{Name: "--from", Value: "<版本>", Summary: "第一次写入时从哪一版起稿"},
			{Name: "--name", Value: "<版本名>", Summary: "第一次写入必填：这一版的名字"},
			{Name: "--note", Value: "<说明>", Summary: "第一次写入时的版本说明"},
		},
		Args: []help.Arg{
			{Name: "<命名空间>/<名字>@<版本码|new>", Key: "address", Required: true},
			{Name: "<相对路径>", Key: "path", Required: true},
		},
		Output: "成功信息与草稿码打在 stderr，stdout 为空。@new 成功后给出下一条命令。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "草稿或文件找不到", Next: "核对版本码与路径"},
			{Code: "invalid_request", Summary: "内容不合格", Next: "看提示改内容"},
		},
		Examples: []string{"echo hi | skmr skill write dev/my-skill@a1b2c3 SKILL.md",
			"skmr skill write dev/my-skill@new SKILL.md --name 首版 --from 1.2.0"},
		Parent: "skill", Next: "skmr skill check <地址>@<草稿码>；整份要换就再 write 一次", Exits: exitsWithCredential,
	},
	{
		Path: "skill edit", Summary: "替换一处文本",
		Usage: "skmr skill edit <命名空间>/<名字>@<版本码|new> <相对路径> (--old <文本> | --old-file <文件>) " +
			"[--new <文本> | --new-file <文件>] [--replace-all]",
		Flags: []help.Flag{
			{Name: "--old", Value: "<文本>", Summary: "要被替换的文本"},
			{Name: "--old-file", Value: "<文件>", Summary: "从文件读要被替换的文本"},
			{Name: "--new", Value: "<文本>", Summary: "替换成什么"},
			{Name: "--new-file", Value: "<文件>", Summary: "从文件读替换成的文本"},
			{Name: "--replace-all", Aliases: []string{"--all"}, Summary: "替换所有出现"},
			{Name: "--from", Value: "<版本>", Summary: "第一次写入时从哪一版起稿"},
			{Name: "--name", Value: "<版本名>", Summary: "第一次写入必填：这一版的名字"},
			{Name: "--note", Value: "<说明>", Summary: "第一次写入时的版本说明"},
		},
		Args: []help.Arg{
			{Name: "<命名空间>/<名字>@<版本码|new>", Key: "address", Required: true},
			{Name: "<相对路径>", Key: "path", Required: true},
		},
		Output: "成功时 stderr 依次打：已修改那一行；改动的 hunk（`@@ -a,b +c,d @@`、`-`/`+` 行与 3 行上下文，最多 60 行）；" +
			"这一版现在的草稿码。stdout 为空。行尾符不参与匹配（CRLF 文件用 LF 的 --old 也能改），" +
			"替换文本按文件本身的风格写回。匹配不到、或匹配多处却没给 --replace-all 时被拒绝（退出 1），" +
			"并指出是带着 read 的行号、只差空白，还是最接近的哪一行；--old 与 --new 相同、或改完与原文一样时" +
			"同样拒绝（退出 1）。服务端归一之后与已存内容相同时只回一句「没有改动」，不打 hunk。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "找不到要替换的文本", Next: "先 skmr skill cat <地址> <路径> 看原文"},
			{Code: "digest_mismatch", Summary: "这一版刚变过", Next: "重读再改"},
		},
		Examples: []string{"skmr skill edit dev/my-skill@a1b2c3 SKILL.md --old 旧 --new 新",
			"skmr skill edit dev/my-skill@a1b2c3 SKILL.md --old-file /tmp/old.md --new-file /tmp/new.md"},
		Parent: "skill", Next: "skmr version diff <地址>@<草稿码>，再 skmr skill check <地址>@<草稿码>", Exits: exitsWithCredential,
	},
	{
		Path: "skill rm", Summary: "删掉草稿里的文件",
		Usage: "skmr skill rm <命名空间>/<名字>@<版本码|new> <相对路径>...",
		Flags: []help.Flag{
			{Name: "--from", Value: "<版本>", Summary: "第一次写入时从哪一版起稿"},
			{Name: "--name", Value: "<版本名>", Summary: "第一次写入必填：这一版的名字"},
			{Name: "--note", Value: "<说明>", Summary: "第一次写入时的版本说明"},
		},
		Args: []help.Arg{
			{Name: "<命名空间>/<名字>@<版本码|new>", Key: "address", Required: true},
			{Name: "<相对路径>...", Key: "paths", Required: true},
		},
		Output: "每个删掉的文件一行。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "文件找不到", Next: "用 skmr skill files 看清单"},
		},
		Examples: []string{"skmr skill rm dev/my-skill@a1b2c3 old.md",
			"skmr skill rm dev/my-skill@new old.md --name 首版"},
		Parent: "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill mv", Summary: "移动草稿里的文件",
		Usage: "skmr skill mv <命名空间>/<名字>@<版本码|new> <from> <to>",
		Flags: []help.Flag{
			{Name: "--from", Value: "<版本>", Summary: "第一次写入时从哪一版起稿"},
			{Name: "--name", Value: "<版本名>", Summary: "第一次写入必填：这一版的名字"},
			{Name: "--note", Value: "<说明>", Summary: "第一次写入时的版本说明"},
		},
		Args: []help.Arg{
			{Name: "<命名空间>/<名字>@<版本码|new>", Key: "address", Required: true},
			{Name: "<from>", Key: "from", Required: true},
			{Name: "<to>", Key: "to", Required: true},
		},
		Output: "报告移动结果。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "源文件找不到", Next: "用 skmr skill files 看清单"},
		},
		Examples: []string{"skmr skill mv dev/my-skill@a1b2c3 a.md b.md",
			"skmr skill mv dev/my-skill@new a.md b.md --name 首版"},
		Parent: "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill cp", Summary: "复制草稿里的文件",
		Usage: "skmr skill cp <命名空间>/<名字>@<版本码> <from> <to>",
		Args: []help.Arg{
			{Name: "<命名空间>/<名字>@<版本码>", Key: "address", Required: true},
			{Name: "<from>", Key: "from", Required: true},
			{Name: "<to>", Key: "to", Required: true},
		},
		Output: "报告复制结果。第一次改动不能是复制：先建草稿再 cp。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "源文件找不到", Next: "用 skmr skill files 看清单"},
		},
		Examples: []string{"skmr skill cp dev/my-skill@a1b2c3 a.md b.md"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill download", Summary: "整版取成一个目录",
		Usage:  "skmr skill download <命名空间>/<名字>[@版本 | @版本码] [--into <目录>]",
		Flags:  []help.Flag{{Name: "--into", Value: "<目录>", Summary: "写到哪个目录"}},
		Args:   []help.Arg{{Name: "<命名空间>/<名字>[@版本 | @版本码]", Key: "address", Required: true}},
		Output: "把这一版写到目标目录并打印落点。这是一次性快照：往返一次摘要相同，适合分发；要改这一版用 skill write / edit，要导入一个本地目录用 skill submit <目录> --name <名>。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址找不到", Next: "核对命名空间与名字"},
			{Code: "usage", Summary: "目标目录不为空", Next: "换一个空目录或删掉目标"},
		},
		Examples: []string{"skmr skill download dev/pdf-tools", "skmr skill download dev/pdf-tools --into ./pdf"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill versions", Summary: "能调用哪些版本",
		Usage:  "skmr skill versions <命名空间>/<名字>",
		Args:   []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		Output: "一版一个地址行，后面跟 0–2 行缩进（版本名、状态）；只含已上线的，走 stdout。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址找不到", Next: "核对命名空间与名字"},
		},
		Examples: []string{"skmr skill versions dev/pdf-tools"},
		Parent:   "skill", Next: "skmr skill invoke <地址>@<版本>", Exits: exitsWithCredential,
	},
	{
		Path: "skill submit", Summary: "提交一版成草稿",
		Usage: "skmr skill submit [名字 | 目录] [--name <版本名称>] [--note <说明>] [--release major|minor|patch] [--to <命名空间>/<名字>]",
		Flags: []help.Flag{
			{Name: "--name", Value: "<版本名称>", Summary: "这一版的名称"},
			{Name: "--note", Value: "<说明>", Summary: "一句说明"},
			{Name: "--release", Value: "major|minor|patch", Summary: "这次改动多大；不是版本号，号在上线时算"},
			{Name: "--to", Value: "<命名空间>/<名字>", Summary: "提到别人的 skill 上"},
		},
		Args:   []help.Arg{{Name: "[名字 | 目录]", Key: "source"}},
		Output: "落成草稿并打印这一版的审批地址——这一版草稿的单版页，打开「操作」那一栏（`?tab=versions&version=<草稿码>&sub=actions`），与 `version open --sub actions` 同一个地址；内容是重复的、这一版已经在线上时，打的是技能页。裸名字按 SKILLMASTER_SKILLS_DIR 解析，不是当前目录；带 / 或以 ./ ~ 开头才当路径。不能上线，上线只在网页上由人点。",
		Failures: []help.Failure{
			{Code: "content_already_exists", Summary: "内容与线上一样", Next: "改点东西再提交"},
			{Code: "insufficient_permission", Summary: "没有写入档", Next: "请对方给你写入档"},
			{Code: "skill_deleted", Summary: "名字属于一个已删除的 skill", Next: "换一个名字，或请所有者恢复"},
			{Code: "draft_name_required", Summary: "这一版还没有名称", Next: "加 --name"},
			{Code: "draft_name_too_long", Summary: "名称太长", Next: "缩短 --name"},
			{Code: "note_too_long", Summary: "说明太长", Next: "缩短 --note"},
			{Code: "internal_error", Summary: "服务端内部错误", Next: "稍后重试；一直出现就报告"},
		},
		Examples: []string{"skmr skill submit . --name 首版", "skmr skill submit ./my-skill --name 从本机导入"},
		Parent:   "skill", Next: "在浏览器里点上线：skmr version open <地址>@<草稿码> --sub actions", Exits: exitsWithCredential,
	},
	{
		Path: "skill share", Summary: "共享给某个人",
		Usage: "skmr skill share <命名空间>/<名字> --to <用户名> [--role invoke|browse|write|maintain|admin]",
		Flags: []help.Flag{
			{Name: "--to", Value: "<用户名>", Summary: "共享给谁"},
			{Name: "--role", Value: "invoke|browse|write|maintain|admin", Summary: "给哪一档，默认 invoke"},
		},
		Args:   []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		Output: "报告这次的档位。五档：invoke 调用、browse 看文件、write 提版本、maintain 上线、admin 连可见性与协作者一起管。默认 invoke——说「让他管理」要给 admin。改已有协作者：skmr skill open <地址> --tab settings --sub collaborators。",
		Failures: []help.Failure{
			{Code: "insufficient_permission", Summary: "你不是管理员", Next: "请所有者来共享"},
			{Code: "not_found", Summary: "用户或 skill 找不到", Next: "核对用户名与地址"},
		},
		Examples: []string{"skmr skill share dev/pdf-tools --to alice --role write"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill create", Summary: "新建一个 skill",
		Usage: "skmr skill create <名字> --description <描述> [--name <版本名称>] [--json]",
		Flags: []help.Flag{
			{Name: "--description", Value: "<描述>", Summary: "这个 skill 做什么"},
			{Name: "--name", Value: "<版本名称>", Summary: "第一版的名称"},
		},
		Args:       []help.Arg{{Name: "<名字>", Key: "name", Required: true}},
		Output:     "建出 skill 与第一版草稿，打印地址与版本码。接着用 skmr skill write <地址>@<版本码> <路径> 写内容，或把整个目录用 skmr skill submit <目录> --name <名> 提上去。",
		JSONFields: []string{"namespace", "name", "version_code", "version_name", "state"},
		Failures: []help.Failure{
			{Code: "skill_already_exists", Summary: "这个名字已被占用", Next: "换一个名字"},
		},
		Examples: []string{"skmr skill create pdf-tools --description 处理 PDF 的工具"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill shares", Summary: "看共享给了谁",
		Usage: "skmr skill shares <命名空间>/<名字> [--query <文字>] [--page <页码>] [--json]",
		Flags: []help.Flag{
			{Name: "--query", Value: "<文字>", Summary: "按用户名筛"},
			{Name: "--page", Value: "<页码>", Summary: "翻页"},
		},
		Args:       []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		Output:     "一行一个被共享的人与档位（档位含义同 share），走 stdout。",
		JSONFields: []string{"grants", "total", "page", "page_size"},
		Failures: []help.Failure{
			{Code: "insufficient_permission", Summary: "你不是管理员", Next: "请所有者来看"},
		},
		Examples: []string{"skmr skill shares dev/pdf-tools", "skmr skill shares dev/pdf-tools --query alice"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill unshare", Summary: "收回某人的共享",
		Usage:      "skmr skill unshare <命名空间>/<名字> --user <用户名> [--json]",
		Flags:      []help.Flag{{Name: "--user", Value: "<用户名>", Summary: "收回谁"}},
		Args:       []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		Output:     "报告收回了谁的哪一档。",
		JSONFields: []string{"handle", "revoked"},
		Failures: []help.Failure{
			{Code: "not_found", Summary: "这个人没有被共享过", Next: "用 skmr skill shares 核对"},
		},
		Examples: []string{"skmr skill unshare dev/pdf-tools --user alice"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill request", Summary: "申请更高一档",
		Usage: "skmr skill request <命名空间>/<名字> --role <档位> | --status | --cancel [--json]",
		Flags: []help.Flag{
			{Name: "--role", Value: "invoke|browse|write|maintain|admin", Summary: "申请哪一档"},
			{Name: "--status", Summary: "看自己的申请状态"},
			{Name: "--cancel", Summary: "撤回申请"},
		},
		Args:       []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		Output:     "按子动作打印申请 / 状态 / 撤回的结果（字段按子动作不同）。档位含义同 skill share。",
		JSONFields: []string{"rung", "pending_rung", "cancelled"},
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址找不到", Next: "核对命名空间与名字"},
		},
		Examples: []string{"skmr skill request dev/pdf-tools --role write"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill open", Summary: "打开网页上的某一页",
		Usage: "skmr skill open <命名空间>/<名字> [--tab info|files|versions|settings] [--sub general|collaborators|details] [--print] [--json]",
		Flags: []help.Flag{
			{Name: "--tab", Value: "info|files|versions|settings", Summary: "打开哪一页"},
			{Name: "--sub", Value: "general|collaborators|details", Summary: "设置页的哪一栏"},
			{Name: "--print", Summary: "只打印地址，不打开"},
		},
		Args:       []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		Output:     "打开浏览器，或只打印地址。",
		JSONFields: []string{"url", "opened"},
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址找不到", Next: "核对命名空间与名字"},
		},
		Examples: []string{"skmr skill open dev/pdf-tools --tab settings --print"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill show", Summary: "读完整信息",
		Usage:  "skmr skill show <命名空间>/<名字> [--no-login]",
		Flags:  []help.Flag{{Name: "--no-login", Summary: "没有凭据时立即失败"}},
		Args:   []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		Output: "描述、场景、环境、版本与三组词，走 stdout。",
		Failures: []help.Failure{
			{Code: "skill_not_found", Summary: "这个 skill 不在你的库里", Next: "用 skmr skills search 找名字"},
			{Code: "not_found", Summary: "地址找不到", Next: "核对命名空间与名字"},
			{Code: "unauthenticated", Summary: "凭据不被接受", Next: credentialNext},
		},
		Examples: []string{"skmr skill show dev/pdf-tools"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill tags", Summary: "读三组词",
		Usage:  "skmr skill tags <命名空间>/<名字> [--no-login]",
		Flags:  []help.Flag{{Name: "--no-login", Summary: "没有凭据时立即失败"}},
		Args:   []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		Output: "分类、公开标签、我的标签三组，走 stdout。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址找不到", Next: "核对命名空间与名字"},
		},
		Examples: []string{"skmr skill tags dev/pdf-tools"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill join", Summary: "收进我的库",
		Usage:  "skmr skill join <命名空间>/<名字> --yes",
		Flags:  []help.Flag{{Name: "--yes", Summary: "确认加入"}},
		Args:   []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		Output: "报告已收进库。",
		Failures: []help.Failure{
			{Code: "skill_not_joinable", Summary: "这个 skill 现在加入不了", Next: "用 skmr skills list 看它现在是什么"},
		},
		Examples: []string{"skmr skill join dev/pdf-tools --yes"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill leave", Summary: "把加入的移出库",
		Usage:  "skmr skill leave <命名空间>/<名字> --yes",
		Flags:  []help.Flag{{Name: "--yes", Summary: "确认移出"}},
		Args:   []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		Output: "报告已移出；我的标签保留。",
		Failures: []help.Failure{
			{Code: "library_reference_missing", Summary: "库里没有这个引用", Next: "用 skmr skills list 核对"},
		},
		Examples: []string{"skmr skill leave dev/pdf-tools --yes"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill restore", Summary: "恢复已删除的 skill",
		Usage:  "skmr skill restore <命名空间>/<名字>",
		Args:   []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		Output: "报告已恢复。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "没有这个已删除的 skill", Next: "用 skills list --show deleted 核对"},
			{Code: "insufficient_permission", Summary: "你不是所有者", Next: "请所有者来恢复"},
		},
		Examples: []string{"skmr skill restore dev/pdf-tools"},
		Parent:   "skill", Exits: exitsWithCredential,
	},
	{
		Path: "skill tag", Summary: "公开标签与我的标签",
		Usage:    "skmr skill tag <子命令> [参数]",
		Children: []string{"add", "remove"}, PureGroup: true, Parent: "skill",
	},
	{
		Path: "skill tag add", Summary: "贴一个标签",
		Usage: "skmr skill tag add <命名空间>/<名字> <名字> [--public]",
		Flags: []help.Flag{{Name: "--public", Summary: "贴公开标签而不是我的"}},
		Args: []help.Arg{
			{Name: "<命名空间>/<名字>", Key: "address", Required: true},
			{Name: "<名字>", Key: "tag", Required: true},
		},
		Output: "报告贴上了哪个标签。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址找不到", Next: "核对命名空间与名字"},
		},
		Examples: []string{"skmr skill tag add dev/pdf-tools 文档", "skmr skill tag add dev/pdf-tools pdf --public"},
		Parent:   "skill tag", Exits: exitsWithCredential,
	},
	{
		Path: "skill tag remove", Summary: "摘一个标签",
		Usage: "skmr skill tag remove <命名空间>/<名字> <名字> [--public]",
		Flags: []help.Flag{{Name: "--public", Summary: "摘公开标签而不是我的"}},
		Args: []help.Arg{
			{Name: "<命名空间>/<名字>", Key: "address", Required: true},
			{Name: "<名字>", Key: "tag", Required: true},
		},
		Output: "报告摘掉了哪个标签。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "这个 skill 上没有这个标签", Next: "用 skmr skill tags 看现有标签"},
		},
		Examples: []string{"skmr skill tag remove dev/pdf-tools 文档"},
		Parent:   "skill tag", Exits: exitsWithCredential,
	},
	{
		Path: "skill category", Summary: "公开分类",
		Usage:    "skmr skill category <子命令> [参数]",
		Children: []string{"set", "clear"}, PureGroup: true, Parent: "skill",
	},
	{
		Path: "skill category set", Summary: "设置公开分类",
		Usage: "skmr skill category set <命名空间>/<名字> <分类>",
		Args: []help.Arg{
			{Name: "<命名空间>/<名字>", Key: "address", Required: true},
			{Name: "<分类>", Key: "category", Required: true},
		},
		Output: "报告设成了哪个分类。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址找不到", Next: "核对命名空间与名字"},
		},
		Examples: []string{"skmr skill category set dev/pdf-tools 文档处理"},
		Parent:   "skill category", Exits: exitsWithCredential,
	},
	{
		Path: "skill category clear", Summary: "清除公开分类",
		Usage:  "skmr skill category clear <命名空间>/<名字>",
		Args:   []help.Arg{{Name: "<命名空间>/<名字>", Key: "address", Required: true}},
		Output: "报告已清除。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "地址找不到", Next: "核对命名空间与名字"},
		},
		Examples: []string{"skmr skill category clear dev/pdf-tools"},
		Parent:   "skill category", Exits: exitsWithCredential,
	},
}

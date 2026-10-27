package main

import (
	"sort"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// commandHelp is the whole command table: 64 command paths, seven pure groups, the top level, and
// three topics. The declarations live here, next to the handlers, because the help and the command have one
// home (v16 做 #7). One declaration per command; the command-tree test proves the set matches the
// dispatch.
type declarationsCatalog struct {
	specs  []*help.Spec
	topics map[string]help.Topic
}

func (c declarationsCatalog) Specs() []*help.Spec { return c.specs }

func (c declarationsCatalog) Topic(name string) (help.Topic, bool) {
	topic, ok := c.topics[name]
	return topic, ok
}

// Topics lists the topics by name, so the top-level help and an unknown-topic error both name the
// real set instead of a copy of it.
func (c declarationsCatalog) Topics() []help.Topic {
	names := make([]string, 0, len(c.topics))
	for name := range c.topics {
		names = append(names, name)
	}
	sort.Strings(names)
	topics := make([]help.Topic, 0, len(names))
	for _, name := range names {
		topics = append(topics, c.topics[name])
	}
	return topics
}

// exitsWithCredential is what a command that talks to the server can return: success, an ordinary
// failure, a usage error, and credential-unavailable.
var exitsWithCredential = []int{0, 1, 2, 4}

// exitsLocal is for a command that touches only this machine.
var exitsLocal = []int{0, 1, 2}

// exitsRefusal is for a command whose only job is to say where the action lives.
var exitsRefusal = []int{0, 1}

// credentialNext is the one next step for every 401-class failure: it is written once because it
// has to read the same on six declarations, and six copies drift (v22 S3).
const credentialNext = "skillmaster login，或到控制台换一把 API key"

// keyLine is the no-browser credential path, worded once. It appears on the pages a person lands on
// when login is not an option (login, startup, help env), because a page that only says "see help
// auth" costs the reader a second command (v22 S1/A1).
const keyLine = "无浏览器（容器 / CI / sandbox）：控制台 /account/keys 签一把 skm_…，" +
	"放进 SKILLMASTER_API_KEY，用 skillmaster auth status 验证。"

// autoLoginLine is what a read command does with no credential. None of these six take `--no-login`,
// so the sentence has to say what happens instead of naming a flag they do not have (v22 A4).
const autoLoginLine = "没有凭据时会打开浏览器登录；无人值守请先设 SKILLMASTER_API_KEY（help auth）。"

// versionRuleLine is the one thing a bare address does not mean: it is the version pinned by this
// machine's last `invoke`, not whatever is live now. One string, four pages — the rule is the same
// fact wherever a read takes an address (v22 B4).
const versionRuleLine = "裸地址用上次 invoke 钉住的版本，不是当前线上；要别的版本就写 @<版本>。"

var commandHelp = declarationsCatalog{
	topics: map[string]help.Topic{
		"env": {
			Name:  "env",
			Label: "环境变量",
			Body: `环境变量：
  SKILLMASTER_SERVER       服务端地址（本地默认 localhost:8080）
  SKILLMASTER_API_KEY      API key；优先级最高，不写本机。别 echo 它
  SKILLMASTER_SKILLS_DIR   submit 解析裸名字的目录（默认 ~/.claude/skills）
  SKILLMASTER_WEB_URL      审批页地址（默认同服务端）
  SKILLMASTER_AUTO_UPDATE  设 0 关掉自动更新（update 照常可用）
无浏览器：控制台 /account/keys 签 skm_… 填进 SKILLMASTER_API_KEY，auth status 验证。
`,
		},
		"auth": {
			Name:  "auth",
			Label: "凭据",
			Body: `两种凭据：
  浏览器：skillmaster login（有图形界面时）
  API key：控制台 /account/keys 签一把 skm_…，放进 SKILLMASTER_API_KEY。
           容器 / CI / sandbox 用它——不要浏览器，也不写本机。
验证：skillmaster auth status
续期 / 撤销：登录的去控制台「登录设备」，key 的去「API 密钥」。logout 只清本机。
`,
		},
		"workflow": {
			Name:  "workflow",
			Label: "主流程",
			Body: `  消费：skills search → skill invoke <地址> → skill read / files
        裸地址=上次 invoke 钉的版；要线上写 @<版本>
  本地：skill submit <目录> --name <名> → skills pending
        → version open <地址>@<草稿码> --sub actions
  在线：skill read / cat → skill write <地址>@new <路径> --name <名> --from <版本>
        → skill edit → version diff → skill check（断链先修）
        → 网页上线：version open <地址>@<草稿码> --sub actions
`,
		},
	},
	specs: append(append(topSpecs, skillSpecs...), otherSpecs...),
}

// topSpecs is the root, the six pure groups, the three top-level commands, and auth status.
var topSpecs = []*help.Spec{
	{
		Path:      "",
		Summary:   "SkillMaster 命令行",
		Children:  []string{"skill", "skills", "version", "auth", "login", "logout", "startup", "prompt", "update"},
		PureGroup: true,
	},
	{
		Path:      "skill",
		Summary:   "单个 skill 的动词",
		Children:  []string{"invoke", "files", "read", "cat", "wc", "grep", "check", "write", "edit", "rm", "mv", "cp", "download", "versions", "submit", "share", "create", "shares", "unshare", "request", "open", "show", "tags", "tag", "category", "join", "leave", "restore"},
		PureGroup: true,
	},
	{
		Path:      "skills",
		Summary:   "整个库的动词",
		Children:  []string{"list", "search", "pending", "categories", "public-tags", "my-tags"},
		PureGroup: true,
	},
	{
		Path:      "version",
		Summary:   "作者面的版本管理",
		Children:  []string{"list", "show", "diff", "rename", "note", "release-type", "discard", "open", "publish", "rollback", "revoke", "restore", "offline", "online", "delete"},
		PureGroup: true,
	},
	{
		Path:      "auth",
		Summary:   "这份凭据的状态",
		Children:  []string{"status"},
		PureGroup: true,
	},
	{
		Path:    "login",
		Summary: "在浏览器里登录这台机器",
		Usage:   "skillmaster login",
		Output: "登录成功后在 stdout 打印这份凭据是谁的、到什么时候；诊断走 stderr。\n" +
			keyLine,
		Failures: []help.Failure{
			{Code: "network", Summary: "打开或连不上服务端", Next: "检查网络与地址后重试"},
			{Code: "login_failed", Summary: "浏览器里没有同意", Next: "重新运行 skillmaster login"},
			{Code: "too_many_requests", Summary: "登录试得太频繁", Next: "等一会儿再试"},
		},
		Examples: []string{"skillmaster login"},
		Parent:   "",
		Exits:    exitsLocal,
	},
	{
		Path:    "logout",
		Summary: "清除本机凭据",
		Usage:   "skillmaster logout",
		Output:  "清掉本机副本，并告诉你服务端那份该去控制台哪里结束。",
		Failures: []help.Failure{
			{Code: "network", Summary: "读不出本机凭据", Next: "检查钥匙串后重试"},
		},
		Examples: []string{"skillmaster logout"},
		Parent:   "",
		Exits:    []int{0, 1},
	},
	{
		Path:    "startup",
		Summary: "开始入门旅程",
		Usage:   "skillmaster startup [--plan [--agent <名字>] [--file <路径>]]",
		Flags: []help.Flag{
			{Name: "--plan", Summary: "只读：按本机与平台状态打印安装清单"},
			{Name: "--agent", Value: "<名字>", Summary: "当前 AI 的名字，只用于清单与记录"},
			{Name: "--file", Value: "<路径>", Summary: "当前 AI 的用户级说明文件，只用于清单"},
		},
		Output: "不带 --plan：先确保这台机器登录（没有凭据时打开浏览器），再加载官方入门技能 " +
			"skillmaster/startup、钉住这一版，正文原样打印到 stdout；提示走 stderr。\n" +
			"带 --plan：不登录、不拉正文、不钉版本、不写文件，只打印七项安装清单与下一步；" +
			"传技能是可选的，不传或只传一部分都不挡后面的步骤。\n" + keyLine,
		Failures: []help.Failure{
			{Code: "network", Summary: "连不上服务端", Next: "检查网络与地址后重试"},
			{Code: "login_failed", Summary: "浏览器里没有完成登录", Next: "重新运行 skillmaster startup"},
			{Code: "not_found", Summary: "旅程技能不存在或对它不可见", Next: "去控制台确认 skillmaster/startup 还在线上"},
		},
		Examples: []string{"skillmaster startup", "skillmaster startup --plan --agent codex --file ~/.codex/AGENTS.md"},
		Parent:   "",
		Exits:    exitsWithCredential,
	},
	{
		Path:    "auth status",
		Summary: "这份凭据是什么",
		Usage:   "skillmaster auth status [<地址>]",
		Args:    []help.Arg{{Name: "[<地址>]", Key: "address"}},
		Output:  "本机存了哪种凭据、服务端认它是谁、到期时间；key 还给出续期 / 撤销地址。诊断走 stderr。",
		Failures: []help.Failure{
			{Code: "unauthenticated", Summary: "服务端不认这份凭据", Next: credentialNext},
			{Code: "token_idle", Summary: "这份凭据闲置太久", Next: credentialNext},
			{Code: "token_expired", Summary: "这份凭据已到期", Next: credentialNext},
			{Code: "token_revoked", Summary: "这份凭据已被结束", Next: credentialNext},
			{Code: "account_suspended", Summary: "账号已被停用", Next: "联系管理员"},
		},
		Examples: []string{"skillmaster auth status", "skillmaster auth status skillmaster/startup"},
		Parent:   "auth",
		Exits:    exitsWithCredential,
	},
	// The two doors added by v25. Both are local: `prompt` never talks to the server, and `update`
	// talks to the release bucket rather than to the API.
	{
		Path:      "prompt",
		Summary:   "系统提示词里的规则段",
		Children:  []string{"status", "print", "install", "update", "uninstall"},
		PureGroup: true,
	},
	{
		Path:    "update",
		Summary: "更新这个 CLI",
		Usage:   "skillmaster update [--check]",
		Flags: []help.Flag{
			{Name: "--check", Summary: "只看有没有新版，不下载"},
		},
		Output: "默认下载本通道的新版本、核对 SHA-256 并换位，下一条命令生效。\n" +
			"平时不需要它：每次命令启动时最多每 24 小时自动检查一次。",
		Failures: []help.Failure{
			{Code: "network", Summary: "连不上发布地址", Next: "检查网络后重试"},
			{Code: "not_found", Summary: "发布源上没有这个平台的包", Next: "重跑安装说明里那条命令"},
			{Code: "invalid_request", Summary: "下载的包校验不符", Next: "重跑安装说明里那条命令"},
		},
		Examples: []string{"skillmaster update --check", "skillmaster update"},
		Parent:   "",
		Exits:    exitsLocal,
	},
	{
		Path:    "prompt status",
		Summary: "规则段是不是最新的",
		Usage:   "skillmaster prompt status",
		Output: "逐个打印记录的文件、记录里的摘要、文件里现在的摘要；不一致时附逐行差异。\n" +
			"全部一致退出 0；没记录、任一文件不在、标记段不在或内容不同，退出 1。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "没有记录、文件或标记段", Next: "prompt install --file <路径>"},
		},
		Examples: []string{"skillmaster prompt status"},
		Parent:   "prompt",
		Exits:    exitsLocal,
	},
	{
		Path:    "prompt print",
		Summary: "打印二进制自带的规则段",
		Usage:   "skillmaster prompt print",
		Output:  "把这个二进制自带的那段原样写到 stdout（含两行标记），断网、没有凭据也能用。",
		Failures: []help.Failure{
			{Code: "usage", Summary: "多给了参数", Next: "去掉多余的参数"},
		},
		Examples: []string{"skillmaster prompt print"},
		Parent:   "prompt",
		Exits:    exitsLocal,
	},
	{
		Path:    "prompt install",
		Summary: "写进指令文件并记住",
		Usage:   "skillmaster prompt install --file <路径> [--agent <名字>] [--yes] [--force]",
		Flags: []help.Flag{
			{Name: "--file", Value: "<路径>", Summary: "写进哪个指令文件（必填）"},
			{Name: "--agent", Value: "<名字>", Summary: "给记录加一个标签；只是标签，不校验"},
			{Name: "--yes", Summary: "确认写入；不给它只打印计划，退出 1"},
			{Name: "--force", Summary: "越过「不在主目录下」这一条检查；仍要 --yes"},
		},
		Output: "不给 --yes 只打印五句话（我要做什么 / 只会动到什么 / 做完会怎样 / 不想做了怎么办 / 不做会怎样）并退出 1，不写文件。\n" +
			"给 --yes 才写：没有标记段就追加，已是最新就只记录，不同就替换；文件其它内容不动。\n" +
			"路径必须是文件、上级目录存在、在主目录下；落在当前项目目录时只警告，不拒绝。",
		Failures: []help.Failure{
			{Code: "usage", Summary: "没给 --file", Next: "补上 --file <路径>"},
			{Code: "invalid_request", Summary: "路径不合法、不在主目录下，或文件里有多段标记", Next: "换路径；确定要写就加 --force；多段标记只保留一段"},
			{Code: "not_found", Summary: "路径的上级目录不存在", Next: "先建好目录或换个路径"},
		},
		Examples: []string{"skillmaster prompt install --file ~/.claude/CLAUDE.md --agent claude", "skillmaster prompt install --file ~/.codex/AGENTS.md --agent codex --yes"},
		Parent:   "prompt",
		Exits:    exitsLocal,
	},
	{
		Path:    "prompt update",
		Summary: "更新文件里的规则段",
		Usage:   "skillmaster prompt update [--yes]",
		Flags: []help.Flag{
			{Name: "--yes", Summary: "确认替换已有的旧标记段"},
		},
		Output: "更新所有 prompt install 记下的文件，把标记段换成这个二进制自带的。\n" +
			"不同就逐份打印差异；没有 --yes 不落笔，退出 1。标记段以外的内容一个字节不动。",
		Failures: []help.Failure{
			{Code: "not_found", Summary: "没有记录、文件或标记段", Next: "prompt install --file <路径>"},
			{Code: "invalid_request", Summary: "文件里有多段标记", Next: "只保留一段再试"},
		},
		Examples: []string{"skillmaster prompt update", "skillmaster prompt update --yes"},
		Parent:   "prompt",
		Exits:    exitsLocal,
	},
	{
		Path:    "prompt uninstall",
		Summary: "去掉规则段",
		Usage:   "skillmaster prompt uninstall (--file <路径> | --all) [--yes]",
		Flags: []help.Flag{
			{Name: "--file", Value: "<路径>", Summary: "去掉这个文件里的标记段"},
			{Name: "--all", Summary: "去掉所有记录里的标记段与记录"},
			{Name: "--yes", Summary: "确认去掉；不给它只打印计划，退出 1"},
		},
		Output: "去掉两个标记之间的整段；文件本身保留，段外内容一个字节不动。\n" +
			"不给 --yes 只按五句话说明会去掉哪个 AI 的说明（--all 时列出记录里的 AI），退出 1。文件不存在或没有标记段时如实报告。",
		Failures: []help.Failure{
			{Code: "usage", Summary: "既没给 --file 也没给 --all", Next: "补上 --file <路径> 或 --all"},
			{Code: "not_found", Summary: "文件不存在或没有标记段", Next: "确认路径；没有标记段就没有可去掉的"},
			{Code: "invalid_request", Summary: "文件里有多段标记", Next: "只保留一段再试"},
		},
		Examples: []string{"skillmaster prompt uninstall --file ~/.codex/AGENTS.md", "skillmaster prompt uninstall --all --yes"},
		Parent:   "prompt",
		Exits:    exitsLocal,
	},
}

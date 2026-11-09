package main

import (
	"sort"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// commandHelp is the whole command table: 76 command paths, nine pure groups, the top level, and
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

// keyLine is the no-browser credential path, worded once. It appears on the pages a person lands on
// when login is not an option (login, startup, help env), because a page that only says "see help
// auth" costs the reader a second command (v22 S1/A1).
const keyLine = "无浏览器（容器 / CI / sandbox）：控制台 /account/keys 签一把 skm_…，" +
	"放进 SKILLMASTER_API_KEY，用 skmr auth status 验证。"

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
  浏览器：skmr login（有图形界面时）
  API key：控制台 /account/keys 签一把 skm_…，放进 SKILLMASTER_API_KEY。
           容器 / CI / sandbox 用它——不要浏览器，也不写本机。
验证：skmr auth status
续期 / 撤销：登录的去控制台「登录设备」，key 的去「API 密钥」。
logout 会清本机；CLI 登录还会结束服务端那次登录，key 只清本机。
`,
		},
		"workflow": {
			Name:  "workflow",
			Label: "主流程",
			Body: `  消费：skills search → skill invoke <地址> → 照正文做；子文件用 skill ls / read
        裸地址=上次 invoke 钉的版；别的版本写 @<版本>
  运行：正文让你跑脚本 → run <地址> -- python3 x.py [参数]
        在 scripts/ 下执行；首次加 --yes；绑定的凭据会注入
  创作：skill create <名> --description <描述>
        或 draft create <地址> --name <名>
        → skill read <地址>@<版本码> <路径>（覆盖前先读）
        → skill write / edit → version diff → skill check
        → 网页上线：version open <地址>@<版本码> --sub actions
  整目录：skill submit <目录> --name <名> → skills pending → 网页上线
  协作：skill share → 对方 notifications list → notifications accept / decline；skill request → notifications approve / deny
  凭据：secret set --skill <地址> --env <NAME>（值走 stdin）→ run 注入
  二进制：skill write <地址>@<版本码> <路径> --from <本地文件>
`,
		},
	},
	specs: append(append(append(append(append(append(append(append(topSpecs, skillSpecs...),
		skillFileSpecs...), skillRoleSpecs...), skillPageSpecs...), otherSpecs...),
		draftSpecs...), notificationSpecs...), secretSpecs...),
}

// topSpecs is the root, the six pure groups, the three top-level commands, and auth status.
var topSpecs = []*help.Spec{
	{
		Path:      "",
		Summary:   "SkillMaster 命令行",
		Children:  []string{"skill", "skills", "version", "draft", "notifications", "secret", "auth", "login", "logout", "startup", "prompt", "update", "run", "doctor"},
		PureGroup: true,
	},
	{
		Path:      "skill",
		Summary:   "单个 skill 的动词",
		Children:  []string{"invoke", "ls", "find", "stat", "head", "tail", "read", "cat", "wc", "grep", "check", "write", "get", "edit", "rm", "mv", "cp", "versions", "submit", "share", "create", "shares", "role", "unshare", "request", "open", "show", "license", "tags", "tag", "category", "join", "leave", "restore", "delete", "offline", "online", "visibility"},
		PureGroup: true,
	},
	{
		Path:      "skills",
		Summary:   "整个库的动词",
		Children:  []string{"list", "search", "pending", "publish", "categories", "public-tags", "my-tags"},
		PureGroup: true,
	},
	{
		Path:      "version",
		Summary:   "作者面的版本管理",
		Children:  []string{"list", "show", "diff", "rename", "note", "release-type", "discard", "open", "publish", "rollback", "revoke", "restore"},
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
		Usage:   "skmr login",
		Output: "登录成功后在 stdout 打印这份凭据是谁的、到什么时候；诊断走 stderr。\n" +
			keyLine,
		Failures: []help.Failure{
			{Code: "network", Summary: "打开或连不上服务端", Next: "检查网络与地址后重试"},
			{Code: "login_failed", Summary: "浏览器里没有同意", Next: "重新运行 skmr login"},
			{Code: "too_many_requests", Summary: "登录试得太频繁", Next: "等一会儿再试"},
		},
		Examples: []string{"skmr login"},
		Parent:   "",
		Exits:    exitsLocal,
	},
	{
		Path:    "logout",
		Summary: "退出本机的凭据",
		Usage:   "skmr logout",
		Output: "清掉本机副本；CLI 登录还会结束服务端那次登录，" +
			"API key 只清本机并告诉你服务端那把去哪里撤销。",
		Failures: []help.Failure{
			{Code: "network", Summary: "读不出本机凭据", Next: "检查钥匙串后重试"},
		},
		Examples: []string{"skmr logout"},
		Parent:   "",
		Exits:    []int{0, 1},
	},
	{
		Path:    "startup",
		Summary: "开始入门旅程",
		Usage:   "skmr startup",
		Output: "先确保这台机器登录：没有凭据、又能打开浏览器时，它先带你登录；打不开浏览器就停下，" +
			"并说明改用 skmr login 或 API key 怎么继续。登录后加载官方入门技能 " +
			"skillmaster/skillmaster-startup、钉住这一版，正文原样打印到 stdout；提示走 stderr。\n" + keyLine,
		Failures: []help.Failure{
			{Code: "usage", Summary: "多给了参数", Next: "去掉多余的参数：startup 不带参数"},
			{Code: "network", Summary: "连不上服务端", Next: "检查网络与地址后重试"},
			{Code: "login_failed", Summary: "浏览器里没有完成登录", Next: "重新运行 skmr startup"},
			{Code: "not_found", Summary: "旅程技能不存在或对它不可见", Next: "去控制台确认它还在线上"},
		},
		Examples: []string{"skmr startup"},
		Parent:   "",
		Exits:    exitsWithCredential,
	},
	{
		Path:    "run",
		Summary: "执行 skill 命令",
		Usage: "skmr run <地址>[@版本] [--cwd <目录>] [--cache-in <目录>] [--no-cache] [--yes] [--json] " +
			"-- <命令...>",
		Flags: []help.Flag{
			{Name: "--cwd", Value: "<目录>", Summary: "在哪个目录执行；默认 scripts/"},
			{Name: "--cache-in", Value: "<目录>", Summary: "取回的版本放哪；默认在临时目录里"},
			{Name: "--no-cache", Summary: "不命中缓存，取到一个新的临时目录再跑"},
			{Name: "--yes", Aliases: []string{"-y"}, Summary: "跳过首次执行的确认，并记下来"},
			{Name: "--json", Summary: "跑完在 stdout 打一行结果 JSON"},
			{Name: "--", Summary: "分隔符：后面是要执行的命令（脚本路径会解析）"},
		},
		Args: []help.Arg{
			{Name: "<地址>", Key: "address"},
			{Name: "<命令...>", Key: "command"},
		},
		Output: "命令的 stdout / stderr 原样透传；`run` 自己只说一句在哪个目录执行（走 stderr）。" +
			"默认把命令里的脚本路径解析到 `scripts/` 下，工作目录也是 `scripts/`。" +
			"绑定了服务凭据的变量会注入到子进程的环境里，并覆盖本机已有的同名变量。",
		Failures: []help.Failure{
			{Code: "usage", Summary: "-- 后面没有命令，或给了不止一个地址", Next: "按 skmr run <地址> -- <命令> 写"},
			{Code: "not_found", Summary: "地址不存在，或这个版本看不到", Next: "用 skmr skills search 找地址"},
			{Code: "network", Summary: "连不上服务端", Next: "先跑 skmr doctor 看网络与沙箱"},
			{Code: "credential_quota_exceeded", Summary: "绑定的凭据今天到上限了", Next: "让用户跑 skmr secret quota；别自行提高"},
		},
		Examples: []string{"skmr run dev/pdf-tools -- python3 plan.py"},
		Parent:   "",
		Exits:    exitsWithCredential,
		ExitNote: "被执行的命令的退出码原样返回（可以是任何值）；run 自己失败时是 0 / 1 / 2 / 4。",
	},
	{
		Path:    "doctor",
		Summary: "自检本机环境",
		Usage:   "skmr doctor",
		Output: "六行状态，加一段按客户端区分的可复制配置。" +
			"**只打印，不改你的设置**——放宽沙箱是人的决定。",
		Failures: []help.Failure{
			{Code: "usage", Summary: "多给了参数", Next: "doctor 不带参数"},
		},
		Examples: []string{"skmr doctor"},
		Parent:   "",
		Exits:    exitsLocal,
	},
	{
		Path:    "auth status",
		Summary: "这份凭据是什么",
		Usage:   "skmr auth status [<地址>]",
		Args:    []help.Arg{{Name: "[<地址>]", Key: "address"}},
		Output:  "本机存了哪种凭据、服务端认它是谁、到期时间；key 还给出续期 / 撤销地址。诊断走 stderr。",
		Failures: []help.Failure{
			{Code: "unauthenticated", Summary: help.UnauthenticatedSummary, Next: help.UnauthenticatedNext},
			{Code: "token_idle", Summary: "这份凭据闲置太久", Next: help.UnauthenticatedNext},
			{Code: "token_expired", Summary: "这份凭据已到期", Next: help.UnauthenticatedNext},
			{Code: "token_revoked", Summary: "这份凭据已被结束", Next: help.UnauthenticatedNext},
			{Code: "account_suspended", Summary: "账号已被停用", Next: "联系管理员"},
		},
		Examples: []string{"skmr auth status", "skmr auth status skillmaster/skillmaster-startup"},
		Parent:   "auth",
		Exits:    exitsWithCredential,
	},
	// The two doors added by v25. Both are local: `prompt` never talks to the server, and `update`
	// talks to the release bucket rather than to the API.
	//
	// **`prompt` is one door now** (v27, ADR 0110): `install` / `status` / `update` / `uninstall`
	// retired with the markers, so the group only prints. The summary names the thing the way the
	// journey does — 「那段说明」 — not 「规则段」, which is our word for it (v26 prd §话术).
	{
		Path:      "prompt",
		Summary:   "写进 Agent 文件的那段说明",
		Children:  []string{"print"},
		PureGroup: true,
	},
	{
		Path:    "update",
		Summary: "更新这个 CLI",
		Usage:   "skmr update [--check]",
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
		Examples: []string{"skmr update --check", "skmr update"},
		Parent:   "",
		Exits:    exitsLocal,
	},
	{
		Path:    "prompt print",
		Summary: "打印那段说明的正文",
		Usage:   "skmr prompt print",
		Output:  "把这个二进制自带的那段说明原样写到 stdout，断网、没有凭据也能用。",
		Failures: []help.Failure{
			{Code: "usage", Summary: "多给了参数", Next: "去掉多余的参数"},
		},
		Examples: []string{"skmr prompt print"},
		Parent:   "prompt",
		Exits:    exitsLocal,
	},
}

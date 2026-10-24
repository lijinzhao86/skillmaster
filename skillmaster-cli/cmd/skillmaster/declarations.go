package main

import (
	"sort"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/help"
)

// commandHelp is the whole command table: 56 command paths, 6 pure groups, the top level, and one
// topic. The declarations live here, next to the handlers, because the help and the command have one
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

var commandHelp = declarationsCatalog{
	topics: map[string]help.Topic{
		"env": {
			Name:  "env",
			Label: "环境变量",
			Body: `环境变量：
  SKILLMASTER_SERVER       服务端地址（发布版指向生产；本地构建是 localhost:8080）
  SKILLMASTER_API_KEY      API key；设了它，所有命令都用它，本机那份仍在但不会被用到。
                           别把它的值打印出来（echo / env）——那会进 agent 的上下文
  SKILLMASTER_SKILLS_DIR   本机 skill 在哪（默认 ~/.claude/skills）
  SKILLMASTER_WEB_URL      审批页的地址（默认与服务端相同）
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
		Children:  []string{"skill", "skills", "version", "auth", "login", "logout", "setup"},
		PureGroup: true,
	},
	{
		Path:      "skill",
		Summary:   "单个 skill 的动词",
		Children:  []string{"invoke", "files", "read", "cat", "wc", "grep", "write", "edit", "rm", "mv", "cp", "download", "versions", "submit", "share", "create", "shares", "unshare", "request", "open", "show", "tags", "tag", "category", "join", "leave", "restore"},
		PureGroup: true,
	},
	{
		Path:      "skills",
		Summary:   "整个库的动词",
		Children:  []string{"list", "search", "categories", "public-tags", "my-tags"},
		PureGroup: true,
	},
	{
		Path:      "version",
		Summary:   "作者面的版本管理",
		Children:  []string{"list", "show", "diff", "rename", "note", "discard", "open", "publish", "rollback", "revoke", "restore", "offline", "online", "delete"},
		PureGroup: true,
	},
	{
		Path:      "auth",
		Summary:   "凭据与登录状态",
		Children:  []string{"status"},
		PureGroup: true,
	},
	{
		Path:    "login",
		Summary: "在浏览器里登录这台机器",
		Usage:   "skillmaster login",
		Output:  "登录成功后在 stdout 打印这份凭据是谁的、到什么时候；诊断走 stderr。",
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
		Path:    "setup",
		Summary: "安装网关 skill",
		Usage:   "skillmaster setup [--dir <技能目录>]",
		Flags:   []help.Flag{{Name: "--dir", Value: "<技能目录>", Summary: "安到哪个目录"}},
		Output:  "把网关 skill 写进本机技能目录，打印落点。",
		Failures: []help.Failure{
			{Code: "network", Summary: "取不到网关正文", Next: "检查网络后重试"},
		},
		Examples: []string{"skillmaster setup", "skillmaster setup --dir ~/.claude/skills"},
		Parent:   "",
		Exits:    exitsLocal,
	},
	{
		Path:    "auth status",
		Summary: "这份凭据是什么",
		Usage:   "skillmaster auth status",
		Output:  "本机存了哪种凭据、服务端认它是谁；诊断走 stderr。",
		Failures: []help.Failure{
			{Code: "unauthenticated", Summary: "服务端不认这份凭据", Next: "重新运行 skillmaster login"},
			{Code: "token_idle", Summary: "这份凭据闲置太久", Next: "重新运行 skillmaster login"},
			{Code: "token_expired", Summary: "这份凭据已到期", Next: "重新运行 skillmaster login"},
			{Code: "token_revoked", Summary: "这份凭据已被结束", Next: "重新运行 skillmaster login"},
			{Code: "account_suspended", Summary: "账号已被停用", Next: "联系管理员"},
		},
		Examples: []string{"skillmaster auth status"},
		Parent:   "auth",
		Exits:    exitsWithCredential,
	},
}

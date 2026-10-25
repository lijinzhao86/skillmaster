package help

// The error registry is the one authority on what a failure means and which status it carries
// (ADR 0087). Declarations name codes from here; the top-level help and the CLI README show the four
// exit classes, which are this table's projection plus the success status 0.
//
// **Generic codes are still listed**, because a declaration may name them and the conformance test
// refuses an unregistered name. They are not required to appear in any command's Failures: the
// classifier produces them on paths no declaration enumerates.

// codes is the registry. Order is the order the conformance test reports in.
var codes = []Code{
	// Usage and process-level failures.
	{Name: "usage", Text: "命令或参数写得不对", Exit: 2},
	{Name: "refused", Text: "这个动作不在 CLI 做", Exit: 1},
	{Name: "login_failed", Text: "登录没有完成", Exit: 1},
	{Name: "network", Text: "连不上服务端", Exit: 1},
	{Name: "server_error", Text: "服务端出错", Exit: 1},
	{Name: "internal_error", Text: "服务端内部错误", Exit: 1},
	{Name: "not_found", Text: "地址或资源找不到", Exit: 1},
	{Name: "invalid_request", Text: "提交的内容有问题", Exit: 1},
	{Name: "bad_pattern", Text: "这个正则服务端不支持", Exit: 1},
	{Name: "too_expensive", Text: "这个正则太费时", Exit: 1},
	{Name: "too_many_requests", Text: "请求太频繁", Exit: 1},
	{Name: "insufficient_permission", Text: "这份凭据没有这项权限", Exit: 1},

	// Credential refusals. Four of them mean "replace the credential"; the fifth does not.
	{Name: "unauthenticated", Text: "服务端不认这份凭据", Exit: 4},
	{Name: "token_idle", Text: "凭据闲置太久", Exit: 4},
	{Name: "token_expired", Text: "凭据已到期", Exit: 4},
	{Name: "token_revoked", Text: "凭据已被结束", Exit: 4},
	{Name: "account_suspended", Text: "账号已被停用", Exit: 1},

	// Skill and version states a caller can meet.
	{Name: "skill_not_found", Text: "这个 skill 在你的库里找不到", Exit: 1},
	{Name: "skill_deleted", Text: "名字属于一个已删除的 skill", Exit: 1},
	{Name: "skill_offline", Text: "这个 skill 已下线", Exit: 1},
	{Name: "skill_already_exists", Text: "这个名字已被占用", Exit: 1},
	{Name: "version_revoked", Text: "这一版已下架", Exit: 1},
	{Name: "invalid_version_state", Text: "这一版当前状态不能做这个操作", Exit: 1},
	{Name: "draft_name_required", Text: "这一版需要一个名称", Exit: 1},
	{Name: "draft_name_too_long", Text: "名称太长", Exit: 1},
	{Name: "draft_name_is_frozen", Text: "这一版的名称不能再改", Exit: 1},
	{Name: "note_too_long", Text: "说明太长", Exit: 1},
	{Name: "note_is_frozen", Text: "这一版的说明不能再改", Exit: 1},
	{Name: "release_type_is_frozen", Text: "这一版的类型不能再改", Exit: 1},
	{Name: "content_already_exists", Text: "同样的内容已经有一版了", Exit: 1},
	{Name: "digest_mismatch", Text: "这一版刚变过", Exit: 1},

	// Membership and tags.
	{Name: "skill_not_joinable", Text: "这个 skill 现在加入不了", Exit: 1},
	{Name: "library_reference_missing", Text: "库里没有这个引用", Exit: 1},
}

// Codes returns the registry.
func Codes() []Code { return codes }

// exitClasses is the four lines the top-level help and the CLI README show.
var exitClasses = []ExitClass{
	{Code: 0, Text: "成功"},
	{Code: 1, Text: "普通失败"},
	{Code: 2, Text: "用法错误"},
	{Code: 4, Text: "凭据不可用"},
}

// ExitClasses returns the four lines. It is a projection of the registry plus success.
func ExitClasses() []ExitClass { return exitClasses }

// codeByName finds a registry entry.
func codeByName(name string) (Code, bool) {
	for _, code := range codes {
		if code.Name == name {
			return code, true
		}
	}
	return Code{}, false
}

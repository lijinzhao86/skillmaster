package help

import (
	"sort"
	"strings"
	"testing"
)

// testCatalog is a small declaration set shaped like the real one: a root, a pure group, a group
// with an executable node, a leaf with a --json object, and a topic.
type testCatalog struct {
	specs  []*Spec
	topics map[string]Topic
}

func (c testCatalog) Specs() []*Spec { return c.specs }
func (c testCatalog) Topic(name string) (Topic, bool) {
	topic, ok := c.topics[name]
	return topic, ok
}

// Topics lists the fixture's topics by name, the way package main's catalog does — sorting here
// rather than trusting map order, so the rendered text is stable.
func (c testCatalog) Topics() []Topic {
	names := make([]string, 0, len(c.topics))
	for name := range c.topics {
		names = append(names, name)
	}
	sort.Strings(names)
	topics := make([]Topic, 0, len(names))
	for _, name := range names {
		topics = append(topics, c.topics[name])
	}
	return topics
}

func fixture() testCatalog {
	return testCatalog{
		specs: []*Spec{
			{Path: "", Summary: "SkillMaster 的命令行", Children: []string{"skill", "skills", "version", "login", "logout", "setup"}},
			{Path: "skill", Summary: "围绕一个 skill 的动词", Children: []string{"grep", "invoke"}, PureGroup: true, Exits: []int{0, 2}},
			{Path: "skill grep", Summary: "在一个草稿里搜文本", Parent: "skill",
				Usage:    "skmr skill grep <地址> <pattern> [--path <前缀>]",
				Flags:    []Flag{{Name: "--path", Value: "<前缀>", Summary: "只在某个路径前缀下搜"}},
				Args:     []Arg{{Name: "<地址>", Key: "address", Required: true}, {Name: "<pattern>", Key: "pattern", Required: true}},
				Output:   "一行一个命中，走 stdout。",
				Failures: []Failure{{Code: "not_found", Summary: "地址找不到", Next: "核对地址"}},
				Examples: []string{"skmr skill grep dev/pdf-tools TODO --path src/"},
				Exits:    []int{0, 1, 2, 4}},
			{Path: "skill invoke", Summary: "把 skill 装进会话", Parent: "skill",
				Args:   []Arg{{Name: "<地址>", Key: "address", Required: true}},
				Output: "正文走 stdout。", JSONFields: []string{"origin", "content"},
				Exits: []int{0, 1, 2, 4}},
			{Path: "skills", Summary: "围绕整个库的动词", Children: []string{"my-tags"}, PureGroup: true, Exits: []int{0}},
			{Path: "skills my-tags", Summary: "我的标签", Parent: "skills", Children: []string{"add"},
				Usage: "skmr skills my-tags [关键词] [--no-login]", Exits: []int{0, 1, 2, 4}},
			{Path: "skills my-tags add", Summary: "新建一个标签", Parent: "skills my-tags",
				Args: []Arg{{Name: "<名字>", Key: "name", Required: true}}, Exits: []int{0, 1, 2, 4}},
			{Path: "version", Summary: "版本相关的动词", Children: []string{"list"}, PureGroup: true, Exits: []int{0}},
			{Path: "version list", Summary: "列出一个 skill 的版本", Parent: "version",
				Usage:  "skmr version list <地址> [--json]",
				Args:   []Arg{{Name: "<地址>", Key: "address", Required: true}},
				Output: "一版一行，走 stdout。", JSONFields: []string{"skill", "versions", "counts", "has_more"},
				Exits: []int{0, 1, 2, 4}},
			{Path: "login", Summary: "登录这台机器", Exits: []int{0, 1, 2}},
			{Path: "logout", Summary: "退出登录", Exits: []int{0, 1, 2}},
			{Path: "setup", Summary: "准备本机环境", Exits: []int{0, 1, 2}},
		},
		topics: map[string]Topic{"env": {
			Name:  "env",
			Label: "环境变量",
			Body:  "SKILLMASTER_SERVER\nSKILLMASTER_API_KEY\n",
		}},
	}
}

func TestRenderLeafHasTheEightItems(t *testing.T) {
	text := Render(fixture(), "skill grep")
	for _, want := range []string{"在一个草稿里搜文本", "用法：skmr skill grep", "参数：", "--path <前缀>", "--help, -h",
		"输出：", "失败：", "地址找不到", "下一步：核对地址", "例子：", "上级：skmr skill --help", "退出码：0 · 1"} {
		if !strings.Contains(text, want) {
			t.Errorf("rendered help is missing %q:\n%s", want, text)
		}
	}
	if strings.Contains(text, "字段：") {
		t.Errorf("a command without JSONFields must not render a fields line:\n%s", text)
	}
}

func TestRenderJSONCommandListsFieldsInOrder(t *testing.T) {
	text := Render(fixture(), "version list")
	if !strings.Contains(text, "字段：skill, versions, counts, has_more") {
		t.Errorf("fields line missing or out of order:\n%s", text)
	}
	if !strings.Contains(text, "--json") {
		t.Errorf("a --json command must list --json among its universal flags:\n%s", text)
	}
	if !strings.Contains(text, "正文走 stdout，诊断走 stderr。") {
		t.Errorf("a --json command must say which stream carries what:\n%s", text)
	}
	if strings.Contains(Render(fixture(), "skill grep"), "正文走 stdout") {
		t.Errorf("a command without --json must not carry the JSON stream sentence")
	}
}

func TestRenderPureGroupHasFourItemsOnly(t *testing.T) {
	text := Render(fixture(), "version")
	// The four items are description, usage, the block, and the parent address — so no flags, no
	// output, no failures, no examples, and no exit line, but the parent line **is** one of them.
	for _, unwanted := range []string{"参数：", "输出：", "失败：", "例子：", "退出码："} {
		if strings.Contains(text, unwanted) {
			t.Errorf("a pure group must not render %q:\n%s", unwanted, text)
		}
	}
	if !strings.Contains(text, "上级：skmr help") {
		t.Errorf("a pure group has no parent line:\n%s", text)
	}
	if !strings.Contains(text, "子命令：") || !strings.Contains(text, "list") || !strings.Contains(text, "列出一个 skill 的版本") {
		t.Errorf("a pure group must list its children with their summaries:\n%s", text)
	}
}

func TestRenderExecutableNodeHasBothBlockAndItems(t *testing.T) {
	text := Render(fixture(), "skills my-tags")
	if !strings.Contains(text, "子命令：") || !strings.Contains(text, "add") {
		t.Errorf("an executable node must still list its children:\n%s", text)
	}
	if !strings.Contains(text, "参数：") || !strings.Contains(text, "上级：skmr skills --help") {
		t.Errorf("an executable node keeps the eight-item body:\n%s", text)
	}
}

func TestRequestedEveryHelpPosition(t *testing.T) {
	cat := fixture()
	cases := []struct {
		args []string
		want Resolution
	}{
		{[]string{"--help"}, Resolution{Kind: KindCommand, Path: ""}},
		{[]string{"skill", "--help"}, Resolution{Kind: KindCommand, Path: "skill"}},
		{[]string{"skill", "grep", "--help"}, Resolution{Kind: KindCommand, Path: "skill grep"}},
		{[]string{"skill", "grep", "a/b", "--help"}, Resolution{Kind: KindCommand, Path: "skill grep"}},
		{[]string{"skill", "grep", "-h"}, Resolution{Kind: KindCommand, Path: "skill grep"}},
		{[]string{"skill", "grep", "a/b", "help"}, Resolution{Kind: KindNone}},
		{[]string{"skills", "search", "help"}, Resolution{Kind: KindNone}},
		{[]string{"help"}, Resolution{Kind: KindCommand, Path: ""}},
		{[]string{"skill", "help"}, Resolution{Kind: KindCommand, Path: "skill"}},
		{[]string{"skills", "my-tags", "help"}, Resolution{Kind: KindCommand, Path: "skills my-tags"}},
		{[]string{"help", "env"}, Resolution{Kind: KindTopic, Topic: "env"}},
		{[]string{"help", "nope"}, Resolution{Kind: KindUnknownTopic, Topic: "nope"}},
		{[]string{"help", "env", "x"}, Resolution{Kind: KindTopicUsage}},
		{[]string{"skill", "invoke", "a/b"}, Resolution{Kind: KindNone}},
	}
	for _, tc := range cases {
		if got := Requested(cat, tc.args); got != tc.want {
			t.Errorf("Requested(%q) = %+v, want %+v", tc.args, got, tc.want)
		}
	}
}

func TestSuggestRefusesToGuess(t *testing.T) {
	candidates := []string{"skill", "skills", "version", "login"}
	if got, ok := Suggest("skil", candidates); !ok || got != "skill" {
		t.Errorf("Suggest(skil) = %q, %v; want skill", got, ok)
	}
	if _, ok := Suggest("foo", candidates); ok {
		t.Error("a word nothing is near must not produce a suggestion")
	}
	if _, ok := Suggest("versoin", candidates); !ok {
		t.Error("a transposition within distance 2 must be suggested")
	}
	if _, ok := Suggest("ad", []string{"add", "ads"}); ok {
		t.Error("a tie between two equally near names must not be guessed")
	}
}

func TestUniversalFlags(t *testing.T) {
	cat := fixture()
	if got := Universal(cat, "skill grep"); len(got) != 1 || got[0].Name != "--help" {
		t.Errorf("a command without JSONFields gets only --help, got %+v", got)
	}
	if got := Universal(cat, "version list"); len(got) != 2 || got[1].Name != "--json" {
		t.Errorf("a --json command gets --json too, got %+v", got)
	}
}

func TestValidateRejectsGaps(t *testing.T) {
	cat := fixture()
	if err := Validate(cat); err != nil {
		t.Fatalf("the fixture must validate: %v", err)
	}
	missing := []Node{{Path: "future", Children: []string{"nowhere"}}}
	if err := Validate(cat, missing); err == nil {
		t.Error("a node without a declaration must fail")
	}
	badCode := fixture()
	badCode.specs = append(badCode.specs, &Spec{Path: "skill invoke", Failures: []Failure{{Code: "not_a_code"}}})
	if err := Validate(badCode); err == nil {
		t.Error("a failure naming an unregistered code must fail")
	}
}

// A second topic is one map entry, not a second list: the top-level help lists what the catalog
// carries, and the unknown-topic error names the same set. Before this, both were literal "env".
func TestASecondTopicReachesEverywhereItIsNamed(t *testing.T) {
	cat := fixture()
	cat.topics["auth"] = Topic{Name: "auth", Label: "登录", Body: "auth 主题\n"}

	top := RenderTop(cat)
	for _, want := range []string{"环境变量：skmr help env", "登录：skmr help auth"} {
		if !strings.Contains(top, want) {
			t.Errorf("top-level help does not carry %q:\n%s", want, top)
		}
	}

	names := TopicNames(cat)
	if len(names) != 2 || names[0] != "auth" || names[1] != "env" {
		t.Errorf("TopicNames = %v, want [auth env] in listing order", names)
	}
}

// Every command that can return 4 gets the two shared failures, and only those commands do: a
// refusal or a machine-local command never mentions a credential or a network.
func TestUniversalFailuresFollowTheExitList(t *testing.T) {
	cat := fixture()
	withCredential := UniversalFailures(cat, "skill grep")
	if len(withCredential) != 2 {
		t.Fatalf("skill grep got %d shared failures, want 2: %v", len(withCredential), withCredential)
	}
	text := Render(cat, "skill grep")
	for _, want := range []string{"凭据不可用", "连不上服务端"} {
		if !strings.Contains(text, want) {
			t.Errorf("skill grep help does not mention %q:\n%s", want, text)
		}
	}

	for _, path := range []string{"skill", "login"} {
		if shared := UniversalFailures(cat, path); len(shared) != 0 {
			t.Errorf("%q got shared failures %v, want none", path, shared)
		}
	}
}

// A command that says something more specific about its 401 keeps its own sentence; the shared one
// would be a second, vaguer line about the same thing.
func TestUniversalFailuresDoNotDuplicateADeclaredCode(t *testing.T) {
	cat := fixture()
	spec, _ := Lookup(cat, "skill invoke")
	spec.Failures = append(spec.Failures, Failure{Code: "unauthenticated", Summary: "自己的说法", Next: "自己说"})

	for _, failure := range UniversalFailures(cat, "skill invoke") {
		if failure.Code == "unauthenticated" {
			t.Fatalf("the shared 401 was added on top of the command's own: %v", failure)
		}
	}
	text := Render(cat, "skill invoke")
	if strings.Count(text, "自己的说法") != 1 || strings.Contains(text, "运行 skmr login 或换一把 API key") {
		t.Errorf("skill invoke renders both the specific and the shared 401:\n%s", text)
	}
}

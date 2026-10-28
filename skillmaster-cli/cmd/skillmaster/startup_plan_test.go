package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/credentials"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/prompt"
)

func TestStartupPlanWithoutCredentialIsReadOnlyAndSaysWhatIsUnknown(t *testing.T) {
	dir := isolateConfig(t)
	localSkill(t, os.Getenv("HOME"), "local-one")

	stdout, stderr, status := runCaptured(t, "startup", "--plan")
	if status != 0 {
		t.Fatalf("status = %d, stderr=%s", status, stderr)
	}
	for _, want := range []string{
		"SkillMaster 安装清单",
		"还差 2 件",
		"登录或注册",
		"登录后检查",
		"让 Agent 先来 SkillMaster 找技能",
		"传技能（可选；登录后才知道还有几个没传，想传的时候再跑一次）",
	} {
		if !strings.Contains(stdout, want) {
			t.Errorf("plan does not contain %q:\n%s", want, stdout)
		}
	}
	for _, want := range []string{"正在检查你的电脑和 SkillMaster", "下一步：先登录"} {
		if !strings.Contains(stderr, want) {
			t.Errorf("stderr does not contain %q:\n%s", want, stderr)
		}
	}
	for _, name := range []string{"prompt.json", "update.json"} {
		if _, err := os.Stat(filepath.Join(dir, name)); !os.IsNotExist(err) {
			t.Errorf("--plan wrote %s (err=%v)", name, err)
		}
	}
	for _, unwanted := range []string{"CLI", "agent", "系统提示词", "标记段", "摘要", "凭据", "--"} {
		if strings.Contains(stdout, unwanted) {
			t.Errorf("the checklist shows %q; it must stay in the person's words:\n%s", unwanted, stdout)
		}
	}
	if home := os.Getenv("HOME"); strings.Contains(stdout, home) {
		t.Errorf("the checklist shows a path:\n%s", stdout)
	}
}

func TestStartupPlanDoesNotCallAnUnreadChecklistComplete(t *testing.T) {
	dir := isolateConfig(t)
	home := os.Getenv("HOME")
	file := filepath.Join(home, "AGENTS.md")
	if err := os.WriteFile(file, []byte(string(prompt.Block())), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := prompt.SaveStore(dir, prompt.Store{Schema: 2, Files: []prompt.Record{{
		File: file, Agent: "codex", Digest: prompt.Digest(),
	}}}); err != nil {
		t.Fatal(err)
	}

	// The credential is valid locally, but the server cannot be read. The prompt item is done, so
	// there is nothing left to do; four server-backed items and the login check are still unknown.
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "down", http.StatusInternalServerError)
	}))
	t.Cleanup(server.Close)
	t.Setenv(config.ServerEnv, server.URL)
	pointTheMachineAt(t, &stubStore{found: true, creds: credentials.Credentials{AccessToken: "token"}})

	stdout, stderr, status := runCaptured(t, "startup", "--plan", "--agent", "codex", "--file", file)
	if status != 0 {
		t.Fatalf("status = %d, stderr=%s", status, stderr)
	}
	for _, want := range []string{"还要确认", "现在还不能算完成"} {
		if !strings.Contains(stdout, want) {
			t.Errorf("plan does not say %q:\n%s", want, stdout)
		}
	}
	for _, unwanted := range []string{"全部好了", "旅程完成"} {
		if strings.Contains(stdout, unwanted) {
			t.Errorf("plan calls an unread checklist complete with %q:\n%s", unwanted, stdout)
		}
	}
	if !strings.Contains(stderr, "下一步：稍后再检查一次") {
		t.Errorf("stderr = %q, want a safe next step", stderr)
	}
}

func TestStartupPlanWithCredentialCountsTheSevenItems(t *testing.T) {
	dir := isolateConfig(t)
	home := os.Getenv("HOME")
	localSkill(t, home, "local-one")
	file := filepath.Join(home, "AGENTS.md")
	if err := os.WriteFile(file, []byte(string(prompt.Block())), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := prompt.SaveStore(dir, prompt.Store{Schema: 2, Files: []prompt.Record{
		{File: file, Agent: "codex", Digest: prompt.Digest()},
		{File: filepath.Join(home, "CLAUDE.md"), Agent: "claude", Digest: prompt.Digest()},
	}}); err != nil {
		t.Fatal(err)
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/me":
			_, _ = w.Write([]byte(`{"user":{"user_id":"u1","username":"dev","namespace":"dev"}}`))
		case "/api/v1/author/publish-queue":
			_, _ = w.Write([]byte(`{"skills":[],"truncated":false}`))
		case "/api/v1/skills":
			_, _ = w.Write([]byte(`{"skills":[{"name":"local-one"}],"next_cursor":""}`))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(server.Close)
	t.Setenv(config.ServerEnv, server.URL)
	pointTheMachineAt(t, &stubStore{found: true, creds: credentials.Credentials{AccessToken: "token"}})

	stdout, stderr, status := runCaptured(t, "startup", "--plan", "--agent", "codex", "--file", file)
	if status != 0 {
		t.Fatalf("status = %d, stderr=%s", status, stderr)
	}
	for _, want := range []string{
		"已经好了（5 件）",
		"小工具",
		"登录",
		"传技能",
		"正式上线",
		"让 Agent 先来 SkillMaster 找技能",
		"跳过（2 件）",
		"让 Agent 用 SkillMaster 自己的文件夹",
		"电脑上原来的那份",
		"必做项都好了；要不要清理电脑上原来那份",
		"记录里还有 1 个别的文件",
	} {
		if !strings.Contains(stdout, want) {
			t.Errorf("plan does not contain %q:\n%s", want, stdout)
		}
	}
	if strings.Contains(stdout, "还差") {
		t.Errorf("a finished plan still says what is left:\n%s", stdout)
	}
	if !strings.Contains(stderr, "下一步：steps/06-permission.md") {
		t.Errorf("stderr = %q, want the optional cleanup", stderr)
	}
}

// ADR 0108: declining the import must not leave the checklist saying something is missing. The
// local skill stays visible in the skipped line, and the journey still finishes.
func TestStartupPlanFinishesWhenThePersonDeclinesToImport(t *testing.T) {
	dir := isolateConfig(t)
	home := os.Getenv("HOME")
	localSkill(t, home, "local-one")
	file := filepath.Join(home, "AGENTS.md")
	if err := os.WriteFile(file, []byte(string(prompt.Block())), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := prompt.SaveStore(dir, prompt.Store{Schema: 2, Files: []prompt.Record{{
		File: file, Agent: "codex", Digest: prompt.Digest(),
	}}}); err != nil {
		t.Fatal(err)
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/me":
			_, _ = w.Write([]byte(`{"user":{"user_id":"u1","username":"dev","namespace":"dev"}}`))
		case "/api/v1/author/publish-queue":
			_, _ = w.Write([]byte(`{"skills":[],"truncated":false}`))
		case "/api/v1/skills":
			_, _ = w.Write([]byte(`{"skills":[],"next_cursor":""}`))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(server.Close)
	t.Setenv(config.ServerEnv, server.URL)
	pointTheMachineAt(t, &stubStore{found: true, creds: credentials.Credentials{AccessToken: "token"}})

	stdout, stderr, status := runCaptured(t, "startup", "--plan", "--agent", "codex", "--file", file)
	if status != 0 {
		t.Fatalf("status = %d, stderr=%s", status, stderr)
	}
	for _, want := range []string{
		"跳过（1 件）：传技能（可选；你电脑上还有 1 个技能没传，想传的时候再跑一次）",
		"必做项都好了；电脑上还有技能没传，想传的时候再跑一次。",
	} {
		if !strings.Contains(stdout, want) {
			t.Errorf("plan does not contain %q:\n%s", want, stdout)
		}
	}
	if strings.Contains(stdout, "还差") {
		t.Errorf("a declined import still blocks the checklist:\n%s", stdout)
	}
	if strings.Contains(stdout, "全部好了") {
		t.Errorf("the plan calls itself completely done while the optional import remains:\n%s", stdout)
	}
	if !strings.Contains(stderr, "下一步：旅程完成") {
		t.Errorf("stderr = %q, want the journey to finish without the import", stderr)
	}
}

// Partially importing is progress: the uploaded draft still goes to 正式上线, and the remaining
// local copy stays optional instead of sending the person back to steps/01.
func TestStartupPlanContinuesAfterAPartialImport(t *testing.T) {
	dir := isolateConfig(t)
	home := os.Getenv("HOME")
	localSkill(t, home, "alpha")
	localSkill(t, home, "beta")
	file := filepath.Join(home, "AGENTS.md")
	if err := os.WriteFile(file, []byte(string(prompt.Block())), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := prompt.SaveStore(dir, prompt.Store{Schema: 2, Files: []prompt.Record{{
		File: file, Agent: "codex", Digest: prompt.Digest(),
	}}}); err != nil {
		t.Fatal(err)
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/me":
			_, _ = w.Write([]byte(`{"user":{"user_id":"u1","username":"dev","namespace":"dev"}}`))
		case "/api/v1/author/publish-queue":
			_, _ = w.Write([]byte(`{"skills":[{"namespace":"dev","name":"alpha","drafts":[{"code":"K1"}]}],"truncated":false}`))
		case "/api/v1/skills":
			_, _ = w.Write([]byte(`{"skills":[{"name":"alpha"}],"next_cursor":""}`))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(server.Close)
	t.Setenv(config.ServerEnv, server.URL)
	pointTheMachineAt(t, &stubStore{found: true, creds: credentials.Credentials{AccessToken: "token"}})

	stdout, stderr, status := runCaptured(t, "startup", "--plan", "--agent", "codex", "--file", file)
	if status != 0 {
		t.Fatalf("status = %d, stderr=%s", status, stderr)
	}
	for _, want := range []string{
		"传技能（可选；你电脑上还有 1 个技能没传，想传的时候再跑一次）",
		"还差 1 件：\n1. 有 1 份草稿在等正式上线",
	} {
		if !strings.Contains(stdout, want) {
			t.Errorf("plan does not contain %q:\n%s", want, stdout)
		}
	}
	if !strings.Contains(stderr, "下一步：steps/02-publish.md") {
		t.Errorf("stderr = %q, want the publish step", stderr)
	}
	if strings.Contains(stderr, "steps/01-import.md") {
		t.Errorf("stderr sends a partially imported machine back to the import: %q", stderr)
	}
}

func TestStartupPrintsTheRecordedAIsOnStderr(t *testing.T) {
	const body = "# SkillMaster 入门\n\n一条链，不是菜单。\n"
	server := startupServer(t, body)
	t.Setenv(config.ServerEnv, server.URL)
	t.Setenv(config.ApiKeyEnv, "skm_livekey123456")
	dir, err := config.Dir()
	if err != nil {
		t.Fatal(err)
	}
	if err := prompt.SaveStore(dir, prompt.Store{Schema: 2, Files: []prompt.Record{{
		File: filepath.Join(os.Getenv("HOME"), "CLAUDE.md"), Agent: "claude", Digest: prompt.Digest(),
	}}}); err != nil {
		t.Fatal(err)
	}

	stdout, stderr, status := runCaptured(t, "startup")
	if status != 0 {
		t.Fatalf("status = %d, stderr=%s", status, stderr)
	}
	if !strings.HasSuffix(stdout, body) {
		t.Errorf("stdout does not end with the journey body:\n%s", stdout)
	}
	if !strings.Contains(stderr, "这台电脑上写过这段说明的 AI：claude") {
		t.Errorf("stderr does not name the recorded AI:\n%s", stderr)
	}
}

func TestPromptRecordLineNamesEachConfiguredAI(t *testing.T) {
	dir := isolateConfig(t)
	if err := prompt.SaveStore(dir, prompt.Store{Schema: 2, Files: []prompt.Record{
		{File: "/a/CLAUDE.md", Agent: "claude", Digest: "sha256:1"},
		{File: "/b/AGENTS.md", Agent: "codex", Digest: "sha256:2"},
		{File: "/c/AGENTS.md", Agent: "claude", Digest: "sha256:3"},
		{File: "/d/AGENTS.md", Digest: "sha256:4"},
	}}); err != nil {
		t.Fatal(err)
	}
	line, ok := promptRecordLine()
	if !ok || !strings.Contains(line, "claude、codex") {
		t.Fatalf("line = %q, ok=%v", line, ok)
	}
	if strings.Count(line, "claude") != 1 {
		t.Errorf("a repeated label was not deduplicated: %q", line)
	}
	if !strings.Contains(line, "未命名") {
		t.Errorf("an unlabelled v25 record is not called 未命名: %q", line)
	}
	if strings.Contains(line, "/d/AGENTS.md") {
		t.Errorf("the line shows a path: %q", line)
	}
}

func TestStartupPlanLeavesAStaleRecordAlone(t *testing.T) {
	dir := isolateConfig(t)
	file := filepath.Join(os.Getenv("HOME"), "AGENTS.md")
	if err := os.WriteFile(file, []byte("旧内容，没有标记段\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := prompt.SaveStore(dir, prompt.Store{Schema: 2, Files: []prompt.Record{{
		File: file, Agent: "codex", Digest: "old-digest",
	}}}); err != nil {
		t.Fatal(err)
	}

	_, stderr, status := runCaptured(t, "startup", "--plan")
	if status != 0 {
		t.Fatalf("status = %d, stderr=%s", status, stderr)
	}
	store, has, err := prompt.LoadStore(dir)
	if err != nil || !has {
		t.Fatalf("LoadStore: has=%v err=%v", has, err)
	}
	if store.Files[0].Notified != "" {
		t.Errorf("--plan marked the stale record as notified: %q", store.Files[0].Notified)
	}
	for _, name := range []string{"update.json"} {
		if _, err := os.Stat(filepath.Join(dir, name)); !os.IsNotExist(err) {
			t.Errorf("--plan wrote %s (err=%v)", name, err)
		}
	}
}

func TestOnlyThePlanFormOfStartupSkipsTheWritingHooks(t *testing.T) {
	cases := []struct {
		args []string
		want bool
	}{
		{[]string{"startup", "--plan"}, true},
		{[]string{"startup", "--plan", "--agent", "codex", "--file", "/tmp/x"}, true},
		{[]string{"startup"}, false},
		{[]string{"prompt", "status"}, false},
		{[]string{"prompt", "status", "--plan"}, false},
		{[]string{"update"}, false},
	}
	for _, c := range cases {
		if got := isReadOnlyPlan(c.args); got != c.want {
			t.Errorf("isReadOnlyPlan(%v) = %v, want %v", c.args, got, c.want)
		}
	}
}

func TestStartupFlagsOnlyWorkWithPlan(t *testing.T) {
	isolateConfig(t)
	_, stderr, status := runCaptured(t, "startup", "--agent", "codex")
	if status != 2 {
		t.Fatalf("status = %d, want a usage error", status)
	}
	if !strings.Contains(stderr, "--agent") || !strings.Contains(stderr, "只和 --plan 一起用") {
		t.Errorf("stderr does not explain --agent:\n%s", stderr)
	}
}

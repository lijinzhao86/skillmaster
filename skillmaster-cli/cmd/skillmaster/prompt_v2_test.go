package main

import (
	"bytes"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/prompt"
)

func homeFile(t *testing.T, name string) string {
	t.Helper()
	return filepath.Join(os.Getenv("HOME"), name)
}

func oldBlock() string {
	return "<!-- skillmaster:begin -->\n旧的一行\n<!-- skillmaster:end -->\n"
}

func TestPromptInstallWithoutYesPrintsThePlainPlanAndWritesNothing(t *testing.T) {
	dir := isolateConfig(t)
	file := homeFile(t, "CLAUDE.md")
	if err := os.WriteFile(file, []byte("# 我的规则\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	before, _ := os.ReadFile(file)
	beforeInfo, _ := os.Stat(file)

	stdout, _, status := runCaptured(t, "prompt", "install", "--file", file)
	if status != 1 {
		t.Fatalf("status = %d, want 1", status)
	}
	for _, want := range []string{"我要做什么", "只会动到什么", "做完会怎样", "不想做了怎么办", "不做会怎样"} {
		if !strings.Contains(stdout, want) {
			t.Errorf("plan does not contain %q:\n%s", want, stdout)
		}
	}
	for _, unwanted := range []string{file, "skillmaster ", "--yes", "--file", "标记段", "摘要", "凭据"} {
		if strings.Contains(stdout, unwanted) {
			t.Errorf("plan shows %q; the five sentences must not carry a path or a command:\n%s", unwanted, stdout)
		}
	}
	after, _ := os.ReadFile(file)
	afterInfo, _ := os.Stat(file)
	if !bytes.Equal(before, after) {
		t.Fatal("install without --yes changed the file")
	}
	if !beforeInfo.ModTime().Equal(afterInfo.ModTime()) {
		t.Fatal("install without --yes changed the file's mtime")
	}

	if _, stderr, status := runCaptured(t, "prompt", "install", "--file", file, "--yes"); status != 0 {
		t.Fatalf("install --yes failed (%d): %s", status, stderr)
	}
	body, _ := os.ReadFile(file)
	if !strings.HasPrefix(string(body), "# 我的规则\n") || !strings.Contains(string(body), string(prompt.Block())) {
		t.Errorf("install --yes did not append the block:\n%s", body)
	}
	store, has, _ := prompt.LoadStore(dir)
	if !has || len(store.Files) != 1 || store.Files[0].File != file {
		t.Errorf("record = %+v, want one for %s", store, file)
	}
}

func TestPromptInstallPathChecks(t *testing.T) {
	isolateConfig(t)
	home := os.Getenv("HOME")

	t.Run("directory", func(t *testing.T) {
		dir := filepath.Join(home, "a-directory")
		if err := os.Mkdir(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		stdout, stderr, status := runCaptured(t, "prompt", "install", "--file", dir, "--yes")
		if status != 1 || !strings.Contains(stdout+stderr, "目录") {
			t.Errorf("status=%d output=%q", status, stdout+stderr)
		}
	})

	t.Run("missing parent", func(t *testing.T) {
		file := filepath.Join(home, "missing", "AGENTS.md")
		stdout, stderr, status := runCaptured(t, "prompt", "install", "--file", file, "--yes")
		if status != 1 || !strings.Contains(stdout+stderr, "上级目录不存在") {
			t.Errorf("status=%d output=%q", status, stdout+stderr)
		}
	})

	t.Run("outside home", func(t *testing.T) {
		file := filepath.Join(t.TempDir(), "AGENTS.md")
		stdout, stderr, status := runCaptured(t, "prompt", "install", "--file", file, "--yes")
		if status != 1 || !strings.Contains(stdout+stderr, "不在你的主目录下") {
			t.Errorf("status=%d output=%q", status, stdout+stderr)
		}
		if _, stderr, status := runCaptured(t, "prompt", "install", "--file", file, "--force", "--yes"); status != 0 {
			t.Fatalf("--force --yes failed (%d): %s", status, stderr)
		}
		body, _ := os.ReadFile(file)
		if !strings.Contains(string(body), string(prompt.Block())) {
			t.Error("--force --yes did not write the block")
		}
	})

	t.Run("project warning", func(t *testing.T) {
		project := filepath.Join(home, "project")
		if err := os.Mkdir(project, 0o755); err != nil {
			t.Fatal(err)
		}
		t.Chdir(project)
		file := filepath.Join(project, "AGENTS.md")
		stdout, _, status := runCaptured(t, "prompt", "install", "--file", file)
		if status != 1 || !strings.Contains(stdout, "只对当前项目生效") {
			t.Errorf("status=%d stdout=%q", status, stdout)
		}
	})
}

func TestPromptRecordsTwoFilesAndUninstall(t *testing.T) {
	dir := isolateConfig(t)
	a := homeFile(t, "a.md")
	b := homeFile(t, "b.md")
	for _, file := range []string{a, b} {
		if _, stderr, status := runCaptured(t, "prompt", "install", "--file", file, "--yes"); status != 0 {
			t.Fatalf("install %s failed (%d): %s", file, status, stderr)
		}
	}
	if _, stderr, status := runCaptured(t, "prompt", "install", "--file", b, "--agent", "codex", "--yes"); status != 0 {
		t.Fatalf("re-install b with label failed (%d): %s", status, stderr)
	}
	stdout, _, status := runCaptured(t, "prompt", "status")
	if status != 0 || !strings.Contains(stdout, a) || !strings.Contains(stdout, b) || !strings.Contains(stdout, "codex") {
		t.Errorf("status=%d stdout=%q", status, stdout)
	}

	if _, stderr, status := runCaptured(t, "prompt", "uninstall", "--file", a, "--yes"); status != 0 {
		t.Fatalf("uninstall a failed (%d): %s", status, stderr)
	}
	body, _ := os.ReadFile(a)
	if strings.Contains(string(body), prompt.Begin) {
		t.Error("uninstall-leftover marker in a")
	}
	if _, err := os.Stat(a); err != nil {
		t.Error("uninstall removed a; it must keep the file")
	}
	store, _, _ := prompt.LoadStore(dir)
	if _, found := prompt.Find(store.Files, a); found {
		t.Error("uninstall kept a's record")
	}
	if _, found := prompt.Find(store.Files, b); !found {
		t.Error("uninstall removed b's record")
	}

	if _, stderr, status := runCaptured(t, "prompt", "uninstall", "--all", "--yes"); status != 0 {
		t.Fatalf("uninstall --all failed (%d): %s", status, stderr)
	}
	body, _ = os.ReadFile(b)
	if strings.Contains(string(body), prompt.Begin) {
		t.Error("uninstall --all left a marker in b")
	}
	store, _, _ = prompt.LoadStore(dir)
	if len(store.Files) != 0 {
		t.Errorf("records after --all = %+v", store.Files)
	}
}

func TestPromptUpdateTwoFiles(t *testing.T) {
	dir := isolateConfig(t)
	a := homeFile(t, "a.md")
	b := homeFile(t, "b.md")
	for _, file := range []string{a, b} {
		if err := os.WriteFile(file, []byte("前文\n"+oldBlock()+"后文\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if err := prompt.SaveStore(dir, prompt.Store{Schema: 2, Files: []prompt.Record{
		{File: a, Digest: "sha256:0000"},
		{File: b, Digest: "sha256:0000"},
	}}); err != nil {
		t.Fatal(err)
	}

	if _, _, status := runCaptured(t, "prompt", "update"); status != 1 {
		t.Fatalf("update without --yes status = %d, want 1", status)
	}
	if _, stderr, status := runCaptured(t, "prompt", "update", "--yes"); status != 0 {
		t.Fatalf("update --yes failed (%d): %s", status, stderr)
	}
	for _, file := range []string{a, b} {
		body, _ := os.ReadFile(file)
		if !strings.Contains(string(body), string(prompt.Block())) {
			t.Errorf("%s did not get the current block", file)
		}
		if !strings.HasPrefix(string(body), "前文\n") || !strings.HasSuffix(string(body), "后文\n") {
			t.Errorf("%s changed outside the markers", file)
		}
	}
}

func TestPromptUninstallWithoutYesDoesNotWrite(t *testing.T) {
	isolateConfig(t)
	file := homeFile(t, "CLAUDE.md")
	if err := os.WriteFile(file, []byte("前文\n"+string(prompt.Block())+"后文\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	before, _ := os.ReadFile(file)
	stdout, _, status := runCaptured(t, "prompt", "uninstall", "--file", file)
	if status != 1 || !strings.Contains(stdout, "去掉") {
		t.Errorf("status=%d stdout=%q", status, stdout)
	}
	if strings.Contains(stdout, file) || strings.Contains(stdout, "--file") {
		t.Errorf("the plan shows the path or the flag:\n%s", stdout)
	}
	if strings.Contains(stdout, "这些 AI") {
		t.Errorf("removing one AI is described as several:\n%s", stdout)
	}
	after, _ := os.ReadFile(file)
	if !bytes.Equal(before, after) {
		t.Fatal("uninstall without --yes changed the file")
	}
}

func TestPromptAgentIsOnlyALabel(t *testing.T) {
	dir := isolateConfig(t)
	file := homeFile(t, "AGENTS.md")
	if _, stderr, status := runCaptured(t, "prompt", "install", "--file", file, "--agent", "一个随意的名字", "--yes"); status != 0 {
		t.Fatalf("install failed (%d): %s", status, stderr)
	}
	store, _, _ := prompt.LoadStore(dir)
	if len(store.Files) != 1 || store.Files[0].Agent != "一个随意的名字" {
		t.Errorf("record = %+v", store.Files)
	}
	stdout, _, status := runCaptured(t, "prompt", "status")
	if status != 0 {
		t.Fatalf("status = %d, stdout=%s", status, stdout)
	}
	if !strings.Contains(stdout, "一个随意的名字") {
		t.Errorf("status does not show the stored label:\n%s", stdout)
	}
}

func TestAHostThatCannotWriteIsGivenACommandToRerun(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("directory permissions are not how Windows refuses a write")
	}
	if os.Geteuid() == 0 {
		t.Skip("root ignores a read-only directory")
	}
	isolateConfig(t)
	ro := filepath.Join(os.Getenv("HOME"), "readonly")
	if err := os.Mkdir(ro, 0o500); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(ro, 0o700) })
	file := filepath.Join(ro, "AGENTS.md")

	stdout, stderr, status := runCaptured(t, "prompt", "install", "--file", file, "--yes")
	if status == 0 {
		t.Fatalf("install into a read-only directory succeeded:\n%s\n%s", stdout, stderr)
	}
	combined := stdout + stderr
	if !strings.Contains(combined, "在你自己的终端里原样重跑") {
		t.Errorf("no retry instruction:\n%s", combined)
	}
	want := "skillmaster prompt install --file '" + file + "' --yes"
	if !strings.Contains(combined, want) {
		t.Errorf("retry command %q is missing:\n%s", want, combined)
	}
}

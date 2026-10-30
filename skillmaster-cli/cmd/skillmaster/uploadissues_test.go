package main

import (
	"context"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// v33 test-plan C2–C8: the refusal for a package whose names cannot be restored on every system.
// The words are the PRD's (§措辞), so the assertions are the whole line rather than a substring:
// a sentence that drifts by one character is a different promise.

func TestSubmitNameRefusalSaysEveryFileAndWhatToDo(t *testing.T) {
	dir := t.TempDir()
	server := &api.ServerError{
		Code: "invalid_upload",
		DetailList: []api.IssueDetail{
			{Field: "报告:v2.md", Issue: issueInvalidChar},
			{Field: "CON.md", Issue: issueReservedName},
			{Field: "A.md", Issue: issueCaseCollision},
			{Field: "a.md", Issue: issueCaseCollision},
		},
	}

	refused, ok := submitNameRefusal(server, dir)

	if !ok {
		t.Fatal("the refusal was not recognised")
	}
	want := "错误：4 个文件名在 macOS 或 Windows 上不可用；这次提交没有落库。\n" +
		"  报告:v2.md：冒号不能作为文件名（Windows）。改成「报告-v2.md」\n" +
		"  CON.md：CON 是 Windows 保留的设备名。改成「_CON.md」\n" +
		"  A.md 与 a.md：两个名字在 macOS 和 Windows 上会被当成同一个文件。改掉其中一个\n" +
		"改完再提交：skmr skill submit " + dir
	if refused != want {
		t.Errorf("refusal =\n%s\nwant\n%s", refused, want)
	}
}

func TestASuggestionIsNotMadeWhenTheNameIsAlreadyTaken(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "报告-v2.md"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	server := &api.ServerError{
		Code:       "invalid_upload",
		DetailList: []api.IssueDetail{{Field: "报告:v2.md", Issue: issueInvalidChar}},
	}

	refused, _ := submitNameRefusal(server, dir)

	if strings.Contains(refused, "改成") {
		t.Errorf("refusal offered a name that already exists:\n%s", refused)
	}
	if !strings.Contains(refused, "  报告:v2.md：冒号不能作为文件名（Windows）。\n") {
		t.Errorf("refusal did not name the character and the reason:\n%s", refused)
	}
}

func TestAReservedNameSuggestionIsDroppedWhenItIsTaken(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "_CON.md"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	server := &api.ServerError{
		Code:       "invalid_upload",
		DetailList: []api.IssueDetail{{Field: "CON.md", Issue: issueReservedName}},
	}

	refused, _ := submitNameRefusal(server, dir)

	if strings.Contains(refused, "改成") {
		t.Errorf("refusal offered a name that already exists:\n%s", refused)
	}
	if !strings.Contains(refused, "CON.md：CON 是 Windows 保留的设备名。换一个名字") {
		t.Errorf("refusal = %s", refused)
	}
}

func TestWriteNameRefusalIsOneSentenceAboutOneFile(t *testing.T) {
	server := &api.ServerError{
		Code:       "invalid_path",
		DetailList: []api.IssueDetail{{Field: "CON.md", Issue: issueReservedName}},
	}

	refused, ok := writeNameRefusal(server)

	if !ok {
		t.Fatal("the refusal was not recognised")
	}
	want := "错误：这个文件名在 macOS 或 Windows 上不可用；草稿没有改动。\n" +
		"  CON.md：CON 是 Windows 保留的设备名。换一个名字\n" +
		"改完再写一次。"
	if refused != want {
		t.Errorf("refusal =\n%s\nwant\n%s", refused, want)
	}
}

func TestMoreThanTenProblemsAreCountedRatherThanListed(t *testing.T) {
	details := make([]api.IssueDetail, 0, 13)
	for index := 0; index < 13; index++ {
		details = append(details, api.IssueDetail{
			Field: string(rune('a'+index)) + "?.md", Issue: issueInvalidChar,
		})
	}
	server := &api.ServerError{Code: "invalid_upload", DetailList: details}

	refused, _ := submitNameRefusal(server, ".")

	lines := strings.Split(refused, "\n")
	if len(lines) != 1+10+1+1 {
		t.Fatalf("printed %d lines:\n%s", len(lines), refused)
	}
	if lines[11] != "  还有 3 个。" {
		t.Errorf("count line = %q", lines[11])
	}
}

func TestANameThatCouldNotBeDecodedHasNoPathToShow(t *testing.T) {
	server := &api.ServerError{
		Code:       "invalid_upload",
		DetailList: []api.IssueDetail{{Field: "", Issue: issueZipName}},
	}

	refused, _ := submitNameRefusal(server, ".")

	want := "错误：1 个文件名在 macOS 或 Windows 上不可用；这次提交没有落库。\n" +
		"  有一个文件名解不出来（压缩包不是 UTF-8 编码）。用 UTF-8 重新打包\n" +
		"改完再提交：skmr skill submit ."
	if refused != want {
		t.Errorf("refusal =\n%s\nwant\n%s", refused, want)
	}
}

func TestCollisionPairIsOneLineNotTwo(t *testing.T) {
	problems := nameProblems([]api.IssueDetail{
		{Field: "A.md", Issue: issueCaseCollision},
		{Field: "a.md", Issue: issueCaseCollision},
	})
	if len(problems) != 1 || len(problems[0].paths) != 2 {
		t.Fatalf("problems = %#v", problems)
	}
}

func TestDroppedFilesAreNamedOnSuccess(t *testing.T) {
	line := droppedLine([]string{".DS_Store", "__MACOSX/._SKILL.md"})
	if line != "  忽略了 2 个不是 skill 内容的文件：.DS_Store、__MACOSX/._SKILL.md" {
		t.Errorf("line = %q", line)
	}
	if droppedLine(nil) != "" {
		t.Error("nothing dropped should print nothing")
	}
}

// The whole command path: a refused package reaches the person as these lines, not as the generic
// `服务端答 400`.
func TestASubmitRefusedForItsNamesPrintsTheNameLines(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":{"code":"invalid_upload","message":"no",` +
			`"details":[{"field":"CON.md","issue":"windows_reserved_name"}]}}`))
	})
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "SKILL.md"),
		[]byte("---\nname: pdf-tools\ndescription: d\n---\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	err := submitSkill(context.Background(), []string{dir})

	if err == nil {
		t.Fatal("a refused submission reported success")
	}
	want := "错误：1 个文件名在 macOS 或 Windows 上不可用；这次提交没有落库。\n" +
		"  CON.md：CON 是 Windows 保留的设备名。改成「_CON.md」\n" +
		"改完再提交：skmr skill submit " + dir
	if err.Error() != want {
		t.Errorf("command error =\n%s\nwant\n%s", err.Error(), want)
	}
}

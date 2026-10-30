package main

// v32's first phase: `skill edit` takes its text from files, shows what it changed, and says what
// went wrong when the text is not there.
//
// These run through `run`, so what they assert is the process result — stdout, stderr, exit status,
// and whether a PUT was sent — rather than a helper's return value.

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// newEditFake answers the three requests a remote edit makes: the manifest, the file, and the write.
func newEditFake(t *testing.T, content string) *fakeServer {
	t.Helper()
	return newFakeServer(t, func(r recordedRequest) (int, string) {
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.Path, "/files"):
			return http.StatusOK, `{"files":[{"relpath":"SKILL.md","is_binary":false,"size":1}]}`
		case r.Method == http.MethodGet && strings.HasSuffix(r.Path, "/files/SKILL.md"):
			return http.StatusOK, content
		case r.Method == http.MethodPut && strings.HasSuffix(r.Path, "/files/SKILL.md"):
			return http.StatusOK, `{"version":{"code":"K3M9QX2P","digest":"sha256:abcdef1234567890"}}`
		}
		return http.StatusNotFound, `{"error":{"code":"not_found","message":"no"}}`
	})
}

// putBody is the content of the one PUT the fake saw, or "" when none was sent.
func putBody(fake *fakeServer) string {
	for _, request := range fake.requests {
		if request.Method == http.MethodPut {
			return request.Body
		}
	}
	return ""
}

func writeTemp(t *testing.T, name, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("write %s: %v", name, err)
	}
	return path
}

func TestEditTakesItsTextFromFilesAndShowsTheHunk(t *testing.T) {
	// Nine lines with the match in the middle, so the three lines of context on each side are
	// actually exercised rather than inferred.
	content := "one\ntwo\nthree\nfour\nnpm install\nsix\nseven\neight\nnine\n"
	fake := newEditFake(t, content)
	oldPath := writeTemp(t, "old.txt", "npm install")
	newPath := writeTemp(t, "new.txt", "npm ci")

	stdout, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old-file", oldPath, "--new-file", newPath)

	if status != 0 {
		t.Fatalf("status = %d, stderr = %s", status, stderr)
	}
	if stdout != "" {
		t.Fatalf("stdout = %q, want empty", stdout)
	}
	if got := putBody(fake); got != "one\ntwo\nthree\nfour\nnpm ci\nsix\nseven\neight\nnine\n" {
		t.Fatalf("PUT body = %q", got)
	}
	want := "已修改 SKILL.md（abcdef12）。\n" +
		"@@ -2,7 +2,7 @@\n" +
		" two\n" +
		" three\n" +
		" four\n" +
		"-npm install\n" +
		"+npm ci\n" +
		" six\n" +
		" seven\n" +
		" eight\n" +
		"（这一版现在是 me/hello@K3M9QX2P；再读拿到的就是这份内容。）\n"
	if stderr != want {
		t.Fatalf("stderr =\n%s\nwant\n%s", stderr, want)
	}
}

func TestEditRefusesTwoSourcesForOneSide(t *testing.T) {
	fake := newEditFake(t, "alpha\n")
	path := writeTemp(t, "old.txt", "alpha")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "alpha", "--old-file", path, "--new", "beta")
	if status != 2 {
		t.Fatalf("status = %d, want 2 (stderr = %s)", status, stderr)
	}
	if !strings.Contains(stderr, "--old 与 --old-file 只给一个") {
		t.Fatalf("stderr = %q", stderr)
	}
	if body := putBody(fake); body != "" {
		t.Fatalf("a PUT was sent: %q", body)
	}
}

func TestEditRefusesANonUTF8File(t *testing.T) {
	fake := newEditFake(t, "alpha\n")
	path := filepath.Join(t.TempDir(), "bad.txt")
	if err := os.WriteFile(path, []byte{0xff, 0xfe}, 0o600); err != nil {
		t.Fatal(err)
	}
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old-file", path, "--new", "beta")
	if status != 1 {
		t.Fatalf("status = %d, want 1 (stderr = %s)", status, stderr)
	}
	if !strings.Contains(stderr, "不是 UTF-8") {
		t.Fatalf("stderr = %q", stderr)
	}
	if body := putBody(fake); body != "" {
		t.Fatalf("a PUT was sent: %q", body)
	}
}

// The bug this version exists for: a caller copied `read`'s rendering, numbers and all. The refusal
// has to name that, because "not found" sends the caller back to `read` — the same rendering.
func TestEditNamesCopiedLineNumbers(t *testing.T) {
	fake := newEditFake(t, "alpha\nnpm install\nomega\n")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "2\tnpm install", "--new", "npm ci")
	if status == 0 {
		t.Fatal("line-numbered text was accepted")
	}
	if !strings.Contains(stderr, "带着 read 的行号") ||
		!strings.Contains(stderr, "skmr skill cat me/hello@K3M9QX2P SKILL.md") {
		t.Fatalf("stderr = %q", stderr)
	}
	if body := putBody(fake); body != "" {
		t.Fatalf("a PUT was sent: %q", body)
	}
}

// One numbered line saved with a trailing newline is still one line: it must not be blamed on
// `read`, because `--old-file` almost always carries that newline.
func TestEditDoesNotBlameAReadForOneNumberedLine(t *testing.T) {
	newEditFake(t, "alpha\nnpm install\nomega\n")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "2\tnpm instxl\n", "--new", "npm ci")
	if status == 0 {
		t.Fatal("a missing line was accepted")
	}
	if strings.Contains(stderr, "带着 read 的行号") {
		t.Fatalf("a single numbered typo was read as a copied rendering: %q", stderr)
	}
	if !strings.Contains(stderr, "最接近的是第 2 行") {
		t.Fatalf("stderr = %q", stderr)
	}
}

// A rendering copied whole carries the CLI's own note; that note is what marks it, even when the
// content was one line.
func TestEditNamesCopiedLineNumbersWithAFooter(t *testing.T) {
	newEditFake(t, "alpha\nnpm install\nomega\n")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "2\tnpm install\n（按 me/hello@1.0.0 取。）\n", "--new", "npm ci")
	if status == 0 {
		t.Fatal("a copied rendering was accepted")
	}
	if !strings.Contains(stderr, "带着 read 的行号") {
		t.Fatalf("stderr = %q", stderr)
	}
}

// Several consecutive numbered lines are a copied window even when their text has since changed.
func TestEditNamesCopiedLineNumbersWhenTheTextMoved(t *testing.T) {
	newEditFake(t, "1\talpha\n2\tbeta\n3\tgamma\n")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "1\talpha\n2\tdelta", "--new", "x")
	if status == 0 {
		t.Fatal("a missing block was accepted")
	}
	if !strings.Contains(stderr, "带着 read 的行号") {
		t.Fatalf("stderr = %q", stderr)
	}
}

// Content that legitimately starts with a number and a tab must still be replaceable.
func TestEditStillMatchesContentThatLooksNumbered(t *testing.T) {
	fake := newEditFake(t, "3\tfoo\nbar\n")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "3\tfoo", "--new", "3\tbaz")
	if status != 0 {
		t.Fatalf("status = %d, stderr = %s", status, stderr)
	}
	if got := putBody(fake); got != "3\tbaz\nbar\n" {
		t.Fatalf("PUT body = %q", got)
	}
}

func TestEditSpotsWhitespaceOnlyDifference(t *testing.T) {
	fake := newEditFake(t, "alpha\nnpm install\nomega\n")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "npm install ", "--new", "npm ci")
	if status == 0 {
		t.Fatal("a whitespace-only difference was applied")
	}
	if !strings.Contains(stderr, "只差空白") || !strings.Contains(stderr, "第 2 行") ||
		!strings.Contains(stderr, "npm install") {
		t.Fatalf("stderr = %q", stderr)
	}
	if body := putBody(fake); body != "" {
		t.Fatalf("a PUT was sent: %q", body)
	}
}

// The trimmed text also appears inside a longer line; the whole-line match is still the answer.
func TestEditPrefersTheLineAlignedWhitespaceMatch(t *testing.T) {
	newEditFake(t, "npm install\nnpm install2\n")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "npm install ", "--new", "npm ci")
	if status == 0 {
		t.Fatal("a whitespace-only difference was applied")
	}
	if !strings.Contains(stderr, "只差空白：第 1 行") {
		t.Fatalf("stderr = %q", stderr)
	}
}

func TestEditPointsAtTheClosestLine(t *testing.T) {
	newEditFake(t, "alpha\nnpm install\nomega\n")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "npm instxl", "--new", "npm ci")
	if status == 0 {
		t.Fatal("a missing line was accepted")
	}
	if !strings.Contains(stderr, "最接近的是第 2 行") ||
		!strings.Contains(stderr, "npm install") {
		t.Fatalf("stderr = %q", stderr)
	}
}

// Below the 0.6 threshold the command says only that it found nothing — a "closest" line that is
// nothing like the input would send the caller to the wrong place.
func TestEditFallsBackWhenNothingIsClose(t *testing.T) {
	newEditFake(t, "alpha\nnpm install\nomega\n")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "zzzzzzzzzzzz", "--new", "x")
	if status == 0 {
		t.Fatal("a missing line was accepted")
	}
	if strings.Contains(stderr, "最接近的是") {
		t.Fatalf("unrelated line reported as closest: %q", stderr)
	}
	if !strings.Contains(stderr, "没有找到要替换的文本") ||
		!strings.Contains(stderr, "skmr skill cat me/hello@K3M9QX2P SKILL.md") {
		t.Fatalf("stderr = %q", stderr)
	}
}

func TestEditListsEveryOccurrence(t *testing.T) {
	fake := newEditFake(t, "same\nalpha\nsame\nomega\nsame\n")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "same", "--new", "other")
	if status == 0 {
		t.Fatal("several matches without --replace-all were accepted")
	}
	if !strings.Contains(stderr, "第 1、3、5 行") {
		t.Fatalf("stderr = %q", stderr)
	}

	_, stderr, status = runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "same", "--new", "other", "--replace-all")
	if status != 0 {
		t.Fatalf("--replace-all: status = %d, stderr = %s", status, stderr)
	}
	if got := putBody(fake); got != "other\nalpha\nother\nomega\nother\n" {
		t.Fatalf("PUT body = %q", got)
	}

	// Two hits on one line are one place to look, not two.
	newEditFake(t, "same same\nalpha\n")
	_, stderr, _ = runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "same", "--new", "other")
	if !strings.Contains(stderr, "第 1 行") || strings.Contains(stderr, "第 1、1 行") {
		t.Fatalf("stderr = %q", stderr)
	}
}

// A file saved by any editor ends with a newline; `--old-file` therefore almost always carries one.
// The first implementation indexed past the line it belonged to and panicked here.
func TestEditAcceptsOldTextWithItsOwnLineEnding(t *testing.T) {
	fake := newEditFake(t, "alpha\nnpm install\nomega\n")
	oldPath := writeTemp(t, "old.txt", "npm install\n")
	newPath := writeTemp(t, "new.txt", "npm ci\n")

	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old-file", oldPath, "--new-file", newPath)
	if status != 0 {
		t.Fatalf("status = %d, stderr = %s", status, stderr)
	}
	if got := putBody(fake); got != "alpha\nnpm ci\nomega\n" {
		t.Fatalf("PUT body = %q", got)
	}
	if !strings.Contains(stderr, "-npm install\n+npm ci") {
		t.Fatalf("stderr = %q", stderr)
	}
}

// Every occurrence must be drawn as a change. The first implementation rendered the line holding the
// second occurrence as unchanged context once the first hunk's context reached it.
func TestEditEchoesEveryOccurrenceOfReplaceAll(t *testing.T) {
	newEditFake(t, "same\nalpha\nsame\nomega\nsame\n")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "same", "--new", "other", "--replace-all")
	if status != 0 {
		t.Fatalf("status = %d, stderr = %s", status, stderr)
	}
	if got := strings.Count(stderr, "-same"); got != 3 {
		t.Fatalf("removed lines = %d, want 3:\n%s", got, stderr)
	}
	if got := strings.Count(stderr, "+other"); got != 3 {
		t.Fatalf("added lines = %d, want 3:\n%s", got, stderr)
	}
	if strings.Contains(stderr, "\n same\n") {
		t.Fatalf("an occurrence was drawn as unchanged context:\n%s", stderr)
	}
}

// Two occurrences on one line are one changed line in the echo.
func TestEditEchoesTwoMatchesOnOneLine(t *testing.T) {
	fake := newEditFake(t, "same same\nalpha\n")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "same", "--new", "other", "--replace-all")
	if status != 0 {
		t.Fatalf("status = %d, stderr = %s", status, stderr)
	}
	if got := putBody(fake); got != "other other\nalpha\n" {
		t.Fatalf("PUT body = %q", got)
	}
	if !strings.Contains(stderr, "-same same\n+other other") {
		t.Fatalf("stderr = %q", stderr)
	}
}

// Deleting the whole matched text empties the line but keeps it; the echo has to say that, not claim
// the file lost a line.
func TestEditEchoesADeletedLineAsAnEmptyOne(t *testing.T) {
	fake := newEditFake(t, "a\nfoo\nb\n")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "foo", "--new", "")
	if status != 0 {
		t.Fatalf("status = %d, stderr = %s", status, stderr)
	}
	if got := putBody(fake); got != "a\n\nb\n" {
		t.Fatalf("PUT body = %q", got)
	}
	if !strings.Contains(stderr, "@@ -1,3 +1,3 @@\n a\n-foo\n+\n b") {
		t.Fatalf("stderr = %q", stderr)
	}
}

// `@new` without a name is an incomplete command, not a failed request: the same class as
// `skill write @new` without `--name`.
func TestEditOnNewDraftWithoutANameIsAUsageError(t *testing.T) {
	fake := newEditFake(t, "alpha\n")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@new", "SKILL.md",
		"--old", "alpha", "--new", "beta")
	if status != 2 {
		t.Fatalf("status = %d, want 2 (stderr = %s)", status, stderr)
	}
	if !strings.Contains(stderr, "用法见：skmr skill edit --help") {
		t.Fatalf("stderr = %q", stderr)
	}
	if body := putBody(fake); body != "" {
		t.Fatalf("a PUT was sent: %q", body)
	}
}

func TestEditRefusesAnEmptyOldFileAsAUsageError(t *testing.T) {
	fake := newEditFake(t, "alpha\n")
	empty := writeTemp(t, "empty.txt", "")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old-file", empty, "--new", "x")
	if status != 2 {
		t.Fatalf("status = %d, want 2 (stderr = %s)", status, stderr)
	}
	if !strings.Contains(stderr, "是空的") || !strings.Contains(stderr, "用法见：skmr skill edit --help") {
		t.Fatalf("stderr = %q", stderr)
	}
	if body := putBody(fake); body != "" {
		t.Fatalf("a PUT was sent: %q", body)
	}
}

func TestEditRefusesAMissingOldFile(t *testing.T) {
	fake := newEditFake(t, "alpha\n")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old-file", filepath.Join(t.TempDir(), "absent.txt"), "--new", "x")
	if status != 1 {
		t.Fatalf("status = %d, want 1 (stderr = %s)", status, stderr)
	}
	if !strings.Contains(stderr, "失败") {
		t.Fatalf("stderr = %q", stderr)
	}
	if body := putBody(fake); body != "" {
		t.Fatalf("a PUT was sent: %q", body)
	}
}

// The @new path creates the draft and must then name it in both the truncation hint and the status
// sentence; an empty code there produced `skmr version diff me/hello@`.
func TestEditOnNewDraftEchoCarriesTheCreatedCode(t *testing.T) {
	lines := make([]string, 80)
	for i := range lines {
		lines[i] = "line"
	}
	content := strings.Join(lines, "\n") + "\n"
	newFakeServer(t, func(r recordedRequest) (int, string) {
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.Path, "/draft-base"):
			return http.StatusOK, `{"base":{"pin":"1.0.0","digest":"sha256:abc"}}`
		case r.Method == http.MethodGet && strings.Contains(r.Path, "/files/"):
			return http.StatusOK, content
		case r.Method == http.MethodPost && strings.HasSuffix(r.Path, "/versions"):
			return http.StatusCreated, `{"version":{"code":"K3M9QX2P","state":"draft"}}`
		}
		return http.StatusNotFound, `{"error":{"code":"not_found","message":"no"}}`
	})
	old := strings.Join(lines[:70], "\n")
	stdout, stderr, status := runCaptured(t, "skill", "edit", "me/hello@new", "SKILL.md",
		"--name", "第二版", "--old", old, "--new", "one")
	if status != 0 {
		t.Fatalf("status = %d, stderr = %s", status, stderr)
	}
	if stdout != "" {
		t.Fatalf("stdout = %q, want empty", stdout)
	}
	// **The whole hint, not just the code**: the create message already prints
	// `下一步：skmr version diff …`, so a bare code check would pass with no hint at all.
	if !strings.Contains(stderr, "只显示前") ||
		!strings.Contains(stderr, "完整改动：skmr version diff me/hello@K3M9QX2P") {
		t.Fatalf("truncation hint lost the code:\n%s", stderr)
	}
	if !strings.Contains(stderr, "（这一版现在是 me/hello@K3M9QX2P") {
		t.Fatalf("status sentence missing or unnamed:\n%s", stderr)
	}
	created := strings.Index(stderr, "已修改并建出草稿")
	hunk := strings.Index(stderr, "@@")
	statusLine := strings.Index(stderr, "这一版现在是")
	if created < 0 || hunk < 0 || statusLine < 0 || !(created < hunk && hunk < statusLine) {
		t.Fatalf("echo order is not message → hunk → status:\n%s", stderr)
	}
}

// More than six lines of hits are listed as six plus the total, which is where to look rather than
// how many replacements `--replace-all` would make.
func TestEditCountsLinesSeparatelyFromOccurrences(t *testing.T) {
	newEditFake(t, "a\nb\na\nb\na\nb\na\nb\na\nb\na\nb\na\nb\n")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "a", "--new", "c")
	if status == 0 {
		t.Fatal("several matches without --replace-all were accepted")
	}
	if !strings.Contains(stderr, "出现了 7 次") || !strings.Contains(stderr, "等 7 行") {
		t.Fatalf("stderr = %q", stderr)
	}
	if !strings.Contains(stderr, "第 1、3、5、7、9、11 行") {
		t.Fatalf("stderr = %q", stderr)
	}
}

// A first hunk that lands exactly on the cap used to be followed by silence, not by the hint.
func TestEditSaysWhenTheEchoWasCutAtTheBoundary(t *testing.T) {
	block := make([]string, 27)
	for i := range block {
		block[i] = "block"
	}
	gap := make([]string, 10)
	pre := []string{"pre1", "pre2", "pre3"}
	content := strings.Join(append(append(append(pre, block...), gap...), block...), "\n") + "\n"
	newEditFake(t, content)
	replacement := make([]string, 26)
	for i := range replacement {
		replacement[i] = "new"
	}

	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", strings.Join(block, "\n"), "--new", strings.Join(replacement, "\n"), "--replace-all")
	if status != 0 {
		t.Fatalf("status = %d, stderr = %s", status, stderr)
	}
	if !strings.Contains(stderr, "只显示前 60 行") {
		t.Fatalf("the second block was dropped without saying so:\n%s", stderr)
	}
}

func TestEditSaysWhenOnlyLineEndingsChanged(t *testing.T) {
	fake := newEditFake(t, "alpha\nb")
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "b", "--new", "b\n")
	if status != 0 {
		t.Fatalf("status = %d, stderr = %s", status, stderr)
	}
	if got := putBody(fake); got != "alpha\nb\n" {
		t.Fatalf("PUT body = %q", got)
	}
	if !strings.Contains(stderr, "只改了行尾") {
		t.Fatalf("stderr = %q", stderr)
	}
}

// The write carries If-Match; a stale baseline is refused with the sentence that says so.
func TestEditReportsAStaleBaseline(t *testing.T) {
	newFakeServer(t, func(r recordedRequest) (int, string) {
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.Path, "/files"):
			return http.StatusOK, `{"files":[{"relpath":"SKILL.md","is_binary":false}]}`
		case r.Method == http.MethodGet && strings.HasSuffix(r.Path, "/files/SKILL.md"):
			return http.StatusOK, "alpha\n"
		case r.Method == http.MethodPut:
			return http.StatusConflict, `{"error":{"code":"digest_mismatch","message":"stale"}}`
		}
		return http.StatusNotFound, `{"error":{"code":"not_found","message":"no"}}`
	})
	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", "alpha", "--new", "beta")
	if status != 1 {
		t.Fatalf("status = %d, want 1 (stderr = %s)", status, stderr)
	}
	if !strings.Contains(stderr, "被改动过") {
		t.Fatalf("stderr = %q", stderr)
	}
}

func TestEditCapsTheEchoAndPointsAtTheDiff(t *testing.T) {
	lines := make([]string, 80)
	for i := range lines {
		lines[i] = "line"
	}
	content := strings.Join(lines, "\n") + "\n"
	newEditFake(t, content)
	old := strings.Join(lines[:70], "\n")

	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--old", old, "--new", "one")
	if status != 0 {
		t.Fatalf("status = %d, stderr = %s", status, stderr)
	}
	if !strings.Contains(stderr, "只显示前 60 行") ||
		!strings.Contains(stderr, "skmr version diff me/hello@K3M9QX2P") {
		t.Fatalf("stderr = %q", stderr)
	}
}

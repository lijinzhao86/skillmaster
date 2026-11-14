package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/feedback"
)

func TestFeedbackAttachCopiesAMessagePathAndPrintsJSON(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("TMPDIR", t.TempDir())

	stdout, stderr, status := runCaptured(t,
		"feedback", "attach", "--copy", "testdata/attachment.png", "--json")
	if status != 0 {
		t.Fatalf("status %d: %s", status, stderr)
	}
	var result feedbackAttachResult
	if err := json.Unmarshal([]byte(stdout), &result); err != nil {
		t.Fatalf("stdout is not one JSON object: %v\n%s", err, stdout)
	}
	if result.Source != "message_path" {
		t.Errorf("source = %q, want message_path", result.Source)
	}
	if result.MediaType != "image/png" || result.SizeBytes != 68 {
		t.Errorf("media = %q, %d bytes", result.MediaType, result.SizeBytes)
	}
	if result.SHA256 == "" || result.OriginalName != "attachment.png" {
		t.Errorf("result = %+v", result)
	}
	info, err := os.Stat(result.Path)
	if err != nil {
		t.Fatalf("the stable copy does not exist: %v", err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Errorf("mode = %o, want 600", info.Mode().Perm())
	}
	if !strings.HasPrefix(filepath.Base(result.Path), "attachment-") {
		t.Errorf("stable name %q does not keep the original stem", result.Path)
	}
	if _, err := os.Stat("testdata/attachment.png"); err != nil {
		t.Errorf("the source was removed: %v", err)
	}
}

func TestFeedbackAttachNeedsExactlyOneSource(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("TMPDIR", t.TempDir())

	_, stderr, status := runCaptured(t, "feedback", "attach",
		"--copy", "testdata/attachment.png", "--last-paste", "--json")
	if status != 2 {
		t.Fatalf("status %d, want 2 (stderr %q)", status, stderr)
	}
	if !strings.Contains(stderr, "要选一个附件来源") {
		t.Errorf("stderr = %q", stderr)
	}
}

func TestFeedbackAttachRejectsMaxAgeWithoutLastPaste(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("TMPDIR", t.TempDir())

	_, stderr, status := runCaptured(t, "feedback", "attach",
		"--copy", "testdata/attachment.png", "--max-age", "2h", "--json")
	if status != 2 {
		t.Fatalf("status %d, want 2 (stderr %q)", status, stderr)
	}
	if !strings.Contains(stderr, "--max-age 只对 --last-paste") {
		t.Errorf("stderr = %q", stderr)
	}
}

func TestFeedbackAttachRefusesAnUnsupportedFile(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("TMPDIR", t.TempDir())
	source := filepath.Join(t.TempDir(), "fake.png")
	if err := os.WriteFile(source, []byte("<svg xmlns=\"http://www.w3.org/2000/svg\"/>"), 0o600); err != nil {
		t.Fatal(err)
	}

	_, stderr, status := runCaptured(t, "feedback", "attach", "--copy", source, "--json")
	if status != 1 {
		t.Fatalf("status %d, want 1 (stderr %q)", status, stderr)
	}
	if !strings.Contains(stderr, "允许的附件类型") {
		t.Errorf("stderr = %q", stderr)
	}
}

func TestAttachErrorNamesTheNextStep(t *testing.T) {
	cases := []struct {
		err  error
		want string
	}{
		{feedback.ErrNoPaste, "拖进对话"},
		{feedback.ErrClipboardUnsupported, "不支持读取剪贴板"},
		{feedback.ErrUnsupportedType, "允许的附件类型"},
	}
	for _, testCase := range cases {
		err := attachError(testCase.err)
		if err == nil || !strings.Contains(err.Error(), testCase.want) {
			t.Errorf("attachError(%v) = %v, want a sentence containing %q",
				testCase.err, err, testCase.want)
		}
	}
	var login *refusalError
	if !errors.As(attachError(feedback.ErrNoPaste), &login) {
		t.Error("a missing paste should be a refusal (exit 1), not a usage error")
	}
}

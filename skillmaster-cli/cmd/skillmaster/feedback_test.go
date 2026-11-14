package main

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestFeedbackTemplateIsLocalAndComplete(t *testing.T) {
	stdout, stderr, status := runCaptured(t, "feedback", "template")
	if status != 0 {
		t.Fatalf("template status %d, stderr %q", status, stderr)
	}
	for _, section := range []string{
		"我想做什么", "发生了什么", "怎么复现", "环境", "已经试过什么", "不要写",
		"附件", "--attach", "不会被自动扫描",
	} {
		if !strings.Contains(stdout, section) {
			t.Errorf("template is missing %q:\n%s", section, stdout)
		}
	}
	if strings.TrimSpace(stderr) != "" {
		t.Errorf("template wrote to stderr: %q", stderr)
	}
}

func TestFeedbackPreviewSendsNothing(t *testing.T) {
	posted := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			posted++
		}
		if r.URL.Path == "/api/v1/feedback/consent" {
			_, _ = io.WriteString(w, `{"version":"1.0","notice":"同意说明"}`)
		}
	})
	file := writeFeedbackFile(t, "文档内容")

	stdout, _, status := runCaptured(t, "feedback", "send", "--file", file)
	if status == 0 {
		t.Fatalf("preview exited 0; it must refuse until --consent")
	}
	if posted != 0 {
		t.Fatalf("preview posted %d times; it must not send", posted)
	}
	if !strings.Contains(stdout, "文档内容") || !strings.Contains(stdout, "同意说明") {
		t.Errorf("preview did not show the document and the notice:\n%s", stdout)
	}
	if !strings.Contains(stdout, "将发送的字段") {
		t.Errorf("preview did not list the fields being sent:\n%s", stdout)
	}
}

func TestFeedbackPreviewUnderJSONKeepsStdoutEmpty(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/feedback/consent" {
			_, _ = io.WriteString(w, `{"version":"1.0","notice":"同意说明"}`)
		}
	})
	file := writeFeedbackFile(t, "文档内容")

	stdout, stderr, status := runCaptured(t, "feedback", "send", "--file", file, "--json")
	if status == 0 {
		t.Fatalf("preview exited 0; it must refuse until --consent")
	}
	if strings.TrimSpace(stdout) != "" {
		t.Errorf("--json left non-JSON text on stdout: %q", stdout)
	}
	if !strings.Contains(stderr, "文档内容") || !strings.Contains(stderr, "同意说明") {
		t.Errorf("the preview did not move to stderr:\n%s", stderr)
	}
	if !strings.Contains(stderr, `"ok":false`) {
		t.Errorf("the failure was not the JSON object:\n%s", stderr)
	}
}

func TestFeedbackUsageFailureHonoursJSONAfterTheBadFlag(t *testing.T) {
	_, stderr, status := runCaptured(t, "feedback", "send", "--no-such-flag", "--json")
	if status != 2 {
		t.Fatalf("usage failure status %d, stderr %q", status, stderr)
	}
	if !strings.Contains(stderr, `"ok":false`) {
		t.Errorf("the failure was not the JSON object:\n%s", stderr)
	}
}

func TestFeedbackSendWithConsentPostsTheDocument(t *testing.T) {
	var body string
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/v1/feedback/consent":
			_, _ = io.WriteString(w, `{"version":"1.0","notice":"同意说明"}`)
		case r.URL.Path == "/api/v1/feedback" && r.Method == http.MethodPost:
			raw, _ := io.ReadAll(r.Body)
			body = string(raw)
			w.WriteHeader(http.StatusCreated)
			_, _ = io.WriteString(w, `{"receipt":"FB-20261113-ABC123","created_at":"2026-11-13T01:00:00Z"}`)
		}
	})
	file := writeFeedbackFile(t, "文档内容")

	stdout, stderr, status := runCaptured(t, "feedback", "send", "--file", file, "--consent")
	if status != 0 {
		t.Fatalf("send status %d, stderr %q", status, stderr)
	}
	if !strings.Contains(stdout, "FB-20261113-ABC123") {
		t.Errorf("no receipt on stdout:\n%s", stdout)
	}
	if !strings.Contains(body, `"consent_version":"1.0"`) {
		t.Errorf("the request did not carry the consent version: %s", body)
	}
	if !strings.Contains(body, `"content":"文档内容"`) {
		t.Errorf("the request did not carry the document: %s", body)
	}
	if strings.Contains(body, `"diagnostics"`) {
		t.Errorf("a submission without --attach-diagnostics carried diagnostics: %s", body)
	}
}

func TestFeedbackAttachDiagnosticsCarriesOnlyTheWhitelistedFields(t *testing.T) {
	var body struct {
		Diagnostics map[string]string `json:"diagnostics"`
	}
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/v1/feedback/consent":
			_, _ = io.WriteString(w, `{"version":"1.0","notice":"同意说明"}`)
		case r.URL.Path == "/api/v1/feedback" && r.Method == http.MethodPost:
			raw, _ := io.ReadAll(r.Body)
			_ = json.Unmarshal(raw, &body)
			w.WriteHeader(http.StatusCreated)
			_, _ = io.WriteString(w, `{"receipt":"FB-20261113-DIAG01","created_at":"2026-11-13T01:00:00Z"}`)
		}
	})
	file := writeFeedbackFile(t, "文档内容")

	_, stderr, status := runCaptured(t, "feedback", "send", "--file", file, "--consent",
		"--attach-diagnostics", "--command", "skmr skill invoke alice/thing", "--error-code", "skill_deleted")
	if status != 0 {
		t.Fatalf("status %d, stderr %q", status, stderr)
	}
	for _, key := range []string{"cli_version", "os", "arch", "at", "command", "error_code"} {
		if body.Diagnostics[key] == "" {
			t.Errorf("diagnostics are missing %q: %v", key, body.Diagnostics)
		}
	}
	if len(body.Diagnostics) != 6 {
		t.Errorf("extra diagnostic keys travelled: %v", body.Diagnostics)
	}
	if body.Diagnostics["command"] != "skmr" {
		t.Errorf("the command kept its arguments: %q", body.Diagnostics["command"])
	}
}

func TestFeedbackFromStandardInput(t *testing.T) {
	var body string
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/v1/feedback/consent":
			_, _ = io.WriteString(w, `{"version":"1.0","notice":"同意说明"}`)
		case r.URL.Path == "/api/v1/feedback" && r.Method == http.MethodPost:
			raw, _ := io.ReadAll(r.Body)
			body = string(raw)
			w.WriteHeader(http.StatusCreated)
			_, _ = io.WriteString(w, `{"receipt":"FB-20261113-STDIN1","created_at":"2026-11-13T01:00:00Z"}`)
		}
	})
	oldStdin, oldTerminal := stdin, stdinIsTerminal
	stdin = strings.NewReader("标准输入的文档")
	stdinIsTerminal = func() bool { return false }
	t.Cleanup(func() {
		stdin = oldStdin
		stdinIsTerminal = oldTerminal
	})

	_, stderr, status := runCaptured(t, "feedback", "send", "--consent")
	if status != 0 {
		t.Fatalf("stdin send status %d, stderr %q", status, stderr)
	}
	if !strings.Contains(body, `"content":"标准输入的文档"`) {
		t.Errorf("the request did not carry the piped document: %s", body)
	}
}

func TestFeedbackSensitiveContentRefusesAndPostsNothing(t *testing.T) {
	posted := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			posted++
		}
		if r.URL.Path == "/api/v1/feedback/consent" {
			_, _ = io.WriteString(w, `{"version":"1.0","notice":"同意说明"}`)
		}
	})
	file := writeFeedbackFile(t, "手机号 13800138000")

	_, stderr, status := runCaptured(t, "feedback", "send", "--file", file, "--consent")
	if status != 1 {
		t.Fatalf("sensitive content status %d, stderr %q", status, stderr)
	}
	if posted != 0 {
		t.Fatalf("sensitive content posted %d times", posted)
	}
	if !strings.Contains(stderr, "手机号") || !strings.Contains(stderr, "--allow-sensitive") {
		t.Errorf("the refusal did not name the category and the override: %q", stderr)
	}
}

func TestFeedbackPreviewStillShowsASensitiveDocument(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/feedback/consent" {
			_, _ = io.WriteString(w, `{"version":"1.0","notice":"同意说明"}`)
		}
	})
	file := writeFeedbackFile(t, "手机号 13800138000")

	stdout, _, status := runCaptured(t, "feedback", "send", "--file", file)
	if status != 1 {
		t.Fatalf("preview status %d", status)
	}
	if !strings.Contains(stdout, "13800138000") {
		t.Errorf("the document was withheld from the preview it exists for:\n%s", stdout)
	}
	if !strings.Contains(stdout, "--allow-sensitive") {
		t.Errorf("the preview did not say what sending will need:\n%s", stdout)
	}
}

func TestFeedbackSecretIsRedactedBeforeItTravels(t *testing.T) {
	var body string
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/v1/feedback/consent":
			_, _ = io.WriteString(w, `{"version":"1.0","notice":"同意说明"}`)
		case r.URL.Path == "/api/v1/feedback" && r.Method == http.MethodPost:
			raw, _ := io.ReadAll(r.Body)
			body = string(raw)
			w.WriteHeader(http.StatusCreated)
			_, _ = io.WriteString(w, `{"receipt":"FB-20261113-ABC123","created_at":"2026-11-13T01:00:00Z"}`)
		}
	})
	file := writeFeedbackFile(t, "key sk-abcdefgh1234 坏了")

	_, stderr, status := runCaptured(t, "feedback", "send", "--file", file, "--consent")
	if status != 0 {
		t.Fatalf("status %d, stderr %q", status, stderr)
	}
	if !strings.Contains(stderr, "1 处密钥前缀") {
		t.Errorf("the replacement was not reported: %q", stderr)
	}
	if strings.Contains(body, "sk-abcdefgh") {
		t.Errorf("the key travelled: %s", body)
	}
	if !strings.Contains(body, "[已隐藏]") {
		t.Errorf("the document did not carry the replacement: %s", body)
	}
}

func TestFeedbackRefusesANulCharacter(t *testing.T) {
	file := writeFeedbackFile(t, "before\x00after")
	_, stderr, status := runCaptured(t, "feedback", "send", "--file", file, "--consent")
	if status == 0 {
		t.Fatalf("a document with U+0000 was accepted")
	}
	if !strings.Contains(stderr, "U+0000") {
		t.Errorf("the refusal did not name the character: %q", stderr)
	}
}

func TestFeedbackRefusesANonUTF8File(t *testing.T) {
	path := filepath.Join(t.TempDir(), "gbk.md")
	if err := os.WriteFile(path, []byte{0xd6, 0xd0, 0xce, 0xc4}, 0o600); err != nil {
		t.Fatalf("write the file: %v", err)
	}
	_, stderr, status := runCaptured(t, "feedback", "send", "--file", path, "--consent")
	if status == 0 {
		t.Fatalf("a non-UTF-8 file was accepted")
	}
	if !strings.Contains(stderr, "UTF-8") {
		t.Errorf("the refusal did not name the encoding: %q", stderr)
	}
}

func TestFeedbackDeleteRequiresYesAndSendsNothingWithoutIt(t *testing.T) {
	deleted := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodDelete {
			deleted++
		}
	})
	_, stderr, status := runCaptured(t, "feedback", "delete", "FB-20261113-ABC123")
	if status != 1 {
		t.Fatalf("delete without --yes status %d, stderr %q", status, stderr)
	}
	if deleted != 0 {
		t.Fatalf("delete without --yes sent %d requests", deleted)
	}
	if !strings.Contains(stderr, "--yes") {
		t.Errorf("the refusal did not offer the confirmed command: %q", stderr)
	}
}

func writeFeedbackFile(t *testing.T, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "feedback.md")
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("write the feedback file: %v", err)
	}
	return path
}

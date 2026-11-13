package main

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/feedback"
)

func TestFeedbackSendPreviewListsAttachmentsAndDoesNotPost(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	var methods []string
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		methods = append(methods, r.Method+" "+r.URL.Path)
		if strings.HasSuffix(r.URL.Path, "/feedback/consent") {
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"version":"1.3","notice":"同意说明"}`)
			return
		}
		t.Errorf("unexpected request %s %s", r.Method, r.URL.Path)
	})

	clip := filepath.Join(t.TempDir(), "clip.mp4")
	if err := os.WriteFile(clip, mp4Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	_, stderr, status := runCaptured(t,
		"feedback", "send", "--file", "testdata/feedback.md",
		"--attach", "testdata/attachment.png", "--attach", clip, "--json")
	if status != 1 {
		t.Fatalf("status %d, want 1 (stderr %q)", status, stderr)
	}
	for _, want := range []string{"attachment.png", "image/png", "68 字节", "clip.mp4",
		"video/mp4", "24 字节", "合计：92 字节", "sha256", "不会被自动扫描"} {
		if !strings.Contains(stderr, want) {
			t.Errorf("preview does not mention %q:\n%s", want, stderr)
		}
	}
	for _, method := range methods {
		if strings.HasPrefix(method, "POST") {
			t.Errorf("a preview sent %s", method)
		}
	}
}

func TestFeedbackSendRefusesBadAttachmentsBeforeTheNetwork(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	requests := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		requests++
		if strings.HasSuffix(r.URL.Path, "/feedback/consent") {
			_, _ = io.WriteString(w, `{"version":"1.3","notice":"同意说明"}`)
		}
	})
	dir := t.TempDir()
	svg := filepath.Join(dir, "fake.png")
	if err := os.WriteFile(svg, []byte(`<svg xmlns="http://www.w3.org/2000/svg"/>`), 0o600); err != nil {
		t.Fatal(err)
	}
	png, err := os.ReadFile("testdata/attachment.png")
	if err != nil {
		t.Fatal(err)
	}
	big := filepath.Join(dir, "big.png")
	if err := os.WriteFile(big, png, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Truncate(big, feedback.MaxImageBytes+1); err != nil {
		t.Fatal(err)
	}

	cases := []struct {
		name     string
		args     []string
		wantText string
	}{
		{"wrong header", []string{"--attach", svg}, "不是允许的类型"},
		{"oversized image", []string{"--attach", big}, "超过单文件上限"},
		{"too many", []string{
			"--attach", "testdata/attachment.png", "--attach", "testdata/attachment.png",
			"--attach", "testdata/attachment.png", "--attach", "testdata/attachment.png",
			"--attach", "testdata/attachment.png", "--attach", "testdata/attachment.png",
		}, "最多带 5 个附件"},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			args := append([]string{"feedback", "send", "--file", "testdata/feedback.md"}, testCase.args...)
			_, stderr, status := runCaptured(t, args...)
			if status != 1 {
				t.Fatalf("status %d, want 1 (stderr %q)", status, stderr)
			}
			if !strings.Contains(stderr, testCase.wantText) {
				t.Errorf("stderr = %q, want %q", stderr, testCase.wantText)
			}
		})
	}
	if requests != 0 {
		t.Errorf("local refusals sent %d request(s)", requests)
	}
}

func mp4Bytes() []byte {
	// A minimal ftyp/isom header: enough for the magic-byte check, 24 bytes in total.
	bytes := make([]byte, 24)
	bytes[3] = 24
	copy(bytes[4:], []byte("ftypisom"))
	return bytes
}

func TestFeedbackSendRefusesAnAttachmentThatDisappearedAfterPreview(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	source, err := os.ReadFile("testdata/attachment.png")
	if err != nil {
		t.Fatal(err)
	}
	attachment := filepath.Join(t.TempDir(), "shot.png")
	if err := os.WriteFile(attachment, source, 0o600); err != nil {
		t.Fatal(err)
	}
	posted := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/feedback/consent") {
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"version":"1.3","notice":"同意说明"}`)
			return
		}
		if r.Method == http.MethodPost {
			posted++
		}
	})

	if _, stderr, status := runCaptured(t, "feedback", "send", "--file", "testdata/feedback.md",
		"--attach", attachment, "--json"); status != 1 {
		t.Fatalf("preview status %d, want 1 (stderr %q)", status, stderr)
	}
	if err := os.Remove(attachment); err != nil {
		t.Fatal(err)
	}
	_, stderr, status := runCaptured(t, "feedback", "send", "--file", "testdata/feedback.md",
		"--attach", attachment, "--consent", "--json")
	if status == 0 {
		t.Fatalf("send with a vanished attachment succeeded (stderr %q)", stderr)
	}
	if !strings.Contains(stderr, "附件") {
		t.Errorf("stderr does not name the attachment: %q", stderr)
	}
	if posted != 0 {
		t.Errorf("a refused submission still posted %d request(s)", posted)
	}
}

func TestFeedbackSendWithConsentPostsMultipart(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	expectedContent, err := os.ReadFile("testdata/feedback.md")
	if err != nil {
		t.Fatal(err)
	}
	expectedAttachment, err := os.ReadFile("testdata/attachment.png")
	if err != nil {
		t.Fatal(err)
	}
	var gotContent, gotConsent string
	var gotAttachment []byte
	var gotFilename string
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/feedback/consent") {
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"version":"1.3","notice":"同意说明"}`)
			return
		}
		if r.Method != http.MethodPost {
			t.Errorf("unexpected request %s %s", r.Method, r.URL.Path)
			return
		}
		if err := r.ParseMultipartForm(10 << 20); err != nil {
			t.Errorf("the request is not multipart: %v", err)
			return
		}
		gotContent = r.FormValue("content")
		gotConsent = r.FormValue("consent_version")
		file, header, err := r.FormFile("attachment")
		if err != nil {
			t.Errorf("no attachment part: %v", err)
			return
		}
		defer file.Close()
		gotFilename = header.Filename
		gotAttachment, _ = io.ReadAll(file)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, `{"receipt":"FB-20260101-ABC123",`+
			`"created_at":"2026-01-01T00:00:00Z",`+
			`"attachments":[{"filename":"attachment.png","media_type":"image/png",`+
			`"size_bytes":68,"sha256":"5e3d382db4dd83d59aa5742793ad6b7903409e865c83bcbc54835049f043bc15"}]}`)
	})

	stdout, stderr, status := runCaptured(t,
		"feedback", "send", "--file", "testdata/feedback.md",
		"--attach", "testdata/attachment.png", "--consent", "--json")
	if status != 0 {
		t.Fatalf("status %d: %s", status, stderr)
	}
	var receipt map[string]any
	if err := json.Unmarshal([]byte(stdout), &receipt); err != nil {
		t.Fatalf("stdout is not one JSON object: %v\n%s", err, stdout)
	}
	if receipt["receipt"] != "FB-20260101-ABC123" {
		t.Errorf("receipt = %v", receipt["receipt"])
	}
	if gotContent != string(expectedContent) {
		t.Errorf("content did not survive multipart")
	}
	if gotConsent != "1.3" {
		t.Errorf("consent version = %q", gotConsent)
	}
	if gotFilename != "attachment.png" {
		t.Errorf("filename = %q", gotFilename)
	}
	if string(gotAttachment) != string(expectedAttachment) {
		t.Errorf("attachment bytes did not survive multipart")
	}
}

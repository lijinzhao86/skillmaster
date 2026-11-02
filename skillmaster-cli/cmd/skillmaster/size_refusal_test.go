package main

import (
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// v39's four size sentences, written from the numbers the server sends (voice-spec §上传与大小的
// 四句话). The point is that no sentence repeats a hard-coded 100 / 512 / 128: what the refusal
// says is what `details` carried.
func TestSizeRefusalSpeaksBothNumbers(t *testing.T) {
	cases := []struct {
		name   string
		detail api.IssueDetail
		want   string
	}{
		{
			"a named file",
			api.IssueDetail{Field: "assets/scan.pdf", Issue: "file_too_large",
				ActualBytes: 120 << 20, LimitBytes: 100 << 20},
			"文件 assets/scan.pdf 有 120 MiB，超过单文件上限 100 MiB。",
		},
		{
			"a file whose route never saw a path",
			api.IssueDetail{Field: "file", Issue: "file_too_large",
				ActualBytes: 120 << 20, LimitBytes: 100 << 20},
			"这个文件有 120 MiB，超过单文件上限 100 MiB。",
		},
		{
			// The same issue code serves both ceilings; only `limitBytes` tells them apart, so the
			// sentence follows the number rather than the command.
			"a version over its total",
			api.IssueDetail{Issue: "package_too_large", ActualBytes: 530 << 20, LimitBytes: 512 << 20},
			"这一版有 530 MiB，超过 512 MiB 的上限。",
		},
		{
			"the zip import's interim ceiling",
			api.IssueDetail{Issue: "package_too_large", ActualBytes: 300 << 20, LimitBytes: 128 << 20},
			"整包导入最多 128 MiB；更大的包请逐个文件上传。",
		},
		{
			"too many files",
			api.IssueDetail{Issue: "too_many_files", ActualBytes: 600, LimitBytes: 512},
			"这一版最多 512 个文件。",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			server := &api.ServerError{Code: "invalid_upload",
				DetailList: []api.IssueDetail{tc.detail}}
			got, ok := sizeRefusal(server)
			if !ok {
				t.Fatalf("no sentence for %+v", tc.detail)
			}
			if got != tc.want {
				t.Fatalf("got %q, want %q", got, tc.want)
			}
		})
	}
}

// The two paths a refusal actually takes to the screen: `write` / `put` go through `writeRefusal`,
// `submit` through `classifyFailure`. Both have to end at the numbered sentence, not the static one.
func TestSizeRefusalReachesBothErrorPaths(t *testing.T) {
	server := &api.ServerError{Status: 413, Code: "invalid_upload",
		Details: map[string]string{"assets/scan.pdf": "file_too_large"},
		DetailList: []api.IssueDetail{{Field: "assets/scan.pdf", Issue: "file_too_large",
			ActualBytes: 120 << 20, LimitBytes: 100 << 20}}}
	want := "文件 assets/scan.pdf 有 120 MiB，超过单文件上限 100 MiB。"

	if got := writeRefusal(server, "me/skill@"+"0"); got == nil || got.Error() != want {
		t.Fatalf("writeRefusal = %v, want %q", got, want)
	}
	if got := classifyFailure(server).Message; got != want {
		t.Fatalf("classifyFailure = %q, want %q", got, want)
	}
}

// A refusal from a server that sent no numbers keeps the old static line: inventing a ceiling here
// would state a limit this client has no business choosing.
func TestSizeRefusalDeclinesWithoutNumbers(t *testing.T) {
	server := &api.ServerError{Code: "invalid_upload",
		Details:    map[string]string{"file": "file_too_large"},
		DetailList: []api.IssueDetail{{Issue: "file_too_large"}}}
	if _, ok := sizeRefusal(server); ok {
		t.Fatal("a refusal with no limit must fall through to the issue table")
	}
	got := versionRefusal(server)
	if got == nil || got.Error() != issueMessages["file_too_large"] {
		t.Fatalf("got %v, want the static line", got)
	}
}

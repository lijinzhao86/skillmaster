package main

// v32's second phase: `write` takes its content from a file, refuses a copied `read` rendering, and
// asks for an empty write explicitly.
//
// **These are also the first tests of writing into an existing version.** Every `write` case before
// this file went through `@new`; the path that overwrites a file in a draft had no test at all.

import (
	"io"
	"net/http"
	"strings"
	"testing"
)

// writeFake answers the requests a write into an existing draft makes, and records what it saw —
// including the headers, which the shared fakeServer does not keep.
type writeFake struct {
	content  string
	listing  string
	etag     string
	digest   string
	conflict bool

	requests []string
	puts     int
	putBody  string
	ifMatch  string
}

func (f *writeFake) start(t *testing.T) *writeFake {
	t.Helper()
	if f.listing == "" {
		f.listing = `{"files":[{"relpath":"SKILL.md","is_binary":false,"size":1}]}`
	}
	withTempHome(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		f.requests = append(f.requests, r.Method+" "+r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/files"):
			w.Header().Set("ETag", f.etag)
			_, _ = w.Write([]byte(f.listing))
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/files/SKILL.md"):
			w.Header().Set("ETag", f.etag)
			_, _ = w.Write([]byte(f.content))
		case r.Method == http.MethodPut && strings.HasSuffix(r.URL.Path, "/files/SKILL.md"):
			f.puts++
			body, _ := io.ReadAll(r.Body)
			f.putBody = string(body)
			f.ifMatch = r.Header.Get("If-Match")
			if f.conflict {
				w.WriteHeader(http.StatusConflict)
				_, _ = w.Write([]byte(`{"error":{"code":"digest_mismatch","message":"stale"}}`))
				return
			}
			_, _ = w.Write([]byte(`{"version":{"code":"K3M9QX2P","digest":"` + f.digest + `"}}`))
		default:
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":{"code":"not_found","message":"no"}}`))
		}
	})
	return f
}

// setStdinText feeds the command's standard input for one test.
func setStdinText(t *testing.T, text string) {
	t.Helper()
	old := stdin
	stdin = strings.NewReader(text)
	t.Cleanup(func() { stdin = old })
}

const readRendering = "1\talpha\n2\tbeta\n（按 me/hello@K3M9QX2P 取。）\n"

// A copied `read` rendering is not a file's text, and `write` replaces whole files with no chance to
// notice afterwards (v32 test-plan T12 / A10).
func TestWriteRefusesACopiedReadRendering(t *testing.T) {
	t.Run("standard input", func(t *testing.T) {
		f := (&writeFake{content: "alpha\nbeta\n", etag: `"sha256:aa"`, digest: "sha256:bb"}).start(t)
		setStdinText(t, readRendering)

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md")

		if status != 1 {
			t.Fatalf("status = %d, want 1 (stderr = %s)", status, stderr)
		}
		if !strings.Contains(stderr, "像是 skmr skill read 的输出") ||
			!strings.Contains(stderr, "skmr skill cat me/hello@K3M9QX2P SKILL.md") {
			t.Fatalf("stderr = %q", stderr)
		}
		if len(f.requests) != 0 {
			t.Fatalf("requests were sent before the refusal: %v", f.requests)
		}
	})

	t.Run("@new", func(t *testing.T) {
		withTempHome(t)
		requests := 0
		pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
			requests++
			w.Write([]byte(`{"base":null}`))
		})
		setStdinText(t, readRendering)

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@new", "SKILL.md",
			"--name", "首版")

		if status != 1 || !strings.Contains(stderr, "像是 skmr skill read 的输出") {
			t.Fatalf("status = %d, stderr = %q", status, stderr)
		}
		if requests != 0 {
			t.Fatalf("%d requests were sent before the refusal", requests)
		}
	})

	t.Run("a numbered line that is real content is written", func(t *testing.T) {
		f := (&writeFake{content: "x\n", etag: `"sha256:aa"`, digest: "sha256:bb"}).start(t)
		setStdinText(t, "note\n3\tfoo\n")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md",
			"--overwrite")

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if f.putBody != "note\n3\tfoo\n" {
			t.Fatalf("PUT body = %q", f.putBody)
		}
	})

	t.Run("numbered lines without the footer are written", func(t *testing.T) {
		// Two of the three conditions are not enough: a caller may copy the body and leave the note
		// behind, and that must not be refused — content that merely looks numbered is legal.
		f := (&writeFake{content: "x\n", etag: `"sha256:aa"`, digest: "sha256:bb"}).start(t)
		setStdinText(t, "1\ta\n2\tb\n")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md",
			"--overwrite")

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if f.putBody != "1\ta\n2\tb\n" {
			t.Fatalf("PUT body = %q", f.putBody)
		}
	})
}

// **There is no file input** (v32 PRD §不做): the standard input is the only source. The flag that
// would have made "author a file locally, push the whole thing" convenient is not accepted at all —
// that round trip is what the version exists to replace, and an agent that starts from a local file
// stops using `edit`, with its hunk, its uniqueness check and its miss diagnosis.
func TestWriteHasNoFileInput(t *testing.T) {
	f := (&writeFake{content: "old\n", etag: `"sha256:aa"`, digest: "sha256:bb"}).start(t)
	path := writeTemp(t, "new.md", "text\n")

	_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md",
		"--content-file", path)

	if status != 2 {
		t.Fatalf("status = %d, want 2 (stderr = %s)", status, stderr)
	}
	if !strings.Contains(stderr, "用法见：skmr skill write --help") {
		t.Fatalf("stderr = %q", stderr)
	}
	if len(f.requests) != 0 {
		t.Fatalf("requests were sent: %v", f.requests)
	}
}

// Empty is legal content, so it is not refused — but it has to be meant (T20 / A14).
func TestWriteNeedsAllowEmpty(t *testing.T) {
	t.Run("empty standard input without the flag", func(t *testing.T) {
		f := (&writeFake{content: "old\n", etag: `"sha256:aa"`, digest: "sha256:bb"}).start(t)
		setStdinText(t, "")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md")

		if status != 2 {
			t.Fatalf("status = %d, want 2 (stderr = %s)", status, stderr)
		}
		if !strings.Contains(stderr, "要写的内容是空的；真要写空内容就加 --allow-empty。") {
			t.Fatalf("stderr = %q", stderr)
		}
		if len(f.requests) != 0 {
			t.Fatalf("requests were sent before the refusal: %v", f.requests)
		}
	})

	t.Run("empty standard input on @new without the flag", func(t *testing.T) {
		f := (&writeFake{content: "old\n", etag: `"sha256:aa"`, digest: "sha256:bb"}).start(t)
		setStdinText(t, "")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@new", "SKILL.md",
			"--name", "首版")

		if status != 2 || !strings.Contains(stderr, "--allow-empty") {
			t.Fatalf("status = %d, stderr = %q", status, stderr)
		}
		if len(f.requests) != 0 {
			t.Fatalf("requests were sent before the refusal: %v", f.requests)
		}
	})

	t.Run("empty with the flag", func(t *testing.T) {
		f := (&writeFake{content: "old\n", etag: `"sha256:aa"`, digest: "sha256:bb"}).start(t)
		setStdinText(t, "")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md",
			"--allow-empty", "--overwrite")

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if f.putBody != "" {
			t.Fatalf("PUT body = %q, want empty", f.putBody)
		}
	})
}

// Writing into a version that already exists (T21 / A8). The If-Match the request carries is the
// whole guard: a write that overwrote a newer draft would be unrecoverable.
func TestWriteIntoAnExistingVersion(t *testing.T) {
	t.Run("overwrite carries the read ETag back as If-Match", func(t *testing.T) {
		f := (&writeFake{content: "old\n", etag: `"sha256:aa"`, digest: "sha256:bb"}).start(t)
		setStdinText(t, "new\n")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md",
			"--overwrite")

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if f.putBody != "new\n" {
			t.Fatalf("PUT body = %q", f.putBody)
		}
		if f.ifMatch != `"sha256:aa"` {
			t.Fatalf("If-Match = %q, want the ETag the read returned", f.ifMatch)
		}
		if !strings.Contains(stderr, "已写入 SKILL.md（bb）。") {
			t.Fatalf("stderr = %q", stderr)
		}
	})

	t.Run("a missing path is created from the listing's ETag", func(t *testing.T) {
		f := (&writeFake{
			listing: `{"files":[{"relpath":"other.md","is_binary":false,"size":1}]}`,
			etag:    `"sha256:aa"`, digest: "sha256:cc"}).start(t)
		setStdinText(t, "fresh\n")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md")

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if f.putBody != "fresh\n" || f.ifMatch != `"sha256:aa"` {
			t.Fatalf("PUT body = %q, If-Match = %q", f.putBody, f.ifMatch)
		}
	})

	t.Run("a stale baseline is refused", func(t *testing.T) {
		(&writeFake{content: "old\n", etag: `"sha256:aa"`, digest: "sha256:bb",
			conflict: true}).start(t)
		setStdinText(t, "new\n")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md",
			"--overwrite")

		if status != 1 || !strings.Contains(stderr, "这一版在读过之后被改动过；重新读一遍再改。") {
			t.Fatalf("status = %d, stderr = %q", status, stderr)
		}
	})

	t.Run("the server kept its version", func(t *testing.T) {
		(&writeFake{content: "old\n", etag: `"sha256:bb"`, digest: "sha256:bb"}).start(t)
		setStdinText(t, "new\n")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md",
			"--overwrite")

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if !strings.Contains(stderr, "没有改动：服务端归一之后与已存的内容相同") {
			t.Fatalf("stderr = %q", stderr)
		}
	})
}

// `write` puts a new file in place by default; replacing one that is already there is a decision the
// caller has to make out loud (v32 test-plan T22 / A14). The refusal is not a wall — stdin can
// still carry anything — but it makes the agent stop and consider `edit` first.
func TestWriteIsCreateByDefault(t *testing.T) {
	t.Run("an existing file is refused without the flag", func(t *testing.T) {
		f := (&writeFake{content: "old\n", etag: `"sha256:aa"`, digest: "sha256:bb"}).start(t)
		setStdinText(t, "new\n")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md")

		if status != 1 {
			t.Fatalf("status = %d, want 1 (stderr = %s)", status, stderr)
		}
		if !strings.Contains(stderr, "改一处用 skmr skill edit me/hello@K3M9QX2P SKILL.md") ||
			!strings.Contains(stderr, "整份覆盖加 --overwrite") {
			t.Fatalf("stderr = %q", stderr)
		}
		if f.puts != 0 {
			t.Fatalf("%d PUTs were sent before the refusal", f.puts)
		}
	})

	t.Run("the flag replaces it", func(t *testing.T) {
		f := (&writeFake{content: "old\n", etag: `"sha256:aa"`, digest: "sha256:bb"}).start(t)
		setStdinText(t, "new\n")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md",
			"--overwrite")

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if f.putBody != "new\n" {
			t.Fatalf("PUT body = %q", f.putBody)
		}
	})

	t.Run("binary replacement goes through the same door", func(t *testing.T) {
		f := (&writeFake{content: "old\n", etag: `"sha256:aa"`, digest: "sha256:bb"}).start(t)
		binary := string([]byte{0x00, 0xff, 0x0a})
		setStdinText(t, binary)

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md",
			"--overwrite")

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if f.putBody != binary {
			t.Fatalf("PUT body = %q", f.putBody)
		}
	})

	t.Run("@new refuses the flag", func(t *testing.T) {
		withTempHome(t)
		requests := 0
		pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
			requests++
			_, _ = w.Write([]byte(`{"base":null}`))
		})
		setStdinText(t, "text\n")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@new", "SKILL.md",
			"--name", "首版", "--overwrite")

		if status != 2 || !strings.Contains(stderr, "--overwrite 只在整份替换已有文件时有意义") {
			t.Fatalf("status = %d, stderr = %q", status, stderr)
		}
		if requests != 0 {
			t.Fatalf("%d requests were sent before the refusal", requests)
		}
	})
}

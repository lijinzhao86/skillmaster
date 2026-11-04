package main

// v44's read/write contract: whole-file writes from local files, whole-version gets, the stdin
// channels of `edit`, the bare-`SKILL.md` refusal, and the "you already have a draft" guard.
// The cases are T3–T9 and T12–T13 in the version's test plan; what needs a real server (T1, T2,
// T6's bytes) stays in that plan's end-to-end section.

import (
	"archive/zip"
	"bytes"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/pins"
)

// fromFake is a draft whose files and version digest move as writes land.
type fromFake struct {
	files map[string]string
	etag  string
	puts  []fromPut
}

type fromPut struct {
	path    string
	body    string
	ifMatch string
}

func (f *fromFake) start(t *testing.T) *fromFake {
	t.Helper()
	if f.files == nil {
		f.files = map[string]string{}
	}
	if f.etag == "" {
		f.etag = `"sha256:v0"`
	}
	withTempHome(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/files"):
			w.Header().Set("ETag", f.etag)
			var entries []string
			for relpath, content := range f.files {
				entries = append(entries, fmt.Sprintf(
					`{"relpath":%q,"is_binary":false,"size":%d}`, relpath, len(content)))
			}
			_, _ = w.Write([]byte(`{"files":[` + strings.Join(entries, ",") + `]}`))
		case r.Method == http.MethodGet && strings.Contains(r.URL.Path, "/files/"):
			relpath := r.URL.Path[strings.Index(r.URL.Path, "/files/")+len("/files/"):]
			w.Header().Set("ETag", f.etag)
			_, _ = w.Write([]byte(f.files[relpath]))
		case r.Method == http.MethodPut && strings.Contains(r.URL.Path, "/files/"):
			relpath := r.URL.Path[strings.Index(r.URL.Path, "/files/")+len("/files/"):]
			raw, _ := io.ReadAll(r.Body)
			body := string(raw)
			f.puts = append(f.puts, fromPut{path: relpath, body: body, ifMatch: r.Header.Get("If-Match")})
			f.files[relpath] = body
			f.etag = fmt.Sprintf(`"sha256:v%d"`, len(f.puts))
			_, _ = w.Write([]byte(`{"version":{"code":"K3M9QX2P","digest":"sha256:v` +
				fmt.Sprint(len(f.puts)) + `"}}`))
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	})
	return f
}

// T4: `write --from` replaces whole files from local paths, and several land under one directory.
func TestWriteFromLocalFiles(t *testing.T) {
	t.Run("a new file is sent byte for byte", func(t *testing.T) {
		fake := (&fromFake{}).start(t)
		local := writeTemp(t, "x.md", "hello\n")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "notes/x.md",
			"--from", local)

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if len(fake.puts) != 1 || fake.puts[0].path != "notes/x.md" ||
			fake.puts[0].body != "hello\n" {
			t.Fatalf("puts = %+v", fake.puts)
		}
		if fake.puts[0].ifMatch != `"sha256:v0"` {
			t.Fatalf("If-Match = %q, want the listing's ETag", fake.puts[0].ifMatch)
		}
	})

	t.Run("several files land in the directory", func(t *testing.T) {
		fake := (&fromFake{}).start(t)
		first := writeTemp(t, "x.txt", "x\n")
		second := writeTemp(t, "y.txt", "y\n")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "refs/",
			"--from", first, "--from", second)

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if len(fake.puts) != 2 || fake.puts[0].path != "refs/x.txt" ||
			fake.puts[1].path != "refs/y.txt" {
			t.Fatalf("puts = %+v", fake.puts)
		}
		// The second write is based on the version the first one produced, not on the stale one.
		if fake.puts[1].ifMatch != `"sha256:v1"` {
			t.Fatalf("second If-Match = %q, want the refreshed baseline", fake.puts[1].ifMatch)
		}
	})

	t.Run("the server's ceilings are checked before the upload", func(t *testing.T) {
		fake := (&fromFake{}).start(t)
		huge := tempSizedFile(t, maxFileBytes+1)

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P",
			"assets/big.bin", "--from", huge)

		if status != 1 || !strings.Contains(stderr, "超过单文件上限") {
			t.Fatalf("status = %d, stderr = %q", status, stderr)
		}
		if len(fake.puts) != 0 {
			t.Fatalf("%d puts were sent", len(fake.puts))
		}
	})

	t.Run("binary bytes are sent unchanged", func(t *testing.T) {
		fake := (&fromFake{}).start(t)
		binary := string([]byte{0x89, 'P', 'N', 'G', 0x00, 0xff, 0x0a})
		local := writeTemp(t, "logo.png", binary)

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P",
			"assets/logo.png", "--from", local)

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if len(fake.puts) != 1 || fake.puts[0].body != binary {
			t.Fatalf("puts = %+v", fake.puts)
		}
	})

	t.Run("a partial read does not unlock an overwrite", func(t *testing.T) {
		fake := (&fromFake{files: map[string]string{"SKILL.md": "a\nb\nc\n"}}).start(t)
		local := writeTemp(t, "new.md", "new\n")
		if _, stderr, status := runCaptured(t, "skill", "head", "me/hello@K3M9QX2P",
			"SKILL.md", "-n", "1"); status != 0 {
			t.Fatalf("head failed: %d %s", status, stderr)
		}

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md",
			"--from", local, "--overwrite")

		if status != 1 || !strings.Contains(stderr, "还没读过") {
			t.Fatalf("status = %d, stderr = %q", status, stderr)
		}
		if len(fake.puts) != 0 {
			t.Fatalf("puts were sent: %+v", fake.puts)
		}
	})

	t.Run("a version that moved after the read is refused", func(t *testing.T) {
		fake := (&fromFake{files: map[string]string{"SKILL.md": "old\n"}}).start(t)
		local := writeTemp(t, "new.md", "new\n")
		readBeforeOverwrite(t)
		// Somebody else wrote in between: the version digest the proof recorded is gone.
		fake.etag = `"sha256:moved"`

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md",
			"--from", local, "--overwrite")

		if status != 1 || !strings.Contains(stderr, "读过之后这一版变过") {
			t.Fatalf("status = %d, stderr = %q", status, stderr)
		}
		if len(fake.puts) != 0 {
			t.Fatalf("puts were sent: %+v", fake.puts)
		}
	})

	t.Run("several files need a directory target", func(t *testing.T) {
		(&fromFake{}).start(t)
		first := writeTemp(t, "x.txt", "x\n")
		second := writeTemp(t, "y.txt", "y\n")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "refs",
			"--from", first, "--from", second)

		if status != 2 || !strings.Contains(stderr, "以 / 结尾") {
			t.Fatalf("status = %d, stderr = %q", status, stderr)
		}
	})

	t.Run("an existing file needs --overwrite", func(t *testing.T) {
		fake := (&fromFake{files: map[string]string{"SKILL.md": "old\n"}}).start(t)
		local := writeTemp(t, "new.md", "new\n")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md",
			"--from", local)

		if status != 1 || !strings.Contains(stderr, "--overwrite") {
			t.Fatalf("status = %d, stderr = %q", status, stderr)
		}
		if len(fake.puts) != 0 {
			t.Fatalf("puts were sent before the refusal: %+v", fake.puts)
		}
	})

	t.Run("--overwrite needs a read of that version first", func(t *testing.T) {
		fake := (&fromFake{files: map[string]string{"SKILL.md": "old\n"}}).start(t)
		local := writeTemp(t, "new.md", "new\n")

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md",
			"--from", local, "--overwrite")

		if status != 1 || !strings.Contains(stderr, "还没读过") {
			t.Fatalf("status = %d, stderr = %q", status, stderr)
		}
		if len(fake.puts) != 0 {
			t.Fatalf("puts were sent before the refusal: %+v", fake.puts)
		}
	})

	t.Run("a read unlocks it and the change is counted", func(t *testing.T) {
		fake := (&fromFake{files: map[string]string{"SKILL.md": "a\nb\n"}}).start(t)
		local := writeTemp(t, "new.md", "a\nc\n")
		readBeforeOverwrite(t)

		_, stderr, status := runCaptured(t, "skill", "write", "me/hello@K3M9QX2P", "SKILL.md",
			"--from", local, "--overwrite")

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if !strings.Contains(stderr, "将改动 2 行（+1 / -1）") {
			t.Fatalf("stderr = %q", stderr)
		}
		if len(fake.puts) != 1 || fake.puts[0].body != "a\nc\n" {
			t.Fatalf("puts = %+v", fake.puts)
		}
	})
}

// zipOf builds an archive in memory, the shape the server serves to `get --all`.
func zipOf(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	for name, content := range files {
		entry, err := writer.Create(name)
		if err != nil {
			t.Fatalf("zip entry %s: %v", name, err)
		}
		if _, err := entry.Write([]byte(content)); err != nil {
			t.Fatalf("zip write %s: %v", name, err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("zip close: %v", err)
	}
	return buffer.Bytes()
}

// T6: `get --all` writes the whole version, sidecar included, and refuses a directory with content.
func TestGetAllWritesTheWholeVersion(t *testing.T) {
	archive := zipOf(t, map[string]string{
		"SKILL.md":            "# body\n",
		"references/notes.md": "notes\n",
		metadataRelpath:       `{"schema_version":1}`,
	})
	requests := 0
	withTempHome(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		requests++
		if !strings.HasSuffix(r.URL.Path, "/archive") {
			t.Errorf("unexpected path %s", r.URL.Path)
			http.NotFound(w, r)
			return
		}
		w.Header().Set("ETag", `"sha256:aa"`)
		_, _ = w.Write(archive)
	})

	into := filepath.Join(t.TempDir(), "whole")
	stdout, stderr, status := runCaptured(t, "skill", "get", "me/hello@1.0.0", "--all",
		"--into", into)
	if status != 0 {
		t.Fatalf("status = %d, stderr = %s", status, stderr)
	}
	for relpath, want := range map[string]string{
		"SKILL.md":            "# body\n",
		"references/notes.md": "notes\n",
		metadataRelpath:       `{"schema_version":1}`,
	} {
		got, err := os.ReadFile(filepath.Join(into, filepath.FromSlash(relpath)))
		if err != nil || string(got) != want {
			t.Errorf("%s = %q, %v", relpath, got, err)
		}
	}
	// The sidecar is not one of the skill's files, and the count says so.
	if !strings.Contains(stdout, "2 个文件") {
		t.Fatalf("stdout = %q", stdout)
	}

	nonEmpty := t.TempDir()
	if err := os.WriteFile(filepath.Join(nonEmpty, "keep.txt"), []byte("mine"), 0o600); err != nil {
		t.Fatal(err)
	}
	requests = 0
	_, stderr, status = runCaptured(t, "skill", "get", "me/hello@1.0.0", "--all",
		"--into", nonEmpty)
	if status == 0 || !strings.Contains(stderr, "不是空目录") {
		t.Fatalf("status = %d, stderr = %q", status, stderr)
	}
	if requests != 0 {
		t.Fatalf("%d requests were sent before the refusal", requests)
	}
}

// T8: the three stdin channels of `edit`, and the batch's all-or-nothing rule.
func TestEditStdinChannels(t *testing.T) {
	t.Run("a long old from standard input", func(t *testing.T) {
		fake := newEditFake(t, "alpha\nbeta\n")
		setStdinText(t, "alpha")

		_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
			"--old-file", "-", "--new", "ALPHA")

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if got := putBody(fake); got != "ALPHA\nbeta\n" {
			t.Fatalf("PUT body = %q", got)
		}
	})

	t.Run("--stdin-json carries one replacement", func(t *testing.T) {
		fake := newEditFake(t, "alpha\n")
		setStdinText(t, `{"old":"alpha","new":"beta"}`)

		_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
			"--stdin-json")

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if got := putBody(fake); got != "beta\n" {
			t.Fatalf("PUT body = %q", got)
		}
	})

	t.Run("a batch lands in one version", func(t *testing.T) {
		fake := newEditFake(t, "a\nb\nc\n")
		setStdinText(t, `{"edits":[{"old":"a","new":"A"},{"old":"c","new":"C"}]}`)

		_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
			"--stdin-json")

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if got := putBody(fake); got != "A\nb\nC\n" {
			t.Fatalf("PUT body = %q", got)
		}
		if puts := countPuts(fake); puts != 1 {
			t.Fatalf("%d PUTs were sent, want one", puts)
		}
	})

	t.Run("a failed batch writes nothing and names its index", func(t *testing.T) {
		fake := newEditFake(t, "a\nb\nc\n")
		setStdinText(t, `{"edits":[{"old":"a","new":"A"},{"old":"missing","new":"X"}]}`)

		_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
			"--stdin-json")

		if status != 1 || !strings.Contains(stderr, "第 2 处") {
			t.Fatalf("status = %d, stderr = %q", status, stderr)
		}
		if puts := countPuts(fake); puts != 0 {
			t.Fatalf("%d PUTs were sent for a failed batch", puts)
		}
	})

	t.Run("--stdin-json and --old cannot both be given", func(t *testing.T) {
		fake := newEditFake(t, "alpha\n")
		setStdinText(t, `{"old":"alpha","new":"beta"}`)

		_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
			"--stdin-json", "--old", "alpha")

		if status != 2 || !strings.Contains(stderr, "不能和") {
			t.Fatalf("status = %d, stderr = %q", status, stderr)
		}
		if puts := countPuts(fake); puts != 0 {
			t.Fatalf("%d PUTs were sent", puts)
		}
	})
}

func countPuts(fake *fakeServer) int {
	count := 0
	for _, request := range fake.requests {
		if request.Method == http.MethodPut {
			count++
		}
	}
	return count
}

// T9: a bare read of the body is a load, so it goes through `invoke`.
func TestBareSkillMdReadIsRefused(t *testing.T) {
	cases := [][]string{
		{"skill", "read", "me/hello", "SKILL.md"},
		{"skill", "cat", "me/hello", "SKILL.md"},
		{"skill", "head", "me/hello", "SKILL.md"},
		{"skill", "tail", "me/hello", "SKILL.md"},
		{"skill", "read", "me/hello", "SKILL.md", "--offset", "5"},
		{"skill", "read", "me/hello", "SKILL.md", "--limit", "5"},
	}
	for _, argv := range cases {
		withTempHome(t)
		requests := 0
		pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
			requests++
			http.NotFound(w, r)
		})

		stdout, stderr, status := runCaptured(t, argv...)

		if status != 2 {
			t.Errorf("%v: status = %d, want 2 (stderr %q)", argv, status, stderr)
		}
		if !strings.Contains(stderr, "skmr skill invoke me/hello") {
			t.Errorf("%v: stderr = %q", argv, stderr)
		}
		if stdout != "" {
			t.Errorf("%v: stdout = %q, want empty", argv, stdout)
		}
		if requests != 0 {
			t.Errorf("%v: %d requests were sent", argv, requests)
		}
	}

	t.Run("other files are read as before", func(t *testing.T) {
		withTempHome(t)
		pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
			http.NotFound(w, r)
		})
		_, stderr, status := runCaptured(t, "skill", "read", "me/hello", "README.md")
		if status == 2 && strings.Contains(stderr, "等于加载") {
			t.Fatalf("README.md was refused as a load: %q", stderr)
		}
	})

	t.Run("a named version is read as before", func(t *testing.T) {
		withTempHome(t)
		pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
			http.NotFound(w, r)
		})
		_, stderr, status := runCaptured(t, "skill", "read", "me/hello@1.0.0", "SKILL.md")
		if status == 2 && strings.Contains(stderr, "等于加载") {
			t.Fatalf("an explicit version was refused as a load: %q", stderr)
		}
	})

	t.Run("an invoked skill is read as before", func(t *testing.T) {
		withTempHome(t)
		pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
			http.NotFound(w, r)
		})
		// The pin store is per server, so the pin is recorded once the fake's URL is in place.
		if _, _, err := rememberPin("me/hello",
			pins.Pin{Kind: "name", Name: "1.0.0", Digest: "sha256:abc"}); err != nil {
			t.Fatalf("rememberPin: %v", err)
		}
		_, stderr, status := runCaptured(t, "skill", "read", "me/hello", "SKILL.md")
		if status == 2 && strings.Contains(stderr, "等于加载") {
			t.Fatalf("an invoked skill was refused as a load: %q", stderr)
		}
	})

	t.Run("cat refuses the whole command when one path is the body", func(t *testing.T) {
		withTempHome(t)
		requests := 0
		pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
			requests++
			http.NotFound(w, r)
		})
		stdout, stderr, status := runCaptured(t, "skill", "cat", "me/hello", "README.md", "SKILL.md")
		if status != 2 || !strings.Contains(stderr, "skmr skill invoke me/hello") {
			t.Fatalf("status = %d, stderr = %q", status, stderr)
		}
		if stdout != "" || requests != 0 {
			t.Fatalf("stdout = %q, %d requests", stdout, requests)
		}
	})
}

// T5: the merged-away names still answer with the new spelling, before any request.
func TestRetiredVerbsPointAtTheirReplacement(t *testing.T) {
	withTempHome(t)
	requests := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		requests++
		http.NotFound(w, r)
	})

	_, stderr, status := runCaptured(t, "skill", "put", "me/hello@K3M9QX2P", "a.md", "refs/a.md")
	if status == 0 || !strings.Contains(stderr, "write") || !strings.Contains(stderr, "--from") {
		t.Fatalf("put: status = %d, stderr = %q", status, stderr)
	}
	_, stderr, status = runCaptured(t, "skill", "download", "me/hello@1.0.0", "--into", "./tmp")
	if status == 0 || !strings.Contains(stderr, "--all") {
		t.Fatalf("download: status = %d, stderr = %q", status, stderr)
	}
	if requests != 0 {
		t.Fatalf("%d requests were sent before the refusals", requests)
	}
}

// T13: one draft per caller per skill. A collaborator's draft is not in the way, and `--new` is
// the override a caller only learns from the refusal.
func TestDraftCreateRefusesWhenTheCallerHasADraft(t *testing.T) {
	start := func(t *testing.T, versions string, created *int) {
		t.Helper()
		withTempHome(t)
		pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
			switch {
			case r.Method == http.MethodGet && r.URL.Path == "/api/v1/me":
				_, _ = w.Write([]byte(`{"user":{"username":"me","namespace":"me"}}`))
			case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/versions"):
				_, _ = w.Write([]byte(`{"versions":[` + versions + `]}`))
			case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/versions"):
				*created++
				w.WriteHeader(http.StatusCreated)
				_, _ = w.Write([]byte(`{"version":{"code":"NEW00000","state":"draft"}}`))
			default:
				t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
			}
		})
	}

	mine := `{"code":"K3M9QX2P","name":"第二版","submitted_by":"me","status":"draft"}`

	t.Run("an own draft is refused with its code", func(t *testing.T) {
		created := 0
		start(t, mine, &created)

		_, stderr, status := runCaptured(t, "draft", "create", "me/hello", "--name", "另一版")

		if status != 1 {
			t.Fatalf("status = %d, stderr = %q", status, stderr)
		}
		for _, want := range []string{"K3M9QX2P", "第二版", "先问用户", "--new"} {
			if !strings.Contains(stderr, want) {
				t.Errorf("stderr is missing %q: %s", want, stderr)
			}
		}
		if created != 0 {
			t.Fatalf("%d creates were sent", created)
		}
	})

	t.Run("--new goes through once the user has agreed", func(t *testing.T) {
		created := 0
		start(t, mine, &created)

		_, stderr, status := runCaptured(t, "draft", "create", "me/hello", "--name", "另一版",
			"--new")

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if created != 1 {
			t.Fatalf("%d creates were sent", created)
		}
	})

	t.Run("a collaborator's draft does not block", func(t *testing.T) {
		created := 0
		start(t, `{"code":"K3M9QX2P","name":"别人的","submitted_by":"other","status":"draft"}`,
			&created)

		_, stderr, status := runCaptured(t, "draft", "create", "me/hello", "--name", "我的")

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if created != 1 {
			t.Fatalf("%d creates were sent", created)
		}
	})
}

// T12: a version code that does not resolve lists the drafts the caller can continue.
func TestWriteWithAMissingCodeListsDrafts(t *testing.T) {
	withTempHome(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/files"):
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":{"code":"not_found","message":"no"}}`))
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/versions"):
			_, _ = w.Write([]byte(`{"versions":[{"code":"K3M9QX2P","name":"第二版",` +
				`"submitted_by":"me","status":"draft"}]}`))
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
		}
	})
	local := writeTemp(t, "x.md", "x\n")

	_, stderr, status := runCaptured(t, "skill", "write", "me/hello@ZZZZZZZZ", "SKILL.md",
		"--from", local, "--overwrite")

	if status == 0 {
		t.Fatalf("status = %d, stderr = %s", status, stderr)
	}
	if !strings.Contains(stderr, "K3M9QX2P") || !strings.Contains(stderr, "当前的草稿") {
		t.Fatalf("stderr = %q", stderr)
	}
}

// T13's other half: `submit` also refuses to pile up a draft, and `--new` is the same hidden
// override. The guard runs before the upload, so a refusal costs no bytes.
func TestSubmitRefusesWhenTheCallerHasADraft(t *testing.T) {
	start := func(t *testing.T, uploadResponse string, uploads *int) {
		t.Helper()
		withTempHome(t)
		pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
			switch {
			case r.URL.Path == "/api/v1/me":
				_, _ = w.Write([]byte(`{"user":{"username":"owner","namespace":"owner"}}`))
			case r.Method == http.MethodGet && strings.Contains(r.URL.Path, "/versions"):
				_, _ = w.Write([]byte(`{"versions":[{"code":"K3M9QX2P","name":"上一份",` +
					`"submitted_by":"owner","status":"draft"}]}`))
			case r.Method == http.MethodPost && r.URL.Path == "/api/v1/skills":
				*uploads++
				w.WriteHeader(http.StatusCreated)
				_, _ = w.Write([]byte(uploadResponse))
			default:
				t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
				http.NotFound(w, r)
			}
		})
	}
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "SKILL.md"),
		[]byte("---\nname: pdf-tools\ndescription: d\n---\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	t.Run("an own draft stops the upload", func(t *testing.T) {
		uploads := 0
		start(t, "", &uploads)

		_, stderr, status := runCaptured(t, "skill", "submit", dir, "--name", "另一版")

		if status != 1 {
			t.Fatalf("status = %d, stderr = %q", status, stderr)
		}
		if !strings.Contains(stderr, "K3M9QX2P") || !strings.Contains(stderr, "先问用户") {
			t.Fatalf("stderr = %q", stderr)
		}
		if uploads != 0 {
			t.Fatalf("%d uploads were sent", uploads)
		}
	})

	t.Run("--new uploads after the user agreed", func(t *testing.T) {
		uploads := 0
		start(t, `{"name":"pdf-tools","namespace":"owner","created":true,`+
			`"skill_created":false,"version":{"draft_id":"NEW00000","draft_name":"另一版",`+
			`"digest":"sha256:aa","state":"draft"}}`, &uploads)

		_, stderr, status := runCaptured(t, "skill", "submit", dir, "--name", "另一版", "--new")

		if status != 0 {
			t.Fatalf("status = %d, stderr = %s", status, stderr)
		}
		if uploads != 1 {
			t.Fatalf("%d uploads were sent", uploads)
		}
	})
}

package main

import (
	"archive/zip"
	"bytes"
	"fmt"
	"net/http"
	"strings"
	"testing"
)

// checkServer answers the two requests `skill check` makes: the file list (with the manifest digest
// as the ETag) and the archive, addressed by that digest.
func checkServer(t *testing.T, digests, files map[string]string, binaries []string) (*http.ServeMux, string) {
	t.Helper()
	const digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	for name, body := range files {
		file, err := writer.Create(name)
		if err != nil {
			t.Fatalf("building the fixture: %v", err)
		}
		_, _ = file.Write([]byte(body))
	}
	for _, name := range binaries {
		file, err := writer.Create(name)
		if err != nil {
			t.Fatalf("building the fixture: %v", err)
		}
		_, _ = file.Write([]byte{0xff, 0xfe, 0x00})
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("building the fixture: %v", err)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/api/v1/author/skills/dev/pdf-tools/versions/a1b2c3/files",
		func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("ETag", `"sha256:`+digest+`"`)
			w.Header().Set("Content-Type", "application/json")
			var entries []string
			for name := range files {
				entries = append(entries, fmt.Sprintf(`{"relpath":%q,"is_binary":false}`, name))
			}
			for _, name := range binaries {
				entries = append(entries, fmt.Sprintf(`{"relpath":%q,"is_binary":true}`, name))
			}
			fmt.Fprintf(w, `{"files":[%s]}`, strings.Join(entries, ","))
		})
	mux.HandleFunc("/api/v1/author/skills/dev/pdf-tools/versions/sha256:"+digest+"/archive",
		func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "application/zip")
			_, _ = w.Write(buffer.Bytes())
		})
	return mux, digest
}

func TestCheckReportsBrokenReferencesAndExitsOne(t *testing.T) {
	mux, _ := checkServer(t, nil, map[string]string{
		"SKILL.md": "# PDF tools\n\n" +
			"[good](references/checklist.md)\n" +
			"[missing](references/missing.md)\n" +
			"`references/gone.md`\n" +
			"`skill read dev/pdf-tools references/also-gone.md`\n" +
			"[external](https://example.com/x.md)\n" +
			"`skillmaster login`\n",
		"references/checklist.md": "fine\n",
	}, nil)
	pointAtServer(t, mux.ServeHTTP)

	stdout, _, status := runCaptured(t, "skill", "check", "dev/pdf-tools@a1b2c3")
	if status != 1 {
		t.Fatalf("status = %d, want 1\n%s", status, stdout)
	}
	for _, want := range []string{
		"SKILL.md:4: references/missing.md 不在这一版里",
		"SKILL.md:5: references/gone.md 不在这一版里",
		"SKILL.md:6: references/also-gone.md 不在这一版里",
	} {
		if !strings.Contains(stdout, want) {
			t.Errorf("output does not report %q:\n%s", want, stdout)
		}
	}
	for _, unwanted := range []string{"checklist.md", "example.com", "skillmaster login"} {
		if strings.Contains(stdout, unwanted) {
			t.Errorf("output reported %q, which is not a broken reference:\n%s", unwanted, stdout)
		}
	}
}

func TestCheckIsQuietAndExitsZeroOnACleanVersion(t *testing.T) {
	mux, _ := checkServer(t, nil, map[string]string{
		"SKILL.md": "[good](references/checklist.md)\n" +
			// The forms prose actually uses: a titled link, a directory, a code span closed by a
			// backtick, a root-relative `skill read` written inside a nested file.
			"[titled](references/checklist.md \"说明\")\n" +
			"[folder](references/)\n" +
			"读全文：`skill read dev/pdf-tools SKILL.md`\n" +
			"或 `SKILL.md`。\n",
		"references/checklist.md": "fine\n",
		"references/guide.md":     "see skill read dev/pdf-tools references/checklist.md in prose\n",
	}, nil)
	pointAtServer(t, mux.ServeHTTP)

	stdout, stderr, status := runCaptured(t, "skill", "check", "dev/pdf-tools@a1b2c3")
	if status != 0 {
		t.Fatalf("status = %d, want 0 (stdout %q, stderr %q)", status, stdout, stderr)
	}
	if stdout != "" {
		t.Errorf("a clean version printed something:\n%s", stdout)
	}
}

func TestCheckReadsCommandsTheWayProseAndMarkdownWriteThem(t *testing.T) {
	mux, _ := checkServer(t, nil, map[string]string{
		"SKILL.md": "# x\n" +
			// A backtick with Chinese prose glued to it: the span, not the sentence, is the command.
			"详情见 `skill read dev/pdf-tools references/checklist.md`后面的说明\n" +
			// Two commands on one line: each span is its own.
			"用 `skill read dev/pdf-tools references/checklist.md` 或 `skill cat dev/pdf-tools references/gone.md`\n" +
			// A file with no extension is still a file.
			"`skill read dev/pdf-tools LICENSE`\n" +
			// CommonMark destinations may contain balanced parentheses, or be <>-wrapped with spaces.
			"[paren](references/a_(b).md)\n" +
			"[spaced](<references/a b.md>)\n",
		"references/checklist.md": "fine\n",
		"LICENSE":                 "MIT\n",
		"references/a_(b).md":     "fine\n",
		"references/a b.md":       "fine\n",
	}, nil)
	pointAtServer(t, mux.ServeHTTP)

	stdout, _, status := runCaptured(t, "skill", "check", "dev/pdf-tools@a1b2c3")
	if status != 1 {
		t.Fatalf("status = %d, want 1 — only the cat's missing file is broken\n%s", status, stdout)
	}
	if !strings.Contains(stdout, "SKILL.md:3: references/gone.md 不在这一版里") {
		t.Errorf("the second command's path was not reported:\n%s", stdout)
	}
	for _, unwanted := range []string{"checklist.md", "LICENSE", "a_(b).md", "a b.md", "后面的说明"} {
		if strings.Contains(stdout, unwanted) {
			t.Errorf("output reported %q, which exists or is not a path:\n%s", unwanted, stdout)
		}
	}
}

func TestCheckUnderstandsShellSyntaxAndCodeContext(t *testing.T) {
	mux, _ := checkServer(t, nil, map[string]string{
		"SKILL.md": "`skill cat dev/pdf-tools references/checklist.md | grep TODO`\n" +
			"```\n$ skill read dev/pdf-tools references/gone.md\n```\n" +
			"`[说明](references/example.md)`\n" +
			"- skill read dev/pdf-tools LICENSE\n",
		"references/checklist.md": "fine\n",
		"LICENSE":                 "MIT\n",
	}, nil)
	pointAtServer(t, mux.ServeHTTP)

	stdout, _, status := runCaptured(t, "skill", "check", "dev/pdf-tools@a1b2c3")
	if status != 1 {
		t.Fatalf("status = %d, want 1 — only the prompt line's file is missing\n%s", status, stdout)
	}
	if !strings.Contains(stdout, "SKILL.md:3: references/gone.md 不在这一版里") {
		t.Errorf("the `$ skill read` line inside a fence was not checked:\n%s", stdout)
	}
	for _, unwanted := range []string{"|", "grep", "TODO", "example.md", "LICENSE", "说明"} {
		if strings.Contains(stdout, unwanted) {
			t.Errorf("output reported %q, which is shell syntax, an example, or a file:\n%s", unwanted, stdout)
		}
	}
}

func TestCheckIgnoresExamplesAndProseAboutCommands(t *testing.T) {
	mux, _ := checkServer(t, nil, map[string]string{
		"SKILL.md": "````markdown\n```js\n[example](not-a-file.md)\n```\n````\n" +
			"~~~\n```js\n[example](also-not.md)\n```\n~~~\n" +
			"- skill cat outputs every file in order\n" +
			"> skill cat a.md b.md — 首尾相接\n",
	}, nil)
	pointAtServer(t, mux.ServeHTTP)

	stdout, _, status := runCaptured(t, "skill", "check", "dev/pdf-tools@a1b2c3")
	if status != 0 {
		t.Fatalf("status = %d, want 0 — examples and prose are not references\n%s", status, stdout)
	}
	if stdout != "" {
		t.Errorf("a clean version printed something:\n%s", stdout)
	}
}

func TestCheckReportsEveryPathOfACatAndAMissingTitledLink(t *testing.T) {
	mux, _ := checkServer(t, nil, map[string]string{
		"SKILL.md": "`skill cat dev/pdf-tools references/ok.md references/gone.md`\n" +
			"[titled](references/also-gone.md \"说明\")\n",
		"references/ok.md": "fine\n",
	}, nil)
	pointAtServer(t, mux.ServeHTTP)

	stdout, _, status := runCaptured(t, "skill", "check", "dev/pdf-tools@a1b2c3")
	if status != 1 {
		t.Fatalf("status = %d, want 1\n%s", status, stdout)
	}
	for _, want := range []string{
		"SKILL.md:1: references/gone.md 不在这一版里",
		"SKILL.md:2: references/also-gone.md 不在这一版里",
	} {
		if !strings.Contains(stdout, want) {
			t.Errorf("output does not report %q:\n%s", want, stdout)
		}
	}
	if strings.Contains(stdout, "ok.md") {
		t.Errorf("output reported the file that exists:\n%s", stdout)
	}
}

func TestCheckSkipsBinaryFiles(t *testing.T) {
	// The binary's own bytes contain a reference that points nowhere. If the `isBinary` guard were
	// missing, this would be reported — so the test proves the guard, not just the happy path.
	mux := http.NewServeMux()
	digest := strings.Repeat("ab", 32)
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	for name, body := range map[string][]byte{
		"SKILL.md":        []byte("[img](assets/logo.png)\n"),
		"assets/logo.png": append([]byte{0xff, 0xfe, 0x00}, []byte("[broken](nope.md)\n")...),
	} {
		file, err := writer.Create(name)
		if err != nil {
			t.Fatalf("building the fixture: %v", err)
		}
		_, _ = file.Write(body)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("building the fixture: %v", err)
	}
	mux.HandleFunc("/api/v1/author/skills/dev/pdf-tools/versions/a1b2c3/files",
		func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("ETag", `"sha256:`+digest+`"`)
			fmt.Fprintf(w, `{"files":[{"relpath":"SKILL.md","is_binary":false},`+
				`{"relpath":"assets/logo.png","is_binary":true}]}`)
		})
	mux.HandleFunc("/api/v1/author/skills/dev/pdf-tools/versions/sha256:"+digest+"/archive",
		func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write(buffer.Bytes()) })
	pointAtServer(t, mux.ServeHTTP)

	stdout, _, status := runCaptured(t, "skill", "check", "dev/pdf-tools@a1b2c3")
	if status != 0 {
		t.Fatalf("status = %d, want 0 — the binary is present, only unreadable\n%s", status, stdout)
	}
}

func TestCheckSaysToRetryWhenTheDigestNoLongerResolves(t *testing.T) {
	mux, _ := checkServer(t, nil, map[string]string{"SKILL.md": "# x\n"}, nil)
	// Everything but the archive: the list names a digest, and the archive route is gone — which is
	// exactly what a draft edited between the two requests looks like from here.
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/archive") {
			http.NotFound(w, r)
			return
		}
		mux.ServeHTTP(w, r)
	})

	_, stderr, status := runCaptured(t, "skill", "check", "dev/pdf-tools@a1b2c3")
	if status != 1 {
		t.Fatalf("status = %d, want 1", status)
	}
	if !strings.Contains(stderr, "检查期间版本变过") {
		t.Errorf("stderr = %q, want it to ask for a retry", stderr)
	}
}

func TestDigestPinOf(t *testing.T) {
	hex := strings.Repeat("ab", 32)
	for _, tc := range []struct{ etag, want string }{
		{`"sha256:` + hex + `"`, "sha256:" + hex},
		{`W/"sha256:` + hex + `"`, "sha256:" + hex},
		{"", ""},
		{`"` + hex + `"`, ""}, // a bare hex is not a pin the server understands
		{`"sha256:abc"`, ""},  // wrong length
		{`"sha256:` + strings.ToUpper(hex) + `"`, ""}, // digests are lower case
	} {
		if got := digestPinOf(tc.etag); got != tc.want {
			t.Errorf("digestPinOf(%q) = %q, want %q", tc.etag, got, tc.want)
		}
	}
}

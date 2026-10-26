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
			"skill read dev/pdf-tools references/also-gone.md\n" +
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
		"SKILL.md":                "[good](references/checklist.md)\n",
		"references/checklist.md": "fine\n",
	}, nil)
	pointAtServer(t, mux.ServeHTTP)

	stdout, stderr, status := runCaptured(t, "skill", "check", "dev/pdf-tools@a1b2c3")
	if status != 0 {
		t.Fatalf("status = %d, want 0 (stderr %q)", status, stderr)
	}
	if stdout != "" {
		t.Errorf("a clean version printed something:\n%s", stdout)
	}
}

func TestCheckSkipsBinaryFiles(t *testing.T) {
	mux, _ := checkServer(t, nil, map[string]string{
		"SKILL.md": "[img](assets/logo.png)\n",
	}, []string{"assets/logo.png"})
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

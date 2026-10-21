package main

import (
	"archive/zip"
	"bytes"
	"context"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

// testArchive builds the shape the server sends: content entries, then the sidecar last.
func testArchive(t *testing.T) []byte {
	t.Helper()
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	for _, entry := range []struct{ name, body string }{
		{"SKILL.md", "---\nname: pdf-tools\ndescription: d\n---\n# PDF tools\n"},
		{"references/checklist.md", "checklist"},
		{".skillmaster/metadata.json", `{"schema_version":1,"version":{"name":"第一版"}}`},
	} {
		file, err := writer.Create(entry.name)
		if err != nil {
			t.Fatalf("building the fixture: %v", err)
		}
		if _, err := file.Write([]byte(entry.body)); err != nil {
			t.Fatalf("building the fixture: %v", err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("building the fixture: %v", err)
	}
	return buffer.Bytes()
}

func TestDownloadMakesOneRequestAndCountsOnlyContentFiles(t *testing.T) {
	var requests atomic.Int32
	archive := testArchive(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.URL.Path != "/api/v1/author/skills/dev/pdf-tools/versions/1.2.3/archive" {
			t.Errorf("unexpected path %s", r.URL.Path)
		}
		w.Header().Set("ETag", `"sha256:abc"`)
		w.Header().Set("Content-Type", "application/zip")
		_, _ = w.Write(archive)
	})

	target := filepath.Join(t.TempDir(), "out")
	if err := download(context.Background(), []string{"dev/pdf-tools@1.2.3", "--into", target}); err != nil {
		t.Fatalf("download failed: %v", err)
	}

	if got := requests.Load(); got != 1 {
		t.Errorf("download made %d requests; an archive is one request", got)
	}
	for _, relpath := range []string{"SKILL.md", "references/checklist.md", ".skillmaster/metadata.json"} {
		if _, err := os.Stat(filepath.Join(target, filepath.FromSlash(relpath))); err != nil {
			t.Errorf("%s was not written: %v", relpath, err)
		}
	}
	if _, err := os.Stat(target + ".partial"); !os.IsNotExist(err) {
		t.Errorf("the partial directory was left behind")
	}
}

func TestAFailedUnpackLeavesNoPartialDirectory(t *testing.T) {
	// An entry that escapes the target is refused while the earlier entries have already been
	// written into `<target>.partial` — the one failure that could leave half a skill on disk.
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	file, err := writer.Create("SKILL.md")
	if err != nil {
		t.Fatalf("building the fixture: %v", err)
	}
	_, _ = file.Write([]byte("---\nname: pdf-tools\ndescription: d\n---\n"))
	evil, err := writer.Create("../escape.txt")
	if err != nil {
		t.Fatalf("building the fixture: %v", err)
	}
	_, _ = evil.Write([]byte("no"))
	if err := writer.Close(); err != nil {
		t.Fatalf("building the fixture: %v", err)
	}

	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("ETag", `"sha256:abc"`)
		_, _ = w.Write(buffer.Bytes())
	})

	target := filepath.Join(t.TempDir(), "out")
	err = download(context.Background(), []string{"dev/pdf-tools@1.2.3", "--into", target})
	if err == nil {
		t.Fatalf("an unsafe entry was accepted")
	}
	if !strings.Contains(err.Error(), "不安全") {
		t.Errorf("the refusal said %q", err)
	}
	if _, statErr := os.Stat(target); !os.IsNotExist(statErr) {
		t.Errorf("a failed download created %s", target)
	}
	if _, statErr := os.Stat(target + ".partial"); !os.IsNotExist(statErr) {
		t.Errorf("a failed unpack left %s.partial behind", target)
	}
}

// A backslash is a separator on Windows and an ordinary character to `path`, so `..\evil` passes
// the `/`-based checks and would be resolved outside the target there. The check runs on every
// platform, so the test can too.
func TestABackslashEntryIsRefusedAsUnsafe(t *testing.T) {
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	evil, err := writer.Create(`..\evil.txt`)
	if err != nil {
		t.Fatalf("building the fixture: %v", err)
	}
	_, _ = evil.Write([]byte("no"))
	if err := writer.Close(); err != nil {
		t.Fatalf("building the fixture: %v", err)
	}

	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("ETag", `"sha256:abc"`)
		_, _ = w.Write(buffer.Bytes())
	})

	target := filepath.Join(t.TempDir(), "out")
	err = download(context.Background(), []string{"dev/pdf-tools@1.2.3", "--into", target})
	if err == nil {
		t.Fatalf("a backslash entry was accepted")
	}
	if !strings.Contains(err.Error(), "不安全") {
		t.Errorf("the refusal said %q", err)
	}
	if _, statErr := os.Stat(target); !os.IsNotExist(statErr) {
		t.Errorf("a failed download created %s", target)
	}
	if _, statErr := os.Stat(target + ".partial"); !os.IsNotExist(statErr) {
		t.Errorf("a failed unpack left %s.partial behind", target)
	}
}

func TestAReservedPathFileThatIsNotMetadataCountsAsContent(t *testing.T) {
	// A historical version may hold that path as ordinary content (ADR 0069). The server then writes
	// no sidecar, and the file is one of the version's files — `skill ls` lists it, so the count
	// here has to include it.
	dir := t.TempDir()
	metadata := filepath.Join(dir, "sidecar.json")
	content := filepath.Join(dir, "content.json")
	if err := os.WriteFile(metadata, []byte(`{"schema_version":1}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(content, []byte("not metadata"), 0o644); err != nil {
		t.Fatal(err)
	}
	if countsAsContent(metadataRelpath, metadata) {
		t.Errorf("a metadata-shaped file at the reserved path was counted as content")
	}
	if !countsAsContent(metadataRelpath, content) {
		t.Errorf("a content file at the reserved path was counted as the sidecar")
	}
	if !countsAsContent("SKILL.md", metadata) {
		t.Errorf("a metadata-shaped file elsewhere must be content")
	}
}

func TestDownloadReplacesAnExistingEmptyDirectory(t *testing.T) {
	archive := testArchive(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("ETag", `"sha256:abc"`)
		_, _ = w.Write(archive)
	})

	target := filepath.Join(t.TempDir(), "out")
	if err := os.Mkdir(target, 0o755); err != nil {
		t.Fatalf("creating the empty target: %v", err)
	}
	if err := download(context.Background(), []string{"dev/pdf-tools", "--into", target}); err != nil {
		t.Fatalf("download into an empty directory failed: %v", err)
	}
	if _, err := os.Stat(filepath.Join(target, "SKILL.md")); err != nil {
		t.Errorf("the archive was not unpacked into the empty directory: %v", err)
	}
}

func TestDownloadRefusesANonEmptyDirectoryWithoutTouchingIt(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		t.Errorf("a refused target must not reach the server")
	})

	target := filepath.Join(t.TempDir(), "out")
	if err := os.Mkdir(target, 0o755); err != nil {
		t.Fatalf("creating the target: %v", err)
	}
	keep := filepath.Join(target, "mine.txt")
	if err := os.WriteFile(keep, []byte("mine"), 0o644); err != nil {
		t.Fatalf("seeding the target: %v", err)
	}

	err := download(context.Background(), []string{"dev/pdf-tools", "--into", target})
	if err == nil {
		t.Fatalf("a non-empty directory was accepted")
	}
	if _, statErr := os.Stat(keep); statErr != nil {
		t.Errorf("the existing file was touched: %v", statErr)
	}
}

func TestAFailedDownloadWritesNothing(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error":{"code":"invalid_version_state","message":"no"}}`))
	})

	target := filepath.Join(t.TempDir(), "out")
	err := download(context.Background(), []string{"dev/pdf-tools@abc", "--into", target})
	if err == nil {
		t.Fatalf("a refused download returned success")
	}
	// The refusal speaks the CLI's own sentence for this state, not the server's English one: the
	// same words `version` commands use, because it is the same rule (v9 TD §接口 4).
	if err.Error() != versionMessages["invalid_version_state"] {
		t.Errorf("refusal said %q, want %q", err, versionMessages["invalid_version_state"])
	}
	if _, statErr := os.Stat(target); !os.IsNotExist(statErr) {
		t.Errorf("a failed download left something at %s", target)
	}
	if _, statErr := os.Stat(target + ".partial"); !os.IsNotExist(statErr) {
		t.Errorf("a failed download left a partial directory")
	}
}

func TestDownloadRefusesTheCurrentDirectory(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		t.Errorf("a refused target must not reach the server")
	})
	err := download(context.Background(), []string{"dev/pdf-tools", "--into", "."})
	if err == nil || !strings.Contains(err.Error(), "当前目录") {
		t.Fatalf("'.' was accepted: %v", err)
	}
}

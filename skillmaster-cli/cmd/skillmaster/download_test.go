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

// zipEntry returns the *zip.File an entry would be read as, for the counting rule.
func zipEntry(t *testing.T, name, body string) *zip.File {
	t.Helper()
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	file, err := writer.Create(name)
	if err != nil {
		t.Fatalf("building the fixture: %v", err)
	}
	if _, err := file.Write([]byte(body)); err != nil {
		t.Fatalf("building the fixture: %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("building the fixture: %v", err)
	}
	reader, err := zip.NewReader(bytes.NewReader(buffer.Bytes()), int64(buffer.Len()))
	if err != nil {
		t.Fatalf("reading the fixture: %v", err)
	}
	return reader.File[0]
}

// stagingOf lists what is left beside the target: the staging directories a run created and could
// not (or did not have to) put anywhere.
func stagingOf(t *testing.T, target string) []string {
	t.Helper()
	entries, err := os.ReadDir(filepath.Dir(target))
	if err != nil {
		t.Fatalf("reading the target's directory: %v", err)
	}
	var leftovers []string
	for _, entry := range entries {
		if entry.Name() != filepath.Base(target) {
			leftovers = append(leftovers, entry.Name())
		}
	}
	return leftovers
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
	// **A successful download leaves nothing but the target.** The staging directory is consumed by
	// the rename, and no `.partial` exists at all any more.
	if leftovers := stagingOf(t, target); len(leftovers) != 0 {
		t.Errorf("a successful download left %v behind", leftovers)
	}
}

// **A failed unpack leaves its staging behind, and the error says where** (ADR 0101). The CLI does
// not delete, so the alternative — cleaning up the half-written copy — is exactly what this version
// removed. What must not happen is a half skill appearing at the target's name.
func TestAFailedUnpackLeavesItsStagingAndNamesIt(t *testing.T) {
	// An entry that escapes the target is refused while the earlier entries have already been
	// written into this run's staging directory.
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
	leftovers := stagingOf(t, target)
	if len(leftovers) != 1 {
		t.Fatalf("a failed unpack left %v behind; want exactly one staging directory", leftovers)
	}
	staging := filepath.Join(filepath.Dir(target), leftovers[0])
	if _, statErr := os.Stat(staging); statErr != nil {
		t.Errorf("the staging directory named in the leftovers is not there: %v", statErr)
	}
	if !strings.Contains(err.Error(), staging) {
		t.Errorf("the error does not name the half-written directory %s: %q", staging, err)
	}
	if !strings.Contains(err.Error(), "确认后请自行删除") {
		t.Errorf("the error does not say the leftover is the person's to remove: %q", err)
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
	if leftovers := stagingOf(t, target); len(leftovers) != 1 {
		t.Errorf("a failed unpack left %v behind; want exactly one staging directory", leftovers)
	}
}

func TestAReservedPathFileThatIsNotMetadataCountsAsContent(t *testing.T) {
	// A historical version may hold that path as ordinary content (ADR 0069). The server then writes
	// no sidecar, and the file is one of the version's files — `skill files` lists it, so the count
	// here has to include it.
	metadata := zipEntry(t, metadataRelpath, `{"schema_version":1}`)
	content := zipEntry(t, metadataRelpath, "not metadata")
	elsewhere := zipEntry(t, "SKILL.md", `{"schema_version":1}`)

	if countsAsContent(metadataRelpath, metadata) {
		t.Errorf("a metadata-shaped file at the reserved path was counted as content")
	}
	if !countsAsContent(metadataRelpath, content) {
		t.Errorf("a content file at the reserved path was counted as the sidecar")
	}
	if !countsAsContent("SKILL.md", elsewhere) {
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
	// **The old empty directory is moved aside, not deleted** (ADR 0101). `os.Rename` refuses to
	// replace a directory on every platform — Go checks the target itself and never reaches the
	// kernel's rename — so this path always takes the fallback. What is left is the person's to
	// remove, and the command says where it is.
	leftovers := stagingOf(t, target)
	if len(leftovers) != 1 || !strings.Contains(leftovers[0], ".replaced-") {
		t.Fatalf("replacing an empty directory left %v behind; want one .replaced-* directory", leftovers)
	}
	aside := filepath.Join(filepath.Dir(target), leftovers[0])
	entries, err := os.ReadDir(aside)
	if err != nil {
		t.Fatalf("the directory moved aside is not readable: %v", err)
	}
	if len(entries) != 0 {
		t.Errorf("the directory moved aside is not the empty one: %v", entries)
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
	// The refusal happens before any staging directory is made.
	if leftovers := stagingOf(t, target); len(leftovers) != 0 {
		t.Errorf("a refused download left %v behind", leftovers)
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

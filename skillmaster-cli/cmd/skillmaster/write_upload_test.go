package main

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

func tempSizedFile(t *testing.T, size int64) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "file.bin")
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := file.Truncate(size); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestPutRefusalNamesTheFileAndBothNumbers(t *testing.T) {
	local := tempSizedFile(t, maxFileBytes+1)
	got := putRefusal([]string{local}, "references/", map[string]api.File{}, false)
	want := "文件 " + local + " 有 100.0 MiB，超过单文件上限 100 MiB。"
	if got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}

func TestPutRefusalCountsOnlyWhatTheBatchAdds(t *testing.T) {
	existing := map[string]api.File{
		"references/old.bin": {Relpath: "references/old.bin", Size: maxSkillBytes - 1024},
	}
	local := tempSizedFile(t, 2048)
	if got := putRefusal([]string{local}, "references/", existing, false); got == "" {
		t.Fatal("a batch that would pass 512 MiB was accepted")
	}
	// Overwriting the existing path is not a new byte; the batch fits.
	existing = map[string]api.File{
		"references/file.bin": {Relpath: "references/file.bin", Size: maxSkillBytes - 4096},
	}
	if got := putRefusal([]string{local}, "references/", existing, true); got != "" {
		t.Fatalf("overwrite was refused: %s", got)
	}
}

func TestPutRefusalLetsAFittingBatchThrough(t *testing.T) {
	local := tempSizedFile(t, 2*1024*1024)
	if got := putRefusal([]string{local}, "references/", map[string]api.File{}, false); got != "" {
		t.Fatalf("a legal file was refused: %s", got)
	}
}

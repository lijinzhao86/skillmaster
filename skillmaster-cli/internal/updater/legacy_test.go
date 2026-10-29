package updater

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

// **The one compatibility step** (ADR 0113): a binary installed as `skmr` also answers to the name
// it had before v29, so the strings already written into agent files and published skills keep
// working.

func TestEnsureLegacyEntryAddsTheOldName(t *testing.T) {
	dir := t.TempDir()
	name := config.Name
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	target := filepath.Join(dir, name)
	if err := os.WriteFile(target, []byte("binary"), 0o755); err != nil {
		t.Fatal(err)
	}

	if err := EnsureLegacyEntry(target); err != nil {
		t.Fatalf("EnsureLegacyEntry: %v", err)
	}
	legacy := filepath.Join(dir, legacyNameFor(runtime.GOOS))
	if _, err := os.Lstat(legacy); err != nil {
		t.Fatalf("the old name was not created: %v", err)
	}
	// The old name has to *be* this binary, not merely exist: a copy written empty or from the wrong
	// file would pass a bare `Lstat` check on Windows and only fail on the machine it shipped to.
	if want, err := os.ReadFile(target); err != nil {
		t.Fatal(err)
	} else if got, err := os.ReadFile(legacy); err != nil || string(got) != string(want) {
		t.Errorf("the old name reads %q (err %v), want %q", got, err, want)
	}
	if runtime.GOOS != "windows" {
		resolvedAlias, err := filepath.EvalSymlinks(legacy)
		resolvedTarget, targetErr := filepath.EvalSymlinks(target)
		if err != nil || targetErr != nil || resolvedAlias != resolvedTarget {
			t.Errorf("the old name resolves to %q (err %v), want %q (err %v)", resolvedAlias, err, resolvedTarget, targetErr)
		}
	}
}

func TestEnsureLegacyEntryLeavesAnExistingFileAlone(t *testing.T) {
	dir := t.TempDir()
	name := config.Name
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	target := filepath.Join(dir, name)
	legacy := filepath.Join(dir, legacyNameFor(runtime.GOOS))
	for path, body := range map[string]string{target: "new", legacy: "somebody else's file"} {
		if err := os.WriteFile(path, []byte(body), 0o755); err != nil {
			t.Fatal(err)
		}
	}

	if err := EnsureLegacyEntry(target); err != nil {
		t.Fatalf("EnsureLegacyEntry: %v", err)
	}
	if body, _ := os.ReadFile(legacy); string(body) != "somebody else's file" {
		t.Errorf("the existing file was replaced: %q — this program does not delete", body)
	}
}

func TestEnsureLegacyEntryIgnoresABuildUnderAnotherName(t *testing.T) {
	dir := t.TempDir()
	other := filepath.Join(dir, "some-build")
	if err := os.WriteFile(other, []byte("dev"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := EnsureLegacyEntry(other); err != nil {
		t.Fatalf("EnsureLegacyEntry: %v", err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 {
		t.Errorf("a build under another name got a legacy entry: %d entries", len(entries))
	}
}

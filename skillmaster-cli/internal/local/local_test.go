package local

import (
	"os"
	"path/filepath"
	"testing"
)

func mkSkill(t *testing.T, dir, name string) {
	t.Helper()
	path := filepath.Join(dir, name)
	if err := os.MkdirAll(path, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(path, "SKILL.md"), []byte("---\nname: "+name+"\n---\n"), 0o644); err != nil {
		t.Fatal(err)
	}
}

// One level, dot entries skipped, missing directories are zero and not an error.
func TestScanReadsOneLevelAndSkipsDotEntries(t *testing.T) {
	root := t.TempDir()
	mkSkill(t, root, "coding")
	mkSkill(t, filepath.Join(root, "coding"), "nested")   // a skill's own sub-structure
	mkSkill(t, filepath.Join(root, ".system"), "bundled") // the client's own, not the user's

	found, err := Scan([]string{root})
	if err != nil {
		t.Fatal(err)
	}
	if len(found) != 1 || found[0].Name != "coding" {
		t.Fatalf("Scan = %+v, want exactly coding", found)
	}
	if found[0].Tag != "" {
		t.Errorf("an explicit directory came back tagged %q", found[0].Tag)
	}
}

func TestScanTreatsAMissingDirectoryAsEmpty(t *testing.T) {
	found, err := Scan([]string{filepath.Join(t.TempDir(), "nope")})
	if err != nil {
		t.Fatalf("a missing directory is the ordinary case: %v", err)
	}
	if len(found) != 0 {
		t.Fatalf("found %d skills in nothing", len(found))
	}
}

// The candidate table is scanned when nothing is named, and its tags say where each skill came from.
func TestScanWithoutArgumentsUsesTheTableAndTagsTheSource(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	mkSkill(t, filepath.Join(home, ".claude", "skills"), "demo")

	found, err := Scan(nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(found) != 1 || found[0].Name != "demo" {
		t.Fatalf("Scan = %+v, want one demo skill", found)
	}
	if found[0].Tag != "agent:claude" {
		t.Errorf("tag = %q, want agent:claude", found[0].Tag)
	}
}

// A skill directory linked in from a checkout is still a skill directory: its SKILL.md is
// readable, and `entry.IsDir()` is false for the link itself.
func TestScanFollowsALinkedSkillDirectory(t *testing.T) {
	source := t.TempDir()
	mkSkill(t, source, "linked")

	root := t.TempDir()
	if err := os.Symlink(filepath.Join(source, "linked"), filepath.Join(root, "linked")); err != nil {
		t.Skipf("symlinks unavailable here: %v", err)
	}

	found, err := Scan([]string{root})
	if err != nil {
		t.Fatal(err)
	}
	if len(found) != 1 || found[0].Name != "linked" {
		t.Fatalf("Scan = %+v, want the linked skill to count", found)
	}
}

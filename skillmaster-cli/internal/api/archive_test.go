package api

// The archive a submission carries: what it includes, what it refuses, and the permission bits a
// script needs to survive the round trip (v51, ADR 0166 — the server derives them from the path now,
// but a self-describing archive is still what a person expects to find).

import (
	"archive/zip"
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestArchiveCarriesTheTreeWithForwardSlashes(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "SKILL.md"), "# hello\n")
	write(t, filepath.Join(dir, "references", "a.md"), "a\n")
	write(t, filepath.Join(dir, "references", "b.md"), "b\n")

	archive, err := Archive(dir)
	if err != nil {
		t.Fatalf("Archive: %v", err)
	}

	names := entryNames(t, archive)
	want := []string{"SKILL.md", "references/a.md", "references/b.md"}
	if len(names) != len(want) {
		t.Fatalf("archive holds %v, want %v", names, want)
	}
	for i, name := range want {
		if names[i] != name {
			t.Errorf("entry %d is %q, want %q", i, names[i], name)
		}
	}

	// Same tree, same bytes — not required for correctness (the server's digest is over the file
	// set), but it makes two archives comparable when something does look wrong.
	again, err := Archive(dir)
	if err != nil {
		t.Fatalf("Archive again: %v", err)
	}
	if !bytes.Equal(archive, again) {
		t.Error("archiving the same tree twice produced different bytes")
	}
}

// **The executable bit is part of the file.** `zip.Writer.Create` writes an entry with no unix
// mode, and the server reads that as 0644 — so a script the author ran as `./run.sh` arrives as a
// plain file. This is the last place the mode exists on this machine.
func TestArchiveCarriesTheExecutableBit(t *testing.T) {
	dir := t.TempDir()
	write(t, filepath.Join(dir, "SKILL.md"), "# hello\n")
	script := filepath.Join(dir, "scripts", "run.sh")
	write(t, script, "#!/bin/sh\necho hi\n")
	if err := os.Chmod(script, 0o755); err != nil {
		t.Fatalf("chmod: %v", err)
	}

	archive, err := Archive(dir)
	if err != nil {
		t.Fatalf("Archive: %v", err)
	}
	reader, err := zip.NewReader(bytes.NewReader(archive), int64(len(archive)))
	if err != nil {
		t.Fatalf("opening the archive: %v", err)
	}
	modes := map[string]os.FileMode{}
	for _, entry := range reader.File {
		modes[entry.Name] = entry.Mode()
	}
	if mode := modes["scripts/run.sh"]; mode.Perm()&0o111 == 0 {
		t.Errorf("scripts/run.sh is %v in the archive, want an executable mode", mode)
	}
	if mode := modes["SKILL.md"]; mode.Perm()&0o111 != 0 {
		t.Errorf("SKILL.md is %v in the archive, want a plain file mode", mode)
	}
}

func TestArchiveRefusesWhatTheFormatCannotCarry(t *testing.T) {
	t.Run("no SKILL.md", func(t *testing.T) {
		dir := t.TempDir()
		write(t, filepath.Join(dir, "notes.md"), "x")
		// Courtesy only — the server refuses this too and is the authority (ADR 0011). This exists
		// so the answer arrives before a possibly large upload.
		if _, err := Archive(dir); err == nil {
			t.Fatal("a directory with no SKILL.md was archived")
		}
	})

	t.Run("a symlink", func(t *testing.T) {
		dir := t.TempDir()
		write(t, filepath.Join(dir, "SKILL.md"), "# hello\n")
		if err := os.Symlink("/etc/hosts", filepath.Join(dir, "escape")); err != nil {
			t.Skipf("cannot create a symlink here: %v", err)
		}
		if _, err := Archive(dir); err == nil {
			t.Fatal("a symlink was archived; it would upload as its target's contents")
		}
	})

	t.Run("a file where a directory was expected", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "SKILL.md")
		write(t, path, "x")
		if _, err := Archive(path); err == nil {
			t.Fatal("a file was archived as if it were a directory")
		}
	})

	t.Run("a directory reached through a symlink", func(t *testing.T) {
		// The root escaped the check above, because `WalkDir` Lstats its own root and does not descend
		// into a link — so the callback returned early and the archive came out **valid and empty**.
		// The server then answered that the upload contained no files, which blames the skill for a
		// link. `ln -s ~/dev/my-skill ~/.claude/skills/my-skill` is an ordinary way to work, so this
		// is a name a person types.
		dir := t.TempDir()
		real := filepath.Join(dir, "real")
		if err := os.MkdirAll(real, 0o755); err != nil {
			t.Fatal(err)
		}
		write(t, filepath.Join(real, "SKILL.md"), "# hello\n")
		link := filepath.Join(dir, "linked")
		if err := os.Symlink(real, link); err != nil {
			t.Skipf("cannot create a symlink here: %v", err)
		}

		archive, err := Archive(link)
		if err == nil {
			t.Fatalf("a symlinked directory was archived as %d bytes rather than refused", len(archive))
		}
	})

	t.Run("a directory reached through a symlink, spelled with a trailing separator", func(t *testing.T) {
		// `Lstat("link/")` resolves the final component — a trailing separator asks the kernel what
		// the *path* denotes rather than lstat-ing the entry itself — so the refusal above did not
		// fire for `submit link/` while it did for `submit link`. `Clean` makes the two spellings
		// take the same path. The per-file rule inside the walk has no such hole: it judges entries.
		dir := t.TempDir()
		real := filepath.Join(dir, "real")
		if err := os.MkdirAll(real, 0o755); err != nil {
			t.Fatal(err)
		}
		write(t, filepath.Join(real, "SKILL.md"), "# hello\n")
		link := filepath.Join(dir, "linked")
		if err := os.Symlink(real, link); err != nil {
			t.Skipf("cannot create a symlink here: %v", err)
		}

		archive, err := Archive(link + string(filepath.Separator))
		if err == nil {
			t.Fatalf("a symlinked directory with a trailing separator was archived as %d bytes", len(archive))
		}
	})
}

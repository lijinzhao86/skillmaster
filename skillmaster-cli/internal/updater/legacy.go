package updater

import (
	"os"
	"path/filepath"
	"runtime"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

// EnsureLegacyEntry gives this binary its pre-v29 name, next to itself.
//
// **One line of compatibility, and it lives in the code rather than in the documents.** Nothing
// depends on it: `skmr` is the name in every command, every hint and every page. But a machine set
// up before the rename still has `skillmaster …` written into an agent memory file, and a skill
// published earlier still says it in its body — a second name for the same file turns those into
// working calls instead of "command not found", and costs nothing when nobody uses it.
//
// **It only creates, and it creates in one step.** A name that already exists, as a file or as a
// link, is left exactly as it is (ADR 0101: this program does not delete, and it does not decide
// somebody else's file is wrong). The new name is made by a single `symlink` (Unix) or `link`
// (Windows — a hard link, because a symlink there needs a privilege this program never asks for),
// so two processes starting at once cannot both win, nothing is ever truncated or written through,
// and there is no window in which the old name exists half-written.
func EnsureLegacyEntry(target string) error {
	if filepath.Base(target) != config.Name && filepath.Base(target) != config.Name+".exe" {
		return nil // a build that is not installed under our name: not ours to arrange
	}
	legacy := filepath.Join(filepath.Dir(target), legacyNameFor(runtime.GOOS))
	if _, err := os.Lstat(legacy); err == nil {
		return nil
	} else if !os.IsNotExist(err) {
		return err
	}
	if runtime.GOOS != "windows" {
		return os.Symlink(filepath.Base(target), legacy)
	}
	// No symlink without a privilege this program never asks for; a hard link needs none, is the
	// same file to everything that matters, and — unlike a copy — cannot be written half-way.
	return os.Link(target, legacy)
}

func legacyNameFor(goos string) string {
	if goos == "windows" {
		return config.LegacyName + ".exe"
	}
	return config.LegacyName
}

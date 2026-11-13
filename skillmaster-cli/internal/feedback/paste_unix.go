//go:build unix

package feedback

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// sessionImageDirs is where Claude Code keeps the images pasted into the current conversation.
//
// **This is a best-effort adapter, not a contract.** The documented location is
// `<CLAUDE_CODE_TMPDIR>/claude-<uid>/<project>/<session>/images/` (the docs describe the temp
// directory, not a handoff of the path to the model), and older versions used
// `~/.claude/image-cache/<session>/`. The CLI looks in the current project's current session only;
// if the session id is absent it falls back to the current project's session directories, newest
// file first. The agent still reads the result before sending it, so a wrong pick is caught.
func sessionImageDirs() []string {
	cwd, err := os.Getwd()
	if err != nil {
		return nil
	}
	slug := strings.ReplaceAll(cwd, string(os.PathSeparator), "-")
	session := os.Getenv("CLAUDE_CODE_SESSION_ID")
	if session == "" {
		session = os.Getenv("CLAUDE_SESSION_ID")
	}

	var bases []string
	seen := map[string]bool{}
	addBase := func(base string) {
		if base != "" && !seen[base] {
			seen[base] = true
			bases = append(bases, base)
		}
	}
	addBase("/tmp")
	addBase(os.Getenv("CLAUDE_CODE_TMPDIR"))
	addBase(os.TempDir())

	var dirs []string
	for _, base := range bases {
		root := filepath.Join(base, fmt.Sprintf("claude-%d", os.Getuid()))
		if session != "" {
			dirs = append(dirs, filepath.Join(root, slug, session, "images"))
			continue
		}
		matches, _ := filepath.Glob(filepath.Join(root, slug, "*", "images"))
		dirs = append(dirs, matches...)
	}
	if home, err := os.UserHomeDir(); err == nil && session != "" {
		dirs = append(dirs, filepath.Join(home, ".claude", "image-cache", session))
	}
	return dirs
}

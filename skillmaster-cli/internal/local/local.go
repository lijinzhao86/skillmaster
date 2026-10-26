// Package local finds the skills that already live on this machine.
//
// **One scanner, one reader today.** `startup` counts what it finds for its state line. `skills
// import` and `skills retire` are still unbuilt (v17 #207 / #209) and will read the same rule when
// they land — a second implementation would eventually disagree about what a local skill is, and
// the disagreement would show up as a skill that "was imported" and then "could not be retired":
//
//   - a skill is `<dir>/<name>/SKILL.md` — one level, never recursive (a skill's own nested
//     SKILL.md files are its sub-structure, not siblings; ADR 0089's v17 PRD measured 33 vs 43 on a
//     real directory);
//   - entries starting with `.` are skipped (client bundles such as `.system` are not the user's);
//   - a directory that does not exist is zero skills, not an error — that is the normal state on a
//     machine that has only some of the eighteen clients installed.
package local

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// Candidate is one directory the ecosystem convention puts skills in, with the tag that records
// where a skill came from.
//
// **The tag is provenance, not compatibility.** Everything under `~/.claude/skills` is readable by
// Codex, Cursor and Zed too; `agent:claude` says which directory it was found in, not who may use
// it (v17 PRD §搬进来时打什么标签).
type Candidate struct {
	Path string
	Tag  string
}

// candidates is the table from the v17 PRD, in its order. A path that does not exist is simply
// skipped later.
var candidates = []Candidate{
	{"~/.agents/skills", "agent:common"},
	{"~/.claude/skills", "agent:claude"},
	{"~/.codex/skills", "agent:codex"},
	{"~/.cursor/skills", "agent:cursor"},
	{"~/.gemini/skills", "agent:gemini"},
	{"~/.copilot/skills", "agent:copilot"},
	{"~/.config/opencode/skills", "agent:opencode"},
	{"~/.codeium/windsurf/skills", "agent:windsurf"},
	{"~/.config/amp/skills", "agent:amp"},
	{"~/.config/agents/skills", "agent:amp"},
	{"~/.kiro/skills", "agent:kiro"},
	{"~/.cline/skills", "agent:cline"},
	{"~/.roo/skills", "agent:roo"},
	{"~/.qwen/skills", "agent:qwen"},
	{"~/.openhands/skills", "agent:openhands"},
	{"~/.factory/skills", "agent:factory"},
	{"~/.workbuddy/skills", "agent:workbuddy"},
	{"~/.dsh/skills", "agent:dsh"},
}

// Candidates returns the table with `~` expanded.
func Candidates() ([]Candidate, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return nil, err
	}
	expanded := make([]Candidate, 0, len(candidates))
	for _, candidate := range candidates {
		expanded = append(expanded, Candidate{
			Path: filepath.Join(home, strings.TrimPrefix(candidate.Path, "~/")),
			Tag:  candidate.Tag,
		})
	}
	return expanded, nil
}

// Skill is one directory on this machine that the conventions call a skill.
type Skill struct {
	Name string
	Path string
	// Tag is the source directory's tag, or empty for a directory outside the table — those are
	// reported untagged rather than given an invented word (v17 PRD §三条规则).
	Tag string
}

// Scan returns the skills in the given directories. Empty means the table's candidates; anything
// given is scanned as-is and comes back untagged, because the table is the only thing that names a
// source.
func Scan(dirs []string) ([]Skill, error) {
	tags := map[string]string{}
	if len(dirs) == 0 {
		expanded, err := Candidates()
		if err != nil {
			return nil, err
		}
		for _, candidate := range expanded {
			dirs = append(dirs, candidate.Path)
			tags[candidate.Path] = candidate.Tag
		}
	}

	var found []Skill
	for _, dir := range dirs {
		entries, err := os.ReadDir(dir)
		if err != nil {
			if os.IsNotExist(err) {
				continue
			}
			return nil, err
		}
		for _, entry := range entries {
			name := entry.Name()
			if strings.HasPrefix(name, ".") {
				continue
			}
			path := filepath.Join(dir, name)
			// `os.Stat` rather than `entry.IsDir()`: the latter is false for a symlink, and a
			// directory linked into `~/.claude/skills` from a checkout is a common way to keep a
			// skill in revision control. Its `SKILL.md` is readable, so it is a local skill.
			if info, err := os.Stat(path); err != nil || !info.IsDir() {
				continue
			}
			if _, err := os.Stat(filepath.Join(path, "SKILL.md")); err != nil {
				continue
			}
			found = append(found, Skill{Name: name, Path: path, Tag: tags[dir]})
		}
	}
	sort.Slice(found, func(i, j int) bool { return found[i].Path < found[j].Path })
	return found, nil
}

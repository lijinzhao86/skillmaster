package agentconfig

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	toml "github.com/pelletier/go-toml/v2"
)

// codexSpec is the one TOML client. **Its file is re-marshalled, so comments do not survive** —
// go-toml/v2 has no comment-preserving writer. The backup is what makes that recoverable, and
// `--dry-run` says so before anything is written.
type codexSpec struct {
	dir string
}

func codexClient(w Want) (codexSpec, bool) {
	dir := envDir("CODEX_HOME", filepath.Join(w.Home, ".codex"))
	if info, err := os.Stat(dir); err == nil && info.IsDir() {
		return codexSpec{dir: dir}, true
	}
	return codexSpec{}, false
}

func runCodex(c codexSpec, w Want, undo, dryRun bool) Change {
	file := filepath.Join(c.dir, "config.toml")
	ch := Change{Client: "Codex", File: file}

	blob, err := os.ReadFile(file)
	existed := err == nil
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		ch.Skip = "配置读不懂，已跳过"
		ch.Err = err
		return ch
	}
	// **Do not create this file.** Writing `sandbox_mode = "workspace-write"` into a Codex that
	// has no config is creating a sandbox switch, not narrowing one (ADR 0186 §决定 1/2 与 §后果); the old
	// form needs that mode to let `writable_roots` take effect, and the mode also makes the
	// workspace writable. A machine with no Codex config has nothing this command should turn on.
	if !existed || strings.TrimSpace(string(blob)) == "" {
		ch.Skip = "没有可用的 config.toml，已跳过（新建它会打开工作区可写）"
		return ch
	}
	root := map[string]any{}
	if err := toml.Unmarshal(blob, &root); err != nil {
		ch.Skip = "配置读不懂，已跳过"
		ch.Err = fmt.Errorf("parsing %s: %w", file, err)
		return ch
	}

	// **Two schemes, and they must not be mixed** (research §1.2). A file that already uses the
	// newer `default_permissions` / `[permissions.<profile>]` form is left alone: adding the old
	// `sandbox_mode` beside it would give Codex two answers to the same question.
	if _, ok := root["default_permissions"]; ok {
		ch.Skip = "已用新式 permissions 配置，没有混写"
		return ch
	}
	if _, ok := root["permissions"]; ok {
		ch.Skip = "已用新式 permissions 配置，没有混写"
		return ch
	}

	var e edit
	section := map[string]any{}
	if existing, ok := root["sandbox_workspace_write"]; ok {
		table, ok := existing.(map[string]any)
		if !ok {
			ch.Skip = "sandbox_workspace_write 不是表，已跳过"
			return ch
		}
		section = table
	}
	roots, hadRoots, problem := readList(section, "writable_roots")
	if problem != "" {
		ch.Skip = "sandbox_workspace_write." + problem + "，已跳过"
		return ch
	}
	for _, p := range w.WritePaths {
		if undo {
			roots, e.removed, e.kept = removeOrKeep(roots, p, e.removed, e.kept)
		} else {
			roots, e.added, e.kept = addOrKeep(roots, p, e.added, e.kept)
		}
	}
	if len(roots) > 0 || hadRoots {
		section["writable_roots"] = roots
	}
	if !undo {
		if on, _ := section["network_access"].(bool); !on {
			section["network_access"] = true
			e.toggled = append(e.toggled, "network_access")
		}
		// `danger-full-access` is already at least as permissive; leave it alone. Everything else,
		// including an absent value (which defaults to read-only), becomes workspace-write.
		switch root["sandbox_mode"] {
		case nil, "", "read-only":
			root["sandbox_mode"] = "workspace-write"
			e.toggled = append(e.toggled, "sandbox_mode")
		}
	}
	if len(section) > 0 {
		root["sandbox_workspace_write"] = section
	}
	if mode, _ := root["sandbox_mode"].(string); mode == "workspace-write" {
		ch.WorkspaceWrite = true
	}

	ch.Added, ch.Removed, ch.Kept, ch.Toggled = e.added, e.removed, e.kept, e.toggled
	ch.Processed = true
	if !ch.Changed() || dryRun {
		return ch
	}
	if existed {
		if err := backup(file); err != nil {
			ch.Err = err
			return ch
		}
	}
	out, err := toml.Marshal(root)
	if err != nil {
		ch.Err = err
		return ch
	}
	if err := writeConfigFile(file, out); err != nil {
		ch.Err = err
	}
	return ch
}

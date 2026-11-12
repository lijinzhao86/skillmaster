// Package agentconfig writes the narrow sandbox allowlist this CLI needs into the configuration of
// the agents it runs under (ADR 0186).
//
// **Narrow on purpose.** Only two things are granted: the state directory (read and write) and the
// hosts this CLI contacts. Nothing is deleted, no other path is touched, and a file this package
// cannot parse is left exactly as it was. The keys and shapes come from
// docs/research/agent-sandboxes.md; a client whose key is not listed there is not written here.
//
// **Only the file formats the client actually uses are implemented here.** The JSON clients share
// one runner; Codex is TOML and lives in codex.go.
package agentconfig

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

// Want is what the caller asks for, in terms no client owns.
type Want struct {
	Home       string
	WritePaths []string // absolute paths
	Hosts      []string // bare hostnames
}

// Change is one client's outcome. An empty Added, Removed and Toggled means the file was not
// written.
type Change struct {
	Client  string
	File    string
	Added   []string
	Removed []string
	Kept    []string
	// Toggled names the switches this run turned on (e.g. `allowLocalBinding`). They are tracked
	// separately because a client can need only a switch: a configuration that already lists the
	// path and the hosts still has to be written when the switch is the part that is missing.
	Toggled []string
	// Processed says the client's configuration was read and understood, even when this run had
	// nothing to change. It is what lets the caller warn about a client whose grant is a total
	// network switch that was already on — the warning is about the client, not about this run.
	Processed bool
	// WorkspaceWrite is Codex's effective old-form sandbox mode. It is reported even when this
	// run did not set it, for the same reason Processed exists: the warning is about the boundary
	// the client is in, not about who put it there.
	WorkspaceWrite bool
	Skip           string
	Err            error
}

func (c Change) Changed() bool {
	return len(c.Added) > 0 || len(c.Removed) > 0 || len(c.Toggled) > 0
}

// Apply runs every client. A client that is not installed is skipped; a client whose file cannot be
// parsed is skipped with an error, never overwritten. dryRun reports what would change without
// touching anything.
func Apply(w Want, undo, dryRun bool) []Change {
	w.Hosts = nonEmptyHosts(w.Hosts)
	var changes []Change
	for _, c := range jsonClients(w) {
		changes = append(changes, runJSON(c, w, undo, dryRun))
	}
	if c, ok := codexClient(w); ok {
		changes = append(changes, runCodex(c, w, undo, dryRun))
	}
	return changes
}

type jsonClient struct {
	name   string
	dir    func(Want) string
	file   string
	mutate func(root map[string]any, w Want, undo bool) edit
}

// edit is one mutate's answer. `skip` means "this client was left alone", and it is a single string
// on purpose: a person reads one reason per client, not a list.
type edit struct {
	added, removed, kept, toggled []string
	skip                          string
}

func runJSON(c jsonClient, w Want, undo, dryRun bool) Change {
	dir := c.dir(w)
	file := filepath.Join(dir, c.file)
	ch := Change{Client: c.name, File: file}
	if info, err := os.Stat(dir); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			ch.Skip = "没有安装"
			return ch
		}
		ch.Skip = "目录读不了"
		ch.Err = err
		return ch
	} else if !info.IsDir() {
		ch.Skip = "没有安装"
		return ch
	}
	root, existed, err := readJSON(file)
	if err != nil {
		ch.Skip = "配置读不懂，已跳过"
		ch.Err = err
		return ch
	}
	// **`--undo` must not leave empty parents behind.** The mutation helpers create missing levels
	// so a fix can write into them; on the way out that would add `"network": {}` to a file that
	// never had one. The tree before the mutation is the only record of what this run created.
	var before map[string]any
	if undo {
		before = cloneTree(root)
	}
	out := c.mutate(root, w, undo)
	if undo && before != nil {
		pruneNewEmptyObjects(root, before)
	}
	ch.Added, ch.Removed, ch.Kept, ch.Toggled = out.added, out.removed, out.kept, out.toggled
	if out.skip != "" {
		ch.Skip = out.skip
		return ch
	}
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
	if err := writeJSON(file, root); err != nil {
		ch.Err = err
	}
	return ch
}

func jsonClients(w Want) []jsonClient {
	home := w.Home
	return []jsonClient{
		{
			name: "WorkBuddy",
			dir:  func(Want) string { return filepath.Join(home, ".workbuddy") },
			file: "settings.json",
			mutate: func(root map[string]any, w Want, undo bool) edit {
				var e edit
				sandbox, ok := ensureObject(root, "sandbox")
				if !ok {
					return edit{skip: "sandbox 不是对象，已跳过"}
				}
				paths, hadPaths, problem := readList(sandbox, "extraAllowWrite")
				if problem != "" {
					return edit{skip: problem + "，已跳过"}
				}
				for _, p := range w.WritePaths {
					display := tildePath(p, w.Home)
					if undo {
						paths, e.removed, e.kept = removeOrKeep(paths, display, e.removed, e.kept)
					} else {
						paths, e.added, e.kept = addOrKeep(paths, display, e.added, e.kept)
					}
				}
				storeSlice(sandbox, "extraAllowWrite", paths, hadPaths)

				ordered, ok := ensureObject(sandbox, "orderedRules")
				if !ok {
					return edit{skip: "sandbox.orderedRules 不是对象，已跳过"}
				}
				network, ok := ensureObject(ordered, "network")
				if !ok {
					return edit{skip: "sandbox.orderedRules.network 不是对象，已跳过"}
				}
				rules, hadRules, problem := readList(network, "rules")
				if problem != "" {
					return edit{skip: problem + "，已跳过"}
				}
				for _, host := range w.Hosts {
					if undo {
						rules, e.removed, e.kept = removeDomainRule(rules, host, e.removed, e.kept)
						continue
					}
					allowed, denied := domainRuleStatus(rules, host)
					if denied {
						// **A refusal is reported, not papered over.** Adding an allow rule beside a
						// deny rule with the same domain depends on rule order this command cannot
						// see, and saying "已是最新" would be false: the host is still blocked.
						return edit{skip: "已有 " + host + " 的拒绝规则，未改动"}
					}
					if allowed {
						e.kept = append(e.kept, host)
						continue
					}
					rules = append(rules, map[string]any{
						"id": config.LegacyName + "-" + host, "source": "user", "domain": host, "action": "allow",
					})
					e.added = append(e.added, host)
				}
				storeSlice(network, "rules", rules, hadRules)
				return e
			},
		},
		{
			name: "Claude Code",
			dir: func(want Want) string {
				return envDir("CLAUDE_CONFIG_DIR", filepath.Join(home, ".claude"))
			},
			file: "settings.json",
			mutate: func(root map[string]any, w Want, undo bool) edit {
				var e edit
				sandbox, ok := ensureObject(root, "sandbox")
				if !ok {
					return edit{skip: "sandbox 不是对象，已跳过"}
				}
				filesystem, ok := ensureObject(sandbox, "filesystem")
				if !ok {
					return edit{skip: "sandbox.filesystem 不是对象，已跳过"}
				}
				paths, hadPaths, problem := readList(filesystem, "allowWrite")
				if problem != "" {
					return edit{skip: "sandbox.filesystem." + problem + "，已跳过"}
				}
				for _, p := range w.WritePaths {
					display := tildePath(p, w.Home)
					if undo {
						paths, e.removed, e.kept = removeOrKeep(paths, display, e.removed, e.kept)
					} else {
						paths, e.added, e.kept = addOrKeep(paths, display, e.added, e.kept)
					}
				}
				storeSlice(filesystem, "allowWrite", paths, hadPaths)

				network, ok := ensureObject(sandbox, "network")
				if !ok {
					return edit{skip: "sandbox.network 不是对象，已跳过"}
				}
				domains, hadDomains, problem := readList(network, "allowedDomains")
				if problem != "" {
					return edit{skip: "sandbox.network." + problem + "，已跳过"}
				}
				for _, host := range w.Hosts {
					if undo {
						domains, e.removed, e.kept = removeOrKeep(domains, host, e.removed, e.kept)
					} else {
						domains, e.added, e.kept = addOrKeep(domains, host, e.added, e.kept)
					}
				}
				storeSlice(network, "allowedDomains", domains, hadDomains)
				if !undo {
					// **The loopback half is a switch, and switches count as changes.** A settings
					// file that already lists the path and the hosts used to be reported as
					// "已是最新" here, leaving the login port blocked on macOS.
					if on, _ := network["allowLocalBinding"].(bool); !on {
						network["allowLocalBinding"] = true
						e.toggled = append(e.toggled, "allowLocalBinding")
					}
				}
				return e
			},
		},
		{
			name: "Gemini",
			dir:  func(Want) string { return filepath.Join(home, ".gemini") },
			file: "settings.json",
			mutate: func(root map[string]any, w Want, undo bool) edit {
				tools, ok := ensureObject(root, "tools")
				if !ok {
					return edit{skip: "tools 不是对象，已跳过"}
				}
				existing, present := tools["sandbox"]
				if !present {
					// **A sandbox that is off has nothing to allow.** Creating `tools.sandbox`
					// would turn Gemini's whole-process sandbox on — a larger change than this
					// command promises, and a switch `--undo` could not put back.
					return edit{skip: "没有开启工具沙箱，无需放行"}
				}
				sandbox, ok := existing.(map[string]any)
				if !ok {
					return edit{skip: "tools.sandbox 不是对象，跳过（可能是布尔或命令字符串）"}
				}
				var e edit
				paths, hadPaths, problem := readList(sandbox, "allowedPaths")
				if problem != "" {
					return edit{skip: "tools.sandbox." + problem + "，已跳过"}
				}
				for _, p := range w.WritePaths {
					if undo {
						paths, e.removed, e.kept = removeOrKeep(paths, p, e.removed, e.kept)
					} else {
						paths, e.added, e.kept = addOrKeep(paths, p, e.added, e.kept)
					}
				}
				storeSlice(sandbox, "allowedPaths", paths, hadPaths)
				if !undo {
					if on, _ := sandbox["networkAccess"].(bool); !on {
						sandbox["networkAccess"] = true
						e.toggled = append(e.toggled, "networkAccess")
					}
				}
				return e
			},
		},
		{
			name: "Cursor",
			dir:  func(Want) string { return filepath.Join(home, ".cursor") },
			file: "sandbox.json",
			mutate: func(root map[string]any, w Want, undo bool) edit {
				var e edit
				paths, hadPaths, problem := readList(root, "additionalReadwritePaths")
				if problem != "" {
					return edit{skip: problem + "，已跳过"}
				}
				for _, p := range w.WritePaths {
					if undo {
						paths, e.removed, e.kept = removeOrKeep(paths, p, e.removed, e.kept)
					} else {
						paths, e.added, e.kept = addOrKeep(paths, p, e.added, e.kept)
					}
				}
				storeSlice(root, "additionalReadwritePaths", paths, hadPaths)

				network, ok := ensureObject(root, "networkPolicy")
				if !ok {
					return edit{skip: "networkPolicy 不是对象，已跳过"}
				}
				// **`networkPolicy.default` is not written.** Cursor's default is already `deny`
				// (research §1.2), so setting it adds a key without changing behaviour — and it is
				// exactly the kind of extra write `--undo` could not tell apart from the person's.
				allow, hadAllow, problem := readList(network, "allow")
				if problem != "" {
					return edit{skip: "networkPolicy." + problem + "，已跳过"}
				}
				for _, host := range w.Hosts {
					if undo {
						allow, e.removed, e.kept = removeOrKeep(allow, host, e.removed, e.kept)
					} else {
						allow, e.added, e.kept = addOrKeep(allow, host, e.added, e.kept)
					}
				}
				storeSlice(network, "allow", allow, hadAllow)
				return e
			},
		},
		{
			name: "Devin",
			dir:  func(Want) string { return filepath.Join(home, ".config", "devin") },
			file: "config.json",
			mutate: func(root map[string]any, w Want, undo bool) edit {
				var e edit
				permissions, ok := ensureObject(root, "permissions")
				if !ok {
					return edit{skip: "permissions 不是对象，已跳过"}
				}
				rules, hadRules, problem := readList(permissions, "allow")
				if problem != "" {
					return edit{skip: "permissions." + problem + "，已跳过"}
				}
				for _, p := range w.WritePaths {
					rule := "Write(" + p + "/**)"
					if undo {
						rules, e.removed, e.kept = removeOrKeep(rules, rule, e.removed, e.kept)
					} else {
						rules, e.added, e.kept = addOrKeep(rules, rule, e.added, e.kept)
					}
				}
				storeSlice(permissions, "allow", rules, hadRules)

				sandbox, ok := ensureObject(root, "sandbox")
				if !ok {
					return edit{skip: "sandbox 不是对象，已跳过"}
				}
				domains, hadDomains, problem := readList(sandbox, "allowed_domains")
				if problem != "" {
					return edit{skip: "sandbox." + problem + "，已跳过"}
				}
				for _, host := range w.Hosts {
					if undo {
						domains, e.removed, e.kept = removeOrKeep(domains, host, e.removed, e.kept)
					} else {
						domains, e.added, e.kept = addOrKeep(domains, host, e.added, e.kept)
					}
				}
				storeSlice(sandbox, "allowed_domains", domains, hadDomains)
				return e
			},
		},
		{
			name: "Zed",
			dir:  func(Want) string { return filepath.Join(home, ".config", "zed") },
			file: "settings.json",
			mutate: func(root map[string]any, w Want, undo bool) edit {
				var e edit
				agent, ok := ensureObject(root, "agent")
				if !ok {
					return edit{skip: "agent 不是对象，已跳过"}
				}
				permissions, ok := ensureObject(agent, "sandbox_permissions")
				if !ok {
					return edit{skip: "agent.sandbox_permissions 不是对象，已跳过"}
				}
				paths, hadPaths, problem := readList(permissions, "write_paths")
				if problem != "" {
					return edit{skip: "agent.sandbox_permissions." + problem + "，已跳过"}
				}
				for _, p := range w.WritePaths {
					if undo {
						paths, e.removed, e.kept = removeOrKeep(paths, p, e.removed, e.kept)
					} else {
						paths, e.added, e.kept = addOrKeep(paths, p, e.added, e.kept)
					}
				}
				storeSlice(permissions, "write_paths", paths, hadPaths)

				hosts, hadHosts, problem := readList(permissions, "network_hosts")
				if problem != "" {
					return edit{skip: "agent.sandbox_permissions." + problem + "，已跳过"}
				}
				for _, host := range w.Hosts {
					// **Zed rejects IP literals and localhost in this list** (research §1.2), so a
					// development server is left out rather than written where it would be refused.
					// The same filter applies to undo: `--fix` never added such an entry, so undo
					// must not delete one the person wrote.
					if !hostnameOnly(host) {
						continue
					}
					if undo {
						hosts, e.removed, e.kept = removeOrKeep(hosts, host, e.removed, e.kept)
					} else {
						hosts, e.added, e.kept = addOrKeep(hosts, host, e.added, e.kept)
					}
				}
				storeSlice(permissions, "network_hosts", hosts, hadHosts)
				return e
			},
		},
		{
			name: "Copilot CLI",
			dir: func(want Want) string {
				return envDir("COPILOT_HOME", filepath.Join(home, ".copilot"))
			},
			file: "settings.json",
			mutate: func(root map[string]any, w Want, undo bool) edit {
				var e edit
				sandbox, ok := ensureObject(root, "sandbox")
				if !ok {
					return edit{skip: "sandbox 不是对象，已跳过"}
				}
				policy, ok := ensureObject(sandbox, "userPolicy")
				if !ok {
					return edit{skip: "sandbox.userPolicy 不是对象，已跳过"}
				}
				filesystem, ok := ensureObject(policy, "filesystem")
				if !ok {
					return edit{skip: "sandbox.userPolicy.filesystem 不是对象，已跳过"}
				}
				paths, hadPaths, problem := readList(filesystem, "readwritePaths")
				if problem != "" {
					return edit{skip: "sandbox.userPolicy.filesystem." + problem + "，已跳过"}
				}
				for _, p := range w.WritePaths {
					if undo {
						paths, e.removed, e.kept = removeOrKeep(paths, p, e.removed, e.kept)
					} else {
						paths, e.added, e.kept = addOrKeep(paths, p, e.added, e.kept)
					}
				}
				storeSlice(filesystem, "readwritePaths", paths, hadPaths)

				network, ok := ensureObject(policy, "network")
				if !ok {
					return edit{skip: "sandbox.userPolicy.network 不是对象，已跳过"}
				}
				hosts, hadHosts, problem := readList(network, "allowedHosts")
				if problem != "" {
					return edit{skip: "sandbox.userPolicy.network." + problem + "，已跳过"}
				}
				for _, host := range w.Hosts {
					if undo {
						hosts, e.removed, e.kept = removeOrKeep(hosts, host, e.removed, e.kept)
					} else {
						hosts, e.added, e.kept = addOrKeep(hosts, host, e.added, e.kept)
					}
				}
				storeSlice(network, "allowedHosts", hosts, hadHosts)
				return e
			},
		},
	}
}

// envDir is a directory env var overrides, e.g. CLAUDE_CONFIG_DIR.
func envDir(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

// tildePath spells a path under the home directory the way the two clients that accept `~/` expect.
func tildePath(abs, home string) string {
	rel, err := filepath.Rel(home, abs)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return abs
	}
	return "~/" + filepath.ToSlash(rel)
}

// --- generic helpers -------------------------------------------------------

func readJSON(path string) (map[string]any, bool, error) {
	blob, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return map[string]any{}, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	if len(strings.TrimSpace(string(blob))) == 0 {
		return map[string]any{}, true, nil
	}
	var root map[string]any
	// **Numbers are kept as written** (`UseNumber`): a JSON round trip through `float64` silently
	// changes integers above 2^53, and "the keys we did not touch are unchanged" is a promise this
	// command makes.
	decoder := json.NewDecoder(bytes.NewReader(stripJSONC(blob)))
	decoder.UseNumber()
	if err := decoder.Decode(&root); err != nil {
		return nil, true, fmt.Errorf("parsing %s: %w", path, err)
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return nil, true, fmt.Errorf("parsing %s: unexpected data after the document", path)
	}
	if root == nil {
		root = map[string]any{}
	}
	return root, true, nil
}

// stripJSONC removes `//` and `/* */` comments and trailing commas outside strings. **Zed's
// settings file is JSONC** — `encoding/json` refuses a comment, which would make every Zed user who
// has one fail to configure. The rewrite this package performs drops the comments anyway (the
// re-serialised file is plain JSON), which the backup and `--dry-run` warning cover.
func stripJSONC(blob []byte) []byte {
	return stripTrailingCommas(stripComments(blob))
}

func stripComments(blob []byte) []byte {
	out := make([]byte, 0, len(blob))
	inString, escaped := false, false
	for i := 0; i < len(blob); i++ {
		c := blob[i]
		if inString {
			out = append(out, c)
			switch {
			case escaped:
				escaped = false
			case c == '\\':
				escaped = true
			case c == '"':
				inString = false
			}
			continue
		}
		switch {
		case c == '"':
			inString = true
			out = append(out, c)
		case c == '/' && i+1 < len(blob) && blob[i+1] == '/':
			for i < len(blob) && blob[i] != '\n' {
				i++
			}
			if i < len(blob) {
				out = append(out, '\n')
			}
		case c == '/' && i+1 < len(blob) && blob[i+1] == '*':
			i += 2
			for i+1 < len(blob) && !(blob[i] == '*' && blob[i+1] == '/') {
				i++
			}
			i++
			out = append(out, ' ')
		default:
			out = append(out, c)
		}
	}
	return out
}

func stripTrailingCommas(blob []byte) []byte {
	out := make([]byte, 0, len(blob))
	inString, escaped := false, false
	for i := 0; i < len(blob); i++ {
		c := blob[i]
		if inString {
			out = append(out, c)
			switch {
			case escaped:
				escaped = false
			case c == '\\':
				escaped = true
			case c == '"':
				inString = false
			}
			continue
		}
		if c == '"' {
			inString = true
			out = append(out, c)
			continue
		}
		if c == ',' {
			j := i + 1
			for j < len(blob) && (blob[j] == ' ' || blob[j] == '\t' || blob[j] == '\n' || blob[j] == '\r') {
				j++
			}
			if j < len(blob) && (blob[j] == ']' || blob[j] == '}') {
				continue
			}
		}
		out = append(out, c)
	}
	return out
}

// cloneTree is a deep copy through the same decoder the reader uses, so `json.Number` survives.
func cloneTree(root map[string]any) map[string]any {
	blob, err := json.Marshal(root)
	if err != nil {
		return nil
	}
	decoder := json.NewDecoder(bytes.NewReader(blob))
	decoder.UseNumber()
	var out map[string]any
	if err := decoder.Decode(&out); err != nil {
		return nil
	}
	return out
}

// pruneNewEmptyObjects removes object levels this run created and left empty. A level that existed
// before is kept even when it is empty — that shape is the person's.
func pruneNewEmptyObjects(root, before map[string]any) {
	if root == nil {
		return
	}
	for key, value := range root {
		nested, ok := value.(map[string]any)
		if !ok {
			continue
		}
		beforeNested, had := before[key].(map[string]any)
		pruneNewEmptyObjects(nested, beforeNested)
		if len(nested) == 0 && !had {
			delete(root, key)
		}
	}
}

func writeJSON(path string, root map[string]any) error {
	blob, err := json.MarshalIndent(root, "", "  ")
	if err != nil {
		return err
	}
	return writeConfigFile(path, append(blob, '\n'))
}

// writeFile writes in place. **No neighbour and no rename**: replacing a name is a directory
// operation that a sandbox can refuse even when the file itself is writable (WorkBuddy 5.8.6,
// measured 2026-11-11), and this command is meant to work there. A symlinked settings file stays a
// symlink because `OpenFile` follows it; the backup is what covers an interrupted write.
func writeConfigFile(path string, blob []byte) error {
	mode := os.FileMode(0o600)
	created := false
	if info, err := os.Stat(path); err == nil {
		mode = info.Mode().Perm()
	} else if os.IsNotExist(err) {
		created = true
	}
	// Open without O_TRUNC and truncate only once the file is open: a failed open must leave the
	// previous bytes alone. The write itself still has a truncate→write window (ADR 0186 §后果).
	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY, mode)
	if err != nil {
		return fmt.Errorf("writing %s: %w", path, err)
	}
	if err := file.Truncate(0); err != nil {
		file.Close()
		kept := "the old file is still there"
		if created {
			kept = "this run did not write it; run `skmr doctor --fix` again"
		}
		return fmt.Errorf("writing %s (%s): %w", path, kept, err)
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		file.Close()
		return fmt.Errorf("writing %s (it may now be half-written; %s): %w",
			path, restoreHint(path), err)
	}
	if _, err := file.Write(blob); err != nil {
		file.Close()
		return fmt.Errorf("writing %s (it may now be half-written; %s): %w",
			path, restoreHint(path), err)
	}
	if err := file.Close(); err != nil {
		return fmt.Errorf("closing %s (it may now be half-written; %s): %w",
			path, restoreHint(path), err)
	}
	return nil
}

// backupPath is the fixed name `backup` writes. One function so the failure message cannot name a
// different file than the one that was actually created.
func backupPath(path string) string {
	return path + "." + config.LegacyName + ".bak"
}

// restoreHint names the backup only when there is one. A client whose settings file did not exist
// has no backup (backup is only taken when the file was already there), so pointing at `.bak`
// would send the person to a file that was never created.
func restoreHint(path string) string {
	if info, err := os.Lstat(backupPath(path)); err == nil && info.Mode().IsRegular() && info.Size() > 0 {
		return "restore from " + backupPath(path)
	}
	// A half-written file without a backup is not fixed by running the same command again: the
	// parser will refuse it. The CLI never deletes a file, so the person has to.
	return "restore or remove that file yourself, then run `skmr doctor --fix`"
}

// backup copies the file beside itself, **once**. It is never removed (ADR 0101), and the fixed name
// keeps a sandbox's approval stable instead of asking about a new random path every run.
//
// **An existing backup is left alone.** `--fix` followed by `--fix --undo` is the documented way to
// undo, and rewriting the backup on the second run would replace the only copy taken before
// SkillMaster touched the file (for Codex, with its comments already gone).
func backup(path string) error {
	blob, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("reading %s to back it up: %w", path, err)
	}
	bak := backupPath(path)
	file, err := os.OpenFile(bak, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if errors.Is(err, os.ErrExist) {
		// **Only a regular, non-empty file is a usable backup.** A dangling symlink, a directory,
		// or a path that cannot be inspected is not "the first copy": silently accepting it means
		// writing the config with no recovery point at all, which is the opposite of what this
		// function promises. Fail closed and let `--fix` report the client as failed.
		info, statErr := os.Lstat(bak)
		switch {
		case statErr != nil:
			return fmt.Errorf("backing up %s: %s exists but cannot be inspected: %w", path, bak, statErr)
		case !info.Mode().IsRegular():
			return fmt.Errorf("backing up %s: %s exists and is not a regular file", path, bak)
		case info.Size() > 0:
			return nil
		default:
			// **A zero-byte backup is a failed attempt, not a copy.** Without this, a backup
			// interrupted by a full disk would be treated as the first copy forever, and the only
			// recovery point would be an empty file. A genuinely empty config also produces an
			// empty backup; the next run then overwrites that empty backup with the file as it is
			// then (after the previous fix). Losing an empty original costs no bytes; keeping a
			// half-written copy as the only recovery point costs the real content.
			file, err = os.OpenFile(bak, os.O_WRONLY|os.O_TRUNC, 0o600)
		}
	}
	if err != nil {
		return fmt.Errorf("backing up %s: %w", path, err)
	}
	if _, err := file.Write(blob); err != nil {
		file.Close()
		_ = os.Truncate(bak, 0) // leave the marker, not a half copy
		return fmt.Errorf("backing up %s: %w", path, err)
	}
	if err := file.Close(); err != nil {
		_ = os.Truncate(bak, 0)
		return fmt.Errorf("backing up %s: %w", path, err)
	}
	return nil
}

// ensureObject walks to a nested object, creating missing levels. **An existing value of another
// type is a refusal, not something to replace**: a `sandbox: true` or a string is the person's
// setting, and overwriting it with `{}` would destroy it while reporting success.
func ensureObject(root map[string]any, keys ...string) (map[string]any, bool) {
	current := root
	for _, key := range keys {
		// A JSON `null` means "no value", the same way `readList` reads it. Treating it as a
		// refusal would skip a whole client over a key that says nothing.
		if existing, ok := current[key]; ok && existing != nil {
			next, ok := existing.(map[string]any)
			if !ok {
				return nil, false
			}
			current = next
			continue
		}
		next := map[string]any{}
		current[key] = next
		current = next
	}
	return current, true
}

// toSlice reports the list and whether the key was there at all. The second half is what lets
// `--undo` delete a key it created instead of leaving `null` in the person's file.
func toSlice(value any) ([]any, bool) {
	if value == nil {
		return nil, false
	}
	list, ok := value.([]any)
	if !ok {
		return nil, true
	}
	return list, true
}

// readList is `toSlice` for a key this package is about to write. **A key that exists and is not a
// list is a refusal**: overwriting a string or an object with an array would destroy the person's
// setting, and `--undo` would turn it into `[]`.
func readList(parent map[string]any, key string) (list []any, had bool, problem string) {
	value, ok := parent[key]
	if !ok || value == nil {
		return nil, false, ""
	}
	list, ok = value.([]any)
	if !ok {
		return nil, true, key + " 不是数组"
	}
	return list, true, ""
}

// nonEmptyHosts drops empty entries an empty or malformed server setting can produce. An empty
// string in a client's allowlist is not a grant, and some clients refuse the whole file for it.
func nonEmptyHosts(hosts []string) []string {
	out := make([]string, 0, len(hosts))
	for _, host := range hosts {
		if host != "" {
			out = append(out, host)
		}
	}
	return out
}

// storeSlice writes the list back, and removes the key when this run created it and left it empty.
func storeSlice(parent map[string]any, key string, list []any, had bool) {
	if len(list) > 0 || had {
		parent[key] = list
		return
	}
	delete(parent, key)
}

func addOrKeep(list []any, value string, added, kept []string) ([]any, []string, []string) {
	if hasString(list, value) {
		return list, added, append(kept, value)
	}
	return append(list, value), append(added, value), kept
}

func removeOrKeep(list []any, value string, removed, kept []string) ([]any, []string, []string) {
	out := list[:0]
	found := false
	for _, item := range list {
		if text, ok := item.(string); ok && text == value {
			found = true
			continue
		}
		out = append(out, item)
	}
	if !found {
		return out, removed, append(kept, value)
	}
	return out, append(removed, value), kept
}

func hasString(list []any, value string) bool {
	for _, item := range list {
		if text, ok := item.(string); ok && text == value {
			return true
		}
	}
	return false
}

// domainRuleStatus says whether the host is already allowed and whether something denies it.
// **An existing rule with the same domain is not automatically "already allowed"**: WorkBuddy's
// rules carry an action, and a deny would keep the host blocked while `--fix` reports success.
func domainRuleStatus(rules []any, host string) (allowed, denied bool) {
	for _, rule := range rules {
		entry, ok := rule.(map[string]any)
		if !ok {
			continue
		}
		domain, ok := entry["domain"].(string)
		if !ok || !strings.EqualFold(domain, host) {
			continue
		}
		switch action, _ := entry["action"].(string); strings.ToLower(action) {
		case "deny", "block":
			denied = true
		default:
			allowed = true
		}
	}
	return allowed, denied
}

// hasDomainRule is the "already allowed" half, kept for the tests' reading.
func hasDomainRule(rules []any, host string) bool {
	allowed, _ := domainRuleStatus(rules, host)
	return allowed
}

// hostnameOnly is what Zed's `network_hosts` accepts: a hostname or a `*.` wildcard, never an IP
// literal or localhost (research §1.2). Those are skipped rather than written to be rejected.
func hostnameOnly(host string) bool {
	return host != "" && !strings.EqualFold(host, "localhost") && net.ParseIP(host) == nil
}

func removeDomainRule(rules []any, host string, removed, kept []string) ([]any, []string, []string) {
	out := rules[:0]
	found := false
	for _, rule := range rules {
		entry, ok := rule.(map[string]any)
		if ok {
			id, _ := entry["id"].(string)
			domain, _ := entry["domain"].(string)
			if strings.EqualFold(domain, host) && strings.HasPrefix(id, config.LegacyName+"-") {
				found = true
				continue
			}
		}
		out = append(out, rule)
	}
	if !found {
		return out, removed, append(kept, host)
	}
	return out, append(removed, host), kept
}

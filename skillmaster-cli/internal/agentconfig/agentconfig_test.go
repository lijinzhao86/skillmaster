package agentconfig

import (
	"crypto/sha256"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	toml "github.com/pelletier/go-toml/v2"
)

// TestMain keeps these tests off this machine's real client configurations. The package reads
// CLAUDE_CONFIG_DIR / CODEX_HOME / COPILOT_HOME when it decides which directory to write, and a
// developer who exports one of them would have `go test` edit their own settings.
func TestMain(m *testing.M) {
	for _, name := range []string{"CLAUDE_CONFIG_DIR", "CODEX_HOME", "COPILOT_HOME"} {
		os.Unsetenv(name)
	}
	os.Exit(m.Run())
}

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatalf("mkdir %s: %v", filepath.Dir(path), err)
	}
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}

func readJSONFile(t *testing.T, path string) map[string]any {
	t.Helper()
	blob, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	var root map[string]any
	if err := json.Unmarshal(blob, &root); err != nil {
		t.Fatalf("parse %s: %v", path, err)
	}
	return root
}

func changeFor(changes []Change, client string) (Change, bool) {
	for _, change := range changes {
		if change.Client == client {
			return change, true
		}
	}
	return Change{}, false
}

func want(home string) Want {
	return Want{
		Home:       home,
		WritePaths: []string{filepath.Join(home, "Library", "Application Support", "skillmaster")},
		Hosts:      []string{"console.example.com", "dl.example.com"},
	}
}

func TestApplyWorkBuddy(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".workbuddy", "settings.json")
	writeFile(t, file, `{
  "sandbox": {
    "extraAllowWrite": ["~/.keep"],
    "orderedRules": {"network": {"rules": []}}
  },
  "keep": {"x": 1}
}`)
	w := want(home)

	changes := Apply(w, false, false)
	change, ok := changeFor(changes, "WorkBuddy")
	if !ok || !change.Changed() || change.Err != nil {
		t.Fatalf("WorkBuddy change = %+v", change)
	}
	root := readJSONFile(t, file)
	sandbox := root["sandbox"].(map[string]any)
	paths := sandbox["extraAllowWrite"].([]any)
	if !hasString(paths, "~/Library/Application Support/skillmaster") || !hasString(paths, "~/.keep") {
		t.Fatalf("extraAllowWrite = %v", paths)
	}
	rules := sandbox["orderedRules"].(map[string]any)["network"].(map[string]any)["rules"].([]any)
	if !hasDomainRule(rules, "console.example.com") || !hasDomainRule(rules, "dl.example.com") {
		t.Fatalf("network rules = %v", rules)
	}
	// The test reads the file with plain `encoding/json` (float64); the package writes it with
	// `UseNumber`. The value has to survive both readings.
	if got := root["keep"].(map[string]any)["x"]; got != float64(1) {
		t.Fatalf("an unrelated key changed: %#v", got)
	}

	if again, _ := changeFor(Apply(w, false, false), "WorkBuddy"); again.Changed() {
		t.Fatalf("second apply changed the file: %+v", again)
	}

	if undone, _ := changeFor(Apply(w, true, false), "WorkBuddy"); !undone.Changed() {
		t.Fatalf("undo changed nothing")
	}
	root = readJSONFile(t, file)
	paths = root["sandbox"].(map[string]any)["extraAllowWrite"].([]any)
	if hasString(paths, "~/Library/Application Support/skillmaster") || !hasString(paths, "~/.keep") {
		t.Fatalf("after undo extraAllowWrite = %v", paths)
	}
	rules = root["sandbox"].(map[string]any)["orderedRules"].(map[string]any)["network"].(map[string]any)["rules"].([]any)
	if hasDomainRule(rules, "console.example.com") {
		t.Fatalf("after undo network rules = %v", rules)
	}
}

func TestApplyClaudeCode(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".claude", "settings.json")
	writeFile(t, file, `{"sandbox":{"filesystem":{"allowWrite":["/tmp/keep"]},"network":{"allowedDomains":["old.example.com"]}}}`)
	w := want(home)

	change, _ := changeFor(Apply(w, false, false), "Claude Code")
	if !change.Changed() || change.Err != nil {
		t.Fatalf("Claude Code change = %+v", change)
	}
	root := readJSONFile(t, file)
	sandbox := root["sandbox"].(map[string]any)
	filesystem := sandbox["filesystem"].(map[string]any)
	paths := filesystem["allowWrite"].([]any)
	if !hasString(paths, "~/Library/Application Support/skillmaster") || !hasString(paths, "/tmp/keep") {
		t.Fatalf("allowWrite = %v", paths)
	}
	network := sandbox["network"].(map[string]any)
	domains := network["allowedDomains"].([]any)
	if !hasString(domains, "console.example.com") || !hasString(domains, "old.example.com") {
		t.Fatalf("allowedDomains = %v", domains)
	}
	if network["allowLocalBinding"] != true {
		t.Fatalf("allowLocalBinding = %v", network["allowLocalBinding"])
	}
	if again, _ := changeFor(Apply(w, false, false), "Claude Code"); again.Changed() {
		t.Fatalf("second apply changed the file: %+v", again)
	}

	Apply(w, true, false)
	root = readJSONFile(t, file)
	filesystem = root["sandbox"].(map[string]any)["filesystem"].(map[string]any)
	if paths := filesystem["allowWrite"].([]any); hasString(paths, "~/Library/Application Support/skillmaster") || !hasString(paths, "/tmp/keep") {
		t.Fatalf("after undo allowWrite = %v", paths)
	}
}

func TestApplyGemini(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".gemini", "settings.json")
	writeFile(t, file, `{"tools":{"sandbox":{"enabled":true,"allowedPaths":["/tmp/keep"]}}}`)
	w := want(home)

	change, _ := changeFor(Apply(w, false, false), "Gemini")
	if !change.Changed() || change.Err != nil {
		t.Fatalf("Gemini change = %+v", change)
	}
	root := readJSONFile(t, file)
	sandbox := root["tools"].(map[string]any)["sandbox"].(map[string]any)
	if paths := sandbox["allowedPaths"].([]any); !hasString(paths, w.WritePaths[0]) || !hasString(paths, "/tmp/keep") {
		t.Fatalf("allowedPaths = %v", paths)
	}
	if sandbox["networkAccess"] != true {
		t.Fatalf("networkAccess = %v", sandbox["networkAccess"])
	}
	if again, _ := changeFor(Apply(w, false, false), "Gemini"); again.Changed() {
		t.Fatalf("second apply changed the file: %+v", again)
	}
	if undone, _ := changeFor(Apply(w, true, false), "Gemini"); !undone.Changed() {
		t.Fatalf("undo changed nothing")
	}
	sandbox = readJSONFile(t, file)["tools"].(map[string]any)["sandbox"].(map[string]any)
	if paths, _ := toSlice(sandbox["allowedPaths"]); hasString(paths, w.WritePaths[0]) || !hasString(paths, "/tmp/keep") {
		t.Fatalf("after undo allowedPaths = %v", paths)
	}
}

func TestApplyCursor(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".cursor", "sandbox.json")
	writeFile(t, file, `{"additionalReadwritePaths":["/tmp/keep"],"networkPolicy":{"allow":["old.example.com"]}}`)
	w := want(home)

	change, _ := changeFor(Apply(w, false, false), "Cursor")
	if !change.Changed() || change.Err != nil {
		t.Fatalf("Cursor change = %+v", change)
	}
	root := readJSONFile(t, file)
	if paths := root["additionalReadwritePaths"].([]any); !hasString(paths, w.WritePaths[0]) || !hasString(paths, "/tmp/keep") {
		t.Fatalf("additionalReadwritePaths = %v", paths)
	}
	network := root["networkPolicy"].(map[string]any)
	if allow := network["allow"].([]any); !hasString(allow, "console.example.com") || !hasString(allow, "old.example.com") {
		t.Fatalf("allow = %v", allow)
	}
	if _, ok := network["default"]; ok {
		t.Fatalf("networkPolicy.default was written: %v", network["default"])
	}
	if again, _ := changeFor(Apply(w, false, false), "Cursor"); again.Changed() {
		t.Fatalf("second apply changed the file: %+v", again)
	}
	if undone, _ := changeFor(Apply(w, true, false), "Cursor"); !undone.Changed() {
		t.Fatalf("undo changed nothing")
	}
	root = readJSONFile(t, file)
	if paths, _ := toSlice(root["additionalReadwritePaths"]); hasString(paths, w.WritePaths[0]) || !hasString(paths, "/tmp/keep") {
		t.Fatalf("after undo additionalReadwritePaths = %v", paths)
	}
	if allow, _ := toSlice(root["networkPolicy"].(map[string]any)["allow"]); hasString(allow, "console.example.com") || !hasString(allow, "old.example.com") {
		t.Fatalf("after undo allow = %v", allow)
	}
}

func TestApplyDevin(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".config", "devin", "config.json")
	writeFile(t, file, `{"permissions":{"allow":["Read(src/**)"]},"sandbox":{"allowed_domains":["old.example.com"]}}`)
	w := want(home)

	change, _ := changeFor(Apply(w, false, false), "Devin")
	if !change.Changed() || change.Err != nil {
		t.Fatalf("Devin change = %+v", change)
	}
	root := readJSONFile(t, file)
	allow := root["permissions"].(map[string]any)["allow"].([]any)
	if !hasString(allow, "Write("+w.WritePaths[0]+"/**)") || !hasString(allow, "Read(src/**)") {
		t.Fatalf("allow = %v", allow)
	}
	domains := root["sandbox"].(map[string]any)["allowed_domains"].([]any)
	if !hasString(domains, "console.example.com") {
		t.Fatalf("allowed_domains = %v", domains)
	}
	if again, _ := changeFor(Apply(w, false, false), "Devin"); again.Changed() {
		t.Fatalf("second apply changed the file: %+v", again)
	}
	if undone, _ := changeFor(Apply(w, true, false), "Devin"); !undone.Changed() {
		t.Fatalf("undo changed nothing")
	}
	root = readJSONFile(t, file)
	allow, _ = toSlice(root["permissions"].(map[string]any)["allow"])
	if hasString(allow, "Write("+w.WritePaths[0]+"/**)") || !hasString(allow, "Read(src/**)") {
		t.Fatalf("after undo allow = %v", allow)
	}
	domains, _ = toSlice(root["sandbox"].(map[string]any)["allowed_domains"])
	if hasString(domains, "console.example.com") || !hasString(domains, "old.example.com") {
		t.Fatalf("after undo allowed_domains = %v", domains)
	}
}

func TestApplyZed(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".config", "zed", "settings.json")
	writeFile(t, file, `{"agent":{"sandbox_permissions":{"write_paths":["/tmp/keep"],"network_hosts":["old.example.com"]}}}`)
	w := want(home)

	change, _ := changeFor(Apply(w, false, false), "Zed")
	if !change.Changed() || change.Err != nil {
		t.Fatalf("Zed change = %+v", change)
	}
	root := readJSONFile(t, file)
	permissions := root["agent"].(map[string]any)["sandbox_permissions"].(map[string]any)
	if paths := permissions["write_paths"].([]any); !hasString(paths, w.WritePaths[0]) {
		t.Fatalf("write_paths = %v", paths)
	}
	if hosts := permissions["network_hosts"].([]any); !hasString(hosts, "dl.example.com") {
		t.Fatalf("network_hosts = %v", hosts)
	}
	if again, _ := changeFor(Apply(w, false, false), "Zed"); again.Changed() {
		t.Fatalf("second apply changed the file: %+v", again)
	}
	if undone, _ := changeFor(Apply(w, true, false), "Zed"); !undone.Changed() {
		t.Fatalf("undo changed nothing")
	}
	permissions = readJSONFile(t, file)["agent"].(map[string]any)["sandbox_permissions"].(map[string]any)
	if paths, _ := toSlice(permissions["write_paths"]); hasString(paths, w.WritePaths[0]) || !hasString(paths, "/tmp/keep") {
		t.Fatalf("after undo write_paths = %v", paths)
	}
	if hosts, _ := toSlice(permissions["network_hosts"]); hasString(hosts, "dl.example.com") || !hasString(hosts, "old.example.com") {
		t.Fatalf("after undo network_hosts = %v", hosts)
	}
}

func TestApplyCopilot(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".copilot", "settings.json")
	writeFile(t, file, `{"sandbox":{"userPolicy":{"filesystem":{"readwritePaths":["/tmp/keep"]},"network":{"allowedHosts":["old.example.com"]}}}}`)
	w := want(home)

	change, _ := changeFor(Apply(w, false, false), "Copilot CLI")
	if !change.Changed() || change.Err != nil {
		t.Fatalf("Copilot change = %+v", change)
	}
	root := readJSONFile(t, file)
	policy := root["sandbox"].(map[string]any)["userPolicy"].(map[string]any)
	paths := policy["filesystem"].(map[string]any)["readwritePaths"].([]any)
	if !hasString(paths, w.WritePaths[0]) || !hasString(paths, "/tmp/keep") {
		t.Fatalf("readwritePaths = %v", paths)
	}
	hosts := policy["network"].(map[string]any)["allowedHosts"].([]any)
	if !hasString(hosts, "console.example.com") || !hasString(hosts, "old.example.com") {
		t.Fatalf("allowedHosts = %v", hosts)
	}
	if again, _ := changeFor(Apply(w, false, false), "Copilot CLI"); again.Changed() {
		t.Fatalf("second apply changed the file: %+v", again)
	}
	if undone, _ := changeFor(Apply(w, true, false), "Copilot CLI"); !undone.Changed() {
		t.Fatalf("undo changed nothing")
	}
	policy = readJSONFile(t, file)["sandbox"].(map[string]any)["userPolicy"].(map[string]any)
	if paths, _ := toSlice(policy["filesystem"].(map[string]any)["readwritePaths"]); hasString(paths, w.WritePaths[0]) || !hasString(paths, "/tmp/keep") {
		t.Fatalf("after undo readwritePaths = %v", paths)
	}
	if hosts, _ := toSlice(policy["network"].(map[string]any)["allowedHosts"]); hasString(hosts, "console.example.com") || !hasString(hosts, "old.example.com") {
		t.Fatalf("after undo allowedHosts = %v", hosts)
	}
}

func TestApplyCodex(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".codex", "config.toml")
	writeFile(t, file, "# keep this comment\nmodel = \"gpt-x\"\n\n[sandbox_workspace_write]\nwritable_roots = [\"/tmp/keep\"]\n")
	w := want(home)

	change, _ := changeFor(Apply(w, false, false), "Codex")
	if !change.Changed() || change.Err != nil {
		t.Fatalf("Codex change = %+v", change)
	}
	if !change.WorkspaceWrite {
		t.Fatalf("Codex change did not report workspace-write: %+v", change)
	}
	blob, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	var root map[string]any
	if err := toml.Unmarshal(blob, &root); err != nil {
		t.Fatalf("parse result: %v", err)
	}
	if root["sandbox_mode"] != "workspace-write" {
		t.Fatalf("sandbox_mode = %v", root["sandbox_mode"])
	}
	section := root["sandbox_workspace_write"].(map[string]any)
	if section["network_access"] != true {
		t.Fatalf("network_access = %v", section["network_access"])
	}
	if roots, _ := toSlice(section["writable_roots"]); !hasString(roots, w.WritePaths[0]) || !hasString(roots, "/tmp/keep") {
		t.Fatalf("writable_roots = %v", roots)
	}
	if root["model"] != "gpt-x" {
		t.Fatalf("model = %v", root["model"])
	}
	if again, _ := changeFor(Apply(w, false, false), "Codex"); again.Changed() {
		t.Fatalf("second apply changed the file: %+v", again)
	}

	Apply(w, true, false)
	blob, _ = os.ReadFile(file)
	root = map[string]any{}
	if err := toml.Unmarshal(blob, &root); err != nil {
		t.Fatalf("parse after undo: %v", err)
	}
	roots, _ := toSlice(root["sandbox_workspace_write"].(map[string]any)["writable_roots"])
	if hasString(roots, w.WritePaths[0]) || !hasString(roots, "/tmp/keep") {
		t.Fatalf("after undo writable_roots = %v", roots)
	}
}

func TestBadFileIsLeftAlone(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".claude", "settings.json")
	writeFile(t, file, "this is not json")
	before := sha256.Sum256(mustRead(t, file))

	change, _ := changeFor(Apply(want(home), false, false), "Claude Code")
	if change.Err == nil || change.Skip == "" {
		t.Fatalf("bad file change = %+v", change)
	}
	after := sha256.Sum256(mustRead(t, file))
	if before != after {
		t.Fatalf("a file that could not be parsed was written")
	}
}

func TestDryRunWritesNothing(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".claude", "settings.json")
	writeFile(t, file, `{"sandbox":{}}`)
	before := sha256.Sum256(mustRead(t, file))

	change, _ := changeFor(Apply(want(home), false, true), "Claude Code")
	if !change.Changed() {
		t.Fatalf("dry run reported no change")
	}
	if after := sha256.Sum256(mustRead(t, file)); before != after {
		t.Fatalf("dry run wrote the file")
	}
}

func TestNothingInstalled(t *testing.T) {
	changes := Apply(want(t.TempDir()), false, false)
	for _, change := range changes {
		if change.Client == "Codex" {
			t.Fatalf("Codex reported without a ~/.codex directory")
		}
		if change.Changed() || change.Skip != "没有安装" {
			t.Fatalf("%s = %+v", change.Client, change)
		}
	}
}

// A settings file that already lists the path and the hosts still needs the switch, and a run that
// only turns a switch on must be written (otherwise the file is reported as up to date forever).
func TestSwitchesAloneAreWritten(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".claude", "settings.json")
	writeFile(t, file, `{"sandbox":{"filesystem":{"allowWrite":["~/Library/Application Support/skillmaster"]},`+
		`"network":{"allowedDomains":["console.example.com","dl.example.com"]}}}`)

	change, _ := changeFor(Apply(want(home), false, false), "Claude Code")
	if !change.Changed() || len(change.Toggled) == 0 || change.Err != nil {
		t.Fatalf("switch-only change = %+v", change)
	}
	network := readJSONFile(t, file)["sandbox"].(map[string]any)["network"].(map[string]any)
	if network["allowLocalBinding"] != true {
		t.Fatalf("allowLocalBinding = %v", network["allowLocalBinding"])
	}

	// The values were already there, so `--fix` kept them. `--undo` removes by value and cannot
	// tell them apart from the person's — the documented cost (ADR 0186).
	if undone, _ := changeFor(Apply(want(home), true, false), "Claude Code"); !undone.Changed() {
		t.Fatalf("undo changed nothing")
	}
	root := readJSONFile(t, file)
	sandbox := root["sandbox"].(map[string]any)
	if paths := sandbox["filesystem"].(map[string]any)["allowWrite"].([]any); hasString(paths, "~/Library/Application Support/skillmaster") {
		t.Fatalf("after undo allowWrite = %v", paths)
	}
	domains := sandbox["network"].(map[string]any)["allowedDomains"].([]any)
	if hasString(domains, "console.example.com") {
		t.Fatalf("after undo allowedDomains = %v", domains)
	}
}

// `--undo` removes the object levels this run created when they end up empty: a file with only
// `filesystem.allowWrite` must not gain an empty `network` object on the way out.
func TestUndoRemovesNewEmptyObjects(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".claude", "settings.json")
	writeFile(t, file, `{"sandbox":{"filesystem":{"allowWrite":["~/Library/Application Support/skillmaster"]}}}`)

	change, _ := changeFor(Apply(want(home), true, false), "Claude Code")
	if change.Err != nil || !change.Changed() {
		t.Fatalf("undo = %+v", change)
	}
	sandbox := readJSONFile(t, file)["sandbox"].(map[string]any)
	if _, ok := sandbox["network"]; ok {
		t.Fatalf("undo left a newly created empty object behind: %v", sandbox)
	}
}

// Zed's settings file is JSONC; a comment must not make the client unconfigurable.
func TestZedJSONCIsAccepted(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".config", "zed", "settings.json")
	writeFile(t, file, `{
  // my machines
  "agent": {
    "sandbox_permissions": {
      "write_paths": ["/tmp/keep"], // keep this
    },
  },
}`)

	change, _ := changeFor(Apply(want(home), false, false), "Zed")
	if change.Err != nil || !change.Changed() {
		t.Fatalf("Zed JSONC = %+v", change)
	}
	paths := readJSONFile(t, file)["agent"].(map[string]any)["sandbox_permissions"].(map[string]any)["write_paths"].([]any)
	if !hasString(paths, want(home).WritePaths[0]) || !hasString(paths, "/tmp/keep") {
		t.Fatalf("write_paths = %v", paths)
	}
}

// A bad TOML file is refused exactly like a bad JSON one.
func TestBadTOMLIsLeftAlone(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".codex", "config.toml")
	writeFile(t, file, "not = [valid\n")
	before := sha256.Sum256(mustRead(t, file))

	change, _ := changeFor(Apply(want(home), false, false), "Codex")
	if change.Err == nil || change.Skip == "" {
		t.Fatalf("bad TOML change = %+v", change)
	}
	if after := sha256.Sum256(mustRead(t, file)); before != after {
		t.Fatalf("a file that could not be parsed was written")
	}
}

// A file using Codex's newer `[permissions.<profile>]` scheme is left alone rather than mixed with
// the older `sandbox_mode` / `[sandbox_workspace_write]` one.
func TestCodexNewSchemeIsNotMixed(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".codex", "config.toml")
	writeFile(t, file, "default_permissions = \"profile\"\n\n[permissions.profile.filesystem]\nwritable_roots = [\"/tmp/keep\"]\n")
	before := sha256.Sum256(mustRead(t, file))

	change, _ := changeFor(Apply(want(home), false, false), "Codex")
	if change.Err != nil || change.Changed() || !strings.Contains(change.Skip, "新式") {
		t.Fatalf("Codex new scheme = %+v", change)
	}
	if after := sha256.Sum256(mustRead(t, file)); before != after {
		t.Fatalf("the file was written")
	}
}

// Codex's old form needs `sandbox_mode = "workspace-write"` for `writable_roots` to take effect,
// and that mode makes the workspace writable. Creating a config file for a Codex that has none
// would be creating that switch, not narrowing one (ADR 0186 §决定 1/2 与 §后果), so --fix leaves it alone.
func TestCodexMissingConfigIsNotCreated(t *testing.T) {
	home := t.TempDir()
	dir := filepath.Join(home, ".codex")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	change, _ := changeFor(Apply(want(home), false, false), "Codex")
	if change.Err != nil || change.Changed() {
		t.Fatalf("Codex change = %+v", change)
	}
	if !strings.Contains(change.Skip, "没有可用的 config.toml") {
		t.Fatalf("skip = %q", change.Skip)
	}
	if _, err := os.Stat(filepath.Join(dir, "config.toml")); !os.IsNotExist(err) {
		t.Fatalf("config.toml was created: %v", err)
	}
}

// An empty config.toml is the same situation as a missing one: Codex is on its defaults, and
// writing workspace-write into it would turn the workspace writable.
func TestEmptyCodexConfigIsNotFilled(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".codex", "config.toml")
	writeFile(t, file, "")

	change, _ := changeFor(Apply(want(home), false, false), "Codex")
	if change.Err != nil || change.Changed() {
		t.Fatalf("Codex change = %+v", change)
	}
	if !strings.Contains(change.Skip, "没有可用的 config.toml") {
		t.Fatalf("skip = %q", change.Skip)
	}
	if got := mustRead(t, file); len(got) != 0 {
		t.Fatalf("empty config was written: %q", got)
	}
}

// A JSON `null` means "no value", the same way readList reads it; it is not a reason to skip a
// whole client over a key that says nothing.
func TestNullSandboxIsTreatedAsAbsent(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".workbuddy", "settings.json")
	writeFile(t, file, `{"sandbox":null}`)

	change, _ := changeFor(Apply(want(home), false, false), "WorkBuddy")
	if change.Err != nil || !change.Changed() || change.Skip != "" {
		t.Fatalf("WorkBuddy with null sandbox = %+v", change)
	}
}

// A deny rule for the same host is reported, not silently treated as "already allowed".
func TestWorkBuddyDenyRuleIsReported(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".workbuddy", "settings.json")
	writeFile(t, file, `{"sandbox":{"orderedRules":{"network":{"rules":[`+
		`{"id":"user-1","domain":"console.example.com","action":"deny"}]}}}}`)
	before := sha256.Sum256(mustRead(t, file))

	change, _ := changeFor(Apply(want(home), false, false), "WorkBuddy")
	if change.Err != nil || !strings.Contains(change.Skip, "拒绝规则") {
		t.Fatalf("WorkBuddy deny = %+v", change)
	}
	if after := sha256.Sum256(mustRead(t, file)); before != after {
		t.Fatalf("the file was written")
	}
}

// Gemini's sandbox being off means there is nothing to allow, and creating `tools.sandbox` would
// turn the whole process sandbox on — a larger change than this command promises.
func TestGeminiSandboxOffIsLeftAlone(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".gemini", "settings.json")
	writeFile(t, file, `{"theme":"dark"}`)
	before := sha256.Sum256(mustRead(t, file))

	change, _ := changeFor(Apply(want(home), false, false), "Gemini")
	if change.Err != nil || change.Changed() || !strings.Contains(change.Skip, "没有开启") {
		t.Fatalf("Gemini sandbox off = %+v", change)
	}
	if after := sha256.Sum256(mustRead(t, file)); before != after {
		t.Fatalf("the file was written")
	}
}

// Zed refuses IP literals and localhost in `network_hosts`, so they are not written there.
func TestZedHostsSkipLocalhost(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".config", "zed", "settings.json")
	writeFile(t, file, `{"agent":{"sandbox_permissions":{"write_paths":[]}}}`)
	w := want(home)
	w.Hosts = append(w.Hosts, "localhost", "127.0.0.1")

	if change, _ := changeFor(Apply(w, false, false), "Zed"); change.Err != nil {
		t.Fatalf("Zed change = %+v", change)
	}
	hosts, _ := toSlice(readJSONFile(t, file)["agent"].(map[string]any)["sandbox_permissions"].(map[string]any)["network_hosts"])
	if hasString(hosts, "localhost") || hasString(hosts, "127.0.0.1") {
		t.Fatalf("network_hosts = %v", hosts)
	}
}

// A key that is a string (or an object) is someone's setting; it is not replaced by an array.
func TestNonArrayKeyIsLeftAlone(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".claude", "settings.json")
	writeFile(t, file, `{"sandbox":{"filesystem":{"allowWrite":"~/work"}}}`)
	before := sha256.Sum256(mustRead(t, file))

	change, _ := changeFor(Apply(want(home), false, false), "Claude Code")
	if change.Err != nil || change.Changed() || !strings.Contains(change.Skip, "不是数组") {
		t.Fatalf("non-array key = %+v", change)
	}
	if after := sha256.Sum256(mustRead(t, file)); before != after {
		t.Fatalf("the file was written")
	}
	// Undo has the same rule: it must not turn the string into `[]`.
	if undo, _ := changeFor(Apply(want(home), true, false), "Claude Code"); undo.Err != nil || undo.Changed() {
		t.Fatalf("undo on a non-array key = %+v", undo)
	}
	if after := sha256.Sum256(mustRead(t, file)); before != after {
		t.Fatalf("undo wrote the file")
	}
}

// The backup is the file as it was before SkillMaster touched it, and a later `--fix --undo` must
// not replace it with an already-rewritten copy.
// A backup interrupted by a full disk leaves a zero-byte file. The next run must replace it, not
// treat it as the first copy forever.
func TestZeroByteBackupIsReplaced(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".claude", "settings.json")
	writeFile(t, file, `{"sandbox":{"filesystem":{"allowWrite":["/tmp/keep"]}}}`)
	original := string(mustRead(t, file))
	writeFile(t, file+".skillmaster.bak", "")

	Apply(want(home), false, false)
	if got := string(mustRead(t, file+".skillmaster.bak")); got != original {
		t.Fatalf("a zero-byte backup was not replaced:\n%s", got)
	}
}

func TestBackupKeepsTheFirstCopy(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".claude", "settings.json")
	writeFile(t, file, `{"sandbox":{"filesystem":{"allowWrite":["/tmp/keep"]}}}`)
	original := string(mustRead(t, file))

	Apply(want(home), false, false)
	bak := file + ".skillmaster.bak"
	if got := string(mustRead(t, bak)); got != original {
		t.Fatalf("backup is not the original file:\n%s", got)
	}
	Apply(want(home), true, false)
	if got := string(mustRead(t, bak)); got != original {
		t.Fatalf("undo rewrote the original backup:\n%s", got)
	}
}

// A dotfiles symlink survives the write; the file it points at is the one changed.
func TestSymlinkedConfigStaysASymlink(t *testing.T) {
	home := t.TempDir()
	real := filepath.Join(home, "dotfiles", "claude-settings.json")
	writeFile(t, real, `{"sandbox":{}}`)
	dir := filepath.Join(home, ".claude")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "settings.json")
	// A relative link is what stow/chezmoi commonly leave behind.
	if err := os.Symlink(filepath.Join("..", "dotfiles", "claude-settings.json"), link); err != nil {
		t.Skipf("symlinks are unavailable: %v", err)
	}

	if change, _ := changeFor(Apply(want(home), false, false), "Claude Code"); change.Err != nil {
		t.Fatalf("change = %+v", change)
	}
	info, err := os.Lstat(link)
	if err != nil || info.Mode()&os.ModeSymlink == 0 {
		t.Fatalf("the symlink was replaced: %v, %v", info, err)
	}
	root := readJSONFile(t, real)
	if paths := root["sandbox"].(map[string]any)["filesystem"].(map[string]any)["allowWrite"].([]any); !hasString(paths, "~/Library/Application Support/skillmaster") {
		t.Fatalf("the link target was not written: %v", paths)
	}
}

// An empty host (an empty or malformed server setting) is not written into any allowlist.
func TestEmptyHostIsSkipped(t *testing.T) {
	home := t.TempDir()
	file := filepath.Join(home, ".claude", "settings.json")
	writeFile(t, file, `{"sandbox":{}}`)
	w := want(home)
	w.Hosts = []string{"", "console.example.com"}

	if change, _ := changeFor(Apply(w, false, false), "Claude Code"); change.Err != nil {
		t.Fatalf("change = %+v", change)
	}
	domains := readJSONFile(t, file)["sandbox"].(map[string]any)["network"].(map[string]any)["allowedDomains"].([]any)
	if hasString(domains, "") || !hasString(domains, "console.example.com") {
		t.Fatalf("allowedDomains = %v", domains)
	}
}

func mustRead(t *testing.T, path string) []byte {
	t.Helper()
	blob, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return blob
}

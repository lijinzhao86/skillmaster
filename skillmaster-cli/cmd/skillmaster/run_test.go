package main

import (
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// **Everything after `--` belongs to the child**, including flags the child itself understands.
// Dropping them here would run a different command than the author's SKILL.md told the agent to run.
func TestRunKeepsTheChildsOwnFlags(t *testing.T) {
	opts, address, command, err := parseRunArgs([]string{
		"dev/pdf-tools", "--cwd", "data", "--yes", "--json",
		"--", "python3", "plan.py", "--json", "--input", "x.csv",
	})
	if err != nil {
		t.Fatalf("parseRunArgs: %v", err)
	}
	if address != "dev/pdf-tools" {
		t.Errorf("address = %q", address)
	}
	if !opts.yes || !opts.json || opts.cwd != "data" {
		t.Errorf("flags not bound: %+v", opts)
	}
	want := []string{"python3", "plan.py", "--json", "--input", "x.csv"}
	if strings.Join(command, "|") != strings.Join(want, "|") {
		t.Errorf("command = %v, want %v", command, want)
	}
}

func TestRunNeedsTheSeparator(t *testing.T) {
	if _, _, _, err := parseRunArgs([]string{"dev/pdf-tools", "python3"}); err == nil {
		t.Fatal("a command without -- was accepted")
	}
}

func TestRunRejectsUnknownFlags(t *testing.T) {
	if _, _, _, err := parseRunArgs([]string{"dev/pdf-tools", "--nope", "--", "python3", "x.py"}); err == nil {
		t.Fatal("an unknown flag was accepted")
	}
}

// The resolution rule, in one table: rewrite what points into `scripts/`, refuse to guess when a
// name matches twice, and leave every other argument exactly as it arrived.
func TestRunResolvesOnlyScriptPaths(t *testing.T) {
	skill := t.TempDir()
	scripts := filepath.Join(skill, "scripts")
	for _, path := range []string{"plan.py", "lib/inner.py", "a/x.py", "b/x.py"} {
		full := filepath.Join(scripts, filepath.FromSlash(path))
		if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(full, []byte("#"), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	direct, err := resolveCommandArgs(skill, []string{"python3", "plan.py", "--input", "x.csv"})
	if err != nil {
		t.Fatalf("unique name: %v", err)
	}
	if want := filepath.Join(scripts, "plan.py"); direct[1] != want {
		t.Errorf("plan.py resolved to %q, want %q", direct[1], want)
	}
	if direct[3] != "x.csv" {
		t.Errorf("a data argument was rewritten to %q", direct[3])
	}

	nested, err := resolveCommandArgs(skill, []string{"python3", "inner.py"})
	if err != nil {
		t.Fatalf("nested name: %v", err)
	}
	if want := filepath.Join(scripts, "lib", "inner.py"); nested[1] != want {
		t.Errorf("inner.py resolved to %q, want %q", nested[1], want)
	}

	if _, err := resolveCommandArgs(skill, []string{"python3", "x.py"}); err == nil {
		t.Error("an ambiguous name was resolved anyway")
	} else if !strings.Contains(err.Error(), "2 个") {
		t.Errorf("the ambiguity was not explained: %v", err)
	}

	if _, err := resolveCommandArgs(skill, []string{"python3", "nope.py"}); err == nil {
		t.Error("a missing script was passed through as if it existed")
	} else if !strings.Contains(err.Error(), "现有") {
		t.Errorf("the refusal does not list what is there: %v", err)
	}
}

// `scripts/` is the default working directory because it is the one both existing spellings of a
// command agree on: `python x.py` needs it, and `python scripts/x.py` was already rewritten to an
// absolute path before the child starts.
func TestRunWorksInScriptsByDefault(t *testing.T) {
	skill := t.TempDir()
	scripts := filepath.Join(skill, "scripts")
	if err := os.MkdirAll(scripts, 0o755); err != nil {
		t.Fatal(err)
	}
	if got, err := workingDirectory(skill, ""); err != nil || got != scripts {
		t.Errorf("default working directory = %q (%v), want %q", got, err, scripts)
	}
	if got, err := workingDirectory(skill, "data"); err != nil || got != filepath.Join(skill, "data") {
		t.Errorf("--cwd resolved to %q (%v)", got, err)
	}

	// A skill with no scripts/ still runs: the root is where its files are.
	plain := t.TempDir()
	if got, err := workingDirectory(plain, ""); err != nil || got != plain {
		t.Errorf("working directory without scripts/ = %q (%v)", got, err)
	}
}

// **The first execution of a version is asked about once**, and the answer is remembered where the
// CLI keeps its state. Asking every time is what would make an agent's shell unusable.
func TestRunAsksOncePerVersion(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("SKILLMASTER_HOME", filepath.Join(home, "state"))

	if err := recordConsent("0123456789abcdef0123", false); err == nil {
		t.Fatal("the first execution was not asked about")
	} else if !strings.Contains(err.Error(), "--yes") {
		t.Errorf("the refusal does not say how to continue: %v", err)
	}
	if err := recordConsent("0123456789abcdef0123", true); err != nil {
		t.Fatalf("--yes was refused: %v", err)
	}
	if err := recordConsent("0123456789abcdef0123", false); err != nil {
		t.Errorf("the same version asked twice: %v", err)
	}
	if err := recordConsent("ffffffffffffffffffff", false); err == nil {
		t.Error("a different version reused the previous answer")
	}
}

// **A digest is a path segment before it is anything else.** A server that answered `..` would
// otherwise place the cache, the staging directory and the rename above the cache root, and it
// would happen before the consent the person is shown.
func TestRunOnlyAcceptsADigestAsADirectoryName(t *testing.T) {
	good := strings.Repeat("0123456789abcdef", 4)
	if !isDigest(good) {
		t.Fatalf("%q was refused", good)
	}
	for _, bad := range []string{"..", "", "sha256:" + good, strings.ToUpper(good),
		good[:63], good + "0", "../../etc/passwd" + strings.Repeat("0", 48)} {
		if isDigest(bad) {
			t.Errorf("%q was accepted as a digest", bad)
		}
	}
}

// **The remembered answer is what makes the cache reachable without the network**, so it has to
// survive a process: written to the state directory, read back by address.
func TestRunRemembersTheDigestOfAnAddress(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("SKILLMASTER_HOME", filepath.Join(home, "state"))

	digest := strings.Repeat("ab", 32)
	if _, found := rememberedDigest("dev/pdf-tools"); found {
		t.Fatal("an address that was never resolved had a remembered digest")
	}
	rememberDigest("dev/pdf-tools", digest)
	got, found := rememberedDigest("dev/pdf-tools")
	if !found || got != digest {
		t.Fatalf("remembered digest = %q, %v", got, found)
	}
	if _, found := rememberedDigest("dev/other"); found {
		t.Error("another address read the same record")
	}
	if digestKey("dev/pdf-tools") == digestKey("dev/other") {
		t.Error("two addresses share a record name")
	}
}

// **A server that answered is never overruled by the cache.** The distinction is the transport, not
// a list of statuses: 404 arrives as a plain error, 401/403 as their own types, and only "no
// answer at all" comes back wrapped in a `*url.Error`.
func TestRunOnlyTreatsATransportFailureAsOffline(t *testing.T) {
	unreachableErr := &url.Error{Op: "Get", URL: "http://127.0.0.1:1/x", Err: errors.New("connection refused")}
	if !unreachable(fmt.Errorf("请求 %s 失败：%w", "/x", unreachableErr)) {
		t.Error("a connection failure was not recognised as offline")
	}
	for name, err := range map[string]error{
		"not found": apiErrNotFound(),
		"a refusal": errors.New("凭据太窄"),
	} {
		if unreachable(err) {
			t.Errorf("%s was treated as a network failure", name)
		}
	}
}

func apiErrNotFound() error {
	return fmt.Errorf("%w /skills/dev/x（服务端不区分「不存在」与「你看不到」）", api.ErrNotFound)
}

// **The cache root is checked, not trusted.** On Linux `$TMPDIR` is `/tmp`, the cache is keyed by a
// public digest, and a directory somebody else created is content this run would execute.
func TestRunRefusesACacheRootAnyoneCanWrite(t *testing.T) {
	root := filepath.Join(t.TempDir(), "cache")
	if err := privateCacheRoot(root); err != nil {
		t.Fatalf("a fresh private root was refused: %v", err)
	}
	if err := os.Chmod(root, 0o777); err != nil {
		t.Fatal(err)
	}
	if err := privateCacheRoot(root); err == nil {
		t.Error("a world-writable cache root was accepted")
	}
}

func TestRunDoesNotResolveOutsideScripts(t *testing.T) {
	root := t.TempDir()
	scripts := filepath.Join(root, "scripts")
	if err := os.MkdirAll(scripts, 0o755); err != nil {
		t.Fatal(err)
	}
	outside := filepath.Join(root, "x.py")
	if err := os.WriteFile(outside, []byte("print(1)\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	// The dangerous shape: the same name also exists *inside* scripts/. A basename search would
	// find it and run a file the command did not name.
	if err := os.WriteFile(filepath.Join(scripts, "x.py"), []byte("print(2)\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if got, err := resolveInsideScripts(scripts, "../x.py"); err == nil {
		t.Errorf("../x.py resolved to %q", got)
	}
}

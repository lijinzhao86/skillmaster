package main

import (
	"archive/zip"
	"bytes"
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/pins"
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

// **A draft's version code is a version reference like any other** (ADR 0178). The public plane
// refuses the spelling; the author plane — the one that knows about drafts — answers with the digest,
// and the bindings question is still answered for the skill (a lease carries no pin).
func TestRunResolvesADraftCodeOnTheAuthorPlane(t *testing.T) {
	withTempHome(t)
	digest := strings.Repeat("ab", 32)
	sawAuthor := false
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/metadata"):
			if r.Header.Get(api.VersionHeader) != "" {
				w.WriteHeader(http.StatusBadRequest)
				_, _ = w.Write([]byte(`{"error":{"code":"invalid_request",` +
					`"message":"X-Skill-Version must be a version name or a sha256: digest, but was 'JD0T518T'"}}`))
				return
			}
			_, _ = w.Write([]byte(`{"name":"hello","version":{"digest":"sha256:` + digest +
				`"},"credentials_bound":true}`))
		case strings.Contains(r.URL.Path, "/author/skills/me/hello/versions/JD0T518T"):
			sawAuthor = true
			_, _ = w.Write([]byte(`{"skill":{"name":"hello"},"version":{"digest":"sha256:` +
				digest + `"}}`))
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	})

	got, note, credentials, err := resolveDigest(context.Background(), "me/hello",
		"me/hello@JD0T518T", true)
	if err != nil {
		t.Fatalf("resolveDigest: %v", err)
	}
	if got != digest {
		t.Errorf("digest = %q, want %q", got, digest)
	}
	if !sawAuthor {
		t.Error("the author plane was never asked")
	}
	if note != "" {
		t.Errorf("note = %q, want none: this is not an offline answer", note)
	}
	if !credentials.Bound {
		t.Error("a skill with bindings must still lease when the version is a draft")
	}
}

// **A published version is answered by the public plane, and only by it.** The fallback must not cost
// every named run a second request.
func TestRunKeepsThePublicAnswerForAPublishedVersion(t *testing.T) {
	withTempHome(t)
	digest := strings.Repeat("cd", 32)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.Contains(r.URL.Path, "/author/") {
			t.Errorf("the author plane was asked about a published version: %s", r.URL.Path)
		}
		_, _ = w.Write([]byte(`{"name":"hello","version":{"digest":"sha256:` + digest + `"}}`))
	})

	got, _, _, err := resolveDigest(context.Background(), "me/hello", "me/hello@1.2.3", true)
	if err != nil {
		t.Fatalf("resolveDigest: %v", err)
	}
	if got != digest {
		t.Errorf("digest = %q, want %q", got, digest)
	}
}

// **Neither plane's wording reaches the person.** The public refusal names a protocol header; the
// author plane's 404 says nothing actionable. What is said instead: the reference is gone, and the
// command that lists what exists.
func TestRunSaysTheVersionIsGoneWithoutProtocolDetails(t *testing.T) {
	withTempHome(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/metadata"):
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":{"code":"invalid_request",` +
				`"message":"X-Skill-Version must be a version name or a sha256: digest, but was 'JD0T518T'"}}`))
		case strings.Contains(r.URL.Path, "/author/skills/"):
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":{"code":"not_found","message":"no such version"}}`))
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	})

	_, _, _, err := resolveDigest(context.Background(), "me/hello", "me/hello@JD0T518T", true)
	if err == nil {
		t.Fatal("a reference neither plane knows was accepted")
	}
	for _, leak := range []string{"X-Skill-Version", "invalid_request", "sha256: digest"} {
		if strings.Contains(err.Error(), leak) {
			t.Errorf("the refusal repeats %q:\n%s", leak, err)
		}
	}
	if !strings.Contains(err.Error(), "没有这一版") ||
		!strings.Contains(err.Error(), "skmr skill versions") {
		t.Errorf("the refusal does not say what is wrong or what to do:\n%s", err)
	}
}

// **A5 end to end**: the draft's own `scripts/` file is what runs. Resolution through the author
// plane, the archive fetch, the cache, the consent, the path rewrite and the child process, with a
// script only the draft contains — the case that motivated the version (a draft's script had never
// been executed before it shipped).
func TestRunExecutesAScriptFromTheDraft(t *testing.T) {
	withTempHome(t)
	digest := strings.Repeat("ef", 32)
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	for _, entry := range []struct{ name, body string }{
		{"SKILL.md", "---\nname: hello\ndescription: d\n---\n# hello\n"},
		{"scripts/hello.sh", "#!/bin/sh\necho hello-from-draft\n"},
		{".skillmaster/metadata.json", `{"schema_version":1,"version":{"name":"草稿"}}`},
	} {
		file, err := writer.Create(entry.name)
		if err != nil {
			t.Fatalf("building the fixture: %v", err)
		}
		if _, err := file.Write([]byte(entry.body)); err != nil {
			t.Fatalf("building the fixture: %v", err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("building the fixture: %v", err)
	}
	archive := buffer.Bytes()

	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/credentials/lease"):
			// A draft with no bindings still answers, with nothing.
			_, _ = w.Write([]byte(`{"values":[],"expires_at":"2030-01-01T00:00:00Z"}`))
		case strings.HasSuffix(r.URL.Path, "/metadata"):
			if r.Header.Get(api.VersionHeader) != "" {
				w.WriteHeader(http.StatusBadRequest)
				_, _ = w.Write([]byte(`{"error":{"code":"invalid_request",` +
					`"message":"X-Skill-Version must be a version name or a sha256: digest, but was 'JD0T518T'"}}`))
				return
			}
			// Never published: the bare address has nothing to say.
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":{"code":"not_found","message":"no"}}`))
		case strings.Contains(r.URL.Path, "/author/skills/me/hello/versions/JD0T518T"):
			_, _ = w.Write([]byte(`{"skill":{"name":"hello"},"version":{"digest":"sha256:` +
				digest + `"}}`))
		case strings.Contains(r.URL.Path, "/versions/sha256:") && strings.HasSuffix(r.URL.Path, "/archive"):
			w.Header().Set("Content-Type", "application/zip")
			w.Header().Set("ETag", `"sha256:`+digest+`"`)
			_, _ = w.Write(archive)
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	})

	stdout, stderr, status := runCaptured(t, "run", "me/hello@JD0T518T", "--yes", "--", "sh", "hello.sh")

	if status != 0 {
		t.Fatalf("status = %d, stderr = %q", status, stderr)
	}
	if !strings.Contains(stdout, "hello-from-draft") {
		t.Errorf("the draft's script did not run; stdout = %q, stderr = %q", stdout, stderr)
	}
	if !strings.Contains(stderr, "执行 me/hello@JD0T518T（sha256:"+digest[:16]) {
		t.Errorf("stderr does not name the draft's digest: %q", stderr)
	}
}

// **A bare address whose remembered pin is a draft code is the same question** as writing `@code`:
// `invoke` remembers a code when a skill has no published version, and `run` then used to hand that
// code to the public plane and print its protocol error.
func TestRunFallsBackForARememberedDraftCode(t *testing.T) {
	withTempHome(t)
	digest := strings.Repeat("12", 32)
	sawAuthor := false
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/metadata"):
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":{"code":"invalid_request",` +
				`"message":"X-Skill-Version must be a version name or a sha256: digest, but was 'JD0T518T'"}}`))
		case strings.Contains(r.URL.Path, "/author/skills/me/hello/versions/JD0T518T"):
			sawAuthor = true
			_, _ = w.Write([]byte(`{"skill":{"name":"hello"},"version":{"digest":"sha256:` +
				digest + `"}}`))
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	})
	// After the server is chosen: the pin record is keyed by server address.
	if _, _, err := rememberPin("me/hello", pins.Pin{Kind: "code", Code: "JD0T518T"}); err != nil {
		t.Fatalf("rememberPin: %v", err)
	}

	got, _, _, err := resolveDigest(context.Background(), "me/hello", "me/hello", false)
	if err != nil {
		t.Fatalf("resolveDigest: %v", err)
	}
	if !sawAuthor || got != digest {
		t.Errorf("digest = %q, author asked = %v", got, sawAuthor)
	}
}

// **A timeout on the author plane is not "there is no such version".** Only 404 and 403 get that
// sentence; a server that could not be reached must say so, or the next step points at the wrong
// problem.
func TestRunKeepsATransportFailureOnTheAuthorPlane(t *testing.T) {
	withTempHome(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/metadata"):
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":{"code":"invalid_request","message":"nope"}}`))
		case strings.Contains(r.URL.Path, "/author/skills/"):
			w.WriteHeader(http.StatusInternalServerError)
			_, _ = w.Write([]byte(`{"error":{"code":"internal","message":"boom"}}`))
		default:
			http.NotFound(w, r)
		}
	})

	_, _, _, err := resolveDigest(context.Background(), "me/hello", "me/hello@JD0T518T", true)
	if err == nil {
		t.Fatal("a 500 was reported as a missing version")
	}
	if strings.Contains(err.Error(), "没有这一版") {
		t.Errorf("a 500 on the author plane was reported as a missing version: %v", err)
	}
}

// **A published skill's draft uses the public answer for bindings.** That answer is per skill, and
// taking it keeps a draft run at the published run's request count.
func TestRunUsesThePublicAnswerForAPublishedSkillsBindings(t *testing.T) {
	withTempHome(t)
	digest := strings.Repeat("34", 32)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/metadata"):
			if r.Header.Get(api.VersionHeader) != "" {
				w.WriteHeader(http.StatusBadRequest)
				_, _ = w.Write([]byte(`{"error":{"code":"invalid_request","message":"nope"}}`))
				return
			}
			_, _ = w.Write([]byte(`{"version":{"digest":"sha256:` + digest +
				`"},"credentials_bound":true,"credentials_missing":["TOKEN"]}`))
		case strings.Contains(r.URL.Path, "/author/skills/"):
			_, _ = w.Write([]byte(`{"skill":{"name":"hello"},"version":{"digest":"sha256:` +
				digest + `"}}`))
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	})

	_, _, credentials, err := resolveDigest(context.Background(), "me/hello",
		"me/hello@JD0T518T", true)
	if err != nil {
		t.Fatalf("resolveDigest: %v", err)
	}
	if !credentials.Bound || credentials.ProbeLease {
		t.Errorf("credentials = %+v, want Bound from the public answer", credentials)
	}
	if len(credentials.Missing) != 1 || credentials.Missing[0] != "TOKEN" {
		t.Errorf("missing = %v, want the public answer's declaration", credentials.Missing)
	}
}

// **A skill that was never published has no public binding answer**, so the run asks the lease
// instead of guessing "nothing bound".
func TestRunProbesTheLeaseWhenTheSkillWasNeverPublished(t *testing.T) {
	withTempHome(t)
	digest := strings.Repeat("56", 32)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/metadata"):
			// The versioned ask is refused for its spelling; the bare ask is the real 404 a
			// never-published skill gives.
			if r.Header.Get(api.VersionHeader) != "" {
				w.WriteHeader(http.StatusBadRequest)
				_, _ = w.Write([]byte(`{"error":{"code":"invalid_request","message":"nope"}}`))
				return
			}
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":{"code":"not_found","message":"no"}}`))
		case strings.Contains(r.URL.Path, "/author/skills/"):
			_, _ = w.Write([]byte(`{"skill":{"name":"hello"},"version":{"digest":"sha256:` +
				digest + `"}}`))
		default:
			http.NotFound(w, r)
		}
	})

	_, _, credentials, err := resolveDigest(context.Background(), "me/hello",
		"me/hello@JD0T518T", true)
	if err != nil {
		t.Fatalf("resolveDigest: %v", err)
	}
	if !credentials.ProbeLease || credentials.Bound {
		t.Errorf("credentials = %+v, want the lease probe", credentials)
	}
}

// **A credential that cannot POST a lease must not stop the run.** The server requires
// `skills:write` for every non-GET, and the skill may bind nothing at all; refusing here would break
// every read-only caller on an unpublished skill. Say what could not be checked, then run.
func TestRunKeepsRunningWhenTheLeaseIsRefused(t *testing.T) {
	withTempHome(t)
	digest := strings.Repeat("78", 32)
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	for _, entry := range []struct{ name, body string }{
		{"SKILL.md", "---\nname: hello\ndescription: d\n---\n"},
		{"scripts/hello.sh", "#!/bin/sh\necho hello-from-draft\n"},
	} {
		file, err := writer.Create(entry.name)
		if err != nil {
			t.Fatalf("building the fixture: %v", err)
		}
		if _, err := file.Write([]byte(entry.body)); err != nil {
			t.Fatalf("building the fixture: %v", err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("building the fixture: %v", err)
	}
	archive := buffer.Bytes()

	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/credentials/lease"):
			w.WriteHeader(http.StatusForbidden)
			_, _ = w.Write([]byte(`{"error":{"code":"insufficient_scope","message":"needs skills:write"}}`))
		case strings.HasSuffix(r.URL.Path, "/metadata"):
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":{"code":"invalid_request","message":"nope"}}`))
		case strings.Contains(r.URL.Path, "/author/skills/me/hello/versions/JD0T518T"):
			_, _ = w.Write([]byte(`{"skill":{"name":"hello"},"version":{"digest":"sha256:` +
				digest + `"}}`))
		case strings.HasSuffix(r.URL.Path, "/archive"):
			w.Header().Set("ETag", `"sha256:`+digest+`"`)
			_, _ = w.Write(archive)
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	})

	stdout, stderr, status := runCaptured(t, "run", "me/hello@JD0T518T", "--yes", "--", "sh", "hello.sh")

	if status != 0 {
		t.Fatalf("status = %d, stderr = %q", status, stderr)
	}
	if !strings.Contains(stdout, "hello-from-draft") {
		t.Errorf("the script did not run: stdout = %q", stdout)
	}
	if !strings.Contains(stderr, "没能确认这个草稿绑定了哪些服务凭据") {
		t.Errorf("the run did not say the lease was refused: %q", stderr)
	}
}

// **A malformed digest is a reference nobody understands**, not a protocol error on screen: the
// public plane's complaint about the header must not reach the caller (A7).
func TestRunTranslatesAMalformedDigest(t *testing.T) {
	withTempHome(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/metadata"):
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":{"code":"invalid_request",` +
				`"message":"X-Skill-Version must be a version name or a sha256: digest, but was 'sha256:deadbeef'"}}`))
		case strings.Contains(r.URL.Path, "/author/skills/"):
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":{"code":"not_found","message":"no"}}`))
		default:
			http.NotFound(w, r)
		}
	})

	_, _, _, err := resolveDigest(context.Background(), "me/hello", "me/hello@sha256:deadbeef", true)
	if err == nil {
		t.Fatal("a malformed digest was accepted")
	}
	if strings.Contains(err.Error(), "X-Skill-Version") {
		t.Errorf("the protocol detail leaked: %v", err)
	}
	if !strings.Contains(err.Error(), "没有这一版") {
		t.Errorf("the refusal is not the person's sentence: %v", err)
	}
}

// **`insufficient_scope` is not "there is no such version".** Another credential would work, and the
// CLI already has that sentence; flattening it would send a read-only key to a command that 403s too.
func TestRunDoesNotCallAnInsufficientScopeAMissingVersion(t *testing.T) {
	withTempHome(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case strings.HasSuffix(r.URL.Path, "/metadata"):
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":{"code":"invalid_request","message":"nope"}}`))
		case strings.Contains(r.URL.Path, "/author/skills/"):
			w.Header().Set("WWW-Authenticate", `Bearer, error="insufficient_scope", scope="skills:write"`)
			w.WriteHeader(http.StatusForbidden)
			_, _ = w.Write([]byte(`{"error":{"code":"insufficient_scope","message":"needs skills:write"}}`))
		default:
			http.NotFound(w, r)
		}
	})

	_, _, _, err := resolveDigest(context.Background(), "me/hello", "me/hello@JD0T518T", true)
	if err == nil {
		t.Fatal("a 403 was accepted")
	}
	if strings.Contains(err.Error(), "没有这一版") {
		t.Errorf("an insufficient scope was reported as a missing version: %v", err)
	}
	if !strings.Contains(err.Error(), "skills:write") {
		t.Errorf("the credential's missing scope is not named: %v", err)
	}
}

// **A published skill's draft takes the public answer and leases when it says bindings exist.**
// The value reaches the child's environment — the branch the "never published" cases do not touch.
func TestRunInjectsTheLeaseForAPublishedSkillsDraft(t *testing.T) {
	withTempHome(t)
	digest := strings.Repeat("9a", 32)
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	for _, entry := range []struct{ name, body string }{
		{"SKILL.md", "---\nname: hello\ndescription: d\n---\n"},
		{"scripts/hello.sh", "#!/bin/sh\necho token=$TOKEN\n"},
	} {
		file, err := writer.Create(entry.name)
		if err != nil {
			t.Fatalf("building the fixture: %v", err)
		}
		if _, err := file.Write([]byte(entry.body)); err != nil {
			t.Fatalf("building the fixture: %v", err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("building the fixture: %v", err)
	}
	archive := buffer.Bytes()

	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/credentials/lease"):
			_, _ = w.Write([]byte(`{"values":[{"env":"TOKEN","value":"from-lease"}],` +
				`"expires_at":"2030-01-01T00:00:00Z"}`))
		case strings.HasSuffix(r.URL.Path, "/metadata"):
			if r.Header.Get(api.VersionHeader) != "" {
				w.WriteHeader(http.StatusBadRequest)
				_, _ = w.Write([]byte(`{"error":{"code":"invalid_request","message":"nope"}}`))
				return
			}
			_, _ = w.Write([]byte(`{"version":{"digest":"sha256:` + digest +
				`"},"credentials_bound":true}`))
		case strings.Contains(r.URL.Path, "/author/skills/me/hello/versions/JD0T518T"):
			_, _ = w.Write([]byte(`{"skill":{"name":"hello"},"version":{"digest":"sha256:` +
				digest + `"}}`))
		case strings.HasSuffix(r.URL.Path, "/archive"):
			w.Header().Set("ETag", `"sha256:`+digest+`"`)
			_, _ = w.Write(archive)
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	})

	stdout, stderr, status := runCaptured(t, "run", "me/hello@JD0T518T", "--yes", "--", "sh", "hello.sh")

	if status != 0 {
		t.Fatalf("status = %d, stderr = %q", status, stderr)
	}
	if !strings.Contains(stdout, "token=from-lease") {
		t.Errorf("the leased value did not reach the child: stdout = %q, stderr = %q", stdout, stderr)
	}
}

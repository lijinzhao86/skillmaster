package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

// withStdin points the package's input at a string, and makes the terminal check answer what the
// test says. Both are seams for the same reason `files_write` has one: a test must be able to stand
// where a pipe and where a person stand, without touching this process's own input.
func withStdin(t *testing.T, content string, terminal bool) {
	t.Helper()
	oldStdin, oldTerminal := stdin, stdinIsTerminal
	stdin = strings.NewReader(content)
	stdinIsTerminal = func() bool { return terminal }
	t.Cleanup(func() {
		stdin = oldStdin
		stdinIsTerminal = oldTerminal
	})
}

func secretCapture(t *testing.T) (*[]byte, *string) {
	t.Helper()
	body := []byte(nil)
	path := ""
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		path = r.URL.Path
		body, _ = io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"id":"sec_1","name":"KEY","per_day":500,` +
			`"used":0,"created_at":"2026-11-08T00:00:00Z","bindings":[{"skill":"dev/x","env":"KEY"}]}`))
	})
	return &body, &path
}

// **The value goes in the request body and nowhere in the output.** The whole feature depends on
// this one direction of travel: a terminal, a log or a CI job that collected the output must not be
// another place the key lives (v52 验收 A2).
func TestSecretSetSendsTheValueAndEchoesNone(t *testing.T) {
	withStdin(t, "test-cookie-value\n", false)
	body, path := secretCapture(t)

	stdout, stderr, status := runCaptured(t, "secret", "set", "--skill", "dev/x", "--env", "KEY")
	if status != 0 {
		t.Fatalf("status = %d, stderr = %q", status, stderr)
	}
	if got := *path; got != "/api/v1/secrets" {
		t.Errorf("posted to %q", got)
	}
	var sent map[string]any
	if err := json.Unmarshal(*body, &sent); err != nil {
		t.Fatalf("request body is not JSON: %v", err)
	}
	// The bytes are the bytes: the newline `echo` added is part of what was piped in.
	if sent["value"] != "test-cookie-value\n" {
		t.Errorf("value = %q, want the bytes that arrived on stdin", sent["value"])
	}
	if sent["skill"] != "dev/x" || sent["env"] != "KEY" || sent["name"] != "KEY" {
		t.Errorf("body = %v", sent)
	}
	if strings.Contains(stdout, "test-cookie-value") || strings.Contains(stderr, "test-cookie-value") {
		t.Errorf("the value was printed: stdout %q stderr %q", stdout, stderr)
	}
	if !strings.Contains(stdout, "KEY") || !strings.Contains(stdout, "sec_1") {
		t.Errorf("the confirmation does not name what was saved: %q", stdout)
	}
	// The trailing newline is kept, and that is said rather than left to be discovered.
	if !strings.Contains(stderr, "换行") {
		t.Errorf("the trailing newline was not mentioned: %q", stderr)
	}
}

// A terminal echoes what is typed and keeps it in the scrollback, so it is refused rather than
// read. The refusal has to say how to hand the value over instead.
func TestSecretSetRefusesToReadAValueFromATerminal(t *testing.T) {
	withStdin(t, "typed-in", true)
	_, path := secretCapture(t)

	_, stderr, status := runCaptured(t, "secret", "set", "--skill", "dev/x", "--env", "KEY")
	if status != 2 {
		t.Fatalf("status = %d, want a usage error", status)
	}
	if *path != "" {
		t.Errorf("a request was made to %q", *path)
	}
	if !strings.Contains(stderr, "标准输入") {
		t.Errorf("the refusal does not say where the value goes: %q", stderr)
	}
}

func TestSecretSetRefusesAnEmptyValue(t *testing.T) {
	withStdin(t, "", false)
	_, path := secretCapture(t)

	_, stderr, status := runCaptured(t, "secret", "set", "--skill", "dev/x", "--env", "KEY")
	if status != 2 {
		t.Fatalf("status = %d, want a usage error", status)
	}
	if *path != "" {
		t.Errorf("a request was made to %q", *path)
	}
	if !strings.Contains(stderr, "标准输入") {
		t.Errorf("the refusal does not say what was missing: %q", stderr)
	}
}

// ADR 0056: deleting prints the consequence and the exact command, and executes nothing.
func TestSecretRemoveWithoutYesExecutesNothing(t *testing.T) {
	_, path := secretCapture(t)

	stdout, stderr, status := runCaptured(t, "secret", "rm", "sec_1")
	if status == 0 {
		t.Fatal("rm without --yes succeeded")
	}
	if *path != "" {
		t.Errorf("a request was made to %q", *path)
	}
	combined := stdout + stderr
	if !strings.Contains(combined, "skmr secret rm sec_1 --yes") {
		t.Errorf("the consequence does not carry the command to run: %q", combined)
	}
}

// **Raising the limit is a person's decision** (v52 验收 A13). Without a terminal there is nobody to
// ask, so it is refused — and the refusal is the sentence that keeps an agent from raising it.
func TestSecretQuotaRaiseNeedsATerminal(t *testing.T) {
	patched := false
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPatch {
			patched = true
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"items":[{"id":"sec_1","name":"KEY","per_day":500,"used":3}]}`))
	})
	withStdin(t, "", false)

	_, stderr, status := runCaptured(t, "secret", "quota", "sec_1", "--per-day", "2000")
	if status == 0 {
		t.Fatal("the raise went through without a terminal")
	}
	if patched {
		t.Error("the quota was changed anyway")
	}
	if !strings.Contains(stderr, "不要自行提高") {
		t.Errorf("the refusal does not tell an agent to stop: %q", stderr)
	}
}

// Lowering takes access away from the caller itself, so it needs no confirmation — and a gate
// nobody can pass would leave a script unable to make the limit stricter.
func TestSecretQuotaLoweringNeedsNoConfirmation(t *testing.T) {
	method := ""
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.Method == http.MethodPatch {
			method = r.Method
			_, _ = w.Write([]byte(`{"id":"sec_1","per_day":100}`))
			return
		}
		_, _ = w.Write([]byte(`{"items":[{"id":"sec_1","name":"KEY","per_day":500,"used":3}]}`))
	})
	withStdin(t, "", false)

	stdout, stderr, status := runCaptured(t, "secret", "quota", "sec_1", "--per-day", "100")
	if status != 0 {
		t.Fatalf("status = %d, stderr = %q", status, stderr)
	}
	if method != http.MethodPatch {
		t.Error("the limit was not written")
	}
	if !strings.Contains(stdout, "100") {
		t.Errorf("the result does not show the new limit: %q", stdout)
	}
}

// **A skill without bindings costs exactly the requests it did before v52** (验收 A11): the answer
// travels on the metadata response, and the lease is a decision `run` makes afterwards. This pins
// the flag's journey — the resolver must not ask for a lease itself, and must report the field it
// was given.
func TestResolveDigestCarriesTheBindingsFlag(t *testing.T) {
	digest := strings.Repeat("ab", 32)
	for _, bound := range []bool{true, false} {
		requests := 0
		pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
			requests++
			if strings.HasSuffix(r.URL.Path, "/credentials/lease") {
				t.Error("the resolver asked for a lease; only run does that")
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"version":{"digest":"sha256:` + digest + `"},` +
				`"credentials_bound":` + map[bool]string{true: "true", false: "false"}[bound] + `}`))
		})

		got, _, reported, err := resolveDigest(context.Background(), "dev/x", "dev/x", false)
		if err != nil || got != digest {
			t.Fatalf("bound=%v: digest %q, err %v", bound, got, err)
		}
		if reported.Bound != bound {
			t.Errorf("bound=%v: resolveDigest said %v", bound, reported.Bound)
		}
		// One request either way: the metadata read is the same one an unbounded skill always made.
		if requests != 1 {
			t.Errorf("bound=%v: %d requests, want 1", bound, requests)
		}
	}
}

// A digest written into the address is answered without a server, so there is nobody to say whether
// bindings exist — and the run does not ask (v52 TD §开放问题 1).
func TestADigestPinnedRunReportsNoBindings(t *testing.T) {
	digest := strings.Repeat("cd", 32)
	pointAtServer(t, func(w http.ResponseWriter, _ *http.Request) {
		t.Error("a digest-pinned resolution made a request")
		w.WriteHeader(http.StatusInternalServerError)
	})

	_, note, credentials, err := resolveDigest(context.Background(),
		"dev/x", "dev/x@sha256:"+digest, true)
	if err != nil || note != "" || credentials.Bound || len(credentials.Missing) != 0 {
		t.Fatalf("digest = %q note = %q credentials = %+v err = %v", digest, note, credentials, err)
	}
}

// **Sharing is a page action, and the command says so before it says anything else** (v52 P2,
// ADR 0126). It must not send a request: the server has no API-plane route for a grant, so a
// command that tried would be the one thing this design cannot have — a second way to create one.
func TestSecretShareOpensTheConsoleAndPerformsNothing(t *testing.T) {
	t.Setenv(config.WebURLEnv, "http://console.test")
	previous := openBrowser
	opened := ""
	openBrowser = func(target string) error {
		opened = target
		return nil
	}
	t.Cleanup(func() { openBrowser = previous })
	pointAtServer(t, func(_ http.ResponseWriter, r *http.Request) {
		t.Errorf("secret share made a request: %s %s", r.Method, r.URL.Path)
	})

	stdout, stderr, status := runCaptured(t, "secret", "share", "sec_1",
		"--skill", "dev/x", "--user", "hexu")
	if status != 0 {
		t.Fatalf("status = %d, stderr = %q", status, stderr)
	}
	// The address carries which credential, which skill and which person — an entry that landed on
	// the wrong row would be a grant nobody meant to make.
	want := "http://console.test/account/secrets?secret=sec_1&share=dev%2Fx&state=share&user=hexu"
	if opened != want {
		t.Errorf("opened %q, want %q", opened, want)
	}
	lines := strings.Split(stdout, "\n")
	if lines[0] != "还没执行：共享只能在网页上由人确认。" {
		t.Errorf("first line = %q", lines[0])
	}
	if !strings.Contains(stdout, want) {
		t.Errorf("stdout does not carry the address: %q", stdout)
	}
}

// The other half: revoking is direct, because taking access back is not the risky direction.
func TestSecretUnshareDeletesTheGrant(t *testing.T) {
	var method, path string
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		method, path = r.Method, r.URL.Path+"?"+r.URL.RawQuery
		w.WriteHeader(http.StatusNoContent)
	})

	stdout, stderr, status := runCaptured(t, "secret", "unshare", "sec_1",
		"--skill", "dev/x", "--user", "hexu")
	if status != 0 {
		t.Fatalf("status = %d, stderr = %q", status, stderr)
	}
	if method != http.MethodDelete {
		t.Errorf("method = %s", method)
	}
	if path != "/api/v1/secrets/sec_1/shares?skill=dev%2Fx&user=hexu" {
		t.Errorf("path = %q", path)
	}
	if !strings.Contains(stdout, "下一次运行就用不了") {
		t.Errorf("stdout = %q", stdout)
	}
}

// What somebody else lent this skill. Read-only, and it names the owner — a row without one decides
// nothing for the person reading it.
func TestSecretIncomingNamesTheOwner(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/secrets/incoming" || r.URL.Query().Get("skill") != "dev/x" {
			t.Errorf("unexpected request %s?%s", r.URL.Path, r.URL.RawQuery)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"items":[{"credential_id":"sec_9","name":"知乎 cookie",` +
			`"owner":"hexu","skill":"dev/x","env":"COOKIE"}]}`))
	})

	stdout, stderr, status := runCaptured(t, "secret", "incoming", "--skill", "dev/x")
	if status != 0 {
		t.Fatalf("status = %d, stderr = %q", status, stderr)
	}
	if !strings.Contains(stdout, "COOKIE") || !strings.Contains(stdout, "来自 hexu") {
		t.Errorf("stdout = %q", stdout)
	}
}

// The declaration hint (v52 A6): it names the variable, hands the user a command, and says the run
// still happens without it. The last line is not decoration — without it "the run succeeded" reads
// as "the credential worked".
func TestTheMissingCredentialHintNamesTheVariableAndTheCommand(t *testing.T) {
	stderr := captureStderr(t, func() {
		printMissingCredentials("dev/x", []string{"COOKIE", "TOKEN"})
	})
	for _, want := range []string{
		"COOKIE、TOKEN",
		"printf '%s' \"$值\" | skmr secret set --skill 'dev/x' --env COOKIE",
		"printf '%s' \"$值\" | skmr secret set --skill 'dev/x' --env TOKEN",
		"这次照常运行，脚本拿不到这些变量。",
	} {
		if !strings.Contains(stderr, want) {
			t.Errorf("hint is missing %q:\n%s", want, stderr)
		}
	}
	// Nothing to say when nothing is missing.
	if empty := captureStderr(t, func() { printMissingCredentials("dev/x", nil) }); empty != "" {
		t.Errorf("printed something for an empty declaration: %q", empty)
	}
}

package main

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/prompt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/updater"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/version"
)

// isolateConfig keeps the config directory inside the test's own temporary home.
//
// **Both variables.** `os.UserConfigDir` reads XDG_CONFIG_HOME before HOME on Linux, and the CI
// runner exports it — a test that set only HOME wrote into the real config directory once already
// (v24, `startup_test.go`).
func isolateConfig(t *testing.T) string {
	t.Helper()
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(home, ".config"))
	// Windows reads %AppData% instead, and the CI runner (or a developer) would then write into a
	// real config directory — the exact v24 failure this comment is about.
	t.Setenv("APPDATA", filepath.Join(home, "AppData", "Roaming"))
	t.Setenv("LOCALAPPDATA", filepath.Join(home, "AppData", "Local"))
	dir, err := config.Dir()
	if err != nil {
		t.Fatal(err)
	}
	return dir
}

// pretendRelease makes this test binary look like a released build pointed at a fake channel.
func pretendRelease(t *testing.T, base string) {
	t.Helper()
	old := version.Version
	oldBase := version.UpdateBase
	version.Version = "1.0.0"
	version.UpdateBase = base
	t.Setenv(UpdateBaseEnv, "")
	t.Cleanup(func() {
		version.Version = old
		version.UpdateBase = oldBase
	})
}

// fakeBinary is a stand-in for the installed executable: the real one must never be the target of
// a test that renames over it.
func fakeBinary(t *testing.T) string {
	t.Helper()
	dirs := officialInstallDirs()
	if len(dirs) == 0 {
		t.Fatal("this platform has no official install directory")
	}
	// **In the official directory**, because that is the only place the updater will replace a
	// binary; a fixture elsewhere would test the refusal, not the update.
	if err := os.MkdirAll(dirs[0], 0o755); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dirs[0], updater.BinaryName(runtime.GOOS))
	if err := os.WriteFile(path, []byte("旧版本"), 0o755); err != nil {
		t.Fatal(err)
	}
	old := executablePath
	executablePath = func() (string, error) { return path, nil }
	t.Cleanup(func() { executablePath = old })
	return path
}

// channel is a release server that counts what was asked for.
type channel struct {
	*httptest.Server
	requests int32
	packages int32
}

func newChannel(t *testing.T, marker string, payload []byte) *channel {
	t.Helper()
	c := &channel{}
	asset := updater.AssetName(runtime.GOOS, runtime.GOARCH)
	archive := platformArchive(t, runtime.GOOS, payload)
	sum := sha256.Sum256(archive)
	checksums := fmt.Sprintf("%s  %s\n", hex.EncodeToString(sum[:]), asset)
	c.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&c.requests, 1)
		switch filepath.Base(r.URL.Path) {
		case "version.txt":
			fmt.Fprintln(w, marker)
		case "checksums.txt":
			fmt.Fprint(w, checksums)
		default:
			atomic.AddInt32(&c.packages, 1)
			w.Write(archive)
		}
	}))
	t.Cleanup(c.Close)
	return c
}

// platformArchive builds the package the way the release pipeline would for this platform.
func platformArchive(t *testing.T, goos string, payload []byte) []byte {
	t.Helper()
	name := updater.BinaryName(goos)
	if goos == "windows" {
		var buffer bytes.Buffer
		writer := zip.NewWriter(&buffer)
		entry, err := writer.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := entry.Write(payload); err != nil {
			t.Fatal(err)
		}
		if err := writer.Close(); err != nil {
			t.Fatal(err)
		}
		return buffer.Bytes()
	}
	var buffer bytes.Buffer
	gz := gzip.NewWriter(&buffer)
	tw := tar.NewWriter(gz)
	if err := tw.WriteHeader(&tar.Header{Name: name, Mode: 0o755, Size: int64(len(payload))}); err != nil {
		t.Fatal(err)
	}
	if _, err := tw.Write(payload); err != nil {
		t.Fatal(err)
	}
	if err := tw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := gz.Close(); err != nil {
		t.Fatal(err)
	}
	return buffer.Bytes()
}

func TestHelpAndVersionNeverCheckForUpdates(t *testing.T) {
	isolateConfig(t)
	c := newChannel(t, "2.0.0", []byte("新版本"))
	pretendRelease(t, c.URL)
	fakeBinary(t)

	for _, args := range [][]string{{"--version"}, {"help"}, {"help", "skill"}, {"skill", "invoke", "--help"}, {"-h"}, {}} {
		runCaptured(t, args...)
	}
	if got := atomic.LoadInt32(&c.requests); got != 0 {
		t.Errorf("help and version made %d requests, want 0", got)
	}
}

func TestTheCheckIsThrottledToOnceADay(t *testing.T) {
	isolateConfig(t)
	c := newChannel(t, "1.0.0", []byte("一样"))
	pretendRelease(t, c.URL)
	fakeBinary(t)

	stdout1, stderr1, _ := runCaptured(t, "skill", "invoke")
	stdout2, stderr2, _ := runCaptured(t, "skill", "invoke")
	runCaptured(t, "skill", "invoke")
	if got := atomic.LoadInt32(&c.requests); got != 1 {
		t.Errorf("three commands made %d requests, want 1", got)
	}
	// **Nothing about the command changes because it was checked.** Same stdout, same stderr.
	if stdout1 != stdout2 || stderr1 != stderr2 {
		t.Errorf("the throttled runs differ:\n1: %q / %q\n2: %q / %q", stdout1, stderr1, stdout2, stderr2)
	}
}

func TestANewerBuildIsInstalledAndTheCommandIsUntouched(t *testing.T) {
	if runtime.GOOS == "windows" {
		// The swap is handed to a helper that has to be a real executable.
		t.Skip("covered by the Windows end-to-end test")
	}
	isolateConfig(t)
	c := newChannel(t, "2.0.0", []byte("新版本"))
	pretendRelease(t, c.URL)
	target := fakeBinary(t)

	stdout, stderr, status := runCaptured(t, "skill", "invoke")
	if status != 2 {
		t.Fatalf("status = %d, want the usage error the command itself produces", status)
	}
	if stdout != "" {
		t.Errorf("stdout = %q, want nothing: the update must not write to stdout", stdout)
	}
	if !strings.Contains(stderr, "已更新到 2.0.0") {
		t.Errorf("stderr does not report the update:\n%s", stderr)
	}
	body, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "新版本" {
		t.Errorf("installed content = %q, want the new build", body)
	}
}

func TestATamperedPackageLeavesTheBinaryAlone(t *testing.T) {
	isolateConfig(t)
	c := newChannel(t, "2.0.0", []byte("新版本"))
	c.Config.Handler = tampering(c.Server.Config.Handler.(http.Handler))
	pretendRelease(t, c.URL)
	target := fakeBinary(t)

	_, stderr, _ := runCaptured(t, "skill", "invoke")
	body, _ := os.ReadFile(target)
	if string(body) != "旧版本" {
		t.Errorf("installed content = %q, want the old build", body)
	}
	if strings.Contains(stderr, "已更新到") {
		t.Errorf("stderr claims an update happened:\n%s", stderr)
	}
	// **PRD acceptance 3: a tampered package gets one line.** Bytes that arrived and did not match
	// their checksum are not the ordinary weather of an update, and silence about them means a
	// broken mirror is never heard from.
	if !strings.Contains(stderr, "没有通过校验") {
		t.Errorf("stderr does not report the failed check:\n%s", stderr)
	}
}

// tampering serves a package whose checksum no longer matches.
func tampering(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, ".tar.gz") || strings.HasSuffix(r.URL.Path, ".zip") {
			_, _ = w.Write([]byte("被篡改"))
			return
		}
		next.ServeHTTP(w, r)
	})
}

func TestTheSwitchTurnsTheAutomaticCheckOff(t *testing.T) {
	isolateConfig(t)
	c := newChannel(t, "2.0.0", []byte("新版本"))
	pretendRelease(t, c.URL)
	fakeBinary(t)
	t.Setenv(AutoUpdateEnv, "0")

	runCaptured(t, "skill", "invoke")
	if got := atomic.LoadInt32(&c.requests); got != 0 {
		t.Errorf("requests = %d with the switch off, want 0", got)
	}
}

func TestUpdateCheckReportsWithoutDownloading(t *testing.T) {
	isolateConfig(t)
	c := newChannel(t, "2.0.0", []byte("新版本"))
	pretendRelease(t, c.URL)
	fakeBinary(t)

	stdout, _, status := runCaptured(t, "update", "--check")
	if status != 0 {
		t.Fatalf("status = %d\n%s", status, stdout)
	}
	if !strings.Contains(stdout, "有新版本") {
		t.Errorf("stdout does not report the new version:\n%s", stdout)
	}
	if got := atomic.LoadInt32(&c.packages); got != 0 {
		t.Errorf("--check downloaded %d packages, want 0", got)
	}
}

func TestUpdateInstallReplacesTheBinary(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("covered by the Windows end-to-end test")
	}
	isolateConfig(t)
	c := newChannel(t, "2.0.0", []byte("新版本"))
	pretendRelease(t, c.URL)
	target := fakeBinary(t)

	stdout, _, status := runCaptured(t, "update")
	if status != 0 {
		t.Fatalf("status = %d\n%s", status, stdout)
	}
	body, _ := os.ReadFile(target)
	if string(body) != "新版本" {
		t.Errorf("installed content = %q", body)
	}
}

func TestPromptPrintIsTheBinarysOwnText(t *testing.T) {
	isolateConfig(t)
	stdout, _, status := runCaptured(t, "prompt", "print")
	if status != 0 {
		t.Fatalf("status = %d", status)
	}
	if stdout != string(prompt.Block()) {
		t.Errorf("prompt print is not the block:\n%q", stdout)
	}
}

func TestUpdateSaysSoWhenTheBuildCannotUpdate(t *testing.T) {
	isolateConfig(t)
	pretendRelease(t, "")
	_, stderr, status := runCaptured(t, "update")
	if status != 1 {
		t.Fatalf("status = %d, want 1", status)
	}
	if !strings.Contains(stderr, "不参与更新") {
		t.Errorf("stderr does not explain the build:\n%s", stderr)
	}
}

func TestUpdateRequiresNoCredential(t *testing.T) {
	// The update path talks to the release bucket, never to the API: a machine that has never
	// logged in must still be able to update. An empty credential store and no server address are
	// the fixture; reaching the API at all would fail the test with a connection error.
	isolateConfig(t)
	c := newChannel(t, "1.0.0", []byte("same"))
	pretendRelease(t, c.URL)
	fakeBinary(t)
	_, stderr, status := runCaptured(t, "update", "--check")
	if status != 0 {
		t.Fatalf("status = %d: %s", status, stderr)
	}
}

func TestABinaryOutsideTheOfficialDirectoryIsNotReplaced(t *testing.T) {
	isolateConfig(t)
	c := newChannel(t, "2.0.0", []byte("新版本"))
	pretendRelease(t, c.URL)
	// A copy somewhere the installer did not put it: the file belongs to whoever put it there.
	target := filepath.Join(t.TempDir(), updater.BinaryName(runtime.GOOS))
	if err := os.WriteFile(target, []byte("旧版本"), 0o755); err != nil {
		t.Fatal(err)
	}
	old := executablePath
	executablePath = func() (string, error) { return target, nil }
	t.Cleanup(func() { executablePath = old })

	runCaptured(t, "skill", "invoke")
	if got := atomic.LoadInt32(&c.requests); got != 0 {
		t.Errorf("the automatic path made %d requests for a binary outside the official directory", got)
	}
	if body, _ := os.ReadFile(target); string(body) != "旧版本" {
		t.Errorf("a binary outside the official directory was replaced: %q", body)
	}

	_, stderr, status := runCaptured(t, "update")
	if status != 1 || !strings.Contains(stderr, "不在官方安装位置") {
		t.Errorf("explicit update: status=%d stderr=%q", status, stderr)
	}
	if body, _ := os.ReadFile(target); string(body) != "旧版本" {
		t.Errorf("explicit update replaced a binary outside the official directory: %q", body)
	}

	// `--check` is read-only, so it still works anywhere: it reports, and it does not touch the file.
	stdout, _, status := runCaptured(t, "update", "--check")
	if status != 0 || !strings.Contains(stdout, "有新版本") {
		t.Errorf("--check outside the official directory: status=%d stdout=%q", status, stdout)
	}
	if body, _ := os.ReadFile(target); string(body) != "旧版本" {
		t.Errorf("--check changed the binary: %q", body)
	}
}

func TestADevBuildNeverChecksForUpdates(t *testing.T) {
	isolateConfig(t)
	c := newChannel(t, "2.0.0", []byte("新版本"))
	// A development build: no `-X version.Version`, and the test seam is the only base it has.
	t.Setenv(UpdateBaseEnv, c.URL)
	fakeBinary(t)
	runCaptured(t, "skill", "invoke")
	if got := atomic.LoadInt32(&c.requests); got != 0 {
		t.Errorf("a dev build made %d requests, want 0", got)
	}
}

func TestUpdateSaysWhenItIsAlreadyCurrent(t *testing.T) {
	isolateConfig(t)
	c := newChannel(t, "1.0.0", []byte("same"))
	pretendRelease(t, c.URL)
	fakeBinary(t)
	stdout, stderr, status := runCaptured(t, "update")
	if status != 0 {
		t.Fatalf("status = %d: %s", status, stderr)
	}
	if !strings.Contains(stdout, "已是最新") {
		t.Errorf("stdout = %q, want it to say there is nothing to do", stdout)
	}
}

func TestThePromptGroupNeverTouchesTheNetwork(t *testing.T) {
	isolateConfig(t)
	c := newChannel(t, "2.0.0", []byte("新版本"))
	pretendRelease(t, c.URL)
	fakeBinary(t)
	runCaptured(t, "prompt", "print")
	runCaptured(t, "prompt", "status")
	if got := atomic.LoadInt32(&c.requests); got != 0 {
		t.Errorf("the prompt group made %d requests, want 0", got)
	}
}

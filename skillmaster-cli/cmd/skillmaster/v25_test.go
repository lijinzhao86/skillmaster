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
	"time"

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

func TestTheStaleBlockIsAnnouncedOnce(t *testing.T) {
	dir := isolateConfig(t)
	// A real file holding an old block: the notice is about the record's digest, and the file has
	// to exist for it to be about anything.
	file := filepath.Join(t.TempDir(), "CLAUDE.md")
	old := "<!-- skillmaster:begin -->\n旧的一行\n<!-- skillmaster:end -->\n"
	if err := os.WriteFile(file, []byte(old), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := prompt.Save(dir, prompt.Record{File: file, Digest: "sha256:0000", Version: "1.0.0"}); err != nil {
		t.Fatal(err)
	}
	_, first, _ := runCaptured(t, "skill", "invoke")
	if !strings.Contains(first, "规则段是旧版") {
		t.Errorf("the first command does not mention the stale block:\n%s", first)
	}
	_, second, _ := runCaptured(t, "skill", "invoke")
	if strings.Contains(second, "规则段是旧版") {
		t.Errorf("the second command says it again:\n%s", second)
	}
}

func TestADeletedBlockFileIsNotAnnounced(t *testing.T) {
	// Missing is not old. The line is for "there is an older block in your file"; a file that is
	// gone is `prompt status`'s answer, on a command that was asked about it.
	dir := isolateConfig(t)
	if err := prompt.Save(dir, prompt.Record{
		File:   filepath.Join(t.TempDir(), "gone.md"),
		Digest: "sha256:0000",
	}); err != nil {
		t.Fatal(err)
	}
	_, stderr, _ := runCaptured(t, "skill", "invoke")
	if strings.Contains(stderr, "规则段是旧版") {
		t.Errorf("a deleted instruction file produced the stale-block line:\n%s", stderr)
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

func TestPromptInstallThenUpdate(t *testing.T) {
	dir := isolateConfig(t)
	file := filepath.Join(t.TempDir(), "CLAUDE.md")
	if err := os.WriteFile(file, []byte("# 我的规则\n\n别的段落\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, stderr, status := runCaptured(t, "prompt", "install", "--file", file); status != 0 {
		t.Fatalf("install failed (%d): %s", status, stderr)
	}
	body, _ := os.ReadFile(file)
	if !strings.Contains(string(body), string(prompt.Block())) {
		t.Fatal("install did not write the block")
	}
	if !strings.HasPrefix(string(body), "# 我的规则\n\n别的段落\n") {
		t.Errorf("install rewrote what was already there:\n%s", body)
	}
	record, found, _ := prompt.Load(dir)
	if !found || record.File != file {
		t.Fatalf("no record for %s: %+v", file, record)
	}

	// A hand-written old block: the update must show it and refuse to write without --yes.
	old := "<!-- skillmaster:begin -->\n旧的一行\n<!-- skillmaster:end -->\n"
	if err := os.WriteFile(file, []byte("开头\n\n"+old+"\n结尾\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	before, _ := os.ReadFile(file)
	stdout, _, status := runCaptured(t, "prompt", "update")
	if status != 1 {
		t.Fatalf("update without --yes exited %d, want 1", status)
	}
	if !strings.Contains(stdout, "旧的一行") || !strings.Contains(stdout, "SkillMaster 管我的全部 skill") {
		t.Errorf("the diff does not show old and new:\n%s", stdout)
	}
	after, _ := os.ReadFile(file)
	if !bytes.Equal(before, after) {
		t.Fatal("update without --yes changed the file")
	}

	if _, stderr, status := runCaptured(t, "prompt", "update", "--yes"); status != 0 {
		t.Fatalf("update --yes failed (%d): %s", status, stderr)
	}
	after, _ = os.ReadFile(file)
	if !strings.HasPrefix(string(after), "开头\n\n") || !strings.HasSuffix(string(after), "\n结尾\n") {
		t.Errorf("update touched the text outside the markers:\n%s", after)
	}
	if !strings.Contains(string(after), string(prompt.Block())) {
		t.Error("update did not install the block")
	}
}

func TestPromptInstallOfAnIdenticalBlockOnlyRecords(t *testing.T) {
	dir := isolateConfig(t)
	file := filepath.Join(t.TempDir(), "AGENTS.md")
	if err := os.WriteFile(file, []byte("前文\n"+string(prompt.Block())), 0o644); err != nil {
		t.Fatal(err)
	}
	before, _ := os.ReadFile(file)
	stat, _ := os.Stat(file)
	if _, stderr, status := runCaptured(t, "prompt", "install", "--file", file); status != 0 {
		t.Fatalf("install failed (%d): %s", status, stderr)
	}
	after, _ := os.ReadFile(file)
	if !bytes.Equal(before, after) {
		t.Error("install rewrote a file that already had the block")
	}
	if stat2, _ := os.Stat(file); !stat2.ModTime().Equal(stat.ModTime()) {
		t.Error("install touched the file's mtime")
	}
	if _, found, _ := prompt.Load(dir); !found {
		t.Error("install did not record the file")
	}
}

func TestPromptStatusExitCodes(t *testing.T) {
	dir := isolateConfig(t)
	stdout, _, status := runCaptured(t, "prompt", "status")
	if status != 1 || !strings.Contains(stdout, "还没有记录") {
		t.Errorf("with no record: status=%d stdout=%q", status, stdout)
	}

	file := filepath.Join(t.TempDir(), "CLAUDE.md")
	if err := os.WriteFile(file, []byte("前文\n"+string(prompt.Block())), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := prompt.Save(dir, prompt.Record{File: file, Digest: prompt.Digest()}); err != nil {
		t.Fatal(err)
	}
	if _, _, status := runCaptured(t, "prompt", "status"); status != 0 {
		t.Errorf("with a current block: status=%d, want 0", status)
	}
	if err := os.WriteFile(file, []byte("前文\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, status := runCaptured(t, "prompt", "status"); status != 1 {
		t.Errorf("with the block removed: status=%d, want 1", status)
	}
}

func TestPromptUpdateDoesNotWaitForInput(t *testing.T) {
	dir := isolateConfig(t)
	file := filepath.Join(t.TempDir(), "CLAUDE.md")
	if err := os.WriteFile(file, []byte("前文\n"+string(prompt.Block())), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := prompt.Save(dir, prompt.Record{File: file, Digest: prompt.Digest()}); err != nil {
		t.Fatal(err)
	}
	// A pipe nobody writes to: a command that read stdin would block here for ever.
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	oldStdin := os.Stdin
	os.Stdin = reader
	t.Cleanup(func() {
		os.Stdin = oldStdin
		reader.Close()
		writer.Close()
	})

	done := make(chan int, 1)
	go func() {
		_, _, status := runCaptured(t, "prompt", "update")
		done <- status
	}()
	select {
	case status := <-done:
		if status != 0 {
			t.Errorf("status = %d, want 0 (already current)", status)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("prompt update blocked on stdin")
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

func TestPromptStatusHealsARecordThatIsBehindTheFile(t *testing.T) {
	dir := isolateConfig(t)
	file := filepath.Join(t.TempDir(), "CLAUDE.md")
	if err := os.WriteFile(file, []byte("前文\n"+string(prompt.Block())), 0o644); err != nil {
		t.Fatal(err)
	}
	// The file was fixed by hand (or by another binary); the record still names the old block.
	if err := prompt.Save(dir, prompt.Record{File: file, Digest: "sha256:0000", Notified: "sha256:0000"}); err != nil {
		t.Fatal(err)
	}
	if _, _, status := runCaptured(t, "prompt", "status"); status != 0 {
		t.Fatalf("status = %d, want 0: the file is current", status)
	}
	record, _, _ := prompt.Load(dir)
	if record.Digest != prompt.Digest() {
		t.Errorf("record digest = %q, want it healed to %q", record.Digest, prompt.Digest())
	}
}

package updater

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"context"
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
)

func TestParseReleaseReadsBothShapes(t *testing.T) {
	cases := []struct {
		line string
		want Release
	}{
		{"1.0.2", Release{Tag: "1.0.2", Raw: "1.0.2"}},
		{"1.1.0-beta (1485383)", Release{Tag: "1.1.0-beta", Revision: "1485383", Raw: "1.1.0-beta (1485383)"}},
		{"  1.1.0-beta (1485383)\n", Release{Tag: "1.1.0-beta", Revision: "1485383", Raw: "1.1.0-beta (1485383)"}},
	}
	for _, c := range cases {
		got, err := ParseRelease(c.line)
		if err != nil {
			t.Fatalf("ParseRelease(%q): %v", c.line, err)
		}
		if got != c.want {
			t.Errorf("ParseRelease(%q) = %+v, want %+v", c.line, got, c.want)
		}
	}
	for _, bad := range []string{"", "  ", "two words", "1.0.2 (a b)"} {
		if _, err := ParseRelease(bad); err == nil {
			t.Errorf("ParseRelease(%q) accepted it", bad)
		}
	}
}

func TestNewer(t *testing.T) {
	must := func(line string) Release {
		r, err := ParseRelease(line)
		if err != nil {
			t.Fatalf("ParseRelease(%q): %v", line, err)
		}
		return r
	}
	cases := []struct {
		name          string
		current, next string
		want          bool
	}{
		{"same", "1.0.2", "1.0.2", false},
		{"patch", "1.0.2", "1.0.3", true},
		{"minor", "1.0.2", "1.1.0", true},
		{"major", "1.0.2", "2.0.0", true},
		// The site only moves forward: a mirror that is behind is not a downgrade to take.
		{"older remote", "1.1.0", "1.0.9", false},
		// A beta's number never moves; the commit is the identity (ADR 0099).
		{"beta same commit", "1.1.0-beta (aaa1111)", "1.1.0-beta (aaa1111)", false},
		{"beta new commit", "1.1.0-beta (aaa1111)", "1.1.0-beta (bbb2222)", true},
		// A release is newer than its own pre-release.
		{"beta to release", "1.1.0-beta", "1.1.0", true},
		{"release to beta", "1.1.0", "1.1.0-beta", false},
		{"beta to newer beta tag", "1.1.0-beta (aaa1111)", "1.2.0-beta (bbb2222)", true},
	}
	for _, c := range cases {
		if got := Newer(must(c.current), must(c.next)); got != c.want {
			t.Errorf("%s: Newer(%q, %q) = %v, want %v", c.name, c.current, c.next, got, c.want)
		}
	}
}

func TestAssetAndStagedNames(t *testing.T) {
	if got := AssetName("linux", "arm64"); got != "skillmaster_linux_arm64.tar.gz" {
		t.Errorf("AssetName = %q", got)
	}
	if got := AssetName("windows", "amd64"); got != "skillmaster_windows_amd64.zip" {
		t.Errorf("AssetName = %q", got)
	}
	if got := BinaryName("windows"); got != "skillmaster.exe" {
		t.Errorf("BinaryName = %q", got)
	}
	release, _ := ParseRelease("1.1.0-beta (abc1234)")
	if got := StagedPath("/usr/local/bin/skillmaster", release, "linux"); got != "/usr/local/bin/skillmaster.new-abc1234" {
		t.Errorf("StagedPath = %q", got)
	}
	// The `.exe` has to be last: this file is executed as the helper.
	if got := StagedPath(`C:\bin\skillmaster.exe`, release, "windows"); got != `C:\bin\skillmaster.exe.new-abc1234.exe` {
		t.Errorf("StagedPath = %q", got)
	}
}

func TestChecksumOfMatchesOnTheBaseName(t *testing.T) {
	checksums := []byte("aaa  skillmaster_linux_amd64.tar.gz\nbbb  skillmaster_darwin_arm64.tar.gz\n")
	if got, ok := checksumOf(checksums, "skillmaster_darwin_arm64.tar.gz"); !ok || got != "bbb" {
		t.Errorf("checksumOf = %q, %v", got, ok)
	}
	if _, ok := checksumOf(checksums, "skillmaster_windows_amd64.zip"); ok {
		t.Error("checksumOf found a file that is not listed")
	}
}

// releaseServer is a channel that serves one build.
type releaseServer struct {
	*httptest.Server
	marker    string
	archive   []byte
	checksums string
	requests  int32
}

func newReleaseServer(t *testing.T, marker string, payload []byte, goos, goarch string) *releaseServer {
	t.Helper()
	s := &releaseServer{marker: marker}
	s.archive = makeArchive(t, goos, payload)
	sum := sha256.Sum256(s.archive)
	asset := AssetName(goos, goarch)
	s.checksums = fmt.Sprintf("%s  %s\n", hex.EncodeToString(sum[:]), asset)
	s.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&s.requests, 1)
		switch filepath.Base(r.URL.Path) {
		case "version.txt":
			fmt.Fprintln(w, s.marker)
		case "checksums.txt":
			fmt.Fprint(w, s.checksums)
		default:
			w.Write(s.archive)
		}
	}))
	t.Cleanup(s.Close)
	return s
}

func makeArchive(t *testing.T, goos string, payload []byte) []byte {
	t.Helper()
	name := BinaryName(goos)
	if goos == "windows" {
		// The asset name is a `.zip` on Windows, so the bytes have to be one; serving tar.gz under
		// a zip name is what made the Windows CI job red (the v25 audit's finding 3).
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

// isolatedTarget is a stand-in for the installed binary.
func isolatedTarget(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), BinaryName(runtime.GOOS))
	if err := os.WriteFile(path, []byte("旧版本"), 0o755); err != nil {
		t.Fatal(err)
	}
	return path
}

func optionsFor(t *testing.T, server *releaseServer, target string) Options {
	t.Helper()
	return Options{
		Base:    server.URL,
		Dir:     t.TempDir(),
		Target:  target,
		Current: "1.0.0",
		GOOS:    runtime.GOOS,
		GOARCH:  runtime.GOARCH,
		Client:  &http.Client{Timeout: 5 * time.Second},
	}
}

func TestFetchStagesAVerifiedBinary(t *testing.T) {
	server := newReleaseServer(t, "2.0.0", []byte("新版本"), runtime.GOOS, runtime.GOARCH)
	target := isolatedTarget(t)
	staged, err := Fetch(context.Background(), server.URL, mustRelease(t, "2.0.0"),
		filepath.Dir(target), runtime.GOOS, runtime.GOARCH, &http.Client{Timeout: 5 * time.Second})
	if err != nil {
		t.Fatalf("Fetch: %v", err)
	}
	body, err := os.ReadFile(staged)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "新版本" {
		t.Errorf("staged content = %q", body)
	}
	info, err := os.Stat(staged)
	if err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o100 == 0 {
		t.Errorf("staged file is not executable: %v", info.Mode())
	}
}

func TestFetchRefusesAChecksumMismatchAndWritesNothing(t *testing.T) {
	server := newReleaseServer(t, "2.0.0", []byte("新版本"), runtime.GOOS, runtime.GOARCH)
	server.archive = makeArchive(t, runtime.GOOS, []byte("被篡改"))
	target := isolatedTarget(t)
	_, err := Fetch(context.Background(), server.URL, mustRelease(t, "2.0.0"),
		filepath.Dir(target), runtime.GOOS, runtime.GOARCH, &http.Client{Timeout: 5 * time.Second})
	if err == nil {
		t.Fatal("Fetch accepted a tampered archive")
	}
	entries, _ := os.ReadDir(filepath.Dir(target))
	for _, entry := range entries {
		if strings.Contains(entry.Name(), ".new-") {
			t.Errorf("a failed fetch left %s behind", entry.Name())
		}
	}
}

func TestPrepareIsThrottled(t *testing.T) {
	server := newReleaseServer(t, "1.0.0", []byte("same"), runtime.GOOS, runtime.GOARCH)
	options := optionsFor(t, server, isolatedTarget(t))

	if job, _, err := Prepare(context.Background(), options); err != nil || job != nil {
		t.Fatalf("Prepare: job=%v err=%v", job, err)
	}
	if got := atomic.LoadInt32(&server.requests); got != 1 {
		t.Fatalf("first run made %d requests, want 1", got)
	}
	if job, _, err := Prepare(context.Background(), options); err != nil || job != nil {
		t.Fatalf("Prepare: job=%v err=%v", job, err)
	}
	if got := atomic.LoadInt32(&server.requests); got != 1 {
		t.Errorf("second run made %d requests in total, want 1 (the throttle did not hold)", got)
	}
}

func TestPrepareInstallsANewerBuild(t *testing.T) {
	if runtime.GOOS == "windows" {
		// The swap is handed to a helper that waits for this process to exit, which a test cannot
		// wait for. The end-to-end Windows job runs a real parent instead.
		t.Skip("covered by the Windows end-to-end test")
	}
	server := newReleaseServer(t, "2.0.0", []byte("新版本"), runtime.GOOS, runtime.GOARCH)
	target := isolatedTarget(t)
	options := optionsFor(t, server, target)

	job, _, err := Prepare(context.Background(), options)
	if err != nil {
		t.Fatal(err)
	}
	if job == nil {
		t.Fatal("Prepare found no update to make")
	}
	outcome := job.Drain(5 * time.Second)
	if outcome.Err != nil {
		t.Fatalf("Drain: %v", outcome.Err)
	}
	if outcome.Scheduled {
		t.Skip("this platform hands the swap to a helper; covered by the Windows job")
	}
	body, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "新版本" {
		t.Errorf("target content = %q, want the new build", body)
	}
	if entries, _ := os.ReadDir(filepath.Dir(target)); len(entries) != 1 {
		t.Errorf("install directory holds %d entries after an update, want just the binary", len(entries))
	}
}

func TestPrepareNoticesAChangedBinary(t *testing.T) {
	// Reinstalling by hand changes the file; the throttle must not answer for a binary it has never
	// seen.
	server := newReleaseServer(t, "1.0.0", []byte("same"), runtime.GOOS, runtime.GOARCH)
	target := isolatedTarget(t)
	options := optionsFor(t, server, target)
	if _, _, err := Prepare(context.Background(), options); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(target, []byte("另一个构建"), 0o755); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Prepare(context.Background(), options); err != nil {
		t.Fatal(err)
	}
	if got := atomic.LoadInt32(&server.requests); got != 2 {
		t.Errorf("requests = %d, want 2: a new fingerprint must force a check", got)
	}
}

func TestPrepareReportsWhenTheMachineWillNotTakeTheUpdate(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("a directory's mode does not make it unwritable on Windows")
	}
	if os.Geteuid() == 0 {
		t.Skip("root writes into a read-only directory")
	}
	server := newReleaseServer(t, "2.0.0", []byte("新版本"), runtime.GOOS, runtime.GOARCH)
	target := isolatedTarget(t)
	dir := filepath.Dir(target)
	if err := os.Chmod(dir, 0o555); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(dir, 0o755) })

	job, _, err := Prepare(context.Background(), optionsFor(t, server, target))
	if err != nil {
		t.Fatal(err)
	}
	if job == nil {
		t.Fatal("Prepare found no update to make")
	}
	outcome := job.Drain(5 * time.Second)
	if !outcome.CannotWrite {
		t.Fatalf("outcome = %+v, want CannotWrite", outcome)
	}
	body, _ := os.ReadFile(target)
	if string(body) != "旧版本" {
		t.Errorf("target content = %q, want the old build", body)
	}
}

func TestShouldCheck(t *testing.T) {
	now := time.Now()
	if !ShouldCheck(State{}, now, "base", 1, now) {
		t.Error("a zero state must check")
	}
	fresh := State{CheckedAt: now.Add(-time.Hour), Base: "base", BinarySize: 1, BinaryMtime: now}
	if ShouldCheck(fresh, now, "base", 1, now) {
		t.Error("a fresh check was repeated")
	}
	if !ShouldCheck(fresh, now, "other", 1, now) {
		t.Error("a different base must check")
	}
	if !ShouldCheck(fresh, now, "base", 2, now) {
		t.Error("a different binary must check")
	}
	old := State{CheckedAt: now.Add(-Throttle - time.Minute), Base: "base", BinarySize: 1, BinaryMtime: now}
	if !ShouldCheck(old, now, "base", 1, now) {
		t.Error("a day-old check must run again")
	}
}

func TestTheHelperRenamesTheStagedFileIntoPlace(t *testing.T) {
	dir := t.TempDir()
	source := filepath.Join(dir, "new")
	target := filepath.Join(dir, "skillmaster")
	if err := os.WriteFile(source, []byte("new"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(target, []byte("old"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := runHelperFrom(source, target, time.Second, 10*time.Millisecond); err != nil {
		t.Fatalf("runHelperFrom: %v", err)
	}
	body, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "new" {
		t.Errorf("target = %q, want the staged file", body)
	}
	if _, err := os.Stat(source); !os.IsNotExist(err) {
		t.Error("the staged file is still there")
	}
}

func TestAStagedUpdateFromAnEarlierRunIsAppliedFirst(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the swap is handed to a helper on Windows; see the end-to-end test")
	}
	server := newReleaseServer(t, "1.0.0", []byte("same"), runtime.GOOS, runtime.GOARCH)
	target := isolatedTarget(t)
	dir := t.TempDir()
	staged := target + ".new-old"
	if err := os.WriteFile(staged, []byte("上次下好的"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := SaveState(dir, State{
		CheckedAt: time.Now(), Base: server.URL,
		Pending: &Pending{Path: staged, Revision: "old", Version: "1.5.0"},
	}); err != nil {
		t.Fatal(err)
	}
	options := optionsFor(t, server, target)
	options.Dir = dir
	job, notice, err := Prepare(context.Background(), options)
	if err != nil || job != nil {
		t.Fatalf("Prepare: job=%v err=%v", job, err)
	}
	if !strings.Contains(notice, "1.5.0") {
		t.Errorf("notice = %q, want it to name the version that was installed", notice)
	}
	body, _ := os.ReadFile(target)
	if string(body) != "上次下好的" {
		t.Errorf("target = %q, want the staged file applied", body)
	}
	if state := LoadState(dir); state.Pending != nil {
		t.Error("the pending record was not cleared")
	}
}

func mustRelease(t *testing.T, line string) Release {
	t.Helper()
	release, err := ParseRelease(line)
	if err != nil {
		t.Fatal(err)
	}
	return release
}

func TestAVerificationFailureIsReportedAndRetriedSoon(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the staged file cannot be executed on Windows; the check itself is the same")
	}
	server := newReleaseServer(t, "2.0.0", []byte("新版本"), runtime.GOOS, runtime.GOARCH)
	server.archive = makeArchive(t, runtime.GOOS, []byte("被篡改"))
	target := isolatedTarget(t)
	options := optionsFor(t, server, target)

	job, _, err := Prepare(context.Background(), options)
	if err != nil {
		t.Fatal(err)
	}
	if job == nil {
		t.Fatal("Prepare found no update to make")
	}
	outcome := job.Drain(5 * time.Second)
	if !outcome.Verify {
		t.Fatalf("outcome = %+v, want Verify", outcome)
	}
	if outcome.Suppress {
		t.Error("the first verification failure was suppressed")
	}
	// **An hour, not a day.** The check succeeded; the transfer did not.
	state := LoadState(options.Dir)
	if age := time.Since(state.CheckedAt); age < Throttle-retryAfterFailure-2*time.Minute {
		t.Errorf("the next check is %v away; a failed download must not wait the full throttle", Throttle-age)
	}
	body, _ := os.ReadFile(target)
	if string(body) != "旧版本" {
		t.Errorf("target = %q, want the old build", body)
	}
}

func TestAStagedFileOlderThanTheRunningBinaryIsDropped(t *testing.T) {
	server := newReleaseServer(t, "1.0.0", []byte("same"), runtime.GOOS, runtime.GOARCH)
	target := isolatedTarget(t)
	dir := t.TempDir()
	staged := target + ".new-old"
	if err := os.WriteFile(staged, []byte("更旧的"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := SaveState(dir, State{
		CheckedAt: time.Now(), Base: server.URL,
		Pending: &Pending{Path: staged, Revision: "old", Version: "0.9.0"},
	}); err != nil {
		t.Fatal(err)
	}
	options := optionsFor(t, server, target)
	options.Dir = dir
	options.Current = "1.0.0" // newer than the staged 0.9.0, and equal to what the channel serves
	if _, _, err := Prepare(context.Background(), options); err != nil {
		t.Fatal(err)
	}
	body, _ := os.ReadFile(target)
	if string(body) != "旧版本" {
		t.Errorf("target = %q: a stale staged file was applied over a newer binary", body)
	}
	if state := LoadState(dir); state.Pending != nil {
		t.Error("the stale pending record was not dropped")
	}
}
func TestAReleaseIsAnnouncedOnce(t *testing.T) {
	job := &Job{dir: t.TempDir(), release: Release{Raw: "2.0.0"}}
	if !job.announceOnce() {
		t.Fatal("the first report was suppressed")
	}
	if job.announceOnce() {
		t.Error("the second report was not suppressed")
	}
	if state := LoadState(job.dir); state.Notified != "2.0.0" {
		t.Errorf("Notified = %q, want the release", state.Notified)
	}
}

func TestALockedMachineIsNotUpdatedTwice(t *testing.T) {
	server := newReleaseServer(t, "2.0.0", []byte("新版本"), runtime.GOOS, runtime.GOARCH)
	target := isolatedTarget(t)
	options := optionsFor(t, server, target)

	unlock, ok, err := AcquireLock(options.Dir)
	if err != nil || !ok {
		t.Fatalf("AcquireLock: ok=%v err=%v", ok, err)
	}
	job, _, err := Prepare(context.Background(), options)
	unlock()
	if err != nil {
		t.Fatal(err)
	}
	if job != nil {
		t.Fatal("a second updater started while the lock was held")
	}
	if got := atomic.LoadInt32(&server.requests); got != 0 {
		t.Errorf("the blocked updater made %d requests, want 0", got)
	}
}

func TestAFailedRecoveryIsAnnouncedOnce(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("a directory's mode does not make it unwritable on Windows")
	}
	if os.Geteuid() == 0 {
		t.Skip("root writes into a read-only directory")
	}
	server := newReleaseServer(t, "1.0.0", []byte("same"), runtime.GOOS, runtime.GOARCH)
	target := isolatedTarget(t)
	dir := t.TempDir()
	staged := target + ".new-old"
	if err := os.WriteFile(staged, []byte("上次下好的"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := SaveState(dir, State{
		CheckedAt: time.Now(), Base: server.URL,
		Pending: &Pending{Path: staged, Revision: "old", Version: "2.0.0"},
	}); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(filepath.Dir(target), 0o555); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(filepath.Dir(target), 0o755) })

	options := optionsFor(t, server, target)
	options.Dir = dir
	options.Current = "1.0.0"
	job, notice, err := Prepare(context.Background(), options)
	if err != nil || job != nil {
		t.Fatalf("Prepare: job=%v err=%v", job, err)
	}
	// **The line has to survive the throttle early-return.** It was computed and marked as
	// announced; dropping it there meant the promised message never appeared (the audit's NEW-A).
	if !strings.Contains(notice, "装不进去") {
		t.Errorf("notice = %q, want the cannot-install line", notice)
	}

	_, second, err := Prepare(context.Background(), options)
	if err != nil {
		t.Fatal(err)
	}
	if second != "" {
		t.Errorf("the same failure was announced twice: %q", second)
	}
}

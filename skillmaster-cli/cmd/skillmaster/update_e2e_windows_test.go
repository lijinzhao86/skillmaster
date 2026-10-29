//go:build windows

package main

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/updater"
)

// TestTheWindowsHelperReplacesTheRunningBinary is the end-to-end proof of ADR 0103's Windows path.
//
// **It needs a real parent process**, which is why it is not a unit test: the helper waits for the
// process that spawned it to exit, so nothing inside one test process can observe the swap. Here a
// built 1.0.0 binary runs a command, downloads a built 2.0.0 binary, starts it as the helper and
// exits; the test then watches the file at the target path until it answers as 2.0.0.
//
// **Opt-in, because it builds two binaries.** The Windows CI job sets SKILLMASTER_E2E_UPDATE=1; a
// laptop runs the rest of the suite without it.
func TestTheWindowsHelperReplacesTheRunningBinary(t *testing.T) {
	if os.Getenv("SKILLMASTER_E2E_UPDATE") != "1" {
		t.Skip("set SKILLMASTER_E2E_UPDATE=1 to build and exercise the real binaries")
	}
	dir := t.TempDir()
	// **The installed copy has to live where the installer puts it.** The updater only replaces a
	// binary in `%LOCALAPPDATA%\SkillMaster\bin`; a fixture anywhere else is refused before it
	// makes a single request, and this test would fail by timing out.
	home := filepath.Join(dir, "home")
	officialDir := filepath.Join(home, "AppData", "Local", "SkillMaster", "bin")
	if err := os.MkdirAll(officialDir, 0o755); err != nil {
		t.Fatal(err)
	}

	// The server has to exist before the old binary is built: its address is compiled into it.
	newBinary := buildCLI(t, filepath.Join(dir, "new"), "2.0.0")
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch filepath.Base(r.URL.Path) {
		case "version.txt":
			fmt.Fprintln(w, "2.0.0")
		case "checksums.txt":
			asset := updater.AssetName(runtime.GOOS, runtime.GOARCH)
			fmt.Fprintf(w, "%s  %s\n", sha256Hex(zipOf(t, newBinary)), asset)
		default:
			w.Write(zipOf(t, newBinary))
		}
	}))
	defer server.Close()

	oldBinary := buildCLI(t, officialDir, "1.0.0", server.URL)

	command := exec.Command(oldBinary, "skill", "invoke")
	command.Env = append(os.Environ(),
		"HOME="+home, "USERPROFILE="+home,
		"APPDATA="+filepath.Join(home, "AppData", "Roaming"),
		"LOCALAPPDATA="+filepath.Join(home, "AppData", "Local"),
		"XDG_CONFIG_HOME="+filepath.Join(home, ".config"),
		"SKILLMASTER_UPDATE_BASE="+server.URL,
		"SKILLMASTER_AUTO_UPDATE=1",
	)
	output, err := command.CombinedOutput()
	if err != nil {
		// `skill invoke` with no address is a usage error; that is the command the update rides on.
		if !strings.Contains(string(output), "用法") {
			t.Fatalf("the parent failed unexpectedly: %v\n%s", err, output)
		}
	}

	// **Watch the path itself while waiting.** The claim this test exists to prove is that the
	// swap never leaves a moment without `skmr.exe`; polling the version every 200 ms would
	// only see the endpoints.
	missing := make(chan string, 1)
	stop := make(chan struct{})
	go func() {
		for {
			select {
			case <-stop:
				return
			default:
			}
			if _, err := os.Stat(oldBinary); err != nil {
				select {
				case missing <- err.Error():
				default:
				}
				return
			}
			time.Sleep(10 * time.Millisecond)
		}
	}()

	deadline := time.Now().Add(90 * time.Second)
	for {
		versionOut, err := exec.Command(oldBinary, "--version").Output()
		if err == nil && strings.Contains(string(versionOut), "2.0.0") {
			break
		}
		if time.Now().After(deadline) {
			close(stop)
			t.Fatalf("the binary still answers as 1.0.0 after 90 s: %v %s", err, versionOut)
		}
		time.Sleep(200 * time.Millisecond)
	}
	close(stop)
	select {
	case message := <-missing:
		t.Errorf("the target path was missing during the swap: %s", message)
	default:
	}

	entries, err := os.ReadDir(filepath.Dir(oldBinary))
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if strings.Contains(entry.Name(), ".new-") {
			t.Errorf("the install directory still holds %s", entry.Name())
		}
	}
}

// buildCLI builds this CLI into outDir at the given version, optionally pointing its updater at
// base.
func buildCLI(t *testing.T, outDir, version string, base ...string) string {
	t.Helper()
	dir := outDir
	out := filepath.Join(dir, updater.BinaryName("windows"))
	if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
		t.Fatal(err)
	}
	ldflags := "-X github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/version.Version=" + version
	if len(base) > 0 {
		ldflags += " -X github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/version.UpdateBase=" + base[0]
	}
	command := exec.Command("go", "build", "-o", out, "-ldflags", ldflags,
		"github.com/lijinzhao86/skillmaster/skillmaster-cli/cmd/skillmaster")
	command.Dir = "../.."
	if output, err := command.CombinedOutput(); err != nil {
		t.Fatalf("build %s: %v\n%s", version, err, output)
	}
	return out
}

func zipOf(t *testing.T, binary string) []byte {
	t.Helper()
	body, err := os.ReadFile(binary)
	if err != nil {
		t.Fatal(err)
	}
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	entry, err := writer.Create(updater.BinaryName("windows"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := entry.Write(body); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	return buffer.Bytes()
}

func sha256Hex(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

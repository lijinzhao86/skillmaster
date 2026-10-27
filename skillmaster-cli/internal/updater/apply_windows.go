//go:build windows

package updater

import (
	"os"
	"os/exec"
	"syscall"
)

// Windows creation flags (winbase.h). DETACHED_PROCESS keeps the helper off our console — the
// terminal may be closed the instant the parent exits, and a process still attached to it can be
// killed with it.
const (
	createNewProcessGroup = 0x00000200
	detachedProcess       = 0x00000008
)

// Apply starts the staged binary as the helper and returns immediately.
//
// **It cannot be a rename from here.** Windows will not replace a running image, so the only way to
// put the new file where the old one is, is to wait until nothing is running from that name — which
// is exactly what the helper does, starting the instant this process exits. The alternative, renaming
// the old binary aside first, leaves a stale copy on disk that this CLI is not allowed to delete
// (ADR 0101) and opens a window with no `skillmaster.exe` at all.
func Apply(staged, target string) (ApplyResult, error) {
	command := exec.Command(staged)
	command.Env = append(os.Environ(), TargetEnv+"="+target)
	command.Stdin = nil
	command.Stdout = nil
	command.Stderr = nil
	command.SysProcAttr = &syscall.SysProcAttr{
		CreationFlags: createNewProcessGroup | detachedProcess,
	}
	if err := command.Start(); err != nil {
		return ApplyResult{}, err
	}
	// Not Wait: the helper outlives this process by design. Releasing the handle is all that is
	// needed so it is not left as a child this process would have to reap.
	_ = command.Process.Release()
	return ApplyResult{Scheduled: true}, nil
}

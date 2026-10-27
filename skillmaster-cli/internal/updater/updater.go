package updater

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

// DrainBudget is how long a command waits for its own download before giving up.
//
// **The command is what pays for the update, because there is no daemon.** Ten seconds is enough
// for 8 MB on anything better than a bad mobile connection, and short enough that a person does not
// wonder whether the CLI hung. A download that misses the budget is simply not kept.
const DrainBudget = 10 * time.Second

// retryAfterFailure is how soon a machine tries again after an update that did not go through.
//
// **Not a day.** The day-long throttle answers "when may this machine look again", and it is
// written when the lookup succeeds; a *download* that failed, or that ran out of budget, is a
// different event, and making it wait a day turns one blip into a machine that never updates. An
// hour is short enough to recover from a bad network and long enough not to hammer a broken mirror.
const retryAfterFailure = time.Hour

// Options is everything Prepare needs to know, passed in rather than reached for, so the whole
// path can be exercised against a test server and a temporary directory.
type Options struct {
	// Base is the channel, from version.UpdateBase (or the test override).
	Base string
	// Dir is the CLI's config directory.
	Dir string
	// Target is the executable that would be replaced.
	Target string
	// Current is `version.String()` of the running binary.
	Current string
	// GOOS / GOARCH pick the artefact. Usually runtime.GOOS / runtime.GOARCH.
	GOOS, GOARCH string
	// Client and Now are test seams; nil means the ordinary defaults.
	Client *http.Client
	Now    func() time.Time
}

func (o Options) now() time.Time {
	if o.Now != nil {
		return o.Now()
	}
	return time.Now()
}

// checkClient bounds the one request every command may pay for.
func (o Options) checkClient() *http.Client {
	if o.Client != nil {
		return o.Client
	}
	return &http.Client{Timeout: CheckTimeout}
}

// downloadClient does **not** carry the check's deadline.
//
// **The 8 MB body is not a 1.5 s request.** `http.Client.Timeout` covers reading the body, so
// handing the check's client to the download silently capped every update at ~43 Mbps and made
// every slower machine fail verification-or-transfer for ever (found in the v25 audit). The
// download is bounded by `DrainBudget` through the context instead.
func (o Options) downloadClient() *http.Client {
	if o.Client != nil {
		return o.Client
	}
	return &http.Client{}
}

// Job is a download in flight. Its zero value is not usable; Prepare returns one only when there is
// something to wait for.
type Job struct {
	release Release
	dir     string
	target  string
	goos    string
	now     func() time.Time
	done    chan Result
	cancel  context.CancelFunc
	unlock  func()
}

// Result is what the download goroutine produced.
type Result struct {
	Path string
	Err  error
}

// Outcome is what Drain reports, for the one line the command prints.
type Outcome struct {
	// Version is the release that was being fetched, for the message.
	Version string
	// Scheduled means a helper was started and the swap happens after this process exits.
	Scheduled bool
	// CannotWrite means the update was real but this machine would not take it (read-only install
	// directory, sandbox). The caller turns it into one line.
	CannotWrite bool
	// Verify means the download arrived and did not check out: a bad checksum, a corrupt archive,
	// a missing asset. **This one is announced**, unlike a transfer that simply failed — a mirror
	// serving broken bytes would otherwise never be heard from (PRD acceptance 3).
	Verify bool
	// Suppress means this exact release was already announced, so the caller prints nothing.
	Suppress bool
	// Err is the failure, if any. Callers stay quiet about transfer failures and about a download
	// that missed its budget.
	Err error
}

// Prepare decides whether this run checks, checks if it should, and starts the download if there is
// one. The returned notice is a line to show before the command runs (a staged update left by an
// earlier run); an empty string means there is nothing to say.
//
// **Every failure is a quiet no.** A command's job is the command; an update it could not look up
// is not a reason to change its output or its exit code.
func Prepare(ctx context.Context, o Options) (*Job, string, error) {
	if o.Base == "" || o.Target == "" || o.Dir == "" {
		return nil, "", nil
	}

	// **The lock is taken before anything else, including the recovery below.** Two processes
	// starting at the same moment must not both apply a staged file (the audit's finding 8), and
	// the recovery is a rename.
	unlock, ok, err := acquireLock(o.Dir)
	if err != nil || !ok {
		return nil, "", nil
	}

	state := LoadState(o.Dir)
	notice := ""

	// **A staged download from an earlier run is applied first, before any network.** It costs
	// nothing, it is the recovery path for a crash between staging and renaming, and for a Windows
	// helper that was not allowed to rename.
	if state.Pending != nil {
		pending := *state.Pending
		// **Stop before spawning, not after.** A capped record that still started one more helper
		// would spawn one time more than the number the docs promise.
		if pending.Spawns >= maxPendingSpawns {
			state.Pending = nil
			_ = SaveState(o.Dir, state)
			unlock()
			return nil, "", nil
		}
		pendingNotice, settled, applyErr := recoverPending(o, pending)
		switch {
		case settled:
			state.Pending = nil
			_ = SaveState(o.Dir, state)
			unlock()
			return nil, pendingNotice, nil
		case applyErr == nil:
			// Handed to a helper: the swap happens after this process exits, so the record stays
			// until a later run sees the file gone. **But not for ever**: a helper that can never
			// rename (the target stays locked) would otherwise start one more of itself on every
			// command and never let the check run again.
			pending.Spawns++
			state.Pending = &pending
			_ = SaveState(o.Dir, state)
			unlock()
			return nil, pendingNotice, nil
		default:
			// **A staged file that cannot be applied must not stop the machine from checking.**
			// Returning here (as an earlier draft did) meant one refused rename turned the updater
			// off for ever and silently. Keep the record, say it once if it is a permission
			// refusal, and carry on to the check below.
			if isPermission(applyErr) && announceRelease(o.Dir, pending.Version) {
				notice = fmt.Sprintf("发现新版本 %s，但这台机器装不进去：%v。更新方法：重跑安装说明里那条命令。",
					pending.Version, applyErr)
			}
		}
	}

	// **The recovery may have written the record** (the announcement above marks `Notified`), so
	// re-read it before this run's own bookkeeping overwrites it — otherwise the mark is thrown
	// away and the same line comes back on every check.
	state = LoadState(o.Dir)

	size, mtime := statTarget(o.Target)
	if !ShouldCheck(state, o.now(), o.Base, size, mtime) {
		unlock()
		return nil, notice, nil
	}

	checkCtx, cancel := context.WithTimeout(ctx, CheckTimeout)
	remote, checkErr := Check(checkCtx, o.Base, o.checkClient())
	cancel()

	state.CheckedAt = o.now()
	state.Base = o.Base
	state.BinarySize = size
	state.BinaryMtime = mtime
	_ = SaveState(o.Dir, state)
	if checkErr != nil {
		unlock()
		return nil, notice, nil
	}

	current, err := ParseRelease(o.Current)
	if err != nil || !Newer(current, remote) {
		unlock()
		return nil, notice, nil
	}

	downloadCtx, cancelDownload := context.WithCancel(context.Background())
	job := &Job{
		release: remote,
		dir:     o.Dir,
		target:  o.Target,
		goos:    o.GOOS,
		now:     o.now,
		done:    make(chan Result, 1),
		cancel:  cancelDownload,
		unlock:  unlock,
	}
	go func() {
		path, err := Fetch(downloadCtx, o.Base, remote, filepath.Dir(o.Target), o.GOOS, o.GOARCH, o.downloadClient())
		job.done <- Result{Path: path, Err: err}
	}()
	return job, notice, nil
}

// recoverPending finishes what an earlier run started.
//
// It answers two questions the record alone cannot: is the staged file still there, and is it still
// newer than what is running now. **The second one matters because the person may have followed the
// "rerun the installer" advice in between** — applying a stale staged file over a fresh install
// would be a silent downgrade (the audit's finding 7).
// `settled` means the record can be dropped: the file is gone, the machine is already past it, or
// the rename went through. A helper that was only *started* is not settled — the swap has not
// happened yet, and a helper that dies must leave the next run something to retry with.
func recoverPending(o Options, pending Pending) (notice string, settled bool, err error) {
	if _, statErr := os.Stat(pending.Path); statErr != nil {
		return "", true, nil // gone: a previous run (or its helper) already renamed it into place
	}
	if pending.Version != "" {
		current, currentErr := ParseRelease(o.Current)
		staged, stagedErr := ParseRelease(pending.Version)
		if currentErr == nil && stagedErr == nil && !Newer(current, staged) {
			return "", true, nil // this machine is already at it, or past it
		}
	}
	result, applyErr := Apply(pending.Path, o.Target)
	if applyErr != nil {
		return "", false, applyErr
	}
	version := pending.Version
	if version == "" {
		version = "新版本"
	}
	if result.Scheduled {
		return fmt.Sprintf("skillmaster 已下载 %s，本命令结束后自动替换。", version), false, nil
	}
	return fmt.Sprintf("skillmaster 已更新到 %s（上一条命令留下的），本条命令仍是旧版。", version), true, nil
}

// Drain waits at most budget for the download, then installs what it has.
//
// Called from a defer in the command, so it runs whatever the command returned.
func (j *Job) Drain(budget time.Duration) Outcome {
	timer := time.NewTimer(budget)
	defer timer.Stop()
	select {
	case result := <-j.done:
		return j.finish(result)
	case <-timer.C:
		// Not finished: drop it. Nothing was written (Fetch stages only after verifying), so there
		// is nothing to clean up.
		j.cancel()
		j.retrySoon()
		j.unlock()
		return Outcome{Version: j.release.Raw, Err: context.DeadlineExceeded}
	}
}

// finish installs a completed download and keeps the record coherent.
//
// **The staged file is recorded before the swap on every platform**, not only on Windows: a crash
// between the write and the rename would otherwise leave a file nobody retries (the audit's
// finding 6).
func (j *Job) finish(result Result) Outcome {
	out := Outcome{Version: j.release.Raw}
	if result.Err != nil {
		out.Err = result.Err
		out.Verify = isVerify(result.Err)
		out.CannotWrite = isPermission(result.Err)
		j.retrySoon()
		if out.Verify || out.CannotWrite {
			out.Suppress = !j.announceOnce()
		}
		j.unlock()
		return out
	}
	state := LoadState(j.dir)
	state.Pending = &Pending{Path: result.Path, Revision: j.release.Revision, Version: j.release.Raw}
	_ = SaveState(j.dir, state)

	applied, err := Apply(result.Path, j.target)
	if err != nil {
		out.Err = err
		out.CannotWrite = isPermission(err)
		if out.CannotWrite {
			out.Suppress = !j.announceOnce()
		} else {
			j.retrySoon()
		}
		j.unlock()
		return out
	}

	// **A started helper has not swapped anything yet.** Keeping the record is what lets a later run
	// retry a helper that was killed or that could not rename (the audit's MEDIUM-1); the next run
	// sees the file gone once the swap did happen.
	if !applied.Scheduled {
		state = LoadState(j.dir)
		state.Pending = nil
		_ = SaveState(j.dir, state)
	}
	out.Scheduled = applied.Scheduled
	j.unlock()
	return out
}

// retrySoon moves the next check an hour away instead of a day.
//
// Called while this job still holds the lock, so the write cannot race another process's.
func (j *Job) retrySoon() {
	state := LoadState(j.dir)
	now := time.Now()
	if j.now != nil {
		now = j.now()
	}
	state.CheckedAt = now.Add(-(Throttle - retryAfterFailure))
	_ = SaveState(j.dir, state)
}

// announceOnce records that this release has been reported, and says whether this call is the one
// that should print. **Once per release**: a sandbox that refuses every update would otherwise add
// a line to every command.
func (j *Job) announceOnce() bool { return announceRelease(j.dir, j.release.Raw) }

// announceRelease is announceOnce for a release named only by its record.
func announceRelease(dir, raw string) bool {
	if raw == "" {
		return false
	}
	state := LoadState(dir)
	if state.Notified == raw {
		return false
	}
	state.Notified = raw
	_ = SaveState(dir, state)
	return true
}

// Release names the version this job is fetching.
func (j *Job) Release() string { return j.release.Raw }

// AcquireLock takes the machine-wide update lock without waiting. `ok == false` means another
// skillmaster is updating; that is not an error.
//
// Exported because `skillmaster update` takes the same lock: without it, a scripted update and an
// automatic one can both stage and rename (the audit's finding 9).
func AcquireLock(dir string) (func(), bool, error) { return acquireLock(dir) }

// isPermission tells "this machine will not take the file" apart from "something went wrong".
// Only the first one deserves a printed line, because only the first one has an action for the
// person: rerun the installer.
func isPermission(err error) bool {
	return errors.Is(err, fs.ErrPermission) || os.IsPermission(err) || isReadOnlyFilesystem(err)
}

// isVerify reports a download that arrived and did not check out.
func isVerify(err error) bool {
	var verify *VerifyError
	return errors.As(err, &verify)
}

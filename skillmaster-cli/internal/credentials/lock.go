package credentials

import (
	"crypto/sha256"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"time"
)

// Lock is a per-machine, per-server mutual exclusion around refreshing.
//
// **It reduces a race; it does not remove the need to survive one.** ADR 0024's grace window is
// what makes a concurrent refresh safe — this just makes it rare, so that the window is a backstop
// rather than the thing doing the work. That is why a lock that cannot be taken is not an error
// the caller has to stop for: proceeding without it is the pre-lock behaviour, which the server
// already tolerates.
//
// The hazard it addresses is real and not theoretical: an agent calls the CLI several times in one
// task (ADR 0011), those invocations are separate processes, and two of them can find the access
// token expired at the same moment. Both then refresh with the same refresh token; one wins, and
// the other is holding a token that has just been rotated away.
type Lock struct {
	path string
}

// NewLock names the lock for one server's credentials.
//
// The server is hashed into the name rather than written into it: a base URL has slashes and a
// colon, and a lock path that needs escaping is a lock path that will one day be a directory
// somebody did not expect. The hash is of the URL as given, so two spellings of one server take
// two locks — over-locking, which is the harmless direction.
func NewLock(dir, server string) Lock {
	sum := sha256.Sum256([]byte(server))
	return Lock{path: filepath.Join(dir, fmt.Sprintf("refresh-%x.lock", sum[:6]))}
}

// Acquire takes the lock, waiting up to timeout for whoever holds it.
//
// The mechanism is `os.Mkdir`, which is atomic on every filesystem this runs on and needs no
// dependency — the same choice Bytebase made for the same problem. A directory rather than a file
// because creating one either succeeds or fails, with nothing in between.
//
// A lock left behind by a process that died is possible, so waiting has a deadline and timing out
// is reported but not fatal. `release` is always safe to call, including when the lock was never
// taken.
//
// **The directory is created here, because on macOS it usually does not exist yet.** The lock sits
// beside the credential *file*, and a keychain credential never touches that file — so its
// directory is never created either, and `Mkdir` for the lock failed with ENOENT on every single
// refresh. That is not the contention this type is built to report: it is a permanent condition
// that reads like a transient one, and it defeated the lock exactly where the CLI is used
// interactively. Found by watching a real refresh (2026-10-05), which printed the warning and went
// ahead — correct per the contract, and for a reason the contract never anticipated.
func (l Lock) Acquire(timeout time.Duration) (release func(), err error) {
	if err := os.MkdirAll(filepath.Dir(l.path), 0o700); err != nil {
		return func() {}, fmt.Errorf("creating the directory for the refresh lock at %s: %w", l.path, err)
	}

	deadline := time.Now().Add(timeout)
	for {
		createErr := os.Mkdir(l.path, 0o700)
		if createErr == nil {
			return func() { _ = os.Remove(l.path) }, nil
		}
		if !errors.Is(createErr, fs.ErrExist) {
			// Not contention — a path that cannot be created at all (permissions, a file in the
			// way). Reported rather than retried, because waiting will not fix it.
			return func() {}, fmt.Errorf("taking the refresh lock at %s: %w", l.path, createErr)
		}

		// **A holder that is gone is not a holder.** The directory outlives the process that made it
		// and nothing else ever clears it, so a single crash mid-refresh used to leave a lock that
		// was permanent in fact rather than in intent: every later refresh waited out the full
		// timeout and printed the "somebody else holds it" warning, for good. The comment above
		// anticipated "a lock left behind by a process that died" as a passing thing; it is not
		// passing unless something ends it.
		//
		// Taking it from an *unfinished* holder is no worse than what the timeout already does. The
		// old behaviour at the deadline was to give up on the lock and refresh anyway, so the worst
		// case here — a live holder older than staleLockAfter — reaches the same place: two
		// refreshes, and ADR 0024's grace window absorbs that.
		var unusable string
		if age, statErr := l.age(); statErr == nil && age > staleLockAfter {
			if os.Remove(l.path) == nil {
				continue
			}
		} else if statErr != nil {
			// Not contention at all — most often a plain file where the directory goes, which nothing
			// will ever clear. Carried into the message below rather than dropped: "held for 10s" is a
			// description of a wait, and waiting again next time will not help.
			unusable = fmt.Sprintf("（%v，所以它不会自己释放）", statErr)
		}

		if time.Now().After(deadline) {
			return func() {}, fmt.Errorf("the refresh lock at %s was held for %s%s", l.path, timeout,
				unusable)
		}
		time.Sleep(25 * time.Millisecond)
	}
}

// staleLockAfter is how old a lock has to be before it is treated as abandoned rather than held.
//
// **It is a fixed age, not the waiter's timeout**, and that distinction is the whole reason this is
// its own constant: comparing against the timeout makes the threshold however long the *next*
// caller was willing to wait, so a second holder whose patience equals the age it is looking at
// takes the lock from a live holder at exactly the moment it should have given up. Fixing the age
// fixes what the comparison means.
//
// Two minutes, because the thing it guards is bounded and known: a refresh is discovery (10s) plus
// one token call (30s), so a lock older than that cannot belong to a refresh that is still running.
// Generous on purpose — being wrong here costs one stolen lock, which the grace window absorbs,
// while being too eager costs it often.
const staleLockAfter = 2 * time.Minute

// age is how long the lock directory has existed, which is how long its holder has held it.
//
// The holder never touches the directory again, so its modification time is the moment it was
// taken — which is what makes an age comparison a statement about the holder rather than about the
// filesystem. Anything that is not a directory is not a lock at all, and an error says so: a file
// sitting where the lock goes is not contention and must not be swept aside as if it were stale.
func (l Lock) age() (time.Duration, error) {
	info, err := os.Stat(l.path)
	if err != nil {
		return 0, err
	}
	if !info.IsDir() {
		return 0, fmt.Errorf("%s is not a lock directory", l.path)
	}
	return time.Since(info.ModTime()), nil
}

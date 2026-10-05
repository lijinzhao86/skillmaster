package credentials

import (
	"errors"
	"fmt"
	"time"
)

// keychainDeadline is how long one keychain call may take before this run gives up on the keychain
// and uses the file instead.
//
// Five seconds, because both directions are wrong. Shorter starts abandoning a keychain that was
// merely slow — a cold `securityd`, a loaded machine — and a credential kept in two places is worse
// than one kept in the slower place. Longer is a wait a person reads as a hang. The call it bounds
// takes milliseconds when the keychain is willing.
const keychainDeadline = 5 * time.Second

// errKeychainSilent says the keychain did not answer, and says what to do about it.
//
// **Returned only when falling back would otherwise be a lie.** A call that timed out on the way
// *in* can be retried against the file and the answer reported; a call that timed out while
// *reading* cannot — "you are not signed in" would be a claim about a keychain nobody heard from.
// So the fallback is tried first, and this is what is left when it has nothing either.
var errKeychainSilent = errors.New(
	"系统钥匙串没有回应：它可能锁着，而解锁需要在机器前有人。凭据仍在钥匙串里——解锁后再运行即可")

// responsiveKeychain is the keychain with a deadline, and a file to move to when it runs out.
//
// **Availability and responsiveness are different questions, and only the second one can be
// probed.** On macOS the keychain is always *available*: `/usr/bin/security` is right there. What
// it is not always, is *willing* — a write to a locked keychain raises an authorization request
// that only a person at the machine can answer, and `security` waits for that answer forever. Seen
// on 2026-10-04: `login` sat inside `keyring.Set` for as long as it was left alone, writing
// nothing, reporting nothing, and hanging an agent that called it.
//
// Hence a deadline rather than a probe, and applied to the real call rather than to a stand-in for
// it: a cheap read says nothing about whether a write will be answered, because it is the write
// that asks for permission. The first call that runs out of time moves this run to the file store
// — which is ADR 0025's fallback, reached on a different trigger than the Linux one — and every
// later call in the same process goes straight there rather than waiting again.
//
// Two costs, stated rather than discovered later:
//
//   - **An abandoned call is not cancelled.** The library shells out to `security` and returns
//     nothing that could be used to stop it, so a timed-out call leaves that process behind until
//     the OS reaps it. Observed on 2026-10-04, twice: the abandoned `security` does **not** land the
//     write it was asked for (`security find-generic-password -s skillmaster-cli` still says "could
//     not be found" afterwards), which is what makes the fallback safe rather than a race — a
//     credential that appeared in the keychain minutes later would be a second copy nobody decided
//     on.
//   - **A delete that times out leaves the keychain entry behind.** It is a dead token by then
//     (`logout` revokes before it deletes, and that is the part that matters), so this is untidiness
//     rather than exposure — but it is untidiness the next run will not clean up either.
//
// **Two copies are possible, and only one of them may be used.** A credential goes to the file when
// a write to the keychain runs out of time, and the keychain may still hold one from before it
// locked — see newerCredential, which is what decides between them and why the newer one is the
// live one. Getting that wrong is not untidiness either: the copy that is behind holds a refresh
// token the server has already rotated, and presenting a spent refresh token is a *replay*, which
// ADR 0024 answers by revoking the whole chain — the person is signed out for no reason they can
// see.
type responsiveKeychain struct {
	keychain Store
	fallback *fileStore
	deadline time.Duration

	// switched is the whole state: once a call has run out of time, this run is a file run. Reset
	// every process, deliberately — an unlocked keychain should be used again next time without
	// anybody having to clear a marker.
	switched bool
	reason   string
}

func newResponsiveKeychain(keychain Store, fallback *fileStore) *responsiveKeychain {
	return &responsiveKeychain{keychain: keychain, fallback: fallback, deadline: keychainDeadline}
}

// Where says where the credential is, and — after a timeout — why it is not where it usually is.
func (s *responsiveKeychain) Where() string {
	if !s.switched {
		return s.keychain.Where()
	}
	return fmt.Sprintf("%s（%s）", s.fallback.Where(), s.reason)
}

func (s *responsiveKeychain) Load() (Credentials, bool, error) {
	if s.switched {
		return s.fallback.Load()
	}

	// Buffered, so the goroutine cannot be left blocked on a send nobody will receive.
	done := make(chan loadResult, 1)
	go func() {
		creds, found, err := s.keychain.Load()
		done <- loadResult{creds, found, err}
	}()

	select {
	case result := <-done:
		if result.err != nil {
			return result.creds, result.found, result.err
		}
		if !result.found {
			// **The keychain answering "nothing here" is not the end of the question.** A read of an
			// item that does not exist needs no unlock, so it comes back instantly even while the
			// keychain is too locked to be written to — which is exactly the state a timed-out
			// `login` leaves behind: a credential in the file, and a keychain that answers this one
			// question fast. Without this line that credential is invisible and the next command
			// says "not signed in" while the file sits there.
			return s.fallback.Load()
		}
		return s.newerCredential(result.creds)
	case <-time.After(s.deadline):
		s.switchToFile("读超时")
		// The file is tried before giving up, because a credential put there by an earlier
		// timed-out run is a real credential and the person is signed in either way.
		creds, found, err := s.fallback.Load()
		if err != nil || found {
			return creds, found, err
		}
		return Credentials{}, false, errKeychainSilent
	}
}

// newerCredential picks between the two copies, when there are two.
//
// **The later write is the live one, and `ExpiresAt` is what orders them**: every login and every
// refresh mints a fresh access token good for the same fixed hour, so the copy written later always
// has the later expiry. Comparing them costs one field read and is the whole of the rule.
//
// The loser is not deleted here. Deleting on a read path is a write nobody asked for, and it is not
// needed: the keychain copy is overwritten by the next write that succeeds, and the file copy is
// removed by that same write (see Save). What matters is that the *stale* copy is never the one
// served, because serving it means presenting a refresh token the server has already rotated.
func (s *responsiveKeychain) newerCredential(fromKeychain Credentials) (Credentials, bool, error) {
	inFile, found, err := s.fallback.Load()
	if err != nil || !found {
		// An unreadable file is not a reason to refuse to be signed in: the keychain answered, and it
		// is the copy the person would expect. Reported by the fallback's own Load if it matters.
		return fromKeychain, true, nil
	}
	if inFile.ExpiresAt.After(fromKeychain.ExpiresAt) {
		return inFile, true, nil
	}
	return fromKeychain, true, nil
}

func (s *responsiveKeychain) Save(creds Credentials) error {
	if s.switched {
		return s.fallback.Save(creds)
	}

	done := make(chan error, 1)
	go func() { done <- s.keychain.Save(creds) }()

	select {
	case err := <-done:
		if err != nil {
			return err
		}
		// The keychain has taken the current credential, so any copy in the file is now the older
		// one — and leaving it there is how the two-copies problem outlives the run that caused it
		// (see newerCredential). Absent already is success.
		return s.fallback.Delete()
	case <-time.After(s.deadline):
		s.switchToFile("写超时")
		return s.fallback.Save(creds)
	}
}

func (s *responsiveKeychain) Delete() error {
	if s.switched {
		return s.fallback.Delete()
	}

	done := make(chan error, 1)
	go func() { done <- s.keychain.Delete() }()

	select {
	case err := <-done:
		if err != nil {
			return err
		}
		// Both places, so that a logout cannot clear one copy and leave the other. This is the shape
		// the whole command exists to avoid.
		return s.fallback.Delete()
	case <-time.After(s.deadline):
		s.switchToFile("删除超时")
		// nil even if the file had nothing: the deletion this command exists for — the server-side
		// revocation — has already happened, and what may be left is a dead token in the keychain
		// (see the type's comment). Reporting a failure here would say the logout did not happen.
		return s.fallback.Delete()
	}
}

func (s *responsiveKeychain) switchToFile(reason string) {
	s.switched = true
	s.reason = fmt.Sprintf("系统钥匙串%s，本次改用这里", reason)
}

type loadResult struct {
	creds Credentials
	found bool
	err   error
}

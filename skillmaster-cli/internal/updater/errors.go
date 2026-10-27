package updater

// VerifyError is a download that arrived and did not check out.
//
// **Its own type because of what the person is told.** A transfer that failed — no network, a
// timeout, a 503 — is the ordinary weather of an update and stays silent; bytes that arrived and
// did not match their checksum are a broken or tampered release, and staying silent about that
// means a mirror can serve rubbish for ever without anyone hearing about it (v25 acceptance 3).
type VerifyError struct{ Err error }

func (e *VerifyError) Error() string { return e.Err.Error() }

func (e *VerifyError) Unwrap() error { return e.Err }

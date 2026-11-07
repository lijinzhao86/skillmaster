//go:build darwin

//go:debug x509usefallbackroots=1

// This directive must live in a darwin-only file, and the reason is scope rather than taste.
//
// **It is process-wide and replaces the trust store, not just the verifier.** Go honours it on every
// platform, so leaving it in the shared file made a macOS sandbox fix into "the CLI ignores the
// system trust store everywhere" — and on a managed Linux laptop whose enterprise CA lives in
// /usr/local/share/ca-certificates that is a CLI that can no longer reach anything.
//
// macOS is the only platform that needs it: there the platform verifier is a separate daemon behind
// Mach IPC, and the sandbox blocks the call. Linux and Windows use their own trust store — read from
// the files, or through the platform verifier — and keep using it, so an enterprise CA goes where it
// always went. The pool in trustroots.go is still built and registered everywhere: it is the fallback
// when a system store cannot be loaded (a minimal container), and on macOS it is the one that is
// forced.
package main

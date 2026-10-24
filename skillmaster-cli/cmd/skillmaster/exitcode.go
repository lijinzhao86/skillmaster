package main

import (
	"errors"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// Exit codes are a public contract (ADR 0087): 0 success, 1 ordinary failure, 2 usage, 4 credential
// unavailable with no point in retrying. This file is the single place that decides which.
//
// `reportError` asks this file for the status before it decides how to print, so a `--json` failure
// gets the same code as a text one.

// refusalError marks a command that exists only to say where an action lives. It is not a usage
// error: the command was written correctly, and the action is simply not the CLI's to take.
type refusalError struct{ message string }

func (e *refusalError) Error() string { return e.message }

// credentialError marks a failure whose next step is to replace the credential: a --no-login command
// with nothing stored, or a SKILLMASTER_API_KEY that is not a key. It is deliberately not used for a
// credential that exists but cannot be read, nor for a login that failed — both are status 1,
// because both can be retried as they are.
type credentialError struct{ message string }

func (e *credentialError) Error() string { return e.message }

// credentialUnavailable reports whether err means "replace the credential".
func credentialUnavailable(err error) bool {
	var credential *credentialError
	return errors.As(err, &credential)
}

// isRefusedCredential reports whether a server refusal's own code is one of the four 401s that mean
// the credential itself is the problem. **The raw code is what counts**: an empty code means the
// refusal came from in front of the application, and it stays status 1.
func isRefusedCredential(code string) bool {
	switch code {
	case "unauthenticated", "token_idle", "token_expired", "token_revoked":
		return true
	}
	return false
}

// classifyExit fills the status on a failure that has been flattened into a cliFailure. It reads the
// raw server code, not the normalised Code: classifyFailure turns an empty refusal code into
// "unauthenticated", and judging by that would report 4 for a 401 that carries no code at all.
func classifyExit(failure *cliFailure) {
	switch {
	case failure.Code == "usage":
		failure.Exit = 2
	case isRefusedCredential(failure.ServerCode):
		failure.Exit = 4
	}
}

// failureExit is the status an already-classified failure carries. Zero means nothing set it, which
// is an ordinary failure.
func failureExit(failure *cliFailure) int {
	if failure.Exit != 0 {
		return failure.Exit
	}
	return 1
}

// refusalCode returns the server's own code for a 401, or the empty string when it named none.
func refusalCode(err error) string {
	var refusal *api.Refusal
	if errors.As(err, &refusal) {
		return refusal.Code
	}
	return ""
}

package main

import (
	"context"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

// **Asking about a command never requires a credential.** Help is answered before dispatch
// (v16 做 #1): no store is read, no server is contacted, no argument is validated. This walks every
// path the binary declares — including the groups, whose bare form is a usage error — with nothing
// on the machine and a server address that refuses instantly.
//
// The shape matters as much as the status: help goes to stdout and stderr stays empty, so a caller
// pipe-parsing the output is not handed a sentence about credentials mixed in.
func TestHelpNeedsNoCredential(t *testing.T) {
	t.Setenv(config.ServerEnv, "http://127.0.0.1:1")
	t.Setenv("HOME", t.TempDir())
	// The empty string is how this CLI spells "not set": config treats it as absent.
	t.Setenv("SKILLMASTER_API_KEY", "")

	cases := [][]string{{"--help"}, {"-h"}}
	for _, spec := range commandHelp.Specs() {
		if spec.Path == "" {
			continue
		}
		words := strings.Split(spec.Path, " ")
		cases = append(cases, append(append([]string{}, words...), "--help"))
		cases = append(cases, append(append([]string{}, words...), "-h"))
	}
	cases = append(cases, []string{"help"}, []string{"help", "env"})

	for _, args := range cases {
		var err error
		var stderr string
		stdout := captureStdout(t, func() {
			stderr = captureStderr(t, func() { err = run(context.Background(), args) })
		})
		if err != nil {
			t.Errorf("%v: %v — help must not need a credential", args, err)
			continue
		}
		if strings.TrimSpace(stdout) == "" {
			t.Errorf("%v printed no help to stdout", args)
		}
		if stderr != "" {
			t.Errorf("%v wrote to stderr: %q", args, stderr)
		}
	}
}

// The same walk with an API key that is not a key: the environment check happens when a client is
// built, which is after the help answer, so `--help` still works on a machine whose variable is
// wrong. This is the case a person hits while debugging — they mistype the variable and ask for
// help, which is exactly when a "not a key" error would be least useful.
func TestHelpNeedsNoUsableKey(t *testing.T) {
	t.Setenv(config.ServerEnv, "http://127.0.0.1:1")
	t.Setenv("HOME", t.TempDir())
	t.Setenv("SKILLMASTER_API_KEY", "not-a-key")

	for _, args := range [][]string{{"skill", "files", "--help"}, {"skills", "list", "--help"}, {"--help"}} {
		var err error
		stdout := captureStdout(t, func() { err = run(context.Background(), args) })
		if err != nil {
			t.Errorf("%v: %v", args, err)
			continue
		}
		if strings.TrimSpace(stdout) == "" {
			t.Errorf("%v printed no help", args)
		}
	}
}

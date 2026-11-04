// Command skmr is the CLI: it holds the credential, loads skills on demand, and submits them
// for approval.
//
// §4.6's command set is here except for rollback, which nothing needs yet. Everything else — login,
// logout, startup, the `skill` group's verbs for one skill (invoke, files, read, versions, submit,
// share, create, shares, unshare, request, open) and the `skills` group's verbs for the collection
// (list, search) — is in this package plus the packages under internal/. The command groups live
// in one file each; this file is the entry point and the top-level dispatch.
//
// **The CLI's writing is submission and draft management; it cannot publish** (ADR 0031). Since v6
// `skill submit` carries the version's name and note, and the `version` group may rename, annotate
// and discard a draft. Publishing moves what every agent reading the API will get, and it is
// deliberately reachable only from the browser, by a person, with the page a command opens. The
// CLI's half of that handover is the deep link it prints and opens; it carries no credential of any
// kind, so a link copied out of a terminal is not a way in.
package main

import (
	"context"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/apikey"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/updater"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/version"
	"os"
	"strconv"
	"strings"
)

func main() {
	if err := run(context.Background(), os.Args[1:]); err != nil {
		os.Exit(reportError(err))
	}
}

func run(ctx context.Context, args []string) (err error) {
	// **The helper branch comes first.** A staged binary started to replace the one at `target` is
	// this program with one environment variable set; it takes no arguments, prints nothing, and
	// must not fall through to the command table or to an update of its own (ADR 0103).
	if updater.IsHelper() {
		return updater.RunHelper(os.Getenv(updater.TargetEnv))
	}
	ensureLegacyEntry()
	if len(args) == 0 {
		writeCommandTable()
		return usagef("没有给出子命令。用法见：skmr help")
	}
	// **Answered before anything else looks at the arguments.** It is a question about the binary,
	// not about a command: it must work with no server reachable, no credential, and no parsing
	// (v17 acceptance 1). The second half is the API generation — the major number and this are the
	// same fact said twice (ADR 0088).
	if len(args) == 1 && args[0] == "--version" {
		// **One name, whichever entry point was typed** (ADR 0113): the legacy name is a way back,
		// not a second identity, so `--version` answers the same from both.
		fmt.Printf("%s %s (API %s)\n", config.Name, version.String(), api.Generation)
		return nil
	}
	if handled, err := helpRequest(args); handled {
		return err
	}
	// The command's own address is settled once, here: a parser deep inside has no way to know which
	// verb reached it (`--offset` serves both `read` and `invoke`), and every usage error has to end
	// at the help of the command that was actually typed (PRD 做 #4).
	defer func() { err = retargetUsage(err, args[0]) }()

	// **Two things happen before the command and one after it.** The check is bounded (1.5 s) and
	// throttled to once a day; the notice is a local comparison; the finish waits at most 10 s for a
	// download that is already under way. None of them can change what the command prints or
	// returns (v25).
	finishUpdate := autoUpdate(ctx, args)
	defer finishUpdate()

	switch args[0] {
	case "login":
		return login(ctx, args[1:])
	case "auth":
		return authCmd(ctx, args[1:])
	case "logout":
		return logout(args[1:])
	case "startup":
		return startup(ctx, args[1:])
	case "update":
		return updateCmd(ctx, args[1:])
	case "prompt":
		return promptVerb(ctx, args[1:])
	case "skills":
		return skillsVerb(ctx, args[1:])
	case "skill":
		return skillVerb(ctx, args[1:])
	case "version":
		return versionVerb(ctx, args[1:])
	case "draft":
		return draftVerb(ctx, args[1:])
	default:
		return unknownName("", args[0], childNames(""))
	}
}

// namedArgument is how a refusal points at an argument it will not repeat.
//
// **The argument that reaches one of these defaults is a credential about as often as it is a typo,
// and a refusal is the wrong place to find out which.** `--api-key=skm_…` typed one level too high,
// a key pasted where a subcommand goes: quoting it back puts the secret in the terminal and in
// whatever collects a CI job's output — one more place it lives, in the message that was supposed to
// protect it. What is left to name is the part the person has to change anyway: the flag, or the
// fact that what they handed over is a credential and where it goes instead.
//
// **The test is the server's own routing rule** (`apikey.IsKey`, the same prefix the API uses to
// decide a bearer token is a key), so this cannot drift from what a key is. An ordinary typo — by
// far the commonest way here — is quoted as before: it is the word to fix, and hiding it would make
// the message useless.
// flagValue reads the value that follows a flag, and refuses a token that is itself a flag.
//
// **Without the check the typo becomes data**: `--name --text` would rename a definition to
// "--text", and `--category --my-tag foo` would send a category named "--my-tag". Every v14 flag
// whose value is free text goes through here.
func flagValue(args []string, i int, flag, path string) (string, error) {
	if i+1 >= len(args) {
		return "", usagef("%s 后面要跟一个值。%s", flag, usageAt(path))
	}
	// Only a long flag (`--name`) is refused. A single dash stays a legal start for free text —
	// a description may begin with "- 交付期内" — while the typo that motivated the check
	// (`--name --text`) is still caught.
	if strings.HasPrefix(args[i+1], "--") && len(args[i+1]) > 2 {
		return "", usagef("%s 后面要跟一个值，但下一个参数看起来是旗标（%s）。%s",
			flag, namedArgument(args[i+1]), usageAt(path))
	}
	return args[i+1], nil
}

func namedArgument(arg string) string {
	switch {
	case apikey.IsKey(arg):
		return apikey.InAnArgument
	case strings.HasPrefix(arg, "-"):
		// The flag name alone: whatever follows `=` may be the secret, and it is not repeated.
		return strconv.Quote(strings.SplitN(arg, "=", 2)[0])
	default:
		return strconv.Quote(arg)
	}
}

// credentialSource names where the credential a command just used came from, for a refusal that has
// to say which of two possible credentials it is about.
func credentialSource() string {
	store, _ := openStore(config.Server())
	return store.Source()
}

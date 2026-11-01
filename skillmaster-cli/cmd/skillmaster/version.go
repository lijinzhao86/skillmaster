// The `version` group: the author plane an agent may read and manage without a browser.
//
// **What is here is what does not change what a caller can read.** Listing, reading, comparing,
// renaming, annotating and discarding a draft all leave the consumer plane alone. Publishing,
// rolling back, revoking and restoring stay page actions: since v37 they are commands that open the
// version's page and check the credential first (ADR 0126, 0127). Taking a whole skill offline lives
// under `skill` since the same version, where the action's owner is.
package main

import (
	"context"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/apikey"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/auth"
)

// openBrowser is a seam, for the same reason `startLogin` is one: a test must not open a window on
// whoever's screen is in front of the machine.
var openBrowser = auth.OpenBrowser

func versionVerb(ctx context.Context, args []string) (err error) {
	if len(args) == 0 {
		return usagef("%s", usageAt("version"))
	}
	// The parsers under this group are shared by several verbs, so they can name only the group.
	// A usage error is retargeted to the verb that was actually typed, once, on the way out.
	defer func() {
		if err != nil {
			err = retargetUsage(err, "version "+args[0])
		}
	}()
	switch args[0] {
	case "list":
		return versionList(ctx, args[1:])
	case "show":
		return versionShow(ctx, args[1:])
	case "diff":
		return versionDiff(ctx, args[1:])
	case "rename":
		return versionRename(ctx, args[1:])
	case "note":
		return versionNote(ctx, args[1:])
	case "release-type":
		return versionReleaseType(ctx, args[1:])
	case "discard":
		return versionDiscard(ctx, args[1:])
	case "open":
		return versionOpen(ctx, args[1:])
	case "publish":
		return versionPublish(ctx, args[1:])
	case "rollback", "revoke", "restore":
		// The four actions that live on a version page (ADR 0126). v37 opens the page and checks
		// the credential first; `offline`, `online` and `delete` are skill-level and live under
		// `skill` since the same version.
		return runPageAction(ctx, pageSpecs["version "+args[0]], args[1:])
	default:
		// A machine contract covers a mistyped verb too: `--json` gets one object on stderr, not a
		// usage line a script cannot parse.
		return versionErr(hasJSONFlag(args), unknownName("version", args[0], childNames("version")), "")
	}
}

// isBumpKind guards the three choices the publish form draws. The value is carried into the address,
// so anything else is refused here rather than silently producing a page with no preselection.
func isBumpKind(value string) bool {
	return value == "patch" || value == "minor" || value == "major"
}

// redactOr is `namedArgument` without the quoting: a value inside a sentence, not a token at the
// command line. A credential pasted as a flag value is replaced; anything else is shown as written.
func redactOr(value string) string {
	if apikey.IsKey(value) {
		return apikey.InAnArgument
	}
	return value
}

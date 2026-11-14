// The two sub-dispatchers: `skill` (one skill) and `skills` (the collection).

package main

import "context"

// skillVerb is the `skill` group: the commands that act on one skill.
//
// **The grammar is singular/plural, and it is the rule rather than a convention.** Every verb here
// takes one skill's address; `skillsVerb` below is the collection (list, search) and takes none.
// Before this split `list` lived here next to `ls`/`files`, and two commands one letter apart
// answered different questions — the confusion the split exists to end.
//
// **A group rather than top-level verbs**, because this CLI is going to grow nouns that are not
// skills — a namespace, a version, a grant — and a bare `list` did not say what it listed. The
// shape is the one `claude mcp list`, `claude plugin eval` and `gh pr list` use, and it is what both
// of those do for a noun that has several verbs under it. Neither of them has a `noun-verb` command
// at all: a hyphen there appears *inside* a verb name (`claude setup-token`), never as a prefix.
//
// **`skill`/`skills` and `version` are groups, and `login`/`logout`/`startup` deliberately stay flat.**
// What decides is which noun a command acts on: `login`/`logout` act only on this machine — a
// credential, a keychain entry — and `startup` starts there too (the skills directory, no credential)
// before loading the journey from the registry; none of them is a verb under `skill`. They are also
// not ambiguous the way a bare `list` was: there is one thing to log in to. The `version` group is the
// author-plane noun v6 added; it is top-level, not a verb under `skill`, because a version is an
// object in its own right and `skill version list` would read as a list of skills.
//
// **The two read commands are the host's two, and the split is the same one.** `invoke` is `Skill`:
// load the body, and it takes no path. `read` is `Read`: one file of it, by path. What `invoke` adds
// is that it resolves the version and writes it down, which is what lets `read` be given a bare
// address — see `invoke` for why that pin exists and why it lives here.
//
// **`files` is the one read command with no counterpart in the host** (ADR 0036): there, a skill is a
// directory and enumerating one is `ls`, and its skill load hands over a base directory rather than a
// listing. Neither of those exists here, so the enumeration is a command — see `files`.
func skillVerb(ctx context.Context, args []string) (err error) {
	if len(args) == 0 {
		return usagef("%s", usageAt("skill"))
	}
	// The command's own address is settled once, here: a parser deep inside has no way to know which
	// verb reached it (`--offset` serves both `read` and `invoke`), and every usage error has to end
	// at the help of the command that was actually typed (PRD 做 #4).
	defer func() { err = retargetUsage(err, "skill "+args[0]) }()
	// Retired before the switch, like the prompt verbs (ADR 0110 / ADR 0112): the name still answers,
	// and it is not a child of the group any more, so help and the suggestion list must not carry it.
	if args[0] == "files" {
		return retiredFiles()
	}
	if args[0] == "put" {
		return retiredPut()
	}
	if args[0] == "download" {
		return retiredDownload()
	}
	switch args[0] {
	case "invoke":
		return invoke(ctx, args[1:])
	case "ls":
		if err := rejectJSON("ls", args[1:]); err != nil {
			return err
		}
		return ls(ctx, args[1:])
	case "find":
		if err := rejectJSON("find", args[1:]); err != nil {
			return err
		}
		return find(ctx, args[1:])
	case "stat":
		if err := rejectJSON("stat", args[1:]); err != nil {
			return err
		}
		return stat(ctx, args[1:])
	case "head":
		if err := rejectJSON("head", args[1:]); err != nil {
			return err
		}
		return headTail(ctx, args[1:], false)
	case "tail":
		if err := rejectJSON("tail", args[1:]); err != nil {
			return err
		}
		return headTail(ctx, args[1:], true)
	case "cat":
		if err := rejectJSON("cat", args[1:]); err != nil {
			return err
		}
		return cat(ctx, args[1:])
	case "wc":
		if err := rejectJSON("wc", args[1:]); err != nil {
			return err
		}
		return wc(ctx, args[1:])
	case "grep":
		if err := rejectJSON("grep", args[1:]); err != nil {
			return err
		}
		return grep(ctx, args[1:])
	case "check":
		if err := rejectJSON("check", args[1:]); err != nil {
			return err
		}
		return checkVersion(ctx, args[1:])
	case "write":
		return write(ctx, args[1:])
	case "get":
		if err := rejectJSON("get", args[1:]); err != nil {
			return err
		}
		return get(ctx, args[1:])
	case "edit":
		return edit(ctx, args[1:])
	case "rm":
		if err := rejectJSON("rm", args[1:]); err != nil {
			return err
		}
		return rm(ctx, args[1:])
	case "mv":
		return moveOrCopy(ctx, args[1:], "mv")
	case "cp":
		return copyFiles(ctx, args[1:])
	case "read":
		if err := rejectJSON("read", args[1:]); err != nil {
			return err
		}
		return read(ctx, args[1:])
	case "versions":
		if err := rejectJSON("versions", args[1:]); err != nil {
			return err
		}
		return versions(ctx, args[1:])
	case "submit":
		return submitSkill(ctx, args[1:])
	case "share":
		return shareSkill(ctx, args[1:])
	case "create":
		return createSkill(ctx, args[1:])
	case "copy":
		return skillCopy(ctx, args[1:])
	case "role":
		return changeGrantRole(ctx, args[1:])
	case "shares":
		return shares(ctx, args[1:])
	case "unshare":
		return unshare(ctx, args[1:])
	case "request":
		return requestAccess(ctx, args[1:])
	case "open":
		return openSkill(ctx, args[1:])
	case "show":
		if err := rejectJSON("show", args[1:]); err != nil {
			return err
		}
		return showSkill(ctx, args[1:])
	case "license":
		return showLicense(ctx, args[1:])
	case "tags":
		if err := rejectJSON("tags", args[1:]); err != nil {
			return err
		}
		return skillTags(ctx, args[1:])
	case "tag":
		if err := rejectJSON("tag", args[1:]); err != nil {
			return err
		}
		return tagVerb(ctx, args[1:])
	case "category":
		if err := rejectJSON("category", args[1:]); err != nil {
			return err
		}
		return categoryVerb(ctx, args[1:])
	case "my-when-to-use":
		if err := rejectJSON("my-when-to-use", args[1:]); err != nil {
			return err
		}
		return myWhenToUseVerb(ctx, args[1:])
	case "join":
		if err := rejectJSON("join", args[1:]); err != nil {
			return err
		}
		return joinLibrary(ctx, args[1:], true)
	case "leave":
		if err := rejectJSON("leave", args[1:]); err != nil {
			return err
		}
		return joinLibrary(ctx, args[1:], false)
	case "restore":
		if err := rejectJSON("restore", args[1:]); err != nil {
			return err
		}
		return restoreSkill(ctx, args[1:])
	case "delete":
		return skillDelete(ctx, args[1:])
	case "offline", "online", "visibility":
		return runPageAction(ctx, pageSpecs["skill "+args[0]], args[1:])
	default:
		return unknownName("skill", args[0], childNames("skill"))
	}
}

// skillsVerb is the `skills` group: the commands that act on the collection.
//
// **It takes no address, and that is the half of the grammar `skillVerb` does not cover.** A verb
// that answers "which skills" belongs here; a verb that acts on one belongs there. The old
// `skill list` / `skill search` spellings are unknown commands, not aliases: they never shipped, so
// there is nothing to keep working (ADR 0077).
func skillsVerb(ctx context.Context, args []string) (err error) {
	if len(args) == 0 {
		return usagef("%s", usageAt("skills"))
	}
	// The command's own address is settled once, here: a parser deep inside has no way to know which
	// verb reached it (`--offset` serves both `read` and `invoke`), and every usage error has to end
	// at the help of the command that was actually typed (PRD 做 #4).
	defer func() { err = retargetUsage(err, "skills "+args[0]) }()
	switch args[0] {
	case "list":
		if err := rejectJSON("list", args[1:]); err != nil {
			return err
		}
		return listSkills(ctx, args[1:])
	case "publish":
		return skillsPublish(ctx, args[1:])
	case "pending":
		// The console's 待上线 page in the terminal (v18). A read that answers a question no other
		// command does: `skills list --show draft` means "never published", and the queue's most
		// common row is a skill that is live and has a new draft.
		return listPending(ctx, args[1:])
	case "search":
		if err := rejectJSON("search", args[1:]); err != nil {
			return err
		}
		return search(ctx, args[1:])
	case "categories":
		if err := rejectJSON("categories", args[1:]); err != nil {
			return err
		}
		return categoriesCmd(ctx, args[1:])
	case "labels":
		if err := rejectJSON("labels", args[1:]); err != nil {
			return err
		}
		return labelsCmd(ctx, args[1:])
	case "public-tags":
		if err := rejectJSON("public-tags", args[1:]); err != nil {
			return err
		}
		return publicTagsCmd(ctx, args[1:])
	case "my-tags":
		if err := rejectJSON("my-tags", args[1:]); err != nil {
			return err
		}
		return myTagsCmd(ctx, args[1:])
	default:
		return unknownName("skills", args[0], childNames("skills"))
	}
}

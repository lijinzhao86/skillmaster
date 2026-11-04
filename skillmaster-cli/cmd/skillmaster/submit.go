// Submitting a skill or a new version.

package main

import (
	"context"
	"errors"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// submitSkill uploads one skill of this machine's, and hands the person the page that can approve it.
//
// The two halves of ADR 0031 meet here. This command makes nothing live — the version lands as a
// draft and the consumption plane does not change — so what it does at the end is open the browser at
// the address where that draft can be looked at and published. Without that step the command would be
// a success that left the work invisible, which is what it was before the split.
//
// **That last step happens only when there is something there to approve.** A submission of content
// the server already has answers with whichever version holds it, and that version may be live or
// discarded — in neither case is there an approval to make, so the address is printed and no tab is
// opened. See stateLine and the branch below it.
//
// **`--to` names somebody else's skill, and it is the `editor` grant's one write path** (ADR 0034).
// It routes to the endpoint that adds a version to an existing skill rather than to the one that
// creates a skill, and what keeps naming a target from being a cross-namespace write primitive is
// the server's rule: that route can only add to a skill the caller has been given write access to.
// The one thing it does not change is publishing — an editor can draft and cannot publish, so the
// draft line below says who can.
func submitSkill(ctx context.Context, args []string) error {
	dirArgs, target, name, note, release, newOne, err := splitSubmitArgs(args)
	if err != nil {
		return err
	}
	dir, err := resolveSkill(dirArgs)
	if err != nil {
		return err
	}

	// Archived first, so a missing SKILL.md or a symlink is a message rather than an upload that
	// fails at the far end. The server checks all of it too and is the authority (ADR 0011); this
	// only saves the round trip. Also what makes the retry below cheap — the archive is built once,
	// not once per attempt.
	archive, err := api.Archive(dir)
	if err != nil {
		return err
	}

	// **`version:` is still legal content and no longer a release name.** v6 assigns the number at
	// publish time, so the field is carried through untouched; saying so once keeps an author from
	// believing the file decides the address. It never blocks the submission.
	if content, readErr := os.ReadFile(filepath.Join(dir, "SKILL.md")); readErr == nil {
		if declared, ok := frontmatterScalar(content, "version"); ok {
			fmt.Printf("（SKILL.md 里的 version: %s 不参与发布命名；发布时在网页上再定版本号。）\n", declared)
		}
	}

	// **One open draft at a time, unless the user says otherwise.** The check is the CLI's, not the
	// server's: the web editor has its own create flow, and this guard is about agent loops rather
	// than about the model. `--new` is hidden and named only in the refusal (ADR 0135).
	guardClient, err := session(ctx)
	if err != nil {
		return err
	}
	me, err := guardClient.Me(ctx)
	if err != nil {
		return err
	}
	guardAddress := target
	if guardAddress == "" {
		guardName := filepath.Base(dir)
		if content, readErr := os.ReadFile(filepath.Join(dir, "SKILL.md")); readErr == nil {
			if declared, ok := frontmatterScalar(content, "name"); ok && declared != "" {
				guardName = declared
			}
		}
		guardAddress = me.User.Namespace + "/" + guardName
	}
	if err := guardOpenDraft(ctx, guardClient, guardAddress, me.User.Username,
		"skmr skill submit <目录> --name <名称> --new", newOne); err != nil {
		return err
	}

	// Retrying a submission is safe, and for a reason worth naming: a rejected token is refused at
	// the security filter, so the first attempt wrote nothing, and submitting the same bytes twice is
	// idempotent anyway (ADR 0005) — it produces no second version, draft or otherwise.
	result, err := authenticated(ctx, func(client api.Client) (api.SubmitResult, error) {
		if target == "" {
			return client.Submit(ctx, archive, name, note, release)
		}
		return client.SubmitVersion(ctx, target, archive, name, note, release)
	})
	if err != nil {
		// v33: a package refused for its file names is answered one line per file, with the fix.
		// Everything else keeps the v6 handling below.
		if refused, ok := uploadRefusal(err, submitDirSpelling(dirArgs)); ok {
			return errors.New(refused)
		}
		if generic := notANameRefusal(err); generic != nil {
			return generic
		}
		// The v6 field refusals (`draft_name_required` and friends) arrive as codes; the one map in
		// `versionMessages` is what turns them into the interface's sentences.
		return versionErr(false, err, "")
	}

	fmt.Printf("%s %s/%s@%s　%s\n", submitVerb(result), result.Namespace, result.Name,
		submitPin(result), result.Version.Digest)
	fmt.Printf("  %d 个文件　%d 字节\n", result.Version.FileCount, result.Version.TotalBytes)
	if line := droppedLine(result.Dropped); line != "" {
		fmt.Println(line)
	}
	if result.Version.DraftName != nil && *result.Version.DraftName != "" {
		fmt.Printf("  名称：%s\n", *result.Version.DraftName)
	}
	if result.Version.Note != nil && *result.Version.Note != "" {
		fmt.Printf("  说明：%s\n", *result.Version.Note)
	}

	// Said before the link, and said plainly, because this is the fact the whole split turns on: a
	// successful `submit` has changed nothing anybody can read yet — when what it left was a draft.
	state := result.Version.State
	fmt.Println(stateLine(state))
	// Said only on the `--to` path, and only about the draft case, because that is where the general
	// sentence above can be read as an instruction the caller cannot carry out: an `editor` grant
	// lets them draft and explicitly does not let them publish (ADR 0034). Worded as "the skill's
	// owner" rather than as "not you", because `--to` naming your own skill is legal and a sentence
	// that called you somebody else would be wrong there.
	if target != "" && state == api.StateDraft {
		fmt.Println("（这个 skill 的上线只能由它的主人在网页上点。）")
	}

	page := skillPageURL(result)
	// The label follows the branch the line above took: calling it an approval address when there
	// is nothing to approve would contradict the sentence printed directly above it.
	//
	// **The address, never a window.** `submit` always leaves a draft — publishing is a separate
	// act — so the draft branch is the only one that runs, and opening the page there meant a tab
	// on every call. That is bearable for one skill and wrong for the migration, where startup's
	// first step runs `submit` once per directory: importing 33 skills opened 33 tabs. The address
	// is printed either way, and `skmr skill open <address>` opens it on request.
	//
	// **The draft's own page, not the skill's** (2026-10-28): the thing a person has to do next
	// lives on that draft's actions tab, and the skill page it used to print still needed a tab and
	// a version picked by hand. `version open --sub actions` prints this same URL, so the two hints
	// a person sees now agree.
	if state != api.StateDraft {
		fmt.Printf("这一版在网页上（不含凭据）：\n%s\n", page)
		return nil
	}
	fmt.Printf("审批地址（不含凭据，在浏览器里用你自己的账号登录）：\n%s\n", draftApprovalURL(result))
	return nil
}

// submitVerb says what actually happened, which is three outcomes and not two.
//
// **`created` is not "the skill was created".** The server sets it when *this call created a
// version*, so it is true both for a brand-new skill and for a new version of an old one — reading
// it as the former prints "已创建" at somebody who just updated a skill that has existed for months.
// `skill_created` is what separates the two, and it is exactly the fact the version number used to
// carry: it is true only for the call that gave the skill its first version of any state (ADR 0033).
//
// The third outcome is a replay of identical content (ADR 0005's idempotence): nothing was written,
// and the version printed is the one that was already there. Any verb with 更新 in it would claim a
// change that did not happen, so this one says only that the content did not move — and what the
// version's state actually is comes from stateLine, printed on the line below.
func submitVerb(result api.SubmitResult) string {
	switch {
	case !result.Created:
		return "内容未变"
	case result.SkillCreated:
		return "已创建"
	default:
		return "已提交"
	}
}

// submitPin is the address suffix a submission's result should be printed with.
//
// **A draft has no version number in v6**, so the digest is not the right suffix for it any more:
// the stable author-plane identity is `draft:<id>`, and that is what a later `version show` or
// `version diff` takes. A replay of already-published content answers with no draft id and keeps the
// number-or-digest spelling the consumer plane uses.
func submitPin(result api.SubmitResult) string {
	// v7: a draft's address is its bare row id (ADR 0063). `draft:` is accepted on input for
	// compatibility but is never written back — the canonical spelling has no status in it.
	if result.Version.DraftID != nil && *result.Version.DraftID != "" {
		return *result.Version.DraftID
	}
	return api.VersionSuffix(result.Version.Name, result.Version.Digest)
}

// stateLine says what the version this call names actually is, which is not always a fresh draft.
//
// **`created` cannot answer this.** Submitting content the server already holds answers 200 with the
// row that holds that digest, and the unique constraint is on the content rather than on the state —
// so that row may be a draft, may already be published, or may have been discarded. Telling somebody
// to go and publish a version that is already live sends them to a page with nothing to do, and the
// discarded case is worse: the server refuses it, so the instruction can never be carried out.
//
// The default is for a state from a newer server than this build. Saying nothing about publishing is
// the honest answer there; the address and digest above still name the version.
func stateLine(state string) string {
	switch state {
	case api.StateDraft:
		return "这一版还是草稿，线上没有任何变化。要生效得去网页上点「上线」。"
	case api.StatePublished:
		return "这一版已经在线上，没有需要审批的东西。"
	case api.StateRevoked:
		return "这一版已被下架，消费面不再提供它；作者面仍可由维护者读到。"
	case api.StateDiscarded:
		return "这一版曾被丢弃，不会被上线；提交相同内容也不会把它变回草稿。"
	default:
		return "这一版已提交。"
	}
}

// skillPageURL is where a person approves what was just submitted.
//
// Built from `WebURL` rather than from the API base: in a deployment they are one origin, and in
// development they are not — the pages are Vite's on 5173 and the API is on 8080 — so a link built
// from the server would open a 404 that reads as a failed submission.
//
// **Both segments are escaped, and nothing else is.** A skill's name and namespace may be non-ASCII
// (M5 permits either), and this string goes to a browser: a space or a `#` left raw would truncate
// the address. The `@version` suffix is deliberately not added — the page is the version list, and a
// person who wants a particular version is about to choose it there.
func skillPageURL(result api.SubmitResult) string {
	return fmt.Sprintf("%s/skills/%s/%s", config.WebURL(),
		url.PathEscape(result.Namespace), url.PathEscape(result.Name))
}

// draftApprovalURL is the draft's own page: the skill's versions tab, pinned at this draft, on its
// actions sub-tab. It is the same URL `version open <address>@<pin> --sub actions` prints, so the
// two hints a person sees agree (2026-10-28). The skill page alone still needs a tab and a version
// picked by hand, which is not what "审批地址" promises.
func draftApprovalURL(result api.SubmitResult) string {
	return versionWebURL(result.Namespace+"/"+result.Name, submitPin(result), "actions", "")
}

// splitSubmitArgs separates the flags from the argument that names what to upload.
//
// The flags come out first so that `resolveSkill` keeps its one job and its one argument. Its reading
// of that argument is deliberately by the argument's own characters and never by asking the
// filesystem, and a flag left in the list would be read as a skill named `--name`.
//
// **`--name` is optional since v9.** A v9 download writes the version's name into the package
// (ADR 0069), and the server uses it when the request does not give one; a package without it,
// submitted without the flag, is refused with `draft_name_required` — the server's answer, quoted
// rather than pre-empted, because only the server can read the package.
//
// The address is checked here rather than left to the call that uses it, so a malformed one is a
// message before an archive of the whole directory is built and uploaded. `api.SkillPath` is the
// check because it is the one spelling of the address grammar this program has.
// A version pin is refused for the same reason `share` refuses one: the version is what this call
// produces, so a target that named one would be naming something this call does not use.
func splitSubmitArgs(args []string) (dirArgs []string, target, name, note, release string,
	newOne bool, err error) {
	var named, noted, released bool

	for i := 0; i < len(args); i++ {
		// `--new` is hidden: it is not a help flag (declarations_skill.go keeps it out), and the
		// refusal is the only place it is named. It still parses here so that a user who has been
		// asked can answer.
		if args[i] == "--new" {
			newOne = true
			continue
		}
		switch args[i] {
		case "--to", "--name", "--note", "--release":
		default:
			// A token that starts like a flag is a flag somebody mistyped, not a directory: read as
			// one it would become `读取 --name` or, worse, upload whatever that path happens to be.
			if strings.HasPrefix(args[i], "-") && len(args[i]) > 1 {
				return nil, "", "", "", "", false, unknownFlag("skill submit", args[i])
			}
			dirArgs = append(dirArgs, args[i])
			continue
		}
		if i+1 >= len(args) {
			return nil, "", "", "", "", false, usagef("%s 后面要跟一个值。%s", args[i], usageAt("skill submit"))
		}
		value := args[i+1]
		switch args[i] {
		case "--to":
			if target != "" {
				return nil, "", "", "", "", false, usagef("--to 只能给一次。%s", usageAt("skill submit"))
			}
			target = value
			if _, pathErr := api.SkillPath(target); pathErr != nil {
				return nil, "", "", "", "", false, usagef("%s", pathErr)
			}
			if strings.Contains(target, "@") {
				return nil, "", "", "", "", false, usagef("--to 指的是一个 skill，不是某一版——地址后面不要带 @版本。%s", usageAt("skill submit"))
			}
		case "--name":
			if named {
				return nil, "", "", "", "", false, usagef("--name 只能给一次。%s", usageAt("skill submit"))
			}
			named = true
			name = value
		case "--note":
			if noted {
				return nil, "", "", "", "", false, usagef("--note 只能给一次。%s", usageAt("skill submit"))
			}
			noted = true
			note = value
		case "--release":
			if released {
				return nil, "", "", "", "", false, usagef("--release 只能给一次。%s", usageAt("skill submit"))
			}
			released = true
			switch value {
			case "major", "minor", "patch":
				release = value
			default:
				return nil, "", "", "", "", false, usagef("--release 只接 major / minor / patch。%s", usageAt("skill submit"))
			}
		}
		i++
	}

	return dirArgs, target, name, note, release, newOne, nil
}

// resolveSkill turns the command's arguments into a directory to upload.
//
// Three shapes, and the point of them is that none requires the person to know where their skills
// live: a **bare name** is looked up under the skills directory, anything with a separator or a
// leading `.` or `~` is a path taken as given, and **no argument at all lists what is there** rather
// than guessing at one. Guessing would be wrong the moment somebody has two.
//
// The classification is by the argument's own characters and never by asking the filesystem, which
// is what makes the three cases deterministic: `submit pdf-tools` means the same thing whether or not
// a directory called `pdf-tools` happens to be in the current one.
func resolveSkill(args []string) (string, error) {
	if len(args) > 1 {
		return "", usagef("%s", usageAt("skill submit"))
	}
	if len(args) == 0 {
		return "", noArgumentGiven()
	}

	arg := args[0]
	// `submit ""` is what an unset shell variable looks like, and it is not a name: it would take the
	// bare-name branch, `filepath.Join(dir, "")` is `dir`, and the whole skills directory would go off
	// as one skill's archive. Refused here rather than left to the server's validator, which would
	// answer about a skill's *contents* after the upload had already happened.
	if arg == "" {
		return "", usagef("名字不能是空的。%s", usageAt("skill submit"))
	}
	if isPathLike(arg) {
		return expandHome(arg)
	}

	dir, err := config.SkillsDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, arg), nil
}

// noArgumentGiven says what could have been named instead, which is the whole value of the empty
// form: a person who does not remember the skill's directory name is who it is for.
func noArgumentGiven() error {
	dir, err := config.SkillsDir()
	if err != nil {
		return err
	}
	names, err := skillNamesIn(dir)
	if err != nil {
		return usagef("读 %s 失败：%v。%s", dir, err, usageAt("skill submit"))
	}
	if len(names) == 0 {
		return usagef("%s 里没有找到 skill。%s", dir, usageAt("skill submit"))
	}
	return usagef("%s 里有：%s。%s", dir, strings.Join(names, "、"), usageAt("skill submit"))
}

// skillNamesIn lists the directories under dir that hold a SKILL.md — which is what makes something
// a skill, and the same check the server's validator starts with.
//
// **A regular file, not merely something that resolves to one.** `os.Stat` follows a symlink, and
// `Archive` refuses one — so a directory whose `SKILL.md` is a link would be offered here and then
// fail on submit with 「是符号链接」, which reads as a broken skill rather than as a name this
// command should not have suggested.
//
// A directory that cannot be read is not an empty one, so it is reported: on most machines the
// skills directory is absent until something installs one, and "there are no skills" would send
// somebody looking for a mistake they did not make.
func skillNamesIn(dir string) ([]string, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	var names []string
	for _, entry := range entries {
		// `IsDir` is Lstat's answer, so a directory reached through a link is not listed — which is
		// now the same answer `Archive` gives, deliberately: offering it would name something that
		// cannot be submitted by that name.
		if !entry.IsDir() {
			continue
		}
		info, err := os.Lstat(filepath.Join(dir, entry.Name(), "SKILL.md"))
		if err != nil || !info.Mode().IsRegular() {
			continue
		}
		names = append(names, entry.Name())
	}
	// `os.ReadDir` already answers in filename order, so this is redundant against that promise —
	// kept because the order is what a person reads, and a caller that swapped the read for one that
	// does not sort should not be able to change the output.
	sort.Strings(names)
	return names, nil
}

// isPathLike reports whether the argument names a location rather than a skill.
//
// A separator anywhere, or a leading `.` or `~`. Nothing else — and the readings are still
// unambiguous, because the classification reads only the argument's own characters.
//
// **The cost is one narrow case, and it is worth stating rather than denying.** The server accepts a
// name that starts with `.` or `~` (it refuses whitespace, separators, `.`, `..`, `@`, `%` and `;` —
// nothing else), so a skill really named `.hidden` cannot be submitted by that bare word: the word
// reads as a path and is resolved against the current directory. It has to be given as a path
// (`submit ~/.claude/skills/.hidden`). The alternative — asking the filesystem which reading was
// meant — would make the same command mean different things on different machines.
func isPathLike(arg string) bool {
	return strings.ContainsRune(arg, '/') ||
		strings.ContainsRune(arg, filepath.Separator) ||
		strings.HasPrefix(arg, ".") ||
		strings.HasPrefix(arg, "~")
}

// expandHome resolves the leading `~`, which a shell would normally do before this sees it — but
// only when the argument arrives unquoted and unexpanded, and `submit ~/my-skills/pdf-tools` is
// exactly the shape somebody types.
//
// **Only a bare `~` or `~/…`.** `~bob/x` is a shell's own expansion and this program is not a shell;
// stripping the tilde from it would resolve to `$HOME/bob/x`, a path nobody named. Left alone it is
// a path that does not exist, which fails where it is used and says so.
func expandHome(path string) (string, error) {
	if path != "~" && !strings.HasPrefix(path, "~/") {
		return path, nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("找不到主目录（%s 要用它展开）：%w", path, err)
	}
	rest := strings.TrimPrefix(path, "~")
	rest = strings.TrimPrefix(rest, "/")
	return filepath.Join(home, rest), nil
}

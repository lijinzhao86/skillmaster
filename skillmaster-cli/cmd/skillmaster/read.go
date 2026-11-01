// Reading a file or a view of it: line ranges, views, and the author plane.

package main

import (
	"context"
	"errors"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// read prints one file of a skill, at the version `invoke` pinned.
//
// **This is the host's `Read`, and like it, it takes one path and returns the bytes** — with the same
// `--offset` for a long file, in the same unit. Which version those bytes come from is the one thing
// that cannot be the host's: locally a skill is a directory and its files are whatever is on disk,
// while here the same path means different bytes at different versions, so the version is decided and
// sent along (ADR 0035).
//
// A pin written into the address wins, and the remembered one is not consulted at all — sending both
// would ask the server to reconcile two sources, which it answers with a 400 (§4.2).
func read(ctx context.Context, args []string) error {
	offset, rest, err := parseOffset(args)
	if err != nil {
		return err
	}
	limit, raw, rest, err := parseReadViewFlags(rest)
	if err != nil {
		return err
	}
	if len(rest) != 2 {
		return usagef("%s", usageAt("skill read"))
	}
	address, relpath := rest[0], rest[1]
	if err := checkAddressSyntax("skill read", address); err != nil {
		return err
	}
	pin, note, err := pinFor(address)
	if err != nil {
		return err
	}
	if raw {
		return readRaw(ctx, address, relpath, offset, limit)
	}
	if limit == 0 {
		limit = maxContentLines
	}
	return readFile(ctx, address, bareAddress(address), relpath, pin, offset, limit, note)
}

// parseReadViewFlags pulls `--limit` and `--raw` out of a `read` argument list (v34).
//
// Both are views over the same bytes `read` already fetches, so neither changes which version is
// read: `--limit` chooses how much of a numbered rendering to show, `--raw` chooses not to render at
// all. A limit below 1 is a usage error — "zero lines" is a request whose answer nobody wants, and
// the typo it usually means (`--limit 0`) would otherwise print nothing and exit 0.
func parseReadViewFlags(args []string) (limit int, raw bool, rest []string, err error) {
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--raw":
			raw = true
		case "--limit":
			if i+1 >= len(args) {
				return 0, false, nil, usagef("--limit 后面要跟行数。%s", usageAt("skill read"))
			}
			count, convErr := strconv.Atoi(args[i+1])
			if convErr != nil || count < 1 {
				return 0, false, nil, usagef("--limit 要跟一个正整数。%s", usageAt("skill read"))
			}
			limit = count
			i++
		default:
			if strings.HasPrefix(args[i], "-") {
				return 0, false, nil, unknownFlag("skill read", args[i])
			}
			rest = append(rest, args[i])
		}
	}
	return limit, raw, rest, nil
}

// readRaw prints the stored bytes with nothing added (v34 A6): no numbers, no footer on stdout, and
// the version note on stderr so the output can be compared byte for byte with what the server holds.
func readRaw(ctx context.Context, address, relpath string, offset, limit int) error {
	fetched, err := contentFor(ctx, address, relpath)
	if err != nil {
		return err
	}
	if fetched.binary {
		return fmt.Errorf("%s 是二进制文件；要原始字节用 skmr skill cat %s %s。",
			relpath, address, relpath)
	}
	lines := splitLines(fetched.content)
	if offset < 1 {
		offset = 1
	}
	if len(lines) > 0 && offset > len(lines) {
		// **Nothing goes to stdout here.** `--raw` promises the stored bytes and nothing else, so
		// the note about an offset past the end is stderr's, like the version note below it.
		fmt.Fprintf(os.Stderr, "（这个文件只有 %d 行，没有第 %d 行。）\n", len(lines), offset)
		lines = nil
	}
	if offset > 0 && offset-1 < len(lines) {
		lines = lines[offset-1:]
	}
	if limit > 0 && len(lines) > limit {
		lines = lines[:limit]
	}
	for _, line := range lines {
		_, _ = os.Stdout.Write(line)
	}
	if fetched.which != "" {
		fmt.Fprintln(os.Stderr, fetched.which)
	}
	return nil
}

// pinFor decides which version a read is for, and what to say about that decision.
//
// Three answers, and the order between them is the design. A version named in the address wins and the
// store is not consulted at all — sending both would ask the server to reconcile two sources, which it
// answers with a 400 (§4.2). Otherwise it is what `invoke` remembered. And otherwise there is nothing,
// which is said out loud rather than passed over: the whole design rests on `invoke` having run, and a
// read that quietly used whatever is current would be exactly the drift this replaced — with nothing
// on screen to show it had happened.
//
// Split out of `read` because this is the part worth testing on its own: it touches a file and no
// network, so the decision can be pinned without a server or a credential.
func pinFor(address string) (pin, note string, err error) {
	key := bareAddress(address)
	version, named, err := versionInAddress(address)
	if err != nil {
		return "", "", err
	}
	if named {
		return version, "", nil
	}
	remembered, found, err := rememberedPin(key)
	if err != nil {
		return "", "", err
	}
	if !found {
		return "", fmt.Sprintf(
			"（%s 还没 invoke 过，按当前线上版本取。要先钉住就先 skmr skill invoke %s。）",
			key, key), nil
	}
	return remembered.Suffix(), "", nil
}

// versionInAddress is the version a caller wrote into the address, if they wrote one — and it refuses
// the one spelling that is neither: an `@` with nothing after it.
//
// **The one place that rule lives, because three things need it and they have to agree**: which
// version to send, whether the caller named one at all, and whether the address is malformed. The
// second is what tells two 404s apart — a version written in the command is a question the server
// answered "no", while a version this machine remembered is a local record that went stale — and each
// of those wants its own sentence and its own next step. The third is `invoke`'s to refuse as well as
// `read`'s, and a second copy of the parse is how the two would come to disagree.
func versionInAddress(address string) (version string, named bool, err error) {
	_, version, found := strings.Cut(address, "@")
	switch {
	case !found:
		return "", false, nil
	case version == "":
		// A bare `@` is not a version, and it is refused rather than read as "nothing named": the
		// quiet reading sends an address nobody meant and skips the line that says which version this
		// request is for — the one thing that must never be missing.
		return "", false, usagef("地址里的 @ 后面要跟一个版本名或 sha256:… 摘要。")
	}
	return version, true, nil
}

// versionGone is what a 404 says when the caller wrote the version into the command themselves.
//
// **It is deliberately not `pinFailure`, and the difference is the whole point of having two.** That
// one speaks about a record on this machine; here nothing local was consulted, so there is no record
// to blame. And its next step is `versions`, which lists what can be invoked — where `pinFailure`
// says to re-invoke, which for a caller whose explicit version just 404'd would resolve their problem
// by quietly handing them a different version, because a bare address means "whatever is current".
//
// **It names both things that may be missing, because the server's 404 does not.** One answer covers
// "no such skill", "not yours" and "no such version" (that is deliberate — §4.2), so a message that
// blamed only the version would send a caller with a typo'd skill name to a command that 404s too.
func versionGone(err error, version, address string) error {
	return fmt.Errorf("%w\n（地址里写的 @%s 取不到：可能没有这个版本，也可能没有这个 skill 或你看不到它。"+
		"看有哪些版本：skmr skill versions %s；要按名字找 skill：skmr skills list。）",
		err, version, bareAddress(address))
}

// notFoundFor picks the message for a 404 from a read command.
//
// **One function rather than the same three lines at three call sites**, because the branch is the
// whole decision and a copy of it is a place where `invoke`, `files` and `read` could come to explain
// the same failure three different ways. It is also the part worth testing on its own: no server and
// no credential are needed to check which of the two messages a given address produces.
func notFoundFor(err error, address, pin, key string) error {
	if version, named, _ := versionInAddress(address); named {
		return versionGone(err, version, address)
	}
	return pinFailure(err, pin, key)
}

// readFile is the one call `read` makes, whichever way the pin was decided.
//
// @param pin   what to send as the version header; empty means "whatever is current"
// @param note  what to say instead of naming the version, when there is no pin to name
func readFile(ctx context.Context, address, key, relpath, pin string, offset, limit int,
	note string) error {
	// A draft's bytes live on the author plane, and the consumer plane refuses the pin outright.
	if pin != "" && isDraftPin(pin) {
		return readFromAuthor(ctx, key, pin, relpath, offset, limit, note)
	}

	fetched, err := authenticated(ctx, func(client api.Client) (loaded, error) {
		return fetchFile(ctx, client, address, relpath, pin)
	})
	if err != nil {
		// A revoked version is readable to `maintain` and above through the author plane. The
		// original answer stands when that retry is refused — a `write` caller is not owed a
		// different explanation from the 410 the server already gave.
		if pin != "" && authorPlaneAnswers(err) {
			if authorErr := readFromAuthor(ctx, key, pin, relpath, offset, limit, note); authorErr == nil {
				return nil
			} else if authorPlaneAnswer(authorErr) {
				return authorErr
			}
		}
		if errors.Is(err, api.ErrNotFound) && pin != "" {
			return notFoundFor(err, address, pin, key)
		}
		return err
	}

	// Which version this was, decided once for both paths. It is not a courtesy: the pin lives in a
	// file on this machine and nothing in the request shows it, so this line is the only thing that
	// makes it visible — and a binary file is still a file read at some version.
	which := note
	if which == "" {
		// The manifest's own answer to which version this was, rather than the pin that was sent: they
		// agree, and this one is the server's spelling of the address — which is what a caller would
		// copy into the next command.
		which = fmt.Sprintf("（按 %s 取。）", fetched.meta.Address())
	}

	if fetched.binary {
		return printBinary(fetched.content, relpath, offset, which)
	}
	printContentLimited(fetched.content, offset, limit, withLineNumbers, func(next int) string {
		return fmt.Sprintf("skmr skill read %s %s --offset %d", address, relpath, next)
	})
	fmt.Println(which)
	return nil
}

// readFromAuthor reads one file of a draft or revoked version from the author plane.
func readFromAuthor(ctx context.Context, address, pin, relpath string, offset, limit int, note string) error {
	client, err := session(ctx)
	if err != nil {
		return err
	}
	// **The manifest first, the bytes second — the consumption path's order.** The bytes alone do
	// not say whether they are text; the manifest does. Fetching the other way round made a failed
	// manifest call leave `binary` false, so a `.png` was printed as mojibake — the exact defect
	// this branch exists to avoid — and the request order contradicted this function's own comment.
	files, err := client.AuthorFiles(ctx, address, pin)
	if err != nil {
		return addressRefusal(err)
	}
	binary := false
	for _, file := range files {
		if file.Relpath == relpath {
			binary = file.IsBinary
			break
		}
	}
	content, err := client.AuthorFile(ctx, address, pin, relpath)
	if err != nil {
		var server *api.ServerError
		if errors.As(err, &server) && server.Status == http.StatusNotFound {
			return fmt.Errorf("%w\n（%s@%s 在作者面也取不到这个路径；它要么不存在，要么这一档看不见这一版。）",
				err, address, pin)
		}
		return err
	}
	if binary {
		which := note
		if which == "" {
			which = fmt.Sprintf("（按 %s@%s 取。）", address, pin)
		}
		return printBinary(content, relpath, offset, which)
	}
	printContentLimited(content, offset, limit, withLineNumbers, func(next int) string {
		return fmt.Sprintf("skmr skill read %s@%s %s --offset %d", address, pin, relpath, next)
	})
	which := note
	if which == "" {
		which = fmt.Sprintf("（按 %s@%s 取。）", address, pin)
	}
	fmt.Println(which)
	return nil
}

// fileMissing is what a `read` of a relpath this version does not have says.
//
// The next step is part of the message, and it is the reason the command exists: the lookup is an
// exact match against the manifest, so nothing here can repair a near miss, and without the pointer
// the only recovery is guessing names until one works. Extracted for the same reason `pinFailure` is
// — a decision worth testing on its own, with no server and no credential.
//
// **The reason is the caller's own. `fetchFile` has already read the rung out of the metadata it
// resolved this path against, so the message states that fact instead of asserting one for everybody:
// only an `invoke` caller cannot list, and only they are told why. Told otherwise, an owner reading a
// typo'd path was handed an explanation that contradicted the rung their own `invoke` header showed.**
func fileMissing(relpath, address, access string) error {
	if access == "invoke" {
		return fmt.Errorf("这个路径在这个 skill 里取不到：%s\n"+
			"（列目录需要「浏览」档：skmr skill ls %s。）", relpath, address)
	}
	return fmt.Errorf("这个路径在这个 skill 里取不到：%s\n"+
		"（这一版有哪些文件：skmr skill ls %s。）", relpath, address)
}

// pinFailure rewrites a 404 that happened while a read was pinned from this machine's records.
//
// The server's 404 says it does not distinguish "no such skill" from "not yours" from "no such
// version", and it means it — but here the CLI knows something the server does not: it pinned this
// request itself. So it can say which pin failed, which turns a message that reads like a puzzle into
// one with a next step. **It does not fall back to the current version**: a silent substitution is the
// failure this whole design is arranged around, and it would hand back a confident answer about the
// wrong version.
func pinFailure(err error, pin, key string) error {
	return fmt.Errorf("%w\n（这个版本是按本地记住的 @%s 取的，现在取不到——可能是这个版本没了，"+
		"也可能是这个 skill 没了或你看不到。重新 skmr skill invoke %s 再取。）", err, pin, key)
}

// findFile looks a relpath up in the manifest, by exact match.
//
// A lookup and not a path join, which is §4.2's rule and also this side's: the manifest is the list of
// files the version actually has, and anything resembling path assembly here would be a second,
// differently-wrong answer to a question the server has already answered.
func findFile(detail api.Detail, relpath string) (api.File, bool) {
	for _, file := range detail.Files {
		if file.Relpath == relpath {
			return file, true
		}
	}
	return api.File{}, false
}

// bodyRelpath is the one file every version has, and the one `invoke` has already delivered.
//
// Spelled here rather than derived, because there is nothing to derive it from: the standard makes
// `SKILL.md` a MUST at the root of a skill, and the server names the same string on its own side when
// it serves the body. The manifest cannot settle it either — `resources.body` is a URI of its own
// shape (`/<address>/body`), not the file route the manifest lists.
const bodyRelpath = "SKILL.md"

// authorPlaneAnswers says whether a failed consumer read is the one the author plane might answer:
// a revoked version (410) or a pin the consumer lookup cannot resolve (404). Anything else is a
// different failure and retrying it on the other plane would only add a second, less useful error.
func authorPlaneAnswers(err error) bool {
	var server *api.ServerError
	if errors.As(err, &server) && server.Code == "version_revoked" {
		return true
	}
	return errors.Is(err, api.ErrNotFound)
}

// authorPlaneAnswer says whether the author plane gave a "not here to you" answer. That is the one
// worth reporting instead of the consumer plane's 410: for `browse`/`write` a revoked pin is
// deliberately invisible (404), not a version they can see was taken down.
func authorPlaneAnswer(err error) bool {
	var server *api.ServerError
	if errors.As(err, &server) && (server.Status == 404 || server.Status == 403) {
		return true
	}
	var denial *api.Denial
	return errors.As(err, &denial)
}

// printBinary leaves a file this command cannot show where it can be seen, and says where.
//
// This is not a fallback for something that failed. The host's `Read` renders images, so a path is the
// only route by which a caller can actually look at a binary — which is why the answer is a location
// and not a refusal. The bytes never go to stdout: a stream of them ruins whatever context it lands
// in, and nothing downstream could do anything with it.
//
// @param offset the line the caller asked to start at — meaningless here, and said so instead of dropped
// @param which the line naming the version this came from, the same one the text path ends with —
//
//	omitted here once, and the omission was a hole in the one thing this design claims:
//	that the local pin is never invisible
func printBinary(content []byte, relpath string, offset int, which string) error {
	dir, err := os.MkdirTemp("", config.Name+"-")
	if err != nil {
		return fmt.Errorf("创建临时目录：%w", err)
	}
	// On its own line, like every other value this CLI hands over for a later command to take: a
	// caller that has to find the path inside a sentence is a caller that will get it wrong.
	target := filepath.Join(dir, filepath.Base(relpath))
	if err := os.WriteFile(target, content, 0o600); err != nil {
		return fmt.Errorf("写入 %s：%w", target, err)
	}
	besideThePoint := ""
	if offset != 1 {
		besideThePoint = fmt.Sprintf("--offset %d 在这里没有意义。", offset)
	}
	fmt.Println(target)
	fmt.Printf("（%s 是二进制文件（%d 字节），内容没有打印——上面那一行是它在磁盘上的位置。%s）\n",
		relpath, len(content), besideThePoint)
	fmt.Println(which)
	return nil
}

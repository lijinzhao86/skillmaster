// The commands that read and write a version's files (v7).
//
// **A file, a version, an address** — the same three things in every command here. What differs is
// which plane answers: a row-id pin (`@<版本码>`) is the author plane and the only one that can
// reach a draft; a version number or digest is the consumer plane, which is the only one an `invoke`
// credential can use. `resolve` below decides that once, and every command takes its answer rather
// than deciding again.
//
// **stdout is content; stderr is everything else.** The rule is what makes `cat | head` and
// `grep | sort` behave like the local tools, and it is why the "按 X 操作" line is on stderr even
// though a person reads it first — a pipeline must not find it in its input.
package main

import (
	"regexp"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/pins"
)

const (
	lsUsage   = "用法：skillmaster skill ls <命名空间>/<名字>[@版本]"
	catUsage  = "用法：skillmaster skill cat <命名空间>/<名字>[@版本] <相对路径>..."
	wcUsage   = "用法：skillmaster skill wc <命名空间>/<名字>[@版本] [相对路径...] [-l] [-w] [-c]"
	grepUsage = "用法：skillmaster skill grep <命名空间>/<名字>[@版本] <pattern> [-F] [-i] [-n] [-l] [--path <前缀>]"
	writeU    = "用法：skillmaster skill write <命名空间>/<名字>@<版本码> <相对路径>（内容从标准输入读）"
	editUsage = "用法：skillmaster skill edit <命名空间>/<名字>@<版本码> <相对路径> --old <文本> --new <文本> [--replace-all]"
	rmUsage   = "用法：skillmaster skill rm <命名空间>/<名字>@<版本码> <相对路径>..."
	mvcpUsage = "用法：skillmaster skill <mv|cp> <命名空间>/<名字>@<版本码> <from> <to>"
)

// fileTarget is one resolved command target: which version, on which plane.
type fileTarget struct {
	address string // namespace/name, no pin
	pin     string // the suffix sent after `@` or as the version header
	rowID   bool   // the author plane
	note    string // what stderr says about which version this was
}

// resolve decides the plane and the pin for a read command.
//
// Explicit pin → remembered pin → the command's default. The defaults differ by command because
// they ask different questions: `cat` can reach the consumer plane and asks it what is current, while
// `ls` / `wc` / `grep` are author-plane commands and ask the author list for the live row (or the
// newest visible one when nothing is live).
func resolve(ctx context.Context, client api.Client, address string, authorOnly bool) (fileTarget, error) {
	base := bareAddress(address)
	version, named, err := versionInAddress(address)
	if err != nil {
		return fileTarget{}, err
	}
	if named {
		if id, ok := rowIDOfPin(version); ok {
			return fileTarget{address: base, pin: id, rowID: true,
				note: fmt.Sprintf("（按 %s@%s 操作。）", base, id)}, nil
		}
		if strings.HasPrefix(version, "draft:") {
			// The one spelling the grammar refuses. It is still an *author-plane* attempt — a
			// consumer request would turn it into a 400 about the header, while the author plane
			// answers the same 404 everywhere else in this version (PRD #23).
			return fileTarget{address: base, pin: version, rowID: true,
				note: fmt.Sprintf("（按 %s@%s 操作。）", base, version)}, nil
		}
		// A suffix the consumption plane has no name for (`@latest`, `@foo`, a malformed digest) is
		// an address the author plane answers: sending it as a version header would be a 400 about
		// the header, while the grammar's answer here is the author plane's 404 (v7 PRD #23). The
		// suffix travels as written, so the CLI does not invent a second answer.
		return fileTarget{address: base, pin: version, rowID: !isConsumerPin(version),
			note: fmt.Sprintf("（按 %s@%s 操作。）", base, version)}, nil
	}
	remembered, found, err := rememberedPin(base)
	if err != nil {
		return fileTarget{}, err
	}
	if found {
		return fileTarget{address: base, pin: remembered.Suffix(), rowID: remembered.UsesAuthorPlane(),
			note: fmt.Sprintf("（按 %s@%s 操作。）", base, remembered.Suffix())}, nil
	}
	if authorOnly {
		pin, note, err := authorDefault(ctx, client, base)
		if err != nil {
			return fileTarget{}, err
		}
		return fileTarget{address: base, pin: pin, rowID: true, note: note}, nil
	}
	return contentTarget(ctx, client, base)
}

// consumerName is SemVer 2.0.0's grammar, the only version spelling the consumption plane accepts
// besides a digest (the server mirrors this in SkillAddress).
var consumerName = regexp.MustCompile(`^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)` +
	`(?:-(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*)?` +
	`(?:\+[0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*)?$`)

var consumerDigest = regexp.MustCompile(`^sha256:[0-9a-f]{64}$`)

// isConsumerPin says whether the consumption plane's parser has a name for this suffix. Anything
// else is an address the author plane must answer, so the CLI does not turn it into a bad header.
func isConsumerPin(pin string) bool {
	return consumerName.MatchString(pin) || consumerDigest.MatchString(pin)
}

// rowIDOfPin answers the row-id spellings the author plane accepts, and only those.
//
// A bare 8/26-character code is a row. The legacy `draft:` prefix is accepted only in front of a
// 26-character ULID — `draft:<short>` is a spelling v7 refuses everywhere (PRD #23), and stripping
// the prefix locally would make the CLI succeed where the server says 404.
func rowIDOfPin(version string) (string, bool) {
	if strings.HasPrefix(version, "draft:") {
		rest := strings.TrimPrefix(version, "draft:")
		if len(rest) == 26 && isRowID(rest) {
			return rest, true
		}
		return "", false
	}
	if isRowID(version) {
		return version, true
	}
	return "", false
}

// contentTarget is the default pin for `invoke` / `cat`: what the consumer plane serves, or — when
// nothing is live — the newest version the caller's rung can read on the author plane.
//
// **The fallback is for `write` and above only** (PRD #27). There is no runtime rung name, so it is
// learnt from two answers: asking the author plane for drafts succeeds for `write`+ and is refused
// for `browse`/`invoke`; and the consumer detail separates those two — a denied detail is `invoke`
// (403, and its answer stays the consumer's 404, so nothing about the skill is revealed), while a
// non-denied answer is `browse`, which gets the "nothing is live" sentence the PRD promises.
func contentTarget(ctx context.Context, client api.Client, base string) (fileTarget, error) {
	meta, err := client.Metadata(ctx, base, "")
	if err == nil {
		pin := api.VersionSuffix(meta.Version.Name, meta.Version.Digest)
		return fileTarget{address: base, pin: pin,
			note: fmt.Sprintf("（按 %s@%s 操作。）", base, pin)}, nil
	}
	if !errors.Is(err, api.ErrNotFound) {
		var offline *api.ServerError
		if errors.As(err, &offline) && offline.Status == http.StatusServiceUnavailable {
			// The skill is offline on the consumption plane. The author plane may still serve it
			// (`browse`+), and for `invoke` the author list answers 403 — in which case the
			// consumer's 503 is the truthful answer and is kept.
			pin, note, authorErr := authorDefault(ctx, client, base)
			if authorErr != nil {
				return fileTarget{}, err
			}
			return fileTarget{address: base, pin: pin, rowID: true,
				note: "（skill 下线中）" + note}, nil
		}
		return fileTarget{}, err
	}
	_, detailErr := client.Detail(ctx, base, "")
	var denied *api.Denial
	if errors.As(detailErr, &denied) &&
			(denied.Code == api.Forbidden || denied.Code == api.InsufficientScope) {
		return fileTarget{}, err
	}
	pin, note, authorErr := authorDefault(ctx, client, base)
	if authorErr != nil {
		return fileTarget{}, authorErr
	}
	return fileTarget{address: base, pin: pin, rowID: true, note: note}, nil
}

// authorDefault is the author plane's answer to "which version" when nothing is pinned.
//
// The live row is the current version, not "the newest row": a draft or an older published row can
// have a higher number, and reading one of those while a live version exists would silently change
// what the caller sees (PRD §地址与钉). Only when nothing is live does the fallback apply — and only
// for a credential that can see drafts (`write`+). `browse` cannot, and gets the sentence the PRD
// promises instead of a version it is not entitled to.
//
// The note is returned rather than formatted by the caller because it is the half that says *why*
// this pin is the one being used; the commands put it on stderr, where a pipeline will not read it.
func authorDefault(ctx context.Context, client api.Client, base string) (string, string, error) {
	live, err := client.AuthorVersions(ctx, base,
		api.AuthorVersionQuery{Statuses: []string{"live"}, Limit: 1})
	if err != nil {
		return "", "", err
	}
	if len(live.Versions) > 0 {
		pin := pinOf(live.Versions[0])
		return pin, fmt.Sprintf("（按 %s@%s 操作。）", base, pin), nil
	}
	drafts, draftErr := client.AuthorVersions(ctx, base,
		api.AuthorVersionQuery{Statuses: []string{"draft"}, Limit: 1})
	if draftErr != nil {
		var denied *api.Denial
		if errors.As(draftErr, &denied) &&
				(denied.Code == api.Forbidden || denied.Code == api.InsufficientScope) {
			return "", "", errors.New("这个 skill 还没有上线版本")
		}
		return "", "", draftErr
	}
	_ = drafts // the query is the probe; the actual pick is "newest visible", below
	all, allErr := client.AuthorVersions(ctx, base, api.AuthorVersionQuery{Limit: 1})
	if allErr != nil {
		return "", "", allErr
	}
	if len(all.Versions) == 0 {
		return "", "", errors.New("这个 skill 还没有上线版本")
	}
	chosen := all.Versions[0]
	pin := pinOf(chosen)
	state := stateLabel(chosen.Status)
	return pin, fmt.Sprintf("（当前没有上线版本，按最近可见的版本 %s@%s（%s）操作。）",
			base, pin, state), nil
}

// writeTarget is the write commands' resolver: a row id, or a refusal.
//
// **Names and digests are refused locally**, because the server's answer for them is about state
// ("not a draft") while the real problem is that the address names content rather than a row. The
// sentence is the same one the server would send, minus a round trip.
func writeTarget(address string) (fileTarget, error) {
	base := bareAddress(address)
	version, named, err := versionInAddress(address)
	if err != nil {
		return fileTarget{}, err
	}
	if named {
		if id, ok := rowIDOfPin(version); ok {
			return fileTarget{address: base, pin: id, rowID: true,
				note: fmt.Sprintf("（按 %s@%s 操作。）", base, id)}, nil
		}
		if strings.HasPrefix(version, "draft:") {
			// The one spelling the grammar refuses. It is still an *author-plane* attempt — a
			// consumer request would turn it into a 400 about the header, while the author plane
			// answers the same 404 everywhere else in this version (PRD #23).
			return fileTarget{address: base, pin: version, rowID: true,
				note: fmt.Sprintf("（按 %s@%s 操作。）", base, version)}, nil
		}
		return fileTarget{}, fmt.Errorf("写只针对草稿；草稿的地址是 @<版本码>。" +
			"用 version list --status draft 找到它。")
	}
	remembered, found, err := rememberedPin(base)
	if err != nil {
		return fileTarget{}, err
	}
	if !found || !remembered.UsesAuthorPlane() {
		return fileTarget{}, fmt.Errorf("写只针对草稿；草稿的地址是 @<版本码>。" +
			"用 version list --status draft 找到它。")
	}
	return fileTarget{address: base, pin: remembered.Suffix(), rowID: true}, nil
}

// rejectJSON is the content commands' one shared refusal.
//
// **Content is not a document.** `version list` and the other author-plane commands answer JSON
// because their subject is facts; `ls` / `cat` / `wc` / `grep` answer lines, bytes and counts, and a
// `--json` on them would either invent a schema nobody asked for or silently do nothing. The refusal
// is explicit and the help does not offer the flag (PRD 验收 #21).
func rejectJSON(command string, args []string) error {
	for _, arg := range args {
		if arg == "--json" {
			return fmt.Errorf("%s 不提供 --json：它是内容命令，输出原始字节、行或计数。", command)
		}
	}
	return nil
}

// ls lists a version's files with their sizes and whether they are text.
func ls(ctx context.Context, args []string) error {
	if len(args) != 1 {
		return errors.New(lsUsage)
	}
	target, err := authenticated(ctx, func(client api.Client) (fileTarget, error) {
		return resolve(ctx, client, args[0], true)
	})
	if err != nil {
		return err
	}
	listed, err := authenticated(ctx, func(client api.Client) (api.AuthorFileList, error) {
		return client.AuthorFileList(ctx, target.address, target.pin)
	})
	if err != nil {
		return err
	}
	for _, file := range listed.Files {
		kind := "text"
		if file.IsBinary {
			kind = "binary"
		}
		fmt.Printf("%s\t%d\t%s\n", file.Relpath, file.Size, kind)
	}
	if target.note != "" {
		fmt.Fprintln(os.Stderr, target.note)
	}
	return nil
}

// cat writes the files' bytes to stdout, in argument order, with nothing added.
func cat(ctx context.Context, args []string) error {
	if len(args) < 2 {
		return errors.New(catUsage)
	}
	address := args[0]
	target, err := authenticated(ctx, func(client api.Client) (fileTarget, error) {
		return resolve(ctx, client, address, false)
	})
	if err != nil {
		return err
	}
	client, err := session(ctx)
	if err != nil {
		return err
	}
	failed := false
	for _, relpath := range args[1:] {
		stream, err := openFile(ctx, client, target, relpath)
		if err != nil {
			fmt.Fprintf(os.Stderr, "错误：%v\n", err)
			failed = true
			continue
		}
		_, copyErr := io.Copy(os.Stdout, stream)
		closeErr := stream.Close()
		if copyErr != nil {
			if isBrokenPipe(copyErr) {
				// The pipeline's reader is gone (head, grep -m1). Stop here instead of asking the
				// server for files nobody will read; the exit status is the reader's, not ours.
				return nil
			}
			fmt.Fprintf(os.Stderr, "错误：读取 %s：%v\n", relpath, copyErr)
			failed = true
		}
		if closeErr != nil && !isBrokenPipe(closeErr) {
			failed = true
		}
	}
	if target.note != "" {
		fmt.Fprintln(os.Stderr, target.note)
	}
	if failed {
		if len(args[1:]) == 1 {
			return &exitError{code: 1}
		}
		return &exitError{code: 1, err: errors.New("有一个或多个文件没有读出来")}
	}
	return nil
}

// openFile opens one file for the plane the target resolved to.
func openFile(ctx context.Context, client api.Client, target fileTarget, relpath string) (io.ReadCloser, error) {
	if target.rowID {
		return client.StreamFile(ctx, authorFilePath(target.address, target.pin, relpath), "", "")
	}
	stream, err := client.StreamFile(ctx, consumerFilePath(target.address, relpath),
		api.VersionHeader, target.pin)
	if err == nil {
		return stream, nil
	}
	// The consumer plane's answer for a version it no longer serves (410), cannot see (404), or is
	// not serving at all (503) is not necessarily the caller's: `maintain`+ can read a revoked
	// version, and `browse`+ can read anything the author plane serves while a skill is offline. Ask
	// the author plane once, and keep the consumer's answer if it refuses too (TD §面与档位).
	if consumerUnavailable(err) {
		if authorStream, authorErr := client.StreamFile(ctx,
			authorFilePath(target.address, target.pin, relpath), "", ""); authorErr == nil {
			return authorStream, nil
		}
	}
	return nil, err
}

// consumerUnavailable is the set of consumer answers that mean "ask the author plane".
func consumerUnavailable(err error) bool {
	if errors.Is(err, api.ErrNotFound) {
		return true
	}
	var server *api.ServerError
	if !errors.As(err, &server) {
		return false
	}
	return server.Status == http.StatusGone || server.Status == http.StatusServiceUnavailable ||
			server.Status == http.StatusNotFound
}

// wc prints counts, per file and as a total when more than one is asked for.
//
// `-c` alone is answered from the manifest (`ls`'s sizes): the design promises that asking how big
// something is never transfers it, and the count endpoint computes lines and words from bytes.
func wc(ctx context.Context, args []string) error {
	options, rest, err := parseWcArgs(args)
	if err != nil {
		return err
	}
	if len(rest) < 1 {
		return errors.New(wcUsage)
	}
	address, paths := rest[0], rest[1:]
	target, err := authenticated(ctx, func(client api.Client) (fileTarget, error) {
		return resolve(ctx, client, address, true)
	})
	if err != nil {
		return err
	}
	if options.onlyBytes() {
		listed, err := authenticated(ctx, func(client api.Client) (api.AuthorFileList, error) {
			return client.AuthorFileList(ctx, target.address, target.pin)
		})
		if err != nil {
			return err
		}
		var total int64
		counted := 0
		seen := map[string]bool{}
		for _, file := range listed.Files {
			if !containsPath(paths, file.Relpath) {
				continue
			}
			fmt.Printf("%d %s\n", file.Size, file.Relpath)
			total += file.Size
			counted++
			seen[file.Relpath] = true
		}
		if counted != 1 {
			fmt.Printf("%d total\n", total)
		}
		printNote(target)
		// The manifest is the answer, so a path it does not contain is a failed argument, not a
		// zero-byte file. Saying `0 total` for a typo is the one thing `wc` must not do: the other
		// path through this command asks the server for counts and gets a 404 for the same mistake.
		missing := 0
		for _, path := range paths {
			if !seen[path] {
				fmt.Fprintf(os.Stderr, "错误：这个版本没有 %s\n", path)
				missing++
			}
		}
		if missing > 0 {
			if len(paths) == 1 {
				return &exitError{code: 1}
			}
			return &exitError{code: 1, err: errors.New("有一个或多个文件没有数出来")}
		}
		return nil
	}
	counts, err := authenticated(ctx, func(client api.Client) (api.AuthorCount, error) {
		return client.AuthorCount(ctx, target.address, target.pin, paths)
	})
	if err != nil {
		return err
	}
	for _, file := range counts.Files {
		fmt.Println(wcLine(options, file.Lines, file.Words, file.Bytes, file.Path))
	}
	if len(counts.Files) != 1 {
		fmt.Println(wcLine(options, counts.Total.Lines, counts.Total.Words, counts.Total.Bytes, "total"))
	}
	printNote(target)
	return nil
}

type wcOptions struct {
	lines bool
	words bool
	bytes bool
}

func (o wcOptions) any() bool { return o.lines || o.words || o.bytes }

func (o wcOptions) onlyBytes() bool { return o.bytes && !o.lines && !o.words }

func wcLine(options wcOptions, lines, words, bytes int64, label string) string {
	if !options.any() {
		options = wcOptions{lines: true, words: true, bytes: true}
	}
	var parts []string
	if options.lines {
		parts = append(parts, fmt.Sprint(lines))
	}
	if options.words {
		parts = append(parts, fmt.Sprint(words))
	}
	if options.bytes {
		parts = append(parts, fmt.Sprint(bytes))
	}
	return strings.Join(parts, " ") + " " + label
}

func parseWcArgs(args []string) (wcOptions, []string, error) {
	var options wcOptions
	var rest []string
	for _, arg := range args {
		switch arg {
		case "-l":
			options.lines = true
		case "-w":
			options.words = true
		case "-c":
			options.bytes = true
		default:
			if strings.HasPrefix(arg, "-") && arg != "-" {
				return wcOptions{}, nil, fmt.Errorf("不认识的参数 %s。%s", namedArgument(arg), wcUsage)
			}
			rest = append(rest, arg)
		}
	}
	return options, rest, nil
}

// grep searches one draft and prints only what matched.
//
// Exit codes are the local tool's: 0 for a hit, 1 for none, 2 for a failure. `--help` on the command
// carries the boundaries the server's regex engine has (v7 做 #5).
func grep(ctx context.Context, args []string) error {
	if len(args) > 0 && (args[0] == "--help" || args[0] == "-h") {
		fmt.Fprintln(os.Stderr, grepHelp)
		return nil
	}
	options, rest, err := parseGrepArgs(args)
	if err != nil {
		return &exitError{code: 2, err: err}
	}
	if len(rest) < 2 {
		return &exitError{code: 2, err: errors.New(grepUsage)}
	}
	address, pattern := rest[0], rest[1]
	if pattern == "" {
		return &exitError{code: 2, err: errors.New(grepUsage)}
	}
	target, err := authenticated(ctx, func(client api.Client) (fileTarget, error) {
		return resolve(ctx, client, address, true)
	})
	if err != nil {
		return &exitError{code: 2, err: err}
	}
	result, err := authenticated(ctx, func(client api.Client) (api.AuthorGrepResult, error) {
		return client.AuthorGrep(ctx, target.address, target.pin, pattern, options.fixed,
			options.ignoreCase, options.path, 0)
	})
	if err != nil {
		return &exitError{code: 2, err: grepRefusal(err)}
	}
	if options.filesOnly {
		seen := map[string]bool{}
		for _, hit := range result.Hits {
			if !seen[hit.Path] {
				fmt.Println(hit.Path)
				seen[hit.Path] = true
			}
		}
	} else {
		for _, hit := range result.Hits {
			if options.lineNumbers {
				fmt.Printf("%s:%d:%s\n", hit.Path, hit.Line, hit.Text)
			} else {
				fmt.Printf("%s:%s\n", hit.Path, hit.Text)
			}
		}
	}
	if result.Truncated {
		fmt.Fprintln(os.Stderr, "（结果太多，服务端截断了；缩小 pattern 或用 --path 收窄。）")
	}
	printNote(target)
	if len(result.Hits) == 0 {
		return &exitError{code: 1, err: errors.New("没有命中")}
	}
	return nil
}

// grepRefusal turns the one refusal `grep` can act on into the command that does work instead.
//
// A published version is not searchable this way by design (grep is a draft-only scan, ADR 0062);
// the server says so in English, and the useful half for a person is which command to run next.
func grepRefusal(err error) error {
	var server *api.ServerError
	if errors.As(err, &server) && server.Code == "grep_requires_draft" {
		return errors.New("grep 只在草稿上搜索；这一版已经发布，用 cat <地址> <文件> | grep <pattern> 在本机过滤。")
	}
	// A pattern whose cost the server refuses is not a malformed one: the fix is to narrow it, use
	// `-F`, or filter locally — all of which are more useful than the envelope's generic sentence.
	if errors.As(err, &server) && server.Code == "invalid_request" &&
		server.Details["pattern"] == "too_expensive" {
		return errors.New("这个正则在服务端太贵，已被拒绝。把重复次数 {n} 收到 100 以内、改用 -F，" +
			"或用 cat <地址> <文件> | grep <pattern> 在本机过滤。")
	}
	return err
}

type grepOptions struct {
	fixed       bool
	ignoreCase  bool
	lineNumbers bool
	filesOnly   bool
	path        string
}

func parseGrepArgs(args []string) (grepOptions, []string, error) {
	var options grepOptions
	var rest []string
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "-F":
			options.fixed = true
		case "-i":
			options.ignoreCase = true
		case "-n":
			options.lineNumbers = true
		case "-l":
			options.filesOnly = true
		case "--path":
			if i+1 >= len(args) {
				return grepOptions{}, nil, errors.New("--path 后面要跟一个路径前缀。" + grepUsage)
			}
			options.path = args[i+1]
			i++
		default:
			if strings.HasPrefix(args[i], "-") && args[i] != "-" {
				return grepOptions{}, nil, fmt.Errorf("不认识的参数 %s。%s", namedArgument(args[i]), grepUsage)
			}
			rest = append(rest, args[i])
		}
	}
	return options, rest, nil
}

// grepHelp is the command's own page, and it is what makes the regex boundary discoverable rather
// than a thing a person runs into (v7 TD §grep, PRD 做 #5).
const grepHelp = `用法：skillmaster skill grep <命名空间>/<名字>[@版本] <pattern> [-F] [-i] [-n] [-l] [--path <前缀>]

  只在草稿上搜索；服务端逐行扫描，只回命中行（绝不回未命中的文件内容）。
  -F        按固定字符串匹配（等价本地 grep -F）；最省事，也最准。
  -i        忽略大小写。
  -n        输出行号：path:line:text。
  -l        只列命中的路径。
  --path    只在这些路径前缀下找（按路径段匹配，references/ 不会命中 references-old/）。

  正则（不带 -F）由 RE2 实现，线性时间，不支持反向引用与 lookaround；这类写法得到
  bad_pattern。执行成本有硬上界：重复次数 {n} / {n,m} 最多 100，嵌套重复的编译展开
  最多约一百万个节点（每个计数至少按 1 计，{0} 不归零），一次请求的匹配时间最多 1 秒；
  超出得到 too_expensive（收窄 pattern、改用 -F，或用 cat | grep）。-F 是线性字面量
  搜索（-F -i 按 Unicode 小写映射折叠）。行尾的 \r 会参与匹配，$ 只匹配输入末尾
  （与 Java 正则「末尾换行之前」不同）。二进制文件不参与搜索。命中过多时服务端截断
  并在 stderr 说明。
  已发布的版本不能 grep：请用 skillmaster skill cat <地址> <文件> | grep <pattern>。`

// editHelp says exactly what Claude Code's Edit does, because that is what the command is modelled
// on (v7 做 #8): one exact substring, one occurrence unless asked for all.
const editHelp = `用法：skillmaster skill edit <命名空间>/<名字>@<版本码> <路径> --old <文本> --new <文本> [--replace-all]

  对一份草稿做精确替换：
  - --old 匹配 0 次：拒绝，退出 1（你的画面过期了）。
  - 匹配多次且没给 --replace-all：拒绝，退出 1（改哪一处无法判断）。
  - --replace-all（别名 --all）：全部替换。
  二进制文件拒绝。写之前先读清单；写时带 If-Match，客户端之间的并发改动得到 409。`

// write replaces or creates one file from standard input.
func write(ctx context.Context, args []string) error {
	if len(args) != 2 {
		return errors.New(writeU)
	}
	content, err := io.ReadAll(os.Stdin)
	if err != nil {
		return fmt.Errorf("读标准输入：%w", err)
	}
	return editRemote(ctx, args[0], args[1], func([]byte) ([]byte, error) { return content, nil },
		"写入", true)
}

// edit is Claude Code's `Edit`, on the server: one exact substring, exactly one occurrence unless
// asked to replace them all.
func edit(ctx context.Context, args []string) error {
	if len(args) > 0 && (args[0] == "--help" || args[0] == "-h") {
		fmt.Fprintln(os.Stderr, editHelp)
		return nil
	}
	parsed, err := parseEditArgs(args)
	if err != nil {
		return err
	}
	if parsed.old == "" {
		return errors.New("--old 不能为空。")
	}
	return editRemote(ctx, parsed.address, parsed.relpath, func(content []byte) ([]byte, error) {
		return replaceExactly(content, parsed.old, parsed.new, parsed.replaceAll)
	}, "修改", false)
}

type editArgs struct {
	address    string
	relpath    string
	old        string
	new        string
	replaceAll bool
}

func parseEditArgs(args []string) (editArgs, error) {
	var parsed editArgs
	var positional []string
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--old":
			if i+1 >= len(args) {
				return editArgs{}, errors.New("--old 后面要跟被替换的文本。" + editUsage)
			}
			parsed.old = args[i+1]
			i++
		case "--new":
			if i+1 >= len(args) {
				return editArgs{}, errors.New("--new 后面要跟新文本。" + editUsage)
			}
			parsed.new = args[i+1]
			i++
		case "--replace-all", "--all":
			parsed.replaceAll = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return editArgs{}, fmt.Errorf("不认识的参数 %s。%s", namedArgument(args[i]), editUsage)
			}
			positional = append(positional, args[i])
		}
	}
	if len(positional) != 2 {
		return editArgs{}, errors.New(editUsage)
	}
	parsed.address, parsed.relpath = positional[0], positional[1]
	return parsed, nil
}

// replaceExactly applies the edit, or refuses without writing.
//
// Zero matches is a refusal because the caller's picture of the file is stale; several matches
// without `--replace-all` is a refusal because "which one" is not something the command can know.
// This is Claude Code's own contract, and it is why the count is taken before anything is written.
func replaceExactly(content []byte, old, replacement string, replaceAll bool) ([]byte, error) {
	count := strings.Count(string(content), old)
	switch {
	case count == 0:
		return nil, fmt.Errorf("这一版里没有找到要替换的文本；文件没有改动。")
	case count > 1 && !replaceAll:
		return nil, fmt.Errorf("要替换的文本出现了 %d 次；加 --replace-all 才会全部替换，文件没有改动。", count)
	}
	if replaceAll {
		return []byte(strings.ReplaceAll(string(content), old, replacement)), nil
	}
	return []byte(strings.Replace(string(content), old, replacement, 1)), nil
}

// editRemote is the read–change–write every content write shares: get the file and its ETag, apply
// the change locally, and PUT it back with the ETag as If-Match.
//
// **`write` may create a file; `edit` may not.** When the path is absent, `write` uses the listing's
// own ETag as its baseline and sends the new content without reading anything; `edit` refuses,
// because there is no text to replace and the caller's picture is stale. A binary target is refused
// for `edit` only — `write` replaces bytes whatever they are.
func editRemote(ctx context.Context, address, relpath string,
	change func([]byte) ([]byte, error), summary string, allowCreate bool) error {
	target, err := writeTarget(address)
	if err != nil {
		return err
	}
	client, err := session(ctx)
	if err != nil {
		return err
	}
	listed, err := client.AuthorFileList(ctx, target.address, target.pin)
	if err != nil {
		return err
	}
	binary := false
	found := false
	for _, file := range listed.Files {
		if file.Relpath == relpath {
			binary = file.IsBinary
			found = true
			break
		}
	}
	if !found && !allowCreate {
		return fmt.Errorf("这个版本没有 %s；文件没有改动。", relpath)
	}
	if found && binary && !allowCreate {
		return fmt.Errorf("%s 是二进制文件，edit 只改文本；文件没有改动。", relpath)
	}
	var content []byte
	var etag string
	if found {
		content, etag, err = client.AuthorFileBytes(ctx, target.address, target.pin, relpath)
		if err != nil {
			return err
		}
	} else {
		etag = listed.ETag
	}
	updated, err := change(content)
	if err != nil {
		return err
	}
	result, err := client.AuthorWriteFile(ctx, target.address, target.pin, relpath, updated, etag)
	if err != nil {
		return writeRefusal(err, address)
	}
	rememberWrite(address, target, result)
	fmt.Fprintf(os.Stderr, "已%s %s（%s）。\n", summary, relpath, shortDigest(result.Version.Digest))
	return nil
}

// writeRefusal turns the server's content conflicts into a sentence about what to do next.
// stateLabel is the console's word for a stored state value.
func stateLabel(state string) string {
	switch state {
	case "draft":
		return "草稿"
	case "published":
		return "线上"
	case "revoked":
		return "已下架"
	case "discarded":
		return "已丢弃"
	default:
		return "未知状态"
	}
}

func writeRefusal(err error, address string) error {
	var server *api.ServerError
	if errors.As(err, &server) {
		switch server.Code {
		case "digest_mismatch":
			return fmt.Errorf("这一版在读过之后被改动过；重新读一遍再改。%w", err)
		case "version_is_not_draft":
			// The server names the row's state, and its pin when that pin is an address the caller
			// can use. No `%w`: the server's sentence is English, and pasting it after a Chinese one
			// reads as two answers.
			state := stateLabel(server.Details["state"])
			next := "要改内容，先找一份草稿：skillmaster version list --status draft。"
			if server.Details["state"] == "published" && server.Details["pin"] != "" {
				return fmt.Errorf("写只针对草稿；这一版现在是「%s」（@%s）。%s",
						state, server.Details["pin"], next)
			}
			return fmt.Errorf("写只针对草稿；这一版现在是「%s」。%s", state, next)
		case "content_already_exists":
			state := stateLabel(server.Details["state"])
			where := ""
			if pin := server.Details["pin"]; pin != "" {
				where = "在 @" + pin + "（" + state + "）"
			} else if server.Details["state"] != "" {
				where = "在同一 skill 的一版「" + state + "」里"
			}
			return fmt.Errorf("这段内容已经存在%s；草稿没有被改动。", where)
		case "if_match_required":
			return fmt.Errorf("这次写没有带上基线摘要；重新运行命令。%w", err)
		}
	}
	return err
}

// rememberWrite updates the remembered pin's digest when the write was against it.
func rememberWrite(address string, target fileTarget, result api.AuthorWriteResult) {
	if strings.Contains(address, "@") {
		// An explicit pin does not move the machine's pin — the same rule `invoke`'s explicit form
		// follows.
		return
	}
	remembered, found, err := rememberedPin(target.address)
	if err != nil || !found || remembered.Suffix() != target.pin {
		return
	}
	remembered.Digest = strings.TrimPrefix(result.Version.Digest, "sha256:")
	_, _, _ = pinsForAndRemember(target.address, remembered)
}

func pinsForAndRemember(address string, pin pins.Pin) (pins.Pin, bool, error) {
	return rememberPin(address, pin)
}

// rm removes one or more files, continuing past a failure and reporting it at the end.
func rm(ctx context.Context, args []string) error {
	if len(args) < 2 {
		return errors.New(rmUsage)
	}
	target, err := writeTarget(args[0])
	if err != nil {
		return err
	}
	client, err := session(ctx)
	if err != nil {
		return err
	}
	ifMatch := ""
	failed := false
	for _, relpath := range args[1:] {
		if ifMatch == "" {
			listed, err := client.AuthorFileList(ctx, target.address, target.pin)
			if err != nil {
				return err
			}
			ifMatch = listed.ETag
		}
		result, err := client.AuthorDeleteFile(ctx, target.address, target.pin, relpath, ifMatch)
		if err != nil {
			fmt.Fprintf(os.Stderr, "错误：删除 %s：%v\n", relpath, writeRefusal(err, args[0]))
			failed = true
			continue
		}
		ifMatch = result.Version.Digest
		rememberWrite(args[0], target, result)
		fmt.Fprintf(os.Stderr, "已删除 %s。\n", relpath)
	}
	if failed {
		if len(args[1:]) == 1 {
			return &exitError{code: 1}
		}
		return &exitError{code: 1, err: errors.New("有一个或多个文件没有删掉")}
	}
	return nil
}

// moveOrCopy is `mv` and `cp`: one request, because the server moves a reference rather than bytes.
func moveOrCopy(ctx context.Context, args []string, op string) error {
	if len(args) != 3 {
		return errors.New(mvcpUsage)
	}
	target, err := writeTarget(args[0])
	if err != nil {
		return err
	}
	client, err := session(ctx)
	if err != nil {
		return err
	}
	listed, err := client.AuthorFileList(ctx, target.address, target.pin)
	if err != nil {
		return err
	}
	result, err := client.AuthorMoveOrCopy(ctx, target.address, target.pin, op, args[1], args[2],
		listed.ETag)
	if err != nil {
		return writeRefusal(err, args[0])
	}
	rememberWrite(args[0], target, result)
	verb := "复制"
	if op == "mv" {
		verb = "移动"
	}
	fmt.Fprintf(os.Stderr, "已%s %s → %s（%s）。\n", verb, args[1], args[2],
		shortDigest(result.Version.Digest))
	return nil
}

// authorFilePath is the author plane's address for one file.
func authorFilePath(address, pin, relpath string) string {
	base, err := api.SkillPath(address)
	if err != nil {
		return ""
	}
	return "/api/v1/author" + strings.TrimPrefix(base, "/api/v1") + "/versions/" +
		url.PathEscape(pin) + "/files/" + escapeRelpath(relpath)
}

// consumerFilePath is the consumption plane's address for one file. The version travels in the
// header, never in the path — the path grammar there is a version suffix on the name.
func consumerFilePath(address, relpath string) string {
	base, err := api.SkillPath(address)
	if err != nil {
		return ""
	}
	return base + "/files/" + escapeRelpath(relpath)
}

func escapeRelpath(relpath string) string {
	segments := strings.Split(relpath, "/")
	for index, segment := range segments {
		segments[index] = url.PathEscape(segment)
	}
	return strings.Join(segments, "/")
}

func containsPath(paths []string, relpath string) bool {
	if len(paths) == 0 {
		return true
	}
	for _, wanted := range paths {
		if wanted == relpath {
			return true
		}
	}
	return false
}

func shortDigest(digest string) string {
	digest = strings.TrimPrefix(digest, "sha256:")
	if len(digest) > 8 {
		return digest[:8]
	}
	return digest
}

func printNote(target fileTarget) {
	if target.note != "" {
		fmt.Fprintln(os.Stderr, target.note)
	}
}

// isBrokenPipe is the downstream having gone away — `cat big | head -5` — which is not a failure of
// this command and must not print an error or change the exit status.
func isBrokenPipe(err error) bool {
	return errors.Is(err, os.ErrClosed) || strings.Contains(err.Error(), "broken pipe") ||
		strings.Contains(err.Error(), "EPIPE")
}

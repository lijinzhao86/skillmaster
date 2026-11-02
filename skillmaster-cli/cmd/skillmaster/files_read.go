// Reading files and views: cat, open, wc, grep.

package main

import (
	"context"
	"errors"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
)

// cat writes the files' bytes to stdout, in argument order, with nothing added.
func cat(ctx context.Context, args []string) error {
	if len(args) < 2 {
		return usagef("要接地址和至少一个相对路径。%s", usageAt("skill cat"))
	}
	address := args[0]
	if err := checkAddressSyntax("skill cat", address); err != nil {
		return err
	}
	target, err := authenticated(ctx, func(client api.Client) (fileTarget, error) {
		return resolve(ctx, client, address, false)
	})
	if err != nil {
		return addressRefusal(err)
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
// `-c` alone is answered from the manifest, which carries each file's size — a number `files`
// leaves out on purpose, since that listing exists to be copied from. The design promises that
// asking how big something is never transfers it, and the count endpoint computes lines and words
// from bytes.
func wc(ctx context.Context, args []string) error {
	options, rest, err := parseWcArgs(args)
	if err != nil {
		return err
	}
	if len(rest) < 1 {
		return usagef("要接一个地址。%s", usageAt("skill wc"))
	}
	address, paths := rest[0], rest[1:]
	if err := checkAddressSyntax("skill wc", address); err != nil {
		return err
	}
	target, err := authenticated(ctx, func(client api.Client) (fileTarget, error) {
		return resolve(ctx, client, address, true)
	})
	if err != nil {
		return addressRefusal(err)
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
		if counted > 1 {
			// Not `!= 1`: when every path was missing there is nothing to total, and `0 total` is the
			// one answer the comment below rules out.
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
	// `> 1`, the same rule the `-c` branch uses: nothing counted is no summary, one file is its own
	// total.
	if len(counts.Files) > 1 {
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
				return wcOptions{}, nil, unknownFlag("skill wc", arg)
			}
			rest = append(rest, arg)
		}
	}
	return options, rest, nil
}

// grep searches one version and prints only what matched.
//
// Exit codes are the local tool's: 0 for a hit, 1 for none, 2 for a failure. `--help` on the command
// carries the boundaries the server's regex engine has (v7 做 #5).
func grep(ctx context.Context, args []string) error {
	options, rest, err := parseGrepArgs(args)
	if err != nil {
		return usagef("%s", err)
	}
	if len(rest) < 1 {
		return usagef("要接地址和 pattern。%s", usageAt("skill grep"))
	}
	if len(rest) > 2 {
		// One positional pattern, the way the usage line reads; more than one is `-e`'s job. The
		// alternative — searching only the first and saying nothing — answered a different query
		// than the one that was asked.
		return usagef("只接一个 pattern；多模式用 -e。%s", usageAt("skill grep"))
	}
	address := rest[0]
	patterns := options.patterns
	if len(rest) > 1 {
		if rest[1] == "" {
			return usagef("要接地址和 pattern。%s", usageAt("skill grep"))
		}
		patterns = append([]string{rest[1]}, patterns...)
	}
	if len(patterns) == 0 {
		return usagef("要接地址和 pattern。%s", usageAt("skill grep"))
	}
	if err := checkAddressSyntax("skill grep", address); err != nil {
		return err
	}
	target, err := authenticated(ctx, func(client api.Client) (fileTarget, error) {
		return resolve(ctx, client, address, true)
	})
	if err != nil {
		return addressRefusal(err)
	}
	result, err := authenticated(ctx, func(client api.Client) (api.AuthorGrepResult, error) {
		return client.AuthorGrep(ctx, target.address, target.pin, api.GrepQuery{
			Patterns:   patterns,
			Fixed:      options.fixed,
			IgnoreCase: options.ignoreCase,
			PathPrefix: options.path,
			Before:     options.before,
			After:      options.after,
			Exclude:    options.exclude,
			Max:        options.max,
		})
	})
	if err != nil {
		return grepRefusal(err)
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
		// **Context lines are marked the way grep marks them**: `:` separates a matching line's
		// fields, `-` a context line's, and a `--` line separates groups that are not adjacent.
		lastPath, lastLine := "", 0
		printed := false
		withContext := options.before > 0 || options.after > 0
		for _, hit := range result.Hits {
			if hit.Path != lastPath {
				lastPath, lastLine = hit.Path, 0
			}
			if withContext && printed && hit.Line > lastLine+1 {
				fmt.Println("--")
			}
			separator := ":"
			if !hit.Match {
				separator = "-"
			}
			if options.lineNumbers {
				fmt.Printf("%s%s%d%s%s\n", hit.Path, separator, hit.Line, separator, hit.Text)
			} else {
				fmt.Printf("%s%s%s\n", hit.Path, separator, hit.Text)
			}
			lastLine = hit.Line
			printed = true
		}
	}
	if result.Truncated {
		fmt.Fprintln(os.Stderr, "（结果太多，服务端截断了；缩小 pattern 或用 --path 收窄。）")
	}
	if len(result.Skipped) > 0 {
		fmt.Fprintf(os.Stderr, "（有 %d 个文本文件超过 16 MiB，没有扫描：%s）\n",
			len(result.Skipped), strings.Join(result.Skipped, ", "))
	}
	printNote(target)
	if len(result.Hits) == 0 {
		return &exitError{code: 1, err: errors.New("没有命中")}
	}
	return nil
}

// grepRefusal turns the one refusal `grep` can act on into the command that does work instead.
//
// Since v22 a current server searches every version the caller may read (ADR 0098), so this code is
// what an **older** server answers when the pin names a published version. The branch stays because
// a new CLI can meet one during a rollout; the sentence describes the server, not this build.
func grepRefusal(err error) error {
	var server *api.ServerError
	if errors.As(err, &server) && server.Code == "grep_requires_draft" {
		return errors.New("这台服务端还只让 grep 搜草稿。用 cat <地址> <文件> | grep <pattern> 在本机过滤，" +
			"或升级 skmr。")
	}
	// A pattern whose cost the server refuses is not a malformed one: the fix is to narrow it, use
	// `-F`, or filter locally — all of which are more useful than the envelope's generic sentence.
	if errors.As(err, &server) && server.Code == "invalid_request" &&
		server.Details["pattern"] == "too_expensive" {
		return errors.New("这个正则在服务端太贵，已被拒绝。把重复次数 {n} 收到 100 以内、改用 -F，" +
			"或用 cat <地址> <文件> | grep <pattern> 在本机过滤。")
	}
	return addressRefusal(err)
}

type grepOptions struct {
	fixed       bool
	ignoreCase  bool
	lineNumbers bool
	filesOnly   bool
	path        string
	patterns    []string
	before      int
	after       int
	exclude     []string
	max         int
	contextSet  bool
	beforeSet   bool
	afterSet    bool
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
		case "-e":
			if i+1 >= len(args) {
				return grepOptions{}, nil, usagef("-e 后面要跟 pattern。%s", usageAt("skill grep"))
			}
			options.patterns = append(options.patterns, args[i+1])
			i++
		case "-C", "-A", "-B":
			flag := args[i]
			if i+1 >= len(args) {
				return grepOptions{}, nil, usagef("%s 后面要跟行数。%s", flag, usageAt("skill grep"))
			}
			count, convErr := strconv.Atoi(args[i+1])
			if convErr != nil || count < 0 {
				return grepOptions{}, nil, usagef("%s 要跟一个非负整数。%s", flag, usageAt("skill grep"))
			}
			switch flag {
			case "-C":
				options.contextSet = true
				options.before, options.after = count, count
			case "-A":
				options.afterSet = true
				options.after = count
			case "-B":
				options.beforeSet = true
				options.before = count
			}
			i++
		case "--exclude":
			if i+1 >= len(args) {
				return grepOptions{}, nil, usagef("--exclude 后面要跟 glob。%s", usageAt("skill grep"))
			}
			options.exclude = append(options.exclude, args[i+1])
			i++
		case "--max-count":
			if i+1 >= len(args) {
				return grepOptions{}, nil, usagef("--max-count 后面要跟数字。%s", usageAt("skill grep"))
			}
			count, convErr := strconv.Atoi(args[i+1])
			if convErr != nil || count < 1 {
				return grepOptions{}, nil, usagef("--max-count 要跟一个正整数。%s", usageAt("skill grep"))
			}
			options.max = count
			i++
		case "--path":
			if i+1 >= len(args) {
				return grepOptions{}, nil, usagef("--path 后面要跟一个路径前缀。%s", usageAt("skill grep"))
			}
			options.path = args[i+1]
			i++
		default:
			if strings.HasPrefix(args[i], "-") && args[i] != "-" {
				return grepOptions{}, nil, unknownFlag("skill grep", args[i])
			}
			rest = append(rest, args[i])
		}
	}
	// `-C` already means "both sides"; pairing it with `-A` or `-B` is a caller who wants two
	// different answers from one request, and grep resolves it by refusing.
	if options.contextSet && (options.beforeSet || options.afterSet) {
		return grepOptions{}, nil, usagef("-C 不能跟 -A / -B 同时用。%s", usageAt("skill grep"))
	}
	return options, rest, nil
}

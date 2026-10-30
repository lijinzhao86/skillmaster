package main

// The read-side views over one version: `ls`, `find`, `stat`, `head`, `tail` (v34).
//
// **Everything here is a view, not a computation over many files.** `ls` / `find` / `stat` work on
// the manifest the server already returns — paths, sizes, digests — and never fetch a file's bytes;
// `head` / `tail` fetch the one file the caller named. That is ADR 0118's split: single-file work on
// the client, cross-file answers on the server.

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"path"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// listing is one version's manifest, resolved the way `files` resolved it: the author plane lists
// every state the caller may read, drafts included.
func listing(ctx context.Context, command, address string) (fileTarget, []api.File, error) {
	if err := checkAddressSyntax(command, address); err != nil {
		return fileTarget{}, nil, err
	}
	target, err := authenticated(ctx, func(client api.Client) (fileTarget, error) {
		return resolve(ctx, client, address, true)
	})
	if err != nil {
		return fileTarget{}, nil, addressRefusal(err)
	}
	listed, err := authenticated(ctx, func(client api.Client) (api.AuthorFileList, error) {
		return client.AuthorFileList(ctx, target.address, target.pin)
	})
	if err != nil {
		return fileTarget{}, nil, addressRefusal(err)
	}
	// The note says which version these paths came from, and it belongs on stderr for the same
	// reason it does in `read` and `grep`: `ls`'s stdout is a list, not a place for bookkeeping.
	printNote(target)
	return target, listed.Files, nil
}

// entry is one line of `ls`: a file, or a directory derived from the paths under a prefix.
type entry struct {
	path   string // the directory's path without a trailing slash, or the file's relpath
	dir    bool
	size   int64
	binary bool
}

// entriesUnder is one level of a directory, or — with recursive — everything below it.
//
// **Directories have no rows of their own** (v32's manifest is files only), so a directory exists
// exactly when some file's path continues through it. The set is derived here and nowhere else,
// which is why `ls`, `find` and `stat` agree on what a directory is.
func entriesUnder(files []api.File, dir string, recursive bool) []entry {
	prefix := ""
	if dir != "" {
		prefix = dir + "/"
	}
	seen := map[string]bool{}
	var out []entry
	for _, file := range files {
		if !strings.HasPrefix(file.Relpath, prefix) {
			continue
		}
		rest := file.Relpath[len(prefix):]
		if rest == "" {
			continue
		}
		if !recursive {
			if name, _, nested := strings.Cut(rest, "/"); nested {
				if !seen[name] {
					seen[name] = true
					out = append(out, entry{path: join(dir, name), dir: true})
				}
				continue
			}
		}
		out = append(out, entry{path: file.Relpath, size: file.Size, binary: file.IsBinary})
		if recursive {
			// Every directory the path passes through is an entry of its own.
			segments := strings.Split(rest, "/")
			for i := 1; i < len(segments); i++ {
				directory := join(dir, strings.Join(segments[:i], "/"))
				if !seen[directory] {
					seen[directory] = true
					out = append(out, entry{path: directory, dir: true})
				}
			}
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if recursive {
			return out[i].path < out[j].path
		}
		// One level of `ls`: directories first, then files, each by name — the console tree's rule.
		if out[i].dir != out[j].dir {
			return out[i].dir
		}
		return out[i].path < out[j].path
	})
	return out
}

func join(dir, name string) string {
	if dir == "" {
		return name
	}
	return dir + "/" + name
}

// entryType is the `ls -l` type column: the body, a directory, text, or binary.
func entryType(e entry) string {
	switch {
	case e.dir:
		return "dir"
	case e.path == "SKILL.md":
		return "body"
	case e.binary:
		return "bin"
	default:
		return "text"
	}
}

// ls lists one level of a version's tree (v34 A1).
func ls(ctx context.Context, args []string) error {
	detailed, recursive := false, false
	var rest []string
	for _, arg := range args {
		switch arg {
		case "-l":
			detailed = true
		case "-R":
			recursive = true
		default:
			if strings.HasPrefix(arg, "-") {
				return unknownFlag("skill ls", arg)
			}
			rest = append(rest, arg)
		}
	}
	if len(rest) < 1 || len(rest) > 2 {
		return usagef("要接地址，可选一个目录。%s", usageAt("skill ls"))
	}
	_, files, err := listing(ctx, "skill ls", rest[0])
	if err != nil {
		return err
	}
	dir := ""
	if len(rest) == 2 {
		dir = strings.Trim(rest[1], "/")
	}
	if dir != "" && !hasEntry(files, dir) {
		return fmt.Errorf("这一版没有 %s 目录；用 skmr skill ls %s 看根目录。", dir, rest[0])
	}
	for _, file := range files {
		if file.Relpath == dir {
			return fmt.Errorf("%s 是文件；读文本用 skmr skill read %s %s，要原始字节用 skmr skill cat %s %s。",
				dir, rest[0], dir, rest[0], dir)
		}
	}
	for _, e := range entriesUnder(files, dir, recursive) {
		name := e.path
		if e.dir {
			name += "/"
		}
		if detailed {
			size := "-"
			if !e.dir {
				size = fmt.Sprintf("%d", e.size)
			}
			fmt.Printf("%s\t%s\t%s\n", entryType(e), size, name)
			continue
		}
		fmt.Println(name)
	}
	return nil
}

// hasEntry reports whether the path is a file or a directory of this version.
func hasEntry(files []api.File, relpath string) bool {
	for _, file := range files {
		if file.Relpath == relpath || strings.HasPrefix(file.Relpath, relpath+"/") {
			return true
		}
	}
	return false
}

// find matches names, not contents (v34 A3). A pattern without a slash is matched against the last
// segment; one with a slash is matched against the whole path — `find -name` and `find -path`.
func find(ctx context.Context, args []string) error {
	ignoreCase := false
	prefix := ""
	var rest []string
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "-i":
			ignoreCase = true
		case "--path":
			if i+1 >= len(args) {
				return usagef("--path 后面要跟一条路径。%s", usageAt("skill find"))
			}
			prefix = args[i+1]
			i++
		default:
			if strings.HasPrefix(args[i], "-") {
				return unknownFlag("skill find", args[i])
			}
			rest = append(rest, args[i])
		}
	}
	if len(rest) != 2 {
		return usagef("要接地址和模式。%s", usageAt("skill find"))
	}
	address, pattern := rest[0], rest[1]
	if pattern == "" {
		return usagef("要接地址和模式。%s", usageAt("skill find"))
	}
	_, files, err := listing(ctx, "skill find", address)
	if err != nil {
		return err
	}
	matched := []string{}
	for _, file := range files {
		// Same rule as the server's `--path` for `grep` (`within`): the path itself, or anything
		// under it. Segment-wise, so `references/a` does not match `references/api.md`. Slashes at
		// both ends are dropped (`/references` and `references/` name the same directory) but spaces
		// are not — the server does not trim them either, and a value that is blank after trimming
		// spaces filters nothing on both sides.
		if strings.TrimSpace(prefix) != "" {
			wanted := strings.Trim(prefix, "/")
			if file.Relpath != wanted && !strings.HasPrefix(file.Relpath, wanted+"/") {
				continue
			}
		}
		candidate := file.Relpath
		if !strings.Contains(pattern, "/") {
			candidate = path.Base(file.Relpath)
		}
		if globMatch(pattern, candidate, ignoreCase) {
			matched = append(matched, file.Relpath)
		}
	}
	sort.Strings(matched)
	for _, relpath := range matched {
		fmt.Println(relpath)
	}
	if len(matched) == 0 {
		return &exitError{code: 1, err: fmt.Errorf("没有匹配 %s 的文件。", pattern)}
	}
	return nil
}

// globMatch is `path.Match` with an optional case fold. `*` does not cross a `/`, which is what makes
// `*.md` mean "this level" rather than "anywhere below".
func globMatch(pattern, candidate string, ignoreCase bool) bool {
	if ignoreCase {
		pattern, candidate = strings.ToLower(pattern), strings.ToLower(candidate)
	}
	ok, err := path.Match(pattern, candidate)
	return err == nil && ok
}

// stat prints one line per path (v34 A4). Everything it needs is already in the manifest.
func stat(ctx context.Context, args []string) error {
	fullDigest := false
	var rest []string
	for _, arg := range args {
		switch arg {
		case "--digest":
			fullDigest = true
		default:
			if strings.HasPrefix(arg, "-") {
				return unknownFlag("skill stat", arg)
			}
			rest = append(rest, arg)
		}
	}
	if len(rest) < 2 {
		return usagef("要接地址和至少一个路径。%s", usageAt("skill stat"))
	}
	_, files, err := listing(ctx, "skill stat", rest[0])
	if err != nil {
		return err
	}
	failed := false
	for _, relpath := range rest[1:] {
		if file, ok := fileOf(files, relpath); ok {
			// The wire spells digests `sha256:<hex>` (a pin's own syntax). The column is the hex:
			// first twelve characters by default, all of them with `--digest`. A manifest from a
			// server that predates the field prints `-` rather than an empty cell.
			digest := strings.TrimPrefix(file.SHA256, "sha256:")
			if digest == "" {
				digest = "-"
			} else if len(digest) > 12 && !fullDigest {
				digest = digest[:12]
			}
			kind := "text"
			if file.IsBinary {
				kind = "bin"
			}
			fmt.Printf("%s\t%d\t%s\t%s\n", relpath, file.Size, kind, digest)
			continue
		}
		if size, count, ok := dirSummary(files, relpath); ok {
			fmt.Printf("%s/\t%d\tdir\tfiles:%d\n", strings.TrimSuffix(relpath, "/"), size, count)
			continue
		}
		fmt.Fprintf(os.Stderr, "错误：这一版没有 %s；用 skmr skill ls %s 看清单。\n", relpath, rest[0])
		failed = true
	}
	if failed {
		return &exitError{code: 1}
	}
	return nil
}

func fileOf(files []api.File, relpath string) (api.File, bool) {
	for _, file := range files {
		if file.Relpath == relpath {
			return file, true
		}
	}
	return api.File{}, false
}

func dirSummary(files []api.File, dir string) (int64, int, bool) {
	dir = strings.TrimSuffix(dir, "/")
	prefix := dir + "/"
	var size int64
	count := 0
	for _, file := range files {
		if strings.HasPrefix(file.Relpath, prefix) {
			size += file.Size
			count++
		}
	}
	return size, count, count > 0
}

// head and tail print raw bytes — no line numbers, no footer (v34 A5). They are the Linux habits;
// `read --raw --limit` is the general form.
func headTail(ctx context.Context, args []string, tail bool) error {
	command := "skill head"
	if tail {
		command = "skill tail"
	}
	lines := 10
	var rest []string
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "-n":
			if i+1 >= len(args) {
				return usagef("-n 后面要跟行数。%s", usageAt(command))
			}
			count, err := strconv.Atoi(args[i+1])
			if err != nil || count < 1 {
				return usagef("-n 要跟一个正整数。%s", usageAt(command))
			}
			lines = count
			i++
		default:
			if strings.HasPrefix(args[i], "-") {
				return unknownFlag(command, args[i])
			}
			rest = append(rest, args[i])
		}
	}
	if len(rest) != 2 {
		return usagef("要接地址和路径。%s", usageAt(command))
	}
	address, relpath := rest[0], rest[1]
	if err := checkAddressSyntax(command, address); err != nil {
		return err
	}
	fetched, err := contentFor(ctx, address, relpath)
	if err != nil {
		return err
	}
	if fetched.binary {
		return fmt.Errorf("%s 是二进制文件；要原始字节用 skmr skill cat %s %s，要整版用 skmr skill download。",
			relpath, address, relpath)
	}
	text := splitLines(fetched.content)
	if len(text) > lines {
		if tail {
			text = text[len(text)-lines:]
		} else {
			text = text[:lines]
		}
	}
	for _, line := range text {
		_, _ = os.Stdout.Write(line)
	}
	if fetched.which != "" {
		fmt.Fprintln(os.Stderr, fetched.which)
	}
	return nil
}

// fileContent is one file with the two facts a view needs: whether it is text, and which version it
// came from.
type fileContent struct {
	content []byte
	binary  bool
	which   string
}

// contentFor is `read`'s resolution, without its rendering: a draft is read from the author plane, a
// published version from the consumer plane, and a revoked one through the author plane's retry.
func contentFor(ctx context.Context, address, relpath string) (fileContent, error) {
	pin, note, err := pinFor(address)
	if err != nil {
		return fileContent{}, err
	}
	key := bareAddress(address)
	if pin != "" && isDraftPin(pin) {
		return contentFromAuthor(ctx, key, pin, relpath, note)
	}
	fetched, err := authenticated(ctx, func(client api.Client) (loaded, error) {
		return fetchFile(ctx, client, address, relpath, pin)
	})
	if err == nil {
		which := note
		if which == "" {
			which = fmt.Sprintf("（按 %s 取。）", fetched.meta.Address())
		}
		return fileContent{content: fetched.content, binary: fetched.binary, which: which}, nil
	}
	if pin != "" && authorPlaneAnswers(err) {
		if fromAuthor, authorErr := contentFromAuthor(ctx, key, pin, relpath, note); authorErr == nil {
			return fromAuthor, nil
		} else if authorPlaneAnswer(authorErr) {
			return fileContent{}, authorErr
		}
	}
	if errors.Is(err, api.ErrNotFound) && pin != "" {
		return fileContent{}, notFoundFor(err, address, pin, key)
	}
	return fileContent{}, err
}

// contentFromAuthor reads one file of a draft (or a version only the author plane serves) and keeps
// the manifest's binary flag, which the bytes alone cannot answer.
func contentFromAuthor(ctx context.Context, address, pin, relpath, note string) (fileContent, error) {
	client, err := session(ctx)
	if err != nil {
		return fileContent{}, err
	}
	files, err := client.AuthorFiles(ctx, address, pin)
	if err != nil {
		return fileContent{}, addressRefusal(err)
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
		return fileContent{}, err
	}
	which := note
	if which == "" {
		which = fmt.Sprintf("（按 %s@%s 取。）", address, pin)
	}
	return fileContent{content: content, binary: binary, which: which}, nil
}

// retiredFiles answers the name `files` carried until v34 (ADR 0112): the work moved to `ls`, and
// naming the new command beats "no such command" for something the help page listed last week.
func retiredFiles() error {
	fmt.Fprintln(os.Stderr, "skmr skill files 已经退役：看一层用 skmr skill ls <地址> [<目录>]；"+
		"整版清单用 skmr skill ls <地址> -R 或 skmr skill find <地址> '*'。")
	return &exitError{code: 2}
}

// compilePattern turns `--old` into a pattern. The refusal lives here because two callers need the
// same words: `edit` compiles once before any request (a pattern that does not parse does not depend
// on the file), and the replacement compiles again with the content in hand.
func compilePattern(pattern string) (*regexp.Regexp, error) {
	compiled, err := regexp.Compile(pattern)
	if err != nil {
		// Usage error: the pattern is the command's own input, and RE2's first line says what is
		// wrong with it. Later lines point at positions in the expression nobody can see.
		first := strings.SplitN(err.Error(), "\n", 2)[0]
		return nil, usagef("正则不合法：%s。%s", first, usageAt("skill edit"))
	}
	return compiled, nil
}

// replaceRegex is `edit --regex` (v34 A9): the same read–change–write as a literal edit, with the
// pattern compiled by RE2 and the replacement expanded as a template (`$1`).
//
// **It is not a second way to write.** The command still anchors on the server's bytes, still
// refuses more than one match without `--replace-all`, still echoes the hunk — `sed` would have been
// a new mutation path; this is the same path with a different matcher.
func replaceRegex(content []byte, view, pattern, replacement, address, relpath string,
	all bool) ([]byte, error) {
	compiled, err := compilePattern(pattern)
	if err != nil {
		return nil, err
	}
	matches := compiled.FindAllStringSubmatchIndex(view, -1)
	switch {
	case len(matches) == 0:
		// Deliberately no "closest line": a pattern is not a piece of text, and the nearest line to
		// `\d{4}-\d{2}` says nothing about why it did not match.
		return nil, fmt.Errorf("没有匹配 %s。先 skmr skill cat %s %s 看一眼原文。文件没有改动。",
			pattern, address, relpath)
	case len(matches) > 1 && !all:
		// The message lists lines, and a regular expression is not a substring: searching for it
		// found nothing and printed an empty list. The matches themselves carry the offsets.
		offsets := make([]int, len(matches))
		for i, match := range matches {
			offsets[i] = match[0]
		}
		return nil, multipleMatchesErrorAt(view, offsets, len(matches))
	}
	terminator := dominantTerminator(content)
	out := make([]byte, 0, len(content))
	cursor := &originalCursor{content: content}
	copied := 0
	for _, match := range matches {
		out = append(out, content[copied:cursor.at(match[0])]...)
		expanded := string(compiled.ExpandString(nil, replacement, view, match))
		out = append(out, encodeReplacement(expanded, terminator)...)
		copied = cursor.at(match[1])
		if !all {
			break
		}
	}
	out = append(out, content[copied:]...)
	if bytes.Equal(out, content) {
		return nil, errors.New("替换后与原文相同，没有可改的内容；文件没有改动。")
	}
	return out, nil
}

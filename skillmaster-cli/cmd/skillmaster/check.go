package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"path"
	"regexp"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// skill check: does every relative reference in one version point at a file that version has?
//
// **Only the references.** Root `SKILL.md`, the frontmatter's `name` and `description` are checked
// when the version is written — the server parses them with a real YAML reader
// (`SkillUploadValidator`), and this CLI deliberately does not parse frontmatter at all
// (`internal/api/client.go` says why). Repeating those checks here would need a YAML dependency or a
// second request to say what has already been said (v22 S9).
//
// **Two requests, one snapshot.** The file list carries the manifest digest; the archive is asked
// for that digest, not for "latest", so the bytes and the list cannot come from different edits.
// When the digest no longer resolves — somebody wrote the draft in between — the honest answer is to
// retry, not to pair one version's list with another version's bytes.

var (
	// `](path)` — a markdown link. The URL form is filtered below; this only finds candidates.
	checkMarkdownLink = regexp.MustCompile(`\]\(([^)\s]+)\)`)
	// `path` — an inline code span. Only spans that look like a path are checked (see looksLikePath),
	// because most of them are command names.
	checkCodeSpan = regexp.MustCompile("`([^`\n]+)`")
	// `skill read <地址> <路径>` and its `cat` sibling: the path is the last token.
	checkReadCommand = regexp.MustCompile("skill(?:master)? (?:read|cat) \\S+ (\\S+)")
)

type brokenReference struct {
	relpath string
	line    int
	target  string
}

func checkVersion(ctx context.Context, args []string) error {
	if len(args) != 1 || strings.HasPrefix(args[0], "-") {
		return usagef("要接一个地址，不收 flag。%s", usageAt("skill check"))
	}
	client, err := session(ctx)
	if err != nil {
		return err
	}
	// Author plane, and the same default a read uses: no pin means what the author list calls
	// current. A non-current version must be named — the archive has no "latest draft".
	target, err := resolve(ctx, client, args[0], true)
	if err != nil {
		return err
	}
	listed, err := client.AuthorFileList(ctx, target.address, target.pin)
	if err != nil {
		return checkRefusal(err)
	}
	pin := digestPinOf(listed.ETag)
	if pin == "" {
		return errors.New("服务端没有给出这一版的摘要，无法保证两次请求读的是同一版；重试，或升级 skillmaster。")
	}
	content, err := client.ReadArchiveInMemory(ctx, archivePath(target.address, pin, true), maxArchiveBytes)
	if err != nil {
		// The list named a digest a moment ago; if the archive cannot find it, the version moved
		// under the check. Pairing the old list with whatever is current now would report links that
		// do exist and miss ones that do not, so the answer is to look again.
		var server *api.ServerError
		if errors.As(err, &server) && server.Status == http.StatusNotFound {
			return errors.New("检查期间版本变过，请重试。")
		}
		return checkRefusal(err)
	}

	present := make(map[string]bool, len(listed.Files))
	for _, file := range listed.Files {
		present[file.Relpath] = true
	}

	var broken []brokenReference
	for _, file := range listed.Files {
		if file.IsBinary {
			continue
		}
		data, ok := content[file.Relpath]
		if !ok {
			continue
		}
		broken = append(broken, brokenReferencesIn(file.Relpath, string(data), present)...)
	}

	if len(broken) == 0 {
		return nil
	}
	for _, ref := range broken {
		fmt.Printf("%s:%d: %s 不在这一版里\n", ref.relpath, ref.line, ref.target)
	}
	// The findings are already on stdout, one line each; the exit status is the answer. An
	// `exitError` with no message is how every multi-file command says "look above".
	return &exitError{code: 1}
}

// checkRefusal says, for the two states check meets, what happened — the same words download uses,
// because it is the same rule refusing the same thing (a discarded version is not exportable).
func checkRefusal(err error) error {
	var server *api.ServerError
	if errors.As(err, &server) {
		if message, known := versionMessages[server.Code]; known {
			return errors.New(message)
		}
	}
	return err
}

// digestPinOf turns the list response's ETag into the pin the archive understands.
//
// The ETag is a quoted entity tag — `"sha256:<hex>"` — while the author plane's pin grammar wants
// the suffix form `sha256:<hex>`: quotes make it neither a version name nor a digest and the archive
// answers 404. A weak tag would carry a `W/` prefix in front of the quotes.
func digestPinOf(etag string) string {
	tag := strings.TrimSpace(etag)
	tag = strings.TrimPrefix(tag, "W/")
	tag = strings.Trim(tag, `"`)
	if !strings.HasPrefix(tag, "sha256:") {
		return ""
	}
	hex := strings.TrimPrefix(tag, "sha256:")
	if len(hex) != 64 || strings.ToLower(hex) != hex {
		return ""
	}
	return tag
}

// brokenReferencesIn reports the relative references in one file that no file in this version
// satisfies. Line numbers are 1-based, like every other line number this CLI prints.
func brokenReferencesIn(relpath, text string, present map[string]bool) []brokenReference {
	var broken []brokenReference
	seen := make(map[string]bool)

	note := func(offset int, target string) {
		key := fmt.Sprintf("%d\x00%s", offset, target)
		if seen[key] {
			return
		}
		seen[key] = true
		broken = append(broken, brokenReference{relpath: relpath, line: lineAt(text, offset), target: target})
	}

	for _, match := range checkMarkdownLink.FindAllStringSubmatchIndex(text, -1) {
		if target := referenceTarget(text[match[2]:match[3]]); target != "" && !hasFile(relpath, target, present) {
			note(match[2], target)
		}
	}
	for _, match := range checkCodeSpan.FindAllStringSubmatchIndex(text, -1) {
		span := text[match[2]:match[3]]
		if !looksLikePath(span) {
			continue
		}
		if target := referenceTarget(span); target != "" && !hasFile(relpath, target, present) {
			note(match[2], target)
		}
	}
	for _, match := range checkReadCommand.FindAllStringSubmatchIndex(text, -1) {
		if target := referenceTarget(text[match[2]:match[3]]); target != "" && !hasFile(relpath, target, present) {
			note(match[2], target)
		}
	}
	return broken
}

// referenceTarget normalises one candidate, or returns "" when it is not a path this command can
// judge: an anchor-only link, an absolute URL, a site-absolute path, or a placeholder with obvious
// holes in it. The anchor is dropped from the rest — `..#section` points at a file that must exist.
func referenceTarget(candidate string) string {
	target := strings.TrimSpace(candidate)
	if hash := strings.IndexByte(target, '#'); hash >= 0 {
		target = target[:hash]
	}
	switch {
	case target == "":
		return ""
	case strings.Contains(target, "://"), strings.HasPrefix(target, "mailto:"):
		return ""
	case strings.HasPrefix(target, "/"), strings.HasPrefix(target, "~"):
		return ""
	case strings.ContainsAny(target, "<>{}*"):
		return ""
	}
	return target
}

// looksLikePath keeps ordinary prose code spans out: `skillmaster login` is a command, not a file.
func looksLikePath(span string) bool {
	if strings.ContainsAny(span, " \t") {
		return false
	}
	if strings.Contains(span, "/") || strings.HasPrefix(span, ".") {
		return true
	}
	switch path.Ext(span) {
	case ".md", ".txt", ".json", ".yaml", ".yml", ".sh", ".py", ".js", ".ts", ".csv", ".toml":
		return true
	}
	return false
}

// hasFile resolves a reference against the file it appears in: relative references are relative to
// that file's directory, and a `/` in the middle is still relative to it.
func hasFile(relpath, target string, present map[string]bool) bool {
	resolved := path.Clean(path.Join(path.Dir(relpath), target))
	return present[resolved]
}

func lineAt(text string, offset int) int {
	if offset > len(text) {
		offset = len(text)
	}
	return strings.Count(text[:offset], "\n") + 1
}

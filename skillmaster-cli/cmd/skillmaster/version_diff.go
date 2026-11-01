// `version diff`: the patch, its limits, and the JSON shape.

package main

import (
	"context"
	"errors"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"strconv"
	"strings"
)

type diffOptions struct {
	from     string
	file     string
	patch    bool
	maxLines int
	json     bool
}

func versionDiff(ctx context.Context, args []string) error {
	jsonMode := hasJSONFlag(args)
	target, options, err := parseVersionDiffArgs(args)
	if err != nil {
		return versionErr(jsonMode, err, "")
	}
	// **`--patch --file` reads one file, in one request.** Without `--file`, `--patch` asks for the
	// ordinary diff: small comparisons carry every hunk, large ones the first batch. It must not
	// invent a path — a file that did not change is not part of the comparison at all (404), and
	// defaulting to SKILL.md is how a draft that only touched another file becomes an error.
	file := options.file

	diff, err := authenticated(ctx, func(client api.Client) (api.AuthorDiff, error) {
		return client.AuthorDiff(ctx, target.skill, target.pin, options.from, file, "")
	})
	if err != nil {
		var server *api.ServerError
		if file != "" && errors.As(err, &server) && server.Code == "file_not_found" {
			// The named file is not one of the changed files. That is the answer, not a failure:
			// say so the way the page does. **The code, not the status**: the author plane answers
			// 404 for two different things, and `skill_not_found` — no such skill, version or
			// baseline — is a failure that must not read as "nothing changed".
			if jsonMode {
				return writeJSON(emptyPatchJSON(target))
			}
			fmt.Printf("%s@%s\n（没有差异。）\n", target.skill, target.pin)
			return nil
		}
		return versionErr(jsonMode, err, "改动按两侧自己的可见性取最高档：已发布要「浏览」及以上，涉及草稿要「写入」，涉及已下架或已丢弃要「维护」。")
	}

	var lines []string
	remaining := 0
	if options.patch {
		lines, remaining = truncateLines(buildPatch(diff, file), options.maxLines)
	}
	truncated := diff.Truncated || remaining > 0

	if jsonMode {
		return writeJSON(diffJSONOf(diff, lines, truncated))
	}
	if options.patch {
		fmt.Printf("%s@%s\n", target.skill, target.pin)
		for _, line := range lines {
			fmt.Println(line)
		}
		if remaining > 0 {
			fmt.Printf("（还有 %d 行没有显示；--max-lines 调到更大可以看全。）\n", remaining)
		}
		if diff.Truncated {
			fmt.Println("（服务端截断了这份比较；加 --file 只看一个文件。）")
		}
		return nil
	}
	printDiffStat(target, diff, file)
	return nil
}

func parseVersionDiffArgs(args []string) (versionTarget, diffOptions, error) {
	options := diffOptions{maxLines: 2000}
	address := ""

	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--json":
			options.json = true
		case "--patch":
			options.patch = true
		case "--from":
			if i+1 >= len(args) {
				return versionTarget{}, options, usagef("--from 后面要跟一个版本（版本号或 sha256:…）。")
			}
			options.from = args[i+1]
			i++
		case "--file":
			if i+1 >= len(args) {
				return versionTarget{}, options, usagef("--file 后面要跟一个相对路径。")
			}
			options.file = args[i+1]
			i++
		case "--max-lines":
			if i+1 >= len(args) {
				return versionTarget{}, options, usagef("--max-lines 后面要跟一个正整数。")
			}
			value, parseErr := strconv.Atoi(args[i+1])
			if parseErr != nil || value < 1 {
				return versionTarget{}, options, usagef("--max-lines 要是正整数，收到 %q", args[i+1])
			}
			options.maxLines = value
			i++
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionTarget{}, options, usagef("不认识的参数 %s。%s", namedArgument(args[i]), usageAt("version"))
			}
			if address != "" {
				return versionTarget{}, options, usagef("version diff 只接一个地址。")
			}
			address = args[i]
		}
	}

	if address == "" {
		return versionTarget{}, options, usagef("%s", usageAt("version"))
	}
	target, err := parseVersionTarget(address)
	return target, options, err
}

func buildPatch(diff api.AuthorDiff, only string) []string {
	var lines []string
	for _, file := range diff.Files {
		if only != "" && file.Relpath != only {
			continue
		}
		if file.Hunks == nil {
			if file.Binary {
				lines = append(lines, fmt.Sprintf("（%s 是二进制文件，没有行级差异。）", file.Relpath))
			} else {
				lines = append(lines, fmt.Sprintf("（%s 没有展开：超过大小上限，或者不在这一批里。）", file.Relpath))
			}
			continue
		}
		before, after := "a/"+file.Relpath, "b/"+file.Relpath
		switch file.Status {
		case "added":
			before = "/dev/null"
		case "removed":
			after = "/dev/null"
		}
		lines = append(lines, "--- "+before, "+++ "+after)
		for _, hunk := range file.Hunks {
			lines = append(lines, hunk.Header)
			lines = append(lines, hunk.Lines...)
		}
	}
	if len(lines) == 0 {
		return []string{"没有差异。"}
	}
	return lines
}

func truncateLines(lines []string, max int) ([]string, int) {
	if max <= 0 || len(lines) <= max {
		return lines, 0
	}
	return lines[:max], len(lines) - max
}

type diffJSON struct {
	From      string         `json:"from"`
	To        string         `json:"to"`
	Added     int            `json:"added"`
	Removed   int            `json:"removed"`
	Files     []diffFileJSON `json:"files"`
	Truncated bool           `json:"truncated"`
	Next      *string        `json:"next"`
	Patch     string         `json:"patch"`
}

type diffFileJSON struct {
	Path    string `json:"path"`
	Status  string `json:"status"`
	Binary  bool   `json:"binary"`
	Added   *int   `json:"added"`
	Removed *int   `json:"removed"`
}

func diffJSONOf(diff api.AuthorDiff, patchLines []string, truncated bool) diffJSON {
	result := diffJSON{
		From:      diff.From,
		To:        diff.To,
		Truncated: truncated,
		Next:      diff.Next,
		Patch:     strings.Join(patchLines, "\n"),
		Files:     make([]diffFileJSON, 0, len(diff.Files)),
	}
	for _, file := range diff.Files {
		if file.Added != nil {
			result.Added += *file.Added
		}
		if file.Removed != nil {
			result.Removed += *file.Removed
		}
		result.Files = append(result.Files, diffFileJSON{
			Path:    file.Relpath,
			Status:  file.Status,
			Binary:  file.Binary,
			Added:   file.Added,
			Removed: file.Removed,
		})
	}
	return result
}

// emptyPatchJSON is the machine answer for "the named file did not change": no files, no patch.
func emptyPatchJSON(target versionTarget) diffJSON {
	return diffJSON{
		To:    target.pin,
		Patch: "",
		Files: []diffFileJSON{},
	}
}

func printDiffStat(target versionTarget, diff api.AuthorDiff, only string) {
	added, removed := 0, 0
	for _, file := range diff.Files {
		if file.Added != nil {
			added += *file.Added
		}
		if file.Removed != nil {
			removed += *file.Removed
		}
	}
	baseline := diff.From
	if baseline == "" {
		baseline = "（没有基线）"
	}
	fmt.Printf("%s@%s\n", target.skill, target.pin)
	fmt.Printf("对照：%s\n", baseline)
	fmt.Printf("+%d −%d　%d 个文件\n", added, removed, len(diff.Files))
	for _, file := range diff.Files {
		if only != "" && file.Relpath != only {
			continue
		}
		fmt.Printf("%s  %s  %s\n", diffStatusMark(file.Status), file.Relpath, numstat(file))
	}
	if diff.Truncated {
		fmt.Println("（服务端截断了这份比较；用 --file 只看一个文件。）")
	}
	if diff.Next != nil && *diff.Next != "" {
		fmt.Println("（还有更多文件没有取；--json 里的 next 是续取游标。）")
	}
}

func diffStatusMark(status string) string {
	switch status {
	case "added":
		return "A"
	case "removed":
		return "D"
	case "modified":
		return "M"
	default:
		return "?"
	}
}

func numstat(file api.DiffFile) string {
	if file.Added == nil || file.Removed == nil {
		if file.Binary {
			return "—"
		}
		return "—（没有展开）"
	}
	return fmt.Sprintf("+%d −%d", *file.Added, *file.Removed)
}

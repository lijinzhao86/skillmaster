// `version list`: the author's version list, its filters, and its output.

package main

import (
	"context"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"strconv"
	"strings"
)

var versionStatuses = []string{"live", "draft", "history", "revoked", "discarded"}

type listOptions struct {
	statuses []string
	query    string
	limit    int
	all      bool
	json     bool
}

func versionList(ctx context.Context, args []string) error {
	jsonMode := hasJSONFlag(args)
	address, options, err := parseVersionListArgs(args)
	if err != nil {
		return versionErr(jsonMode, err, "")
	}

	listed, more, err := readAuthorVersions(ctx, address, options)
	if err != nil {
		return versionErr(jsonMode, err, "作者面的版本列表需要「浏览」及以上。")
	}

	if options.json {
		// The cursor is the CLI's, not the caller's: `--all` already decided what to fetch. What the
		// caller does need is whether this is the whole list — a truncated answer that does not say
		// so is a wrong answer to "which versions exist".
		listed.Next = nil
		return writeJSON(struct {
			api.AuthorVersionList
			HasMore bool `json:"has_more"`
		}{listed, more})
	}
	printAuthorVersions(address, listed, more)
	return nil
}

// readAuthorVersions reads one page, or every page when `--all` was asked for.
//
// The pages are gathered before anything is printed, so a request that fails in the middle prints
// no half list. The cursor is only ever a local loop variable; it never reaches stdout or `--json`.
// The same `--status` / `--query` slice goes on every request because the server's cursor carries
// ordering, not the predicate.
func readAuthorVersions(ctx context.Context, address string, options listOptions) (api.AuthorVersionList, bool, error) {
	if !options.all {
		listed, err := authenticated(ctx, func(client api.Client) (api.AuthorVersionList, error) {
			return client.AuthorVersions(ctx, address, api.AuthorVersionQuery{
				Statuses: options.statuses,
				Query:    options.query,
				Limit:    options.limit,
			})
		})
		if err != nil {
			return api.AuthorVersionList{}, false, err
		}
		return listed, listed.Next != nil && *listed.Next != "", nil
	}

	var all api.AuthorVersionList
	cursor := ""
	seen := map[string]struct{}{}
	for {
		page, err := authenticated(ctx, func(client api.Client) (api.AuthorVersionList, error) {
			return client.AuthorVersions(ctx, address, api.AuthorVersionQuery{
				Statuses: options.statuses,
				Query:    options.query,
				Limit:    options.limit,
				Cursor:   cursor,
			})
		})
		if err != nil {
			return api.AuthorVersionList{}, false, err
		}
		all.Skill = page.Skill
		all.Versions = append(all.Versions, page.Versions...)
		all.Counts = page.Counts
		next := ""
		if page.Next != nil {
			next = *page.Next
		}
		if next == "" {
			all.Next = nil
			return all, false, nil
		}
		if _, repeated := seen[next]; repeated {
			return api.AuthorVersionList{}, false, fmt.Errorf(
				"服务端反复给出同一个游标，列表走不下去了（已取到 %d 条）", len(all.Versions))
		}
		seen[next] = struct{}{}
		cursor = next
	}
}

func parseVersionListArgs(args []string) (string, listOptions, error) {
	options := listOptions{limit: 50}
	address := ""

	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--json":
			options.json = true
		case "--status":
			if i+1 >= len(args) {
				return "", options, usagef("--status 后面要跟一个状态：%s", strings.Join(versionStatuses, " / "))
			}
			status := args[i+1]
			if !containsString(versionStatuses, status) {
				return "", options, usagef("--status 只能是 %s，收到 %q",
					strings.Join(versionStatuses, " / "), status)
			}
			options.statuses = append(options.statuses, status)
			i++
		case "--query":
			if i+1 >= len(args) {
				return "", options, usagef("--query 后面要跟一段文字。")
			}
			options.query = args[i+1]
			i++
		case "--limit":
			if i+1 >= len(args) {
				return "", options, usagef("--limit 后面要跟一个 1–200 的数。")
			}
			limit, parseErr := strconv.Atoi(args[i+1])
			if parseErr != nil || limit < 1 || limit > 200 {
				return "", options, usagef("--limit 要是 1–200 之间的整数，收到 %q", args[i+1])
			}
			options.limit = limit
			i++
		case "--all":
			options.all = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return "", options, unknownFlag("version list", args[i])
			}
			if address != "" {
				return "", options, usagef("version list 只接一个 skill 地址。")
			}
			address = args[i]
		}
	}

	if address == "" {
		return "", options, usagef("%s", usageAt("version"))
	}
	validated, err := parseSkillAddress(address)
	if err != nil {
		return "", options, err
	}
	return validated, options, nil
}

func printAuthorVersions(address string, listed api.AuthorVersionList, more bool) {
	for _, version := range listed.Versions {
		fmt.Printf("%s@%s\n", address, pinOf(version))
		fmt.Printf("    %s · %s · %s\n", statusLabel(version.Status), versionDisplayName(version),
			firstTen(version.SubmittedAt))
		if version.Note != nil && *version.Note != "" {
			fmt.Printf("    %s\n", *version.Note)
		}
	}
	fmt.Printf("（%d 行；可见 %s）\n", len(listed.Versions), countsLine(listed.Counts))
	if more {
		fmt.Println("（还有更多；用 --all 取全，或用 --status / --query 收窄。）")
	}
}

func countsLine(counts api.AuthorCounts) string {
	parts := []string{fmt.Sprintf("全部 %d", counts.All)}
	for _, part := range []struct {
		label string
		value int
	}{
		{"线上", counts.Live},
		{"草稿", counts.Draft},
		{"历史版本", counts.History},
		{"已下架", counts.Revoked},
		{"已丢弃", counts.Discarded},
	} {
		if part.value > 0 {
			parts = append(parts, fmt.Sprintf("%s %d", part.label, part.value))
		}
	}
	return strings.Join(parts, " · ")
}

func statusLabel(status string) string {
	switch status {
	case "live":
		return "线上"
	case "draft":
		return "草稿"
	case "history":
		return "历史版本"
	case "revoked":
		return "已下架"
	case "discarded":
		return "已丢弃"
	default:
		return status
	}
}

// pinOf is what follows the `@` when a row is printed. The server sends `pin` on every version;
// the two fallbacks are for a build reading a response from a server that has not filled it in yet,
// and they use the same precedence the address grammar does.
func pinOf(version api.AuthorVersion) string {
	if version.Pin != "" {
		return version.Pin
	}
	if version.Version != nil && *version.Version != "" {
		return *version.Version
	}
	return version.Digest
}

func versionDisplayName(version api.AuthorVersion) string {
	if version.Name != nil && *version.Name != "" {
		return *version.Name
	}
	if version.Version != nil && *version.Version != "" {
		return *version.Version
	}
	return "—"
}

func firstTen(value *string) string {
	if value == nil || *value == "" {
		return "—"
	}
	if len(*value) >= 10 {
		return (*value)[:10]
	}
	return *value
}

func stringOrDash(value *string) string {
	if value == nil || *value == "" {
		return "—"
	}
	return *value
}

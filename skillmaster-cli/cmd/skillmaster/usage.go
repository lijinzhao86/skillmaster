// `usage`: the personal interaction log, its stats, and a skill's owner view (v58).

package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

func usageVerb(ctx context.Context, args []string) error {
	stats := false
	users := false
	mine := false
	expand := false
	jsonMode := false
	page := 1
	limit := 50
	address := ""
	days := ""
	since := ""
	until := ""
	skill := ""
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--days":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--days 后面要跟一个 1–90 的数。%s",
					usageAt("usage")), "")
			}
			days = args[i+1]
			i++
		case "--since":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--since 后面要跟 YYYY-MM-DD。%s",
					usageAt("usage")), "")
			}
			since = args[i+1]
			i++
		case "--until":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--until 后面要跟 YYYY-MM-DD。%s",
					usageAt("usage")), "")
			}
			until = args[i+1]
			i++
		case "--skill":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--skill 后面要跟一段地址或关键词。%s",
					usageAt("usage")), "")
			}
			skill = args[i+1]
			i++
		case "--stats":
			stats = true
		case "--users":
			users = true
		case "--mine":
			mine = true
		case "--expand":
			expand = true
		case "--json":
			jsonMode = true
		case "--page":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--page 后面要跟页码。%s", usageAt("usage")), "")
			}
			parsed, err := strconv.Atoi(args[i+1])
			if err != nil || parsed < 1 {
				return versionErr(jsonMode, usagef("--page 要从 1 开始。%s", usageAt("usage")), "")
			}
			page = parsed
			i++
		case "--limit":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--limit 后面要跟条数。%s", usageAt("usage")), "")
			}
			parsed, err := strconv.Atoi(args[i+1])
			if err != nil || parsed < 1 || parsed > 100 {
				return versionErr(jsonMode, usagef("--limit 要在 1 到 100 之间。%s",
					usageAt("usage")), "")
			}
			limit = parsed
			i++
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionErr(jsonMode, unknownFlag("usage", args[i]), "")
			}
			if address != "" {
				return versionErr(jsonMode, usagef("%s", usageAt("usage")), "")
			}
			address = args[i]
		}
	}

	if stats && address != "" {
		return versionErr(jsonMode, usagef("--stats 看的是我的统计，不接地址。%s",
			usageAt("usage")), "")
	}
	if stats && (users || mine || expand) {
		return versionErr(jsonMode, usagef("--stats 不能和 --users / --mine / --expand 一起用。%s",
			usageAt("usage")), "")
	}
	if users && mine {
		return versionErr(jsonMode, usagef("--users 和 --mine 只能给一个。%s", usageAt("usage")), "")
	}
	if (users || mine) && address == "" {
		return versionErr(jsonMode, usagef("--users 与 --mine 都要跟一个地址。%s", usageAt("usage")), "")
	}
	if expand && address != "" {
		return versionErr(jsonMode, usagef("--expand 只用于我的明细，不接地址。%s",
			usageAt("usage")), "")
	}
	if address != "" && skill != "" {
		return versionErr(jsonMode, usagef("地址已经指定了一条 skill，不用再给 --skill。%s",
			usageAt("usage")), "")
	}
	window, err := usageWindowOf(days, since, until, skill, time.Now())
	if err != nil {
		return versionErr(jsonMode, err, "")
	}
	filter := api.UsageFilter{Since: window.Since, Until: window.Until, Skill: window.Skill}

	if address != "" {
		namespace, name, err := usageAddress(address)
		if err != nil {
			return versionErr(jsonMode, err, "")
		}
		if mine {
			if jsonMode {
				return usageRaw(ctx, func(client api.Client) ([]byte, error) {
					return client.SkillUsageRaw(ctx, namespace, name, "/mine", filter)
				}, usageLookupError)
			}
			result, err := authenticated(ctx, func(client api.Client) (api.UsageMine, error) {
				return client.SkillUsageMine(ctx, namespace, name, filter)
			})
			if err != nil {
				return versionErr(jsonMode, usageLookupError(err), "")
			}
			printMine(result, window.Label)
			return nil
		}
		if users {
			if jsonMode {
				return usageRaw(ctx, func(client api.Client) ([]byte, error) {
					return client.SkillUsageRaw(ctx, namespace, name, "/users", filter)
				}, usageLookupError)
			}
			result, err := authenticated(ctx, func(client api.Client) (api.UsageUsers, error) {
				return client.SkillUsageUsers(ctx, namespace, name, filter)
			})
			if err != nil {
				return versionErr(jsonMode, usageLookupError(err), "")
			}
			fmt.Printf("%s %s\n", namespace+"/"+name, window.Label)
			if result.Public {
				fmt.Println("公开 skill 仅提供聚合，不按协作者统计。区间数不是平台信号；累计调用与近 30 天账号分桶看 skmr skill show。")
				return nil
			}
			printUsers(result.Users)
			return nil
		}
		if jsonMode {
			return usageRaw(ctx, func(client api.Client) ([]byte, error) {
				return client.SkillUsageRaw(ctx, namespace, name, "", filter)
			}, usageLookupError)
		}
		result, err := authenticated(ctx, func(client api.Client) (api.SkillUsage, error) {
			return client.SkillUsage(ctx, namespace, name, filter)
		})
		if err != nil {
			return versionErr(jsonMode, usageLookupError(err), "")
		}
		printSkillUsage(result, window.Label)
		return nil
	}

	if stats {
		if jsonMode {
			return usageRaw(ctx, func(client api.Client) ([]byte, error) {
				return client.UsageStatsRaw(ctx, filter)
			}, nil)
		}
		result, err := authenticated(ctx, func(client api.Client) (api.UsageStats, error) {
			return client.UsageStats(ctx, filter)
		})
		if err != nil {
			return versionErr(jsonMode, err, "")
		}
		printUsageStats(result, window)
		return nil
	}

	if jsonMode {
		return usageRaw(ctx, func(client api.Client) ([]byte, error) {
			return client.UsageRaw(ctx, page, limit, filter)
		}, nil)
	}
	result, err := authenticated(ctx, func(client api.Client) (api.UsagePage, error) {
		return client.ListUsage(ctx, page, limit, filter)
	})
	if err != nil {
		return versionErr(jsonMode, err, "")
	}
	if len(result.Items) == 0 {
		// Two different sentences: the account has nothing at all in the retention window, or the
		// filters kept nothing. `has_any` is the server's answer to the first question; the CLI's
		// own flags cannot tell (a default 30-day window can be empty while older rows exist).
		if result.HasAny {
			fmt.Println(usageEmptyFiltered)
		} else {
			fmt.Println("暂无使用记录。搜索或调用 skill 后，记录会显示在这里。")
		}
		return nil
	}
	for _, row := range result.Items {
		printUsageRow(row, expand)
	}
	fmt.Printf("共 %d 条，第 %d 页 · %s%s\n", result.Total, result.Page, window.Label,
		usageSkillSuffix(window.Skill))
	return nil
}

func usageAddress(address string) (string, string, error) {
	base, _, _ := strings.Cut(address, "@")
	namespace, name, ok := strings.Cut(base, "/")
	if !ok || namespace == "" || name == "" {
		return "", "", usagef("地址要写成 <命名空间>/<名字>，收到 %q。%s", address, usageAt("usage"))
	}
	return namespace, name, nil
}

// usageRaw prints the server's answer exactly: `--json` is the wire shape, and a decode/encode
// round trip through the typed structs would drop fields the struct marks optional.
func usageRaw(ctx context.Context, fetch func(api.Client) ([]byte, error),
	wrap func(error) error) error {
	raw, err := authenticated(ctx, fetch)
	if err != nil {
		if wrap != nil {
			err = wrap(err)
		}
		return versionErr(true, err, "")
	}
	if !json.Valid(raw) {
		return versionErr(true, usagef("服务端没有返回一份 JSON。"), "")
	}
	fmt.Println(string(raw))
	return nil
}

func usageLookupError(err error) error {
	if errors.Is(err, api.ErrNotFound) {
		// `%w`, not a fresh error: the classifier reads the chain to turn a 404 into
		// `skill_not_found`; a message-only error would fall through to `network`.
		return fmt.Errorf("%w：没有这个 skill，或它不是你共享的。", api.ErrNotFound)
	}
	return err
}

func usageTime(at string) string {
	parsed, err := time.Parse(time.RFC3339, at)
	if err != nil {
		return at
	}
	return parsed.Local().Format("2006-01-02 15:04")
}

func usageClock(at string) string {
	parsed, err := time.Parse(time.RFC3339, at)
	if err != nil {
		return at
	}
	return parsed.Local().Format("15:04")
}

func printUsageRow(row api.UsageInteraction, expand bool) {
	if row.Kind == "direct" {
		fmt.Printf("%s  → %s@%s（直接调用）\n", usageTime(row.At), row.Skill, row.Version)
		return
	}
	total := row.Returned
	if row.Total != nil {
		total = int(*row.Total)
	}
	fmt.Printf("%s  “%s”  %d 个结果", usageTime(row.At), row.Query, total)
	if len(row.Invocations) == 0 {
		fmt.Printf("  未调用\n")
	} else if len(row.Invocations) == 1 {
		invocation := row.Invocations[0]
		fmt.Printf("  → %s@%s（第 %d 个结果）\n", invocation.Skill, invocation.Version,
			invocation.Rank)
	} else {
		fmt.Printf("  → 调用了 %d 个：", len(row.Invocations))
		for index, invocation := range row.Invocations {
			prefix := fmt.Sprintf("#%d", index+1)
			if index == 0 {
				fmt.Printf("%s %s@%s（%s）\n", prefix, invocation.Skill, invocation.Version,
					usageClock(invocation.At))
			} else {
				fmt.Printf("        %s %s@%s（%s）\n", prefix, invocation.Skill,
					invocation.Version, usageClock(invocation.At))
			}
		}
	}
	if !expand {
		return
	}
	if row.Total != nil && row.Returned < int(*row.Total) {
		fmt.Printf("        搜索返回 %d 个结果，这里只记录前 %d 个，按搜索时的顺序排列：\n",
			*row.Total, row.Returned)
	} else {
		fmt.Printf("        搜索返回 %d 个结果，按搜索时的顺序排列：\n", total)
	}
	invoked := map[int]api.UsageInvocation{}
	for _, invocation := range row.Invocations {
		invoked[invocation.Rank] = invocation
	}
	for _, result := range row.Results {
		line := fmt.Sprintf("        #%d %s", result.Rank, result.Skill)
		if invocation, ok := invoked[result.Rank]; ok {
			line += "  ← 已调用（" + usageClock(invocation.At) + "）"
		}
		fmt.Println(line)
	}
}

func printUsageStats(stats api.UsageStats, window usageWindow) {
	fmt.Printf("%s%s\n", window.Label, usageSkillSuffix(window.Skill))
	fmt.Printf("调用次数      %d\n", stats.Calls)
	fmt.Printf("搜索次数      %d\n", stats.Searches)
	fmt.Printf("使用过的 skill %d\n", stats.Skills)
	if stats.Latest == nil {
		fmt.Printf("最近使用      —\n")
	} else {
		fmt.Printf("最近使用      %s\n", usageTime(*stats.Latest))
	}
	if window.Skill != "" {
		fmt.Printf("\n%d 次搜索结果里出现了这条 skill，%d 次最终调用了它，%d 次未调用。\n",
			stats.Searches, stats.Converted, stats.Missed)
	} else {
		fmt.Printf("\n%d 次搜索中，%d 次最终调用了结果，%d 次未调用。\n",
			stats.Searches, stats.Converted, stats.Missed)
	}
	if len(stats.Top) == 0 {
		return
	}
	fmt.Printf("\n按调用次数（仅统计调用）：\n")
	for _, row := range stats.Top {
		fmt.Printf("  %-44s %5d 次  最近 %s\n", row.Skill, row.Calls, usageTime(row.Last))
	}
}

func stateText(state string) string {
	switch state {
	case "live":
		return "线上"
	case "history":
		return "历史版本"
	case "draft":
		return "草稿"
	case "revoked":
		return "已下架"
	case "discarded":
		return "已丢弃"
	default:
		return state
	}
}

func printSkillUsage(usage api.SkillUsage, label string) {
	fmt.Printf("%s %s\n", usage.Skill, label)
	fmt.Printf("调用次数 %s · 使用账号数 %s · 累计入库 %d", usage.Totals.Calls,
		usage.Totals.Accounts, usage.Totals.Joins)
	if usage.Totals.LastUsed != nil {
		fmt.Printf(" · 最近使用 %s", usageTime(*usage.Totals.LastUsed))
	}
	fmt.Println()
	if usage.Public {
		fmt.Println("\n公开 skill 仅提供聚合，不按协作者统计。区间数不是平台信号；累计调用与近 30 天账号分桶看 skmr skill show。")
		return
	}
	fmt.Println()
	printUsers(usage.Users)
}

func printUsers(users []api.UsageUser) {
	if len(users) == 0 {
		fmt.Println("还没有人用过这个 skill。")
		return
	}
	fmt.Printf("%-20s %-8s %8s %8s  %s\n", "使用者", "档位", "调用", "提交版本", "最近活跃")
	for _, user := range users {
		last := "—"
		if user.Last != nil {
			last = usageTime(*user.Last)
		}
		// The wire carries the rung key (`maintain`); the column is the interface's word (维护),
		// the same translation every other page action uses. The owner row is already 所有者.
		fmt.Printf("%-20s %-8s %8d %8d  %s\n", user.Who, rungLabel(user.Role), user.Invoke,
			user.Submitted, last)
	}
}

func printMine(mine api.UsageMine, label string) {
	fmt.Printf("%s 上的我的使用 · %s\n", mine.Skill, label)
	fmt.Printf("调用次数 %d · 使用过的版本 %d", mine.Calls, mine.Versions)
	if mine.Latest != nil {
		fmt.Printf(" · 最近使用 %s", usageTime(*mine.Latest))
	}
	fmt.Println()
	if len(mine.ByVersion) == 0 {
		return
	}
	fmt.Println()
	fmt.Printf("%-20s %-10s %8s  %s\n", "版本", "状态", "调用", "最近使用")
	for _, row := range mine.ByVersion {
		last := "—"
		if row.Last != nil {
			last = usageTime(*row.Last)
		}
		fmt.Printf("%-20s %-10s %8d  %s\n", "@"+row.Version, stateText(row.State), row.Calls,
			last)
	}
}

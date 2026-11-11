package main

import (
	"fmt"
	"strconv"
	"strings"
	"time"
)

/**
 * The usage window, converted where the timezone is known: in the CLI (v60).
 *
 * Local calendar days, both ends included. The two instants sent to the API are the start day's
 * local 00:00 and the day after the end day's local 00:00 (exclusive), so "today" naturally runs
 * up to now and daylight saving never has to be added by hand. The server knows only instants.
 */
type usageWindow struct {
	Since string
	Until string
	Skill string
	Label string
}

const (
	usageDefaultDays = 30
	usageMaxDays     = 90
	usageDayFormat   = "2006-01-02"
)

func usageWindowOf(days, since, until, skill string, now time.Time) (usageWindow, error) {
	skill = strings.TrimSpace(skill)
	if len([]rune(skill)) > 100 {
		return usageWindow{}, usagef("--skill 最多 100 个字符。%s", usageAt("usage"))
	}
	hasDays := days != ""
	hasSince := since != ""
	hasUntil := until != ""
	if hasDays && (hasSince || hasUntil) {
		return usageWindow{}, usagef("--days 与 --since/--until 只能给一种。%s", usageAt("usage"))
	}
	if hasSince != hasUntil {
		return usageWindow{}, usagef("--since 与 --until 要成对出现。%s", usageAt("usage"))
	}

	local := now.Local()
	today := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, local.Location())

	var start, end time.Time
	var label string
	switch {
	case hasDays:
		count, err := strconv.Atoi(days)
		if err != nil {
			return usageWindow{}, usagef("--days 后面要跟一个 1–90 的数。%s", usageAt("usage"))
		}
		if count < 1 || count > usageMaxDays {
			return usageWindow{}, usagef("--days 要在 1 到 %d 之间，收到 “%s”。%s",
				usageMaxDays, days, usageAt("usage"))
		}
		start = today.AddDate(0, 0, -(count - 1))
		end = today
		if count == 1 {
			label = fmt.Sprintf("今天（%s）", dayText(start))
		} else {
			label = fmt.Sprintf("近 %d 天（%s 至 %s）", count, dayText(start), dayText(end))
		}
	case hasSince:
		first, err := time.ParseInLocation(usageDayFormat, since, local.Location())
		if err != nil {
			return usageWindow{}, usagef("--since 要写成 YYYY-MM-DD，收到 “%s”。%s",
				since, usageAt("usage"))
		}
		last, err := time.ParseInLocation(usageDayFormat, until, local.Location())
		if err != nil {
			return usageWindow{}, usagef("--until 要写成 YYYY-MM-DD，收到 “%s”。%s",
				until, usageAt("usage"))
		}
		if first.After(last) {
			return usageWindow{}, usagef("开始日期不能晚于结束日期。%s", usageAt("usage"))
		}
		oldest := today.AddDate(0, 0, -(usageMaxDays - 1))
		if first.Before(oldest) {
			return usageWindow{}, usagef("只能看最近 %d 天：开始日期不能早于 %s。%s",
				usageMaxDays, dayText(oldest), usageAt("usage"))
		}
		if last.After(today) {
			return usageWindow{}, usagef("结束日期不能晚于今天（%s）。%s",
				dayText(today), usageAt("usage"))
		}
		start, end = first, last
		label = fmt.Sprintf("%s 至 %s", dayText(start), dayText(end))
	default:
		start = today.AddDate(0, 0, -(usageDefaultDays - 1))
		end = today
		label = fmt.Sprintf("近 %d 天（%s 至 %s）", usageDefaultDays,
			dayText(start), dayText(end))
	}

	return usageWindow{
		Since: start.UTC().Format(time.RFC3339),
		Until: end.AddDate(0, 0, 1).UTC().Format(time.RFC3339),
		Skill: skill,
		Label: label,
	}, nil
}

func dayText(value time.Time) string {
	return value.Format(usageDayFormat)
}

/** 「 · skill 地址含 “x”」, or nothing when no keyword is set. */
func usageSkillSuffix(skill string) string {
	if skill == "" {
		return ""
	}
	return fmt.Sprintf(" · skill 地址含 “%s”", skill)
}

const usageEmptyFiltered = "没有匹配的使用记录。去掉 --skill 或换个时间再看。"

package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
)

// The personal usage history (v58). The wire shapes are the ones the console reads; `--json`
// prints them unchanged.

type UsageResult struct {
	Skill string `json:"skill"`
	Rank  int    `json:"rank"`
}

type UsageInvocation struct {
	Skill   string `json:"skill"`
	Version string `json:"version"`
	At      string `json:"at"`
	Rank    int    `json:"rank"`
}

type UsageInteraction struct {
	Kind        string            `json:"kind"`
	ID          int64             `json:"id"`
	At          string            `json:"at"`
	Query       string            `json:"query,omitempty"`
	Total       *int64            `json:"total,omitempty"`
	Returned    int               `json:"returned,omitempty"`
	Results     []UsageResult     `json:"results,omitempty"`
	Invocations []UsageInvocation `json:"invocations,omitempty"`
	Skill       string            `json:"skill,omitempty"`
	Version     string            `json:"version,omitempty"`
}

type UsagePage struct {
	Items      []UsageInteraction `json:"items"`
	Total      int64              `json:"total"`
	Page       int                `json:"page"`
	PageSize   int                `json:"page_size"`
	WindowDays int                `json:"window_days"`
	Since      string             `json:"since"`
	Until      string             `json:"until"`
	Skill      *string            `json:"skill"`
	HasAny     bool               `json:"has_any"`
}

type UsageTop struct {
	Skill string `json:"skill"`
	Calls int64  `json:"calls"`
	Last  string `json:"last"`
}

type UsageStats struct {
	WindowDays int        `json:"window_days"`
	Since      string     `json:"since"`
	Until      string     `json:"until"`
	Skill      *string    `json:"skill"`
	Calls      int64      `json:"calls"`
	Searches   int64      `json:"searches"`
	Converted  int64      `json:"converted"`
	Missed     int64      `json:"missed"`
	Skills     int64      `json:"skills"`
	Latest     *string    `json:"latest"`
	Top        []UsageTop `json:"top"`
}

type UsageUser struct {
	Who       string  `json:"who"`
	Owner     bool    `json:"owner"`
	Role      string  `json:"role"`
	Invoke    int64   `json:"invoke"`
	Submitted int64   `json:"submitted"`
	Last      *string `json:"last"`
}

type UsageTotals struct {
	Calls    string  `json:"calls"`
	Accounts string  `json:"accounts"`
	Joins    int64   `json:"joins"`
	LastUsed *string `json:"last_used"`
}

type SkillUsage struct {
	Skill      string      `json:"skill"`
	WindowDays int         `json:"window_days"`
	Since      string      `json:"since"`
	Until      string      `json:"until"`
	Public     bool        `json:"public"`
	Totals     UsageTotals `json:"totals"`
	Users      []UsageUser `json:"users"`
}

type UsageUsers struct {
	WindowDays int         `json:"window_days"`
	Public     bool        `json:"public"`
	Users      []UsageUser `json:"users"`
}

type MineVersion struct {
	Version string  `json:"version"`
	State   string  `json:"state"`
	Calls   int64   `json:"calls"`
	Last    *string `json:"last"`
}

type UsageMine struct {
	Skill      string        `json:"skill"`
	WindowDays int           `json:"window_days"`
	Since      string        `json:"since"`
	Until      string        `json:"until"`
	Calls      int64         `json:"calls"`
	Versions   int           `json:"versions"`
	Latest     *string       `json:"latest"`
	ByVersion  []MineVersion `json:"by_version"`
}

// UsageFilter is the window every usage read accepts (v60). The client sends the two instants —
// local calendar days are converted where the timezone is known, which is here, not the server.
type UsageFilter struct {
	Since string
	Until string
	Skill string
}

func (f UsageFilter) values() url.Values {
	params := url.Values{}
	params.Set("since", f.Since)
	params.Set("until", f.Until)
	if f.Skill != "" {
		params.Set("skill", f.Skill)
	}
	return params
}

func (c Client) ListUsage(ctx context.Context, page, limit int, filter UsageFilter) (UsagePage, error) {
	params := filter.values()
	if page > 1 {
		params.Set("page", strconv.Itoa(page))
	}
	params.Set("limit", strconv.Itoa(limit))
	raw, err := c.request(ctx, http.MethodGet, APIPrefix+"/usage/interactions?"+params.Encode(), nil)
	if err != nil {
		return UsagePage{}, err
	}
	var result UsagePage
	if err := json.Unmarshal(raw, &result); err != nil {
		return UsagePage{}, fmt.Errorf("服务端没有返回一份使用记录：%w", err)
	}
	return result, nil
}

// UsageRaw is the same request as ListUsage, answered as the server wrote it.
//
// **`--json` promises the wire shape, not a shape this build happens to know.** Decoding into
// `UsageInteraction` and re-encoding drops every field the struct marks `omitempty` — a 0-result
// search loses `returned`, `results` and `invocations` — so the JSON mode uses this and prints the
// bytes unchanged.
func (c Client) UsageRaw(ctx context.Context, page, limit int, filter UsageFilter) ([]byte, error) {
	params := filter.values()
	if page > 1 {
		params.Set("page", strconv.Itoa(page))
	}
	params.Set("limit", strconv.Itoa(limit))
	return c.request(ctx, http.MethodGet, APIPrefix+"/usage/interactions?"+params.Encode(), nil)
}

func (c Client) UsageStatsRaw(ctx context.Context, filter UsageFilter) ([]byte, error) {
	return c.request(ctx, http.MethodGet, APIPrefix+"/usage/stats?"+filter.values().Encode(), nil)
}

func (c Client) SkillUsageRaw(ctx context.Context, namespace, name, suffix string,
	filter UsageFilter) ([]byte, error) {
	return c.request(ctx, http.MethodGet,
		usageSkillPath(namespace, name)+suffix+"?"+filter.values().Encode(), nil)
}

func (c Client) UsageStats(ctx context.Context, filter UsageFilter) (UsageStats, error) {
	raw, err := c.request(ctx, http.MethodGet, APIPrefix+"/usage/stats?"+filter.values().Encode(), nil)
	if err != nil {
		return UsageStats{}, err
	}
	var result UsageStats
	if err := json.Unmarshal(raw, &result); err != nil {
		return UsageStats{}, fmt.Errorf("服务端没有返回使用统计：%w", err)
	}
	return result, nil
}

func usageSkillPath(namespace, name string) string {
	return APIPrefix + "/usage/skills/" + url.PathEscape(namespace) + "/" + url.PathEscape(name)
}

func (c Client) SkillUsage(ctx context.Context, namespace, name string,
	filter UsageFilter) (SkillUsage, error) {
	raw, err := c.request(ctx, http.MethodGet,
		usageSkillPath(namespace, name)+"?"+filter.values().Encode(), nil)
	if err != nil {
		return SkillUsage{}, err
	}
	var result SkillUsage
	if err := json.Unmarshal(raw, &result); err != nil {
		return SkillUsage{}, fmt.Errorf("服务端没有返回这个 skill 的使用情况：%w", err)
	}
	return result, nil
}

func (c Client) SkillUsageUsers(ctx context.Context, namespace, name string,
	filter UsageFilter) (UsageUsers, error) {
	raw, err := c.request(ctx, http.MethodGet,
		usageSkillPath(namespace, name)+"/users?"+filter.values().Encode(), nil)
	if err != nil {
		return UsageUsers{}, err
	}
	var result UsageUsers
	if err := json.Unmarshal(raw, &result); err != nil {
		return UsageUsers{}, fmt.Errorf("服务端没有返回使用者表：%w", err)
	}
	return result, nil
}

func (c Client) SkillUsageMine(ctx context.Context, namespace, name string,
	filter UsageFilter) (UsageMine, error) {
	raw, err := c.request(ctx, http.MethodGet,
		usageSkillPath(namespace, name)+"/mine?"+filter.values().Encode(), nil)
	if err != nil {
		return UsageMine{}, err
	}
	var result UsageMine
	if err := json.Unmarshal(raw, &result); err != nil {
		return UsageMine{}, fmt.Errorf("服务端没有返回我的使用：%w", err)
	}
	return result, nil
}

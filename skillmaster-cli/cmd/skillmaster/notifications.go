// `notifications`: the feed, the two invitation answers, and the two request decisions (v45).

package main

import (
	"context"
	"fmt"
	"strconv"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

func notificationsVerb(ctx context.Context, args []string) error {
	// **Bare `skmr notifications` is the list**, because that is the question a person or an agent
	// arrives with: "is there anything for me". The declaration still calls the verb `list`; this
	// is the same default the console's bell opens with. A leading flag is the list's, not an
	// unknown subcommand: `skmr notifications --status all` is spelled that way in the PRD.
	if len(args) == 0 || strings.HasPrefix(args[0], "-") {
		return notificationsList(ctx, args)
	}
	switch args[0] {
	case "list":
		return notificationsList(ctx, args[1:])
	case "show":
		return notificationsShow(ctx, args[1:])
	case "read":
		return notificationsRead(ctx, args[1:])
	case "accept":
		return notificationsAccept(ctx, args[1:])
	case "decline":
		return notificationsDecline(ctx, args[1:])
	case "approve":
		return notificationsDecide(ctx, args[1:], true)
	case "deny":
		return notificationsDecide(ctx, args[1:], false)
	default:
		return unknownName("notifications", args[0], []string{
			"list", "show", "read", "accept", "decline", "approve", "deny"})
	}
}

func notificationsList(ctx context.Context, args []string) error {
	status := "attention"
	unread := false
	page := 0
	jsonMode := jsonModeIn(args, "--status", "--page")
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--status":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--status 后面要跟 attention、pending 或 all。%s",
					usageAt("notifications list")), "")
			}
			status = args[i+1]
			i++
		case "--unread":
			unread = true
		case "--page":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--page 后面要跟页码。%s",
					usageAt("notifications list")), "")
			}
			parsed, err := strconv.Atoi(args[i+1])
			if err != nil || parsed < 1 {
				return versionErr(jsonMode, usagef("--page 要从 1 开始。%s",
					usageAt("notifications list")), "")
			}
			page = parsed
			i++
		case "--json":
			jsonMode = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionErr(jsonMode, unknownFlag("notifications list", args[i]), "")
			}
			return versionErr(jsonMode, usagef("%s", usageAt("notifications list")), "")
		}
	}
	if status != "attention" && status != "pending" && status != "all" {
		return versionErr(jsonMode, usagef("--status 只能是 attention、pending 或 all，收到 %q。",
			status), "")
	}

	result, err := authenticated(ctx, func(client api.Client) (api.NotificationPage, error) {
		return client.ListNotifications(ctx, status, unread, page)
	})
	if err != nil {
		return versionErr(jsonMode, err, "")
	}
	if jsonMode {
		return writeJSON(result)
	}
	if len(result.Items) == 0 {
		fmt.Println("没有要看的通知。")
		return nil
	}
	for _, row := range result.Items {
		printNotificationLine(row)
	}
	fmt.Printf("共 %d 条，未读 %d 条。\n", result.Total, result.Unread)
	return nil
}

func notificationsShow(ctx context.Context, args []string) error {
	id, jsonMode, err := oneNotificationArgument("notifications show", args)
	if err != nil {
		return err
	}
	row, err := authenticated(ctx, func(client api.Client) (api.Notification, error) {
		return client.GetNotification(ctx, id)
	})
	if err != nil {
		return versionErr(jsonMode, err, "")
	}
	if jsonMode {
		return writeJSON(row)
	}
	printNotificationLine(row)
	if row.Title != "" {
		fmt.Printf("%s\n", row.Title)
	}
	if row.Body != "" {
		fmt.Printf("%s\n", row.Body)
	}
	return nil
}

func notificationsRead(ctx context.Context, args []string) error {
	all := false
	jsonMode := jsonModeIn(args)
	id := ""
	for _, arg := range args {
		switch {
		case arg == "--all":
			all = true
		case arg == "--json":
			jsonMode = true
		case strings.HasPrefix(arg, "-"):
			return versionErr(jsonMode, unknownFlag("notifications read", arg), "")
		default:
			if id != "" {
				return versionErr(jsonMode, usagef("%s", usageAt("notifications read")), "")
			}
			id = arg
		}
	}
	if all && id != "" {
		return versionErr(jsonMode, usagef("--all 和一条通知的 id 只能给一个。%s",
			usageAt("notifications read")), "")
	}
	if !all && id == "" {
		return versionErr(jsonMode, usagef("%s", usageAt("notifications read")), "")
	}

	if all {
		marked, err := authenticated(ctx, func(client api.Client) (int, error) {
			return client.MarkAllNotificationsRead(ctx)
		})
		if err != nil {
			return versionErr(jsonMode, err, "")
		}
		if jsonMode {
			return writeJSON(map[string]any{"marked": marked})
		}
		fmt.Printf("已把 %d 条标为已读。\n", marked)
		return nil
	}
	if _, err := authenticated(ctx, func(client api.Client) (struct{}, error) {
		return struct{}{}, client.MarkNotificationRead(ctx, id)
	}); err != nil {
		return versionErr(jsonMode, err, "")
	}
	if jsonMode {
		return writeJSON(map[string]any{"id": id, "read": true})
	}
	fmt.Println("已标为已读。")
	return nil
}

func notificationsAccept(ctx context.Context, args []string) error {
	id, jsonMode, err := oneNotificationArgument("notifications accept", args)
	if err != nil {
		return err
	}
	result, err := authenticated(ctx, func(client api.Client) (api.AcceptedInvitation, error) {
		return client.AcceptInvitation(ctx, id)
	})
	if err != nil {
		return versionErr(jsonMode, err, "")
	}
	if jsonMode {
		return writeJSON(result)
	}
	fmt.Printf("已接受 %s，拿到 %s 档（%s）。\n", result.Skill.Address(),
		rungLabel(result.Role), roleLine(result.Role))
	return nil
}

func notificationsDecline(ctx context.Context, args []string) error {
	id, jsonMode, err := oneNotificationArgument("notifications decline", args)
	if err != nil {
		return err
	}
	if _, err := authenticated(ctx, func(client api.Client) (struct{}, error) {
		return struct{}{}, client.DeclineInvitation(ctx, id)
	}); err != nil {
		return versionErr(jsonMode, err, "")
	}
	if jsonMode {
		return writeJSON(map[string]any{"id": id, "declined": true})
	}
	fmt.Println("已拒绝。没有拿到任何权限。")
	return nil
}

// notificationsDecide is approve and deny. **`--yes` is required** (ADR 0056 / ADR 0140): without
// it the command prints the consequence and the exact command, and exits non-zero. The read that
// supplies the requested rung is not the action — deciding is.
func notificationsDecide(ctx context.Context, args []string, approve bool) error {
	path := "notifications deny"
	if approve {
		path = "notifications approve"
	}
	id := ""
	role := ""
	yes := false
	jsonMode := jsonModeIn(args, "--role")
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--role":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--role 后面要跟档位。%s", usageAt(path)), "")
			}
			role = args[i+1]
			i++
		case "--yes":
			yes = true
		case "--json":
			jsonMode = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionErr(jsonMode, unknownFlag(path, args[i]), "")
			}
			if id != "" {
				return versionErr(jsonMode, usagef("%s", usageAt(path)), "")
			}
			id = args[i]
		}
	}
	if id == "" {
		return versionErr(jsonMode, usagef("%s", usageAt(path)), "")
	}
	if !approve && role != "" {
		return versionErr(jsonMode, usagef("拒绝不带 --role。%s", usageAt(path)), "")
	}

	if !yes {
		row, err := authenticated(ctx, func(client api.Client) (api.Notification, error) {
			return client.GetNotification(ctx, id)
		})
		if err != nil {
			return versionErr(jsonMode, err, "")
		}
		granted := role
		if granted == "" {
			granted = row.Role
		}
		var consequence, command string
		if approve {
			consequence = fmt.Sprintf("批准后 %s 会拿到 %s 档（%s），这条申请从待处理里消失。",
				row.Actor, rungLabel(granted), roleLine(granted))
			command = fmt.Sprintf("skmr notifications approve %s --role %s --yes", id, granted)
		} else {
			consequence = fmt.Sprintf("拒绝后 %s 不会拿到权限，这条申请从待处理里消失。", row.Actor)
			command = fmt.Sprintf("skmr notifications deny %s --yes", id)
		}
		if jsonMode {
			return &cliFailure{Code: "confirmation_required",
				Message: consequence + "确认后执行：" + command, JSON: true}
		}
		fmt.Println(consequence)
		fmt.Printf("确认后执行：\n%s\n", command)
		return &refusalError{message: "没有执行：批准或拒绝要加 --yes。"}
	}

	if approve {
		result, err := authenticated(ctx, func(client api.Client) (api.DecidedRequest, error) {
			return client.ApproveAccessRequest(ctx, id, role)
		})
		if err != nil {
			return versionErr(jsonMode, err, "")
		}
		if jsonMode {
			return writeJSON(result)
		}
		fmt.Printf("已批准：%s 拿到 %s 档（%s）。\n", result.Skill.Address(),
			rungLabel(result.Role), roleLine(result.Role))
		return nil
	}
	if _, err := authenticated(ctx, func(client api.Client) (struct{}, error) {
		return struct{}{}, client.DenyAccessRequest(ctx, id)
	}); err != nil {
		return versionErr(jsonMode, err, "")
	}
	if jsonMode {
		return writeJSON(map[string]any{"id": id, "approved": false})
	}
	fmt.Println("已拒绝这条申请；对方没有拿到权限。")
	return nil
}

func oneNotificationArgument(path string, args []string) (id string, jsonMode bool, err error) {
	jsonMode = jsonModeIn(args)
	for _, arg := range args {
		switch {
		case arg == "--json":
			jsonMode = true
		case strings.HasPrefix(arg, "-"):
			return "", jsonMode, versionErr(jsonMode, unknownFlag(path, arg), "")
		default:
			if id != "" {
				return "", jsonMode, versionErr(jsonMode, usagef("%s", usageAt(path)), "")
			}
			id = arg
		}
	}
	if id == "" {
		return "", jsonMode, versionErr(jsonMode, usagef("%s", usageAt(path)), "")
	}
	return id, jsonMode, nil
}

func printNotificationLine(row api.Notification) {
	marker := " "
	if !row.Read {
		marker = "●"
	}
	source := row.Actor
	if source == "" {
		source = "平台"
	}
	address := ""
	if row.Skill != nil {
		address = row.Skill.Address()
	}
	at := row.CreatedAt
	if row.ResolvedAt != "" {
		at = row.ResolvedAt
	}
	fmt.Printf("%s %s\t%s\t%s\t%s\t%s\t%s\n", marker, notificationStateLabel(row.State),
		notificationKindLabel(row.Kind), source, address, rungLabel(row.Role), at)
	if row.Title != "" {
		fmt.Printf("    %s\n", row.Title)
	}
	if row.Body != "" {
		fmt.Printf("    %s\n", row.Body)
	}
	for _, next := range notificationNextCommands(row) {
		fmt.Printf("    %s\n", next)
	}
}

// notificationNextCommands is the per-row half of the CLI's contract (v45 PRD §控制台对应):
// every actionable row hands over the exact command, and a row with no action prints none.
func notificationNextCommands(row api.Notification) []string {
	if row.Skill == nil {
		return nil
	}
	address := row.Skill.Address()
	if row.State == "pending" {
		switch row.Kind {
		case "share_invitation":
			return []string{
				"接受：skmr notifications accept " + row.ID,
				"拒绝：skmr notifications decline " + row.ID,
			}
		case "access_request":
			role := row.Role
			return []string{
				fmt.Sprintf("批准：skmr notifications approve %s --role %s --yes", row.ID, role),
				"拒绝：skmr notifications deny " + row.ID + " --yes",
				"去网页处理（可改档位）：skmr skill open " + address +
					" --tab settings --sub collaborators",
			}
		}
	}
	// A row that points at something this caller can no longer open gets no 「打开」 — the same rule
	// as the console's `viewable` (ADR 0139 §决定 7). History stays; the dead command does not.
	if !row.Viewable {
		return nil
	}
	switch row.State {
	case "accepted", "approved":
		return []string{
			"使用：skmr skill invoke " + address,
			"打开：skmr skill open " + address,
		}
	case "rejected":
		// The requester could already see the skill when they asked; giving them the page back is
		// what the console's 「查看」 does for the same state.
		return []string{"打开：skmr skill open " + address}
	}
	// declined / expired / revoked / withdrawn / info: history, no action.
	return nil
}

func notificationKindLabel(kind string) string {
	switch kind {
	case "share_invitation":
		return "共享邀请"
	case "access_request":
		return "权限申请"
	case "access_request_result":
		return "申请结果"
	case "announcement":
		return "公告"
	default:
		return kind
	}
}

func notificationStateLabel(state string) string {
	switch state {
	case "pending":
		return "待处理"
	case "accepted":
		return "已接受"
	case "declined":
		return "已拒绝"
	case "expired":
		return "已过期"
	case "revoked":
		return "已撤销"
	case "approved":
		return "已批准"
	case "rejected":
		return "未通过"
	case "withdrawn":
		return "已撤回"
	case "info":
		return "通知"
	default:
		return state
	}
}

package main

import (
	"context"
	"fmt"
	"slices"
	"strconv"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// The collaborator list and its withdrawal (v45: the list now has two groups, and withdrawing
// does both the invitation and the grant).
func shares(ctx context.Context, args []string) error {
	address := ""
	query := ""
	page := 0
	jsonMode := jsonModeIn(args, "--query", "--page")
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--query":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--query 后面要跟要过滤的文字。%s", usageAt("skill shares")), "")
			}
			query = args[i+1]
			i++
		case "--page":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--page 后面要跟页码。%s", usageAt("skill shares")), "")
			}
			parsed, err := strconv.Atoi(args[i+1])
			if err != nil || parsed < 1 {
				return versionErr(jsonMode, usagef("--page 要从 1 开始。%s", usageAt("skill shares")), "")
			}
			page = parsed
			i++
		case "--json":
			jsonMode = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionErr(jsonMode, unknownFlag("skill shares", args[i]), "")
			}
			if address != "" {
				return versionErr(jsonMode, usagef("%s", usageAt("skill shares")), "")
			}
			address = args[i]
		}
	}
	if address == "" || strings.Contains(address, "@") {
		return versionErr(jsonMode, usagef("%s", usageAt("skill shares")), "")
	}

	type sharesAnswer struct {
		Grants      []api.Grant      `json:"grants"`
		Invitations []api.Invitation `json:"invitations"`
		Total       int              `json:"total"`
		Page        int              `json:"page"`
		PageSize    int              `json:"page_size"`
	}
	result, err := authenticated(ctx, func(client api.Client) (sharesAnswer, error) {
		grants, err := client.ListGrants(ctx, address, query, page)
		if err != nil {
			return sharesAnswer{}, err
		}
		invitations, err := client.ListInvitations(ctx, address)
		if err != nil {
			return sharesAnswer{}, err
		}
		return sharesAnswer{grants.Grants, invitations.Items, grants.Total, grants.Page,
			grants.PageSize}, nil
	})
	if err != nil {
		return versionErr(jsonMode, err, "")
	}
	// Grants are filtered by the server; invitations come back whole, so the same query has to be
	// applied here or the two halves of one listing would answer different questions.
	if query != "" {
		needle := strings.ToLower(query)
		filtered := make([]api.Invitation, 0, len(result.Invitations))
		for _, invitation := range result.Invitations {
			if strings.Contains(strings.ToLower(invitation.Handle), needle) {
				filtered = append(filtered, invitation)
			}
		}
		result.Invitations = filtered
	}
	if jsonMode {
		return writeJSON(result)
	}

	// Three groups because they are three states of the same offer: already holds the rung, has not
	// answered yet, and never answered before the 30 days ran out. This is the inviter's view —
	// accepting and declining belong to the recipient, in `skmr notifications`.
	var pending, expired []api.Invitation
	for _, invitation := range result.Invitations {
		if invitation.State == "expired" {
			expired = append(expired, invitation)
		} else {
			pending = append(pending, invitation)
		}
	}
	if result.Total == 0 && len(result.Invitations) == 0 {
		fmt.Println("这个 skill 还没有共享给任何人，也没有待接受的邀请。")
		return nil
	}
	if result.Total > 0 {
		if len(result.Grants) == 0 {
			fmt.Println("这一页没有人。")
		} else {
			fmt.Println("已生效：")
			for _, grant := range result.Grants {
				fmt.Printf("  %s\t%s\t%s\n", grant.Handle, rungLabel(grant.Role), grant.CreatedAt)
			}
		}
	}
	if len(pending) > 0 {
		fmt.Println("待接受：")
		for _, invitation := range pending {
			fmt.Printf("  %s\t%s\t%s 到期\n", invitation.Handle, rungLabel(invitation.Role),
				invitation.ExpiresAt)
		}
	}
	if len(expired) > 0 {
		fmt.Println("已过期：")
		for _, invitation := range expired {
			fmt.Printf("  %s\t%s\t%s 到期\n", invitation.Handle, rungLabel(invitation.Role),
				invitation.ExpiresAt)
		}
	}
	fmt.Printf("第 %d 页，共 %d 人，待接受 %d 人，已过期 %d 人。\n", result.Page, result.Total,
		len(pending), len(expired))
	fmt.Println("接受或拒绝由对方在 skmr notifications 里做。")
	return nil
}

// changeGrantRole moves an existing collaborator down. Raising is a share, not a role change: it
// gives access that did not exist, so it goes through an invitation the person accepts.
func changeGrantRole(ctx context.Context, args []string) error {
	address := ""
	handle := ""
	role := ""
	jsonMode := jsonModeIn(args, "--user", "--role")
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--user":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--user 后面要跟用户名。%s", usageAt("skill role")), "")
			}
			handle = args[i+1]
			i++
		case "--role":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--role 后面要跟档位。%s", usageAt("skill role")), "")
			}
			role = args[i+1]
			i++
		case "--json":
			jsonMode = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionErr(jsonMode, unknownFlag("skill role", args[i]), "")
			}
			if address != "" {
				return versionErr(jsonMode, usagef("%s", usageAt("skill role")), "")
			}
			address = args[i]
		}
	}
	if address == "" || handle == "" || role == "" || strings.Contains(address, "@") {
		return versionErr(jsonMode, usagef("%s", usageAt("skill role")), "")
	}
	if !slices.Contains(api.Roles, role) {
		return versionErr(jsonMode, usagef("档位只能是 %s，收到 %q。%s",
			strings.Join(api.Roles, "、"), role, usageAt("skill role")), "")
	}

	changed, err := authenticated(ctx, func(client api.Client) (api.ChangedRole, error) {
		return client.ChangeGrantRole(ctx, address, handle, role)
	})
	if err != nil {
		return versionErr(jsonMode, err, "")
	}
	if jsonMode {
		return writeJSON(changed)
	}
	if !changed.Changed {
		fmt.Printf("%s 已经是「%s」。\n", handle, rungLabel(changed.Role))
		return nil
	}
	fmt.Printf("已把 %s 的档位改为「%s」。\n", handle, rungLabel(changed.Role))
	return nil
}

func unshare(ctx context.Context, args []string) error {
	address := ""
	handle := ""
	jsonMode := jsonModeIn(args, "--user")
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--user":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--user 后面要跟用户名。%s", usageAt("skill unshare")), "")
			}
			handle = args[i+1]
			i++
		case "--json":
			jsonMode = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionErr(jsonMode, unknownFlag("skill unshare", args[i]), "")
			}
			if address != "" {
				return versionErr(jsonMode, usagef("%s", usageAt("skill unshare")), "")
			}
			address = args[i]
		}
	}
	if address == "" || handle == "" {
		return versionErr(jsonMode, usagef("要给一个 skill 地址和 --user。%s", usageAt("skill unshare")), "")
	}
	// The grammar is refused here, before a credential is asked for: a typo is a usage error, and
	// reaching for the credential first would answer a mistyped address with a login prompt.
	if err := checkAddressSyntax("skill unshare", address); err != nil {
		return versionErr(jsonMode, err, "")
	}

	removed, err := authenticated(ctx, func(client api.Client) (revokedKinds, error) {
		grants, err := client.ListGrants(ctx, address, handle, 1)
		if err != nil {
			return revokedKinds{}, err
		}
		invitations, err := client.ListInvitations(ctx, address)
		if err != nil {
			return revokedKinds{}, err
		}
		hadGrant := false
		for _, grant := range grants.Grants {
			if grant.Handle == handle {
				hadGrant = true
			}
		}
		hadInvitation := false
		for _, invitation := range invitations.Items {
			if invitation.Handle == handle {
				hadInvitation = true
			}
		}
		// Both deletes are idempotent, so calling both is the whole operation: a person may have
		// an active grant, a waiting invitation, or a grant that was accepted after they asked to
		// withdraw it. The console keeps the two groups separate and revokes one row at a time;
		// this command is the combined action the PRD names.
		if err := client.RevokeInvitation(ctx, address, handle); err != nil {
			return revokedKinds{}, err
		}
		if err := client.RevokeShare(ctx, address, handle); err != nil {
			return revokedKinds{}, err
		}
		return revokedKinds{hadGrant, hadInvitation}, nil
	})
	if err != nil {
		return versionErr(jsonMode, err, "")
	}
	if jsonMode {
		return writeJSON(map[string]any{"handle": handle,
			"revoked": removed.grant, "withdrew_invitation": removed.invitation})
	}
	switch {
	case removed.grant && removed.invitation:
		fmt.Printf("已撤销 %s 在 %s 上的访问，并撤销了待接受的邀请。\n", handle, address)
	case removed.invitation:
		fmt.Printf("已撤销发给 %s 的邀请（对方还没接受）。\n", handle)
	case removed.grant:
		fmt.Printf("已撤销 %s 在 %s 上的访问。\n", handle, address)
	default:
		fmt.Printf("%s 在 %s 上本来就没有访问，也没有待接受的邀请。\n", handle, address)
	}
	return nil
}

// revokedKinds records which of the two rows the unshare found. The server's 204 does not say, and
// the two are different facts to the person who asked.
type revokedKinds struct {
	grant      bool
	invitation bool
}

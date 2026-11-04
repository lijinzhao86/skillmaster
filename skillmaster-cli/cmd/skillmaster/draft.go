// The draft commands: creating one without editing anything (v44).

package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

type draftCreateOptions struct {
	from   string
	name   string
	note   string
	newOne bool
}

// draftVerb is the `draft` group.
func draftVerb(ctx context.Context, args []string) error {
	if len(args) == 0 {
		return usagef("%s", usageAt("draft"))
	}
	switch args[0] {
	case "create":
		return draftCreate(ctx, args[1:])
	default:
		return unknownName("draft", args[0], childNames("draft"))
	}
}

// splitDraftCreateArgs reads the flags, including the hidden `--new`.
//
// `--new` is not a help flag (declarations_draft.go says why); it is still parsed here because the
// refusal names it as the way forward once the user has been asked.
func splitDraftCreateArgs(args []string) (draftCreateOptions, []string, error) {
	var opts draftCreateOptions
	var rest []string
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--from":
			if i+1 >= len(args) {
				return opts, nil, usagef("--from 后面要跟一个版本或版本码。%s", usageAt("draft create"))
			}
			opts.from = args[i+1]
			i++
		case "--name":
			if i+1 >= len(args) {
				return opts, nil, usagef("--name 后面要跟名称。%s", usageAt("draft create"))
			}
			opts.name = args[i+1]
			i++
		case "--note":
			if i+1 >= len(args) {
				return opts, nil, usagef("--note 后面要跟说明。%s", usageAt("draft create"))
			}
			opts.note = args[i+1]
			i++
		case "--new":
			opts.newOne = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return opts, nil, unknownFlag("draft create", args[i])
			}
			rest = append(rest, args[i])
		}
	}
	return opts, rest, nil
}

// draftCreate copies a base version into a new draft without changing a byte.
func draftCreate(ctx context.Context, args []string) error {
	opts, rest, err := splitDraftCreateArgs(args)
	if err != nil {
		return err
	}
	if len(rest) != 1 {
		return usagef("要且只要一个 skill 地址。%s", usageAt("draft create"))
	}
	address := rest[0]
	if _, _, named := strings.Cut(address, "@"); named {
		return usagef("建草稿给的是 skill 地址，不带 @版本。%s", usageAt("draft create"))
	}
	if err := checkAddressSyntax("draft create", address); err != nil {
		return err
	}
	if opts.name == "" {
		return usagef("新建草稿要一个名称（--name）。%s", usageAt("draft create"))
	}

	base := bareAddress(address)
	client, err := session(ctx)
	if err != nil {
		return err
	}
	me, err := client.Me(ctx)
	if err != nil {
		return err
	}
	if err := guardOpenDraft(ctx, client, base, me.User.Username,
		"skmr draft create "+base+" --name <名称> --new", opts.newOne); err != nil {
		return err
	}

	created, err := client.CreateDraft(ctx, base, api.CreateDraftRequest{
		Name: opts.name,
		Note: opts.note,
		From: opts.from,
	})
	if err != nil {
		return writeRefusal(err, base)
	}
	fmt.Fprintf(os.Stderr, "已建出草稿 %s@%s。\n下一步：skmr skill write %s@%s <路径> --from <本地文件>\n",
		base, created.Version.Code, base, created.Version.Code)
	return nil
}

// guardOpenDraft refuses a new draft when this account already has one under the skill.
//
// A 404 is "the skill does not exist yet", which is not a draft to continue: `submit` of a brand
// new skill must pass. The caller names the override line, because the two commands differ there.
func guardOpenDraft(ctx context.Context, client api.Client, address, username, override string,
	allow bool) error {
	mine, err := myOpenDrafts(ctx, client, address, username)
	if err != nil {
		var server *api.ServerError
		if errors.As(err, &server) && server.Status == http.StatusNotFound {
			return nil
		}
		return addressRefusal(err)
	}
	if len(mine) > 0 && !allow {
		return refuseNewDraft(address, override, mine)
	}
	return nil
}

// myOpenDrafts is the guard's read: the caller's own unpublished drafts of this skill.
//
// **Only `submitted_by` equal to this account counts.** A collaborator's draft is not "yours to
// continue", and blocking on it would stop two people from drafting the same skill at once.
func myOpenDrafts(ctx context.Context, client api.Client, address, username string) ([]api.AuthorVersion, error) {
	var mine []api.AuthorVersion
	cursor := ""
	for {
		list, err := client.AuthorVersions(ctx, address, api.AuthorVersionQuery{
			Statuses: []string{"draft"},
			Limit:    100,
			Cursor:   cursor,
		})
		if err != nil {
			return nil, err
		}
		for _, version := range list.Versions {
			if version.SubmittedBy != nil && *version.SubmittedBy == username {
				mine = append(mine, version)
			}
		}
		if list.Next == nil || *list.Next == "" {
			return mine, nil
		}
		cursor = *list.Next
	}
}

// refuseNewDraft is the one sentence both `draft create` and `submit` give when a draft exists.
//
// The last line names `--new` and says who has to agree to it. It is the only place the flag
// appears: help stays silent, so an agent does not learn it from a success path.
func refuseNewDraft(address, override string, mine []api.AuthorVersion) error {
	var b strings.Builder
	b.WriteString("你在这个 skill 下已有未上线的草稿：\n")
	for _, version := range mine {
		name := "未命名"
		if version.Name != nil && *version.Name != "" {
			name = *version.Name
		}
		at := ""
		if version.SubmittedAt != nil && len(*version.SubmittedAt) >= 10 {
			at = " · " + (*version.SubmittedAt)[:10]
		}
		fmt.Fprintf(&b, "  %s  %s%s\n", version.Code, name, at)
	}
	fmt.Fprintf(&b, "接着改这一份：\n  skmr skill write %s@%s <路径>\n", address, mine[0].Code)
	b.WriteString("确实要另开一份：先问用户；用户确认后加 --new 再跑一次\n")
	fmt.Fprintf(&b, "  %s\n", override)
	return &exitError{code: 1, err: errors.New(b.String())}
}

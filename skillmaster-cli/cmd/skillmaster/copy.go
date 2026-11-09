package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// sourceNotFound is `skill copy`'s own 404 sentence: the shared one says 「在你的 skill 里」, which
// names the wrong owner for a source that belongs to somebody else.
type sourceNotFound struct{}

func (sourceNotFound) Error() string {
	return "这个地址找不到；也可能它没有公开、也没有共享给你。"
}

// skillCopy is `skill copy` (v54, ADR 0173): one published version of somebody else's skill
// becomes a new private skill of mine, with a draft I can change.
//
// **The name is required and there is no second prompt.** A copy's name is the new skill's address;
// an interactive confirmation would make the command unusable from a script and would ask the
// person to type the one value the shell already has. A conflict is refused by the server and the
// next sentence says to pick another name — the CLI does not invent a suffix.
//
// **A draft pin is a usage error here, not a server 404.** The consumption plane cannot address a
// draft, and a row id sent as `@<code>` would come back as «这个地址找不到» — a sentence about the
// wrong thing. Checking the suffix against the same grammar the address parser uses says what is
// actually wrong before a request is made.
func skillCopy(ctx context.Context, args []string) error {
	address := ""
	newName := ""
	nameGiven := false
	jsonMode := jsonModeIn(args, "--name")
	var positional []string
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--name":
			if i+1 >= len(args) {
				return versionErr(jsonMode, usagef("--name 后面要跟复制后的新名字。%s",
					usageAt("skill copy")), "")
			}
			newName = args[i+1]
			nameGiven = true
			i++
		case "--json":
			jsonMode = true
		default:
			if strings.HasPrefix(args[i], "-") {
				return versionErr(jsonMode, unknownFlag("skill copy", args[i]), "")
			}
			positional = append(positional, args[i])
		}
	}
	if len(positional) != 1 || !nameGiven || strings.TrimSpace(newName) == "" {
		return versionErr(jsonMode, usagef("%s", usageAt("skill copy")), "")
	}
	address = positional[0]
	if err := checkAddressSyntax("skill copy", address); err != nil {
		return versionErr(jsonMode, err, "")
	}
	_, version, _ := strings.Cut(address, "@")
	if version != "" && !isConsumerPin(version) {
		return versionErr(jsonMode, usagef(
			"复制只接已发布的版本：@ 后面要跟版本号（如 1.2.3）或 sha256:… 摘要，草稿的版本码不行。%s",
			usageAt("skill copy")), "")
	}

	copied, err := authenticated(ctx, func(client api.Client) (api.CopiedSkill, error) {
		return client.CopySkill(ctx, address, newName, version)
	})
	if err != nil {
		var denied *api.Denial
		if errors.As(err, &denied) && denied.Code == api.Forbidden {
			return versionErr(jsonMode, err,
				"复制要对方的「浏览」及以上档位，而且不能是你自己的 skill；改自己的直接新建版本。")
		}
		// **The 404 is about the source, not about "your skills".** The shared sentence
		// (`addressRefusal`) says 「在你的 skill 里找不到」 — true for a version command reading
		// one's own library, wrong here: the source is somebody else's, and the same 404 also
		// covers "you cannot see it".
		var server *api.ServerError
		if errors.As(err, &server) && server.Status == http.StatusNotFound {
			return versionErr(jsonMode, sourceNotFound{}, "")
		}
		return versionErr(jsonMode, err, "")
	}
	if jsonMode {
		return writeJSON(copied)
	}
	fmt.Printf("已复制 %s@%s → %s/%s@%s（版本名称：%s）。\n",
		copied.Source.Address, copied.Source.Version,
		copied.Skill.Namespace, copied.Skill.Name, copied.Draft, copied.DraftName)
	fmt.Printf("接着改：skmr skill write %s/%s@%s <路径>。\n",
		copied.Skill.Namespace, copied.Skill.Name, copied.Draft)
	fmt.Printf("第一版上线后，%s/%s 就可以用 skmr skill show 看信息页。\n",
		copied.Skill.Namespace, copied.Skill.Name)
	return nil
}

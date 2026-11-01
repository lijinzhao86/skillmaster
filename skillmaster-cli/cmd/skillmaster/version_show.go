// `version show`: one version in the author plane.

package main

import (
	"context"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"strings"
)

func versionShow(ctx context.Context, args []string) error {
	jsonMode := hasJSONFlag(args)
	target, err := parseOneTarget(args, "--json")
	if err != nil {
		return versionErr(jsonMode, err, "")
	}

	envelope, err := authenticated(ctx, func(client api.Client) (api.AuthorVersionEnvelope, error) {
		return client.ReadAuthorVersion(ctx, target.skill, target.pin)
	})
	if err != nil {
		return versionErr(jsonMode, err, "作者面的单版读：已发布要「浏览」及以上；草稿要「写入」及以上；已下架与已丢弃要「维护」及以上。")
	}

	if jsonMode {
		return writeJSON(envelope)
	}
	printAuthorVersion(envelope)
	return nil
}

func printAuthorVersion(envelope api.AuthorVersionEnvelope) {
	version := envelope.Version
	address := version.Address
	if address == "" {
		address = envelope.Skill.Address
	}
	fmt.Printf("%s@%s\n", address, pinOf(version))
	fmt.Printf("  名称：%s\n", versionDisplayName(version))
	if version.Version == nil || *version.Version == "" {
		if version.Status == "draft" {
			fmt.Println("  版本号：—（草稿还没有版本号，上线时分配）")
		} else {
			fmt.Println("  版本号：—（这一行没有版本号，按摘要寻址）")
		}
	} else {
		fmt.Printf("  版本号：%s\n", *version.Version)
	}
	// The row id as a value of its own — the same fact the console's 「版本码」 row shows. For a
	// draft it is also the address above; for a published version the address is the number, so this
	// is the only place the terminal can see which row it is looking at.
	fmt.Printf("  版本码：%s\n", stringOrDash(&version.Code))
	fmt.Printf("  状态：%s\n", statusLabel(version.Status))
	// **类型不是版本号**：it only says how big this change is; the number is computed when somebody
	// publishes, from this and what is live at that moment (ADR 0091). A draft without one says so,
	// because "no type" is a state somebody has to act on before a publish can be computed.
	if version.Status == "draft" {
		if version.ReleaseType == nil || *version.ReleaseType == "" {
			fmt.Println("  版本类型：—（没选；上线时要选一档，或直接给版本号）")
		} else {
			fmt.Printf("  版本类型：%s\n", releaseTypeLabel(*version.ReleaseType))
		}
	} else if version.ReleaseType != nil && *version.ReleaseType != "" {
		fmt.Printf("  版本类型：%s（发布时用过的那一档）\n", releaseTypeLabel(*version.ReleaseType))
	}
	fmt.Printf("  提交人：%s · %s\n", stringOrDash(version.SubmittedBy),
		firstTen(version.SubmittedAt))
	fmt.Printf("  首次上线：%s\n", firstTen(version.FirstPublishedAt))
	fmt.Printf("  最近状态变更：%s\n", firstTen(version.StateAt))
	fmt.Printf("  说明：%s\n", stringOrDash(version.Note))
	fmt.Printf("  文件：%d 个 · %d 字节\n", version.Files, version.Bytes)
	fmt.Printf("  摘要：%s\n", version.Digest)
	if version.RevokedReason != nil && *version.RevokedReason != "" {
		fmt.Printf("  已下架原因：%s\n", *version.RevokedReason)
	}
}

// parseOneTarget reads `[--json] <address>` for the verbs that take one address and nothing else.
func parseOneTarget(args []string, optional ...string) (versionTarget, error) {
	address := ""
	for _, arg := range args {
		if arg == "--json" || containsString(optional, arg) {
			continue
		}
		if strings.HasPrefix(arg, "-") {
			return versionTarget{}, usagef("不认识的参数 %s。%s", namedArgument(arg), usageAt("version"))
		}
		if address != "" {
			return versionTarget{}, usagef("只接一个地址。%s", usageAt("version"))
		}
		address = arg
	}
	if address == "" {
		return versionTarget{}, usagef("%s", usageAt("version"))
	}
	return parseVersionTarget(address)
}

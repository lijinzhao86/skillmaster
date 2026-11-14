// The version list and the skill's own page.

package main

import (
	"context"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
	"strings"
)

// versions lists the versions of a skill that this caller may invoke, newest first.
//
// **The only command that prints a pin, and it exists for exactly that.** A bare address means
// "whatever is current", so without this a caller could never learn the name of a superseded version
// — and a superseded version is what somebody wants after a bad publish, because it is still published
// and still addressable. Everything else in this CLI works from names alone.
//
// The address carries the pin because choosing a version is the one thing that cannot be said without
// naming one. For the same reason the copy instruction is "whole": the line is what a caller pastes
// into `invoke`, and a line trimmed at the `@` would resolve to a different version.
func versions(ctx context.Context, args []string) error {
	if len(args) != 1 {
		return usagef("%s", usageAt("skill versions"))
	}
	address := args[0]
	if err := checkAddressSyntax("skill versions", address); err != nil {
		return err
	}
	if strings.Contains(address, "@") {
		return usagef("版本列表是针对整个 skill 的，地址后面不要带 @版本。%s", usageAt("skill versions"))
	}

	listed, err := authenticated(ctx, func(client api.Client) ([]api.VersionInfo, error) {
		return client.ReadVersions(ctx, address)
	})
	if err != nil {
		return err
	}

	// The same shape a listing uses: the value to copy alone on its line, what is known about it
	// indented underneath.
	for _, version := range listed {
		fmt.Println(address + "@" + api.VersionSuffix(version.Name, version.Digest))
		if line := versionLine(version); line != "" {
			fmt.Printf("    %s\n", line)
		}
		// v6's note is the caller's answer to "what changed"; it is version metadata on the
		// consumer plane, and the same two-space shape as a listing's reading.
		if version.Note != nil && *version.Note != "" {
			fmt.Printf("    %s\n", *version.Note)
		}
	}
	fmt.Printf("（共 %d 版。要钉住其中某一版：把上面某一行的地址整段抄进 skmr skill invoke。）\n",
		len(listed))
	return nil
}

// showSkill reads one skill's complete information: two reads composed into one card.
//
// **`metadata` and `tags` are two endpoints because they answer two questions**, and this command is
// the caller that wants both at once (TD §CLI 映射). The composition is here rather than a third
// server route because neither half changes shape for it — and because the two are the same
// visibility predicate, so a caller allowed to read one is allowed to read the other.
//
// **The address carries no version**, and a pin is refused rather than dropped: the fields below are
// facts about the skill, and the current version is one of them.
func showSkill(ctx context.Context, args []string) error {
	address := ""
	noLogin := false
	for _, arg := range args {
		switch arg {
		case "--no-login":
			noLogin = true
		default:
			if strings.HasPrefix(arg, "-") {
				return unknownFlag("skill show", arg)
			}
			if address != "" {
				return usagef("%s", usageAt("skill show"))
			}
			address = arg
		}
	}
	if address == "" {
		return usagef("%s", usageAt("skill show"))
	}
	if err := checkBareAddress(address); err != nil {
		return err
	}

	var meta api.Metadata
	var tags api.SkillTags
	myWhenToUse := ""
	_, err := authenticatedWith(ctx, noLogin, func(client api.Client) (struct{}, error) {
		var err error
		meta, err = client.Metadata(ctx, address, "")
		if err != nil {
			return struct{}{}, err
		}
		tags, err = client.Tags(ctx, address)
		if err != nil {
			return struct{}{}, err
		}
		// Only an entry in the caller's library has a personal sentence to read; asking outside
		// the library would be a 403 the command should not turn into an error. A dormant entry
		// is still in the library and its sentence is readable, even though the tags are not
		// (`reason=dormant` is how the tags answer names that state).
		if tags.MyTags.Readable || (tags.MyTags.Reason != nil && *tags.MyTags.Reason == "dormant") {
			myWhenToUse, err = client.MyWhenToUse(ctx, address)
			if err != nil {
				return struct{}{}, err
			}
		}
		return struct{}{}, nil
	})
	if err != nil {
		return v14Error(err)
	}
	printSkillShow(address, meta, tags, myWhenToUse)
	return nil
}

// showLicense prints the license block's detail. `skill show` gives only its name and status; this
// is the command that says what the name means. It reads the same /metadata block — no new endpoint
// (PRD §`skill license` 输出什么).
func showLicense(ctx context.Context, args []string) error {
	address := ""
	noLogin := false
	for _, arg := range args {
		switch arg {
		case "--no-login":
			noLogin = true
		default:
			if strings.HasPrefix(arg, "-") {
				return unknownFlag("skill license", arg)
			}
			if address != "" {
				return usagef("%s", usageAt("skill license"))
			}
			address = arg
		}
	}
	if address == "" {
		return usagef("%s", usageAt("skill license"))
	}
	if err := checkBareAddress(address); err != nil {
		return err
	}

	var meta api.Metadata
	_, err := authenticatedWith(ctx, noLogin, func(client api.Client) (struct{}, error) {
		var err error
		meta, err = client.Metadata(ctx, address, "")
		return struct{}{}, err
	})
	if err != nil {
		return v14Error(err)
	}
	printLicenseBlock(address, meta.License)
	return nil
}

// printSkillShow writes the card, in the order the information page fixes: address, description,
// when to use, my when to use, outcome, compatibility, attributes, then version, state, category,
// public tags, my tags. **An empty field is not printed** — a blank label says nothing and makes the
// ones that do carry a value harder to find.
func printSkillShow(address string, meta api.Metadata, tags api.SkillTags,
	myWhenToUse string) {
	fmt.Println(address)
	if meta.Description != "" {
		fmt.Printf("  %s\n", meta.Description)
	}
	if meta.WhenToUse != "" {
		fmt.Printf("  适用场景：%s\n", meta.WhenToUse)
	}
	if myWhenToUse != "" {
		fmt.Printf("  我的适用场景：%s\n", myWhenToUse)
	}
	// v66: the platform's reading sits after the author's and the caller's, before the facts below.
	// The two are different claims — 「预期结果」 is what using it produces, 「兼容性」 is the author's
	// runtime note — so neither replaces the other, and the author's wording keeps its own line.
	if meta.Outcome != "" {
		fmt.Printf("  预期结果：%s\n", meta.Outcome)
	}
	if meta.Compatibility != "" {
		// Renamed from 「运行环境」 in v66: the platform's attributes are what say which runtime a
		// skill needs, and this line is the author's own free-form note. 兼容性 says that without
		// claiming to be the vocabulary.
		fmt.Printf("  兼容性：%s\n", meta.Compatibility)
	}
	if names := shortLabelNames(meta.Labels); len(names) > 0 {
		// **Every short name, no +N**: the information page is where a reader decides, and this
		// command is its terminal half. The long form (`需要 Python`) stays in `skills labels`.
		fmt.Printf("  属性：%s\n", strings.Join(names, "、"))
	}
	if version := api.VersionSuffix(meta.Version.Name, meta.Version.Digest); version != "" {
		fmt.Printf("  版本：%s\n", version)
	}
	// v61: a ramp serves, so this read answered — and the date belongs on the page the person
	// opens to decide what to do about it. Offline skills never reach here (503).
	if meta.OfflinePendingUntil != "" {
		until := meta.OfflinePendingUntil
		if len(until) >= 10 {
			until = until[:10]
		}
		fmt.Printf("  状态：下线中 · %s\n", until)
	}
	if tags.Category != nil && tags.Category.Name != "" {
		fmt.Printf("  分类：%s\n", tags.Category.Name)
	}
	if names := tagNames(tags.PublicTags); len(names) > 0 {
		fmt.Printf("  公开标签：%s\n", strings.Join(names, "、"))
	}
	if meta.CopiedFrom != nil && meta.CopiedFrom.Address != "" {
		// v54: the source snapshot, before the external origin — the nearer fact comes first.
		fmt.Printf("  复制自：%s @%s\n", meta.CopiedFrom.Address, meta.CopiedFrom.Version)
	}
	printOrigin(meta)
	printLicenseLine(meta.License)
	printUsage(meta.Usage)
	if !tags.MyTags.Readable {
		// A reason line rather than silence: the group exists, and a reader who expected it needs to
		// know why it is not there (PRD #53).
		fmt.Printf("  我的标签：%s\n", myTagsUnreadable(tags.MyTags.Reason))
	} else if names := myTagNames(tags.MyTags.Tags); len(names) > 0 {
		fmt.Printf("  我的标签：%s\n", strings.Join(names, "、"))
	}
}

// versionLine is what sits under a version's address: whether a bare address would resolve to it, and
// when it first went live.
func versionLine(version api.VersionInfo) string {
	when := version.PublishedAt
	if len(when) >= 10 {
		// RFC3339 as the server writes it, and the date alone. Somebody choosing between versions is
		// answering "which one", and the clock time has never decided that.
		when = when[:10]
	}
	switch {
	case version.IsCurrent && when != "":
		return "当前 · " + when
	case version.IsCurrent:
		return "当前"
	default:
		return when
	}
}

// bareAddress is an address with its version suffix removed — the key a pin is remembered under.
//
// The suffix comes off because a pin is *about* the version: remembering `alice/pdf-tools@1.2.3 →
// 1.2.3` would work right up until the same skill is invoked at a different version, which is the one
// thing the key has to survive. What is left is what a person would have typed anyway.
func bareAddress(address string) string {
	held, _, _ := strings.Cut(address, "@")
	return held
}

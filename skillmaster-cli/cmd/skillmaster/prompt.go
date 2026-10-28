package main

import (
	"context"
	"fmt"
	"os"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/prompt"
)

// `prompt` — the rule block that goes into an agent's instruction file.
//
// **The block is a paragraph, and writing it belongs to the journey** (ADR 0110, 2026-10-28).
// Until this version the CLI also wrote the paragraph (`install`), located it again through two
// HTML comment markers, then updated or removed it (`status`, `update`, `uninstall`). The markers
// are gone: the paragraph lives in a file the person owns, and the platform skill writes it. All
// four retired. What is left is `print` — the text itself — which is what the journey and a person
// pasting by hand both need.
func promptVerb(ctx context.Context, args []string) error {
	_ = ctx
	if len(args) == 0 {
		return usagef("%s", usageAt("prompt"))
	}
	// Named rather than unknown, and checked before the dispatch switch: these four were in the
	// help page until v26, and a person who types one deserves to know why it stopped. They are not
	// children of `prompt` any more, so they stay out of the switch — the declared tree and the
	// dispatch have to agree (tree_test.go).
	if retiredPromptVerbs[args[0]] {
		return retiredPromptVerb(args[0])
	}
	switch args[0] {
	case "print":
		return promptPrint(args[1:])
	default:
		return unknownName("prompt", args[0], childNames("prompt"))
	}
}

// retiredPromptVerbs are the commands that wrote, located, updated or removed the copy.
var retiredPromptVerbs = map[string]bool{"install": true, "status": true, "update": true, "uninstall": true}

// retiredPromptVerb answers them. It says where the work happens now instead of only refusing:
// "no such command" for something the help page listed last week would be a worse answer.
func retiredPromptVerb(name string) error {
	// **On stderr, like every other refusal.** stdout carries results — for this group that means
	// exactly one thing, the block from `prompt print` — so `skillmaster prompt install > notes.md`
	// must not put prose in the file.
	fmt.Fprintf(os.Stderr, "skillmaster prompt %s 已经退役：写进 Agent 文件的那段说明现在由 skillmaster startup 负责，CLI 不再检查、写入或修改它。\n", name)
	fmt.Fprintln(os.Stderr, "要看那段说明的正文：skillmaster prompt print。")
	// **By position, not by a quoted label.** 「让 Agent 先来 SkillMaster 找技能」 was the name of
	// an item in the seven-item `--plan` list, which no longer exists (ADR 0111); the journey asks
	// two questions and this one is first. Borrowing the old label would point at a list nobody sees.
	fmt.Fprintln(os.Stderr, "要写或更新：重跑 skillmaster startup，在它列的清单里选第一项。")
	return &exitError{code: 2}
}

// promptPrint writes the block this binary carries, verbatim.
//
// **The manual escape hatch, and the one that works offline.** A person who would rather paste it
// themselves, or whose sandbox refuses the write, gets exactly the bytes the journey would write.
func promptPrint(args []string) error {
	if len(args) > 0 {
		return unknownFlag("prompt print", args[0])
	}
	os.Stdout.Write(prompt.Block())
	return nil
}

package main

import (
	"context"
	"fmt"
	"io"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// myWhenToUseVerb reads and writes the caller's own sentence on a skill (v59).
//
// **The author's sentence is not here.** `metadata.when_to_use` is skill content and travels with a
// version; a command without a version that changed it would bypass drafts and publishing. The help
// says so, and `skill show` prints the two lines apart.
func myWhenToUseVerb(ctx context.Context, args []string) (err error) {
	if len(args) == 0 {
		return usagef("%s", usageAt("skill my-when-to-use set"))
	}
	defer func() { err = retargetUsage(err, "skill my-when-to-use "+args[0]) }()
	switch args[0] {
	case "set":
		return myWhenToUseSet(ctx, args[1:])
	case "clear":
		return myWhenToUseClear(ctx, args[1:])
	default:
		return unknownName("skill my-when-to-use", args[0],
			childNames("skill my-when-to-use"))
	}
}

func myWhenToUseSet(ctx context.Context, args []string) error {
	address := ""
	text := ""
	given := false
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--text":
			value, err := flagValue(args, i, "--text", "skill my-when-to-use set")
			if err != nil {
				return err
			}
			if value == "-" {
				raw, err := io.ReadAll(stdin)
				if err != nil {
					return fmt.Errorf("从标准输入读我的适用场景失败：%w", err)
				}
				value = string(raw)
			}
			text = value
			given = true
			i++
		default:
			if strings.HasPrefix(args[i], "-") {
				return unknownFlag("skill my-when-to-use set", args[i])
			}
			if address != "" {
				return usagef("%s", usageAt("skill my-when-to-use set"))
			}
			address = args[i]
		}
	}
	if address == "" || !given {
		return usagef("%s", usageAt("skill my-when-to-use set"))
	}
	if err := checkBareAddress(address); err != nil {
		return err
	}
	if strings.TrimSpace(text) == "" {
		return usagef("要写一句你平时会怎么找它；要清除请用 skmr skill my-when-to-use clear %s。", address)
	}

	change, err := authenticated(ctx, func(client api.Client) (api.Change, error) {
		return client.SetMyWhenToUse(ctx, address, text)
	})
	if err != nil {
		return v14Error(err)
	}
	if !change.Changed {
		fmt.Printf("本来就是这句，没有需要再做的：%s。\n", address)
		return nil
	}
	fmt.Printf("已记下 %s 的我的适用场景。\n", address)
	return nil
}

func myWhenToUseClear(ctx context.Context, args []string) error {
	address := ""
	for _, arg := range args {
		switch {
		case strings.HasPrefix(arg, "-"):
			return unknownFlag("skill my-when-to-use clear", arg)
		case address != "":
			return usagef("%s", usageAt("skill my-when-to-use clear"))
		default:
			address = arg
		}
	}
	if address == "" {
		return usagef("%s", usageAt("skill my-when-to-use clear"))
	}
	if err := checkBareAddress(address); err != nil {
		return err
	}

	change, err := authenticated(ctx, func(client api.Client) (api.Change, error) {
		return client.ClearMyWhenToUse(ctx, address)
	})
	if err != nil {
		return v14Error(err)
	}
	if !change.Changed {
		fmt.Printf("本来就没有写，没有需要再做的：%s。\n", address)
		return nil
	}
	fmt.Printf("已清除 %s 的我的适用场景。\n", address)
	return nil
}

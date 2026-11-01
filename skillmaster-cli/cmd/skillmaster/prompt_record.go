package main

import (
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/prompt"
)

// promptRecordLine is the local, best-effort answer to "have I already configured another AI?".
// It is on stderr because stdout is the journey, and it never asks the server.
func promptRecordLine() (string, bool) {
	dir, err := config.Dir()
	if err != nil {
		return "", false
	}
	store, has, err := prompt.LoadStore(dir)
	if err != nil || !has || len(store.Files) == 0 {
		return "", false
	}
	seen := map[string]bool{}
	labels := make([]string, 0, len(store.Files))
	for _, record := range store.Files {
		label := record.Agent
		if label == "" {
			// A v25 record has no label. The path is an internal word the person should not be asked
			// to read; one "未命名" is the honest answer for every such record.
			label = "未命名"
		}
		if seen[label] {
			continue
		}
		seen[label] = true
		labels = append(labels, label)
	}
	if len(labels) == 0 {
		return "", false
	}
	// **No instruction to write it again.** Until v38 this line ended with "重跑 startup 选第一项
	// 再写一份", which told the agent to put the block back into a user's global memory file — the
	// exact step the version removes. The records still exist on machines that installed earlier,
	// so the line still names them; what it says about them is now the truth: they are not needed.
	return "这台电脑上写过这段说明的 Agent：" + strings.Join(labels, "、") +
		"。这一版起不再写这份说明，旧的那份留着不影响。", true
}

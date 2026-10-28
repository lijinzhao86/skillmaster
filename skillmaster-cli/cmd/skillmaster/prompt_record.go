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
	return "这台电脑上写过这段说明的 Agent：" + strings.Join(labels, "、") +
		"。如果你不是其中之一，重跑 startup 选第一项再写一份。", true
}

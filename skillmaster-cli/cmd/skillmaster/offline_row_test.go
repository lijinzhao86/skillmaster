package main

import (
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// 下线中 is a state the listing has to name, and the date is the only part of it a caller can act on
// ("when does it stop"). Split from main_test.go because that file is already at its size budget —
// the rule there is the same one this test follows: the bytes are the contract, so assert them.
func TestVerboseRowNamesTheRamp(t *testing.T) {
	for _, c := range []struct {
		name  string
		cards []api.Card
		want  string
	}{
		{
			name:  "a skill inside its ramp reads 下线中",
			cards: []api.Card{{Namespace: "demo", Name: "pdf-tools", Source: "created", State: "pending"}},
			want:  "demo/pdf-tools\n    来源：我创建的 · 状态：下线中\n",
		},
		{
			name: "the ramp's date follows 下线中",
			cards: []api.Card{{Namespace: "demo", Name: "pdf-tools", Source: "created",
				State: "pending", OfflinePendingUntil: "2026-12-11T00:00:00Z"}},
			want: "demo/pdf-tools\n    来源：我创建的 · 状态：下线中 · 2026-12-11\n",
		},
	} {
		t.Run(c.name, func(t *testing.T) {
			if got := captureStdout(t, func() { printCards(c.cards, true, true) }); got != c.want {
				t.Errorf("printed\n%q\nwant\n%q", got, c.want)
			}
		})
	}
}

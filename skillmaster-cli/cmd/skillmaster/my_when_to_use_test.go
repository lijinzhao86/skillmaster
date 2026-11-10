package main

import (
	"net/http"
	"strings"
	"testing"
)

func TestMyWhenToUseSetWritesAndReportsAChange(t *testing.T) {
	fake := newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"changed":true}`
	})
	out := mustRun(t, "skill", "my-when-to-use", "set", "dev/pdf-tools",
		"--text", "当我说循环审计时用它")
	if out != "已记下 dev/pdf-tools 的我的适用场景。\n" {
		t.Errorf("output = %q", out)
	}
	puts := wantsRequests(t, fake, http.MethodPut,
		"/api/v1/skills/dev/pdf-tools/my-when-to-use")
	if len(puts) != 1 || puts[0].Body !=
		`{"when_to_use":"当我说循环审计时用它"}` {
		t.Errorf("puts = %+v", puts)
	}
}

func TestMyWhenToUseSetSaysWhenNothingChanged(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"changed":false}`
	})
	out := mustRun(t, "skill", "my-when-to-use", "set", "dev/pdf-tools",
		"--text", "当我说循环审计时用它")
	if out != "本来就是这句，没有需要再做的：dev/pdf-tools。\n" {
		t.Errorf("output = %q", out)
	}
}

func TestMyWhenToUseSetReadsDashFromStdin(t *testing.T) {
	old := stdin
	stdin = strings.NewReader("当我说循环审计时用它\n")
	t.Cleanup(func() { stdin = old })

	fake := newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"changed":true}`
	})
	mustRun(t, "skill", "my-when-to-use", "set", "dev/pdf-tools", "--text", "-")
	puts := wantsRequests(t, fake, http.MethodPut,
		"/api/v1/skills/dev/pdf-tools/my-when-to-use")
	if len(puts) != 1 || !strings.Contains(puts[0].Body, "循环审计") {
		t.Errorf("puts = %+v", puts)
	}
}

func TestMyWhenToUseSetRefusesEmptyTextWithoutARequest(t *testing.T) {
	fake := newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"changed":true}`
	})
	_, stderr, status := runCaptured(t, "skill", "my-when-to-use", "set",
		"dev/pdf-tools", "--text", "  \n ")
	if status == 0 || !strings.Contains(stderr, "clear") {
		t.Errorf("status=%d stderr=%q", status, stderr)
	}
	if len(wantsRequests(t, fake, http.MethodPut,
		"/api/v1/skills/dev/pdf-tools/my-when-to-use")) != 0 {
		t.Error("an empty sentence reached the server")
	}
}

func TestMyWhenToUseRefusesAVersionPin(t *testing.T) {
	fake := newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"changed":true}`
	})
	_, stderr, status := runCaptured(t, "skill", "my-when-to-use", "set",
		"dev/pdf-tools@1.2.3", "--text", "循环审计")
	if status == 0 || !strings.Contains(stderr, "@版本") {
		t.Errorf("status=%d stderr=%q", status, stderr)
	}
	if len(fake.requests) != 0 {
		t.Errorf("a pinned address reached the server: %+v", fake.requests)
	}
}

func TestMyWhenToUseClearAndItsNoOp(t *testing.T) {
	for _, test := range []struct {
		name    string
		changed string
		want    string
	}{
		{name: "cleared", changed: "true", want: "已清除 dev/pdf-tools 的我的适用场景。\n"},
		{name: "none", changed: "false",
			want: "本来就没有写，没有需要再做的：dev/pdf-tools。\n"},
	} {
		t.Run(test.name, func(t *testing.T) {
			newFakeServer(t, func(recordedRequest) (int, string) {
				return http.StatusOK, `{"changed":` + test.changed + `}`
			})
			if out := mustRun(t, "skill", "my-when-to-use", "clear",
				"dev/pdf-tools"); out != test.want {
				t.Errorf("output = %q, want %q", out, test.want)
			}
		})
	}
}

func TestMyWhenToUseMapsTheServersRefusals(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusBadRequest, `{"error":{"code":"my_when_to_use_too_long",
			"message":"too long","details":[{"field":"when_to_use","issue":"too_long",
			"actual_code_points":201,"limit_code_points":200}]}}`
	})
	_, stderr, status := runCaptured(t, "skill", "my-when-to-use", "set",
		"dev/pdf-tools", "--text", strings.Repeat("汉", 201))
	if status == 0 || !strings.Contains(stderr, "200") {
		t.Errorf("status=%d stderr=%q", status, stderr)
	}
}

func TestSearchMarksAPersonalHitAndUsesItsSentence(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[{"id":"01","namespace":"dev","name":"pdf-tools",
			"description":"处理 PDF。","when_to_use":"作者的说法",
			"my_when_to_use":"当我说循环审计时用它","my_when_to_use_matched":true}],
			"next_cursor":null,"note":null}`
	})
	out := mustRun(t, "skills", "search", "循环审计")
	if !strings.Contains(out, "当我说循环审计时用它") {
		t.Errorf("the effective sentence was not used: %q", out)
	}
	if !strings.Contains(out, "（命中你写的适用场景）") {
		t.Errorf("the personal marker is missing: %q", out)
	}
	if strings.Contains(out, "作者的说法") {
		t.Errorf("the author's sentence was shown instead of mine: %q", out)
	}
}

func TestSearchZeroResultSuggestsOnePersonalSentence(t *testing.T) {
	newFakeServer(t, func(recordedRequest) (int, string) {
		return http.StatusOK, `{"skills":[],"next_cursor":null,"note":null}`
	})
	out := mustRun(t, "skills", "search", "没有这个词")
	if !strings.Contains(out, "skmr skill my-when-to-use set") {
		t.Errorf("the personal-sentence hint is missing: %q", out)
	}
}

package main

import (
	"context"
	"io"
	"net/http"
	"strings"
	"testing"
)

func TestChangingARoleCallsTheGrantResourceAndSaysSo(t *testing.T) {
	var body string
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPut || !strings.HasSuffix(r.URL.Path, "/grants/alice") {
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
			return
		}
		raw, _ := io.ReadAll(r.Body)
		body = string(raw)
		_, _ = w.Write([]byte(`{"handle":"alice","role":"write","changed":true}`))
	})

	out := captureStdout(t, func() {
		if err := changeGrantRole(context.Background(),
			[]string{"dev/pdf-tools", "--user", "alice", "--role", "write"}); err != nil {
			t.Fatalf("skill role: %v", err)
		}
	})
	if !strings.Contains(body, `"role":"write"`) {
		t.Errorf("body = %q", body)
	}
	if !strings.Contains(out, "已把 alice 的档位改为「写入」") {
		t.Errorf("output = %q", out)
	}
}

func TestChangingARoleToTheSameRungSaysNothingChanged(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"handle":"alice","role":"write","changed":false}`))
	})
	out := captureStdout(t, func() {
		if err := changeGrantRole(context.Background(),
			[]string{"dev/pdf-tools", "--user", "alice", "--role", "write"}); err != nil {
			t.Fatalf("skill role: %v", err)
		}
	})
	if !strings.Contains(out, "已经是「写入」") {
		t.Errorf("output = %q", out)
	}
}

func TestChangingARoleRefusesARungOutsideTheFive(t *testing.T) {
	if err := changeGrantRole(context.Background(),
		[]string{"dev/pdf-tools", "--user", "alice", "--role", "owner"}); err == nil {
		t.Fatal("skill role accepted `owner`")
	}
}

package main

import (
	"context"
	"net/http"
	"strings"
	"testing"
)

// The feed's per-row contract (v45): an actionable row hands over the exact command, and a
// confirmation-gated one does not execute without --yes.
func TestNotificationsListPrintsTheNextCommandForEachRow(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/notifications") {
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
		}
		_, _ = w.Write([]byte(`{"items":[
		 {"id":"n-1","kind":"share_invitation","state":"pending","actor":"yuhan",
		  "skill":{"namespace":"yuhan","name":"api-client"},"role":"write",
		  "created_at":"2026-11-04T09:12:00Z","read":false,"viewable":false},
		 {"id":"n-2","kind":"access_request","state":"pending","actor":"lin",
		  "skill":{"namespace":"dev","name":"pdf-tools"},"role":"write",
		  "created_at":"2026-11-03T18:40:00Z","read":true,"viewable":true},
		 {"id":"n-3","kind":"share_invitation","state":"accepted","actor":"yuhan",
		  "skill":{"namespace":"yuhan","name":"lost-access"},"role":"write",
		  "created_at":"2026-11-02T09:00:00Z","resolved_at":"2026-11-02T10:00:00Z",
		  "read":true,"viewable":false},
		 {"id":"n-4","kind":"share_invitation","state":"expired","actor":"yuhan",
		  "skill":{"namespace":"yuhan","name":"old-invite"},"role":"write",
		  "created_at":"2026-10-01T09:00:00Z","expires_at":"2026-10-31T09:00:00Z",
		  "read":true,"viewable":false}
		],"total":4,"unread":1}`))
	})

	out := captureStdout(t, func() {
		if err := notificationsList(context.Background(), nil); err != nil {
			t.Fatalf("notifications list: %v", err)
		}
	})
	for _, want := range []string{
		"skmr notifications accept n-1",
		"skmr notifications decline n-1",
		"skmr notifications approve n-2 --role write --yes",
		"skmr notifications deny n-2 --yes",
		"skmr skill open dev/pdf-tools --tab settings --sub collaborators",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("output does not carry %q:\n%s", want, out)
		}
	}
	if strings.Contains(out, "skmr skill open yuhan/lost-access") {
		t.Errorf("a row the caller can no longer open must not offer the command:\n%s", out)
	}
	if !strings.Contains(out, "2026-11-02T10:00:00Z") {
		t.Errorf("a settled row shows resolved_at, not created_at:\n%s", out)
	}
	if !strings.Contains(out, "2026-10-31T09:00:00Z") {
		t.Errorf("an invitation that ran out shows expires_at, not created_at:\n%s", out)
	}
}

func TestNotificationsWithALeadingFlagIsStillTheList(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/notifications") || r.URL.Query().Get("status") != "all" {
			t.Errorf("unexpected %s %s?%s", r.Method, r.URL.Path, r.URL.RawQuery)
		}
		_, _ = w.Write([]byte(`{"items":[],"total":0,"unread":0}`))
	})

	out := captureStdout(t, func() {
		if err := notificationsVerb(context.Background(), []string{"--status", "all"}); err != nil {
			t.Fatalf("notifications --status all: %v", err)
		}
	})
	if !strings.Contains(out, "没有要看的通知") {
		t.Errorf("output = %q", out)
	}
}

func TestBareNotificationsListsTheDefaultFeed(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/notifications") || r.URL.Query().Get("status") != "attention" {
			t.Errorf("unexpected %s %s?%s", r.Method, r.URL.Path, r.URL.RawQuery)
		}
		_, _ = w.Write([]byte(`{"items":[{"id":"n-1","kind":"share_invitation","state":"pending",
			"actor":"yuhan","skill":{"namespace":"yuhan","name":"api-client"},"role":"write",
			"created_at":"2026-11-04T09:12:00Z","read":false,"viewable":false}],
			"total":1,"unread":1}`))
	})

	out := captureStdout(t, func() {
		if err := notificationsVerb(context.Background(), nil); err != nil {
			t.Fatalf("bare notifications: %v", err)
		}
	})
	if !strings.Contains(out, "yuhan/api-client") || !strings.Contains(out, "skmr notifications accept n-1") {
		t.Errorf("output = %q", out)
	}
}

func TestApproveWithoutYesPrintsTheCommandAndDoesNotExecute(t *testing.T) {
	posted := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/notifications/n-2"):
			_, _ = w.Write([]byte(`{"id":"n-2","kind":"access_request","state":"pending",
				"actor":"lin","skill":{"namespace":"dev","name":"pdf-tools"},"role":"write",
				"created_at":"2026-11-03T18:40:00Z","read":true,"viewable":true}`))
		case r.Method == http.MethodPost:
			posted++
			w.WriteHeader(http.StatusOK)
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
		}
	})

	stdout, stderr, status := runCaptured(t, "notifications", "approve", "n-2")
	if status == 0 {
		t.Fatal("approve without --yes exited 0")
	}
	if posted != 0 {
		t.Fatalf("approve ran %d writes without --yes", posted)
	}
	if !strings.Contains(stdout, "批准后 lin 会拿到 写入 档") ||
		!strings.Contains(stdout, "skmr notifications approve n-2 --role write --yes") {
		t.Errorf("stdout does not say the consequence and the command:\n%s", stdout)
	}
	if !strings.Contains(stderr, "--yes") {
		t.Errorf("stderr does not explain the missing switch:\n%s", stderr)
	}
}

func TestAcceptInvitationPrintsTheRung(t *testing.T) {
	var accepted string
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || !strings.HasSuffix(r.URL.Path, "/notifications/n-1/accept") {
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
			return
		}
		accepted = r.URL.Path
		_, _ = w.Write([]byte(`{"skill":{"namespace":"yuhan","name":"api-client"},
			"role":"write"}`))
	})

	out := captureStdout(t, func() {
		if err := notificationsAccept(context.Background(), []string{"n-1"}); err != nil {
			t.Fatalf("accept: %v", err)
		}
	})
	if accepted == "" {
		t.Fatal("accept did not reach the server")
	}
	if !strings.Contains(out, "yuhan/api-client") || !strings.Contains(out, "写入") {
		t.Errorf("output = %q", out)
	}
}

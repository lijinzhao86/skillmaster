package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
)

// v54's `skill copy` (ADR 0173): the request it builds, the usage errors it refuses before one,
// and the sentences a refusal and a success print.

func TestCopyBuildsTheAuthorRequestAndPrintsTheNewAddress(t *testing.T) {
	var seen map[string]string
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/v1/author/skills/dev/pdf-tools/copy" {
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
		}
		body, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(body, &seen)
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"skill":{"namespace":"me","name":"pdf-tools-copy"},` +
			`"draft":"K3M9QX2P","draft_name":"复制自 dev/pdf-tools","version":null,` +
			`"source":{"address":"dev/pdf-tools","version":"1.2.2"}}`))
	})

	out := captureStdout(t, func() {
		if err := skillCopy(context.Background(),
			[]string{"dev/pdf-tools@1.2.2", "--name", "pdf-tools-copy"}); err != nil {
			t.Fatalf("copy: %v", err)
		}
	})
	if seen["name"] != "pdf-tools-copy" || seen["version"] != "1.2.2" {
		t.Fatalf("request body = %#v", seen)
	}
	if !strings.Contains(out, "me/pdf-tools-copy@K3M9QX2P") ||
		!strings.Contains(out, "dev/pdf-tools@1.2.2") {
		t.Errorf("output = %q", out)
	}

	jsonOut := captureStdout(t, func() {
		if err := skillCopy(context.Background(),
			[]string{"dev/pdf-tools@1.2.2", "--name", "pdf-tools-copy", "--json"}); err != nil {
			t.Fatalf("copy --json: %v", err)
		}
	})
	var copied map[string]any
	if err := json.Unmarshal([]byte(jsonOut), &copied); err != nil {
		t.Fatalf("--json output = %q (%v)", jsonOut, err)
	}
	for _, field := range []string{"skill", "draft", "draft_name", "version", "source"} {
		if _, ok := copied[field]; !ok {
			t.Errorf("--json is missing %q: %#v", field, copied)
		}
	}
}

func TestCopyRefusesBadUsageBeforeARequest(t *testing.T) {
	requests := 0
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		requests++
		http.NotFound(w, r)
	})
	for _, args := range [][]string{
		{"dev/pdf-tools"},                         // --name is required
		{"dev/pdf-tools", "--name", ""},           // and cannot be empty
		{"dev/pdf-tools", "--name", "x", "extra"}, // one address
		{"dev/pdf-tools", "--name"},               // the flag has no value
		{"dev/pdf-tools@A1B2C3D4", "--name", "x"}, // a draft pin is not a version
		{"dev/pdf-tools@draft:01JKMNPQRSTVWXYZ01234", "--name", "x"},
		{"pdf-tools", "--name", "x"}, // the address needs a namespace
	} {
		if err := skillCopy(context.Background(), args); err == nil {
			t.Errorf("%v was accepted", args)
		}
	}
	if requests != 0 {
		t.Errorf("usage errors sent %d requests", requests)
	}
}

func TestCopyLeavesTheVersionOutWhenTheAddressDoesNotPinOne(t *testing.T) {
	var seen map[string]string
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(body, &seen)
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"skill":{"namespace":"me","name":"x"},"draft":"d1",` +
			`"draft_name":"复制自 dev/x","version":null,` +
			`"source":{"address":"dev/x","version":"1.0.0"}}`))
	})
	if err := skillCopy(context.Background(), []string{"dev/x", "--name", "x"}); err != nil {
		t.Fatalf("copy: %v", err)
	}
	if _, present := seen["version"]; present {
		t.Errorf("an unpinned address sent version=%q; omission means the live version", seen["version"])
	}
}

func TestCopySaysTheNextStepForATakenName(t *testing.T) {
	for code, want := range map[string]string{
		"skill_already_exists": "这个名字已经被一个 skill 占着。",
	} {
		t.Run(code, func(t *testing.T) {
			pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusConflict)
				_, _ = w.Write([]byte(`{"error":{"code":"` + code + `","message":"refused"}}`))
			})
			_, stderr, status := runCaptured(t, "skill", "copy", "dev/x", "--name", "x")
			if status != 1 {
				t.Fatalf("status = %d, want 1 (stderr %q)", status, stderr)
			}
			if !strings.Contains(stderr, want) {
				t.Errorf("stderr = %q, want %q", stderr, want)
			}
		})
	}
}

func TestSkillShowPrintsWhereTheCopyCameFrom(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/api/v1/skills/you/x/metadata":
			_, _ = w.Write([]byte(`{"namespace":{"slug":"you"},"name":"x","description":"d",` +
				`"version":{"name":null,"digest":"sha256:abc"},` +
				`"copied_from":{"address":"dev/source","version":"1.2.3"}}`))
		case "/api/v1/skills/you/x/tags":
			_, _ = w.Write([]byte(`{"category":null,"public_tags":[],` +
				`"my_tags":{"readable":false,"reason":"not_in_library","tags":[]}}`))
		default:
			http.NotFound(w, r)
		}
	})
	stdout, stderr, status := runCaptured(t, "skill", "show", "you/x")
	if status != 0 {
		t.Fatalf("status = %d (stderr %q)", status, stderr)
	}
	if !strings.Contains(stdout, "复制自：dev/source @1.2.3") {
		t.Errorf("stdout = %q", stdout)
	}
}

func TestCopySaysWhatToDoWhenTheSourceCannotBeRenamed(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(`{"error":{"code":"name_not_rewritable","message":"cannot rewrite"}}`))
	})
	_, stderr, status := runCaptured(t, "skill", "copy", "dev/x", "--name", "x-copy")
	if status != 1 {
		t.Fatalf("status = %d, want 1 (stderr %q)", status, stderr)
	}
	if !strings.Contains(stderr, "用它的原名复制一次") {
		t.Errorf("stderr = %q", stderr)
	}
}

// The 422's `details` names `invalid_name` as the *reason*, and that spelling is also a tag-name
// issue. The tag rule (1–24 个字) must not answer a copy question: the top-level code is what
// classifies this refusal, and `issueMessages` only speaks for `invalid_request` / `invalid_upload`.
func TestCopyInvalidNameUsesTheCopyRuleNotTheTagRule(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnprocessableEntity)
		_, _ = w.Write([]byte(`{"error":{"code":"invalid_name","message":"bad name",` +
			`"details":[{"field":"name","issue":"invalid_name"}]}}`))
	})
	_, stderr, status := runCaptured(t, "skill", "copy", "dev/x", "--name", "Bad_Name")
	if status != 1 {
		t.Fatalf("status = %d, want 1 (stderr %q)", status, stderr)
	}
	if !strings.Contains(stderr, "复制后的名字要是") {
		t.Errorf("stderr = %q, want the copy naming rule", stderr)
	}
	if strings.Contains(stderr, "1–24") {
		t.Errorf("stderr = %q, the tag rule answered a copy refusal", stderr)
	}
}

// A copy's 404 is about the source. The shared sentence says 「在你的 skill 里」, which is the wrong
// owner: the source belongs to somebody else, and the same answer also means "you cannot see it".
func TestCopySaysTheMissingSourceWithoutClaimingItIsYours(t *testing.T) {
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"error":{"code":"skill_not_found","message":"no such skill"}}`))
	})
	_, stderr, status := runCaptured(t, "skill", "copy", "dev/x", "--name", "x-copy")
	if status != 1 {
		t.Fatalf("status = %d, want 1 (stderr %q)", status, stderr)
	}
	if !strings.Contains(stderr, "这个地址找不到") {
		t.Errorf("stderr = %q", stderr)
	}
	if strings.Contains(stderr, "在你的 skill 里") {
		t.Errorf("stderr = %q, the shared 404 claims the source is the caller's", stderr)
	}
}

package main

import (
	"encoding/json"
	"net/http"
	"os"
	"sort"
	"strings"
	"testing"
)

// **The before/after harness** (PRD 验收 #17, test-plan T19). `testdata/golden/index.json` was
// recorded by running the pre-v16 binary built from `main` over the product's own documented
// examples; this test replays the same argvs through `run` and compares every byte of stdout and
// stderr plus the exit status.
//
// Every difference has to be listed in `expected-changes.json`, and every listed difference has to
// still happen — so the file cannot become a place where a regression hides.
//
// Four commands are not replayed, because their subject is this machine rather than the protocol:
// `login` (opens a browser and waits), `logout` (deletes a credential), `startup` (fetches a skill), and
// `skill download` (writes a directory). Each has its own tests. They are skipped by path, not by
// trusting the recording: a case that would write something must not run here at all.
//
// The server answers with one 404 envelope, except for `version list --json`, which gets a real
// list: that one row pins a **successful `--json` object**, byte for byte (验收 #17). Everything
// else here pins argument parsing, error rendering and exit statuses; the remaining success output
// is pinned by the command tests themselves, which assert exact strings.

type goldenRecord struct {
	Argv   []string `json:"argv"`
	Status int      `json:"status"`
	Stdout string   `json:"stdout"`
	Stderr string   `json:"stderr"`
}

type expectedChange struct {
	Case   string `json:"case"`
	Field  string `json:"field"`
	Reason string `json:"reason"`
}

func readJSONFile(t *testing.T, path string, into any) {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	if err := json.Unmarshal(data, into); err != nil {
		t.Fatalf("parse %s: %v", path, err)
	}
}

func TestGoldenOutputMatchesThePreV16Binary(t *testing.T) {
	var index map[string]goldenRecord
	readJSONFile(t, "testdata/golden/index.json", &index)
	var responses struct {
		Default struct {
			Status int    `json:"status"`
			Body   string `json:"body"`
		} `json:"default"`
		ByPath map[string]struct {
			Status int    `json:"status"`
			Body   string `json:"body"`
		} `json:"by_path"`
	}
	readJSONFile(t, "testdata/golden/response.json", &responses)
	var listed struct {
		Changes []expectedChange `json:"changes"`
	}
	readJSONFile(t, "testdata/golden/expected-changes.json", &listed)

	allowed := map[string]bool{}
	for _, change := range listed.Changes {
		if strings.TrimSpace(change.Reason) == "" {
			t.Errorf("the change %s/%s has no reason", change.Case, change.Field)
		}
		allowed[change.Case+"/"+change.Field] = false
	}

	server := pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		status, body := responses.Default.Status, responses.Default.Body
		for prefix, answer := range responses.ByPath {
			if strings.HasPrefix(r.URL.Path, prefix) {
				status, body = answer.Status, answer.Body
			}
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	})
	t.Setenv("HOME", t.TempDir())
	// **A directory of its own, because `skill submit .` reads the one it is run in.** Run from the
	// package directory it depended on no `SKILL.md` ever appearing there — a real file this test
	// would have called a regression. The recording was made in an empty directory, which is what
	// this is.
	t.Chdir(t.TempDir())

	names := make([]string, 0, len(index))
	for name := range index {
		names = append(names, name)
	}
	sort.Strings(names)

	skipped := map[string]bool{"login": true, "logout": true, "startup": true, "skill download": true}
	for _, name := range names {
		record := index[name]
		if skipped[strings.Join(pathOf(record.Argv), " ")] {
			continue
		}
		stdout, stderr, status := runCaptured(t, record.Argv...)
		stdout = strings.ReplaceAll(stdout, server.URL, "{{SERVER}}")
		stderr = strings.ReplaceAll(stderr, server.URL, "{{SERVER}}")

		got := map[string]string{"stdout": stdout, "stderr": stderr, "status": itoa(status)}
		want := map[string]string{"stdout": record.Stdout, "stderr": record.Stderr, "status": itoa(record.Status)}
		for _, field := range []string{"stdout", "stderr", "status"} {
			key := name + "/" + field
			if got[field] == want[field] {
				continue
			}
			if _, ok := allowed[key]; ok {
				allowed[key] = true
				continue
			}
			t.Errorf("%s %v: %s changed and is not listed in expected-changes.json\nbefore: %q\nnow:    %q",
				name, record.Argv, field, want[field], got[field])
		}
	}

	for key, happened := range allowed {
		if !happened {
			t.Errorf("expected-changes.json lists %s, but nothing differs there any more", key)
		}
	}
}

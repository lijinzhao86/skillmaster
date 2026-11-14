package main

// **The Guide eval.** The help eval asks "can an Agent find the right command in `--help`"; this
// one asks a different question: given a user's own words, does the Guide send the Agent to the
// right page, and does that page answer the question. It is the test ADR 0174 calls for when the
// Guide changes.
//
// The ruler is pinned here and in `testdata/guide-eval.json`:
//
//	routed   = tasks that read the index first, then the expected page ÷ tasks
//	answered = tasks whose final reply names every required phrase ÷ tasks
//	pass     = routed, answered, and no violation (read cap, forbidden tool) ÷ tasks
//
// All three rates are gated at 90%. Gating only route and answer let a run where every task broke
// the read cap still exit 0. **Changing the prompt, the model, the read cap, the run count or the
// table changes the ruler**, so the document and the number have to change together.
//
// The agent under test is Claude Code headless (`--bare`): it skips CLAUDE.md, hooks, skills and
// memory, the working directory holds only the Guide, and only Read is enabled (measured: this
// `--bare` build exposes Read alone even when Glob/Grep are named). It never sees this
// repository, so what it answers comes from the Guide alone.

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

const guideEvalPrompt = `你在隔离目录 %s 里。目录里的 guide/ 是 SkillMaster 官方使用指南，入口是 guide/SKILL.md。

任务：%s

规则：
1. 先读 guide/SKILL.md，再按它的路由表找到该读的那一篇，然后读那一篇。
2. 只能使用 Read；不要执行命令，不要用别的工具。
3. 最多读 %d 个不同的文件；重复读同一文件不另计。
4. 最后只回复正文：先一句结论，再给出要执行的完整命令（以 skmr 开头）或要做的步骤。不要解释你的查找过程。`

const (
	guideEvalModel   = "deepseek-v4-flash"
	guideEvalReadCap = 4
	guideEvalRuns    = 1
	guideEvalTarget  = 0.90
)

type guideEvalTask struct {
	ID     string   `json:"id"`
	Group  string   `json:"group"`
	Prompt string   `json:"prompt"`
	Page   string   `json:"page"`
	Must   []string `json:"must"`
	Any    []string `json:"any,omitempty"`
}

type guideEvalOutcome struct {
	Task      guideEvalTask
	Answer    string
	Reads     []string
	FirstRead string
	ReadCount int
	// IndexFirst records that SKILL.md was read before any page. The prompt requires it, and
	// routing without it is a guess: a model that opens the page straight from the question
	// does not prove the index points there.
	IndexFirst bool
	Violation  string
	Err        string
}

// Routed and Answered are independent readings: a violation (too many reads, a forbidden tool)
// fails the run through Passed, and must not be reported as "did not find the page" or "did not
// answer". Mixing them made a navigation success look like a navigation failure.
func (o guideEvalOutcome) Routed() bool {
	if o.Err != "" || !o.IndexFirst {
		return false
	}
	want := filepath.ToSlash(filepath.Join("guide", o.Task.Page))
	for _, path := range o.Reads {
		if strings.HasSuffix(filepath.ToSlash(path), "/"+want) || filepath.ToSlash(path) == want {
			return true
		}
	}
	return false
}

// guideEvalNormalize removes whitespace and case so "30天" and "30 天" are the same answer.
func guideEvalNormalize(s string) string {
	return strings.ToLower(strings.Join(strings.Fields(s), ""))
}

var guideEvalASCII = regexp.MustCompile(`^[\x00-\x7F]+$`)

// guideEvalPhraseMatches keeps word boundaries for ASCII phrases (so "skmr run" does not match
// "skmr runs") and treats "=" as whitespace between flags and values (so "--role=maintain" matches
// "--role maintain"). CJK phrases compare with whitespace removed.
func guideEvalPhraseMatches(answer, phrase string) bool {
	if !guideEvalASCII.MatchString(phrase) {
		return strings.Contains(guideEvalNormalize(answer), guideEvalNormalize(phrase))
	}
	tokens := strings.FieldsFunc(strings.ToLower(phrase), func(r rune) bool {
		return r == ' ' || r == '\t' || r == '='
	})
	if len(tokens) == 0 {
		return true
	}
	parts := make([]string, len(tokens))
	for i, token := range tokens {
		parts[i] = regexp.QuoteMeta(token)
	}
	pattern := strings.Join(parts, `[\s=]+`)
	if r := rune(tokens[0][0]); isWordRune(r) {
		pattern = `(^|[^a-z0-9_])` + pattern
	}
	if last := tokens[len(tokens)-1]; len(last) > 0 && isWordRune(rune(last[len(last)-1])) {
		pattern += `([^a-z0-9_]|$)`
	}
	matched, err := regexp.MatchString(`(?i)`+pattern, answer)
	return err == nil && matched
}

func isWordRune(r rune) bool {
	return r == '_' || (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9')
}

func (o guideEvalOutcome) Answered() bool {
	if o.Err != "" || o.Answer == "" {
		return false
	}
	for _, phrase := range o.Task.Must {
		if !guideEvalPhraseMatches(o.Answer, phrase) {
			return false
		}
	}
	if len(o.Task.Any) > 0 {
		matched := false
		for _, phrase := range o.Task.Any {
			if guideEvalPhraseMatches(o.Answer, phrase) {
				matched = true
				break
			}
		}
		if !matched {
			return false
		}
	}
	return true
}

func (o guideEvalOutcome) Passed() bool {
	return o.Err == "" && o.Violation == "" && o.Routed() && o.Answered()
}

// FirstReadRouted is the diagnostic "did the index point at the right page on the first try".
// The index itself is not a try: every run reads guide/SKILL.md first, so the first *leaf* read is
// what gets compared (successful or not — this is a diagnostic, not a gate). It is recorded, not gated: a correct second read still counts as routed.
func (o guideEvalOutcome) FirstReadRouted() bool {
	if !o.IndexFirst {
		return false
	}
	want := filepath.ToSlash(filepath.Join("guide", o.Task.Page))
	got := filepath.ToSlash(o.FirstRead)
	return got == want || strings.HasSuffix(got, "/"+want)
}

type guideEvalTable struct {
	Tasks []guideEvalTask `json:"tasks"`
}

func loadGuideEvalTable(t *testing.T, root string) guideEvalTable {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(root, "skillmaster-cli", "testdata", "guide-eval.json"))
	if err != nil {
		t.Fatalf("read the Guide eval table: %v", err)
	}
	var table guideEvalTable
	if err := json.Unmarshal(data, &table); err != nil {
		t.Fatalf("parse the Guide eval table: %v", err)
	}
	return table
}

// TestGuideEvalTableIsValid keeps the ruler honest without a model: every page is covered, every
// expected phrase that is a command exists, and no prompt leaks its own answer.
func TestGuideEvalTableIsValid(t *testing.T) {
	root := helpRepoRoot(t)
	table := loadGuideEvalTable(t, root)
	if len(table.Tasks) != 43 {
		t.Fatalf("the table has %d tasks; the ruler is 43", len(table.Tasks))
	}

	guideDir := filepath.Join(root, "skills", "skillmaster-guide")
	indexData, err := os.ReadFile(filepath.Join(guideDir, "SKILL.md"))
	if err != nil {
		t.Fatalf("read the Guide index: %v", err)
	}
	indexText := string(indexData)
	covered := map[string]bool{}
	ids := map[string]bool{}
	commands := helpCommandTable()
	idPattern := regexp.MustCompile(`^[a-z0-9]+(-[a-z0-9]+)*$`)

	walk := func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() || !strings.HasSuffix(path, ".md") {
			return nil
		}
		rel, err := filepath.Rel(guideDir, path)
		if err != nil {
			return err
		}
		rel = filepath.ToSlash(rel)
		if rel != "SKILL.md" {
			covered[rel] = false
		}
		return nil
	}
	if err := filepath.WalkDir(guideDir, walk); err != nil {
		t.Fatalf("walk the Guide: %v", err)
	}

	for _, task := range table.Tasks {
		if task.ID == "" || task.Prompt == "" || task.Page == "" {
			t.Errorf("a task is missing id, prompt or page: %+v", task)
		}
		if !idPattern.MatchString(task.ID) {
			t.Errorf("%s is not kebab-case", task.ID)
		}
		if ids[task.ID] {
			t.Errorf("the table repeats the id %q", task.ID)
		}
		ids[task.ID] = true
		if _, ok := covered[task.Page]; !ok {
			t.Errorf("%s names a page the Guide does not have: %q", task.ID, task.Page)
			continue
		}
		covered[task.Page] = true
		if len(task.Must)+len(task.Any) == 0 {
			t.Errorf("%s has no required phrase", task.ID)
		}
		pageData, err := os.ReadFile(filepath.Join(guideDir, task.Page))
		if err != nil {
			t.Errorf("%s cannot read its page: %v", task.ID, err)
			continue
		}
		pageText := string(pageData)
		answerableFromIndex := true
		phrases := append(append([]string{}, task.Must...), task.Any...)
		for _, phrase := range task.Any {
			if strings.Contains(indexText, phrase) {
				t.Errorf("%s accepts %q, which also appears in SKILL.md: a broken page could still answer", task.ID, phrase)
			}
		}
		for _, phrase := range phrases {
			if strings.Contains(task.Prompt, phrase) {
				t.Errorf("%s leaks %q in its prompt", task.ID, phrase)
			}
			if strings.HasPrefix(phrase, "guide/") {
				t.Errorf("%s asks for a page path in `must`: %q", task.ID, phrase)
			}
			if strings.Contains(phrase, "smkr") {
				t.Errorf("%s spells smkr, not skmr: %q", task.ID, phrase)
			}
			if !strings.Contains(pageText, phrase) {
				t.Errorf("%s requires %q, which is not in %s", task.ID, phrase, task.Page)
			}
			if !strings.Contains(indexText, phrase) {
				answerableFromIndex = false
			}
			if !strings.HasPrefix(phrase, "skmr ") {
				continue
			}
			if bad := guideEvalCommandIssues(phrase, commands); bad != "" {
				t.Errorf("%s requires an undeclared command or flag: %q (%s)", task.ID, phrase, bad)
			}
		}
		if answerableFromIndex {
			t.Errorf("%s can be answered from SKILL.md alone; at least one required phrase must come from the page", task.ID)
		}
		if strings.Contains(task.Prompt, task.Page) || strings.Contains(task.Prompt, filepath.Base(task.Page)) {
			t.Errorf("%s leaks its page in the prompt: %q", task.ID, task.Page)
		}
	}

	for page, used := range covered {
		if !used {
			t.Errorf("the eval table never targets %q; every Guide page needs one task", page)
		}
	}
}

// guideEvalCommandIssues reuses the Guide parser instead of keeping a second one. It adds the two
// checks this table needs: a required command has to exist as a leaf, and `skmr help <topic>` is a
// legal spelling that the shared parser already understands.
func guideEvalCommandIssues(line string, table map[string]*guideCommand) string {
	fields := strings.Fields(line)
	if len(fields) == 0 || fields[0] != "skmr" {
		return ""
	}
	args := fields[1:]
	if len(args) == 0 || strings.HasPrefix(args[0], "-") || args[0] == "help" {
		return ""
	}
	if issues := invocationIssues("guide-eval", line, args, table); len(issues) > 0 {
		return issues[0].Check + ": " + issues[0].Match
	}
	command, _, ok := longestCommand(table, args)
	if !ok {
		return "unknown command"
	}
	if table[command].spec.PureGroup {
		return "group, not a leaf: " + command
	}
	return ""
}

// TestGuideEval is the opt-in model run. A table of 43 tasks is expensive; the default is one
// run, and `SKILLMASTER_GUIDE_EVAL_LIMIT` can smoke a prefix of the table.
func TestGuideEval(t *testing.T) {
	if os.Getenv("SKILLMASTER_GUIDE_EVAL") != "1" {
		t.Skip("the Guide eval runs a real model; set SKILLMASTER_GUIDE_EVAL=1 to run it")
	}
	if _, err := exec.LookPath("claude"); err != nil {
		t.Skipf("the headless Claude Code CLI is not installed: %v", err)
	}

	root := helpRepoRoot(t)
	table := loadGuideEvalTable(t, root)
	tasks := table.Tasks
	limited := false
	if raw := os.Getenv("SKILLMASTER_GUIDE_EVAL_LIMIT"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit <= 0 || limit > len(tasks) {
			t.Fatalf("SKILLMASTER_GUIDE_EVAL_LIMIT must be 1..%d, got %q", len(tasks), raw)
		}
		tasks = tasks[:limit]
		limited = limit < len(table.Tasks)
		t.Logf("smoke run: the first %d of %d tasks; thresholds are not enforced", limit, len(table.Tasks))
	}

	workspace := t.TempDir()
	if err := os.CopyFS(filepath.Join(workspace, "guide"), os.DirFS(filepath.Join(root, "skills", "skillmaster-guide"))); err != nil {
		t.Fatalf("copy the Guide into the workspace: %v", err)
	}

	results := make([][]guideEvalOutcome, guideEvalRuns)
	for run := range results {
		results[run] = make([]guideEvalOutcome, len(tasks))
	}

	semaphore := make(chan struct{}, 6)
	var wg sync.WaitGroup
	for run := range results {
		for i := range tasks {
			wg.Add(1)
			go func(run, i int) {
				defer wg.Done()
				semaphore <- struct{}{}
				defer func() { <-semaphore }()
				results[run][i] = runGuideEvalTask(workspace, tasks[i])
			}(run, i)
		}
	}
	wg.Wait()

	type outcomeRecord struct {
		Task            string   `json:"task"`
		Page            string   `json:"page"`
		Answer          string   `json:"answer"`
		FirstRead       string   `json:"first_read"`
		Reads           []string `json:"reads"`
		ReadCount       int      `json:"read_count"`
		Violation       string   `json:"violation,omitempty"`
		Err             string   `json:"error,omitempty"`
		Routed          bool     `json:"routed"`
		Answered        bool     `json:"answered"`
		Passed          bool     `json:"passed"`
		FirstReadRouted bool     `json:"first_read_routed"`
	}
	type runRecord struct {
		Run        int             `json:"run"`
		FirstRead  int             `json:"first_read_routed"`
		FirstRate  float64         `json:"first_read_rate"`
		Routed     int             `json:"routed"`
		Answered   int             `json:"answered"`
		Passed     int             `json:"passed"`
		Rate       float64         `json:"pass_rate"`
		RouteRate  float64         `json:"route_rate"`
		AnswerRate float64         `json:"answer_rate"`
		Outcomes   []outcomeRecord `json:"outcomes"`
	}
	records := make([]runRecord, 0, guideEvalRuns)
	for _, run := range results {
		record := runRecord{Run: len(records)}
		for _, outcome := range run {
			if outcome.Routed() {
				record.Routed++
			}
			if outcome.Answered() {
				record.Answered++
			}
			if outcome.Passed() {
				record.Passed++
			}
			if outcome.FirstReadRouted() {
				record.FirstRead++
			}
			if !outcome.Passed() {
				t.Logf("task %-28s page=%-32s routed=%v answered=%v reads=%d answer=%q violation=%q err=%q",
					outcome.Task.ID, outcome.Task.Page, outcome.Routed(), outcome.Answered(),
					outcome.ReadCount, outcome.Answer, outcome.Violation, outcome.Err)
			}
			record.Outcomes = append(record.Outcomes, outcomeRecord{
				Task: outcome.Task.ID, Page: outcome.Task.Page, Answer: outcome.Answer,
				FirstRead: outcome.FirstRead,
				Reads:     outcome.Reads, ReadCount: outcome.ReadCount,
				Violation: outcome.Violation, Err: outcome.Err,
				Routed: outcome.Routed(), Answered: outcome.Answered(), Passed: outcome.Passed(),
				FirstReadRouted: outcome.FirstReadRouted(),
			})
		}
		total := float64(len(run))
		record.Rate = float64(record.Passed) / total
		record.RouteRate = float64(record.Routed) / total
		record.AnswerRate = float64(record.Answered) / total
		record.FirstRate = float64(record.FirstRead) / total
		records = append(records, record)
		t.Logf("run %d: first-read %d/%d = %.0f%%, routed %d/%d = %.0f%%, answered %d/%d = %.0f%%, passed %d/%d = %.0f%%",
			record.Run, record.FirstRead, len(run), record.FirstRate*100,
			record.Routed, len(run), record.RouteRate*100,
			record.Answered, len(run), record.AnswerRate*100,
			record.Passed, len(run), record.Rate*100)
	}

	if out := os.Getenv("SKILLMASTER_GUIDE_EVAL_OUT"); out != "" {
		payload, _ := json.MarshalIndent(map[string]any{
			"model":    guideEvalModel,
			"runs":     guideEvalRuns,
			"read_cap": guideEvalReadCap,
			"tasks":    len(tasks),
			"results":  records,
		}, "", "  ")
		if err := os.WriteFile(out, payload, 0o600); err != nil {
			t.Errorf("write the results: %v", err)
		}
	}

	if limited {
		return
	}
	for _, record := range records {
		if record.RouteRate < guideEvalTarget {
			t.Errorf("run %d routes only %.0f%% of tasks (target >= %.0f%%)", record.Run, record.RouteRate*100, guideEvalTarget*100)
		}
		if record.AnswerRate < guideEvalTarget {
			t.Errorf("run %d answers only %.0f%% of tasks (target >= %.0f%%)", record.Run, record.AnswerRate*100, guideEvalTarget*100)
		}
		if record.Rate < guideEvalTarget {
			t.Errorf("run %d passes only %.0f%% of tasks (target >= %.0f%%; pass = routed + answered + no violation)", record.Run, record.Rate*100, guideEvalTarget*100)
		}
		if record.Passed == 0 && record.Routed == 0 && record.Answered == 0 {
			t.Errorf("run %d produced no usable answer at all; if every task errored, `claude --bare` cannot authenticate with OAuth — export ANTHROPIC_API_KEY or ANTHROPIC_AUTH_TOKEN", record.Run)
		}
	}
}

func runGuideEvalTask(workspace string, task guideEvalTask) guideEvalOutcome {
	outcome := guideEvalOutcome{Task: task}
	prompt := fmt.Sprintf(guideEvalPrompt, workspace, task.Prompt, guideEvalReadCap)
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	command := exec.CommandContext(ctx, "claude",
		"--bare",
		"--print", prompt,
		"--model", guideEvalModel,
		"--output-format", "stream-json",
		"--verbose",
		"--no-session-persistence",
		"--tools", "Read",
		"--allowedTools", "Read",
		"--disallowedTools", "Bash,WebFetch,WebSearch,Edit,Write,NotebookEdit,Task,Agent,Skill,Glob,Grep",
		"--restricted",
	)
	command.Dir = workspace
	var stderr bytes.Buffer
	command.Stderr = &stderr
	output, err := command.Output()
	if err != nil {
		outcome.Err = err.Error()
		if detail := strings.TrimSpace(stderr.String()); detail != "" {
			outcome.Err += ": " + detail
		}
		return outcome
	}
	return parseGuideEvalOutputIn(task, output, workspace)
}

// parseGuideEvalOutput reads the stream for a caller with no workspace (the tests).
func parseGuideEvalOutput(task guideEvalTask, stream []byte) guideEvalOutcome {
	return parseGuideEvalOutputIn(task, stream, "")
}

// parseGuideEvalOutputIn reads the stream: a Read counts only after its tool_result came back
// without an error (a failed Read visits nothing), anything other than Read is a violation, and
// the final result is what gets scored. `--tools Read` means a run that uses anything else was not
// the run the prompt asked for.
func parseGuideEvalOutputIn(task guideEvalTask, stream []byte, workspace string) guideEvalOutcome {
	outcome := guideEvalOutcome{Task: task}
	seen := map[string]bool{}
	pending := map[string]string{}

	commit := func(path string) {
		// A directory or an extensionless path is a listing, not a file: it neither uses up the
		// read cap nor counts as a page read.
		if filepath.Ext(path) == "" {
			return
		}
		outcome.Reads = append(outcome.Reads, path)
		leaf := strings.HasSuffix(path, ".md") && filepath.Base(path) != "SKILL.md"
		if leaf && outcome.FirstRead == "" {
			outcome.FirstRead = path
		}
		if !leaf && strings.HasSuffix(path, ".md") && !outcome.IndexFirst {
			// The index was read before any page; a later re-read cannot reset it.
			outcome.IndexFirst = outcome.FirstRead == ""
		}
		key := guideEvalKey(workspace, path)
		if seen[key] {
			return
		}
		seen[key] = true
		outcome.ReadCount++
		if outcome.ReadCount > guideEvalReadCap {
			outcome.Violation = fmt.Sprintf("超过 %d 个不同文件", guideEvalReadCap)
		}
	}

	scanner := bufio.NewScanner(bytes.NewReader(stream))
	scanner.Buffer(make([]byte, 0, 1024*1024), 8*1024*1024)
	for scanner.Scan() {
		var event struct {
			Type    string `json:"type"`
			Message struct {
				Content []struct {
					Type      string `json:"type"`
					Name      string `json:"name"`
					Text      string `json:"text"`
					ID        string `json:"id"`
					ToolUseID string `json:"tool_use_id"`
					IsError   bool   `json:"is_error"`
					Input     struct {
						FilePath string `json:"file_path"`
						Path     string `json:"path"`
					} `json:"input"`
				} `json:"content"`
			} `json:"message"`
			Result string `json:"result"`
		}
		if err := json.Unmarshal(scanner.Bytes(), &event); err != nil {
			continue
		}
		switch event.Type {
		case "assistant":
			for _, block := range event.Message.Content {
				if block.Type != "tool_use" {
					continue
				}
				switch block.Name {
				case "Read":
					path := filepath.Clean(strings.TrimSpace(block.Input.FilePath))
					if path == "." || path == "" {
						continue
					}
					pending[block.ID] = path
				default:
					outcome.Violation = "用了不允许的工具：" + block.Name
				}
			}
		case "user":
			for _, block := range event.Message.Content {
				if block.Type != "tool_result" {
					continue
				}
				path, ok := pending[block.ToolUseID]
				if !ok {
					continue
				}
				delete(pending, block.ToolUseID)
				if block.IsError {
					continue
				}
				commit(path)
			}
		case "result":
			outcome.Answer = strings.TrimSpace(event.Result)
		}
	}
	if err := scanner.Err(); err != nil && outcome.Err == "" {
		outcome.Err = "read the stream: " + err.Error()
	}
	if outcome.Answer == "" && outcome.Violation == "" && outcome.Err == "" {
		outcome.Violation = "没有作答"
	}
	return outcome
}

// guideEvalKey is one file seen through any spelling: relative vs absolute (relative resolves
// inside the workspace the model ran in), /var vs /private/var.
func guideEvalKey(workspace, path string) string {
	if !filepath.IsAbs(path) && workspace != "" {
		path = filepath.Join(workspace, path)
	}
	if resolved, err := filepath.EvalSymlinks(path); err == nil {
		path = resolved
	}
	if abs, err := filepath.Abs(path); err == nil {
		path = abs
	}
	return path
}

// TestGuideEvalScoring pins the three checks so the metric cannot start counting the wrong thing.
func TestGuideEvalScoring(t *testing.T) {
	task := guideEvalTask{ID: "probe", Page: "use/run.md", Must: []string{"skmr run"}}
	toolUse := `{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t","name":"Read","input":{"file_path":%q}}]}}`
	readOK := `{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t"}]}}`
	readFailed := `{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t","is_error":true}]}}`
	read := func(path string) string { return fmt.Sprintf(toolUse, path) + "\n" + readOK }
	readErr := func(path string) string { return fmt.Sprintf(toolUse, path) + "\n" + readFailed }
	result := `{"type":"result","result":%q}`
	stream := func(lines ...string) []byte { return []byte(strings.Join(lines, "\n")) }

	t.Run("right page and right answer", func(t *testing.T) {
		got := parseGuideEvalOutput(task, stream(
			read("/tmp/x/guide/SKILL.md"),
			read("/tmp/x/guide/use/run.md"),
			fmt.Sprintf(result, "运行它：\nskmr run <地址> -- python3 plan.py"),
		))
		if !got.Passed() {
			t.Fatalf("a correct run was scored wrong: %+v", got)
		}
	})

	t.Run("right answer from the wrong page", func(t *testing.T) {
		got := parseGuideEvalOutput(task, stream(
			read("/tmp/x/guide/use/search.md"),
			fmt.Sprintf(result, "skmr run <地址> -- python3 plan.py"),
		))
		if got.Routed() {
			t.Fatal("a different page was counted as the route")
		}
		if !got.Answered() {
			t.Fatal("the answer should still have been found")
		}
		if got.Passed() {
			t.Fatal("an unrouted run cannot pass")
		}
	})

	t.Run("route without the required phrase", func(t *testing.T) {
		got := parseGuideEvalOutput(task, stream(
			read("/tmp/x/guide/SKILL.md"),
			read("/tmp/x/guide/use/run.md"),
			fmt.Sprintf(result, "照正文做。"),
		))
		if !got.Routed() || got.Answered() || got.Passed() {
			t.Fatalf("a route-only run passed: %+v", got)
		}
	})

	t.Run("forbidden tool", func(t *testing.T) {
		got := parseGuideEvalOutput(task, stream(
			`{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{}}]}}`,
			fmt.Sprintf(result, "skmr run"),
		))
		if got.Passed() || got.Violation == "" {
			t.Fatalf("a forbidden tool was not refused: %+v", got)
		}
	})

	t.Run("over the read cap uses distinct files", func(t *testing.T) {
		lines := []string{read("/tmp/x/guide/SKILL.md")}
		paths := []string{
			"/tmp/x/guide/use/run.md",
			"/tmp/x/guide/use/secrets.md",
			"/tmp/x/guide/author/edit.md",
			"/tmp/x/guide/ship/release.md",
			"/tmp/x/guide/use/search.md",
		}
		for _, path := range paths {
			lines = append(lines, read(path))
		}
		lines = append(lines, fmt.Sprintf(result, "skmr run"))
		got := parseGuideEvalOutput(task, stream(lines...))
		if got.Passed() || got.Violation == "" {
			t.Fatalf("a run over the read cap passed: %+v", got)
		}
		if !got.Routed() || !got.Answered() {
			t.Fatalf("a violation must not erase the route or the answer: %+v", got)
		}
	})

	t.Run("reading the same file again is not a new file", func(t *testing.T) {
		lines := []string{read("/tmp/x/guide/SKILL.md")}
		for i := 0; i <= guideEvalReadCap; i++ {
			lines = append(lines, read("/tmp/x/guide/use/run.md"))
		}
		lines = append(lines, fmt.Sprintf(result, "skmr run"))
		got := parseGuideEvalOutput(task, stream(lines...))
		if got.Violation != "" || got.ReadCount != 2 || !got.Passed() {
			t.Fatalf("a repeated read was counted as another file: %+v", got)
		}
	})

	t.Run("first leaf read is recorded", func(t *testing.T) {
		got := parseGuideEvalOutput(task, stream(
			read("/tmp/x/guide/SKILL.md"),
			read("/tmp/x/guide/use/search.md"),
			read("/tmp/x/guide/use/run.md"),
			fmt.Sprintf(result, "skmr run"),
		))
		if !got.Routed() || got.FirstReadRouted() {
			t.Fatalf("first-read should be false when the first leaf is not the target: %+v", got)
		}
		direct := parseGuideEvalOutput(task, stream(
			read("/tmp/x/guide/SKILL.md"),
			read("/tmp/x/guide/use/run.md"),
			fmt.Sprintf(result, "skmr run"),
		))
		if !direct.FirstReadRouted() {
			t.Fatalf("the index itself must not count as a try: %+v", direct)
		}
	})

	t.Run("a directory read is not a page read", func(t *testing.T) {
		got := parseGuideEvalOutput(task, stream(
			read("/tmp/x/guide/SKILL.md"),
			read("/tmp/x/guide"),
			read("/tmp/x/guide/use/run.md"),
			fmt.Sprintf(result, "skmr run"),
		))
		if !got.FirstReadRouted() || !got.Passed() || got.ReadCount != 2 {
			t.Fatalf("listing the guide folder must not consume the first-read or the cap: %+v", got)
		}
	})

	t.Run("the page without the index is a guess", func(t *testing.T) {
		got := parseGuideEvalOutput(task, stream(
			read("/tmp/x/guide/use/run.md"),
			fmt.Sprintf(result, "skmr run"),
		))
		if got.Routed() || got.Passed() || !got.Answered() {
			t.Fatalf("opening the page without the index must not count as routed: %+v", got)
		}
	})

	t.Run("the index read after the page does not count", func(t *testing.T) {
		got := parseGuideEvalOutput(task, stream(
			read("/tmp/x/guide/use/run.md"),
			read("/tmp/x/guide/SKILL.md"),
			fmt.Sprintf(result, "skmr run"),
		))
		if got.Routed() || got.FirstReadRouted() {
			t.Fatalf("an index read after the page is not routing through the index: %+v", got)
		}
	})

	t.Run("a failed read visits nothing", func(t *testing.T) {
		got := parseGuideEvalOutput(task, stream(
			readErr("/tmp/x/guide/SKILL.md"),
			read("/tmp/x/guide/use/run.md"),
			fmt.Sprintf(result, "skmr run"),
		))
		if got.Routed() || got.Passed() || got.ReadCount != 1 {
			t.Fatalf("a Read that errored must not count as the index: %+v", got)
		}
	})

	t.Run("relative and absolute spellings are one file", func(t *testing.T) {
		got := parseGuideEvalOutputIn(task, stream(
			read("guide/SKILL.md"),
			read("/tmp/x/guide/SKILL.md"),
			read("/tmp/x/guide/use/run.md"),
			fmt.Sprintf(result, "skmr run"),
		), "/tmp/x")
		if got.ReadCount != 2 || got.Violation != "" || !got.Passed() {
			t.Fatalf("the same file under two spellings consumed the cap twice: %+v", got)
		}
	})

	t.Run("whitespace does not decide the answer", func(t *testing.T) {
		spaced := guideEvalTask{ID: "spaced", Page: "use/run.md", Must: []string{"30 天"}}
		got := parseGuideEvalOutput(spaced, stream(
			read("/tmp/x/guide/SKILL.md"),
			read("/tmp/x/guide/use/run.md"),
			fmt.Sprintf(result, "30天后生效"),
		))
		if !got.Answered() {
			t.Fatalf("30天 and 30 天 are the same answer: %+v", got)
		}
	})
}

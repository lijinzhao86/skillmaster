package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

// **T25 — the help eval (prd.md §指标「首次找对率」).** Opt-in and expensive: it runs a real
// language model over a fixed table of tasks, three times each, and measures two numbers:
//
//	first-find rate  = tasks whose answer names the right command ÷ 20
//	median calls     = the median number of `--help` runs it took to answer
//
// The ruler is nailed in `test-plan.md §环境与数据`. **Changing any of it is changing the ruler** —
// the prompt below, the model, the eight-call cap, the three runs, the task table — so the document
// has to change in the same edit or the numbers stop meaning what they say.
//
// The agent under test is Claude Code in its headless form, isolated: `--bare` skips CLAUDE.md,
// hooks, skills and memory, the working directory holds nothing but the binary, and every tool but
// Bash is removed, so the only thing it can learn about this CLI is its own `--help`.

const helpEvalPrompt = `你在一个隔离目录里，那里有一个命令行工具 ./skmr（一个 skill 管理工具）。

任务：%s

规则：
1. 你只能运行形如 ./skmr ... --help 的命令来了解它的能力；不要运行任何会真正执行操作的子命令，也不要用别的工具。
2. 最多执行 8 次 --help。
3. 完成后，最后一条回复必须只有一行：你要执行的完整命令，以 "skmr " 开头（不要 ./ 前缀），不要解释，不要代码块。`

const helpEvalModel = "deepseek-v4-flash"
const helpEvalCallCap = 8
const helpEvalRuns = 3

type evalTask struct {
	ID       string `json:"id"`
	Group    string `json:"group"`
	Prompt   string `json:"prompt"`
	Expected string `json:"expected"`
}

type evalOutcome struct {
	Task      evalTask
	Answer    string
	HelpCalls int
	Violation string
	Err       string
}

func (o evalOutcome) Correct() bool {
	if o.Err != "" || o.Violation != "" || o.Answer == "" || o.HelpCalls > helpEvalCallCap {
		return false
	}
	// The answer is a whole command line (`skmr skills list --all`); what is scored is the
	// **path** — the words after the program's own name.
	got, want := strings.Fields(o.Answer), strings.Fields(o.Task.Expected)
	if len(got) == 0 || got[0] != config.Name {
		return false
	}
	got = got[1:]
	if len(got) < len(want) {
		return false
	}
	for i := range want {
		if got[i] != want[i] {
			return false
		}
	}
	return true
}

func TestHelpEval(t *testing.T) {
	if os.Getenv("SKILLMASTER_HELP_EVAL") != "1" {
		t.Skip("the help eval runs a real model; set SKILLMASTER_HELP_EVAL=1 to run it")
	}
	if _, err := exec.LookPath("claude"); err != nil {
		t.Skipf("the headless Claude Code CLI is not installed: %v", err)
	}

	var table struct {
		Tasks []evalTask `json:"tasks"`
	}
	// The table lives where the metric names it (`skillmaster-cli/testdata/help-eval.json`), not beside
	// this package's other fixtures: it is a ruler, not a test input.
	data, err := os.ReadFile("../../testdata/help-eval.json")
	if err != nil {
		t.Fatalf("read the task table: %v", err)
	}
	if err := json.Unmarshal(data, &table); err != nil {
		t.Fatalf("parse the task table: %v", err)
	}
	if len(table.Tasks) != 20 {
		t.Fatalf("the table has %d tasks; the ruler is 20", len(table.Tasks))
	}

	workspace := t.TempDir()
	binary := filepath.Join(workspace, config.Name)
	build := exec.Command("go", "build", "-o", binary, ".")
	if output, err := build.CombinedOutput(); err != nil {
		t.Fatalf("build the binary under test: %v\n%s", err, output)
	}

	type result struct {
		run      int
		outcomes []evalOutcome
	}
	results := make([]result, helpEvalRuns)
	for run := range results {
		results[run] = result{run: run, outcomes: make([]evalOutcome, len(table.Tasks))}
	}

	// Six at a time: each is a separate process and the work is model latency, not this machine.
	semaphore := make(chan struct{}, 6)
	var wg sync.WaitGroup
	for run := range results {
		for i := range table.Tasks {
			wg.Add(1)
			go func(run, i int) {
				defer wg.Done()
				semaphore <- struct{}{}
				defer func() { <-semaphore }()
				results[run].outcomes[i] = runHelpEvalTask(workspace, table.Tasks[i])
			}(run, i)
		}
	}
	wg.Wait()

	// The artifact is for a person to read later: exported fields, because the in-memory view keeps
	// its state in unexported ones and would serialise to `{}`.
	type outcomeRecord struct {
		Task      string `json:"task"`
		Answer    string `json:"answer"`
		HelpCalls int    `json:"help_calls"`
		Violation string `json:"violation,omitempty"`
		Err       string `json:"error,omitempty"`
		Correct   bool   `json:"correct"`
	}
	type runRecord struct {
		Run      int             `json:"run"`
		Correct  int             `json:"correct"`
		Rate     float64         `json:"rate"`
		Median   float64         `json:"median_calls"`
		Outcomes []outcomeRecord `json:"outcomes"`
	}
	records := make([]runRecord, 0, helpEvalRuns)
	summary := map[string]any{}
	rates := make([]float64, 0, helpEvalRuns)
	medians := make([]float64, 0, helpEvalRuns)
	for _, res := range results {
		correct := 0
		calls := make([]int, 0, len(res.outcomes))
		for _, outcome := range res.outcomes {
			if outcome.Correct() {
				correct++
			}
			calls = append(calls, outcome.HelpCalls)
			if !outcome.Correct() {
				t.Logf("run %d · %-24s answer=%q calls=%d violation=%q err=%q",
					res.run, outcome.Task.ID, outcome.Answer, outcome.HelpCalls, outcome.Violation, outcome.Err)
			}
		}
		rate := float64(correct) / float64(len(res.outcomes))
		median := medianFloat(calls)
		rates = append(rates, rate)
		medians = append(medians, median)
		record := runRecord{Run: res.run, Correct: correct, Rate: rate, Median: median}
		for _, outcome := range res.outcomes {
			record.Outcomes = append(record.Outcomes, outcomeRecord{
				Task: outcome.Task.ID, Answer: outcome.Answer, HelpCalls: outcome.HelpCalls,
				Violation: outcome.Violation, Err: outcome.Err, Correct: outcome.Correct(),
			})
		}
		records = append(records, record)
		t.Logf("run %d: first-find %d/%d = %.0f%%, median calls %.1f",
			res.run, correct, len(res.outcomes), rate*100, median)
	}
	sort.Float64s(rates)
	sort.Float64s(medians)
	summary["first_find_rate"] = rates[len(rates)/2]
	summary["median_calls"] = medians[len(medians)/2]

	t.Logf("T25: first-find rate %.0f%% (target >= 80%%), median --help calls %.1f (target <= 4)",
		summary["first_find_rate"].(float64)*100, summary["median_calls"].(float64))
	if out := os.Getenv("SKILLMASTER_HELP_EVAL_OUT"); out != "" {
		payload, _ := json.MarshalIndent(map[string]any{
			"model":      helpEvalModel,
			"runs":       helpEvalRuns,
			"call_cap":   helpEvalCallCap,
			"first_find": summary["first_find_rate"],
			"median":     summary["median_calls"],
			"results":    records,
		}, "", "  ")
		if err := os.WriteFile(out, payload, 0o600); err != nil {
			t.Errorf("write the results: %v", err)
		}
	}
}

// runHelpEvalTask drives one task in its own isolated conversation.
func runHelpEvalTask(workspace string, task evalTask) evalOutcome {
	outcome := evalOutcome{Task: task}
	prompt := fmt.Sprintf(helpEvalPrompt, task.Prompt)
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	command := exec.CommandContext(ctx, "claude",
		"--bare",
		"--print", prompt,
		"--model", helpEvalModel,
		"--output-format", "stream-json",
		"--verbose",
		"--no-session-persistence",
		"--allowedTools", "Bash",
		"--disallowedTools", "Read,Glob,Grep,WebFetch,WebSearch,Edit,Write,NotebookEdit,Task,Agent",
	)
	command.Dir = workspace
	output, err := command.Output()
	if err != nil {
		outcome.Err = err.Error()
		return outcome
	}
	return parseHelpEvalOutput(task, output)
}

// parseHelpEvalOutput reads the stream and applies the answering protocol: every executed
// `./skmr … --help` is a call, anything else executed is a violation, and the final result
// has to be exactly one line starting with `skmr `.
func parseHelpEvalOutput(task evalTask, stream []byte) evalOutcome {
	outcome := evalOutcome{Task: task}
	scanner := bufio.NewScanner(bytes.NewReader(stream))
	scanner.Buffer(make([]byte, 0, 1024*1024), 8*1024*1024)
	for scanner.Scan() {
		var event struct {
			Type    string `json:"type"`
			Message struct {
				Content []struct {
					Type  string `json:"type"`
					Name  string `json:"name"`
					Text  string `json:"text"`
					Input struct {
						Command string `json:"command"`
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
				if block.Type != "tool_use" || block.Name != "Bash" {
					continue
				}
				line := strings.TrimSpace(block.Input.Command)
				if line == "" {
					// A tool call that runs nothing is not an execution; the stream occasionally
					// carries one, and counting it as a violation would turn a correct answer into
					// a miss for no reason.
					continue
				}
				switch {
				case isHelpCall(line):
					outcome.HelpCalls++
					if outcome.HelpCalls > helpEvalCallCap {
						outcome.Violation = fmt.Sprintf("超过 %d 次 --help", helpEvalCallCap)
					}
				default:
					outcome.Violation = "执行了 --help 以外的命令：" + line
				}
			}
		case "result":
			outcome.Answer = answerLine(event.Result)
		}
	}
	if outcome.Answer == "" {
		outcome.Violation = "没有按规定作答"
	}
	// **Over the cap counts as the cap** (prd.md §指标): a run that never answered inside the budget
	// has no honest count of its own, so the ceiling is what gets recorded.
	if outcome.HelpCalls > helpEvalCallCap {
		outcome.HelpCalls = helpEvalCallCap
	}
	return outcome
}

// isHelpCall is a help page and nothing else: `./skmr … --help`, or the bare `help` word
// (`skmr help env`), which is the same page under its own name. **The subject is "can it find
// the answer in the help", not "does it spell help the way this harness expected"** — counting the
// documented spelling as a violation would score protocol pedantry instead.
func isHelpCall(command string) bool {
	fields := strings.Fields(command)
	if len(fields) == 0 {
		return false
	}
	switch fields[0] {
	case "./" + config.Name, config.Name:
	default:
		return false
	}
	for _, field := range fields[1:] {
		if field == "--help" || field == "-h" {
			return true
		}
	}
	return len(fields) > 1 && fields[1] == "help"
}

// answerLine takes the final message as the protocol demands it: one line, starting with
// `skmr `. Anything else is not an answer.
func answerLine(text string) string {
	lines := []string{}
	for _, line := range strings.Split(text, "\n") {
		if trimmed := strings.TrimSpace(line); trimmed != "" {
			lines = append(lines, trimmed)
		}
	}
	if len(lines) != 1 || !strings.HasPrefix(lines[0], "skmr ") {
		return ""
	}
	return lines[0]
}

func medianFloat(values []int) float64 {
	if len(values) == 0 {
		return 0
	}
	sorted := append([]int(nil), values...)
	sort.Ints(sorted)
	middle := len(sorted) / 2
	if len(sorted)%2 == 0 {
		return float64(sorted[middle-1]+sorted[middle]) / 2
	}
	return float64(sorted[middle])
}

// The scoring is part of the ruler too, and it costs nothing to pin: a harness that counts the
// right thing on the wrong line is how a metric starts lying. These cases are the protocol in
// test-plan §环境与数据, one line of it each.
func TestHelpEvalScoringFollowsTheRuler(t *testing.T) {
	helpCall := `{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"./skmr %s --help"}}]}}`
	result := `{"type":"result","result":%q}`

	stream := func(lines ...string) []byte { return []byte(strings.Join(lines, "\n")) }
	task := evalTask{ID: "probe", Expected: "skills list"}

	t.Run("right path, flags and all", func(t *testing.T) {
		got := parseHelpEvalOutput(task, stream(
			fmt.Sprintf(helpCall, ""),
			fmt.Sprintf(helpCall, "skills list"),
			fmt.Sprintf(result, "skmr skills list --all"),
		))
		if !got.Correct() {
			t.Fatalf("a correct answer was scored wrong: %+v", got)
		}
		if got.HelpCalls != 2 {
			t.Errorf("HelpCalls = %d, want 2", got.HelpCalls)
		}
	})

	t.Run("wrong path", func(t *testing.T) {
		got := parseHelpEvalOutput(task, stream(fmt.Sprintf(result, "skmr skills search pdf")))
		if got.Correct() {
			t.Fatal("a different command was scored correct")
		}
	})

	t.Run("over the cap is the cap", func(t *testing.T) {
		lines := make([]string, 0, helpEvalCallCap+1)
		for i := 0; i <= helpEvalCallCap; i++ {
			lines = append(lines, fmt.Sprintf(helpCall, ""))
		}
		lines = append(lines, fmt.Sprintf(result, "skmr skills list"))
		got := parseHelpEvalOutput(task, stream(lines...))
		if got.Correct() {
			t.Fatal("a run over the call cap was scored correct")
		}
		if got.HelpCalls != helpEvalCallCap {
			t.Errorf("HelpCalls = %d, want the cap %d", got.HelpCalls, helpEvalCallCap)
		}
	})

	t.Run("anything but --help is a violation", func(t *testing.T) {
		got := parseHelpEvalOutput(task, stream(
			`{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"./skmr skills list"}}]}}`,
			fmt.Sprintf(result, "skmr skills list"),
		))
		if got.Violation == "" || got.Correct() {
			t.Fatalf("a real command was not refused by the harness: %+v", got)
		}
	})

	t.Run("an answer is one line", func(t *testing.T) {
		got := parseHelpEvalOutput(task, stream(fmt.Sprintf(result, "skmr skills list\n这会把它们列出来。")))
		if got.Answer != "" || got.Correct() {
			t.Fatalf("a multi-line reply was accepted as an answer: %+v", got)
		}
	})
}

// `skmr help env` is the same page as `skmr … --help`, and the ruler counts it as a
// call rather than a violation; `ls` is not.
func TestHelpEvalCountsBothSpellingsOfHelp(t *testing.T) {
	for _, command := range []string{"./skmr --help", "./skmr skill --help", "./skmr help env", "skmr help"} {
		if !isHelpCall(command) {
			t.Errorf("%q should be a help call", command)
		}
	}
	for _, command := range []string{"ls -la", "./skmr skills list", "./skillmaster", "cat help"} {
		if isHelpCall(command) {
			t.Errorf("%q is not a help call", command)
		}
	}
}

package main

// v34: the read-side views (`ls`, `find`, `stat`, `head`, `tail`), `read --limit/--raw`, `grep` with
// context, and `edit --regex`.
//
// The fakes answer the author plane's two paths — the manifest and one file — because every command
// here addresses a draft pin, which is where these views are used.

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
)

// viewFake serves a manifest and file contents; `listing` is the JSON body of the manifest call and
// `contents` maps a relpath to its bytes.
type viewFake struct {
	listing  string
	contents map[string]string
	grep     string
	puts     int
	putBody  string
	queries  []string
}

func (f *viewFake) start(t *testing.T) *viewFake {
	t.Helper()
	withTempHome(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/grep"):
			f.queries = append(f.queries, r.URL.RawQuery)
			_, _ = w.Write([]byte(f.grep))
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/metadata"):
			_, _ = w.Write([]byte(`{"name":"hello","namespace":{"slug":"me"},` +
				`"version":{"name":"1.0.0","digest":"abc"},"access":"browse","permissions":[]}`))
		case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/files"):
			_, _ = w.Write([]byte(f.listing))
		case r.Method == http.MethodGet && strings.Contains(r.URL.Path, "/files/"):
			relpath := r.URL.Path[strings.Index(r.URL.Path, "/files/")+len("/files/"):]
			body, ok := f.contents[relpath]
			if !ok {
				w.WriteHeader(http.StatusNotFound)
				_, _ = w.Write([]byte(`{"error":{"code":"file_not_found","message":"no"}}`))
				return
			}
			_, _ = w.Write([]byte(body))
		case r.Method == http.MethodPut:
			f.puts++
			body, _ := io.ReadAll(r.Body)
			f.putBody = string(body)
			_, _ = w.Write([]byte(`{"version":{"code":"K3M9QX2P","digest":"sha256:abcdef1234567890"}}`))
		default:
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":{"code":"not_found","message":"no"}}`))
		}
	})
	return f
}

const viewListing = `{"files":[
 {"relpath":"SKILL.md","sha256":"aaaabbbbccccddddeeeeffff00001111","size":12,"is_binary":false},
 {"relpath":"assets/diagram.png","sha256":"11112222333344445555666677778888","size":2048,"is_binary":true},
 {"relpath":"references/api.md","sha256":"99990000111122223333444455556666","size":100,"is_binary":false},
 {"relpath":"references/split.md","sha256":"77778888999900001111222233334444","size":50,"is_binary":false}
]}`

func viewFakeWith(t *testing.T) *viewFake {
	return (&viewFake{
		listing:  viewListing,
		contents: map[string]string{"SKILL.md": "one\ntwo\nthree\nfour\nfive\n"},
	}).start(t)
}

func TestLsListsOneLevelAndKeepsDirectoriesFirst(t *testing.T) {
	viewFakeWith(t)

	stdout, stderr, status := runCaptured(t, "skill", "ls", "me/hello@K3M9QX2P")

	if status != 0 {
		t.Fatalf("status = %d", status)
	}
	want := "assets/\nreferences/\nSKILL.md\n"
	if stdout != want {
		t.Fatalf("stdout = %q, want %q", stdout, want)
	}
	if !strings.Contains(stderr, "按 me/hello@K3M9QX2P") {
		t.Fatalf("the version note is missing from stderr: %q", stderr)
	}
}

// One note, not two: `listing` prints it for every caller, and `ls` used to print it again.
func TestLsPrintsTheVersionNoteOnce(t *testing.T) {
	viewFakeWith(t)

	_, stderr, status := runCaptured(t, "skill", "ls", "me/hello@K3M9QX2P")
	if status != 0 {
		t.Fatalf("status = %d", status)
	}
	if n := strings.Count(stderr, "按 me/hello@K3M9QX2P"); n != 1 {
		t.Fatalf("the note appears %d times: %q", n, stderr)
	}
}

func TestLsSubdirectoryAndDetails(t *testing.T) {
	viewFakeWith(t)

	stdout, _, status := runCaptured(t, "skill", "ls", "me/hello@K3M9QX2P", "references", "-l")
	if status != 0 {
		t.Fatalf("status = %d", status)
	}
	want := "text\t100\treferences/api.md\ntext\t50\treferences/split.md\n"
	if stdout != want {
		t.Fatalf("stdout = %q, want %q", stdout, want)
	}

	assets, _, _ := runCaptured(t, "skill", "ls", "me/hello@K3M9QX2P", "assets", "-l")
	if !strings.Contains(assets, "bin\t2048\tassets/diagram.png") {
		t.Errorf("ls -l of assets is missing the binary row:\n%s", assets)
	}

	root, _, _ := runCaptured(t, "skill", "ls", "me/hello@K3M9QX2P", "-l")
	for _, row := range []string{"dir\t-\tassets/", "dir\t-\treferences/", "body\t12\tSKILL.md"} {
		if !strings.Contains(root, row) {
			t.Errorf("ls -l is missing %q:\n%s", row, root)
		}
	}
}

func TestLsRecursiveAndItsTwoErrors(t *testing.T) {
	viewFakeWith(t)

	stdout, _, status := runCaptured(t, "skill", "ls", "me/hello@K3M9QX2P", "-R")
	if status != 0 {
		t.Fatalf("status = %d", status)
	}
	// Byte order, so `SKILL.md` (uppercase) sorts before the lowercase directories.
	want := "SKILL.md\nassets/\nassets/diagram.png\nreferences/\nreferences/api.md\nreferences/split.md\n"
	if stdout != want {
		t.Fatalf("stdout = %q, want %q", stdout, want)
	}

	_, stderr, status := runCaptured(t, "skill", "ls", "me/hello@K3M9QX2P", "nowhere")
	if status != 1 || !strings.Contains(stderr, "看根目录") {
		t.Fatalf("missing directory: status = %d, stderr = %q", status, stderr)
	}
	_, stderr, status = runCaptured(t, "skill", "ls", "me/hello@K3M9QX2P", "SKILL.md")
	if status != 1 || !strings.Contains(stderr, "是文件") || !strings.Contains(stderr, "skill read") ||
		!strings.Contains(stderr, "skill cat") {
		t.Fatalf("file as directory: status = %d, stderr = %q", status, stderr)
	}
}

func TestFindMatchesNamesAndFolders(t *testing.T) {
	viewFakeWith(t)

	cases := []struct {
		args []string
		want string
	}{
		{[]string{"*.md"}, "SKILL.md\nreferences/api.md\nreferences/split.md\n"}, // basename match
		{[]string{"references/*.md"}, "references/api.md\nreferences/split.md\n"},
		{[]string{"API*", "-i"}, "references/api.md\n"},
		{[]string{"*", "--path", "references/"}, "references/api.md\nreferences/split.md\n"},
		// `--path` is a path-segment boundary, the same rule the server uses for `grep`: the path
		// itself or anything under it, never a bare string prefix.
		{[]string{"*", "--path", "SKILL.md"}, "SKILL.md\n"},
		// A leading or trailing `/` is tolerated, and a blank value filters nothing — the same two
		// answers the server gives `grep --path`.
		{[]string{"*", "--path", "/references/"}, "references/api.md\nreferences/split.md\n"},
		{[]string{"*", "--path", "  "}, "SKILL.md\nassets/diagram.png\nreferences/api.md\nreferences/split.md\n"},
	}
	for _, c := range cases {
		stdout, _, status := runCaptured(t, append([]string{"skill", "find", "me/hello@K3M9QX2P"}, c.args...)...)
		if status != 0 {
			t.Fatalf("find %v: status = %d", c.args, status)
		}
		if stdout != c.want {
			t.Errorf("find %v = %q, want %q", c.args, stdout, c.want)
		}
	}

	// A string prefix is not a directory: `references/a` names no directory here, so it matches
	// nothing — the same answer `grep --path references/a` gives on the server.
	stdout, _, status := runCaptured(t, "skill", "find", "me/hello@K3M9QX2P", "*", "--path", "references/a")
	if status != 1 || stdout != "" {
		t.Fatalf("partial-segment path: status = %d, stdout = %q", status, stdout)
	}

	// Spaces are not trimmed from a non-blank value, matching the server: ` references ` names
	// nothing, so it matches nothing.
	stdout, _, status = runCaptured(t, "skill", "find", "me/hello@K3M9QX2P", "*", "--path", " references ")
	if status != 1 || stdout != "" {
		t.Fatalf("padded path: status = %d, stdout = %q", status, stdout)
	}

	_, stderr, status := runCaptured(t, "skill", "find", "me/hello@K3M9QX2P", "zzz")
	if status != 1 || !strings.Contains(stderr, "没有匹配") {
		t.Fatalf("no match: status = %d, stderr = %q", status, stderr)
	}
}

func TestStatPrintsHexDigestAndStripsThePrefix(t *testing.T) {
	(&viewFake{listing: `{"files":[
	 {"relpath":"SKILL.md","sha256":"sha256:0123456789abcdef","size":3,"is_binary":false}
	]}`}).start(t)

	stdout, _, status := runCaptured(t, "skill", "stat", "me/hello@K3M9QX2P", "SKILL.md")
	if status != 0 || !strings.Contains(stdout, "SKILL.md\t3\ttext\t0123456789ab") {
		t.Fatalf("short column = %q (status %d)", stdout, status)
	}
	full, _, _ := runCaptured(t, "skill", "stat", "me/hello@K3M9QX2P", "SKILL.md", "--digest")
	if !strings.Contains(full, "0123456789abcdef") || strings.Contains(full, "sha256:") {
		t.Fatalf("--digest = %q", full)
	}

	// A server that predates the field leaves the cell empty; `-` says so instead.
	(&viewFake{listing: `{"files":[{"relpath":"SKILL.md","size":3,"is_binary":false}]}`}).start(t)
	old, _, _ := runCaptured(t, "skill", "stat", "me/hello@K3M9QX2P", "SKILL.md")
	if !strings.Contains(old, "SKILL.md\t3\ttext\t-") {
		t.Fatalf("a manifest without sha256 = %q", old)
	}
}

func TestStatPrintsFileAndDirectoryRows(t *testing.T) {
	viewFakeWith(t)

	stdout, _, status := runCaptured(t, "skill", "stat", "me/hello@K3M9QX2P", "SKILL.md", "references/")
	if status != 0 {
		t.Fatalf("status = %d", status)
	}
	if !strings.Contains(stdout, "SKILL.md\t12\ttext\taaaabbbbcccc") {
		t.Errorf("file row = %q", stdout)
	}
	if !strings.Contains(stdout, "references/\t150\tdir\tfiles:2") {
		t.Errorf("directory row = %q", stdout)
	}

	full, _, _ := runCaptured(t, "skill", "stat", "me/hello@K3M9QX2P", "SKILL.md", "--digest")
	if !strings.Contains(full, "aaaabbbbccccddddeeeeffff00001111") {
		t.Errorf("--digest = %q", full)
	}

	_, stderr, status := runCaptured(t, "skill", "stat", "me/hello@K3M9QX2P", "nope.md")
	if status != 1 || !strings.Contains(stderr, "看清单") {
		t.Fatalf("missing path: status = %d, stderr = %q", status, stderr)
	}
}

func TestHeadAndTailAreRaw(t *testing.T) {
	viewFakeWith(t)

	// The default is ten lines, and the sample has five: a bare `head` prints all of them.
	stdout, _, status := runCaptured(t, "skill", "head", "me/hello@K3M9QX2P", "SKILL.md")
	if status != 0 || stdout != "one\ntwo\nthree\nfour\nfive\n" {
		t.Fatalf("head without -n = %q (status %d)", stdout, status)
	}

	stdout, _, status = runCaptured(t, "skill", "head", "me/hello@K3M9QX2P", "SKILL.md", "-n", "2")
	if status != 0 || stdout != "one\ntwo\n" {
		t.Fatalf("head = %q (status %d)", stdout, status)
	}
	stdout, _, status = runCaptured(t, "skill", "tail", "me/hello@K3M9QX2P", "SKILL.md", "-n", "2")
	if status != 0 || stdout != "four\nfive\n" {
		t.Fatalf("tail = %q (status %d)", stdout, status)
	}
	stdout, _, status = runCaptured(t, "skill", "head", "me/hello@K3M9QX2P", "SKILL.md", "-n", "50")
	if status != 0 || stdout != "one\ntwo\nthree\nfour\nfive\n" {
		t.Fatalf("head past the end = %q (status %d)", stdout, status)
	}
}

// binaryFake serves one version whose manifest marks its only file as binary, and answers the author
// plane (a draft pin), which is where the binary flag is known at all.
func binaryFake(t *testing.T) *viewFake {
	return (&viewFake{
		listing:  `{"files":[{"relpath":"assets/diagram.png","sha256":"sha256:aa","size":3,"is_binary":true}]}`,
		contents: map[string]string{"assets/diagram.png": "\xff\xfe\x00"},
	}).start(t)
}

func TestHeadTailAndRawReadRefuseBinary(t *testing.T) {
	binaryFake(t)

	for _, argv := range [][]string{
		{"skill", "head", "me/hello@draft:01JKMNPQ", "assets/diagram.png"},
		{"skill", "tail", "me/hello@draft:01JKMNPQ", "assets/diagram.png"},
		{"skill", "read", "me/hello@draft:01JKMNPQ", "assets/diagram.png", "--raw"},
	} {
		stdout, stderr, status := runCaptured(t, argv...)
		if status != 1 || !strings.Contains(stderr, "二进制文件") {
			t.Errorf("%v: status = %d, stdout = %q, stderr = %q", argv, status, stdout, stderr)
		}
		if stdout != "" {
			t.Errorf("%v printed bytes of a binary file: %q", argv, stdout)
		}
	}
}

// A published version's read has no manifest, so the refusal cannot come from `is_binary`. The
// bytes decide: a `.png` is not valid UTF-8, and it used to be printed as mojibake.
func TestAPublishedBinaryIsRefusedToo(t *testing.T) {
	(&viewFake{contents: map[string]string{
		"assets/diagram.png": "\xff\xfe\x00",
		"SKILL.md":           "one\ntwo\n",
	}}).start(t)

	for _, argv := range [][]string{
		{"skill", "read", "me/hello@1.0.0", "assets/diagram.png", "--raw"},
		{"skill", "head", "me/hello@1.0.0", "assets/diagram.png"},
	} {
		stdout, stderr, status := runCaptured(t, argv...)
		if status != 1 || !strings.Contains(stderr, "二进制文件") || stdout != "" {
			t.Errorf("%v: status = %d, stdout = %q, stderr = %q", argv, status, stdout, stderr)
		}
	}
	if text, _, status := runCaptured(t, "skill", "read", "me/hello@1.0.0", "SKILL.md"); status != 0 ||
		!strings.Contains(text, "1\tone") {
		t.Fatalf("a published text file must still read: %q (status %d)", text, status)
	}
}

func TestReadRawPastTheEndWritesNothingToStdout(t *testing.T) {
	viewFakeWith(t)

	stdout, stderr, status := runCaptured(t, "skill", "read", "me/hello@K3M9QX2P", "SKILL.md",
		"--raw", "--offset", "99")
	if status != 0 {
		t.Fatalf("status = %d", status)
	}
	// `--raw` promises the stored bytes on stdout; a sentence about the offset is not them.
	if stdout != "" {
		t.Fatalf("stdout = %q", stdout)
	}
	if !strings.Contains(stderr, "只有 5 行") || !strings.Contains(stderr, "按 me/hello@K3M9QX2P") {
		t.Fatalf("stderr = %q", stderr)
	}

	// An empty file has no bytes to give; a sentence about line 99 would be the only stdout and
	// `--raw` promises the stored bytes and nothing else.
	(&viewFake{
		listing:  `{"files":[{"relpath":"SKILL.md","sha256":"sha256:aa","size":0,"is_binary":false}]}`,
		contents: map[string]string{"SKILL.md": ""},
	}).start(t)
	empty, _, emptyStatus := runCaptured(t, "skill", "read", "me/hello@K3M9QX2P", "SKILL.md",
		"--raw", "--offset", "99")
	if emptyStatus != 0 || empty != "" {
		t.Fatalf("empty file: status = %d, stdout = %q", emptyStatus, empty)
	}
}

func TestReadRawIsByteExactAndLimitCutsTheRange(t *testing.T) {
	viewFakeWith(t)

	stdout, stderr, status := runCaptured(t, "skill", "read", "me/hello@K3M9QX2P", "SKILL.md", "--raw")
	if status != 0 || stdout != "one\ntwo\nthree\nfour\nfive\n" {
		t.Fatalf("read --raw = %q (status %d)", stdout, status)
	}
	if strings.Contains(stdout, "\t") || !strings.Contains(stderr, "按 me/hello@K3M9QX2P") {
		t.Fatalf("raw output or note is wrong: stdout %q stderr %q", stdout, stderr)
	}
	if cat, _, catStatus := runCaptured(t, "skill", "cat", "me/hello@K3M9QX2P", "SKILL.md"); catStatus != 0 || cat != stdout {
		t.Fatalf("read --raw and cat disagree: %q vs %q (status %d)", stdout, cat, catStatus)
	}

	stdout, _, status = runCaptured(t, "skill", "read", "me/hello@K3M9QX2P", "SKILL.md",
		"--raw", "--offset", "2", "--limit", "2")
	if status != 0 || stdout != "two\nthree\n" {
		t.Fatalf("read --raw range = %q (status %d)", stdout, status)
	}

	numbered, _, status := runCaptured(t, "skill", "read", "me/hello@K3M9QX2P", "SKILL.md",
		"--offset", "2", "--limit", "2")
	if status != 0 || !strings.Contains(numbered, "2\ttwo") || strings.Contains(numbered, "4\tfour") {
		t.Fatalf("read --limit = %q (status %d)", numbered, status)
	}
}

func TestGrepRendersContextTheWayGrepDoes(t *testing.T) {
	fake := (&viewFake{
		listing: viewListing,
		grep: `{"digest":"sha256:aa","pin":"K3M9QX2P","truncated":false,"hits":[
		 {"path":"SKILL.md","line":1,"text":"one","match":false},
		 {"path":"SKILL.md","line":2,"text":"needle","match":true},
		 {"path":"SKILL.md","line":3,"text":"three","match":false},
		 {"path":"SKILL.md","line":9,"text":"needle","match":true}]}`,
	}).start(t)

	stdout, _, status := runCaptured(t, "skill", "grep", "me/hello@K3M9QX2P", "needle", "-C", "1", "-n")
	if status != 0 {
		t.Fatalf("status = %d, stdout = %s", status, stdout)
	}
	want := "SKILL.md-1-one\nSKILL.md:2:needle\nSKILL.md-3-three\n--\nSKILL.md:9:needle\n"
	if stdout != want {
		t.Fatalf("stdout = %q, want %q", stdout, want)
	}
	query := fake.queries[0]
	if !strings.Contains(query, "before=1") || !strings.Contains(query, "after=1") {
		t.Fatalf("context did not reach the server: %s", query)
	}

	_, stderr, status := runCaptured(t, "skill", "grep", "me/hello@K3M9QX2P", "needle", "-C", "1", "-A", "2")
	if status != 2 || !strings.Contains(stderr, "-C 不能跟 -A / -B 同时用") {
		t.Fatalf("-C with -A: status = %d, stderr = %q", status, stderr)
	}
}

func TestGrepSendsEveryFilterToTheServer(t *testing.T) {
	fake := (&viewFake{listing: viewListing,
		grep: `{"digest":"sha256:aa","pin":"K3M9QX2P","truncated":false,"hits":[]}`}).start(t)

	_, _, status := runCaptured(t, "skill", "grep", "me/hello@K3M9QX2P", "alpha",
		"-e", "beta", "--exclude", "*.md", "--max-count", "2")

	if status != 1 {
		t.Fatalf("status = %d, want 1 for no hits", status)
	}
	query := fake.queries[0]
	for _, want := range []string{"pattern=alpha", "pattern=beta", "exclude=%2A.md", "max=2"} {
		if !strings.Contains(query, want) {
			t.Errorf("request is missing %q: %s", want, query)
		}
	}
}

func TestGrepRejectsASecondPattern(t *testing.T) {
	viewFakeWith(t)

	_, stderr, status := runCaptured(t, "skill", "grep", "me/hello@K3M9QX2P", "alpha", "beta")
	if status != 2 || !strings.Contains(stderr, "只接一个 pattern") {
		t.Fatalf("status = %d, stderr = %q", status, stderr)
	}
}

// GNU grep's `-e`-only form: no positional pattern at all.
func TestGrepAcceptsOnlyDashEPatterns(t *testing.T) {
	fake := (&viewFake{listing: viewListing,
		grep: `{"digest":"sha256:aa","pin":"K3M9QX2P","truncated":false,"hits":[
		 {"path":"SKILL.md","line":1,"text":"alpha","match":true}]}`}).start(t)

	_, _, status := runCaptured(t, "skill", "grep", "me/hello@K3M9QX2P", "-e", "alpha", "-e", "beta")
	if status != 0 {
		t.Fatalf("status = %d", status)
	}
	query := fake.queries[0]
	for _, want := range []string{"pattern=alpha", "pattern=beta"} {
		if !strings.Contains(query, want) {
			t.Errorf("request is missing %q: %s", want, query)
		}
	}
}

func TestGrepSaysWhenTheResultWasTruncated(t *testing.T) {
	(&viewFake{listing: viewListing,
		grep: `{"digest":"sha256:aa","pin":"K3M9QX2P","truncated":true,"hits":[
		 {"path":"SKILL.md","line":1,"text":"needle","match":true}]}`}).start(t)

	stdout, stderr, status := runCaptured(t, "skill", "grep", "me/hello@K3M9QX2P", "needle")
	if status != 0 || !strings.Contains(stdout, "SKILL.md") {
		t.Fatalf("status = %d, stdout = %q", status, stdout)
	}
	if !strings.Contains(stderr, "截断") {
		t.Fatalf("a truncated list looks complete: %q", stderr)
	}
}

// A pattern RE2 cannot run comes back as a 400 `invalid_request` whose `details[].issue` is
// `bad_pattern`. The help page promises the caller a next step, so the runtime has to be the one
// that says it: the generic 「提交的内容有问题」 names nothing the caller can change.
func TestGrepSaysWhatToDoWithAPatternRE2Refuses(t *testing.T) {
	withTempHome(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/grep") {
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"error":{"code":"invalid_request","message":"refused",` +
				`"details":[{"field":"pattern","issue":"bad_pattern"}]}}`))
			return
		}
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"error":{"code":"not_found","message":"no"}}`))
	})

	stdout, stderr, status := runCaptured(t, "skill", "grep", "me/hello@K3M9QX2P", "[")
	if status != 1 || stdout != "" {
		t.Fatalf("status = %d, stdout = %q", status, stdout)
	}
	if !strings.Contains(stderr, "RE2") || !strings.Contains(stderr, "-F") {
		t.Fatalf("the next step is missing: %q", stderr)
	}
}

// **`--raw` has no byte cap.** It promises the stored bytes and nothing else, so the 128 KiB budget
// the numbered rendering keeps does not apply — a file twice that size comes out whole.
func TestRawReadHasNoByteCap(t *testing.T) {
	big := strings.Repeat("y", 2*maxContentBytes)
	(&viewFake{listing: viewListing,
		contents: map[string]string{"SKILL.md": big}}).start(t)

	stdout, _, status := runCaptured(t, "skill", "read", "me/hello@K3M9QX2P", "SKILL.md", "--raw")
	if status != 0 {
		t.Fatalf("status = %d", status)
	}
	if len(stdout) != len(big) {
		t.Fatalf("--raw printed %d bytes of a %d-byte file", len(stdout), len(big))
	}
}

func TestWriteOnNewNeedsAName(t *testing.T) {
	viewFakeWith(t)

	_, stderr, status := runCaptured(t, "skill", "write", "me/hello@new", "SKILL.md")
	if status != 2 || !strings.Contains(stderr, "--name") {
		t.Fatalf("status = %d, stderr = %q", status, stderr)
	}
}

// A pattern that does not compile is the caller's own typo; it must be answered before the file is
// asked for, or an unreachable server gets to answer first.
func TestEditCompilesThePatternBeforeAnyRequest(t *testing.T) {
	withTempHome(t)
	pointAtServer(t, func(w http.ResponseWriter, r *http.Request) {
		t.Errorf("a request was sent: %s", r.URL)
	})

	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--regex", "--old", "(", "--new", "x")
	if status != 2 || !strings.Contains(stderr, "正则不合法") {
		t.Fatalf("status = %d, stderr = %q", status, stderr)
	}
}

func TestEditRegexMatchesAndExpands(t *testing.T) {
	fake := (&viewFake{
		listing:  `{"files":[{"relpath":"SKILL.md","sha256":"aa","size":10,"is_binary":false}]}`,
		contents: map[string]string{"SKILL.md": "run npm install now\n"},
	}).start(t)

	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--regex", "--old", `npm (install)`, "--new", "npm $1 --save")

	if status != 0 {
		t.Fatalf("status = %d, stderr = %s", status, stderr)
	}
	if fake.putBody != "run npm install --save now\n" {
		t.Fatalf("PUT body = %q", fake.putBody)
	}

	_, stderr, status = runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--regex", "--old", "(", "--new", "x")
	if status != 2 || !strings.Contains(stderr, "正则不合法") {
		t.Fatalf("bad pattern: status = %d, stderr = %q", status, stderr)
	}
}

func TestEditRegexRefusesSeveralMatchesWithoutReplaceAll(t *testing.T) {
	fake := (&viewFake{
		listing:  `{"files":[{"relpath":"SKILL.md","sha256":"aa","size":10,"is_binary":false}]}`,
		contents: map[string]string{"SKILL.md": "a1\nb2\n"},
	}).start(t)

	_, stderr, status := runCaptured(t, "skill", "edit", "me/hello@K3M9QX2P", "SKILL.md",
		"--regex", "--old", `\w\d`, "--new", "x")

	if status != 1 || !strings.Contains(stderr, "出现了 2 次") {
		t.Fatalf("status = %d, stderr = %q", status, stderr)
	}
	// A regex is not a substring; searching for it printed an empty line list.
	if !strings.Contains(stderr, "在第 1、2 行") {
		t.Fatalf("the lines are missing: %q", stderr)
	}
	if fake.puts != 0 {
		t.Fatalf("%d PUTs were sent", fake.puts)
	}

	_ = json.Valid // keep the import honest if the fake grows
}

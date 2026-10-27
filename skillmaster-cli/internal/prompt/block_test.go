package prompt

import (
	"bytes"
	"strings"
	"testing"
)

func TestTheBlockIsTheApprovedText(t *testing.T) {
	block := string(Block())
	for _, want := range []string{Begin, End,
		"SkillMaster 管我的全部 skill",
		"先 skillmaster skills search",
		"更多命令见 skillmaster help"} {
		if !strings.Contains(block, want) {
			t.Errorf("the block does not contain %q", want)
		}
	}
	if !strings.HasSuffix(block, "\n") {
		t.Error("the block does not end in a newline")
	}
	if strings.Count(block, Begin) != 1 || strings.Count(block, End) != 1 {
		t.Error("the block does not carry exactly one pair of markers")
	}
	if Digest() != DigestOf(Block()) {
		t.Error("Digest is not the digest of Block")
	}
	if !strings.HasPrefix(Digest(), "sha256:") || len(Digest()) != len("sha256:")+64 {
		t.Errorf("digest %q is not a sha256", Digest())
	}
}

func TestLocateFindsTheMarkedRange(t *testing.T) {
	file := []byte("前文\n\n" + string(Block()) + "\n后文\n")
	region, err := Locate("f", file)
	if err != nil {
		t.Fatalf("Locate: %v", err)
	}
	got := string(file[region.Start:region.End])
	if got != string(Block()) {
		t.Errorf("region = %q, want the block", got)
	}
}

func TestLocateSaysWhenThereIsNoBlock(t *testing.T) {
	if _, err := Locate("f", []byte("只有正文\n")); err != ErrMissing {
		t.Fatalf("err = %v, want ErrMissing", err)
	}
}

func TestLocateRefusesTwoBlocksAndNamesBoth(t *testing.T) {
	file := []byte(string(Block()) + "中间\n" + string(Block()))
	_, err := Locate("f", file)
	ambiguous, ok := err.(*AmbiguousError)
	if !ok {
		t.Fatalf("err = %v, want *AmbiguousError", err)
	}
	// Line 1 is the first begin, line 5 the first end, line 7 the second begin, line 11 the second
	// end (the block is five lines and each copy is followed by a newline but the last).
	want := []int{1, 5, 7, 11}
	if len(ambiguous.Lines) != len(want) {
		t.Fatalf("lines = %v, want %v", ambiguous.Lines, want)
	}
	for i := range want {
		if ambiguous.Lines[i] != want[i] {
			t.Fatalf("lines = %v, want %v", ambiguous.Lines, want)
		}
	}
}

func TestReplaceKeepsEverythingOutsideTheMarkers(t *testing.T) {
	file := []byte("前文\n\n" + string(Block()) + "\n后文\n")
	region, err := Locate("f", file)
	if err != nil {
		t.Fatal(err)
	}
	replacement := []byte("<!-- skillmaster:begin -->\n新的一行\n<!-- skillmaster:end -->\n")
	out := Replace(file, region, replacement)
	if string(out) != "前文\n\n"+string(replacement)+"\n后文\n" {
		t.Errorf("Replace produced %q", out)
	}
}

func TestReplaceKeepsWindowsLineEndings(t *testing.T) {
	block := WithLineEnding(Block(), true)
	file := []byte("前文\r\n\r\n" + string(block) + "\r\n后文\r\n")
	region, err := Locate("f", file)
	if err != nil {
		t.Fatal(err)
	}
	if !region.CRLF {
		t.Fatal("Locate did not notice the file uses CRLF")
	}
	out := Replace(file, region, Block())
	if bytes.Contains(out, []byte("\n")) && bytes.Contains(out, []byte("\r\n")) {
		// Mixed is only acceptable if every \n is part of a \r\n; check that explicitly.
		normalised := bytes.ReplaceAll(out, []byte("\r\n"), []byte(""))
		if bytes.Contains(normalised, []byte("\n")) {
			t.Errorf("Replace mixed line endings: %q", out)
		}
	}
}

func TestAppendLeavesOneBlankLine(t *testing.T) {
	got := string(Append([]byte("上一段"), Block(), false))
	if got != "上一段\n\n"+string(Block()) {
		t.Errorf("Append produced %q", got)
	}
	if got := string(Append(nil, Block(), false)); got != string(Block()) {
		t.Errorf("Append to an empty file produced %q", got)
	}
}

func TestBlockInNormalisesLineEndings(t *testing.T) {
	file := WithLineEnding(Block(), true)
	block, _, err := BlockIn("f", file)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(block, Normalise(Block())) {
		t.Error("a CRLF copy of the block did not compare equal to the block")
	}
}

func TestReplaceUsesTheFilesEndingNotTheBlocks(t *testing.T) {
	// The case the whole-file rule exists for: a CRLF file holding a block pasted with LF.
	lfBlock := Block()
	file := []byte("前文\r\n\r\n" + string(lfBlock) + "\r\n后文\r\n")
	region, err := Locate("f", file)
	if err != nil {
		t.Fatal(err)
	}
	if !region.CRLF {
		t.Fatal("a CRLF file holding an LF block was read as LF")
	}
	out := Replace(file, region, lfBlock)
	if bytes.Contains(bytes.ReplaceAll(out, []byte("\r\n"), nil), []byte("\n")) {
		t.Errorf("Replace mixed line endings: %q", out)
	}
}

func TestRemoveKeepsEverythingOutsideTheMarkers(t *testing.T) {
	file := []byte("前文\n\n" + string(Block()) + "\n后文\n")
	region, err := Locate("f", file)
	if err != nil {
		t.Fatal(err)
	}
	out := string(Remove(file, region))
	if out != "前文\n\n\n后文\n" {
		t.Errorf("Remove produced %q, want the markers and their block gone but the surrounding bytes intact", out)
	}
}

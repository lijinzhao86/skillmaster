package prompt

import (
	"bytes"
	"os"
	"strings"
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
)

func TestTheBlockIsTheApprovedText(t *testing.T) {
	block := string(Block())
	for _, want := range []string{
		// **The block is a reminder, not a second rulebook** (2026-11-03). The skill is required
		// to be installed and is the authority for 判据、例外、流程与回复格式; carrying any of
		// those here gives the agent two versions to choose between, and it picks the looser one.
		// What the block alone can do is make the agent load the skill *before* it has the rules —
		// so that is all it says.
		"铁律",
		"任何任务都先调用 SkillMaster",
		"照它的要求做",
		// The fail-safe survives: 「拿不准就调用」 is the one line that does not need the skill's
		// contents to be actionable, which is exactly the moment the block exists for.
		"拿不准就调用",
		// **The three excuses are the one thing the block must not drop** (2026-11-03):
		// issue #30387 measured skills that overlap a built-in capability being ignored ~50% of
		// the time, and naming the excuse is what the workaround there did. The description carries
		// them too, but the description is read only when the list is in context; the block is read
		// every turn, which is why they belong here and not only there.
		"这次很简单",
		"手边有现成工具",
		"刚才已经加载过"} {
		if !strings.Contains(block, want) {
			t.Errorf("the block does not contain %q", want)
		}
	}
	if !strings.HasSuffix(block, "\n") {
		t.Error("the block does not end in a newline")
	}
	// **And it names the command this binary answers to** (ADR 0113). The block is the text an agent
	// follows for every task; one old name in it is a rule that reads fine and fails at run time.
	// **Any occurrence, not just one followed by a space.** The guard test learned this the hard way:
	// `升级 skillmaster。` has no space after the name, and that is exactly how it would appear in a
	// Chinese sentence.
	if strings.Contains(block, config.LegacyName) {
		t.Errorf("the block still names the old command %q", config.LegacyName)
	}
	// **No mark-up** (ADR 0110): the paragraph is the person's, not a machine-managed region.
	for _, unwanted := range []string{"<!-- skillmaster:begin -->", "<!-- skillmaster:end -->", "<!--"} {
		if strings.Contains(block, unwanted) {
			t.Errorf("the block still carries %q", unwanted)
		}
	}
	// **No conditional escape hatch.** 「拿不准就查」 was the old wording; leaving it next to
	// 「不判断「拿不拿得准」」 would hand a model the option it was just denied (ADR 0109).
	if strings.Contains(block, "拿不准就查") {
		t.Error("the block still offers 「拿不准就查」")
	}
	// **No write recipes** (2026-11-03). The step-by-step commands live in the guide the block
	// points at; carrying them here lets an agent act without ever loading it.
	for _, unwanted := range []string{"skill write", "skill put", "skill create", "skill edit", "skills search", "skill invoke", "skill read", "判据", "例外", "纯闲聊", "常识问答", "回复格式", "回执", "skillmaster/"} {
		if strings.Contains(block, unwanted) {
			t.Errorf("the block still carries the write recipe %q", unwanted)
		}
	}
	if Digest() != DigestOf(Block()) {
		t.Error("Digest is not the digest of Block")
	}
	if !strings.HasPrefix(Digest(), "sha256:") || len(Digest()) != len("sha256:")+64 {
		t.Errorf("digest %q is not a sha256", Digest())
	}
}

// **The shape of what `prompt print` writes** (v27 acceptance 2): no mark-up, no leading blank line,
// and exactly one newline at the end. A leading blank would push a blank line into the person's file
// wherever they paste it; two trailing newlines would leave a gap below the paragraph.
func TestTheBlockStartsAndEndsCleanly(t *testing.T) {
	block := Block()
	if len(block) == 0 {
		t.Fatal("the block is empty")
	}
	if block[0] == '\n' {
		t.Error("the block starts with a blank line")
	}
	if strings.HasSuffix(string(block), "\n\n") {
		t.Error("the block ends with more than one newline")
	}
}

func TestNormaliseIsWhatMakesACrlfCopyEqual(t *testing.T) {
	crlf := []byte(strings.ReplaceAll(string(Block()), "\n", "\r\n"))
	if !strings.Contains(string(Normalise(crlf)), strings.TrimSuffix(string(Block()), "\n")) {
		t.Error("a CRLF copy did not normalise back to the block")
	}
}

// **What `prompt print` emits is this file, byte for byte** (v27 acceptance 1). The binary embeds
// `block.md`, so the only way the two could drift is a build that skipped the embed — this test is
// what makes 「两个来源逐字节一致」 a checked fact rather than a reading of the source tree.
func TestThePrintedBlockIsTheFileOnDisk(t *testing.T) {
	onDisk, err := os.ReadFile("block.md")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(Normalise(onDisk), Block()) {
		t.Error("the embedded block and block.md have drifted apart")
	}
}

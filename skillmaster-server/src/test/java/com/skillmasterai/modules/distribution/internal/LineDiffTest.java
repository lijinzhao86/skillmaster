package com.skillmasterai.modules.distribution.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.modules.distribution.SkillDiff.Hunk;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The line diff, at the shape the wire carries.
 *
 * <p>Tests the <em>conversion</em> rather than the algorithm: which lines change is the library's
 * question and it has its own tests; what belongs to this codebase is that the hunks come out as
 * unified diff without the preamble, that the prefixes survive, and that the two easy-to-confuse
 * nothings stay apart — an empty list of hunks (nothing changed at line level) versus the caller's
 * decision not to render at all, which is {@code null} and never produced here.
 */
class LineDiffTest {

    @Test
    void aChangedLineComesBackWithItsContextAndNoPreamble() {
        // Neither side ends with a newline, so the lines are exactly the three that were written and
        // the header's counts are the ones a reader can check by eye.
        List<Hunk> hunks = LineDiff.hunksOf("SKILL.md",
                LineDiff.lines("one\ntwo\nthree"), LineDiff.lines("one\nTWO\nthree"), 3);

        assertThat(hunks).hasSize(1);
        assertThat(hunks.get(0).header()).isEqualTo("@@ -1,3 +1,3 @@");
        assertThat(hunks.get(0).lines())
                .as("context, removal and addition, each carrying unified diff's own prefix — "
                        + "which is what lets a client that renders plain text still show a diff")
                .containsExactly(" one", "-two", "+TWO", " three");
        assertThat(hunks.get(0).lines())
                .as("the ---/+++ preamble names the file, and the file is a field of the response")
                .noneMatch(line -> line.startsWith("---") || line.startsWith("+++"));
    }

    @Test
    void twoSeparatedChangesComeBackAsTwoHunks() {
        List<Hunk> hunks = LineDiff.hunksOf("SKILL.md", LineDiff.lines(numbered(1, 30)),
                LineDiff.lines(numberedChanged(1, 30)), 3);

        assertThat(hunks).hasSize(2);
        assertThat(hunks.get(0).header()).isEqualTo("@@ -1,6 +1,6 @@");
        assertThat(hunks.get(1).header()).isEqualTo("@@ -22,7 +22,7 @@");
    }

    @Test
    void identicalTextHasNoHunks() {
        assertThat(LineDiff.hunksOf("SKILL.md", LineDiff.lines("a\nb\n"), LineDiff.lines("a\nb\n"), 3))
                .as("empty is a real answer — nothing changed at line level — and is not how a "
                        + "caller says 'not rendered', which is null")
                .isEmpty();
    }

    @Test
    void aWholeFileOnOneSideIsEveryLineAddedOrRemoved() {
        List<Hunk> added = LineDiff.hunksOf("new.md", List.of(), LineDiff.lines("a\nb"), 3);

        assertThat(added).hasSize(1);
        assertThat(added.get(0).header()).isEqualTo("@@ -1,0 +1,2 @@");
        assertThat(added.get(0).lines()).containsExactly("+a", "+b");
    }

    @Test
    void aTrailingNewlineIsContentRatherThanFormatting() {
        // The digest identifies the bytes (ADR 0005), so a version that only gained a final newline
        // is a different version — and a comparison that normalised it away would report two
        // different versions as identical.
        assertThat(LineDiff.hunksOf("SKILL.md", LineDiff.lines("a\nb"), LineDiff.lines("a\nb\n"), 3))
                .as("splitting keeps the empty last line, so the difference is visible")
                .isNotEmpty();
        assertThat(LineDiff.hunksOf("SKILL.md", LineDiff.lines("a\nb\n"), LineDiff.lines("a\nb\n"), 3))
                .isEmpty();
    }

    @Test
    void lineEndingsSurviveIntoTheComparison() {
        assertThat(LineDiff.lines("a\r\nb\r\n"))
                .as("\\r is left where it is: rewriting it would compare something other than the "
                        + "two versions this claims to compare")
                .containsExactly("a\r", "b\r", "");
    }

    private static String numbered(int from, int to) {
        StringBuilder text = new StringBuilder();
        for (int i = from; i <= to; i++) {
            text.append("line ").append(i).append('\n');
        }
        return text.toString();
    }

    private static String numberedChanged(int from, int to) {
        StringBuilder text = new StringBuilder();
        for (int i = from; i <= to; i++) {
            text.append(i == 3 || i == 25 ? "CHANGED " + i : "line " + i).append('\n');
        }
        return text.toString();
    }
}

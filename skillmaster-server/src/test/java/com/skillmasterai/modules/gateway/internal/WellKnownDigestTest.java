package com.skillmasterai.modules.gateway.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The digest a client computes for itself, reproduced.
 *
 * <p>The integration tests assert its shape ({@code [0-9a-f]{64}}) and that it is not the version
 * digest. What they cannot see from the outside are the two properties that make it a digest of
 * <em>content</em> rather than of a byte stream that happens to contain the same characters — and
 * both were arrived at by reading a decompiled client, so both are easy to lose in a tidy-up.
 */
class WellKnownDigestTest {

    @Test
    void hashesTheSameFilesTheSameWayWhateverOrderTheyArriveIn() {
        // Sorted here rather than trusted from the caller: the client sorts by path too, and a
        // different traversal order for identical content would make every check conclude the skill
        // had changed — a client that re-downloads for ever, rather than an error.
        List<WellKnownDigest.File> forward = List.of(
                file("SKILL.md", "hello"), file("references/a.md", "world"));
        List<WellKnownDigest.File> backward = List.of(
                file("references/a.md", "world"), file("SKILL.md", "hello"));

        assertThat(WellKnownDigest.of(forward)).isEqualTo(WellKnownDigest.of(backward));
    }

    @Test
    void separatesAPathFromItsContentAndOneFileFromTheNext() {
        // Two separators doing two different jobs, and each failure looks like a working digest.
        // With no separator at all, ("ab","c") and ("a","bc") hash the same bytes; with a space as
        // the separator, ("a b","c") and ("a","b c") do.
        assertThat(WellKnownDigest.of(List.of(file("ab", "c"))))
                .isNotEqualTo(WellKnownDigest.of(List.of(file("a", "bc"))));
        assertThat(WellKnownDigest.of(List.of(file("a b", "c"))))
                .isNotEqualTo(WellKnownDigest.of(List.of(file("a", "b c"))));
    }

    @Test
    void matchesTheFrozenVector() {
        // A vector, for the reason the class note gives: nothing can yet say whether this is exactly
        // what a real client expects — that needs P0b's CLI fetching from a live server. What a
        // vector can do is fail the day the algorithm changes by accident (an ordering, a separator,
        // a different hash) while someone believes they are only tidying.
        assertThat(WellKnownDigest.of(List.of(
                file("SKILL.md", "hello"), file("references/a.md", "world"))))
                .isEqualTo("8a01273feb82a2bd358e065be9fa3946efd9a67c042531f50a7696c9e3dc5446");
    }

    @Test
    void changesWhenAContentByteDoes() {
        assertThat(WellKnownDigest.of(List.of(file("SKILL.md", "hello"))))
                .isNotEqualTo(WellKnownDigest.of(List.of(file("SKILL.md", "hellp"))));
    }

    private static WellKnownDigest.File file(String path, String content) {
        return new WellKnownDigest.File(path, content.getBytes(StandardCharsets.UTF_8));
    }
}

package com.skillmasterai.modules.canon;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The names macOS and Windows cannot restore (v33 test-plan S7–S10 / A3, A4).
 *
 * <p>Tested at the seam rather than behind HTTP: the container refuses some shapes before Spring
 * sees them, so the issue code — the part clients branch on — is pinned here.
 */
class PathCanonTest {

    @Test
    void eachRuleHasItsOwnIssue() {
        assertThat(issueOf("报告:v2.md")).contains(PathCanon.WINDOWS_INVALID_CHAR);
        assertThat(issueOf("notes/plan?.md")).contains(PathCanon.WINDOWS_INVALID_CHAR);
        assertThat(issueOf("CON.md")).contains(PathCanon.WINDOWS_RESERVED_NAME);
        assertThat(issueOf("notes/con.txt")).contains(PathCanon.WINDOWS_RESERVED_NAME);
        assertThat(issueOf("com1")).contains(PathCanon.WINDOWS_RESERVED_NAME);
        assertThat(issueOf("notes.md ")).contains(PathCanon.TRAILING_SPACE_OR_DOT);
        assertThat(issueOf("notes/报告 .md.")).contains(PathCanon.TRAILING_SPACE_OR_DOT);
        // A space in the middle of a name is ordinary; only the end is a problem.
        assertThat(PathCanon.check("notes .md")).isEmpty();
        assertThat(issueOf("段".repeat(100) + ".md")).contains(PathCanon.PATH_TOO_LONG);
        assertThat(issueOf("a/".repeat(600) + "b.md")).contains(PathCanon.PATH_TOO_LONG);
    }

    @Test
    void ordinaryNamesPass() {
        assertThat(PathCanon.check("SKILL.md")).isEmpty();
        assertThat(PathCanon.check("references/split.md")).isEmpty();
        assertThat(PathCanon.check("scripts/compress.sh")).isEmpty();
        assertThat(PathCanon.check("console.md")).isEmpty();
    }

    @Test
    void segmentsAreNormalizedToNfc() {
        String nfd = Normalizer.normalize("café/notes.md", Normalizer.Form.NFD);
        assertThat(PathCanon.normalize(nfd))
                .isEqualTo(Normalizer.normalize(nfd, Normalizer.Form.NFC));
    }

    @Test
    void caseDifferenceIsACollision() {
        List<PathCanon.Collision> collisions = PathCanon.collisions(List.of("A.md", "a.md"));
        assertThat(collisions).hasSize(1);
        assertThat(collisions.getFirst().issue()).isEqualTo(PathCanon.CASE_COLLISION);
        assertThat(List.of(collisions.getFirst().a(), collisions.getFirst().b()))
                .containsExactlyInAnyOrder("A.md", "a.md");
    }

    @Test
    void twoSpellingsOfOneNameAreACollision() {
        String nfc = Normalizer.normalize("café.md", Normalizer.Form.NFC);
        String nfd = Normalizer.normalize("café.md", Normalizer.Form.NFD);
        List<PathCanon.Collision> collisions = PathCanon.collisions(List.of(nfc, nfd));

        assertThat(collisions).hasSize(1);
        assertThat(collisions.getFirst().issue()).isEqualTo(PathCanon.UNICODE_COLLISION);
    }

    @Test
    void everyCollidingPathIsNamedExactlyOnce() {
        // The pairing is two at a time, so the result is linear and nothing is capped — the client's
        // "还有 N 个" has to be able to count what the server actually found (v33 PRD §措辞).
        List<String> paths = new java.util.ArrayList<>();
        for (int index = 0; index < 20; index++) {
            paths.add("name" + index + ".MD");
            paths.add("name" + index + ".md");
        }

        List<PathCanon.Collision> collisions = PathCanon.collisions(paths);

        assertThat(collisions).hasSize(20);
        List<String> named = new java.util.ArrayList<>();
        collisions.forEach(collision -> {
            named.add(collision.a());
            named.add(collision.b());
        });
        assertThat(named).containsExactlyInAnyOrderElementsOf(paths);
    }

    @Test
    void aTargetCollidesWithOneExistingNameOnly() {
        Optional<PathCanon.Collision> collision = PathCanon.collidesWith(
                "notes/Readme.md", List.of("notes/readme.md", "notes/other.md"));
        assertThat(collision).isPresent();
        assertThat(collision.get().issue()).isEqualTo(PathCanon.CASE_COLLISION);

        assertThat(PathCanon.collidesWith("notes/new.md", List.of("notes/other.md"))).isEmpty();
        // The file being renamed away from is not a collision with itself.
        assertThat(PathCanon.collidesWith("notes/Readme.md", List.of("notes/Readme.md"))).isEmpty();
    }

    private static Optional<String> issueOf(String path) {
        return PathCanon.check(path).map(PathCanon.Issue::issue);
    }
}

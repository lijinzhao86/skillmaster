package com.skillmasterai.modules.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The path rules a skill entry and a draft write share (v7 test-plan T-WRITE-04).
 *
 * <p>These sit at the seam rather than behind HTTP on purpose: the container refuses a literal
 * {@code ..} or an empty segment before Spring routes the request, so an integration test can only
 * show that the request is refused, not which rule refused it. The issue code is what the CLI and
 * the page branch on, so it is pinned here.
 */
class SkillPathsTest {

    @Test
    void theRootAndItsRulesAreTheOnesTheErrorCodesName() {
        assertThat(SkillPaths.check("references/a.md")).isEmpty();

        assertThat(SkillPaths.check("../escape.md"))
                .hasValueSatisfying(issue -> {
                    assertThat(issue.issue()).isEqualTo("path_traversal");
                    assertThat(issue.message()).contains("cannot escape");
                });
        assertThat(SkillPaths.check("/etc/passwd"))
                .hasValueSatisfying(issue -> assertThat(issue.issue()).isEqualTo("absolute_path"));
        assertThat(SkillPaths.check("a//b.md"))
                .hasValueSatisfying(issue -> assertThat(issue.issue())
                        .isEqualTo("empty_path_segment"));
        assertThat(SkillPaths.check("a\\b.md"))
                .hasValueSatisfying(issue -> assertThat(issue.issue())
                        .isEqualTo("backslash_in_path"));
        assertThat(SkillPaths.check(""))
                .hasValueSatisfying(issue -> assertThat(issue.issue()).isEqualTo("empty_name"));
    }
}

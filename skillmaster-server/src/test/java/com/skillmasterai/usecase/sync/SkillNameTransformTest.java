package com.skillmasterai.usecase.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SkillNameTransformTest {

    @Test
    void aPrefixOrAnExplicitNameChangesThePlatformName() {
        SyncEntry.Naming none = SyncEntry.Naming.NONE;
        assertThat(SkillNameTransform.platformName("pdf-tools", none)).isEqualTo("pdf-tools");
        assertThat(SkillNameTransform.platformName("pdf-tools",
                new SyncEntry.Naming("mattpocock", "")))
                .isEqualTo("mattpocock-pdf-tools");
        assertThat(SkillNameTransform.platformName("pdf-tools",
                new SyncEntry.Naming("", "curated-pdf")))
                .isEqualTo("curated-pdf");
    }

    @Test
    void aNameMustFollowTheAgentSkillsShapeAndReserveTheOfficialPrefix() {
        assertThat(SkillNameTransform.platformName("pdf", SyncEntry.Naming.NONE))
                .isEqualTo("pdf");

        assertThatThrownBy(() -> SkillNameTransform.requireValid("PDF"))
                .isInstanceOf(SkillNameTransform.InvalidName.class)
                .hasMessage("invalid_name");
        assertThatThrownBy(() -> SkillNameTransform.requireValid("pdf--tools"))
                .isInstanceOf(SkillNameTransform.InvalidName.class)
                .hasMessage("invalid_name");
        assertThatThrownBy(() -> SkillNameTransform.requireValid("pdf_"))
                .isInstanceOf(SkillNameTransform.InvalidName.class)
                .hasMessage("invalid_name");
        assertThatThrownBy(() -> SkillNameTransform.requireValid("skillmaster-guide"))
                .isInstanceOf(SkillNameTransform.InvalidName.class)
                .hasMessage("name_reserved");
        assertThatThrownBy(() -> SkillNameTransform.requireValid("a".repeat(65)))
                .isInstanceOf(SkillNameTransform.InvalidName.class)
                .hasMessage("name_too_long");
    }

    @Test
    void theRewriteChangesOnlyTheTopLevelNameScalar() {
        byte[] original = md("""
                ---
                name: pdf-tools
                title: PDF tools
                description: Split and merge PDFs.
                metadata:
                  name: nested-is-untouched
                ---

                # pdf-tools

                The body mentions pdf-tools but must not be rewritten.
                """);

        byte[] rewritten = SkillNameTransform.rewriteSkillMd(original, "mattpocock-pdf-tools");
        String text = new String(rewritten, StandardCharsets.UTF_8);

        assertThat(text).contains("name: mattpocock-pdf-tools\n");
        assertThat(text).contains("metadata:\n  name: nested-is-untouched\n");
        assertThat(text).contains("The body mentions pdf-tools but must not be rewritten.");
        assertThat(text).doesNotContain("name: pdf-tools\n");
    }

    @Test
    void quotesAndInlineCommentsSurviveTheRewrite() {
        assertThat(rewrite("name: 'pdf-tools' # keep me", "prefixed"))
                .contains("name: 'prefixed' # keep me");
        assertThat(rewrite("name: \"pdf-tools\"", "prefixed"))
                .contains("name: \"prefixed\"");
        assertThat(rewrite("name: pdf-tools # keep me", "prefixed"))
                .contains("name: prefixed # keep me");
        assertThat(rewrite("name: pdf-tools   ", "prefixed"))
                .contains("name: prefixed");
    }

    @Test
    void anIndentedRootMappingStillHasOneTopLevelName() {
        byte[] original = md("""
                ---
                  name: pdf-tools
                  description: Split and merge PDFs.
                  metadata:
                    name: nested-is-untouched
                ---
                """);

        String text = new String(
                SkillNameTransform.rewriteSkillMd(original, "prefixed"),
                StandardCharsets.UTF_8);

        assertThat(text).contains("  name: prefixed\n");
        assertThat(text).contains("    name: nested-is-untouched\n");
    }

    @Test
    void aCommentAtAnyColumnDoesNotHideAnIndentedRootMapping() {
        byte[] original = md("""
                ---
                # the skill's identity
                  name: pdf-tools
                  description: Split and merge PDFs.
                ---
                """);

        String text = new String(
                SkillNameTransform.rewriteSkillMd(original, "prefixed"),
                StandardCharsets.UTF_8);

        assertThat(text).contains("  name: prefixed\n");
        assertThat(text).contains("# the skill's identity\n");
    }

    @Test
    void aCrOnlyFileIsRefusedAsNotRewritable() {
        byte[] original = md("---\rname: pdf-tools\rdescription: Split PDFs\r---\r");

        assertThatThrownBy(() -> SkillNameTransform.rewriteSkillMd(original, "prefixed"))
                .isInstanceOf(SkillNameTransform.NotRewritable.class);
    }

    @Test
    void aCrlfFileKeepsItsLineEndings() {
        byte[] original = md("---\r\nname: pdf-tools\r\ndescription: Split and merge PDFs.\r\n---\r\n");

        String text = new String(
                SkillNameTransform.rewriteSkillMd(original, "prefixed"),
                StandardCharsets.UTF_8);

        assertThat(text).contains("---\r\nname: prefixed\r\ndescription: Split and merge PDFs.\r\n");
    }

    @Test
    void aBlockScalarNameIsRefusedRatherThanGuessedAt() {
        byte[] original = md("""
                ---
                name: >
                  pdf-tools
                description: Split and merge PDFs.
                ---
                """);

        assertThatThrownBy(() -> SkillNameTransform.rewriteSkillMd(original, "prefixed"))
                .isInstanceOf(SkillNameTransform.NotRewritable.class);
    }

    @Test
    void aFlowMappingIsRefusedAsNotRewritable() {
        byte[] original = md("---\n{name: pdf-tools, description: Split PDFs}\n---\n");

        assertThatThrownBy(() -> SkillNameTransform.rewriteSkillMd(original, "prefixed"))
                .isInstanceOf(SkillNameTransform.NotRewritable.class);
    }

    @Test
    void aQuotedKeyIsRefusedAsNotRewritable() {
        byte[] original = md("---\n\"name\": pdf-tools\ndescription: Split PDFs\n---\n");

        assertThatThrownBy(() -> SkillNameTransform.rewriteSkillMd(original, "prefixed"))
                .isInstanceOf(SkillNameTransform.NotRewritable.class);
    }

    private static String rewrite(String nameLine, String platformName) {
        byte[] original = md("---\n" + nameLine + "\ndescription: A skill.\n---\n");
        return new String(SkillNameTransform.rewriteSkillMd(original, platformName),
                StandardCharsets.UTF_8);
    }

    private static byte[] md(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

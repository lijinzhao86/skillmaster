package com.skillmasterai.modules.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skillmasterai.common.ApiError;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The author-text contract at the write door (ADR 0179, v59 T17). */
class SkillDocumentTest {

    @Test
    void descriptionIsOneTo1024CodePoints() {
        assertThat(parse(md("d".repeat(1024))).description())
                .hasSize(1024);

        IngestException over = thrown(md("d".repeat(1025)));
        assertThat(over.details()).hasSize(1);
        ApiError.Detail detail = over.details().getFirst();
        assertThat(detail.field()).isEqualTo("description");
        assertThat(detail.issue()).isEqualTo("too_long");
        assertThat(detail.actualCodePoints()).isEqualTo(1025);
        assertThat(detail.limitCodePoints()).isEqualTo(1024);
        assertThat(detail.actualBytes()).isNull();
    }

    @Test
    void aHanCharacterAndAnEmojiEachCountAsOneCodePoint() {
        assertThat(parse(md("汉".repeat(1024))).description()).hasSize(1024);
        // 1024 emoji are 2048 UTF-16 units; counting units would refuse this.
        String emoji = parse(md("😀".repeat(1024))).description();
        assertThat(emoji.codePointCount(0, emoji.length())).isEqualTo(1024);
        assertThat(thrown(md("😀".repeat(1025))).details().getFirst().actualCodePoints())
                .isEqualTo(1025);
    }

    @Test
    void theAuthorsWhenToUseIsNestedFirstThenTopLevelAndCappedAt200() {
        assertThat(parse(withMetadataWhenToUse("当我说循环审计时用它")).whenToUse())
                .isEqualTo("当我说循环审计时用它");
        assertThat(parse(withTopLevelWhenToUse("合同审查")).whenToUse())
                .isEqualTo("合同审查");
        assertThat(parse(md("d")).whenToUse()).isNull();

        String twoHundred = parse(withMetadataWhenToUse("汉".repeat(200))).whenToUse();
        assertThat(twoHundred.codePointCount(0, twoHundred.length())).isEqualTo(200);
        IngestException over = thrown(withMetadataWhenToUse("汉".repeat(201)));
        assertThat(over.details().getFirst().field()).isEqualTo("metadata.when_to_use");
        assertThat(over.details().getFirst().issue()).isEqualTo("too_long");
        assertThat(over.details().getFirst().actualCodePoints()).isEqualTo(201);
        assertThat(over.details().getFirst().limitCodePoints()).isEqualTo(200);
    }

    @Test
    void everyProblemIsReportedInOneRoundTrip() {
        IngestException both = thrown("""
                ---
                name: pdf-tools
                description: %s
                metadata:
                  when_to_use: %s
                ---
                """.formatted("d".repeat(1025), "汉".repeat(201)));

        assertThat(both.details()).extracting(ApiError.Detail::field)
                .containsExactlyInAnyOrder("description", "metadata.when_to_use");
    }

    @Test
    void aNonTextWhenToUseIsRefusedRatherThanStringified() {
        IngestException wrong = thrown("""
                ---
                name: pdf-tools
                description: d
                metadata:
                  when_to_use: [a, b]
                ---
                """);
        assertThat(wrong.details().getFirst().field()).isEqualTo("metadata.when_to_use");
        assertThat(wrong.details().getFirst().issue()).isEqualTo("field_not_text");
    }

    @Test
    void aNonTextDescriptionDoesNotHideAnOverLongWhenToUse() {
        IngestException both = thrown("""
                ---
                name: pdf-tools
                description: [not, text]
                metadata:
                  when_to_use: %s
                ---
                """.formatted("汉".repeat(201)));

        assertThat(both.details()).extracting(ApiError.Detail::field)
                .containsExactlyInAnyOrder("description", "metadata.when_to_use");
        assertThat(both.details()).extracting(ApiError.Detail::issue)
                .containsExactlyInAnyOrder("field_not_text", "too_long");
    }

    @Test
    void anEmptyMetadataKeyFallsThroughToTheTopLevelBeforeTheLimitIsChecked() {
        IngestException over = thrown("""
                ---
                name: pdf-tools
                description: d
                metadata:
                  when_to_use:
                when_to_use: %s
                ---
                """.formatted("汉".repeat(201)));

        assertThat(over.details().getFirst().field()).isEqualTo("when_to_use");
        assertThat(over.details().getFirst().issue()).isEqualTo("too_long");
    }

    @Test
    void aNearMissSpellingIsPreservedRatherThanTreatedAsTheField() {
        SkillDocument.Document document = parse("""
                ---
                name: pdf-tools
                description: d
                whenToUse: 循环审计
                ---
                """);
        assertThat(document.whenToUse()).isNull();
        assertThat(document.frontmatter()).containsEntry("whenToUse", "循环审计");
    }

    private static String md(String description) {
        return """
                ---
                name: pdf-tools
                description: %s
                ---
                """.formatted(description);
    }

    private static String withMetadataWhenToUse(String text) {
        return """
                ---
                name: pdf-tools
                description: d
                metadata:
                  when_to_use: %s
                ---
                """.formatted(text);
    }

    private static String withTopLevelWhenToUse(String text) {
        return """
                ---
                name: pdf-tools
                description: d
                when_to_use: %s
                ---
                """.formatted(text);
    }

    private static SkillDocument.Document parse(String markdown) {
        return SkillDocument.parse(markdown.getBytes(StandardCharsets.UTF_8));
    }

    private static IngestException thrown(String markdown) {
        try {
            parse(markdown);
            throw new AssertionError("expected the document to be refused");
        } catch (IngestException e) {
            return e;
        }
    }
}

package com.skillmasterai.modules.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** v14's one spelling of a tag word (test-plan T3). */
class TagNamesTest {

    @Test
    void caseWidthAndSpacingCollapseToOneKey() {
        assertThat(TagNames.parse("  PDF  ").key()).isEqualTo("pdf");
        assertThat(TagNames.parse("ＰＤＦ").key()).isEqualTo("pdf");
        assertThat(TagNames.parse("客户   项目").key()).isEqualTo("客户 项目");
        assertThat(TagNames.parse("客户　项目").key()).isEqualTo("客户 项目");
    }

    @Test
    void theDisplayFormKeepsTheAuthorsSpelling() {
        assertThat(TagNames.parse("  PDF Tools ").name()).isEqualTo("PDF Tools");
        assertThat(TagNames.parse("ＰＤＦ").name()).isEqualTo("PDF");
    }

    @Test
    void twentyFourCodePointsPassAndTwentyFiveDoNot() {
        String twentyFour = "😀".repeat(24);
        String twentyFive = "😀".repeat(25);

        assertThat(TagNames.parse(twentyFour).name()).isEqualTo(twentyFour);
        assertThatThrownBy(() -> TagNames.parse(twentyFive))
                .isInstanceOfSatisfying(SkillRequestException.class, refusal -> {
                    assertThat(refusal.field()).isEqualTo("name");
                    assertThat(refusal.issue()).isEqualTo("invalid_name");
                });
    }

    @Test
    void emptyAndWhitespaceOnlyAreRefused() {
        for (String raw : new String[] {"", "   ", "　", "\t\n"}) {
            assertThatThrownBy(() -> TagNames.parse(raw))
                    .as("raw=%s", raw)
                    .isInstanceOf(SkillRequestException.class);
        }
    }

    @Test
    void theHashIsTheDisplayPrefixAndNeverPartOfTheName() {
        // `#名字` is how a public tag is written on screen (voice-spec). A name that carries one
        // would be stored with a character every surface adds itself, and the CLI's own sentence
        // promises this refusal.
        for (String raw : new String[] {"#pdf", "pdf#", "#", "＃pdf"}) {
            assertThatThrownBy(() -> TagNames.parse(raw))
                    .as("raw=%s", raw)
                    .isInstanceOfSatisfying(SkillRequestException.class, refusal -> {
                        assertThat(refusal.field()).isEqualTo("name");
                        assertThat(refusal.issue()).isEqualTo("invalid_name");
                    });
        }
        assertThat(TagNames.parse("C++").name())
                .as("punctuation other than the display hash is still the author's to use")
                .isEqualTo("C++");
    }
}

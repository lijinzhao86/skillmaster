package com.skillmasterai.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PersonalTriggersTest {

    @Test
    void aNameThatIsTheQueryIsS0() {
        var hit = PersonalTriggers.exact("PDF-Tools", "pdf-tools", null).orElseThrow();
        assertThat(hit.tier()).isEqualTo(PersonalTriggers.S0);
        assertThat(hit.personal()).isFalse();
    }

    @Test
    void aTitleIsAlsoS0EvenWhenTheAddressIsNotTheQuery() {
        var hit = PersonalTriggers.exact("PDF 拆分合并", "pdf-tools", "PDF 拆分合并", null)
                .orElseThrow();
        assertThat(hit.tier()).isEqualTo(PersonalTriggers.S0);
        assertThat(hit.personal()).isFalse();
    }

    @Test
    void aHanRunOfThreeHitsS1() {
        var hit = PersonalTriggers.exact("帮我用循环审计跑一遍", "pdf-tools",
                "当我说循环审计的时候用它").orElseThrow();
        assertThat(hit.tier()).isEqualTo(PersonalTriggers.S1);
        assertThat(hit.personal()).isTrue();
        assertThat(hit.score()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void twoHanCharactersAreNotEnough() {
        assertThat(PersonalTriggers.exact("审计", "pdf-tools", "审计的时候用它")).isEmpty();
    }

    @Test
    void fiveLatinLettersHitButFourDoNot() {
        assertThat(PersonalTriggers.exact("please review", "pdf-tools", "contract review"))
                .isPresent();
        assertThat(PersonalTriggers.exact("review", "pdf-tools", "contract review"))
                .as("a shared run of six letters is above the five-letter floor")
                .isPresent();
        assertThat(PersonalTriggers.exact("abcd", "pdf-tools", "xx abcd yy"))
                .as("four Latin letters are below the floor")
                .isEmpty();
    }

    @Test
    void theLongerRunRanksHigher() {
        double shortRun = PersonalTriggers.exact("循环审计", "x", "循环审计")
                .orElseThrow().score();
        double longRun = PersonalTriggers.exact("循环审计", "x", "我说循环审计时")
                .orElseThrow().score();
        assertThat(longRun).isGreaterThanOrEqualTo(shortRun);
    }

    @Test
    void aMixedRunCanQualifyThroughItsLatinHalf() {
        assertThat(PersonalTriggers.exact("Alice 那套", "x", "当我说 Alice 那套时用它"))
                .as("five Latin letters qualify even when two Han characters sit beside them")
                .isPresent();
    }

    @Test
    void theAuthorsTextIsS3AndNotPersonal() {
        var hit = PersonalTriggers.authorExact("循环审计", "循环审计时用我").orElseThrow();
        assertThat(hit.tier()).isEqualTo(PersonalTriggers.S3);
        assertThat(hit.personal()).as("it is the skill's published text, not the caller's").isFalse();
        assertThat(PersonalTriggers.authorExact("审计", "审计时用我")).isEmpty();
    }

    @Test
    void anEmojiIsCountedByCodePoint() {
        assertThat(PersonalTriggers.longestCommonSubstringLength("😀😀😀", "x😀😀😀y"))
                .isEqualTo(3);
    }
}

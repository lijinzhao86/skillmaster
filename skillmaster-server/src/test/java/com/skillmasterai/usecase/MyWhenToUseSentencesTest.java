package com.skillmasterai.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** One vector per situation (v59 P2); an English note has to split too. */
class MyWhenToUseSentencesTest {

    @Test
    void splitsOnFullWidthAndAsciiSentenceEnds() {
        assertThat(MyWhenToUseUseCase.sentences("循环审计时用我。合同挑刺时也用它。"))
                .containsExactly("循环审计时用我。", "合同挑刺时也用它。");
        assertThat(MyWhenToUseUseCase.sentences(
                "When I audit code. When I review contracts."))
                .containsExactly("When I audit code.", "When I review contracts.");
    }

    @Test
    void aDecimalPointIsNotASentenceEnd() {
        assertThat(MyWhenToUseUseCase.sentences("只在 3.14 附近用它"))
                .containsExactly("只在 3.14 附近用它");
    }

    @Test
    void fiveIsTheCeiling() {
        assertThat(MyWhenToUseUseCase.sentences("一。二。三。四。五。六。")).hasSize(5);
    }
}

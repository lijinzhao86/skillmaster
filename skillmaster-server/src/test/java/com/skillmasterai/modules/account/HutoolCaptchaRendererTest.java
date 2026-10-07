package com.skillmasterai.modules.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * What the production renderer must be, now that the tests substitute a fake one.
 *
 * <p>The fake is what makes the flow testable, and it is also why this file has to exist: it is the
 * only thing standing between "the captcha is wired up" and "the captcha draws something a person
 * can actually read". Nothing here can test that an attacker finds it hard — that is a judgement
 * about glyph rendering, not an assertion — but everything here is what would silently break.
 */
class HutoolCaptchaRendererTest {

    private final HutoolCaptchaRenderer renderer = new HutoolCaptchaRenderer();

    @Test
    void drawsAReadableImageAndSaysWhatIsInIt() {
        CaptchaRenderer.Rendered rendered = renderer.render();

        assertThat(rendered.answer()).hasSize(4).matches("[2-9A-HJ-NP-Z]{4}");
        assertThat(Base64.getDecoder().decode(rendered.image()))
                .as("the image must be a PNG: %s", rendered.image())
                .startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }

    @Test
    void neverDrawsTheCharactersPeopleCannotTellApart() {
        // 0/O and 1/I are read wrong in upper case, and a person who cannot tell them apart retypes
        // the whole image — a cost that lands on every real user to inconvenience nobody.
        assertThat(render(200)).allSatisfy(answer ->
                assertThat(answer).matches("[2-9A-HJ-NP-Z]{4}"));
    }

    @Test
    void doesNotDrawTheSameAnswerEveryTime() {
        // Not a test of unpredictability — that is a property of the SecureRandom behind it and
        // cannot be asserted from outside. What it catches is the mistake that would make this
        // decorative: a generator that returns a constant, or one seeded once at class load.
        //
        // Five draws, not fifty. The space is 32^4 ≈ 1.05 million, so the chance of two draws
        // colliding is about 10 in a million at five and about 0.1% at fifty — and a test that fails
        // once in eight hundred runs is worse than no test.
        assertThat(render(5)).doesNotHaveDuplicates();
    }

    private List<String> render(int times) {
        return IntStream.range(0, times).mapToObj(ignored -> renderer.render().answer()).toList();
    }
}

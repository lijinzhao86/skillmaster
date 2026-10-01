package com.skillmasterai.modules.account;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.captcha.generator.CodeGenerator;
import java.security.SecureRandom;

/**
 * The captcha image, drawn by Hutool.
 *
 * <p><strong>Only the drawing is the library's.</strong> Its default answer generator is
 * {@code RandomGenerator}, which draws through {@code RandomUtil.getRandom()} — and that method
 * returns {@code ThreadLocalRandom} (verified in {@code hutool-core} 5.8.47, not assumed). A
 * predictable captcha is not a weak captcha but a decorative one: an attacker who has seen a few
 * answers can compute the next without looking at the image. The answer therefore comes from
 * {@link SecureCodeGenerator} below, and the library is left the part that is genuinely worth
 * reusing — glyph rendering, per-character rotation, and interference lines that cost an automated
 * reader something.
 *
 * <p>Readable rather than hard, deliberately. The image is a speed bump in front of an endpoint that
 * spends money, not a gate: the alphabet omits the two pairs people read wrong in upper case (O/0
 * and I/1), and the answer is four characters — short enough to retype, long enough that guessing is
 * not worth trying against a challenge that allows three attempts.
 */
public final class HutoolCaptchaRenderer implements CaptchaRenderer {

    private static final int WIDTH = 160;
    private static final int HEIGHT = 60;
    private static final int INTERFERENCE_LINES = 40;

    @Override
    public Rendered render() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(WIDTH, HEIGHT, new SecureCodeGenerator(),
                INTERFERENCE_LINES);
        // getCode() first, which is what draws the image: the two are produced together, so reading
        // them in either order gives the answer that is actually in the picture.
        return new Rendered(captcha.getCode(), captcha.getImageBase64());
    }

    /** Four characters, drawn from a source an attacker cannot predict. */
    private static final class SecureCodeGenerator implements CodeGenerator {

        /**
         * No 0/O and no 1/I. A person who cannot tell one glyph from another retypes the whole
         * image, and that cost lands on every real user to inconvenience nobody.
         */
        private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

        private static final int LENGTH = 4;

        private final SecureRandom random = new SecureRandom();

        @Override
        public String generate() {
            StringBuilder code = new StringBuilder(LENGTH);
            for (int i = 0; i < LENGTH; i++) {
                code.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            }
            return code.toString();
        }

        /**
         * Not used — {@code CaptchaService} does its own comparison, against a hash rather than a
         * live answer. Present because the interface asks for it.
         */
        @Override
        public boolean verify(String code, String userInputCode) {
            return code != null && code.equalsIgnoreCase(userInputCode);
        }
    }
}

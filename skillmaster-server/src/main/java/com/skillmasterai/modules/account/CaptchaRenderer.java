package com.skillmasterai.modules.account;

/**
 * Draws a captcha and says what the answer is.
 *
 * <p>A seam rather than a library call at the point of use, for the same reason
 * {@link SmsSender} is one: <strong>a captcha image cannot be read by a test.</strong> The
 * integration tests substitute a renderer whose answer is known — otherwise no flow past "solve the
 * captcha" could be tested at all, and the wiring would be taken on faith. What the production
 * renderer actually draws is pinned by its own unit test.
 */
@FunctionalInterface
public interface CaptchaRenderer {

    Rendered render();

    /**
     * @param answer   what a person would type. Hashed before it is stored and never sent back
     * @param image    the PNG, base64-encoded. Base64 rather than {@code byte[]} so that this stays
     *                 an immutable value: a byte array in a record is an array anybody can write to,
     *                 and the wire format wants base64 anyway
     */
    record Rendered(String answer, String image) {
    }
}

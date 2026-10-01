package com.skillmasterai.support;

import com.skillmasterai.modules.account.CaptchaRenderer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The captcha, for tests that have to get past one.
 *
 * <p>It does the thing the production renderer must never do: it always draws the same answer, and
 * the answer is a constant a test can read. An image cannot be read by a test, so without this no
 * flow past "solve the captcha" could be exercised at all — the wiring would be taken on faith, and
 * the one thing that would then be untested is whether the endpoints check it.
 *
 * <p>{@code @Primary} rather than replacing the production bean, for the reason
 * {@link RecordingSmsSender} gives: overriding a bean definition needs
 * {@code allow-bean-definition-overriding}, which would be a property set for the whole suite so
 * that one class could win a name collision. Being pickable by type applies to every context in the
 * suite because this class sits in the scanned package.
 *
 * <p>The image is a real PNG, built rather than pasted: a base64 blob copied out of something is a
 * string nobody can check, and this way the file is correct by construction.
 */
@Component
@Primary
public class RecordingCaptchaRenderer implements CaptchaRenderer {

    /** What every challenge in every test expects to be answered with. */
    public static final String ANSWER = "TEST";

    private final String image = tinyPngBase64();

    @Override
    public Rendered render() {
        return new Rendered(ANSWER, image);
    }

    private static String tinyPngBase64() {
        try (ByteArrayOutputStream png = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", png);
            return Base64.getEncoder().encodeToString(png.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("could not build the test captcha image", e);
        }
    }
}

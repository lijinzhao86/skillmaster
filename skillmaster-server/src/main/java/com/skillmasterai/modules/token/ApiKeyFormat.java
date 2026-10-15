package com.skillmasterai.modules.token;

import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;

/**
 * The shape of an API key's secret: what it starts with, how long it is, and how one is made.
 *
 * <p>Written here rather than inlined at the generator and again at the validator, for the reason
 * {@link com.skillmasterai.modules.auth.Scopes} exists: a wire fact kept in two places is one copy
 * that will drift, and the symptom of drift here is a key that is refused with no explanation.
 *
 * <p><strong>The prefix is load-bearing twice.</strong> It is what a secret scanner needs in order
 * to recognise a leaked key wherever it turns up, and it is how the request path routes without a
 * second lookup — an OAuth access token is base64url and never begins with it. Routing only: the
 * check that decides is still the hash lookup, so a key that takes the wrong route is a miss rather
 * than an acceptance.
 *
 * <p><strong>The length is not a UX constraint.</strong> Nobody types one of these; it is pasted
 * from the console (ADR 0040). Its only job is entropy, which is why the secret is 32 bytes of
 * {@code SecureRandom} — the whole of a key's security is that nobody can guess it.
 */
public final class ApiKeyFormat {

    private ApiKeyFormat() {
    }

    /** The recognisable beginning of every key this service issues (ADR 0038 §决定 2). */
    public static final String PREFIX = "skm_";

    /** 256 bits. Below this the hash lookup stops being the only thing an attacker has to beat. */
    private static final int SECRET_BYTES = 32;

    /** {@code SECRET_BYTES} in base64url without padding — 43 characters, stated once, here. */
    private static final int SECRET_LENGTH = 43;

    /** A whole key: the prefix and nothing but the base64url alphabet after it. */
    private static final Pattern WHOLE_KEY =
            Pattern.compile("^" + Pattern.quote(PREFIX) + "[A-Za-z0-9_-]{" + SECRET_LENGTH + "}$");

    private static final Base64StringKeyGenerator GENERATOR =
            new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), SECRET_BYTES);

    /** A new secret, prefix included. The caller stores its hash and shows it once. */
    public static String generate() {
        return PREFIX + GENERATOR.generateKey();
    }

    /**
     * Whether this string is a key rather than an OAuth token — the routing question, answered
     * without touching the database.
     *
     * <p>Deliberately loose: a string that merely begins with the prefix is routed here and then
     * fails the hash lookup like any other unknown credential. Requiring the full shape would be a
     * second opinion about what a key is, and the day the two disagree the one that is wrong is the
     * one deciding whether to look.
     */
    public static boolean routesToApiKeys(String presentedToken) {
        return presentedToken != null && presentedToken.startsWith(PREFIX);
    }

    /**
     * Whether this string has the shape of a key.
     *
     * <p>For the client: {@code login --with-key} refuses a paste that cannot be one before it
     * spends a request on it. The server never needs this — the hash lookup already answers it — so
     * a shape check here is not a security boundary and must not be mistaken for one.
     */
    public static boolean hasKeyShape(String presentedToken) {
        return presentedToken != null && WHOLE_KEY.matcher(presentedToken).matches();
    }
}

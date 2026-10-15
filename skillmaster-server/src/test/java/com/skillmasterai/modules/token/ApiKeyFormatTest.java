package com.skillmasterai.modules.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The three facts about a key's secret that other code depends on: it is long enough, it carries the
 * prefix, and it cannot be mistaken for an OAuth access token.
 *
 * <p>That last one is the routing rule (§接口), and it is the reason the alphabet matters rather than
 * the length: the prefix decides which table the request path looks in, and a token that could begin
 * with the prefix would be a token that occasionally looked in the wrong one. Asserted here as
 * "generated keys stay inside the alphabet the prefix implies" rather than by comparing against the
 * framework's generator, which is not ours to pin.
 */
class ApiKeyFormatTest {

    @Test
    void everyKeyBeginsWithThePrefixAndHasTheShapeTheCliChecksFor() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String key = ApiKeyFormat.generate();

            assertThat(key).startsWith(ApiKeyFormat.PREFIX);
            assertThat(ApiKeyFormat.hasKeyShape(key))
                    .as("a freshly generated key must have the shape the CLI accepts: %s", key)
                    .isTrue();
            assertThat(ApiKeyFormat.routesToApiKeys(key)).isTrue();
            // 200 draws from 256 bits: a repeat would mean the generator is not random, not bad luck.
            assertThat(seen.add(key)).as("two keys were identical").isTrue();
        }
    }

    @Test
    void theSecretIsLongEnoughToBeUnguessable() {
        // 32 bytes of base64url is 43 characters, and the entropy is the whole of a key's security:
        // the hash lookup answers "is this one of ours", and nothing else stands between a guess and
        // an account. Stated as a number so that shortening it is a decision somebody has to make on
        // purpose, in a diff where this line turns red.
        String secret = ApiKeyFormat.generate().substring(ApiKeyFormat.PREFIX.length());

        assertThat(secret).hasSize(43);
        assertThat(secret.chars()).allMatch(c -> c == '-' || c == '_'
                || (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'));
    }

    @Test
    void stringsThatAreNotKeysAreNotRoutedToKeys() {
        // The routing question is answered without touching the database, so its two answers have to
        // be right for the shapes that actually arrive: an OAuth access token, a truncated paste, and
        // nothing at all.
        assertThat(ApiKeyFormat.routesToApiKeys(null)).isFalse();
        assertThat(ApiKeyFormat.routesToApiKeys("")).isFalse();
        assertThat(ApiKeyFormat.routesToApiKeys("a".repeat(96))).isFalse();
        assertThat(ApiKeyFormat.routesToApiKeys("skm_")).isTrue(); // loose on purpose: routing, not judging
        assertThat(ApiKeyFormat.hasKeyShape("skm_")).isFalse();
        assertThat(ApiKeyFormat.hasKeyShape(ApiKeyFormat.generate() + "x")).isFalse();
    }
}

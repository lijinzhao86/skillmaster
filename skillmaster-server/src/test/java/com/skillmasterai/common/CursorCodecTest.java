package com.skillmasterai.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The opaque cursor, and the two ways it can go wrong without saying anything.
 *
 * <p>The payload is assembled by hand rather than by a serializer, so a value that is not escaped is
 * a cursor that is not valid JSON — which decodes to empty, which the reader turns into "start
 * again". The query succeeds and returns the wrong page: no exception, no 400, nothing in a log.
 *
 * <p>The other direction is the promise that a bad query string is never a 500. That is what the
 * rejection cases below are for.
 */
class CursorCodecTest {

    private static final String ORDERING = "skills:relevance:v1";

    @Test
    void carriesAKeyBackUnchanged() {
        List<String> key = List.of("2026-10-02T01:02:03Z", "01M3HTG7GCCVBGRPAFFSVSF12W");

        assertThat(CursorCodec.decode(CursorCodec.encode(ORDERING, key)))
                .hasValueSatisfying(cursor -> {
                    assertThat(cursor.version()).isEqualTo(1);
                    assertThat(cursor.ordering()).isEqualTo(ORDERING);
                    assertThat(cursor.key()).isEqualTo(key);
                });
    }

    @Test
    void carriesBackAKeyFullOfCharactersJsonHasOpinionsAbout() {
        // Every one of these breaks the hand-built payload in a different way: a quote ends the
        // string early, a backslash starts an escape that is not there, a raw newline is not legal
        // inside JSON, and a control character is not either.
        List<String> hostile = List.of(
                "quote\"inside",
                "back\\slash",
                "line\nbreak",
                "carriage\rreturn",
                "tab\there",
                "control\u0001char",
                "中文键",
                "emoji 🎵 and 𝄞 a surrogate pair");

        assertThat(CursorCodec.decode(CursorCodec.encode(ORDERING, hostile)))
                .hasValueSatisfying(cursor -> assertThat(cursor.key()).isEqualTo(hostile));
    }

    @Test
    void survivesAnEmptyKey() {
        // A boundary rather than a real query: it shows the refusals below are about what cannot be
        // read, not about what looks unusual.
        assertThat(CursorCodec.decode(CursorCodec.encode(ORDERING, List.of())))
                .hasValueSatisfying(cursor -> assertThat(cursor.key()).isEmpty());
    }

    @Test
    void isBase64UrlWithNoPadding() {
        // It travels in a query string: `+` and `/` would have to be percent-encoded, and a client
        // that forgot would send a cursor this class cannot read back.
        assertThat(CursorCodec.encode(ORDERING, List.of("k"))).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void refusesEverythingThatIsNotACursorItUnderstands() {
        // The contract is empty, never an exception: a decoder that throws is a decoder that can
        // turn a typo in a query string into a 500.
        for (String notACursor : List.of(
                "",
                "   ",
                "not-a-cursor",
                encoded("[1,2,3]"),
                encoded("{\"v\":2,\"o\":\"o\",\"k\":[\"k\"]}"),
                encoded("{\"v\":1,\"k\":[\"k\"]}"),
                encoded("{\"v\":1,\"o\":\"o\"}"),
                encoded("{\"v\":1,\"o\":5,\"k\":[\"k\"]}"),
                encoded("{\"v\":1,\"o\":\"o\",\"k\":\"k\"}"))) {
            assertThat(CursorCodec.decode(notACursor))
                    .as("cursor %s", notACursor)
                    .isEmpty();
        }

        assertThat(CursorCodec.decode(null)).isEmpty();
    }

    @Test
    void keepsTheOrderingItWasMadeFor() {
        // Carried so that a reader can refuse a cursor that belongs to a different sort instead of
        // resuming at a position that means something else under this one.
        assertThat(CursorCodec.decode(CursorCodec.encode("skills:recent:v2", List.of("k"))))
                .hasValueSatisfying(cursor ->
                        assertThat(cursor.ordering()).isEqualTo("skills:recent:v2"));
    }

    /** Base64url, which is what {@code decode} expects — plain base64 would not even get decoded. */
    private static String encoded(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}

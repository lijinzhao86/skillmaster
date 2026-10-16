package com.skillmasterai.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The two words the design draws, from the strings real browsers actually send.
 *
 * <p>The strings below are the ones that make a hand-rolled parser wrong, which is why they are here
 * rather than a made-up example each: Chrome's own User-Agent ends in {@code Safari}, Edge's
 * contains {@code Chrome}, and an iPhone's contains {@code like Mac OS X}. None of those mistakes
 * throws anything — they produce a plausible wrong name, which is the failure this test exists to
 * catch.
 */
class DeviceDescriptionTest {

    private static final String CHROME_MACOS = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"
            + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";
    private static final String SAFARI_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS"
            + " X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";
    private static final String CHROME_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

    @Test
    void theDesignsExamplesComeOutAsTheDesignDrawsThem() {
        assertThat(DeviceDescription.of(CHROME_MACOS)).isEqualTo("Chrome · macOS");
        assertThat(DeviceDescription.of(SAFARI_IPHONE)).isEqualTo("Safari · iPhone");
        assertThat(DeviceDescription.of(CHROME_WINDOWS)).isEqualTo("Chrome · Windows");
    }

    @Test
    void theMoreSpecificNameWinsBecauseEveryChromiumBrowserClaimsTheOthers() {
        // Chrome says `Safari`, and Edge and Opera say `Chrome`. Testing in the other order names
        // all three of them Safari or Chrome, and nothing about the result looks wrong.
        assertThat(DeviceDescription.of(CHROME_WINDOWS + " Edg/141.0.0.0"))
                .isEqualTo("Edge · Windows");
        assertThat(DeviceDescription.of(CHROME_WINDOWS + " OPR/115.0.0.0"))
                .isEqualTo("Opera · Windows");
        assertThat(DeviceDescription.of("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)"
                        + " AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/141.0 Mobile/15E148"
                        + " Safari/605.1.15"))
                .isEqualTo("Firefox · iPhone");
        // Chrome on iOS never says `Chrome` — it says `CriOS`, under a name that also says Safari.
        assertThat(DeviceDescription.of("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)"
                        + " AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/141.0.0.0 Mobile/15E148"
                        + " Safari/604.1"))
                .isEqualTo("Chrome · iPhone");
        // Android's User-Agent contains `Linux` as well, and its phones are not Linux desktops.
        assertThat(DeviceDescription.of("Mozilla/5.0 (Linux; Android 14; Pixel 8)"
                        + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile"
                        + " Safari/537.36"))
                .isEqualTo("Chrome · Android");
    }

    @Test
    void anAgentWeCannotPlaceIsShownAsItArrivedRatherThanAsAWordWeChose() {
        // The suite's own client, and a plausible future one. "Unknown" would throw away the only
        // thing that might identify the machine — which is the question the list is asked.
        assertThat(DeviceDescription.of("Java-http-client/25")).isEqualTo("Java-http-client/25");
        assertThat(DeviceDescription.of("some-agent-we-have-never-seen")).isEqualTo(
                "some-agent-we-have-never-seen");
    }

    @Test
    void halfOfAKnownAgentKeepsTheHalfThatIsKnown() {
        assertThat(DeviceDescription.of("Mozilla/5.0 AppleWebKit/537.36 Chrome/141.0.0.0"))
                .as("a browser with no platform named")
                .isEqualTo("Chrome");
        assertThat(DeviceDescription.of("curl/8.7.1 (Linux)"))
                .as("a platform with no browser named")
                .isEqualTo("Linux");
    }

    @Test
    void noAgentAtAllIsAnEmptyDescriptionRatherThanAnInventedOne() {
        assertThat(DeviceDescription.of(null)).isEmpty();
        assertThat(DeviceDescription.of("")).isEmpty();
        assertThat(DeviceDescription.of("   ")).isEmpty();
    }
}

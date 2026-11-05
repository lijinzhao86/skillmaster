package com.skillmasterai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.skillmasterai.config.SkillmasterProperties.Account;

import com.skillmasterai.config.SkillmasterProperties.Search;
import com.skillmasterai.config.SkillmasterProperties.Sms;
import com.skillmasterai.config.SkillmasterProperties.Tokens;
import com.skillmasterai.config.SkillmasterProperties.Welcome;
import com.skillmasterai.modules.account.SessionPolicy;
import com.skillmasterai.modules.blob.BlobSettings;
import com.skillmasterai.modules.distribution.DiffLimits;
import com.skillmasterai.modules.search.RelevanceWeights;
import com.skillmasterai.modules.token.ApiKeyPolicy;
import com.skillmasterai.usecase.WelcomeSkills;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SkillmasterPropertiesTest {

    @Test
    void aTrailingSlashOnTheBaseUrlIsRemoved() {
        // Every consumer appends a path that already starts with a slash, and "//" is rejected
        // outright by StrictHttpFirewall — so a base URL configured with one would publish an
        // `issuer` and a `resource_metadata` URL no client could fetch. Normalising once, where the
        // property enters the application, is what keeps each consumer from having to remember.
        assertThat(propertiesWith("https://skills.example.com/").publicBaseUrl())
                .isEqualTo("https://skills.example.com");
        assertThat(propertiesWith("https://skills.example.com").publicBaseUrl())
                .isEqualTo("https://skills.example.com");
        assertThat(propertiesWith("https://skills.example.com///").publicBaseUrl())
                .isEqualTo("https://skills.example.com");
    }

    /** The design's six numbers, which is what a deployment that says nothing gets. */
    private static Tokens tokens() {
        return new Tokens(Duration.ofHours(1), Duration.ofDays(30), Duration.ofDays(180),
                Duration.ofMinutes(5), Duration.ofSeconds(60), Duration.ofMinutes(5));
    }

    /** ADR 0043's two rulers, as application.yml declares them. */
    private static SessionPolicy sessions() {
        return new SessionPolicy(Duration.ofHours(24), Duration.ofDays(7));
    }

    private static SkillmasterProperties propertiesWith(String publicBaseUrl) {
        return new SkillmasterProperties(publicBaseUrl,
                new Search(RelevanceWeights.DEFAULTS),
                new Account("a-hmac-key", "an-enc-key", 4),
                sessions(),
                new Sms("", "", "", "", false, false),
                tokens(),
                limits(),
                new ApiKeyPolicy(true, Duration.ofDays(90), Duration.ofMinutes(10)),
                BlobSettings.defaults(),
                new Welcome(java.util.List.of("skillmaster/skillmaster-guide")));
    }

    @Test
    void aMalformedWelcomeAddressIsRefusedAtStartup() {
        // Skipping an address the runtime cannot find is right; skipping one that cannot be parsed
        // would turn a typo into "every new account is quietly missing this skill".
        assertThatThrownBy(() -> new Welcome(java.util.List.of("no-slash")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-slash");
        assertThatThrownBy(() -> new Welcome(java.util.List.of("a/b@1.0.0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a/b@1.0.0");
        assertThat(new Welcome(java.util.List.of(" a/b ", "")).skills()).containsExactly("a/b");
        // A space inside the address is a typo the runtime would turn into a silent skip.
        assertThatThrownBy(() -> new Welcome(java.util.List.of("a /b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whitespace");
    }

    @Test
    void theConfiguredAddressesReachTheUseCaseAsSlugAndName() {
        WelcomeSkills skills = new WelcomeConfig().welcomeSkills(propertiesWith("http://localhost"));

        assertThat(skills.skills())
                .extracting(WelcomeSkills.Skill::namespaceSlug, WelcomeSkills.Skill::name)
                .containsExactly(tuple("skillmaster", "skillmaster-guide"));
    }

    /**
     * The design's three diff caps, as application.yml declares them. Written out here rather than
     * read from that file, because this test is about the record's own behaviour and a value that
     * changed in the yml should not make it fail for a reason it does not test.
     */
    private static DiffLimits limits() {
        return new DiffLimits(1048576, 20000, 100);
    }
}

package com.skillmasterai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.config.SkillmasterProperties.Account;

import com.skillmasterai.config.SkillmasterProperties.Gateway;
import com.skillmasterai.config.SkillmasterProperties.Search;
import com.skillmasterai.config.SkillmasterProperties.Sms;
import com.skillmasterai.config.SkillmasterProperties.Tokens;
import com.skillmasterai.modules.search.RelevanceWeights;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SkillmasterPropertiesTest {

    @Test
    void aTrailingSlashOnTheBaseUrlIsRemoved() {
        // Every consumer appends a path that already starts with a slash, and "//" is rejected
        // outright by StrictHttpFirewall — so a base URL configured with one would publish a
        // gateway index advertising a URL no client could fetch. Normalising once, where the
        // property enters the application, is what keeps each consumer from having to remember.
        assertThat(propertiesWith("https://skills.example.com/").publicBaseUrl())
                .isEqualTo("https://skills.example.com");
        assertThat(propertiesWith("https://skills.example.com").publicBaseUrl())
                .isEqualTo("https://skills.example.com");
        assertThat(propertiesWith("https://skills.example.com///").publicBaseUrl())
                .isEqualTo("https://skills.example.com");
    }

    /** The design's five numbers, which is what a deployment that says nothing gets. */
    private static Tokens tokens() {
        return new Tokens(Duration.ofHours(1), Duration.ofDays(30), Duration.ofDays(180),
                Duration.ofMinutes(5), Duration.ofSeconds(60));
    }

    private static SkillmasterProperties propertiesWith(String publicBaseUrl) {
        return new SkillmasterProperties(publicBaseUrl,
                new Search(RelevanceWeights.DEFAULTS),
                new Gateway(""),
                new Account("a-hmac-key", "an-enc-key", 4),
                new Sms("", "", "", "", false, false),
                tokens());
    }
}

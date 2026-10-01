package com.skillmasterai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmasterai.config.SkillmasterProperties.Account;
import com.skillmasterai.config.SkillmasterProperties.Auth;
import com.skillmasterai.config.SkillmasterProperties.Gateway;
import com.skillmasterai.config.SkillmasterProperties.Search;
import com.skillmasterai.config.SkillmasterProperties.Sms;
import com.skillmasterai.modules.search.RelevanceWeights;
import java.util.Set;
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

    private static SkillmasterProperties propertiesWith(String publicBaseUrl) {
        return new SkillmasterProperties(publicBaseUrl,
                new Auth("a-token", "01M3HTG7GCCVBGRPAFFSVSF12W", Set.of("skills:read")),
                new Search(RelevanceWeights.DEFAULTS),
                new Gateway(""),
                new Account("a-hmac-key", "an-enc-key", 4),
                new Sms("", "", "", "", false));
    }
}

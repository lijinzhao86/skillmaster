package com.skillmasterai.modules.ingest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires M5. It has no hidden implementation to protect — the validator is a plain public type —
 * but the wiring still lives here rather than in {@code internal} so that every module assembles
 * itself the same way, and so that adding a hidden implementation later does not force the
 * wiring to move.
 *
 * <p>The limits are the platform's standard, not a deployment setting. They are deliberately not
 * configurable: a server that accepted more than the standard file count, per-file or per-version
 * bytes would store skills that the clients enforcing the same constants will refuse to load, and
 * a configurable ceiling is an invitation for the two sides to disagree. Tests pass their own
 * {@link IngestLimits} instead.
 */
@Configuration(proxyBeanMethods = false)
public class IngestConfiguration {

    @Bean
    SkillUploadValidator skillUploadValidator() {
        return new SkillUploadValidator(IngestLimits.STANDARD);
    }
}

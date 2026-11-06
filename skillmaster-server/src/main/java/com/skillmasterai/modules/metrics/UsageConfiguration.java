package com.skillmasterai.modules.metrics;

import com.skillmasterai.modules.metrics.internal.PgUsage;
import com.skillmasterai.modules.metrics.internal.UsageRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wires M15 — usage metrics (ADR 0153). */
@Configuration(proxyBeanMethods = false)
public class UsageConfiguration {

    @Bean
    UsageRepository usageRepository(JdbcClient jdbc) {
        return new UsageRepository(jdbc);
    }

    @Bean
    PgUsage usage(UsageRepository repository) {
        // One instance serves both published edges. Two beans of this type would make every
        // injection point ambiguous, because PgUsage implements both interfaces.
        return new PgUsage(repository);
    }

    @Bean
    UsageRetentionJob usageRetentionJob(UsageRepository repository) {
        return new UsageRetentionJob(repository);
    }
}

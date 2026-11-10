package com.skillmasterai.modules.activity;

import com.skillmasterai.modules.activity.internal.ActivityRepository;
import com.skillmasterai.modules.activity.internal.PgActivity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Wires M17 — personal usage history (ADR 0179). */
@Configuration(proxyBeanMethods = false)
public class ActivityConfiguration {

    @Bean
    ActivityRepository activityRepository(JdbcClient jdbc, ObjectMapper json,
            ActivityPartitionJob partitions) {
        return new ActivityRepository(jdbc, json, partitions::ensure);
    }

    @Bean
    PgActivity activityRecorder(ActivityRepository repository) {
        return new PgActivity(repository);
    }

    @Bean
    ActivityPartitionJob activityPartitionJob(JdbcClient jdbc) {
        return new ActivityPartitionJob(jdbc);
    }

    @Bean
    ActivitySummaryJob activitySummaryJob(ActivityRepository repository,
            com.skillmasterai.modules.audit.AuditCounts auditCounts) {
        return new ActivitySummaryJob(repository, auditCounts);
    }

    @Bean
    ActivityArchiveJob activityArchiveJob(JdbcClient jdbc, javax.sql.DataSource dataSource,
            com.skillmasterai.modules.blob.BlobStore blobs, ObjectMapper json,
            ArchiveSettings settings) {
        return new ActivityArchiveJob(jdbc, dataSource, blobs, json, settings.directory());
    }
}

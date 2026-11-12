package com.skillmasterai.modules.feedback;

import com.skillmasterai.modules.activity.ArchiveSettings;
import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.feedback.internal.FeedbackArchiveJob;
import com.skillmasterai.modules.feedback.internal.FeedbackRepository;
import com.skillmasterai.modules.feedback.internal.PgFeedbackService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires M18 (v64). The staging directory is M17's {@link ArchiveSettings}: the same host-private
 * place the activity export uses, for the same reason — a failed upload leaves a file an operator
 * can retry.
 */
@Configuration(proxyBeanMethods = false)
class FeedbackConfiguration {

    @Bean
    FeedbackRepository feedbackRepository(JdbcClient jdbc, ObjectMapper json) {
        return new FeedbackRepository(jdbc, json);
    }

    @Bean
    FeedbackService feedbackService(FeedbackRepository repository, BlobStore blobs) {
        return new PgFeedbackService(repository, blobs);
    }

    @Bean
    FeedbackArchiveJob feedbackArchiveJob(FeedbackService feedback, BlobStore blobs,
            ObjectMapper json, ArchiveSettings settings) {
        return new FeedbackArchiveJob(feedback, blobs, json, settings.directory());
    }
}

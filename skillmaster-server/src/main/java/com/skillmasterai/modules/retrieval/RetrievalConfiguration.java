package com.skillmasterai.modules.retrieval;

import com.skillmasterai.modules.retrieval.internal.EmbeddingRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires M13.
 *
 * <p><strong>The {@link Embedder} is conditional, and everything else takes it through an
 * {@link ObjectProvider}.</strong> With the switch off there is no client at all — not a client
 * that refuses to call — so there is nothing to reach the provider with by mistake.
 *
 * <p>The text source is not declared here: M12 implements {@link EmbeddingTextSource}, because the
 * text is M12's to read. This class only knows the interface.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetrievalProperties.class)
public class RetrievalConfiguration {

    @Bean
    EmbeddingSettings embeddingSettings(RetrievalProperties properties) {
        return new EmbeddingSettings(properties.enabled(), properties.apiKey(), properties.model(),
                properties.dimensions(), properties.timeout(), properties.threshold(),
                properties.overFetch());
    }

    @Bean
    EmbeddingRepository embeddingRepository(JdbcClient jdbc) {
        return new EmbeddingRepository(jdbc);
    }

    /** Only when enabled: nothing is constructed for a deployment that switched this off. */
    @Bean
    Embedder embedder(EmbeddingSettings settings) {
        if (!settings.enabled()) {
            return null;
        }
        return new DashScopeEmbedder(settings.apiKey(), settings.model(), settings.dimensions(),
                settings.timeout());
    }

    @Bean
    RetrievalService retrievalService(EmbeddingRepository repository,
            ObjectProvider<Embedder> embedder, EmbeddingSettings settings) {
        return new RetrievalService(repository, embedder.getIfAvailable(), settings);
    }

    @Bean
    EmbeddingIndex embeddingIndex(EmbeddingRepository repository, EmbeddingTextSource source,
            ObjectProvider<Embedder> embedder) {
        return new EmbeddingIndex(repository, source, embedder.getIfAvailable());
    }
}

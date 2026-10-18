package com.skillmasterai.modules.version;

import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.internal.BlobGc;
import com.skillmasterai.modules.version.internal.VersionRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires M7 — versions and content (ADR 0053).
 *
 * <p>M7 depends on M6's {@link BlobStore} interface and not the other way round, which is what
 * keeps the two modules acyclic: references live in M7's {@code version_file}, bytes live in M6,
 * and reclaiming one because of the other has to be driven from the side that knows what is
 * referenced.
 *
 * <p>It also depends on M12's {@link SkillMetadataService} for the write-back of the projections,
 * and M12 depends on nothing of M7's — so the two stay acyclic. Metadata's wiring lives in
 * {@code SkillConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
public class VersionConfiguration {

    @Bean
    VersionRepository versionRepository(JdbcClient jdbc) {
        return new VersionRepository(jdbc);
    }

    @Bean
    BlobGc blobGc(JdbcClient jdbc, BlobStore blobs) {
        return new BlobGc(jdbc, blobs);
    }

    @Bean
    SkillVersionService skillVersionService(VersionRepository versions, BlobGc blobGc,
            SkillMetadataService metadata) {
        return new SkillVersionService(versions, blobGc, metadata);
    }
}

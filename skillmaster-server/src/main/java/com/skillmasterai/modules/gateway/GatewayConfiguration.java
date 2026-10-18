package com.skillmasterai.modules.gateway;

import com.skillmasterai.modules.blob.BlobStore;
import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.version.SkillVersionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires M11.
 *
 * <p>M11 owns no tables (§2.5): it answers with M7's versions and M6's bytes, and resolves its
 * namespace through M4. Every dependency points one way, so nothing here can form a cycle.
 *
 * <p>{@link GatewaySettings} arrives as a bean because a module's wiring may not read
 * configuration; {@code GatewayConfig} builds it.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayConfiguration {

    @Bean
    GatewayService gatewayService(NamespaceService namespaces, SkillVersionService versions,
            SkillMetadataService metadata, BlobStore blobs, GatewaySettings settings) {
        return new GatewayService(namespaces, versions, metadata, blobs, settings.publicBaseUrl(),
                settings.indexSchemaUrl());
    }
}

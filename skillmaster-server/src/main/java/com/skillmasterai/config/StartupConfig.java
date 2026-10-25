package com.skillmasterai.config;

import com.skillmasterai.modules.gateway.StartupBody;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds M11's one artefact: the startup journey, with this deployment's address in it.
 *
 * <p>This used to publish a gateway skill at startup and log its version. Both are gone (ADR 0089,
 * ADR 0093) — nothing is written to the database, and there is no index to advertise. What remains
 * is a document read off the classpath, assembled once so a request pays for nothing but a copy.
 *
 * <p>The failure mode moved with it. A missing source file still stops the process, for the reason
 * the bootstrap used to: a server that starts without its journey answers 404 to the one client
 * that has no credential and no other way to learn what to do.
 */
@Configuration(proxyBeanMethods = false)
class StartupConfig {

    @Bean
    StartupBody startupBody(SkillmasterProperties properties) {
        return new StartupBody(properties.publicBaseUrl());
    }
}

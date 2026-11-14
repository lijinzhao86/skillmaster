package com.skillmasterai.ops.config;

import com.skillmasterai.ops.imports.ContentSource;
import com.skillmasterai.ops.imports.ContentSourceRegistry;
import com.skillmasterai.ops.imports.GitHubContentSource;
import com.skillmasterai.ops.imports.GitHubContentReader;
import com.skillmasterai.ops.imports.LicenseGate;
import com.skillmasterai.ops.imports.SecurityRules;
import com.skillmasterai.ops.imports.SecurityScanner;
import com.skillmasterai.ops.imports.SyncSources;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.ObjectMapper;

/**
 * Wiring for the import. Startup-time validation is deliberate: a typo in the whitelist must stop
 * the process with the offending entry named, not produce a service that starts and imports
 * nothing (ADR 0155).
 */
@Configuration(proxyBeanMethods = false)
public class OpsConfig {

    @Bean
    SyncSources syncSources(OpsProperties properties, ResourceLoader resources,
            ContentSourceRegistry registry) {
        SyncSources sources =
                new SyncSources(new SyncSourcesLoader(resources).load("file:" + properties.sourcesFile()));
        // Startup-time, like the loader's own checks: a kind nobody implements is a typo, and a
        // service that starts and then imports nothing hides it until the first full run.
        for (com.skillmasterai.ops.imports.SyncEntry entry : sources.entries()) {
            if (registry.get(entry.kind()).isEmpty()) {
                throw new IllegalStateException("no content source for kind '" + entry.kind()
                        + "' in the sources file");
            }
        }
        return sources;
    }

    @Bean
    GitHubContentReader githubContentReader(OpsProperties properties, ObjectMapper json) {
        return new GitHubContentReader(json, properties.githubToken(),
                new GitHubContentReader.Limits(properties.maxFiles(), properties.maxFileBytes(),
                        properties.maxTotalBytes(), properties.maxRepoBytes(),
                        properties.maxUnpackedBytes()));
    }

    @Bean
    GitHubContentSource githubContentSource(GitHubContentReader reader, ObjectMapper json,
            OpsProperties properties) {
        return new GitHubContentSource(reader, json, properties.githubToken());
    }

    @Bean
    ContentSourceRegistry contentSourceRegistry(List<ContentSource> sources) {
        return new ContentSourceRegistry(sources);
    }

    @Bean
    SecurityRules securityRules() {
        try (InputStream rules = new ClassPathResource("sync/security-rules.yml").getInputStream()) {
            return SecurityRules.load(rules);
        } catch (IOException failure) {
            throw new IllegalStateException("sync/security-rules.yml could not be read", failure);
        }
    }

    @Bean
    SecurityScanner securityScanner(SecurityRules rules) {
        return new SecurityScanner(rules);
    }

    @Bean
    LicenseGate licenseGate(ObjectMapper json) {
        return new LicenseGate(json);
    }

    /** One import at a time; the lock is the real mutex, this just keeps the request thread free. */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService jobWorker() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ops-job");
            thread.setDaemon(true);
            return thread;
        });
    }
}

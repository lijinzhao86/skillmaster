package com.skillmasterai.config;

import com.skillmasterai.modules.namespace.NamespaceService;
import com.skillmasterai.modules.skill.GitHubContentReader;
import com.skillmasterai.modules.skill.SkillMetadataService;
import com.skillmasterai.modules.skill.SourceService;
import com.skillmasterai.modules.version.SkillVersionService;
import com.skillmasterai.usecase.DraftRecorder;
import com.skillmasterai.usecase.SubmitSkillUseCase;
import com.skillmasterai.usecase.sync.ContentSource;
import com.skillmasterai.usecase.sync.ContentSourceRegistry;
import com.skillmasterai.usecase.sync.GitHubContentSource;
import com.skillmasterai.usecase.sync.LicenseGate;
import com.skillmasterai.usecase.sync.SecurityRules;
import com.skillmasterai.usecase.sync.SecurityScanner;
import com.skillmasterai.usecase.sync.SyncDraftWriter;
import com.skillmasterai.usecase.sync.SyncEntry;
import com.skillmasterai.usecase.sync.SyncJob;
import com.skillmasterai.usecase.sync.SyncPipeline;
import com.skillmasterai.usecase.sync.SyncRunner;
import com.skillmasterai.usecase.sync.SyncSources;
import com.skillmasterai.usecase.sync.SyncTriggerGuard;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires v50's open-source sync (ADR 0158).
 *
 * <p><strong>Off unless the deployment says otherwise.</strong> {@code skillmaster.sync.enabled}
 * gates every bean here, so tests and local runs keep the behavior they had before v50: no sources
 * file read, no scheduled task, no trigger endpoint.
 *
 * <p>The validation in {@link #syncSources} is startup-time on purpose. A typo in the whitelist is a
 * configuration mistake, and a server that starts and then syncs nothing is worse than one that
 * refuses to start with the offending entry named.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "skillmaster.sync.enabled", havingValue = "true")
public class SyncConfig {

    @Bean
    SyncSourcesLoader syncSourcesLoader(ResourceLoader resources) {
        return new SyncSourcesLoader(resources);
    }

    @Bean
    GitHubContentReader githubContentReader(SkillmasterProperties properties,
            ObjectMapper json) {
        SkillmasterProperties.Sync sync = properties.sync();
        return new GitHubContentReader(json, sync.githubToken(),
                new GitHubContentReader.Limits(sync.maxFiles(), sync.maxFileBytes(),
                        sync.maxTotalBytes(), sync.maxRepoBytes(), sync.maxUnpackedBytes()));
    }

    @Bean
    GitHubContentSource githubContentSource(GitHubContentReader reader, ObjectMapper json) {
        return new GitHubContentSource(reader, json);
    }

    @Bean
    ContentSourceRegistry contentSourceRegistry(List<ContentSource> sources) {
        // Every adapter bean, by interface — a second source is then one bean, not an edit here.
        return new ContentSourceRegistry(sources);
    }

    @Bean
    SyncSources syncSources(SkillmasterProperties properties, SyncSourcesLoader loader,
            ContentSourceRegistry registry) {
        SkillmasterProperties.Sync sync = properties.sync();
        if (sync.githubToken() == null || sync.githubToken().isBlank()) {
            throw new IllegalStateException(
                    "skillmaster.sync.github-token is required when sync is enabled:"
                            + " unauthenticated GitHub allows 60 requests an hour");
        }
        if (sync.concurrency() < 1 || sync.concurrency() > 16) {
            throw new IllegalStateException(
                    "skillmaster.sync.concurrency must be between 1 and 16");
        }
        /* A typo in the expression would otherwise surface only when the scheduler parses it. */
        try {
            org.springframework.scheduling.support.CronExpression.parse(sync.cron());
        } catch (IllegalArgumentException invalidCron) {
            throw new IllegalStateException("skillmaster.sync.cron is not a valid six-field cron: "
                    + sync.cron(), invalidCron);
        }
        if (sync.maxRepoBytes() <= 0 || sync.maxFiles() <= 0 || sync.maxFileBytes() <= 0
                || sync.maxTotalBytes() <= 0 || sync.maxUnpackedBytes() <= 0) {
            throw new IllegalStateException(
                    "every skillmaster.sync.max-* value must be a positive number");
        }
        List<SyncEntry> entries = loader.load(sync.sourcesFile());
        if (entries.isEmpty()) {
            throw new IllegalStateException("the sync sources file lists no sources");
        }
        Set<String> kinds = registry.kinds();
        for (SyncEntry entry : entries) {
            if (!kinds.contains(entry.kind())) {
                throw new IllegalStateException("sync source kind '" + entry.kind()
                        + "' has no adapter; known kinds: " + kinds);
            }
            ContentSource source = registry.get(entry.kind()).orElseThrow();
            // Validates the locator and path with the adapter's own parser, at startup rather than
            // on the first run: a typo in the whitelist is a configuration mistake.
            source.binding(entry);
            entry.names().forEach(name -> source.binding(entry.child(name)));
            if (entry.mode() != SyncEntry.Mode.ONE && !source.supportsChildren()) {
                throw new IllegalStateException("sync source '" + entry.kind()
                        + "' does not support select; it has no children to list");
            }
        }
        return new SyncSources(entries);
    }

    @Bean
    LicenseGate licenseGate(ObjectMapper json) {
        return new LicenseGate(json);
    }

    @Bean
    SecurityScanner securityScanner() {
        try (InputStream rules = new ClassPathResource("sync/security-rules.yml")
                .getInputStream()) {
            return new SecurityScanner(SecurityRules.load(rules));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("sync/security-rules.yml is missing", failure);
        }
    }

    @Bean
    SyncDraftWriter syncDraftWriter(SubmitSkillUseCase submit, DraftRecorder drafts,
            SkillMetadataService metadata, SkillVersionService versions, SourceService sources) {
        return new SyncDraftWriter(submit, drafts, metadata, versions, sources);
    }

    @Bean
    SyncPipeline syncPipeline(SyncSources sources, ContentSourceRegistry registry,
            SourceService sourceFacts, SkillVersionService versions, NamespaceService namespaces,
            LicenseGate licenses, SecurityScanner scanner, SyncDraftWriter writer,
            SkillmasterProperties properties) {
        SkillmasterProperties.Sync sync = properties.sync();
        return new SyncPipeline(sources.entries(), registry, sourceFacts, versions, namespaces,
                licenses, scanner, writer, sync.concurrency(), sync.maxFiles(),
                sync.maxFileBytes(), sync.maxTotalBytes());
    }

    @Bean
    SyncRunner syncRunner(SyncPipeline pipeline) {
        return new SyncRunner(pipeline);
    }

    @Bean
    SyncJob syncJob(SyncRunner runner) {
        return new SyncJob(runner);
    }

    /**
     * The guard is always present when sync is enabled; a blank token means it is not configured,
     * and the controller answers 404. The token is never put into a SpEL condition: a value with a
     * quote would otherwise break context creation instead of being rejected as "not configured".
     */
    @Bean
    SyncTriggerGuard syncTriggerGuard(SkillmasterProperties properties) {
        return new SyncTriggerGuard(properties.sync().triggerToken());
    }
}
